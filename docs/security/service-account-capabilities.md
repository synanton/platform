# Service-account capability design

**Status:** Proposed 2026-10-24 for #339 review. No implementation yet.
**Scope:** platform authorization and data access for `PrincipalType.SERVICE`;
issuance is #340, identity resolution is #341.
**Related:** [security model](security-model.md), [tenant-context contract](../api/tenant-context-contract.md), #339, #340, #341.

## Decision

Service principals are a **separate authorization axis**, not a way to omit
tenant scope. A service principal carries an explicit, compiled capability set.
Tenant scope is still required for every tenant-bound operation. Cross-tenant
work is a **named capability with an explicit tenant set**, audited per tenant.
There is no `tenant == null` system mode.

The pattern below is rejected:

```java
if (tenantScope == null) { /* system account — skip RLS */ }
```

That is an unauthenticated superuser and will not be accepted in review.

## Model

```java
enum PrincipalType { USER, SERVICE, WORKLOAD, CONNECTOR, AGENT, SYSTEM }

enum ServiceCapability {
    CONTENT_RECONCILE_TENANT,
    CONTENT_RECONCILE_CROSS_TENANT,
    MIGRATION_READ_TENANT,
    SEARCH_REINDEX_TENANT
}

record ServicePrincipal(
    String principalId,
    Set<ServiceCapability> capabilities,
    Instant expiresAt,
    String issuer) {
    // non-empty principalId; capabilities are the compiled enum, not strings
}

record CrossTenantRequest(
    ServicePrincipal principal,
    Set<TenantScope> tenants,
    String operation,
    String reason) {
    // construction requires CONTENT_RECONCILE_CROSS_TENANT and a non-empty,
    // bounded tenant set; no wildcard tenant
}
```

- `ServicePrincipal` is explicit. It is not inferred from a missing user.
- Capabilities are code-defined enum constants. A token/config may select a
  subset from the compiled enum; it cannot invent a capability or grant one by
  configuration alone.
- `expiresAt` and `issuer` are mandatory; service capabilities are time-bound.
- No capability implies "all tenants." Cross-tenant is a distinct capability
  and requires `CrossTenantRequest` with listed tenants.
- Tenant-bound service calls use the same `TenantScope` path as users:
  `TenantScopedContentStore.forTenant(dataSource, retention, scope)`.

## Legitimate cross-tenant reconcile

Lucentrix reconciliation and #29-style failure classification may need to read
the same resource across tenants. The design supports that without ambient
scope:

```java
interface CrossTenantReconciler {
    ReconcileReport reconcile(CrossTenantRequest request);
}

final class ContentReconciler implements CrossTenantReconciler {
    public ReconcileReport reconcile(CrossTenantRequest request) {
        ServiceAuthorization.require(request.principal(), CONTENT_RECONCILE_CROSS_TENANT);
        for (TenantScope scope : request.tenants()) {
            TenantScopedContentStore store =
                TenantScopedContentStore.forTenant(dataSource, retentionDays, scope);
            // each iteration is a normal tenant-bound read; RLS stays on
            audit.record(request.principal(), CONTENT_RECONCILE_CROSS_TENANT,
                         scope, request.operation(), request.reason());
            reconcileOne(store, scope);
        }
        return report;
    }
}
```

The loop is deliberately per tenant. It does not open a global query, set a
null scope, or bypass RLS. A future bulk path may reuse one connection across
tenants only if it still calls the transaction-local setter per tenant and is
covered by the same audit test; that optimization is not required for the
first reconciliation job.

## Failure semantics

| Condition | Result |
|---|---|
| Service principal without `CONTENT_RECONCILE_CROSS_TENANT` attempts cross-tenant read | zero rows / empty report, not an auth error; denial audited |
| Cross-tenant request with a tenant outside its explicit set | zero rows for that tenant, not an error that reveals existence |
| Tenant-bound service call with no `TenantScope` | rejected (401/`MissingTenantContextException`), never system mode |
| Expired service principal | rejected (401), capabilities not evaluated |
| Unknown capability string in a token | rejected at verification; never mapped to a permissive default |

Zero rows is intentional: it is indistinguishable from "no data" and avoids a
tenant-existence oracle. An explicit error is permitted only where the caller
already knows the tenant (the 403 case in the security model).

## Audit

Every cross-tenant operation records, at minimum:

- principal id and issuer;
- capability;
- operation;
- the explicit tenant set (one line per tenant is acceptable);
- reason;
- outcome and timestamp.

The audit is written even when the read returns zero rows. A capability grant
without a code-defined enum constant is a configuration error, not a grant.

## Tests required before implementation is accepted

1. **No capability, cross-tenant:** service principal without
   `CONTENT_RECONCILE_CROSS_TENANT` → zero rows/empty report, not error.
2. **Capability, listed tenants:** positive reconcile → one audit line per
   tenant, results only from the listed tenants.
3. **Capability, unlisted tenant:** zero rows for the unlisted tenant.
4. **Tenant-bound service:** `TenantScope` required; null/blank rejected.
5. **Expired principal:** rejected before any read.
6. **Config cannot grant:** an unknown capability string is rejected; a
   configuration-only change cannot add a capability.
7. **No ambient bypass:** code search/test proves no `null` tenant branch
   reaches RLS as a system mode.

## Relationship to #339 layers

- **Relational content cache:** `TenantScopedContentStore` + RLS. Service
  principals use the same facade per tenant.
- **Search projection:** per-tenant index/snapshot; a cross-tenant reconcile
  must not use a global search query. Reindex is `SEARCH_REINDEX_TENANT`
  (tenant-bound) or a cross-tenant reconcile with the same explicit-set rule.
- **Provenance:** tenant-scoped reads; cross-tenant reconcile reads one tenant
  at a time and still returns no A data under B.
- **Cache:** tenant-prefixed keys; cross-tenant reconcile does not introduce a
  shared cache namespace.

## Deployment topology note

Read replicas inherit RLS policies, but the connection path must use the same
application role and the same transaction-local `set_config('app.tenant_id',
?, true)` / `SET LOCAL`. A replica pool without the setter returns zero rows
(fail-closed). Do not use an owner/superuser role to avoid RLS overhead.
Acceptance reads from the primary; replica read-your-writes behavior is a
separate consistency decision.

## Open questions for review

- Capability names and the first positive cross-tenant operation (Lucentrix
  reconcile vs #29 classification).
- Whether the cross-tenant reconcile runs in-process per tenant or as a
  dedicated job; both must use the explicit-set + audit path.
- Where the service principal is issued (#340) and how the compiled capability
  allowlist is distributed.
