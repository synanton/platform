package org.synanton.relix.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.synanton.common.resolution.GraphIdentity;
import org.synanton.common.tenant.MissingTenantContextException;
import org.synanton.common.tenant.TenantContext;
import org.synanton.common.tenant.TenantIdentityMismatchException;
import org.synanton.relix.api.dto.GraphQueryRequest;
import org.synanton.relix.config.RelixProperties;
import org.synanton.relix.graph.GraphReadPort;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class KHopPathExecutorIdentityTest {

    private final GraphReadPort connector = mock(GraphReadPort.class);
    private final GraphIdentity identityA = GraphIdentity.canonical("tenant-a", "v1");
    private final GraphIdentity identityB = GraphIdentity.canonical("tenant-b", "v1");
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

        assertThatThrownBy(() -> executor.execute(connector, identityA, request))
                .isInstanceOf(MissingTenantContextException.class);
        verifyNoInteractions(connector);
    }

    @Test
    void blankTenantFailsBeforeGraphAccess() {
        GraphQueryRequest request = request(" ");

        assertThatThrownBy(() -> executor.execute(connector, identityA, request))
                .isInstanceOf(MissingTenantContextException.class);
        verifyNoInteractions(connector);
    }

    @Test
    void mismatchedTenantFailsBeforeGraphAccess() {
        TenantContext.setAnonymous("tenant-a");

        assertThatThrownBy(() -> executor.execute(connector, identityB, request("tenant-b")))
                .isInstanceOf(TenantIdentityMismatchException.class);
        verifyNoInteractions(connector);
    }

    private static GraphQueryRequest request(String tenant) {
        return new GraphQueryRequest(tenant, "k_hop_path",
                Map.of("from_entity_id", UUID.randomUUID().toString(),
                        "to_entity_id", UUID.randomUUID().toString()));
    }
}
