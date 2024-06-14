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
package zipkin2.reporter.otel.grpc;

import static io.grpc.CallOptions.DEFAULT;
import static io.grpc.MethodDescriptor.generateFullMethodName;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.trace.v1.TracesData;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import zipkin2.reporter.BytesMessageSender;
import zipkin2.reporter.ClosedSenderException;
import zipkin2.reporter.Encoding;

public final class OtelGrpcSender extends BytesMessageSender.Base {
  static final int DEFAULT_SERVER_TIMEOUT_MS = 5000;

  public static Builder newBuilder(Channel channel) { // visible for testing
    return new Builder(channel);
  }

  public static final class Builder {
    final Channel channel;
    CallOptions callOptions = DEFAULT;
    boolean shutdownChannelOnClose;
    long serverResponseTimeoutMs = DEFAULT_SERVER_TIMEOUT_MS;

    Builder(Channel channel) {
      if (channel == null) throw new NullPointerException("channel == null");
      this.channel = channel;
    }

    public Builder callOptions(CallOptions callOptions) {
      if (callOptions == null) throw new NullPointerException("callOptions == null");
      this.callOptions = callOptions;
      return this;
    }

    public Builder serverResponseTimeoutMs(long serverResponseTimeoutMs) {
      if (serverResponseTimeoutMs <= 0) {
        throw new IllegalArgumentException("Server response timeout must be greater than 0");
      }
      this.serverResponseTimeoutMs = serverResponseTimeoutMs;
      return this;
    }

    public OtelGrpcSender build() {
      return new OtelGrpcSender(this);
    }
  }

  final Channel channel;
  final CallOptions callOptions;
  final boolean shutdownChannelOnClose;
  final long serverResponseTimeoutMs;

  OtelGrpcSender(Builder builder) {
    super(Encoding.PROTO3);
    channel = builder.channel;
    callOptions = builder.callOptions;
    serverResponseTimeoutMs = builder.serverResponseTimeoutMs;
    shutdownChannelOnClose = builder.shutdownChannelOnClose;
  }

  @Override public int messageMaxBytes() {
    return 1024 * 1024; // 1 MiB for now
  }

  /** close is typically called from a different thread */
  volatile boolean closeCalled;

  private static final String SERVICE_NAME = "opentelemetry.proto.collector.trace.v1.TraceService";

  private static final MethodDescriptor.Marshaller<TracesData> REQUEST_MARSHALLER =
      new MethodDescriptor.Marshaller<TracesData>() {
        @Override
        public InputStream stream(TracesData value) {
          return value.toByteString().newInput();
        }

        @Override
        public TracesData parse(InputStream stream) {
          throw new UnsupportedOperationException("Only for serializing");
        }
      };

  private static final MethodDescriptor.Marshaller<ExportTraceServiceResponse> RESPONSE_MARSHALER =
      new MethodDescriptor.Marshaller<ExportTraceServiceResponse>() {
        @Override
        public InputStream stream(ExportTraceServiceResponse value) {
          throw new UnsupportedOperationException("Only for parsing");
        }

        @Override
        public ExportTraceServiceResponse parse(InputStream stream) {
          try {
            return ExportTraceServiceResponse.parseFrom(stream);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }
      };

  private static final io.grpc.MethodDescriptor<TracesData, ExportTraceServiceResponse>
      getExportMethod =
      io.grpc.MethodDescriptor.<TracesData, ExportTraceServiceResponse>newBuilder()
          .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
          .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Export"))
          .setRequestMarshaller(REQUEST_MARSHALLER)
          .setResponseMarshaller(RESPONSE_MARSHALER)
          .build();

  @Override public void send(List<byte[]> encodedSpans) throws IOException {
    if (closeCalled) throw new ClosedSenderException();

    TracesData.Builder request = TracesData.newBuilder();

    for (byte[] encodedSpan : encodedSpans) {
      request.mergeFrom(TracesData.parseFrom(encodedSpan));
    }

    ClientCall<TracesData, ExportTraceServiceResponse> call =
        channel.newCall(getExportMethod, callOptions);

    AwaitableUnaryClientCallListener<ExportTraceServiceResponse> listener =
        new AwaitableUnaryClientCallListener<>(serverResponseTimeoutMs);
    try {
      call.start(listener, new Metadata());
      call.request(1);
      call.sendMessage(request.build());
      call.halfClose();
    } catch (RuntimeException | Error t) {
      call.cancel(null, t);
      throw t;
    }
    listener.await();
  }

  @Override public String toString() {
    return "OtelGrpcSender{}";
  }

  @Override public void close() {
    if (!shutdownChannelOnClose) return;
    if (closeCalled) return;
    closeCalled = true;
    ((ManagedChannel) channel).shutdownNow();
  }
}
