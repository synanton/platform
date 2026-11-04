package org.synanton.eventing.contract;

import java.util.Objects;

/**
 * Shared wire-shape admission for every EWP-1 envelope, independent of storage. Both the
 * JDBC outbox publisher and non-JDBC producers (Cassandra) route through this so the
 * tenant-scope and registered-type/version checks cannot drift between code paths.
 */
public final class EventEnvelopeValidator {

    private EventEnvelopeValidator() {
    }

    public static void validate(String verifiedTenantId, Event<?> event, EventTypeRegistry registry) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(registry, "registry");
        TenantScope.requireMatches(verifiedTenantId, event);
        registry.validate(event);
    }
}
