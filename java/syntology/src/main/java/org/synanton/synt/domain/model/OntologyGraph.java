package org.synanton.synt.domain.model;

import java.util.List;

public record OntologyGraph(
        List<GraphNode> nodes,
        List<GraphEdge> edges
) {
    public record GraphNode(String id, String label, String type) {
    }

    public record GraphEdge(String source, String target, String label) {
    }
}
