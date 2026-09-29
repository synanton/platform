package org.synanton.bench.emitter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.EmbeddingModelRef;
import org.synanton.storage.contract.GenerationId;
import org.synanton.synquest.api.ChunkProjection;
import org.synanton.synquest.ydb.YdbSearchSchema;
import org.synanton.synquest.ydb.YdbSynquestEngine;
import tech.ydb.core.grpc.GrpcTransport;
import tech.ydb.table.Session;
import tech.ydb.table.TableClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 index-isolation matrix (gated -Dbench.run.h2matrix=true): same 5k rows
 * into four schema variants (indexes on/off × {vector, fulltext}), per-chunk
 * commit times recorded. If a variant collapses the ~32s cost, index
 * maintenance is confirmed and the fix is post-load index DDL.
 * Run: {@code CORPUS_DIR=/tmp/corpus-v1 ./gradlew
 * :java:synanton-bench-emitter:test --tests "*H2IndexMatrix"
 * -Dbench.run.h2matrix=true}.
 */
@EnabledIfSystemProperty(named = "bench.run.h2matrix", matches = "true")
class H2IndexMatrix {

    private static final int ROWS = 10_000;
    private static final GenerationId GEN = new GenerationId("h2-gen-1");
    private static final EmbeddingModelRef MODEL = new EmbeddingModelRef("h2", "v1", "h2");

    private record Variant(String name, boolean vec, boolean ft) {}

    private static TableClient client() throws Exception {
        String caPath = firstExisting(
                System.getenv().getOrDefault("YDB_CA_PATH", ""),
                System.getProperty("ydb.ca.path", ""),
                "/tmp/ydb-ca.pem",
                System.getProperty("user.home") + "/.config/ydb-ca.pem");
        byte[] ca = java.nio.file.Files.readAllBytes(Paths.get(caPath));
        GrpcTransport transport =
                GrpcTransport.forConnectionString("grpcs://localhost:2135/local")
                        .withSecureConnection(ca)
                        .build();
        return TableClient.newClient(transport).build();
    }

    private static String firstExisting(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank() && java.nio.file.Files.isReadable(Paths.get(c))) {
                return c;
            }
        }
        throw new IllegalStateException("YDB CA not found");
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

    private static void dropIndex(TableClient client, String prefix, String table, String index)
            throws Exception {
        try (Session session = client.createSession(Duration.ofSeconds(30)).join().getValue()) {
            var status =
                    session
                            .executeSchemeQuery(
                                    "ALTER TABLE `" + prefix + "_" + table + "` DROP INDEX `" + index + "`;")
                            .join();
            if (!status.isSuccess()) {
                throw new IllegalStateException("DROP INDEX failed: " + status);
            }
        }
    }

    @Test
    void indexIsolationMatrix() throws Exception {
        Path corpusDir = Paths.get(System.getenv().getOrDefault("CORPUS_DIR", "/tmp/corpus-v1"));
        TableClient client = client();
        try {
            List<Variant> variants =
                    List.of(
                            new Variant("full", true, true),
                            new Variant("no-vec", false, true),
                            new Variant("no-ft", true, false),
                            new Variant("neither", false, false));
            for (Variant v : variants) {
                String prefix = "t_emit_h2_" + v.name().replace("-", "_");
                YdbSearchSchema.ensureSchema(client, prefix, 384);
                if (!v.vec()) {
                    dropIndex(client, prefix, "vectors", "v_vec");
                    dropIndex(client, prefix, "vectors", "v_hyb");
                }
                if (!v.ft()) {
                    dropIndex(client, prefix, "projections", "ft");
                    dropIndex(client, prefix, "vectors", "v_ft");
                }
                YdbSearchSchema.truncateAll(client, prefix);
                YdbSynquestEngine engine = new YdbSynquestEngine(client, prefix);

                List<ChunkProjection> rows = new ArrayList<>(ROWS);
                final int[] seen = {0};
                CorpusLoader.streamChunks(
                        corpusDir,
                        row -> {
                            if (seen[0]++ < ROWS) {
                                rows.add(
                                        new ChunkProjection(
                                                ChunkId.of(row.chunkId()), DocumentId.of(row.docId()),
                                                row.tenantId(), row.text(), Map.copyOf(row.metadata()),
                                                decodeVec(row.embeddingB64()), MODEL, seen[0], GEN));
                            }
                        });
                assertThat(rows).hasSize(ROWS);
                long t0 = System.nanoTime();
                int commits;
                try (Session session = client.createSession(Duration.ofSeconds(30)).join().getValue()) {
                    var stored = engine.readOrderingBatch(session, rows);
                    var fresh = YdbSynquestEngine.keepFresh(rows, stored);
                    assertThat(fresh).hasSize(ROWS);
                    commits = engine.upsertBatch(session, fresh);
                }
                double secs = (System.nanoTime() - t0) / 1_000_000_000.0;
                String line =
                        String.format(
                                "H2-MATRIX variant=%s rows=%d secs=%.1f rows_per_s=%.1f commits=%d",
                                v.name(), ROWS, secs, ROWS / secs, commits);
                System.out.println(line);
                // Per-variant log file (Gradle captures stdout until task end;
                // the file is the live signal — same discipline as BULK_PROGRESS_FILE).
                try {
                    Path logDir =
                            Paths.get(
                                    System.getenv().getOrDefault("H2_LOG_DIR", "/tmp/h2-logs"));
                    Files.createDirectories(logDir);
                    Path log = logDir.resolve("h2-" + v.name() + ".log");
                    if (!Files.exists(log)) {
                        Files.writeString(log, line + "\n");
                    } else {
                        Files.writeString(
                                log, line + "\n", java.nio.file.StandardOpenOption.APPEND);
                    }
                } catch (Exception ignored) {
                    // Logging must never break measurement.
                }
            }
        } finally {
            client.close();
        }
    }
}
