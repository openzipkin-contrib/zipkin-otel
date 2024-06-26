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
package zipkin2.collector.otel.http;

import static io.opentelemetry.sdk.trace.samplers.Sampler.alwaysOn;
import static org.assertj.core.api.Assertions.assertThat;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zipkin2.collector.CollectorComponent;
import zipkin2.collector.CollectorSampler;
import zipkin2.collector.InMemoryCollectorMetrics;
import zipkin2.storage.InMemoryStorage;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ITOpenTelemetryHttpCollector {

  private static final Logger log = LoggerFactory.getLogger(ITOpenTelemetryHttpCollector.class);

  InMemoryStorage store;
  InMemoryCollectorMetrics metrics;
  CollectorComponent collector;

  SpanExporter spanExporter = OtlpHttpSpanExporter.builder().build();

  SdkTracerProvider sdkTracerProvider = SdkTracerProvider.builder()
      .setSampler(alwaysOn())
      .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
      .build();

  OpenTelemetrySdk openTelemetrySdk = OpenTelemetrySdk.builder()
      .setTracerProvider(sdkTracerProvider)
      .build();

  Tracer tracer = openTelemetrySdk.getTracerProvider()
      .get("zipkin2.collector.otel.http");

  Server server;

  @BeforeEach public void setup() {
    store = InMemoryStorage.newBuilder().build();
    metrics = new InMemoryCollectorMetrics();

    collector = OpenTelemetryHttpCollector.newBuilder()
        .metrics(metrics)
        .sampler(CollectorSampler.ALWAYS_SAMPLE)
        .storage(store)
        .build()
        .start();
    ServerBuilder serverBuilder = Server.builder().http(4318);
    ((OpenTelemetryHttpCollector) collector).reconfigure(serverBuilder);
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
  void otelHttpExporterWorksWithZipkinOtelCollector() throws InterruptedException {
    List<String> traceIds = new ArrayList<>();
    final int size = 5;
    for (int i = 0; i < size; i++) {
      // Given
      Span span = tracer.spanBuilder("foo " + i)
          .setAttribute("foo tag", "foo value")
          .setSpanKind(SpanKind.CONSUMER)
          .startSpan();
      String traceId = span.getSpanContext().getTraceId();
      log.info("Trace Id <" + traceId + ">");
      Thread.sleep(50);
      span.addEvent("boom!");
      Thread.sleep(50);

      // When
      span.end();
      traceIds.add(traceId);
    }

    Awaitility.await().untilAsserted(() -> assertThat(store.acceptedSpanCount()).isEqualTo(5));

  }
}
