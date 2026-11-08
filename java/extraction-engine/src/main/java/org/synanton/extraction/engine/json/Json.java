package org.synanton.extraction.engine.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON reader/writer for the extraction engine.
 *
 * <p>The module deliberately keeps its main classpath JDK-only so the evaluator
 * can run the CLI with {@code build/classes/java/main}. The parser is tolerant
 * of raw control characters inside strings (the 4B model occasionally emits
 * them) and tolerates unknown escapes rather than failing the whole document.
 */
public final class Json {

    private final String input;
    private int position;

    private Json(String input) {
        this.input = input;
    }

    public static Object parse(String json) {
        Json parser = new Json(json == null ? "" : json);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.position != parser.input.length()) {
            throw new IllegalArgumentException("trailing content at " + parser.position);
        }
        return value;
    }

    public static Map<String, Object> parseObject(String json) {
        Object value = parse(json);
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        throw new IllegalArgumentException("expected a JSON object");
    }

    /** Extracts the first JSON object from model output, trimming code fences. */
    public static String extractObject(String content) {
        String value = content == null ? "" : content.strip();
        if (value.startsWith("```")) {
            int firstNewline = value.indexOf('\n');
            if (firstNewline >= 0) {
                value = value.substring(firstNewline + 1);
            }
            int fence = value.lastIndexOf("```");
            if (fence >= 0) {
                value = value.substring(0, fence);
            }
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("model output contains no JSON object");
        }
        return value.substring(start, end + 1);
    }

    public static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    public static String string(Map<String, Object> object, String key, String fallback) {
        Object value = object.get(key);
        return value instanceof String text ? text : fallback;
    }

    public static int integer(Map<String, Object> object, String key, int fallback) {
        Object value = object.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.strip());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    public static List<Map<String, Object>> objectList(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                out.add(typed);
            }
        }
        return out;
    }

    private Object readValue() {
        if (position >= input.length()) {
            throw new IllegalArgumentException("unexpected end of JSON");
        }
        char c = input.charAt(position);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> object = new LinkedHashMap<>();
        skipWhitespace();
        if (peek('}')) {
            position++;
            return object;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            object.put(key, readValue());
            skipWhitespace();
            if (peek('}')) {
                position++;
                return object;
            }
            expect(',');
        }
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> values = new ArrayList<>();
        skipWhitespace();
        if (peek(']')) {
            position++;
            return values;
        }
        while (true) {
            skipWhitespace();
            values.add(readValue());
            skipWhitespace();
            if (peek(']')) {
                position++;
                return values;
            }
            expect(',');
        }
    }

    private String readString() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (position < input.length()) {
            char c = input.charAt(position++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (position >= input.length()) {
                break;
            }
            char escape = input.charAt(position++);
            switch (escape) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (position + 4 <= input.length()) {
                        String hex = input.substring(position, position + 4);
                        try {
                            out.append((char) Integer.parseInt(hex, 16));
                            position += 4;
                        } catch (NumberFormatException ignored) {
                            out.append('u');
                        }
                    } else {
                        out.append('u');
                    }
                }
                default -> out.append(escape);
            }
        }
        throw new IllegalArgumentException("unterminated string");
    }

    private Object readNumber() {
        int start = position;
        while (position < input.length() && "-+.eE0123456789".indexOf(input.charAt(position)) >= 0) {
            position++;
        }
        String token = input.substring(start, position);
        if (token.isEmpty()) {
            throw new IllegalArgumentException("unexpected character: " + input.charAt(start));
        }
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid number: " + token, e);
        }
    }

    private Object readLiteral(String literal, Object value) {
        if (!input.startsWith(literal, position)) {
            throw new IllegalArgumentException("invalid literal at " + position);
        }
        position += literal.length();
        return value;
    }

    private void expect(char expected) {
        if (position >= input.length() || input.charAt(position) != expected) {
            throw new IllegalArgumentException("expected '" + expected + "' at " + position);
        }
        position++;
    }

    private boolean peek(char c) {
        return position < input.length() && input.charAt(position) == c;
    }

    private void skipWhitespace() {
        while (position < input.length() && Character.isWhitespace(input.charAt(position))) {
            position++;
        }
    }
}
