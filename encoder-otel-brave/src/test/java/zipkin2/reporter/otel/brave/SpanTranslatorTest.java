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
import static zipkin2.reporter.otel.brave.TestObjects.clientSpan;

import brave.Tags;
import brave.handler.MutableSpan;
import brave.propagation.TraceContext;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Span.Event;
import io.opentelemetry.proto.trace.v1.Span.SpanKind;
import io.opentelemetry.proto.trace.v1.TracesData;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SpanTranslatorTest {

  SpanTranslator spanTranslator = new SpanTranslator(Tags.ERROR);

  /**
   * This test is intentionally sensitive, so changing other parts makes obvious impact here
   */
  @Test
  void translate_clientSpan() {
    MutableSpan braveSpan = clientSpan();
    TracesData translated = spanTranslator.translate(braveSpan);

    assertThat(firstSpan(translated))
        .isEqualTo(
            Span.newBuilder()
                .setTraceId(ByteString.fromHex(braveSpan.traceId()))
                .setSpanId(ByteString.fromHex(braveSpan.id()))
                .setParentSpanId(ByteString.fromHex(braveSpan.parentId()))
                .setName("get")
                .setKind(SpanKind.SPAN_KIND_CLIENT)
                .setStartTimeUnixNano(
                    TimeUnit.MILLISECONDS.toNanos(
                        Instant.ofEpochSecond(1472470996, 199_000_000).toEpochMilli()))
                .setEndTimeUnixNano(
                    TimeUnit.MILLISECONDS.toNanos(
                        Instant.ofEpochSecond(1472470996, 406_000_000).toEpochMilli()))
                .addAllAttributes(
                    Arrays.asList(KeyValue.newBuilder().setKey("network.local.address").setValue(
                            AnyValue.newBuilder().setStringValue("127.0.0.1").build()).build(),
                        KeyValue.newBuilder().setKey("net.sock.peer.name").setValue(
                            AnyValue.newBuilder().setStringValue("backend").build()).build(),
                        KeyValue.newBuilder().setKey("net.sock.peer.addr").setValue(
                            AnyValue.newBuilder().setStringValue("192.168.99.101").build()).build(),
                        KeyValue.newBuilder().setKey("net.sock.peer.port").setValue(
                            AnyValue.newBuilder().setIntValue(9000).build()).build(),
                        KeyValue.newBuilder().setKey("clnt/finagle.version").setValue(
                            AnyValue.newBuilder().setStringValue("6.45.0").build()).build(),
                        KeyValue.newBuilder().setKey("http.path").setValue(
                            AnyValue.newBuilder().setStringValue("/api").build()).build())
                )
                .addAllEvents(Arrays.asList(
                    Event.newBuilder().setTimeUnixNano(
                            TimeUnit.MILLISECONDS.toNanos(
                                Instant.ofEpochSecond(1472470996, 238_000_000).toEpochMilli()))
                        .setName("foo").build(),
                    Event.newBuilder().setTimeUnixNano(TimeUnit.MILLISECONDS.toNanos(
                            Instant.ofEpochSecond(1472470996, 403_000_000).toEpochMilli()))
                        .setName("bar").build()))
                .build());
  }

  @Test
  void translate_missingName() {
    MutableSpan braveSpan =
        new MutableSpan(TraceContext.newBuilder().traceId(3).spanId(2).build(), null);
    TracesData translated = spanTranslator.translate(braveSpan);

    assertThat(firstSpan(translated).getName()).isEqualTo("unknown");
  }

  @Test
  void translate_emptyName() {
    MutableSpan braveSpan =
        new MutableSpan(TraceContext.newBuilder().traceId(3).spanId(2).build(), null);
    braveSpan.name("");
    TracesData translated = spanTranslator.translate(braveSpan);

    assertThat(firstSpan(translated).getName()).isEqualTo("unknown");
  }

  private static Span firstSpan(TracesData translated) {
    return translated.getResourceSpans(0).getScopeSpans(0).getSpans(0);
  }
}
