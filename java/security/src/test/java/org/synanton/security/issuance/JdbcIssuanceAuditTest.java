package org.synanton.security.issuance;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcIssuanceAuditTest {

    @Test
    void failedInsertIsReportedToCaller() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        JdbcIssuanceAudit audit = new JdbcIssuanceAudit(jdbc);

        assertThatThrownBy(() -> audit.record(new IssuanceEvent(
                "svc", "tenant-a", "jti", "issuer",
                Instant.now(), Instant.now().plusSeconds(900),
                Set.of("content.reconcile"), "issued")))
                .isInstanceOf(IssuanceAuditException.class);
    }
}
