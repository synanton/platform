package org.synanton.synvault.inmemory;

import org.junit.jupiter.api.Test;
import org.synanton.storage.contract.AdapterMetrics;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.InMemoryAdapterMetrics;
import org.synanton.storage.contract.PolicyContext;
import org.synanton.storage.contract.PrincipalRef;
import org.synanton.storage.contract.SecurityContext;
import org.synanton.storage.contract.TenantScope;
import org.synanton.synvault.api.ChunkQuery;
import org.synanton.synvault.api.DocumentWriteOptions;

import static org.assertj.core.api.Assertions.assertThat;

class AdapterMetricsWiringTest {

    private static final SecurityContext CTX =
            SecurityContext.user(TenantScope.of("t"), PrincipalRef.user("u"), PolicyContext.of("p", "r"));

    @Test
    void operationsAreRecorded() {
        InMemoryAdapterMetrics metrics = new InMemoryAdapterMetrics("inmemory@1.0.0");
        InMemorySynvaultStore store = new InMemorySynvaultStore(metrics);
        var doc =
                new org.synanton.synvault.api.Document(
                        DocumentId.of("d1"),
                        "t",
                        "cache://x",
                        java.util.Map.of(),
                        0,
                        java.time.Instant.now(),
                        java.time.Instant.now());
        store.putDocument(CTX, doc, DocumentWriteOptions.upserting()).toCompletableFuture().join();
        store.getDocument(CTX, DocumentId.of("d1")).toCompletableFuture().join();
        store.getDocument(CTX, DocumentId.of("missing")).toCompletableFuture().join();
        store.getChunks(CTX, DocumentId.of("d1"), ChunkQuery.all(),
                        new org.synanton.storage.contract.PageRequest(10, java.util.Optional.empty(), true))
                .toCompletableFuture()
                .join();

        var snapshot = metrics.snapshot();
        assertThat(snapshot.operations().get(AdapterMetrics.SYNVAULT_PUT).count()).isEqualTo(1);
        assertThat(snapshot.operations().get(AdapterMetrics.SYNVAULT_GET).count()).isEqualTo(2);
        assertThat(snapshot.operations().get(AdapterMetrics.SYNVAULT_CHUNKS).count()).isEqualTo(1);
        assertThat(snapshot.operations().get(AdapterMetrics.SYNVAULT_GET).errors()).isZero();
    }
}
