/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.reporter.otel.brave;

import java.util.Collections;
import java.util.Map;

import brave.Tag;
import brave.Tags;
import brave.handler.MutableSpan;
import io.opentelemetry.proto.trace.v1.TracesData;
import zipkin2.reporter.BytesEncoder;
import zipkin2.reporter.Encoding;

public final class OtlpProtoV1Encoder implements BytesEncoder<MutableSpan> {
  public static OtlpProtoV1Encoder create() {
    return newBuilder().build();
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {
    Tag<Throwable> errorTag = Tags.ERROR;

    Map<String, String> resourceAttributes = Collections.emptyMap();

    /** The throwable parser. Defaults to {@link Tags#ERROR}. */
    public Builder errorTag(Tag<Throwable> errorTag) {
      if (errorTag == null) {
        throw new NullPointerException("errorTag == null");
      } this.errorTag = errorTag; return this;
    }

    /** static resource attributes added to a {@link io.opentelemetry.proto.resource.v1.Resource}. Defaults to empty map. */
    public Builder resourceAttributes(Map<String, String> resourceAttributes) {
      if (resourceAttributes == null) {
        throw new NullPointerException("resourceAttributes == null");
      } this.resourceAttributes = resourceAttributes; return this;
    }

    public OtlpProtoV1Encoder build() {
      return new OtlpProtoV1Encoder(this);
    }

    Builder() {

    }
  }

  final SpanTranslator spanTranslator;

  private OtlpProtoV1Encoder(Builder builder) {
    this.spanTranslator = new SpanTranslator(builder.errorTag, builder.resourceAttributes);
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
