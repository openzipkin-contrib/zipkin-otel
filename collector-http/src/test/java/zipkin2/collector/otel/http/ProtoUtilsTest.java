/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.collector.otel.http;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.ArrayValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.common.v1.KeyValueList;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static zipkin2.collector.otel.http.ProtoUtils.kvListToJson;
import static zipkin2.collector.otel.http.ProtoUtils.valueToJson;

class ProtoUtilsTest {

  @Test
  void testValueToJson() {
    assertThat(valueToJson(AnyValue.newBuilder().setStringValue("string").build())).isEqualTo("\"string\"");
    assertThat(valueToJson(AnyValue.newBuilder().setIntValue(100).build())).isEqualTo("100");
    assertThat(valueToJson(AnyValue.newBuilder().setBoolValue(true).build())).isEqualTo("true");
    assertThat(valueToJson(AnyValue.newBuilder().setDoubleValue(1.2).build())).isEqualTo("1.2");
    assertThat(valueToJson(AnyValue.newBuilder().setArrayValue(ArrayValue.newBuilder()
            .addValues(AnyValue.newBuilder().setStringValue("abc"))
            .addValues(AnyValue.newBuilder().setIntValue(20))
            .addValues(AnyValue.newBuilder().setBoolValue(false)))
        .build()))
        .isEqualTo("[\"abc\",20,false]");
    assertThat(valueToJson(AnyValue.newBuilder().setKvlistValue(KeyValueList.newBuilder()
        .addValues(KeyValue.newBuilder().setKey("x").setValue(AnyValue.newBuilder().setStringValue("abc")))
        .addValues(KeyValue.newBuilder().setKey("y").setValue(AnyValue.newBuilder().setStringValue("efg")))
        .addValues(KeyValue.newBuilder().setKey("z").setValue(AnyValue.newBuilder().setIntValue(0)))
        .build()).build()))
        .isEqualTo("{\"x\":\"abc\",\"y\":\"efg\",\"z\":0}");
    assertThat(valueToJson(AnyValue.newBuilder().setBytesValue(ByteString.fromHex("cafebabe")).build()))
        .isEqualTo("\"\\312\\376\\272\\276\"");
    assertThat(valueToJson(AnyValue.newBuilder().build())).isEqualTo("\"\"");
  }

  @Test
  void testKvListToJson() {
    assertThat(kvListToJson(Arrays.asList(KeyValue.newBuilder().setKey("string").setValue(AnyValue.newBuilder().setStringValue("s")).build(),
        KeyValue.newBuilder().setKey("int").setValue(AnyValue.newBuilder().setIntValue(100)).build(),
        KeyValue.newBuilder().setKey("boolean").setValue(AnyValue.newBuilder().setBoolValue(true)).build(),
        KeyValue.newBuilder().setKey("double").setValue(AnyValue.newBuilder().setDoubleValue(1.2)).build(),
        KeyValue.newBuilder().setKey("array").setValue(AnyValue.newBuilder().setArrayValue(ArrayValue.newBuilder()
            .addValues(AnyValue.newBuilder().setStringValue("abc"))
            .addValues(AnyValue.newBuilder().setIntValue(20))
            .addValues(AnyValue.newBuilder().setBoolValue(false)))).build(),
        KeyValue.newBuilder().setKey("kvlist").setValue(AnyValue.newBuilder().setKvlistValue(KeyValueList.newBuilder()
            .addValues(KeyValue.newBuilder().setKey("x").setValue(AnyValue.newBuilder().setStringValue("abc")))
            .addValues(KeyValue.newBuilder().setKey("y").setValue(AnyValue.newBuilder().setStringValue("efg")))
            .addValues(KeyValue.newBuilder().setKey("z").setValue(AnyValue.newBuilder().setIntValue(0)))
            .build())).build())))
        .isEqualTo("{\"string\":\"s\",\"int\":100,\"boolean\":true,\"double\":1.2,\"array\":[\"abc\",20,false],\"kvlist\":{\"x\":\"abc\",\"y\":\"efg\",\"z\":0}}");
  }

}
