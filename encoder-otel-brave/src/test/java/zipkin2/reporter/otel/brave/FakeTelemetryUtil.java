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

import brave.Span.Kind;
import brave.handler.MutableSpan;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.concurrent.TimeUnit;

/**
 * Taken from https://github.com/open-telemetry/opentelemetry-java/blob/v1.39.0/exporters/otlp/testing-internal/src/main/java/io/opentelemetry/exporter/otlp/testing/internal/FakeTelemetryUtil.java
 */
public class FakeTelemetryUtil {

  private static final String TRACE_ID = "00000000000000000000000000abc123";
  private static final String SPAN_ID = "0000000000def456";


  /** Generate a fake {@link SpanData}. */
  public static MutableSpan generateFakeSpanData() {
    long duration = TimeUnit.MILLISECONDS.toNanos(900);
    long startNs = TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis());
    long endNs = startNs + duration;
    MutableSpan mutableSpan = new MutableSpan();
    mutableSpan.traceId(TRACE_ID);
    mutableSpan.id(SPAN_ID);
    mutableSpan.name("GET /api/endpoint");
    mutableSpan.startTimestamp(TimeUnit.NANOSECONDS.toMicros(startNs));
    mutableSpan.finishTimestamp(TimeUnit.NANOSECONDS.toMicros(endNs));
    mutableSpan.kind(Kind.SERVER);
    return mutableSpan;
  }

  private FakeTelemetryUtil() {}
}
