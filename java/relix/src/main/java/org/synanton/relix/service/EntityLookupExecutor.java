package org.synanton.relix.service;

import org.synanton.common.resolution.GraphIdentity;
import org.synanton.relix.graph.GraphConnector;
import org.synanton.relix.graph.GraphReadPort;
import org.springframework.stereotype.Component;

@Component
public class EntityLookupExecutor {

    public GraphConnector.EntityLookupResult execute(GraphReadPort port, GraphIdentity identity,
                                                      org.synanton.relix.api.dto.GraphQueryRequest req) {
        TenantIdentityGuard.requireRequestTenant(req.tenant(), identity);
        String label = req.paramString("label");
        String type = req.paramString("type");
        int limit = req.paramInt("limit", 10);
        return port.lookupEntities(identity, label, type, limit);
    }
}
