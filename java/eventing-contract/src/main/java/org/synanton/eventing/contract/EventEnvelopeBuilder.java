package org.synanton.eventing.contract;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared wire-shape contract for EWP-1 envelopes, independent of storage. Both the JDBC
 * outbox publisher and non-JDBC producers (Cassandra) construct the envelope through
 * {@link #build} and admit it through {@link #validate}, so envelope construction and the
 * tenant-scope / registered-type-version checks cannot drift between code paths.
 */
public final class EventEnvelopeBuilder {

    private EventEnvelopeBuilder() {
    }

    /** Builds an EWP-1 envelope with a fresh event/correlation id and the given producer and security claim. */
    public static <T> Event<T> build(
            String eventType,
            int eventVersion,
            String tenantId,
            T payload,
            Producer producer,
            ReplayClass replayClass,
            SecurityMetadata security,
            Clock clock) {
        return new Event<>(
                UUID.randomUUID(),
                eventType,
                eventVersion,
                Instant.now(clock),
                producer,
                tenantId,
                Optional.empty(),
                UUID.randomUUID(),
                Optional.empty(),
                replayClass,
                Optional.empty(),
                security,
                payload);
    }

    /** Tenant-scope and registry admission for an already-built envelope. */
    public static void validate(String verifiedTenantId, Event<?> event, EventTypeRegistry registry) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(registry, "registry");
        TenantScope.requireMatches(verifiedTenantId, event);
        registry.validate(event);
    }

    /**
     * Builds an EWP-1 envelope from caller-supplied identity/timing — deterministic
     * event id, correlation/causation, occurred-at — and admits it through the same
     * tenant-scope + registry checks as the generated-id path. Used by producers (e.g.
     * the Cassandra outbox) that own stable ids and traceability links.
     */
    public static <T> Event<T> build(
            UUID eventId,
            String eventType,
            int eventVersion,
            Instant occurredAt,
            String tenantId,
            T payload,
            Producer producer,
            UUID correlationId,
            Optional<UUID> causationId,
            ReplayClass replayClass,
            SecurityMetadata security,
            EventTypeRegistry registry) {
        Event<T> event = new Event<>(
                eventId,
                eventType,
                eventVersion,
                occurredAt,
                producer,
                tenantId,
                Optional.empty(),
                correlationId,
                causationId,
                replayClass,
                Optional.empty(),
                security,
                payload);
        validate(tenantId, event, registry);
        return event;
    }
}
