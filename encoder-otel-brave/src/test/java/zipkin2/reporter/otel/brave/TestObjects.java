/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.reporter.otel.brave;

import brave.handler.MutableSpan;

public class TestObjects {
  static MutableSpan clientSpan() {
    MutableSpan braveSpan = new MutableSpan();
    braveSpan.traceId("7180c278b62e8f6a216a2aea45d08fc9");
    braveSpan.parentId("6b221d5bc9e6496c");
    braveSpan.id("5b4185666d50f68b");
    braveSpan.name("get");
    braveSpan.kind(brave.Span.Kind.CLIENT);
    braveSpan.localServiceName("frontend");
    braveSpan.localIp("127.0.0.1");
    braveSpan.remoteServiceName("backend");
    braveSpan.remoteIpAndPort("192.168.99.101", 9000);
    braveSpan.startTimestamp(1472470996199000L);
    braveSpan.finishTimestamp(1472470996199000L + 207000L);
    braveSpan.annotate(1472470996238000L, "foo");
    braveSpan.annotate(1472470996403000L, "bar");
    braveSpan.tag("clnt/finagle.version", "6.45.0");
    braveSpan.tag("http.path", "/api");
    braveSpan.tag("links[0].traceId", "8291c278b62e8f6a216a2aea45d08fc8");
    braveSpan.tag("links[0].spanId", "6c5295666d50f69c");
    braveSpan.tag("links[0].tags[foo]", "bar");
    return braveSpan;
  }
}
