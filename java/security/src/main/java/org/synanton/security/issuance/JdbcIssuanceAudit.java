package org.synanton.security.issuance;

import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Durable issuance audit; a failed insert aborts issuance via the caller. */
public final class JdbcIssuanceAudit implements IssuanceAudit {

    private final JdbcTemplate jdbc;

    public JdbcIssuanceAudit(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void record(IssuanceEvent event) {
        int rows = jdbc.update(
                "INSERT INTO security.issuance_audit"
                        + " (principal_id, tenant_id, jti, issuer, issued_at, expires_at,"
                        + " claimed_capabilities, outcome)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                event.principalId(),
                event.tenantId(),
                event.jti(),
                event.issuer(),
                java.sql.Timestamp.from(event.issuedAt()),
                java.sql.Timestamp.from(event.expiresAt()),
                String.join(" ", event.claimedCapabilities().stream().sorted().toList()),
                event.outcome());
        if (rows != 1) {
            throw new IssuanceAuditException(
                    "issuance audit insert affected " + rows + " rows", null);
        }
    }
}
