package org.synanton.extraction.client;

/**
 * Raised when ingestion requires the Content Extractor and the plane cannot produce a
 * structured payload.
 */
public class StructuredExtractionRequiredException extends RuntimeException {

    public StructuredExtractionRequiredException(String message) {
        super(message);
    }

    public StructuredExtractionRequiredException(String message, Throwable cause) {
        super(message, cause);
    }
}
