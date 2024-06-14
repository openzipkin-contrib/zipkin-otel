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
package zipkin2.collector.otel.grpc;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerConfigurator;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.grpc.protocol.AbstractUnsafeUnaryGrpcService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import zipkin2.Callback;
import zipkin2.codec.SpanBytesDecoder;
import zipkin2.collector.Collector;
import zipkin2.collector.CollectorComponent;
import zipkin2.collector.CollectorMetrics;
import zipkin2.collector.CollectorSampler;
import zipkin2.storage.StorageComponent;

public final class OpenTelemetryGrpcCollector extends CollectorComponent
    implements ServerConfigurator {
  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder extends CollectorComponent.Builder {

    Collector.Builder delegate = Collector.newBuilder(OpenTelemetryGrpcCollector.class);
    CollectorMetrics metrics = CollectorMetrics.NOOP_METRICS;

    @Override public Builder storage(StorageComponent storageComponent) {
      delegate.storage(storageComponent);
      return this;
    }

    @Override public Builder metrics(CollectorMetrics metrics) {
      if (metrics == null) throw new NullPointerException("metrics == null");
      delegate.metrics(this.metrics = metrics.forTransport("otel/grpc"));
      return this;
    }

    @Override public Builder sampler(CollectorSampler sampler) {
      delegate.sampler(sampler);
      return this;
    }

    @Override public OpenTelemetryGrpcCollector build() {
      return new OpenTelemetryGrpcCollector(this);
    }

    Builder() {
    }
  }

  final Collector collector;
  final CollectorMetrics metrics;

  OpenTelemetryGrpcCollector(Builder builder) {
    collector = builder.delegate.build();
    metrics = builder.metrics;
  }

  @Override public OpenTelemetryGrpcCollector start() {
    return this;
  }

  @Override public String toString() {
    return "OpenTelemetryGrpcCollector{}";
  }

  /**
   * Reconfigures the service per https://github.com/open-telemetry/opentelemetry-proto/blob/v1.0.0/opentelemetry/proto/collector/trace/v1/trace_service.proto
   */
  @Override public void reconfigure(ServerBuilder sb) {
    sb.service("/opentelemetry.proto.collector.trace.v1.TraceService/Export", new HttpService(this));
  }

  static final class HttpService extends AbstractUnsafeUnaryGrpcService {
    final Collector collector;
    final CollectorMetrics metrics;

    HttpService(OpenTelemetryGrpcCollector collector) {
      this.collector = collector.collector;
      this.metrics = collector.metrics;
    }

    @Override
    protected CompletionStage<ByteBuf> handleMessage(ServiceRequestContext ctx, ByteBuf bytes) {
      metrics.incrementMessages();
      metrics.incrementBytes(bytes.readableBytes());

      if (!bytes.isReadable()) {
        return CompletableFuture.completedFuture(bytes); // lenient on empty messages
      }

      try {
        CompletableFutureCallback result = new CompletableFutureCallback();
        collector.acceptSpans(bytes.nioBuffer(), SpanBytesDecoder.PROTO3, result, ctx.blockingTaskExecutor());
        return result;
      } finally {
        bytes.release();
      }
    }
  }

  static final class CompletableFutureCallback extends CompletableFuture<ByteBuf>
      implements Callback<Void> {

    @Override public void onSuccess(Void value) {
      complete(Unpooled.EMPTY_BUFFER);
    }

    @Override public void onError(Throwable t) {
      completeExceptionally(t);
    }
  }
}
