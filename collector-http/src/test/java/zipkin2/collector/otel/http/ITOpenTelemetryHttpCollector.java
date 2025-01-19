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
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.TracesData;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
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
import java.util.stream.Stream;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.PullPolicy;
import org.testcontainers.utility.DockerImageName;
import zipkin2.Annotation;
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

  static int port = ZipkinTestUtil.getFreePort();

  static Tracer tracer = getTracer(port);

  static Tracer tracerForOtelCollector;

  static Logger logger = getLogger(port);

  static Logger loggerForOtelCollector;

  Server server;

  private static final String COLLECTOR_IMAGE =
      "ghcr.io/open-telemetry/opentelemetry-collector-releases/opentelemetry-collector-contrib:0.116.1";

  private static final Integer COLLECTOR_OTLP_HTTP_PORT = 4318;

  private static final Integer COLLECTOR_HEALTH_CHECK_PORT = 13133;

  private static GenericContainer<?> otelCollector;

  static final String OTEL_SDK_VERSION = "1.43.0";

  @BeforeAll
  static void beforeAll() {
    Testcontainers.exposeHostPorts(port);
    otelCollector = new GenericContainer<>(DockerImageName.parse(COLLECTOR_IMAGE))
        .withImagePullPolicy(PullPolicy.alwaysPull())
        .withEnv("OTLP_EXPORTER_ENDPOINT", "http://host.testcontainers.internal:" + port)
        .withClasspathResourceMapping("otel-config.yaml", "/otel-config.yaml", BindMode.READ_ONLY)
        .withCommand("--config", "/otel-config.yaml")
        .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("otel-collector")))
        .withExposedPorts(
            COLLECTOR_OTLP_HTTP_PORT,
            COLLECTOR_HEALTH_CHECK_PORT)
        .waitingFor(Wait.forHttp("/").forPort(COLLECTOR_HEALTH_CHECK_PORT));
    otelCollector.start();
    // Send JSON requests to the Zipkin OTLP collector via the OTel collector
    int otelCollectorPort = otelCollector.getMappedPort(COLLECTOR_OTLP_HTTP_PORT);
    tracerForOtelCollector = getTracer(otelCollectorPort);
    loggerForOtelCollector = getLogger(otelCollectorPort);
  }

  static Tracer getTracer(int port) {
    return SdkTracerProvider.builder()
        .setSampler(alwaysOn())
        .addSpanProcessor(BatchSpanProcessor.builder(OtlpHttpSpanExporter.builder()
                .setCompression("gzip")
                .setEndpoint("http://localhost:" + port + "/v1/traces")
                .build())
            .build())
        .addResource(Resource.create(
            Attributes.of(ServiceAttributes.SERVICE_NAME, "zipkin-collector-otel-http-test")))
        .build()
        .get("io.zipkin.contrib.otel:zipkin-collector-otel-http", "0.0.1");
  }

  static Logger getLogger(int port) {
    return SdkLoggerProvider.builder()
        .addLogRecordProcessor(BatchLogRecordProcessor.builder(OtlpHttpLogRecordExporter.builder()
                .setCompression("gzip")
                .setEndpoint("http://localhost:" + port + "/v1/logs")
                .build())
            .build())
        .addResource(Resource.create(
            Attributes.of(ServiceAttributes.SERVICE_NAME, "zipkin-collector-otel-http-test")))
        .build()
        .get("io.zipkin.contrib.otel:zipkin-collector-otel-http");
  }

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

  static Stream<Tracer> tracers() {
    return Stream.of(tracer, tracerForOtelCollector);
  }

  static Stream<Logger> loggers() {
    return Stream.of(logger, loggerForOtelCollector);
  }

  @ParameterizedTest
  @MethodSource("tracers")
  void testServerKind(Tracer tracer) throws Exception {
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
    Awaitility.waitAtMost(Duration.ofSeconds(10))
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
    assertThat(metrics.messages()).isBetween(1, 2); // Spans can be split into two messages
    assertThat(metrics.messagesDropped()).isZero();
    // TODO calculate received bytes
  }

  @ParameterizedTest
  @MethodSource("tracers")
  void testServerKindWithEvents(Tracer tracer) throws Exception {
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
    Awaitility.waitAtMost(Duration.ofSeconds(10))
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
    assertThat(metrics.messages()).isBetween(1, 2); // Spans can be split into two messages
    assertThat(metrics.messagesDropped()).isZero();
    // TODO calculate received bytes
  }

  @ParameterizedTest
  @MethodSource("tracers")
  void testServerKindWithError(Tracer tracer) throws Exception {
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
    Awaitility.waitAtMost(Duration.ofSeconds(10))
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
    assertThat(metrics.messages()).isBetween(1, 2); // Spans can be split into two messages
    assertThat(metrics.messagesDropped()).isZero();
    // TODO calculate received bytes
  }

  @ParameterizedTest
  @MethodSource("tracers")
  void testClientKind(Tracer tracer) throws Exception {
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
    Awaitility.waitAtMost(Duration.ofSeconds(10))
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
    assertThat(metrics.messages()).isBetween(1, 2); // Spans can be split into two messages
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

  @ParameterizedTest
  @MethodSource("loggers")
  void testEventLog(Logger logger) throws Exception {
    List<String> traceIds = new ArrayList<>();
    List<String> spanIds = new ArrayList<>();
    final int size = 5;
    Instant now = Instant.now();
    for (int i = 0; i < size; i++) {
      Context context = Context.current();
      try (Scope ignored = context.makeCurrent()) {
        Span span = tracer.spanBuilder("dummy").startSpan();
        logger
            .logRecordBuilder()
            .setContext(span.storeInContext(context))
            .setAttribute(AttributeKey.stringKey("event.name"), "test-event")
            .setBody("Hello " + i)
            .setSeverity(Severity.INFO)
            .setTimestamp(now.plusSeconds(i))
            .emit();
        spanIds.add(span.getSpanContext().getSpanId());
        traceIds.add(span.getSpanContext().getTraceId());
      }
    }
    Awaitility.waitAtMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(store.acceptedSpanCount()).isEqualTo(size));
    List<List<zipkin2.Span>> received = store.getTraces(traceIds).execute();
    assertThat(received.size()).isEqualTo(size);
    for (int i = 0; i < size; i++) {
      assertThat(received.get(i)).hasSize(1);
      zipkin2.Span span = received.get(i).get(0);
      assertThat(span.id()).isEqualTo(spanIds.get(i));
      assertThat(span.traceId()).isEqualTo(traceIds.get(i));
      assertThat(span.parentId()).isNull();
      assertThat(span.annotations()).hasSize(1);
      Annotation annotation = span.annotations().get(0);
      assertThat(annotation.timestamp()).isEqualTo(toMillis(now.plusSeconds(i)));
      assertThat(annotation.value()).isEqualTo(
          String.format("\"test-event\":{\"severity_number\":9,\"body\":\"Hello %d\"}", i));
    }
  }

  static long toMillis(Instant instant) {
    long time = TimeUnit.SECONDS.toNanos(instant.getEpochSecond());
    time += instant.getNano();
    return SpanTranslator.nanoToMills(time);
  }
}
