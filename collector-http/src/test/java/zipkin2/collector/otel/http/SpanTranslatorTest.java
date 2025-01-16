/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.collector.otel.http;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.ArrayValue;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.Span.SpanKind;
import io.opentelemetry.proto.trace.v1.Status;
import io.opentelemetry.semconv.NetworkAttributes;
import io.opentelemetry.semconv.OtelAttributes;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import zipkin2.Endpoint;
import zipkin2.Span;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static zipkin2.collector.otel.http.ZipkinTestUtil.attribute;
import static zipkin2.collector.otel.http.ZipkinTestUtil.longAttribute;
import static zipkin2.collector.otel.http.ZipkinTestUtil.requestBuilder;
import static zipkin2.collector.otel.http.ZipkinTestUtil.requestBuilderWithResourceCustomizer;
import static zipkin2.collector.otel.http.ZipkinTestUtil.requestBuilderWithScopeCustomizer;
import static zipkin2.collector.otel.http.ZipkinTestUtil.requestBuilderWithSpanCustomizer;
import static zipkin2.collector.otel.http.ZipkinTestUtil.stringAttribute;
import static zipkin2.collector.otel.http.ZipkinTestUtil.zipkinSpanBuilder;

/* Based on code from https://github.com/open-telemetry/opentelemetry-java/blob/d37c1c74e7ec20a990e1a0a07a5daa1a2ecf9f0b/exporters/zipkin/src/test/java/io/opentelemetry/exporter/zipkin/OtelToZipkinSpanTransformerTest.java */
class SpanTranslatorTest {
  SpanTranslator spanTranslator = new SpanTranslator();

  @Test
  void translate_remoteParent() {
    ExportTraceServiceRequest data = requestBuilder().build();
    Span expected = zipkinSpanBuilder(Span.Kind.SERVER)
        .putTag(OtelAttributes.OTEL_STATUS_CODE.getKey(), "OK")
        .build();
    assertThat(spanTranslator.translate(data)).containsExactly(expected);
  }

  @Test
  void translate_invalidParent() {
    ExportTraceServiceRequest data = requestBuilderWithSpanCustomizer(span -> span
        .setParentSpanId(ByteString.fromHex("0000000000000000")))
        .build();
    Span expected = zipkinSpanBuilder(Span.Kind.SERVER)
        .parentId(0)
        .putTag(OtelAttributes.OTEL_STATUS_CODE.getKey(), "OK")
        .build();
    assertThat(spanTranslator.translate(data)).containsExactly(expected);
  }

  @Test
  void translate_invalidTraceId() {
    ExportTraceServiceRequest data = requestBuilderWithSpanCustomizer(span -> span
        .setTraceId(ByteString.fromHex("00000000000000000000000000000000")))
        .build();
    assertThatThrownBy(() -> spanTranslator.translate(data))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void translate_invalidSpanId() {
    ExportTraceServiceRequest data = requestBuilderWithSpanCustomizer(span -> span
        .setSpanId(ByteString.fromHex("0000000000000000")))
        .build();
    assertThatThrownBy(() -> spanTranslator.translate(data))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void translate_subMicroDurations() {
    ExportTraceServiceRequest data =
        ZipkinTestUtil.requestBuilderWithSpanCustomizer(span -> span
                .setStartTimeUnixNano(1505855794_194009601L)
                .setEndTimeUnixNano(1505855794_194009999L))
            .build();
    Span expected =
        zipkinSpanBuilder(Span.Kind.SERVER)
            .putTag(OtelAttributes.OTEL_STATUS_CODE.getKey(), "OK")
            .duration(1)
            .build();
    assertThat(spanTranslator.translate(data)).containsExactly(expected);
  }

  @Test
  void translate_ServerKind() {
    ExportTraceServiceRequest data = ZipkinTestUtil.requestBuilderWithSpanCustomizer(span -> span
        .setKind(SpanKind.SPAN_KIND_SERVER)).build();
    assertThat(spanTranslator.translate(data))
        .containsExactly(
            zipkinSpanBuilder(Span.Kind.SERVER)
                .putTag(OtelAttributes.OTEL_STATUS_CODE.getKey(), "OK")
                .build());
  }

  @Test
  void translate_ClientKind() {
    ExportTraceServiceRequest data = ZipkinTestUtil.requestBuilderWithSpanCustomizer(span -> span
        .setKind(SpanKind.SPAN_KIND_CLIENT)).build();
    assertThat(spanTranslator.translate(data))
        .containsExactly(
            zipkinSpanBuilder(Span.Kind.CLIENT)
                .putTag(OtelAttributes.OTEL_STATUS_CODE.getKey(), "OK")
                .build());
  }

  @Test
  void translate_InternalKind() {
    ExportTraceServiceRequest data = ZipkinTestUtil.requestBuilderWithSpanCustomizer(span -> span
        .setKind(SpanKind.SPAN_KIND_INTERNAL)).build();
    assertThat(spanTranslator.translate(data))
        .containsExactly(
            zipkinSpanBuilder(null)
                .putTag(OtelAttributes.OTEL_STATUS_CODE.getKey(), "OK")
                .build());
  }

  @Test
  void translate_ConsumeKind() {
    ExportTraceServiceRequest data = ZipkinTestUtil.requestBuilderWithSpanCustomizer(span -> span
        .setKind(SpanKind.SPAN_KIND_CONSUMER)).build();
    assertThat(spanTranslator.translate(data))
        .containsExactly(
            zipkinSpanBuilder(Span.Kind.CONSUMER)
                .putTag(OtelAttributes.OTEL_STATUS_CODE.getKey(), "OK")
                .build());
  }

  @Test
  void translate_ProducerKind() {
    ExportTraceServiceRequest data = ZipkinTestUtil.requestBuilderWithSpanCustomizer(span -> span
        .setKind(SpanKind.SPAN_KIND_PRODUCER)).build();
    assertThat(spanTranslator.translate(data))
        .containsExactly(
            zipkinSpanBuilder(Span.Kind.PRODUCER)
                .putTag(OtelAttributes.OTEL_STATUS_CODE.getKey(), "OK")
                .build());
  }

  @Test
  void translate_ResourceServiceNameMapping() {
    ExportTraceServiceRequest data = requestBuilderWithResourceCustomizer(resource -> resource
        .clearAttributes()
        .addAttributes(stringAttribute("service.name", "super-zipkin-service")))
        .build();
    Endpoint expectedLocalEndpoint = Endpoint.newBuilder()
        .serviceName("super-zipkin-service")
        .ip("1.2.3.4")
        .build();
    Span expectedZipkinSpan =
        zipkinSpanBuilder(Span.Kind.SERVER)
            .localEndpoint(expectedLocalEndpoint)
            .putTag(OtelAttributes.OTEL_STATUS_CODE.getKey(), "OK")
            .build();
    assertThat(spanTranslator.translate(data)).containsExactly(expectedZipkinSpan);
  }

  @Test
  void translate_noServiceName() {
    ExportTraceServiceRequest data =
        requestBuilderWithResourceCustomizer(Resource.Builder::clearAttributes)
            .build();
    Span expectedZipkinSpan =
        zipkinSpanBuilder(Span.Kind.SERVER)
            .localEndpoint(null)
            .putTag(OtelAttributes.OTEL_STATUS_CODE.getKey(), "OK")
            .build();
    assertThat(spanTranslator.translate(data))
        .containsExactly(expectedZipkinSpan);
  }

  @ParameterizedTest
  @EnumSource(value = SpanKind.class, names = {"SPAN_KIND_CLIENT", "SPAN_KIND_PRODUCER"})
  void translate_RemoteEndpointMapping(SpanKind spanKind) {
    ExportTraceServiceRequest data = requestBuilderWithSpanCustomizer(span -> span
        .setKind(spanKind)
        .addAttributes(
            stringAttribute(SemanticConventionsAttributes.PEER_SERVICE, "remote-test-service"))
        .addAttributes(stringAttribute(NetworkAttributes.NETWORK_PEER_ADDRESS.getKey(), "8.8.8.8"))
        .addAttributes(longAttribute(NetworkAttributes.NETWORK_PEER_PORT.getKey(), 42L)))
        .build();

    Endpoint expectedRemoteEndpoint = Endpoint.newBuilder()
        .serviceName("remote-test-service")
        .ip("8.8.8.8")
        .port(42)
        .build();

    Span expectedSpan =
        zipkinSpanBuilder(SpanTranslator.toSpanKind(spanKind))
            .remoteEndpoint(expectedRemoteEndpoint)
            .putTag(SemanticConventionsAttributes.PEER_SERVICE, "remote-test-service")
            .putTag(NetworkAttributes.NETWORK_PEER_ADDRESS.getKey(), "8.8.8.8")
            .putTag(NetworkAttributes.NETWORK_PEER_PORT.getKey(), "42")
            .putTag(OtelAttributes.OTEL_STATUS_CODE.getKey(), "OK")
            .build();

    assertThat(spanTranslator.translate(data))
        .containsExactly(expectedSpan);
  }

  @ParameterizedTest
  @EnumSource(value = SpanKind.class, names = {"SPAN_KIND_SERVER", "SPAN_KIND_CONSUMER",
      "SPAN_KIND_INTERNAL", "SPAN_KIND_UNSPECIFIED"})
  void translate_RemoteEndpointMappingWhenKindIsNotClientOrProducer(SpanKind spanKind) {
    ExportTraceServiceRequest data = requestBuilderWithSpanCustomizer(span -> span
        .setKind(spanKind)
        .addAttributes(
            stringAttribute(SemanticConventionsAttributes.PEER_SERVICE, "remote-test-service"))
        .addAttributes(stringAttribute(NetworkAttributes.NETWORK_PEER_ADDRESS.getKey(), "8.8.8.8"))
        .addAttributes(longAttribute(NetworkAttributes.NETWORK_PEER_PORT.getKey(), 42L)))
        .build();

    Span expectedSpan =
        zipkinSpanBuilder(SpanTranslator.toSpanKind(spanKind))
            .remoteEndpoint(null)
            .putTag(SemanticConventionsAttributes.PEER_SERVICE, "remote-test-service")
            .putTag(NetworkAttributes.NETWORK_PEER_ADDRESS.getKey(), "8.8.8.8")
            .putTag(NetworkAttributes.NETWORK_PEER_PORT.getKey(), "42")
            .putTag(OtelAttributes.OTEL_STATUS_CODE.getKey(), "OK")
            .build();

    assertThat(spanTranslator.translate(data))
        .containsExactly(expectedSpan);
  }

  @ParameterizedTest
  @EnumSource(value = SpanKind.class, names = {"SPAN_KIND_CLIENT", "SPAN_KIND_PRODUCER"})
  void translate_RemoteEndpointMappingWhenServiceNameAndPeerAddressAreMissing(SpanKind spanKind) {
    ExportTraceServiceRequest data = requestBuilderWithSpanCustomizer(span -> span
        .setKind(spanKind)
        .addAttributes(longAttribute(NetworkAttributes.NETWORK_PEER_PORT.getKey(), 42L)))
        .build();

    Span expectedSpan =
        zipkinSpanBuilder(SpanTranslator.toSpanKind(spanKind))
            .remoteEndpoint(null)
            .putTag(NetworkAttributes.NETWORK_PEER_PORT.getKey(), "42")
            .putTag(OtelAttributes.OTEL_STATUS_CODE.getKey(), "OK")
            .build();

    assertThat(spanTranslator.translate(data))
        .containsExactly(expectedSpan);
  }

  @ParameterizedTest
  @EnumSource(value = SpanKind.class, names = {"SPAN_KIND_CLIENT", "SPAN_KIND_PRODUCER"})
  void translate_RemoteEndpointMappingWhenServiceNameIsMissingButPeerAddressExists(
      SpanKind spanKind) {
    ExportTraceServiceRequest data = requestBuilderWithSpanCustomizer(span -> span
        .setKind(spanKind)
        .addAttributes(stringAttribute(NetworkAttributes.NETWORK_PEER_ADDRESS.getKey(), "8.8.8.8"))
        .addAttributes(longAttribute(NetworkAttributes.NETWORK_PEER_PORT.getKey(), 42L)))
        .build();

    Endpoint expectedRemoteEndpoint = Endpoint.newBuilder()
        .serviceName("8.8.8.8")
        .ip("8.8.8.8")
        .port(42)
        .build();

    Span expectedSpan =
        zipkinSpanBuilder(SpanTranslator.toSpanKind(spanKind))
            .remoteEndpoint(expectedRemoteEndpoint)
            .putTag(NetworkAttributes.NETWORK_PEER_ADDRESS.getKey(), "8.8.8.8")
            .putTag(NetworkAttributes.NETWORK_PEER_PORT.getKey(), "42")
            .putTag(OtelAttributes.OTEL_STATUS_CODE.getKey(), "OK")
            .build();

    assertThat(spanTranslator.translate(data))
        .containsExactly(expectedSpan);
  }

  @ParameterizedTest
  @EnumSource(value = SpanKind.class, names = {"SPAN_KIND_CLIENT", "SPAN_KIND_PRODUCER"})
  void translate_RemoteEndpointMappingWhenPortIsMissing(SpanKind spanKind) {
    ExportTraceServiceRequest data = requestBuilderWithSpanCustomizer(span -> span
        .setKind(spanKind)
        .addAttributes(
            stringAttribute(SemanticConventionsAttributes.PEER_SERVICE, "remote-test-service"))
        .addAttributes(stringAttribute(NetworkAttributes.NETWORK_PEER_ADDRESS.getKey(), "8.8.8.8")))
        .build();

    Endpoint expectedRemoteEndpoint = Endpoint.newBuilder()
        .serviceName("remote-test-service")
        .ip("8.8.8.8")
        .build();

    Span expectedSpan =
        zipkinSpanBuilder(SpanTranslator.toSpanKind(spanKind))
            .remoteEndpoint(expectedRemoteEndpoint)
            .putTag(SemanticConventionsAttributes.PEER_SERVICE, "remote-test-service")
            .putTag(NetworkAttributes.NETWORK_PEER_ADDRESS.getKey(), "8.8.8.8")
            .putTag(OtelAttributes.OTEL_STATUS_CODE.getKey(), "OK")
            .build();

    assertThat(spanTranslator.translate(data))
        .containsExactly(expectedSpan);
  }

  @Test
  void translate_WithAttributes() {
    ExportTraceServiceRequest data = requestBuilderWithSpanCustomizer(span -> span
        .setKind(SpanKind.SPAN_KIND_CLIENT)
        .addAttributes(stringAttribute("string", "string value"))
        .addAttributes(attribute("boolean", av -> av.setBoolValue(false)))
        .addAttributes(longAttribute("long", 9999L))
        .addAttributes(attribute("double", av -> av.setDoubleValue(222.333)))
        .addAttributes(attribute("booleanArray", av -> av.setArrayValue(ArrayValue.newBuilder()
            .addValues(AnyValue.newBuilder().setBoolValue(true))
            .addValues(AnyValue.newBuilder().setBoolValue(false)))))
        .addAttributes(attribute("stringArray", av -> av.setArrayValue(ArrayValue.newBuilder()
            .addValues(AnyValue.newBuilder().setStringValue("Hello")))))
        .addAttributes(attribute("doubleArray", av -> av.setArrayValue(ArrayValue.newBuilder()
            .addValues(AnyValue.newBuilder().setDoubleValue(32.33))
            .addValues(AnyValue.newBuilder().setDoubleValue(-98.3)))))
        .addAttributes(attribute("longArray", av -> av.setArrayValue(ArrayValue.newBuilder()
            .addValues(AnyValue.newBuilder().setIntValue(32L))
            .addValues(AnyValue.newBuilder().setIntValue(999L))))))
        .build();

    Span expectedSpan =
        zipkinSpanBuilder(Span.Kind.CLIENT)
            .putTag("string", "string value")
            .putTag("boolean", "false")
            .putTag("long", "9999")
            .putTag("double", "222.333")
            .putTag("booleanArray", "true,false")
            .putTag("stringArray", "Hello")
            .putTag("doubleArray", "32.33,-98.3")
            .putTag("longArray", "32,999")
            .putTag(OtelAttributes.OTEL_STATUS_CODE.getKey(), "OK")
            .build();

    assertThat(spanTranslator.translate(data))
        .containsExactly(expectedSpan);
  }

  @Test
  void translate_WithInstrumentationLibraryInfo() {
    ExportTraceServiceRequest data = requestBuilderWithScopeCustomizer(scope -> scope
        .setName("io.opentelemetry.auto")
        .setVersion("1.0.0"))
        .build();

    Span expectedSpan =
        zipkinSpanBuilder(Span.Kind.SERVER)
            .putTag(OtelAttributes.OTEL_SCOPE_NAME.getKey(), "io.opentelemetry.auto")
            .putTag(OtelAttributes.OTEL_SCOPE_VERSION.getKey(), "1.0.0")
            .putTag(OtelAttributes.OTEL_STATUS_CODE.getKey(), "OK")
            .build();

    assertThat(spanTranslator.translate(data))
        .containsExactly(expectedSpan);
  }

  @Test
  void translate_AlreadyHasHttpStatusInfo() {
    ExportTraceServiceRequest data = requestBuilderWithSpanCustomizer(span -> span
        .setKind(SpanKind.SPAN_KIND_CLIENT)
        .addAttributes(longAttribute("http.response.status.code", 404))
        .addAttributes(stringAttribute("error", "A user provided error"))
        .setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_ERROR).build()))
        .build();

    Span expectedSpan =
        zipkinSpanBuilder(Span.Kind.CLIENT)
            .putTag("http.response.status.code", "404")
            .putTag("error", "A user provided error")
            .putTag(OtelAttributes.OTEL_STATUS_CODE.getKey(), "ERROR")
            .build();

    assertThat(spanTranslator.translate(data))
        .containsExactly(expectedSpan);
  }

  @Test
  void translate_WithRpcTimeoutErrorStatus_WithTimeoutErrorDescription() {
    ExportTraceServiceRequest data = requestBuilderWithSpanCustomizer(span -> span
        .setKind(SpanKind.SPAN_KIND_SERVER)
        .addAttributes(stringAttribute("rpc.service", "my service name"))
        .setStatus(Status.newBuilder()
            .setCode(Status.StatusCode.STATUS_CODE_ERROR)
            .setMessage("timeout")
            .build()))
        .build();

    Span expectedSpan =
        zipkinSpanBuilder(Span.Kind.SERVER)
            .putTag("rpc.service", "my service name")
            .putTag("error", "timeout")
            .putTag(OtelAttributes.OTEL_STATUS_CODE.getKey(), "ERROR")
            .build();

    assertThat(spanTranslator.translate(data))
        .containsExactly(expectedSpan);
  }

  @Test
  void translate_WithRpcErrorStatus_WithEmptyErrorDescription() {
    ExportTraceServiceRequest data = requestBuilderWithSpanCustomizer(span -> span
        .setKind(SpanKind.SPAN_KIND_SERVER)
        .addAttributes(stringAttribute("rpc.service", "my service name"))
        .setStatus(Status.newBuilder()
            .setCode(Status.StatusCode.STATUS_CODE_ERROR)
            .setMessage("")
            .build()))
        .build();

    Span expectedSpan =
        zipkinSpanBuilder(Span.Kind.SERVER)
            .putTag("rpc.service", "my service name")
            .putTag("error", "")
            .putTag(OtelAttributes.OTEL_STATUS_CODE.getKey(), "ERROR")
            .build();

    assertThat(spanTranslator.translate(data))
        .containsExactly(expectedSpan);
  }

  @Test
  void translate_WithRpcUnsetStatus() {
    ExportTraceServiceRequest data = requestBuilderWithSpanCustomizer(span -> span
        .setKind(SpanKind.SPAN_KIND_SERVER)
        .addAttributes(stringAttribute("rpc.service", "my service name"))
        .setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_UNSET).build()))
        .build();

    Span expectedSpan =
        zipkinSpanBuilder(Span.Kind.SERVER)
            .putTag("rpc.service", "my service name")
            .build();

    assertThat(spanTranslator.translate(data))
        .containsExactly(expectedSpan);
  }

  @Test
  void translate_WithDuplicateKeys() {
    ExportTraceServiceRequest data = requestBuilderWithSpanCustomizer(span -> span
        .setKind(SpanKind.SPAN_KIND_SERVER)
        .addAttributes(stringAttribute("foo", "bar1"))
        .addAttributes(stringAttribute("foo", "bar2"))
        .setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_UNSET).build()))
        .build();

    Span expectedSpan =
        zipkinSpanBuilder(Span.Kind.SERVER)
            .putTag("foo", "bar2")
            .putTag(SpanTranslator.OTEL_DROPPED_ATTRIBUTES_COUNT, "1")
            .build();

    assertThat(spanTranslator.translate(data))
        .containsExactly(expectedSpan);
  }

  @Test
  void translate_WithResourceAttributes() {
    ExportTraceServiceRequest data = requestBuilder(resource -> resource
            .addAttributes(stringAttribute("java.version", "21.0.4"))
            .addAttributes(stringAttribute("os.name", "Linux"))
            .addAttributes(stringAttribute("os.arch", "amd64"))
            .addAttributes(stringAttribute("hostname", "localhost")),
        Function.identity(), span -> span
            .setKind(SpanKind.SPAN_KIND_SERVER)
            .addAttributes(stringAttribute("foo", "bar1"))
            .setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_UNSET).build()))
        .build();

    Span expectedSpan =
        zipkinSpanBuilder(Span.Kind.SERVER)
            .putTag("foo", "bar1")
            .putTag("java.version", "21.0.4")
            .putTag("os.name", "Linux")
            .putTag("os.arch", "amd64")
            .putTag("hostname", "localhost")
            .build();

    assertThat(spanTranslator.translate(data))
        .containsExactly(expectedSpan);
  }

  @Test
  void translate_WithResourceAttributes_prefixed() {
    SpanTranslator spanTranslator = new SpanTranslator(DefaultOtelResourceMapper.newBuilder()
        .resourceAttributePrefix("otel.resources.")
        .build());
    ExportTraceServiceRequest data = requestBuilder(resource -> resource
            .addAttributes(stringAttribute("java.version", "21.0.4"))
            .addAttributes(stringAttribute("os.name", "Linux"))
            .addAttributes(stringAttribute("os.arch", "amd64"))
            .addAttributes(stringAttribute("hostname", "localhost")),
        Function.identity(), span -> span
            .setKind(SpanKind.SPAN_KIND_SERVER)
            .addAttributes(stringAttribute("foo", "bar1"))
            .setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_UNSET).build()))
        .build();

    Span expectedSpan =
        zipkinSpanBuilder(Span.Kind.SERVER)
            .putTag("foo", "bar1")
            .putTag("otel.resources.java.version", "21.0.4")
            .putTag("otel.resources.os.name", "Linux")
            .putTag("otel.resources.os.arch", "amd64")
            .putTag("otel.resources.hostname", "localhost")
            .build();

    assertThat(spanTranslator.translate(data))
        .containsExactly(expectedSpan);
  }
}
