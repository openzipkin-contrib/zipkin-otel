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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import brave.Span.Kind;
import brave.Tags;
import brave.handler.MutableSpan;
import brave.propagation.B3SingleFormat;
import brave.propagation.TraceContext;
import com.google.protobuf.ByteString;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.PullPolicy;
import org.testcontainers.utility.DockerImageName;
import zipkin2.reporter.BytesEncoder;
import zipkin2.reporter.BytesMessageSender;
import zipkin2.reporter.Encoding;
import zipkin2.reporter.brave.AsyncZipkinSpanHandler;
import zipkin2.reporter.brave.MutableSpanBytesEncoder;
import zipkin2.reporter.okhttp3.OkHttpSender;

import static org.assertj.core.api.Assertions.assertThat;
import static zipkin2.reporter.otel.brave.SpanTranslator.intAttribute;
import static zipkin2.reporter.otel.brave.SpanTranslator.stringAttribute;

public class ITOtelEncoderTest {
	private static GenericContainer<?> collector;

	private static OtlpHttpServer otlpHttpServer;

	private static final String COLLECTOR_IMAGE =
			"ghcr.io/open-telemetry/opentelemetry-java/otel-collector";

	private static final Integer COLLECTOR_OTLP_HTTP_PORT = 4318;

	private static final Integer COLLECTOR_ZIPKIN_PORT = 9411;

	private static final Integer COLLECTOR_HEALTH_CHECK_PORT = 13133;

	@BeforeAll
	static void beforeAll() {
		otlpHttpServer = new OtlpHttpServer();
		otlpHttpServer.start();
		collector = new GenericContainer<>(DockerImageName.parse(COLLECTOR_IMAGE))
				.withImagePullPolicy(PullPolicy.alwaysPull())
				.withEnv("OTLP_EXPORTER_ENDPOINT", "http://host.docker.internal:" + otlpHttpServer.httpPort())
				.withClasspathResourceMapping("otel-config.yaml", "/otel-config.yaml", BindMode.READ_ONLY)
				.withCommand("--config", "/otel-config.yaml")
				.withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("otel-collector")))
				.withExposedPorts(
						COLLECTOR_OTLP_HTTP_PORT,
						COLLECTOR_ZIPKIN_PORT,
						COLLECTOR_HEALTH_CHECK_PORT)
				.waitingFor(Wait.forHttp("/").forPort(COLLECTOR_HEALTH_CHECK_PORT));
		collector.start();
	}

	@AfterAll
	static void afterAll() {
		otlpHttpServer.stop().join();
		collector.stop();
		collector.close();
	}

	@BeforeEach
	void beforeEach() {
		otlpHttpServer.reset();
	}

	static Stream<Arguments> encoderAndEndpoint() {
		return Stream.of(
				/* existing sender + new encoder -> otlp with otel collector */
				Arguments.of(Encoding.PROTO3, new OtelEncoder(Tags.ERROR), String.format("http://localhost:%d/v1/traces", collector.getMappedPort(COLLECTOR_OTLP_HTTP_PORT))),
				/* existing sender + new encoder -> otlp without otel collector */
				Arguments.of(Encoding.PROTO3, new OtelEncoder(Tags.ERROR), String.format("http://localhost:%d/v1/traces", otlpHttpServer.httpPort())),
				/* existing sender + zipkin encoder -> zipkin endpoint on collector */
				Arguments.of(Encoding.JSON, MutableSpanBytesEncoder.create(Encoding.JSON, Tags.ERROR), String.format("http://localhost:%d/api/v2/spans", collector.getMappedPort(COLLECTOR_ZIPKIN_PORT))));
	}

	@ParameterizedTest
	@MethodSource("encoderAndEndpoint")
	void testEncoder(Encoding encoding, BytesEncoder<MutableSpan> encoder, String endpoint) throws Exception {
		try (BytesMessageSender okHttpSender = OkHttpSender.newBuilder()
				.encoding(encoding)
				.endpoint(endpoint)
				.build();
			 AsyncZipkinSpanHandler spanHandler = AsyncZipkinSpanHandler.newBuilder(okHttpSender).build(encoder);
		) {
			TraceContext context = B3SingleFormat.parseB3SingleFormat("0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-1").context();
			MutableSpan span = new MutableSpan(context, null);
			span.name("get");
			span.startTimestamp(1510256710021866L);
			span.finishTimestamp(1510256710021866L + 1117L);
			span.kind(Kind.SERVER);
			span.localServiceName("isao01");
			span.localIp("10.23.14.72");
			span.localPort(12345);
			span.tag("http.path", "/rs/A");
			span.tag("location", "T67792");
			span.tag("other", "A");
			spanHandler.end(context, span, null);
			spanHandler.flush();
		}
		if (otlpHttpServer.waitUntilTraceRequestsAreSent(Duration.ofSeconds(3))) {
			List<ResourceSpans> receivedSpans = otlpHttpServer.receivedSpans();
			assertThat(receivedSpans).hasSize(1);
			ResourceSpans resourceSpans = receivedSpans.get(0);
			assertThat(resourceSpans.getResource().getAttributesCount()).isEqualTo(1);
			KeyValue attribute = resourceSpans.getResource().getAttributes(0);
			assertThat(attribute.getKey()).isEqualTo("service.name");
			assertThat(attribute.getValue().getStringValue()).isEqualTo("isao01");
			assertThat(resourceSpans.getScopeSpansCount()).isEqualTo(1);
			ScopeSpans scopeSpans = resourceSpans.getScopeSpans(0);
			InstrumentationScope scope = scopeSpans.getScope();
			if (encoder instanceof OtelEncoder) {
				assertThat(scope.getName()).isEqualTo("zipkin2.reporter.otel");
				assertThat(scope.getVersion()).isEqualTo("0.0.1");
			}
			else {
				assertThat(scope.getName()).isEmpty();
				assertThat(scope.getVersion()).isEmpty();
			}
			assertThat(scopeSpans.getSpansCount()).isEqualTo(1);
			Span span = scopeSpans.getSpans(0);
			assertThat(span.getTraceId()).isEqualTo(ByteString.fromHex("0af7651916cd43dd8448eb211c80319c"));
			assertThat(span.getSpanId()).isEqualTo(ByteString.fromHex("b7ad6b7169203331"));
			if (encoder instanceof OtelEncoder) {
				assertThat(span.getAttributesCount()).isEqualTo(7);
				assertThat(span.getAttributesList()).contains(stringAttribute("otel.library.name", "zipkin2.reporter.otel"));
				assertThat(span.getAttributesList()).contains(stringAttribute("otel.library.version", "0.0.1"));
				assertThat(span.getAttributesList()).contains(stringAttribute("network.local.address", "10.23.14.72"));
				assertThat(span.getAttributesList()).contains(intAttribute("network.local.port", 12345));
			}
			else {
				assertThat(span.getAttributesCount()).isEqualTo(5);
				assertThat(span.getAttributesList()).contains(stringAttribute("net.host.ip", "10.23.14.72"));
				assertThat(span.getAttributesList()).contains(intAttribute("net.host.port", 12345));
			}
			assertThat(span.getAttributesList()).contains(stringAttribute("http.path", "/rs/A"));
			assertThat(span.getAttributesList()).contains(stringAttribute("location", "T67792"));
			assertThat(span.getAttributesList()).contains(stringAttribute("other", "A"));
			assertThat(span.getKind()).isEqualTo(Span.SpanKind.SPAN_KIND_SERVER);
		}
		else {
			Assertions.fail("Traces not sent");
		}
	}

	private static class OtlpHttpServer extends ServerExtension {

		private final List<ResourceSpans> spans = new ArrayList<>();

		private CountDownLatch latch = new CountDownLatch(1);

		private void reset() {
			this.spans.clear();
			this.latch = new CountDownLatch(1);
		}

		@Override
		protected void configure(ServerBuilder sb) {
			sb.service(
					"/v1/traces",
					new AbstractHttpService() {
						@Override
						protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
							return HttpResponse.of(req.aggregate()
									.handle((msg, t) -> {
										try (HttpData content = msg.content()) {
											if (!content.isEmpty()) {
												try {
													ExportTraceServiceRequest request = ExportTraceServiceRequest.parseFrom(
															"gzip".equalsIgnoreCase(req.headers().get("Content-Encoding")) ?
																	decompressGzip(content.array()) : content.array());
													if (request.getResourceSpansCount() > 0) {
														spans.addAll(request.getResourceSpansList());
														latch.countDown();
													}
												}
												catch (IOException e) {
													throw new UncheckedIOException(e);
												}
											}
											return HttpResponse.of(HttpStatus.ACCEPTED);
										}
									}));
						}
					});
			sb.http(0);
		}


		private byte[] decompressGzip(byte[] compressed) throws IOException {
			try (InputStream gis = new GZIPInputStream(new ByteArrayInputStream(compressed));
				 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
				byte[] buffer = new byte[1024];
				int len;
				while ((len = gis.read(buffer)) != -1) {
					out.write(buffer, 0, len);
				}
				return out.toByteArray();
			}
		}

		public boolean waitUntilTraceRequestsAreSent(Duration timeout) throws InterruptedException {
			return this.latch.await(timeout.getSeconds(), TimeUnit.SECONDS);
		}

		public List<ResourceSpans> receivedSpans() {
			return this.spans;
		}
	}
}
