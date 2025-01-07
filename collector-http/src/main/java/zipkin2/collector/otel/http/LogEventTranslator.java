/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.collector.otel.http;

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.common.v1.KeyValueList;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import zipkin2.Span;
import zipkin2.internal.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static zipkin2.collector.otel.http.SpanTranslator.bytesToLong;
import static zipkin2.collector.otel.http.SpanTranslator.nanoToMills;

/**
 * LogEventTranslator converts OpenTelemetry Log Events to Zipkin Spans
 * <p>
 * See <a href="https://opentelemetry.io/docs/specs/otel/logs/api/#emit-an-event">https://opentelemetry.io/docs/specs/otel/logs/api/#emit-an-event</a>
 */
final class LogEventTranslator {
  final OtelResourceMapper resourceMapper;

  public static LogEventTranslator create() {
    return newBuilder().build();
  }

  static Builder newBuilder() {
    return new Builder();
  }

  static final class Builder {
    private OtelResourceMapper resourceMapper;
    private String logEventNameAttribute;

    public Builder otelResourceMapper(OtelResourceMapper resourceMapper) {
      this.resourceMapper = resourceMapper;
      return this;
    }

    public LogEventTranslator build() {
      return new LogEventTranslator(this);
    }
  }

  private LogEventTranslator(Builder builder) {
    this.resourceMapper = builder.resourceMapper == null ? DefaultOtelResourceMapper.create() : builder.resourceMapper;
  }

  List<Span> translate(ExportLogsServiceRequest logs) {
    ArrayList<Span> spans = new ArrayList<>();
    List<ResourceLogs> resourceLogsList = logs.getResourceLogsList();
    for (ResourceLogs resourceLogs : resourceLogsList) {
      for (ScopeLogs scopeLogs : resourceLogs.getScopeLogsList()) {
        for (LogRecord logRecord : scopeLogs.getLogRecordsList()) {
          Span span = generateSpan(logRecord);
          if (span != null) {
            spans.add(span);
          }
        }
      }
    }
    return spans;
  }

  @Nullable
  Span generateSpan(LogRecord logRecord) {
    // the log record must have both trace id and span id
    if (logRecord.getTraceId().isEmpty() || logRecord.getSpanId().isEmpty()) {
      return null;
    }
    Optional<String> eventNameOptional = logRecord.getAttributesList().stream()
        .filter(attribute -> attribute.getKey().equals(SemanticConventionsAttributes.EVENT_NAME))
        .findAny()
        .map(kv -> ProtoUtils.valueToString(kv.getValue()));
    if (!eventNameOptional.isPresent()) {
      return null;
    }
    String eventName = eventNameOptional.get();
    long timestamp = nanoToMills(logRecord.getTimeUnixNano());
    KeyValueList.Builder kvListBuilder = KeyValueList.newBuilder();
    if (logRecord.getSeverityNumber() != SeverityNumber.SEVERITY_NUMBER_UNSPECIFIED) {
      kvListBuilder.addValues(KeyValue.newBuilder()
          .setKey("severity_number")
          .setValue(AnyValue.newBuilder().setIntValue(logRecord.getSeverityNumberValue())));
    }
    if (!logRecord.getSeverityText().isEmpty()) {
      kvListBuilder.addValues(KeyValue.newBuilder()
          .setKey("severity_text")
          .setValue(AnyValue.newBuilder().setStringValue(logRecord.getSeverityText())));
    }
    int droppedAttributesCount = logRecord.getDroppedAttributesCount();
    if (droppedAttributesCount > 0) {
      kvListBuilder.addValues(KeyValue.newBuilder()
          .setKey("dropped_attributes_count")
          .setValue(AnyValue.newBuilder().setIntValue(droppedAttributesCount)));
    }
    String annotationValue = "\"" + eventName + "\":" + ProtoUtils.valueToJson(AnyValue.newBuilder()
        .setKvlistValue(kvListBuilder.addValues(KeyValue.newBuilder()
            .setKey("body")
            .setValue(logRecord.getBody()))).build());
    byte[] traceIdBytes = logRecord.getTraceId().toByteArray();
    long high = bytesToLong(traceIdBytes, 0);
    long low = bytesToLong(traceIdBytes, 8);
    return Span.newBuilder()
        .traceId(high, low)
        .id(bytesToLong(logRecord.getSpanId().toByteArray(), 0))
        .addAnnotation(timestamp, annotationValue)
        .build();
  }
}
