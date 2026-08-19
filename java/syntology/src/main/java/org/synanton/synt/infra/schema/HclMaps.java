package org.synanton.synt.infra.schema;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class HclMaps {

    private HclMaps() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, nested) -> result.put(String.valueOf(key), nested));
            return result;
        }
        return Map.of();
    }

    static List<Object> asList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        return List.of(value);
    }

    static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static List<String> asStringList(Object value) {
        List<String> result = new ArrayList<>();
        for (Object item : asList(value)) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    /**
     * Labeled HCL blocks ({@code class "Supplier" {}}) become nested maps.
     * Repeated unlabeled blocks may be a list of maps.
     */
    static Map<String, Map<String, Object>> labeledBlocks(Object value) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map && !looksLikeAttributes(map)) {
            map.forEach((key, nested) -> result.put(String.valueOf(key), asMap(nested)));
            return result;
        }
        int index = 0;
        for (Object item : asList(value)) {
            Map<String, Object> body = asMap(item);
            Object id = body.getOrDefault("id", body.get("name"));
            String key = id != null ? String.valueOf(id) : "block-" + index++;
            result.put(key, body);
        }
        return result;
    }

    private static boolean looksLikeAttributes(Map<?, ?> map) {
        if (map.isEmpty()) {
            return false;
        }
        Object first = map.values().iterator().next();
        return !(first instanceof Map<?, ?>);
    }
}
