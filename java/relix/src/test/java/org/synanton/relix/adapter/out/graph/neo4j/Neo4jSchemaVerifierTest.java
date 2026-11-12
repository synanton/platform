package org.synanton.relix.adapter.out.graph.neo4j;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Neo4jSchemaVerifierTest {

    private final CypherExecutor cypher = mock(CypherExecutor.class);

    @Test
    void acceptsAllRequiredConstraints() {
        when(cypher.read("SHOW CONSTRAINTS YIELD name RETURN collect(name) AS names", Map.of()))
                .thenReturn(List.of(Map.of("names", List.copyOf(
                        Neo4jSchemaVerifier.REQUIRED_CONSTRAINTS))));

        assertThatCode(() -> new Neo4jSchemaVerifier(cypher).verify())
                .doesNotThrowAnyException();
    }

    @Test
    void failsClosedWhenFenceConstraintIsMissing() {
        when(cypher.read("SHOW CONSTRAINTS YIELD name RETURN collect(name) AS names", Map.of()))
                .thenReturn(List.of(Map.of("names", List.of(
                        "canonical_relation_anchor_unique", "entity_identity_unique"))));

        assertThatThrownBy(() -> new Neo4jSchemaVerifier(cypher).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("source_revision_fence_unique");
    }
}
