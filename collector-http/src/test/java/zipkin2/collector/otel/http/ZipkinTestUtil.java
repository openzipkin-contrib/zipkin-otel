/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.collector.otel.http;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import com.google.protobuf.ByteString;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Status;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.semconv.NetworkAttributes;
import zipkin2.Endpoint;
import zipkin2.Span;

import static io.opentelemetry.proto.trace.v1.Span.Event;
import static io.opentelemetry.proto.trace.v1.Span.SpanKind;

/* Based on code from https://github.com/open-telemetry/opentelemetry-java/blob/d37c1c74e7ec20a990e1a0a07a5daa1a2ecf9f0b/exporters/zipkin/src/test/java/io/opentelemetry/exporter/zipkin/ZipkinTestUtil.java */
class ZipkinTestUtil {

  static final String TRACE_ID = "d239036e7d5cec116b562147388b35bf";

  static final String SPAN_ID = "9cc1e3049173be09";

  static final String PARENT_SPAN_ID = "8b03ab423da481c5";

  private static final Attributes attributes = Attributes.empty();

  private static final List<EventData> annotations =
      Collections.unmodifiableList(
          Arrays.asList(
              EventData.create(1505855799_433901068L, "RECEIVED", Attributes.empty()),
              EventData.create(1505855799_459486280L, "SENT", Attributes.empty())));

  private ZipkinTestUtil() {
  }

  static Span.Builder zipkinSpanBuilder(Span.Kind kind) {
    return Span.newBuilder()
        .traceId(TRACE_ID)
        .parentId(PARENT_SPAN_ID)
        .id(SPAN_ID)
        .kind(kind)
        .name("Recv.helloworld.Greeter.SayHello")
        .timestamp(1505855794000000L + 194009601L / 1000)
        .duration((1505855799000000L + 465726528L / 1000) - (1505855794000000L + 194009601L / 1000))
        .localEndpoint(Endpoint.newBuilder().ip("1.2.3.4").serviceName("tweetiebird").build())
        .putTag(NetworkAttributes.NETWORK_LOCAL_ADDRESS.getKey(), "1.2.3.4")
        .addAnnotation(1505855799000000L + 433901068L / 1000, "RECEIVED")
        .addAnnotation(1505855799000000L + 459486280L / 1000, "SENT");
  }

  static ExportTraceServiceRequest.Builder requestBuilder(
      Function<Resource.Builder, Resource.Builder> resourceCustomizer,
      Function<InstrumentationScope.Builder, InstrumentationScope.Builder> scopeCustomizer,
      Function<io.opentelemetry.proto.trace.v1.Span.Builder, io.opentelemetry.proto.trace.v1.Span.Builder> spanCustomizer) {
    return ExportTraceServiceRequest.newBuilder()
        .addResourceSpans(ResourceSpans.newBuilder()
            .setResource(resourceCustomizer.apply(Resource.newBuilder()
                .addAttributes(stringAttribute("service.name", "tweetiebird"))))
            .addScopeSpans(ScopeSpans.newBuilder()
                .setScope(scopeCustomizer.apply(InstrumentationScope.newBuilder()).build())
                .addSpans(spanCustomizer.apply(io.opentelemetry.proto.trace.v1.Span.newBuilder()
                    .setSpanId(ByteString.fromHex(SPAN_ID))
                    .setTraceId(ByteString.fromHex(TRACE_ID))
                    .setParentSpanId(ByteString.fromHex(PARENT_SPAN_ID))
                    .setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK))
                    .setKind(SpanKind.SPAN_KIND_SERVER)
                    .setName("Recv.helloworld.Greeter.SayHello")
                    .setStartTimeUnixNano(1505855794_194009601L)
                    .setEndTimeUnixNano(1505855799_465726528L)
                    .addAttributes(stringAttribute(NetworkAttributes.NETWORK_LOCAL_ADDRESS.getKey(), "1.2.3.4"))
                    .addEvents(Event.newBuilder()
                        .setName("RECEIVED").setTimeUnixNano(1505855799_433901068L))
                    .addEvents(Event.newBuilder()
                        .setName("SENT").setTimeUnixNano(1505855799_459486280L))
                ))));
  }

  static ExportTraceServiceRequest.Builder requestBuilderWithResourceCustomizer(Function<Resource.Builder, Resource.Builder> resourceCustomizer) {
    return requestBuilder(resourceCustomizer, Function.identity(), Function.identity());
  }

  static ExportTraceServiceRequest.Builder requestBuilderWithScopeCustomizer(Function<InstrumentationScope.Builder, InstrumentationScope.Builder> scopeCustomizer) {
    return requestBuilder(Function.identity(), scopeCustomizer, Function.identity());
  }

  static ExportTraceServiceRequest.Builder requestBuilderWithSpanCustomizer(Function<io.opentelemetry.proto.trace.v1.Span.Builder, io.opentelemetry.proto.trace.v1.Span.Builder> spanCustomizer) {
    return requestBuilder(Function.identity(), Function.identity(), spanCustomizer);
  }

  static ExportTraceServiceRequest.Builder requestBuilder() {
    return requestBuilder(Function.identity(), Function.identity(), Function.identity());
  }

  static KeyValue stringAttribute(String key, String value) {
    return attribute(key, av -> av.setStringValue(value));
  }

  static KeyValue longAttribute(String key, long value) {
    return attribute(key, av -> av.setIntValue(value));
  }

  static KeyValue attribute(String key, Function<AnyValue.Builder, AnyValue.Builder> builder) {
    return KeyValue.newBuilder()
        .setKey(key)
        .setValue(builder.apply(AnyValue.newBuilder()))
        .build();
  }
}
