package org.synanton.bench.corpus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 028a.8/.10 canonical corpus serialization. Byte-identical output is a
 * contract (§6c): fixed field order (Jackson insertion order, no pretty
 * print), base64 float32-LE embeddings (no decimal rendering), \n line
 * endings. Any formatting change breaks determinism by design — the .8 test
 * catches it.
 *
 * <p>Memory discipline (phantom-SKIP lesson): nothing here holds all 160k
 * embeddings at once. Chunk rows embed-then-write one at a time; digests
 * stream. Peak is texts + skeletons (~400MB). The 2g test heap covers it;
 * the 1g subprocess proves the margin.
 */
public final class CorpusIo {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CorpusIo() {}

    public record Corpus(
            List<DocRecord> docs, List<ChunkRecord> chunks, List<GoldenQuery> queries) {}

    /** Skeleton build: docs → chunks → queries (query centroids embed 600 planted chunks only). */
    public static Corpus buildSkeletons() {
        List<DocRecord> docs = DocRecord.generate(new TenantDealing());
        List<ChunkRecord> chunks = ChunkRecord.generate(docs);
        List<GoldenQuery> queries = GoldenQuery.generate(chunks, new ChunkEmbedding());
        return new Corpus(docs, chunks, queries);
    }

    public static String documentLine(DocRecord d) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("doc_id", d.docId().toString());
        o.put("tenant_id", d.tenantId());
        return o.toString();
    }

    public static String chunkLine(ChunkRecord c, String embeddingB64) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("chunk_id", c.chunkId().toString());
        o.put("doc_id", c.docId().toString());
        o.put("tenant_id", c.tenantId());
        o.put("ordinal", c.ordinal());
        o.put("text", c.text());
        o.put("doc_type", c.docType());
        o.put("lang", c.lang());
        o.put("sensitivity", c.sensitivity());
        o.put("source_id", c.sourceId());
        o.put("embedding_b64", embeddingB64);
        return o.toString();
    }

    public static String queryLine(GoldenQuery q) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("query_id", q.queryId());
        o.put("mode", q.mode());
        o.put("text", q.text());
        o.put("query_vector_b64", q.queryVectorB64());
        o.put("filter", q.filterKind());
        o.putPOJO("tenant_scope", q.tenantScope());
        o.putPOJO("metadata_predicate", q.metadataPredicate());
        o.put("selectivity", q.selectivity());
        o.putPOJO("relevant_chunk_ids", uuids(q.relevantChunkIds()));
        o.putPOJO("eligible_chunk_ids", uuids(q.eligibleChunkIds()));
        return o.toString();
    }

    private static List<String> uuids(List<UUID> ids) {
        List<String> out = new ArrayList<>(ids.size());
        ids.forEach(id -> out.add(id.toString()));
        return out;
    }

    /** Streams the full corpus to dir; returns per-file sha256 (documents, chunks, queries). */
    public static List<String> emitAll(Corpus corpus, Path dir) throws Exception {
        Files.createDirectories(dir);
        ChunkEmbedding embedding = new ChunkEmbedding();
        List<String> shas = new ArrayList<>();
        shas.add(writeStreamed(dir.resolve("documents.jsonl"), corpus.docs().stream().map(CorpusIo::documentLine).toList()));
        // Chunks embed-then-write one row at a time: never 160k vectors in memory.
        MessageDigest chunksDigest = MessageDigest.getInstance("SHA-256");
        try (BufferedWriter w = Files.newBufferedWriter(dir.resolve("chunks.jsonl"), StandardCharsets.UTF_8)) {
            for (ChunkRecord c : corpus.chunks()) {
                String line =
                        chunkLine(c, embedding.embed(c.text(), c.docIndex(), c.ordinal())) + "\n";
                w.write(line);
                chunksDigest.update(line.getBytes(StandardCharsets.UTF_8));
            }
        }
        shas.add(HexFormat.of().formatHex(chunksDigest.digest()));
        shas.add(
                writeStreamed(
                        dir.resolve("golden-queries.jsonl"),
                        corpus.queries().stream().map(CorpusIo::queryLine).toList()));
        return List.copyOf(shas);
    }

    private static String writeStreamed(Path file, List<String> lines) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (String line : lines) {
                w.write(line);
                w.write('\n');
                digest.update(line.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String sha256File(Path file) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
