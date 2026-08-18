package org.synanton.security.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.synanton.common.error.ForbiddenException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SupportAdminServiceTest {

    @Test
    void shouldRejectOidcUserForSupportAdmin() {
        SupportAdminService service = new SupportAdminService(
                mock(JdbcTemplate.class), Clock.fixed(Instant.parse("2026-08-18T07:00:00Z"), ZoneOffset.UTC));
        assertThatThrownBy(() -> service.grantRole("user:alice", "USER_SUBJECT", "OIDC", 1))
                .isInstanceOf(ForbiddenException.class);
    }
}
