package org.synanton.ingestioncache.client;

import org.synanton.ingestioncache.CassandraTestSupport;
import static org.assertj.core.api.Assertions.assertThat;

import com.datastax.oss.driver.api.core.CqlSession;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.synanton.ingestioncache.config.SchemaInstaller;
import org.synanton.ingestioncache.domain.ExtractionResultRow;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

class ExtractionProvenancePersistenceTest {

    private static CqlSession session;
    private static IngestionCacheClient cache;

    @BeforeAll
    static void startCassandra() {
        session = CassandraTestSupport.session();
        CassandraTestSupport.reset();
        cache = new IngestionCacheClient(session);
    }


    @Test
    void persistsAndReadsCompiledAndRenderedProvenance() {
        String tenant = tenant();
        UUID contentRef = UUID.randomUUID();
        UUID sourceVersion = UUID.randomUUID();

        cache.upsertExtractionResult(tenant, contentRef, 0, sourceVersion, "[]", "[]",
                "extraction-v2", "a".repeat(64), "b".repeat(64),
                "schema-v1", "c".repeat(64), Instant.now());

        ExtractionResultRow row = onlyRow(tenant, contentRef);
        assertThat(row.promptVersion()).isEqualTo("extraction-v2");
        assertThat(row.promptCompiledDigest()).isEqualTo("a".repeat(64));
        assertThat(row.promptRenderedDigest()).isEqualTo("b".repeat(64));
        assertThat(row.schemaVersion()).isEqualTo("schema-v1");
        assertThat(row.schemaDigest()).isEqualTo("c".repeat(64));
    }

    @Test
    void persistsGatewayFinishReasonAndIdentifiers() {
        String tenant = tenant();
        UUID contentRef = UUID.randomUUID();
        UUID sourceVersion = UUID.randomUUID();

        cache.upsertExtractionResult(tenant, contentRef, 0, sourceVersion, "[]", "[]",
                "extraction-v2", "a".repeat(64), "b".repeat(64),
                "schema-v1", "c".repeat(64), null, Instant.now(),
                "stop", "req-42", "exec-42");

        ExtractionResultRow row = onlyRow(tenant, contentRef);
        assertThat(row.finishReason()).isEqualTo("stop");
        assertThat(row.gatewayRequestId()).isEqualTo("req-42");
        assertThat(row.gatewayExecutionId()).isEqualTo("exec-42");
    }

    @Test
    void legacyNullsRemainNullWithoutBackfill() {
        String tenant = tenant();
        UUID contentRef = UUID.randomUUID();
        UUID sourceVersion = UUID.randomUUID();

        cache.upsertExtractionResult(tenant, contentRef, 0, sourceVersion,
                "[]", "[]", Instant.now());

        ExtractionResultRow row = onlyRow(tenant, contentRef);
        assertThat(row.promptVersion()).isNull();
        assertThat(row.promptCompiledDigest()).isNull();
        assertThat(row.promptRenderedDigest()).isNull();
        assertThat(row.schemaVersion()).isNull();
        assertThat(row.schemaDigest()).isNull();
    }

    @Test
    void renderedOnlyRemainsRenderedOnly() {
        String tenant = tenant();
        UUID contentRef = UUID.randomUUID();
        UUID sourceVersion = UUID.randomUUID();

        cache.upsertExtractionResult(tenant, contentRef, 0, sourceVersion, "[]", "[]",
                "extraction-v2", null, "b".repeat(64), null, null, Instant.now());

        ExtractionResultRow row = onlyRow(tenant, contentRef);
        assertThat(row.promptCompiledDigest()).isNull();
        assertThat(row.promptRenderedDigest()).isEqualTo("b".repeat(64));
        assertThat(row.schemaDigest()).isNull();
    }

    @Test
    void provenanceSurvivesASecondSessionRead() {
        String tenant = tenant();
        UUID contentRef = UUID.randomUUID();
        UUID sourceVersion = UUID.randomUUID();
        cache.upsertExtractionResult(tenant, contentRef, 0, sourceVersion, "[]", "[]",
                "extraction-v2", "a".repeat(64), "b".repeat(64), null, null, Instant.now());

        try (CqlSession restarted = CassandraTestSupport.newSession()) {
            List<ExtractionResultRow> rows = new IngestionCacheClient(restarted)
                    .listExtractionResults(tenant, contentRef);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().promptCompiledDigest()).isEqualTo("a".repeat(64));
            assertThat(rows.getFirst().promptRenderedDigest()).isEqualTo("b".repeat(64));
        }
    }

    private static ExtractionResultRow onlyRow(String tenant, UUID contentRef) {
        List<ExtractionResultRow> rows = cache.listExtractionResults(tenant, contentRef);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private static String tenant() {
        return "tenant-" + UUID.randomUUID();
    }
}
