package org.synanton.relix.adapter.out.metadata;

import org.junit.jupiter.api.Test;
import org.neo4j.driver.exceptions.ClientException;
import org.synanton.relix.adapter.out.graph.neo4j.CypherExecutor;
import org.synanton.relix.identity.AmbiguousCanonicalGraphException;
import org.synanton.relix.identity.CanonicalGraphMetadata;
import org.synanton.relix.identity.MetadataWriteConflictException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Neo4jCanonicalGraphMetadataStoreTest {

    private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");

    @Test
    void createsConstraintOnceAndReadsMetadata() {
        FakeCypher cypher = new FakeCypher();
        cypher.readRows = List.of(metadataRow());
        Neo4jCanonicalGraphMetadataStore store = new Neo4jCanonicalGraphMetadataStore(cypher);

        CanonicalGraphMetadata first = store.find("tenant-a").orElseThrow();
        CanonicalGraphMetadata second = store.find("tenant-a").orElseThrow();

        assertThat(first.tenantId()).isEqualTo("tenant-a");
        assertThat(first.projectionVersion()).isEqualTo("v1.2.0");
        assertThat(second.tenantId()).isEqualTo("tenant-a");
        assertThat(cypher.writes).hasSize(1);
        assertThat(cypher.writes.getFirst()).contains("canonical_graph_metadata_tenant_unique");
        assertThat(cypher.reads).hasSize(2);
        assertThat(cypher.reads.getFirst()).contains("LIMIT 2");
    }

    @Test
    void writesMergeThatNeverOverwritesProjectionVersion() {
        FakeCypher cypher = new FakeCypher();
        cypher.writeRows = List.of(metadataRow());
        Neo4jCanonicalGraphMetadataStore store = new Neo4jCanonicalGraphMetadataStore(cypher);
        CanonicalGraphMetadata candidate = new CanonicalGraphMetadata(
                "tenant-a", "canonical", "v1.2.0", NOW, NOW, NOW);

        CanonicalGraphMetadata result = store.bootstrapIfAbsent(candidate);

        assertThat(result.projectionVersion()).isEqualTo("v1.2.0");
        String query = cypher.writeReturningQueries.getFirst();
        assertThat(query).contains("MERGE (m:CanonicalGraphMetadata");
        assertThat(query).contains("ON CREATE SET m.graph_id");
        assertThat(query).contains("ON MATCH SET m.updated_at");
        int matchClause = query.indexOf("ON MATCH SET");
        int returnClause = query.indexOf("RETURN m.tenant_id");
        assertThat(query.substring(matchClause, returnClause)).doesNotContain("m.projection_version");
        assertThat(cypher.writeReturningParams.getFirst())
                .containsEntry("projectionVersion", "v1.2.0")
                .containsEntry("syncedAt", NOW.toString());
    }

    @Test
    void mapsConstraintValidationToRetryableRace() {
        FakeCypher cypher = new FakeCypher();
        cypher.writeReturningFailure = new ClientException(
                "Neo.ClientError.Schema.ConstraintValidationFailed", "duplicate metadata");
        Neo4jCanonicalGraphMetadataStore store = new Neo4jCanonicalGraphMetadataStore(cypher);

        assertThatThrownBy(() -> store.bootstrapIfAbsent(new CanonicalGraphMetadata(
                "tenant-a", "canonical", "v1.2.0", NOW, NOW, NOW)))
                .isInstanceOf(MetadataWriteConflictException.class);
    }

    @Test
    void rejectsDuplicateMetadataRows() {
        FakeCypher cypher = new FakeCypher();
        cypher.readRows = List.of(metadataRow(), metadataRow());
        Neo4jCanonicalGraphMetadataStore store = new Neo4jCanonicalGraphMetadataStore(cypher);

        assertThatThrownBy(() -> store.find("tenant-a"))
                .isInstanceOf(AmbiguousCanonicalGraphException.class);
    }

    @Test
    void detectsCanonicalEntityData() {
        FakeCypher cypher = new FakeCypher();
        cypher.readRows = List.of(Map.of("canonical_data", true));
        Neo4jCanonicalGraphMetadataStore store = new Neo4jCanonicalGraphMetadataStore(cypher);

        assertThat(store.hasCanonicalData("tenant-a")).isTrue();
        assertThat(cypher.reads.getFirst()).contains(":Entity");
    }

    private static Map<String, Object> metadataRow() {
        return Map.of(
                "tenant_id", "tenant-a",
                "graph_id", "canonical",
                "projection_version", "v1.2.0",
                "synced_at", NOW.toString(),
                "created_at", NOW.toString(),
                "updated_at", NOW.toString());
    }

    private static final class FakeCypher implements CypherExecutor {
        private final List<String> writes = new ArrayList<>();
        private final List<String> reads = new ArrayList<>();
        private final List<String> writeReturningQueries = new ArrayList<>();
        private final List<Map<String, Object>> writeReturningParams = new ArrayList<>();
        private List<Map<String, Object>> readRows = List.of();
        private List<Map<String, Object>> writeRows = List.of();
        private RuntimeException writeReturningFailure;

        @Override
        public List<Map<String, Object>> read(String cypher, Map<String, Object> params) {
            reads.add(cypher);
            return readRows;
        }

        @Override
        public void write(String cypher, Map<String, Object> params) {
            writes.add(cypher);
        }

        @Override
        public List<Map<String, Object>> writeReturning(String cypher, Map<String, Object> params) {
            writeReturningQueries.add(cypher);
            writeReturningParams.add(params);
            if (writeReturningFailure != null) {
                throw writeReturningFailure;
            }
            return writeRows;
        }
    }
}
