/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.reporter.otel.brave;

import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.Span;

/**
 * Map a Brave Tag to OTel attributes. <br>
 * The mapping can be one to many.
 */
@FunctionalInterface
public interface TagToAttribute {

	void mapTag(Span.Builder spanBuilder, String value);

	/**
	 * simple 1:1 mapping from a brave tag key to an otel attribute name with the same
	 * value.
	 */
	static TagToAttribute keyToAttributeName(String attributeName) {
		return (spanBuilder, value) -> spanBuilder.addAttributes(stringAttribute(attributeName, value));
	}

	static KeyValue stringAttribute(String key, String value) {
		return KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)).build();
	}

	static KeyValue intAttribute(String key, int value) {
		return KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setIntValue(value)).build();
	}

	static void maybeAddStringAttribute(Span.Builder spanBuilder, String key, String value) {
		if (value != null) {
			spanBuilder.addAttributes(stringAttribute(key, value));
		}
	}

	static void maybeAddIntAttribute(Span.Builder spanBuilder, String key, int value) {
		if (value != 0) {
			spanBuilder.addAttributes(intAttribute(key, value));
		}
	}

}
