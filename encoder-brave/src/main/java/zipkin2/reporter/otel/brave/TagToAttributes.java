/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package zipkin2.reporter.otel.brave;

import brave.Tag;
import brave.handler.MutableSpan;
import brave.http.HttpTags;
import io.opentelemetry.proto.trace.v1.Span;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static zipkin2.reporter.otel.brave.TagToAttribute.keyToAttributeName;
import static zipkin2.reporter.otel.brave.TagToAttribute.maybeAddStringAttribute;
import static zipkin2.reporter.otel.brave.TagToAttribute.stringAttribute;

/**
 * Tag to Attribute mappings which map brave data policy to otel semantics.
 *
 * @see <a href="https://opentelemetry.io/docs/specs/semconv/http/http-spans/">https://opentelemetry.io/docs/specs/semconv/http/http-spans/</a>
 * @see brave.http.HttpTags
 */
public final class TagToAttributes implements MutableSpan.TagConsumer<Span.Builder> {

  public static TagToAttributes create() {
    return newBuilder().withDefaults().build();
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {
    private final Map<String, TagToAttribute> map = new LinkedHashMap<>();

    /**
     * Covert brave <code>http.url</code> tag to otel attributes (<code>url.path</code>, <code>url.query</code> and <code>url.fragment</code>).<br>
     * If the <code>http.url</code> includes the schema part (mostly client side), <code>url.full</code> and <code>url.schema</code> will be added.
     */
    private static final TagToAttribute URL_TAG_TO_ATTRIBUTE = (spanBuilder, value) -> {
      try {
        URI uri = URI.create(value);
        String scheme = uri.getScheme();
        if (scheme != null) {
          spanBuilder.addAttributes(stringAttribute(SemanticConventionsAttributes.URL_FULL, value))
              .addAttributes(stringAttribute(SemanticConventionsAttributes.URL_SCHEME, scheme));
        }
        // map URI parts to "stable" attributes as of OpenTelemetry Semantic Conventions 1.29.0
        maybeAddStringAttribute(spanBuilder, SemanticConventionsAttributes.URL_PATH, uri.getPath());
        maybeAddStringAttribute(spanBuilder, SemanticConventionsAttributes.URL_QUERY, uri.getQuery());
        maybeAddStringAttribute(spanBuilder, SemanticConventionsAttributes.URL_FRAGMENT, uri.getFragment());
      } catch (IllegalArgumentException e) {
        // do not convert
        spanBuilder.addAttributes(stringAttribute(HttpTags.URL.key(), value));
      }
    };

    /**
     * Default tag to attribute mappings
     *
     * <table>
     *   <tr>
     *     <th>Brave Tag</th><th>OTel Attribute</th>
     *   </tr>
     *   <tr>
     *     <td><code>http.method</code></td><td><code>http.request.method</code></td>
     *   </tr>
     *   <tr>
     *     <td><code>http.path</code></td><td><code>url.path</code></td>
     *   </tr>
     *   <tr>
     *     <td><code>http.route</code></td><td><code>http.route</code></td>
     *   </tr>
     *   <tr>
     *     <td><code>http.url</code></td><td>See {@link #URL_TAG_TO_ATTRIBUTE}</td>
     *   </tr>
     *   <tr>
     *     <td><code>http.status_code</code></td><td><code>http.response.status_code</code></td>
     *   </tr>
     * </table>
     *
     * @see #URL_TAG_TO_ATTRIBUTE
     */
    public Builder withDefaults() {
      // TODO: brave also defines rpc and messaging data policy
      return this
          .tagToAttribute(HttpTags.METHOD, SemanticConventionsAttributes.HTTP_REQUEST_METHOD)
          .tagToAttribute(HttpTags.PATH, SemanticConventionsAttributes.URL_PATH)
          .tagToAttribute(HttpTags.ROUTE, SemanticConventionsAttributes.HTTP_ROUTE)
          .tagToAttribute(HttpTags.URL.key(), URL_TAG_TO_ATTRIBUTE)
          .tagToAttribute(HttpTags.STATUS_CODE, SemanticConventionsAttributes.HTTP_RESPONSE_STATUS_CODE);
    }

    /**
     * Set the mapping by <code>tagToAttribute</code> to the <code>tagKey</code>.
     */
    public Builder tagToAttribute(String tagKey, TagToAttribute tagToAttribute) {
      if (tagKey == null) {
        throw new IllegalArgumentException("tagKey == null");
      }
      if (tagToAttribute == null) {
        throw new NullPointerException("tagToAttribute == null");
      }
      map.put(tagKey, tagToAttribute);
      return this;
    }

    /**
     * Set a simple 1:1 mapping from a brave tag key to an otel attribute name with the same value.
     */
    public Builder tagToAttribute(String tagKey, String attributeName) {
      if (attributeName == null) {
        throw new IllegalArgumentException("attributeName == null");
      }
      return this.tagToAttribute(tagKey, keyToAttributeName(attributeName));
    }

    /**
     * Set a simple 1:1 mapping from a brave tag key to an otel attribute name with the same value.
     */
    public Builder tagToAttribute(Tag<?> tag, String attributeName) {
      if (attributeName == null) {
        throw new IllegalArgumentException("attributeName == null");
      }
      return this.tagToAttribute(tag.key(), keyToAttributeName(attributeName));
    }

    /**
     * Set a map of brave tag key to otel attribute name
     */
    public Builder tagToAttributes(Map<String, String> tagToAttributes) {
      if (tagToAttributes == null) {
        throw new NullPointerException("tagToAttributes == null");
      }
      tagToAttributes.forEach(this::tagToAttribute);
      return this;
    }

    public TagToAttributes build() {
      return new TagToAttributes(this);
    }
  }

  private final Map<String, TagToAttribute> tagToAttributeMap;

  public TagToAttributes(Builder builder) {
    this.tagToAttributeMap = Collections.unmodifiableMap(builder.map);
  }

  @Override
  public void accept(Span.Builder target, String tagKey, String tagValue) {
    TagToAttribute tagToAttribute = this.tagToAttributeMap.get(tagKey);
    if (tagToAttribute != null) {
      tagToAttribute.mapTag(target, tagValue);
    } else {
      target.addAttributes(stringAttribute(tagKey, tagValue));
    }
  }
}
