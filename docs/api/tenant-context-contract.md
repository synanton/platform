# Tenant Context Contract

**Status:** Contract frozen 2026-10-23 for #350 and consumed by #339.
**Owners:** Platform (boundary and enforcement); security-provider (issuance);
UI (client state). Authority model: [security model](../security/security-model.md),
[ADR 0007](https://github.com/synanton/ui/blob/main/docs/decisions/0007-tenant-selection-and-token-bound-context.md).

This is the named boundary contract #339 must consume. It exists so the data
layer does not invent its own tenant API after the fact.

## 1. Authority and ingress

The only tenant authority is the `tenant_id` claim in a signed access token.

- The browser never sends `X-Tenant-ID`, a body `tenant_id`, or a path tenant.
  Platform request paths reject those with `400 TENANT_HEADER_NOT_SUPPORTED`
  or `400 TENANT_IN_REQUEST_BODY` (search path, #347).
- `TenantContextFilter` verifies signature, expiry, and the tenant claim once at
  the HTTP boundary.
- A valid token with no `tenant_id` is **401 `TENANT_CONTEXT_MISSING`**. It is
  not admitted to a controller and never falls back to a default tenant.
- No/invalid token is **401 `AUTHENTICATION_REQUIRED`**.
- Exempt operational paths (`/health`, `/actuator`, …) may run without a tenant
  because they do not touch tenant-scoped data.

The demo login is a configuration-driven placeholder, not an authority:

- `DEMO_AUTH_USER`, `DEMO_AUTH_PASSWORD`, `DEMO_AUTH_TENANT`,
  `SYNANTON_JWT_SECRET` are required by the Vite dev login.
- Missing config → `503 DEMO_AUTH_NOT_CONFIGURED`, no token.
- The issued token has the configured user/tenant; changing
  `DEMO_AUTH_TENANT` changes the token with no code change.
- The login handler sits behind an `AuthProvider` interface. The replacement is
  `POST /v1/auth/tenant` (RS256 + JWKS, #340); the client login call does not
  change.

## 2. Immutable values and direction

Two values, one direction:

```text
signed token
  -> TenantContextFilter (verify + require tenant claim)
  -> TenantContext (request-scoped, immutable boundary carrier)
  -> TenantScope (storage/query contract; non-blank, immutable)
  -> repository / cache / search / provenance (explicit parameter)
```

- **`TenantScope`** (`org.synanton.storage.contract.TenantScope`) is the only
  tenant type that may cross into a store or query. It refuses blank ids at
  construction.
- **`TenantContext`** is the immutable request carrier. It is populated by the
  filter and cleared in `finally`. API adapters call `requireTenantId()` and
  convert **once** to `TenantScope`.
- Downstream code receives the scope as a parameter. It must not call
  `TenantContext.get()` below the API layer, and it must not re-read the
  request, the token, or a thread-local to recover the tenant.
- `TenantScope` is never derived from a storage key, a path, a query parameter,
  or a cache key. Those are consequences of the scope, not sources of it.

This mirrors the identity model's rule: the platform binds an explicit
`(principal_id, tenant_id)` pair after authentication; client input is never
identity.

## 3. Per-query snapshot

Search and provenance resolve the scope once per request. That scope rides on
the same immutable query snapshot established for tombstone filtering (#410):

- one snapshot read per query;
- the initial leg, any dense leg, and the guard refetch all use the same scope;
- tenant filtering is part of the snapshot's read, not a post-retrieval filter;
- no leg may widen, re-resolve, or omit the scope.

This prevents two context objects (one for search, one for RLS) from drifting.

## 4. Fail-closed matrix

| Condition | Result |
|---|---|
| No/invalid token | `401 AUTHENTICATION_REQUIRED` |
| Valid token, no tenant claim | `401 TENANT_CONTEXT_MISSING` |
| Demo auth config missing | `503 DEMO_AUTH_NOT_CONFIGURED`, no token |
| `X-Tenant-ID` header | `400 TENANT_HEADER_NOT_SUPPORTED` |
| `tenant_id` in search body | `400 TENANT_IN_REQUEST_BODY` |
| Tenant-scoped call with empty scope | `MissingTenantContextException`, no query |
| Cross-tenant object lookup | `404` (not a filtered `200`) |

The full anti-oracle matrix (403 vs uniform 404 for selection) is in the
[security model](../security/security-model.md).

## 5. What #339 consumes

#339 must implement, per layer, against this contract:

1. **Repository/RLS** — the public content-cache entry point is
   `TenantScopedContentStore`, with `TenantScope` as a required constructor
   value; the old `JdbcContentStore` is package-private. Every transaction
   sets `SET LOCAL app.tenant_id`; a raw query with no scope returns zero rows.
   Facade coverage is the relational content cache only; the search projection
   and provenance paths are separate and re-verified by the two-tenant
   acceptance.
2. **Cache keys** — tenant prefix in gateway/Redis and search-projection cache
   keys; a cache miss fails closed; A's entry is never served to B.
3. **Search projection** — scope is in the snapshot read, shared by legs and
   refetch; a cross-tenant query never sees A's chunks.
4. **Provenance** — live identities are tenant-scoped; cross-tenant lookup is
   `404`, not a filtered `200`.
5. **Tests** — permanent two-tenant read/write/cache/provenance negatives,
   including a path that bypasses the scoped repository.

RLS is not the only control; cache and search projection are separate stores.

## 6. Evidence

- Boundary tests: `TenantContextFilterTest` (valid token without claim is 401;
  verified tenant is available only through the API adapter).
- Demo login tests: `apps/web/src/real/auth.test.ts` in `synanton/ui`
  (missing config 503; configured tenant changes with config).
- #339 baseline: tenant A and tenant B tokens against the local-real-demo
  stack, recorded in the #350 acceptance comment.
