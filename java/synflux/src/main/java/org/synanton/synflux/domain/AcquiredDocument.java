package org.synanton.synflux.domain;

import org.synanton.synvault.domain.ContentRef;

import java.util.UUID;

public record AcquiredDocument(
    ContentRef ref,
    byte[] bytes,
    String sha256,
    String mimeType,
    String sourceUri,
    UUID contentRefId,
    String contentCacheReference
) {
    public AcquiredDocument(
            org.synanton.synvault.domain.ContentRef ref,
            byte[] bytes,
            String sha256,
            String mimeType,
            String sourceUri,
            UUID contentRefId) {
        this(ref, bytes, sha256, mimeType, sourceUri, contentRefId, "");
    }
}
