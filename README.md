[![Gitter chat](http://img.shields.io/badge/gitter-join%20chat%20%E2%86%92-brightgreen.svg)](https://gitter.im/openzipkin/zipkin)
[![Build Status](https://github.com/openzipkin-contrib/zipkin-otel/workflows/test/badge.svg)](https://github.com/openzipkin-contrib/zipkin-otel/actions?query=workflow%3Atest)
[![Maven Central](https://img.shields.io/maven-central/v/io.zipkin.contrib.otel/zipkin-module-otel.svg)](https://search.maven.org/search?q=g:io.zipkin.contrib.otel%20AND%20a:zipkin-module-otel)

# zipkin-otel
Shared libraries that provide Zipkin integration with the OpenTelemetry. Requires JRE 11 or later.

## Usage
These components integrate traced applications and servers with OpenTelemetry protocols
via interfaces defined by [Zipkin](https://github.com/openzipkin/zipkin).

### Collectors
The component in a zipkin server that receives trace data is called a
collector. A collector decodes spans reported by applications and
persists them to a configured collector component.

Zipkin's OTLP endpoints are bound to its server port, which defaults to 9411.
Hence, applications should override OpenTelemetry exporter configuration to:
```
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:9411
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
```

| Collector                          | Description                                                                                                                    |
|------------------------------------|--------------------------------------------------------------------------------------------------------------------------------|
| [collector-http](./collector-http) | Implements the [OTLP/HTTP protocol](https://opentelemetry.io/docs/specs/otlp/#otlphttp). Both Protobuf and JSON are supported. |

#### Signal Handling

This project supports some OpenTelemetry signals with the following behavior:

| Signal  | Description                                                                                                                            |
|---------|----------------------------------------------------------------------------------------------------------------------------------------|
| Traces  | Translated to Zipkin spans. Otel attributes and resource attributes are converted into zipkin annotations.                             |
| Metrics | Not Supported.                                                                                                                         |
| Logs    | Recorded as annotations on Zipkin spans if the log entry includes a span context (span id and trace id) and an `event.name` attribute. |

### Encoders

The encoder encodes brave spans into OTLP proto format.

| Encoder                                 | Description                                    |
|-----------------------------------------|------------------------------------------------|
| [`OtlpProtoV1Encoder`](./encoder-brave) | zipkin-reporter-brave `AsyncZipkinSpanHandler` |

## Server integration

If you cannot use our [Docker image](./docker/README.md), you can still integrate
yourself by downloading a couple jars.

[Here's an example](module#quick-start) of integrating OpenTelemetry collectors.

## Troubleshooting

## Artifacts
All artifacts publish to the group ID "io.zipkin.contrib.otel". We use a common
release version for all components.

### Library Releases
Releases are at [Sonatype](https://oss.sonatype.org/content/repositories/releases) and  [Maven Central](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22io.zipkin.contrib.otel%22)

### Library Snapshots
Snapshots are uploaded to [Sonatype](https://oss.sonatype.org/content/repositories/snapshots) after
commits to main.

### Docker Images
Released versions of zipkin-otel are published to the GitHub Container Registry
as `ghcr.io/openzipkin-contrib/zipkin-otel`.

See [docker](./docker) for details.
