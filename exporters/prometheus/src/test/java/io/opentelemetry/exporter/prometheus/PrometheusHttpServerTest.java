/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.exporter.prometheus;

import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableDoublePointData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableLongPointData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableMetricData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableSumData;
import io.opentelemetry.sdk.metrics.internal.export.MetricProducer;
import io.opentelemetry.sdk.resources.Resource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PrometheusHttpServerTest {
  private static final MetricProducer metricProducer = PrometheusHttpServerTest::generateTestData;

  static PrometheusHttpServer prometheusServer;
  static WebClient client;

  @BeforeAll
  static void setUp() {
    // Register the SDK metric producer with the prometheus reader.
    prometheusServer = PrometheusHttpServer.builder().setHost("localhost").setPort(0).build();
    prometheusServer.register(metricProducer);

    client = WebClient.of("http://localhost:" + prometheusServer.getAddress().getPort());
  }

  @AfterAll
  static void tearDown() {
    prometheusServer.shutdown();
  }

  @Test
  void invalidConfig() {
    assertThatThrownBy(() -> PrometheusHttpServer.builder().setPort(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("port must be positive");
    assertThatThrownBy(() -> PrometheusHttpServer.builder().setHost(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("host");
    assertThatThrownBy(() -> PrometheusHttpServer.builder().setHost(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("host must not be empty");
  }

  @ParameterizedTest
  @ValueSource(strings = {"/metrics", "/"})
  void fetchPrometheus(String endpoint) {
    AggregatedHttpResponse response = client.get(endpoint).aggregate().join();
    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
        .isEqualTo("text/plain; version=0.0.4; charset=utf-8");
    assertThat(response.contentUtf8())
        .isEqualTo(
            "# TYPE target info\n"
                + "# HELP target Target metadata\n"
                + "target_info{kr=\"vr\"} 1\n"
                + "# TYPE otel_scope_info info\n"
                + "# HELP otel_scope_info Scope metadata\n"
                + "otel_scope_info{otel_scope_name=\"grpc\",otel_scope_version=\"version\"} 1\n"
                + "# TYPE grpc_name_total counter\n"
                + "# HELP grpc_name_total long_description\n"
                + "grpc_name_total{otel_scope_name=\"grpc\",otel_scope_version=\"version\",kp=\"vp\"} 5.0 0\n"
                + "# TYPE otel_scope_info info\n"
                + "# HELP otel_scope_info Scope metadata\n"
                + "otel_scope_info{otel_scope_name=\"http\",otel_scope_version=\"version\"} 1\n"
                + "# TYPE http_name_total counter\n"
                + "# HELP http_name_total double_description\n"
                + "http_name_total{otel_scope_name=\"http\",otel_scope_version=\"version\",kp=\"vp\"} 3.5 0\n");
  }

  @ParameterizedTest
  @ValueSource(strings = {"/metrics", "/"})
  void fetchOpenMetrics(String endpoint) {
    AggregatedHttpResponse response =
        client
            .execute(
                RequestHeaders.of(
                    HttpMethod.GET,
                    endpoint,
                    HttpHeaderNames.ACCEPT,
                    "application/openmetrics-text"))
            .aggregate()
            .join();
    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
        .isEqualTo("application/openmetrics-text; version=1.0.0; charset=utf-8");
    assertThat(response.contentUtf8())
        .isEqualTo(
            "# TYPE target info\n"
                + "# HELP target Target metadata\n"
                + "target_info{kr=\"vr\"} 1\n"
                + "# TYPE otel_scope_info info\n"
                + "# HELP otel_scope_info Scope metadata\n"
                + "otel_scope_info{otel_scope_name=\"grpc\",otel_scope_version=\"version\"} 1\n"
                + "# TYPE grpc_name counter\n"
                + "# HELP grpc_name long_description\n"
                + "grpc_name_total{otel_scope_name=\"grpc\",otel_scope_version=\"version\",kp=\"vp\"} 5.0 0.000\n"
                + "# TYPE otel_scope_info info\n"
                + "# HELP otel_scope_info Scope metadata\n"
                + "otel_scope_info{otel_scope_name=\"http\",otel_scope_version=\"version\"} 1\n"
                + "# TYPE http_name counter\n"
                + "# HELP http_name double_description\n"
                + "http_name_total{otel_scope_name=\"http\",otel_scope_version=\"version\",kp=\"vp\"} 3.5 0.000\n"
                + "# EOF\n");
  }

  @Test
  void fetchFiltered() {
    AggregatedHttpResponse response =
        client.get("/?name[]=grpc_name_total&name[]=bears_total").aggregate().join();
    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
        .isEqualTo("text/plain; version=0.0.4; charset=utf-8");
    assertThat(response.contentUtf8())
        .isEqualTo(
            "# TYPE target info\n"
                + "# HELP target Target metadata\n"
                + "target_info{kr=\"vr\"} 1\n"
                + "# TYPE otel_scope_info info\n"
                + "# HELP otel_scope_info Scope metadata\n"
                + "otel_scope_info{otel_scope_name=\"grpc\",otel_scope_version=\"version\"} 1\n"
                + "# TYPE grpc_name_total counter\n"
                + "# HELP grpc_name_total long_description\n"
                + "grpc_name_total{otel_scope_name=\"grpc\",otel_scope_version=\"version\",kp=\"vp\"} 5.0 0\n");
  }

  @Test
  void fetchPrometheusCompressed() throws IOException {
    WebClient client =
        WebClient.builder("http://localhost:" + prometheusServer.getAddress().getPort())
            .addHeader(HttpHeaderNames.ACCEPT_ENCODING, "gzip")
            .build();
    AggregatedHttpResponse response = client.get("/").aggregate().join();
    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
        .isEqualTo("text/plain; version=0.0.4; charset=utf-8");
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("gzip");
    GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(response.content().array()));
    String content = new String(ByteStreams.toByteArray(gis), StandardCharsets.UTF_8);
    assertThat(content)
        .isEqualTo(
            "# TYPE target info\n"
                + "# HELP target Target metadata\n"
                + "target_info{kr=\"vr\"} 1\n"
                + "# TYPE otel_scope_info info\n"
                + "# HELP otel_scope_info Scope metadata\n"
                + "otel_scope_info{otel_scope_name=\"grpc\",otel_scope_version=\"version\"} 1\n"
                + "# TYPE grpc_name_total counter\n"
                + "# HELP grpc_name_total long_description\n"
                + "grpc_name_total{otel_scope_name=\"grpc\",otel_scope_version=\"version\",kp=\"vp\"} 5.0 0\n"
                + "# TYPE otel_scope_info info\n"
                + "# HELP otel_scope_info Scope metadata\n"
                + "otel_scope_info{otel_scope_name=\"http\",otel_scope_version=\"version\"} 1\n"
                + "# TYPE http_name_total counter\n"
                + "# HELP http_name_total double_description\n"
                + "http_name_total{otel_scope_name=\"http\",otel_scope_version=\"version\",kp=\"vp\"} 3.5 0\n");
  }

  @Test
  void fetchHead() {
    AggregatedHttpResponse response = client.head("/").aggregate().join();
    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
        .isEqualTo("text/plain; version=0.0.4; charset=utf-8");
    assertThat(response.content().isEmpty()).isTrue();
  }

  @Test
  void fetchHealth() {
    AggregatedHttpResponse response = client.get("/-/healthy").aggregate().join();

    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    assertThat(response.contentUtf8()).isEqualTo("Exporter is Healthy.");
  }

  @Test
  void stringRepresentation() {
    assertThat(prometheusServer.toString())
        .isEqualTo("PrometheusHttpServer{address=" + prometheusServer.getAddress() + "}");
  }

  private static ImmutableList<MetricData> generateTestData() {
    return ImmutableList.of(
        ImmutableMetricData.createLongSum(
            Resource.create(Attributes.of(stringKey("kr"), "vr")),
            InstrumentationScopeInfo.builder("grpc").setVersion("version").build(),
            "grpc.name",
            "long_description",
            "1",
            ImmutableSumData.create(
                /* isMonotonic= */ true,
                AggregationTemporality.CUMULATIVE,
                Collections.singletonList(
                    ImmutableLongPointData.create(
                        123, 456, Attributes.of(stringKey("kp"), "vp"), 5)))),
        ImmutableMetricData.createDoubleSum(
            Resource.create(Attributes.of(stringKey("kr"), "vr")),
            InstrumentationScopeInfo.builder("http").setVersion("version").build(),
            "http.name",
            "double_description",
            "1",
            ImmutableSumData.create(
                /* isMonotonic= */ true,
                AggregationTemporality.CUMULATIVE,
                Collections.singletonList(
                    ImmutableDoublePointData.create(
                        123, 456, Attributes.of(stringKey("kp"), "vp"), 3.5)))));
  }
}
