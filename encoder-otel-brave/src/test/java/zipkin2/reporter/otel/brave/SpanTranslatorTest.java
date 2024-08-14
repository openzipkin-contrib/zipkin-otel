/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.reporter.otel.brave;

import static org.assertj.core.api.Assertions.assertThat;
import static zipkin2.reporter.otel.brave.SpanTranslator.stringAttribute;
import static zipkin2.reporter.otel.brave.TestObjects.clientSpan;

import brave.Tags;
import brave.handler.MutableSpan;
import brave.propagation.TraceContext;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Span.Event;
import io.opentelemetry.proto.trace.v1.Span.SpanKind;
import io.opentelemetry.proto.trace.v1.Status;
import io.opentelemetry.proto.trace.v1.Status.StatusCode;
import io.opentelemetry.proto.trace.v1.TracesData;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SpanTranslatorTest {

  SpanTranslator spanTranslator = new SpanTranslator(Tags.ERROR);

  /**
   * This test is intentionally sensitive, so changing other parts makes obvious impact here
   */
  @Test
  void translate_clientSpan() {
    MutableSpan braveSpan = clientSpan();
    TracesData translated = spanTranslator.translate(braveSpan);

    assertThat(firstSpan(translated))
        .isEqualTo(
            Span.newBuilder()
                .setTraceId(ByteString.fromHex(braveSpan.traceId()))
                .setSpanId(ByteString.fromHex(braveSpan.id()))
                .setParentSpanId(ByteString.fromHex(braveSpan.parentId()))
                .setName("get")
                .setKind(SpanKind.SPAN_KIND_CLIENT)
                .setStartTimeUnixNano(
                    TimeUnit.MILLISECONDS.toNanos(
                        Instant.ofEpochSecond(1472470996, 199_000_000).toEpochMilli()))
                .setEndTimeUnixNano(
                    TimeUnit.MILLISECONDS.toNanos(
                        Instant.ofEpochSecond(1472470996, 406_000_000).toEpochMilli()))
                .addAllAttributes(
                    Arrays.asList(stringAttribute("network.local.address", "127.0.0.1"),
                        stringAttribute("otel.library.name", "zipkin2.reporter.otel"),
                        stringAttribute("otel.library.version", "0.0.1"),
                        stringAttribute("clnt/finagle.version", "6.45.0"),
                        stringAttribute("url.path", "/api"))
                )
                .addAllEvents(Arrays.asList(
                    Event.newBuilder().setTimeUnixNano(
                            TimeUnit.MILLISECONDS.toNanos(
                                Instant.ofEpochSecond(1472470996, 238_000_000).toEpochMilli()))
                        .setName("foo").build(),
                    Event.newBuilder().setTimeUnixNano(TimeUnit.MILLISECONDS.toNanos(
                            Instant.ofEpochSecond(1472470996, 403_000_000).toEpochMilli()))
                        .setName("bar").build()))
                .setStatus(Status.newBuilder().setCode(StatusCode.STATUS_CODE_OK).build())
                .build());
  }

  @Test
  void translate_missingName() {
    MutableSpan braveSpan =
        new MutableSpan(TraceContext.newBuilder().traceId(3).spanId(2).build(), null);
    TracesData translated = spanTranslator.translate(braveSpan);

    assertThat(firstSpan(translated).getName()).isEqualTo("unknown");
  }

  @Test
  void translate_emptyName() {
    MutableSpan braveSpan =
        new MutableSpan(TraceContext.newBuilder().traceId(3).spanId(2).build(), null);
    braveSpan.name("");
    TracesData translated = spanTranslator.translate(braveSpan);

    assertThat(firstSpan(translated).getName()).isEqualTo("unknown");
  }

  private static Span firstSpan(TracesData translated) {
    return translated.getResourceSpans(0).getScopeSpans(0).getSpans(0);
  }
}
