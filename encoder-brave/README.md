# encoder-brave

This encodes brave spans into OTLP proto format.

```java
// connect the sender to the correct encoding
spanHandler = AsyncZipkinSpanHandler.newBuilder(sender).build(new OtlpProtoV1Encoder(Tags.ERROR));
```
