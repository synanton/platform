package org.synanton.annotations.domain.repository;

import org.synanton.annotations.domain.model.ProcessingRun;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ProcessingRunRepository {

    ProcessingRun insert(ProcessingRun run);

    Optional<ProcessingRun> findById(UUID processingRunId);

    void complete(UUID processingRunId, String status, Instant endedAt, String errorSummary, String resourceConsumptionJson);
}
