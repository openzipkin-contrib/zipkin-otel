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
package zipkin2.collector.otel.http;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerConfigurator;
import com.linecorp.armeria.server.ServiceRequestContext;
import zipkin2.collector.Collector;
import zipkin2.collector.CollectorComponent;
import zipkin2.collector.CollectorMetrics;
import zipkin2.collector.CollectorSampler;
import zipkin2.storage.StorageComponent;

public final class OpenTelemetryHttpCollector extends CollectorComponent
    implements ServerConfigurator {
  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder extends CollectorComponent.Builder {

    Collector.Builder delegate = Collector.newBuilder(OpenTelemetryHttpCollector.class);
    CollectorMetrics metrics = CollectorMetrics.NOOP_METRICS;

    @Override public Builder storage(StorageComponent storageComponent) {
      delegate.storage(storageComponent);
      return this;
    }

    @Override public Builder metrics(CollectorMetrics metrics) {
      if (metrics == null) throw new NullPointerException("metrics == null");
      delegate.metrics(this.metrics = metrics.forTransport("otel/http"));
      return this;
    }

    @Override public Builder sampler(CollectorSampler sampler) {
      delegate.sampler(sampler);
      return this;
    }

    @Override public OpenTelemetryHttpCollector build() {
      return new OpenTelemetryHttpCollector(this);
    }

    Builder() {
    }
  }

  final Collector collector;
  final CollectorMetrics metrics;

  OpenTelemetryHttpCollector(Builder builder) {
    collector = builder.delegate.build();
    metrics = builder.metrics;
  }

  @Override public OpenTelemetryHttpCollector start() {
    return this;
  }

  @Override public String toString() {
    return "OpenTelemetryHttpCollector{}";
  }

  /**
   * Reconfigures the service per https://opentelemetry.io/docs/specs/otlp/#otlphttp-request
   */
  @Override public void reconfigure(ServerBuilder sb) {
    sb.service("/v1/traces", new HttpService(this));
  }

  static final class HttpService extends AbstractHttpService {
    final OpenTelemetryHttpCollector collector;

    HttpService(OpenTelemetryHttpCollector collector) {
      this.collector = collector;
    }

    @Override protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req)
        throws Exception {
      throw new RuntimeException("Implement me!");
    }
  }
}
