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
package zipkin2.reporter.otel.zipkin;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.trace.v1.TracesData;
import org.junit.jupiter.api.Test;
import zipkin2.Span;
import zipkin2.TestObjects;
import zipkin2.translation.zipkin.SpanTranslator;

class OtelEncoderTest {
  OtelEncoder encoder = OtelEncoder.V1;
  Span zipkinSpan = TestObjects.CLIENT_SPAN;

  @Test void sizeInBytes() {
    assertThat(encoder.sizeInBytes(zipkinSpan)).isEqualTo(encoder.encode(zipkinSpan).length);
  }

  @Test void encode_writesTraceIdPrefixedSpan() throws Exception {
    assertTraceIdPrefixedSpan(encoder.encode(zipkinSpan), zipkinSpan.traceId());
  }

  void assertTraceIdPrefixedSpan(byte[] serialized, String expectedTraceId) throws Exception {
    TracesData deserialized = TracesData.parseFrom(serialized);

    assertThat(deserialized)
        .isEqualTo(SpanTranslator.translate(zipkinSpan));
    assertThat(deserialized.getResourceSpans(0).getScopeSpans(0).getSpans(0).getTraceId()).isEqualTo(
        ByteString.fromHex(expectedTraceId));
  }
}
