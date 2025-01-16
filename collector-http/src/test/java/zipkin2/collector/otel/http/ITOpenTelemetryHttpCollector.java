/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.collector.otel.http;

import com.google.protobuf.ByteString;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.TracesData;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.semconv.NetworkAttributes;
import io.opentelemetry.semconv.OtelAttributes;
import io.opentelemetry.semconv.ServiceAttributes;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import zipkin2.collector.CollectorSampler;
import zipkin2.collector.InMemoryCollectorMetrics;
import zipkin2.storage.InMemoryStorage;

import static io.opentelemetry.sdk.trace.samplers.Sampler.alwaysOn;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ITOpenTelemetryHttpCollector {
  InMemoryStorage store;

  InMemoryCollectorMetrics metrics;

  OpenTelemetryHttpCollector collector;

  int port = ZipkinTestUtil.getFreePort();

  SpanExporter spanExporter = OtlpHttpSpanExporter.builder()
      .setCompression("gzip")
      .setEndpoint("http://localhost:" + port + "/v1/traces")
      .build();

  SdkTracerProvider sdkTracerProvider = SdkTracerProvider.builder()
      .setSampler(alwaysOn())
      .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
      .addResource(Resource.create(
          Attributes.of(ServiceAttributes.SERVICE_NAME, "zipkin-collector-otel-http-test")))
      .build();

  OpenTelemetrySdk openTelemetrySdk = OpenTelemetrySdk.builder()
      .setTracerProvider(sdkTracerProvider)
      .build();

  Tracer tracer = openTelemetrySdk.getTracerProvider()
      .get("io.zipkin.contrib.otel:zipkin-collector-otel-http", "0.0.1");

  Server server;

  static final String OTEL_SDK_VERSION = "1.43.0";

  @BeforeEach
  public void setup() {
    store = InMemoryStorage.newBuilder().build();
    metrics = new InMemoryCollectorMetrics();

    collector = OpenTelemetryHttpCollector.newBuilder()
        .metrics(metrics)
        .sampler(CollectorSampler.ALWAYS_SAMPLE)
        .storage(store)
        .build()
        .start();
    ServerBuilder serverBuilder = Server.builder().http(port);
    collector.reconfigure(serverBuilder);
    metrics = metrics.forTransport("otel/http");
    server = serverBuilder.build();
    server.start().join();
  }

  @AfterEach
  void teardown() throws IOException {
    store.close();
    collector.close();
    server.stop().join();
  }

  @Test
  void testServerKind() throws Exception {
    List<String> traceIds = new ArrayList<>();
    List<String> spanIds = new ArrayList<>();
    final int size = 5;
    for (int i = 0; i < size; i++) {
      Span span = tracer
          .spanBuilder("get")
          .setSpanKind(SpanKind.SERVER)
          .setAttribute("string", "foo" + i)
          .setAttribute("int", 100)
          .setAttribute("double", 10.5)
          .setAttribute("boolean", true)
          .setAttribute(AttributeKey.stringArrayKey("array"), Arrays.asList("a", "b", "c"))
          .setAttribute(NetworkAttributes.NETWORK_LOCAL_ADDRESS, "127.0.0.1")
          .setAttribute(NetworkAttributes.NETWORK_LOCAL_PORT, 12345L)
          .startSpan();
      Thread.sleep(100); // do something
      span.end();
      spanIds.add(span.getSpanContext().getSpanId());
      traceIds.add(span.getSpanContext().getTraceId());
    }
    Awaitility.waitAtMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(store.acceptedSpanCount()).isEqualTo(size));
    List<List<zipkin2.Span>> received = store.getTraces(traceIds).execute();
    assertThat(received.size()).isEqualTo(size);
    for (int i = 0; i < size; i++) {
      assertThat(received.get(i)).hasSize(1);
      zipkin2.Span span = received.get(i).get(0);
      assertThat(span.id()).isEqualTo(spanIds.get(i));
      assertThat(span.traceId()).isEqualTo(traceIds.get(i));
      assertThat(span.parentId()).isNull();
      assertThat(span.name()).isEqualTo("get");
      assertThat(span.kind()).isEqualTo(zipkin2.Span.Kind.SERVER);
      assertThat(span.tags()).hasSize(12);
      assertThat(span.tags()).containsEntry("string", "foo" + i);
      assertThat(span.tags()).containsEntry("int", "100");
      assertThat(span.tags()).containsEntry("double", "10.5");
      assertThat(span.tags()).containsEntry("boolean", "true");
      assertThat(span.tags()).containsEntry("array", "a,b,c");
      assertThat(span.tags()).containsEntry(NetworkAttributes.NETWORK_LOCAL_ADDRESS.getKey(),
          "127.0.0.1");
      assertThat(span.tags()).containsEntry(NetworkAttributes.NETWORK_LOCAL_PORT.getKey(), "12345");
      assertThat(span.tags()).containsEntry(OtelAttributes.OTEL_SCOPE_NAME.getKey(),
          "io.zipkin.contrib.otel:zipkin-collector-otel-http");
      assertThat(span.tags()).containsEntry(OtelAttributes.OTEL_SCOPE_VERSION.getKey(), "0.0.1");
      // resource attributes
      assertThat(span.tags()).containsEntry("telemetry.sdk.language", "java");
      assertThat(span.tags()).containsEntry("telemetry.sdk.name", "opentelemetry");
      assertThat(span.tags()).containsEntry("telemetry.sdk.version", OTEL_SDK_VERSION);
      assertThat(span.duration()).isGreaterThan(100_000 /* 100ms */)
          .isLessThan(110_000 /* 110ms */);
      assertThat(span.localServiceName()).isEqualTo("zipkin-collector-otel-http-test");
      assertThat(span.localEndpoint().ipv4()).isEqualTo("127.0.0.1");
      assertThat(span.localEndpoint().port()).isEqualTo(12345);
      assertThat(span.remoteServiceName()).isNull();
      assertThat(span.remoteEndpoint()).isNull();
      assertThat(span.annotations()).isEmpty();
    }
    assertThat(metrics.spans()).isEqualTo(size);
    assertThat(metrics.spansDropped()).isZero();
    assertThat(metrics.messages()).isEqualTo(1);
    assertThat(metrics.messagesDropped()).isZero();
    // TODO calculate received bytes
  }

  @Test
  void testServerKindWithEvents() throws Exception {
    List<String> traceIds = new ArrayList<>();
    List<String> spanIds = new ArrayList<>();
    final int size = 5;
    Instant eventTime1 = Instant.now();
    Instant eventTime2 = eventTime1.plusMillis(10);
    Instant eventTime3 = eventTime1.plusMillis(100);
    for (int i = 0; i < size; i++) {
      Span span = tracer
          .spanBuilder("do-something")
          .setSpanKind(SpanKind.SERVER)
          .setAttribute(NetworkAttributes.NETWORK_LOCAL_ADDRESS, "127.0.0.1")
          .setAttribute(NetworkAttributes.NETWORK_LOCAL_PORT, 12345L)
          .startSpan();
      span.addEvent("event-1", Attributes.builder().put("foo", "bar").put("i", i).build(),
          eventTime1.plusMillis(size));
      span.addEvent("event-2", eventTime2.plusMillis(size));
      Thread.sleep(100); // do something
      span.addEvent("event-3", eventTime3.plusMillis(size));
      span.end();
      spanIds.add(span.getSpanContext().getSpanId());
      traceIds.add(span.getSpanContext().getTraceId());
    }
    Awaitility.waitAtMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(store.acceptedSpanCount()).isEqualTo(size));
    List<List<zipkin2.Span>> received = store.getTraces(traceIds).execute();
    assertThat(received.size()).isEqualTo(size);
    for (int i = 0; i < size; i++) {
      assertThat(received.get(i)).hasSize(1);
      zipkin2.Span span = received.get(i).get(0);
      assertThat(span.id()).isEqualTo(spanIds.get(i));
      assertThat(span.traceId()).isEqualTo(traceIds.get(i));
      assertThat(span.parentId()).isNull();
      assertThat(span.name()).isEqualTo("do-something");
      assertThat(span.kind()).isEqualTo(zipkin2.Span.Kind.SERVER);
      assertThat(span.tags()).hasSize(7);
      assertThat(span.tags()).containsEntry(NetworkAttributes.NETWORK_LOCAL_ADDRESS.getKey(),
          "127.0.0.1");
      assertThat(span.tags()).containsEntry(NetworkAttributes.NETWORK_LOCAL_PORT.getKey(), "12345");
      assertThat(span.tags()).containsEntry(OtelAttributes.OTEL_SCOPE_NAME.getKey(),
          "io.zipkin.contrib.otel:zipkin-collector-otel-http");
      assertThat(span.tags()).containsEntry(OtelAttributes.OTEL_SCOPE_VERSION.getKey(), "0.0.1");
      // resource attributes
      assertThat(span.tags()).containsEntry("telemetry.sdk.language", "java");
      assertThat(span.tags()).containsEntry("telemetry.sdk.name", "opentelemetry");
      assertThat(span.tags()).containsEntry("telemetry.sdk.version", OTEL_SDK_VERSION);
      assertThat(span.duration()).isGreaterThan(100_000 /* 100ms */)
          .isLessThan(110_000 /* 110ms */);
      assertThat(span.localServiceName()).isEqualTo("zipkin-collector-otel-http-test");
      assertThat(span.localEndpoint().ipv4()).isEqualTo("127.0.0.1");
      assertThat(span.localEndpoint().port()).isEqualTo(12345);
      assertThat(span.remoteServiceName()).isNull();
      assertThat(span.remoteEndpoint()).isNull();
      assertThat(span.annotations()).isNotNull();
      assertThat(span.annotations()).hasSize(3);
      // https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/sdk_exporters/zipkin.md#events
      assertThat(span.annotations().get(0).value()).isEqualTo(
          "\"event-1\":{\"foo\":\"bar\",\"i\":" + i + "}");
      assertThat(span.annotations().get(0).timestamp()).isEqualTo(
          toMillis(eventTime1.plusMillis(size)));
      assertThat(span.annotations().get(1).value()).isEqualTo("event-2");
      assertThat(span.annotations().get(1).timestamp()).isEqualTo(
          toMillis(eventTime2.plusMillis(size)));
      assertThat(span.annotations().get(2).value()).isEqualTo("event-3");
      assertThat(span.annotations().get(2).timestamp()).isEqualTo(
          toMillis(eventTime3.plusMillis(size)));
    }
    assertThat(metrics.spans()).isEqualTo(size);
    assertThat(metrics.spansDropped()).isZero();
    assertThat(metrics.messages()).isEqualTo(1);
    assertThat(metrics.messagesDropped()).isZero();
    // TODO calculate received bytes
  }

  @Test
  void testServerKindWithError() throws Exception {
    List<String> traceIds = new ArrayList<>();
    List<String> spanIds = new ArrayList<>();
    final int size = 5;
    for (int i = 0; i < size; i++) {
      Span span = tracer
          .spanBuilder("do-something")
          .setSpanKind(SpanKind.SERVER)
          .setAttribute(NetworkAttributes.NETWORK_LOCAL_ADDRESS, "127.0.0.1")
          .setAttribute(NetworkAttributes.NETWORK_LOCAL_PORT, 12345L)
          .startSpan();
      Thread.sleep(100); // do something
      span.setStatus(StatusCode.ERROR, "Exception!!");
      span.end();
      spanIds.add(span.getSpanContext().getSpanId());
      traceIds.add(span.getSpanContext().getTraceId());
    }
    Awaitility.waitAtMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(store.acceptedSpanCount()).isEqualTo(size));
    List<List<zipkin2.Span>> received = store.getTraces(traceIds).execute();
    assertThat(received.size()).isEqualTo(size);
    for (int i = 0; i < size; i++) {
      assertThat(received.get(i)).hasSize(1);
      zipkin2.Span span = received.get(i).get(0);
      assertThat(span.id()).isEqualTo(spanIds.get(i));
      assertThat(span.traceId()).isEqualTo(traceIds.get(i));
      assertThat(span.parentId()).isNull();
      assertThat(span.name()).isEqualTo("do-something");
      assertThat(span.kind()).isEqualTo(zipkin2.Span.Kind.SERVER);
      assertThat(span.tags()).hasSize(9);
      assertThat(span.tags()).containsEntry(NetworkAttributes.NETWORK_LOCAL_ADDRESS.getKey(),
          "127.0.0.1");
      assertThat(span.tags()).containsEntry(NetworkAttributes.NETWORK_LOCAL_PORT.getKey(), "12345");
      assertThat(span.tags()).containsEntry(OtelAttributes.OTEL_SCOPE_NAME.getKey(),
          "io.zipkin.contrib.otel:zipkin-collector-otel-http");
      assertThat(span.tags()).containsEntry(OtelAttributes.OTEL_SCOPE_VERSION.getKey(), "0.0.1");
      assertThat(span.tags()).containsEntry(SpanTranslator.ERROR_TAG, "Exception!!");
      assertThat(span.tags()).containsEntry(OtelAttributes.OTEL_STATUS_CODE.getKey(), "ERROR");
      // resource attributes
      assertThat(span.tags()).containsEntry("telemetry.sdk.language", "java");
      assertThat(span.tags()).containsEntry("telemetry.sdk.name", "opentelemetry");
      assertThat(span.tags()).containsEntry("telemetry.sdk.version", OTEL_SDK_VERSION);
      assertThat(span.duration()).isGreaterThan(100_000 /* 100ms */)
          .isLessThan(110_000 /* 110ms */);
      assertThat(span.localServiceName()).isEqualTo("zipkin-collector-otel-http-test");
      assertThat(span.localEndpoint().ipv4()).isEqualTo("127.0.0.1");
      assertThat(span.localEndpoint().port()).isEqualTo(12345);
      assertThat(span.remoteServiceName()).isNull();
      assertThat(span.remoteEndpoint()).isNull();
      assertThat(span.annotations()).isEmpty();
    }
    assertThat(metrics.spans()).isEqualTo(size);
    assertThat(metrics.spansDropped()).isZero();
    assertThat(metrics.messages()).isEqualTo(1);
    assertThat(metrics.messagesDropped()).isZero();
    // TODO calculate received bytes
  }

  @Test
  void testClientKind() throws Exception {
    List<String> traceIds = new ArrayList<>();
    List<String> spanIds = new ArrayList<>();
    final int size = 5;
    for (int i = 0; i < size; i++) {
      Span span = tracer
          .spanBuilder("send")
          .setSpanKind(SpanKind.CLIENT)
          .setAttribute("string", "foo" + i)
          .setAttribute("int", 100)
          .setAttribute("double", 10.5)
          .setAttribute("boolean", true)
          .setAttribute(AttributeKey.stringArrayKey("array"), Arrays.asList("a", "b", "c"))
          .setAttribute(NetworkAttributes.NETWORK_LOCAL_ADDRESS, "127.0.0.1")
          .setAttribute(NetworkAttributes.NETWORK_LOCAL_PORT, 12345L)
          .setAttribute(SemanticConventionsAttributes.PEER_SERVICE, "demo")
          .setAttribute(NetworkAttributes.NETWORK_PEER_ADDRESS, "1.2.3.4")
          .setAttribute(NetworkAttributes.NETWORK_PEER_PORT, 8080L)
          .startSpan();
      Thread.sleep(100); // do something
      span.end();
      spanIds.add(span.getSpanContext().getSpanId());
      traceIds.add(span.getSpanContext().getTraceId());
    }
    Awaitility.waitAtMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(store.acceptedSpanCount()).isEqualTo(size));
    List<List<zipkin2.Span>> received = store.getTraces(traceIds).execute();
    assertThat(received.size()).isEqualTo(size);
    for (int i = 0; i < size; i++) {
      assertThat(received.get(i)).hasSize(1);
      zipkin2.Span span = received.get(i).get(0);
      assertThat(span.id()).isEqualTo(spanIds.get(i));
      assertThat(span.traceId()).isEqualTo(traceIds.get(i));
      assertThat(span.parentId()).isNull();
      assertThat(span.name()).isEqualTo("send");
      assertThat(span.kind()).isEqualTo(zipkin2.Span.Kind.CLIENT);
      assertThat(span.tags()).hasSize(15);
      assertThat(span.tags()).containsEntry("string", "foo" + i);
      assertThat(span.tags()).containsEntry("int", "100");
      assertThat(span.tags()).containsEntry("double", "10.5");
      assertThat(span.tags()).containsEntry("boolean", "true");
      assertThat(span.tags()).containsEntry("array", "a,b,c");
      assertThat(span.tags()).containsEntry(NetworkAttributes.NETWORK_LOCAL_ADDRESS.getKey(),
          "127.0.0.1");
      assertThat(span.tags()).containsEntry(NetworkAttributes.NETWORK_LOCAL_PORT.getKey(), "12345");
      assertThat(span.tags()).containsEntry(SemanticConventionsAttributes.PEER_SERVICE, "demo");
      assertThat(span.tags()).containsEntry(NetworkAttributes.NETWORK_PEER_ADDRESS.getKey(),
          "1.2.3.4");
      assertThat(span.tags()).containsEntry(NetworkAttributes.NETWORK_PEER_PORT.getKey(), "8080");
      assertThat(span.tags()).containsEntry(OtelAttributes.OTEL_SCOPE_NAME.getKey(),
          "io.zipkin.contrib.otel:zipkin-collector-otel-http");
      assertThat(span.tags()).containsEntry(OtelAttributes.OTEL_SCOPE_VERSION.getKey(), "0.0.1");
      // resource attributes
      assertThat(span.tags()).containsEntry("telemetry.sdk.language", "java");
      assertThat(span.tags()).containsEntry("telemetry.sdk.name", "opentelemetry");
      assertThat(span.tags()).containsEntry("telemetry.sdk.version", OTEL_SDK_VERSION);
      assertThat(span.duration()).isGreaterThan(100_000 /* 100ms */)
          .isLessThan(110_000 /* 110ms */);
      assertThat(span.localServiceName()).isEqualTo("zipkin-collector-otel-http-test");
      assertThat(span.localEndpoint().ipv4()).isEqualTo("127.0.0.1");
      assertThat(span.localEndpoint().port()).isEqualTo(12345);
      assertThat(span.remoteServiceName()).isEqualTo("demo");
      assertThat(span.remoteEndpoint().ipv4()).isEqualTo("1.2.3.4");
      assertThat(span.remoteEndpoint().port()).isEqualTo(8080);
      assertThat(span.annotations()).isEmpty();
    }
    assertThat(metrics.spans()).isEqualTo(size);
    assertThat(metrics.spansDropped()).isZero();
    assertThat(metrics.messages()).isEqualTo(1);
    assertThat(metrics.messagesDropped()).isZero();
    // TODO calculate received bytes
  }

  @Test
  void minimalSpan() throws Exception {
    TracesData tracesData = TracesData.newBuilder()
        .addResourceSpans(ResourceSpans.newBuilder()
            .addScopeSpans(ScopeSpans.newBuilder()
                .addSpans(io.opentelemetry.proto.trace.v1.Span.newBuilder()
                    .setSpanId(ByteString.fromHex("0000000000000001"))
                    .setTraceId(ByteString.fromHex("00000000000000000000000000000001")))))
        .build();

    URL url = URI.create("http://localhost:" + port + "/v1/traces").toURL();
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setRequestMethod("POST");
    connection.setDoOutput(true);
    connection.setRequestProperty("Content-Type", "application/x-protobuf");
    try (OutputStream os = connection.getOutputStream()) {
      os.write(tracesData.toByteArray());
      os.flush();
    }
    connection.disconnect();
    int responseCode = connection.getResponseCode();
    assertThat(responseCode).isEqualTo(HttpURLConnection.HTTP_ACCEPTED);
    Awaitility.waitAtMost(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(store.acceptedSpanCount()).isEqualTo(1));
    assertThat(metrics.spans()).isEqualTo(1);
    assertThat(metrics.spansDropped()).isZero();
    assertThat(metrics.messages()).isEqualTo(1);
    assertThat(metrics.messagesDropped()).isZero();
    assertThat(metrics.bytes()).isEqualTo(tracesData.getSerializedSize());
  }

  @Test
  void invalidSpanId() throws Exception {
    TracesData tracesData = TracesData.newBuilder()
        .addResourceSpans(ResourceSpans.newBuilder()
            .addScopeSpans(ScopeSpans.newBuilder()
                .addSpans(io.opentelemetry.proto.trace.v1.Span.newBuilder()
                    .setSpanId(ByteString.fromHex("0000000000000000"))
                    .setTraceId(ByteString.fromHex("00000000000000000000000000000001")))))
        .build();

    URL url = URI.create("http://localhost:" + port + "/v1/traces").toURL();
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setRequestMethod("POST");
    connection.setDoOutput(true);
    connection.setRequestProperty("Content-Type", "application/x-protobuf");
    try (OutputStream os = connection.getOutputStream()) {
      os.write(tracesData.toByteArray());
      os.flush();
    }
    connection.disconnect();
    int responseCode = connection.getResponseCode();
    assertThat(responseCode).isEqualTo(HttpURLConnection.HTTP_INTERNAL_ERROR);
    Awaitility.waitAtMost(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(store.acceptedSpanCount()).isEqualTo(0));
    assertThat(metrics.spans()).isZero();
    assertThat(metrics.spansDropped()).isEqualTo(1);
    assertThat(metrics.messages()).isEqualTo(1);
    assertThat(metrics.messagesDropped()).isZero();
    assertThat(metrics.bytes()).isEqualTo(tracesData.getSerializedSize());
  }

  @Test
  void invalidTraceId() throws Exception {
    TracesData tracesData = TracesData.newBuilder()
        .addResourceSpans(ResourceSpans.newBuilder()
            .addScopeSpans(ScopeSpans.newBuilder()
                .addSpans(io.opentelemetry.proto.trace.v1.Span.newBuilder()
                    .setSpanId(ByteString.fromHex("0000000000000001"))
                    .setTraceId(ByteString.fromHex("00000000000000000000000000000000")))))
        .build();

    URL url = URI.create("http://localhost:" + port + "/v1/traces").toURL();
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setRequestMethod("POST");
    connection.setDoOutput(true);
    connection.setRequestProperty("Content-Type", "application/x-protobuf");
    try (OutputStream os = connection.getOutputStream()) {
      os.write(tracesData.toByteArray());
      os.flush();
    }
    connection.disconnect();
    int responseCode = connection.getResponseCode();
    assertThat(responseCode).isEqualTo(HttpURLConnection.HTTP_INTERNAL_ERROR);
    Awaitility.waitAtMost(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(store.acceptedSpanCount()).isEqualTo(0));
    assertThat(metrics.spans()).isZero();
    assertThat(metrics.spansDropped()).isEqualTo(1);
    assertThat(metrics.messages()).isEqualTo(1);
    assertThat(metrics.messagesDropped()).isZero();
    assertThat(metrics.bytes()).isEqualTo(tracesData.getSerializedSize());
  }

  @Test
  void emptyRequest() throws Exception {
    URL url = URI.create("http://localhost:" + port + "/v1/traces").toURL();
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setRequestMethod("POST");
    connection.setDoOutput(true);
    connection.setRequestProperty("Content-Type", "application/x-protobuf");
    try (OutputStream os = connection.getOutputStream()) {
      os.write(new byte[0]); // empty
      os.flush();
    }
    connection.disconnect();
    int responseCode = connection.getResponseCode();
    assertThat(responseCode).isEqualTo(HttpURLConnection.HTTP_ACCEPTED);
    Awaitility.waitAtMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(store.acceptedSpanCount()).isEqualTo(0));
    assertThat(metrics.spans()).isZero();
    assertThat(metrics.spansDropped()).isZero();
    assertThat(metrics.messages()).isZero();
    assertThat(metrics.messagesDropped()).isZero();
    assertThat(metrics.bytes()).isZero();
  }

  @Test
  void brokenRequest() throws Exception {
    URL url = URI.create("http://localhost:" + port + "/v1/traces").toURL();
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setRequestMethod("POST");
    connection.setDoOutput(true);
    connection.setRequestProperty("Content-Type", "application/x-protobuf");
    try (OutputStream os = connection.getOutputStream()) {
      os.write(0x00);
      os.flush();
    }
    connection.disconnect();
    int responseCode = connection.getResponseCode();
    assertThat(responseCode).isEqualTo(HttpURLConnection.HTTP_INTERNAL_ERROR);
    Awaitility.waitAtMost(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(store.acceptedSpanCount()).isEqualTo(0));
    assertThat(metrics.spans()).isZero();
    assertThat(metrics.spansDropped()).isZero();
    assertThat(metrics.messages()).isZero();
    assertThat(metrics.messagesDropped()).isEqualTo(1);
    assertThat(metrics.bytes()).isEqualTo(1);
  }

  static long toMillis(Instant instant) {
    long time = TimeUnit.SECONDS.toNanos(instant.getEpochSecond());
    time += instant.getNano();
    return SpanTranslator.nanoToMills(time);
  }
}
