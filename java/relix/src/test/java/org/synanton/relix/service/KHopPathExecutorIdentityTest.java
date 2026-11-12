package org.synanton.relix.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.synanton.common.tenant.MissingTenantContextException;
import org.synanton.common.tenant.TenantContext;
import org.synanton.common.tenant.TenantIdentityMismatchException;
import org.synanton.relix.api.dto.GraphQueryRequest;
import org.synanton.relix.config.RelixProperties;
import org.synanton.relix.graph.GraphConnector;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class KHopPathExecutorIdentityTest {

    private final GraphConnector connector = mock(GraphConnector.class);
    private final KHopPathExecutor executor = new KHopPathExecutor(new RelixProperties(
            new RelixProperties.Graph(true, "demo", "memory", null, null),
            new RelixProperties.Query(10, 50, 6, 100)));

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void missingTenantFailsBeforeGraphAccess() {
        GraphQueryRequest request = request(null);

        assertThatThrownBy(() -> executor.execute(connector, request))
                .isInstanceOf(MissingTenantContextException.class);
        verifyNoInteractions(connector);
    }

    @Test
    void blankTenantFailsBeforeGraphAccess() {
        GraphQueryRequest request = request(" ");

        assertThatThrownBy(() -> executor.execute(connector, request))
                .isInstanceOf(MissingTenantContextException.class);
        verifyNoInteractions(connector);
    }

    @Test
    void mismatchedTenantFailsBeforeGraphAccess() {
        TenantContext.setAnonymous("tenant-a");

        assertThatThrownBy(() -> executor.execute(connector, request("tenant-b")))
                .isInstanceOf(TenantIdentityMismatchException.class);
        verifyNoInteractions(connector);
    }

    private static GraphQueryRequest request(String tenant) {
        return new GraphQueryRequest(tenant, "k_hop_path",
                Map.of("from_entity_id", UUID.randomUUID().toString(),
                        "to_entity_id", UUID.randomUUID().toString()));
    }
}
