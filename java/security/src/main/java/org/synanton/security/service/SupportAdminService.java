package org.synanton.security.service;

import org.synanton.common.error.ForbiddenException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

public class SupportAdminService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SupportAdminService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public UUID grantRole(String subjectId, String identityProfile, String source, Integer ttlHours) {
        if ("USER_SUBJECT".equals(identityProfile) && "OIDC".equals(source)) {
            throw new ForbiddenException("OIDC-federated users cannot be assigned support_admin");
        }
        if (!"SERVICE_ACCOUNT".equals(identityProfile) && (ttlHours == null || ttlHours > 24)) {
            throw new ForbiddenException("support_admin requires a service principal or break-glass TTL <= 24h");
        }
        UUID assignmentId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO security.role_assignments
                  (assignment_id, subject_id, role_name, identity_profile, source, ttl_hours, created_at)
                VALUES (?, ?, 'support_admin', ?, ?, ?, ?)
                """,
                assignmentId,
                subjectId,
                identityProfile,
                source,
                ttlHours,
                Instant.now(clock)
        );
        return assignmentId;
    }
}
