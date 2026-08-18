package org.synanton.relix.service;

import org.jgrapht.graph.DirectedMultigraph;
import org.synanton.relix.api.dto.*;
import org.synanton.relix.graph.GraphEdge;
import org.synanton.relix.graph.GraphNode;
import org.synanton.relix.graph.InMemoryConnector;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class OneHopExecutor {

    public record Result(List<Entity> entities, List<Edge> edges, int candidateCount) {}

    public Result execute(InMemoryConnector connector, GraphQueryRequest req) {
        UUID entityId = req.paramUuid("entity_id");
        List<String> edgeTypes = req.paramStringList("edge_types");
        String direction = req.paramString("direction");
        int limit = req.paramInt("limit", 50);
        if (direction == null) direction = "OUT";

        GraphNode center = connector.nodeById().get(entityId);
        if (center == null) return new Result(List.of(), List.of(), 0);

        DirectedMultigraph<GraphNode, GraphEdge> graph = connector.graph();
        Set<GraphEdge> rawEdges = new LinkedHashSet<>();

        if ("OUT".equalsIgnoreCase(direction) || "BOTH".equalsIgnoreCase(direction)) {
            rawEdges.addAll(graph.outgoingEdgesOf(center));
        }
        if ("IN".equalsIgnoreCase(direction) || "BOTH".equalsIgnoreCase(direction)) {
            rawEdges.addAll(graph.incomingEdgesOf(center));
        }

        // Filter by edge types if provided
        if (!edgeTypes.isEmpty()) {
            rawEdges.removeIf(e -> !edgeTypes.contains(e.verb()));
        }

        int candidateCount = rawEdges.size();
        List<Edge> edges = new ArrayList<>();
        Set<GraphNode> neighbourNodes = new LinkedHashSet<>();

        rawEdges.stream().limit(limit).forEach(e -> {
            GraphNode source = graph.getEdgeSource(e);
            GraphNode target = graph.getEdgeTarget(e);
            edges.add(DtoMapper.toDto(e, source.entityId(), target.entityId()));
            neighbourNodes.add(source == center ? target : source);
        });

        List<Entity> entities = neighbourNodes.stream()
                .map(DtoMapper::toDto)
                .collect(Collectors.toList());

        return new Result(entities, edges, candidateCount);
    }
}
