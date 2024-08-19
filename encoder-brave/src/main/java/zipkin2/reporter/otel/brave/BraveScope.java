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
  static final String NAME;

  static final String VERSION;

  static {
    try (InputStream stream = BraveScope.class.getClassLoader().getResourceAsStream("scope.properties")) {
      if (stream != null) {
        Properties props = new Properties();
        props.load(stream);
        NAME = props.getProperty("name");
        VERSION = props.getProperty("version");
      }
      else {
        NAME = "unknown";
        VERSION = "unknown";
      }
    }
    catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

}
