package org.synanton.topology.domain.event;

/** Payload of a {@code ClassGrantUpsert} event: a sensitivity-class grant was created. */
public record ClassGrantUpsert(String tenantId, String subjectId, String sensitivityClass, String permission) {
}
