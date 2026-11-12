package org.synanton.relix.service;

import org.synanton.common.resolution.GraphIdentity;
import org.synanton.relix.api.dto.GraphQueryRequest;
import org.synanton.relix.graph.GraphConnector;
import org.synanton.relix.graph.GraphReadPort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class OneHopExecutor {

    public GraphConnector.OneHopResult execute(GraphReadPort port, GraphIdentity identity,
                                                GraphQueryRequest req) {
        TenantIdentityGuard.requireRequestTenant(req.tenant(), identity);
        UUID entityId = req.paramUuid("entity_id");
        List<String> edgeTypes = req.paramStringList("edge_types");
        String direction = req.paramString("direction");
        int limit = req.paramInt("limit", 50);
        return port.oneHop(identity, entityId, edgeTypes, direction, limit);
    }
}
