package org.synanton.extraction.engine.jsonl;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.synanton.extraction.engine.json.Json;

/**
 * Parses one model response as JSONL, independently validating each line.
 *
 * <p>One malformed line never prevents its neighbours from being accepted. The
 * parser records 1-based line numbers and stable reason codes, and the caller
 * keeps the raw evidence. It is deliberately non-streaming: the full response is
 * split on {@code \n} so record boundaries are explicit and auditable.
 *
 * <p><b>Recovery policy.</b> A valid prefix is labelled as truncation only when
 * the final non-blank line is incomplete and {@code finish_reason=length}. A
 * malformed line with any other finish reason is {@code JSON_PARSE}; valid
 * neighbours are still accepted for containment, but the response is not
 * presented as a clean truncation. A response with no valid lines is a failure.
 */
public final class JsonlLineParser {

    public static final int DEFAULT_MAX_LINE_BYTES = 16 * 1024;

    private final int maxLineBytes;

    public JsonlLineParser() {
        this(DEFAULT_MAX_LINE_BYTES);
    }

    public JsonlLineParser(int maxLineBytes) {
        this.maxLineBytes = Math.max(1, maxLineBytes);
    }

    public JsonlParseResult parse(String content) {
        return parse(content, "stop");
    }

    public JsonlParseResult parse(String content, String finishReason) {
        FenceResult fence = stripFences(content == null ? "" : content);
        String[] lines = fence.text().split("\n", -1);
        int lastNonBlank = lastNonBlankIndex(lines);
        boolean lengthFinish = finishReason != null && finishReason.equalsIgnoreCase("length");
        List<JsonlLine> accepted = new ArrayList<>();
        List<JsonlRejectedLine> rejected = new ArrayList<>();
        int blankLines = 0;
        boolean priorStructuralRejection = false;
        for (int index = 0; index < lines.length; index++) {
            String raw = lines[index];
            int lineNumber = index + 1;
            if (raw.isBlank()) {
                blankLines++;
                continue;
            }
            if (raw.getBytes(StandardCharsets.UTF_8).length > maxLineBytes) {
                rejected.add(new JsonlRejectedLine(lineNumber, raw, JsonlFailureReason.LINE_TOO_LARGE,
                        "line exceeds " + maxLineBytes + " UTF-8 bytes"));
                priorStructuralRejection = true;
                continue;
            }
            String trimmed = raw.strip();
            Map<String, Object> value;
            try {
                Object parsed = Json.parse(trimmed);
                if (!(parsed instanceof Map<?, ?> map)) {
                    rejected.add(new JsonlRejectedLine(lineNumber, raw, JsonlFailureReason.SCHEMA,
                            "line is not a JSON object"));
                    priorStructuralRejection = true;
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                value = typed;
            } catch (RuntimeException parseFailure) {
                boolean cleanPrefix = !priorStructuralRejection;
                boolean incompleteFinal = index == lastNonBlank && isIncompleteRecord(trimmed);
                JsonlFailureReason reason = index == lastNonBlank
                        && cleanPrefix
                        && (lengthFinish || incompleteFinal)
                        ? JsonlFailureReason.TRUNCATED
                        : hasMultipleTopLevelObjects(trimmed)
                                ? JsonlFailureReason.CONCATENATED_RECORDS
                                : JsonlFailureReason.JSON_PARSE;
                rejected.add(new JsonlRejectedLine(lineNumber, raw, reason,
                        parseFailure.getMessage() == null ? parseFailure.toString() : parseFailure.getMessage()));
                priorStructuralRejection = true;
                continue;
            }
            JsonlFailureReason shapeFailure = shapeFailure(value);
            if (shapeFailure != null) {
                rejected.add(new JsonlRejectedLine(lineNumber, raw, shapeFailure,
                        "record does not match the JSONL message shape"));
                priorStructuralRejection = true;
                continue;
            }
            accepted.add(new JsonlLine(lineNumber, raw, value));
        }
        return new JsonlParseResult(List.copyOf(accepted), List.copyOf(rejected),
                lines.length, blankLines, fence.stripped());
    }

    private static JsonlFailureReason shapeFailure(Map<String, Object> value) {
        Object type = value.get("type");
        if (!(type instanceof String kind)) {
            return JsonlFailureReason.SCHEMA;
        }
        if (kind.equals("entity")) {
            if (!nonBlank(value.get("surfaceForm"))) {
                return JsonlFailureReason.EMPTY_FIELD;
            }
            if (!nonBlank(value.get("proposedType"))) {
                return JsonlFailureReason.EMPTY_FIELD;
            }
            if (value.containsKey("charStart") && !(value.get("charStart") instanceof Number)) {
                return JsonlFailureReason.SCHEMA;
            }
            if (value.containsKey("charEnd") && !(value.get("charEnd") instanceof Number)) {
                return JsonlFailureReason.SCHEMA;
            }
            return null;
        }
        if (kind.equals("relation")) {
            if (!nonBlank(value.get("subjectForm"))
                    || !nonBlank(value.get("predicate"))
                    || !nonBlank(value.get("objectForm"))
                    || !nonBlank(value.get("evidenceQuote"))) {
                return JsonlFailureReason.EMPTY_FIELD;
            }
            return null;
        }
        return JsonlFailureReason.UNKNOWN_RECORD_TYPE;
    }

    private static boolean nonBlank(Object value) {
        return value instanceof String text && !text.isBlank();
    }

    private static int lastNonBlankIndex(String[] lines) {
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                return i;
            }
        }
        return -1;
    }

    static boolean hasMultipleTopLevelObjects(String line) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        boolean completed = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (completed) {
                if (!Character.isWhitespace(c)) {
                    return c == '{';
                }
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                completed = depth == 0;
            }
        }
        return false;
    }

    static boolean isIncompleteRecord(String line) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            }
        }
        return inString || depth > 0;
    }

    private static FenceResult stripFences(String content) {
        String text = content;
        String stripped = text.strip();
        if (!stripped.startsWith("```")) {
            return new FenceResult(text, false);
        }
        int firstNewline = stripped.indexOf('\n');
        if (firstNewline < 0) {
            int start = stripped.indexOf('{');
            int end = stripped.lastIndexOf('}');
            if (start >= 0 && end > start) {
                return new FenceResult(stripped.substring(start, end + 1), true);
            }
            return new FenceResult(text, false);
        }
        String body = stripped.substring(firstNewline + 1);
        int closingFence = body.lastIndexOf("```");
        if (closingFence >= 0) {
            body = body.substring(0, closingFence);
        }
        return new FenceResult(body, true);
    }

    private record FenceResult(String text, boolean stripped) {}
}
