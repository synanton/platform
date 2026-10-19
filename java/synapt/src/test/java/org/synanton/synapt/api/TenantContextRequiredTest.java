package org.synanton.synapt.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.synanton.common.tenant.MissingTenantContextException;
import org.synanton.common.tenant.TenantContext;
import org.synanton.synapt.domain.SearchRequest;
import org.synanton.synapt.client.GatewayClient;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * A search without a verified tenant context rejects — the removed
 * properties default ("demo") and controller fallback used to hide it.
 */
class TenantContextRequiredTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void searchWithoutVerifiedContextRejects() {
        TenantContext.clear();
        SearchController controller = new SearchController(mock(GatewayClient.class));
        SearchRequest request = new SearchRequest("q", 5, null);
        assertThatThrownBy(() -> controller.search(request, new MockHttpServletRequest()))
                .isInstanceOf(MissingTenantContextException.class);
    }
}
