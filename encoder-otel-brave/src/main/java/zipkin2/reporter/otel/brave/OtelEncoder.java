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
import io.opentelemetry.proto.trace.v1.TracesData;
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
