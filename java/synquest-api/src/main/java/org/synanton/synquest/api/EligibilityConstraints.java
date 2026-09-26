package org.synanton.synquest.api;

import java.util.List;
import java.util.Objects;
import org.synanton.storage.contract.PolicyContext;
import org.synanton.storage.contract.PrincipalRef;
import org.synanton.storage.contract.TenantScope;

/**
 * Security-only eligibility, applied during candidate generation before ranking
 * (proposal §10.2). Never carries temporal fields — those live in
 * {@link TemporalExtension}. A post-ranking implementation is a contract violation.
 */
public record EligibilityConstraints(
        TenantScope tenantScope,
        List<PrincipalRef> principals,
        PolicyContext policy,
        boolean requireExplicitAuthorization) {
    public EligibilityConstraints {
        Objects.requireNonNull(tenantScope, "tenantScope");
        Objects.requireNonNull(policy, "policy");
        principals = principals == null ? List.of() : List.copyOf(principals);
        if (principals.isEmpty()) {
            throw new IllegalArgumentException("principals must not be empty");
        }
    }

    /** Derive the search-side view from an already-validated {@code SecurityContext}. */
    public static EligibilityConstraints from(
            TenantScope tenantScope, List<PrincipalRef> principals, PolicyContext policy) {
        return new EligibilityConstraints(tenantScope, principals, policy, true);
    }
}
