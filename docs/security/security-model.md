# Synanton security model

**Status:** Canonical shared security contract; implementation status updated 2026-10-21
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
    └── security-provider      — verifies identity; tenant-bound token issuance is planned (#340)

Platform APIs → data layer     — RLS + tenant-scoped repository
```

The client is untrusted. The target contract is that the platform validates a tenant claim and derives scope from it; it never trusts tenant identity from a request header, body, or path. Data-layer enforcement is also required so scope survives application-layer bypasses. The current demo evidence below is narrower than these platform-wide requirements.

## Invariants

1. **Server-derived tenant scope.** The selected tenant travels only as a claim inside a signed access token. Client-supplied tenant identifiers are selectors, never authority. `requested_tenant_id` is an untrusted hint. [ADR 0005, ADR 0007]
2. **Set-shaped identity.** The identity-to-tenant relation is many-to-many at the contract level (`authorizedTenantIds`). Single-tenant is the N=1 case. [#335, #341]
3. **Fail closed.** Missing or invalid tenant context returns 401 or zero rows. Never a default tenant, never a full-table scan.
4. **Anti-oracle error semantics.** 401 for no/invalid token or missing claim; 403 when the claim is no longer authorized; **uniform 404** for selection outside the authorized set; 404 for cross-tenant object access; 400 for tenant in the body or a tenant header. [ADR 0007]
5. **Structural enforcement, not convention.** Boundary lint, branded `TenantId` with a cast guard, RLS + scoped repository, and a PR checklist for semantic rules that cannot be compiled.
6. **Defense in depth.** Token scope is the contract; the data layer is the guarantee; cache keying and tab isolation are additional barriers.
7. **Service accounts are a separate authorization axis.** Operators and service principals are scoped, auditable, and time-bound; they are not tenant members. Cross-tenant work is an explicit capability plus a listed tenant set, never a null tenant. [#339, design](service-account-capabilities.md)
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
| Search, unknown/absent tenant | 404 | (normal not-found) |
| Search, known tenant, projection building | 503 + `Retry-After` | (retryable readiness) |

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

- **Proposed** — under review; not approved.
- **Approved (design)** — architecture decision accepted; not a claim of implementation.
- **Contract frozen** — named contract artifacts are baselined; implementation may still be pending.
- **In progress** — implementation or validation is active under a linked tracker.
- **Implemented** — code exists; scope and tests are stated.
- **Validated** — evidence proves a named path/criteria, with date; not a broader guarantee.
- **Superseded** — useful history retained, with a link to the canonical replacement.

## Current implementation status (2026-10-21)

- **Phase 1 BM25 demo path:** validated and accepted 2026-10-21. Its request boundary rejects `X-Tenant-ID` and body `tenant_id` with 400 (#347, ADR 0007). This is demo-path evidence, not a platform-wide tenant-isolation acceptance.
- **Tenant-bound token issuance:** approved direction Path A; implementation not started (#340; security-provider #3). The current security-provider produces internal HS256 security-context tokens, not browser tenant-bound access tokens/JWKS.
- **Identity 1.29 set-shaped resolution:** approved contract work is tracked by #341; implementation status is owned by that workstream and must not be inferred from demo evidence.
- **Tenant-context contract:** frozen 2026-10-23 in [tenant-context-contract.md](../api/tenant-context-contract.md) (#350): verified `tenant_id` claim is the only authority; missing claim is 401 `TENANT_CONTEXT_MISSING`; API adapters convert once to immutable `TenantScope`; downstream code receives it as a parameter and shares it on the per-query snapshot.
- **Platform data-layer enforcement:** in progress (#339): RLS, transaction-scoped `SET LOCAL`, scoped repositories, cache keying, and permanent isolation/deny tests remain required. #339 consumes the frozen #350 contract. Service-account capabilities are implemented for the content cache in [service-account-capabilities.md](service-account-capabilities.md); no ambient system mode is permitted. Replica RLS topology is proposed in [replica-rls-topology.md](../architecture/replica-rls-topology.md).
- **UI client-side invariants:** implemented and accepted against the conformance double (UI ADRs 0005/0006/0007); this does not establish production issuer or platform data-layer controls.

The authoritative evidence and remaining gaps are linked from [platform #368](https://github.com/synanton/platform/issues/368), [#339](https://github.com/synanton/platform/issues/339), [#340](https://github.com/synanton/platform/issues/340), [#341](https://github.com/synanton/platform/issues/341), and [security-provider #3](https://github.com/synanton/security-provider/issues/3).
