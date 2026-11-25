package org.synanton.syntology.domain.port.out;

import org.synanton.syntology.domain.model.OntologyVersion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MetadataRepository {

    List<OntologyVersion> findAll(String tenantId);

    Optional<OntologyVersion> findByVersion(String tenantId, String version);

    Optional<OntologyVersion> findActive(String tenantId);

    Optional<OntologyVersion> findActive(String tenantId, String ontologyId);

    void insert(OntologyVersion version);

    void deprecateAllActive(String tenantId);

    void deprecateAllActive(String tenantId, String ontologyId);

    void updateStatus(UUID versionId, String status);

    boolean isEmpty();
}
