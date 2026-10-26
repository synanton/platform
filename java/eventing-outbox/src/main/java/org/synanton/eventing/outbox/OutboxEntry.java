package org.synanton.eventing.outbox;

import java.util.Objects;
import java.util.UUID;

/** A durable event awaiting broker dispatch. The event ID is stable across retries. */
public record OutboxEntry(
        UUID eventId,
        String tenantId,
        String eventType,
        String payloadJson,
        int attempts) {

    public OutboxEntry {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(payloadJson, "payloadJson");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must be non-negative");
        }
    }
}
