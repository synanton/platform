package org.synanton.extraction.client;

/**
 * Raised when {@link ExtractionFallbackPolicy#STRUCTURED_REQUIRED} is active and the plane
 * cannot produce a structured payload.
 */
public class StructuredExtractionRequiredException extends RuntimeException {

    public StructuredExtractionRequiredException(String message) {
        super(message);
    }

    public StructuredExtractionRequiredException(String message, Throwable cause) {
        super(message, cause);
    }
}
