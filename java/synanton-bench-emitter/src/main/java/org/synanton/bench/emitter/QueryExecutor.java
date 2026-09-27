package org.synanton.bench.emitter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.synanton.storage.contract.EmbeddingModelRef;
import org.synanton.storage.contract.PolicyContext;
import org.synanton.storage.contract.PrincipalRef;
import org.synanton.storage.contract.SecurityContext;
import org.synanton.storage.contract.TenantScope;
import org.synanton.synquest.api.EligibilityConstraints;
import org.synanton.synquest.api.RelevanceFilters;
import org.synanton.synquest.api.SearchHit;
import org.synanton.synquest.api.SearchMode;
import org.synanton.synquest.api.SearchRequest;
import org.synanton.synquest.api.SynquestEngine;
import org.synanton.synquest.api.TemporalExtension;

/**
 * A.3 shared port-based query executor. Takes the {@code SynquestEngine} port,
 * never a concrete adapter; constructs the port's native {@code SearchRequest}.
 * No new request types, no per-mode wrappers.
 *
 * <p><b>Finding (surfaced, not worked around):</b> the port is single-tenant
 * per call ({@code EligibilityScope} rejects cross-tenant eligibility with
 * FORBIDDEN), so multi-tenant corpus scopes fan out to one request per tenant
 * here. Each tenant call is independently pre-ranking-correct; the merge below
 * is benchmark-harness measurement behavior, not adapter semantics.
 *
 * <p>Eligibility is tenant-scoped only (PARTIAL(scope=tenant) per the YDB PoC
 * conformance resolution; principals/policy deferred per 025b). The
 * principals/policy below satisfy the type without asserting enforcement.
 *
 * <p>Normalization lives here, not in adapters: post-retrieval
 * (score desc, chunkId asc) sort (013 obligation; idempotent over adapter
 * sorting) and minScore=0 neutral per the frozen config.
 *
 * <p>Timing scope is the adapter call only: timer starts before the port call
 * and stops immediately after (executor overhead, tie-break, and
 * serialization excluded — else frozen thresholds absorb harness cost).
 * Multi-tenant fan-out runs sequentially; reported timing is the sum.
 */
public final class QueryExecutor {

    /** Bench placeholder identity (type requires non-empty principals/policy). */
    static final PrincipalRef BENCH_PRINCIPAL = new PrincipalRef("benchmark", "q3-emitter");

    static final PolicyContext BENCH_POLICY = new PolicyContext("benchmark", "v1");

    static final EmbeddingModelRef BENCH_MODEL =
            new EmbeddingModelRef("q3-bench", "v1", "synthetic");

    /** Q3 top-K width (matches the Recall@10 scale; overlap is K-robust). */
    static final int TOP_K = 10;

    private QueryExecutor() {}

    /** Golden-query input (mirrors the corpus fixture + Q3 fields). */
    public record GoldenInput(
            String queryId,
            String mode,
            String text,
            String queryVectorB64,
            List<String> tenantScope,
            Map<String, String> metadataPredicate,
            String selectivity,
            String filter,
            List<String> eligibleIds) {}

    public record Hit(String chunkId, double score, int rank) {}

    /** Per-query result mirroring Q3 structure (A.4 serializes this directly). */
    public record QueryOutput(
            String queryId,
            String mode,
            String filter,
            String selectivity,
            List<Hit> topK,
            List<String> eligibleIds,
            double timingMs,
            String timingScope) {}

    /**
     * Executes one golden query. Empty scope fans out over the full tenant
     * universe (filter=none legs); otherwise over the listed scope.
     */
    public static QueryOutput execute(
            SynquestEngine engine, GoldenInput input, List<String> tenantUniverse)
            throws Exception {
        List<String> tenants =
                input.tenantScope().isEmpty() ? tenantUniverse : input.tenantScope();
        SearchMode mode = SearchMode.valueOf(input.mode().toUpperCase());
        List<Scored> merged = new ArrayList<>();
        double timingMs = 0.0;
        for (String tenant : tenants) {
            TenantScope scope = TenantScope.of(tenant);
            SecurityContext context = SecurityContext.user(scope, BENCH_PRINCIPAL, BENCH_POLICY);
            SearchRequest request =
                    new SearchRequest(
                            input.text(),
                            queryVector(input),
                            Optional.of(BENCH_MODEL),
                            mode,
                            EligibilityConstraints.from(scope, List.of(BENCH_PRINCIPAL), BENCH_POLICY),
                            new RelevanceFilters(input.metadataPredicate()),
                            TemporalExtension.empty(),
                            TOP_K,
                            0.0);
            long start = System.nanoTime();
            List<SearchHit> hits =
                    engine.search(context, request).toCompletableFuture().join().hits();
            timingMs += (System.nanoTime() - start) / 1_000_000.0;
            hits.forEach(h -> merged.add(new Scored(h.chunkId().value(), h.score())));
        }
        merged.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(Scored::chunkId));
        List<Hit> top = new ArrayList<>();
        for (int i = 0; i < Math.min(TOP_K, merged.size()); i++) {
            top.add(new Hit(merged.get(i).chunkId(), merged.get(i).score(), i));
        }
        return new QueryOutput(
                input.queryId(), input.mode(), input.filter(), input.selectivity(),
                List.copyOf(top), List.copyOf(input.eligibleIds()), timingMs,
                tenants.size() == 1 ? "single" : "summed_fanout_" + tenants.size());
    }

    private record Scored(String chunkId, double score) {}

    private static Optional<float[]> queryVector(GoldenInput input) {
        if (input.queryVectorB64() == null || input.queryVectorB64().isBlank()) {
            return Optional.empty();
        }
        byte[] bytes = Base64.getDecoder().decode(input.queryVectorB64());
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vec = new float[bytes.length / 4];
        for (int i = 0; i < vec.length; i++) {
            vec[i] = buf.getFloat();
        }
        return Optional.of(vec);
    }
}
