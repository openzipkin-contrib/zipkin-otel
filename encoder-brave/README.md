# encoder-brave

This encodes brave spans into OTLP proto format.

```java
// Use OTLP encoder when sending to an OTLP backend
spanHandler = AsyncZipkinSpanHandler.newBuilder(sender).build(new OtlpProtoV1Encoder(Tags.ERROR));
```
