/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.reporter.otel.brave;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import brave.Span.Kind;
import brave.Tag;
import brave.handler.MutableSpan;
import brave.handler.MutableSpan.AnnotationConsumer;
import brave.handler.MutableSpan.TagConsumer;
import brave.http.HttpTags;
import com.google.protobuf.ByteString;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ResourceSpans.Builder;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Span.Event;
import io.opentelemetry.proto.trace.v1.Span.SpanKind;
import io.opentelemetry.proto.trace.v1.Status;
import io.opentelemetry.proto.trace.v1.TracesData;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.semconv.HttpAttributes;
import io.opentelemetry.semconv.NetworkAttributes;
import io.opentelemetry.semconv.ServerAttributes;
import io.opentelemetry.semconv.ServiceAttributes;
import io.opentelemetry.semconv.UrlAttributes;


/**
 * SpanTranslator converts a Brave Span to a OpenTelemetry Span.
 */
final class SpanTranslator {

  // Defined in the incubating SDK https://github.com/open-telemetry/semantic-conventions-java/blob/main/semconv-incubating/src/main/java/io/opentelemetry/semconv/incubating/PeerIncubatingAttributes.java
  static final String PEER_SERVICE = "peer.service";

  static final ByteString INVALID_TRACE_ID = ByteString.fromHex(SpanContext.getInvalid().getTraceId());

  static final ByteString INVALID_SPAN_ID = ByteString.fromHex(SpanContext.getInvalid().getSpanId());

  /**
   * Tag to Attribute mappings which map brave data policy to otel semantics.
   *
   * @see <a href="https://opentelemetry.io/docs/specs/semconv/http/http-spans/">https://opentelemetry.io/docs/specs/semconv/http/http-spans/</a>
   * @see brave.http.HttpTags
   */
  // TODO: brave also defines rpc and messaging data policy
  private static final Map<String, String> RENAMED_ATTRIBUTES;

  static {
    RENAMED_ATTRIBUTES = new LinkedHashMap<>();
    // "http.host" is not defined in HttpTags, but is a well-known tag.
    RENAMED_ATTRIBUTES.put("http.host", ServerAttributes.SERVER_ADDRESS.getKey());
    RENAMED_ATTRIBUTES.put(HttpTags.METHOD.key(), HttpAttributes.HTTP_REQUEST_METHOD.getKey());
    RENAMED_ATTRIBUTES.put(HttpTags.PATH.key(), UrlAttributes.URL_PATH.getKey());
    RENAMED_ATTRIBUTES.put(HttpTags.ROUTE.key(), HttpAttributes.HTTP_ROUTE.getKey());
    RENAMED_ATTRIBUTES.put(HttpTags.URL.key(), UrlAttributes.URL_FULL.getKey());
    RENAMED_ATTRIBUTES.put(HttpTags.STATUS_CODE.key(), HttpAttributes.HTTP_RESPONSE_STATUS_CODE.getKey());
  }

  private final Consumer consumer;

  SpanTranslator(Tag<Throwable> errorTag) {
    this.consumer = new Consumer(errorTag, RENAMED_ATTRIBUTES);
  }

  TracesData translate(MutableSpan braveSpan) {
    TracesData.Builder tracesDataBuilder = TracesData.newBuilder();
    Builder resourceSpansBuilder = ResourceSpans.newBuilder();
    ScopeSpans.Builder scopeSpanBuilder = ScopeSpans.newBuilder();
    Span.Builder spanBuilder = builderForSingleSpan(braveSpan, resourceSpansBuilder);
    scopeSpanBuilder.addSpans(spanBuilder
        .build());
    InstrumentationScope.Builder scopeBuilder = InstrumentationScope.newBuilder();
    scopeBuilder.setName(BraveScope.getName());
    scopeBuilder.setVersion(BraveScope.getVersion());
    scopeSpanBuilder.setScope(scopeBuilder.build());
    resourceSpansBuilder.addScopeSpans(scopeSpanBuilder
        .build());
    tracesDataBuilder.addResourceSpans(resourceSpansBuilder.build());
    return tracesDataBuilder.build();
  }

  static KeyValue stringAttribute(String key, String value) {
    return KeyValue.newBuilder()
        .setKey(key)
        .setValue(AnyValue.newBuilder().setStringValue(value))
        .build();
  }

  static KeyValue intAttribute(String key, int value) {
    return KeyValue.newBuilder()
        .setKey(key)
        .setValue(AnyValue.newBuilder().setIntValue(value))
        .build();
  }

  private Span.Builder builderForSingleSpan(MutableSpan span, Builder resourceSpansBuilder) {
    Span.Builder spanBuilder = Span.newBuilder()
        .setTraceId(span.traceId() != null ? ByteString.fromHex(span.traceId()) : INVALID_TRACE_ID)
        .setSpanId(span.id() != null ? ByteString.fromHex(span.id()) : INVALID_SPAN_ID);
    if (span.name() != null) {
      spanBuilder.setName(span.name());
    }
    if (span.parentId() != null) {
      spanBuilder.setParentSpanId(ByteString.fromHex(span.parentId()));
    }
    long start = span.startTimestamp();
    long finish = span.finishTimestamp();
    spanBuilder.setStartTimeUnixNano(TimeUnit.MICROSECONDS.toNanos(start));
    if (start != 0 && finish != 0L) {
      spanBuilder.setEndTimeUnixNano(TimeUnit.MICROSECONDS.toNanos(finish));
    }
    Kind kind = span.kind();
    if (kind != null) {
      switch (kind) {
        case CLIENT:
          spanBuilder.setKind(SpanKind.SPAN_KIND_CLIENT);
          break;
        case SERVER:
          spanBuilder.setKind(SpanKind.SPAN_KIND_SERVER);
          break;
        case PRODUCER:
          spanBuilder.setKind(SpanKind.SPAN_KIND_PRODUCER);
          break;
        case CONSUMER:
          spanBuilder.setKind(SpanKind.SPAN_KIND_CONSUMER);
          break;
        default:
          spanBuilder.setKind(SpanKind.SPAN_KIND_INTERNAL); //TODO: Should it work like this?
      }
    }
    else {
      spanBuilder.setKind(SpanKind.SPAN_KIND_INTERNAL); //TODO: Should it work like this?
    }
    String localServiceName = span.localServiceName();
    if (localServiceName == null) {
      localServiceName = Resource.getDefault().getAttribute(ServiceAttributes.SERVICE_NAME);
    }
    resourceSpansBuilder.getResourceBuilder().addAttributes(stringAttribute(ServiceAttributes.SERVICE_NAME.getKey(), localServiceName));
    maybeAddStringAttribute(spanBuilder, NetworkAttributes.NETWORK_LOCAL_ADDRESS.getKey(), span.localIp());
    maybeAddIntAttribute(spanBuilder, NetworkAttributes.NETWORK_LOCAL_PORT.getKey(), span.localPort());
    maybeAddStringAttribute(spanBuilder, NetworkAttributes.NETWORK_PEER_ADDRESS.getKey(), span.remoteIp());
    maybeAddIntAttribute(spanBuilder, NetworkAttributes.NETWORK_PEER_PORT.getKey(), span.remotePort());
    maybeAddStringAttribute(spanBuilder, PEER_SERVICE, span.remoteServiceName());
    span.forEachTag(consumer, spanBuilder);
    span.forEachAnnotation(consumer, spanBuilder);
    consumer.addErrorTag(spanBuilder, span);

    return spanBuilder;
  }

  private static void maybeAddStringAttribute(Span.Builder spanBuilder, String key, String value) {
    if (value != null) {
      spanBuilder.addAttributes(stringAttribute(key, value));
    }
  }

  private static void maybeAddIntAttribute(Span.Builder spanBuilder, String key, int value) {
    if (value != 0) {
      spanBuilder.addAttributes(intAttribute(key, value));
    }
  }

  static final class Consumer implements TagConsumer<Span.Builder>, AnnotationConsumer<Span.Builder> {

    private final Tag<Throwable> errorTag;

    private final Map<String, String> renamedLabels;

    Consumer(Tag<Throwable> errorTag, Map<String, String> renamedLabels) {
      this.errorTag = errorTag;
      this.renamedLabels = renamedLabels;
    }

    @Override
    public void accept(Span.Builder target, String key, String value) {
      target.addAttributes(stringAttribute(getLabelName(key), value));
    }

    void addErrorTag(Span.Builder target, MutableSpan span) {
      String errorValue = errorTag.value(span.error(), null);
      if (errorValue != null) {
        target.addAttributes(stringAttribute("error", errorValue));
        target.setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_ERROR).build());
      }
      else {
        target.setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build());
      }
    }

    @Override
    public void accept(Span.Builder target, long timestamp, String value) {
      target.addEvents(Event.newBuilder().setTimeUnixNano(TimeUnit.MICROSECONDS.toNanos(timestamp))
          .setName(value).build());
    }

    private String getLabelName(String zipkinName) {
      String renamed = renamedLabels.get(zipkinName);
      return renamed != null ? renamed : zipkinName;
    }
  }

}
