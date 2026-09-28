package org.synanton.bench.baseline;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.FSDirectory;

/**
 * 028b.3 tenant-scoped index construction. One Lucene directory per tenant
 * (structural isolation — never a shared index with a tenant filter).
 *
 * <p>Doc schema extends BaselineBench's ({@code id}, {@code text},
 * {@code embedding} COSINE) with stored-only tenant/metadata fields.
 * Stored-only additions never enter term vectors, so BM25/vector scoring on
 * existing fields is bit-identical to the original harness (equivalence
 * gate in test).
 */
public final class BaselineIndexer {

    private BaselineIndexer() {}

    public record BuildStats(long chunks, long tenants, long bytes, long millis) {}

    /**
     * Streams corpus rows into per-tenant indexes. Caller supplies rows
     * (shared loader); embeddings decoded from base64 float32-LE here.
     */
    public static BuildStats build(
            java.util.function.Supplier<Iterable<ChunkRow>> rows, Path root) throws IOException {
        long t0 = System.nanoTime();
        Files.createDirectories(root);
        Map<String, IndexWriter> writers = new HashMap<>();
        Map<String, Long> counts = new HashMap<>();
        long chunks = 0;
        try {
            for (ChunkRow row : rows.get()) {
                IndexWriter writer =
                        writers.computeIfAbsent(
                                row.tenantId(),
                                tenant -> {
                                    try {
                                        Path dir = root.resolve("tenant-" + tenant);
                                        Files.createDirectories(dir);
                                        return new IndexWriter(
                                                FSDirectory.open(dir),
                                                new IndexWriterConfig(new StandardAnalyzer()));
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                });
                Document doc = new Document();
                doc.add(new StringField("id", row.chunkId(), Field.Store.YES));
                doc.add(new StringField("tenant_id", row.tenantId(), Field.Store.YES));
                doc.add(new TextField("text", row.text(), Field.Store.YES));
                for (Map.Entry<String, String> meta : row.metadata().entrySet()) {
                    doc.add(new StringField("meta_" + meta.getKey(), meta.getValue(), Field.Store.YES));
                }
                doc.add(
                        new KnnFloatVectorField(
                                "embedding", decodeVec(row.embeddingB64()),
                                VectorSimilarityFunction.COSINE));
                writer.addDocument(doc);
                counts.merge(row.tenantId(), 1L, Long::sum);
                chunks++;
            }
            for (IndexWriter writer : writers.values()) {
                writer.commit();
                writer.close();
            }
        } catch (RuntimeException e) {
            for (IndexWriter writer : writers.values()) {
                try {
                    writer.rollback();
                    writer.close();
                } catch (Exception suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
        long bytes = 0;
        try (var walk = Files.walk(root)) {
            bytes =
                    walk.filter(Files::isRegularFile)
                            .mapToLong(
                                    p -> {
                                        try {
                                            return Files.size(p);
                                        } catch (IOException e) {
                                            return 0L;
                                        }
                                    })
                            .sum();
        }
        return new BuildStats(
                chunks, writers.size(), bytes, (System.nanoTime() - t0) / 1_000_000);
    }

    private static float[] decodeVec(String b64) {
        byte[] bytes = Base64.getDecoder().decode(b64);
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vec = new float[bytes.length / 4];
        for (int i = 0; i < vec.length; i++) {
            vec[i] = buf.getFloat();
        }
        return vec;
    }

    /** Minimal row view (shared loader rows adapt to this; see test). */
    public record ChunkRow(
            String chunkId, String tenantId, String text, String embeddingB64,
            Map<String, String> metadata) {}
}
