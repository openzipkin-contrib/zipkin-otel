/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.reporter.otel.brave;

/**
 * OpenTelemetry Semantic Conventions Attributes
 */
final class SemanticConventionsAttributes {
  // https://github.com/open-telemetry/semantic-conventions-java/blob/v1.28.0/semconv/src/main/java/io/opentelemetry/semconv/HttpAttributes.java#L62
  static final String HTTP_REQUEST_METHOD = "http.request.method";
  // https://github.com/open-telemetry/semantic-conventions-java/blob/v1.28.0/semconv/src/main/java/io/opentelemetry/semconv/HttpAttributes.java#L97
  static final String HTTP_RESPONSE_STATUS_CODE = "http.response.status_code";
  // https://github.com/open-telemetry/semantic-conventions-java/blob/v1.28.0/semconv/src/main/java/io/opentelemetry/semconv/HttpAttributes.java#L111
  static final String HTTP_ROUTE = "http.route";
  // https://github.com/open-telemetry/semantic-conventions-java/blob/v1.28.0/semconv/src/main/java/io/opentelemetry/semconv/NetworkAttributes.java#L18
  static final String NETWORK_LOCAL_ADDRESS = "network.local.address";
  // https://github.com/open-telemetry/semantic-conventions-java/blob/v1.28.0/semconv/src/main/java/io/opentelemetry/semconv/NetworkAttributes.java#L22
  static final String NETWORK_LOCAL_PORT = "network.local.port";
  // https://github.com/open-telemetry/semantic-conventions-java/blob/v1.28.0/semconv/src/main/java/io/opentelemetry/semconv/NetworkAttributes.java#L25
  static final String NETWORK_PEER_ADDRESS = "network.peer.address";
  // https://github.com/open-telemetry/semantic-conventions-java/blob/v1.28.0/semconv/src/main/java/io/opentelemetry/semconv/NetworkAttributes.java#L28
  static final String NETWORK_PEER_PORT = "network.peer.port";
  // https://github.com/open-telemetry/semantic-conventions-java/blob/v1.28.0/semconv/src/main/java/io/opentelemetry/semconv/ServerAttributes.java#L27
  static final String SERVER_ADDRESS = "server.address";
  // https://github.com/open-telemetry/semantic-conventions-java/blob/v1.28.0/semconv/src/main/java/io/opentelemetry/semconv/ServiceAttributes.java#L27
  static final String SERVICE_NAME = "service.name";
  // https://github.com/open-telemetry/semantic-conventions-java/blob/v1.28.0/semconv/src/main/java/io/opentelemetry/semconv/UrlAttributes.java#L34
  static final String URL_FULL = "url.full";
  // https://github.com/open-telemetry/semantic-conventions-java/blob/v1.28.0/semconv/src/main/java/io/opentelemetry/semconv/UrlAttributes.java#L44
  static final String URL_PATH = "url.path";
  // https://github.com/open-telemetry/semantic-conventions-java/blob/v1.28.0/semconv-incubating/src/main/java/io/opentelemetry/semconv/incubating/PeerIncubatingAttributes.java#L21
  static final String PEER_SERVICE = "peer.service";
}
