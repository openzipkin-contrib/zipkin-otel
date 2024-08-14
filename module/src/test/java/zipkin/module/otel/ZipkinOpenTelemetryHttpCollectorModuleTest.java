/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin.module.otel;

import com.linecorp.armeria.spring.ArmeriaServerConfigurator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import zipkin2.collector.CollectorMetrics;
import zipkin2.collector.CollectorSampler;
import zipkin2.collector.otel.http.OpenTelemetryHttpCollector;
import zipkin2.storage.InMemoryStorage;
import zipkin2.storage.StorageComponent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatExceptionOfType;

class ZipkinOpenTelemetryHttpCollectorModuleTest {
  AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();

  @AfterEach void close() {
    context.close();
  }

  @Test void httpCollector_enabledByDefault() {
    context.register(
        ZipkinOpenTelemetryHttpCollectorProperties.class,
        ZipkinOpenTelemetryHttpCollectorModule.class,
        InMemoryConfiguration.class
    );
    context.refresh();

    assertThat(context.getBean(OpenTelemetryHttpCollector.class)).isNotNull();
    assertThat(context.getBean(ArmeriaServerConfigurator.class)).isNotNull();
  }

  @Test void httpCollector_canDisable() {
    assertThatExceptionOfType(NoSuchBeanDefinitionException.class).isThrownBy(() -> {
      TestPropertyValues.of("zipkin.collector.otel.http.enabled:false").applyTo(context);
      context.register(
          ZipkinOpenTelemetryHttpCollectorProperties.class,
          ZipkinOpenTelemetryHttpCollectorModule.class,
          InMemoryConfiguration.class
      );
      context.refresh();

      context.getBean(OpenTelemetryHttpCollector.class);
    });
  }

  @Configuration
  static class InMemoryConfiguration {
    @Bean CollectorSampler sampler() {
      return CollectorSampler.ALWAYS_SAMPLE;
    }

    @Bean CollectorMetrics metrics() {
      return CollectorMetrics.NOOP_METRICS;
    }

    @Bean StorageComponent storage() {
      return InMemoryStorage.newBuilder().build();
    }
  }
}
