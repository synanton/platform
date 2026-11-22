package org.synanton.relix.provisioning;

import java.util.Optional;

/** Syntology's active-version/bind surface. */
public interface OntologyProvisioner {
    Optional<String> activeVersion(String tenantId);

    void createVersion(String tenantId, String version, byte[] ttl);
}
