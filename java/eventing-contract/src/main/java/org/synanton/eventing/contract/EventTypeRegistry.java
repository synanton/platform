package org.synanton.eventing.contract;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Validates event instances against the replay declaration registered for their type and version. */
public final class EventTypeRegistry {

    private final Map<EventSchemaVersion, EventTypeDeclaration> declarations;

    public EventTypeRegistry(Collection<EventTypeDeclaration> declarations) {
        Objects.requireNonNull(declarations, "declarations");
        Map<EventSchemaVersion, EventTypeDeclaration> byVersion = new HashMap<>();
        Map<String, ReplayPolicy> policiesByType = new HashMap<>();
        for (EventTypeDeclaration declaration : declarations) {
            Objects.requireNonNull(declaration, "declaration");
            EventSchemaVersion key = new EventSchemaVersion(declaration.eventType(), declaration.eventVersion());
            if (byVersion.putIfAbsent(key, declaration) != null) {
                throw new IllegalArgumentException("event type/schema-version declarations must be unique");
            }
            ReplayPolicy policy = new ReplayPolicy(declaration.replayClass(), declaration.replayCondition());
            ReplayPolicy previousPolicy = policiesByType.putIfAbsent(declaration.eventType(), policy);
            if (previousPolicy != null && !previousPolicy.equals(policy)) {
                throw new IllegalArgumentException("replay policy must remain constant across versions of an event type");
            }
        }
        this.declarations = Map.copyOf(byVersion);
    }

    /** Rejects undeclared event types and instances whose replay class differs from the declaration. */
    public void validate(Event<?> event) {
        Objects.requireNonNull(event, "event");
        EventTypeDeclaration declaration = declarations.get(
                new EventSchemaVersion(event.eventType(), event.eventVersion()));
        if (declaration == null) {
            throw new IllegalArgumentException("event type/schema version is not registered");
        }
        if (declaration.replayClass() != event.replayClass()
                || !declaration.replayCondition().equals(event.replayCondition())) {
            throw new IllegalArgumentException("event replay metadata does not match its type declaration");
        }
    }

    private record EventSchemaVersion(String eventType, int eventVersion) {
    }

    private record ReplayPolicy(ReplayClass replayClass, Optional<String> replayCondition) {
    }
}
