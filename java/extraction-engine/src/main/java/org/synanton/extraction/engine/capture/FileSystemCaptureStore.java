package org.synanton.extraction.engine.capture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.synanton.extraction.engine.ExtractionContext;
import org.synanton.extraction.engine.LlmUsage;
import org.synanton.extraction.engine.QuarantinedLine;
import org.synanton.extraction.engine.TextSegment;
import org.synanton.extraction.engine.json.Json;
import org.synanton.extraction.engine.jsonl.JsonlWriter;

/**
 * Filesystem capture store. Production paths are tenant/source-version scoped;
 * a null/blank scope is retained only for legacy diagnostic tests. Deletion and
 * expiry are scoped and idempotent. This store does not provide encryption,
 * KMS, read/export audit, or production enablement.
 */
public final class FileSystemCaptureStore implements CaptureStore {

    private final Path root;

    public FileSystemCaptureStore(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    @Override
    public void writeQuarantine(ExtractionContext context, TextSegment segment, LlmUsage usage,
                                List<QuarantinedLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        try {
            Path directory = directory(context);
            Files.createDirectories(directory);
            List<Map<String, Object>> records = new ArrayList<>(lines.size());
            for (QuarantinedLine line : lines) {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("timestamp", Instant.now().toString());
                event.put("tenant_id", context == null ? null : context.tenantId());
                event.put("source_version_id", context == null ? null : context.sourceVersionId());
                event.put("document_id", context == null ? null : context.documentId());
                event.put("chunk_id", segment == null ? null : segment.chunkId());
                event.put("finish_reason", usage == null ? null : usage.finishReason());
                event.put("line", line.lineNumber());
                event.put("reason", line.reasonCode());
                event.put("category", line.category());
                event.put("detail", line.detail());
                event.put("raw", line.raw());
                records.add(event);
            }
            write(directory, "quarantine-" + UUID.randomUUID() + ".jsonl",
                    JsonlWriter.writeAll(records));
        } catch (Exception ignored) {
            // Capture must never mask the extraction result.
        }
    }

    @Override
    public void writeParseFailure(ExtractionContext context, TextSegment segment, LlmUsage usage,
                                  String rawBody, String content, String parserInput,
                                  RuntimeException failure) {
        try {
            Path directory = directory(context);
            Files.createDirectories(directory);
            String raw = rawBody == null ? "" : rawBody;
            String parsed = content == null ? "" : content;
            String input = parserInput == null ? "" : parserInput;
            StringBuilder json = new StringBuilder(256 + raw.length() + parsed.length());
            json.append('{');
            appendField(json, "timestamp", Instant.now().toString()).append(',');
            appendField(json, "tenant_id", context == null ? null : context.tenantId()).append(',');
            appendField(json, "source_version_id", context == null ? null : context.sourceVersionId()).append(',');
            appendField(json, "document_id", context == null ? null : context.documentId()).append(',');
            appendField(json, "chunk_id", segment == null ? null : segment.chunkId()).append(',');
            appendField(json, "finish_reason", usage == null ? null : usage.finishReason()).append(',');
            json.append("\"prompt_tokens\":").append(usage == null ? 0 : usage.promptTokens()).append(',');
            json.append("\"completion_tokens\":").append(usage == null ? 0 : usage.completionTokens()).append(',');
            json.append("\"total_tokens\":").append(usage == null ? 0 : usage.totalTokens()).append(',');
            appendField(json, "raw_response_sha256", sha256(raw)).append(',');
            json.append("\"raw_response_chars\":").append(raw.length()).append(',');
            appendField(json, "content_sha256", sha256(parsed)).append(',');
            json.append("\"content_chars\":").append(parsed.length()).append(',');
            appendField(json, "parser_input_sha256", sha256(input)).append(',');
            json.append("\"parser_input_chars\":").append(input.length()).append(',');
            appendField(json, "parser_exception", failure == null ? null : failure.toString()).append(',');
            appendField(json, "raw_response", raw).append(',');
            appendField(json, "content", parsed).append(',');
            appendField(json, "parser_input", input);
            json.append('}');
            write(directory, "parse-failure-" + UUID.randomUUID() + ".json", json.toString());
        } catch (Exception ignored) {
            // Capture must never mask the parser failure.
        }
    }

    @Override
    public void deleteSource(String tenantId, String sourceVersionId) {
        if (tenantId == null || tenantId.isBlank()
                || sourceVersionId == null || sourceVersionId.isBlank()) {
            return;
        }
        Path directory = directory(tenantId, sourceVersionId);
        if (!directory.normalize().startsWith(root.normalize())) {
            throw new IllegalArgumentException("capture path escapes root");
        }
        deleteRecursively(directory);
    }

    @Override
    public int expireOlderThan(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff");
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int deleted = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(path);
                    deleted++;
                }
            }
        } catch (IOException ignored) {
            // Retention is best-effort here; failures leave data for the next run.
        }
        return deleted;
    }

    @Override
    public Optional<Path> root() {
        return Optional.of(root);
    }

    private Path directory(ExtractionContext context) {
        if (context == null || context.tenantId() == null || context.tenantId().isBlank()
                || context.sourceVersionId() == null || context.sourceVersionId().isBlank()) {
            return root;
        }
        return directory(context.tenantId(), context.sourceVersionId());
    }

    private Path directory(String tenantId, String sourceVersionId) {
        return root.resolve(sanitize(tenantId)).resolve(sanitize(sourceVersionId));
    }

    private static String sanitize(String value) {
        String cleaned = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() || ".".equals(cleaned) || "..".equals(cleaned) ? "_" : cleaned;
    }

    private static void write(Path directory, String name, String content) throws IOException {
        Path target = directory.resolve(name);
        Files.writeString(target, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems still rely on the capture directory ACL.
        }
    }

    private static void deleteRecursively(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Idempotent cleanup; the next run retries remaining entries.
        }
    }

    private static StringBuilder appendField(StringBuilder json, String key, String value) {
        return json.append(Json.quote(key)).append(':')
                .append(value == null ? "null" : Json.quote(value));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
