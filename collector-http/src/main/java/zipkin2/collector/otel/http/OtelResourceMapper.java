/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.collector.otel.http;

import java.util.function.BiConsumer;

import io.opentelemetry.proto.resource.v1.Resource;

/**
 * The interface to map OpenTelemetry Resource to Zipkin Span
 */
public interface OtelResourceMapper extends BiConsumer<Resource, zipkin2.Span.Builder> {

}
