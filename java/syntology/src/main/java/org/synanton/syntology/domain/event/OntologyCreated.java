package org.synanton.syntology.domain.event;

import java.util.UUID;

/** Payload of an {@code OntologyCreated} event: the seed/creation of an ontology version. */
public record OntologyCreated(UUID versionId, String version) {
}
