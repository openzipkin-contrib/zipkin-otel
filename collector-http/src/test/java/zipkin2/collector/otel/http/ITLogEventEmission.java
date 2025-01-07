/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.collector.otel.http;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Consumes;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.Produces;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.utility.MountableFile;
import zipkin2.Span;
import zipkin2.collector.CollectorSampler;
import zipkin2.collector.InMemoryCollectorMetrics;
import zipkin2.storage.InMemoryStorage;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ITLogEventEmission {
  InMemoryStorage store;

  InMemoryCollectorMetrics metrics;

  OpenTelemetryHttpCollector collector;

  int zipkinPort = ZipkinTestUtil.getFreePort();
  Server zipkinServer;
  int openAiPort = ZipkinTestUtil.getFreePort();
  Server openAiServer;

  @BeforeEach
  public void setup() {
    store = InMemoryStorage.newBuilder().build();
    metrics = new InMemoryCollectorMetrics();
    collector = OpenTelemetryHttpCollector.newBuilder()
        .metrics(metrics)
        .sampler(CollectorSampler.ALWAYS_SAMPLE)
        .storage(store)
        .build()
        .start();
    ServerBuilder serverBuilder = Server.builder().http(zipkinPort);
    collector.reconfigure(serverBuilder);
    metrics = metrics.forTransport("otel/http");
    zipkinServer = serverBuilder.build();
    zipkinServer.start().join();
    openAiServer = Server.builder().http(openAiPort).annotatedService(new ChatCompletionService()).build();
    openAiServer.start().join();
    Testcontainers.exposeHostPorts(zipkinPort, openAiPort);
  }

  @AfterEach
  void teardown() throws IOException {
    store.close();
    collector.close();
    zipkinServer.stop().join();
    openAiServer.stop().join();
  }

  @Test
  void testLogEventEmissionWithGenAiCaptureMessageContentInPython() {
    try (GenericContainer<?> container = new GenericContainer<>("python:3.13")
        .withWorkingDirectory("/")
        .withCopyFileToContainer(MountableFile.forClasspathResource("python/requirements.txt"), "/requirements.txt")
        .withCopyFileToContainer(MountableFile.forClasspathResource("python/main.py"), "/main.py")
        .withCopyFileToContainer(MountableFile.forClasspathResource("python/run.sh"), "/run.sh")
        .withCommand("bash", "run.sh")
        .withEnv("OPENAI_BASE_URL", "http://host.testcontainers.internal:" + openAiPort + "/v1")
        .withEnv("OPENAI_API_KEY", "sk-mock")
        .withEnv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://host.testcontainers.internal:" + zipkinPort)
        .withEnv("OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT", "true")
        .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("python")))) {
      container.start();
      Awaitility.waitAtMost(Duration.ofMinutes(1)).untilAsserted(() -> {
        List<List<Span>> traces = store.getTraces();
        assertThat(traces).isNotEmpty();
        assertThat(traces.get(0)).hasSize(3);
      });
      List<Span> spans = store.getTraces().get(0);
      String spanId = spans.get(0).id();
      String traceId = spans.get(0).traceId();
      assertThat(spans.get(0).kind()).isNull();
      assertThat(spans.get(0).name()).isNull();
      assertThat(spans.get(0).duration()).isNull();
      assertThat(spans.get(0).localEndpoint()).isNull();
      assertThat(spans.get(0).tags()).isEmpty();
      assertThat(spans.get(0).annotations()).hasSize(1);
      assertThat(spans.get(0).annotations().get(0).value()).isEqualTo("\"gen_ai.user.message\":{\"severity_number\":9,\"body\":{\"content\":\"Write a short poem on OpenTelemetry.\"}}");
      assertThat(spans.get(1).id()).isEqualTo(spanId);
      assertThat(spans.get(1).traceId()).isEqualTo(traceId);
      assertThat(spans.get(1).kind()).isNull();
      assertThat(spans.get(1).name()).isNull();
      assertThat(spans.get(1).duration()).isNull();
      assertThat(spans.get(1).localEndpoint()).isNull();
      assertThat(spans.get(1).tags()).isEmpty();
      assertThat(spans.get(1).annotations()).hasSize(1);
      assertThat(spans.get(1).annotations().get(0).value()).isEqualTo("\"gen_ai.choice\":{\"severity_number\":9,\"body\":{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"This is a mock response from the server.\"}}}");
      assertThat(spans.get(2).id()).isEqualTo(spanId);
      assertThat(spans.get(2).traceId()).isEqualTo(traceId);
      assertThat(spans.get(2).annotations()).isEmpty();
      assertThat(spans.get(2).kind()).isEqualTo(Span.Kind.CLIENT);
      assertThat(spans.get(2).name()).isEqualTo("chat gpt-4o-mini");
      assertThat(spans.get(2).duration()).isGreaterThan(0);
      assertThat(spans.get(2).localEndpoint()).isNotNull();
      assertThat(spans.get(2).localEndpoint().serviceName()).isEqualTo("opentelemetry-python-openai");
      assertThat(spans.get(2).tags()).hasSize(15);
      assertThat(spans.get(2).tags()).containsEntry("gen_ai.operation.name", "chat");
      assertThat(spans.get(2).tags()).containsEntry("gen_ai.request.model", "gpt-4o-mini");
      assertThat(spans.get(2).tags()).containsEntry("gen_ai.response.finish_reasons", "stop");
      assertThat(spans.get(2).tags()).containsEntry("gen_ai.response.id", "chatcmpl-1234");
      assertThat(spans.get(2).tags()).containsEntry("gen_ai.response.model", "gpt-4o-mini");
      assertThat(spans.get(2).tags()).containsEntry("gen_ai.system", "openai");
      assertThat(spans.get(2).tags()).containsEntry("gen_ai.usage.input_tokens", "5");
      assertThat(spans.get(2).tags()).containsEntry("gen_ai.usage.output_tokens", "7");
      assertThat(spans.get(2).tags()).containsEntry("otel.scope.name", "opentelemetry.instrumentation.openai_v2");
      assertThat(spans.get(2).tags()).containsEntry("server.address", "host.testcontainers.internal");
      assertThat(spans.get(2).tags()).containsEntry("server.port", String.valueOf(openAiPort));
      assertThat(spans.get(2).tags()).containsKey("telemetry.auto.version");
      assertThat(spans.get(2).tags()).containsEntry("telemetry.sdk.language", "python");
      assertThat(spans.get(2).tags()).containsEntry("telemetry.sdk.name", "opentelemetry");
      assertThat(spans.get(2).tags()).containsKey("telemetry.sdk.version");
    }
  }

  @Test
  void testLogEventEmissionWithoutGenAiCaptureMessageContentInPython() {
    try (GenericContainer<?> container = new GenericContainer<>("python:3.13")
        .withWorkingDirectory("/")
        .withCopyFileToContainer(MountableFile.forClasspathResource("python/requirements.txt"), "/requirements.txt")
        .withCopyFileToContainer(MountableFile.forClasspathResource("python/main.py"), "/main.py")
        .withCopyFileToContainer(MountableFile.forClasspathResource("python/run.sh"), "/run.sh")
        .withEnv("OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT", "false")
        .withCommand("bash", "run.sh")
        .withEnv("OPENAI_BASE_URL", "http://host.testcontainers.internal:" + openAiPort + "/v1")
        .withEnv("OPENAI_API_KEY", "sk-mock")
        .withEnv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://host.testcontainers.internal:" + zipkinPort)
        .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("python")))) {
      container.start();
      Awaitility.waitAtMost(Duration.ofMinutes(1)).untilAsserted(() -> {
        List<List<Span>> traces = store.getTraces();
        assertThat(traces).isNotEmpty();
        assertThat(traces.get(0)).hasSize(3);
      });
      List<Span> spans = store.getTraces().get(0);
      String spanId = spans.get(0).id();
      String traceId = spans.get(0).traceId();
      assertThat(spans.get(0).kind()).isNull();
      assertThat(spans.get(0).name()).isNull();
      assertThat(spans.get(0).duration()).isNull();
      assertThat(spans.get(0).localEndpoint()).isNull();
      assertThat(spans.get(0).tags()).isEmpty();
      assertThat(spans.get(0).annotations()).hasSize(1);
      assertThat(spans.get(0).annotations().get(0).value()).isEqualTo("\"gen_ai.user.message\":{\"severity_number\":9,\"body\":\"\"}");
      assertThat(spans.get(1).id()).isEqualTo(spanId);
      assertThat(spans.get(1).traceId()).isEqualTo(traceId);
      assertThat(spans.get(1).kind()).isNull();
      assertThat(spans.get(1).name()).isNull();
      assertThat(spans.get(1).duration()).isNull();
      assertThat(spans.get(1).localEndpoint()).isNull();
      assertThat(spans.get(1).tags()).isEmpty();
      assertThat(spans.get(1).annotations()).hasSize(1);
      assertThat(spans.get(1).annotations().get(0).value()).isEqualTo("\"gen_ai.choice\":{\"severity_number\":9,\"body\":{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\"}}}");
      assertThat(spans.get(2).id()).isEqualTo(spanId);
      assertThat(spans.get(2).traceId()).isEqualTo(traceId);
      assertThat(spans.get(2).annotations()).isEmpty();
      assertThat(spans.get(2).kind()).isEqualTo(Span.Kind.CLIENT);
      assertThat(spans.get(2).name()).isEqualTo("chat gpt-4o-mini");
      assertThat(spans.get(2).duration()).isGreaterThan(0);
      assertThat(spans.get(2).localEndpoint()).isNotNull();
      assertThat(spans.get(2).localEndpoint().serviceName()).isEqualTo("opentelemetry-python-openai");
      assertThat(spans.get(2).tags()).hasSize(15);
      assertThat(spans.get(2).tags()).containsEntry("gen_ai.operation.name", "chat");
      assertThat(spans.get(2).tags()).containsEntry("gen_ai.request.model", "gpt-4o-mini");
      assertThat(spans.get(2).tags()).containsEntry("gen_ai.response.finish_reasons", "stop");
      assertThat(spans.get(2).tags()).containsEntry("gen_ai.response.id", "chatcmpl-1234");
      assertThat(spans.get(2).tags()).containsEntry("gen_ai.response.model", "gpt-4o-mini");
      assertThat(spans.get(2).tags()).containsEntry("gen_ai.system", "openai");
      assertThat(spans.get(2).tags()).containsEntry("gen_ai.usage.input_tokens", "5");
      assertThat(spans.get(2).tags()).containsEntry("gen_ai.usage.output_tokens", "7");
      assertThat(spans.get(2).tags()).containsEntry("otel.scope.name", "opentelemetry.instrumentation.openai_v2");
      assertThat(spans.get(2).tags()).containsEntry("server.address", "host.testcontainers.internal");
      assertThat(spans.get(2).tags()).containsEntry("server.port", String.valueOf(openAiPort));
      assertThat(spans.get(2).tags()).containsKey("telemetry.auto.version");
      assertThat(spans.get(2).tags()).containsEntry("telemetry.sdk.language", "python");
      assertThat(spans.get(2).tags()).containsEntry("telemetry.sdk.name", "opentelemetry");
      assertThat(spans.get(2).tags()).containsKey("telemetry.sdk.version");
    }
  }

  @Test
  void testLogEventEmissionWithGenAiCaptureMessageContentInNodeJs() {
    try (GenericContainer<?> container = new GenericContainer<>("node:22")
        .withWorkingDirectory("/")
        .withCopyFileToContainer(MountableFile.forClasspathResource("nodejs/package.json"), "/package.json")
        .withCopyFileToContainer(MountableFile.forClasspathResource("nodejs/.npmrc"), "/.npmrc")
        .withCopyFileToContainer(MountableFile.forClasspathResource("nodejs/index.js"), "/index.js")
        .withCopyFileToContainer(MountableFile.forClasspathResource("nodejs/run.sh"), "/run.sh")
        .withCommand("bash", "run.sh")
        .withEnv("OPENAI_BASE_URL", "http://host.testcontainers.internal:" + openAiPort + "/v1")
        .withEnv("OPENAI_API_KEY", "sk-mock")
        .withEnv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://host.testcontainers.internal:" + zipkinPort)
        .withEnv("OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT", "true")
        .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("nodejs")))) {
      container.start();
      Awaitility.waitAtMost(Duration.ofMinutes(1)).untilAsserted(() -> {
        List<List<Span>> traces = store.getTraces();
        assertThat(traces).isNotEmpty();
        assertThat(traces.get(0)).hasSize(4);
      });
      List<Span> spans = store.getTraces().get(0);
      String spanId = spans.get(0).id();
      String traceId = spans.get(0).traceId();
      assertThat(spans.get(0).kind()).isNull();
      assertThat(spans.get(0).name()).isNull();
      assertThat(spans.get(0).duration()).isNull();
      assertThat(spans.get(0).localEndpoint()).isNull();
      assertThat(spans.get(0).tags()).isEmpty();
      assertThat(spans.get(0).annotations()).hasSize(1);
      assertThat(spans.get(0).annotations().get(0).value()).isEqualTo("\"gen_ai.user.message\":{\"severity_number\":9,\"body\":{\"role\":\"user\",\"content\":\"Write a short poem on OpenTelemetry.\"}}");
      assertThat(spans.get(1).id()).isEqualTo(spanId);
      assertThat(spans.get(1).traceId()).isEqualTo(traceId);
      assertThat(spans.get(1).kind()).isNull();
      assertThat(spans.get(1).name()).isNull();
      assertThat(spans.get(1).duration()).isNull();
      assertThat(spans.get(1).localEndpoint()).isNull();
      assertThat(spans.get(1).tags()).isEmpty();
      assertThat(spans.get(1).annotations()).hasSize(1);
      assertThat(spans.get(1).annotations().get(0).value()).isEqualTo("\"gen_ai.choice\":{\"severity_number\":9,\"body\":{\"finish_reason\":\"stop\",\"index\":0,\"message\":{\"content\":\"This is a mock response from the server.\"}}}");
      assertThat(spans.get(2).id()).isNotEmpty();
      assertThat(spans.get(2).id()).isNotEqualTo(spanId);
      assertThat(spans.get(2).traceId()).isEqualTo(traceId);
      assertThat(spans.get(2).parentId()).isEqualTo(spanId);
      assertThat(spans.get(2).annotations()).isEmpty();
      assertThat(spans.get(2).kind()).isEqualTo(Span.Kind.CLIENT);
      assertThat(spans.get(2).name()).isEqualTo("post");
      assertThat(spans.get(2).duration()).isGreaterThan(0);
      assertThat(spans.get(2).localEndpoint()).isNotNull();
      assertThat(spans.get(2).localEndpoint().serviceName()).isEqualTo("opentelemetry-nodejs-openai");
      assertThat(spans.get(2).tags()).hasSize(20);
      assertThat(spans.get(2).tags()).containsEntry("http.flavor", "1.1");
      assertThat(spans.get(2).tags()).containsEntry("http.host", "host.testcontainers.internal:" + openAiPort);
      assertThat(spans.get(2).tags()).containsEntry("http.method", "POST");
      assertThat(spans.get(2).tags()).containsEntry("http.response_content_length_uncompressed", "291");
      assertThat(spans.get(2).tags()).containsEntry("http.status_code", "200");
      assertThat(spans.get(2).tags()).containsEntry("http.status_text", "OK");
      assertThat(spans.get(2).tags()).containsEntry("http.target", "/v1/chat/completions");
      assertThat(spans.get(2).tags()).containsEntry("http.url", "http://host.testcontainers.internal:" + openAiPort + "/v1/chat/completions");
      assertThat(spans.get(2).tags()).containsKey("http.user_agent");
      assertThat(spans.get(2).tags()).containsKey("net.peer.ip");
      assertThat(spans.get(2).tags()).containsEntry("net.peer.name", "host.testcontainers.internal");
      assertThat(spans.get(2).tags()).containsEntry("net.peer.port", String.valueOf(openAiPort));
      assertThat(spans.get(2).tags()).containsEntry("net.transport", "ip_tcp");
      assertThat(spans.get(2).tags()).containsEntry("otel.scope.name", "@opentelemetry/instrumentation-http");
      assertThat(spans.get(2).tags()).containsKey("otel.scope.version");
      assertThat(spans.get(2).tags()).containsEntry("telemetry.distro.name", "elastic");
      assertThat(spans.get(2).tags()).containsKey("telemetry.distro.version");
      assertThat(spans.get(2).tags()).containsEntry("telemetry.sdk.language", "nodejs");
      assertThat(spans.get(2).tags()).containsEntry("telemetry.sdk.name", "opentelemetry");
      assertThat(spans.get(2).tags()).containsKey("telemetry.sdk.version");
      assertThat(spans.get(3).id()).isEqualTo(spanId);
      assertThat(spans.get(3).traceId()).isEqualTo(traceId);
      assertThat(spans.get(3).annotations()).isEmpty();
      assertThat(spans.get(3).kind()).isEqualTo(Span.Kind.CLIENT);
      assertThat(spans.get(3).name()).isEqualTo("chat gpt-4o-mini");
      assertThat(spans.get(3).duration()).isGreaterThan(0);
      assertThat(spans.get(3).localEndpoint()).isNotNull();
      assertThat(spans.get(3).localEndpoint().serviceName()).isEqualTo("opentelemetry-nodejs-openai");
      assertThat(spans.get(3).tags()).hasSize(17);
      assertThat(spans.get(3).tags()).containsEntry("gen_ai.operation.name", "chat");
      assertThat(spans.get(3).tags()).containsEntry("gen_ai.request.model", "gpt-4o-mini");
      assertThat(spans.get(3).tags()).containsEntry("gen_ai.response.finish_reasons", "stop");
      assertThat(spans.get(3).tags()).containsEntry("gen_ai.response.id", "chatcmpl-1234");
      assertThat(spans.get(3).tags()).containsEntry("gen_ai.response.model", "gpt-4o-mini");
      assertThat(spans.get(3).tags()).containsEntry("gen_ai.system", "openai");
      assertThat(spans.get(3).tags()).containsEntry("gen_ai.usage.input_tokens", "5");
      assertThat(spans.get(3).tags()).containsEntry("gen_ai.usage.output_tokens", "7");
      assertThat(spans.get(3).tags()).containsEntry("otel.scope.name", "@elastic/opentelemetry-instrumentation-openai");
      assertThat(spans.get(3).tags()).containsKey("otel.scope.version");
      assertThat(spans.get(3).tags()).containsEntry("server.address", "host.testcontainers.internal");
      assertThat(spans.get(3).tags()).containsEntry("server.port", String.valueOf(openAiPort));
      assertThat(spans.get(3).tags()).containsEntry("telemetry.distro.name", "elastic");
      assertThat(spans.get(3).tags()).containsKey("telemetry.distro.version");
      assertThat(spans.get(3).tags()).containsEntry("telemetry.sdk.language", "nodejs");
      assertThat(spans.get(3).tags()).containsEntry("telemetry.sdk.name", "opentelemetry");
      assertThat(spans.get(3).tags()).containsKey("telemetry.sdk.version");
    }
  }

  @Test
  void testLogEventEmissionWithoutGenAiCaptureMessageContentInNodeJs() {
    try (GenericContainer<?> container = new GenericContainer<>("node:22")
        .withWorkingDirectory("/")
        .withCopyFileToContainer(MountableFile.forClasspathResource("nodejs/package.json"), "/package.json")
        .withCopyFileToContainer(MountableFile.forClasspathResource("nodejs/.npmrc"), "/.npmrc")
        .withCopyFileToContainer(MountableFile.forClasspathResource("nodejs/index.js"), "/index.js")
        .withCopyFileToContainer(MountableFile.forClasspathResource("nodejs/run.sh"), "/run.sh")
        .withCommand("bash", "run.sh")
        .withEnv("OPENAI_BASE_URL", "http://host.testcontainers.internal:" + openAiPort + "/v1")
        .withEnv("OPENAI_API_KEY", "sk-mock")
        .withEnv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://host.testcontainers.internal:" + zipkinPort)
        .withEnv("OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT", "false")
        .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("nodejs")))) {
      container.start();
      Awaitility.waitAtMost(Duration.ofMinutes(1)).untilAsserted(() -> {
        List<List<Span>> traces = store.getTraces();
        assertThat(traces).isNotEmpty();
        assertThat(traces.get(0)).hasSize(3);
      });
      List<Span> spans = store.getTraces().get(0);
      String spanId = spans.get(0).id();
      String traceId = spans.get(0).traceId();
      assertThat(spans.get(0).kind()).isNull();
      assertThat(spans.get(0).name()).isNull();
      assertThat(spans.get(0).duration()).isNull();
      assertThat(spans.get(0).localEndpoint()).isNull();
      assertThat(spans.get(0).tags()).isEmpty();
      assertThat(spans.get(0).annotations()).hasSize(1);
      assertThat(spans.get(0).annotations().get(0).value()).isEqualTo("\"gen_ai.choice\":{\"severity_number\":9,\"body\":{\"finish_reason\":\"stop\",\"index\":0,\"message\":{}}}");
      assertThat(spans.get(1).id()).isNotEmpty();
      assertThat(spans.get(1).id()).isNotEqualTo(spanId);
      assertThat(spans.get(1).traceId()).isEqualTo(traceId);
      assertThat(spans.get(1).parentId()).isEqualTo(spanId);
      assertThat(spans.get(1).annotations()).isEmpty();
      assertThat(spans.get(1).kind()).isEqualTo(Span.Kind.CLIENT);
      assertThat(spans.get(1).name()).isEqualTo("post");
      assertThat(spans.get(1).duration()).isGreaterThan(0);
      assertThat(spans.get(1).localEndpoint()).isNotNull();
      assertThat(spans.get(1).localEndpoint().serviceName()).isEqualTo("opentelemetry-nodejs-openai");
      assertThat(spans.get(1).tags()).hasSize(20);
      assertThat(spans.get(1).tags()).containsEntry("http.flavor", "1.1");
      assertThat(spans.get(1).tags()).containsEntry("http.host", "host.testcontainers.internal:" + openAiPort);
      assertThat(spans.get(1).tags()).containsEntry("http.method", "POST");
      assertThat(spans.get(1).tags()).containsEntry("http.response_content_length_uncompressed", "291");
      assertThat(spans.get(1).tags()).containsEntry("http.status_code", "200");
      assertThat(spans.get(1).tags()).containsEntry("http.status_text", "OK");
      assertThat(spans.get(1).tags()).containsEntry("http.target", "/v1/chat/completions");
      assertThat(spans.get(1).tags()).containsEntry("http.url", "http://host.testcontainers.internal:" + openAiPort + "/v1/chat/completions");
      assertThat(spans.get(1).tags()).containsKey("http.user_agent");
      assertThat(spans.get(1).tags()).containsKey("net.peer.ip");
      assertThat(spans.get(1).tags()).containsEntry("net.peer.name", "host.testcontainers.internal");
      assertThat(spans.get(1).tags()).containsEntry("net.peer.port", String.valueOf(openAiPort));
      assertThat(spans.get(1).tags()).containsEntry("net.transport", "ip_tcp");
      assertThat(spans.get(1).tags()).containsEntry("otel.scope.name", "@opentelemetry/instrumentation-http");
      assertThat(spans.get(1).tags()).containsKey("otel.scope.version");
      assertThat(spans.get(1).tags()).containsEntry("telemetry.distro.name", "elastic");
      assertThat(spans.get(1).tags()).containsKey("telemetry.distro.version");
      assertThat(spans.get(1).tags()).containsEntry("telemetry.sdk.language", "nodejs");
      assertThat(spans.get(1).tags()).containsEntry("telemetry.sdk.name", "opentelemetry");
      assertThat(spans.get(1).tags()).containsKey("telemetry.sdk.version");
      assertThat(spans.get(2).id()).isEqualTo(spanId);
      assertThat(spans.get(2).traceId()).isEqualTo(traceId);
      assertThat(spans.get(2).annotations()).isEmpty();
      assertThat(spans.get(2).kind()).isEqualTo(Span.Kind.CLIENT);
      assertThat(spans.get(2).name()).isEqualTo("chat gpt-4o-mini");
      assertThat(spans.get(2).duration()).isGreaterThan(0);
      assertThat(spans.get(2).localEndpoint()).isNotNull();
      assertThat(spans.get(2).localEndpoint().serviceName()).isEqualTo("opentelemetry-nodejs-openai");
      assertThat(spans.get(2).tags()).hasSize(17);
      assertThat(spans.get(2).tags()).containsEntry("gen_ai.operation.name", "chat");
      assertThat(spans.get(2).tags()).containsEntry("gen_ai.request.model", "gpt-4o-mini");
      assertThat(spans.get(2).tags()).containsEntry("gen_ai.response.finish_reasons", "stop");
      assertThat(spans.get(2).tags()).containsEntry("gen_ai.response.id", "chatcmpl-1234");
      assertThat(spans.get(2).tags()).containsEntry("gen_ai.response.model", "gpt-4o-mini");
      assertThat(spans.get(2).tags()).containsEntry("gen_ai.system", "openai");
      assertThat(spans.get(2).tags()).containsEntry("gen_ai.usage.input_tokens", "5");
      assertThat(spans.get(2).tags()).containsEntry("gen_ai.usage.output_tokens", "7");
      assertThat(spans.get(2).tags()).containsEntry("otel.scope.name", "@elastic/opentelemetry-instrumentation-openai");
      assertThat(spans.get(2).tags()).containsKey("otel.scope.version");
      assertThat(spans.get(2).tags()).containsEntry("server.address", "host.testcontainers.internal");
      assertThat(spans.get(2).tags()).containsEntry("server.port", String.valueOf(openAiPort));
      assertThat(spans.get(2).tags()).containsEntry("telemetry.distro.name", "elastic");
      assertThat(spans.get(2).tags()).containsKey("telemetry.distro.version");
      assertThat(spans.get(2).tags()).containsEntry("telemetry.sdk.language", "nodejs");
      assertThat(spans.get(2).tags()).containsEntry("telemetry.sdk.name", "opentelemetry");
      assertThat(spans.get(2).tags()).containsKey("telemetry.sdk.version");
    }
  }

  static class ChatCompletionService {
    @Post("/v1/chat/completions")
    @Consumes("application/json")
    @Produces("application/json")
    public Map<String, Object> handleChatCompletion(String requestBody) {
      Map<String, Object> response = new HashMap<>();
      response.put("id", "chatcmpl-1234");
      response.put("object", "chat.completion");
      response.put("created", 1677652281);
      response.put("model", "gpt-4o-mini");
      Map<String, Object> message = new HashMap<>();
      message.put("role", "assistant");
      message.put("content", "This is a mock response from the server.");
      Map<String, Object> choice = new HashMap<>();
      choice.put("index", 0);
      choice.put("message", message);
      choice.put("finish_reason", "stop");
      response.put("choices", new Map[]{choice});
      Map<String, Integer> usage = new HashMap<>();
      usage.put("prompt_tokens", 5);
      usage.put("completion_tokens", 7);
      usage.put("total_tokens", 12);
      response.put("usage", usage);
      return response;
    }
  }
}
