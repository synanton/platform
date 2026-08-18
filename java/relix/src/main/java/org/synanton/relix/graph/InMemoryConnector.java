package org.synanton.relix.graph;

import org.jgrapht.graph.DirectedMultigraph;
import org.synanton.relix.api.dto.*;
import org.synanton.relix.index.EntityIndex;

import java.util.Map;
import java.util.UUID;

/**
 * §28 SPI - Phase 1 ships only the in-memory connector.
 * Mutations throw UnsupportedOperationException; all patterns are declared NATIVE.
 */
public class InMemoryConnector {

    private final DirectedMultigraph<GraphNode, GraphEdge> graph;
    private final EntityIndex entityIndex;
    private final Map<UUID, GraphNode> nodeById;
    private final long generation;
    private final long loadTimeMs;

    public InMemoryConnector(GraphLoader.LoadResult result) {
        this.graph = result.graph();
        this.entityIndex = result.entityIndex();
        this.nodeById = result.nodeById();
        this.generation = result.generation();
        this.loadTimeMs = result.loadTimeMs();
    }

    public DirectedMultigraph<GraphNode, GraphEdge> graph() { return graph; }
    public EntityIndex entityIndex() { return entityIndex; }
    public Map<UUID, GraphNode> nodeById() { return nodeById; }
    public long generation() { return generation; }
    public long loadTimeMs() { return loadTimeMs; }

    public int entityCount() { return graph.vertexSet().size(); }
    public int edgeCount() { return graph.edgeSet().size(); }

    public void executeBulkMutation(Object mutation) {
        throw new UnsupportedOperationException("InMemoryConnector is read-only in Phase 1");
    }

    public record CostProfile(String connectorType, String patternCoverage) {}

    public CostProfile getEngineDescriptor() {
        return new CostProfile("in-memory-jgrapht", "ALL_NATIVE");
    }
}
