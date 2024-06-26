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
package zipkin2.translation.zipkin;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.Span.Link;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class LinkUtils {

  private static final String LINKS_PREFIX = "links[";

  private static final String TRACE_ID = "links[%s].traceId";

  private static final String SPAN_ID = "links[%s].spanId";

  private static final String TAG = "links[%s].tags[%s]";

  private static final Pattern LINKS_ID = Pattern.compile("^links\\[(.*)]\\..*$");

  private static final Pattern TAG_KEY = Pattern.compile("^links\\[.*]\\.tags\\[(.*)]$");

  static boolean isApplicable(Map.Entry<String, String> entry) {
    return isApplicable(entry.getKey());
  }

  static boolean isApplicable(String key) {
    return key.startsWith(LINKS_PREFIX);
  }

  private static int linkGroup(Map.Entry<String, String> entry) {
    Matcher matcher = LINKS_ID.matcher(entry.getKey());
    if (matcher.matches()) {
      return Integer.parseInt(matcher.group(1));
    }
    return -1;
  }

  static List<Link> toLinks(Map<String, String> tags) {
    return tags.entrySet()
        .stream()
        .filter(LinkUtils::isApplicable)
        .collect(Collectors.groupingBy(LinkUtils::linkGroup))
        .values()
        .stream().map(LinkUtils::toLink)
        .collect(Collectors.toList());
  }

  private static Link toLink(List<Entry<String, String>> groupedTags) {
    String traceId = "";
    String spanId = "";
    Map<String, Object> tags = new HashMap<>();
    for (Map.Entry<String, String> groupedTag : groupedTags) {
      if (groupedTag.getKey().endsWith(".traceId")) {
        traceId = groupedTag.getValue();
      }
      else if (groupedTag.getKey().endsWith(".spanId")) {
        spanId = groupedTag.getValue();
      }
      else if (groupedTag.getKey().contains("tags")) {
        String tagKey = tagKeyNameFromString(groupedTag.getKey());
        if (tagKey != null) {
          tags.put(tagKey, groupedTag.getValue());
        }
      }
    }
    if (traceId != null && !traceId.isEmpty()) {
      List<KeyValue> keyValues = tags.entrySet().stream().map(e -> KeyValue.newBuilder().setKey(e.getKey()).setValue(
          AnyValue.newBuilder().setStringValue(String.valueOf(e.getValue())).build()).build()).collect(
          Collectors.toList());
      return Link.newBuilder()
          .setSpanId(ByteString.fromHex(spanId))
          .setTraceId(ByteString.fromHex(traceId))
          .addAllAttributes(keyValues)
          .build();
    }
    return null;
  }

  static String tagKeyNameFromString(String tag) {
    Matcher matcher = TAG_KEY.matcher(tag);
    if (matcher.matches()) {
      return matcher.group(1);
    }
    return null;
  }

  static String traceIdKey(long index) {
    return String.format(TRACE_ID, index);
  }

  static String spanIdKey(long index) {
    return String.format(SPAN_ID, index);
  }

  static String tagKey(long index, String tagKey) {
    return String.format(TAG, index, tagKey);
  }

}
