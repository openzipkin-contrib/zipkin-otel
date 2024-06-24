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
package zipkin2.translation.zipkin;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import zipkin2.Endpoint;
import zipkin2.Span.Kind;


/**
 * SpanTranslator converts a Zipkin Span to a OpenTelemetry Span and vice versa
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

  private static Span.Builder builderForSingleSpan(zipkin2.Span span,
      Builder resourceSpansBuilder) {
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
      spanBuilder.addAttributes(
          KeyValue.newBuilder().setKey(NetworkAttributes.NETWORK_LOCAL_ADDRESS.getKey())
              .setValue(AnyValue.newBuilder().setStringValue(localIp).build()).build());
    }
    int localPort = span.localEndpoint() != null ? span.localEndpoint().portAsInt() : 0;
    if (localPort != 0) {
      spanBuilder.addAttributes(KeyValue.newBuilder().setKey(ServerAttributes.SERVER_PORT.getKey())
          .setValue(AnyValue.newBuilder().setIntValue(localPort).build()).build());
    }
    String peerName = span.remoteServiceName();
    if (peerName != null) {
      spanBuilder.addAttributes(
          KeyValue.newBuilder().setKey(SemanticAttributes.NET_SOCK_PEER_NAME.getKey())
              .setValue(AnyValue.newBuilder().setStringValue(peerName).build()).build());
    }
    String peerIp = span.remoteEndpoint() != null ? span.remoteEndpoint().ipv4() : null;
    if (peerIp != null) {
      spanBuilder.addAttributes(
          KeyValue.newBuilder().setKey(SemanticAttributes.NET_SOCK_PEER_ADDR.getKey())
              .setValue(AnyValue.newBuilder().setStringValue(peerIp).build()).build());
    }
    int peerPort = span.remoteEndpoint() != null ? span.remoteEndpoint().portAsInt() : 0;
    if (peerPort != 0) {
      spanBuilder.addAttributes(
          KeyValue.newBuilder().setKey(SemanticAttributes.NET_SOCK_PEER_PORT.getKey())
              .setValue(AnyValue.newBuilder().setIntValue(peerPort).build()).build());
    }
    span.tags()
        .forEach((key, value) -> ATTRIBUTES_EXTRACTOR.addTag(KeyValue.newBuilder(), key, value));
    span.annotations().forEach(annotation -> spanBuilder.addEventsBuilder()
        .setTimeUnixNano(TimeUnit.MICROSECONDS.toNanos(annotation.timestamp()))
        .setName(annotation.value()));
    return spanBuilder;
  }

  /**
   * Converts OpenTelemetry Spans into Zipkin spans.
   *
   * <p>Ex.
   *
   * <pre>{@code
   * zipkinSpans = SpanTranslator.translate(exportTraceServiceRequest);
   * }</pre>
   *
   * @param otelSpans The OpenTelemetry Spans.
   * @return Zipkin Spans.
   */
  public static List<zipkin2.Span> translate(ExportTraceServiceRequest otelSpans) {
    List<zipkin2.Span> spans = new ArrayList<>();
    List<ResourceSpans> spansList = otelSpans.getResourceSpansList();
    for (ResourceSpans resourceSpans : spansList) {
      // TODO: Use semantic attributes
      KeyValue localServiceName = getValueFromAttributes("service.name", resourceSpans);
      KeyValue localIp = getValueFromAttributes("net.host.ip", resourceSpans);
      KeyValue localPort = getValueFromAttributes("net.host.port", resourceSpans);
      KeyValue peerName = getValueFromAttributes("net.sock.peer.name", resourceSpans);
      KeyValue peerIp = getValueFromAttributes("net.sock.peer.addr", resourceSpans);
      KeyValue peerPort = getValueFromAttributes("net.sock.peer.port", resourceSpans);
      for (ScopeSpans scopeSpans : resourceSpans.getScopeSpansList()) {
        for (io.opentelemetry.proto.trace.v1.Span span : scopeSpans.getSpansList()) {
          zipkin2.Span.Builder builder = zipkin2.Span.newBuilder();
          builder.name(span.getName());
          builder.traceId(OtelEncodingUtils.traceIdFromBytes(span.getTraceId().toByteArray()));
          builder.id(OtelEncodingUtils.spanIdFromBytes(span.getSpanId().toByteArray()));
          ByteString parent = span.getParentSpanId();
          if (parent != null) {
            builder.parentId(OtelEncodingUtils.spanIdFromBytes(parent.toByteArray()));
          }
          long startMicros = TimeUnit.NANOSECONDS.toMicros(span.getStartTimeUnixNano());
          builder.timestamp(startMicros);
          builder.duration(TimeUnit.NANOSECONDS.toMicros(span.getEndTimeUnixNano()) - startMicros);
          SpanKind spanKind = span.getKind();
          switch (spanKind) {
            case SPAN_KIND_UNSPECIFIED:
              break;
            case SPAN_KIND_INTERNAL:
              break;
            case SPAN_KIND_SERVER:
              builder.kind(Kind.SERVER);
              break;
            case SPAN_KIND_CLIENT:
              builder.kind(Kind.CLIENT);
              break;
            case SPAN_KIND_PRODUCER:
              builder.kind(Kind.PRODUCER);
              break;
            case SPAN_KIND_CONSUMER:
              builder.kind(Kind.CONSUMER);
              break;
            case UNRECOGNIZED:
              break;
          }
          Endpoint.Builder localEndpointBuilder = Endpoint.newBuilder();
          if (localServiceName != null) {
            localEndpointBuilder.serviceName(localServiceName.getValue().getStringValue());
          }
          if (localPort != null) {
            localEndpointBuilder.port((int) localPort.getValue().getIntValue());
          }
          if (localIp != null) {
            localEndpointBuilder.ip(localIp.getValue().getStringValue());
          }
          builder.localEndpoint(localEndpointBuilder.build());
          Endpoint.Builder remoteEndpointBuilder = Endpoint.newBuilder();
          if (peerName != null) {
            remoteEndpointBuilder.serviceName(peerName.getValue().getStringValue());
          }
          if (peerPort != null) {
            remoteEndpointBuilder.port((int) peerPort.getValue().getIntValue());
          }
          if (peerIp != null) {
            remoteEndpointBuilder.ip(peerIp.getValue().getStringValue());
          }
          builder.remoteEndpoint(remoteEndpointBuilder.build());
          // TODO: Remove the ones from above
          span.getAttributesList().forEach(
              keyValue -> builder.putTag(keyValue.getKey(), keyValue.getValue().getStringValue()));
          span.getEventsList().forEach(
              event -> builder.addAnnotation(TimeUnit.NANOSECONDS.toMicros(event.getTimeUnixNano()),
                  event.getName()));
          spans.add(builder.shared(false).build());
        }
      }
    }
    return spans;

  }

  private static KeyValue getValueFromAttributes(String key, ResourceSpans resourceSpans) {
    return resourceSpans.getResource().getAttributesList().stream()
        .filter(keyValue -> keyValue.getKey().equals(key)).findFirst().orElse(null);
  }

  /**
   * Taken from OpenTelemetry codebase.
   */
  static class OtelEncodingUtils {

    private static final String ALPHABET = "0123456789abcdef";

    private static final char[] ENCODING = buildEncodingArray();

    private static final String INVALID_TRACE = "00000000000000000000000000000000";

    private static final int TRACE_BYTES_LENGTH = 16;

    private static final int TRACE_HEX_LENGTH = 2 * TRACE_BYTES_LENGTH;

    private static final int SPAN_BYTES_LENGTH = 8;

    private static final int SPAN_HEX_LENGTH = 2 * SPAN_BYTES_LENGTH;

    private static final String INVALID_SPAN = "0000000000000000";

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

      // Visible for testing
      static void clearChars() {
        CHAR_ARRAY.set(null);
      }

      private TemporaryBuffers() {
      }
    }
  }

}