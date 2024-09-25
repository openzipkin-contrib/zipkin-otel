/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin.module.otel;

import com.linecorp.armeria.spring.ArmeriaServerConfigurator;
import zipkin2.collector.CollectorMetrics;
import zipkin2.collector.CollectorSampler;
import zipkin2.collector.otel.http.DefaultOtelResourceMapper;
import zipkin2.collector.otel.http.OpenTelemetryHttpCollector;
import zipkin2.collector.otel.http.OtelResourceMapper;
import zipkin2.storage.StorageComponent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "zipkin.collector.otel.http.enabled", matchIfMissing = true)
@EnableConfigurationProperties(ZipkinOpenTelemetryHttpCollectorProperties.class)
class ZipkinOpenTelemetryHttpCollectorModule {
  @Bean
  OpenTelemetryHttpCollector otelHttpCollector(StorageComponent storage,
      CollectorSampler sampler, CollectorMetrics metrics, OtelResourceMapper otelResourceMapper) {
    return OpenTelemetryHttpCollector.newBuilder()
        .storage(storage)
        .sampler(sampler)
        .metrics(metrics)
        .otelResourceMapper(otelResourceMapper)
        .build();
  }

  @Bean
  ArmeriaServerConfigurator otelHttpCollectorConfigurator(
      OpenTelemetryHttpCollector collector) {
    return collector::reconfigure;
  }

  @ConditionalOnMissingBean(OtelResourceMapper.class)
  @Bean
  OtelResourceMapper otelResourceMapper(ZipkinOpenTelemetryHttpCollectorProperties properties) {
    return new DefaultOtelResourceMapper(properties.resourceAttributePrefix());
  }
}
