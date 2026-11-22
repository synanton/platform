package org.synanton.relix.graph;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule 5: Cypher and Neo4j driver/session types stay inside the Neo4j adapter.
 * Narrow wiring/metadata exclusions are deliberate and documented here.
 */
class GraphBoundaryTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final String ADAPTER = "org/synanton/relix/adapter/out/graph/neo4j/";

    @Test
    void neo4jTypesDoNotLeakOutsideTheAdapter() throws Exception {
        List<String> offenders = new ArrayList<>();
        try (var walk = Files.walk(MAIN)) {
            for (Path file : walk.filter(path -> path.toString().endsWith(".java")).toList()) {
                String relative = MAIN.relativize(file).toString().replace('\\', '/');
                if (relative.startsWith(ADAPTER)
                        || relative.equals("org/synanton/relix/config/RelixConfig.java")
                        || relative.equals("org/synanton/relix/adapter/out/metadata/Neo4jCanonicalGraphMetadataStore.java")
                        || relative.equals("org/synanton/relix/adapter/out/metadata/Neo4jSourceFenceStore.java")) {
                    continue;
                }
                String source = Files.readString(file);
                if (source.contains("import org.neo4j")
                        || source.contains("CypherExecutor")
                        || source.contains("org.neo4j.driver")) {
                    offenders.add(relative);
                }
            }
        }
        assertThat(offenders).as("Neo4j/Cypher leakage outside the adapter and documented exclusions")
                .isEmpty();
    }
}
