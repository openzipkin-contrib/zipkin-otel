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

import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.stream.Collectors.joining;

import com.google.protobuf.ByteString;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ResourceSpans.Builder;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Span.Event;
import io.opentelemetry.proto.trace.v1.Span.SpanKind;
import io.opentelemetry.proto.trace.v1.Status;
import io.opentelemetry.proto.trace.v1.Status.StatusCode;
import io.opentelemetry.proto.trace.v1.TracesData;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.semconv.HttpAttributes;
import io.opentelemetry.semconv.NetworkAttributes;
import io.opentelemetry.semconv.SemanticAttributes;
import io.opentelemetry.semconv.ServerAttributes;
import io.opentelemetry.semconv.ServiceAttributes;
import io.opentelemetry.semconv.UrlAttributes;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import zipkin2.Endpoint;
import zipkin2.Span.Kind;
import zipkin2.internal.Nullable;


/**
 * SpanTranslator converts a Zipkin Span to a OpenTelemetry Span and vice versa
 */
public final class SpanTranslator {

  static final AttributesExtractor ATTRIBUTES_EXTRACTOR;


  private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");
  private static final AttributeKey<String> PEER_SERVICE = stringKey("peer.service");
  private static final AttributeKey<String> SERVER_SOCKET_ADDRESS =
      stringKey("server.socket.address");
  private static final AttributeKey<Long> SERVER_SOCKET_PORT = longKey("server.socket.port");


  static final String KEY_INSTRUMENTATION_SCOPE_NAME = "otel.scope.name";
  static final String KEY_INSTRUMENTATION_SCOPE_VERSION = "otel.scope.version";
  static final String KEY_INSTRUMENTATION_LIBRARY_NAME = "otel.library.name";
  static final String KEY_INSTRUMENTATION_LIBRARY_VERSION = "otel.library.version";
  static final String OTEL_DROPPED_ATTRIBUTES_COUNT = "otel.dropped_attributes_count";
  static final String OTEL_DROPPED_EVENTS_COUNT = "otel.dropped_events_count";
  static final String OTEL_STATUS_CODE = "otel.status_code";

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
        .setTraceId(ByteString.fromHex(span.traceId() != null ? span.traceId()
            : io.opentelemetry.api.trace.SpanContext.getInvalid().getTraceId()))
        .setSpanId(ByteString.fromHex(span.id() != null ? span.id()
            : io.opentelemetry.api.trace.SpanContext.getInvalid().getSpanId()))
        .setName((span.name() == null || span.name().isEmpty()) ? "unknown" : span.name());
    if (span.parentId() != null) {
      spanBuilder.setParentSpanId(ByteString.fromHex(span.parentId()));
    }
    Long start = span.timestamp() != null ? span.timestamp() : NANOSECONDS.toMicros(System.nanoTime());
    Long finish = span.timestampAsLong() + span.durationAsLong();
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
    } else {
      resourceSpansBuilder.getResourceBuilder()
          .addAttributes(KeyValue.newBuilder().setKey("service.name").setValue(
              AnyValue.newBuilder().setStringValue(
                  Resource.getDefault().getAttribute(stringKey("service.name"))).build()).build());
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
        .forEach((key, value) -> ATTRIBUTES_EXTRACTOR.addTag(spanBuilder, key, value));
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
      for (ScopeSpans scopeSpans : resourceSpans.getScopeSpansList()) {
        for (io.opentelemetry.proto.trace.v1.Span span : scopeSpans.getSpansList()) {
          spans.add(generateSpan(span, resourceSpans));
        }
      }
    }
    return spans;

  }

  /**
   * Creates an instance of a Zipkin Span from an OpenTelemetry SpanData instance.
   *
   * @param spanData an OpenTelemetry spanData instance
   * @return a new Zipkin Span
   */
  private static zipkin2.Span generateSpan(io.opentelemetry.proto.trace.v1.Span spanData, ResourceSpans resourceSpans) {
    long startTimestamp = toEpochMicros(spanData.getStartTimeUnixNano());
    long endTimestamp = toEpochMicros(spanData.getEndTimeUnixNano());

    zipkin2.Span.Builder spanBuilder =
        zipkin2.Span.newBuilder()
            .traceId(OtelEncodingUtils.traceIdFromBytes(spanData.getTraceId().toByteArray()))
            .id(OtelEncodingUtils.spanIdFromBytes(spanData.getSpanId().toByteArray()))
            .kind(toSpanKind(spanData))
            .name(spanData.getName())
            .timestamp(toEpochMicros(spanData.getStartTimeUnixNano()))
            .duration(Math.max(1, endTimestamp - startTimestamp))
            .localEndpoint(getLocalEndpoint(resourceSpans))
            .remoteEndpoint(getRemoteEndpoint(spanData));

    if (spanData.getParentSpanId().isEmpty() || OtelEncodingUtils.spanIdFromBytes(
        spanData.getParentSpanId().toByteArray()).equals(SpanContext.getInvalid().getSpanId())) {
      spanBuilder.parentId(
          OtelEncodingUtils.spanIdFromBytes(spanData.getParentSpanId().toByteArray()));
    }

    List<KeyValue> spanAttributes = spanData.getAttributesList();
    spanAttributes.forEach(
        (kv) -> spanBuilder.putTag(kv.getKey(), valueToString(kv, kv.getValue())));
    int droppedAttributes = spanData.getAttributesCount() - spanAttributes.size();
    if (droppedAttributes > 0) {
      spanBuilder.putTag(OTEL_DROPPED_ATTRIBUTES_COUNT, String.valueOf(droppedAttributes));
    }

    Status status = spanData.getStatus();

    // include status code & error.
    if (status.getCode() != Status.StatusCode.STATUS_CODE_UNSET) {
      spanBuilder.putTag(OTEL_STATUS_CODE, status.getCode().toString());

      // add the error tag, if it isn't already in the source span.
      if (status.getCode() == StatusCode.STATUS_CODE_ERROR && spanAttributes.stream()
          .anyMatch(keyValue -> keyValue.getKey().equals("error"))) {
        spanBuilder.putTag("error", nullToEmpty(status.getMessage()));
      }
    }

    spanBuilder.putTag(KEY_INSTRUMENTATION_SCOPE_NAME, "zipkin2.reporter.otel");
    spanBuilder.putTag(KEY_INSTRUMENTATION_SCOPE_VERSION, "0.0.1");

    // Include instrumentation library name for backwards compatibility
    spanBuilder.putTag(KEY_INSTRUMENTATION_LIBRARY_NAME, "zipkin2.reporter.otel");
    // Include instrumentation library name for backwards compatibility
    spanBuilder.putTag(KEY_INSTRUMENTATION_LIBRARY_VERSION, "0.0.1");

    for (Event eventData : spanData.getEventsList()) {
      String annotation = EventDataToAnnotation.apply(eventData);
      spanBuilder.addAnnotation(toEpochMicros(eventData.getTimeUnixNano()), annotation);
    }
    int droppedEvents = spanData.getEventsCount() - spanData.getEventsList().size();
    if (droppedEvents > 0) {
      spanBuilder.putTag(OTEL_DROPPED_EVENTS_COUNT, String.valueOf(droppedEvents));
    }

    return spanBuilder.shared(false).build();
  }

  private static String nullToEmpty(@Nullable String value) {
    return value != null ? value : "";
  }

  private static Endpoint getLocalEndpoint(ResourceSpans spanData) {
    List<KeyValue> resourceAttributes = spanData.getResource().getAttributesList();

    Endpoint.Builder endpoint = Endpoint.newBuilder();
    endpoint.ip(LocalInetAddressSupplier.findLocalIp());

    // use the service.name from the Resource, if it's been set.
    KeyValue serviceNameValue = resourceAttributes.stream().filter(keyValue -> SERVICE_NAME.getKey().equals(
        keyValue.getKey())).findFirst().orElse(null);
    String serviceName = null;
    if (serviceNameValue == null) {
      serviceName = Resource.getDefault().getAttribute(SERVICE_NAME);
    }
    // In practice should never be null unless the default Resource spec is changed.
    if (serviceName != null) {
      endpoint.serviceName(serviceName);
    }
    return endpoint.build();
  }

  @Nullable
  private static Endpoint getRemoteEndpoint(Span spanData) {
    if (spanData.getKind() == SpanKind.SPAN_KIND_CLIENT
        || spanData.getKind() == SpanKind.SPAN_KIND_PRODUCER) {
      // TODO: Implement fallback mechanism:
      // https://opentelemetry.io/docs/reference/specification/trace/sdk_exporters/zipkin/#otlp---zipkin
      List<KeyValue> attributes = spanData.getAttributesList();
      String serviceName = attributes.stream().filter(keyValue -> PEER_SERVICE.getKey().equals(keyValue.getKey())).map(keyValue -> keyValue.getValue().getStringValue()).findFirst().orElse(null);

      if (serviceName != null) {
        Endpoint.Builder endpoint = Endpoint.newBuilder();
        endpoint.serviceName(serviceName);
        endpoint.ip(attributes.stream().filter(keyValue -> SERVER_SOCKET_ADDRESS.getKey().equals(keyValue.getKey())).map(keyValue -> keyValue.getValue().getStringValue()).findFirst().orElse(null));
        attributes.stream()
            .filter(keyValue -> SERVER_SOCKET_PORT.getKey().equals(keyValue.getKey()))
            .map(keyValue -> keyValue.getValue().getIntValue()).findFirst()
            .ifPresent(port -> endpoint.port(port.intValue()));

        return endpoint.build();
      }
    }

    return null;
  }

  @Nullable
  private static zipkin2.Span.Kind toSpanKind(io.opentelemetry.proto.trace.v1.Span spanData) {
    switch (spanData.getKind()) {
      case SPAN_KIND_UNSPECIFIED:
        break;
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
      case UNRECOGNIZED:
        break;
      default:
        return null;
    }
    return null;
  }

  private static long toEpochMicros(long epochNanos) {
    return NANOSECONDS.toMicros(epochNanos);
  }

  private static String valueToString(KeyValue key, AnyValue attributeValue) {
    if (attributeValue.hasArrayValue()) {
      return commaSeparated(attributeValue.getArrayValue().getValuesList().stream().map(
          AnyValue::getStringValue).collect(
          Collectors.toList()));
    } else if (attributeValue.hasStringValue()) {
      return attributeValue.getStringValue();
    }
    throw new IllegalStateException("Unknown attribute type");
  }

  private static String commaSeparated(List<?> values) {
    StringBuilder builder = new StringBuilder();
    for (Object value : values) {
      if (builder.length() != 0) {
        builder.append(',');
      }
      builder.append(value);
    }
    return builder.toString();
  }

  static final class EventDataToAnnotation {

    private EventDataToAnnotation() {
    }

    static String apply(Event eventData) {
      String name = eventData.getName();
      String value = toJson(eventData.getAttributesList());
      return "\"" + name + "\":" + value;
    }

    private static String toJson(List<KeyValue> attributes) {
      return attributes.stream()
          .map(entry -> "\"" + entry.getKey() + "\":" + toValue(entry.getValue()))
          .collect(joining(",", "{", "}"));
    }

    private static String toValue(Object o) {
      if (o instanceof String) {
        return "\"" + o + "\"";
      }
      if (o instanceof List) {
        return ((List<?>) o)
            .stream().map(EventDataToAnnotation::toValue).collect(joining(",", "[", "]"));
      }
      return String.valueOf(o);
    }
  }

  static class LocalInetAddressSupplier implements Supplier<InetAddress> {

    private static final Logger logger = Logger.getLogger(LocalInetAddressSupplier.class.getName());
    private static final LocalInetAddressSupplier INSTANCE =
        new LocalInetAddressSupplier(findLocalIp());
    @Nullable private final InetAddress inetAddress;

    private LocalInetAddressSupplier(@Nullable InetAddress inetAddress) {
      this.inetAddress = inetAddress;
    }

    @Nullable
    @Override
    public InetAddress get() {
      return inetAddress;
    }

    /** Logic borrowed from brave.internal.Platform.produceLocalEndpoint */
    @Nullable
    private static InetAddress findLocalIp() {
      try {
        Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
        while (nics.hasMoreElements()) {
          NetworkInterface nic = nics.nextElement();
          Enumeration<InetAddress> addresses = nic.getInetAddresses();
          while (addresses.hasMoreElements()) {
            InetAddress address = addresses.nextElement();
            if (address.isSiteLocalAddress()) {
              return address;
            }
          }
        }
      } catch (Exception e) {
        // don't crash the caller if there was a problem reading nics.
        logger.log(Level.FINE, "error reading nics", e);
      }
      return null;
    }

    static LocalInetAddressSupplier getInstance() {
      return INSTANCE;
    }
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