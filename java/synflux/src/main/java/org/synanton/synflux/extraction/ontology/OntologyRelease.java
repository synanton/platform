package org.synanton.synflux.extraction.ontology;

import java.util.Objects;

/** Immutable ontology release identity resolved for extraction. */
public record OntologyRelease(String releaseId, String releaseDigest) {
    public OntologyRelease {
        Objects.requireNonNull(releaseId, "releaseId");
    }
}
