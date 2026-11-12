package org.synanton.relix.identity;

import java.util.Optional;

public interface CanonicalGraphMetadataStore {

    Optional<CanonicalGraphMetadata> find(String tenantId);

    CanonicalGraphMetadata bootstrapIfAbsent(CanonicalGraphMetadata candidate);

    boolean hasCanonicalData(String tenantId);
}
