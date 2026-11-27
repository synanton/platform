package org.synanton.synflux.extraction.cache;

import org.synanton.synflux.SynfluxCassandraTestSupport;
import static org.assertj.core.api.Assertions.assertThat;

import com.datastax.oss.driver.api.core.CqlSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.synanton.extraction.engine.ExtractionIntegrity;
import org.synanton.extraction.engine.ExtractionResult;
import org.synanton.extraction.engine.LlmProvenance;
import org.synanton.extraction.engine.LlmUsage;
import org.synanton.extraction.engine.PreEntity;
import org.synanton.extraction.engine.provenance.ExtractionCacheKey;
import org.synanton.ingestioncache.cache.ExtractionCacheStore;
import org.synanton.ingestioncache.config.SchemaInstaller;
import org.synanton.ingestioncache.quarantine.QuarantineGeneration;
import org.synanton.ingestioncache.quarantine.QuarantineStore;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

class ExtractionCacheServiceTest {

    private static CqlSession session;
    private static QuarantineStore quarantine;
    private static ExtractionCacheService service;

    @BeforeAll
    static void startCassandra() {
        session = SynfluxCassandraTestSupport.session();
        SynfluxCassandraTestSupport.reset();
        quarantine = new QuarantineStore(session);
        service = new ExtractionCacheService(new ExtractionCacheStore(session), new ObjectMapper());
    }


    @Test
    void hitReturnsOriginalGenerationWithoutLoaderOrPointerChange() {
        Fixture fixture = fixture();
        AtomicInteger calls = new AtomicInteger();

        ExtractionCacheService.CachedExtractionResult miss = service.getOrLoad(
                fixture.key, fixture.generation,
                () -> {
                    calls.incrementAndGet();
                    return result(fixture.promptDigest, fixture.schemaDigest);
                });
        assertThat(miss.cacheHit()).isFalse();
        assertThat(calls).hasValue(1);

        ExtractionCacheService.CachedExtractionResult hit = service.getOrLoad(
                fixture.key, UUID.randomUUID(),
                () -> {
                    throw new AssertionError("loader must not run on a cache hit");
                });

        assertThat(hit.cacheHit()).isTrue();
        assertThat(hit.generationId()).isEqualTo(fixture.generation);
        assertThat(hit.result().provenance().promptCompiledDigest())
                .isEqualTo(fixture.promptDigest);
        assertThat(hit.result().preEntities()).extracting(PreEntity::surfaceForm)
                .containsExactly("Acme");
        assertThat(calls).hasValue(1);
        assertThat(quarantine.generations(fixture.tenant, fixture.sourceVersion)).hasSize(1);
        assertThat(quarantine.currentGeneration(fixture.tenant, fixture.sourceVersion))
                .contains(fixture.generation);
    }

    @Test
    void changedContractInputMissesAndCallsLoader() {
        Fixture fixture = fixture();
        AtomicInteger calls = new AtomicInteger();
        service.getOrLoad(fixture.key, fixture.generation,
                () -> {
                    calls.incrementAndGet();
                    return result(fixture.promptDigest, fixture.schemaDigest);
                });

        ExtractionCacheKey changed = ExtractionCacheKey.forRequest(fixture.tenant,
                fixture.sourceVersion, "Acme supplies widgets.", "b".repeat(64),
                fixture.schemaDigest, "qwen3-8b-fp8", "rev", 0.0, 1.0, "tok-v1");
        ExtractionCacheService.CachedExtractionResult miss = service.getOrLoad(changed,
                UUID.randomUUID(), () -> {
                    calls.incrementAndGet();
                    return result("b".repeat(64), fixture.schemaDigest);
                });

        assertThat(miss.cacheHit()).isFalse();
        assertThat(calls).hasValue(2);
    }

    @Test
    void hitDoesNotRequireLoaderAndCanServeRenderedOnlyProvenance() {
        Fixture fixture = fixture();
        service.getOrLoad(fixture.key, fixture.generation,
                () -> new ExtractionResult(List.of(), List.of(), LlmUsage.NONE, List.of(),
                        ExtractionIntegrity.CLEAN,
                        new LlmProvenance("extraction-v2", null, "b".repeat(64),
                                null, null)));

        ExtractionCacheService.CachedExtractionResult hit = service.getOrLoad(
                fixture.key, null, () -> {
                    throw new AssertionError("loader must not run");
                });

        assertThat(hit.cacheHit()).isTrue();
        assertThat(hit.result().provenance().promptCompiledDigest()).isNull();
        assertThat(hit.result().provenance().promptRenderedDigest()).isEqualTo("b".repeat(64));
    }

    @Test
    void hitWithNullGenerationPassedByCallerStillUsesCachedGeneration() {
        Fixture fixture = fixture();
        service.getOrLoad(fixture.key, fixture.generation,
                () -> result(fixture.promptDigest, fixture.schemaDigest));

        ExtractionCacheService.CachedExtractionResult hit = service.getOrLoad(
                fixture.key, null, () -> {
                    throw new AssertionError("loader must not run");
                });
        assertThat(hit.generationId()).isEqualTo(fixture.generation);
    }

    private static Fixture fixture() {
        String tenant = "tenant-" + UUID.randomUUID();
        UUID sourceVersion = UUID.randomUUID();
        UUID generation = UUID.randomUUID();
        quarantine.createGeneration(QuarantineGeneration.staged(tenant, sourceVersion, generation,
                "doc", "extraction-v2", "a".repeat(64), "c".repeat(64),
                "qwen3-8b-fp8", "rev", Instant.now(), UUID.randomUUID()));
        quarantine.promote(tenant, sourceVersion, generation, "reviewer", "promote");
        String schemaDigest = "c".repeat(64);
        return new Fixture(tenant, sourceVersion, generation,
                ExtractionCacheKey.forRequest(tenant, sourceVersion, "Acme supplies widgets.",
                        "a".repeat(64), schemaDigest, "qwen3-8b-fp8", "rev", 0.0, 1.0, "tok-v1"),
                "a".repeat(64), schemaDigest);
    }

    private static ExtractionResult result(String promptDigest, String schemaDigest) {
        PreEntity entity = new PreEntity("m1", "Acme", "Supplier", 0, 4, "Acme");
        return new ExtractionResult(List.of(entity), List.of(), LlmUsage.NONE, List.of(),
                ExtractionIntegrity.CLEAN, new LlmProvenance("extraction-v2", promptDigest,
                        "b".repeat(64), "schema-v1", schemaDigest));
    }

    private record Fixture(String tenant, UUID sourceVersion, UUID generation,
                           ExtractionCacheKey key, String promptDigest, String schemaDigest) {}
}
