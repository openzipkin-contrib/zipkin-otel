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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import zipkin2.collector.CollectorMetrics;
import zipkin2.collector.CollectorSampler;
import zipkin2.collector.otel.grpc.OpenTelemetryGrpcCollector;
import zipkin2.storage.StorageComponent;

@Configuration
@ConditionalOnProperty(name = "zipkin.collector.otel.grpc.enabled", matchIfMissing = true)
@EnableConfigurationProperties(ZipkinOpenTelemetryGrpcCollectorProperties.class)
class ZipkinOpenTelemetryGrpcCollectorModule {
  @Bean OpenTelemetryGrpcCollector otelGrpcCollector(StorageComponent storage,
      CollectorSampler sampler, CollectorMetrics metrics) {
    return OpenTelemetryGrpcCollector.newBuilder()
        .storage(storage)
        .sampler(sampler)
        .metrics(metrics)
        .build();
  }

  @Bean ArmeriaServerConfigurator otelGrpcCollectorConfigurator(
      OpenTelemetryGrpcCollector collector) {
    return collector::reconfigure;
  }
}
