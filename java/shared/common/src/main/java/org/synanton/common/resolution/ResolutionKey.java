package org.synanton.common.resolution;

/**
 * Mention-scoped identity for one reconciliation observation. The graph read is
 * by (tenant, mention); source version is returned in the observation and
 * compared by policy, not used as a query key.
 */
public record ResolutionKey(String tenantId, String mentionId) {}
