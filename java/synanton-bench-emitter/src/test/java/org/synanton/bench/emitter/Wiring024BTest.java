package org.synanton.bench.emitter;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import org.synanton.bench.convergence.RunOutput;
import org.synanton.bench.emitter.QueryExecutor.GoldenInput;
import org.synanton.bench.emitter.QueryExecutor.QueryOutput;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.EmbeddingModelRef;
import org.synanton.storage.contract.GenerationId;
import org.synanton.synquest.api.ChunkProjection;
import org.synanton.synquest.ydb.YdbSearchSchema;
import org.synanton.synquest.ydb.YdbSynquestEngine;
import tech.ydb.core.grpc.GrpcTransport;
import tech.ydb.table.TableClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C.1 024B wiring: mini corpus → shared loader → YDB upsert → shared executor
 * → Q3 → strict parser. Live YDB required (ydb-poc container + CA chain per
 * dev-guide-tests.md); proves the thin slice end to end before the full C.2.
 */
class Wiring024BTest {

    private static final GenerationId GEN = new GenerationId("wire24b-gen-1");
    private static final EmbeddingModelRef MODEL = new EmbeddingModelRef("wire", "v1", "wire");

    private static TableClient client() throws Exception {
        String caPath = firstExisting(
                System.getenv().getOrDefault("YDB_CA_PATH", ""),
                System.getProperty("ydb.ca.path", ""),
                "/tmp/ydb-ca.pem",
                System.getProperty("user.home") + "/.config/ydb-ca.pem");
        byte[] ca = Files.readAllBytes(Paths.get(caPath));
        GrpcTransport transport =
                GrpcTransport.forConnectionString("grpcs://localhost:2135/local")
                        .withSecureConnection(ca)
                        .build();
        return TableClient.newClient(transport).build();
    }

    private static String firstExisting(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank() && Files.isReadable(Paths.get(c))) {
                return c;
            }
        }
        throw new IllegalStateException("YDB CA not found; start ydb-poc and copy the CA");
    }

    private static float[] decodeVec(String b64) {
        byte[] bytes = Base64.getDecoder().decode(b64);
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vec = new float[2];
        for (int i = 0; i < vec.length && buf.hasRemaining(); i++) {
            vec[i] = buf.getFloat();
        }
        return vec;
    }

    @Test
    void wire024BEndToEnd() throws Exception {
        Path dir;
        try {
            URI uri = Wiring024BTest.class.getResource("/corpus-mini/manifest.json").toURI();
            dir = Paths.get(uri).getParent();
        } catch (Exception e) {
            throw new IllegalStateException("mini fixture missing", e);
        }
        TableClient client = client();
        String prefix = "t_emit_wire24b";
        YdbSearchSchema.ensureSchema(client, prefix, 2);
        YdbSearchSchema.truncateAll(client, prefix);
        YdbSynquestEngine engine = new YdbSynquestEngine(client, prefix);

        List<ChunkProjection> projections = new ArrayList<>();
        long[] ord = {0};
        CorpusLoader.streamChunks(
                dir,
                row -> {
                    projections.add(
                            new ChunkProjection(
                                    ChunkId.of(row.chunkId()), DocumentId.of(row.docId()),
                                    row.tenantId(), row.text(), Map.copyOf(row.metadata()),
                                    decodeVec(row.embeddingB64()), MODEL, ord[0]++, GEN));
                });
        assertThat(projections).hasSize(4);
        engine.upsert(projections).toCompletableFuture().join();

        List<String> universe = List.of("tenant_07", "tenant_11");
        QueryOutput lex =
                QueryExecutor.execute(
                        engine,
                        new GoldenInput("q-lex", "lexical", "alpha", "",
                                List.of("tenant_07"), Map.of(), "-", "tenant",
                                List.of("c1", "c2")),
                        universe);
        assertThat(lex.topK()).isNotEmpty();
        assertThat(lex.topK().stream().map(QueryExecutor.Hit::chunkId))
                .doesNotContain("c3", "c4");

        QueryOutput vec =
                QueryExecutor.execute(
                        engine,
                        new GoldenInput("q-vec", "vector", "",
                                MiniVectors.b64(new float[] {1, 0}), List.of("tenant_07"),
                                Map.of(), "-", "tenant", List.of("c1", "c2")),
                        universe);
        assertThat(vec.topK()).isNotEmpty();

        String json = Q3Emitter.emit("wire24b", "mini", List.of(lex, vec));
        RunOutput parsed =
                RunOutput.parse(
                        new java.io.ByteArrayInputStream(
                                json.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        "wire24b");
        assertThat(parsed.queries()).hasSize(2);
        client.close();
    }

    /** Manifest-vector encoder (test-local; production vectors come from corpus). */
    static final class MiniVectors {
        static String b64(float[] vec) {
            ByteBuffer buf =
                    ByteBuffer.allocate(vec.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (float v : vec) {
                buf.putFloat(v);
            }
            return Base64.getEncoder().encodeToString(buf.array());
        }
    }
}
