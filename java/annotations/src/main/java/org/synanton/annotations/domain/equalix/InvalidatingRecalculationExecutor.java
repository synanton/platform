package org.synanton.annotations.domain.equalix;

import org.synanton.annotations.domain.resolutor.AnnotationInstanceStore;
import org.synanton.annotations.domain.resolutor.RecalculationWorkItem;

import java.util.UUID;

/**
 * Default executor: supersedes the stale annotation row left by the definition version
 * that actually changed (design §52 - historical facts remain queryable, not deleted).
 *
 * <p>It does <b>not</b> recompute a new value. Producing a new {@code toVersion} row
 * requires calling back into whichever pipeline can regenerate the annotation (e.g.
 * synflux's {@code AnnotationStage} over the target's current content) - wiring that
 * callback is a follow-up, not resolved by this executor. A work item for a
 * transitively-affected downstream definition ({@code fromVersion == null}) is a no-op
 * here for the same reason: its own row is not "the wrong version", it just may need
 * re-derivation because an upstream input changed.
 */
public class InvalidatingRecalculationExecutor implements RecalculationExecutor {

    private final AnnotationInstanceStore instanceStore;

    public InvalidatingRecalculationExecutor(AnnotationInstanceStore instanceStore) {
        this.instanceStore = instanceStore;
    }

    @Override
    public void execute(RecalculationWorkItem item, String tenantId, UUID processingRunId) {
        if (item.fromVersion() == null) {
            return;
        }
        instanceStore.invalidate(tenantId, item.targetType(), item.targetId(), item.definitionId(), item.fromVersion());
    }
}
