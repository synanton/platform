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
        Instant createdAt,
        Instant publishedAt,
        int attemptCount,
        String lastError,
        Instant nextAttemptAt,
        boolean deadLettered
) {
    public OutboxRow(
            String tenantId,
            UUID eventId,
            UUID manifestId,
            String transitionFrom,
            String transitionTo,
            String topic,
            String payloadJson,
            boolean published,
            Instant createdAt) {
        this(tenantId, eventId, manifestId, transitionFrom, transitionTo, topic, payloadJson,
            published, createdAt, null, 0, null, null, false);
    }
}
