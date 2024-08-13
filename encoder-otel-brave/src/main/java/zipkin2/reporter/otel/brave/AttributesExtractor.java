/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.reporter.otel.brave;

import brave.Tag;
import brave.handler.MutableSpan;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;
import io.opentelemetry.proto.trace.v1.Status.StatusCode;
import java.util.Map;

/**
 * LabelExtractor extracts the set of OTel Span labels equivalent to the annotations in a
 * given Zipkin Span.
 *
 * <p>Zipkin annotations are converted to OTel Span labels by using annotation.value as the
 * key and annotation.timestamp as the value.
 *
 * <p>Zipkin tags are converted to OTel Span labels by using annotation.key as the key and
 * the String value of annotation.value as the value.
 *
 * <p>Zipkin annotations with equivalent OTel labels will be renamed to the Stackdriver
 * Trace name.
 */
final class AttributesExtractor {

  private final Tag<Throwable> errorTag;
  private final Map<String, String> renamedLabels;

  AttributesExtractor(Tag<Throwable> errorTag, Map<String, String> renamedLabels) {
    this.errorTag = errorTag;
    this.renamedLabels = renamedLabels;
  }

  void addErrorTag(Span.Builder target, MutableSpan braveSpan) {
    String errorValue = errorTag.value(braveSpan.error(), null);
    if (errorValue != null) {
      target.addAttributes(KeyValue.newBuilder().setKey(getLabelName("error")).setValue(
          AnyValue.newBuilder().setStringValue(errorValue).build()).build());
      target.setStatus(Status.newBuilder().setCode(StatusCode.STATUS_CODE_ERROR).build());
    } else {
      target.setStatus(Status.newBuilder().setCode(StatusCode.STATUS_CODE_OK).build());
    }
  }

  void addTag(Span.Builder target, String key, String value) {
    target.addAttributes(KeyValue.newBuilder().setKey(getLabelName(key)).setValue(
        AnyValue.newBuilder().setStringValue(value).build()).build());
  }

  private String getLabelName(String zipkinName) {
    String renamed = renamedLabels.get(zipkinName);
    return renamed != null ? renamed : zipkinName;
  }

}
