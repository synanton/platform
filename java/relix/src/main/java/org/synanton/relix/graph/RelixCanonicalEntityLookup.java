package org.synanton.relix.graph;

import java.util.Optional;
import org.synanton.common.resolution.CanonicalEntityLookup;
import org.synanton.common.resolution.GraphIdentity;
import org.synanton.common.resolution.RetryableProcessingException;
import org.synanton.relix.identity.GraphIdentityResolver;
import org.springframework.stereotype.Component;

/** Neo4j-backed source-of-truth lookup for resolution candidates. */
@Component
public final class RelixCanonicalEntityLookup implements CanonicalEntityLookup {

    private final GraphReadPort readPort;
    private final GraphIdentityResolver identityResolver;

    public RelixCanonicalEntityLookup(GraphReadPort readPort,
                                      GraphIdentityResolver identityResolver) {
        this.readPort = readPort;
        this.identityResolver = identityResolver;
    }

    @Override
    public Optional<String> getActiveEntityType(String tenantId, String entityId) {
        try {
            GraphIdentity identity = identityResolver.resolveCanonical(tenantId);
            return readPort.getActiveEntityType(identity, entityId);
        } catch (RuntimeException e) {
            throw new RetryableProcessingException("Canonical entity lookup failed for " + entityId, e);
        }
    }
}
