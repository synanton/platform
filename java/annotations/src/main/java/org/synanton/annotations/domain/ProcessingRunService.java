package org.synanton.annotations.domain;

import org.synanton.annotations.domain.model.ProcessingRun;
import org.synanton.annotations.domain.repository.ProcessingRunRepository;
import org.synanton.common.error.NotFoundException;
import org.synanton.common.error.ValidationException;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Tracks processing runs - the permanent provenance objects behind every derived-knowledge operation (design §12-13). */
public class ProcessingRunService {

    private final ProcessingRunRepository runs;
    private final Clock clock;

    public ProcessingRunService(ProcessingRunRepository runs, Clock clock) {
        this.runs = runs;
        this.clock = clock;
    }

    public ProcessingRun start(
            String producer,
            String producerVersion,
            String tenantId,
            String definitionId,
            Integer definitionVersion,
            String scope
    ) {
        ProcessingRun run = new ProcessingRun(
                UUID.randomUUID(), producer, producerVersion, tenantId, definitionId, definitionVersion,
                scope, Instant.now(clock), null, ProcessingRun.RUNNING, null, null);
        return runs.insert(run);
    }

    public ProcessingRun complete(UUID processingRunId, String status, String errorSummary, String resourceConsumptionJson) {
        if (!ProcessingRun.SUCCEEDED.equals(status) && !ProcessingRun.FAILED.equals(status)) {
            throw new ValidationException("status must be SUCCEEDED or FAILED, got: " + status);
        }
        runs.complete(processingRunId, status, Instant.now(clock), errorSummary, resourceConsumptionJson);
        return get(processingRunId);
    }

    public ProcessingRun get(UUID processingRunId) {
        return runs.findById(processingRunId)
                .orElseThrow(() -> new NotFoundException("Unknown processing run: " + processingRunId));
    }
}
