package org.synanton.gateway.domain;

public record QueryRequest(
        String tenant,
        String query,
        Integer topK,
        Hints hints
) {
    public record Hints(Boolean preferGraph, Boolean preferRetrieval) {}

    public int effectiveTopK() {
        return topK != null && topK > 0 ? topK : 10;
    }
}
