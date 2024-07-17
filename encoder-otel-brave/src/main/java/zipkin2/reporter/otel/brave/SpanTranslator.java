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

import static io.opentelemetry.api.common.AttributeKey.stringKey;

import brave.Span.Kind;
import brave.Tag;
import brave.handler.MutableSpan;
import brave.handler.MutableSpan.AnnotationConsumer;
import brave.handler.MutableSpan.TagConsumer;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ResourceSpans.Builder;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Span.Event;
import io.opentelemetry.proto.trace.v1.Span.Link;
import io.opentelemetry.proto.trace.v1.Span.SpanKind;
import io.opentelemetry.proto.trace.v1.TracesData;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.semconv.HttpAttributes;
import io.opentelemetry.semconv.NetworkAttributes;
import io.opentelemetry.semconv.SemanticAttributes;
import io.opentelemetry.semconv.ServerAttributes;
import io.opentelemetry.semconv.ServiceAttributes;
import io.opentelemetry.semconv.UrlAttributes;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


/**
 * SpanTranslator converts a Brave Span to a OpenTelemetry Span.
 */
final class SpanTranslator {

  static final String KEY_INSTRUMENTATION_SCOPE_NAME = "otel.scope.name";
  static final String KEY_INSTRUMENTATION_SCOPE_VERSION = "otel.scope.version";
  static final String KEY_INSTRUMENTATION_LIBRARY_NAME = "otel.library.name";
  static final String KEY_INSTRUMENTATION_LIBRARY_VERSION = "otel.library.version";
  static final String OTEL_STATUS_CODE = "otel.status_code";

  private static final Map<String, String> RENAMED_LABELS;

  static {
    RENAMED_LABELS = new LinkedHashMap<>();
    RENAMED_LABELS.put("http.host", ServerAttributes.SERVER_ADDRESS.getKey());
    RENAMED_LABELS.put("http.method", HttpAttributes.HTTP_REQUEST_METHOD.getKey());
    RENAMED_LABELS.put("http.status_code", HttpAttributes.HTTP_RESPONSE_STATUS_CODE.getKey());
    RENAMED_LABELS.put("http.request.size", "http.request.body.size");
    RENAMED_LABELS.put("http.response.size", "http.response.body.size");
    RENAMED_LABELS.put("http.url", UrlAttributes.URL_FULL.getKey());
  }

  private final Consumer consumer;

  SpanTranslator(Tag<Throwable> errorTag) {
    this.consumer = new Consumer(new AttributesExtractor(errorTag, RENAMED_LABELS));
  }

  /**
   * Converts a Zipkin Span into a OpenTelemetry Span.
   *
   * <p>Ex.
   *
   * <pre>{@code
   * tracesData = SpanTranslator.translate(braveSpan);
   * }</pre>
   *
   * @param braveSpan The Zipkin Span.
   * @return A OpenTelemetry Span.
   */
  TracesData translate(MutableSpan braveSpan) {
    TracesData.Builder tracesDataBuilder = TracesData.newBuilder();
    Builder resourceSpansBuilder = ResourceSpans.newBuilder();
    ScopeSpans.Builder scopeSpanBuilder = ScopeSpans.newBuilder();
    Span.Builder spanBuilder = builderForSingleSpan(braveSpan, resourceSpansBuilder);
    scopeSpanBuilder.addSpans(spanBuilder
        .build());
    InstrumentationScope.Builder scopeBuilder = InstrumentationScope.newBuilder();
    // TODO: What should we put here?
    scopeBuilder.setName("zipkin2.reporter.otel");
    // TODO: Hardcoded library version
    scopeBuilder.setVersion("0.0.1");
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
        .setTraceId(ByteString.fromHex(span.traceId() != null ? span.traceId() : io.opentelemetry.api.trace.SpanContext.getInvalid().getTraceId()))
        .setSpanId(ByteString.fromHex(span.id() != null ? span.id() : io.opentelemetry.api.trace.SpanContext.getInvalid().getSpanId()))
        .setName((span.name() == null || span.name().isEmpty()) ? "unknown" : span.name());
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
    } else {
      spanBuilder.setKind(SpanKind.SPAN_KIND_INTERNAL); //TODO: Should it work like this?
    }
    String localServiceName = span.localServiceName();
    if (localServiceName == null) {
      localServiceName = Resource.getDefault().getAttribute(ServiceAttributes.SERVICE_NAME);
    }
    resourceSpansBuilder.getResourceBuilder()
        .addAttributes(stringAttribute(ServiceAttributes.SERVICE_NAME.getKey(), localServiceName));
    String localIp = span.localIp();
    if (localIp != null) {
      spanBuilder.addAttributes(stringAttribute(NetworkAttributes.NETWORK_LOCAL_ADDRESS.getKey(), localIp));
    }
    int localPort = span.localPort();
    if (localPort != 0) {
      spanBuilder.addAttributes(intAttribute(NetworkAttributes.NETWORK_LOCAL_PORT.getKey(), localPort));
    }
    String peerName = span.remoteServiceName();
    if (peerName != null) {
      spanBuilder.addAttributes(stringAttribute(SemanticAttributes.NET_SOCK_PEER_NAME.getKey(), peerName));
    }
    String peerIp = span.remoteIp();
    if (peerIp != null) {
      spanBuilder.addAttributes(stringAttribute(SemanticAttributes.NET_SOCK_PEER_ADDR.getKey(), peerIp));
    }
    int peerPort = span.remotePort();
    if (peerPort != 0) {
      spanBuilder.addAttributes(intAttribute(SemanticAttributes.NET_SOCK_PEER_PORT.getKey(), peerPort));
    }
    // Include instrumentation library name for backwards compatibility
    spanBuilder.addAttributes(stringAttribute(KEY_INSTRUMENTATION_LIBRARY_NAME, "zipkin2.reporter.otel"));
    // TODO: Hardcoded library version
    // Include instrumentation library name for backwards compatibility
    spanBuilder.addAttributes(stringAttribute(KEY_INSTRUMENTATION_LIBRARY_VERSION, "0.0.1"));

    span.forEachTag(consumer, spanBuilder);
    span.forEachAnnotation(consumer, spanBuilder);
    consumer.addErrorTag(spanBuilder, span);

    List<Link> links = LinkUtils.toLinks(span.tags());
    spanBuilder.addAllLinks(links);

    return spanBuilder;
  }

  class Consumer implements TagConsumer<Span.Builder>, AnnotationConsumer<Span.Builder> {

    private final AttributesExtractor attributesExtractor;

    Consumer(AttributesExtractor attributesExtractor) {
      this.attributesExtractor = attributesExtractor;
    }

    @Override
    public void accept(Span.Builder target, String key, String value) {
      if (!LinkUtils.isApplicable(key)) {
        attributesExtractor.addTag(target, key, value);
      }
    }

    void addErrorTag(Span.Builder target, MutableSpan span) {
      attributesExtractor.addErrorTag(target, span);
    }

    @Override
    public void accept(Span.Builder target, long timestamp, String value) {
      target.addEvents(Event.newBuilder().setTimeUnixNano(TimeUnit.MICROSECONDS.toNanos(timestamp))
          .setName(value).build());
    }
  }

  static class LinkUtils {

    private static final String LINKS_PREFIX = "links[";

    private static final Pattern LINKS_ID = Pattern.compile("^links\\[(.*)]\\..*$");

    private static final Pattern TAG_KEY = Pattern.compile("^links\\[.*]\\.tags\\[(.*)]$");

    static boolean isApplicable(Map.Entry<String, String> entry) {
      return isApplicable(entry.getKey());
    }

    private static boolean isApplicable(String key) {
      return key.startsWith(LINKS_PREFIX);
    }

    private static int linkGroup(Map.Entry<String, String> entry) {
      Matcher matcher = LINKS_ID.matcher(entry.getKey());
      if (matcher.matches()) {
        return Integer.parseInt(matcher.group(1));
      }
      return -1;
    }

    static List<Link> toLinks(Map<String, String> tags) {
      return tags.entrySet()
          .stream()
          .filter(LinkUtils::isApplicable)
          .collect(Collectors.groupingBy(LinkUtils::linkGroup))
          .values()
          .stream().map(LinkUtils::toLink)
          .collect(Collectors.toList());
    }

    private static Link toLink(List<Entry<String, String>> groupedTags) {
      String traceId = "";
      String spanId = "";
      Map<String, Object> tags = new HashMap<>();
      for (Map.Entry<String, String> groupedTag : groupedTags) {
        if (groupedTag.getKey().endsWith(".traceId")) {
          traceId = groupedTag.getValue();
        }
        else if (groupedTag.getKey().endsWith(".spanId")) {
          spanId = groupedTag.getValue();
        }
        else if (groupedTag.getKey().contains("tags")) {
          String tagKey = tagKeyNameFromString(groupedTag.getKey());
          if (tagKey != null) {
            tags.put(tagKey, groupedTag.getValue());
          }
        }
      }
      if (traceId != null && !traceId.isEmpty()) {
        List<KeyValue> keyValues = tags.entrySet().stream().map(e -> KeyValue.newBuilder().setKey(e.getKey()).setValue(
            AnyValue.newBuilder().setStringValue(String.valueOf(e.getValue())).build()).build()).collect(
            Collectors.toList());
        return Link.newBuilder()
            .setSpanId(ByteString.fromHex(spanId))
            .setTraceId(ByteString.fromHex(traceId))
            .addAllAttributes(keyValues)
            .build();
      }
      return null;
    }

    static String tagKeyNameFromString(String tag) {
      Matcher matcher = TAG_KEY.matcher(tag);
      if (matcher.matches()) {
        return matcher.group(1);
      }
      return null;
    }

  }

}
