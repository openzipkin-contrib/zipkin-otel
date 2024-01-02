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
import zipkin2.collector.otel.grpc.OpenTelemetryGrpcCollector;
import zipkin2.storage.InMemoryStorage;
import zipkin2.storage.StorageComponent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatExceptionOfType;

class ZipkinOpenTelemetryGrpcCollectorModuleTest {
  AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();

  @AfterEach void close() {
    context.close();
  }

  @Test void grpcCollector_enabledByDefault() {
    context.register(
        ZipkinOpenTelemetryGrpcCollectorProperties.class,
        ZipkinOpenTelemetryGrpcCollectorModule.class,
        InMemoryConfiguration.class
    );
    context.refresh();

    assertThat(context.getBean(OpenTelemetryGrpcCollector.class)).isNotNull();
    assertThat(context.getBean(ArmeriaServerConfigurator.class)).isNotNull();
  }

  @Test void grpcCollector_canDisable() {
    assertThatExceptionOfType(NoSuchBeanDefinitionException.class).isThrownBy(() -> {
      TestPropertyValues.of("zipkin.collector.otel.grpc.enabled:false").applyTo(context);
      context.register(
          ZipkinOpenTelemetryGrpcCollectorProperties.class,
          ZipkinOpenTelemetryGrpcCollectorModule.class,
          InMemoryConfiguration.class
      );
      context.refresh();

      context.getBean(OpenTelemetryGrpcCollector.class);
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
