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

    InstrumentationScope instrumentationScope = BraveScope.instrumentationScope();

    /** The throwable parser. Defaults to {@link Tags#ERROR}. */
    public Builder errorTag(Tag<Throwable> errorTag) {
      if (errorTag == null) {
        throw new NullPointerException("errorTag == null");
      }
      this.errorTag = errorTag;
      return this;
    }

    /** static resource attributes added to a {@link io.opentelemetry.proto.resource.v1.Resource}. Defaults to empty map. */
    public Builder resourceAttributes(Map<String, String> resourceAttributes) {
      if (resourceAttributes == null) {
        throw new NullPointerException("resourceAttributes == null");
      }
      this.resourceAttributes = resourceAttributes;
      return this;
    }

    /** The Instrumentation scope which represents a logical unit within the application code with which the emitted telemetry can be associated */
    public Builder instrumentationScope(InstrumentationScope instrumentationScope) {
      if (instrumentationScope == null) {
        throw new NullPointerException("implementationScope == null");
      }
      this.instrumentationScope = instrumentationScope;
      return this;
    }

    public OtlpProtoV1Encoder build() {
      return new OtlpProtoV1Encoder(this);
    }

    Builder() {

    }
  }

  final SpanTranslator spanTranslator;

  private OtlpProtoV1Encoder(Builder builder) {
    this.spanTranslator = SpanTranslator.newBuilder()
        .errorTag(builder.errorTag)
        .resourceAttributes(builder.resourceAttributes)
        .instrumentationScope(builder.instrumentationScope)
        // Use the fully-qualified class name as the SDK name following the spec
        // https://opentelemetry.io/docs/specs/semconv/attributes-registry/telemetry/#telemetry-sdk-name
        .telemetrySdkName(this.getClass().getName())
        .build();
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
