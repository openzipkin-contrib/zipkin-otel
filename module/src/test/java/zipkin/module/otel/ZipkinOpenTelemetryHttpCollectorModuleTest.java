/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin.module.otel;

import com.linecorp.armeria.spring.ArmeriaServerConfigurator;
import org.junit.jupiter.api.Test;
import zipkin2.collector.CollectorMetrics;
import zipkin2.collector.CollectorSampler;
import zipkin2.collector.otel.http.DefaultOtelResourceMapper;
import zipkin2.collector.otel.http.OpenTelemetryHttpCollector;
import zipkin2.collector.otel.http.OtelResourceMapper;
import zipkin2.storage.InMemoryStorage;
import zipkin2.storage.StorageComponent;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ZipkinOpenTelemetryHttpCollectorModuleTest {

  ApplicationContextRunner contextRunner = new ApplicationContextRunner();


  @Test
  void httpCollector_enabledByDefault() {
    contextRunner.withUserConfiguration(ZipkinOpenTelemetryHttpCollectorModule.class)
        .withUserConfiguration(InMemoryConfiguration.class)
        .run(context -> assertThat(context).hasSingleBean(OpenTelemetryHttpCollector.class)
            .hasSingleBean(DefaultOtelResourceMapper.class)
            .hasSingleBean(ArmeriaServerConfigurator.class));
  }

  @Test
  void httpCollector_resourceAttributePrefix() {
    contextRunner.withUserConfiguration(ZipkinOpenTelemetryHttpCollectorModule.class)
        .withUserConfiguration(InMemoryConfiguration.class)
        .withPropertyValues("zipkin.collector.otel.http.resource-attribute-prefix=otel.resources.")
        .run(context -> {
          assertThat(context).hasSingleBean(OpenTelemetryHttpCollector.class)
              .hasSingleBean(DefaultOtelResourceMapper.class)
              .hasSingleBean(ArmeriaServerConfigurator.class);
          OpenTelemetryHttpCollector collector = context.getBean(OpenTelemetryHttpCollector.class);
          OtelResourceMapper otelResourceMapper = collector.getOtelResourceMapper();
          assertThat(otelResourceMapper).isInstanceOf(DefaultOtelResourceMapper.class);
          assertThat(((DefaultOtelResourceMapper) otelResourceMapper).getResourceAttributePrefix()).isEqualTo("otel.resources.");
        });
  }


  @Test
  void httpCollector_customOtelResourceMapper() {
    OtelResourceMapper customOtelResourceMapper = (resource, builder) -> {

    };
    contextRunner.withUserConfiguration(ZipkinOpenTelemetryHttpCollectorModule.class)
        .withUserConfiguration(InMemoryConfiguration.class)
        .withBean(OtelResourceMapper.class, () -> customOtelResourceMapper)
        .run(context -> {
          assertThat(context).hasSingleBean(OpenTelemetryHttpCollector.class)
              .hasSingleBean(ArmeriaServerConfigurator.class)
              .doesNotHaveBean(DefaultOtelResourceMapper.class);
          OpenTelemetryHttpCollector collector = context.getBean(OpenTelemetryHttpCollector.class);
          OtelResourceMapper otelResourceMapper = collector.getOtelResourceMapper();
          assertThat(otelResourceMapper).isEqualTo(customOtelResourceMapper);
        });
  }

  @Test
  void httpCollector_canDisable() {
    contextRunner.withUserConfiguration(ZipkinOpenTelemetryHttpCollectorModule.class)
        .withUserConfiguration(InMemoryConfiguration.class)
        .withPropertyValues("zipkin.collector.otel.http.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(OpenTelemetryHttpCollector.class)
            .doesNotHaveBean(DefaultOtelResourceMapper.class)
            .doesNotHaveBean(ArmeriaServerConfigurator.class));
  }

  @Configuration
  static class InMemoryConfiguration {
    @Bean
    CollectorSampler sampler() {
      return CollectorSampler.ALWAYS_SAMPLE;
    }

    @Bean
    CollectorMetrics metrics() {
      return CollectorMetrics.NOOP_METRICS;
    }

    @Bean
    StorageComponent storage() {
      return InMemoryStorage.newBuilder().build();
    }
  }
}
