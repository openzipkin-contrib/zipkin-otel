# zipkin-module-otel

## Overview

This is a module that can be added to
a [Zipkin Server](https://github.com/openzipkin/zipkin/tree/master/zipkin-server)
deployment to receive Spans to OpenTelemetry's OLTP/GRPC and OLTP/HTTP protocols.

## Experimental

* Note: This is currently experimental! *
* Note: This requires reporters send 128-bit trace IDs *
* Check https://github.com/openzipkin/b3-propagation/issues/6 for tracers that support 128-bit trace
  IDs

## Quick start

JRE 11+ is required to run Zipkin server.

Fetch the latest released
[executable jar for Zipkin server](https://search.maven.org/remote_content?g=io.zipkin&a=zipkin-server&v=LATEST&c=exec)
and
[module jar for otel](https://search.maven.org/remote_content?g=io.zipkin.contrib.otel&a=zipkin-module-otel&v=LATEST&c=module).
Run Zipkin server with the StackDriver Storage enabled.

For example:

```bash
$ curl -sSL https://zipkin.io/quickstart.sh | bash -s
$ curl -sSL https://zipkin.io/quickstart.sh | bash -s io.zipkin.contrib.otel:zipkin-module-otel:LATEST:module otel.jar
$ java \
    -Dloader.path='otel.jar,otel.jar!/lib' \
    -Dspring.profiles.active=otel \
    -cp zipkin.jar \
    org.springframework.boot.loader.PropertiesLauncher
```

TODO: add example of sending span in otel protocols 

The Zipkin server can be further configured as described in the
[Zipkin server documentation](https://github.com/openzipkin/zipkin/blob/master/zipkin-server/README.md).

### Configuration

Configuration can be applied either through environment variables or an external Zipkin
configuration file. The module includes default configuration that can be used as a
[reference](https://github.com/openzipkin-contrib/zipkin-otel/tree/master/autoconfigure/collector/src/main/resources/zipkin-server-otel.yml)
for users that prefer a file based approach.

| Property                             | Environment Variable          | Description                                              |
|--------------------------------------|-------------------------------|----------------------------------------------------------|
| `zipkin.collector.otel.grpc.enabled` | `COLLECTOR_GRPC_OTEL_ENABLED` | `false` disables the GRPC collector. Defaults to `true`. |
| `zipkin.collector.otel.http.enabled` | `COLLECTOR_HTTP_OTEL_ENABLED` | `false` disables the HTTP collector. Defaults to `true`. |

### Running

```bash
$ java \
    -Dloader.path='otel.jar,otel.jar!/lib' \
    -Dspring.profiles.active=otel \
    -cp zipkin.jar \
    org.springframework.boot.loader.PropertiesLauncher
```

### Testing

Once your collector is enabled, verify it is running:

```bash
$ curl -s localhost:9411/health|jq .zipkin.details.OpenTelemetryCollector
{
  "status": "UP"
}
```
