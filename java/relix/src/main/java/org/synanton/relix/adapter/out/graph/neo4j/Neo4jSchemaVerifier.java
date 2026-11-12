package org.synanton.relix.adapter.out.graph.neo4j;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fail-closed startup check for the Neo4j constraints required by Relix. */
public final class Neo4jSchemaVerifier {

    public static final Set<String> REQUIRED_CONSTRAINTS = Set.of(
            "canonical_relation_anchor_unique",
            "entity_identity_unique",
            "extracted_mention_unique",
            "extracted_relation_unique",
            "relation_evidence_unique",
            "resolution_slot_unique",
            "source_revision_fence_unique");

    private final CypherExecutor cypher;

    public Neo4jSchemaVerifier(CypherExecutor cypher) {
        this.cypher = cypher;
    }

    public void verify() {
        List<Map<String, Object>> rows = cypher.read(
                "SHOW CONSTRAINTS YIELD name RETURN collect(name) AS names", Map.of());
        if (rows.isEmpty()) {
            throw new IllegalStateException("Neo4j is missing all required constraints");
        }
        Object value = rows.getFirst().get("names");
        if (!(value instanceof List<?> names)) {
            throw new IllegalStateException("Neo4j constraint inventory was not readable");
        }
        Set<String> actual = names.stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
        Set<String> missing = new java.util.HashSet<>(REQUIRED_CONSTRAINTS);
        missing.removeAll(actual);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Neo4j is missing required constraints: " + missing);
        }
    }
}
