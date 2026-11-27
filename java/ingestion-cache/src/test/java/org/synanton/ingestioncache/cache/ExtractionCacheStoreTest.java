package org.synanton.ingestioncache.cache;

import org.synanton.ingestioncache.CassandraTestSupport;
import static org.assertj.core.api.Assertions.assertThat;

import com.datastax.oss.driver.api.core.CqlSession;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.synanton.ingestioncache.config.SchemaInstaller;
import org.synanton.ingestioncache.quarantine.QuarantineGeneration;
import org.synanton.ingestioncache.quarantine.QuarantineStore;

class ExtractionCacheStoreTest {

    private static CqlSession session;
    private static QuarantineStore quarantine;
    private static ExtractionCacheStore cache;

    @BeforeAll
    static void startCassandra() {
        session = CassandraTestSupport.session();
        CassandraTestSupport.reset();
        quarantine = new QuarantineStore(session);
        cache = new ExtractionCacheStore(session);
    }


    @Test
    void cacheHitReturnsOriginalGenerationAndLeavesCurrentPointerUnchanged() {
        String tenant = tenant();
        UUID sourceVersion = UUID.randomUUID();
        UUID generation = UUID.randomUUID();
        quarantine.createGeneration(QuarantineGeneration.staged(tenant, sourceVersion, generation,
                "doc", "extraction-v2", "a".repeat(64), "c".repeat(64),
                "qwen3-8b-fp8", "rev", Instant.now(), UUID.randomUUID()));
        quarantine.promote(tenant, sourceVersion, generation, "reviewer", "promote");

        ExtractionCacheEntry entry = new ExtractionCacheEntry(tenant, sourceVersion, "key|digest",
                generation, "[]", "[]", "extraction-v2", "a".repeat(64), "b".repeat(64),
                "schema-v1", "c".repeat(64), Instant.now());
        cache.put(entry);

        ExtractionCacheEntry hit = cache.get(tenant, sourceVersion, "key|digest").orElseThrow();
        assertThat(hit.generationId()).isEqualTo(generation);
        assertThat(quarantine.currentGeneration(tenant, sourceVersion)).contains(generation);
        assertThat(quarantine.generations(tenant, sourceVersion)).hasSize(1);
    }

    @Test
    void changedKeyMissesAndInvalidationDoesNotDeleteGeneration() {
        String tenant = tenant();
        UUID sourceVersion = UUID.randomUUID();
        UUID generation = UUID.randomUUID();
        quarantine.createGeneration(QuarantineGeneration.staged(tenant, sourceVersion, generation,
                "doc", "extraction-v2", "a".repeat(64), null,
                "qwen3-8b-fp8", "rev", Instant.now(), UUID.randomUUID()));
        cache.put(new ExtractionCacheEntry(tenant, sourceVersion, "key-a", generation,
                "[]", "[]", "extraction-v2", "a".repeat(64), null, null, null, Instant.now()));

        assertThat(cache.get(tenant, sourceVersion, "key-b")).isEmpty();
        cache.invalidate(tenant, sourceVersion, "key-a");
        assertThat(cache.get(tenant, sourceVersion, "key-a")).isEmpty();
        assertThat(quarantine.generations(tenant, sourceVersion)).hasSize(1);
    }

    @Test
    void renderedOnlyProvenanceRoundTripsAndSurvivesRestart() {
        String tenant = tenant();
        UUID sourceVersion = UUID.randomUUID();
        UUID generation = UUID.randomUUID();
        cache.put(new ExtractionCacheEntry(tenant, sourceVersion, "key-rendered", generation,
                "[]", "[]", "extraction-v2", null, "b".repeat(64), null, null, Instant.now()));

        try (CqlSession restarted = CassandraTestSupport.newSession()) {
            ExtractionCacheEntry row = new ExtractionCacheStore(restarted)
                    .get(tenant, sourceVersion, "key-rendered").orElseThrow();
            assertThat(row.generationId()).isEqualTo(generation);
            assertThat(row.promptCompiledDigest()).isNull();
            assertThat(row.promptRenderedDigest()).isEqualTo("b".repeat(64));
        }
    }

    @Test
    void sourceDeletionRemovesCacheRowsOnly() {
        String tenant = tenant();
        UUID sourceVersion = UUID.randomUUID();
        UUID generation = UUID.randomUUID();
        quarantine.createGeneration(QuarantineGeneration.staged(tenant, sourceVersion, generation,
                "doc", "extraction-v2", "a".repeat(64), null,
                "qwen3-8b-fp8", "rev", Instant.now(), UUID.randomUUID()));
        cache.put(new ExtractionCacheEntry(tenant, sourceVersion, "key", generation,
                "[]", "[]", "extraction-v2", "a".repeat(64), null, null, null, Instant.now()));

        cache.deleteForSource(tenant, sourceVersion);

        assertThat(cache.get(tenant, sourceVersion, "key")).isEmpty();
        assertThat(quarantine.generations(tenant, sourceVersion)).hasSize(1);
    }

    private static String tenant() {
        return "tenant-" + UUID.randomUUID();
    }
}
