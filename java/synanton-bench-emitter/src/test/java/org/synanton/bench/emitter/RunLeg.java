package org.synanton.bench.emitter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.synanton.bench.emitter.QueryExecutor.GoldenInput;
import org.synanton.bench.emitter.QueryExecutor.QueryOutput;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.EmbeddingModelRef;
import org.synanton.storage.contract.GenerationId;
import org.synanton.synquest.api.ChunkProjection;
import org.synanton.synquest.api.SynquestEngine;
import org.synanton.synquest.api.SynquestIndexWriter;

/**
 * CLI.1 documented-run runner (plain {@code java -cp}, no Gradle wall):
 *
 * <pre>
 * java -Xmx10g -cp &lt;runtime-cp&gt; org.synanton.bench.emitter.RunLeg \
 *   --engine ydb --corpus /tmp/corpus-v1 --out runs/ [--resume]
 * </pre>
 *
 * Rules (pinned): absolute --out; per-query checkpoint flushes
 * ({@code <out>/.checkpoint.jsonl}: a LOAD-DONE marker then one Q3 query
 * object per completed query); --resume skips truncate + load (when
 * LOAD-DONE present) + completed queries; no index cache — every documented
 * run cold-builds; build.json emission beside Q3. Engine construction is the
 * only adapter-specific code here; load/query/validate/emit are port-level.
 *
 * <p>Byte-identity (resume vs fresh) is modulo {@code timing_ms}: run_id is
 * deterministic by construction ({@code <engine>-v1}, no timestamps), but
 * timings are real measurements and legitimately differ. The comparator never
 * gates on timing; thresholds read it with topology context.
 */
public final class RunLeg {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final EmbeddingModelRef MODEL = new EmbeddingModelRef("bench", "v1", "bench");
    private static final GenerationId GEN = new GenerationId("bench-gen-1");
    private static final int BATCH = 500;

    private RunLeg() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> flags = flags(args);
        String engineName = required(flags, "--engine");
        Path corpusDir = Paths.get(required(flags, "--corpus"));
        Path outFile = Paths.get(required(flags, "--out")).toAbsolutePath();
        boolean resume = flags.containsKey("--resume");

        Path checkpoint = outFile.resolveSibling(outFile.getFileName() + ".checkpoint.jsonl");
        Set<String> done = new java.util.HashSet<>();
        boolean loadDone = false;
        if (resume && Files.isRegularFile(checkpoint)) {
            for (String line : Files.readAllLines(checkpoint)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode o = MAPPER.readTree(line);
                if (o.has("phase") && o.get("phase").asText().equals("load-done")) {
                    loadDone = true;
                } else if (o.has("query_id")) {
                    done.add(o.get("query_id").asText());
                }
            }
            System.out.println("RESUME load-done=" + loadDone + " queries-done=" + done.size());
        } else {
            Files.deleteIfExists(checkpoint);
            Files.deleteIfExists(outFile);
        }

        CorpusLoader.Manifest manifest = CorpusLoader.loadManifest(corpusDir);
        Engine engine = open(engineName, corpusDir);
        long tLoad0 = System.nanoTime();
        if (!loadDone) {
            engine.truncate();
            streamLoad(engine, corpusDir);
            appendLine(checkpoint, "{\"phase\":\"load-done\"}");
        } else {
            System.out.println("RESUME skipping truncate+load");
        }
        long loadMs = (System.nanoTime() - tLoad0) / 1_000_000;

        List<String> universe = engine.tenantUniverse();
        List<QueryOutput> outputs = new ArrayList<>();
        Map<String, QueryOutput> prior = readCheckpointQueries(checkpoint);
        int qtotal = 0;
        for (String line : Files.readAllLines(corpusDir.resolve("golden-queries.jsonl"))) {
            if (line.isBlank()) {
                continue;
            }
            qtotal++;
        }
        int qdone = 0;
        try (var reader = Files.newBufferedReader(corpusDir.resolve("golden-queries.jsonl"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode q = MAPPER.readTree(line);
                String qid = q.get("query_id").asText();
                if (done.contains(qid) && prior.containsKey(qid)) {
                    outputs.add(prior.get(qid));
                    continue;
                }
                QueryOutput out =
                        QueryExecutor.execute(
                                engine.engine(),
                                new GoldenInput(
                                        qid, q.get("mode").asText(),
                                        q.has("text") ? q.get("text").asText() : "",
                                        q.has("query_vector_b64") ? q.get("query_vector_b64").asText() : "",
                                        strings(q.get("tenant_scope")), stringMap(q.get("metadata_predicate")),
                                        q.has("selectivity") ? q.get("selectivity").asText() : "-",
                                        q.get("filter").asText(), strings(q.get("eligible_set"))),
                                universe);
                outputs.add(out);
                appendLine(checkpoint, Q3Emitter.emit("x", "x", List.of(out)));
                if (++qdone % 20 == 0) {
                    System.out.println("QUERY done=" + qdone + "/" + qtotal);
                }
            }
        }
        // NOTE: checkpoint lines are full Q3 docs; re-parse for merge.
        // (outputs already holds prior + new in order; the file is truth on resume.)
        String json = Q3Emitter.emit(engineName + "-v1", manifest.corpusVersion(), outputs);
        Files.createDirectories(outFile.getParent());
        Files.writeString(outFile, json);
        ObjectMapper m = MAPPER;
        var build = m.createObjectNode();
        build.put("leg", engineName);
        build.put("adapter", engine.describe());
        build.put("rows_loaded", countRows(engine, corpusDir));
        build.put("load_ms", loadMs);
        build.put("queries", outputs.size());
        build.put("emitted_at", Instant.now().toString());
        build.put("corpus_version", manifest.corpusVersion());
        build.put("index_fresh", true);
        Files.writeString(
                outFile.resolveSibling(outFile.getFileName() + ".build.json"), build.toPrettyString());
        System.out.println("DONE wrote=" + outFile + " queries=" + outputs.size());
        engine.close();
    }

    private record Engine(
            SynquestEngine engine, Runnable truncate, java.util.function.Supplier<List<String>> universeSup,
            String describe, Runnable close,
            java.util.function.Supplier<tech.ydb.table.Session> sessions) {
        List<String> tenantUniverse() {
            return universeSup.get();
        }
    }

    private static Engine open(String name, Path corpusDir) throws Exception {
        return switch (name) {
            case "ydb" -> YdbLeg.open(corpusDir);
            case "cassandra" -> CassandraLeg.open(corpusDir);
            default -> throw new IllegalArgumentException("unknown --engine (ydb|cassandra): " + name);
        };
    }

    private static void streamLoad(Engine engine, Path corpusDir) throws Exception {
        // YDB leg uses the batch path (read → keepFresh → single-tx commits);
        // per-row upsert at 160k scale is ~10hr (041). Others use port upsert.
        if (engine.engine() instanceof org.synanton.synquest.ydb.YdbSynquestEngine ydb) {
            bulkLoadYdb(engine, ydb, corpusDir);
            return;
        }
        SynquestIndexWriter writer = (SynquestIndexWriter) engine.engine();
        List<ChunkProjection> batch = new ArrayList<>(BATCH);
        long loaded = 0;
        List<CorpusLoader.ChunkRow> rows = new ArrayList<>();
        CorpusLoader.streamChunks(corpusDir, rows::add);
        for (int i = 0; i < rows.size(); i += BATCH) {
            batch.clear();
            for (int j = i; j < Math.min(i + BATCH, rows.size()); j++) {
                CorpusLoader.ChunkRow r = rows.get(j);
                batch.add(
                        new ChunkProjection(
                                ChunkId.of(r.chunkId()), DocumentId.of(r.docId()), r.tenantId(),
                                r.text(), Map.copyOf(r.metadata()), decodeVec(r.embeddingB64()),
                                MODEL, j, GEN));
            }
            writer.upsert(List.copyOf(batch)).toCompletableFuture().join();
            loaded += batch.size();
            if (loaded % 20000 == 0) {
                System.out.println("LOAD loaded=" + loaded);
            }
        }
        System.out.println("LOAD done loaded=" + loaded);
        rows.clear();
    }

    private static final int BULK_WINDOW = 10_000;

    private static void bulkLoadYdb(
            Engine engine, org.synanton.synquest.ydb.YdbSynquestEngine ydb, Path corpusDir)
            throws Exception {
        // True streaming: projections accumulate to one window, flush, clear.
        // Nothing here ever holds 160k rows (texts included).
        List<ChunkProjection> window = new ArrayList<>(BULK_WINDOW);
        long[] loaded = {0};
        int[] commits = {0};
        long[] ordinal = {0};
        CorpusLoader.streamChunks(
                corpusDir,
                r -> {
                    window.add(
                            new ChunkProjection(
                                    ChunkId.of(r.chunkId()), DocumentId.of(r.docId()),
                                    r.tenantId(), r.text(), Map.copyOf(r.metadata()),
                                    decodeVec(r.embeddingB64()), MODEL, ordinal[0]++, GEN));
                    if (window.size() >= BULK_WINDOW) {
                        flushWindow(engine, ydb, window, loaded, commits);
                    }
                });
        if (!window.isEmpty()) {
            flushWindow(engine, ydb, window, loaded, commits);
        }
        System.out.println(
                "LOAD bulk done rows=" + loaded[0] + " commits=" + commits[0]);
    }

    private static void flushWindow(
            Engine engine,
            org.synanton.synquest.ydb.YdbSynquestEngine ydb,
            List<ChunkProjection> window,
            long[] loaded,
            int[] commits) {
        try (tech.ydb.table.Session session = engine.sessions().get()) {
            java.util.Map<String, Long> stored = ydb.readOrderingBatch(session, window);
            List<ChunkProjection> fresh =
                    org.synanton.synquest.ydb.YdbSynquestEngine.keepFresh(window, stored);
            commits[0] += ydb.upsertBatch(session, fresh);
            loaded[0] += fresh.size();
            System.out.println(
                    "LOAD bulk window done loaded=" + loaded[0] + " commits=" + commits[0]);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            window.clear();
        }
    }

    private static long countRows(Engine engine, Path corpusDir) throws Exception {
        long[] n = {0};
        CorpusLoader.streamChunks(corpusDir, row -> n[0]++);
        return n[0];
    }

    private static Map<String, QueryOutput> readCheckpointQueries(Path checkpoint) throws Exception {
        Map<String, QueryOutput> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(checkpoint)) {
            return out;
        }
        for (String line : Files.readAllLines(checkpoint)) {
            if (line.isBlank() || line.contains("\"phase\"")) {
                continue;
            }
            JsonNode root = MAPPER.readTree(line);
            for (JsonNode q : root.get("queries")) {
                List<QueryExecutor.Hit> hits = new ArrayList<>();
                q.get("top_k")
                        .forEach(
                                e ->
                                        hits.add(
                                                new QueryExecutor.Hit(
                                                        e.get("chunk_id").asText(),
                                                        e.get("score").asDouble(),
                                                        e.get("rank").asInt())));
                List<String> elig = new ArrayList<>();
                q.get("eligible_set").forEach(e -> elig.add(e.asText()));
                out.put(
                        q.get("query_id").asText(),
                        new QueryOutput(
                                q.get("query_id").asText(), q.get("mode").asText(),
                                q.get("filter").asText(), q.get("selectivity").asText(),
                                hits, elig, q.get("timing_ms").asDouble(),
                                q.has("timing_scope") ? q.get("timing_scope").asText() : "unspecified"));
            }
        }
        return out;
    }

    private static void appendLine(Path file, String line) throws Exception {
        Files.createDirectories(file.getParent() == null ? Paths.get(".") : file.getParent());
        Files.writeString(
                file, line + "\n", java.nio.charset.StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static float[] decodeVec(String b64) {
        byte[] bytes = Base64.getDecoder().decode(b64);
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        float[] vec = new float[bytes.length / 4];
        for (int i = 0; i < vec.length; i++) {
            vec[i] = buf.getFloat();
        }
        return vec;
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

    private static Map<String, String> flags(String[] args) {
        Map<String, String> flags = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                flags.put(args[i], (i + 1 < args.length && !args[i + 1].startsWith("--")) ? args[++i] : "");
            }
        }
        return flags;
    }

    private static String required(Map<String, String> flags, String name) {
        String v = flags.get(name);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("missing required " + name);
        }
        return v;
    }

    /** Per-leg engine openers (the only adapter-specific code in the CLI). */
    static final class YdbLeg {
        static Engine open(Path corpusDir) throws Exception {
            String caPath = firstExisting(
                    System.getenv().getOrDefault("YDB_CA_PATH", ""),
                    System.getProperty("ydb.ca.path", ""),
                    "/tmp/ydb-ca.pem",
                    System.getProperty("user.home") + "/.config/ydb-ca.pem");
            byte[] ca = Files.readAllBytes(Paths.get(caPath));
            tech.ydb.core.grpc.GrpcTransport transport =
                    tech.ydb.core.grpc.GrpcTransport.forConnectionString(
                            System.getenv()
                                    .getOrDefault("YDB_ENDPOINT", "grpcs://localhost:2135/local"))
                            .withSecureConnection(ca)
                            .build();
            tech.ydb.table.TableClient client = tech.ydb.table.TableClient.newClient(transport).build();
            String prefix = "t_emit_leg";
            org.synanton.synquest.ydb.YdbSearchSchema.ensureSchema(client, prefix, 384);
            org.synanton.synquest.ydb.YdbSynquestEngine engine =
                    new org.synanton.synquest.ydb.YdbSynquestEngine(client, prefix);
            Set<String> tenants = new LinkedHashSet<>();
            CorpusLoader.streamChunks(corpusDir, row -> tenants.add(row.tenantId()));
            List<String> universe = new ArrayList<>(tenants);
            universe.sort(null);
            return new Engine(
                    engine,
                    () -> {
                        try {
                            org.synanton.synquest.ydb.YdbSearchSchema.truncateAll(client, prefix);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    },
                    () -> universe,
                    "ydb@26.3", () -> client.close(),
                    () -> {
                        try {
                            return client.createSession(java.time.Duration.ofSeconds(30))
                                    .join().getValue();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
        }

        private static String firstExisting(String... candidates) {
            for (String c : candidates) {
                if (c != null && !c.isBlank() && Files.isReadable(Paths.get(c))) {
                    return c;
                }
            }
            throw new IllegalStateException("YDB CA not found");
        }
    }

    /** Per-leg engine openers (the only adapter-specific code in the CLI). */
    static final class CassandraLeg {
        static Engine open(Path corpusDir) throws Exception {
            Path root = Files.createTempDirectory("bench-leg-cassandra");
            org.synanton.synquest.cassandra.CassandraSynquestEngine engine =
                    new org.synanton.synquest.cassandra.CassandraSynquestEngine(root);
            Set<String> tenants = new LinkedHashSet<>();
            CorpusLoader.streamChunks(corpusDir, row -> tenants.add(row.tenantId()));
            List<String> universe = new ArrayList<>(tenants);
            universe.sort(null);
            return new Engine(
                    engine,
                    () -> {},
                    () -> universe,
                    "cassandra-lucene",
                    () -> {},
                    () -> {
                        throw new UnsupportedOperationException("no sessions on cassandra leg");
                    });
        }
    }
}
