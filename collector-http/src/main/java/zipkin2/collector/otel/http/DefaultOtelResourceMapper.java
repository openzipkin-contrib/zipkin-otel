/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.collector.otel.http;

import io.opentelemetry.proto.resource.v1.Resource;
import zipkin2.Span;

/**
 * Default implementation of {@link OtelResourceMapper} that simply maps resource
 * attributes except for {@link SemanticConventionsAttributes#SERVICE_NAME} to tags with
 * optional prefix.
 */
public class DefaultOtelResourceMapper implements OtelResourceMapper {

	public static DefaultOtelResourceMapper create() {
		return newBuilder().build();
	}

	public static Builder newBuilder() {
		return new Builder();
	}

	public static final class Builder {

		private String resourceAttributePrefix = "";

		/**
		 * The prefix for tags mapped from resource attributes. Defaults to the empty
		 * string.
		 */
		public Builder resourceAttributePrefix(String resourceAttributePrefix) {
			if (resourceAttributePrefix == null) {
				throw new NullPointerException("resourceAttributePrefix == null");
			}
			this.resourceAttributePrefix = resourceAttributePrefix;
			return this;
		}

		public DefaultOtelResourceMapper build() {
			return new DefaultOtelResourceMapper(this);
		}

	}

	private final String resourceAttributePrefix;

	private DefaultOtelResourceMapper(Builder builder) {
		this.resourceAttributePrefix = builder.resourceAttributePrefix;
	}

	public String getResourceAttributePrefix() {
		return resourceAttributePrefix;
	}

	@Override
	public void accept(Resource resource, Span.Builder builder) {
		resource.getAttributesList()
			.stream()
			.filter(kv -> !kv.getKey().equals(SemanticConventionsAttributes.SERVICE_NAME))
			.forEach(kv -> builder.putTag(resourceAttributePrefix + kv.getKey(),
					ProtoUtils.valueToString(kv.getValue())));
	}

}
