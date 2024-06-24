package zipkin2.reporter.otel.brave;

import io.opentelemetry.exporter.internal.compression.Compressor;
import java.io.OutputStream;
import java.util.Base64;

/**
 * Taken from https://github.com/open-telemetry/opentelemetry-java/blob/v1.39.0/exporters/otlp/testing-internal/src/main/java/io/opentelemetry/exporter/otlp/testing/internal/compressor/Base64Compressor.java
 */
class Base64Compressor implements Compressor {

  private static final Base64Compressor INSTANCE = new Base64Compressor();

  private Base64Compressor() {}

  public static Base64Compressor getInstance() {
    return INSTANCE;
  }

  @Override
  public String getEncoding() {
    return "base64";
  }

  @Override
  public OutputStream compress(OutputStream outputStream) {
    return Base64.getEncoder().wrap(outputStream);
  }
}