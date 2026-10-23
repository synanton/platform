package org.synanton.eventing.contract;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Validates event instances against the replay declaration registered for their event type. */
public final class EventTypeRegistry {

    private final Map<String, EventTypeDeclaration> declarations;

    public EventTypeRegistry(Collection<EventTypeDeclaration> declarations) {
        Objects.requireNonNull(declarations, "declarations");
        Map<String, EventTypeDeclaration> byName = new HashMap<>();
        for (EventTypeDeclaration declaration : declarations) {
            Objects.requireNonNull(declaration, "declaration");
            if (byName.putIfAbsent(declaration.eventType(), declaration) != null) {
                throw new IllegalArgumentException("event type declarations must be unique");
            }
        }
        this.declarations = Map.copyOf(byName);
    }

    /** Rejects undeclared event types and instances whose replay class differs from the declaration. */
    public void validate(Event<?> event) {
        Objects.requireNonNull(event, "event");
        EventTypeDeclaration declaration = declarations.get(event.eventType());
        if (declaration == null) {
            throw new IllegalArgumentException("event type has no replay declaration");
        }
        if (declaration.replayClass() != event.replayClass()
                || !declaration.replayCondition().equals(event.replayCondition())) {
            throw new IllegalArgumentException("event replay metadata does not match its type declaration");
        }
    }
}
