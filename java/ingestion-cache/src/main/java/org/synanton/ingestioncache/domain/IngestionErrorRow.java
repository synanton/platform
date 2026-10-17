package org.synanton.ingestioncache.domain;

import java.time.Instant;
import java.util.UUID;

/** RFC 9457/W2 problem detail referenced by a run or manifest entry. */
public record IngestionErrorRow(
    String tenantId,
    UUID errorId,
    String type,
    String title,
    int status,
    String detail,
    String instance,
    String code,
    boolean retryable,
    String requestId,
    String detailsJson,
    Instant occurredAt
) {}
