package org.synanton.relix.syntology;

/** Syntology rejected the service identity or tenant scope. */
public class SyntologyAuthException extends RuntimeException {
    public SyntologyAuthException(String message) {
        super(message);
    }
}
