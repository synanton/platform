package org.synanton.synquest.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.synanton.storage.contract.PolicyContext;
import org.synanton.storage.contract.PrincipalRef;
import org.synanton.storage.contract.SecurityContext;
import org.synanton.storage.contract.TenantScope;
import org.synanton.synquest.api.EligibilityConstraints;
import org.synanton.synquest.api.RelevanceFilters;
import org.synanton.synquest.api.SearchMode;
import org.synanton.synquest.api.SearchRequest;
import org.synanton.synquest.api.SearchResult;
import org.synanton.synquest.api.TemporalExtension;

/**
 * PG-POC-007-8 emitter wiring proof (test scope): runs a fixed query set
 * through {@link PostgresSynquestEngine} and emits Q3 JSON satisfying the
 * shared emitter contract (parsed by {@code RunOutput} strict parser).
 *
 * <p>Three pre-checks, enforced here not assumed:
 * <ol>
 *   <li>{@code run_id} is caller-supplied and deterministic ({@code pg-v1}
 *       in the test) — the emitter never mints timestamps. Resume-vs-fresh
 *       comparison holds by construction.</li>
 *   <li>{@code corpus} is read from the manifest file (§7 rule) — a missing
 *       manifest fails loudly, never a hardcoded or guessed version.</li>
 *   <li>{@code eligible_set} comes from ground-truth SQL (tenant +
 *       predicate set query), never recomputed from engine output (028a.5
 *       rule). The engine ranks; the manifest-derived truth judges.</li>
 * </ol>
 *
 * <p>Carries {@code min_score} per query (007-4 follow-up obligation for
 * 028e); the current parser ignores it, Phase-4 internal parity reads it.
 * This is the wiring proof, not the frozen run — the real
 * {@code runs/pg-v1.json} belongs to 028e on the frozen corpus.
 */
final class PgQ3Emitter {

    /** One emitted query leg. */
    record Leg(String queryId, SearchMode mode, String filterKind, Map<String, String> filter,
            String queryText, float[] embedding, double minScore) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PostgresSynquestEngine engine;
    private final String corpusVersion;

    PgQ3Emitter(PostgresSynquestEngine engine, Path manifestPath) throws Exception {
        this.engine = engine;
        this.corpusVersion = readCorpusVersion(manifestPath);
    }

    static String readCorpusVersion(Path manifestPath) throws Exception {
        if (!Files.isRegularFile(manifestPath)) {
            throw new IllegalArgumentException(
                    "emitter manifest missing: " + manifestPath + " — corpus version is never guessed");
        }
        JsonNode manifest = MAPPER.readTree(Files.readAllBytes(manifestPath));
        JsonNode version = manifest.get("corpus_version");
        if (version == null || !version.isTextual() || version.asText().isBlank()) {
            throw new IllegalArgumentException(
                    "emitter manifest has no corpus_version: " + manifestPath);
        }
        return version.asText();
    }

    String emit(String runId, String tenant, List<Leg> legs) throws Exception {
        TenantScope scope = TenantScope.of(tenant);
        PolicyContext policy = PolicyContext.of("p", "r1");
        SecurityContext ctx = SecurityContext.user(scope, PrincipalRef.user("u-1"), policy);
        EligibilityConstraints eligibility =
                EligibilityConstraints.from(scope, List.of(PrincipalRef.user("u-1")), policy);

        ObjectNode root = MAPPER.createObjectNode();
        root.put("run_id", runId);
        root.put("corpus", corpusVersion);
        ArrayNode queries = root.putArray("queries");
        for (Leg leg : legs) {
            long start = System.nanoTime();
            SearchResult result =
                    engine.search(
                                    ctx,
                                    new SearchRequest(
                                            leg.queryText(),
                                            Optional.ofNullable(leg.embedding()),
                                            Optional.empty(),
                                            leg.mode(),
                                            eligibility,
                                            leg.filter().isEmpty()
                                                    ? RelevanceFilters.none()
                                                    : new RelevanceFilters(leg.filter()),
                                            TemporalExtension.empty(),
                                            10,
                                            leg.minScore()))
                            .toCompletableFuture()
                            .join();
            double timingMs = (System.nanoTime() - start) / 1_000_000.0;
            ObjectNode q = queries.addObject();
            q.put("query_id", leg.queryId());
            q.put("mode", leg.mode().name().toLowerCase());
            q.put("filter", leg.filterKind());
            q.put("selectivity", "fixture");
            q.put("min_score", leg.minScore());
            ArrayNode topK = q.putArray("top_k");
            int rank = 1;
            for (var hit : result.hits()) {
                ObjectNode e = topK.addObject();
                e.put("chunk_id", hit.chunkId().value());
                e.put("score", hit.score());
                e.put("rank", rank++);
            }
            if (!leg.filterKind().equals("none")) {
                ArrayNode eligible = q.putArray("eligible_set");
                for (String id : groundTruthEligible(tenant, leg.filter())) {
                    eligible.add(id);
                }
            }
            q.put("timing_ms", timingMs);
            q.put("timing_scope", "engine-only");
        }
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    /**
     * Ground truth (028a.5): eligible set from a set query over the tables,
     * independent of engine ranking. Tenant + metadata containment — the
     * same predicate family the corpus manifests.
     */
    private List<String> groundTruthEligible(String tenant, Map<String, String> filter)
            throws Exception {
        // Direct connection, not the engine: truth must not route through
        // the system under test. Admin handle: ground truth is a test oracle.
        List<String> ids = new ArrayList<>();
        try (Connection admin = QuestPostgresFixture.adminConnection();
                var ps =
                        admin.prepareStatement(
                                "SELECT chunk_id FROM chunks WHERE tenant_id = ?"
                                        + (filter.isEmpty() ? "" : " AND metadata @> ?::jsonb")
                                        + " ORDER BY chunk_id")) {
            ps.setString(1, tenant);
            if (!filter.isEmpty()) {
                ps.setString(2, toJson(filter));
            }
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            }
        }
        return ids;
    }

    private static String toJson(Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : map.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":\"").append(e.getValue()).append('"');
        }
        return sb.append('}').toString();
    }
}
