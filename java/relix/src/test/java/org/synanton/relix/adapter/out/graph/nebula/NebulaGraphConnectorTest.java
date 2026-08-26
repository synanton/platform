package org.synanton.relix.adapter.out.graph.nebula;

import org.jgrapht.graph.DirectedMultigraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.synanton.relix.api.dto.Path;
import org.synanton.relix.graph.EntityCanonicalizer;
import org.synanton.relix.graph.GraphEdge;
import org.synanton.relix.graph.GraphLoader;
import org.synanton.relix.graph.GraphNode;
import org.synanton.relix.index.EntityIndex;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NebulaGraphConnectorTest {

    @Mock
    private GraphLoader graphLoader;

    @Test
    void shouldEmitInsertVertexAndEdgeNgqlOnLoad() {
        EntityCanonicalizer canonicalizer = new EntityCanonicalizer();
        DirectedMultigraph<GraphNode, GraphEdge> graph = new DirectedMultigraph<>(GraphEdge.class);
        EntityIndex index = new EntityIndex();
        Map<UUID, GraphNode> nodeById = new LinkedHashMap<>();
        GraphNode acme = new GraphNode(canonicalizer.entityId("demo", "Org", "Acme"), "Acme", "Org", 0.9);
        GraphNode beta = new GraphNode(canonicalizer.entityId("demo", "Org", "Beta"), "Beta", "Org", 0.8);
        graph.addVertex(acme);
        graph.addVertex(beta);
        nodeById.put(acme.entityId(), acme);
        nodeById.put(beta.entityId(), beta);
        index.add(acme);
        index.add(beta);
        GraphEdge supplies = new GraphEdge(
                canonicalizer.edgeId("demo", acme.entityId(), "supplies_to", beta.entityId()),
                "supplies_to", 0.7);
        graph.addEdge(acme, beta, supplies);
        when(graphLoader.load("demo")).thenReturn(new GraphLoader.LoadResult(graph, index, nodeById, 1, 2));

        RecordingNebulaSession session = new RecordingNebulaSession();
        NebulaGraphConnector connector = new NebulaGraphConnector(graphLoader, session, "synanton");
        connector.loadTenant("demo");

        assertThat(session.executed).anyMatch(sql -> sql.contains("INSERT VERTEX entity"));
        assertThat(session.executed).anyMatch(sql -> sql.contains("INSERT EDGE rel"));
        assertThat(session.executed).anyMatch(sql -> sql.contains("supplies_to"));
        assertThat(connector.id()).isEqualTo("nebula");
    }

    @Test
    void shouldEscapeQuotesInNgqlLiterals() {
        assertThat(NebulaGraphConnector.escape("a\"b")).isEqualTo("a\\\"b");
    }

    @Test
    void shouldMapLookupQueryRows() {
        UUID entityId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        RecordingNebulaSession session = new RecordingNebulaSession();
        session.queryRows = List.of(Map.of(
                "id", entityId.toString(),
                "label", "Acme",
                "type", "Org",
                "confidence", 0.9,
                "sources", ""));
        NebulaGraphConnector connector = new NebulaGraphConnector(null, session, "synanton");
        var result = connector.lookupEntities("demo", "Acme", "Org", 10);
        assertThat(session.queries.getFirst()).contains("LOOKUP ON entity");
        assertThat(result.entities().getFirst().entityId()).isEqualTo(entityId);
    }

    @Test
    void shouldMapKHopRowsFromSession() {
        RecordingNebulaSession session = new RecordingNebulaSession();
        session.queryRows = List.of(Map.of(
                "hops", List.of("a", "e", "b"),
                "score", 0.5,
                "entities", List.of(),
                "edges", List.of()));
        NebulaGraphConnector connector = new NebulaGraphConnector(null, session, "synanton");
        var result = connector.kHopPaths("demo", UUID.randomUUID(), UUID.randomUUID(), 4, 10);
        assertThat(result.paths()).containsExactly(new Path(List.of("a", "e", "b"), 0.5));
        assertThat(session.queries.getFirst()).contains("FIND");
        assertThat(session.queries.getFirst()).contains("OVER rel");
    }

    private static final class RecordingNebulaSession implements NebulaSession {
        private final List<String> executed = new ArrayList<>();
        private final List<String> queries = new ArrayList<>();
        private List<Map<String, Object>> queryRows = List.of();

        @Override
        public void execute(String statement) {
            executed.add(statement);
        }

        @Override
        public List<Map<String, Object>> query(String statement) {
            queries.add(statement);
            return queryRows;
        }
    }
}
