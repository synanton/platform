package org.synanton.relix.identity;

import java.util.Optional;

public interface CanonicalGraphMetadataStore {

    Optional<CanonicalGraphMetadata> find(String tenantId);

    CanonicalGraphMetadata bootstrapIfAbsent(CanonicalGraphMetadata candidate);

    default ProvisionResult provision(CanonicalGraphMetadata candidate) {
        throw new UnsupportedOperationException("graph metadata store does not support provisioning");
    }

    boolean hasCanonicalData(String tenantId);
}
