package org.synanton.synvault.security;

import org.junit.jupiter.api.Test;
import org.synanton.common.error.ForbiddenException;
import org.synanton.common.jwt.SubjectAssertion;
import org.synanton.common.tenant.TenantContext;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantScopeGuardTest {

    @Test
    void shouldDenyCrossTenantRead() {
        TenantContext.set(new SubjectAssertion("alice", 1, List.of(), "demo", Instant.now().plusSeconds(60), Set.of(), "USER_SUBJECT", "a1"));
        try {
            assertThatThrownBy(() -> new TenantScopeGuard().check("other"))
                    .isInstanceOf(ForbiddenException.class);
        } finally {
            TenantContext.clear();
        }
    }
}
