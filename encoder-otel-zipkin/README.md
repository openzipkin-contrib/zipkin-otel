# encoder-otel-zipkin

This encodes zipkin spans into OTLP proto format.

```java
// connect the sender to the correct encoding
reporter = AsyncReporter.newBuilder(sender).build(OtelEncoder.V1);
```
