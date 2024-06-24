/*
 * Copyright 2024 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin2.reporter.otel;

import brave.Span;
import brave.Span.Kind;
import brave.Tags;
import brave.Tracer;
import brave.Tracer.SpanInScope;
import brave.Tracing;
import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.propagation.ThreadLocalCurrentTraceContext;
import brave.propagation.TraceContext;
import brave.sampler.Sampler;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import okhttp3.OkHttpClient;
import okhttp3.OkHttpClient.Builder;
import okhttp3.Request;
import okhttp3.Response;
import org.assertj.core.api.BDDAssertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.util.TestSocketUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import zipkin.module.otel.ZipkinOpenTelemetryHttpCollectorProperties;
import zipkin.server.ZipkinServer;
import zipkin2.reporter.AsyncReporter;
import zipkin2.reporter.Encoding;
import zipkin2.reporter.okhttp3.OkHttpSender;
import zipkin2.reporter.otel.brave.OtelEncoder;

class ITBasicUsage {

  private static final Logger log = LoggerFactory.getLogger(ITBasicUsage.class);

  private static final int EXPECTED_TRACE_SIZE = 5;

  @Nested
  class ZipkinCollectorTests {

    TestingScenario testingScenario;

    @ParameterizedTest
    @EnumSource(TestingScenario.class)
    void shouldSendOtlpHttpSpansToOtlpEndpoint(TestingScenario testingScenario) throws Exception {
      // Setup
      this.testingScenario = testingScenario;
      testingScenario.setup();

      List<String> traceIds = testingScenario.exportedTraceIds();

      // Then
      Awaitility.await().untilAsserted(() -> {
        BDDAssertions.then(traceIds).hasSize(EXPECTED_TRACE_SIZE);
        thenAllTraceIdsPresentInBackend(testingScenario.queryUrl(), traceIds);
      });
    }

    @AfterEach
    void shutdown() throws IOException {
      testingScenario.close();
    }
  }

  @Nested
  @Testcontainers(disabledWithoutDocker = true)
  class ZipkinSenderTests {

    @Container
    JaegerAllInOne jaegerAllInOne = new JaegerAllInOne();

    JaegerTestingScenario testingScenario;

    @Test
    void shouldSendBraveSpansToJaegerOtlpEndpoint() throws Exception {
      // Setup
      testingScenario = new JaegerTestingScenario(jaegerAllInOne.getHttpOtlpPort());
      testingScenario.setup();

      List<String> traceIds = testingScenario.exportedTraceIds();

      // Then
      Awaitility.await().untilAsserted(() -> {
        BDDAssertions.then(traceIds).hasSize(EXPECTED_TRACE_SIZE);
        thenAllTraceIdsPresentInBackend(testingScenario.queryUrl(), traceIds);
      });
    }

    /**
     * Sender: Brave OKHttp with OTLP proto over HTTP ; Receiver: Jaeger OTLP
     */
    class JaegerTestingScenario implements ScenarioSetup {

      private final BraveTraceIdGenerator braveTraceIdGenerator;

      JaegerTestingScenario(int otlpPort) {
        this.braveTraceIdGenerator = new BraveTraceIdGenerator(otlpPort);
      }

      @Override
      public String queryUrl() {
        return "http://localhost:" + jaegerAllInOne.getQueryPort() + "/api/traces/";
      }

      @Override
      public List<String> exportedTraceIds() throws Exception {
        return braveTraceIdGenerator.traceIds();
      }

      @Override
      public void close() {
        braveTraceIdGenerator.close();
      }
    }
  }

  private static void thenAllTraceIdsPresentInBackend(String queryUrl, List<String> traceIds) {
    OkHttpClient client = new Builder()
        .build();

    traceIds.forEach(traceId -> {
      Request request = new Request.Builder().url(queryUrl + traceId).build();
      try (Response response = client.newCall(request).execute()) {
        BDDAssertions.then(response.isSuccessful()).isTrue();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
  }


  interface ScenarioSetup extends Closeable {

    /**
     * Code to be run before tests are executed.
     */
    default void setup() {

    }

    /**
     * URL from which trace data can be collected.
     *
     * @return query URL
     */
    String queryUrl();

    /**
     * Actual testing code that will create spans and send them to the backend.
     *
     * @return list of generated trace ids
     * @throws Exception exception
     */
    List<String> exportedTraceIds() throws Exception;

  }

  enum TestingScenario implements ScenarioSetup {

    // TODO: Why is it so slow?
    /**
     * Sender: Brave OKHttp with OTLP proto over HTTP ; Receiver: Zipkin OTLP
     */
    BRAVE_OK_HTTP_OTLP_SENDER_TO_ZIPKIN_OLTP {

      private ConfigurableApplicationContext ctx;

      private final int port = TestSocketUtils.findAvailableTcpPort();

      private final BraveTraceIdGenerator braveTraceIdGenerator = new BraveTraceIdGenerator(port);

      @Override
      public void setup() {
        ctx = new SpringApplicationBuilder(Config.class)
            .web(WebApplicationType.NONE)
            .run("--spring.main.allow-bean-definition-overriding=true", "--server.port=0",
                "--armeria.ports[0].port=" + port, "--logging.level.zipkin2=trace",
                "--logging.level.com.linecorp=debug");
      }


      @Override
      public String queryUrl() {
        return "http://localhost:" + port + "/api/v2/trace/";
      }

      @Override
      public List<String> exportedTraceIds() throws Exception {
        return braveTraceIdGenerator.traceIds();
      }

      @Override
      public void close() {
        braveTraceIdGenerator.close();
        if (ctx != null) {
          ctx.close();
        }
      }
    },

    /**
     * Sender: OpenTelemetry OTLP proto over HTTP Exporter ; Receiver: Zipkin OTLP
     */
    OTEL_OTLP_EXPORTER_TO_ZIPKIN_OTLP {

      private ConfigurableApplicationContext ctx;

      private final int port = TestSocketUtils.findAvailableTcpPort();

      private final OpenTelemetry openTelemetry = initOpenTelemetry(port);

      /**
       * Initialize OpenTelemetry.
       *
       * @return a ready-to-use {@link OpenTelemetry} instance.
       */
      OpenTelemetry initOpenTelemetry(int port) {
        OpenTelemetrySdk openTelemetrySdk =
            OpenTelemetrySdk.builder()
                .setTracerProvider(
                    SdkTracerProvider.builder()
                        .addSpanProcessor(
                            BatchSpanProcessor.builder(
                                    OtlpHttpSpanExporter.builder()
                                        .setEndpoint("http://localhost:" + port + "/v1/traces")
                                        .setTimeout(2, TimeUnit.SECONDS)
                                        .build())
                                .setScheduleDelay(100, TimeUnit.MILLISECONDS)
                                .build())
                        .build())
                .buildAndRegisterGlobal();

        Runtime.getRuntime().addShutdownHook(new Thread(openTelemetrySdk::close));

        return openTelemetrySdk;
      }

      @Override
      public void setup() {
        ctx = new SpringApplicationBuilder(Config.class)
            .web(WebApplicationType.NONE)
            .run("--spring.main.allow-bean-definition-overriding=true", "--server.port=0",
                "--armeria.ports[0].port=" + port, "--logging.level.zipkin2=trace",
                "--logging.level.com.linecorp=debug");
      }


      @Override
      public String queryUrl() {
        return "http://localhost:" + port + "/api/v2/trace/";
      }

      @Override
      public List<String> exportedTraceIds() throws Exception {
        io.opentelemetry.api.trace.Tracer tracer = openTelemetry.getTracer(
            "io.opentelemetry.example");
        List<String> traceIds = new ArrayList<>();
        for (int i = 0; i < EXPECTED_TRACE_SIZE; i++) {
          io.opentelemetry.api.trace.Span span = tracer.spanBuilder("foo " + i)
              .setAttribute("foo tag", "foo value")
              .setSpanKind(SpanKind.CONSUMER)
              .startSpan()
              .recordException(new RuntimeException("BOOOOOM!"));
          String traceId = span.getSpanContext().getTraceId();
          traceIds.add(traceId);
          try (Scope scope = Context.current().with(span).makeCurrent()) {
            log.info("Trace Id <" + traceId + ">");
            Thread.sleep(50);
            span.addEvent("boom!");
            Thread.sleep(50);
          } finally {
            span.end();
          }
        }
        return traceIds;
      }

      @Override
      public void close() {
        if (ctx != null) {
          ctx.close();
        }
      }
    }
  }

  @SpringBootApplication(scanBasePackageClasses = {ZipkinOpenTelemetryHttpCollectorProperties.class,
      ZipkinServer.class})
  static class Config {

  }

  /**
   * Actual testing logic that uses Brave to generate spans and send them to the backend.
   */
  static class BraveTraceIdGenerator implements Closeable {

    private final BraveHttpSenderProvider braveHttpSenderProvider = new BraveHttpSenderProvider();

    private final int port;

    Tracing tracing;

    BraveTraceIdGenerator(int port) {
      this.port = port;
    }

    List<String> traceIds() throws Exception {
      ThreadLocalCurrentTraceContext braveCurrentTraceContext = ThreadLocalCurrentTraceContext.newBuilder()
          .build();
      List<String> traceIds = new ArrayList<>();
      tracing = Tracing.newBuilder()
          .currentTraceContext(braveCurrentTraceContext)
          .supportsJoin(false)
          .traceId128Bit(true)
          .sampler(Sampler.ALWAYS_SAMPLE)
          .addSpanHandler(braveHttpSenderProvider.apply(port))
          .localServiceName("my-service")
          .build();
      Tracer braveTracer = tracing.tracer();

      for (int i = 0; i < EXPECTED_TRACE_SIZE; i++) {
        Span span = braveTracer.nextSpan().name("foo " + i)
            .tag("foo tag", "foo value")
            .kind(Kind.CONSUMER)
            .error(new RuntimeException("BOOOOOM!"))
            .remoteServiceName("remote service")
            .start();
        try (SpanInScope scope = braveTracer.withSpanInScope(span)) {
          String traceId = span.context().traceIdString();
          log.info("Trace Id <" + traceId + ">");
          span.remoteIpAndPort("http://localhost", 123456);
          Thread.sleep(50);
          span.annotate("boom!");
          Thread.sleep(50);
        } finally {
          span.finish();
        }

        traceIds.add(span.context().traceIdString());
      }
      flush();
      return traceIds;
    }

    void flush() {
      braveHttpSenderProvider.flush();
    }

    @Override
    public void close() {
      braveHttpSenderProvider.close();
      tracing.close();
    }

    /**
     * Provides a {@link SpanHandler} that uses OKHttp to send spans to a given port.
     */
    static class BraveHttpSenderProvider implements Function<Integer, SpanHandler>, Closeable {

      OkHttpSender okHttpSender;

      AsyncReporter<MutableSpan> reporter;

      SpanHandler spanHandler;

      @Override
      public void close() {
        if (reporter != null) {
          reporter.close();
        }
        if (okHttpSender != null) {
          okHttpSender.close();
        }
      }

      @Override
      public SpanHandler apply(Integer port) {
        okHttpSender = OkHttpSender.newBuilder()
            .encoding(Encoding.PROTO3)
            .endpoint("http://localhost:" + port + "/v1/traces")
            .build();
        OtelEncoder otelEncoder = new OtelEncoder(Tags.ERROR);
        reporter = AsyncReporter.builder(okHttpSender).build(otelEncoder);
        spanHandler = new SpanHandler() {
          @Override
          public boolean end(TraceContext context, MutableSpan span, Cause cause) {
            reporter.report(span);
            return true;
          }
        };
        return spanHandler;
      }

      void flush() {
        reporter.flush();
      }
    }
  }
}