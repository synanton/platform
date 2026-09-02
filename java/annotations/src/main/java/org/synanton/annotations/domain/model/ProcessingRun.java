package org.synanton.annotations.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A traceable execution instance that produced or updated derived state (design §12-13).
 * Permanent provenance object, subject to retention policy.
 */
public record ProcessingRun(
        UUID processingRunId,
        String producer,
        String producerVersion,
        String tenantId,
        String definitionId,
        Integer definitionVersion,
        String scope,
        Instant startedAt,
        Instant endedAt,
        String status,
        String errorSummary,
        String resourceConsumptionJson
) {
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
}
