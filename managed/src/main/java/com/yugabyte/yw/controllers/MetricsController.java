// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.controllers;

import com.typesafe.config.Config;
import com.yugabyte.yw.common.ApiHelper;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.common.metrics.MetricService;
import com.yugabyte.yw.models.Metric;
import com.yugabyte.yw.models.MetricLabel;
import com.yugabyte.yw.models.filters.MetricFilter;
import com.yugabyte.yw.models.helpers.CommonUtils;
import com.yugabyte.yw.models.helpers.KnownAlertLabels;
import com.yugabyte.yw.models.helpers.PlatformMetrics;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.common.TextFormat;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.Results;

@Api(value = "Metrics", authorizations = @Authorization(AbstractPlatformController.API_KEY_AUTH))
@Slf4j
public class MetricsController extends Controller {

  private static final String KAMON_EMBEDDED_SERVER_HOST =
      "kamon.prometheus.embedded-server.hostname";
  private static final String KAMON_EMBEDDED_SERVER_PORT = "kamon.prometheus.embedded-server.port";

  @Inject private MetricService metricService;

  @Inject ApiHelper apiHelper;

  @Inject Config config;

  private Date lastKamonErrorPrinted = null;

  @ApiOperation(
      value = "Get Prometheus metrics",
      response = String.class,
      nickname = "MetricsDetail")
  public Result index() {
    final ByteArrayOutputStream response = new ByteArrayOutputStream(1 << 20);
    try {
      final OutputStreamWriter osw = new OutputStreamWriter(response);
      // Write runtime metrics
      TextFormat.write004(osw, CollectorRegistry.defaultRegistry.metricFamilySamples());
      // Write persisted metrics
      TextFormat.write004(osw, Collections.enumeration(getPersistedMetrics()));
      // Write Kamon metrics
      osw.write(getKamonMetrics());

      osw.flush();
      osw.close();
      response.flush();
      response.close();
    } catch (Exception e) {
      throw new PlatformServiceException(INTERNAL_SERVER_ERROR, e.getMessage());
    }

    return Results.status(OK, response.toString());
  }

  private String getKamonMetrics() {
    try {
      String host = config.getString(KAMON_EMBEDDED_SERVER_HOST);
      int port = config.getInt(KAMON_EMBEDDED_SERVER_PORT);
      String url = "http://" + host + ":" + port + "/metrics";
      return apiHelper.getBody(url);
    } catch (Exception e) {
      if (lastKamonErrorPrinted == null
          || lastKamonErrorPrinted.before(CommonUtils.nowMinus(1, ChronoUnit.HOURS))) {
        log.error("Failed to retrieve Kamon metrics", e);
        lastKamonErrorPrinted = new Date();
      }
    }
    return StringUtils.EMPTY;
  }

  private List<Collector.MetricFamilySamples> getPersistedMetrics() {
    List<MetricFamilySamples> result = new ArrayList<>();
    List<Metric> allMetrics = metricService.list(MetricFilter.builder().expired(false).build());

    Map<String, List<Metric>> metricsByName =
        allMetrics
            .stream()
            .collect(Collectors.groupingBy(Metric::getName, TreeMap::new, Collectors.toList()));

    Map<String, PlatformMetrics> platformMetricsMap =
        Arrays.stream(PlatformMetrics.values())
            .collect(Collectors.toMap(PlatformMetrics::name, Function.identity()));
    for (Map.Entry<String, List<Metric>> metric : metricsByName.entrySet()) {
      String metricName = metric.getKey();
      List<Metric> metrics = metric.getValue();
      PlatformMetrics knownMetric = platformMetricsMap.get(metricName);
      String help = knownMetric != null ? knownMetric.getHelp() : StringUtils.EMPTY;
      String unit = knownMetric != null ? knownMetric.getUnitName() : StringUtils.EMPTY;
      Collector.Type type = metrics.get(0).getType().getPrometheusType();

      List<Collector.MetricFamilySamples.Sample> samples =
          metrics.stream().map(this::convert).collect(Collectors.toList());
      result.add(new Collector.MetricFamilySamples(metricName, unit, type, help, samples));
    }
    return result;
  }

  private Collector.MetricFamilySamples.Sample convert(Metric metric) {
    List<MetricLabel> metricLabels =
        metric
            .getLabels()
            .stream()
            .sorted(Comparator.comparing(MetricLabel::getName))
            .collect(Collectors.toList());
    List<String> labelNames =
        metricLabels.stream().map(MetricLabel::getName).collect(Collectors.toList());
    List<String> labelValues =
        metricLabels.stream().map(MetricLabel::getValue).collect(Collectors.toList());
    if (metric.getCustomerUUID() != null) {
      labelNames.add(KnownAlertLabels.CUSTOMER_UUID.labelName());
      labelValues.add(metric.getCustomerUUID().toString());
    }
    return new Collector.MetricFamilySamples.Sample(
        metric.getName(), labelNames, labelValues, metric.getValue());
  }
}
