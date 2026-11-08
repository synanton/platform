package org.synanton.relix.graph;

import java.util.Optional;
import org.synanton.common.resolution.CanonicalEntityLookup;
import org.synanton.common.resolution.RetryableProcessingException;
import org.springframework.stereotype.Component;

/** Neo4j-backed source-of-truth lookup for resolution candidates. */
@Component
public final class RelixCanonicalEntityLookup implements CanonicalEntityLookup {

    private final GraphConnector connector;

    public RelixCanonicalEntityLookup(GraphConnector connector) {
        this.connector = connector;
    }

    @Override
    public Optional<String> getActiveEntityType(String tenantId, String entityId) {
        try {
            return connector.getActiveEntityType(tenantId, entityId);
        } catch (RuntimeException e) {
            throw new RetryableProcessingException("Canonical entity lookup failed for " + entityId, e);
        }
    }
}
