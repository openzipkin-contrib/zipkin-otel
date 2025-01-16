/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.collector.otel.http;

/**
 * OpenTelemetry Semantic Conventions Attributes
 */
final class SemanticConventionsAttributes {

	// https://github.com/open-telemetry/semantic-conventions-java/blob/v1.29.0/semconv/src/main/java/io/opentelemetry/semconv/NetworkAttributes.java#L18
	static final String NETWORK_LOCAL_ADDRESS = "network.local.address";

	// https://github.com/open-telemetry/semantic-conventions-java/blob/v1.29.0/semconv/src/main/java/io/opentelemetry/semconv/NetworkAttributes.java#L22
	static final String NETWORK_LOCAL_PORT = "network.local.port";

	// https://github.com/open-telemetry/semantic-conventions-java/blob/v1.29.0/semconv/src/main/java/io/opentelemetry/semconv/NetworkAttributes.java#L25
	static final String NETWORK_PEER_ADDRESS = "network.peer.address";

	// https://github.com/open-telemetry/semantic-conventions-java/blob/v1.29.0/semconv/src/main/java/io/opentelemetry/semconv/NetworkAttributes.java#L28
	static final String NETWORK_PEER_PORT = "network.peer.port";

	// https://github.com/open-telemetry/semantic-conventions-java/blob/v1.29.0/semconv/src/main/java/io/opentelemetry/semconv/ServerAttributes.java#L27
	static final String SERVER_ADDRESS = "server.address";

	// https://github.com/open-telemetry/semantic-conventions-java/blob/v1.29.0/semconv/src/main/java/io/opentelemetry/semconv/ServiceAttributes.java#L27
	static final String SERVICE_NAME = "service.name";

	// https://github.com/open-telemetry/semantic-conventions-java/blob/v1.29.0/semconv/src/main/java/io/opentelemetry/semconv/OtelAttributes.java#L17
	static final String OTEL_SCOPE_NAME = "otel.scope.name";

	// https://github.com/open-telemetry/semantic-conventions-java/blob/v1.29.0/semconv/src/main/java/io/opentelemetry/semconv/OtelAttributes.java#L20
	static final String OTEL_SCOPE_VERSION = "otel.scope.version";

	// https://github.com/open-telemetry/semantic-conventions-java/blob/v1.29.0/semconv/src/main/java/io/opentelemetry/semconv/OtelAttributes.java#L23
	static final String OTEL_STATUS_CODE = "otel.status_code";

	// https://github.com/open-telemetry/semantic-conventions-java/blob/v1.29.0/semconv-incubating/src/main/java/io/opentelemetry/semconv/incubating/EventIncubatingAttributes.java#L26
	static final String EVENT_NAME = "event.name";

	// https://github.com/open-telemetry/semantic-conventions-java/blob/v1.29.0/semconv-incubating/src/main/java/io/opentelemetry/semconv/incubating/PeerIncubatingAttributes.java#L21
	static final String PEER_SERVICE = "peer.service";

}
