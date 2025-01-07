/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.collector.otel.http;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.common.v1.KeyValueList;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import org.junit.jupiter.api.Test;
import zipkin2.Span;

import static org.assertj.core.api.Assertions.assertThat;
import static zipkin2.collector.otel.http.ZipkinTestUtil.stringAttribute;

class LogEventTranslatorTest {
  LogEventTranslator logEventTranslator = LogEventTranslator.create();

  @Test
  void nullSpanShouldBeReturnedWithoutSpanId() {
    Span span = logEventTranslator.generateSpan(LogRecord.newBuilder()
        .setSpanId(ByteString.fromHex("7180c278b62e8f6a216a2aea45d08fc9"))
        .build());
    assertThat(span).isNull();
  }

  @Test
  void nullSpanShouldBeReturnedWithoutTraceId() {
    Span span = logEventTranslator.generateSpan(LogRecord.newBuilder()
        .setTraceId(ByteString.fromHex("6b221d5bc9e6496c6b221d5bc9e6496c"))
        .build());
    assertThat(span).isNull();
  }

  @Test
  void nullSpanShouldBeReturnedWithoutEventNameAttribute() {
    Span span = logEventTranslator.generateSpan(LogRecord.newBuilder()
        .setSpanId(ByteString.fromHex("7180c278b62e8f6a216a2aea45d08fc9"))
        .setTraceId(ByteString.fromHex("6b221d5bc9e6496c6b221d5bc9e6496c"))
        .build());
    assertThat(span).isNull();
  }

  @Test
  void severityNumberShouldBeTranslated() {
    Span span = logEventTranslator.generateSpan(LogRecord.newBuilder()
        .setSpanId(ByteString.fromHex("7180c278b62e8f6a216a2aea45d08fc9"))
        .setTraceId(ByteString.fromHex("6b221d5bc9e6496c6b221d5bc9e6496c"))
        .setTimeUnixNano(1505855794000000L)
        .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_INFO)
        .setBody(AnyValue.newBuilder().setStringValue("Hello World!").build())
        .addAttributes(stringAttribute("event.name", "demo.event"))
        .build());
    assertThat(span).isEqualTo(Span.newBuilder()
        .id("7180c278b62e8f6a")
        .traceId("6b221d5bc9e6496c6b221d5bc9e6496c")
        .addAnnotation(1505855794000L, "\"demo.event\":{\"severity_number\":9,\"body\":\"Hello World!\"}")
        .build());
  }

  @Test
  void severityTextShouldBeTranslated() {
    Span span = logEventTranslator.generateSpan(LogRecord.newBuilder()
        .setSpanId(ByteString.fromHex("7180c278b62e8f6a216a2aea45d08fc9"))
        .setTraceId(ByteString.fromHex("6b221d5bc9e6496c6b221d5bc9e6496c"))
        .setTimeUnixNano(1505855794000000L)
        .setSeverityText("INFO")
        .setBody(AnyValue.newBuilder().setStringValue("Hello World!").build())
        .addAttributes(stringAttribute("event.name", "demo.event"))
        .build());
    assertThat(span).isEqualTo(Span.newBuilder()
        .id("7180c278b62e8f6a")
        .traceId("6b221d5bc9e6496c6b221d5bc9e6496c")
        .addAnnotation(1505855794000L, "\"demo.event\":{\"severity_text\":\"INFO\",\"body\":\"Hello World!\"}")
        .build());
  }

  @Test
  void droppedAttributesCountShouldBeTranslated() {
    Span span = logEventTranslator.generateSpan(LogRecord.newBuilder()
        .setSpanId(ByteString.fromHex("7180c278b62e8f6a216a2aea45d08fc9"))
        .setTraceId(ByteString.fromHex("6b221d5bc9e6496c6b221d5bc9e6496c"))
        .setTimeUnixNano(1505855794000000L)
        .setBody(AnyValue.newBuilder().setStringValue("Hello World!").build())
        .addAttributes(stringAttribute("event.name", "demo.event"))
        .setDroppedAttributesCount(3)
        .build());
    assertThat(span).isEqualTo(Span.newBuilder()
        .id("7180c278b62e8f6a")
        .traceId("6b221d5bc9e6496c6b221d5bc9e6496c")
        .addAnnotation(1505855794000L, "\"demo.event\":{\"dropped_attributes_count\":3,\"body\":\"Hello World!\"}")
        .build());
  }

  @Test
  void jsonBodyShouldBeTranslated() {
    Span span = logEventTranslator.generateSpan(LogRecord.newBuilder()
        .setSpanId(ByteString.fromHex("7180c278b62e8f6a216a2aea45d08fc9"))
        .setTraceId(ByteString.fromHex("6b221d5bc9e6496c6b221d5bc9e6496c"))
        .setTimeUnixNano(1505855794000000L)
        .setBody(AnyValue.newBuilder().setKvlistValue(KeyValueList.newBuilder()
            .addValues(KeyValue.newBuilder().setKey("string").setValue(AnyValue.newBuilder().setStringValue("value1")))
            .addValues(KeyValue.newBuilder().setKey("int").setValue(AnyValue.newBuilder().setIntValue(2)))))
        .addAttributes(stringAttribute("event.name", "demo.event"))
        .build());
    assertThat(span).isEqualTo(Span.newBuilder()
        .id("7180c278b62e8f6a")
        .traceId("6b221d5bc9e6496c6b221d5bc9e6496c")
        .addAnnotation(1505855794000L, "\"demo.event\":{\"body\":{\"string\":\"value1\",\"int\":2}}")
        .build());
  }
}