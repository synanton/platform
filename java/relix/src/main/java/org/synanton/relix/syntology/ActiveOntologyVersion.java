package org.synanton.relix.syntology;

import java.time.Instant;

/** Authoritative active ontology version returned by Syntology. */
public record ActiveOntologyVersion(
        String tenantId,
        String activeVersion,
        String versionRef,
        Instant activatedAt) {
}
