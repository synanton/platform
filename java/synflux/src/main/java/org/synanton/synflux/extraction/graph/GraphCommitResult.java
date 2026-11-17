package org.synanton.synflux.extraction.graph;

import java.util.List;
import java.util.Map;

/** Outcome of one graph commit, used for extraction metrics and structured logs. */
public record GraphCommitResult(
        int mentions,
        int accepted,
        int rejected,
        List<String> rejectionCodes,
        String status,
        int candidates,
        Map<String, Integer> rejectionCounts) {

    public GraphCommitResult(int mentions, int accepted, int rejected,
                             List<String> rejectionCodes, String status) {
        this(mentions, accepted, rejected, rejectionCodes, status,
                accepted + rejected, Map.of());
    }
}
