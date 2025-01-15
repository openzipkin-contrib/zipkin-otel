/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.reporter.otel.brave;

import brave.Span.Kind;
import brave.Tag;
import brave.handler.MutableSpan;
import brave.handler.MutableSpan.AnnotationConsumer;
import brave.http.HttpTags;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Span.Event;
import io.opentelemetry.proto.trace.v1.Span.SpanKind;
import io.opentelemetry.proto.trace.v1.Status;
import io.opentelemetry.proto.trace.v1.TracesData;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static zipkin2.reporter.otel.brave.TagToAttribute.maybeAddIntAttribute;
import static zipkin2.reporter.otel.brave.TagToAttribute.maybeAddStringAttribute;
import static zipkin2.reporter.otel.brave.TagToAttribute.stringAttribute;

/**
 * SpanTranslator converts a Brave Span to a OpenTelemetry Span.
 */
final class SpanTranslator {

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

  // Defined value in https://opentelemetry.io/docs/specs/semconv/attributes-registry/telemetry/#telemetry-sdk-language
  static final String TELEMETRY_SDK_LANGUAGE = "java";

  // Same version as the default instrumentation name
  static final String TELEMETRY_SDK_NAME = BraveScope.NAME;

  // Same version as the default instrumentation scope
  static final String TELEMETRY_SDK_VERSION = BraveScope.VERSION;

  final TagToAttributes tagToAttributes;

  final Tag<Throwable> errorTag;

  final AnnotationMapper annotationMapper = new AnnotationMapper();

  final Map<String, String> resourceAttributes;

  final InstrumentationScope instrumentationScope;

  static final class Builder {
    private Tag<Throwable> errorTag;

    private TagToAttributes tagToAttributes;

    private Map<String, String> resourceAttributes;

    private InstrumentationScope instrumentationScope;

    Builder() {

    }

    Builder errorTag(Tag<Throwable> errorTag) {
      this.errorTag = errorTag;
      return this;
    }

    Builder tagToAttributes(TagToAttributes tagToAttributes) {
      this.tagToAttributes = tagToAttributes;
      return this;
    }

    Builder resourceAttributes(Map<String, String> resourceAttributes) {
      this.resourceAttributes = resourceAttributes;
      return this;
    }

    Builder instrumentationScope(InstrumentationScope instrumentationScope) {
      this.instrumentationScope = instrumentationScope;
      return this;
    }

    SpanTranslator build() {
      return new SpanTranslator(this);
    }
  }

  static Builder newBuilder() {
    return new Builder();
  }

  SpanTranslator(Builder builder) {
    this.tagToAttributes = builder.tagToAttributes == null ? TagToAttributes.create() : builder.tagToAttributes;
    this.errorTag = builder.errorTag;
    this.resourceAttributes = builder.resourceAttributes;
    this.instrumentationScope = builder.instrumentationScope;
  }

  TracesData translate(MutableSpan braveSpan) {
    TracesData.Builder tracesDataBuilder = TracesData.newBuilder();
    ResourceSpans.Builder resourceSpansBuilder = ResourceSpans.newBuilder();
    ScopeSpans.Builder scopeSpanBuilder = ScopeSpans.newBuilder();
    Span.Builder spanBuilder = builderForSingleSpan(braveSpan, resourceSpansBuilder);
    scopeSpanBuilder.addSpans(spanBuilder.build());
    io.opentelemetry.proto.common.v1.InstrumentationScope.Builder scopeBuilder = io.opentelemetry.proto.common.v1.InstrumentationScope.newBuilder();
    scopeBuilder.setName(this.instrumentationScope.name());
    scopeBuilder.setVersion(this.instrumentationScope.version());
    scopeSpanBuilder.setScope(scopeBuilder.build());
    resourceSpansBuilder.addScopeSpans(scopeSpanBuilder.build());
    tracesDataBuilder.addResourceSpans(resourceSpansBuilder.build());
    return tracesDataBuilder.build();
  }

  private Span.Builder builderForSingleSpan(MutableSpan span, ResourceSpans.Builder resourceSpansBuilder) {
    Span.Builder spanBuilder = Span.newBuilder()
        .setTraceId(span.traceId() != null ? ByteString.fromHex(span.traceId()) : INVALID_TRACE_ID)
        .setSpanId(span.id() != null ? ByteString.fromHex(span.id()) : INVALID_SPAN_ID)
        .setName(span.name() == null || span.name().isEmpty() ? DEFAULT_SPAN_NAME : span.name());
    if (span.parentId() != null) {
      spanBuilder.setParentSpanId(ByteString.fromHex(span.parentId()));
    }
    long start = span.startTimestamp();
    long finish = span.finishTimestamp();
    spanBuilder.setStartTimeUnixNano(TimeUnit.MICROSECONDS.toNanos(start));
    spanBuilder.setEndTimeUnixNano(TimeUnit.MICROSECONDS.toNanos(finish));
    spanBuilder.setKind(translateKind(span.kind()));
    Resource.Builder resourceBuilder = resourceSpansBuilder.getResourceBuilder();
    if (!this.resourceAttributes.containsKey(SemanticConventionsAttributes.SERVICE_NAME)) {
      String localServiceName = span.localServiceName();
      if (localServiceName == null || localServiceName.isEmpty()) {
        localServiceName = DEFAULT_SERVICE_NAME;
      }
      resourceBuilder.addAttributes(stringAttribute(SemanticConventionsAttributes.SERVICE_NAME, localServiceName));
    }
    resourceAttributes.forEach((k, v) -> resourceBuilder.addAttributes(stringAttribute(k, v)));
    // Set Telemetry SDK resource attributes https://opentelemetry.io/docs/specs/semconv/attributes-registry/telemetry/
    resourceBuilder.addAttributes(stringAttribute(SemanticConventionsAttributes.TELEMETRY_SDK_LANGUAGE, TELEMETRY_SDK_LANGUAGE))
        .addAttributes(stringAttribute(SemanticConventionsAttributes.TELEMETRY_SDK_NAME, TELEMETRY_SDK_NAME))
        .addAttributes(stringAttribute(SemanticConventionsAttributes.TELEMETRY_SDK_VERSION, TELEMETRY_SDK_VERSION));
    if (span.kind() == Kind.SERVER || span.kind() == Kind.CONSUMER) {
      resolveAddressAndPort(span, span::localIp, span::localPort, (address, port) -> {
        maybeAddStringAttribute(spanBuilder, SemanticConventionsAttributes.SERVER_ADDRESS, address);
        maybeAddIntAttribute(spanBuilder, SemanticConventionsAttributes.SERVER_PORT, port);
      });
      maybeAddStringAttribute(spanBuilder, SemanticConventionsAttributes.CLIENT_ADDRESS, span.remoteIp());
      maybeAddIntAttribute(spanBuilder, SemanticConventionsAttributes.CLIENT_PORT, span.remotePort());
    } else if (span.kind() == Kind.CLIENT || span.kind() == Kind.PRODUCER) {
      resolveAddressAndPort(span, span::remoteIp, span::remotePort, (address, port) -> {
        maybeAddStringAttribute(spanBuilder, SemanticConventionsAttributes.SERVER_ADDRESS, address);
        maybeAddIntAttribute(spanBuilder, SemanticConventionsAttributes.SERVER_PORT, port);
      });
    }
    maybeAddStringAttribute(spanBuilder, SemanticConventionsAttributes.PEER_SERVICE, span.remoteServiceName());
    span.forEachTag(tagToAttributes, spanBuilder);
    span.forEachAnnotation(annotationMapper, spanBuilder);
    String errorValue = errorTag.value(span.error(), null);
    if (errorValue != null) {
      spanBuilder.addAttributes(stringAttribute("error", errorValue));
      spanBuilder.setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_ERROR).build());
    } else {
      spanBuilder.setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build());
    }
    return spanBuilder;
  }

  void resolveAddressAndPort(MutableSpan span, Supplier<String> fallbackAddress, Supplier<Integer> fallbackPort, BiConsumer<String, Integer> addressAndPortConsumer) {
    String url = span.tag(HttpTags.URL.key());
    String address = null;
    int port = 0;
    if (url != null) {
      try {
        URI uri = URI.create(url);
        address = uri.getHost();
        int p = uri.getPort();
        if (p != -1) {
          port = p;
        } else if ("http".equals(uri.getScheme())) {
          port = 80;
        } else if ("https".equals(uri.getScheme())) {
          port = 443;
        }
      } catch (IllegalArgumentException ignored) {
      }
    }
    addressAndPortConsumer.accept(address == null ? fallbackAddress.get() : address, port == 0 ? fallbackPort.get() : port);
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

  static final class AnnotationMapper implements AnnotationConsumer<Span.Builder> {
    @Override
    public void accept(Span.Builder target, long timestamp, String value) {
      target.addEvents(Event.newBuilder().setTimeUnixNano(TimeUnit.MICROSECONDS.toNanos(timestamp))
          .setName(value).build());
    }
  }

}
