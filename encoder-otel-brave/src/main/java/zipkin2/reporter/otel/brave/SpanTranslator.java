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
import brave.Tag;
import brave.handler.MutableSpan;
import brave.handler.MutableSpan.AnnotationConsumer;
import brave.handler.MutableSpan.TagConsumer;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ResourceSpans.Builder;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Span.Event;
import io.opentelemetry.proto.trace.v1.Span.SpanKind;
import io.opentelemetry.proto.trace.v1.TracesData;
import io.opentelemetry.semconv.HttpAttributes;
import io.opentelemetry.semconv.NetworkAttributes;
import io.opentelemetry.semconv.SemanticAttributes;
import io.opentelemetry.semconv.ServerAttributes;
import io.opentelemetry.semconv.ServiceAttributes;
import io.opentelemetry.semconv.UrlAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;


/**
 * SpanTranslator converts a Zipkin Span to a OpenTelemetry Span.
 */
final class SpanTranslator {

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
    resourceSpansBuilder.addScopeSpans(scopeSpanBuilder
        .build());
    tracesDataBuilder.addResourceSpans(resourceSpansBuilder.build());
    return tracesDataBuilder.build();
  }

  private Span.Builder builderForSingleSpan(MutableSpan span, Builder resourceSpansBuilder) {
    Span.Builder spanBuilder = Span.newBuilder()
        .setTraceId(ByteString.fromHex(span.traceId()))
        .setSpanId(ByteString.fromHex(span.id()))
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
    }
    String localServiceName = span.localServiceName();
    if (localServiceName != null) {
      resourceSpansBuilder.getResourceBuilder().addAttributes(
          KeyValue.newBuilder().setKey(ServiceAttributes.SERVICE_NAME.getKey())
              .setValue(AnyValue.newBuilder().setStringValue(localServiceName).build()).build());
    }
    String localIp = span.localIp();
    if (localIp != null) {
      spanBuilder.addAttributes(KeyValue.newBuilder().setKey(NetworkAttributes.NETWORK_LOCAL_ADDRESS.getKey())
          .setValue(AnyValue.newBuilder().setStringValue(localIp).build()).build());
    }
    int localPort = span.localPort();
    if (localPort != 0) {
      spanBuilder.addAttributes(KeyValue.newBuilder().setKey(ServerAttributes.SERVER_PORT.getKey())
          .setValue(AnyValue.newBuilder().setIntValue(localPort).build()).build());
    }
    String peerName = span.remoteServiceName();
    if (peerName != null) {
      spanBuilder.addAttributes(KeyValue.newBuilder().setKey(SemanticAttributes.NET_SOCK_PEER_NAME.getKey())
          .setValue(AnyValue.newBuilder().setStringValue(peerName).build()).build());
    }
    String peerIp = span.remoteIp();
    if (peerIp != null) {
      spanBuilder.addAttributes(KeyValue.newBuilder().setKey(SemanticAttributes.NET_SOCK_PEER_ADDR.getKey())
          .setValue(AnyValue.newBuilder().setStringValue(peerIp).build()).build());
    }
    int peerPort = span.remotePort();
    if (peerPort != 0) {
      spanBuilder.addAttributes(KeyValue.newBuilder().setKey(SemanticAttributes.NET_SOCK_PEER_PORT.getKey())
          .setValue(AnyValue.newBuilder().setIntValue(peerPort).build()).build());
    }
    span.forEachTag(consumer, spanBuilder);
    span.forEachAnnotation(consumer, spanBuilder);
    consumer.addErrorTag(spanBuilder, span);
    return spanBuilder;
  }

  class Consumer implements TagConsumer<Span.Builder>, AnnotationConsumer<Span.Builder> {

    private final AttributesExtractor attributesExtractor;

    Consumer(AttributesExtractor attributesExtractor) {
      this.attributesExtractor = attributesExtractor;
    }

    @Override
    public void accept(Span.Builder target, String key, String value) {
      attributesExtractor.addTag(target, key, value);
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

}
