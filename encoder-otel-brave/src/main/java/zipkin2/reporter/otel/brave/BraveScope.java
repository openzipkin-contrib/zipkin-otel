/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.reporter.otel.brave;

/**
 * Define InstrumentationScope for brave-encoder-otel
 */
public class BraveScope {
  public static String getName() {
    // TODO: What should we put here?
    return "zipkin2.reporter.otel";
  }

  public static String getVersion() {
    // TODO: Hardcoded library version
    return "0.0.1";
  }
}
