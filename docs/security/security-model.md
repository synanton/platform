# Synanton security model

**Status:** Canonical shared model — 2026-10-20
**Applies to:** `synanton/platform`, `synanton/ui`, `synanton/security-provider`
**Owners:** Platform (enforcement, contracts), Identity 1.29 (set-shaped identity), security-provider (verification/issuance), UI (client state)

## Trust boundaries

```text
Browser (untrusted)
    │  tenant-bound access token (claim carries the selected tenant)
    ▼
Platform APIs (validate claim, derive scope)
    │
    ├── Identity 1.29          — authoritative for authorizedTenantIds
    └── security-provider      — verifies identity; issues tenant-bound tokens

Platform APIs → data layer     — RLS + tenant-scoped repository
```

The client is untrusted. The platform validates the tenant claim and derives scope from it; it never trusts tenant identity from a request header, body, or path. The data layer enforces the scope even if the application layer is bypassed.

## Invariants

1. **Server-derived tenant scope.** The selected tenant travels only as a claim inside a signed access token. Client-supplied tenant identifiers are selectors, never authority. `requested_tenant_id` is an untrusted hint. [ADR 0005, ADR 0007]
2. **Set-shaped identity.** The identity-to-tenant relation is many-to-many at the contract level (`authorizedTenantIds`). Single-tenant is the N=1 case. [#335, #341]
3. **Fail closed.** Missing or invalid tenant context returns 401 or zero rows. Never a default tenant, never a full-table scan.
4. **Anti-oracle error semantics.** 401 for no/invalid token or missing claim; 403 when the claim is no longer authorized; **uniform 404** for selection outside the authorized set; 404 for cross-tenant object access; 400 for tenant in the body or a tenant header. [ADR 0007]
5. **Structural enforcement, not convention.** Boundary lint, branded `TenantId` with a cast guard, RLS + scoped repository, and a PR checklist for semantic rules that cannot be compiled.
6. **Defense in depth.** Token scope is the contract; the data layer is the guarantee; cache keying and tab isolation are additional barriers.
7. **Service accounts are a separate authorization axis.** Operators and service principals are scoped, auditable, and time-bound; they are not tenant members. [#339]
8. **Accepted risks have revisit triggers.** For example, the `sessionStorage` token XSS blast radius is bounded by the N=1 policy; migrate to in-memory + HttpOnly refresh before the policy relaxes to N>1. [ADR 0006]

## Error matrix (authoritative)

| Condition | Response | Code |
|---|---|---|
| No / invalid token | 401 | `AUTHENTICATION_REQUIRED` |
| Valid token, no tenant claim | 401 | `TENANT_CONTEXT_MISSING` |
| Claim no longer authorized | 403 | `TENANT_ACCESS_DENIED` |
| Selection outside authorized set | **uniform 404** | `TENANT_NOT_AVAILABLE` |
| Cross-tenant object access | 404 | (normal not-found) |
| `tenant_id` in request body | 400 | `TENANT_IN_REQUEST_BODY` |
| `X-Tenant-ID` header | 400 | `TENANT_HEADER_NOT_SUPPORTED` |

The anti-oracle row is the one most often missed: "tenant doesn't exist" and "tenant exists but you are not a member" must be identical to an unauthorized caller.

## Ownership

| Concern | Owner |
|---|---|
| Identity model (set-shaped `authorizedTenantIds`) | Identity 1.29 [#341] |
| Identity verification, tenant-bound token issuance | security-provider [#340] |
| Request validation, data-layer enforcement | platform [#339] |
| Client-side state, per-tab token isolation | UI [ADR 0006] |
| Shared protobuf contracts | platform |

"Share contracts, not implementation ownership": each layer consumes the contract of the layer it depends on and does not re-implement its rules.

## Status vocabulary

- **Implemented** — in code and tested.
- **Design fixed** — contract recorded in an ADR; implementation may follow.
- **In progress** — tracked issue, active work.
- **Deferred** — tracked issue, waiting on a named trigger.

## Current status (2026-10-20)

- **Tenant-bound token issuance:** Design fixed (ADR 0007); ownership decided Path A (#340); Phase 1 not started.
- **Identity 1.29 set-shaped resolution:** Design fixed (#341, additive `authorized_tenant_ids`); implementation not started.
- **Platform data-layer enforcement:** Design fixed (#339); implementation not started.
- **UI client-side invariants:** Implemented (ADRs 0005/0006/0007; contract acceptance against the conformance double).
