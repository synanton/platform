package org.synanton.extraction.engine.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.synanton.extraction.engine.ExtractionContext;
import org.synanton.extraction.engine.ExtractionResult;
import org.synanton.extraction.engine.PreEntity;
import org.synanton.extraction.engine.RelationCandidate;
import org.synanton.extraction.engine.TextSegment;
import org.synanton.extraction.engine.chunking.SentenceChunker;
import org.synanton.extraction.engine.regex.RegexExtractorAdapter;

/**
 * Bridge for the Python evaluator: runs the regex adapter over a corpus
 * directory and writes a predictions JSON (hand-written, so the module has no
 * runtime dependencies). This is the Phase A adapter; the LLM adapter will use
 * the same output shape.
 *
 * Usage: ExtractionCli --corpus-dir evaluation/corpus --out evaluation/regex-predictions.json
 *        [--sentence-chunks] [--max-tokens 512]
 *
 * With {@code --sentence-chunks} each document is split on sentence boundaries
 * (max tokens, one-sentence overlap) and the adapter runs per chunk; mention
 * offsets stay document-relative, so the prediction shape is unchanged.
 */
public final class ExtractionCli {

    private ExtractionCli() {
    }

    public static void main(String[] args) throws Exception {
        Path corpusDir = null;
        Path out = null;
        boolean sentenceChunks = false;
        int maxTokens = 512;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--corpus-dir" -> corpusDir = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--sentence-chunks" -> sentenceChunks = true;
                case "--max-tokens" -> maxTokens = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }
        if (corpusDir == null || out == null) {
            throw new IllegalArgumentException("--corpus-dir and --out are required");
        }

        RegexExtractorAdapter adapter = new RegexExtractorAdapter();
        List<Path> files;
        try (var stream = Files.list(corpusDir)) {
            files = stream.filter(path -> path.toString().endsWith(".txt")).sorted().toList();
        }

        StringBuilder json = new StringBuilder("{\n");
        boolean firstDocument = true;
        for (Path file : files) {
            String documentId = file.getFileName().toString().replaceAll("\\.txt$", "");
            String text = Files.readString(file);
            ExtractionContext context = new ExtractionContext("eval", documentId, Set.of());
            List<ExtractionResult> results = new ArrayList<>();
            if (sentenceChunks) {
                List<SentenceChunker.Chunk> chunks = SentenceChunker.chunk(text, maxTokens, 1);
                if (chunks.isEmpty()) {
                    chunks = List.of(new SentenceChunker.Chunk(text, 0, text.length()));
                }
                for (int chunkIndex = 0; chunkIndex < chunks.size(); chunkIndex++) {
                    SentenceChunker.Chunk chunk = chunks.get(chunkIndex);
                    results.add(adapter.extract(
                            new TextSegment(documentId + "-c" + chunkIndex, chunk.text(), chunk.charStart()),
                            context));
                }
            } else {
                results.add(adapter.extract(new TextSegment(documentId, text, 0), context));
            }
            List<PreEntity> preEntities = new ArrayList<>();
            List<RelationCandidate> relations = new ArrayList<>();
            for (ExtractionResult result : results) {
                preEntities.addAll(result.preEntities());
                relations.addAll(result.relations());
            }
            if (sentenceChunks) {
                // Overlap repeats mentions at chunk boundaries: keep one per
                // document-relative span, then remap relation endpoints.
                Map<String, PreEntity> canonical = new LinkedHashMap<>();
                Map<String, String> remap = new HashMap<>();
                for (PreEntity entity : preEntities) {
                    String key = entity.surfaceForm() + "|" + entity.charStart() + "|"
                            + entity.charEnd() + "|" + entity.proposedType();
                    PreEntity kept = canonical.putIfAbsent(key, entity);
                    remap.put(entity.mentionId(), (kept == null ? entity : kept).mentionId());
                }
                Map<String, RelationCandidate> dedupedRelations = new LinkedHashMap<>();
                for (RelationCandidate relation : relations) {
                    String subject = remap.getOrDefault(relation.subjectMentionId(), relation.subjectMentionId());
                    String object = remap.getOrDefault(relation.objectMentionId(), relation.objectMentionId());
                    RelationCandidate merged = new RelationCandidate(
                            subject, relation.predicate(), object, relation.evidenceChunkId(),
                            relation.evidenceCharStart(), relation.evidenceCharEnd());
                    String key = subject + "|" + relation.predicate() + "|" + object + "|"
                            + relation.evidenceCharStart() + "|" + relation.evidenceCharEnd();
                    dedupedRelations.putIfAbsent(key, merged);
                }
                preEntities = new ArrayList<>(canonical.values());
                relations = new ArrayList<>(dedupedRelations.values());
            }
            if (!firstDocument) {
                json.append(",\n");
            }
            firstDocument = false;
            json.append("  \"").append(escape(documentId)).append("\": {\n");
            json.append("    \"document_id\": \"").append(escape(documentId)).append("\",\n");
            json.append("    \"preEntities\": [");
            for (int i = 0; i < preEntities.size(); i++) {
                PreEntity entity = preEntities.get(i);
                if (i > 0) {
                    json.append(",");
                }
                json.append("\n      {")
                        .append("\"mentionId\": \"").append(escape(entity.mentionId())).append("\", ")
                        .append("\"surfaceForm\": \"").append(escape(entity.surfaceForm())).append("\", ")
                        .append("\"proposedType\": \"").append(escape(entity.proposedType())).append("\", ")
                        .append("\"charStart\": ").append(entity.charStart()).append(", ")
                        .append("\"charEnd\": ").append(entity.charEnd()).append(", ")
                        .append("\"context\": \"").append(escape(entity.context())).append("\"}");
            }
            json.append("\n    ],\n    \"relations\": [");
            for (int i = 0; i < relations.size(); i++) {
                RelationCandidate relation = relations.get(i);
                if (i > 0) {
                    json.append(",");
                }
                json.append("\n      {")
                        .append("\"subjectMentionId\": \"").append(escape(relation.subjectMentionId())).append("\", ")
                        .append("\"predicate\": \"").append(escape(relation.predicate())).append("\", ")
                        .append("\"objectMentionId\": \"").append(escape(relation.objectMentionId())).append("\", ")
                        .append("\"evidenceChunkId\": \"").append(escape(relation.evidenceChunkId())).append("\", ")
                        .append("\"evidenceCharStart\": ").append(relation.evidenceCharStart()).append(", ")
                        .append("\"evidenceCharEnd\": ").append(relation.evidenceCharEnd()).append("}");
            }
            json.append("\n    ]\n  }");
        }
        json.append("\n}\n");

        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(out, json.toString());
        System.out.println("wrote " + out + " (" + files.size() + " documents)");
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
