/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.reporter.otel.brave;

import brave.Tags;
import brave.handler.MutableSpan;
import brave.propagation.TraceContext;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Span.Event;
import io.opentelemetry.proto.trace.v1.Span.SpanKind;
import io.opentelemetry.proto.trace.v1.Status;
import io.opentelemetry.proto.trace.v1.Status.StatusCode;
import io.opentelemetry.proto.trace.v1.TracesData;
import io.opentelemetry.semconv.HttpAttributes;
import io.opentelemetry.semconv.NetworkAttributes;
import io.opentelemetry.semconv.UrlAttributes;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static zipkin2.reporter.otel.brave.SpanTranslator.DEFAULT_SPAN_NAME;
import static zipkin2.reporter.otel.brave.TagToAttribute.intAttribute;
import static zipkin2.reporter.otel.brave.TagToAttribute.stringAttribute;
import static zipkin2.reporter.otel.brave.TestObjects.clientSpan;

class SpanTranslatorTest {

  SpanTranslator spanTranslator = SpanTranslator.newBuilder()
      .errorTag(Tags.ERROR)
      .resourceAttributes(Collections.emptyMap())
      .instrumentationScope(BraveScope.instrumentationScope())
      .build();

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
                    Arrays.asList(stringAttribute(NetworkAttributes.NETWORK_LOCAL_ADDRESS.getKey(), "127.0.0.1"),
                        stringAttribute(NetworkAttributes.NETWORK_PEER_ADDRESS.getKey(), "192.168.99.101"),
                        intAttribute(NetworkAttributes.NETWORK_PEER_PORT.getKey(), 9000),
                        stringAttribute("peer.service", "backend"),
                        stringAttribute("clnt/finagle.version", "6.45.0"),
                        stringAttribute(UrlAttributes.URL_PATH.getKey(), "/api"),
                        stringAttribute("method", "GET"),
                        stringAttribute("status", "200"))
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

    io.opentelemetry.proto.common.v1.InstrumentationScope scope = implementationScope(translated);
    assertThat(scope.getName()).isEqualTo(BraveScope.NAME);
    assertThat(scope.getVersion()).isEqualTo(BraveScope.VERSION);
  }

  @Test
  void translate_missingName() {
    MutableSpan braveSpan =
        new MutableSpan(TraceContext.newBuilder().traceId(3).spanId(2).build(), null);
    TracesData translated = spanTranslator.translate(braveSpan);

    assertThat(firstSpan(translated).getName()).isEqualTo(DEFAULT_SPAN_NAME);

    io.opentelemetry.proto.common.v1.InstrumentationScope scope = implementationScope(translated);
    assertThat(scope.getName()).isEqualTo(BraveScope.NAME);
    assertThat(scope.getVersion()).isEqualTo(BraveScope.VERSION);
  }

  @Test
  void translate_emptyName() {
    MutableSpan braveSpan =
        new MutableSpan(TraceContext.newBuilder().traceId(3).spanId(2).build(), null);
    braveSpan.name("");
    TracesData translated = spanTranslator.translate(braveSpan);

    assertThat(firstSpan(translated).getName()).isEqualTo(DEFAULT_SPAN_NAME);

    io.opentelemetry.proto.common.v1.InstrumentationScope scope = implementationScope(translated);
    assertThat(scope.getName()).isEqualTo(BraveScope.NAME);
    assertThat(scope.getVersion()).isEqualTo(BraveScope.VERSION);
  }

  @Test
  void custom_implementationScope() {
    MutableSpan braveSpan = clientSpan();
    SpanTranslator translator = SpanTranslator.newBuilder()
        .errorTag(Tags.ERROR)
        .resourceAttributes(Collections.emptyMap())
        .instrumentationScope(new zipkin2.reporter.otel.brave.InstrumentationScope("com.example.app", "3.3.5"))
        .build();

    TracesData tracesData = translator.translate(braveSpan);
    io.opentelemetry.proto.common.v1.InstrumentationScope scope = implementationScope(tracesData);
    assertThat(scope.getName()).isEqualTo("com.example.app");
    assertThat(scope.getVersion()).isEqualTo("3.3.5");
  }

  @Test
  void custom_attributesMapping() {
    MutableSpan braveSpan = clientSpan();
    Map<String, String> tagToAttributes = new LinkedHashMap<>();
    tagToAttributes.put("method", HttpAttributes.HTTP_REQUEST_METHOD.getKey());
    tagToAttributes.put("status", HttpAttributes.HTTP_RESPONSE_STATUS_CODE.getKey());
    SpanTranslator translator = SpanTranslator.newBuilder()
        .errorTag(Tags.ERROR)
        .resourceAttributes(Collections.emptyMap())
        .instrumentationScope(BraveScope.instrumentationScope())
        .tagToAttributes(TagToAttributes.newBuilder().withDefaults().tagToAttributes(tagToAttributes).build())
        .build();
    TracesData tracesData = translator.translate(braveSpan);
    assertThat(firstSpan(tracesData))
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
                    Arrays.asList(stringAttribute(NetworkAttributes.NETWORK_LOCAL_ADDRESS.getKey(), "127.0.0.1"),
                        stringAttribute(NetworkAttributes.NETWORK_PEER_ADDRESS.getKey(), "192.168.99.101"),
                        intAttribute(NetworkAttributes.NETWORK_PEER_PORT.getKey(), 9000),
                        stringAttribute("peer.service", "backend"),
                        stringAttribute("clnt/finagle.version", "6.45.0"),
                        stringAttribute(UrlAttributes.URL_PATH.getKey(), "/api"),
                        stringAttribute(HttpAttributes.HTTP_REQUEST_METHOD.getKey(), "GET"),
                        stringAttribute(HttpAttributes.HTTP_RESPONSE_STATUS_CODE.getKey(), "200"))
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

  private static io.opentelemetry.proto.common.v1.InstrumentationScope implementationScope(TracesData translated) {
    assertThat(translated.getResourceSpansCount()).isEqualTo(1);
    ResourceSpans resourceSpans = translated.getResourceSpans(0);
    assertThat(resourceSpans.getScopeSpansCount()).isEqualTo(1);
    ScopeSpans scopeSpans = resourceSpans.getScopeSpans(0);
    return scopeSpans.getScope();
  }

  private static Span firstSpan(TracesData translated) {
    assertThat(translated.getResourceSpansCount()).isEqualTo(1);
    ResourceSpans resourceSpans = translated.getResourceSpans(0);
    assertThat(resourceSpans.getScopeSpansCount()).isEqualTo(1);
    ScopeSpans scopeSpans = resourceSpans.getScopeSpans(0);
    assertThat(scopeSpans.getSpansCount()).isEqualTo(1);
    return scopeSpans.getSpans(0);
  }
}
