/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package zipkin2.reporter.otel.brave;

import brave.handler.MutableSpan;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import zipkin2.reporter.okhttp3.OkHttpSender;

/**
 * Taken from https://github.com/open-telemetry/opentelemetry-java/blob/v1.39.0/exporters/otlp/testing-internal/src/main/java/io/opentelemetry/exporter/otlp/testing/internal/OtlpHttpSpanExporterOkHttpSenderTest.java
 */
class OtlpHttpSpanExporterOkHttpSenderTest
    extends AbstractHttpTelemetryExporterTest<MutableSpan, ResourceSpans> {

  protected OtlpHttpSpanExporterOkHttpSenderTest() {
    super("span", "/v1/traces", ResourceSpans.getDefaultInstance());
  }

  @Override
  protected TelemetryExporterBuilder<MutableSpan> exporterBuilder() {
    return new HttpSpanExporterBuilderWrapper(OkHttpSender.newBuilder());
  }

  @Override
  protected MutableSpan generateFakeTelemetry() {
    return FakeTelemetryUtil.generateFakeSpanData();
  }
}
