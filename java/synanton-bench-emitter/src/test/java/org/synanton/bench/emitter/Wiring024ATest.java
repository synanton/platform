package org.synanton.bench.emitter;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.synanton.bench.convergence.RunOutput;
import org.synanton.bench.emitter.QueryExecutor.GoldenInput;
import org.synanton.bench.emitter.QueryExecutor.QueryOutput;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.EmbeddingModelRef;
import org.synanton.storage.contract.GenerationId;
import org.synanton.synquest.api.ChunkProjection;
import org.synanton.synquest.cassandra.CassandraSynquestEngine;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B.1 024A wiring: mini corpus → shared loader → adapter upsert → shared
 * executor → Q3 → strict parser. Proves the thin slice end to end; B.2
 * repeats it at v1 scale (160k) once the corpus is generated.
 */
class Wiring024ATest {

    private static final GenerationId GEN = new GenerationId("wire-gen-1");
    private static final EmbeddingModelRef MODEL = new EmbeddingModelRef("wire", "v1", "wire");

    private static Path fixtureDir() throws Exception {
        URI uri = Wiring024ATest.class.getResource("/corpus-mini/manifest.json").toURI();
        return Paths.get(uri).getParent();
    }

    private static float[] decodeVec(String b64, int dims) {
        byte[] bytes = Base64.getDecoder().decode(b64);
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vec = new float[dims];
        for (int i = 0; i < vec.length && buf.hasRemaining(); i++) {
            vec[i] = buf.getFloat();
        }
        return vec;
    }

    @Test
    void wire024AEndToEnd() throws Exception {
        Path dir = fixtureDir();
        Path root = Files.createTempDirectory("wire-024a");
        CassandraSynquestEngine engine = new CassandraSynquestEngine(root);
        List<ChunkProjection> projections = new ArrayList<>();
        long ord = 0;
        var rows = new ArrayList<CorpusLoader.ChunkRow>();
        CorpusLoader.streamChunks(dir, rows::add);
        for (CorpusLoader.ChunkRow row : rows) {
            projections.add(
                    new ChunkProjection(
                            ChunkId.of(row.chunkId()), DocumentId.of(row.docId()), row.tenantId(),
                            row.text(), Map.of(), decodeVec(row.embeddingB64(), 2),
                            MODEL, ord++, GEN));
        }
        engine.upsert(projections).toCompletableFuture().join();

        List<String> universe = List.of("tenant_07", "tenant_11");
        QueryOutput out =
                QueryExecutor.execute(
                        engine,
                        new GoldenInput("q-wire", "lexical", "alpha", "", List.of("tenant_07"),
                                Map.of(), "-", "tenant", List.of("c1", "c2")),
                        universe);
        assertThat(out.topK()).isNotEmpty();
        assertThat(out.topK().stream().map(QueryExecutor.Hit::chunkId))
                .doesNotContain("c3", "c4");

        String json = Q3Emitter.emit("wire-024a", "ydb-poc-corpus-v1-mini", List.of(out));
        RunOutput parsed =
                RunOutput.parse(
                        new java.io.ByteArrayInputStream(
                                json.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        "wire-q3");
        assertThat(parsed.queries()).hasSize(1);
        assertThat(parsed.queries().get(0).timingScope()).isEqualTo("single");
    }
}
