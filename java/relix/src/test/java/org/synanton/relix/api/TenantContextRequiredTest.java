package org.synanton.relix.api;

import org.junit.jupiter.api.Test;
import org.synanton.common.tenant.MissingTenantContextException;
import org.synanton.relix.api.dto.GraphQueryRequest;
import org.synanton.relix.graph.GraphConnector;
import org.synanton.relix.service.EntityLookupExecutor;
import org.synanton.relix.service.OneHopExecutor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The demo fallback is gone: a graph operation without a tenant rejects
 * instead of silently querying the "demo" tenant (which returned demo data
 * that looked like the caller's data).
 */
class TenantContextRequiredTest {

    private final GraphConnector connector = mock(GraphConnector.class);

    @Test
    void entityLookupWithoutTenantRejects() {
        GraphQueryRequest request = new GraphQueryRequest(null, "lookup", Map.of("label", "x"));
        assertThatThrownBy(() -> new EntityLookupExecutor().execute(connector, request))
                .isInstanceOf(MissingTenantContextException.class);
    }

    @Test
    void oneHopWithoutTenantRejects() {
        GraphQueryRequest request = new GraphQueryRequest(null, "hop", Map.of());
        assertThatThrownBy(() -> new OneHopExecutor().execute(connector, request))
                .isInstanceOf(MissingTenantContextException.class);
    }
}
