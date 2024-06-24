/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package zipkin2.reporter.otel.brave;

import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.propagation.TraceContext;
import io.opentelemetry.sdk.common.CompletableResultCode;
import java.util.Collection;
import zipkin2.reporter.AsyncReporter;
import zipkin2.reporter.InMemoryReporterMetrics;

public class CloseableSpanHandler extends SpanHandler {

  private final AsyncReporter<MutableSpan> reporter;

  private final InMemoryReporterMetrics reporterMetrics;

  CloseableSpanHandler(AsyncReporter<MutableSpan> reporter, InMemoryReporterMetrics reporterMetrics) {
    this.reporter = reporter;
    this.reporterMetrics = reporterMetrics;
  }

  @Override
  public boolean end(TraceContext context, MutableSpan span, Cause cause) {
    long currentDropped = reporterMetrics.messagesDropped();
    this.reporter.report(span);
    this.reporter.flush();
    long newDropped = reporterMetrics.messagesDropped();
    if (newDropped > currentDropped) {
      throw new IllegalStateException("Dropped message");
    }
    return true;
  }

  public CompletableResultCode export(Collection<MutableSpan> spans) {
    try {
      for (MutableSpan span : spans) {
        end(null, span, Cause.FINISHED);
      }
    } catch (Exception ex) {
      return CompletableResultCode.ofFailure();
    }
    return CompletableResultCode.ofSuccess();
  }

  public CompletableResultCode shutdown() {
    try {
      this.reporter.flush();
      this.reporter.close();
    } catch (Exception ex) {
      return CompletableResultCode.ofFailure();
    }
    return CompletableResultCode.ofSuccess();
  }
}
