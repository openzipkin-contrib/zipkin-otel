/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.collector.otel.http;

import io.opentelemetry.proto.resource.v1.Resource;
import java.util.function.BiConsumer;

/**
 * The interface to map OpenTelemetry Resource to Zipkin Span
 */
public interface OtelResourceMapper extends BiConsumer<Resource, zipkin2.Span.Builder> {
}
