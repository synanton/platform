package org.synanton.synflux.domain;

import org.synanton.synvault.domain.ContentRef;

import java.util.UUID;

public record AcquiredDocument(
    ContentRef ref,
    byte[] bytes,
    String sha256,
    String mimeType,
    String sourceUri,
    UUID contentRefId
) {}
