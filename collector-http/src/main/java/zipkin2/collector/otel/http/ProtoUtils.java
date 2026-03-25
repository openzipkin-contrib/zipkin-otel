/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.collector.otel.http;

import com.google.protobuf.ByteString;
import com.google.protobuf.TextFormat;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import static java.util.stream.Collectors.joining;

final class ProtoUtils {

  /**
   * Fixes trace, span and parent IDs that were mis-decoded by {@code JsonFormat.parser()}.
   *
   * <p>OTLP/JSON encodes trace and span IDs as hex strings, but protobuf's JSON parser treats
   * {@code bytes} fields as base64, producing wrong bytes. This recovers the original hex and
   * re-decodes it correctly.
   *
   * <p>The Go collector handles this natively via hex decode:
   * https://github.com/open-telemetry/opentelemetry-collector/blob/main/pdata/internal/bytesid.go
   *
   * @see <a href="https://opentelemetry.io/docs/specs/otlp/#json-protobuf-encoding">OTLP JSON encoding</a>
   */
  static void fixJsonIds(ExportTraceServiceRequest.Builder builder) {
    for (ResourceSpans.Builder rs : builder.getResourceSpansBuilderList()) {
      for (ScopeSpans.Builder ss : rs.getScopeSpansBuilderList()) {
        for (io.opentelemetry.proto.trace.v1.Span.Builder span : ss.getSpansBuilderList()) {
          span.setTraceId(hexToBytes(span.getTraceId()));
          span.setSpanId(hexToBytes(span.getSpanId()));
          span.setParentSpanId(hexToBytes(span.getParentSpanId()));
          for (io.opentelemetry.proto.trace.v1.Span.Link.Builder link : span.getLinksBuilderList()) {
            link.setTraceId(hexToBytes(link.getTraceId()));
            link.setSpanId(hexToBytes(link.getSpanId()));
          }
        }
      }
    }
  }

  /** Same as above, for log records which also carry trace and span IDs. */
  static void fixJsonIds(ExportLogsServiceRequest.Builder builder) {
    for (ResourceLogs.Builder rl : builder.getResourceLogsBuilderList()) {
      for (ScopeLogs.Builder sl : rl.getScopeLogsBuilderList()) {
        for (LogRecord.Builder log : sl.getLogRecordsBuilderList()) {
          log.setTraceId(hexToBytes(log.getTraceId()));
          log.setSpanId(hexToBytes(log.getSpanId()));
        }
      }
    }
  }

  /** Re-encodes mis-decoded bytes back to the original hex, then decodes as proper hex. */
  static ByteString hexToBytes(ByteString hexReadAsBase64) {
    if (hexReadAsBase64.isEmpty()) return hexReadAsBase64;
    String hex = Base64.getEncoder().encodeToString(hexReadAsBase64.toByteArray())
        .toLowerCase(Locale.ROOT);
    return ByteString.fromHex(hex);
  }
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
      return quote(value.getStringValue());
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
      return quote(TextFormat.escapeBytes(value.getBytesValue()));
    }
    return quote(value.toString());
  }

  static String quote(String value) {
    return "\"" + value.replace("\n", "\\n") + "\"";
  }
}
