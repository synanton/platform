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
- **No wildcard scope.** `ALL`, `*`, an empty set, and a set larger than
  `MAX_CROSS_TENANT_SCOPE = 100` are rejected at construction. Larger jobs are
  split into bounded batches. A future `CROSS_TENANT_ALL` capability, if ever
  needed, requires a code change, a distinct audit event, and a separate review;
  it is not expressible by the current `CrossTenantRequest`.
- Tenant-bound service calls use the same `TenantScope` path as users:
  `TenantScopedContentStore.forTenant(dataSource, retention, scope)`.

## Cross-tenant execution under RLS (binding)

A cross-tenant read is a **bounded loop over the validated tenant set**, with a
per-tenant `TenantScope` and a transaction-local RLS context for each
iteration. RLS remains enabled for every read.

Not acceptable, even as an optimization:

- `SET ROLE` or a `BYPASSRLS` role;
- a session-level flag or a connection that skips `set_config`/`SET LOCAL`;
- a single query that selects across tenants without entering each tenant's
  scope;
- a wildcard/ALL tenant set (see the bound above).

A future bulk path may reuse one connection across tenants only if it still
enters each tenant's transaction-local scope and emits the per-tenant audit
line. That optimization is not required for the first reconciliation job.

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
        if (!ServiceAuthorization.allows(
                request.principal(), CONTENT_RECONCILE_CROSS_TENANT)) {
            audit.denied(request.principal(), CONTENT_RECONCILE_CROSS_TENANT,
                         request.operation(), request.reason());
            return ReconcileReport.empty();   // zero rows, not an auth error
        }
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
null scope, or bypass RLS. The cross-tenant capability authorizes the bounded
loop; it does not authorize a global read.

## Failure semantics

| Condition | Result |
|---|---|
| Service principal without `CONTENT_RECONCILE_CROSS_TENANT` attempts cross-tenant read | zero rows / empty report, not an auth error; denial audited |
| Cross-tenant request with a tenant outside its explicit set | zero rows for that tenant, not an error that reveals existence |
| Service principal without the required mutation capability attempts a write/delete | explicit `ServiceAuthorizationException`; no partial write; audited |
| Cross-tenant mutation (write/delete) requested | rejected explicitly; this design defines no cross-tenant write capability |
| Wildcard/ALL/empty/oversized tenant set | rejected at `CrossTenantRequest` construction |
| Audit write fails | operation aborts; no results returned, no mutation committed |
| Tenant-bound service call with no `TenantScope` | rejected (401/`MissingTenantContextException`), never system mode |
| Expired service principal | rejected (401), capabilities not evaluated |
| Unknown capability string in a token | rejected at verification; never mapped to a permissive default |

Zero rows is intentional **for reads**: it is indistinguishable from "no
data" and avoids a tenant-existence oracle. An explicit error is permitted only
where the caller already knows the tenant (the 403 case in the security model).

**Mutations are different.** A silent zero-row no-op on a write or delete is a
data-integrity bug, not a denial. Missing mutation authorization is an explicit
error before any write; the transaction is rolled back with no partial effect.
This design does not define cross-tenant mutations at all.

## Audit

Audit is a security control, not best-effort logging. **If the audit write
fails, the cross-tenant operation aborts before returning data or committing a
mutation.** A database hiccup must not silently disable the only record of
cross-tenant access.

Every cross-tenant operation records, at minimum:

- principal id and issuer;
- capability;
- operation and reason;
- the requested tenant set and the per-tenant iteration;
- per-tenant row count (including zero);
- outcome (allowed/denied/error) and timestamp;
- trace id.

Denied attempts are audited too. A capability grant without a code-defined enum
constant is a configuration error, not a grant.

## Tests required before implementation is accepted

1. **Capability present, enumerated set:** capability present with tenant set
   `{A}` → reads succeed for A, return zero rows for B, and the audit line
   records exactly `{A}`, not `{A, B}`.
2. **Capability absent, enumerated set:** capability absent with tenant set
   `{A}` → reads return zero rows for A, **not an error**. This is distinct
   from test 1 and prevents the capability from becoming ambient.
3. **No capability, cross-tenant:** service principal without
   `CONTENT_RECONCILE_CROSS_TENANT` → zero rows/empty report, not error.
4. **Capability, unlisted tenant:** zero rows for the unlisted tenant.
5. **Mutation denial:** write/delete without the required capability → explicit
   error, no partial write, audit line.
6. **Wildcard/oversize:** `ALL`, `*`, empty, and >100 tenant sets rejected.
7. **Audit failure:** audit write failure aborts the read/mutation; no result
   leaks and no commit occurs.
8. **Per-tenant RLS:** cross-tenant reads use the transaction-local setter per
   tenant; no `SET ROLE`/`BYPASSRLS`/session flag path exists.
9. **Tenant-bound service:** `TenantScope` required; null/blank rejected.
10. **Expired principal:** rejected before any read.
11. **Config cannot grant:** an unknown capability string is rejected; a
    configuration-only change cannot add a capability.
12. **No ambient bypass:** code search/test proves no `null` tenant branch
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

## Open decisions (must be resolved in the implementation PR)

1. **Facade entry point (item 5).** Intended direction: service principals use
   the same `TenantScopedContentStore` and the facade consults the capability
   set; there is no second store entry point. The exact API (a capability
   parameter vs a service-scoped facade) is decided in the implementation PR.
   Whichever shape wins, the #422 compile-time facade guard must cover it.
2. **Store coverage (item 6).** This design covers the relational content cache
   only. Cross-tenant service access to the search projection or provenance is
   **out of scope** until a separate design covers those stores. A reconcile
   job must not invent a cross-tenant search/provenance path in the meantime.
   The layer-by-layer acceptance will name which stores each run proves.

Additional review questions:

- Capability names and the first positive cross-tenant operation (Lucentrix
  reconcile vs #29 classification).
- Whether the cross-tenant reconcile runs in-process per tenant or as a
  dedicated job; both must use the explicit-set + audit path.
- Where the service principal is issued (#340) and how the compiled capability
  allowlist is distributed.
