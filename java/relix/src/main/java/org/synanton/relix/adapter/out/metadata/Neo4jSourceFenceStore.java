package org.synanton.relix.adapter.out.metadata;

import org.synanton.relix.adapter.out.graph.neo4j.CypherExecutor;
import org.synanton.relix.identity.SourceFenceKey;
import org.synanton.relix.identity.SourceFenceAdvance;
import org.synanton.relix.identity.SourceFenceStore;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class Neo4jSourceFenceStore implements SourceFenceStore {

    private final CypherExecutor cypher;

    public Neo4jSourceFenceStore(CypherExecutor cypher) {
        this.cypher = cypher;
    }

    @Override
    public Optional<Long> read(SourceFenceKey key) {
        List<Map<String, Object>> rows = cypher.read("""
                MATCH (f:SourceRevisionFence {tenant_id: $tenantId, source_id: $sourceId})
                RETURN f.accepted_generation AS generation
                """, Map.of("tenantId", key.tenantId(), "sourceId", key.sourceId()));
        if (rows.isEmpty() || rows.getFirst().get("generation") == null) {
            return Optional.empty();
        }
        return Optional.of(((Number) rows.getFirst().get("generation")).longValue());
    }

    @Override
    public SourceFenceAdvance advance(SourceFenceKey key, long generation) {
        String now = Instant.now().toString();
        String token = java.util.UUID.randomUUID().toString();
        List<Map<String, Object>> rows = cypher.writeReturning("""
                MERGE (f:SourceRevisionFence {tenant_id: $tenantId, source_id: $sourceId})
                ON CREATE SET f.accepted_generation = $generation, f.created_at = datetime($now),
                              f.updated_at = datetime($now), f.creation_token = $token
                ON MATCH SET f.accepted_generation =
                    CASE WHEN coalesce(f.accepted_generation, 0) < $generation
                         THEN $generation ELSE f.accepted_generation END,
                    f.updated_at = datetime($now)
                RETURN f.accepted_generation AS generation,
                       f.creation_token = $token AS created
                """, Map.of("tenantId", key.tenantId(), "sourceId", key.sourceId(),
                "generation", generation, "now", now, "token", token));
        if (rows.isEmpty()) {
            throw new IllegalStateException("Neo4j source fence advance returned no row");
        }
        Map<String, Object> row = rows.getFirst();
        return new SourceFenceAdvance(
                ((Number) row.get("generation")).longValue(),
                Boolean.TRUE.equals(row.get("created")));
    }
}
