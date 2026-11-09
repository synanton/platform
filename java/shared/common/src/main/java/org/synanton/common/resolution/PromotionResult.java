package org.synanton.common.resolution;

/** Actual persisted counts returned by the promotion transaction. */
public record PromotionResult(int relationsCreated, int evidenceCreated, int evidenceMatched) {}
