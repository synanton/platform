package org.synanton.ingestioncache.domain;

import java.util.Objects;

/** Pinned ontology release identity used for an extraction request/generation. */
public record OntologyReleaseRef(String releaseId, String releaseDigest) {
    public OntologyReleaseRef {
        Objects.requireNonNull(releaseId, "releaseId");
    }
}
