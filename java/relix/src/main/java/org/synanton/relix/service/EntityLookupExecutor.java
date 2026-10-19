package org.synanton.relix.service;

import org.synanton.common.tenant.MissingTenantContextException;
import org.synanton.relix.graph.GraphConnector;
import org.springframework.stereotype.Component;

@Component
public class EntityLookupExecutor {

    public GraphConnector.EntityLookupResult execute(GraphConnector connector, org.synanton.relix.api.dto.GraphQueryRequest req) {
        String tenant = req.tenant();
        if (tenant == null || tenant.isBlank()) {
            throw new MissingTenantContextException();
        }
        String label = req.paramString("label");
        String type = req.paramString("type");
        int limit = req.paramInt("limit", 10);
        return connector.lookupEntities(tenant, label, type, limit);
    }
}
