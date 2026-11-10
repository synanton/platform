package org.synanton.relix.api;

import java.util.List;
import org.synanton.common.tenant.MissingTenantContextException;
import org.synanton.common.tenant.TenantContext;
import org.synanton.relix.api.dto.EntityNeighborhoodResponse;
import org.synanton.relix.graph.EntityNeighborhood;
import org.synanton.relix.graph.GraphConnector;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * #508: the graph UI's bounded traversal contract.
 *
 * <p>Exactly one hop, server-capped at 50 neighbors. There are deliberately no
 * depth/filter/hops parameters; re-root from a clicked neighbor instead. Tenant
 * isolation, traversal limits, and provenance association are backend concerns.
 */
@RestController
public class EntityNeighborhoodController {

    private final GraphConnector graphConnector;

    public EntityNeighborhoodController(GraphConnector graphConnector) {
        this.graphConnector = graphConnector;
    }

    @GetMapping("/v1/entities/{entityId}/neighbors")
    public ResponseEntity<EntityNeighborhoodResponse> neighbors(@PathVariable String entityId) {
        TenantContext context = TenantContext.get();
        if (context == null || context.tenantId() == null || context.tenantId().isBlank()) {
            throw new MissingTenantContextException();
        }
        return graphConnector.neighborhood(context.tenantId(), entityId, 50)
                .map(this::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private EntityNeighborhoodResponse toResponse(EntityNeighborhood neighborhood) {
        EntityNeighborhoodResponse.Node entity = new EntityNeighborhoodResponse.Node(
                neighborhood.entityId(), neighborhood.label(), neighborhood.type());
        List<EntityNeighborhoodResponse.Neighbor> neighbors = neighborhood.neighbors().stream()
                .map(neighbor -> new EntityNeighborhoodResponse.Neighbor(
                        new EntityNeighborhoodResponse.Node(neighbor.entityId(), neighbor.label(),
                                neighbor.type()),
                        neighbor.relation(), neighbor.direction()))
                .toList();
        List<EntityNeighborhoodResponse.Evidence> evidence = neighborhood.evidence().stream()
                .map(item -> new EntityNeighborhoodResponse.Evidence(
                        item.relation(), item.neighborEntityId(), item.chunkId(),
                        item.sourceVersion(), item.offsetStart(), item.offsetEnd()))
                .toList();
        return new EntityNeighborhoodResponse(entity, neighbors, evidence, neighborhood.truncated());
    }
}
