/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.collector.otel.http;

import org.junit.jupiter.api.Test;
import zipkin2.storage.InMemoryStorage;

import static org.assertj.core.api.Assertions.assertThat;

class OpenTelemetryHttpCollectorTest {

	OpenTelemetryHttpCollector collector = OpenTelemetryHttpCollector.newBuilder()
		.storage(InMemoryStorage.newBuilder().build())
		.build();

	@Test
	void check_ok() {
		assertThat(collector.check().ok()).isTrue();
	}

	/**
	 * The output of toString() on {@link zipkin2.collector.Collector} implementations
	 * appear in the /health endpoint. Make sure it is minimal and human-readable.
	 */
	@Test
	void toStringContainsOnlyConfigurableFields() {
		assertThat(collector.toString()).isEqualTo("OpenTelemetryHttpCollector{}");
	}

}
