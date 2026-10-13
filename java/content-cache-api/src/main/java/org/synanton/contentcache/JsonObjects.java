package org.synanton.contentcache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal flat-JSON reader/writer for metadata sidecar files.
 *
 * <p>Only top-level string fields are supported; every value the store
 * persists (sizes, flags, timestamps) is encoded as a string. Nested
 * values are rejected rather than silently misread. This keeps the
 * {@code content-cache-api} module free of JSON libraries.
 */
final class JsonObjects {

    private JsonObjects() {
    }

    static String write(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(Objects.requireNonNull(entry.getKey())));
            sb.append("\":\"").append(escape(Objects.requireNonNull(entry.getValue()))).append('"');
        }
        return sb.append('}').toString();
    }

    static Map<String, String> read(String raw) {
        String json = Objects.requireNonNull(raw, "raw").trim();
        if (json.length() < 2 || json.charAt(0) != '{' || json.charAt(json.length() - 1) != '}') {
            throw new ContentStoreException("metadata sidecar is not a JSON object");
        }
        Map<String, String> out = new LinkedHashMap<>();
        int i = 1;
        int end = json.length() - 1;
        while (i < end) {
            i = skipWs(json, i, end);
            if (i >= end) {
                break;
            }
            if (json.charAt(i) != '"') {
                throw new ContentStoreException("metadata sidecar has a non-string key");
            }
            int[] next = new int[1];
            String key = parseString(json, i, end, next);
            i = skipWs(json, next[0], end);
            expect(json, i, end, ':');
            i = skipWs(json, i + 1, end);
            if (i >= end || json.charAt(i) != '"') {
                throw new ContentStoreException("metadata sidecar has a non-string value");
            }
            out.put(key, parseString(json, i, end, next));
            i = skipWs(json, next[0], end);
            if (i < end && json.charAt(i) == ',') {
                i++;
            }
        }
        return out;
    }

    private static int skipWs(String json, int i, int end) {
        while (i < end && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        return i;
    }

    private static void expect(String json, int i, int end, char c) {
        if (i >= end || json.charAt(i) != c) {
            throw new ContentStoreException("metadata sidecar is malformed");
        }
    }

    private static String parseString(String json, int i, int end, int[] next) {
        StringBuilder sb = new StringBuilder();
        i++;
        while (i < end) {
            char c = json.charAt(i);
            if (c == '"') {
                next[0] = i + 1;
                return sb.toString();
            }
            if (c == '\\') {
                i++;
                if (i >= end) {
                    break;
                }
                switch (json.charAt(i)) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (i + 4 >= end) {
                            throw new ContentStoreException("metadata sidecar has a bad escape");
                        }
                        sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                        i += 4;
                        break;
                    default:
                        throw new ContentStoreException("metadata sidecar has a bad escape");
                }
            } else {
                sb.append(c);
            }
            i++;
        }
        throw new ContentStoreException("metadata sidecar has an unterminated string");
    }

    private static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.toString();
    }
}
