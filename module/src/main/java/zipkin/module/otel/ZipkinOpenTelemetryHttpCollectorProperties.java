/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin.module.otel;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("zipkin.collector.otel.http")
public class ZipkinOpenTelemetryHttpCollectorProperties {
}
