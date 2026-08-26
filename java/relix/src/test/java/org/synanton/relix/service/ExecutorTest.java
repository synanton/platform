package org.synanton.relix.service;

import org.jgrapht.graph.DirectedMultigraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synanton.relix.adapter.out.graph.memory.InMemoryGraphConnector;
import org.synanton.relix.api.dto.GraphQueryRequest;
import org.synanton.relix.config.RelixProperties;
import org.synanton.relix.graph.EntityCanonicalizer;
import org.synanton.relix.graph.GraphConnector;
import org.synanton.relix.graph.GraphEdge;
import org.synanton.relix.graph.GraphLoader;
import org.synanton.relix.graph.GraphNode;
import org.synanton.relix.graph.InMemoryConnector;
import org.synanton.relix.index.EntityIndex;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutorTest {

    private GraphConnector connector;
    private GraphNode nodeA;
    private GraphNode nodeB;
    private GraphNode nodeC;
    private GraphNode nodeD;
    private RelixProperties props;

    @BeforeEach
    void setUp() {
        props = new RelixProperties(
                new RelixProperties.Graph(true, "demo", "memory", null, null),
                new RelixProperties.Query(10, 50, 6, 100));
        EntityCanonicalizer canonicalizer = new EntityCanonicalizer();
        DirectedMultigraph<GraphNode, GraphEdge> graph = new DirectedMultigraph<>(GraphEdge.class);
        EntityIndex index = new EntityIndex();
        Map<UUID, GraphNode> nodeById = new LinkedHashMap<>();

        nodeA = new GraphNode(canonicalizer.entityId("t", "Org", "Acme Corp"), "Acme Corp", "Org", 0.9);
        nodeB = new GraphNode(canonicalizer.entityId("t", "Org", "Beta Inc"), "Beta Inc", "Org", 0.85);
        nodeC = new GraphNode(canonicalizer.entityId("t", "Person", "Alice"), "Alice", "Person", 0.95);
        nodeD = new GraphNode(canonicalizer.entityId("t", "Org", "Gamma Ltd"), "Gamma Ltd", "Org", 0.7);

        UUID refId = UUID.randomUUID();
        nodeA.mergeSourceRef(refId, List.of(1, 2));
        nodeB.mergeSourceRef(refId, List.of(3));

        for (GraphNode node : List.of(nodeA, nodeB, nodeC, nodeD)) {
            graph.addVertex(node);
            nodeById.put(node.entityId(), node);
            index.add(node);
        }

        GraphEdge edgeAB = new GraphEdge(canonicalizer.edgeId("t", nodeA.entityId(), "supplies_to", nodeB.entityId()),
                "supplies_to", 0.8);
        GraphEdge edgeBC = new GraphEdge(canonicalizer.edgeId("t", nodeB.entityId(), "employs", nodeC.entityId()),
                "employs", 0.75);
        GraphEdge edgeAC = new GraphEdge(canonicalizer.edgeId("t", nodeA.entityId(), "knows", nodeC.entityId()),
                "knows", 0.65);
        edgeAB.mergeSourceRef(refId, List.of(1));

        graph.addEdge(nodeA, nodeB, edgeAB);
        graph.addEdge(nodeB, nodeC, edgeBC);
        graph.addEdge(nodeA, nodeC, edgeAC);

        var loadResult = new GraphLoader.LoadResult(graph, index, nodeById, 5, System.currentTimeMillis());
        InMemoryGraphConnector memory = new InMemoryGraphConnector(null);
        memory.install("demo", new InMemoryConnector(loadResult));
        connector = memory;
    }

    @Test
    void shouldFindExistingEntityByLabelAndType() {
        EntityLookupExecutor executor = new EntityLookupExecutor();
        var req = new GraphQueryRequest("demo", "entity_lookup",
                Map.of("label", "Acme Corp", "type", "Org", "limit", 10));
        var result = executor.execute(connector, req);
        assertThat(result.entities()).hasSize(1);
        assertThat(result.entities().getFirst().label()).isEqualTo("Acme Corp");
        assertThat(result.entities().getFirst().sourceRefs()).isNotEmpty();
    }

    @Test
    void shouldMatchEntityLookupCaseInsensitively() {
        EntityLookupExecutor executor = new EntityLookupExecutor();
        var req = new GraphQueryRequest("demo", "entity_lookup",
                Map.of("label", "ACME CORP", "type", "Org", "limit", 10));
        var result = executor.execute(connector, req);
        assertThat(result.entities()).hasSize(1);
    }

    @Test
    void shouldReturnEntityWhenTypeFilterOmitted() {
        EntityLookupExecutor executor = new EntityLookupExecutor();
        var req = new GraphQueryRequest("demo", "entity_lookup",
                Map.of("label", "Acme Corp", "limit", 10));
        var result = executor.execute(connector, req);
        assertThat(result.entities()).hasSize(1);
    }

    @Test
    void shouldReturnEmptyWhenLookupLabelMissing() {
        EntityLookupExecutor executor = new EntityLookupExecutor();
        var req = new GraphQueryRequest("demo", "entity_lookup", Map.of("limit", 5));
        var result = executor.execute(connector, req);
        assertThat(result.entities()).isEmpty();
    }

    @Test
    void shouldReturnOutgoingNeighboursForOneHop() {
        OneHopExecutor executor = new OneHopExecutor();
        var req = new GraphQueryRequest("demo", "one_hop",
                Map.of("entity_id", nodeA.entityId().toString(), "direction", "OUT", "limit", 50));
        var result = executor.execute(connector, req);
        assertThat(result.edges()).hasSize(2);
        assertThat(result.entities()).hasSize(2);
    }

    @Test
    void shouldFilterOneHopByEdgeType() {
        OneHopExecutor executor = new OneHopExecutor();
        var req = new GraphQueryRequest("demo", "one_hop",
                Map.of("entity_id", nodeA.entityId().toString(),
                        "direction", "OUT",
                        "edge_types", List.of("supplies_to"),
                        "limit", 50));
        var result = executor.execute(connector, req);
        assertThat(result.edges()).hasSize(1);
        assertThat(result.edges().getFirst().verb()).isEqualTo("supplies_to");
    }

    @Test
    void shouldReturnEmptyOneHopForUnknownEntity() {
        OneHopExecutor executor = new OneHopExecutor();
        var req = new GraphQueryRequest("demo", "one_hop",
                Map.of("entity_id", UUID.randomUUID().toString(), "direction", "OUT", "limit", 50));
        var result = executor.execute(connector, req);
        assertThat(result.edges()).isEmpty();
    }

    @Test
    void shouldFindMultipleKHopPaths() {
        KHopPathExecutor executor = new KHopPathExecutor(props);
        var req = new GraphQueryRequest("demo", "k_hop_path",
                Map.of("from_entity_id", nodeA.entityId().toString(),
                        "to_entity_id", nodeC.entityId().toString(),
                        "max_hops", 4,
                        "max_paths", 10));
        var result = executor.execute(connector, req);
        assertThat(result.paths()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void shouldRespectMaxPathsOnKHop() {
        KHopPathExecutor executor = new KHopPathExecutor(props);
        var req = new GraphQueryRequest("demo", "k_hop_path",
                Map.of("from_entity_id", nodeA.entityId().toString(),
                        "to_entity_id", nodeC.entityId().toString(),
                        "max_hops", 4,
                        "max_paths", 1));
        var result = executor.execute(connector, req);
        assertThat(result.paths()).hasSize(1);
    }

    @Test
    void shouldReturnEmptyKHopWhenUnreachable() {
        KHopPathExecutor executor = new KHopPathExecutor(props);
        var req = new GraphQueryRequest("demo", "k_hop_path",
                Map.of("from_entity_id", nodeA.entityId().toString(),
                        "to_entity_id", nodeD.entityId().toString(),
                        "max_hops", 4,
                        "max_paths", 10));
        var result = executor.execute(connector, req);
        assertThat(result.paths()).isEmpty();
    }
}
