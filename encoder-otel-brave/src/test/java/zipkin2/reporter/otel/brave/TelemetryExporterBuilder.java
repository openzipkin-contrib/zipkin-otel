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

import io.opentelemetry.sdk.common.export.ProxyOptions;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Taken from https://github.com/open-telemetry/opentelemetry-java/blob/v1.39.0/exporters/otlp/testing-internal/src/main/java/io/opentelemetry/exporter/otlp/testing/internal/TelemetryExporterBuilder.java
 */
public interface TelemetryExporterBuilder<T> {

  TelemetryExporterBuilder<T> setEndpoint(String endpoint);

  TelemetryExporterBuilder<T> setTimeout(Duration timeout);

  TelemetryExporterBuilder<T> setConnectTimeout(Duration timeout);

  TelemetryExporterBuilder<T> setCompression(String compression);

  TelemetryExporterBuilder<T> addHeader(String key, String value);

  TelemetryExporterBuilder<T> setHeaders(Supplier<Map<String, String>> headerSupplier);

  TelemetryExporterBuilder<T> setProxyOptions(ProxyOptions proxyOptions);

  CloseableSpanHandler build();
}
