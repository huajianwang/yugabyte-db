// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner.tasks;

import static com.yugabyte.yw.common.AssertHelper.assertJsonEqual;
import static com.yugabyte.yw.common.ModelFactory.createUniverse;
import static com.yugabyte.yw.models.TaskInfo.State.Failure;
import static com.yugabyte.yw.models.TaskInfo.State.Success;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.yugabyte.yw.commissioner.tasks.params.NodeTaskParams;
import com.yugabyte.yw.common.ApiUtils;
import com.yugabyte.yw.common.ModelFactory;
import com.yugabyte.yw.common.ShellResponse;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.models.AvailabilityZone;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.Region;
import com.yugabyte.yw.models.TaskInfo;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.TaskType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;
import org.yb.client.YBClient;
import play.libs.Json;

@RunWith(MockitoJUnitRunner.class)
public class StopNodeInUniverseTest extends CommissionerBaseTest {

  private Universe defaultUniverse;

  @Override
  @Before
  public void setUp() {
    super.setUp();

    Region region = Region.create(defaultProvider, "region-1", "Region 1", "yb-image-1");
    AvailabilityZone.createOrThrow(region, "az-1", "AZ 1", "subnet-1");
    // create default universe
    UniverseDefinitionTaskParams.UserIntent userIntent =
        new UniverseDefinitionTaskParams.UserIntent();
    userIntent.numNodes = 3;
    userIntent.ybSoftwareVersion = "yb-version";
    userIntent.accessKeyCode = "demo-access";
    userIntent.regionList = ImmutableList.of(region.uuid);
    defaultUniverse = createUniverse(defaultCustomer.getCustomerId());
    Universe.saveDetails(
        defaultUniverse.universeUUID,
        ApiUtils.mockUniverseUpdater(userIntent, true /* setMasters */));

    ShellResponse dummyShellResponse = new ShellResponse();
    dummyShellResponse.message = "true";
    when(mockNodeManager.nodeCommand(any(), any())).thenReturn(dummyShellResponse);

    YBClient mockClient = mock(YBClient.class);
    try {
      doNothing().when(mockClient).waitForMasterLeader(anyLong());
    } catch (Exception e) {
    }

    when(mockYBClient.getClient(any(), any())).thenReturn(mockClient);
  }

  private TaskInfo submitTask(NodeTaskParams taskParams, String nodeName) {
    taskParams.expectedUniverseVersion = 2;
    taskParams.nodeName = nodeName;
    try {
      UUID taskUUID = commissioner.submit(TaskType.StopNodeInUniverse, taskParams);
      return waitForTask(taskUUID);
    } catch (InterruptedException e) {
      assertNull(e.getMessage());
    }
    return null;
  }

  private static final List<TaskType> STOP_NODE_TASK_SEQUENCE =
      ImmutableList.of(
          TaskType.SetNodeState,
          TaskType.AnsibleClusterServerCtl,
          TaskType.UpdateNodeProcess,
          TaskType.SetNodeState,
          TaskType.UniverseUpdateSucceeded);

  private static final List<JsonNode> STOP_NODE_TASK_EXPECTED_RESULTS =
      ImmutableList.of(
          Json.toJson(ImmutableMap.of("state", "Stopping")),
          Json.toJson(ImmutableMap.of("process", "tserver", "command", "stop")),
          Json.toJson(ImmutableMap.of("processType", "TSERVER", "isAdd", false)),
          Json.toJson(ImmutableMap.of("state", "Stopped")),
          Json.toJson(ImmutableMap.of()));

  private static final List<TaskType> STOP_NODE_TASK_SEQUENCE_MASTER =
      ImmutableList.of(
          TaskType.SetNodeState,
          TaskType.AnsibleClusterServerCtl,
          TaskType.AnsibleClusterServerCtl,
          TaskType.WaitForMasterLeader,
          TaskType.UpdateNodeProcess,
          TaskType.ChangeMasterConfig,
          TaskType.UpdateNodeProcess,
          TaskType.SetNodeState,
          TaskType.UniverseUpdateSucceeded);

  private static final List<JsonNode> STOP_NODE_TASK_SEQUENCE_MASTER_RESULTS =
      ImmutableList.of(
          Json.toJson(ImmutableMap.of("state", "Stopping")),
          Json.toJson(ImmutableMap.of("process", "tserver", "command", "stop")),
          Json.toJson(ImmutableMap.of("process", "master", "command", "stop")),
          Json.toJson(ImmutableMap.of()),
          Json.toJson(ImmutableMap.of("processType", "TSERVER", "isAdd", false)),
          Json.toJson(ImmutableMap.of()),
          Json.toJson(ImmutableMap.of("processType", "MASTER", "isAdd", false)),
          Json.toJson(ImmutableMap.of("state", "Stopped")),
          Json.toJson(ImmutableMap.of()));

  private void assertStopNodeSequence(
      Map<Integer, List<TaskInfo>> subTasksByPosition, boolean isMaster) {
    int position = 0;
    if (isMaster) {
      for (TaskType taskType : STOP_NODE_TASK_SEQUENCE_MASTER) {
        List<TaskInfo> tasks = subTasksByPosition.get(position);
        assertEquals(1, tasks.size());
        assertEquals(taskType, tasks.get(0).getTaskType());
        JsonNode expectedResults = STOP_NODE_TASK_SEQUENCE_MASTER_RESULTS.get(position);
        List<JsonNode> taskDetails =
            tasks.stream().map(TaskInfo::getTaskDetails).collect(Collectors.toList());
        assertJsonEqual(expectedResults, taskDetails.get(0));
        position++;
      }
    } else {
      for (TaskType taskType : STOP_NODE_TASK_SEQUENCE) {
        List<TaskInfo> tasks = subTasksByPosition.get(position);
        assertEquals(1, tasks.size());
        assertEquals(taskType, tasks.get(0).getTaskType());
        JsonNode expectedResults = STOP_NODE_TASK_EXPECTED_RESULTS.get(position);
        List<JsonNode> taskDetails =
            tasks.stream().map(TaskInfo::getTaskDetails).collect(Collectors.toList());
        assertJsonEqual(expectedResults, taskDetails.get(0));
        position++;
      }
    }
  }

  @Test
  public void testStopMasterNode() {
    NodeTaskParams taskParams = new NodeTaskParams();
    taskParams.universeUUID = defaultUniverse.universeUUID;

    TaskInfo taskInfo = submitTask(taskParams, "host-n1");
    assertEquals(Success, taskInfo.getTaskState());

    verify(mockNodeManager, times(3)).nodeCommand(any(), any());
    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTasks.stream().collect(Collectors.groupingBy(TaskInfo::getPosition));
    assertEquals(subTasksByPosition.size(), STOP_NODE_TASK_SEQUENCE_MASTER.size());
    assertStopNodeSequence(subTasksByPosition, true);
  }

  @Test
  public void testStopNonMasterNode() {
    NodeTaskParams taskParams = new NodeTaskParams();
    Customer customer = ModelFactory.testCustomer("tc2", "Test Customer 2");
    Universe universe = createUniverse(customer.getCustomerId());
    UniverseDefinitionTaskParams.UserIntent userIntent =
        new UniverseDefinitionTaskParams.UserIntent();
    userIntent.numNodes = 5;
    userIntent.replicationFactor = 3;
    universe =
        Universe.saveDetails(
            universe.universeUUID, ApiUtils.mockUniverseUpdater(userIntent, true /* setMasters */));
    taskParams.universeUUID = universe.universeUUID;

    TaskInfo taskInfo = submitTask(taskParams, "host-n4");
    assertEquals(Success, taskInfo.getTaskState());

    verify(mockNodeManager, times(2)).nodeCommand(any(), any());
    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTasks.stream().collect(Collectors.groupingBy(TaskInfo::getPosition));
    assertEquals(subTasksByPosition.size(), STOP_NODE_TASK_SEQUENCE.size());
    assertStopNodeSequence(subTasksByPosition, false);
  }

  @Test
  public void testStopUnknownNode() {
    NodeTaskParams taskParams = new NodeTaskParams();
    taskParams.universeUUID = defaultUniverse.universeUUID;
    TaskInfo taskInfo = submitTask(taskParams, "host-n9");
    verify(mockNodeManager, times(0)).nodeCommand(any(), any());
    assertEquals(Failure, taskInfo.getTaskState());
  }
}
