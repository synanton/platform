package org.synanton.relix.identity;

public class MissingCanonicalGraphException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MissingCanonicalGraphException(String message) {
        super(message);
    }
}
