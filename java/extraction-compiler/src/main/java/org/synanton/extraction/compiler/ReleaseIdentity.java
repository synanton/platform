package org.synanton.extraction.compiler;

import java.util.Objects;

/** The immutable release selected through the production Syntology API. */
public record ReleaseIdentity(
    String tenantId,
    String ontologyId,
    String version,
    String releaseId,
    String releaseDigest
) {
    public ReleaseIdentity {
        require(tenantId, "tenantId");
        require(ontologyId, "ontologyId");
        require(version, "version");
        require(releaseId, "releaseId");
        require(releaseDigest, "releaseDigest");
        if (!releaseDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("releaseDigest must be a lowercase SHA-256 hex digest");
        }
    }

    private static void require(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
