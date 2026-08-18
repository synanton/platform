# 04 - synapt - Phase 2 - JWT/API-Key Authentication + Trace Propagation

**Version:** 1.0
**Date:** 2026-07-21
**Status:** Draft for review
**Depends on:** [05-security.md](./05-security.md) (Phase 2 DoD - security module is up with JWT issuance). [../phase1/05-synapt.md](../phase1/05-synapt.md) (Phase 1 DoD met).
**Scope:** Replace `MockTenantFilter` with real JWT/API-key authentication delegated to the `security` module. Add `X-Trace-Id` header propagation to downstream services. Redact internal fields from error responses before returning to external callers. No rate limiting, no budget enforcement, no JSON sanitisation - those are Phase 3 and 4.

---

## 1. Context and Document Alignment

| Source | Role |
|--------|------|
| [synanton-design-1.19.md §24 `synapt`](../../architecture/synanton-design-1.19.md) | Production target - Jakarta Validation, global JSON sanitisation, JWT/API-key auth, budget enforcement (HTTP 429), rate limiting, deprecation policy, CSP headers. Phase 2 implements **auth + trace propagation + error redaction** only. |
| [../phase1/05-synapt.md](../phase1/05-synapt.md) | Phase 1 baseline - Jakarta Validation, MockTenantFilter, thin proxy. Phase 2 is additive. |
| [05-security.md](./05-security.md) | Dependency - `security.ValidateToken(token) → SubjectAssertion` is the contract synapt calls on every request. |

**Explicit non-goals for Phase 2:**

- No rate limiting per tenant (`synapt.rate_limit_per_tenant_qps`) - Phase 3.
- No budget enforcement (HTTP 429, `Retry-After`) - Phase 3.
- No global JSON sanitisation (OWASP Jackson deserialiser) - Phase 4.
- No CSP headers - Phase 4.
- No deprecation policy machinery - Phase 5.
- No gRPC ingress - REST only.
- No cost-privacy handling.

---

## 2. Phase 2 in One Sentence

> Validate the incoming `Authorization` header (Bearer JWT or API key) by calling `security.ValidateToken`, attach the resulting `SubjectAssertion` to `TenantContext`, propagate `X-Trace-Id` to all downstream calls, and strip internal-only fields from error responses before returning them to external callers.

---

## 3. Target Architecture

```mermaid
flowchart LR
  CLIENT[caller] -->|"POST /search\nAuthorization: Bearer <token>\nX-Trace-Id: abc"| SA[synapt :8080]
  SA -->|ValidateToken(token)| SEC[security :8088]
  SEC -->|SubjectAssertion{tenant, subject_id}| SA
  SA -->|@Valid + TenantContext| SA
  SA -->|POST /query\nX-Trace-Id: abc| GW[gateway :8086]
  GW -->|QueryResponse| SA
  SA -->|SearchResponse (redacted)| CLIENT
```

**Deployment.** Same Spring Boot service on `:8080`. No new containers - `security` is the new dependency introduced in Phase 2.

---

## 4. Authentication Model

### 4.1 Bearer JWT

The `Authorization: Bearer <jwt>` header is validated by calling `security.POST /auth/validate` with the raw token. On success, security returns a `SubjectAssertion{tenant_id, subject_id, scopes[], expiry}`. Synapt populates `TenantContext` from the assertion.

Caching: synapt caches valid `SubjectAssertion` objects keyed by `sha256(token)` for `min(30s, token.expiry - now - 10s)` using a Caffeine cache. This avoids a security call on every request while respecting the token's TTL. Cache size cap: `synapt.auth.cache-max-size` (default 10 000).

### 4.2 API Key

The `Authorization: Bearer syn_<tenant>_<yyMM>_<secret>` format (API key prefix convention from §26a) is recognised by synapt's `AuthFilter` and routed to `security.POST /auth/validate` the same way. The security module distinguishes JWT vs API key by prefix inspection.

### 4.3 Auth filter chain

```
AuthFilter (order=1):
  1. Extract Authorization header.
  2. If missing → 401 {"error": "missing_credentials"}.
  3. Cache lookup: hit → reuse SubjectAssertion.
  4. Cache miss → security.ValidateToken(token).
     - 200 → SubjectAssertion → cache + TenantContext.set(assertion).
     - 401 → return 401 {"error": "invalid_token"}.
     - security 5xx → return 503 {"error": "auth_service_unavailable"}.
  5. Continue filter chain.
```

`MockTenantFilter` is **disabled** in Phase 2 (it is conditional on `synapt.auth.mock-tenant.enabled=true`, which defaults to `false` from Phase 2 onwards; default profile keeps it enabled for tests without a security service).

### 4.4 Error response redaction

Phase 1 returns `execution_trace` verbatim (full engine topology, internal step IDs). Phase 2 strips these before returning to the external caller:

- Remove `execution_trace.plan.steps[].body` (contains internal service call bodies).
- Remove `execution_trace.steps[].engine` raw addresses.
- Keep `execution_trace.total_ms` and `execution_trace.warnings`.

Redaction is applied in `SearchResponseMapper` (a new bean). The full trace is logged internally at DEBUG before redaction.

---

## 5. X-Trace-Id Propagation

Phase 1 generates a local `trace_id` (UUID) per request but does not propagate it downstream. Phase 2 propagates it:

1. `TraceFilter` (order=0) reads `X-Trace-Id` from the incoming request header (if absent, generates a new UUID). Sets in MDC.
2. `GatewayClient` adds `X-Trace-Id: {trace_id}` as a header on every outbound call to gateway.
3. Gateway (Phase 2 plan) echoes the header to planner, synquest, relix.
4. Synapt includes `trace_id` in the response body (already present in Phase 1 for error responses; Phase 2 adds it to `200` responses too as `meta.trace_id`).

---

## 6. Module Boundaries (delta from Phase 1)

**New / changed in `java/synapt/`:**
- `AuthFilter` - validates `Authorization` header via `SecurityClient`; manages Caffeine token cache.
- `SecurityClient` - thin `WebClient` wrapper for `security.POST /auth/validate`.
- `TraceFilter` - reads/generates `X-Trace-Id`; sets in MDC and propagates to `GatewayClient`.
- `GatewayClient` extended - forwards `X-Trace-Id` header on every request.
- `SearchResponseMapper` - redacts `execution_trace` internal fields; appends `meta.trace_id`.
- `MockTenantFilter` - made conditional on `synapt.auth.mock-tenant.enabled`; disabled by default.
- `SynaptProperties` extended: `auth.*` sub-section.
- New error code `auth_service_unavailable` in `GlobalExceptionHandler`.

**Not changed:**
- `SearchRequest`, `Hints` DTOs - unchanged.
- `SearchController` - reads `TenantContext` (same API; now populated by `AuthFilter` instead of `MockTenantFilter`).
- Jakarta Validation (`@Valid`) - unchanged.

---

## 7. Prerequisites

| # | Prereq | Home | Notes |
|---|--------|------|-------|
| P1 | Phase 1 synapt DoD met. | - | Non-negotiable. |
| P2 | Security Phase 2 DoD met - `security.POST /auth/validate` available and returns `SubjectAssertion`. | [05-security.md](./05-security.md) | Blocking. |
| P3 | Add Caffeine dependency to `java/synapt/build.gradle.kts`. | synapt | `implementation("com.github.ben-manes.caffeine:caffeine")` |
| P4 | `shared/common` `SubjectAssertion` record is defined (it was a stub in Phase 1; Phase 2 needs the full record shape). | shared/common | Coordinate with security plan (SE-1). |

---

## 8. Task Breakdown

Ordered by dependency. Each task ≤ 1 day.

| # | Task | Deliverable |
|---|------|-------------|
| SA2-1 | Define full `SubjectAssertion` record in `shared/common`: `{tenant_id, subject_id, scopes[], expires_at}`. Update security stub to return this shape. | Record + shared/common update |
| SA2-2 | Implement `SecurityClient`: `WebClient` wrapper for `POST http://security:8088/auth/validate`. Maps 200 → `SubjectAssertion`, 401 → throw `InvalidTokenException`, 5xx → throw `AuthServiceUnavailableException`. Timeout: `synapt.auth.security-timeout-ms` (default 500 ms). | Client + tests (WireMock) |
| SA2-3 | Implement `AuthFilter` (Spring `OncePerRequestFilter`): extract header → Caffeine cache check → `SecurityClient.validate()` → `TenantContext.set(assertion)`. Handle both error types. Order=1. | Filter + tests |
| SA2-4 | Caffeine token cache: key = `sha256(raw_token)`, value = `SubjectAssertion`, TTL = `min(30s, expiry - now - 10s)`. Max size = `synapt.auth.cache-max-size`. | Cache wiring + eviction test |
| SA2-5 | Make `MockTenantFilter` conditional on `@ConditionalOnProperty("synapt.auth.mock-tenant.enabled")`. Set `enabled=false` in `application.yaml`; set `enabled=true` in `application-test.yaml`. | Conditional wiring + test |
| SA2-6 | Implement `TraceFilter` (order=0): reads `X-Trace-Id` header (or generates UUID), sets in MDC as `trace_id`. | Filter + test |
| SA2-7 | Extend `GatewayClient`: add `X-Trace-Id` request header to every outbound call from MDC. | Client change + propagation test |
| SA2-8 | Implement `SearchResponseMapper`: redact `execution_trace.plan.steps[].body` and `execution_trace.steps[].engine` addresses. Add `meta.trace_id` to the 200 response envelope. Log full unredacted trace at DEBUG before mapping. | Mapper + unit tests |
| SA2-9 | Extend `GlobalExceptionHandler`: add `InvalidTokenException` → 401 + structured body; `AuthServiceUnavailableException` → 503. | Handler change + tests |
| SA2-10 | E2E tests (`SynaptAuthE2EIT`): WireMock stubs for security + gateway. Scenarios: (a) valid JWT → 200 with answer; (b) invalid JWT → 401; (c) missing header → 401; (d) security 500 → 503; (e) cache hit - security stub asserts single call on two requests with same token; (f) X-Trace-Id propagated to gateway stub (assert header on captured request). | `SynaptAuthE2EIT` |

---

## 9. Data Flow

For request `POST /search  Authorization: Bearer <jwt>  X-Trace-Id: trace-42`:

1. `TraceFilter` reads `X-Trace-Id: trace-42`, sets MDC `trace_id=trace-42`.
2. `AuthFilter`:
   - Cache miss (first request) → `SecurityClient.validate(jwt)` → `security.POST /auth/validate`.
   - Security returns `{tenant_id: "acme", subject_id: "user-7", scopes: ["search"]}`.
   - `TenantContext.set({tenant_id: "acme", ...})`.
   - Cache stores the `SubjectAssertion` for 30 s.
3. `SearchController.search()` proceeds with `TenantContext.tenant_id = "acme"`.
4. `GatewayClient` calls `POST http://gateway:8086/query` with `X-Tenant: acme` and `X-Trace-Id: trace-42`.
5. Gateway returns `QueryResponse{hits, graph_result, answer, execution_trace}`.
6. `SearchResponseMapper` redacts internal trace fields; adds `meta.trace_id = "trace-42"`.
7. `200 OK` with mapped `SearchResponse`.

Cache hit path (second request within 30 s with same token):
2b. Cache hits → security call skipped entirely. `TenantContext` populated from cache.

---

## 10. Configuration Surface (Phase 2 delta)

```yaml
synapt:
  auth:
    mock-tenant:
      enabled: false                  # true only in test profile
    security-base-url: http://security:8088
    security-timeout-ms: 500
    cache-max-size: 10000
    cache-ttl-seconds: 30             # upper bound; respects token expiry
  trace:
    propagate-header: X-Trace-Id
    generate-if-absent: true
  gateway:
    base-url: http://gateway:8086
    timeout-ms: 10000
  server:
    port: 8080
```

In `application-test.yaml` (for unit / integration tests without a security service):
```yaml
synapt:
  auth:
    mock-tenant:
      enabled: true
```

---

## 11. Testing Strategy

- **Unit tests** - `AuthFilter`: three branches (missing header, cache hit, cache miss + valid/invalid/5xx from security). `TraceFilter`: header present vs absent. `SearchResponseMapper`: assert redacted fields absent, `meta.trace_id` present.
- **Caffeine eviction test** - populate cache, advance mocked clock past TTL, assert next call goes to security again.
- **WireMock E2E (SynaptAuthE2EIT)** - six scenarios in SA2-10.
- **Backward-compatibility test** - in the `test` profile (`mock-tenant.enabled=true`), the Phase 1 E2E scenarios in `SynaptE2EIT` must all still pass without a running security service.

---

## 12. Risks and Open Questions

| Risk | Mitigation |
|------|------------|
| Security service is down - all requests return 503. | Cache absorbs short outages (up to 30 s with warm cache). Phase 4 adds `IdpAmortizationCache` with longer TTL. |
| Token cache allows a revoked token to remain valid for up to 30 s. | Accepted for Phase 2. Phase 4 wires SCIM eviction events from security to force cache invalidation. |
| X-Trace-Id spoofed by external caller. | Accepted for Phase 2; trace IDs are not security-sensitive. Phase 4 may validate format. |
| `sha256(token)` in cache key leaks timing info if cache lookup is constant-time-inconsistent. | Caffeine lookups are ~O(1); no user-observable timing channel here. |
| `SubjectAssertion.scopes[]` not validated against endpoint-level permissions. | Accepted; single-tenant Phase 2 has one scope (`search`). Scope enforcement is Phase 3 with `topology`. |

---

## 13. Definition of Done (Phase 2)

Phase 2 is complete when **all** of the following hold with Phase 1 synapt DoD and security Phase 2 DoD met:

1. `POST /search` without `Authorization` returns `401 {"error":"missing_credentials"}`.
2. `POST /search` with an invalid JWT returns `401 {"error":"invalid_token"}`.
3. `POST /search` with a valid JWT returns `200` with `meta.trace_id` in the response body.
4. Two consecutive requests with the same token produce exactly one call to the security service (cache hit demonstrated in `SynaptAuthE2EIT` scenario e).
5. `X-Trace-Id` header from the client is visible on the captured gateway request in `SynaptAuthE2EIT` scenario f.
6. `execution_trace.plan.steps[].body` and engine addresses absent from the `200` response body.
7. `SynaptAuthE2EIT` passes; `SynaptE2EIT` (Phase 1 suite) still passes with `mock-tenant.enabled=true`.
8. Phase 1 synapt DoD remains green.
9. No modifications to `planner`, `gateway`, `synquest`, `relix`, `synvault`, `synflux`, `ingestion-cache`.

---

## 14. Follow-on Phases (Signposted)

- **Phase 3 (synapt)** - Rate limiting per tenant, budget enforcement (HTTP 429 + `Retry-After`).
- **Phase 4 (synapt)** - Global JSON sanitisation (OWASP Jackson deserialiser, §24.1), CSP headers (§49), deprecation policy machinery.
- **Phase 5 (synapt)** - gRPC ingress, MCP session revalidation wiring.
