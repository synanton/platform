package org.synanton.ingestioncache.domain;

/** A tenant-scoped source ID was reused for a different connector. */
public class SourceIdentityConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SourceIdentityConflictException(String message) {
        super(message);
    }
}
