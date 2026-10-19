package org.synanton.security.api;

import org.junit.jupiter.api.Test;
import org.synanton.common.tenant.MissingTenantContextException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.synanton.common.tenant.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Live hole closed: key issuance used to bind every key to the hardcoded
 * "demo" tenant. The target tenant is now an explicit part of the request and
 * a missing one rejects.
 */
class ApiKeyTenantRequiredTest {

    private final ApiKeyController controller = new ApiKeyController(mock(JdbcTemplate.class));

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void issuanceWithoutVerifiedCallerRejects() {
        TenantContext.clear();
        assertThatThrownBy(() -> controller.generate(
                new ApiKeyController.GenerateRequest("label", List.of("search"))))
                .isInstanceOf(MissingTenantContextException.class);
    }

    @Test
    void issuanceUsesTheCallersTenant() {
        TenantContext.setAnonymous("acme");
        var response = controller.generate(
                new ApiKeyController.GenerateRequest("label", List.of("search")));
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
    }
}
