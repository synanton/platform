package org.synanton.storage.contract;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Validated security and tenant context consumed by every {@code SynvaultStore} and
 * {@code SynquestEngine} operation (Architecture 1.0 invariants 5–6; proposal §8.4).
 *
 * <p>Instances originate from Identity 1.29 validation at the trust boundary. They are
 * explicit parameters — never ambient state — and must not weaken across asynchronous
 * boundaries (invariant 5): relays propagate the same instance (or a re-validated
 * equivalent), they never broaden it.
 *
 * @param tenantScope                  validated tenant scope
 * @param principals                   principals authorizing this call; non-empty
 * @param policy                       policy reference in force for this call
 * @param requireExplicitAuthorization when true, unproven eligibility is a hard
 *                                     failure rather than an empty result
 * @param service                      true only for service-level contexts (e.g. an
 *                                     approved cross-tenant relay exception per §12.2)
 */
public record SecurityContext(
        TenantScope tenantScope,
        List<PrincipalRef> principals,
        PolicyContext policy,
        boolean requireExplicitAuthorization,
        boolean service) {

    public SecurityContext {
        Objects.requireNonNull(tenantScope, "tenantScope");
        Objects.requireNonNull(principals, "principals");
        Objects.requireNonNull(policy, "policy");
        if (principals.isEmpty()) {
            throw new IllegalArgumentException("principals must not be empty");
        }
        principals = List.copyOf(principals);
    }

    /** Single-user context scoped to one tenant. */
    public static SecurityContext user(TenantScope tenantScope, PrincipalRef user, PolicyContext policy) {
        return new SecurityContext(tenantScope, List.of(user), policy, true, false);
    }

    /** Service-level context (audited; see §12.2 exception path). */
    public static SecurityContext service(
            TenantScope tenantScope, PrincipalRef serviceIdentity, PolicyContext policy) {
        if (!serviceIdentity.isService()) {
            throw new IllegalArgumentException("service contexts require a service principal");
        }
        return new SecurityContext(tenantScope, List.of(serviceIdentity), policy, true, true);
    }

    /** Scope this context down to a single tenant; broadening is not permitted. */
    public SecurityContext narrowTo(TenantScope scope) {
        if (!scope.equals(tenantScope)) {
            throw new SecurityException(
                    "SecurityContext may only narrow to its own tenant scope, requested: " + scope);
        }
        return this;
    }

    public Optional<PrincipalRef> primaryPrincipal() {
        return principals.stream().findFirst();
    }
}
