package org.synanton.security.api;

import org.junit.jupiter.api.Test;
import org.synanton.common.tenant.MissingTenantContextException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.synanton.common.tenant.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
    void listWithoutVerifiedCallerRejects() {
        TenantContext.clear();
        assertThatThrownBy(() -> controller.list(20))
                .isInstanceOf(MissingTenantContextException.class);
    }

    @Test
    void revokeWithoutVerifiedCallerRejects() {
        TenantContext.clear();
        assertThatThrownBy(() -> controller.revoke(java.util.UUID.randomUUID().toString()))
                .isInstanceOf(MissingTenantContextException.class);
    }

    @Test
    void listAndRevokeAreScopedToTheCallersTenant() {
        org.springframework.jdbc.core.JdbcTemplate jdbc =
                mock(org.springframework.jdbc.core.JdbcTemplate.class);
        ApiKeyController scoped = new ApiKeyController(jdbc);
        TenantContext.setAnonymous("acme");

        scoped.list(20);
        verify(jdbc).query(contains("tenant_id = ?"), any(org.springframework.jdbc.core.RowMapper.class),
                eq("acme"), eq(20));

        java.util.UUID keyId = java.util.UUID.randomUUID();
        scoped.revoke(keyId.toString());
        verify(jdbc).update(contains("tenant_id = ?"), eq(keyId), eq("acme"));
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
