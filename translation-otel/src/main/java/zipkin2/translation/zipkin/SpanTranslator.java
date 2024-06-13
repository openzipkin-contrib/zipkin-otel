/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.translation.zipkin;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ResourceSpans.Builder;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
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
import zipkin2.Span.Kind;


/**
 * SpanTranslator converts a Zipkin Span to a OpenTelemetry Span.
 */
public final class SpanTranslator {

  static final AttributesExtractor ATTRIBUTES_EXTRACTOR;

  static {
    Map<String, String> renamedLabels = new LinkedHashMap<>();
    renamedLabels.put("http.host", ServerAttributes.SERVER_ADDRESS.getKey());
    renamedLabels.put("http.method", HttpAttributes.HTTP_REQUEST_METHOD.getKey());
    renamedLabels.put("http.status_code", HttpAttributes.HTTP_RESPONSE_STATUS_CODE.getKey());
    renamedLabels.put("http.request.size", "http.request.body.size");
    renamedLabels.put("http.response.size", "http.response.body.size");
    renamedLabels.put("http.url", UrlAttributes.URL_FULL.getKey());
    ATTRIBUTES_EXTRACTOR = new AttributesExtractor(renamedLabels);
  }

  /**
   * Converts a Zipkin Span into a OpenTelemetry Span.
   *
   * <p>Ex.
   *
   * <pre>{@code
   * tracesData = SpanTranslator.translate(zipkinSpan);
   * }</pre>
   *
   * @param zipkinSpan The Zipkin Span.
   * @return A OpenTelemetry Span.
   */
  public static TracesData translate(zipkin2.Span zipkinSpan) {
    TracesData.Builder tracesDataBuilder = TracesData.newBuilder();
    Builder resourceSpansBuilder = ResourceSpans.newBuilder();
    ScopeSpans.Builder scopeSpanBuilder = ScopeSpans.newBuilder();
    Span.Builder spanBuilder = builderForSingleSpan(zipkinSpan, resourceSpansBuilder);
    scopeSpanBuilder.addSpans(spanBuilder
        .build());
    resourceSpansBuilder.addScopeSpans(scopeSpanBuilder
        .build());
    tracesDataBuilder.addResourceSpans(resourceSpansBuilder.build());
    return tracesDataBuilder.build();
  }

  private static Span.Builder builderForSingleSpan(zipkin2.Span span, Builder resourceSpansBuilder) {
    Span.Builder spanBuilder = Span.newBuilder()
        .setTraceId(ByteString.fromHex(span.traceId()))
        .setSpanId(ByteString.fromHex(span.id()))
        .setName(span.name());
    if (span.parentId() != null) {
      spanBuilder.setParentSpanId(ByteString.fromHex(span.parentId()));
    }
    long start = span.timestamp();
    long finish = span.timestampAsLong() + span.durationAsLong();
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
    String localIp = span.localEndpoint() != null ? span.localEndpoint().ipv4() : null;
    if (localIp != null) {
      spanBuilder.addAttributes(KeyValue.newBuilder().setKey(NetworkAttributes.NETWORK_LOCAL_ADDRESS.getKey())
          .setValue(AnyValue.newBuilder().setStringValue(localIp).build()).build());
    }
    int localPort = span.localEndpoint() != null ? span.localEndpoint().portAsInt() : 0;
    if (localPort != 0) {
      spanBuilder.addAttributes(KeyValue.newBuilder().setKey(ServerAttributes.SERVER_PORT.getKey())
          .setValue(AnyValue.newBuilder().setIntValue(localPort).build()).build());
    }
    String peerName = span.remoteServiceName();
    if (peerName != null) {
      spanBuilder.addAttributes(KeyValue.newBuilder().setKey(SemanticAttributes.NET_SOCK_PEER_NAME.getKey())
          .setValue(AnyValue.newBuilder().setStringValue(peerName).build()).build());
    }
    String peerIp = span.remoteEndpoint() != null ? span.remoteEndpoint().ipv4() : null;
    if (peerIp != null) {
      spanBuilder.addAttributes(KeyValue.newBuilder().setKey(SemanticAttributes.NET_SOCK_PEER_ADDR.getKey())
          .setValue(AnyValue.newBuilder().setStringValue(peerIp).build()).build());
    }
    int peerPort = span.remoteEndpoint() != null ? span.remoteEndpoint().portAsInt() : 0;
    if (peerPort != 0) {
      spanBuilder.addAttributes(KeyValue.newBuilder().setKey(SemanticAttributes.NET_SOCK_PEER_PORT.getKey())
          .setValue(AnyValue.newBuilder().setIntValue(peerPort).build()).build());
    }
    span.tags().forEach((key, value) -> ATTRIBUTES_EXTRACTOR.addTag(KeyValue.newBuilder(), key, value));
    span.annotations().forEach(annotation -> spanBuilder.addEventsBuilder().setTimeUnixNano(TimeUnit.MICROSECONDS.toNanos(annotation.timestamp()))
        .setName(annotation.value()));
    return spanBuilder;
  }


}
