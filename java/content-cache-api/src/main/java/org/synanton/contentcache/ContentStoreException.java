package org.synanton.contentcache;

/** Base failure for {@link ContentStore} operations (infrastructure or contract). */
public class ContentStoreException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ContentStoreException(String message) {
        super(message);
    }

    public ContentStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
