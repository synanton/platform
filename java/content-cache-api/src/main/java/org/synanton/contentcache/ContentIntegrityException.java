package org.synanton.contentcache;

/** Stored bytes do not match the recorded digest; callers must fail closed. */
public class ContentIntegrityException extends ContentStoreException {
    private static final long serialVersionUID = 1L;

    public ContentIntegrityException(String message) {
        super(message);
    }
}
