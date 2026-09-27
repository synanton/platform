package org.synanton.synquest.ydb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.EmbeddingModelRef;
import org.synanton.storage.contract.GenerationId;
import org.synanton.storage.contract.PolicyContext;
import org.synanton.storage.contract.PrincipalRef;
import org.synanton.storage.contract.SecurityContext;
import org.synanton.storage.contract.TenantScope;
import org.synanton.synquest.api.ChunkProjection;
import org.synanton.synquest.api.EligibilityConstraints;
import org.synanton.synquest.api.RelevanceFilters;
import org.synanton.synquest.api.SearchMode;
import org.synanton.synquest.api.SearchRequest;
import org.synanton.synquest.api.TemporalExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 033: cross-tenant leakage + rebuild/replay under load, YDB path alone (no
 * baseline needed). Concurrent writers, searchers, and stale-replay traffic;
 * tenant convention: chunk ids prefixed per tenant ("a-…", "b-…").
 */
class YdbLoadTest {

    private static final TenantScope TENANT_A = TenantScope.of("tenant_a");
    private static final TenantScope TENANT_B = TenantScope.of("tenant_b");
    private static final PolicyContext POLICY = PolicyContext.of("p", "r1");
    private static final GenerationId GEN = GenerationId.of("gen-load-1");
    private static final int CHUNKS = 10;
    private static final int ROUNDS = 15;

    private static SecurityContext ctx(TenantScope tenant) {
        return SecurityContext.user(tenant, PrincipalRef.user("u-1"), POLICY);
    }

    private static ChunkProjection projection(TenantScope tenant, int i, long ordering) {
        String pfx = tenant.equals(TENANT_A) ? "a" : "b";
        return new ChunkProjection(
                ChunkId.of(pfx + "-c" + i),
                DocumentId.of(pfx + "-doc"),
                tenant.tenantId(),
                "load bearing wall content number " + i + " round " + ordering,
                Map.of("round", String.valueOf(ordering)),
                null,
                EmbeddingModelRef.of("m", "v1", "d"),
                ordering,
                GEN);
    }

    private static SearchRequest query(TenantScope tenant, String text) {
        return new SearchRequest(
                text,
                Optional.empty(),
                Optional.empty(),
                SearchMode.LEXICAL,
                EligibilityConstraints.from(tenant, List.of(PrincipalRef.user("u-1")), POLICY),
                RelevanceFilters.none(),
                TemporalExtension.empty(),
                20,
                0.0);
    }

    @Test
    void leakageAndReplayUnderLoad() throws Exception {
        YdbSearchTestBase.ensureStarted();
        String prefix = "t_quest_load";
        YdbSearchSchema.ensureSchema(YdbSearchTestBase.client(), prefix, 2);
        YdbSearchSchema.truncateAll(YdbSearchTestBase.client(), prefix);
        YdbSynquestEngine engine = new YdbSynquestEngine(YdbSearchTestBase.client(), prefix);

        List<ChunkProjection> seed = new ArrayList<>();
        for (int i = 0; i < CHUNKS; i++) {
            seed.add(projection(TENANT_A, i, 1));
            seed.add(projection(TENANT_B, i, 1));
        }
        engine.upsert(seed).toCompletableFuture().join();

        ConcurrentLinkedQueue<String> violations = new ConcurrentLinkedQueue<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Future<?>> futures = new ArrayList<>();

        // Writers: fresh ordering each round (accepted).
        for (TenantScope tenant : List.of(TENANT_A, TENANT_B)) {
            futures.add(
                    pool.submit(
                            () -> {
                                go.await(10, TimeUnit.SECONDS);
                                for (int r = 2; r < 2 + ROUNDS; r++) {
                                    List<ChunkProjection> batch = new ArrayList<>();
                                    for (int i = 0; i < CHUNKS; i++) {
                                        batch.add(projection(tenant, i, r));
                                    }
                                    engine.upsert(batch).toCompletableFuture().join();
                                }
                                return null;
                            }));
        }
        // Replayer: stale ordering must be ignored (regression prevention under load).
        futures.add(
                pool.submit(
                        () -> {
                            go.await(10, TimeUnit.SECONDS);
                            for (int r = 0; r < ROUNDS; r++) {
                                List<ChunkProjection> stale = new ArrayList<>();
                                for (int i = 0; i < CHUNKS; i++) {
                                    stale.add(projection(TENANT_A, i, 1));
                                    stale.add(projection(TENANT_B, i, 1));
                                }
                                engine.upsert(stale).toCompletableFuture().join();
                            }
                            return null;
                        }));
        // Searchers: every hit must belong to the requesting tenant.
        for (TenantScope tenant : List.of(TENANT_A, TENANT_B)) {
            String expect = tenant.equals(TENANT_A) ? "a-" : "b-";
            futures.add(
                    pool.submit(
                            () -> {
                                go.await(10, TimeUnit.SECONDS);
                                for (int r = 0; r < ROUNDS * 2; r++) {
                                    var result =
                                            engine.search(ctx(tenant), query(tenant, "load bearing wall"))
                                                    .toCompletableFuture()
                                                    .join();
                                    for (var hit : result.hits()) {
                                        if (!hit.chunkId().value().startsWith(expect)) {
                                            violations.add(
                                                    "leak: " + tenant.tenantId() + " saw " + hit.chunkId().value());
                                        }
                                    }
                                }
                                return null;
                            }));
        }
        go.countDown();
        try {
            for (Future<?> f : futures) {
                try {
                    f.get(300, TimeUnit.SECONDS);
                } catch (Exception e) {
                    failure.compareAndSet(null, e);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(failure.get()).as("no worker may fail").isNull();
        assertThat(violations).as("zero cross-tenant leakage under load").isEmpty();

        // Final state: current generation fully visible per tenant.
        for (TenantScope tenant : List.of(TENANT_A, TENANT_B)) {
            var result =
                    engine.search(ctx(tenant), query(tenant, "load bearing wall"))
                            .toCompletableFuture()
                            .join();
            assertThat(result.hits()).as("final state consistent for " + tenant.tenantId()).hasSize(CHUNKS);
        }
        System.out.println(
                "LOAD rounds=" + ROUNDS + " violations=" + violations.size() + " workers=5");
    }

}
