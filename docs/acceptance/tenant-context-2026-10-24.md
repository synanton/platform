# Tenant-context contract and two-tenant acceptance — 2026-10-24

**Result: PASS for the #350 contract, demo-auth configuration, and two-tenant
baseline that #339 will extend.**

## Artifacts

| Item | Value |
|---|---|
| Contract | [Tenant Context Contract](../api/tenant-context-contract.md) |
| Platform contract commit | `af8e3439f7c0c2a3dda201000511e0557a8cf0c5` |
| UI auth-provider commit | `bdefc5afcf66b7d666fe97af4e3b8e1bd7966065` |
| Platform runtime under test | `e5114918f7de24260eea340dffefcf24da927d89` (local-real-demo images) |
| Core corpus hash | `b3c4fedb457d9dc746ce723180dad76858e805e79387ebc504128a8f0d59f08e` |
| Stack | local-real-demo, synquest `:18083`, synflux `:18090` |
| Tenant A | `lucentrix-rerun` (249 projected chunks) |
| Tenant B | `lucentrix-rerun-other` (0 projected chunks) |

The contract is frozen before #339: the signed `tenant_id` claim is the only
authority; the boundary rejects a valid token without it as 401
`TENANT_CONTEXT_MISSING`; API adapters convert once to immutable `TenantScope`;
downstream code receives the scope as a parameter and shares it on the
per-query snapshot (#410). #339 consumes this contract; it does not define a
second tenant API.

## Observed values

### Demo login (Vite dev handler behind `AuthProvider`)

| Check | Observed |
|---|---|
| Tenant A config → login | `200`, token `sub=alice`, `tenant_id=lucentrix-rerun` |
| Tenant B config → login | `200`, token `sub=alice`, `tenant_id=lucentrix-rerun-other` |
| Missing `DEMO_AUTH_*` + secret | `503`, `DEMO_AUTH_NOT_CONFIGURED`, no token |
| No literal credentials in code | Config only; no hardcoded user/password/tenant values |

Changing `DEMO_AUTH_TENANT` changed the minted token tenant with no code change.

### Two-tenant stack baseline

| Check | Tenant A | Tenant B |
|---|---|---|
| Rebuild projection | 249 chunks | 0 chunks |
| Search PDF fact | 20 results; top `683c831a-3b22-50f3-bfe5-6ac810a9e531:5` | 0 results |
| Search `retention` | (covered by A corpus) | 0 results |
| Provenance for A object | `200`; `manual-upload://fs-rerun/level1/level2/level3/duplicate.pdf` | `404` |

Tenant B saw neither A's search hit nor A's provenance. This is the baseline
#339 must preserve as it adds RLS, cache keying, projection scoping, and
per-layer negative tests.

### Seed idempotency (Postgres 16 temp container, topology V1–V4)

| Step | users | tenants | grants |
|---|---|---|---|
| Seed run 1 | 1 | 1 | 1 |
| Seed run 2 | 1 | 1 | 1 |
| Teardown | 0 | 0 | 0 |

### Tests

| Suite | Result |
|---|---|
| `TenantContextFilterTest` (4) | PASS: missing claim 401; missing bearer 401; verified tenant; empty scope rejected |
| `KnowledgeSearchControllerTest` + `KnowledgeProvenanceControllerTest` | PASS |
| UI `auth.test.ts` (6) | PASS, including `AuthProvider` seam and 503 |
| UI typecheck | PASS |

## Caveats

- **Pre-image evidence.** The two-tenant run above is source-based against the
  `e511491` local-real-demo image, not against an image containing the frozen
  contract. `TENANT_CONTEXT_MISSING` was unit-tested at `af8e343` only. This is
  the same evidence class as #16/#27: it must not be read as proof of the
  shipped artifact. The post-rebuild run is recorded below.
- The two-tenant run uses the real UI dev-auth handler and real platform
  endpoints, but it is not the production issuer (#340).
- RLS, cache-key isolation, and per-layer negatives are #339 work; they are not
  claimed here.
- `DEMO_AUTH_*` remains a development placeholder. The replacement is
  `POST /v1/auth/tenant` (RS256 + JWKS, #340) behind the same `AuthProvider`
  seam; the client login call does not change.
