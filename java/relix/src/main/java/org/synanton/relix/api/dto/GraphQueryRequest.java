package org.synanton.relix.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record GraphQueryRequest(
        String tenant,
        String shape,
        Map<String, Object> params
) {
    // ---- Typed param extractors ----

    public String paramString(String key) {
        Object v = params.get(key);
        return v != null ? v.toString() : null;
    }

    public UUID paramUuid(String key) {
        String v = paramString(key);
        return v != null ? UUID.fromString(v) : null;
    }

    public int paramInt(String key, int defaultValue) {
        Object v = params.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) return Integer.parseInt(s);
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    public List<String> paramStringList(String key) {
        Object v = params.get(key);
        if (v instanceof List<?> list) return (List<String>) list;
        return List.of();
    }
}
