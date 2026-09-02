package org.synanton.synflux.annotation;

import java.util.UUID;

/**
 * Port to the {@code annotations} service (AAP-1). Kept as an interface so
 * {@code AnnotationStage} can be unit-tested without a running HTTP dependency.
 */
public interface AnnotationsServiceClient {

    UUID startProcessingRun(
            String producer,
            String producerVersion,
            String tenantId,
            String definitionId,
            Integer definitionVersion,
            String scope
    );

    void completeProcessingRun(UUID processingRunId, String status, String errorSummary);
}
