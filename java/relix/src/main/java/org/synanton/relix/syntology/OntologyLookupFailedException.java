package org.synanton.relix.syntology;

/** Syntology lookup failed transiently; retry is safe. */
public class OntologyLookupFailedException extends RuntimeException {
    public OntologyLookupFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
