package org.synanton.extraction.engine;

import java.util.Set;

/** Tenant/document scope and the entity types the ontology allows. */
public record ExtractionContext(
        String tenantId,
        String documentId,
        Set<String> allowedEntityTypes,
        String sourceVersionId) {

    public ExtractionContext(String tenantId, String documentId, Set<String> allowedEntityTypes) {
        this(tenantId, documentId, allowedEntityTypes, null);
    }
}
