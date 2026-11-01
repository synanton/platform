package org.synanton.syntology.domain.event;

/** Payload of an {@code EntityCreated} event: a new entity type was created in the ontology. */
public record EntityCreated(String label) {
}
