/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.reporter.otel.brave;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import brave.Span.Kind;
import brave.Tags;
import brave.handler.MutableSpan;
import brave.propagation.B3SingleFormat;
import brave.propagation.TraceContext;
import com.google.protobuf.ByteString;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.PullPolicy;
import org.testcontainers.utility.DockerImageName;
import zipkin2.reporter.BytesEncoder;
import zipkin2.reporter.BytesMessageSender;
import zipkin2.reporter.Encoding;
import zipkin2.reporter.brave.AsyncZipkinSpanHandler;
import zipkin2.reporter.brave.MutableSpanBytesEncoder;
import zipkin2.reporter.okhttp3.OkHttpSender;

import static org.assertj.core.api.Assertions.assertThat;
import static zipkin2.reporter.otel.brave.SpanTranslator.intAttribute;
import static zipkin2.reporter.otel.brave.SpanTranslator.stringAttribute;

public class ITOtlpProtoV1EncoderTest {
  private static GenericContainer<?> collector;

  private static OtlpHttpServer otlpHttpServer;

  private static final String COLLECTOR_IMAGE =
      "ghcr.io/open-telemetry/opentelemetry-collector-releases/opentelemetry-collector-contrib:0.116.1";

  private static final Integer COLLECTOR_OTLP_HTTP_PORT = 4318;

  private static final Integer COLLECTOR_ZIPKIN_PORT = 9411;

  private static final Integer COLLECTOR_HEALTH_CHECK_PORT = 13133;

  @BeforeAll
  static void beforeAll() {
    otlpHttpServer = new OtlpHttpServer();
    otlpHttpServer.start();
    Testcontainers.exposeHostPorts(otlpHttpServer.httpPort());
    collector = new GenericContainer<>(DockerImageName.parse(COLLECTOR_IMAGE))
        .withImagePullPolicy(PullPolicy.alwaysPull())
        .withEnv("OTLP_EXPORTER_ENDPOINT", "http://host.testcontainers.internal:" + otlpHttpServer.httpPort())
        .withClasspathResourceMapping("otel-config.yaml", "/otel-config.yaml", BindMode.READ_ONLY)
        .withCommand("--config", "/otel-config.yaml")
        .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("otel-collector")))
        .withExposedPorts(
            COLLECTOR_OTLP_HTTP_PORT,
            COLLECTOR_ZIPKIN_PORT,
            COLLECTOR_HEALTH_CHECK_PORT)
        .waitingFor(Wait.forHttp("/").forPort(COLLECTOR_HEALTH_CHECK_PORT));
    collector.start();
  }

  @AfterAll
  static void afterAll() {
    otlpHttpServer.stop().join();
    collector.stop();
    collector.close();
  }

  @BeforeEach
  void beforeEach() {
    otlpHttpServer.reset();
  }

  static Stream<Arguments> encoderAndEndpoint() {
    return Stream.of(
        /* existing sender + new encoder -> otlp with otel collector */
        Arguments.of(Encoding.PROTO3, OtlpProtoV1Encoder.create(), String.format("http://localhost:%d/v1/traces", collector.getMappedPort(COLLECTOR_OTLP_HTTP_PORT))),
        /* existing sender + new encoder -> otlp without otel collector */
        Arguments.of(Encoding.PROTO3, OtlpProtoV1Encoder.create(), String.format("http://localhost:%d/v1/traces", otlpHttpServer.httpPort())),
        /* existing sender + zipkin encoder -> zipkin endpoint on collector */
        Arguments.of(Encoding.JSON, MutableSpanBytesEncoder.create(Encoding.JSON, Tags.ERROR), String.format("http://localhost:%d/api/v2/spans", collector.getMappedPort(COLLECTOR_ZIPKIN_PORT))));
  }

  @ParameterizedTest
  @MethodSource("encoderAndEndpoint")
  void testEncoder(Encoding encoding, BytesEncoder<MutableSpan> encoder, String endpoint) throws Exception {
    try (BytesMessageSender okHttpSender = OkHttpSender.newBuilder()
        .encoding(encoding)
        .endpoint(endpoint)
        .build();
       AsyncZipkinSpanHandler spanHandler = AsyncZipkinSpanHandler.newBuilder(okHttpSender).build(encoder);
    ) {
      TraceContext context = B3SingleFormat.parseB3SingleFormat("0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-1").context();
      MutableSpan span = new MutableSpan(context, null);
      span.name("get");
      span.startTimestamp(1510256710021866L);
      span.finishTimestamp(1510256710021866L + 1117L);
      span.kind(Kind.SERVER);
      span.localServiceName("isao01");
      span.localIp("10.23.14.72");
      span.localPort(12345);
      span.tag("http.host", "zipkin.example.com");
      span.tag("http.method", "GET");
      span.tag("http.url", "https://zipkin.example.com/rs/A");
      span.tag("http.path", "/rs/A");
      span.tag("http.status_code", "200");
      span.tag("location", "T67792");
      span.tag("other", "A");
      span.annotate(1510256710021866L + 1000L, "Foo");
      spanHandler.end(context, span, null);
      spanHandler.flush();
    }
    if (otlpHttpServer.waitUntilTraceRequestsAreSent(Duration.ofSeconds(3))) {
      Span.Builder spanBuilder = Span.newBuilder()
          .setName("get")
          .setStartTimeUnixNano(milliToNanos(1510256710021866L))
          .setEndTimeUnixNano(milliToNanos(1510256710021866L + 1117L))
          .setTraceId(ByteString.fromHex("0af7651916cd43dd8448eb211c80319c"))
          .setSpanId(ByteString.fromHex("b7ad6b7169203331"))
          .setKind(Span.SpanKind.SPAN_KIND_SERVER)
          .addEvents(Span.Event.newBuilder().setName("Foo").setTimeUnixNano(milliToNanos(1510256710021866L + 1000L)).build());
      ScopeSpans.Builder scopeSpanBuilder = ScopeSpans.newBuilder();
      Resource.Builder resourceBuilder = Resource.newBuilder().addAttributes(stringAttribute("service.name", "isao01"));
      if (encoder instanceof OtlpProtoV1Encoder) {
        scopeSpanBuilder.setScope(InstrumentationScope.newBuilder().setName(BraveScope.NAME).setVersion(BraveScope.VERSION));
        spanBuilder.addAttributes(stringAttribute("network.local.address", "10.23.14.72"))
            .addAttributes(intAttribute("network.local.port", 12345))
            .addAttributes(stringAttribute("server.address", "zipkin.example.com"))
            .addAttributes(stringAttribute("http.request.method", "GET"))
            .addAttributes(stringAttribute("url.full", "https://zipkin.example.com/rs/A"))
            .addAttributes(stringAttribute("url.path", "/rs/A"))
            .addAttributes(stringAttribute("http.response.status_code", "200"))
            .addAttributes(stringAttribute("location", "T67792"))
            .addAttributes(stringAttribute("other", "A"))
            .setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build());
        resourceBuilder.addAttributes(stringAttribute("telemetry.sdk.language", "java"))
            .addAttributes(stringAttribute("telemetry.sdk.name", OtlpProtoV1Encoder.class.getName()))
            .addAttributes(stringAttribute("telemetry.sdk.version", BraveScope.VERSION));
      } else {
        scopeSpanBuilder.setScope(InstrumentationScope.newBuilder().build() /* empty */);
        spanBuilder.addAttributes(stringAttribute("http.method", "GET"))
            .addAttributes(stringAttribute("http.url", "https://zipkin.example.com/rs/A"))
            .addAttributes(stringAttribute("http.path", "/rs/A"))
            .addAttributes(stringAttribute("http.status_code", "200"))
            .addAttributes(stringAttribute("location", "T67792"))
            .addAttributes(stringAttribute("other", "A"))
            .addAttributes(stringAttribute("http.host", "zipkin.example.com"))
            .addAttributes(stringAttribute("net.host.ip", "10.23.14.72"))
            .addAttributes(intAttribute("net.host.port", 12345))
            .setStatus(Status.newBuilder().build() /* empty */);
      }
      ResourceSpans resourceSpans = ResourceSpans.newBuilder()
          .setResource(resourceBuilder)
          .addScopeSpans(scopeSpanBuilder.addSpans(spanBuilder))
          .build();
      List<ResourceSpans> receivedSpans = otlpHttpServer.receivedSpans();
      assertThat(receivedSpans.size()).isEqualTo(1);
      compareResourceSpans(receivedSpans.get(0), resourceSpans);
    } else {
      Assertions.fail("Traces not sent");
    }
  }

  @ParameterizedTest
  @MethodSource("encoderAndEndpoint")
  void testEncoderWithException(Encoding encoding, BytesEncoder<MutableSpan> encoder, String endpoint) throws Exception {
    try (BytesMessageSender okHttpSender = OkHttpSender.newBuilder()
        .encoding(encoding)
        .endpoint(endpoint)
        .build();
       AsyncZipkinSpanHandler spanHandler = AsyncZipkinSpanHandler.newBuilder(okHttpSender).build(encoder);
    ) {
      TraceContext context = B3SingleFormat.parseB3SingleFormat("123caa480c3fa187dd37f5d5c991f2c7-5d64683224ba9b17-1").context();
      MutableSpan span = new MutableSpan(context, null);
      span.name("post");
      span.startTimestamp(1510256710021866L);
      span.finishTimestamp(1510256710021866L + 1117L);
      span.kind(Kind.CLIENT);
      span.localServiceName("test-api");
      span.localIp("10.99.99.99");
      span.localPort(43210);
      span.remoteIp("1.2.3.4");
      span.remotePort(9999);
      span.remoteServiceName("demo");
      span.tag("http.host", "zipkin.example.com");
      span.tag("http.method", "POST");
      span.tag("http.url", "https://zipkin.example.com/order");
      span.tag("http.path", "/order");
      span.tag("http.status_code", "500");
      span.error(new RuntimeException("Unexpected Exception!"));
      spanHandler.end(context, span, null);
      spanHandler.flush();
    }
    if (otlpHttpServer.waitUntilTraceRequestsAreSent(Duration.ofSeconds(3))) {
      Span.Builder spanBuilder = Span.newBuilder()
          .setName("post")
          .setStartTimeUnixNano(milliToNanos(1510256710021866L))
          .setEndTimeUnixNano(milliToNanos(1510256710021866L + 1117L))
          .setTraceId(ByteString.fromHex("123caa480c3fa187dd37f5d5c991f2c7"))
          .setSpanId(ByteString.fromHex("5d64683224ba9b17"))
          .setKind(Span.SpanKind.SPAN_KIND_CLIENT);
      ScopeSpans.Builder scopeSpanBuilder = ScopeSpans.newBuilder();
      Resource.Builder resourceBuilder = Resource.newBuilder().addAttributes(stringAttribute("service.name", "test-api"));
      if (encoder instanceof OtlpProtoV1Encoder) {
        scopeSpanBuilder.setScope(InstrumentationScope.newBuilder().setName(BraveScope.NAME).setVersion(BraveScope.VERSION));
        spanBuilder.addAttributes(stringAttribute("network.local.address", "10.99.99.99"))
            .addAttributes(intAttribute("network.local.port", 43210))
            .addAttributes(stringAttribute("network.peer.address", "1.2.3.4"))
            .addAttributes(intAttribute("network.peer.port", 9999))
            .addAttributes(stringAttribute("peer.service", "demo"))
            .addAttributes(stringAttribute("server.address", "zipkin.example.com"))
            .addAttributes(stringAttribute("http.request.method", "POST"))
            .addAttributes(stringAttribute("url.full", "https://zipkin.example.com/order"))
            .addAttributes(stringAttribute("url.path", "/order"))
            .addAttributes(stringAttribute("http.response.status_code", "500"))
            .addAttributes(stringAttribute("error", "Unexpected Exception!"))
            .setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_ERROR).build());
        resourceBuilder
            .addAttributes(stringAttribute("telemetry.sdk.language", "java"))
            .addAttributes(stringAttribute("telemetry.sdk.name", OtlpProtoV1Encoder.class.getName()))
            .addAttributes(stringAttribute("telemetry.sdk.version", BraveScope.VERSION));
      } else {
        scopeSpanBuilder.setScope(InstrumentationScope.newBuilder().build() /* empty */);
        spanBuilder.addAttributes(stringAttribute("http.method", "POST"))
            .addAttributes(stringAttribute("http.url", "https://zipkin.example.com/order"))
            .addAttributes(stringAttribute("error", "Unexpected Exception!"))
            .addAttributes(stringAttribute("http.path", "/order"))
            .addAttributes(stringAttribute("http.status_code", "500"))
            .addAttributes(stringAttribute("http.host", "zipkin.example.com"))
            .addAttributes(stringAttribute("net.host.ip", "10.99.99.99"))
            .addAttributes(intAttribute("net.host.port", 43210))
            .addAttributes(stringAttribute("net.peer.ip", "1.2.3.4"))
            .addAttributes(intAttribute("net.peer.port", 9999))
            .addAttributes(stringAttribute("peer.service", "demo"))
            .setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_ERROR).build());
      }
      ResourceSpans resourceSpans = ResourceSpans.newBuilder()
          .setResource(resourceBuilder)
          .addScopeSpans(scopeSpanBuilder.addSpans(spanBuilder))
          .build();
      List<ResourceSpans> receivedSpans = otlpHttpServer.receivedSpans();
      assertThat(receivedSpans.size()).isEqualTo(1);
      compareResourceSpans(receivedSpans.get(0), resourceSpans);
    } else {
      Assertions.fail("Traces not sent");
    }
  }


  void compareResourceSpans(ResourceSpans actual, ResourceSpans expected) {
    assertThat(actual.getResource()).isEqualTo(expected.getResource());
    assertThat(actual.getScopeSpansCount()).isEqualTo(expected.getScopeSpansCount());
    for (int i = 0; i < expected.getScopeSpansCount(); i++) {
      ScopeSpans actualScopeSpans = actual.getScopeSpans(i);
      ScopeSpans expectedScopeSpans = expected.getScopeSpans(i);
      assertThat(actualScopeSpans.getScope()).isEqualTo(expectedScopeSpans.getScope());
      assertThat(actualScopeSpans.getSpansCount()).isEqualTo(expectedScopeSpans.getSpansCount());
      for (int j = 0; j < expectedScopeSpans.getSpansCount(); j++) {
        Span actualSpan = actualScopeSpans.getSpans(j);
        Span expectedSpan = expectedScopeSpans.getSpans(j);
        assertThat(actualSpan.getName()).isEqualTo(expectedSpan.getName());
        assertThat(actualSpan.getStartTimeUnixNano()).isEqualTo(expectedSpan.getStartTimeUnixNano());
        assertThat(actualSpan.getEndTimeUnixNano()).isEqualTo(expectedSpan.getEndTimeUnixNano());
        assertThat(actualSpan.getTraceId()).isEqualTo(expectedSpan.getTraceId());
        assertThat(actualSpan.getSpanId()).isEqualTo(expectedSpan.getSpanId());
        assertThat(actualSpan.getKind()).isEqualTo(expectedSpan.getKind());
        assertThat(actualSpan.getEventsList()).containsExactlyInAnyOrderElementsOf(expectedSpan.getEventsList());
        assertThat(actualSpan.getAttributesList()).containsExactlyInAnyOrderElementsOf(expectedSpan.getAttributesList());
      }
    }
  }

  private static long milliToNanos(long millis) {
    return millis * 1_000L;
  }

  private static class OtlpHttpServer extends ServerExtension {

    private final List<ResourceSpans> spans = new ArrayList<>();

    private CountDownLatch latch = new CountDownLatch(1);

    private void reset() {
      this.spans.clear();
      this.latch = new CountDownLatch(1);
    }

    @Override
    protected void configure(ServerBuilder sb) {
      sb.service(
          "/v1/traces",
          new AbstractHttpService() {
            @Override
            protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
              return HttpResponse.of(req.aggregate()
                  .handle((msg, t) -> {
                    try (HttpData content = msg.content()) {
                      if (!content.isEmpty()) {
                        try {
                          ExportTraceServiceRequest request = ExportTraceServiceRequest.parseFrom(
                              "gzip".equalsIgnoreCase(req.headers().get("Content-Encoding")) ?
                                  decompressGzip(content.array()) : content.array());
                          if (request.getResourceSpansCount() > 0) {
                            spans.addAll(request.getResourceSpansList());
                            latch.countDown();
                          }
                        } catch (IOException e) {
                          throw new UncheckedIOException(e);
                        }
                      }
                      return HttpResponse.of(HttpStatus.ACCEPTED);
                    }
                  }));
            }
          });
      sb.http(0);
    }


    private byte[] decompressGzip(byte[] compressed) throws IOException {
      try (InputStream gis = new GZIPInputStream(new ByteArrayInputStream(compressed));
         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[1024];
        int len;
        while ((len = gis.read(buffer)) != -1) {
          out.write(buffer, 0, len);
        }
        return out.toByteArray();
      }
    }

    public boolean waitUntilTraceRequestsAreSent(Duration timeout) throws InterruptedException {
      return this.latch.await(timeout.getSeconds(), TimeUnit.SECONDS);
    }

    public List<ResourceSpans> receivedSpans() {
      return this.spans;
    }
  }
}
