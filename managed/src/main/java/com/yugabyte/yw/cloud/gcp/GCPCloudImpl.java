// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.cloud.gcp;

import static play.mvc.Http.Status.BAD_REQUEST;
import static play.mvc.Http.Status.INTERNAL_SERVER_ERROR;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.compute.model.Backend;
import com.google.api.services.compute.model.BackendService;
import com.google.api.services.compute.model.ConnectionDraining;
import com.google.api.services.compute.model.ForwardingRule;
import com.google.api.services.compute.model.HealthCheck;
import com.google.api.services.compute.model.InstanceGroup;
import com.google.api.services.compute.model.InstanceReference;
import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Inject;
import com.yugabyte.yw.cloud.CloudAPI;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.common.config.RuntimeConfGetter;
import com.yugabyte.yw.models.AvailabilityZone;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.Region;
import com.yugabyte.yw.models.helpers.CloudInfoInterface;
import com.yugabyte.yw.models.helpers.NodeID;
import com.yugabyte.yw.models.helpers.provider.GCPCloudInfo;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections4.SetUtils;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class GCPCloudImpl implements CloudAPI {
  public static final String PROJECT_ID_PROPERTY = "gce_project";
  public static final String CUSTOM_GCE_NETWORK_PROPERTY = "CUSTOM_GCE_NETWORK";
  public static final String GCE_PROJECT_PROPERTY = "GCE_PROJECT";
  public static final String GOOGLE_APPLICATION_CREDENTIALS_PROPERTY =
      "GOOGLE_APPLICATION_CREDENTIALS";

  @Inject private RuntimeConfGetter runtimeConfGetter;

  /**
   * Find the instance types offered in availabilityZones.
   *
   * @param provider the cloud provider bean for the AWS provider.
   * @param azByRegionMap user selected availabilityZones by their parent region.
   * @param instanceTypesFilter list of instanceTypes for which we want to list the offerings.
   * @return a map. Key of this map is instance type like "c5.xlarge" and value is all the
   *     availabilityZones for which the instance type is being offered.
   */
  @Override
  public Map<String, Set<String>> offeredZonesByInstanceType(
      Provider provider, Map<Region, Set<String>> azByRegionMap, Set<String> instanceTypesFilter) {
    // TODO make a call to the cloud provider to populate.
    // Make the instances available in all availabilityZones.
    Set<String> azs =
        azByRegionMap.values().stream().flatMap(s -> s.stream()).collect(Collectors.toSet());
    return instanceTypesFilter.stream().collect(Collectors.toMap(Function.identity(), i -> azs));
  }

  // Basic validation to make sure that the credentials work with GCP.
  @Override
  public boolean isValidCreds(Provider provider, String region) {
    GCPProjectApiClient apiClient = new GCPProjectApiClient(runtimeConfGetter, provider);
    GCPCloudInfo gcpCloudInfo = CloudInfoInterface.get(provider);
    boolean useHostCredentials =
        Optional.ofNullable(gcpCloudInfo.getUseHostCredentials()).orElse(false);
    if (useHostCredentials) {
      log.error("using host credentials for provisioning the provider, skipping validation");
      // TODO validate for service account.
      return true;
    }
    try {
      apiClient.checkInstanceFetching();
    } catch (GeneralSecurityException | IOException e) {
      log.error("Error in validating GCP credentials", e);
      return false;
    }
    return true;
  }

  @Override
  public boolean isValidCredsKms(ObjectNode config, UUID customerUUID) {
    return true;
  }

  // Given a mapping from AvailablityZone to a set of NodeIDs, get a mapping from zoneName to a set
  // of InstnceReferences for those nodeIDs
  @VisibleForTesting
  protected Map<String, List<InstanceReference>> getAzToInstanceReferenceMap(
      GCPProjectApiClient apiClient, Map<AvailabilityZone, Set<NodeID>> azToNodeIDs) {
    Map<String, List<InstanceReference>> nodesMap = new HashMap<>();
    if (azToNodeIDs.isEmpty()) {
      return nodesMap;
    }
    for (Map.Entry<AvailabilityZone, Set<NodeID>> azToNodeID : azToNodeIDs.entrySet()) {
      String zone = azToNodeID.getKey().getName();
      List<String> nodeNames =
          azToNodeID.getValue().stream()
              .map(nodeId -> nodeId.getName())
              .collect(Collectors.toList());
      List<InstanceReference> instances = apiClient.getInstancesInZoneByNames(zone, nodeNames);
      if (instances.size() != azToNodeID.getValue().size()) {
        throw new PlatformServiceException(
            INTERNAL_SERVER_ERROR, "Cannot find all instances in zone " + zone);
      }
      if (!instances.isEmpty()) {
        nodesMap.put(zone, instances);
      }
    }
    log.info("Sucessfully mapped all nodes with availablity zones");
    return nodesMap;
  }

  /**
   * Create new instnce groups based on the zone to instnce reference map
   *
   * @param apiClient GCP API client
   * @param nodeAzMap zone to instnce reference map
   * @return List of the newly created Backend objects
   */
  @VisibleForTesting
  protected List<Backend> createNewBackends(
      GCPProjectApiClient apiClient, Map<String, List<InstanceReference>> nodeAzMap) {
    List<Backend> backends = new ArrayList<>();
    if (nodeAzMap == null) {
      return backends;
    }
    for (Map.Entry<String, List<InstanceReference>> mapEntry : nodeAzMap.entrySet()) {
      Backend backend = new Backend();
      String zone = mapEntry.getKey();
      List<InstanceReference> instances = mapEntry.getValue();
      if (instances != null && !CollectionUtils.isEmpty(instances)) {
        try {
          String instanceGroupUrl = apiClient.createNewInstanceGroupInZone(zone);
          String instanceGroupName = CloudAPI.getResourceNameFromResourceUrl(instanceGroupUrl);
          apiClient.addInstancesToInstaceGroup(zone, instanceGroupName, instances);
          backend.setGroup(instanceGroupUrl);
          backends.add(backend);
        } catch (IOException e) {
          log.error(e.getMessage());
          throw new PlatformServiceException(
              INTERNAL_SERVER_ERROR, "Failed to create new instance group in zone " + zone);
        }
      }
    }
    return backends;
  }

  /**
   * Update existing backends (Instance Groups) to ensure that each backend contains the given
   * instances only It adds instances that are not already present in the backend, and removes
   * instances that are no longer required
   *
   * @param apiClient GCP API client
   * @param zonesToBackend Mapping between the zones and backend that is a part of that zone
   * @param zonesToNodes Mapping btween zones and the references for instances that should be a part
   *     of the backend belonging to that zone
   */
  @VisibleForTesting
  protected void updateInstancesInInstanceGroup(
      GCPProjectApiClient apiClient,
      Map<String, Backend> zonesToBackend,
      Map<String, List<InstanceReference>> zonesToNodes) {
    for (Map.Entry<String, List<InstanceReference>> zoneToNodes : zonesToNodes.entrySet()) {
      String zone = zoneToNodes.getKey();
      Backend backend = zonesToBackend.get(zone);
      Set<InstanceReference> newInstances = new HashSet(zoneToNodes.getValue());
      String instanceGroupUrl = backend.getGroup();
      String instanceGroupName = CloudAPI.getResourceNameFromResourceUrl(instanceGroupUrl);
      InstanceGroup instanceGroup = apiClient.getInstanceGroup(zone, instanceGroupName);
      log.info("Sucessfully fetched instance group " + instanceGroupName);
      try {
        Set<InstanceReference> existingInstances =
            new HashSet(apiClient.getInstancesForInstanceGroup(zone, instanceGroupName));
        List<InstanceReference> instancesToAdd =
            new ArrayList(SetUtils.difference(newInstances, existingInstances));
        apiClient.addInstancesToInstaceGroup(zone, instanceGroupName, instancesToAdd);
        List<InstanceReference> instancesToRemove =
            new ArrayList(SetUtils.difference(existingInstances, newInstances));
        apiClient.removeInstancesFromInstaceGroup(zone, instanceGroupName, instancesToRemove);
      } catch (IOException e) {
        log.error(e.getMessage());
        throw new PlatformServiceException(
            INTERNAL_SERVER_ERROR, "Unable to update instance groups in zone " + zone);
      }
    }
  }

  // Helper function to get map a given list of backends to the zones in which they belong
  private Map<String, Backend> mapBackendsToZones(List<Backend> backends) {
    Map<String, Backend> backendToZoneMap = new HashMap<>();
    for (Backend backend : backends) {
      String instanceGroupUrl = backend.getGroup();
      String[] urlComponents = instanceGroupUrl.split("/", 0);
      String zone = urlComponents[urlComponents.length - 3];
      backendToZoneMap.put(zone, backend);
    }
    return backendToZoneMap;
  }

  /**
   * Check and perform modifications on an existing list of bakcends to ensure that the nodes that
   * should be a part of the various AZs
   *
   * @param apiClient GCP API client
   * @param nodeAzMap Mapping from avilablity zone to list of references of instnces that should be
   *     a part of the backend in that AZ
   * @param backends List of existing backends
   * @return List of final backends after the modifications
   */
  @VisibleForTesting
  protected List<Backend> ensureBackends(
      GCPProjectApiClient apiClient,
      Map<String, List<InstanceReference>> nodeAzMap,
      List<Backend> backends) {
    if (backends == null) {
      backends = new ArrayList<Backend>();
    }
    Map<String, Backend> backendToZoneMap = mapBackendsToZones(backends);
    Set<String> zonesToAdd = SetUtils.difference(nodeAzMap.keySet(), backendToZoneMap.keySet());
    Set<String> zonesToRemove = SetUtils.difference(backendToZoneMap.keySet(), nodeAzMap.keySet());
    Set<String> zonesToUpdate =
        SetUtils.intersection(nodeAzMap.keySet(), backendToZoneMap.keySet());
    Map<String, List<InstanceReference>> nodesInNewZones =
        nodeAzMap.entrySet().stream()
            .filter(mapEntry -> zonesToAdd.contains(mapEntry.getKey()))
            .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
    Map<String, List<InstanceReference>> nodesInUpdateZones =
        nodeAzMap.entrySet().stream()
            .filter(mapEntry -> zonesToUpdate.contains(mapEntry.getKey()))
            .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
    Map<String, Backend> backendsInZonesToRemove =
        backendToZoneMap.entrySet().stream()
            .filter(mapEntry -> zonesToRemove.contains(mapEntry.getKey()))
            .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
    try {
      apiClient.deleteBackends(backendsInZonesToRemove);
    } catch (Exception e) {
      log.warn("Failed to remove extra backends: " + backendsInZonesToRemove);
      // In this case instead of throwind a Platform service exception, we simply continue to do the
      // rest of the operations.
      // This is because failure to delete extra backends is not a very critical error. Since we are
      // updating the forwarding rules with the updated list of backends without these backends, so
      // no traffic would be forwarded to the incorrect backends.
      // Client can manually delete these extra and non-required backends as and when required
    }
    backends =
        new ArrayList(
            backendToZoneMap.entrySet().stream()
                .filter(mapEntry -> !zonesToRemove.contains(mapEntry.getKey()))
                .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()))
                .values());
    backends.addAll(createNewBackends(apiClient, nodesInNewZones));
    updateInstancesInInstanceGroup(apiClient, backendToZoneMap, nodesInUpdateZones);
    return backends;
  }

  /**
   * Check if the list of health checks need to be updated or not based on the list of protocols and
   * ports For now, only TCP healthchecks are supported
   *
   * @param apiClient GCP API client
   * @param region Region where the health checks exists
   * @param protocol Protocol for the health checks
   * @param port Port on which the health check is supposed to be present
   * @param healthCheckUrls Existing health checks already present in the backend service
   * @return Updated list of health checks that should be associated with the backend service
   */
  @VisibleForTesting
  protected List<String> ensureHealthChecks(
      GCPProjectApiClient apiClient,
      String region,
      String protocol,
      Integer port,
      List<String> healthCheckUrls) {
    List<HealthCheck> healthChecks = new ArrayList();
    // This list will always either be empty or a singleton list as GCP doesn't allow multiple
    // health checks
    // However, we are not making that assumption here, to support future changes in the GCP API
    for (String healthCheckUrl : healthCheckUrls) {
      String healthCheckName = CloudAPI.getResourceNameFromResourceUrl(healthCheckUrl);
      healthChecks.add(apiClient.getRegionalHelathCheckByName(region, healthCheckName));
    }
    List<String> newHealthCheckUrls = new ArrayList();
    Set<Integer> portsWithHealthCheck =
        new HashSet(
            healthChecks.stream()
                .map(hc -> hc.getTcpHealthCheck().getPort())
                .collect(Collectors.toList()));
    if (!portsWithHealthCheck.contains(port)) {
      log.debug("Creating new health checks on port " + port);
      try {
        newHealthCheckUrls.add(apiClient.createNewHealthCheckForPort(region, protocol, port));
        return newHealthCheckUrls;
      } catch (IOException e) {
        log.error(e.getMessage());
        throw new PlatformServiceException(
            INTERNAL_SERVER_ERROR, "Failed to create new health check on port " + port);
      }
    }
    return healthCheckUrls;
  }

  /**
   * Check if the list of forwarding rules cover all protocls and ports required
   *
   * @param protocol Protocol for the forwarding rule. Only TCP supported for now
   * @param portsToCheck List of ports that should be forwarded by the forwarding service
   * @param forwardingRules List of existing forwarding rules
   */
  @VisibleForTesting
  protected void ensureForwardingRules(
      String protocol, List<Integer> portsToCheck, List<ForwardingRule> forwardingRules) {
    for (ForwardingRule forwardingRule : forwardingRules) {
      if (forwardingRule.getAllPorts() != null && forwardingRule.getAllPorts() == true) {
        return;
        // We found a forwarding rule that forwards all ports. So no need to check
        // if protocol specific ports are forwarded or not
      }
      if (forwardingRule.getIPProtocol().equals(protocol)) {
        portsToCheck.removeAll(
            forwardingRule.getPorts().stream()
                .map(port -> Integer.parseInt(port))
                .collect(Collectors.toList()));
      }
    }
    if (!portsToCheck.isEmpty()) {
      throw new PlatformServiceException(
          BAD_REQUEST, "Forwarding rule missing for some ports: " + portsToCheck.toString());
    }
  }

  // Wrapper function to keep the exposed API consistent, as well as allow for unit testing
  @Override
  public void manageNodeGroup(
      Provider provider,
      String regionCode,
      String lbName,
      Map<AvailabilityZone, Set<NodeID>> azToNodeIDs,
      String protocol,
      List<Integer> ports) {
    GCPProjectApiClient apiClient = new GCPProjectApiClient(runtimeConfGetter, provider);
    manageNodeGroup(provider, regionCode, lbName, azToNodeIDs, protocol, ports, apiClient);
  }

  /**
   * Update the existing load balancer with the given nodes and parameters. Google Cloud API has no
   * seperate data structure for a load balancer. Thus the load balancer name provided by the user
   * is actually the name of the backendService
   *
   * @param provider the cloud provider bean for the AWS provider.
   * @param regionCode the region code.
   * @param lbName the load balancer name.
   * @param nodeIDs the DB node IDs (name, uuid).
   * @param protocol the listening protocol. (Only TCP supported for now)
   * @param ports the listening ports enabled (YSQL, YCQL, YEDIS).
   * @param apiClient client object to make requests to the Google compute platform
   */
  @VisibleForTesting
  protected void manageNodeGroup(
      Provider provider,
      String regionCode,
      String lbName,
      Map<AvailabilityZone, Set<NodeID>> azToNodeIDs,
      String protocol,
      List<Integer> ports,
      GCPProjectApiClient apiClient) {
    String backendServiceName = lbName;
    if (!protocol.equals("TCP")) {
      throw new PlatformServiceException(
          BAD_REQUEST, "Currently only TCP load balancers are supported.");
    }
    if (ports.isEmpty()) {
      throw new PlatformServiceException(
          BAD_REQUEST, "Load balancer must be configured for atleast one port");
    }
    try {
      // Group NodeIDs based on availablity zones, as backends (InstanceGroups) are per-zone
      Map<String, List<InstanceReference>> nodeAzMap =
          getAzToInstanceReferenceMap(apiClient, azToNodeIDs);
      BackendService backendService = apiClient.getBackendService(regionCode, backendServiceName);
      List<Backend> backends = backendService.getBackends();
      log.debug("Checking backends....");
      backends = ensureBackends(apiClient, nodeAzMap, backends);
      backendService.setConnectionDraining((new ConnectionDraining()).setDrainingTimeoutSec(3600));
      backendService.setBackends(backends);
      if (!backendService.getProtocol().equals(protocol)) {
        backendService.setProtocol(protocol);
      }
      log.debug("Checking health checks....");
      List<String> healthChecks = backendService.getHealthChecks();
      // It is the responsiblity of the caller to add the ysql port first in the list
      Integer healthCheckPort = ports.get(0);
      healthChecks =
          ensureHealthChecks(apiClient, regionCode, protocol, healthCheckPort, healthChecks);
      backendService.setHealthChecks(healthChecks);
      apiClient.updateBackendService(regionCode, backendService);

      // Get forwarding rules for backend service
      log.debug("Checking forwarding rules....");
      List<ForwardingRule> forwardingRules =
          apiClient.getRegionalForwardingRulesForBackend(regionCode, backendService.getSelfLink());
      ensureForwardingRules(protocol, ports, forwardingRules);
    } catch (Exception e) {
      String message = "Error executing task {manageNodeGroup()} " + e.toString();
      throw new PlatformServiceException(INTERNAL_SERVER_ERROR, message);
    }
  }

  @Override
  public void validateInstanceTemplate(Provider provider, String instanceTemplate) {
    GCPProjectApiClient apiClient = new GCPProjectApiClient(runtimeConfGetter, provider);
    GCPCloudInfo gcpCloudInfo = CloudInfoInterface.get(provider);
    String projectId = gcpCloudInfo.getGceProject();

    if (StringUtils.isBlank(projectId)) {
      String errorMessage = "Project ID must be set for instance template validation";
      log.error(errorMessage);
      throw new PlatformServiceException(BAD_REQUEST, errorMessage);
    }
    String errorMessage = "Unable to validate GCP instance template: " + instanceTemplate;
    try {
      apiClient.checkInstanceTempelate(instanceTemplate);
    } catch (GeneralSecurityException e) {
      log.error("GeneralSecurityException validating instance template", e);
      throw new PlatformServiceException(INTERNAL_SERVER_ERROR, errorMessage);
    } catch (GoogleJsonResponseException e) {
      log.error("GoogleJsonResponseException validating instance template", e);
      throw new PlatformServiceException(e.getStatusCode(), e.getMessage());
    } catch (IOException e) {
      log.error("IOException validating instance template", e);
      throw new PlatformServiceException(INTERNAL_SERVER_ERROR, errorMessage);
    }
  }
}
