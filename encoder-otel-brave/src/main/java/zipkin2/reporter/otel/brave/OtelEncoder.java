/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.reporter.otel.brave;

import brave.Tag;
import brave.handler.MutableSpan;
import com.google.protobuf.CodedOutputStream;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.proto.trace.v1.TracesData;
import java.io.IOException;
import zipkin2.reporter.BytesEncoder;
import zipkin2.reporter.Encoding;

@SuppressWarnings("ImmutableEnumChecker") // because span is immutable
public class OtelEncoder implements BytesEncoder<MutableSpan> {
  final SpanTranslator spanTranslator;

  public OtelEncoder(Tag<Throwable> errorTag) {
    if (errorTag == null) throw new NullPointerException("errorTag == null");
    this.spanTranslator = new SpanTranslator(errorTag);
  }

  @Override
  public Encoding encoding() {
    return Encoding.PROTO3;
  }

  @Override public int sizeInBytes(MutableSpan span) {
    // TODO: Optimize this by caching?
    TracesData convert = translate(span);
    return encoding().listSizeInBytes(convert.getSerializedSize());
  }

  @Override public byte[] encode(MutableSpan span) {
    return translate(span).toByteArray();
  }

  TracesData translate(MutableSpan span) {
    return spanTranslator.translate(span);
  }
}
