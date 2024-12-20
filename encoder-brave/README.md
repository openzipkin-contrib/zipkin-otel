# encoder-brave

This encodes brave spans into OTLP proto format.

```java
// Use OTLP encoder when sending to an OTLP backend
spanHandler = AsyncZipkinSpanHandler.newBuilder(sender).build(OtlpProtoV1Encoder.create());
```

You can customize `OtlpProtoV1Encoder` as follows:

```java
OtlpProtoV1Encoder encoder = OtlpProtoV1Encoder.newBuilder()
    // OpenTelemetry Instrumentation scope
    .instrumentationScope(new InstrumentationScope("com.example.app", "1.0.0"))
    // OpenTelemetry Resource Attributes
    .resourceAttributes(Map.of("key", "value"))
    // Brave Error Tag
    .errorTag(Tags.ERROR)
    .build();
```
