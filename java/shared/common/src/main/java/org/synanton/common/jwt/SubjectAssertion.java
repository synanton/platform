package org.synanton.common.jwt;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Verified identity extracted from a JWT issued by the security module.
 */
public record SubjectAssertion(
        String subject,
        int uid,
        List<Integer> gids,
        String tenantId,
        Instant exp,
        Set<String> roles,
        String identityProfile,
        String assertionId
) {
    public SubjectAssertion {
        gids = gids == null ? List.of() : List.copyOf(gids);
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        identityProfile = identityProfile == null ? "USER_SUBJECT" : identityProfile;
    }

    public SubjectAssertion(String subject, int uid, List<Integer> gids, String tenantId, Instant exp) {
        this(subject, uid, gids, tenantId, exp, Set.of(), "USER_SUBJECT", null);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean isSupportAdmin() {
        return hasRole("support_admin");
    }
}
