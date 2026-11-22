package org.synanton.extraction.engine.jsonl;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.synanton.extraction.engine.json.Json;

/**
 * Deterministic JSONL construction: one complete JSON object per line, with
 * embedded newlines escaped and keys sorted so repeated writes are byte-stable.
 *
 * <p>This writer is intentionally small and JDK-only. It never concatenates
 * records on one line and never emits a partial object.
 */
public final class JsonlWriter {

    private JsonlWriter() {}

    public static String write(Map<String, Object> record) {
        return toJson(record);
    }

    public static String writeAll(List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Map<String, Object> record : records) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(toJson(record));
        }
        return out.append('\n').toString();
    }

    private static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return Json.quote(text);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("JSON object keys must be strings");
                }
                sorted.put(key, entry.getValue());
            }
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(Json.quote(entry.getKey())).append(':').append(toJson(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(toJson(list.get(i)));
            }
            return out.append(']').toString();
        }
        throw new IllegalArgumentException("unsupported JSON value: " + value.getClass().getName());
    }
}
