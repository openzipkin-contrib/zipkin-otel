/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.collector.otel.http;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.protobuf.ByteString;
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
import zipkin2.Endpoint;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * SpanTranslator converts OpenTelemetry Spans to Zipkin Spans It is based, in part, on
 * code from
 * https://github.com/open-telemetry/opentelemetry-java/blob/ad120a5bff0887dffedb9c73af8e8e0aeb63659a/exporters/zipkin/src/main/java/io/opentelemetry/exporter/zipkin/OtelToZipkinSpanTransformer.java
 *
 * @see <a href=
 * "https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/sdk_exporters/zipkin.md#status">https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/sdk_exporters/zipkin.md#status</a>
 */
final class SpanTranslator {

	static final String OTEL_DROPPED_ATTRIBUTES_COUNT = "otel.dropped_attributes_count";

	static final String OTEL_DROPPED_EVENTS_COUNT = "otel.dropped_events_count";

	static final String ERROR_TAG = "error";

	final OtelResourceMapper resourceMapper;

	SpanTranslator(OtelResourceMapper resourceMapper) {
		this.resourceMapper = resourceMapper;
	}

	SpanTranslator() {
		this(DefaultOtelResourceMapper.create());
	}

	List<zipkin2.Span> translate(ExportTraceServiceRequest otelSpans) {
		List<zipkin2.Span> spans = new ArrayList<>();
		List<ResourceSpans> spansList = otelSpans.getResourceSpansList();
		for (ResourceSpans resourceSpans : spansList) {
			Resource resource = resourceSpans.getResource();
			for (ScopeSpans scopeSpans : resourceSpans.getScopeSpansList()) {
				InstrumentationScope scope = scopeSpans.getScope();
				for (io.opentelemetry.proto.trace.v1.Span span : scopeSpans.getSpansList()) {
					spans.add(generateSpan(span, scope, resource));
				}
			}
		}
		return spans;
	}

	/**
	 * Creates an instance of a Zipkin Span from an OpenTelemetry SpanData instance.
	 * @param spanData an OpenTelemetry spanData instance
	 * @param scope InstrumentationScope of the span
	 * @return a new Zipkin Span
	 */
	private zipkin2.Span generateSpan(Span spanData, InstrumentationScope scope, Resource resource) {
		long startTimestamp = nanoToMills(spanData.getStartTimeUnixNano());
		long endTimestamp = nanoToMills(spanData.getEndTimeUnixNano());
		Map<String, AnyValue> attributesMap = spanData.getAttributesList()
			.stream()
			.collect(Collectors.toMap(KeyValue::getKey, KeyValue::getValue,
					(a, b) -> b /* The latter wins */));
		zipkin2.Span.Builder spanBuilder = zipkin2.Span.newBuilder();
		byte[] traceIdBytes = spanData.getTraceId().toByteArray();
		long high = bytesToLong(traceIdBytes, 0);
		long low = bytesToLong(traceIdBytes, 8);
		spanBuilder.traceId(high, low)
			.id(bytesToLong(spanData.getSpanId().toByteArray(), 0))
			.kind(toSpanKind(spanData.getKind()))
			.name(spanData.getName())
			.timestamp(nanoToMills(spanData.getStartTimeUnixNano()))
			.duration(Math.max(1, endTimestamp - startTimestamp))
			.localEndpoint(getLocalEndpoint(attributesMap, resource))
			.remoteEndpoint(getRemoteEndpoint(attributesMap, spanData.getKind()));
		ByteString parentSpanId = spanData.getParentSpanId();
		if (!parentSpanId.isEmpty()) {
			long parentId = bytesToLong(parentSpanId.toByteArray(), 0);
			if (parentId != 0) {
				spanBuilder.parentId(parentId);
			}
		}
		resourceMapper.accept(resource, spanBuilder);
		attributesMap.forEach((k, v) -> spanBuilder.putTag(k, ProtoUtils.valueToString(v)));
		// https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/common/mapping-to-non-otlp.md#dropped-attributes-count
		int droppedAttributes = spanData.getAttributesCount() - attributesMap.size();
		if (droppedAttributes > 0) {
			spanBuilder.putTag(OTEL_DROPPED_ATTRIBUTES_COUNT, String.valueOf(droppedAttributes));
		}
		Status status = spanData.getStatus();
		// https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/sdk_exporters/zipkin.md#status
		if (status.getCode() != Status.StatusCode.STATUS_CODE_UNSET) {
			String codeValue = status.getCode().toString().replace("STATUS_CODE_", ""); // either
																						// OK
																						// or
																						// ERROR
			spanBuilder.putTag(SemanticConventionsAttributes.OTEL_STATUS_CODE, codeValue);
			// add the error tag, if it isn't already in the source span.
			if (status.getCode() == StatusCode.STATUS_CODE_ERROR && !attributesMap.containsKey(ERROR_TAG)) {
				spanBuilder.putTag(ERROR_TAG, status.getMessage());
			}
		}
		// https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/common/mapping-to-non-otlp.md#instrumentationscope
		if (!scope.getName().isEmpty()) {
			spanBuilder.putTag(SemanticConventionsAttributes.OTEL_SCOPE_NAME, scope.getName());
		}
		if (!scope.getVersion().isEmpty()) {
			spanBuilder.putTag(SemanticConventionsAttributes.OTEL_SCOPE_VERSION, scope.getVersion());
		}
		for (Event eventData : spanData.getEventsList()) {
			// https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/sdk_exporters/zipkin.md#events
			String name = eventData.getName();
			List<KeyValue> attributesList = eventData.getAttributesList();
			String annotation;
			if (attributesList.isEmpty()) {
				annotation = name;
			}
			else {
				String value = ProtoUtils.kvListToJson(attributesList);
				annotation = "\"" + name + "\":" + value;
			}
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
		AnyValue serviceName = resource.getAttributesList()
			.stream()
			.filter(kv -> kv.getKey().equals(SemanticConventionsAttributes.SERVICE_NAME))
			.findFirst()
			.map(KeyValue::getValue)
			.orElse(null);
		if (serviceName != null) {
			Endpoint.Builder endpoint = Endpoint.newBuilder().serviceName(serviceName.getStringValue());
			AnyValue networkLocalAddress = attributesMap.get(SemanticConventionsAttributes.NETWORK_LOCAL_ADDRESS);
			AnyValue networkLocalPort = attributesMap.get(SemanticConventionsAttributes.NETWORK_LOCAL_PORT);
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
			AnyValue peerService = attributesMap.get(SemanticConventionsAttributes.PEER_SERVICE);
			AnyValue networkPeerAddress = attributesMap.get(SemanticConventionsAttributes.NETWORK_PEER_ADDRESS);
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
				AnyValue networkPeerPort = attributesMap.get(SemanticConventionsAttributes.NETWORK_PEER_PORT);
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

	static long bytesToLong(byte[] bytes, int offset) {
		if (bytes == null || bytes.length < offset + 8) {
			return 0;
		}
		return ByteBuffer.wrap(bytes, offset, 8).getLong();
	}

}
