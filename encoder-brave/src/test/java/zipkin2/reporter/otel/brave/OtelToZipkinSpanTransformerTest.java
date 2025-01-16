/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.reporter.otel.brave;

import brave.Span;
import brave.Span.Kind;
import brave.handler.MutableSpan;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span.Event;
import io.opentelemetry.proto.trace.v1.Span.SpanKind;
import io.opentelemetry.proto.trace.v1.Status;
import io.opentelemetry.proto.trace.v1.Status.StatusCode;
import io.opentelemetry.proto.trace.v1.TracesData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.opentelemetry.proto.trace.v1.Span.newBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static zipkin2.reporter.otel.brave.TagToAttribute.intAttribute;
import static zipkin2.reporter.otel.brave.TagToAttribute.stringAttribute;

// Adapted from https://github.com/open-telemetry/opentelemetry-java/blob/v1.39.0/exporters/zipkin/src/test/java/io/opentelemetry/exporter/zipkin/OtelToZipkinSpanTransformerTest.java
class OtelToZipkinSpanTransformerTest {

	static final String TRACE_ID = "d239036e7d5cec116b562147388b35bf";

	static final String SPAN_ID = "9cc1e3049173be09";

	static final String PARENT_SPAN_ID = "8b03ab423da481c5";

	private OtlpProtoV1Encoder encoder;

	@BeforeEach
	void setup() {
		encoder = OtlpProtoV1Encoder.create();
	}

	@Test
	void generateSpan_remoteParent() {
		MutableSpan data = span(Kind.SERVER);

		assertThat(encoder.translate(data)).isEqualTo(TracesData.newBuilder()
			.addResourceSpans(ResourceSpans.newBuilder()
				.setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder()
					.addAttributes(stringAttribute("service.name", "tweetiebird"))
					.addAttributes(stringAttribute("telemetry.sdk.language", "java"))
					.addAttributes(stringAttribute("telemetry.sdk.name", BraveScope.NAME))
					.addAttributes(stringAttribute("telemetry.sdk.version", BraveScope.VERSION)))
				.addScopeSpans(ScopeSpans.newBuilder()
					.setScope(InstrumentationScope.newBuilder()
						.setName(BraveScope.NAME)
						.setVersion(BraveScope.VERSION)
						.build())
					.addSpans(newBuilder().setSpanId(ByteString.fromHex(data.id()))
						.setTraceId(ByteString.fromHex(data.traceId()))
						.setParentSpanId(ByteString.fromHex(data.parentId()))
						.setName(data.name())
						.setStartTimeUnixNano(1505855794194009000L)
						.setEndTimeUnixNano(1505855799465726000L)
						.setKind(SpanKind.SPAN_KIND_SERVER)
						.setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
						.addEvents(Event.newBuilder()
							.setName("\"RECEIVED\":{}")
							.setTimeUnixNano(1505855799433901000L)
							.build())
						.addEvents(
								Event.newBuilder().setName("\"SENT\":{}").setTimeUnixNano(1505855799459486000L).build())
						.build())
					.build())
				.build())
			.build());
	}

	@Test
	void generateSpan_resourceAttributes() {
		MutableSpan data = span(Kind.SERVER);
		Map<String, String> resourceAttributes = new LinkedHashMap<>();
		resourceAttributes.put("java.version", "21.0.4");
		resourceAttributes.put("os.name", "Linux");
		resourceAttributes.put("os.arch", "amd64");
		resourceAttributes.put("hostname", "localhost");
		OtlpProtoV1Encoder encoder = OtlpProtoV1Encoder.newBuilder().resourceAttributes(resourceAttributes).build();
		assertThat(encoder.translate(data)).isEqualTo(TracesData.newBuilder()
			.addResourceSpans(ResourceSpans.newBuilder()
				.setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder()
					.addAttributes(stringAttribute("service.name", "tweetiebird"))
					.addAttributes(stringAttribute("java.version", "21.0.4"))
					.addAttributes(stringAttribute("os.name", "Linux"))
					.addAttributes(stringAttribute("os.arch", "amd64"))
					.addAttributes(stringAttribute("hostname", "localhost"))
					.addAttributes(stringAttribute("telemetry.sdk.language", "java"))
					.addAttributes(stringAttribute("telemetry.sdk.name", BraveScope.NAME))
					.addAttributes(stringAttribute("telemetry.sdk.version", BraveScope.VERSION)))
				.addScopeSpans(ScopeSpans.newBuilder()
					.setScope(InstrumentationScope.newBuilder()
						.setName(BraveScope.NAME)
						.setVersion(BraveScope.VERSION)
						.build())
					.addSpans(newBuilder().setSpanId(ByteString.fromHex(data.id()))
						.setTraceId(ByteString.fromHex(data.traceId()))
						.setParentSpanId(ByteString.fromHex(data.parentId()))
						.setName(data.name())
						.setStartTimeUnixNano(1505855794194009000L)
						.setEndTimeUnixNano(1505855799465726000L)
						.setKind(SpanKind.SPAN_KIND_SERVER)
						.setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
						.addEvents(Event.newBuilder()
							.setName("\"RECEIVED\":{}")
							.setTimeUnixNano(1505855799433901000L)
							.build())
						.addEvents(
								Event.newBuilder().setName("\"SENT\":{}").setTimeUnixNano(1505855799459486000L).build())
						.build())
					.build())
				.build())
			.build());
	}

	@Test
	void generateSpan_resourceAttributes_with_serviceName() {
		MutableSpan data = span(Kind.SERVER);
		Map<String, String> resourceAttributes = new LinkedHashMap<>();
		resourceAttributes.put("service.name", "tweetie-bird");
		resourceAttributes.put("java.version", "21.0.4");
		resourceAttributes.put("os.name", "Linux");
		resourceAttributes.put("os.arch", "amd64");
		resourceAttributes.put("hostname", "localhost");
		OtlpProtoV1Encoder encoder = OtlpProtoV1Encoder.newBuilder().resourceAttributes(resourceAttributes).build();
		assertThat(encoder.translate(data)).isEqualTo(TracesData.newBuilder()
			.addResourceSpans(ResourceSpans.newBuilder()
				.setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder()
					.addAttributes(stringAttribute("service.name", "tweetie-bird"))
					.addAttributes(stringAttribute("java.version", "21.0.4"))
					.addAttributes(stringAttribute("os.name", "Linux"))
					.addAttributes(stringAttribute("os.arch", "amd64"))
					.addAttributes(stringAttribute("hostname", "localhost"))
					.addAttributes(stringAttribute("telemetry.sdk.language", "java"))
					.addAttributes(stringAttribute("telemetry.sdk.name", BraveScope.NAME))
					.addAttributes(stringAttribute("telemetry.sdk.version", BraveScope.VERSION)))
				.addScopeSpans(ScopeSpans.newBuilder()
					.setScope(InstrumentationScope.newBuilder()
						.setName(BraveScope.NAME)
						.setVersion(BraveScope.VERSION)
						.build())
					.addSpans(newBuilder().setSpanId(ByteString.fromHex(data.id()))
						.setTraceId(ByteString.fromHex(data.traceId()))
						.setParentSpanId(ByteString.fromHex(data.parentId()))
						.setName(data.name())
						.setStartTimeUnixNano(1505855794194009000L)
						.setEndTimeUnixNano(1505855799465726000L)
						.setKind(SpanKind.SPAN_KIND_SERVER)
						.setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
						.addEvents(Event.newBuilder()
							.setName("\"RECEIVED\":{}")
							.setTimeUnixNano(1505855799433901000L)
							.build())
						.addEvents(
								Event.newBuilder().setName("\"SENT\":{}").setTimeUnixNano(1505855799459486000L).build())
						.build())
					.build())
				.build())
			.build());
	}

	@Test
	void generateSpan_subMicroDurations() {
		MutableSpan data = span(Kind.SERVER);
		data.startTimestamp(TimeUnit.NANOSECONDS.toMicros(1505855794_194009601L));
		data.finishTimestamp(TimeUnit.NANOSECONDS.toMicros(1505855794_194009999L));

		assertThat(encoder.translate(data)).isEqualTo(TracesData.newBuilder()
			.addResourceSpans(ResourceSpans.newBuilder()
				.setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder()
					.addAttributes(stringAttribute("service.name", "tweetiebird"))
					.addAttributes(stringAttribute("telemetry.sdk.language", "java"))
					.addAttributes(stringAttribute("telemetry.sdk.name", BraveScope.NAME))
					.addAttributes(stringAttribute("telemetry.sdk.version", BraveScope.VERSION))
					.build())
				.addScopeSpans(ScopeSpans.newBuilder()
					.setScope(InstrumentationScope.newBuilder()
						.setName(BraveScope.NAME)
						.setVersion(BraveScope.VERSION)
						.build())
					.addSpans(newBuilder().setSpanId(ByteString.fromHex(data.id()))
						.setTraceId(ByteString.fromHex(data.traceId()))
						.setParentSpanId(ByteString.fromHex(data.parentId()))
						.setName(data.name())
						.setStartTimeUnixNano(1505855794_194009000L) // We lose precision
						.setEndTimeUnixNano(1505855794_194009000L) // We lose precision
						.setKind(SpanKind.SPAN_KIND_SERVER)
						.setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
						.addEvents(Event.newBuilder()
							.setName("\"RECEIVED\":{}")
							.setTimeUnixNano(1505855799433901000L)
							.build())
						.addEvents(
								Event.newBuilder().setName("\"SENT\":{}").setTimeUnixNano(1505855799459486000L).build())
						.build())
					.build())
				.build())
			.build());
	}

	@Test
	void generateSpan_ServerKind() {
		MutableSpan data = span(Kind.SERVER);

		assertThat(encoder.translate(data)).isEqualTo(TracesData.newBuilder()
			.addResourceSpans(ResourceSpans.newBuilder()
				.setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder()
					.addAttributes(stringAttribute("service.name", "tweetiebird"))
					.addAttributes(stringAttribute("telemetry.sdk.language", "java"))
					.addAttributes(stringAttribute("telemetry.sdk.name", BraveScope.NAME))
					.addAttributes(stringAttribute("telemetry.sdk.version", BraveScope.VERSION)))
				.addScopeSpans(ScopeSpans.newBuilder()
					.setScope(InstrumentationScope.newBuilder()
						.setName(BraveScope.NAME)
						.setVersion(BraveScope.VERSION)
						.build())
					.addSpans(newBuilder().setSpanId(ByteString.fromHex(data.id()))
						.setTraceId(ByteString.fromHex(data.traceId()))
						.setParentSpanId(ByteString.fromHex(data.parentId()))
						.setName(data.name())
						.setStartTimeUnixNano(1505855794194009000L)
						.setEndTimeUnixNano(1505855799465726000L)
						.setKind(SpanKind.SPAN_KIND_SERVER)
						.setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
						.addEvents(Event.newBuilder()
							.setName("\"RECEIVED\":{}")
							.setTimeUnixNano(1505855799433901000L)
							.build())
						.addEvents(
								Event.newBuilder().setName("\"SENT\":{}").setTimeUnixNano(1505855799459486000L).build())
						.build())
					.build())
				.build())
			.build());
	}

	@Test
	void generateSpan_ClientKind() {
		MutableSpan data = span(Kind.CLIENT);

		assertThat(encoder.translate(data)).isEqualTo(TracesData.newBuilder()
			.addResourceSpans(ResourceSpans.newBuilder()
				.setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder()
					.addAttributes(stringAttribute("service.name", "tweetiebird"))
					.addAttributes(stringAttribute("telemetry.sdk.language", "java"))
					.addAttributes(stringAttribute("telemetry.sdk.name", BraveScope.NAME))
					.addAttributes(stringAttribute("telemetry.sdk.version", BraveScope.VERSION)))
				.addScopeSpans(ScopeSpans.newBuilder()
					.setScope(InstrumentationScope.newBuilder()
						.setName(BraveScope.NAME)
						.setVersion(BraveScope.VERSION)
						.build())
					.addSpans(newBuilder().setSpanId(ByteString.fromHex(data.id()))
						.setTraceId(ByteString.fromHex(data.traceId()))
						.setParentSpanId(ByteString.fromHex(data.parentId()))
						.setName(data.name())
						.setStartTimeUnixNano(1505855794194009000L)
						.setEndTimeUnixNano(1505855799465726000L)
						.setKind(SpanKind.SPAN_KIND_CLIENT)
						.setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
						.addEvents(Event.newBuilder()
							.setName("\"RECEIVED\":{}")
							.setTimeUnixNano(1505855799433901000L)
							.build())
						.addEvents(
								Event.newBuilder().setName("\"SENT\":{}").setTimeUnixNano(1505855799459486000L).build())
						.build())
					.build())
				.build())
			.build());
	}

	@Test
	void generateSpan_DefaultKind() {
		MutableSpan data = span(null);

		assertThat(encoder.translate(data)).isEqualTo(TracesData.newBuilder()
			.addResourceSpans(ResourceSpans.newBuilder()
				.setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder()
					.addAttributes(stringAttribute("service.name", "tweetiebird"))
					.addAttributes(stringAttribute("telemetry.sdk.language", "java"))
					.addAttributes(stringAttribute("telemetry.sdk.name", BraveScope.NAME))
					.addAttributes(stringAttribute("telemetry.sdk.version", BraveScope.VERSION)))
				.addScopeSpans(ScopeSpans.newBuilder()
					.setScope(InstrumentationScope.newBuilder()
						.setName(BraveScope.NAME)
						.setVersion(BraveScope.VERSION)
						.build())
					.addSpans(newBuilder().setSpanId(ByteString.fromHex(data.id()))
						.setTraceId(ByteString.fromHex(data.traceId()))
						.setParentSpanId(ByteString.fromHex(data.parentId()))
						.setName(data.name())
						.setKind(SpanTranslator.DEFAULT_KIND)
						.setStartTimeUnixNano(1505855794194009000L)
						.setEndTimeUnixNano(1505855799465726000L)
						.setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
						.addEvents(Event.newBuilder()
							.setName("\"RECEIVED\":{}")
							.setTimeUnixNano(1505855799433901000L)
							.build())
						.addEvents(
								Event.newBuilder().setName("\"SENT\":{}").setTimeUnixNano(1505855799459486000L).build())
						.build())
					.build())
				.build())
			.build());
	}

	@Test
	void generateSpan_ConsumeKind() {
		MutableSpan data = span(Kind.CONSUMER);

		assertThat(encoder.translate(data)).isEqualTo(TracesData.newBuilder()
			.addResourceSpans(ResourceSpans.newBuilder()
				.setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder()
					.addAttributes(stringAttribute("service.name", "tweetiebird"))
					.addAttributes(stringAttribute("telemetry.sdk.language", "java"))
					.addAttributes(stringAttribute("telemetry.sdk.name", BraveScope.NAME))
					.addAttributes(stringAttribute("telemetry.sdk.version", BraveScope.VERSION)))
				.addScopeSpans(ScopeSpans.newBuilder()
					.setScope(InstrumentationScope.newBuilder()
						.setName(BraveScope.NAME)
						.setVersion(BraveScope.VERSION)
						.build())
					.addSpans(newBuilder().setSpanId(ByteString.fromHex(data.id()))
						.setTraceId(ByteString.fromHex(data.traceId()))
						.setParentSpanId(ByteString.fromHex(data.parentId()))
						.setName(data.name())
						.setStartTimeUnixNano(1505855794194009000L)
						.setEndTimeUnixNano(1505855799465726000L)
						.setKind(SpanKind.SPAN_KIND_CONSUMER)
						.setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
						.addEvents(Event.newBuilder()
							.setName("\"RECEIVED\":{}")
							.setTimeUnixNano(1505855799433901000L)
							.build())
						.addEvents(
								Event.newBuilder().setName("\"SENT\":{}").setTimeUnixNano(1505855799459486000L).build())
						.build())
					.build())
				.build())
			.build());
	}

	@Test
	void generateSpan_ProducerKind() {
		MutableSpan data = span(Kind.PRODUCER);

		assertThat(encoder.translate(data)).isEqualTo(TracesData.newBuilder()
			.addResourceSpans(ResourceSpans.newBuilder()
				.setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder()
					.addAttributes(stringAttribute("service.name", "tweetiebird"))
					.addAttributes(stringAttribute("telemetry.sdk.language", "java"))
					.addAttributes(stringAttribute("telemetry.sdk.name", BraveScope.NAME))
					.addAttributes(stringAttribute("telemetry.sdk.version", BraveScope.VERSION)))
				.addScopeSpans(ScopeSpans.newBuilder()
					.setScope(InstrumentationScope.newBuilder()
						.setName(BraveScope.NAME)
						.setVersion(BraveScope.VERSION)
						.build())
					.addSpans(newBuilder().setSpanId(ByteString.fromHex(data.id()))
						.setTraceId(ByteString.fromHex(data.traceId()))
						.setParentSpanId(ByteString.fromHex(data.parentId()))
						.setName(data.name())
						.setStartTimeUnixNano(1505855794194009000L)
						.setEndTimeUnixNano(1505855799465726000L)
						.setKind(SpanKind.SPAN_KIND_PRODUCER)
						.setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
						.addEvents(Event.newBuilder()
							.setName("\"RECEIVED\":{}")
							.setTimeUnixNano(1505855799433901000L)
							.build())
						.addEvents(
								Event.newBuilder().setName("\"SENT\":{}").setTimeUnixNano(1505855799459486000L).build())
						.build())
					.build())
				.build())
			.build());
	}

	@Test
	void generateSpan_ResourceServiceNameMapping() {
		MutableSpan data = span(Kind.PRODUCER);
		data.localServiceName("super-zipkin-service");

		assertThat(encoder.translate(data)).isEqualTo(TracesData.newBuilder()
			.addResourceSpans(ResourceSpans.newBuilder()
				.setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder()
					.addAttributes(stringAttribute("service.name", "super-zipkin-service"))
					.addAttributes(stringAttribute("telemetry.sdk.language", "java"))
					.addAttributes(stringAttribute("telemetry.sdk.name", BraveScope.NAME))
					.addAttributes(stringAttribute("telemetry.sdk.version", BraveScope.VERSION)))
				.addScopeSpans(ScopeSpans.newBuilder()
					.setScope(InstrumentationScope.newBuilder()
						.setName(BraveScope.NAME)
						.setVersion(BraveScope.VERSION)
						.build())
					.addSpans(newBuilder().setSpanId(ByteString.fromHex(data.id()))
						.setTraceId(ByteString.fromHex(data.traceId()))
						.setParentSpanId(ByteString.fromHex(data.parentId()))
						.setName(data.name())
						.setStartTimeUnixNano(1505855794194009000L)
						.setEndTimeUnixNano(1505855799465726000L)
						.setKind(SpanKind.SPAN_KIND_PRODUCER)
						.setStatus(Status.newBuilder().setCode(StatusCode.STATUS_CODE_OK).build())
						.addEvents(Event.newBuilder()
							.setName("\"RECEIVED\":{}")
							.setTimeUnixNano(1505855799433901000L)
							.build())
						.addEvents(
								Event.newBuilder().setName("\"SENT\":{}").setTimeUnixNano(1505855799459486000L).build())
						.build())
					.build())
				.build())
			.build());
	}

	@Test
	void generateSpan_defaultResourceServiceName() {
		MutableSpan data = span(Kind.PRODUCER);
		data.localServiceName(null);

		assertThat(encoder.translate(data)).isEqualTo(TracesData.newBuilder()
			.addResourceSpans(ResourceSpans.newBuilder()
				.setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder()
					.addAttributes(stringAttribute("service.name", SpanTranslator.DEFAULT_SERVICE_NAME))
					.addAttributes(stringAttribute("telemetry.sdk.language", "java"))
					.addAttributes(stringAttribute("telemetry.sdk.name", BraveScope.NAME))
					.addAttributes(stringAttribute("telemetry.sdk.version", BraveScope.VERSION)))
				.addScopeSpans(ScopeSpans.newBuilder()
					.setScope(InstrumentationScope.newBuilder()
						.setName(BraveScope.NAME)
						.setVersion(BraveScope.VERSION)
						.build())
					.addSpans(newBuilder().setSpanId(ByteString.fromHex(data.id()))
						.setTraceId(ByteString.fromHex(data.traceId()))
						.setParentSpanId(ByteString.fromHex(data.parentId()))
						.setName(data.name())
						.setStartTimeUnixNano(1505855794194009000L)
						.setEndTimeUnixNano(1505855799465726000L)
						.setKind(SpanKind.SPAN_KIND_PRODUCER)
						.setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
						.addEvents(Event.newBuilder()
							.setName("\"RECEIVED\":{}")
							.setTimeUnixNano(1505855799433901000L)
							.build())
						.addEvents(
								Event.newBuilder().setName("\"SENT\":{}").setTimeUnixNano(1505855799459486000L).build())
						.build())
					.build())
				.build())
			.build());
	}

	@ParameterizedTest
	@EnumSource(value = Kind.class, names = { "CLIENT", "PRODUCER" })
	void generateSpan_RemoteEndpointMapping(Kind spanKind) {
		MutableSpan data = new MutableSpan();
		data.remoteServiceName("remote-test-service");
		data.remoteIp("8.8.8.8");
		data.remotePort(42);
		data.kind(spanKind);

		assertThat(encoder.translate(data)).isEqualTo(TracesData.newBuilder()
			.addResourceSpans(ResourceSpans.newBuilder()
				.setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder()
					.addAttributes(stringAttribute("service.name", SpanTranslator.DEFAULT_SERVICE_NAME))
					.addAttributes(stringAttribute("telemetry.sdk.language", "java"))
					.addAttributes(stringAttribute("telemetry.sdk.name", BraveScope.NAME))
					.addAttributes(stringAttribute("telemetry.sdk.version", BraveScope.VERSION)))
				.addScopeSpans(ScopeSpans.newBuilder()
					.setScope(InstrumentationScope.newBuilder()
						.setName(BraveScope.NAME)
						.setVersion(BraveScope.VERSION)
						.build())
					.addSpans(newBuilder().setName(SpanTranslator.DEFAULT_SPAN_NAME)
						.setSpanId(SpanTranslator.INVALID_SPAN_ID)
						.setTraceId(SpanTranslator.INVALID_TRACE_ID)
						.setKind(toSpanKind(spanKind))
						.addAttributes(stringAttribute("server.address", "8.8.8.8"))
						.addAttributes(intAttribute("server.port", 42))
						.addAttributes(stringAttribute("peer.service", "remote-test-service"))
						.setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
						.build())
					.build())
				.build())
			.build());
	}

	@ParameterizedTest
	@EnumSource(value = Kind.class, names = { "SERVER", "CONSUMER" })
	void generateSpan_RemoteEndpointMappingWhenKindIsNotClientOrProducer(Kind spanKind) {
		MutableSpan data = new MutableSpan();
		data.remoteServiceName("remote-test-service");
		data.remoteIp("8.8.8.8");
		data.remotePort(42);
		data.kind(spanKind);

		assertThat(encoder.translate(data)).isEqualTo(TracesData.newBuilder()
			.addResourceSpans(ResourceSpans.newBuilder()
				.setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder()
					.addAttributes(stringAttribute("service.name", SpanTranslator.DEFAULT_SERVICE_NAME))
					.addAttributes(stringAttribute("telemetry.sdk.language", "java"))
					.addAttributes(stringAttribute("telemetry.sdk.name", BraveScope.NAME))
					.addAttributes(stringAttribute("telemetry.sdk.version", BraveScope.VERSION)))
				.addScopeSpans(ScopeSpans.newBuilder()
					.setScope(InstrumentationScope.newBuilder()
						.setName(BraveScope.NAME)
						.setVersion(BraveScope.VERSION)
						.build())
					.addSpans(newBuilder().setName(SpanTranslator.DEFAULT_SPAN_NAME)
						.setSpanId(SpanTranslator.INVALID_SPAN_ID)
						.setTraceId(SpanTranslator.INVALID_TRACE_ID)
						.setKind(toSpanKind(spanKind))
						.addAttributes(stringAttribute("client.address", "8.8.8.8"))
						.addAttributes(intAttribute("client.port", 42))
						.addAttributes(stringAttribute("peer.service", "remote-test-service"))
						.setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
						.build())
					.build())
				.build())
			.build());
	}

	@ParameterizedTest
	@EnumSource(value = Kind.class, names = { "CLIENT", "PRODUCER" })
	void generateSpan_RemoteEndpointMappingWhenServiceNameIsMissing(Kind spanKind) {
		MutableSpan data = new MutableSpan();
		data.remoteIp("8.8.8.8");
		data.remotePort(42);
		data.kind(spanKind);

		assertThat(encoder.translate(data)).isEqualTo(TracesData.newBuilder()
			.addResourceSpans(ResourceSpans.newBuilder()
				.setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder()
					.addAttributes(stringAttribute("service.name", SpanTranslator.DEFAULT_SERVICE_NAME))
					.addAttributes(stringAttribute("telemetry.sdk.language", "java"))
					.addAttributes(stringAttribute("telemetry.sdk.name", BraveScope.NAME))
					.addAttributes(stringAttribute("telemetry.sdk.version", BraveScope.VERSION)))
				.addScopeSpans(ScopeSpans.newBuilder()
					.setScope(InstrumentationScope.newBuilder()
						.setName(BraveScope.NAME)
						.setVersion(BraveScope.VERSION)
						.build())
					.addSpans(newBuilder().setName(SpanTranslator.DEFAULT_SPAN_NAME)
						.setSpanId(SpanTranslator.INVALID_SPAN_ID)
						.setTraceId(SpanTranslator.INVALID_TRACE_ID)
						.setKind(toSpanKind(spanKind))
						.addAttributes(stringAttribute("server.address", "8.8.8.8"))
						.addAttributes(intAttribute("server.port", 42))
						.setStatus(Status.newBuilder().setCode(StatusCode.STATUS_CODE_OK).build())
						.build())
					.build())
				.build())
			.build());
	}

	@ParameterizedTest
	@EnumSource(value = Kind.class, names = { "CLIENT", "PRODUCER" })
	void generateSpan_RemoteEndpointMappingWhenPortIsMissing(Kind spanKind) {
		MutableSpan data = new MutableSpan();
		data.remoteServiceName("remote-test-service");
		data.remoteIp("8.8.8.8");
		data.kind(spanKind);

		assertThat(encoder.translate(data)).isEqualTo(TracesData.newBuilder()
			.addResourceSpans(ResourceSpans.newBuilder()
				.setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder()
					.addAttributes(stringAttribute("service.name", SpanTranslator.DEFAULT_SERVICE_NAME))
					.addAttributes(stringAttribute("telemetry.sdk.language", "java"))
					.addAttributes(stringAttribute("telemetry.sdk.name", BraveScope.NAME))
					.addAttributes(stringAttribute("telemetry.sdk.version", BraveScope.VERSION)))
				.addScopeSpans(ScopeSpans.newBuilder()
					.setScope(InstrumentationScope.newBuilder()
						.setName(BraveScope.NAME)
						.setVersion(BraveScope.VERSION)
						.build())
					.addSpans(newBuilder().setName(SpanTranslator.DEFAULT_SPAN_NAME)
						.setSpanId(SpanTranslator.INVALID_SPAN_ID)
						.setTraceId(SpanTranslator.INVALID_TRACE_ID)
						.setKind(toSpanKind(spanKind))
						.addAttributes(stringAttribute("server.address", "8.8.8.8"))
						.addAttributes(stringAttribute("peer.service", "remote-test-service"))
						.setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
						.build())
					.build())
				.build())
			.build());
	}

	@ParameterizedTest
	@EnumSource(value = Kind.class, names = { "CLIENT", "PRODUCER" })
	void generateSpan_RemoteEndpointMappingWhenIpAndPortAreMissing(Kind spanKind) {
		MutableSpan data = new MutableSpan();
		data.remoteServiceName("remote-test-service");
		data.kind(spanKind);

		assertThat(encoder.translate(data)).isEqualTo(TracesData.newBuilder()
			.addResourceSpans(ResourceSpans.newBuilder()
				.setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder()
					.addAttributes(stringAttribute("service.name", SpanTranslator.DEFAULT_SERVICE_NAME))
					.addAttributes(stringAttribute("telemetry.sdk.language", "java"))
					.addAttributes(stringAttribute("telemetry.sdk.name", BraveScope.NAME))
					.addAttributes(stringAttribute("telemetry.sdk.version", BraveScope.VERSION)))
				.addScopeSpans(ScopeSpans.newBuilder()
					.setScope(InstrumentationScope.newBuilder()
						.setName(BraveScope.NAME)
						.setVersion(BraveScope.VERSION)
						.build())
					.addSpans(newBuilder().setName(SpanTranslator.DEFAULT_SPAN_NAME)
						.setSpanId(SpanTranslator.INVALID_SPAN_ID)
						.setTraceId(SpanTranslator.INVALID_TRACE_ID)
						.setKind(toSpanKind(spanKind))
						.addAttributes(stringAttribute("peer.service", "remote-test-service"))
						.setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
						.build())
					.build())
				.build())
			.build());
	}

	@Test
	void generateSpan_WithAttributes() {
		MutableSpan data = new MutableSpan();
		data.kind(Kind.CLIENT);
		data.tag("string", "string value");
		data.tag("boolean", "false");
		data.tag("long", "9999");
		data.tag("double", "222.333");
		data.tag("booleanArray", "true,false");
		data.tag("stringArray", "Hello");
		data.tag("doubleArray", "32.33,-98.3");
		data.tag("longArray", "33,999");

		assertThat(encoder.translate(data)).isEqualTo(TracesData.newBuilder()
			.addResourceSpans(ResourceSpans.newBuilder()
				.setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder()
					.addAttributes(stringAttribute("service.name", SpanTranslator.DEFAULT_SERVICE_NAME))
					.addAttributes(stringAttribute("telemetry.sdk.language", "java"))
					.addAttributes(stringAttribute("telemetry.sdk.name", BraveScope.NAME))
					.addAttributes(stringAttribute("telemetry.sdk.version", BraveScope.VERSION)))
				.addScopeSpans(ScopeSpans.newBuilder()
					.setScope(InstrumentationScope.newBuilder()
						.setName(BraveScope.NAME)
						.setVersion(BraveScope.VERSION)
						.build())
					.addSpans(newBuilder().setName(SpanTranslator.DEFAULT_SPAN_NAME)
						.setSpanId(SpanTranslator.INVALID_SPAN_ID)
						.setTraceId(SpanTranslator.INVALID_TRACE_ID)
						.setKind(toSpanKind(Kind.CLIENT))
						.addAttributes(stringAttribute("string", "string value"))
						.addAttributes(stringAttribute("boolean", "false"))
						.addAttributes(stringAttribute("long", "9999"))
						.addAttributes(stringAttribute("double", "222.333"))
						.addAttributes(stringAttribute("booleanArray", "true,false"))
						.addAttributes(stringAttribute("stringArray", "Hello"))
						.addAttributes(stringAttribute("doubleArray", "32.33,-98.3"))
						.addAttributes(stringAttribute("longArray", "33,999"))
						.setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
						.build())
					.build())
				.build())
			.build());
	}

	@Test
	void generateSpan_WithInstrumentationLibraryInfo() {
		MutableSpan data = new MutableSpan();
		data.kind(Kind.CLIENT);

		assertThat(encoder.translate(data)).isEqualTo(TracesData.newBuilder()
			.addResourceSpans(ResourceSpans.newBuilder()
				.setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder()
					.addAttributes(stringAttribute("service.name", SpanTranslator.DEFAULT_SERVICE_NAME))
					.addAttributes(stringAttribute("telemetry.sdk.language", "java"))
					.addAttributes(stringAttribute("telemetry.sdk.name", BraveScope.NAME))
					.addAttributes(stringAttribute("telemetry.sdk.version", BraveScope.VERSION)))
				.addScopeSpans(ScopeSpans.newBuilder()
					.setScope(InstrumentationScope.newBuilder()
						.setName(BraveScope.NAME)
						.setVersion(BraveScope.VERSION)
						.build())
					.addSpans(newBuilder().setName(SpanTranslator.DEFAULT_SPAN_NAME)
						.setSpanId(SpanTranslator.INVALID_SPAN_ID)
						.setTraceId(SpanTranslator.INVALID_TRACE_ID)
						.setKind(toSpanKind(Kind.CLIENT))
						.setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
						.build())
					.build())
				.build())
			.build());
	}

	@Test
	void generateSpan_AlreadyHasHttpStatusInfo() {
		MutableSpan data = new MutableSpan();
		data.kind(Kind.CLIENT);
		data.error(new RuntimeException("A user provided error"));
		data.tag("http.response.status.code", "404");

		assertThat(encoder.translate(data)).isEqualTo(TracesData.newBuilder()
			.addResourceSpans(ResourceSpans.newBuilder()
				.setResource(io.opentelemetry.proto.resource.v1.Resource.newBuilder()
					.addAttributes(stringAttribute("service.name", SpanTranslator.DEFAULT_SERVICE_NAME))
					.addAttributes(stringAttribute("telemetry.sdk.language", "java"))
					.addAttributes(stringAttribute("telemetry.sdk.name", BraveScope.NAME))
					.addAttributes(stringAttribute("telemetry.sdk.version", BraveScope.VERSION)))
				.addScopeSpans(ScopeSpans.newBuilder()
					.setScope(InstrumentationScope.newBuilder()
						.setName(BraveScope.NAME)
						.setVersion(BraveScope.VERSION)
						.build())
					.addSpans(newBuilder().setName(SpanTranslator.DEFAULT_SPAN_NAME)
						.setSpanId(SpanTranslator.INVALID_SPAN_ID)
						.setTraceId(SpanTranslator.INVALID_TRACE_ID)
						.setKind(toSpanKind(Kind.CLIENT))
						.addAttributes(stringAttribute("http.response.status.code", "404"))
						.addAttributes(stringAttribute("error", "A user provided error"))
						.setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_ERROR).build())
						.build())
					.build())
				.build())
			.build());
	}

	static MutableSpan span(@Nullable Span.Kind kind) {
		MutableSpan mutableSpan = new MutableSpan();
		mutableSpan.traceId(TRACE_ID);
		mutableSpan.parentId(PARENT_SPAN_ID);
		mutableSpan.id(SPAN_ID);
		mutableSpan.kind(kind);
		mutableSpan.name("Recv.helloworld.Greeter.SayHello");
		mutableSpan.startTimestamp(1505855794000000L + 194009601L / 1000);
		mutableSpan.finishTimestamp(1505855799000000L + 465726528L / 1000);
		mutableSpan.localServiceName("tweetiebird");
		mutableSpan.annotate(1505855799000000L + 433901068L / 1000, "\"RECEIVED\":{}");
		mutableSpan.annotate(1505855799000000L + 459486280L / 1000, "\"SENT\":{}");
		return mutableSpan;
	}

	static SpanKind toSpanKind(Span.Kind kind) {
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
		return SpanTranslator.DEFAULT_KIND;
	}

}
