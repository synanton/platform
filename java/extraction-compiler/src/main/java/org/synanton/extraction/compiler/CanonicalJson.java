package org.synanton.extraction.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Canonical JSON and SHA-256 helpers shared by the compiler outputs. */
public final class CanonicalJson {
    public static final String CANONICALIZATION = "json-v1:sorted-keys,utf8,no-whitespace,sha256";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CanonicalJson() {
    }

    public static byte[] canonicalBytes(JsonNode node) {
        try {
            return MAPPER.writeValueAsBytes(sort(node));
        } catch (Exception e) {
            throw new IllegalStateException("Could not canonicalize JSON", e);
        }
    }

    public static String sha256(JsonNode node) {
        return sha256(canonicalBytes(node));
    }

    public static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String pretty(JsonNode node) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(sort(node)) + "\n";
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize JSON", e);
        }
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    public static ArrayNode array() {
        return MAPPER.createArrayNode();
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    private static JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = MAPPER.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            for (String name : names) {
                sorted.set(name, sort(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = MAPPER.createArrayNode();
            node.forEach(child -> sorted.add(sort(child)));
            return sorted;
        }
        return node;
    }
}
