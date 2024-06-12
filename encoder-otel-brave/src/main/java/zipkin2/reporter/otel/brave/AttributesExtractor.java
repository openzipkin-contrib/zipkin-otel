/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.reporter.otel.brave;

import brave.Tag;
import brave.handler.MutableSpan;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
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

  void addErrorTag(KeyValue.Builder target, MutableSpan braveSpan) {
    String errorValue = errorTag.value(braveSpan.error(), null);
    if (errorValue != null) {
      target.setKey(getLabelName("error")).setValue(
          AnyValue.newBuilder().setStringValue(errorValue).build());
    }
  }

  void addTag(KeyValue.Builder target, String key, String value) {
    target.setKey(getLabelName(key)).setValue(
        AnyValue.newBuilder().setStringValue(value).build());
  }

  private String getLabelName(String zipkinName) {
    String renamed = renamedLabels.get(zipkinName);
    return renamed != null ? renamed : zipkinName;
  }

}
