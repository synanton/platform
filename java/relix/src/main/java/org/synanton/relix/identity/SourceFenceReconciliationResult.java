package org.synanton.relix.identity;

import java.time.Instant;

public record SourceFenceReconciliationResult(
        SourceFenceKey key,
        SourceFenceStatus status,
        Long acceptedGeneration,
        Long evaluatedGeneration,
        Long neo4jGeneration,
        Instant observedAt,
        String detail) {}
