package org.synanton.relix.graph;

import java.util.*;

public class GraphEdge {
    private final UUID edgeId;
    private final String verb;
    private double confidence;
    private final Map<UUID, Set<Integer>> sourceRefs = new LinkedHashMap<>();

    public GraphEdge(UUID edgeId, String verb, double confidence) {
        this.edgeId = edgeId;
        this.verb = verb;
        this.confidence = confidence;
    }

    public void mergeSourceRef(UUID contentRefId, List<Integer> ordinals) {
        sourceRefs.computeIfAbsent(contentRefId, k -> new LinkedHashSet<>()).addAll(ordinals);
    }

    public void mergeConfidence(double other) {
        this.confidence = Math.max(this.confidence, other);
    }

    public UUID edgeId() { return edgeId; }
    public String verb() { return verb; }
    public double confidence() { return confidence; }
    public Map<UUID, Set<Integer>> sourceRefs() { return Collections.unmodifiableMap(sourceRefs); }
}
