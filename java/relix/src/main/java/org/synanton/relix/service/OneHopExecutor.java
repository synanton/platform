package org.synanton.relix.service;

import org.synanton.relix.api.dto.GraphQueryRequest;
import org.synanton.relix.graph.GraphConnector;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class OneHopExecutor {

    public GraphConnector.OneHopResult execute(GraphConnector connector, GraphQueryRequest req) {
        String tenant = req.tenant() != null ? req.tenant() : "demo";
        UUID entityId = req.paramUuid("entity_id");
        List<String> edgeTypes = req.paramStringList("edge_types");
        String direction = req.paramString("direction");
        int limit = req.paramInt("limit", 50);
        return connector.oneHop(tenant, entityId, edgeTypes, direction, limit);
    }
}
