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
package zipkin2.reporter.otel.brave;

import static org.assertj.core.api.BDDAssertions.then;

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
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import okhttp3.OkHttpClient;
import okhttp3.OkHttpClient.Builder;
import okhttp3.Request;
import okhttp3.Response;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import zipkin2.reporter.AsyncReporter;
import zipkin2.reporter.Encoding;
import zipkin2.reporter.okhttp3.OkHttpSender;

@Testcontainers(disabledWithoutDocker = true)
class ITZipkinSenderTests {

  private static final Logger log = LoggerFactory.getLogger(ITZipkinSenderTests.class);

  private static final int EXPECTED_TRACE_SIZE = 5;

  @Container
  JaegerAllInOne jaegerAllInOne = new JaegerAllInOne();

  JaegerTestingScenario testingScenario;

  @Test
  void shouldSendBraveSpansToJaegerOtlpEndpoint() throws Exception {
    testingScenario = new JaegerTestingScenario(
        jaegerAllInOne.getHttpOtlpPort());

    List<String> traceIds = testingScenario.exportedTraceIds();

    Awaitility.await().untilAsserted(() -> {
      then(traceIds).hasSize(EXPECTED_TRACE_SIZE);
      thenAllTraceIdsPresentInBackend(testingScenario.queryUrl(), traceIds);
    });
  }

  @AfterEach
  void shutdown() {
    testingScenario.close();
  }

  private static void thenAllTraceIdsPresentInBackend(String queryUrl, List<String> traceIds) {
    OkHttpClient client = new Builder()
        .build();

    traceIds.forEach(traceId -> {
      Request request = new Request.Builder().url(queryUrl + traceId).build();
      try (Response response = client.newCall(request).execute()) {
        then(response.isSuccessful()).isTrue();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
  }

  /**
   * Sender: Brave OKHttp with OTLP proto over HTTP ; Receiver: Jaeger OTLP
   */
  class JaegerTestingScenario {

    private final BraveTraceIdGenerator braveTraceIdGenerator;

    JaegerTestingScenario(int otlpPort) {
      this.braveTraceIdGenerator = new BraveTraceIdGenerator(otlpPort);
    }

    String queryUrl() {
      return "http://localhost:" + jaegerAllInOne.getQueryPort() + "/api/traces/";
    }

    List<String> exportedTraceIds() throws Exception {
      return braveTraceIdGenerator.traceIds();
    }

    public void close() {
      braveTraceIdGenerator.close();
    }
  }

  /**
   * Actual testing logic that uses Brave to generate spans and send them to the backend.
   */
  static class BraveTraceIdGenerator {

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

    private void flush() {
      braveHttpSenderProvider.flush();
    }

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

      private void flush() {
        reporter.flush();
      }
    }
  }
}