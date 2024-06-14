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
package zipkin2.reporter.otel;

import brave.Tags;
import brave.handler.MutableSpan;
import brave.propagation.TraceContext;
import io.grpc.ManagedChannelBuilder;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import brave.Span;
import brave.Span.Kind;
import brave.Tracer;
import brave.Tracing;
import brave.handler.SpanHandler;
import brave.propagation.ThreadLocalCurrentTraceContext;
import brave.sampler.Sampler;
import java.util.function.Supplier;
import okhttp3.OkHttpClient;
import okhttp3.OkHttpClient.Builder;
import okhttp3.Request;
import okhttp3.Response;
import org.assertj.core.api.BDDAssertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import zipkin2.reporter.AsyncReporter;
import zipkin2.reporter.Encoding;
import zipkin2.reporter.okhttp3.OkHttpSender;
import zipkin2.reporter.otel.brave.OtelEncoder;
import zipkin2.reporter.otel.grpc.OtelGrpcSender;

@Testcontainers
class BasicUsageTest {

  @Container
  static JaegerAllInOne jaegerAllInOne = new JaegerAllInOne();

  TestSetup testSetup;

  Tracing tracing;

  @ParameterizedTest
  @EnumSource(TestSetup.class)
  void shouldSendSpansToOtlpEndpoint(TestSetup testSetup) throws InterruptedException, IOException {
    // Setup
    ThreadLocalCurrentTraceContext braveCurrentTraceContext = ThreadLocalCurrentTraceContext.newBuilder()
      .build();
    this.testSetup = testSetup;
    SpanHandler spanHandler = testSetup.get();
    tracing = Tracing.newBuilder()
      .currentTraceContext(braveCurrentTraceContext)
      .supportsJoin(false)
      .traceId128Bit(true)
      .sampler(Sampler.ALWAYS_SAMPLE)
      .addSpanHandler(spanHandler)
      .localServiceName("my-service")
      .build();
    Tracer braveTracer = tracing.tracer();

    List<String> traceIds = new ArrayList<>();
    final int size = 5;
    for (int i = 0; i < size; i++) {
      // Given
      Span span = braveTracer.nextSpan().name("foo " + i)
        .tag("foo tag", "foo value")
        .kind(Kind.CONSUMER)
        .error(new RuntimeException("BOOOOOM!"))
        .remoteServiceName("remote service")
        .start();
      String traceId = span.context().traceIdString();
      System.out.println("Trace Id <" + traceId + ">");
      span.remoteIpAndPort("http://localhost", 123456);
      Thread.sleep(50);
      span.annotate("boom!");
      Thread.sleep(50);

      // When
      span.finish();
      traceIds.add(span.context().traceIdString());
    }

    testSetup.close();

    // Then
    Awaitility.await().untilAsserted(() -> {
      BDDAssertions.then(traceIds).hasSize(size);
      OkHttpClient client = new Builder()
        .build();
      traceIds.forEach(traceId -> {
        Request request = new Request.Builder().url("http://localhost:" + jaegerAllInOne.getQueryPort() + "/api/traces/" + traceId).build();
        try (Response response = client.newCall(request).execute()) {
          BDDAssertions.then(response.isSuccessful()).isTrue();
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
      });

    });
  }

  @AfterEach
  void shutdown() throws IOException {
    if (tracing != null) {
      tracing.close();
    }
  }

  static class HttpSenderProvider implements Function<Integer, SpanHandler>, Closeable {

    OkHttpSender okHttpSender;

    AsyncReporter<MutableSpan> reporter;

    SpanHandler spanHandler;

    @Override
    public void close() {
      if (reporter != null) {
        reporter.close();
      }
      if (okHttpSender != null) {
        okHttpSender.close();
      }
    }

    @Override
    public SpanHandler apply(Integer port) {
      okHttpSender = OkHttpSender.newBuilder()
          .encoding(Encoding.PROTO3)
          .endpoint("http://localhost:" + port + "/v1/traces")
          .build();
      OtelEncoder otelEncoder = new OtelEncoder(Tags.ERROR);
      reporter = AsyncReporter.builder(okHttpSender).build(otelEncoder);
      spanHandler = new SpanHandler() {
        @Override
        public boolean end(TraceContext context, MutableSpan span, Cause cause) {
          reporter.report(span);
          return true;
        }
      };
      return spanHandler;
    }
  }

  static class GrpcSenderProvider implements Function<Integer, SpanHandler>, Closeable {

    OtelGrpcSender otelGrpcSender;

    AsyncReporter<MutableSpan> reporter;

    SpanHandler spanHandler;

    @Override
    public void close() {
      if (otelGrpcSender != null) {
        otelGrpcSender.close();
      }
    }

    @Override
    public SpanHandler apply(Integer port) {
      otelGrpcSender = OtelGrpcSender.newBuilder(ManagedChannelBuilder
          .forAddress("localhost", jaegerAllInOne.getGrpcOtlpPort())
          .usePlaintext()
          .build()).build();
      OtelEncoder otelEncoder = new OtelEncoder(Tags.ERROR);
      reporter = AsyncReporter.builder(otelGrpcSender).build(otelEncoder);
      spanHandler = new SpanHandler() {
        @Override
        public boolean end(TraceContext context, MutableSpan span, Cause cause) {
          reporter.report(span);
          return true;
        }
      };
      return spanHandler;
    }

  }

  enum TestSetup implements Supplier<SpanHandler>, Closeable {


    OK_HTTP_OTEL_SENDER_TO_JAEGER {

      private final HttpSenderProvider httpSenderProvider = new HttpSenderProvider();

      @Override
      public void close() {
        httpSenderProvider.close();
      }

      @Override
      public SpanHandler get() {
        return httpSenderProvider.apply(jaegerAllInOne.getHttpOtlpPort());
      }
    },

    GRPC_SENDER_TO_JAEGER {

      private final GrpcSenderProvider grpcSenderProvider = new GrpcSenderProvider();

      @Override
      public void close() {
        grpcSenderProvider.close();
      }

      @Override
      public SpanHandler get() {
        return grpcSenderProvider.apply(jaegerAllInOne.getGrpcOtlpPort());
      }

    },

    OK_HTTP_OTEL_SENDER_TO_ZIPKIN {

      private final HttpSenderProvider httpSenderProvider = new HttpSenderProvider();

      @Override
      public void close() {
        httpSenderProvider.close();
      }

      @Override
      public SpanHandler get() {
        return httpSenderProvider.apply(jaegerAllInOne.getHttpOtlpPort());
      }
    },

    GRPC_SENDER_TO_ZIPKIN {

      private final GrpcSenderProvider grpcSenderProvider = new GrpcSenderProvider();

      @Override
      public void close() {
        grpcSenderProvider.close();
      }

      @Override
      public SpanHandler get() {
        return grpcSenderProvider.apply(jaegerAllInOne.getGrpcOtlpPort());
      }

    }
  }

}