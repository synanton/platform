package org.synanton.synquest.ydb;

import java.util.Map;

/** Minimal JSON string-map codec (mirrors the synvault-ydb codec; zero dependencies). */
final class YdbJson {
    private YdbJson() {}

    static String toJson(Map<String, String> metadata) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : metadata.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":\"").append(escape(e.getValue())).append('"');
        }
        return sb.append('}').toString();
    }

    static Map<String, String> fromJson(String json) {
        if (json == null || json.isBlank() || json.trim().equals("{}")) {
            return Map.of();
        }
        String body = json.trim();
        body = body.substring(1, body.length() - 1);
        if (body.isBlank()) {
            return Map.of();
        }
        Map<String, String> out = new java.util.HashMap<>();
        for (String pair : body.split("\",\"")) {
            String[] kv = pair.replaceFirst("^\"", "").split("\":\"", 2);
            if (kv.length == 2) {
                out.put(unescape(kv[0]), unescape(kv[1].replaceFirst("\"$", "")));
            }
        }
        return Map.copyOf(out);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
