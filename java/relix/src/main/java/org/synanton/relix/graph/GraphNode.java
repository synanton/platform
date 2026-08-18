package org.synanton.relix.graph;

import java.util.*;

public class GraphNode {
    private final UUID entityId;
    private final String label;
    private final String type;
    private double confidence;
    // accumulated source refs: contentRefId → chunk ordinals
    private final Map<UUID, Set<Integer>> sourceRefs = new LinkedHashMap<>();

    public GraphNode(UUID entityId, String label, String type, double confidence) {
        this.entityId = entityId;
        this.label = label;
        this.type = type;
        this.confidence = confidence;
    }

    public void mergeSourceRef(UUID contentRefId, List<Integer> ordinals) {
        sourceRefs.computeIfAbsent(contentRefId, k -> new LinkedHashSet<>()).addAll(ordinals);
    }

    public void mergeConfidence(double other) {
        this.confidence = Math.max(this.confidence, other);
    }

    public UUID entityId() { return entityId; }
    public String label() { return label; }
    public String type() { return type; }
    public double confidence() { return confidence; }
    public Map<UUID, Set<Integer>> sourceRefs() { return Collections.unmodifiableMap(sourceRefs); }
}
