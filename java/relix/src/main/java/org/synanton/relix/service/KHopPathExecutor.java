package org.synanton.relix.service;

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
        String tenant = req.tenant() != null ? req.tenant() : "demo";
        UUID fromId = req.paramUuid("from_entity_id");
        UUID toId = req.paramUuid("to_entity_id");
        int maxHops = Math.min(req.paramInt("max_hops", 4), props.query().kHopMaxHopsCap());
        int maxPaths = Math.min(req.paramInt("max_paths", 10), props.query().kHopMaxPathsCap());
        return connector.kHopPaths(tenant, fromId, toId, maxHops, maxPaths);
    }
}
