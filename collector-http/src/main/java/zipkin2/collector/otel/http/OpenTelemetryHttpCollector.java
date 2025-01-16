/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.collector.otel.http;

import com.google.protobuf.UnsafeByteOperations;
import com.linecorp.armeria.common.AggregationOptions;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.encoding.StreamDecoderFactory;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerConfigurator;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.encoding.DecodingService;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import zipkin2.Callback;
import zipkin2.Span;
import zipkin2.collector.Collector;
import zipkin2.collector.CollectorComponent;
import zipkin2.collector.CollectorMetrics;
import zipkin2.collector.CollectorSampler;
import zipkin2.storage.StorageComponent;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class OpenTelemetryHttpCollector extends CollectorComponent implements ServerConfigurator {

	public static Builder newBuilder() {
		return new Builder();
	}

	public static final class Builder extends CollectorComponent.Builder {

		Collector.Builder delegate = Collector.newBuilder(OpenTelemetryHttpCollector.class);

		CollectorMetrics metrics = CollectorMetrics.NOOP_METRICS;

		OtelResourceMapper otelResourceMapper;

		@Override
		public Builder storage(StorageComponent storageComponent) {
			delegate.storage(storageComponent);
			return this;
		}

		@Override
		public Builder metrics(CollectorMetrics metrics) {
			if (metrics == null) {
				throw new NullPointerException("metrics == null");
			}
			delegate.metrics(this.metrics = metrics.forTransport("otel/http"));
			return this;
		}

		@Override
		public Builder sampler(CollectorSampler sampler) {
			delegate.sampler(sampler);
			return this;
		}

		public Builder otelResourceMapper(OtelResourceMapper otelResourceMapper) {
			this.otelResourceMapper = otelResourceMapper;
			return this;
		}

		@Override
		public OpenTelemetryHttpCollector build() {
			return new OpenTelemetryHttpCollector(this);
		}

		Builder() {
		}

	}

	final Collector collector;

	final CollectorMetrics metrics;

	final OtelResourceMapper otelResourceMapper;

	OpenTelemetryHttpCollector(Builder builder) {
		collector = builder.delegate.build();
		metrics = builder.metrics;
		otelResourceMapper = builder.otelResourceMapper == null ? DefaultOtelResourceMapper.create()
				: builder.otelResourceMapper;
	}

	@Override
	public OpenTelemetryHttpCollector start() {
		return this;
	}

	@Override
	public String toString() {
		return "OpenTelemetryHttpCollector{}";
	}

	public OtelResourceMapper getOtelResourceMapper() {
		return otelResourceMapper;
	}

	/**
	 * Reconfigures the service per
	 * https://opentelemetry.io/docs/specs/otlp/#otlphttp-request
	 */
	@Override
	public void reconfigure(ServerBuilder sb) {
		sb.decorator(DecodingService.newDecorator(StreamDecoderFactory.gzip()));
		sb.service("/v1/traces", new OtlpProtoV1TracesHttpService(this));
		sb.service("/v1/logs", new OtlpProtoV1LogsHttpService(this));
	}

	static final class OtlpProtoV1TracesHttpService extends AbstractHttpService {

		private static final Logger LOG = Logger.getLogger(OtlpProtoV1TracesHttpService.class.getName());

		final OpenTelemetryHttpCollector collector;

		final SpanTranslator spanTranslator;

		OtlpProtoV1TracesHttpService(OpenTelemetryHttpCollector collector) {
			this.collector = collector;
			this.spanTranslator = new SpanTranslator(collector.otelResourceMapper);
		}

		@Override
		protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
			CompletableCallback result = new CompletableCallback();
			req.aggregate(AggregationOptions.usePooledObjects(ctx.alloc(), ctx.eventLoop())).handle((msg, t) -> {
				if (t != null) {
					collector.metrics.incrementMessagesDropped();
					result.onError(t);
					return null;
				}
				try (HttpData content = msg.content()) {
					if (content.isEmpty()) {
						result.onSuccess(null);
						return null;
					}
					collector.metrics.incrementBytes(content.length());
					try {
						ExportTraceServiceRequest request = ExportTraceServiceRequest
							.parseFrom(UnsafeByteOperations.unsafeWrap(content.byteBuf().nioBuffer()).newCodedInput());
						collector.metrics.incrementMessages();
						try {
							List<Span> spans = spanTranslator.translate(request);
							collector.collector.accept(spans, result);
						}
						catch (RuntimeException e) {
							// If the span is invalid, an exception such as
							// IllegalArgumentException will be thrown.
							int spanSize = request.getResourceSpansList()
								.stream()
								.flatMap(rs -> rs.getScopeSpansList().stream())
								.mapToInt(ScopeSpans::getSpansCount)
								.sum();
							collector.metrics.incrementSpansDropped(spanSize);
							LOG.log(Level.WARNING, "Unable to translate the spans:", e);
							result.onError(e);
						}
					}
					catch (IOException e) {
						collector.metrics.incrementMessagesDropped();
						LOG.log(Level.WARNING, "Unable to parse the request:", e);
						result.onError(e);
					}
					return null;
				}
			});
			return HttpResponse.of(result);
		}

	}

	static final class OtlpProtoV1LogsHttpService extends AbstractHttpService {

		private static final Logger LOG = Logger.getLogger(OtlpProtoV1LogsHttpService.class.getName());

		final OpenTelemetryHttpCollector collector;

		final LogEventTranslator logEventTranslator;

		OtlpProtoV1LogsHttpService(OpenTelemetryHttpCollector collector) {
			this.collector = collector;
			this.logEventTranslator = LogEventTranslator.newBuilder()
				.otelResourceMapper(collector.otelResourceMapper)
				.build();
		}

		@Override
		protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) throws Exception {
			CompletableCallback result = new CompletableCallback();
			req.aggregate(AggregationOptions.usePooledObjects(ctx.alloc(), ctx.eventLoop())).handle((msg, t) -> {
				if (t != null) {
					collector.metrics.incrementMessagesDropped();
					result.onError(t);
					return null;
				}
				try (HttpData content = msg.content()) {
					if (content.isEmpty()) {
						result.onSuccess(null);
						return null;
					}
					collector.metrics.incrementBytes(content.length());
					try {
						ExportLogsServiceRequest request = ExportLogsServiceRequest
							.parseFrom(UnsafeByteOperations.unsafeWrap(content.byteBuf().nioBuffer()).newCodedInput());
						collector.metrics.incrementMessages();
						try {
							List<Span> spans = logEventTranslator.translate(request);
							collector.collector.accept(spans, result);
						}
						catch (RuntimeException e) {
							// TODO count dropped spans
							LOG.log(Level.WARNING, "Unable to translate the logs:", e);
							result.onError(e);
						}
					}
					catch (IOException e) {
						collector.metrics.incrementMessagesDropped();
						LOG.log(Level.WARNING, "Unable to parse the request:", e);
						result.onError(e);
					}
					return null;
				}
			});
			return HttpResponse.of(result);
		}

	}

	static final class CompletableCallback extends CompletableFuture<HttpResponse> implements Callback<Void> {

		static final ResponseHeaders ACCEPTED_RESPONSE = ResponseHeaders.of(HttpStatus.ACCEPTED);

		@Override
		public void onSuccess(Void value) {
			complete(HttpResponse.of(ACCEPTED_RESPONSE));
		}

		@Override
		public void onError(Throwable t) {
			completeExceptionally(t);
		}

	}

}
