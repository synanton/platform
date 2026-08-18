# 05 - security - Phase 2 - AuthN/Z Foundation (JWT + Local IdP)

**Version:** 1.0
**Date:** 2026-07-21
**Status:** Draft for review
**Depends on:** [../phase1/05-synapt.md](../phase1/05-synapt.md) (Phase 1 DoD met - synapt exists and will consume security). Phase 2 is the **first real implementation** of this module; Phase 1 left it as an empty stub.
**Scope:** Implement local user authentication (htpasswd backend), RS256 JWT issuance, and a `POST /auth/validate` endpoint that synapt calls to verify tokens. Implement `IdentityProviderPort` as a local (non-OIDC) implementation. No outbound auth broker, no API key lifecycle, no MCP session revalidation - those are Phase 3 and 4.

---

## 1. Context and Document Alignment

| Source | Role |
|--------|------|
| [synanton-design-1.19.md §26 `security`](../../architecture/synanton-design-1.19.md) | Production target - OIDC/IdP integration, outbound auth broker (RFC 8693), MCP session revalidation, IdP amortisation cache, API key lifecycle, `support_admin` role. Phase 2 implements **local JWT auth** only. |
| [synanton-design-1.19.md §26a API Key Lifecycle](../../architecture/synanton-design-1.19.md) | Defines argon2id hashing requirements for stored secrets. Phase 2 does not implement API keys yet; the schema seam is placed now. |
| [04-synapt.md](./04-synapt.md) | Primary consumer - synapt calls `POST /auth/validate` on every unauthenticated cache miss. |
| [06-topology.md](./06-topology.md) | Phase 2 topology seeds the `"demo"` tenant in PostgreSQL; security reads the tenant list from topology to validate `tenant_id` claims in tokens. |

**Explicit non-goals for Phase 2:**

- No OIDC/OAuth IdP integration (Keycloak, Google, Azure AD) - Phase 4.
- No outbound auth broker (RFC 8693 token exchange) - Phase 3.
- No API key generation/rotation/revocation (§26a) - Phase 3.
- No MCP session revalidation - Phase 4.
- No IdP amortisation cache (with staleness metric) - Phase 4.
- No worker token renewal - Phase 4.
- No `support_admin` role provisioning (§26b) - Phase 5.
- No multi-tenant user stores - all users stored in a single flat htpasswd file for Phase 2.

---

## 2. Phase 2 in One Sentence

> Issue RS256 JWTs to users who authenticate with a username+password stored in a bcrypt htpasswd file, and validate those JWTs on request, exposing a `POST /auth/validate` endpoint for synapt to consume.

---

## 3. Target Architecture

```mermaid
flowchart LR
  CLIENT[caller] -->|"POST /auth/login\n{username, password}"| SEC[security :8088]
  SEC -->|bcrypt verify| HF[htpasswd file\n on disk]
  SEC -->|RS256 sign| KS[keypair\n in keystore]
  SEC -->|{access_token, expires_in}| CLIENT
  SA[synapt] -->|"POST /auth/validate\n{token}"| SEC
  SEC -->|SubjectAssertion| SA
```

**Deployment.** New Spring Boot service on `:8088`. No new Docker containers - runs alongside existing services. The htpasswd file and RSA keypair are volume-mounted into the container.

---

## 4. Data Contracts

### 4.1 POST /auth/login

**Request:**
```json
{ "username": "alice", "password": "s3cr3t" }
```

**Response (200):**
```json
{
  "access_token": "eyJ…",
  "token_type": "Bearer",
  "expires_in": 3600,
  "tenant_id": "demo",
  "subject_id": "alice"
}
```

**Error (401):**
```json
{ "error": "invalid_credentials", "message": "Unknown user or wrong password." }
```

### 4.2 POST /auth/validate

**Request:**
```json
{ "token": "eyJ…" }
```

**Response (200):**
```json
{
  "tenant_id": "demo",
  "subject_id": "alice",
  "scopes": ["search"],
  "expires_at": "2026-07-21T15:00:00Z"
}
```

**Error (401):**
```json
{ "error": "invalid_token", "reason": "expired | signature_invalid | tenant_unknown" }
```

---

## 5. Implementation Design

### 5.1 Htpasswd user store

Users are stored in a BCrypt htpasswd file at the path `security.auth.htpasswd-path`. Each line: `username:$2y$10$<bcrypt_hash>`. The `demo` environment ships with two pre-seeded users: `alice:demo-password-1` and `bob:demo-password-2`.

The htpasswd file is read into memory at startup and cached. A file-watch (Java `WatchService`) triggers a reload on modification - no restart needed when adding users. Reload latency: up to 5 s.

### 5.2 JWT issuance (RS256)

Security holds a 2048-bit RSA keypair in a Java keystore (`security.auth.keystore-path`, type PKCS12). On startup, it loads the keypair and holds the `PrivateKey` in memory.

JWT claims (per `RFC 7519`):
- `iss`: `"synanton"` (configurable).
- `sub`: `username` (= `subject_id`).
- `tenant_id`: derived from the user-to-tenant mapping (Phase 2: all users map to `"demo"`).
- `scopes`: `["search"]` (hardcoded for Phase 2; Phase 3 reads from topology ACL).
- `exp`: `iat + security.auth.token-ttl-seconds` (default 3600).
- `jti`: random UUID (enables future revocation checks).

Signing: `com.auth0:java-jwt:4.x` or `io.jsonwebtoken:jjwt-impl:0.12.x`. Both are acceptable; pick one consistently.

### 5.3 JWT validation

`POST /auth/validate` verifies:
1. Signature with the RSA public key.
2. `exp` - token not expired.
3. `iss` = `"synanton"`.
4. `tenant_id` claim is a known tenant (Phase 2: must equal `"demo"`; Phase 3 checks against topology's tenant list).

Validation is done in-process - no external call. The public key is loaded from the same keystore; only the public half is needed for verification.

Synapt's Caffeine token cache (see `04-synapt.md`) absorbs repeated validate calls - so the security service does not need its own caching of validated tokens.

### 5.4 User-to-tenant mapping (Phase 2)

All users map to `tenant_id = "demo"`. This mapping is hardcoded in a `UserDetailsService` bean that reads the htpasswd file. Phase 3 moves tenant assignment to `topology.organizations` (where users are linked to tenants via `acl_grants`).

---

## 6. Module Boundaries

**Owned by `java/security/` in Phase 2:**
- `POST /auth/login` - authenticates via htpasswd, issues JWT.
- `POST /auth/validate` - validates JWT, returns `SubjectAssertion`.
- `GET /health`, `GET /security/keys` (JWK Set endpoint, public key only - for future OIDC interop).
- `HtpasswdUserStore` - file-backed user store with file-watch reload.
- `JwtService` - issues and validates RS256 JWTs.
- `IdentityProviderPort` local implementation (wraps `HtpasswdUserStore`).
- Keystore management (startup load, exception on missing key).

**Not owned in Phase 2:**
- API key generation (§26a) - no `api_keys` table yet.
- OIDC - no `IdentityProviderPort` external implementation.
- Outbound auth broker - Phase 3.
- `support_admin` role - Phase 5.

---

## 7. Prerequisites

| # | Prereq | Home | Notes |
|---|--------|------|-------|
| P1 | Add `java/security` to `settings.gradle.kts`. | root | New module. |
| P2 | RSA keypair generated and stored in a PKCS12 keystore. Committed as `deployment/docker/security/demo.p12` (dev-only key; **not for production**). | deployment | One-time: `keytool -genkeypair -alias synanton -keyalg RSA -keysize 2048 -keystore demo.p12 -storetype PKCS12 -validity 3650`. |
| P3 | Htpasswd file committed as `deployment/docker/security/users.htpasswd` with `alice` and `bob` entries. | deployment | `htpasswd -B -c users.htpasswd alice` then `htpasswd -B users.htpasswd bob`. |
| P4 | `shared/common` `SubjectAssertion` record finalised (coordinates with `SA2-1` in synapt plan). | shared/common | Blocking: security's validate endpoint returns this type. |
| P5 | Topology Phase 2 started - at minimum the `organizations` table is seeded with `tenant_id=demo` so security can validate `tenant_id` claims. | [06-topology.md](./06-topology.md) | Can start in parallel; security has a config fallback `security.known-tenants=["demo"]` for pre-topology environments. |

---

## 8. Task Breakdown

Ordered by dependency. Each task ≤ 1-2 days.

| # | Task | Deliverable |
|---|------|-------------|
| SE-1 | Finalise `SubjectAssertion` record in `shared/common` (`tenant_id`, `subject_id`, `scopes[]`, `expires_at`). | Record + update shared/common tests |
| SE-2 | Create Gradle module; deps: Spring Boot web, Spring Security (for filter chain, NOT for IdP integration), `com.auth0:java-jwt` (or JJWT), BCrypt support, `shared/common`. | `build.gradle.kts` |
| SE-3 | Implement `HtpasswdUserStore`: parse `username:$2y$…` lines, verify BCrypt via Spring Security's `BCryptPasswordEncoder`. File-watch for hot reload (debounced, 5 s). | Class + tests (in-memory htpasswd fixture) |
| SE-4 | Implement `JwtService.issue(username, tenant_id, scopes)`: builds JWT claims, signs with RSA private key. | Class + unit tests (sign + verify roundtrip) |
| SE-5 | Implement `JwtService.validate(token)`: verifies signature, expiry, issuer, tenant_id claim. Returns `SubjectAssertion` or throws `InvalidTokenException{reason}`. | Class + unit tests (expired, bad signature, unknown tenant) |
| SE-6 | Implement `KeystoreLoader`: loads PKCS12 keystore at startup; fails fast with a clear error if the file is missing or the password is wrong. | Class + test |
| SE-7 | REST controllers: `POST /auth/login`, `POST /auth/validate`, `GET /health`, `GET /security/keys` (JWK Set with public key). Map exceptions to the structured error shapes in §4. | Controllers + integration tests |
| SE-8 | `application.yaml`; `SecurityApplication` boot class; Docker Compose entry in the `phase2` profile: volume-mounts `demo.p12` and `users.htpasswd`. | Boot + compose extension |
| SE-9 | E2E tests (`SecurityE2EIT`): (a) valid login → 200 with `access_token`; (b) bad password → 401; (c) unknown user → 401; (d) validate a just-issued token → 200 with correct `SubjectAssertion`; (e) validate expired token (mock clock) → 401 `reason=expired`; (f) validate token with tampered signature → 401 `reason=signature_invalid`; (g) hot-reload test: modify htpasswd, wait 6 s, new login with new password → 200. | `SecurityE2EIT` |
| SE-10 | Add `security_login_total{outcome}` and `security_validate_total{outcome}` Prometheus counters. | Metrics + assertion |

---

## 9. Data Flow

For a first-time login by `alice`:

1. `POST /auth/login {"username":"alice","password":"s3cr3t"}`.
2. `HtpasswdUserStore.verify("alice", "s3cr3t")` - BCrypt hash comparison: ~100 ms.
3. Match → `JwtService.issue("alice", "demo", ["search"])` → JWT string.
4. `200 {"access_token": "eyJ...", "expires_in": 3600}`.

For a subsequent search request (via synapt):

1. Synapt calls `POST /auth/validate {"token": "eyJ..."}`.
2. `JwtService.validate(token)`:
   - Verify RS256 signature: ~2 ms.
   - Check `exp > now`.
   - Check `tenant_id = "demo"` is known.
3. `200 {"tenant_id":"demo","subject_id":"alice","scopes":["search"],"expires_at":"..."}`.
4. Synapt caches the `SubjectAssertion` for 30 s → subsequent synapt calls within 30 s don't hit security.

---

## 10. Configuration Surface

```yaml
security:
  auth:
    htpasswd-path: /etc/synanton/security/users.htpasswd
    keystore-path: /etc/synanton/security/demo.p12
    keystore-password: ${SECURITY_KEYSTORE_PASSWORD:changeit-dev}
    keystore-alias: synanton
    token-ttl-seconds: 3600
    issuer: synanton
    known-tenants: [demo]   # fallback; Phase 3 replaces with topology lookup
  server:
    port: 8088
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
```

---

## 11. Testing Strategy

- **Unit tests** - `HtpasswdUserStore`: parse, BCrypt verify (correct and wrong passwords), file format edge cases. `JwtService`: sign-verify roundtrip, expired token, bad signature, unknown tenant.
- **Boot integration test** - `@SpringBootTest` with volume-mounted fixtures; assert `/health` is UP; assert login → validate roundtrip returns consistent `SubjectAssertion`.
- **Hot-reload test** - modify the htpasswd file in a temp dir, advance the debounce interval (mocked clock), assert new credentials work and old ones don't.
- **SecurityE2EIT** - seven scenarios in SE-9 (no external dependencies beyond disk files).
- **Metrics assertion** - after 3 valid logins and 1 invalid, assert `security_login_total{outcome=success}=3` and `security_login_total{outcome=invalid_credentials}=1`.

---

## 12. Risks and Open Questions

| Risk | Mitigation |
|------|------------|
| BCrypt login is slow (~100 ms) - concurrent logins may saturate the thread pool. | Acceptable at PoC scale. Phase 4 adds IdP amortisation cache. Login rate is low in Phase 2 (single-tenant demo). |
| RSA private key in a dev PKCS12 committed to Git. | Clearly labelled as dev-only. Production deployment must generate its own keypair. README must warn. |
| Token revocation not implemented - a stolen token is valid until expiry. | Accepted for Phase 2 (1-hour TTL). Phase 3 introduces `jti` revocation list in Redis. |
| Phase 2 uses a flat user list; multi-tenant user isolation not enforced. | One tenant (`demo`) only; isolation is not a concern. Phase 3 binds users to tenants via topology. |
| Hot-reload race: file partially written when watch fires. | Debounce 5 s + atomic parse: parse the new file in memory; only replace the in-memory store if parsing succeeds. |

---

## 13. Definition of Done (Phase 2)

Phase 2 is complete when **all** of the following hold:

1. `./gradlew :java:security:bootRun` starts cleanly on `:8088`.
2. `POST /auth/login` with `alice`/`demo-password-1` returns `200` with a non-null `access_token`.
3. `POST /auth/validate` with that token returns `200` with `tenant_id=demo`, `subject_id=alice`.
4. Invalid password → `401`. Expired token → `401 reason=expired`. Tampered token → `401 reason=signature_invalid`.
5. `SecurityE2EIT` (7 scenarios) passes.
6. Synapt Phase 2 `SynaptAuthE2EIT` passes with security running (end-to-end auth flow).
7. `security_login_total` and `security_validate_total` counters visible in `/actuator/prometheus`.
8. No external service dependencies (Cassandra, MinIO, vLLM) required to start security.

---

## 14. Follow-on Phases (Signposted)

- **Phase 3 (security)** - Outbound Auth Broker (RFC 8693), `USER_SUBJECT`/`SERVICE_ACCOUNT`/`MTLS`/`API_KEY` profiles. Full API key lifecycle (§26a): generation, rotation, revocation.
- **Phase 4 (security)** - Full OIDC/Keycloak integration. `IdentityProviderPort` wired to an external IdP. IdP amortisation cache with staleness metric. MCP session revalidation. Worker token renewal.
- **Phase 5 (security)** - `support_admin` role provisioning. Cross-region key management. Prompt/model version tracking integration with `synreview`.
