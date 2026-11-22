package org.synanton.relix.graph;

import org.synanton.common.resolution.GraphIdentity;
import org.synanton.relix.api.dto.Entity;
import org.synanton.relix.api.dto.GraphStats;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Canonical graph read contract (:Entity/:REL). Every operation requires an
 * explicit GraphIdentity; extraction-pipeline reads belong on
 * ExtractedMentionGraphReadPort.
 */
public interface GraphReadPort {

    Optional<Entity> getEntity(GraphIdentity identity, String entityId);

    GraphConnector.EntityLookupResult lookupEntities(GraphIdentity identity, String label,
                                                     String type, int limit);

    GraphConnector.OneHopResult oneHop(GraphIdentity identity, UUID entityId, List<String> edgeTypes,
                                       String direction, int limit);

    GraphConnector.KHopResult kHopPaths(GraphIdentity identity, UUID fromEntityId, UUID toEntityId,
                                        int maxHops, int maxPaths);

    Optional<String> getActiveEntityType(GraphIdentity identity, String entityId);

    Optional<EntityNeighborhood> neighborhood(GraphIdentity identity, String entityId, int limit);

    GraphStats stats(GraphIdentity identity);
}
