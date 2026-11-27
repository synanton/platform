package org.synanton.ingestioncache.compliance;

import org.synanton.ingestioncache.CassandraTestSupport;
import static org.assertj.core.api.Assertions.assertThat;

import com.datastax.oss.driver.api.core.CqlSession;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.synanton.ingestioncache.cache.ExtractionCacheStore;
import org.synanton.ingestioncache.cache.ExtractionCacheEntry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.config.SchemaInstaller;
import org.synanton.ingestioncache.quarantine.QuarantineGeneration;
import org.synanton.ingestioncache.quarantine.QuarantineRecord;
import org.synanton.ingestioncache.quarantine.QuarantineStore;

class ComplianceDeletionStoreTest {

    private static CqlSession session;
    private static QuarantineStore quarantine;
    private static IngestionCacheClient cache;
    private static ComplianceDeletionAuditStore audit;
    private static ComplianceDeletionStore deletion;

    @BeforeAll
    static void startCassandra() {
        session = CassandraTestSupport.session();
        CassandraTestSupport.reset();
        quarantine = new QuarantineStore(session);
        cache = new IngestionCacheClient(session);
        audit = new ComplianceDeletionAuditStore(session);
        deletion = new ComplianceDeletionStore(quarantine, cache, audit);
    }


    @Test
    void hardDeleteRemovesOnlyTargetVersionAndWritesDurableAudit() {
        String tenant = tenant();
        String otherTenant = tenant();
        UUID contentRef = UUID.randomUUID();
        UUID targetVersion = UUID.randomUUID();
        UUID siblingVersion = UUID.randomUUID();
        UUID targetGeneration = UUID.randomUUID();

        quarantine.createGeneration(generation(tenant, targetVersion, targetGeneration));
        quarantine.createGeneration(generation(tenant, siblingVersion, UUID.randomUUID()));
        quarantine.append(record(tenant, targetVersion, targetGeneration, 0, "target"));
        quarantine.append(record(tenant, siblingVersion, UUID.randomUUID(), 0, "sibling"));
        cache.upsertExtractionResult(tenant, contentRef, 0, targetVersion, "[]", "[]", Instant.now());
        cache.upsertExtractionResult(tenant, contentRef, 1, siblingVersion, "[]", "[]", Instant.now());
        cache.upsertExtractionResult(otherTenant, contentRef, 0, targetVersion, "[]", "[]",
                Instant.now());

        ComplianceDeletionStore.DeletionResult result = deletion.hardDeleteSource(
                tenant, targetVersion, contentRef, "reviewer", "hard delete");

        assertThat(result.generations()).isEqualTo(1);
        assertThat(result.extractionResults()).isEqualTo(1);
        assertThat(result.idempotent()).isFalse();
        assertThat(quarantine.generations(tenant, targetVersion)).isEmpty();
        assertThat(quarantine.records(tenant, targetVersion)).isEmpty();
        assertThat(quarantine.currentGeneration(tenant, targetVersion)).isEmpty();
        assertThat(quarantine.generations(tenant, siblingVersion)).hasSize(1);
        assertThat(quarantine.records(tenant, siblingVersion)).hasSize(1);
        assertThat(cache.listExtractionResults(tenant, contentRef)).hasSize(1);
        assertThat(cache.listExtractionResults(tenant, contentRef).getFirst().sourceVersionId())
                .isEqualTo(siblingVersion);
        assertThat(cache.listExtractionResults(otherTenant, contentRef)).hasSize(1);

        List<ComplianceDeletionEvent> events = audit.events(tenant, targetVersion);
        assertThat(events).extracting(ComplianceDeletionEvent::eventType)
                .contains("HARD_DELETE_ATTEMPTED", "HARD_DELETE_APPLIED");
        assertThat(events).filteredOn(event -> event.eventType().equals("HARD_DELETE_APPLIED"))
                .singleElement().satisfies(event -> {
                    assertThat(event.deletedCount()).isEqualTo(2);
                    assertThat(event.recordScope()).isEqualTo(
                            "GENERATION+QUARANTINE+EXTRACTION_RESULTS");
                });
    }

    @Test
    void retryIsIdempotentAndAuditedWithoutContradictoryState() {
        String tenant = tenant();
        UUID contentRef = UUID.randomUUID();
        UUID sourceVersion = UUID.randomUUID();
        UUID generation = UUID.randomUUID();
        quarantine.createGeneration(generation(tenant, sourceVersion, generation));
        cache.upsertExtractionResult(tenant, contentRef, 0, sourceVersion, "[]", "[]",
                Instant.now());

        deletion.hardDeleteSource(tenant, sourceVersion, contentRef, "reviewer", "first");
        ComplianceDeletionStore.DeletionResult retry = deletion.hardDeleteSource(
                tenant, sourceVersion, contentRef, "reviewer", "retry");

        assertThat(retry.idempotent()).isTrue();
        assertThat(retry.total()).isZero();
        assertThat(audit.events(tenant, sourceVersion)).extracting(
                        ComplianceDeletionEvent::eventType)
                .contains("HARD_DELETE_APPLIED", "HARD_DELETE_IDEMPOTENT");
    }

    @Test
    void cascadeDeletesCacheAndCallsCaptureWithoutTouchingSiblings() {
        String tenant = tenant();
        String otherTenant = tenant();
        UUID contentRef = UUID.randomUUID();
        UUID targetVersion = UUID.randomUUID();
        UUID siblingVersion = UUID.randomUUID();
        UUID targetGeneration = UUID.randomUUID();
        quarantine.createGeneration(generation(tenant, targetVersion, targetGeneration));
        quarantine.createGeneration(generation(tenant, siblingVersion, UUID.randomUUID()));
        quarantine.promote(tenant, targetVersion, targetGeneration, "reviewer", "promote");
        ExtractionCacheStore cacheStore = new ExtractionCacheStore(session);
        cacheStore.put(new ExtractionCacheEntry(tenant, targetVersion, "target-key",
                targetGeneration, "[]", "[]", "extraction-v2", "a".repeat(64), null,
                null, null, Instant.now()));
        cacheStore.put(new ExtractionCacheEntry(tenant, siblingVersion, "sibling-key",
                UUID.randomUUID(), "[]", "[]", "extraction-v2", "a".repeat(64), null,
                null, null, Instant.now()));
        cacheStore.put(new ExtractionCacheEntry(otherTenant, targetVersion, "other-key",
                UUID.randomUUID(), "[]", "[]", "extraction-v2", "a".repeat(64), null,
                null, null, Instant.now()));
        AtomicInteger captureCalls = new AtomicInteger();
        ComplianceDeletionStore cascade = new ComplianceDeletionStore(quarantine, cache,
                cacheStore, (captureTenant, captureVersion) -> {
                    assertThat(captureTenant).isEqualTo(tenant);
                    assertThat(captureVersion).isEqualTo(targetVersion);
                    captureCalls.incrementAndGet();
                    return 1;
                }, audit);

        ComplianceDeletionStore.DeletionResult result = cascade.hardDeleteSource(
                tenant, targetVersion, contentRef, "reviewer", "hard delete");

        assertThat(result.generations()).isEqualTo(1);
        assertThat(result.cacheEntries()).isEqualTo(1);
        assertThat(result.captureArtifacts()).isEqualTo(1);
        assertThat(result.idempotent()).isFalse();
        assertThat(captureCalls).hasValue(1);
        assertThat(cacheStore.get(tenant, targetVersion, "target-key")).isEmpty();
        assertThat(cacheStore.get(tenant, siblingVersion, "sibling-key")).isPresent();
        assertThat(cacheStore.get(otherTenant, targetVersion, "other-key")).isPresent();
        assertThat(quarantine.generations(tenant, siblingVersion)).hasSize(1);
        assertThat(audit.events(tenant, targetVersion)).filteredOn(
                        event -> event.eventType().equals("HARD_DELETE_APPLIED"))
                .singleElement().satisfies(event -> {
                    assertThat(event.recordScope()).contains("EXTRACTION_CACHE", "CAPTURE");
                    assertThat(event.detail()).contains("cacheEntries=1", "captureArtifacts=1");
                });
    }

    private static QuarantineGeneration generation(String tenant, UUID sourceVersion,
                                                   UUID generation) {
        return QuarantineGeneration.staged(tenant, sourceVersion, generation, "doc",
                "extraction-v2", "a".repeat(64), "c".repeat(64), "qwen3-8b-fp8", "rev",
                Instant.now(), UUID.randomUUID());
    }

    private static QuarantineRecord record(String tenant, UUID sourceVersion, UUID generation,
                                          int line, String raw) {
        return new QuarantineRecord(tenant, sourceVersion, generation, 0, line,
                "MALFORMED_LINE", "parse", "detail", "d".repeat(64), raw, Instant.now(),
                "QUARANTINED", null, null, null, null);
    }

    private static String tenant() {
        return "tenant-" + UUID.randomUUID();
    }
}
