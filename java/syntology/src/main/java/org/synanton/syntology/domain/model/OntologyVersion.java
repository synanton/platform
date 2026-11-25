package org.synanton.syntology.domain.model;

import java.time.Instant;
import java.util.UUID;

public record OntologyVersion(
        UUID versionId,
        String tenantId,
        String version,
        String label,
        String description,
        String graphUri,
        String status,
        Instant createdAt,
        String releaseId,
        String releaseDigest,
        String ontologyId
) {
    public static final String DEFAULT_ONTOLOGY_ID = "supply-chain";

    public OntologyVersion {
        ontologyId = ontologyId == null || ontologyId.isBlank() ? DEFAULT_ONTOLOGY_ID : ontologyId;
    }

    /** Backward-compatible constructor for existing callers/tests. */
    public OntologyVersion(UUID versionId, String tenantId, String version, String label,
                           String description, String graphUri, String status, Instant createdAt,
                           String releaseId, String releaseDigest) {
        this(versionId, tenantId, version, label, description, graphUri, status, createdAt,
                releaseId, releaseDigest, DEFAULT_ONTOLOGY_ID);
    }

    /** Backward-compatible constructor for existing callers/tests. */
    public OntologyVersion(UUID versionId, String tenantId, String version, String label,
                           String description, String graphUri, String status, Instant createdAt) {
        this(versionId, tenantId, version, label, description, graphUri, status, createdAt,
                versionId == null ? null : versionId.toString(), null, DEFAULT_ONTOLOGY_ID);
    }
}
