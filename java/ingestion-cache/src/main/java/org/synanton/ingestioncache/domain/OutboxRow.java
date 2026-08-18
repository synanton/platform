package org.synanton.ingestioncache.domain;

import java.time.Instant;
import java.util.UUID;

public record OutboxRow(
        String tenantId,
        UUID eventId,
        UUID manifestId,
        String transitionFrom,
        String transitionTo,
        String topic,
        String payloadJson,
        boolean published,
        Instant createdAt
) {}
