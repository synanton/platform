package org.synanton.common.resolution;

/** The row already has a terminal promotion outcome; it cannot be overwritten. */
public class TerminalOutcomeImmutableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TerminalOutcomeImmutableException(String message) {
        super(message);
    }
}
