/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
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
