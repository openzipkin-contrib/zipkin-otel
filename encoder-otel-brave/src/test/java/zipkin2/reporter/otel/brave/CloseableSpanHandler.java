/*
 * Copyright 2024 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin2.reporter.otel.brave;

import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.propagation.TraceContext;
import io.opentelemetry.sdk.common.CompletableResultCode;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import org.awaitility.Awaitility;
import zipkin2.reporter.AsyncReporter;
import zipkin2.reporter.InMemoryReporterMetrics;

public class CloseableSpanHandler extends SpanHandler {

  private final AsyncReporter<MutableSpan> reporter;

  private final InMemoryReporterMetrics reporterMetrics;

  private final AtomicBoolean shutdown = new AtomicBoolean();

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

  public CompletableResultCode export(Collection<MutableSpan> spansToExport) {
    if (shutdown.get()) {
      return CompletableResultCode.ofFailure();
    }
    long spansBeforeExport = reporterMetrics.spans();
    try {
      for (MutableSpan span : spansToExport) {
        end(null, span, Cause.FINISHED);
      }
    } catch (Exception ex) {
      return CompletableResultCode.ofFailure();
    }
    Awaitility.await().untilTrue(new AtomicBoolean(reporterMetrics.spans() == spansBeforeExport + spansToExport.size()));
    return CompletableResultCode.ofSuccess();
  }

  public CompletableResultCode shutdown() {
    if (shutdown.get()) {
      return CompletableResultCode.ofSuccess();
    }
    try {
      this.reporter.flush();
      this.reporter.close();
      shutdown.set(true);
    } catch (Exception ex) {
      return CompletableResultCode.ofFailure();
    }
    return CompletableResultCode.ofSuccess();
  }
}
