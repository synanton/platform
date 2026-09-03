package org.synanton.annotations.domain.equalix;

import org.synanton.annotations.domain.resolutor.RecalculationWorkItem;

import java.util.UUID;

/**
 * Performs one work item's recalculation. Kept as a port so Equalix's scheduling logic
 * is testable independently of what actually recomputes an annotation.
 */
public interface RecalculationExecutor {
    void execute(RecalculationWorkItem item, String tenantId, UUID processingRunId);
}
