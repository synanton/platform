package org.synanton.ingestioncache.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Permanent provenance record of one knowledge-processing execution
 * (Design 1.25 §12): producer, versions, configuration, input/output
 * references, status, and failure details.
 */
public record KnowledgeProcessingRunRow(
    String tenantId,
    UUID processingRunId,
    String producer,
    String producerVersion,
    String configurationJson,
    String inputJson,
    String outputJson,
    String status,
    Instant startedAt,
    Instant completedAt,
    String errorCode,
    String errorMessage
) {
    public boolean terminal() {
        return completedAt != null;
    }
}
