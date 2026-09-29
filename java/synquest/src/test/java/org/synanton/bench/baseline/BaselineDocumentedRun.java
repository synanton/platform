package org.synanton.bench.baseline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.synanton.bench.baseline.BaselineIndexer.ChunkRow;
import org.synanton.bench.emitter.CorpusLoader;
import org.synanton.bench.emitter.Q3Emitter;
import org.synanton.bench.emitter.QueryExecutor;
import org.synanton.bench.emitter.QueryExecutor.GoldenInput;
import org.synanton.bench.emitter.QueryExecutor.QueryOutput;
import org.synanton.synquest.service.HybridSearcher;

/**
 * 028b.8 documented baseline run (test-scope direct, like BaselineBench —
 * RunLeg integration is a later convenience, not a prerequisite).
 * Gated: {@code CORPUS_DIR=/tmp/corpus-v1 ./gradlew :java:synquest:test
 * --tests "*BaselineDocumentedRun" -Dbench.run.baseline=true}.
 * Emits {@code runs/baseline-v1.json} + {@code runs/baseline-v1.build.json}.
 */
@EnabledIfSystemProperty(named = "bench.run.baseline", matches = "true")
class BaselineDocumentedRun {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void documentedBaselineRun() throws Exception {
        Path corpusDir = Paths.get(System.getenv().getOrDefault("CORPUS_DIR", "/tmp/corpus-v1"));
        CorpusLoader.Manifest manifest = CorpusLoader.loadManifest(corpusDir);
        if (!manifest.corpusVersion().equals("ydb-poc-corpus-v1")) {
            throw new IllegalStateException("unexpected corpus: " + manifest.corpusVersion());
        }
        Path root = Files.createTempDirectory("baseline-v1-index");

        // Load: stream corpus rows into per-tenant indexes (shared loader).
        List<ChunkRow> rows = new ArrayList<>();
        CorpusLoader.streamChunks(
                corpusDir,
                r ->
                        rows.add(
                                new ChunkRow(
                                        r.chunkId(), r.tenantId(), r.text(), r.embeddingB64(),
                                        r.metadata())));
        long tBuild0 = System.nanoTime();
        BaselineIndexer.BuildStats stats = BaselineIndexer.build(() -> rows, root);
        long buildMs = (System.nanoTime() - tBuild0) / 1_000_000;
        System.out.println(
                "BASELINE-BUILD chunks=" + stats.chunks() + " tenants=" + stats.tenants()
                        + " bytes=" + stats.bytes() + " ms=" + buildMs);
        if (stats.chunks() != 160_000 || stats.tenants() != 50) {
            throw new IllegalStateException("unexpected build shape: " + stats);
        }
        rows.clear();

        // Tenant universe from built index dirs.
        List<String> universe = new ArrayList<>();
        try (var stream = Files.list(root)) {
            stream.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString().replace("tenant-", ""))
                    .sorted()
                    .forEach(universe::add);
        }

        BaselineSynquestEngine engine;
        BaselineIndex.TenantIndex tenantIndex;
        {
            Map<String, HybridSearcher> searchers = new LinkedHashMap<>();
            for (String tenant : universe) {
                searchers.put(tenant, new HybridSearcher(root.resolve("tenant-" + tenant), 384));
            }
            tenantIndex = new BaselineIndex.TenantIndex(searchers);
            engine = new BaselineSynquestEngine(tenantIndex);
        }
        try {
            // Queries: golden-queries fixture through the shared executor.
            List<QueryOutput> outputs = new ArrayList<>();
            int qcount = 0;
            try (var reader =
                    Files.newBufferedReader(corpusDir.resolve("golden-queries.jsonl"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode q = MAPPER.readTree(line);
                    outputs.add(
                            QueryExecutor.execute(
                                    engine,
                                    new GoldenInput(
                                            q.get("query_id").asText(), q.get("mode").asText(),
                                            q.has("text") ? q.get("text").asText() : "",
                                            q.has("query_vector_b64")
                                                    ? q.get("query_vector_b64").asText() : "",
                                            strings(q.get("tenant_scope")),
                                            stringMap(q.get("metadata_predicate")),
                                            q.has("selectivity") ? q.get("selectivity").asText() : "-",
                                            q.get("filter").asText(), CorpusLoader.eligibleIds(q)),
                                    universe));
                    if (++qcount % 20 == 0) {
                        System.out.println("BASELINE-QUERY done=" + qcount);
                    }
                }
            }
            if (qcount != 120) {
                throw new IllegalStateException("expected 120 queries, ran " + qcount);
            }

            String json = Q3Emitter.emit("baseline-v1", manifest.corpusVersion(), outputs);
            Path runs = Paths.get("runs");
            Files.createDirectories(runs);
            Files.deleteIfExists(runs.resolve("baseline-v1.json"));
            Files.writeString(runs.resolve("baseline-v1.json"), json);
            var build = MAPPER.createObjectNode();
            build.put("leg", "baseline");
            build.put("adapter", "lucene-per-tenant");
            build.put("rows_loaded", stats.chunks());
            build.put("load_ms", buildMs);
            build.put("index_bytes", stats.bytes());
            build.put("queries", outputs.size());
            build.put("emitted_at", Instant.now().toString());
            build.put("corpus_version", manifest.corpusVersion());
            build.put("index_fresh", true);
            Files.writeString(
                    runs.resolve("baseline-v1.build.json"), build.toPrettyString());
            System.out.println(
                    "BASELINE-DONE queries=" + outputs.size() + " build_ms=" + buildMs);
        } finally {
            tenantIndex.close();
        }
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null) {
            node.forEach(e -> out.add(e.asText()));
        }
        return out;
    }

    private static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node != null) {
            node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        }
        return out;
    }
}
