package org.synanton.common.resolution;

/** Terminal: the source generation is older than the current accepted generation. */
public class StaleGenerationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StaleGenerationException(String message) {
        super(message);
    }
}
