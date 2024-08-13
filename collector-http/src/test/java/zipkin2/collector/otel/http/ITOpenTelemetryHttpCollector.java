/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.collector.otel.http;

import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import zipkin2.collector.CollectorComponent;
import zipkin2.collector.CollectorSampler;
import zipkin2.collector.InMemoryCollectorMetrics;
import zipkin2.storage.InMemoryStorage;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ITOpenTelemetryHttpCollector {
  InMemoryStorage store;
  InMemoryCollectorMetrics metrics;
  CollectorComponent collector;

  @BeforeEach public void setup() {
    store = InMemoryStorage.newBuilder().build();
    metrics = new InMemoryCollectorMetrics();

    collector = OpenTelemetryHttpCollector.newBuilder()
        .metrics(metrics)
        .sampler(CollectorSampler.ALWAYS_SAMPLE)
        .storage(store)
        .build()
        .start();
    metrics = metrics.forTransport("otel/http");
  }

  @AfterEach void teardown() throws IOException {
    store.close();
    collector.close();
  }

  // TODO: integration test
}
