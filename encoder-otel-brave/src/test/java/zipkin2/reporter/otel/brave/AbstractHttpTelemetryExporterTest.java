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
package zipkin2.reporter.otel.brave;

import static org.assertj.core.api.Assertions.assertThat;

import brave.Tags;
import brave.handler.MutableSpan;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.ProxyOptions;
import io.opentelemetry.sdk.common.export.RetryPolicy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import okio.Buffer;
import okio.GzipSource;
import okio.Okio;
import okio.Source;
import org.assertj.core.api.iterable.ThrowingExtractor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockserver.integration.ClientAndServer;

/**
 * Taken from https://github.com/open-telemetry/opentelemetry-java/blob/v1.39.0/exporters/otlp/testing-internal/src/main/java/io/opentelemetry/exporter/otlp/testing/internal/AbstractHttpTelemetryExporterTest.java
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractHttpTelemetryExporterTest<T, U extends Message> {

  private static final ConcurrentLinkedQueue<Object> exportedResourceTelemetry =
      new ConcurrentLinkedQueue<>();

  private static final ConcurrentLinkedQueue<HttpResponse> httpErrors =
      new ConcurrentLinkedQueue<>();

  private static final AtomicInteger attempts = new AtomicInteger();

  private static final ConcurrentLinkedQueue<HttpRequest> httpRequests =
      new ConcurrentLinkedQueue<>();

  @RegisterExtension
  @Order(3)
  static final ServerExtension server =
      new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
          sb.service(
              "/v1/traces",
              new CollectorService<>(
                  ExportTraceServiceRequest::parseFrom,
                  ExportTraceServiceRequest::getResourceSpansList,
                  ExportTraceServiceResponse.getDefaultInstance().toByteArray()));

          sb.http(0);
        }
      };

  private static class CollectorService<T> implements HttpService {
    private final ThrowingExtractor<byte[], T, InvalidProtocolBufferException> parse;
    private final Function<T, List<? extends Object>> getResourceTelemetry;
    private final byte[] successResponse;

    private CollectorService(
        ThrowingExtractor<byte[], T, InvalidProtocolBufferException> parse,
        Function<T, List<? extends Object>> getResourceTelemetry,
        byte[] successResponse) {
      this.parse = parse;
      this.getResourceTelemetry = getResourceTelemetry;
      this.successResponse = successResponse;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
      httpRequests.add(ctx.request());
      attempts.incrementAndGet();
      CompletableFuture<HttpResponse> responseFuture =
          req.aggregate()
              .thenApply(
                  aggReq -> {
                    T request;
                    try {
                      byte[] requestBody = maybeInflate(aggReq.headers(), aggReq.content().array());
                      request = parse.extractThrows(requestBody);
                    } catch (IOException e) {
                      throw new UncheckedIOException(e);
                    }
                    exportedResourceTelemetry.addAll(getResourceTelemetry.apply(request));
                    HttpResponse errorResponse = httpErrors.poll();
                    return errorResponse != null
                        ? errorResponse
                        : HttpResponse.of(
                            HttpStatus.OK,
                            MediaType.parse("application/x-protobuf"),
                            successResponse);
                  });
      return HttpResponse.of(responseFuture);
    }

    private static byte[] maybeInflate(RequestHeaders requestHeaders, byte[] content)
        throws IOException {
      if (requestHeaders.contains("content-encoding", "gzip")) {
        Buffer buffer = new Buffer();
        GzipSource gzipSource = new GzipSource(Okio.source(new ByteArrayInputStream(content)));
        gzipSource.read(buffer, Integer.MAX_VALUE);
        return buffer.readByteArray();
      }
      if (requestHeaders.contains("content-encoding", "base64")) {
        Buffer buffer = new Buffer();
        Source base64Source =
            Okio.source(Base64.getDecoder().wrap(new ByteArrayInputStream(content)));
        base64Source.read(buffer, Integer.MAX_VALUE);
        return buffer.readByteArray();
      }
      return content;
    }
  }

  private final String type;
  private final String path;
  private final U resourceTelemetryInstance;

  private CloseableSpanHandler exporter; // Brave OKHttp sender

  protected AbstractHttpTelemetryExporterTest(
      String type, String path, U resourceTelemetryInstance) {
    this.type = type;
    this.path = path;
    this.resourceTelemetryInstance = resourceTelemetryInstance;
  }

  @BeforeAll
  void setUp() {
    exporter = exporterBuilder().setEndpoint(server.httpUri() + path).build();
  }

  @AfterAll
  void shutdown() {
    if (exporter != null) {
      exporter.shutdown();
    }
  }

  @AfterEach
  void reset() {
    exportedResourceTelemetry.clear();
    httpErrors.clear();
    attempts.set(0);
    httpRequests.clear();
  }

  @Test
  void export() {
    List<MutableSpan> telemetry = Collections.singletonList(generateFakeTelemetry());
    assertThat(exporter.export(telemetry).join(10, TimeUnit.SECONDS).isSuccess()).isTrue();
    List<ResourceSpans> expectedResourceTelemetry = toProto(telemetry);
    assertThat(exportedResourceTelemetry).containsExactlyElementsOf(expectedResourceTelemetry);

    // Assert request contains OTLP spec compliant User-Agent header
    assertThat(httpRequests)
        .singleElement()
        .satisfies(
            req ->
                assertThat(req.headers().get("User-Agent"))
                    .matches("OTel-OTLP-Exporter-Java/1\\..*"));
  }

  @Test
  void multipleItems() {
    List<MutableSpan> telemetry = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      telemetry.add(generateFakeTelemetry());
    }
    assertThat(exporter.export(telemetry).join(10, TimeUnit.SECONDS).isSuccess()).isTrue();
    List<ResourceSpans> expectedResourceTelemetry = toProto(telemetry);
    assertThat(exportedResourceTelemetry).containsExactlyElementsOf(expectedResourceTelemetry);
  }

  @Test
  void compressionWithNone() {
    CloseableSpanHandler exporter =
        exporterBuilder().setEndpoint(server.httpUri() + path).setCompression("none").build();
    try {
      CompletableResultCode result =
          exporter.export(Collections.singletonList(generateFakeTelemetry()));
      assertThat(result.join(10, TimeUnit.SECONDS).isSuccess()).isTrue();
      assertThat(httpRequests)
          .singleElement()
          .satisfies(req -> assertThat(req.headers().get("content-encoding")).isNull());
    } finally {
      exporter.shutdown();
    }
  }

  @Test
  void compressionWithGzip() {
    CloseableSpanHandler exporter =
        exporterBuilder().setEndpoint(server.httpUri() + path).setCompression("gzip").build();
    try {
      CompletableResultCode result =
          exporter.export(Collections.singletonList(generateFakeTelemetry()));
      assertThat(result.join(10, TimeUnit.SECONDS).isSuccess()).isTrue();
      assertThat(httpRequests)
          .singleElement()
          .satisfies(req -> assertThat(req.headers().get("content-encoding")).isEqualTo("gzip"));
    } finally {
      exporter.shutdown();
    }
  }

  @Test
  void authorityWithAuth() {
    CloseableSpanHandler exporter =
        exporterBuilder()
            .setEndpoint("http://foo:bar@localhost:" + server.httpPort() + path)
            .build();
    try {
      CompletableResultCode result =
          exporter.export(Collections.singletonList(generateFakeTelemetry()));
      assertThat(result.join(10, TimeUnit.SECONDS).isSuccess()).isTrue();
    } finally {
      exporter.shutdown();
    }
  }

  @Test
  void withHeaders() {
    AtomicInteger count = new AtomicInteger();
    CloseableSpanHandler exporter =
        exporterBuilder()
            .setEndpoint(server.httpUri() + path)
            .addHeader("key1", "value1")
            .setHeaders(() -> Collections.singletonMap("key2", "value" + count.incrementAndGet()))
            .build();
    try {
      // Export twice to ensure header supplier gets invoked twice
      CompletableResultCode result =
          exporter.export(Collections.singletonList(generateFakeTelemetry()));
      assertThat(result.join(10, TimeUnit.SECONDS).isSuccess()).isTrue();
      result = exporter.export(Collections.singletonList(generateFakeTelemetry()));
      assertThat(result.join(10, TimeUnit.SECONDS).isSuccess()).isTrue();

      assertThat(httpRequests)
          .satisfiesExactly(
              req -> {
                assertThat(req.headers().get("key1")).isEqualTo("value1");
                assertThat(req.headers().get("key2")).isEqualTo("value" + (count.get() - 1));
              },
              req -> {
                assertThat(req.headers().get("key1")).isEqualTo("value1");
                assertThat(req.headers().get("key2")).isEqualTo("value" + count.get());
              });
    } finally {
      exporter.shutdown();
    }
  }

  @Test
  void connectTimeout() {
    CloseableSpanHandler exporter =
        exporterBuilder()
            // Connecting to a non-routable IP address to trigger connection error
            .setEndpoint("http://10.255.255.1")
            .setConnectTimeout(Duration.ofMillis(1))
            .build();
    try {
      long startTimeMillis = System.currentTimeMillis();
      CompletableResultCode result =
          exporter.export(Collections.singletonList(generateFakeTelemetry()));
      assertThat(result.join(10, TimeUnit.SECONDS).isSuccess()).isFalse();
      // Assert that the export request fails well before the default connect timeout of 10s
      assertThat(System.currentTimeMillis() - startTimeMillis)
          .isLessThan(TimeUnit.SECONDS.toMillis(1));
    } finally {
      exporter.shutdown();
    }
  }

  @Test
  void deadlineSetPerExport() throws InterruptedException {
    CloseableSpanHandler exporter =
        exporterBuilder()
            .setEndpoint(server.httpUri() + path)
            .setTimeout(Duration.ofMillis(1500))
            .build();
    try {
      TimeUnit.MILLISECONDS.sleep(2000);
      CompletableResultCode result =
          exporter.export(Collections.singletonList(generateFakeTelemetry()));
      assertThat(result.join(10, TimeUnit.SECONDS).isSuccess()).isTrue();
    } finally {
      exporter.shutdown();
    }
  }

  @Test
  void exportAfterShutdown() {
    CloseableSpanHandler exporter = exporterBuilder().setEndpoint(server.httpUri() + path).build();
    exporter.shutdown();
    assertThat(
        exporter
            .export(Collections.singletonList(generateFakeTelemetry()))
            .join(10, TimeUnit.SECONDS)
            .isSuccess())
        .isFalse();
    assertThat(httpRequests).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(ints = {429, 502, 503, 504})
  void retryableError(int code) {
    addHttpError(code);

    CloseableSpanHandler exporter = retryingExporter();

    try {
      assertThat(
          exporter
              .export(Collections.singletonList(generateFakeTelemetry()))
              .join(10, TimeUnit.SECONDS)
              .isSuccess())
          .isTrue();
    } finally {
      exporter.shutdown();
    }

    assertThat(attempts).hasValue(2);
  }

  @Test
  void retryableError_tooManyAttempts() {
    addHttpError(502);
    addHttpError(502);

    CloseableSpanHandler exporter = retryingExporter();

    try {
      assertThat(
          exporter
              .export(Collections.singletonList(generateFakeTelemetry()))
              .join(10, TimeUnit.SECONDS)
              .isSuccess())
          .isFalse();
    } finally {
      exporter.shutdown();
    }

    assertThat(attempts).hasValue(2);
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 500, 501})
  void nonRetryableError(int code) {
    addHttpError(code);

    CloseableSpanHandler exporter = retryingExporter();

    try {
      assertThat(
          exporter
              .export(Collections.singletonList(generateFakeTelemetry()))
              .join(10, TimeUnit.SECONDS)
              .isSuccess())
          .isFalse();
    } finally {
      exporter.shutdown();
    }

    assertThat(attempts).hasValue(1);
  }

  @Test
  void proxy() {
    // configure mockserver to proxy to the local OTLP server
    InetSocketAddress serverSocketAddress = server.httpSocketAddress();
    try (ClientAndServer clientAndServer =
        ClientAndServer.startClientAndServer(
            serverSocketAddress.getHostName(), serverSocketAddress.getPort())) {
      CloseableSpanHandler exporter =
          exporterBuilder()
              // Configure exporter with server endpoint, and proxy options to route through
              // mockserver proxy
              .setEndpoint(server.httpUri() + path)
              .setProxyOptions(
                  ProxyOptions.create(
                      InetSocketAddress.createUnresolved("localhost", clientAndServer.getPort())))
              .build();

      try {
        List<MutableSpan> telemetry = Collections.singletonList(generateFakeTelemetry());

        assertThat(exporter.export(telemetry).join(10, TimeUnit.SECONDS).isSuccess()).isTrue();
        // assert that mock server received request
        assertThat(clientAndServer.retrieveRecordedRequests(new org.mockserver.model.HttpRequest()))
            .hasSize(1);
        // assert that server received telemetry from proxy, and is as expected
        List<ResourceSpans> expectedResourceTelemetry = toProto(telemetry);
        assertThat(exportedResourceTelemetry).containsExactlyElementsOf(expectedResourceTelemetry);
      } finally {
        exporter.shutdown();
      }
    }
  }

  protected abstract TelemetryExporterBuilder<T> exporterBuilder();

  protected abstract MutableSpan generateFakeTelemetry();

  private List<ResourceSpans> toProto(List<MutableSpan> telemetry) {
    OtelEncoder otelEncoder = new OtelEncoder(Tags.ERROR);
    return
        telemetry.stream()
            .map(otelEncoder::encode)
            .flatMap(bytes -> {
              try {
                return ExportTraceServiceRequest.parseFrom(bytes).getResourceSpansList().stream();
              } catch (InvalidProtocolBufferException e) {
                throw new RuntimeException(e);
              }
            })
        .collect(Collectors.toList());
  }

  private CloseableSpanHandler retryingExporter() {
    return exporterBuilder()
        .setEndpoint(server.httpUri() + path)
        .setRetryPolicy(
            RetryPolicy.builder()
                .setMaxAttempts(2)
                // We don't validate backoff time itself in these tests, just that retries
                // occur. Keep the tests fast by using minimal backoff.
                .setInitialBackoff(Duration.ofMillis(1))
                .setMaxBackoff(Duration.ofMillis(1))
                .setBackoffMultiplier(1)
                .build())
        .build();
  }

  private static void addHttpError(int code) {
    httpErrors.add(HttpResponse.of(code));
  }
}