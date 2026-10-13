package org.synanton.contentcache;

/**
 * Indistinguishable not-found: the artifact is missing <em>or</em> belongs
 * to another tenant (proposal §2.4.3). Callers must not branch on the cause.
 */
public class ContentNotFoundException extends ContentStoreException {
    private static final long serialVersionUID = 1L;

    public ContentNotFoundException(String message) {
        super(message);
    }
}
