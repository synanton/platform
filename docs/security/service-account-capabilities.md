# Service-account capability design

**Status:** Implemented for the content-cache service path and demo
issuance mapping 2026-10-25; production keys/revocation remain #436.
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
    CONTENT_MUTATE_TENANT,
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
    String reason,
    String operationId,
    String traceId) {
    // construction validates the principal and a non-empty, bounded set;
    // capability is checked at execution so "capability absent" can return
    // zero rows instead of throwing. operationId is the retry/idempotency key.
}
```

- `ServicePrincipal` is explicit. It is not inferred from a missing user.
- **Claim mapping is total and closed.** `ServiceCapability.fromClaim` maps each
  emitted claim to a compiled enum constant; `ServicePrincipal.fromClaims` fails
  at issuance for a blank/unknown claim. A typo never becomes a silent
  empty-capability read denial.
  | Claim | Capability |
  |---|---|
  | `content.reconcile` | `CONTENT_RECONCILE_TENANT` |
  | `content.reconcile.cross_tenant` | `CONTENT_RECONCILE_CROSS_TENANT` |
  | `content.mutate` | `CONTENT_MUTATE_TENANT` |
  | `migration.read` | `MIGRATION_READ_TENANT` |
  | `search.reindex` | `SEARCH_REINDEX_TENANT` |
- The token issuer (#340) must produce this same `ServicePrincipal`; a parallel
  principal type would bypass the facade guard.
- Capabilities are code-defined enum constants. A token/config may select a
  subset from the compiled enum; it cannot invent a capability or grant one by
  configuration alone.
- `expiresAt` and `issuer` are mandatory; service capabilities are time-bound.
- No capability implies "all tenants." Cross-tenant is a distinct capability
  and requires `CrossTenantRequest` with listed tenants.
- **No wildcard scope.** `ALL`, `*`, an empty set, and a set larger than
  `MAX_CROSS_TENANT_SCOPE = 100` are rejected at construction. The cap is **per
  request/set**, not on the principal's configured access: a 500-tenant job
  paginates into five bounded calls. A future `CROSS_TENANT_ALL` capability, if
  ever needed, requires a code change, a distinct audit event, and a separate
  review; it is not expressible by the current `CrossTenantRequest`.
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

### Transaction shape (binding)

The implementation uses **N short transactions, one per tenant**, not one long
transaction with N `SET LOCAL` calls. Each iteration:

1. opens a transaction;
2. sets `app.tenant_id` transaction-locally;
3. executes the read;
4. writes the per-tenant audit line in that same transaction;
5. commits before moving to the next tenant.

There is deliberately no cross-tenant snapshot: reconcile does not need
cross-tenant atomicity, and N short transactions keep pool/lock/vacuum
behavior bounded. The audit line for tenant X is in tenant X's transaction, so
a failed audit rolls back that tenant's read and returns no result.

**Buffered read:** the read result is materialized (a count) before the audit
write and commit. This path does not stream rows; streaming would allow the
caller to observe data before the audit failure is known. If streaming is ever
added, the abort guarantee must be revisited.

**Partial success:** with N transactions, tenant A may commit while tenant B
fails. The reconciler continues to collect per-tenant outcomes and throws
`PartialReconcileException` carrying a `TenantOutcome` per tenant (success row
count or failure). The caller retries only failed tenants. Reads are
side-effect-free; audit rows are keyed by
`sha256(principal, capability, tenant, operation, operationId)` where
`operationId` is caller-supplied and stable across retries. No timestamp,
request id, or random value is in the key, so retrying the same
`operationId` does not duplicate the successful tenant's audit line. The test
retries a real partial call and asserts one audit row, not merely the SQL
clause.

**Actionable failure:** `PartialReconcileException.outcomes()` maps each tenant
to `TenantOutcome` (success row count or failure). A capability denial is not a
partial failure: it returns an empty report, not an exception. An oversize set
is rejected at construction, not partially skipped. The intended retry pattern
is to retry the failed tenants (or the whole set); both are safe because the
audit key is stable and the read is side-effect-free.

A future bulk path may reuse one connection across tenants only if it still
enters each tenant's transaction-local scope and keeps the same per-tenant
audit transaction. That optimization is not required for the first job.

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
| Expired service principal | zero rows / empty report, not an error (same anti-oracle response as a missing capability); expiry audited as a denial |
| One tenant fails in an N-transaction reconcile | `PartialReconcileException` with per-tenant `TenantOutcome`; successes remain committed; retry failed tenants with the same `operationId` |
| Unknown capability string in a token | rejected at verification; never mapped to a permissive default |

Zero rows is intentional **for reads**: it is indistinguishable from "no
data" and avoids a tenant-existence oracle. An explicit error is permitted only
where the caller already knows the tenant (the 403 case in the security model).

**Mutations are different.** A silent zero-row no-op on a write or delete is a
data-integrity bug, not a denial. Missing mutation authorization is an explicit
error before any write; the transaction is rolled back with no partial effect.
This design does not define cross-tenant mutations at all.

## Audit

Audit is a security control, not best-effort logging. **For an allowed
operation, the audit write happens in the same transaction as the read, before
commit. If it fails, the operation aborts and no result is returned.** A
database hiccup must not silently disable the only record of cross-tenant
access.

**Granularity:** one audit line per tenant touched, carrying that tenant's row
count. This matches the N-transactions shape and is the useful unit for
forensics. `row_count=0` is recorded when the tenant was in scope but had no
matching rows.

**Denial audit failure:** a request already denied for lack of capability is
different. If its denial-audit write fails, the caller still receives zero
rows, not an error; no data was exposed, and an error would be a side channel.
Allowed operations remain abort-on-audit-failure.

This is the only path where a security event can be silently dropped. The
reconciler and mutation gate increment the Micrometer counter
`audit.denial.write.failure` for every failed denial audit; alert on any
non-zero rate. The same value is exposed as a `LongAdder` for non-Spring
callers. A retry of the same `operationId` is idempotent via the audit key.

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
3. **Multi-tenant positive:** capability present with set `{A, B}` → both
   tenants return rows, proving the loop iterates; one audit line per tenant.
4. **Read-path parity:** the cross-tenant loop's per-tenant read returns the
   same count/data as a single-tenant read through the facade, so the
   cross-tenant path does not drift onto a different read route.
5. **Capability, unlisted tenant:** zero rows for the unlisted tenant.
6. **Mutation denial:** write/delete without the required capability → explicit
   error, no partial write, audit line.
7. **Wildcard/oversize:** `ALL`, `*`, empty, and >100 tenant sets rejected.
8. **Audit failure:** audit write failure aborts an allowed read/mutation; no
   result leaks and no commit occurs.
9. **Per-tenant RLS:** cross-tenant reads use the transaction-local setter per
   tenant; no `SET ROLE`/`BYPASSRLS`/session flag path exists.
10. **Tenant-bound service:** `TenantScope` required; null/blank rejected.
11. **Expired principal:** zero rows, not an error; denial audited.
12. **Config cannot grant:** an unknown capability claim is rejected at
    issuance (`ServicePrincipal.fromClaims`), not mapped to an empty set; a
    configuration-only change cannot add a capability.
13. **No ambient bypass / no second path:** a source test proves service code
    does not construct the repository outside the facade.
14. **Denial-audit failure:** denied request with a failing denial-audit write
    still returns zero rows, not an error; the failure counter increments.
15. **Partial failure/retry:** one tenant's audit fails, `PartialReconcileException`
    carries success/failure per tenant; retry with the same `operationId` does
    not duplicate the successful tenant's audit line.

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

## Resolved in the implementation (2026-10-25)

1. **Facade entry point (item 5).** Service mutations use
   `ServiceContentStore`, a thin wrapper over the same
   `TenantScopedContentStore`; cross-tenant reconcile uses
   `TenantScopedContentStore` directly per tenant. There is no second store
   entry point. `FacadeBoundaryTest` keeps `JdbcContentStore` package-private
   and forbids main-source references outside the facade package. Token
   issuance (#340) produces a `ServicePrincipal`; it does not grant
   capabilities directly.
2. **Store coverage (item 6).** Relational content cache only. Cross-tenant
   service access to search projection/provenance remains out of scope; the
   layer-by-layer acceptance names the stores each run proves.
3. **Transaction shape.** N short transactions, one per tenant; each read and
   its audit row commit together. No cross-tenant snapshot.
4. **Audit.** Same transaction, fail-closed for allowed operations; per-tenant
   line with row count; denial-audit failure still returns zero rows.
5. **Capability bound.** Enumerated set, maximum 100 **per call**; a 500-tenant
   job paginates into five calls and produces 500 per-tenant audit rows. No
   wildcard/ALL.

Additional review questions:

- Capability names and the first positive cross-tenant operation (Lucentrix
  reconcile vs #29 classification).
- Whether the cross-tenant reconcile runs in-process per tenant or as a
  dedicated job; both must use the explicit-set + audit path.
- Where the service principal is issued (#340) and how the compiled capability
  allowlist is distributed.

## Implementation status — 2026-10-25

Implemented in `java/content-cache-postgres`:

- `ServiceCapability`, `ServicePrincipal`, `CrossTenantRequest`,
  `ReconcileReport`, `CrossTenantContentReconciler`, `ServiceContentStore`.
- `TenantScopedContentStore.countByDigestWithAudit` writes the audit row in the
  same transaction as the per-tenant read.
- Content-cache suite: 32 tests. Thirteen service-capability tests cover
  (including closed claim mapping/fail-at-issuance):
  capability-present `{A}` (audit exactly A), capability-absent `{A}` (zero rows,
  not error), multi-tenant `{A,B}`, per-tenant attribution, read-path parity
  (same count; this read has no ordering/pagination), wildcard/oversize
  rejection, mutation denial with no partial write + audit, allowed-read audit
  failure with per-tenant outcome, denial-audit failure returns zero rows and
  increments the counter, mutation-denial audit failure increments its counter,
  expired principal returns zero rows, and partial failure/retry audit
  idempotence.
- Structural `FacadeBoundaryTest` and the existing RLS/facade tests remain.
- Not implemented: HTTP/token issuance (#340), search/provenance cross-tenant
  access, replica topology.
