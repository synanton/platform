package org.synanton.relix.adapter.out.metadata;

import org.neo4j.driver.exceptions.ClientException;
import org.synanton.relix.adapter.out.graph.neo4j.CypherExecutor;
import org.synanton.relix.identity.AmbiguousCanonicalGraphException;
import org.synanton.relix.identity.CanonicalGraphMetadata;
import org.synanton.relix.identity.CanonicalGraphMetadataStore;
import org.synanton.relix.identity.MetadataWriteConflictException;
import org.synanton.relix.identity.ProvisionResult;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Neo4jCanonicalGraphMetadataStore implements CanonicalGraphMetadataStore {

    private static final String RETURN_METADATA = """
            RETURN m.tenant_id AS tenant_id, m.graph_id AS graph_id,
                   m.projection_version AS projection_version,
                   toString(m.synced_at) AS synced_at,
                   toString(m.created_at) AS created_at,
                   toString(m.updated_at) AS updated_at
            """;

    private final CypherExecutor cypher;
    private final AtomicBoolean schemaEnsured = new AtomicBoolean();

    public Neo4jCanonicalGraphMetadataStore(CypherExecutor cypher) {
        this.cypher = cypher;
    }

    @Override
    public Optional<CanonicalGraphMetadata> find(String tenantId) {
        ensureSchema();
        List<Map<String, Object>> rows = cypher.read("""
                MATCH (m:CanonicalGraphMetadata {tenant_id: $tenantId})
                """ + RETURN_METADATA + " LIMIT 2",
                Map.of("tenantId", tenantId));
        if (rows.size() > 1) {
            throw new AmbiguousCanonicalGraphException(
                    "Multiple canonical graph metadata nodes for tenant " + tenantId);
        }
        return rows.stream().findFirst().map(this::toMetadata);
    }

    @Override
    public CanonicalGraphMetadata bootstrapIfAbsent(CanonicalGraphMetadata candidate) {
        ensureSchema();
        try {
            List<Map<String, Object>> rows = cypher.writeReturning("""
                    MERGE (m:CanonicalGraphMetadata {tenant_id: $tenantId})
                    ON CREATE SET m.graph_id = $graphId,
                                  m.projection_version = $projectionVersion,
                                  m.synced_at = datetime($syncedAt),
                                  m.created_at = datetime($createdAt),
                                  m.updated_at = datetime($updatedAt)
                    ON MATCH SET m.updated_at = datetime($updatedAt)
                    """ + RETURN_METADATA,
                    Map.of(
                            "tenantId", candidate.tenantId(),
                            "graphId", candidate.graphId(),
                            "projectionVersion", candidate.projectionVersion(),
                            "syncedAt", candidate.syncedAt().toString(),
                            "createdAt", candidate.createdAt().toString(),
                            "updatedAt", candidate.updatedAt().toString()));
            if (!rows.isEmpty()) {
                return toMetadata(rows.getFirst());
            }
        } catch (ClientException e) {
            if (isConstraintConflict(e)) {
                throw new MetadataWriteConflictException("Metadata bootstrap write conflict", e);
            }
            throw e;
        }
        return find(candidate.tenantId()).orElseThrow(() -> new AmbiguousCanonicalGraphException(
                "Metadata bootstrap wrote no readable row for tenant " + candidate.tenantId()));
    }

    @Override
    public ProvisionResult provision(CanonicalGraphMetadata candidate) {
        ensureSchema();
        try {
            List<Map<String, Object>> rows = cypher.writeReturning("""
                    MERGE (m:CanonicalGraphMetadata {tenant_id: $tenantId})
                    ON CREATE SET m.graph_id = $graphId,
                                  m.projection_version = $projectionVersion,
                                  m.synced_at = datetime($syncedAt),
                                  m.created_at = datetime($createdAt),
                                  m.updated_at = datetime($updatedAt)
                    ON MATCH SET m.synced_at = datetime($syncedAt),
                                 m.updated_at = datetime($updatedAt)
                    RETURN m.tenant_id AS tenant_id, m.graph_id AS graph_id,
                           m.projection_version AS projection_version,
                           toString(m.synced_at) AS synced_at,
                           toString(m.created_at) AS created_at,
                           toString(m.updated_at) AS updated_at,
                           (m.created_at = datetime($createdAt)) AS created
                    """, Map.of(
                    "tenantId", candidate.tenantId(),
                    "graphId", candidate.graphId(),
                    "projectionVersion", candidate.projectionVersion(),
                    "syncedAt", candidate.syncedAt().toString(),
                    "createdAt", candidate.createdAt().toString(),
                    "updatedAt", candidate.updatedAt().toString()));
            if (rows.isEmpty()) {
                throw new AmbiguousCanonicalGraphException(
                        "Metadata provisioning wrote no readable row for tenant " + candidate.tenantId());
            }
            Map<String, Object> row = rows.getFirst();
            return new ProvisionResult(toMetadata(row), Boolean.TRUE.equals(row.get("created")));
        } catch (ClientException e) {
            if (isConstraintConflict(e)) {
                throw new MetadataWriteConflictException("Metadata provisioning write conflict", e);
            }
            throw e;
        }
    }

    @Override
    public boolean hasCanonicalData(String tenantId) {
        List<Map<String, Object>> rows = cypher.read("""
                MATCH (e:Entity {tenant: $tenantId})
                RETURN count(e) > 0 AS canonical_data
                """, Map.of("tenantId", tenantId));
        return !rows.isEmpty() && Boolean.TRUE.equals(rows.getFirst().get("canonical_data"));
    }

    private void ensureSchema() {
        if (schemaEnsured.get()) {
            return;
        }
        cypher.write("""
                CREATE CONSTRAINT canonical_graph_metadata_tenant_unique IF NOT EXISTS
                FOR (m:CanonicalGraphMetadata) REQUIRE m.tenant_id IS UNIQUE
                """, Map.of());
        schemaEnsured.set(true);
    }

    private CanonicalGraphMetadata toMetadata(Map<String, Object> row) {
        return new CanonicalGraphMetadata(
                String.valueOf(row.get("tenant_id")),
                String.valueOf(row.get("graph_id")),
                String.valueOf(row.get("projection_version")),
                parseInstant(row.get("synced_at")),
                parseInstant(row.get("created_at")),
                parseInstant(row.get("updated_at")));
    }

    private static Instant parseInstant(Object value) {
        String text = String.valueOf(value);
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return OffsetDateTime.parse(text).toInstant();
        }
    }

    private static boolean isConstraintConflict(ClientException e) {
        return e.code() != null && e.code().contains("ConstraintValidation")
                || e.getMessage() != null && e.getMessage().toLowerCase().contains("constraint");
    }
}
