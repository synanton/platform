package org.synanton.bench.emitter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * A.2 shared streaming corpus loader. Reads manifest FIRST (fail fast on
 * missing), then streams rows one at a time — never a full in-memory list of
 * chunks or embeddings (phantom-SKIP prevention; A.2 acceptance pins peak
 * heap ≤ 1g proven by subprocess leg).
 *
 * <p>Format-level only: rows are JSON with string fields; embeddings stay
 * opaque base64 here (decoded by the A.3 executor, not the loader).
 */
public final class CorpusLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CorpusLoader() {}

    /** Manifest read failure names the expected path (fail fast, fail loud). */
    public static class MissingManifestException extends IllegalStateException {
        MissingManifestException(Path expected) {
            super("corpus manifest missing at " + expected
                    + " — generate the corpus first (028a.10 one-command emission)");
        }
    }

    public record Manifest(
            String corpusVersion, int seed, Map<String, Integer> counts, JsonNode files) {}

    public record ChunkRow(
            String chunkId,
            String docId,
            String tenantId,
            int ordinal,
            String text,
            String embeddingB64) {}

    public interface ChunkHandler {
        void accept(ChunkRow row) throws Exception;
    }

    public static Manifest loadManifest(Path dir) throws Exception {
        Path manifest = dir.resolve("manifest.json");
        if (!Files.isRegularFile(manifest)) {
            throw new MissingManifestException(manifest.toAbsolutePath());
        }
        JsonNode root = MAPPER.readTree(manifest.toFile());
        for (String field : new String[] {"corpus_version", "seed", "counts", "files"}) {
            if (!root.hasNonNull(field)) {
                throw new IllegalStateException(
                        "manifest at " + manifest + " missing required field '" + field + "'");
            }
        }
        return new Manifest(
                root.get("corpus_version").asText(),
                root.get("seed").asInt(),
                MAPPER.convertValue(
                        root.get("counts"),
                        MAPPER.getTypeFactory().constructMapType(Map.class, String.class, Integer.class)),
                root.get("files"));
    }

    /** Streams chunks.jsonl row by row. Returns the row count. */
    public static long streamChunks(Path dir, ChunkHandler handler) throws Exception {
        Path chunks = dir.resolve("chunks.jsonl");
        if (!Files.isRegularFile(chunks)) {
            throw new IllegalStateException("chunks file missing at " + chunks.toAbsolutePath());
        }
        long count = 0;
        try (BufferedReader reader = Files.newBufferedReader(chunks, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode o = MAPPER.readTree(line);
                handler.accept(
                        new ChunkRow(
                                required(o, "chunk_id"),
                                required(o, "doc_id"),
                                required(o, "tenant_id"),
                                o.has("ordinal") ? o.get("ordinal").asInt() : 0,
                                o.has("text") ? o.get("text").asText() : "",
                                o.has("embedding_b64") ? o.get("embedding_b64").asText() : ""));
                count++;
            }
        }
        return count;
    }

    private static String required(JsonNode o, String field) {
        if (!o.hasNonNull(field)) {
            throw new IllegalStateException("chunk row missing required field '" + field + "'");
        }
        return o.get(field).asText();
    }
}
