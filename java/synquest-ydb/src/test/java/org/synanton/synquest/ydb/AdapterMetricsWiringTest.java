package org.synanton.synquest.ydb;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.synanton.storage.contract.AdapterMetrics;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.EmbeddingModelRef;
import org.synanton.storage.contract.InMemoryAdapterMetrics;
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

class AdapterMetricsWiringTest {

    private static final TenantScope TENANT = TenantScope.of("tenant_a");
    private static final PolicyContext POLICY = PolicyContext.of("p", "r1");

    private YdbSynquestEngine engine(InMemoryAdapterMetrics metrics, String prefix) {
        return new YdbSynquestEngine(YdbSearchTestBase.client(), prefix, metrics);
    }

    @Test
    void searchOperationsAreRecorded() {
        YdbSearchTestBase.ensureStarted();
        String prefix = "t_quest_metrics";
        YdbSearchSchema.ensureSchema(YdbSearchTestBase.client(), prefix, 2);
        YdbSearchSchema.truncateAll(YdbSearchTestBase.client(), prefix);
        InMemoryAdapterMetrics metrics = new InMemoryAdapterMetrics("ydb@1.0.0");
        YdbSynquestEngine engine = engine(metrics, prefix);
        SecurityContext ctx = SecurityContext.user(TENANT, PrincipalRef.user("u-1"), POLICY);
        engine.upsert(
                        List.of(
                                new ChunkProjection(
                                        ChunkId.of("c1"),
                                        DocumentId.of("d1"),
                                        "tenant_a",
                                        "metrics probe text",
                                        Map.of(),
                                        new float[] {1.0f, 0.0f},
                                        EmbeddingModelRef.of("m", "v1", "d"),
                                        1,
                                        org.synanton.storage.contract.GenerationId.of("gen-1"))))
                .toCompletableFuture()
                .join();
        engine.search(
                        ctx,
                        new SearchRequest(
                                "probe",
                                Optional.empty(),
                                Optional.empty(),
                                SearchMode.LEXICAL,
                                EligibilityConstraints.from(
                                        TENANT, List.of(PrincipalRef.user("u-1")), POLICY),
                                RelevanceFilters.none(),
                                TemporalExtension.empty(),
                                10,
                                0.0))
                .toCompletableFuture()
                .join();
        var snapshot = metrics.snapshot();
        assertThat(snapshot.operations().get(AdapterMetrics.SYNQUEST_UPSERT).count()).isEqualTo(1);
        assertThat(snapshot.operations().get(AdapterMetrics.SYNQUEST_SEARCH).count()).isEqualTo(1);
    }

}
