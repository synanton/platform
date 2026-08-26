package org.synanton.relix.adapter.out.graph.neo4j;

import org.jgrapht.graph.DirectedMultigraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.synanton.relix.graph.EntityCanonicalizer;
import org.synanton.relix.graph.GraphEdge;
import org.synanton.relix.graph.GraphLoader;
import org.synanton.relix.graph.GraphNode;
import org.synanton.relix.index.EntityIndex;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Neo4jGraphConnectorTest {

    @Mock
    private GraphLoader graphLoader;
    @Mock
    private CypherExecutor cypher;

    @Test
    void shouldHydrateTenantWithParameterizedUnwindCypher() {
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
        when(graphLoader.load("demo")).thenReturn(
                new GraphLoader.LoadResult(graph, index, nodeById, 1, 99));

        Neo4jGraphConnector connector = new Neo4jGraphConnector(graphLoader, cypher);
        connector.loadTenant("demo");

        ArgumentCaptor<String> cypherCaptor = ArgumentCaptor.forClass(String.class);
        verify(cypher, times(3)).write(cypherCaptor.capture(), anyMap());
        assertThat(cypherCaptor.getAllValues().getFirst()).contains("DETACH DELETE");
        assertThat(cypherCaptor.getAllValues().get(1)).contains("UNWIND $nodes");
        assertThat(cypherCaptor.getAllValues().get(2)).contains("UNWIND $edges");
        assertThat(connector.stats("demo").entityCount()).isEqualTo(2);
        assertThat(connector.stats("demo").edgeCount()).isEqualTo(1);
        assertThat(connector.id()).isEqualTo("neo4j");
    }

    @Test
    void shouldMapLookupRowsToEntities() {
        UUID entityId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(cypher.read(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "id", entityId.toString(),
                "label", "Acme Corp",
                "type", "Org",
                "confidence", 0.9,
                "sources", "")));
        Neo4jGraphConnector connector = new Neo4jGraphConnector(graphLoader, cypher);
        var result = connector.lookupEntities("demo", "Acme Corp", "Org", 10);
        assertThat(result.candidateCount()).isEqualTo(1);
        assertThat(result.entities().getFirst().entityId()).isEqualTo(entityId);
        assertThat(result.entities().getFirst().label()).isEqualTo("Acme Corp");
    }

    @Test
    void shouldBoundVariableLengthPathInCypher() {
        when(cypher.read(anyString(), any())).thenReturn(List.of());
        Neo4jGraphConnector connector = new Neo4jGraphConnector(graphLoader, cypher);
        connector.kHopPaths("demo", UUID.randomUUID(), UUID.randomUUID(), 99, 5);
        ArgumentCaptor<String> cypherCaptor = ArgumentCaptor.forClass(String.class);
        verify(cypher).read(cypherCaptor.capture(), anyMap());
        assertThat(cypherCaptor.getValue()).contains("[:REL*1..6]");
        assertThat(cypherCaptor.getValue()).doesNotContain("99");
    }
}
