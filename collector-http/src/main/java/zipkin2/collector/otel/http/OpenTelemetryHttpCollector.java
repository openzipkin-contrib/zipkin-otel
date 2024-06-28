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
package zipkin2.collector.otel.http;

import com.google.protobuf.CodedInputStream;
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
import io.netty.buffer.ByteBufAllocator;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import zipkin2.Callback;
import zipkin2.Span;
import zipkin2.collector.Collector;
import zipkin2.collector.CollectorComponent;
import zipkin2.collector.CollectorMetrics;
import zipkin2.collector.CollectorSampler;
import zipkin2.storage.StorageComponent;
import zipkin2.translation.zipkin.SpanTranslator;

public final class OpenTelemetryHttpCollector extends CollectorComponent
    implements ServerConfigurator {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder extends CollectorComponent.Builder {

    Collector.Builder delegate = Collector.newBuilder(OpenTelemetryHttpCollector.class);
    CollectorMetrics metrics = CollectorMetrics.NOOP_METRICS;

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

    @Override
    public OpenTelemetryHttpCollector build() {
      return new OpenTelemetryHttpCollector(this);
    }

    Builder() {
    }
  }

  final Collector collector;
  final CollectorMetrics metrics;

  OpenTelemetryHttpCollector(Builder builder) {
    collector = builder.delegate.build();
    metrics = builder.metrics;
  }

  @Override
  public OpenTelemetryHttpCollector start() {
    return this;
  }

  @Override
  public String toString() {
    return "OpenTelemetryHttpCollector{}";
  }

  /**
   * Reconfigures the service per https://opentelemetry.io/docs/specs/otlp/#otlphttp-request
   */
  @Override
  public void reconfigure(ServerBuilder sb) {
    sb.decorator(DecodingService.newDecorator(StreamDecoderFactory.gzip()));
    sb.service("/v1/traces", new HttpService(this));
  }

  static final class HttpService extends AbstractHttpService {

    final OpenTelemetryHttpCollector collector;

    HttpService(OpenTelemetryHttpCollector collector) {
      this.collector = collector;
    }

    @Override
    protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req)
        throws Exception {
      CompletableCallback result = new CompletableCallback();
      req.aggregate(AggregationOptions.usePooledObjects(ByteBufAllocator.DEFAULT, ctx.eventLoop()
      )).handle((msg, t) -> {
        if (t != null) {
          result.onError(t);
          return null;
        }
        try (HttpData content = msg.content()) {
          if (content.isEmpty()) {
            result.onSuccess(null);
            return null;
          }

          try {
            ExportTraceServiceRequest request = ExportTraceServiceRequest.parseFrom(CodedInputStream.newInstance(content.byteBuf().nioBuffer()));
            List<Span> spans = SpanTranslator.translate(request);
            collector.collector.accept(spans, result);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
          return null;
        }
      });
      return HttpResponse.of(result);
    }
  }

  static final class CompletableCallback extends CompletableFuture<HttpResponse>
      implements Callback<Void> {

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