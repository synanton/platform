package org.synanton.relix.service;

import org.synanton.relix.api.dto.*;
import org.synanton.relix.graph.GraphEdge;
import org.synanton.relix.graph.GraphNode;

import java.util.*;
import java.util.stream.Collectors;

final class DtoMapper {

    private DtoMapper() {}

    static Entity toDto(GraphNode node) {
        List<SourceRef> refs = node.sourceRefs().entrySet().stream()
                .map(e -> new SourceRef(e.getKey(), new ArrayList<>(e.getValue())))
                .collect(Collectors.toList());
        return new Entity(node.entityId(), node.label(), node.type(), node.confidence(), refs);
    }

    static Edge toDto(GraphEdge edge, UUID fromId, UUID toId) {
        List<SourceRef> refs = edge.sourceRefs().entrySet().stream()
                .map(e -> new SourceRef(e.getKey(), new ArrayList<>(e.getValue())))
                .collect(Collectors.toList());
        return new Edge(edge.edgeId(), fromId, toId, edge.verb(), edge.confidence(), refs);
    }
}
