package org.synanton.relix.index;

import org.synanton.relix.graph.GraphNode;

import java.util.*;

public class EntityIndex {

    // normalised_label → Set<GraphNode>
    private final Map<String, Set<GraphNode>> byLabel = new HashMap<>();
    // type → List<GraphNode>
    private final Map<String, List<GraphNode>> byType = new HashMap<>();

    public void add(GraphNode node) {
        String key = node.label().toLowerCase().trim();
        byLabel.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(node);
        byType.computeIfAbsent(node.type(), k -> new ArrayList<>()).add(node);
    }

    public Set<GraphNode> lookup(String label, String type) {
        String key = label.toLowerCase().trim();
        Set<GraphNode> candidates = byLabel.getOrDefault(key, Set.of());
        if (type == null || type.isBlank()) return candidates;
        Set<GraphNode> result = new LinkedHashSet<>();
        for (GraphNode n : candidates) {
            if (type.equalsIgnoreCase(n.type())) result.add(n);
        }
        return result;
    }

    public List<GraphNode> byType(String type) {
        return byType.getOrDefault(type, List.of());
    }

    public int size() {
        return byLabel.values().stream().mapToInt(Set::size).sum();
    }
}
