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

  // Same value as the API https://github.com/open-telemetry/opentelemetry-java/blob/3e8092d086967fa24a0559044651781403033313/api/all/src/main/java/io/opentelemetry/api/trace/TraceId.java#L32
  static final ByteString INVALID_TRACE_ID = ByteString.fromHex("00000000000000000000000000000000");

  // Same value as the API https://github.com/open-telemetry/opentelemetry-java/blob/3e8092d086967fa24a0559044651781403033313/api/all/src/main/java/io/opentelemetry/api/trace/SpanId.java#L28
  static final ByteString INVALID_SPAN_ID = ByteString.fromHex("0000000000000000");

  // Required to be set to non-empty string. https://github.com/open-telemetry/opentelemetry-proto/blob/14afbd4e133ee8a8a5a9f7a0fd3a09d5a9456340/opentelemetry/proto/trace/v1/trace.proto#L142-L143
  static final String DEFAULT_SPAN_NAME = "unknown";

  // "INTERNAL" is the default value https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/api.md#spankind
  static final SpanKind DEFAULT_KIND = SpanKind.SPAN_KIND_INTERNAL;

  // Same default value as the SDK https://github.com/open-telemetry/opentelemetry-java/blob/3e8092d086967fa24a0559044651781403033313/sdk/common/src/main/java/io/opentelemetry/sdk/resources/Resource.java#L46-L52
  static final String DEFAULT_SERVICE_NAME = "unknown_service:java";

  /**
   * Tag to Attribute mappings which map brave data policy to otel semantics.
   *
   * @see <a href="https://opentelemetry.io/docs/specs/semconv/http/http-spans/">https://opentelemetry.io/docs/specs/semconv/http/http-spans/</a>
   * @see brave.http.HttpTags
   */
  // TODO: brave also defines rpc and messaging data policy
  static final Map<String, String> TAG_TO_ATTRIBUTE;

  static {
    TAG_TO_ATTRIBUTE = new LinkedHashMap<>();
    // "http.host" is not defined in HttpTags, but is a well-known tag.
    TAG_TO_ATTRIBUTE.put("http.host", ServerAttributes.SERVER_ADDRESS.getKey());
    TAG_TO_ATTRIBUTE.put(HttpTags.METHOD.key(), HttpAttributes.HTTP_REQUEST_METHOD.getKey());
    TAG_TO_ATTRIBUTE.put(HttpTags.PATH.key(), UrlAttributes.URL_PATH.getKey());
    TAG_TO_ATTRIBUTE.put(HttpTags.ROUTE.key(), HttpAttributes.HTTP_ROUTE.getKey());
    TAG_TO_ATTRIBUTE.put(HttpTags.URL.key(), UrlAttributes.URL_FULL.getKey());
    TAG_TO_ATTRIBUTE.put(HttpTags.STATUS_CODE.key(), HttpAttributes.HTTP_RESPONSE_STATUS_CODE.getKey());
  }

  private final TagMapper tagMapper;

  SpanTranslator(Tag<Throwable> errorTag) {
    this.tagMapper = new TagMapper(errorTag, TAG_TO_ATTRIBUTE);
  }

  TracesData translate(MutableSpan braveSpan) {
    TracesData.Builder tracesDataBuilder = TracesData.newBuilder();
    Builder resourceSpansBuilder = ResourceSpans.newBuilder();
    ScopeSpans.Builder scopeSpanBuilder = ScopeSpans.newBuilder();
    Span.Builder spanBuilder = builderForSingleSpan(braveSpan, resourceSpansBuilder);
    scopeSpanBuilder.addSpans(spanBuilder.build());
    InstrumentationScope.Builder scopeBuilder = InstrumentationScope.newBuilder();
    scopeBuilder.setName(BraveScope.NAME);
    scopeBuilder.setVersion(BraveScope.VERSION);
    scopeSpanBuilder.setScope(scopeBuilder.build());
    resourceSpansBuilder.addScopeSpans(scopeSpanBuilder.build());
    tracesDataBuilder.addResourceSpans(resourceSpansBuilder.build());
    return tracesDataBuilder.build();
  }

  private Span.Builder builderForSingleSpan(MutableSpan span, Builder resourceSpansBuilder) {
    Span.Builder spanBuilder = Span.newBuilder()
        .setTraceId(span.traceId() != null ? ByteString.fromHex(span.traceId()) : INVALID_TRACE_ID)
        .setSpanId(span.id() != null ? ByteString.fromHex(span.id()) : INVALID_SPAN_ID)
        .setName(span.name() == null || span.name().isEmpty() ?  DEFAULT_SPAN_NAME : span.name());
    if (span.parentId() != null) {
      spanBuilder.setParentSpanId(ByteString.fromHex(span.parentId()));
    }
    long start = span.startTimestamp();
    long finish = span.finishTimestamp();
    spanBuilder.setStartTimeUnixNano(TimeUnit.MICROSECONDS.toNanos(start));
    spanBuilder.setEndTimeUnixNano(TimeUnit.MICROSECONDS.toNanos(finish));
    spanBuilder.setKind(translateKind(span.kind()));
    String localServiceName = span.localServiceName();
    if (localServiceName == null || localServiceName.isEmpty()) {
      localServiceName = DEFAULT_SERVICE_NAME;
    }
    resourceSpansBuilder.getResourceBuilder().addAttributes(stringAttribute(ServiceAttributes.SERVICE_NAME.getKey(), localServiceName));
    maybeAddStringAttribute(spanBuilder, NetworkAttributes.NETWORK_LOCAL_ADDRESS.getKey(), span.localIp());
    maybeAddIntAttribute(spanBuilder, NetworkAttributes.NETWORK_LOCAL_PORT.getKey(), span.localPort());
    maybeAddStringAttribute(spanBuilder, NetworkAttributes.NETWORK_PEER_ADDRESS.getKey(), span.remoteIp());
    maybeAddIntAttribute(spanBuilder, NetworkAttributes.NETWORK_PEER_PORT.getKey(), span.remotePort());
    maybeAddStringAttribute(spanBuilder, PEER_SERVICE, span.remoteServiceName());
    span.forEachTag(tagMapper, spanBuilder);
    span.forEachAnnotation(tagMapper, spanBuilder);
    tagMapper.addErrorTag(spanBuilder, span);

    return spanBuilder;
  }

  private static SpanKind translateKind(Kind kind) {
    if (kind != null) {
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
    }
    return DEFAULT_KIND;
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

  static final class TagMapper implements TagConsumer<Span.Builder>, AnnotationConsumer<Span.Builder> {

    final Tag<Throwable> errorTag;

    final Map<String, String> tagToAttribute;

    TagMapper(Tag<Throwable> errorTag, Map<String, String> tagToAttribute) {
      this.errorTag = errorTag;
      this.tagToAttribute = tagToAttribute;
    }

    @Override
    public void accept(Span.Builder target, String tagKey, String value) {
      target.addAttributes(stringAttribute(convertTagToAttribute(tagKey), value));
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

    private String convertTagToAttribute(String tagKey) {
      String attributeKey = tagToAttribute.get(tagKey);
      return attributeKey != null ? attributeKey : tagKey;
    }
  }

}
