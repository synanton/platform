package org.synanton.relix.syntology;

/** Syntology has no active ontology for the tenant. */
public class OntologyVersionUnavailableException extends RuntimeException {
    public OntologyVersionUnavailableException(String message) {
        super(message);
    }
}
