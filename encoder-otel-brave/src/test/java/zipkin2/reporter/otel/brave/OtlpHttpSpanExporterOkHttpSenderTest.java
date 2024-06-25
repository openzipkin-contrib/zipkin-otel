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

import brave.handler.MutableSpan;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import zipkin2.reporter.okhttp3.OkHttpSender;

/**
 * Taken from https://github.com/open-telemetry/opentelemetry-java/blob/v1.39.0/exporters/otlp/testing-internal/src/main/java/io/opentelemetry/exporter/otlp/testing/internal/OtlpHttpSpanExporterOkHttpSenderTest.java
 */
class OtlpHttpSpanExporterOkHttpSenderTest
    extends AbstractHttpTelemetryExporterTest<MutableSpan, ResourceSpans> {

  protected OtlpHttpSpanExporterOkHttpSenderTest() {
    super("/v1/traces");
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
