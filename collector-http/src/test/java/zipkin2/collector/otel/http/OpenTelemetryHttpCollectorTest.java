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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import zipkin2.storage.InMemoryStorage;

class OpenTelemetryHttpCollectorTest {
  OpenTelemetryHttpCollector collector = OpenTelemetryHttpCollector.newBuilder()
      .storage(InMemoryStorage.newBuilder().build())
      .build();

  @Test void check_ok() {
    assertThat(collector.check().ok()).isTrue();
  }

  /**
   * The output of toString() on {@link zipkin2.collector.Collector} implementations appear in the
   * /health endpoint. Make sure it is minimal and human-readable.
   */
  @Test void toStringContainsOnlyConfigurableFields() {
    assertThat(collector.toString())
        .isEqualTo("OpenTelemetryHttpCollector{}");
  }
}
