package org.synanton.relix.reconciliation;

/** Single mention/resolution identity for one reconciliation observation. */
public record ResolutionKey(String tenantId, String sourceVersionId, String mentionId,
                            String resolutionId) {}
