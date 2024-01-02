## zipkin-otel Docker image

This repository contains the Docker build definition for `zipkin-otel`.

This layers OpenTelemetry protocols on the base zipkin docker image.

## Running

```bash
$ docker run -d -p 9411:9411 --name zipkin-otel \
  ghcr.io/openzipkin/zipkin-otel
```

## Configuration

Configuration variables are detailed [here](../module/README.md#configuration).

## Building

To build a zipkin-otel Docker image from source, in the top level of the repository, run:

```bash
$ build-bin/docker/docker_build openzipkin/zipkin-otel:test
```

To build from a published version, run this instead:

```bash
$ build-bin/docker/docker_build openzipkin/zipkin-otel:test 0.18.1
```

