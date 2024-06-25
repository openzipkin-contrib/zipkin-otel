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

import brave.Tag;
import brave.handler.MutableSpan;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.Span;
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
