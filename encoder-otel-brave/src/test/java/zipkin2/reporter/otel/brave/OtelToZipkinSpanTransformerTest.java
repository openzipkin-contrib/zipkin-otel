/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.reporter.otel.brave;

import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.assertj.core.api.Assertions.assertThat;

import brave.Span;
import brave.Span.Kind;
import brave.Tags;
import brave.handler.MutableSpan;
import com.google.protobuf.ByteString;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span.Event;
import io.opentelemetry.proto.trace.v1.Span.SpanKind;
import io.opentelemetry.proto.trace.v1.Status;
import io.opentelemetry.proto.trace.v1.Status.StatusCode;
import io.opentelemetry.proto.trace.v1.TracesData;
import io.opentelemetry.sdk.resources.Resource;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

// Adopted from https://github.com/open-telemetry/opentelemetry-java/blob/v1.39.0/exporters/zipkin/src/test/java/io/opentelemetry/exporter/zipkin/OtelToZipkinSpanTransformerTest.java
class OtelToZipkinSpanTransformerTest {

  static final String TRACE_ID = "d239036e7d5cec116b562147388b35bf";

  static final String SPAN_ID = "9cc1e3049173be09";

  static final String PARENT_SPAN_ID = "8b03ab423da481c5";

  private OtelEncoder transformer;

  @BeforeEach
  void setup() {
    transformer = new OtelEncoder(Tags.ERROR);
  }

  @Test
  void generateSpan_remoteParent() {
    MutableSpan data = span(Kind.SERVER);

    assertThat(transformer.translate(data))
        .isEqualTo(
            TracesData.newBuilder().addResourceSpans(ResourceSpans
                    .newBuilder()
                    .setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder().addAttributes(
                        KeyValue.newBuilder().setKey("service.name").setValue(
                                AnyValue.newBuilder().setStringValue("tweetiebird").build())
                            .build()
                    ).build())
                    .addScopeSpans(ScopeSpans.newBuilder()
                        .setScope(InstrumentationScope.newBuilder()
                             .setName("zipkin2.reporter.otel").setVersion("0.0.1").build())
                        .addSpans(io.opentelemetry.proto.trace.v1.Span.newBuilder()
                            .setSpanId(ByteString.fromHex(data.id()))
                            .setTraceId(ByteString.fromHex(data.traceId()))
                            .setParentSpanId(ByteString.fromHex(data.parentId()))
                            .setName(data.name())
                            .setStartTimeUnixNano(1505855794194009000L)
                            .setEndTimeUnixNano(1505855799465726000L)
                            .addAttributes(KeyValue.newBuilder().setKey("otel.library.name").setValue(
                                    AnyValue.newBuilder().setStringValue("zipkin2.reporter.otel").build())
                                .build())
                            .addAttributes(
                                KeyValue.newBuilder().setKey("otel.library.version").setValue(
                                    AnyValue.newBuilder().setStringValue("0.0.1").build()).build())
                            .setKind(io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_SERVER)
                            .setStatus(
                                Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
                            .addEvents(Event.newBuilder().setName("\"RECEIVED\":{}").setTimeUnixNano(1505855799433901000L).build())
                            .addEvents(Event.newBuilder().setName("\"SENT\":{}").setTimeUnixNano(1505855799459486000L).build())
                            .build())
                        .build())
                    .build())
                .build());
  }

  @Test
  void generateSpan_subMicroDurations() {
    MutableSpan data = span(Kind.SERVER);
    data.startTimestamp(TimeUnit.NANOSECONDS.toMicros(1505855794_194009601L));
    data.finishTimestamp(TimeUnit.NANOSECONDS.toMicros(1505855794_194009999L));

    assertThat(transformer.translate(data))
        .isEqualTo(
            TracesData.newBuilder().addResourceSpans(ResourceSpans
                    .newBuilder()
                    .setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder().addAttributes(
                        KeyValue.newBuilder().setKey("service.name").setValue(
                                AnyValue.newBuilder().setStringValue("tweetiebird").build())
                            .build()
                    ).build())
                    .addScopeSpans(ScopeSpans.newBuilder()
                        .setScope(InstrumentationScope.newBuilder()
                             .setName("zipkin2.reporter.otel").setVersion("0.0.1").build())
                        .addSpans(io.opentelemetry.proto.trace.v1.Span.newBuilder()
                            .setSpanId(ByteString.fromHex(data.id()))
                            .setTraceId(ByteString.fromHex(data.traceId()))
                            .setParentSpanId(ByteString.fromHex(data.parentId()))
                            .setName(data.name())
                            .setStartTimeUnixNano(1505855794_194009000L) // We lose precision
                            .setEndTimeUnixNano(1505855794_194009000L) // We lose precision
                            .addAttributes(KeyValue.newBuilder().setKey("otel.library.name").setValue(
                                    AnyValue.newBuilder().setStringValue("zipkin2.reporter.otel").build())
                                .build())
                            .addAttributes(
                                KeyValue.newBuilder().setKey("otel.library.version").setValue(
                                    AnyValue.newBuilder().setStringValue("0.0.1").build()).build())
                            .setKind(io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_SERVER)
                            .setStatus(
                                Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
                            .addEvents(Event.newBuilder().setName("\"RECEIVED\":{}").setTimeUnixNano(1505855799433901000L).build())
                            .addEvents(Event.newBuilder().setName("\"SENT\":{}").setTimeUnixNano(1505855799459486000L).build())
                            .build())
                        .build())
                    .build())
                .build());
  }

  @Test
  void generateSpan_ServerKind() {
    MutableSpan data = span(Kind.SERVER);

    assertThat(transformer.translate(data))
        .isEqualTo(
            TracesData.newBuilder().addResourceSpans(ResourceSpans
                    .newBuilder()
                    .setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder().addAttributes(
                        KeyValue.newBuilder().setKey("service.name").setValue(
                                AnyValue.newBuilder().setStringValue("tweetiebird").build())
                            .build()
                    ).build())
                    .addScopeSpans(ScopeSpans.newBuilder()
                        .setScope(InstrumentationScope.newBuilder()
                             .setName("zipkin2.reporter.otel").setVersion("0.0.1").build())
                        .addSpans(io.opentelemetry.proto.trace.v1.Span.newBuilder()
                            .setSpanId(ByteString.fromHex(data.id()))
                            .setTraceId(ByteString.fromHex(data.traceId()))
                            .setParentSpanId(ByteString.fromHex(data.parentId()))
                            .setName(data.name())
                            .setStartTimeUnixNano(1505855794194009000L)
                            .setEndTimeUnixNano(1505855799465726000L)
                            .addAttributes(KeyValue.newBuilder().setKey("otel.library.name").setValue(
                                    AnyValue.newBuilder().setStringValue("zipkin2.reporter.otel").build())
                                .build())
                            .addAttributes(
                                KeyValue.newBuilder().setKey("otel.library.version").setValue(
                                    AnyValue.newBuilder().setStringValue("0.0.1").build()).build())
                            .setKind(io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_SERVER)
                            .setStatus(
                                Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
                            .addEvents(Event.newBuilder().setName("\"RECEIVED\":{}").setTimeUnixNano(1505855799433901000L).build())
                            .addEvents(Event.newBuilder().setName("\"SENT\":{}").setTimeUnixNano(1505855799459486000L).build())
                            .build())
                        .build())
                    .build())
                .build());
  }

  @Test
  void generateSpan_ClientKind() {
    MutableSpan data = span(Kind.CLIENT);

    assertThat(transformer.translate(data))
        .isEqualTo(
            TracesData.newBuilder().addResourceSpans(ResourceSpans
                    .newBuilder()
                    .setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder().addAttributes(
                        KeyValue.newBuilder().setKey("service.name").setValue(
                                AnyValue.newBuilder().setStringValue("tweetiebird").build())
                            .build()
                    ).build())
                    .addScopeSpans(ScopeSpans.newBuilder()
                        .setScope(InstrumentationScope.newBuilder()
                             .setName("zipkin2.reporter.otel").setVersion("0.0.1").build())
                        .addSpans(io.opentelemetry.proto.trace.v1.Span.newBuilder()
                            .setSpanId(ByteString.fromHex(data.id()))
                            .setTraceId(ByteString.fromHex(data.traceId()))
                            .setParentSpanId(ByteString.fromHex(data.parentId()))
                            .setName(data.name())
                            .setStartTimeUnixNano(1505855794194009000L)
                            .setEndTimeUnixNano(1505855799465726000L)
                            .addAttributes(KeyValue.newBuilder().setKey("otel.library.name").setValue(
                                    AnyValue.newBuilder().setStringValue("zipkin2.reporter.otel").build())
                                .build())
                            .addAttributes(
                                KeyValue.newBuilder().setKey("otel.library.version").setValue(
                                    AnyValue.newBuilder().setStringValue("0.0.1").build()).build())
                            .setKind(io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_CLIENT)
                            .setStatus(
                                Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
                            .addEvents(Event.newBuilder().setName("\"RECEIVED\":{}").setTimeUnixNano(1505855799433901000L).build())
                            .addEvents(Event.newBuilder().setName("\"SENT\":{}").setTimeUnixNano(1505855799459486000L).build())
                            .build())
                        .build())
                    .build())
                .build());
  }

  @Test
  void generateSpan_InternalKind() {
    MutableSpan data = span(null);

    assertThat(transformer.translate(data))
        .isEqualTo(
            TracesData.newBuilder().addResourceSpans(ResourceSpans
                    .newBuilder()
                    .setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder().addAttributes(
                        KeyValue.newBuilder().setKey("service.name").setValue(
                                AnyValue.newBuilder().setStringValue("tweetiebird").build())
                            .build()
                    ).build())
                    .addScopeSpans(ScopeSpans.newBuilder()
                        .setScope(InstrumentationScope.newBuilder()
                             .setName("zipkin2.reporter.otel").setVersion("0.0.1").build())
                        .addSpans(io.opentelemetry.proto.trace.v1.Span.newBuilder()
                            .setSpanId(ByteString.fromHex(data.id()))
                            .setTraceId(ByteString.fromHex(data.traceId()))
                            .setParentSpanId(ByteString.fromHex(data.parentId()))
                            .setName(data.name())
                            .setKind(SpanKind.SPAN_KIND_INTERNAL)
                            .setStartTimeUnixNano(1505855794194009000L)
                            .setEndTimeUnixNano(1505855799465726000L)
                            .addAttributes(KeyValue.newBuilder().setKey("otel.library.name").setValue(
                                    AnyValue.newBuilder().setStringValue("zipkin2.reporter.otel").build())
                                .build())
                            .addAttributes(
                                KeyValue.newBuilder().setKey("otel.library.version").setValue(
                                    AnyValue.newBuilder().setStringValue("0.0.1").build()).build())
                            .setKind(io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_INTERNAL)
                            .setStatus(
                                Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
                            .addEvents(Event.newBuilder().setName("\"RECEIVED\":{}").setTimeUnixNano(1505855799433901000L).build())
                            .addEvents(Event.newBuilder().setName("\"SENT\":{}").setTimeUnixNano(1505855799459486000L).build())
                            .build())
                        .build())
                    .build())
                .build());
  }

  @Test
  void generateSpan_ConsumeKind() {
    MutableSpan data = span(Kind.CONSUMER);

    assertThat(transformer.translate(data))
        .isEqualTo(
            TracesData.newBuilder().addResourceSpans(ResourceSpans
                    .newBuilder()
                    .setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder().addAttributes(
                        KeyValue.newBuilder().setKey("service.name").setValue(
                                AnyValue.newBuilder().setStringValue("tweetiebird").build())
                            .build()
                    ).build())
                    .addScopeSpans(ScopeSpans.newBuilder()
                        .setScope(InstrumentationScope.newBuilder()
                             .setName("zipkin2.reporter.otel").setVersion("0.0.1").build())
                        .addSpans(io.opentelemetry.proto.trace.v1.Span.newBuilder()
                            .setSpanId(ByteString.fromHex(data.id()))
                            .setTraceId(ByteString.fromHex(data.traceId()))
                            .setParentSpanId(ByteString.fromHex(data.parentId()))
                            .setName(data.name())
                            .setStartTimeUnixNano(1505855794194009000L)
                            .setEndTimeUnixNano(1505855799465726000L)
                            .addAttributes(KeyValue.newBuilder().setKey("otel.library.name").setValue(
                                    AnyValue.newBuilder().setStringValue("zipkin2.reporter.otel").build())
                                .build())
                            .addAttributes(
                                KeyValue.newBuilder().setKey("otel.library.version").setValue(
                                    AnyValue.newBuilder().setStringValue("0.0.1").build()).build())
                            .setKind(io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_CONSUMER)
                            .setStatus(
                                Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
                            .addEvents(Event.newBuilder().setName("\"RECEIVED\":{}").setTimeUnixNano(1505855799433901000L).build())
                            .addEvents(Event.newBuilder().setName("\"SENT\":{}").setTimeUnixNano(1505855799459486000L).build())
                            .build())
                        .build())
                    .build())
                .build());
  }

  @Test
  void generateSpan_ProducerKind() {
    MutableSpan data = span(Kind.PRODUCER);

    assertThat(transformer.translate(data))
        .isEqualTo(
            TracesData.newBuilder().addResourceSpans(ResourceSpans
                    .newBuilder()
                    .setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder().addAttributes(
                        KeyValue.newBuilder().setKey("service.name").setValue(
                                AnyValue.newBuilder().setStringValue("tweetiebird").build())
                            .build()
                    ).build())
                    .addScopeSpans(ScopeSpans.newBuilder()
                        .setScope(InstrumentationScope.newBuilder()
                             .setName("zipkin2.reporter.otel").setVersion("0.0.1").build())
                        .addSpans(io.opentelemetry.proto.trace.v1.Span.newBuilder()
                            .setSpanId(ByteString.fromHex(data.id()))
                            .setTraceId(ByteString.fromHex(data.traceId()))
                            .setParentSpanId(ByteString.fromHex(data.parentId()))
                            .setName(data.name())
                            .setStartTimeUnixNano(1505855794194009000L)
                            .setEndTimeUnixNano(1505855799465726000L)
                            .addAttributes(KeyValue.newBuilder().setKey("otel.library.name").setValue(
                                    AnyValue.newBuilder().setStringValue("zipkin2.reporter.otel").build())
                                .build())
                            .addAttributes(
                                KeyValue.newBuilder().setKey("otel.library.version").setValue(
                                    AnyValue.newBuilder().setStringValue("0.0.1").build()).build())
                            .setKind(io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_PRODUCER)
                            .setStatus(
                                Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
                            .addEvents(Event.newBuilder().setName("\"RECEIVED\":{}").setTimeUnixNano(1505855799433901000L).build())
                            .addEvents(Event.newBuilder().setName("\"SENT\":{}").setTimeUnixNano(1505855799459486000L).build())
                            .build())
                        .build())
                    .build())
                .build());
  }

  @Test
  void generateSpan_ResourceServiceNameMapping() {
    MutableSpan data = span(Kind.PRODUCER);
    data.localServiceName("super-zipkin-service");

    assertThat(transformer.translate(data))
        .isEqualTo(
            TracesData.newBuilder().addResourceSpans(ResourceSpans
                    .newBuilder()
                    .setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder().addAttributes(
                        KeyValue.newBuilder().setKey("service.name").setValue(
                                AnyValue.newBuilder().setStringValue("super-zipkin-service").build())
                            .build()
                    ).build())
                    .addScopeSpans(ScopeSpans.newBuilder()
                        .setScope(InstrumentationScope.newBuilder()
                             .setName("zipkin2.reporter.otel").setVersion("0.0.1").build())
                        .addSpans(io.opentelemetry.proto.trace.v1.Span.newBuilder()
                            .setSpanId(ByteString.fromHex(data.id()))
                            .setTraceId(ByteString.fromHex(data.traceId()))
                            .setParentSpanId(ByteString.fromHex(data.parentId()))
                            .setName(data.name())
                            .setStartTimeUnixNano(1505855794194009000L)
                            .setEndTimeUnixNano(1505855799465726000L)
                            .addAttributes(KeyValue.newBuilder().setKey("otel.library.name").setValue(
                                    AnyValue.newBuilder().setStringValue("zipkin2.reporter.otel").build())
                                .build())
                            .addAttributes(
                                KeyValue.newBuilder().setKey("otel.library.version").setValue(
                                    AnyValue.newBuilder().setStringValue("0.0.1").build()).build())
                            .setKind(SpanKind.SPAN_KIND_PRODUCER)
                            .setStatus(
                                Status.newBuilder().setCode(StatusCode.STATUS_CODE_OK).build())
                            .addEvents(Event.newBuilder().setName("\"RECEIVED\":{}").setTimeUnixNano(1505855799433901000L).build())
                            .addEvents(Event.newBuilder().setName("\"SENT\":{}").setTimeUnixNano(1505855799459486000L).build())
                            .build())
                        .build())
                    .build())
                .build());
  }

  @Test
  void generateSpan_defaultResourceServiceName() {
    MutableSpan data = span(Kind.PRODUCER);
    data.localServiceName(null);

    assertThat(transformer.translate(data))
        .isEqualTo(
            TracesData.newBuilder().addResourceSpans(ResourceSpans
                    .newBuilder()
                    .setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder().addAttributes(
                        KeyValue.newBuilder().setKey("service.name").setValue(AnyValue.newBuilder()
                                .setStringValue(
                                    Resource.getDefault().getAttribute(stringKey("service.name"))).build())
                            .build()
                    ).build())
                    .addScopeSpans(ScopeSpans.newBuilder()
                        .setScope(InstrumentationScope.newBuilder()
                             .setName("zipkin2.reporter.otel").setVersion("0.0.1").build())
                        .addSpans(io.opentelemetry.proto.trace.v1.Span.newBuilder()
                            .setSpanId(ByteString.fromHex(data.id()))
                            .setTraceId(ByteString.fromHex(data.traceId()))
                            .setParentSpanId(ByteString.fromHex(data.parentId()))
                            .setName(data.name())
                            .setStartTimeUnixNano(1505855794194009000L)
                            .setEndTimeUnixNano(1505855799465726000L)
                            .addAttributes(KeyValue.newBuilder().setKey("otel.library.name").setValue(
                                    AnyValue.newBuilder().setStringValue("zipkin2.reporter.otel").build())
                                .build())
                            .addAttributes(
                                KeyValue.newBuilder().setKey("otel.library.version").setValue(
                                    AnyValue.newBuilder().setStringValue("0.0.1").build()).build())
                            .setKind(io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_PRODUCER)
                            .setStatus(
                                Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
                            .addEvents(Event.newBuilder().setName("\"RECEIVED\":{}").setTimeUnixNano(1505855799433901000L).build())
                            .addEvents(Event.newBuilder().setName("\"SENT\":{}").setTimeUnixNano(1505855799459486000L).build())
                            .build())
                        .build())
                    .build())
                .build());
  }

  @ParameterizedTest
  @EnumSource(
      value = Kind.class,
      names = {"CLIENT", "PRODUCER"})
  void generateSpan_RemoteEndpointMapping(Kind spanKind) {
    MutableSpan data = new MutableSpan();
    data.remoteServiceName("remote-test-service");
    data.remoteIp("8.8.8.8");
    data.remotePort(42);
    data.kind(spanKind);

    assertThat(transformer.translate(data))
        .isEqualTo(
            TracesData.newBuilder().addResourceSpans(ResourceSpans
                    .newBuilder()
                    .setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder().addAttributes(
                        KeyValue.newBuilder().setKey("service.name").setValue(AnyValue.newBuilder()
                                .setStringValue(
                                    Resource.getDefault().getAttribute(stringKey("service.name"))).build())
                            .build()
                    ).build())
                    .addScopeSpans(ScopeSpans.newBuilder()
                        .setScope(InstrumentationScope.newBuilder()
                             .setName("zipkin2.reporter.otel").setVersion("0.0.1").build())
                        .addSpans(io.opentelemetry.proto.trace.v1.Span.newBuilder()
                            .setSpanId(ByteString.fromHex(SpanContext.getInvalid().getSpanId()))
                            .setTraceId(ByteString.fromHex(SpanContext.getInvalid().getTraceId()))
                            .setName("unknown")
                            .setKind(
                                toSpanKind(spanKind))
                            .addAttributes(KeyValue.newBuilder().setKey("net.sock.peer.name").setValue(
                                    AnyValue.newBuilder().setStringValue("remote-test-service").build())
                                .build())
                            .addAttributes(KeyValue.newBuilder().setKey("net.sock.peer.addr")
                                .setValue(AnyValue.newBuilder().setStringValue("8.8.8.8").build())
                                .build())
                            .addAttributes(KeyValue.newBuilder().setKey("net.sock.peer.port")
                                .setValue(AnyValue.newBuilder().setIntValue(42).build()).build())
                            .addAttributes(KeyValue.newBuilder().setKey("otel.library.name").setValue(
                                    AnyValue.newBuilder().setStringValue("zipkin2.reporter.otel").build())
                                .build())
                            .addAttributes(
                                KeyValue.newBuilder().setKey("otel.library.version").setValue(
                                    AnyValue.newBuilder().setStringValue("0.0.1").build()).build())
                            .setStatus(
                                Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
                            .build())
                        .build())
                    .build())
                .build());
  }

  @ParameterizedTest
  @EnumSource(
      value = Kind.class,
      names = {"SERVER", "CONSUMER"})
  void generateSpan_RemoteEndpointMappingWhenKindIsNotClientOrProducer(Kind spanKind) {
    MutableSpan data = new MutableSpan();
    data.remoteServiceName("remote-test-service");
    data.remoteIp("8.8.8.8");
    data.remotePort(42);
    data.kind(spanKind);

    assertThat(transformer.translate(data))
        .isEqualTo(
            TracesData.newBuilder().addResourceSpans(ResourceSpans
                    .newBuilder()
                    .setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder().addAttributes(
                        KeyValue.newBuilder().setKey("service.name").setValue(AnyValue.newBuilder()
                                .setStringValue(
                                    Resource.getDefault().getAttribute(stringKey("service.name"))).build())
                            .build()
                    ).build())
                    .addScopeSpans(ScopeSpans.newBuilder()
                        .setScope(InstrumentationScope.newBuilder()
                             .setName("zipkin2.reporter.otel").setVersion("0.0.1").build())
                        .addSpans(io.opentelemetry.proto.trace.v1.Span.newBuilder()
                            .setSpanId(ByteString.fromHex(SpanContext.getInvalid().getSpanId()))
                            .setTraceId(ByteString.fromHex(SpanContext.getInvalid().getTraceId()))
                            .setName("unknown")
                            .setKind(
                                toSpanKind(spanKind))
                            .addAttributes(KeyValue.newBuilder().setKey("net.sock.peer.name").setValue(
                                    AnyValue.newBuilder().setStringValue("remote-test-service").build())
                                .build())
                            .addAttributes(KeyValue.newBuilder().setKey("net.sock.peer.addr")
                                .setValue(AnyValue.newBuilder().setStringValue("8.8.8.8").build())
                                .build())
                            .addAttributes(KeyValue.newBuilder().setKey("net.sock.peer.port")
                                .setValue(AnyValue.newBuilder().setIntValue(42).build()).build())
                            .addAttributes(KeyValue.newBuilder().setKey("otel.library.name").setValue(
                                    AnyValue.newBuilder().setStringValue("zipkin2.reporter.otel").build())
                                .build())
                            .addAttributes(
                                KeyValue.newBuilder().setKey("otel.library.version").setValue(
                                    AnyValue.newBuilder().setStringValue("0.0.1").build()).build())
                            .setStatus(
                                Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
                            .build())
                        .build())
                    .build())
                .build());
  }

  @ParameterizedTest
  @EnumSource(
      value = Kind.class,
      names = {"CLIENT", "PRODUCER"})
  void generateSpan_RemoteEndpointMappingWhenServiceNameIsMissing(Kind spanKind) {
    MutableSpan data = new MutableSpan();
    data.remoteIp("8.8.8.8");
    data.remotePort(42);
    data.kind(spanKind);

    assertThat(transformer.translate(data))
        .isEqualTo(
            TracesData.newBuilder().addResourceSpans(ResourceSpans
                    .newBuilder()
                    .setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder().addAttributes(
                        KeyValue.newBuilder().setKey("service.name").setValue(AnyValue.newBuilder()
                                .setStringValue(
                                    Resource.getDefault().getAttribute(stringKey("service.name"))).build())
                            .build()
                    ).build())
                    .addScopeSpans(ScopeSpans.newBuilder()
                        .setScope(InstrumentationScope.newBuilder()
                            .setName("zipkin2.reporter.otel").setVersion("0.0.1").build())
                        .addSpans(io.opentelemetry.proto.trace.v1.Span.newBuilder()
                            .setSpanId(ByteString.fromHex(SpanContext.getInvalid().getSpanId()))
                            .setTraceId(ByteString.fromHex(SpanContext.getInvalid().getTraceId()))
                            .setName("unknown")
                            .setKind(
                                toSpanKind(spanKind))
                            .addAttributes(KeyValue.newBuilder().setKey("net.sock.peer.addr")
                                .setValue(AnyValue.newBuilder().setStringValue("8.8.8.8").build())
                                .build())
                            .addAttributes(KeyValue.newBuilder().setKey("net.sock.peer.port")
                                .setValue(AnyValue.newBuilder().setIntValue(42).build()).build())
                            .addAttributes(KeyValue.newBuilder().setKey("otel.library.name").setValue(
                                    AnyValue.newBuilder().setStringValue("zipkin2.reporter.otel").build())
                                .build())
                            .addAttributes(
                                KeyValue.newBuilder().setKey("otel.library.version").setValue(
                                    AnyValue.newBuilder().setStringValue("0.0.1").build()).build())
                            .setStatus(
                                Status.newBuilder().setCode(StatusCode.STATUS_CODE_OK).build())
                            .build())
                        .build())
                    .build())
                .build());
  }

  @ParameterizedTest
  @EnumSource(
      value = Kind.class,
      names = {"CLIENT", "PRODUCER"})
  void generateSpan_RemoteEndpointMappingWhenPortIsMissing(Kind spanKind) {
    MutableSpan data = new MutableSpan();
    data.remoteServiceName("remote-test-service");
    data.remoteIp("8.8.8.8");
    data.kind(spanKind);

    assertThat(transformer.translate(data))
        .isEqualTo(
            TracesData.newBuilder().addResourceSpans(ResourceSpans
                    .newBuilder()
                    .setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder().addAttributes(
                        KeyValue.newBuilder().setKey("service.name").setValue(AnyValue.newBuilder()
                                .setStringValue(
                                    Resource.getDefault().getAttribute(stringKey("service.name"))).build())
                            .build()
                    ).build())
                    .addScopeSpans(ScopeSpans.newBuilder()
                        .setScope(InstrumentationScope.newBuilder()
                             .setName("zipkin2.reporter.otel").setVersion("0.0.1").build())
                        .addSpans(io.opentelemetry.proto.trace.v1.Span.newBuilder()
                            .setSpanId(ByteString.fromHex(SpanContext.getInvalid().getSpanId()))
                            .setTraceId(ByteString.fromHex(SpanContext.getInvalid().getTraceId()))
                            .setName("unknown")
                            .setKind(
                                toSpanKind(spanKind))
                            .addAttributes(KeyValue.newBuilder().setKey("net.sock.peer.name").setValue(
                                    AnyValue.newBuilder().setStringValue("remote-test-service").build())
                                .build())
                            .addAttributes(KeyValue.newBuilder().setKey("net.sock.peer.addr")
                                .setValue(AnyValue.newBuilder().setStringValue("8.8.8.8").build())
                                .build())
                            .addAttributes(KeyValue.newBuilder().setKey("otel.library.name").setValue(
                                    AnyValue.newBuilder().setStringValue("zipkin2.reporter.otel").build())
                                .build())
                            .addAttributes(
                                KeyValue.newBuilder().setKey("otel.library.version").setValue(
                                    AnyValue.newBuilder().setStringValue("0.0.1").build()).build())
                            .setStatus(
                                Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
                            .build())
                        .build())
                    .build())
                .build());
  }

  @ParameterizedTest
  @EnumSource(
      value = Kind.class,
      names = {"CLIENT", "PRODUCER"})
  void generateSpan_RemoteEndpointMappingWhenIpAndPortAreMissing(Kind spanKind) {
    MutableSpan data = new MutableSpan();
    data.remoteServiceName("remote-test-service");
    data.kind(spanKind);

    assertThat(transformer.translate(data))
        .isEqualTo(
            TracesData.newBuilder().addResourceSpans(ResourceSpans
                    .newBuilder()
                    .setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder().addAttributes(
                        KeyValue.newBuilder().setKey("service.name").setValue(AnyValue.newBuilder()
                                .setStringValue(
                                    Resource.getDefault().getAttribute(stringKey("service.name"))).build())
                            .build()
                    ).build())
                    .addScopeSpans(ScopeSpans.newBuilder()
                        .setScope(InstrumentationScope.newBuilder()
                             .setName("zipkin2.reporter.otel").setVersion("0.0.1").build())
                        .addSpans(io.opentelemetry.proto.trace.v1.Span.newBuilder()
                            .setSpanId(ByteString.fromHex(SpanContext.getInvalid().getSpanId()))
                            .setTraceId(ByteString.fromHex(SpanContext.getInvalid().getTraceId()))
                            .setName("unknown")
                            .setKind(
                                toSpanKind(spanKind))
                            .addAttributes(KeyValue.newBuilder().setKey("net.sock.peer.name").setValue(
                                    AnyValue.newBuilder().setStringValue("remote-test-service").build())
                                .build())
                            .addAttributes(KeyValue.newBuilder().setKey("otel.library.name").setValue(
                                    AnyValue.newBuilder().setStringValue("zipkin2.reporter.otel").build())
                                .build())
                            .addAttributes(
                                KeyValue.newBuilder().setKey("otel.library.version").setValue(
                                    AnyValue.newBuilder().setStringValue("0.0.1").build()).build())
                            .setStatus(
                                Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
                            .build())
                        .build())
                    .build())
                .build());
  }

  @Test
  void generateSpan_WithAttributes() {
    MutableSpan data = new MutableSpan();
    data.kind(Kind.CLIENT);
    data.tag("string", "string value");
    data.tag("boolean", "false");
    data.tag("long", "9999");
    data.tag("double", "222.333");
    data.tag("booleanArray", "true,false");
    data.tag("stringArray", "Hello");
    data.tag("doubleArray", "32.33,-98.3");
    data.tag("longArray", "33,999");

    assertThat(transformer.translate(data))
        .isEqualTo(
            TracesData.newBuilder().addResourceSpans(ResourceSpans
                    .newBuilder()
                    .setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder().addAttributes(
                        KeyValue.newBuilder().setKey("service.name").setValue(AnyValue.newBuilder()
                                .setStringValue(
                                    Resource.getDefault().getAttribute(stringKey("service.name"))).build())
                            .build()
                    ).build())
                    .addScopeSpans(ScopeSpans.newBuilder()
                        .setScope(InstrumentationScope.newBuilder()
                             .setName("zipkin2.reporter.otel").setVersion("0.0.1").build())
                        .addSpans(io.opentelemetry.proto.trace.v1.Span.newBuilder()
                            .setSpanId(ByteString.fromHex(SpanContext.getInvalid().getSpanId()))
                            .setTraceId(ByteString.fromHex(SpanContext.getInvalid().getTraceId()))
                            .setName("unknown")
                            .setKind(
                                toSpanKind(Kind.CLIENT))
                            .addAttributes(KeyValue.newBuilder().setKey("otel.library.name").setValue(
                                    AnyValue.newBuilder().setStringValue("zipkin2.reporter.otel").build())
                                .build())
                            .addAttributes(
                                KeyValue.newBuilder().setKey("otel.library.version").setValue(
                                    AnyValue.newBuilder().setStringValue("0.0.1").build()).build())
                            .addAttributes(KeyValue.newBuilder().setKey("string").setValue(
                                AnyValue.newBuilder().setStringValue("string value").build()).build())
                            .addAttributes(KeyValue.newBuilder().setKey("boolean").setValue(
                                AnyValue.newBuilder().setStringValue("false").build()).build())
                            .addAttributes(KeyValue.newBuilder().setKey("long").setValue(
                                AnyValue.newBuilder().setStringValue("9999").build()).build())
                            .addAttributes(KeyValue.newBuilder().setKey("double").setValue(
                                AnyValue.newBuilder().setStringValue("222.333").build()).build())
                            .addAttributes(KeyValue.newBuilder().setKey("booleanArray").setValue(
                                AnyValue.newBuilder().setStringValue("true,false").build()).build())
                            .addAttributes(KeyValue.newBuilder().setKey("stringArray").setValue(
                                AnyValue.newBuilder().setStringValue("Hello").build()).build())
                            .addAttributes(KeyValue.newBuilder().setKey("doubleArray").setValue(
                                AnyValue.newBuilder().setStringValue("32.33,-98.3").build()).build())
                            .addAttributes(KeyValue.newBuilder().setKey("longArray").setValue(
                                AnyValue.newBuilder().setStringValue("33,999").build()).build())
                            .setStatus(
                                Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
                            .build())
                        .build())
                    .build())
                .build());
  }

  @Test
  void generateSpan_WithInstrumentationLibraryInfo() {
    MutableSpan data = new MutableSpan();
    data.kind(Kind.CLIENT);

    assertThat(transformer.translate(data))
        .isEqualTo(
            TracesData.newBuilder().addResourceSpans(ResourceSpans
                    .newBuilder()
                    .setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder().addAttributes(
                        KeyValue.newBuilder().setKey("service.name").setValue(AnyValue.newBuilder()
                                .setStringValue(
                                    Resource.getDefault().getAttribute(stringKey("service.name"))).build())
                            .build()
                    ).build())
                    .addScopeSpans(ScopeSpans.newBuilder()
                        .setScope(InstrumentationScope.newBuilder()
                             .setName("zipkin2.reporter.otel").setVersion("0.0.1").build())
                        .addSpans(io.opentelemetry.proto.trace.v1.Span.newBuilder()
                            .setSpanId(ByteString.fromHex(SpanContext.getInvalid().getSpanId()))
                            .setTraceId(ByteString.fromHex(SpanContext.getInvalid().getTraceId()))
                            .setName("unknown")
                            .setKind(
                                toSpanKind(Kind.CLIENT))
                            .addAttributes(KeyValue.newBuilder().setKey("otel.library.name").setValue(
                                    AnyValue.newBuilder().setStringValue("zipkin2.reporter.otel").build())
                                .build())
                            .addAttributes(
                                KeyValue.newBuilder().setKey("otel.library.version").setValue(
                                    AnyValue.newBuilder().setStringValue("0.0.1").build()).build())
                            .setStatus(
                                Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
                            .build())
                        .build())
                    .build())
                .build());
  }

  @Test
  void generateSpan_AlreadyHasHttpStatusInfo() {
    MutableSpan data = new MutableSpan();
    data.kind(Kind.CLIENT);
    data.error(new RuntimeException("A user provided error"));
    data.tag("http.response.status.code", "404");

    assertThat(transformer.translate(data))
        .isEqualTo(
            TracesData.newBuilder().addResourceSpans(ResourceSpans
                    .newBuilder()
                    .setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder().addAttributes(
                        KeyValue.newBuilder().setKey("service.name").setValue(AnyValue.newBuilder()
                                .setStringValue(
                                    Resource.getDefault().getAttribute(stringKey("service.name"))).build())
                            .build()
                    ).build())
                    .addScopeSpans(ScopeSpans.newBuilder()
                        .setScope(InstrumentationScope.newBuilder()
                             .setName("zipkin2.reporter.otel").setVersion("0.0.1").build())
                        .addSpans(io.opentelemetry.proto.trace.v1.Span.newBuilder()
                            .setSpanId(ByteString.fromHex(SpanContext.getInvalid().getSpanId()))
                            .setTraceId(ByteString.fromHex(SpanContext.getInvalid().getTraceId()))
                            .setName("unknown")
                            .setKind(
                                toSpanKind(Kind.CLIENT))
                            .addAttributes(KeyValue.newBuilder().setKey("otel.library.name").setValue(
                                    AnyValue.newBuilder().setStringValue("zipkin2.reporter.otel").build())
                                .build())
                            .addAttributes(
                                KeyValue.newBuilder().setKey("otel.library.version").setValue(
                                    AnyValue.newBuilder().setStringValue("0.0.1").build()).build())
                            .addAttributes(
                                KeyValue.newBuilder().setKey("http.response.status.code").setValue(
                                    AnyValue.newBuilder().setStringValue("404").build()).build())
                            .addAttributes(KeyValue.newBuilder().setKey("error").setValue(
                                    AnyValue.newBuilder().setStringValue("A user provided error").build())
                                .build())
                            .setStatus(
                                Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_ERROR)
                                    .build())
                            .build())
                        .build())
                    .build())
                .build());
  }

  static MutableSpan span(@Nullable Span.Kind kind) {
    MutableSpan mutableSpan = new MutableSpan();
    mutableSpan.traceId(TRACE_ID);
    mutableSpan.parentId(PARENT_SPAN_ID);
    mutableSpan.id(SPAN_ID);
    mutableSpan.kind(kind);
    mutableSpan.name("Recv.helloworld.Greeter.SayHello");
    mutableSpan.startTimestamp(1505855794000000L + 194009601L / 1000);
    mutableSpan.finishTimestamp(1505855799000000L + 465726528L / 1000);
    mutableSpan.localServiceName("tweetiebird");
    mutableSpan.annotate(1505855799000000L + 433901068L / 1000, "\"RECEIVED\":{}");
    mutableSpan.annotate(1505855799000000L + 459486280L / 1000, "\"SENT\":{}");
    return mutableSpan;
  }

  static io.opentelemetry.proto.trace.v1.Span.SpanKind toSpanKind(Span.Kind kind) {
    switch (kind) {
      case CLIENT:
        return SpanKind.SPAN_KIND_CLIENT;
      case SERVER:
        return SpanKind.SPAN_KIND_SERVER;
      case PRODUCER:
        return SpanKind.SPAN_KIND_PRODUCER;
      case CONSUMER:
        return SpanKind.SPAN_KIND_CONSUMER;
    }
    return SpanKind.SPAN_KIND_INTERNAL;
  }
}
