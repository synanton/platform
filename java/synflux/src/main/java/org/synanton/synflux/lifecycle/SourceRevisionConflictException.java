package org.synanton.synflux.lifecycle;

/** A connector reused a source revision signal for different source bytes. */
public class SourceRevisionConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SourceRevisionConflictException(String message) {
        super(message);
    }
}
