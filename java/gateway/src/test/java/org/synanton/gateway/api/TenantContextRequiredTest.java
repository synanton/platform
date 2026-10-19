package org.synanton.gateway.api;

import org.junit.jupiter.api.Test;
import org.synanton.common.tenant.MissingTenantContextException;
import org.synanton.gateway.domain.QueryRequest;
import org.synanton.gateway.service.QueryService;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * No body tenant and no context attribute means no tenant — reject, never
 * "demo". The opt-in mock (synanton.mock-tenant.enabled) is the only demo
 * path, and it rejects on a missing header too.
 */
class TenantContextRequiredTest {

    @Test
    void queryWithoutTenantOrContextRejects() {
        QueryController controller = new QueryController(mock(QueryService.class));
        QueryRequest request = new QueryRequest(null, "q", null, null);
        assertThatThrownBy(() -> controller.query(request, new MockHttpServletRequest()))
                .isInstanceOf(MissingTenantContextException.class);
    }
}
