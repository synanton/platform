package org.synanton.annotations.domain.model;

import java.time.Instant;

/**
 * A single edge of the annotation dependency DAG (design §10-§11): {@code from}
 * depends on {@code to}, meaning {@code to} must be (re)computed before {@code from}.
 * Distinct from taxonomy - see design §9.
 */
public record DependencyEdge(
        String fromDefinitionId,
        int fromVersion,
        String toDefinitionId,
        int toVersion,
        Instant createdAt
) {
    public DefinitionVersionKey from() {
        return new DefinitionVersionKey(fromDefinitionId, fromVersion);
    }

    public DefinitionVersionKey to() {
        return new DefinitionVersionKey(toDefinitionId, toVersion);
    }
}
