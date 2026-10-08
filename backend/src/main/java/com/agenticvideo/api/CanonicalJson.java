package com.agenticvideo.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;

/** v1 canonicalization: recursively sort object keys, retain array order, encode UTF-8. */
public final class CanonicalJson {
  public static final ObjectMapper MAPPER = new ObjectMapper();

  private CanonicalJson() {}

  public static JsonNode tree(Object value) {
    return MAPPER.valueToTree(value);
  }

  public static String text(Object value) {
    try {
      return MAPPER.writeValueAsString(sorted(tree(value)));
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException("Invalid JSON", e);
    }
  }

  public static JsonNode parse(String value) {
    try {
      return MAPPER.readTree(value);
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException("Invalid stored JSON", e);
    }
  }

  public static String hash(Object value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(text(value).getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static void requireKey(String key) {
    if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}"))
      throw new ApiException(422, "IDEMPOTENCY_KEY_INVALID", "请提供 1–128 位幂等键");
  }

  public static void sameHash(String expected, String actual) {
    if (!expected.equals(actual))
      throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "同一幂等键对应不同请求");
  }

  private static JsonNode sorted(JsonNode value) {
    if (value.isObject()) {
      ObjectNode object = MAPPER.createObjectNode();
      var entries = new TreeMap<String, JsonNode>();
      value.fields().forEachRemaining(e -> entries.put(e.getKey(), e.getValue()));
      entries.forEach((key, node) -> object.set(key, sorted(node)));
      return object;
    }
    if (value.isArray()) {
      ArrayNode array = MAPPER.createArrayNode();
      value.forEach(node -> array.add(sorted(node)));
      return array;
    }
    return value;
  }
}
