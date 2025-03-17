## zipkin-otel Docker image

This repository contains the Docker build definition for `zipkin-otel`.

This layers OpenTelemetry protocols on the base zipkin docker image.

## Running

```bash
$ docker run --rm -p 9411:9411 --name zipkin-otel \
  ghcr.io/openzipkin-contrib/zipkin-otel
```

## Configuration

Server configuration variables are detailed [here](../module/README.md#configuration).

Zipkin's OTLP endpoints are bound to its server port, which defaults to 9411.
Hence, applications should override OpenTelemetry exporter configuration to:
```
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:9411
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
```

## Building

To build a zipkin-otel Docker image from source, in the top level of the repository, run:

```bash
$ build-bin/docker/docker_build openzipkin-contrib/zipkin-otel:test
```

To build from a published version, run this instead:

```bash
$ build-bin/docker/docker_build openzipkin-contrib/zipkin-otel:test 0.18.1
```

