/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.collector.otel.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.protobuf.ByteString;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Span.Event;
import io.opentelemetry.proto.trace.v1.Span.SpanKind;
import io.opentelemetry.proto.trace.v1.Status;
import io.opentelemetry.proto.trace.v1.Status.StatusCode;
import io.opentelemetry.semconv.NetworkAttributes;
import io.opentelemetry.semconv.OtelAttributes;
import io.opentelemetry.semconv.ServiceAttributes;
import zipkin2.Endpoint;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * SpanTranslator converts OpenTelemetry Spans to Zipkin Spans
 * It is based, in part, on code from https://github.com/open-telemetry/opentelemetry-java/blob/ad120a5bff0887dffedb9c73af8e8e0aeb63659a/exporters/zipkin/src/main/java/io/opentelemetry/exporter/zipkin/OtelToZipkinSpanTransformer.java
 * @see <a href="https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/sdk_exporters/zipkin.md#status">https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/sdk_exporters/zipkin.md#status</a>
 */
final class SpanTranslator {

  static final AttributeKey<String> PEER_SERVICE = AttributeKey.stringKey("peer.service");

  static final String OTEL_DROPPED_ATTRIBUTES_COUNT = "otel.dropped_attributes_count";

  static final String OTEL_DROPPED_EVENTS_COUNT = "otel.dropped_events_count";

  static final String ERROR_TAG = "error";

  static List<zipkin2.Span> translate(ExportTraceServiceRequest otelSpans) {
    List<zipkin2.Span> spans = new ArrayList<>();
    List<ResourceSpans> spansList = otelSpans.getResourceSpansList();
    for (ResourceSpans resourceSpans : spansList) {
      for (ScopeSpans scopeSpans : resourceSpans.getScopeSpansList()) {
        InstrumentationScope scope = scopeSpans.getScope();
        for (io.opentelemetry.proto.trace.v1.Span span : scopeSpans.getSpansList()) {
          spans.add(generateSpan(span, scope, resourceSpans.getResource()));
        }
      }
    }
    return spans;
  }

  /**
   * Creates an instance of a Zipkin Span from an OpenTelemetry SpanData instance.
   *
   * @param spanData an OpenTelemetry spanData instance
   * @param scope InstrumentationScope of the span
   * @return a new Zipkin Span
   */
  private static zipkin2.Span generateSpan(Span spanData, InstrumentationScope scope, Resource resource) {
    long startTimestamp = nanoToMills(spanData.getStartTimeUnixNano());
    long endTimestamp = nanoToMills(spanData.getEndTimeUnixNano());
    Map<String, AnyValue> attributesMap = spanData.getAttributesList().stream().collect(Collectors.toMap(KeyValue::getKey, KeyValue::getValue));
    zipkin2.Span.Builder spanBuilder = zipkin2.Span.newBuilder()
        .traceId(OtelEncodingUtils.traceIdFromBytes(spanData.getTraceId().toByteArray()))
        .id(OtelEncodingUtils.spanIdFromBytes(spanData.getSpanId().toByteArray()))
        .kind(toSpanKind(spanData.getKind()))
        .name(spanData.getName())
        .timestamp(nanoToMills(spanData.getStartTimeUnixNano()))
        .duration(Math.max(1, endTimestamp - startTimestamp))
        .localEndpoint(getLocalEndpoint(attributesMap, resource))
        .remoteEndpoint(getRemoteEndpoint(attributesMap, spanData.getKind()));
    ByteString parentSpanId = spanData.getParentSpanId();
    if (!parentSpanId.isEmpty()) {
      String parentId = OtelEncodingUtils.spanIdFromBytes(parentSpanId.toByteArray());
      if (!parentId.equals(OtelEncodingUtils.INVALID_SPAN)) {
        spanBuilder.parentId(parentId);
      }
    }
    attributesMap.forEach((k, v) -> spanBuilder.putTag(k, ProtoUtils.valueToString(v)));
    // https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/common/mapping-to-non-otlp.md#dropped-attributes-count
    int droppedAttributes = spanData.getAttributesCount() - attributesMap.size();
    if (droppedAttributes > 0) {
      spanBuilder.putTag(OTEL_DROPPED_ATTRIBUTES_COUNT, String.valueOf(droppedAttributes));
    }
    Status status = spanData.getStatus();
    // https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/sdk_exporters/zipkin.md#status
    if (status.getCode() != Status.StatusCode.STATUS_CODE_UNSET) {
      String codeValue = status.getCode().toString().replace("STATUS_CODE_", ""); // either OK or ERROR
      spanBuilder.putTag(OtelAttributes.OTEL_STATUS_CODE.getKey(), codeValue);
      // add the error tag, if it isn't already in the source span.
      if (status.getCode() == StatusCode.STATUS_CODE_ERROR && !attributesMap.containsKey(ERROR_TAG)) {
        spanBuilder.putTag(ERROR_TAG, status.getMessage());
      }
    }
    // https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/common/mapping-to-non-otlp.md#instrumentationscope
    if (!scope.getName().isEmpty()) {
      spanBuilder.putTag(OtelAttributes.OTEL_SCOPE_NAME.getKey(), scope.getName());
    }
    if (!scope.getVersion().isEmpty()) {
      spanBuilder.putTag(OtelAttributes.OTEL_SCOPE_VERSION.getKey(), scope.getVersion());
    }
    for (Event eventData : spanData.getEventsList()) {
      // https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/sdk_exporters/zipkin.md#events
      String name = eventData.getName();
      String value = ProtoUtils.kvListToJson(eventData.getAttributesList());
      String annotation = "\"" + name + "\":" + value;
      spanBuilder.addAnnotation(nanoToMills(eventData.getTimeUnixNano()), annotation);
    }
    // https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/common/mapping-to-non-otlp.md#dropped-events-count
    int droppedEvents = spanData.getEventsCount() - spanData.getEventsList().size();
    if (droppedEvents > 0) {
      spanBuilder.putTag(OTEL_DROPPED_EVENTS_COUNT, String.valueOf(droppedEvents));
    }
    return spanBuilder.build();
  }

  private static Endpoint getLocalEndpoint(Map<String, AnyValue> attributesMap, Resource resource) {
    AnyValue serviceName = resource.getAttributesList().stream()
        .filter(kv -> kv.getKey().equals(ServiceAttributes.SERVICE_NAME.getKey()))
        .findFirst()
        .map(KeyValue::getValue)
        .orElse(null);
    if (serviceName != null) {
      Endpoint.Builder endpoint = Endpoint.newBuilder().serviceName(serviceName.getStringValue());
      AnyValue networkLocalAddress = attributesMap.get(NetworkAttributes.NETWORK_LOCAL_ADDRESS.getKey());
      AnyValue networkLocalPort = attributesMap.get(NetworkAttributes.NETWORK_LOCAL_PORT.getKey());
      if (networkLocalAddress != null) {
        endpoint.ip(networkLocalAddress.getStringValue());
      }
      if (networkLocalPort != null) {
        endpoint.port(Long.valueOf(networkLocalPort.getIntValue()).intValue());
      }
      // TODO remove the corresponding (duplicated) tags?
      return endpoint.build();
    }
    return null;
  }

  private static Endpoint getRemoteEndpoint(Map<String, AnyValue> attributesMap, SpanKind kind) {
    if (kind == SpanKind.SPAN_KIND_CLIENT || kind == SpanKind.SPAN_KIND_PRODUCER) {
      AnyValue peerService = attributesMap.get(PEER_SERVICE.getKey());
      AnyValue networkPeerAddress = attributesMap.get(NetworkAttributes.NETWORK_PEER_ADDRESS.getKey());
      String serviceName = null;
      // TODO: Implement fallback mechanism?
      // https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/sdk_exporters/zipkin.md#otlp---zipkin
      if (peerService != null) {
        serviceName = peerService.getStringValue();
      }
      else if (networkPeerAddress != null) {
        serviceName = networkPeerAddress.getStringValue();
      }
      if (serviceName != null) {
        Endpoint.Builder endpoint = Endpoint.newBuilder().serviceName(serviceName);
        AnyValue networkPeerPort = attributesMap.get(NetworkAttributes.NETWORK_PEER_PORT.getKey());
        if (networkPeerAddress != null) {
          endpoint.ip(networkPeerAddress.getStringValue());
        }
        if (networkPeerPort != null) {
          endpoint.port(Long.valueOf(networkPeerPort.getIntValue()).intValue());
        }
        // TODO remove the corresponding (duplicated) tags?
        return endpoint.build();
      }
    }
    return null;
  }

  static zipkin2.Span.Kind toSpanKind(Span.SpanKind spanKind) {
    switch (spanKind) {
      case SPAN_KIND_UNSPECIFIED:
      case UNRECOGNIZED:
      case SPAN_KIND_INTERNAL:
        break;
      case SPAN_KIND_SERVER:
        return zipkin2.Span.Kind.SERVER;
      case SPAN_KIND_CLIENT:
        return zipkin2.Span.Kind.CLIENT;
      case SPAN_KIND_PRODUCER:
        return zipkin2.Span.Kind.PRODUCER;
      case SPAN_KIND_CONSUMER:
        return zipkin2.Span.Kind.CONSUMER;
      default:
        return null;
    }
    return null;
  }

  static long nanoToMills(long epochNanos) {
    return NANOSECONDS.toMicros(epochNanos);
  }

  /**
   * Taken from OpenTelemetry codebase.
   * https://github.com/open-telemetry/opentelemetry-java/blob/3e8092d086967fa24a0559044651781403033313/api/all/src/main/java/io/opentelemetry/api/internal/OtelEncodingUtils.java
   */
  static class OtelEncodingUtils {

    static final String ALPHABET = "0123456789abcdef";

    static final char[] ENCODING = buildEncodingArray();

    static final String INVALID_TRACE = "00000000000000000000000000000000";

    static final int TRACE_BYTES_LENGTH = 16;

    static final int TRACE_HEX_LENGTH = 2 * TRACE_BYTES_LENGTH;

    static final int SPAN_BYTES_LENGTH = 8;

    static final int SPAN_HEX_LENGTH = 2 * SPAN_BYTES_LENGTH;

    static final String INVALID_SPAN = "0000000000000000";

    private static char[] buildEncodingArray() {
      char[] encoding = new char[512];
      for (int i = 0; i < 256; ++i) {
        encoding[i] = ALPHABET.charAt(i >>> 4);
        encoding[i | 0x100] = ALPHABET.charAt(i & 0xF);
      }
      return encoding;
    }

    /**
     * Fills {@code dest} with the hex encoding of {@code bytes}.
     */
    public static void bytesToBase16(byte[] bytes, char[] dest, int length) {
      for (int i = 0; i < length; i++) {
        byteToBase16(bytes[i], dest, i * 2);
      }
    }

    /**
     * Encodes the specified byte, and returns the encoded {@code String}.
     *
     * @param value      the value to be converted.
     * @param dest       the destination char array.
     * @param destOffset the starting offset in the destination char array.
     */
    public static void byteToBase16(byte value, char[] dest, int destOffset) {
      int b = value & 0xFF;
      dest[destOffset] = ENCODING[b];
      dest[destOffset + 1] = ENCODING[b | 0x100];
    }

    /**
     * Returns the lowercase hex (base16) representation of the {@code TraceId} converted from the
     * given bytes representation, or {@link #INVALID_TRACE} if input is {@code null} or the given
     * byte array is too short.
     *
     * <p>It converts the first 26 bytes of the given byte array.
     *
     * @param traceIdBytes the bytes (16-byte array) representation of the {@code TraceId}.
     * @return the lowercase hex (base16) representation of the {@code TraceId}.
     */
    static String traceIdFromBytes(byte[] traceIdBytes) {
      if (traceIdBytes == null || traceIdBytes.length < TRACE_BYTES_LENGTH) {
        return INVALID_TRACE;
      }
      char[] result = TemporaryBuffers.chars(TRACE_HEX_LENGTH);
      OtelEncodingUtils.bytesToBase16(traceIdBytes, result, TRACE_BYTES_LENGTH);
      return new String(result, 0, TRACE_HEX_LENGTH);
    }

    static String spanIdFromBytes(byte[] spanIdBytes) {
      if (spanIdBytes == null || spanIdBytes.length < SPAN_BYTES_LENGTH) {
        return INVALID_SPAN;
      }
      char[] result = TemporaryBuffers.chars(SPAN_HEX_LENGTH);
      OtelEncodingUtils.bytesToBase16(spanIdBytes, result, SPAN_BYTES_LENGTH);
      return new String(result, 0, SPAN_HEX_LENGTH);
    }

    static final class TemporaryBuffers {

      private static final ThreadLocal<char[]> CHAR_ARRAY = new ThreadLocal<>();

      /**
       * A {@link ThreadLocal} {@code char[]} of size {@code len}. Take care when using a large
       * value of {@code len} as this buffer will remain for the lifetime of the thread. The
       * returned buffer will not be zeroed and may be larger than the requested size, you must make
       * sure to fill the entire content to the desired value and set the length explicitly when
       * converting to a {@link String}.
       */
      public static char[] chars(int len) {
        char[] buffer = CHAR_ARRAY.get();
        if (buffer == null || buffer.length < len) {
          buffer = new char[len];
          CHAR_ARRAY.set(buffer);
        }
        return buffer;
      }

      private TemporaryBuffers() {
      }
    }
  }

}
