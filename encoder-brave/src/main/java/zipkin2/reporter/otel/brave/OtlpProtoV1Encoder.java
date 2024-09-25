/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.reporter.otel.brave;

import java.util.Collections;
import java.util.Map;

import brave.Tag;
import brave.handler.MutableSpan;
import io.opentelemetry.proto.trace.v1.TracesData;
import zipkin2.reporter.BytesEncoder;
import zipkin2.reporter.Encoding;

public final class OtlpProtoV1Encoder implements BytesEncoder<MutableSpan> {
  final SpanTranslator spanTranslator;

  public OtlpProtoV1Encoder(Tag<Throwable> errorTag, Map<String, String> resourceAttributes) {
    if (errorTag == null) throw new NullPointerException("errorTag == null");
    this.spanTranslator = new SpanTranslator(errorTag, resourceAttributes);
  }

  public OtlpProtoV1Encoder(Tag<Throwable> errorTag) {
    this(errorTag, Collections.emptyMap());
  }

  @Override
  public Encoding encoding() {
    return Encoding.PROTO3;
  }

  @Override
  public int sizeInBytes(MutableSpan span) {
    // TODO: Create a proto size function to avoid allocations here
    TracesData convert = translate(span);
    return encoding().listSizeInBytes(convert.getSerializedSize());
  }

  @Override
  public byte[] encode(MutableSpan span) {
    return translate(span).toByteArray();
  }

  TracesData translate(MutableSpan span) {
    return spanTranslator.translate(span);
  }
}
