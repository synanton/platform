package org.synanton.synflux.extraction.graph;

import java.util.List;

/** Outcome of one graph commit, used for extraction metrics and structured logs. */
public record GraphCommitResult(
        int mentions,
        int accepted,
        int rejected,
        List<String> rejectionCodes,
        String status) {
}
