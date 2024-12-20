/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.reporter.otel.brave;

import java.util.Objects;

/**
 * Instrumentation Scope indicates the application component that produced spans. This is more
 * specific than the local service name.
 * <p>
 * See https://opentelemetry.io/docs/specs/otel/glossary/#instrumentation-scope
 *
 * @see BraveScope#instrumentationScope
 */
public final class InstrumentationScope {
  private final String name;
  private final String version;

  public InstrumentationScope(String name, String version) {
    this.name = name;
    this.version = version;
  }

  public String name() {
    return name;
  }

  public String version() {
    return version;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    InstrumentationScope that = (InstrumentationScope) o;
    return Objects.equals(name, that.name) && Objects.equals(version, that.version);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, version);
  }
}
