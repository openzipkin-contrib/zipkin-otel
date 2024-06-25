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

import brave.Tags;
import brave.handler.MutableSpan;
import io.opentelemetry.sdk.common.export.ProxyOptions;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import okhttp3.Request;
import okhttp3.Request.Builder;
import zipkin2.reporter.AsyncReporter;
import zipkin2.reporter.Encoding;
import zipkin2.reporter.InMemoryReporterMetrics;
import zipkin2.reporter.okhttp3.OkHttpSender;

/**
 * Taken from https://github.com/open-telemetry/opentelemetry-java/blob/v1.39.0/exporters/otlp/testing-internal/src/main/java/io/opentelemetry/exporter/otlp/testing/internal/HttpSpanExporterBuilderWrapper.java
 */
public class HttpSpanExporterBuilderWrapper implements TelemetryExporterBuilder<MutableSpan> {

  private final OkHttpSender.Builder builder;

  public HttpSpanExporterBuilderWrapper(OkHttpSender.Builder builder) {
    this.builder = builder.encoding(Encoding.PROTO3).compressionEnabled(false);
    this.builder.clientBuilder()
        .addInterceptor(chain -> {
          // TODO: This should be added to the docs or somewhere to wrap OkHttp Sender
          Request request = chain.request().newBuilder()
              .addHeader("User-Agent", "OTel-OTLP-Exporter-Java/1.0.0").build();
          return chain.proceed(request);
        });
  }

  @Override
  public TelemetryExporterBuilder<MutableSpan> setEndpoint(String endpoint) {
    builder.endpoint(endpoint);
    return this;
  }

  @Override
  public TelemetryExporterBuilder<MutableSpan> setTimeout(Duration timeout) {
    builder.readTimeout(Math.toIntExact(timeout.toMillis()));
    return this;
  }

  @Override
  public TelemetryExporterBuilder<MutableSpan> setConnectTimeout(Duration timeout) {
    builder.connectTimeout(Math.toIntExact(timeout.toMillis()));
    return this;
  }

  @Override
  public TelemetryExporterBuilder<MutableSpan> setCompression(String compression) {
    if ("gzip".equalsIgnoreCase(compression)) {
      builder.compressionEnabled(true);
    } else if ("none".equalsIgnoreCase(compression)) {
      builder.compressionEnabled(false);
    } else {
      throw new UnsupportedOperationException("Only gzip is supported");
    }
    return this;
  }

  @Override
  public TelemetryExporterBuilder<MutableSpan> addHeader(String key, String value) {
    builder.clientBuilder().addInterceptor(chain -> {
      Request request = chain.request();
      Request newRequest = request.newBuilder()
          .addHeader(key, value)
          .build();
      return chain.proceed(newRequest);
    });
    return this;
  }

  @Override
  public TelemetryExporterBuilder<MutableSpan> setHeaders(
      Supplier<Map<String, String>> headerSupplier) {
    builder.clientBuilder().addInterceptor(chain -> {
      Request request = chain.request();
      Builder newBuilder = request.newBuilder();
      headerSupplier.get().forEach(newBuilder::addHeader);
      Request newRequest = newBuilder.build();
      return chain.proceed(newRequest);
    });
    return this;
  }

  @Override
  public TelemetryExporterBuilder<MutableSpan> setProxyOptions(ProxyOptions proxyOptions) {
    builder.clientBuilder().proxy(proxyOptions.getProxySelector().select(URI.create("localhost")).get(0));
    return this;
  }

  @Override
  public CloseableSpanHandler build() {
    OtelEncoder otelEncoder = new OtelEncoder(Tags.ERROR);
    OkHttpSender sender = builder.build();
    InMemoryReporterMetrics reporterMetrics = new InMemoryReporterMetrics();
    AsyncReporter<MutableSpan> reporter = AsyncReporter.builder(sender)
        .metrics(reporterMetrics)
        .build(otelEncoder);
    return new CloseableSpanHandler(reporter, reporterMetrics);
  }
}
