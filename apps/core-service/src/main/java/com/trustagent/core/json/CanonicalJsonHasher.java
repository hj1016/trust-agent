package com.trustagent.core.json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class CanonicalJsonHasher {

    private final ObjectMapper objectMapper;

    public CanonicalJsonHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CanonicalJson canonicalize(JsonNode input) {
        JsonNode normalized = normalize(input);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(normalized);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return new CanonicalJson(
                    new String(bytes, StandardCharsets.UTF_8),
                    "sha256:" + HexFormat.of().formatHex(digest.digest(bytes)));
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("JSON을 canonical form으로 만들 수 없습니다.", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    private JsonNode normalize(JsonNode node) {
        if (node.isFloatingPointNumber()) {
            throw new IllegalArgumentException("Canonical JSON에는 부동소수점을 허용하지 않습니다.");
        }
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            node.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((name, value) -> result.set(name, normalize(value)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(value -> result.add(normalize(value)));
            return result;
        }
        return node.deepCopy();
    }

    public record CanonicalJson(String json, String sha256) {
    }
}
