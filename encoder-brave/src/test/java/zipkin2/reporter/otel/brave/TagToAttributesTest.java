/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.reporter.otel.brave;

import brave.http.HttpTags;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.semconv.HttpAttributes;
import io.opentelemetry.semconv.UrlAttributes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static zipkin2.reporter.otel.brave.TagToAttribute.stringAttribute;

class TagToAttributesTest {

	@Test
	void defaultMappingShouldMapMethodTag() {
		TagToAttributes tagToAttributes = TagToAttributes.create();
		Span.Builder spanBuilder = Span.newBuilder();
		tagToAttributes.accept(spanBuilder, HttpTags.METHOD.key(), "GET");
		Span span = spanBuilder.build();
		assertThat(span.getAttributesList())
			.containsExactlyInAnyOrder(stringAttribute(HttpAttributes.HTTP_REQUEST_METHOD.getKey(), "GET"));
	}

	@Test
	void defaultMappingShouldMapPathTag() {
		TagToAttributes tagToAttributes = TagToAttributes.create();
		Span.Builder spanBuilder = Span.newBuilder();
		tagToAttributes.accept(spanBuilder, HttpTags.PATH.key(), "/entries/123");
		Span span = spanBuilder.build();
		assertThat(span.getAttributesList())
			.containsExactlyInAnyOrder(stringAttribute(UrlAttributes.URL_PATH.getKey(), "/entries/123"));
	}

	@Test
	void defaultMappingShouldMapRouteTag() {
		TagToAttributes tagToAttributes = TagToAttributes.create();
		Span.Builder spanBuilder = Span.newBuilder();
		tagToAttributes.accept(spanBuilder, HttpTags.ROUTE.key(), "/entries/{entryId}");
		Span span = spanBuilder.build();
		assertThat(span.getAttributesList())
			.containsExactlyInAnyOrder(stringAttribute(HttpAttributes.HTTP_ROUTE.getKey(), "/entries/{entryId}"));
	}

	@Test
	void defaultMappingShouldMapUrlTag() {
		TagToAttributes tagToAttributes = TagToAttributes.create();
		Span.Builder spanBuilder = Span.newBuilder();
		tagToAttributes.accept(spanBuilder, HttpTags.URL.key(), "https://example.com/entries/123");
		Span span = spanBuilder.build();
		assertThat(span.getAttributesList()).containsExactlyInAnyOrder(
				stringAttribute(UrlAttributes.URL_FULL.getKey(), "https://example.com/entries/123"),
				stringAttribute(UrlAttributes.URL_SCHEME.getKey(), "https"),
				stringAttribute(UrlAttributes.URL_PATH.getKey(), "/entries/123"));
	}

	@Test
	void defaultMappingShouldMapUrlTagWithQueryAndFragment() {
		TagToAttributes tagToAttributes = TagToAttributes.create();
		Span.Builder spanBuilder = Span.newBuilder();
		tagToAttributes.accept(spanBuilder, HttpTags.URL.key(), "https://example.com/search?q=OpenTelemetry#SemConv");
		Span span = spanBuilder.build();
		assertThat(span.getAttributesList()).containsExactlyInAnyOrder(
				stringAttribute(UrlAttributes.URL_FULL.getKey(), "https://example.com/search?q=OpenTelemetry#SemConv"),
				stringAttribute(UrlAttributes.URL_SCHEME.getKey(), "https"),
				stringAttribute(UrlAttributes.URL_PATH.getKey(), "/search"),
				stringAttribute(UrlAttributes.URL_QUERY.getKey(), "q=OpenTelemetry"),
				stringAttribute(UrlAttributes.URL_FRAGMENT.getKey(), "SemConv"));
	}

	@Test
	void defaultMappingShouldMapUrlTagNotFull() {
		TagToAttributes tagToAttributes = TagToAttributes.create();
		Span.Builder spanBuilder = Span.newBuilder();
		tagToAttributes.accept(spanBuilder, HttpTags.URL.key(), "/entries/123");
		Span span = spanBuilder.build();
		assertThat(span.getAttributesList())
			.containsExactlyInAnyOrder(stringAttribute(UrlAttributes.URL_PATH.getKey(), "/entries/123"));
	}

	@Test
	void defaultMappingShouldMapUrlTagNotFullButWithQueryAndFragment() {
		TagToAttributes tagToAttributes = TagToAttributes.create();
		Span.Builder spanBuilder = Span.newBuilder();
		tagToAttributes.accept(spanBuilder, HttpTags.URL.key(), "/search?q=OpenTelemetry#SemConv");
		Span span = spanBuilder.build();
		assertThat(span.getAttributesList()).containsExactlyInAnyOrder(
				stringAttribute(UrlAttributes.URL_PATH.getKey(), "/search"),
				stringAttribute(UrlAttributes.URL_QUERY.getKey(), "q=OpenTelemetry"),
				stringAttribute(UrlAttributes.URL_FRAGMENT.getKey(), "SemConv"));
	}

	@Test
	void defaultMappingShouldNotMapViolatedUrlTag() {
		TagToAttributes tagToAttributes = TagToAttributes.create();
		Span.Builder spanBuilder = Span.newBuilder();
		tagToAttributes.accept(spanBuilder, HttpTags.URL.key(), "https://example.com/entries|123");
		Span span = spanBuilder.build();
		assertThat(span.getAttributesList())
			.containsExactlyInAnyOrder(stringAttribute(HttpTags.URL.key(), "https://example.com/entries|123"));
	}

	@Test
	void defaultMappingShouldMapStatusCodeTag() {
		TagToAttributes tagToAttributes = TagToAttributes.create();
		Span.Builder spanBuilder = Span.newBuilder();
		tagToAttributes.accept(spanBuilder, HttpTags.STATUS_CODE.key(), "200");
		Span span = spanBuilder.build();
		assertThat(span.getAttributesList())
			.containsExactlyInAnyOrder(stringAttribute(HttpAttributes.HTTP_RESPONSE_STATUS_CODE.getKey(), "200"));
	}

	@Test
	void defaultMappingShouldNotMapUnknownTag() {
		TagToAttributes tagToAttributes = TagToAttributes.create();
		Span.Builder spanBuilder = Span.newBuilder();
		tagToAttributes.accept(spanBuilder, "status", "200");
		Span span = spanBuilder.build();
		assertThat(span.getAttributesList()).containsExactlyInAnyOrder(stringAttribute("status", "200"));
	}

	@Test
	void customMappingShouldMap() {
		TagToAttributes tagToAttributes = TagToAttributes.newBuilder()
			.withDefaults()
			.tagToAttribute("status", HttpAttributes.HTTP_RESPONSE_STATUS_CODE.getKey())
			.build();
		Span.Builder spanBuilder = Span.newBuilder();
		tagToAttributes.accept(spanBuilder, "status", "200");
		Span span = spanBuilder.build();
		assertThat(span.getAttributesList())
			.containsExactlyInAnyOrder(stringAttribute(HttpAttributes.HTTP_RESPONSE_STATUS_CODE.getKey(), "200"));
	}

}