package org.synanton.relix.identity;

public class AmbiguousCanonicalGraphException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AmbiguousCanonicalGraphException(String message) {
        super(message);
    }

    public AmbiguousCanonicalGraphException(String message, Throwable cause) {
        super(message, cause);
    }
}
