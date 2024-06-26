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
package zipkin2.reporter.otel.brave;

import java.util.Collections;
import java.util.Set;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;

class JaegerAllInOne extends GenericContainer<JaegerAllInOne> {

  static final int JAEGER_QUERY_PORT = 16686;

  static final int JAEGER_ADMIN_PORT = 14269;

  static final int HTTP_OTLP_PORT = 4318;

  JaegerAllInOne() {
    super("jaegertracing/all-in-one:1.57");
    init();
  }

  private void init() {
    waitingFor(new BoundPortHttpWaitStrategy(JAEGER_ADMIN_PORT));
    withExposedPorts(JAEGER_ADMIN_PORT,
      JAEGER_QUERY_PORT,
      HTTP_OTLP_PORT);
  }

  int getHttpOtlpPort() {
    return getMappedPort(HTTP_OTLP_PORT);
  }

  int getQueryPort() {
    return getMappedPort(JAEGER_QUERY_PORT);
  }

  private static class BoundPortHttpWaitStrategy extends HttpWaitStrategy {
    private final int port;

    public BoundPortHttpWaitStrategy(int port) {
      this.port = port;
    }

    @Override
    protected Set<Integer> getLivenessCheckPorts() {
      int mappedPort = this.waitStrategyTarget.getMappedPort(port);
      return Collections.singleton(mappedPort);
    }
  }
}