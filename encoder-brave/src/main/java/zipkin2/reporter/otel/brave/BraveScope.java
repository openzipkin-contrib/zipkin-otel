/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.reporter.otel.brave;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * Define InstrumentationScope for encoder-brave
 */
final class BraveScope {
  static final String name;

  static final String version;

  static {
    try (InputStream stream = BraveScope.class.getClassLoader().getResourceAsStream("scope.properties")) {
      if (stream != null) {
        Properties props = new Properties();
        props.load(stream);
        name = props.getProperty("name");
        version = props.getProperty("version");
      }
      else {
        name = "unknown";
        version = "unknown";
      }
    }
    catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static String getName() {
    return name;
  }

  public static String getVersion() {
    return version;
  }
}
