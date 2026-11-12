package org.synanton.relix.service;

import org.synanton.common.tenant.MissingTenantContextException;
import org.synanton.common.tenant.TenantContext;
import org.synanton.common.tenant.TenantIdentityMismatchException;
import org.synanton.relix.api.dto.GraphQueryRequest;
import org.synanton.relix.config.RelixProperties;
import org.synanton.relix.graph.GraphConnector;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class KHopPathExecutor {

    private final RelixProperties props;

    public KHopPathExecutor(RelixProperties props) {
        this.props = props;
    }

    public GraphConnector.KHopResult execute(GraphConnector connector, GraphQueryRequest req) {
        String tenant = req.tenant();
        if (tenant == null || tenant.isBlank()) {
            throw new MissingTenantContextException("k_hop_path requires an explicit tenant");
        }
        TenantContext context = TenantContext.get();
        if (context != null && context.tenantId() != null && !context.tenantId().isBlank()
                && !context.tenantId().equals(tenant)) {
            throw new TenantIdentityMismatchException(
                    "k_hop_path tenant does not match the verified identity");
        }
        UUID fromId = req.paramUuid("from_entity_id");
        UUID toId = req.paramUuid("to_entity_id");
        int maxHops = Math.min(req.paramInt("max_hops", 4), props.query().kHopMaxHopsCap());
        int maxPaths = Math.min(req.paramInt("max_paths", 10), props.query().kHopMaxPathsCap());
        return connector.kHopPaths(tenant, fromId, toId, maxHops, maxPaths);
    }
}
