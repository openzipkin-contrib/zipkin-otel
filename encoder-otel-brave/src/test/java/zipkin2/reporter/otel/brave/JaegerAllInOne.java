/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
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