package org.synanton.relix.adapter.out.graph.neo4j;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Neo4jSchemaManifestConsistencyTest {

    private static final Path NEO4J_MANIFEST =
            Path.of("../../deployments/homelab/k8s/local-real-demo/neo4j.yaml");

    @Test
    void readinessAndInitManifestCoverEveryRequiredConstraint() throws Exception {
        String manifest = Files.readString(NEO4J_MANIFEST);
        assertThat(manifest).contains("schema-init");
        assertThat(manifest).doesNotContain("publishNotReadyAddresses");
        for (String constraint : Neo4jSchemaVerifier.REQUIRED_CONSTRAINTS) {
            assertThat(manifest).as("Neo4j manifest must reference %s", constraint)
                    .contains(constraint);
        }
    }
}
