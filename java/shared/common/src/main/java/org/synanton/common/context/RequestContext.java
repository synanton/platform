package org.synanton.common.context;

/**
 * Immutable per-request context carrying tenant, subject, and trace identifiers.
 * Populated by TenantContextFilter and stored in RequestContextHolder.
 */
public record RequestContext(String tenantId, String subjectId, String traceId) {}
