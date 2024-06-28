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

import io.opentelemetry.proto.trace.v1.TracesData;
import zipkin2.Span;
import zipkin2.reporter.BytesEncoder;
import zipkin2.reporter.Encoding;
import zipkin2.translation.zipkin.SpanTranslator;

@SuppressWarnings("ImmutableEnumChecker") // because span is immutable
public enum OtelEncoder implements BytesEncoder<Span> {
  V1 {
    @Override
    public Encoding encoding() {
      return Encoding.PROTO3;
    }

    @Override
    public int sizeInBytes(Span input) {
      return translate(input).getSerializedSize();
    }

    /** This encodes a TraceSpan message prefixed by a potentially padded 32 character trace ID */
    @Override
    public byte[] encode(Span span) {
      TracesData translated = translate(span);
      return translated.toByteArray();
    }

    TracesData translate(Span span) {
      return SpanTranslator.translate(span);
    }
  }
}
