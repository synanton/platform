package org.synanton.ingestioncache.config;

import com.datastax.oss.driver.api.core.CqlSession;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 035 class audit, permanent guard: every table the migrations declare must
 * actually exist after install. Catches silent migration skips (e.g. the V7
 * header-comment semicolon that suppressed the annotations table everywhere).
 */
class SchemaInventoryTest {

    private static final List<String> EXPECTED_TABLES =
            List.of(
                    "analysis_cache",
                    "annotations",
                    "chunks_payload",
                    "chunk_source_versions",
                    "dead_letter_events",
                    "embedding_content_cache",
                    "extraction_results",
                    "relation_promotion_results",
                    "resolution_results",
                    "image_caption_cache",
                    "ingestion_errors",
                    "ingestion_manifest_entries",
                    "ingestion_manifests",
                    "ingestion_runs",
                    "jobs",
                    "knowledge_processing_runs",
                    "manifest",
                    "manifest_transitions_outbox",
                    "operation_idempotency",
                    "operations",
                    "source_activation",
                    "source_current",
                    "source_identities",
                    "source_revision_generation",
                    "source_tombstones",
                    "source_version_manifest_entries",
                    "source_versions",
                    "sources",
                    "tenant_registry",
                    "workflow_states",
                    "workflow_leases",
                    "workflow_steps");

    private static CassandraContainer cassandra;
    private static CqlSession session;

    @BeforeAll
    static void startInfra() {
        cassandra = new CassandraContainer(DockerImageName.parse("cassandra:4.1"));
        cassandra.start();
        InetSocketAddress contact =
                new InetSocketAddress(cassandra.getHost(), cassandra.getMappedPort(9042));
        try (CqlSession admin =
                CqlSession.builder()
                        .addContactPoint(contact)
                        .withLocalDatacenter("datacenter1")
                        .build()) {
            admin.execute(
                    "CREATE KEYSPACE IF NOT EXISTS ingestion_cache WITH replication = "
                            + "{'class':'SimpleStrategy','replication_factor':1}");
        }
        session =
                CqlSession.builder()
                        .addContactPoint(contact)
                        .withLocalDatacenter("datacenter1")
                        .withKeyspace("ingestion_cache")
                        .build();
        SchemaInstaller.install(session);
    }

    @AfterAll
    static void stopInfra() {
        if (session != null) {
            session.close();
        }
        if (cassandra != null) {
            cassandra.stop();
        }
    }

    @Test
    void allMigrationTablesExist() {
        List<String> actual =
                session
                        .execute(
                                "SELECT table_name FROM system_schema.tables"
                                        + " WHERE keyspace_name='ingestion_cache'")
                        .all()
                        .stream()
                        .map(row -> row.getString("table_name"))
                        .sorted()
                        .collect(Collectors.toList());
        assertThat(actual).containsAll(EXPECTED_TABLES);
    }

    @Test
    void contentCacheReferencesAreStoredAlongsideLegacyLookupFields() {
        List<String> actual = session
            .execute("SELECT table_name, column_name FROM system_schema.columns " +
                "WHERE keyspace_name='ingestion_cache'")
            .all()
            .stream()
            .map(row -> row.getString("table_name") + "." + row.getString("column_name"))
            .toList();

        assertThat(actual).contains(
            "manifest.content_cache_reference",
            "source_versions.content_cache_reference",
            "workflow_states.workflow_id",
            "workflow_steps.command_id",
            "workflow_steps.expected_events_json",
            "workflow_steps.retry_policy_json",
            "source_versions.event_attempt_count",
            "source_tombstones.event_last_error",
            "chunks_payload.source_version_ids",
            "chunks_payload.processing_run_id",
            "chunk_source_versions.chunk_sha256",
            "chunk_source_versions.processing_run_id",
            "manifest.processing_run_id",
            "knowledge_processing_runs.producer_version",
            "knowledge_processing_runs.input_source_version_ids"
        );

        // EWP-2 Migration 3 (V16): typed-envelope metadata on both outbox tables.
        assertThat(actual).contains(
            "source_versions.schema_version",
            "source_versions.correlation_id",
            "source_versions.causation_id",
            "source_versions.replay_class",
            "source_versions.security_metadata",
            "manifest_transitions_outbox.schema_version",
            "manifest_transitions_outbox.correlation_id",
            "manifest_transitions_outbox.causation_id",
            "manifest_transitions_outbox.replay_class",
            "manifest_transitions_outbox.security_metadata"
        );

        // RAKG Phase 2 (V17): extraction request slot + search-projection readiness.
        assertThat(actual).contains(
            "source_versions.search_projected_at",
            "source_versions.extraction_event_id",
            "source_versions.extraction_event_type",
            "source_versions.extraction_event_payload_json",
            "source_versions.extraction_event_published",
            "source_versions.extraction_event_published_at",
            "source_versions.extraction_event_attempt_count",
            "source_versions.extraction_event_last_error",
            "source_versions.extraction_event_next_attempt_at",
            "source_versions.extraction_event_dead_lettered",
            "source_versions.extraction_status",
            "source_versions.activation_event_id",
            "source_versions.activation_event_type",
            "source_versions.activation_event_payload_json",
            "source_versions.activation_event_published",
            "source_versions.activation_event_published_at",
            "source_versions.activation_event_attempt_count",
            "source_versions.activation_event_last_error",
            "source_versions.activation_event_next_attempt_at",
            "source_versions.activation_event_dead_lettered",
            "source_current.accepted_generation",
            "source_revision_generation.tenant_id",
            "source_revision_generation.source_id",
            "source_revision_generation.revision_id",
            "source_revision_generation.generation",
            "source_revision_generation.connector_id",
            "source_revision_generation.resource_id",
            "source_revision_generation.created_at",
            "extraction_results.entities_json",
            "extraction_results.relations_json",
            "source_versions.graph_status",
            "source_versions.graph_commit_at",
            "source_versions.graph_validation_counters",
            "chunks_payload.document_offset_start",
            "chunks_payload.document_offset_end",
            "source_versions.graph_attempt_count",
            "source_versions.graph_next_attempt_at",
            "source_versions.resolution_status",
            "source_versions.resolution_started_at",
            "source_versions.resolution_completed_at",
            "source_versions.resolution_attempt_count",
            "source_versions.resolution_next_attempt_at",
            "source_versions.resolution_counters",
            "resolution_results.outcome",
            "resolution_results.strategy_version",
            "resolution_results.reason_code",
            "relation_promotion_results.promotion_status",
            "resolution_results.claim_status",
            "resolution_results.claim_owner_id",
            "resolution_results.lease_expires_at",
            "resolution_results.attempt_count",
            "resolution_results.next_attempt_at",
            "resolution_results.claimed_at",
            "resolution_results.work_created_at",
            "resolution_results.last_error",
            "resolution_results.failure_class",
            "resolution_results.connector_id",
            "resolution_results.source_id",
            "resolution_results.resource_id",
            "resolution_results.search_generation",
            "relation_promotion_results.claim_status",
            "relation_promotion_results.claim_owner_id",
            "relation_promotion_results.lease_expires_at",
            "relation_promotion_results.attempt_count",
            "relation_promotion_results.next_attempt_at",
            "relation_promotion_results.claimed_at",
            "relation_promotion_results.last_error",
            "relation_promotion_results.failure_class"
        );
    }
}
