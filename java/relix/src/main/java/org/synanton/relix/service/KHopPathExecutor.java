package org.synanton.relix.service;

import org.synanton.common.resolution.GraphIdentity;
import org.synanton.relix.api.dto.GraphQueryRequest;
import org.synanton.relix.config.RelixProperties;
import org.synanton.relix.graph.GraphConnector;
import org.synanton.relix.graph.GraphReadPort;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class KHopPathExecutor {

    private final RelixProperties props;

    public KHopPathExecutor(RelixProperties props) {
        this.props = props;
    }

    public GraphConnector.KHopResult execute(GraphReadPort port, GraphIdentity identity,
                                              GraphQueryRequest req) {
        TenantIdentityGuard.requireRequestTenant(req.tenant(), identity);
        UUID fromId = req.paramUuid("from_entity_id");
        UUID toId = req.paramUuid("to_entity_id");
        int maxHops = Math.min(req.paramInt("max_hops", 4), props.query().kHopMaxHopsCap());
        int maxPaths = Math.min(req.paramInt("max_paths", 10), props.query().kHopMaxPathsCap());
        return port.kHopPaths(identity, fromId, toId, maxHops, maxPaths);
    }
}
