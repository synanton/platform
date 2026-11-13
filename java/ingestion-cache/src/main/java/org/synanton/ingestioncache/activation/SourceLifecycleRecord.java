package org.synanton.ingestioncache.activation;

import java.time.Instant;
import java.util.UUID;

/** Source-level current/pending pointer and operator-visible lifecycle state. */
public record SourceLifecycleRecord(
        String tenantId,
        String sourceId,
        long acceptedGeneration,
        long currentGeneration,
        UUID activeRevisionId,
        UUID pendingRevisionId,
        ActivationState lifecycleState,
        String lastError,
        boolean retryEligible,
        Instant updatedAt) {}
