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
package zipkin.module.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.OkHttpClient.Builder;
import okhttp3.Request;
import okhttp3.Response;
import org.assertj.core.api.BDDAssertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.util.TestSocketUtils;
import zipkin.server.ZipkinServer;

class ZipkinCollectorTests {

  private static final Logger log = LoggerFactory.getLogger(
      ZipkinCollectorTests.class);

  private static final int EXPECTED_TRACE_SIZE = 5;

  private ConfigurableApplicationContext ctx;

  private final int port = TestSocketUtils.findAvailableTcpPort();

  private final OpenTelemetry openTelemetry = initOpenTelemetry(port);

  /**
   * Initialize OpenTelemetry.
   *
   * @return a ready-to-use {@link OpenTelemetry} instance.
   */
  private OpenTelemetry initOpenTelemetry(int port) {
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

  @BeforeEach
  void setup() {
    ctx = new SpringApplicationBuilder(Config.class)
        .web(WebApplicationType.NONE)
        .run("--spring.main.allow-bean-definition-overriding=true", "--server.port=0",
            "--armeria.ports[0].port=" + port, "--logging.level.zipkin2=trace",
            "--logging.level.com.linecorp=debug");
  }


  @Test
  void shouldSendOtlpHttpSpansToOtlpEndpoint() throws Exception {
    List<String> traceIds = exportedTraceIds();

    Awaitility.await().untilAsserted(() -> {
      BDDAssertions.then(traceIds).hasSize(EXPECTED_TRACE_SIZE);
      thenAllTraceIdsPresentInBackend(queryUrl(), traceIds);
    });
  }

  private String queryUrl() {
    return "http://localhost:" + port + "/api/v2/trace/";
  }

  private List<String> exportedTraceIds() throws Exception {
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

  @AfterEach
  void shutdown() throws IOException {
    if (ctx != null) {
      ctx.close();
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

  @SpringBootApplication(scanBasePackageClasses = {ZipkinOpenTelemetryHttpCollectorProperties.class,
      ZipkinServer.class})
  static class Config {

  }
}