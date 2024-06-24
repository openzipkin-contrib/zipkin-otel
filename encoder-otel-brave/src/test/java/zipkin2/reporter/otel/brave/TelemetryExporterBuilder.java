/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package zipkin2.reporter.otel.brave;

import io.opentelemetry.sdk.common.export.ProxyOptions;
import io.opentelemetry.sdk.common.export.RetryPolicy;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Taken from https://github.com/open-telemetry/opentelemetry-java/blob/v1.39.0/exporters/otlp/testing-internal/src/main/java/io/opentelemetry/exporter/otlp/testing/internal/TelemetryExporterBuilder.java
 */
public interface TelemetryExporterBuilder<T> {

  TelemetryExporterBuilder<T> setEndpoint(String endpoint);

  TelemetryExporterBuilder<T> setTimeout(long timeout, TimeUnit unit);

  TelemetryExporterBuilder<T> setTimeout(Duration timeout);

  TelemetryExporterBuilder<T> setConnectTimeout(long timeout, TimeUnit unit);

  TelemetryExporterBuilder<T> setConnectTimeout(Duration timeout);

  TelemetryExporterBuilder<T> setCompression(String compression);

  TelemetryExporterBuilder<T> addHeader(String key, String value);

  TelemetryExporterBuilder<T> setHeaders(Supplier<Map<String, String>> headerSupplier);

  TelemetryExporterBuilder<T> setRetryPolicy(RetryPolicy retryPolicy);

  TelemetryExporterBuilder<T> setProxyOptions(ProxyOptions proxyOptions);

  CloseableSpanHandler build();
}
