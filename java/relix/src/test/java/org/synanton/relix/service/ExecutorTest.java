package org.synanton.relix.service;

import org.jgrapht.graph.DirectedMultigraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synanton.relix.api.dto.*;
import org.synanton.relix.graph.*;
import org.synanton.relix.index.EntityIndex;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class ExecutorTest {

    private InMemoryConnector connector;
    private GraphNode nodeA, nodeB, nodeC, nodeD;
    private GraphEdge edgeAB, edgeBC, edgeAC;

    @BeforeEach
    void setUp() {
        EntityCanonicalizer can = new EntityCanonicalizer();
        DirectedMultigraph<GraphNode, GraphEdge> graph = new DirectedMultigraph<>(GraphEdge.class);
        EntityIndex index = new EntityIndex();
        Map<UUID, GraphNode> nodeById = new LinkedHashMap<>();

        nodeA = new GraphNode(can.entityId("t", "Org", "Acme Corp"), "Acme Corp", "Org", 0.9);
        nodeB = new GraphNode(can.entityId("t", "Org", "Beta Inc"), "Beta Inc", "Org", 0.85);
        nodeC = new GraphNode(can.entityId("t", "Person", "Alice"), "Alice", "Person", 0.95);
        nodeD = new GraphNode(can.entityId("t", "Org", "Gamma Ltd"), "Gamma Ltd", "Org", 0.7);

        UUID refId = UUID.randomUUID();
        nodeA.mergeSourceRef(refId, List.of(1, 2));
        nodeB.mergeSourceRef(refId, List.of(3));

        for (GraphNode n : List.of(nodeA, nodeB, nodeC, nodeD)) {
            graph.addVertex(n);
            nodeById.put(n.entityId(), n);
            index.add(n);
        }

        edgeAB = new GraphEdge(can.edgeId("t", nodeA.entityId(), "supplies_to", nodeB.entityId()),
                "supplies_to", 0.8);
        edgeBC = new GraphEdge(can.edgeId("t", nodeB.entityId(), "employs", nodeC.entityId()),
                "employs", 0.75);
        edgeAC = new GraphEdge(can.edgeId("t", nodeA.entityId(), "knows", nodeC.entityId()),
                "knows", 0.65);
        edgeAB.mergeSourceRef(refId, List.of(1));

        graph.addEdge(nodeA, nodeB, edgeAB);
        graph.addEdge(nodeB, nodeC, edgeBC);
        graph.addEdge(nodeA, nodeC, edgeAC);

        var loadResult = new GraphLoader.LoadResult(graph, index, nodeById, 5, System.currentTimeMillis());
        connector = new InMemoryConnector(loadResult);
    }

    // ---- EntityLookupExecutor ----

    @Test
    void entityLookupFindsExistingEntity() {
        EntityLookupExecutor executor = new EntityLookupExecutor();
        var req = new GraphQueryRequest("demo", "entity_lookup",
                Map.of("label", "Acme Corp", "type", "Org", "limit", 10));
        var result = executor.execute(connector, req);
        assertThat(result.entities()).hasSize(1);
        assertThat(result.entities().get(0).label()).isEqualTo("Acme Corp");
        assertThat(result.entities().get(0).sourceRefs()).isNotEmpty();
    }

    @Test
    void entityLookupCaseInsensitive() {
        EntityLookupExecutor executor = new EntityLookupExecutor();
        var req = new GraphQueryRequest("demo", "entity_lookup",
                Map.of("label", "ACME CORP", "type", "Org", "limit", 10));
        var result = executor.execute(connector, req);
        assertThat(result.entities()).hasSize(1);
    }

    @Test
    void entityLookupNoTypeFilterReturnsAll() {
        EntityLookupExecutor executor = new EntityLookupExecutor();
        var req = new GraphQueryRequest("demo", "entity_lookup",
                Map.of("label", "Acme Corp", "limit", 10));
        var result = executor.execute(connector, req);
        assertThat(result.entities()).hasSize(1);
    }

    @Test
    void entityLookupMissingLabelReturnsEmpty() {
        EntityLookupExecutor executor = new EntityLookupExecutor();
        var req = new GraphQueryRequest("demo", "entity_lookup", Map.of("limit", 5));
        var result = executor.execute(connector, req);
        assertThat(result.entities()).isEmpty();
    }

    // ---- OneHopExecutor ----

    @Test
    void oneHopOutgoingFromA() {
        OneHopExecutor executor = new OneHopExecutor();
        var req = new GraphQueryRequest("demo", "one_hop",
                Map.of("entity_id", nodeA.entityId().toString(), "direction", "OUT", "limit", 50));
        var result = executor.execute(connector, req);
        // nodeA has 2 outgoing edges (to B and C)
        assertThat(result.edges()).hasSize(2);
        assertThat(result.entities()).hasSize(2);
    }

    @Test
    void oneHopEdgeTypeFilter() {
        OneHopExecutor executor = new OneHopExecutor();
        var req = new GraphQueryRequest("demo", "one_hop",
                Map.of("entity_id", nodeA.entityId().toString(),
                       "direction", "OUT",
                       "edge_types", List.of("supplies_to"),
                       "limit", 50));
        var result = executor.execute(connector, req);
        assertThat(result.edges()).hasSize(1);
        assertThat(result.edges().get(0).verb()).isEqualTo("supplies_to");
    }

    @Test
    void oneHopUnknownEntityReturnsEmpty() {
        OneHopExecutor executor = new OneHopExecutor();
        var req = new GraphQueryRequest("demo", "one_hop",
                Map.of("entity_id", UUID.randomUUID().toString(), "direction", "OUT", "limit", 50));
        var result = executor.execute(connector, req);
        assertThat(result.edges()).isEmpty();
    }

    // ---- KHopPathExecutor ----

    @Test
    void kHopFindsPathAThroughBToC() {
        KHopPathExecutor executor = new KHopPathExecutor();
        var req = new GraphQueryRequest("demo", "k_hop_path",
                Map.of("from_entity_id", nodeA.entityId().toString(),
                       "to_entity_id", nodeC.entityId().toString(),
                       "max_hops", 4,
                       "max_paths", 10));
        var result = executor.execute(connector, req);
        // Should find at least 2 paths: A→C direct, and A→B→C
        assertThat(result.paths()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void kHopRespectsMaxPaths() {
        KHopPathExecutor executor = new KHopPathExecutor();
        var req = new GraphQueryRequest("demo", "k_hop_path",
                Map.of("from_entity_id", nodeA.entityId().toString(),
                       "to_entity_id", nodeC.entityId().toString(),
                       "max_hops", 4,
                       "max_paths", 1));
        var result = executor.execute(connector, req);
        assertThat(result.paths()).hasSize(1);
    }

    @Test
    void kHopUnreachableReturnsEmpty() {
        KHopPathExecutor executor = new KHopPathExecutor();
        // D has no incoming/outgoing edges from A
        var req = new GraphQueryRequest("demo", "k_hop_path",
                Map.of("from_entity_id", nodeA.entityId().toString(),
                       "to_entity_id", nodeD.entityId().toString(),
                       "max_hops", 4,
                       "max_paths", 10));
        var result = executor.execute(connector, req);
        assertThat(result.paths()).isEmpty();
    }
}
