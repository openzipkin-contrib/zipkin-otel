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

import static org.assertj.core.api.Assertions.assertThat;

import brave.Tags;
import brave.handler.MutableSpan;
import brave.propagation.TraceContext;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.Span;
import java.util.Collections;
import org.assertj.core.util.Maps;
import org.junit.jupiter.api.Test;

class AttributesExtractorTest {
  @Test void testTag() {
    AttributesExtractor extractor = new AttributesExtractor(Tags.ERROR, Collections.emptyMap());
    Span.Builder builder = Span.newBuilder();

    extractor.addTag(builder, "tag", "value");

    Span span = builder.build();
    KeyValue keyValue = span.getAttributes(0);
    assertThat(keyValue.getKey()).isEqualTo("tag");
    assertThat(keyValue.getValue()).isEqualTo(AnyValue.newBuilder().setStringValue("value").build());
  }

  @Test void testTagWithRename() {
    AttributesExtractor extractor = new AttributesExtractor(Tags.ERROR, Maps.newHashMap("tag", "tag2"));
    Span.Builder builder = Span.newBuilder();

    extractor.addTag(builder, "tag", "value");

    Span span = builder.build();
    KeyValue keyValue = span.getAttributes(0);
    assertThat(keyValue.getKey()).isEqualTo("tag2");
    assertThat(keyValue.getValue()).isEqualTo(AnyValue.newBuilder().setStringValue("value").build());
  }

  @Test void testErrorTag() {
    AttributesExtractor extractor = new AttributesExtractor(Tags.ERROR, Collections.emptyMap());
    Span.Builder builder = Span.newBuilder();

    MutableSpan serverSpan = spanWithError();

    extractor.addErrorTag(builder, serverSpan);

    Span span = builder.build();
    KeyValue keyValue = span.getAttributes(0);
    assertThat(keyValue.getKey()).isEqualTo("error");
    assertThat(keyValue.getValue()).isEqualTo(AnyValue.newBuilder().setStringValue("this cake is a lie").build());
  }

  @Test void testErrorTagWithRename() {
    AttributesExtractor extractor = new AttributesExtractor(Tags.ERROR, Maps.newHashMap("error", "error2"));
    Span.Builder builder = Span.newBuilder();

    MutableSpan serverSpan = spanWithError();

    extractor.addErrorTag(builder, serverSpan);

    Span span = builder.build();
    KeyValue keyValue = span.getAttributes(0);
    assertThat(keyValue.getKey()).isEqualTo("error2");
    assertThat(keyValue.getValue()).isEqualTo(AnyValue.newBuilder().setStringValue("this cake is a lie").build());
  }

  private static MutableSpan spanWithError() {
    MutableSpan serverSpan =
        new MutableSpan(TraceContext.newBuilder().traceId(4).spanId(5).build(), null);
    serverSpan.name("test-span");
    serverSpan.error(new RuntimeException("this cake is a lie"));
    return serverSpan;
  }

}
