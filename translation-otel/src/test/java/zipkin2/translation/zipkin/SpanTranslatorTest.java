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
package zipkin2.translation.zipkin;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span.Event;
import io.opentelemetry.proto.trace.v1.Span.SpanKind;
import io.opentelemetry.proto.trace.v1.TracesData;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import zipkin2.Endpoint;
import zipkin2.Span;

public class SpanTranslatorTest {

  /**
   * This test is intentionally sensitive, so changing other parts makes obvious impact here
   */
  @Test
  void translate_clientSpan() {
    Span zipkinSpan =
        Span.newBuilder()
            .traceId("7180c278b62e8f6a216a2aea45d08fc9")
            .parentId("6b221d5bc9e6496c")
            .id("5b4185666d50f68b")
            .name("get")
            .kind(Span.Kind.CLIENT)
            .localEndpoint(Endpoint.newBuilder().serviceName("frontend").build())
            .remoteEndpoint(
                Endpoint.newBuilder()
                    .serviceName("backend")
                    .ip("192.168.99.101")
                    .port(9000)
                    .build())
            .timestamp(1_000_000L) // 1 second after epoch
            .duration(123_456L)
            .addAnnotation(1_123_000L, "foo")
            .putTag("http.path", "/api")
            .putTag("clnt/finagle.version", "6.45.0")
            .build();

    TracesData translated = SpanTranslator.translate(zipkinSpan);

    assertThat(translated)
        .isEqualTo(
            TracesData.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                    .setResource(
                        io.opentelemetry.proto.resource.v1.Resource.newBuilder().addAttributes(
                            KeyValue.newBuilder().setKey("service.name").setValue(
                                    AnyValue.newBuilder().setStringValue("frontend").build())
                                .build()
                        ).build())
                    .addScopeSpans(ScopeSpans.newBuilder()
                        .addSpans(io.opentelemetry.proto.trace.v1.Span.newBuilder()
                            .setSpanId(ByteString.fromHex(zipkinSpan.id()))
                            .setTraceId(ByteString.fromHex(zipkinSpan.traceId()))
                            .setParentSpanId(ByteString.fromHex(zipkinSpan.parentId()))
                            .setName("get")
                            .setKind(SpanKind.SPAN_KIND_CLIENT)
                            .setStartTimeUnixNano(TimeUnit.SECONDS.toNanos(1))
                            .setEndTimeUnixNano(TimeUnit.SECONDS.toNanos(1) + 123_456_000)
                            .addAttributes(
                                KeyValue.newBuilder().setKey("net.sock.peer.name").setValue(
                                        AnyValue.newBuilder().setStringValue("backend").build())
                                    .build())
                            .addAttributes(
                                KeyValue.newBuilder().setKey("net.sock.peer.addr").setValue(
                                        AnyValue.newBuilder().setStringValue("192.168.99.101").build())
                                    .build())
                            .addAttributes(
                                KeyValue.newBuilder().setKey("net.sock.peer.port").setValue(
                                    AnyValue.newBuilder().setIntValue(9000).build()).build())
                            .addAttributes(
                                KeyValue.newBuilder().setKey("clnt/finagle.version").setValue(
                                    AnyValue.newBuilder().setStringValue("6.45.0").build()).build())
                            .addAttributes(KeyValue.newBuilder().setKey("http.path").setValue(
                                AnyValue.newBuilder().setStringValue("/api").build()).build())
                            .addEvents(Event.newBuilder()
                                .setTimeUnixNano(TimeUnit.MICROSECONDS.toNanos(1_123_000L))
                                .setName("foo").build())
                            .build())
                        .build())
                    .build()).build());
  }

  @Test
  void translate_missingName() {
    Span zipkinSpan = Span.newBuilder().traceId("3").id("2").build();
    TracesData translated = SpanTranslator.translate(zipkinSpan);

    assertThat(translated.getResourceSpans(0).getScopeSpans(0).getSpans(0).getName()).isNotEmpty();
  }

  @Test
  void testTranslateSpans() {
    Span span1 =
        Span.newBuilder().id("1").traceId("1").name("/a").timestamp(1L).duration(1L).build();
    Span span2 =
        Span.newBuilder().id("2").traceId("2").name("/b").timestamp(2L).duration(1L).build();
    Span span3 =
        Span.newBuilder().id("3").traceId("1").name("/c").timestamp(3L).duration(1L).build();

    List<Span> spans = asList(span1, span2, span3);
    List<TracesData> translated = spans.stream().map(SpanTranslator::translate).collect(
        Collectors.toList());

    assertThat(translated).hasSize(3);
    assertThat(translated).extracting(
            tracesData -> tracesData.getResourceSpans(0).getScopeSpans(0).getSpans(0).getName())
        .containsExactlyInAnyOrder(
            "/a",
            "/b",
            "/c");
  }

  @Test
  void testTranslateSpanEmptyName() {
    Span spanNullName =
        Span.newBuilder().id("1").traceId("1").timestamp(1L).duration(1L).build();
    Span spanEmptyName =
        Span.newBuilder().id("2").traceId("2").name("").timestamp(2L).duration(1L).build();
    Span spanNonEmptyName =
        Span.newBuilder().id("2").traceId("2").name("somename").timestamp(2L).duration(1L).build();

    List<Span> spans = asList(spanNullName, spanEmptyName, spanNonEmptyName);
    List<TracesData> translated = spans.stream().map(SpanTranslator::translate).collect(
        Collectors.toList());

    assertThat(translated).hasSize(3);
    assertThat(
        translated.get(0).getResourceSpans(0).getScopeSpans(0).getSpans(0).getName()).isEqualTo(
        "unknown");
    assertThat(
        translated.get(1).getResourceSpans(0).getScopeSpans(0).getSpans(0).getName()).isEqualTo(
        "unknown");
    assertThat(
        translated.get(2).getResourceSpans(0).getScopeSpans(0).getSpans(0).getName()).isEqualTo(
        "somename");
  }
}
