/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.collector.otel.http;

import java.util.List;

import com.google.protobuf.TextFormat;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;

import static java.util.stream.Collectors.joining;

final class ProtoUtils {
  static String kvListToJson(List<KeyValue> attributes) {
    return attributes.stream()
        .map(entry -> "\"" + entry.getKey() + "\":" + valueToJson(entry.getValue()))
        .collect(joining(",", "{", "}"));
  }

  static String valueToString(AnyValue value) {
    if (value.hasStringValue()) {
      return value.getStringValue();
    }
    if (value.hasArrayValue()) {
      // While https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/common/README.md#attribute says
      // that an array should be encoded as a json,
      // the Otel Zipkin Exporter doesn't implement like that https://github.com/open-telemetry/opentelemetry-java/blob/main/exporters/zipkin/src/test/java/io/opentelemetry/exporter/zipkin/OtelToZipkinSpanTransformerTest.java#L382-L385
      // Also Brave doesn't use the json encoding.
      // So follow the comma separator here.
      return value.getArrayValue().getValuesList().stream()
              .map(ProtoUtils::valueToString)
              .collect(joining(","));
    }
    return valueToJson(value);
  }

  static String valueToJson(AnyValue value) {
    if (value.hasStringValue()) {
      return "\"" + value.getStringValue() + "\"";
    }
    if (value.hasArrayValue()) {
      return value.getArrayValue().getValuesList().stream()
          .map(ProtoUtils::valueToJson)
          .collect(joining(",", "[", "]"));
    }
    if (value.hasKvlistValue()) {
      return kvListToJson(value.getKvlistValue().getValuesList());
    }
    if (value.hasBoolValue()) {
      return String.valueOf(value.getBoolValue());
    }
    if (value.hasDoubleValue()) {
      return String.valueOf(value.getDoubleValue());
    }
    if (value.hasIntValue()) {
      return String.valueOf(value.getIntValue());
    }
    if (value.hasBytesValue()) {
      // TODO
      return TextFormat.escapeBytes(value.getBytesValue());
    }
    return value.toString();
  }
}
