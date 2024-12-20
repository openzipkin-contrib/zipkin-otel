/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.reporter.otel.brave;

import brave.Tag;
import brave.Tags;
import brave.propagation.TraceContext;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OtlpProtoV1EncoderTest {
  @Test
  void defaultConfiguration() {
    OtlpProtoV1Encoder encoder = OtlpProtoV1Encoder.newBuilder()
        .build();
    SpanTranslator spanTranslator = encoder.spanTranslator;
    assertThat(spanTranslator.instrumentationScope).isEqualTo(BraveScope.instrumentationScope());
    assertThat(spanTranslator.resourceAttributes).isEqualTo(Collections.emptyMap());
    assertThat(spanTranslator.tagMapper.errorTag).isEqualTo(Tags.ERROR);
  }

  @Test
  void customResourceAttributes() {
    Map<String, String> resourceAttributes = Collections.singletonMap("key", "value");
    OtlpProtoV1Encoder encoder = OtlpProtoV1Encoder.newBuilder()
        .resourceAttributes(resourceAttributes)
        .build();
    SpanTranslator spanTranslator = encoder.spanTranslator;
    assertThat(spanTranslator.instrumentationScope).isEqualTo(BraveScope.instrumentationScope());
    assertThat(spanTranslator.resourceAttributes).isEqualTo(resourceAttributes);
    assertThat(spanTranslator.tagMapper.errorTag).isEqualTo(Tags.ERROR);
  }

  @Test
  void customInstrumentationScope() {
    InstrumentationScope instrumentationScope = new InstrumentationScope("com.example.app", "1.0.0");
    OtlpProtoV1Encoder encoder = OtlpProtoV1Encoder.newBuilder()
        .instrumentationScope(instrumentationScope)
        .build();
    SpanTranslator spanTranslator = encoder.spanTranslator;
    assertThat(spanTranslator.instrumentationScope).isEqualTo(instrumentationScope);
    assertThat(spanTranslator.resourceAttributes).isEqualTo(Collections.emptyMap());
    assertThat(spanTranslator.tagMapper.errorTag).isEqualTo(Tags.ERROR);
  }

  @Test
  void customErrorTag() {
    Tag<Throwable> errorTag = new Tag<Throwable>("test") {
      @Override
      protected String parseValue(Throwable input, TraceContext context) {
        return "test";
      }
    };
    OtlpProtoV1Encoder encoder = OtlpProtoV1Encoder.newBuilder()
        .errorTag(errorTag)
        .build();
    SpanTranslator spanTranslator = encoder.spanTranslator;
    assertThat(spanTranslator.instrumentationScope).isEqualTo(BraveScope.instrumentationScope());
    assertThat(spanTranslator.resourceAttributes).isEqualTo(Collections.emptyMap());
    assertThat(spanTranslator.tagMapper.errorTag).isEqualTo(errorTag);
  }
}
