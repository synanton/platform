package org.synanton.synvault.domain;

import java.time.Instant;

public record ContentRef(
    String scheme,       // "file"
    String uri,          // e.g. file:///demo-data/documents/foo.md
    String mimeType,
    long sizeBytes,
    Instant lastModified
) {}
