package org.synanton.relix.identity;

import org.synanton.common.resolution.GraphIdentity;

import java.util.Optional;

public final class CanonicalGraphIdentityResolver implements GraphIdentityResolver {

    private final CanonicalGraphMetadataStore store;
    private final CanonicalGraphMetadataBootstrap bootstrap;

    public CanonicalGraphIdentityResolver(CanonicalGraphMetadataStore store,
                                          CanonicalGraphMetadataBootstrap bootstrap) {
        this.store = store;
        this.bootstrap = bootstrap;
    }

    @Override
    public GraphIdentity resolveCanonical(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        Optional<CanonicalGraphMetadata> metadata = store.find(tenantId);
        if (metadata.isPresent()) {
            return identity(metadata.get());
        }
        return bootstrap.bootstrap(tenantId);
    }

    private GraphIdentity identity(CanonicalGraphMetadata metadata) {
        if (!"canonical".equals(metadata.graphId())) {
            throw new UnsupportedGraphIdException(
                    "Unsupported graph id for tenant " + metadata.tenantId() + ": "
                            + metadata.graphId());
        }
        return metadata.identity();
    }
}
