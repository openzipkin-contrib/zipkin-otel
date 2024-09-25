/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.collector.otel.http;

import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.semconv.ServiceAttributes;
import zipkin2.Span;

/**
 * Default implementation of {@link OtelResourceMapper} that simply maps resource attributes except for {@link ServiceAttributes#SERVICE_NAME} to tags with optional prefix.
 */
public class DefaultOtelResourceMapper implements OtelResourceMapper {
  private final String resourceAttributePrefix;

  private static final String DEFAULT_PREFIX = "";

  public DefaultOtelResourceMapper(String resourceAttributePrefix) {
    this.resourceAttributePrefix = resourceAttributePrefix;
  }

  public DefaultOtelResourceMapper() {
    this(DEFAULT_PREFIX);
  }

  public String getResourceAttributePrefix() {
    return resourceAttributePrefix;
  }

  @Override
  public void accept(Resource resource, Span.Builder builder) {
    resource.getAttributesList().stream()
        .filter(kv -> !kv.getKey().equals(ServiceAttributes.SERVICE_NAME.getKey()))
        .forEach(kv -> builder.putTag(resourceAttributePrefix + kv.getKey(), ProtoUtils.valueToString(kv.getValue())));
  }
}
