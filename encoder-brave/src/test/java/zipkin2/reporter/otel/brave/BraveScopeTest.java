/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.reporter.otel.brave;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BraveScopeTest {

  @Test
  void getName() {
    assertThat(BraveScope.getName()).isEqualTo("io.zipkin.contrib.otel:encoder-brave");
  }
}
