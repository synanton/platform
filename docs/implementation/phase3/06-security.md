# 06 - security - Phase 3 - RFC 8693 Token Exchange, Identity Profiles, API Key Lifecycle

**Version:** 1.0
**Date:** 2026-07-24
**Status:** Draft for review
**Depends on:** `security` Phase 2 DoD met (JWT issuance RS256, htpasswd IdP); PostgreSQL `security` schema from Phase 2
**Scope:** Outbound Auth Broker implementing RFC 8693 token exchange, four identity profiles (USER_SUBJECT, SERVICE_ACCOUNT, MTLS, API_KEY), full API key lifecycle: generation, listing, rotation, revocation.

---

## 1. Context and Document Alignment

| Source | Role in this plan |
|--------|-------------------|
| [synanton-design-1.19.md](../../architecture/platform/synanton-design-1.19.md) §26 `security` (identity profiles, RFC 8693 token exchange, §26a API key lifecycle, service accounts) | Production target. Phase 3 adds service-to-service auth and API keys on top of the Phase 2 user JWT baseline. |
| [security Phase 2](../phase2/05-security.md) | Foundation. Phase 2 delivered `POST /auth/login` (RS256 JWT), `POST /auth/validate`, and `POST /auth/refresh`. Phase 3 builds on those endpoints without changing them. |
| [01-ingestion-pipeline Phase 3](./01-ingestion-pipeline.md) | `synflux-router → synflux` inter-service call uses RFC 8693 token exchange to obtain a short-lived service token. |

**Explicit non-goals for Phase 3:**

- No MTLS handshake implementation - `IdentityProviderPort` dispatch is plumbed but `MtlsIdentityProvider` throws `UnsupportedOperationException`.
- No OIDC provider federation - the security service is still a first-party IdP only.
- No key rotation automation (scheduled rotation) - operators call `DELETE` + `POST` manually.
- No HSM for key storage - argon2id hash in PostgreSQL is sufficient for Phase 3.
- No API key scoping beyond a simple `text[]` array - fine-grained scope enforcement is Phase 4.

---

## 2. Phase 3 in One Sentence

> Add an RFC 8693 token-exchange endpoint that issues short-lived (5 min) service-to-service tokens, introduce four identity profile types (USER_SUBJECT, SERVICE_ACCOUNT, API_KEY, MTLS) resolved by `IdentityProviderPort` dispatch, and deliver the full API key lifecycle: generate, list, validate, revoke.

---

## 3. Target Architecture

```mermaid
flowchart TD
  USER[User / external client] -->|POST /auth/login| LOGIN[JwtIssuer\nPhase 2]
  SVC[Service caller e.g. synflux-router] -->|POST /auth/token\nRFC 8693| EXCH[TokenExchangeEndpoint]
  EXCH -->|subject token: caller JWT| VERIFY[JwtVerifier]
  EXCH -->|issue short-lived service JWT| SVCJWT[SubjectAssertion\nSERVICE_ACCOUNT]
  APICLIENT[API key caller] -->|POST /auth/validate\nBearer syn_xxx| VALIDATE[ValidateEndpoint]
  VALIDATE --> DISPATCH[IdentityProviderPort dispatcher]
  DISPATCH -->|syn_ prefix| APIKEY[ApiKeyIdentityProvider]
  DISPATCH -->|Bearer JWT| JWT[JwtIdentityProvider\nPhase 2]
  DISPATCH -->|mTLS cert| MTLS[MtlsIdentityProvider\nnot impl Phase 3]
  APIKEY -->|argon2id verify| DB[(PostgreSQL\nsecurity.api_keys)]
  APIKEY --> ASSERTION[SubjectAssertion\nAPI_KEY profile]
```

---

## 4. Data Contracts

### 4.1 `POST /auth/token` (RFC 8693)

Request:
```
POST /auth/token
Content-Type: application/x-www-form-urlencoded

grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange
&subject_token=eyJhbGc...
&subject_token_type=urn%3Aietf%3Aparams%3Aoauth%3Atoken-type%3Aaccess_token
&requested_token_type=urn%3Aietf%3Aparams%3Aoauth%3Atoken-type%3Aaccess_token
&actor_id=synflux-router
```

Response (HTTP 200):
```json
{
  "access_token": "eyJhbGc...",
  "issued_token_type": "urn:ietf:params:oauth:token-type:access_token",
  "token_type": "Bearer",
  "expires_in": 300
}
```

### 4.2 `POST /auth/api-keys` - generate

Request:
```json
{ "label": "demo-prod-key", "scopes": ["search", "ingest"] }
```
Response (HTTP 201, plaintext key shown exactly once):
```json
{
  "key_id": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "key": "syn_aB3xK9mP2qRvTzWdYjLcNfGhEiOpQrSt7uVwXy",
  "label": "demo-prod-key",
  "scopes": ["search", "ingest"],
  "created_at": "2026-07-24T10:00:00Z",
  "expires_at": null
}
```

### 4.3 `POST /auth/validate` - API key resolution

Request: `Authorization: Bearer syn_aB3xK9mP2qRvTzWdYjLcNfGhEiOpQrSt7uVwXy`

Response (HTTP 200):
```json
{
  "subject_id": "user:alice",
  "tenant_id": "demo",
  "identity_profile": "API_KEY",
  "scopes": ["search", "ingest"],
  "key_id": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"
}
```

---

## 5. Implementation Design

### 5.1 `IdentityProviderPort` dispatcher

```java
public interface IdentityProviderPort {
    boolean supports(String authorizationHeader);
    SubjectAssertion resolve(String authorizationHeader);
}
```

`IdentityProviderDispatcher` holds a `List<IdentityProviderPort>` ordered by priority:
1. `ApiKeyIdentityProvider` - supports headers starting with `Bearer syn_`.
2. `JwtIdentityProvider` - supports `Bearer eyJ` (existing Phase 2 logic refactored into this interface).
3. `MtlsIdentityProvider` - supports `Bearer mtls:` (not implemented, throws `UnsupportedOperationException`).

`ValidateEndpoint.POST /auth/validate` now delegates to `IdentityProviderDispatcher.resolve()` instead of directly calling `JwtVerifier`.

### 5.2 `ApiKeyIdentityProvider`

On call:
1. Strip `Bearer syn_` prefix to get the raw key string (48 base62 chars).
2. Compute BLAKE2b-256 hash of the raw key (fast pre-filter, not used for security - only for DB lookup key). Stored as `key_lookup_hash` column.
3. Look up `api_keys WHERE key_lookup_hash = ? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now())`.
4. If found: argon2id verify `rawKey` against `key_hash`. If matches: return `SubjectAssertion(subjectId, tenantId, identityProfile=API_KEY, scopes)`.
5. If not found or argon2id fails: throw `AuthenticationException` → 401.

**Performance note:** BLAKE2b lookup narrowing avoids a full-table scan. Argon2id verification takes ~50 ms by design - cache the `SubjectAssertion` in a Caffeine cache keyed by `(key_lookup_hash)` with TTL=60 s to avoid re-hashing on every request.

### 5.3 API key lifecycle - endpoints

**`POST /auth/api-keys`** - requires authenticated caller (any profile). Caller's `tenantId` from `SubjectAssertion` is used as the key's `tenant_id`. Steps:
- Generate 48 random base62 chars using `SecureRandom`. Format: `syn_` + 48 chars.
- Compute `key_hash = argon2id(rawKey, salt)` using `de.mkammerer:argon2-jvm`.
- Compute `key_lookup_hash = BLAKE2b(rawKey)` for fast lookup.
- Insert into `security.api_keys(key_id, tenant_id, subject_id, key_hash, key_lookup_hash, label, scopes, created_at)`.
- Return `{ key_id, key=rawKey, label, scopes, created_at }`. Raw key is never stored and never returned again.

**`GET /auth/api-keys`** - returns all non-revoked keys for the caller's tenant. Does not return `key_hash` or `key_lookup_hash`. Pagination: `?limit=20&cursor={key_id}`.

**`DELETE /auth/api-keys/{key_id}`** - sets `revoked_at = now()`. Subsequent `validate` calls return 401. Caffeine cache is invalidated by `key_lookup_hash` on revocation.

**`POST /auth/api-keys/{key_id}/rotate`** - generates a new key, revokes the old key atomically (in a PostgreSQL transaction). Returns the new plaintext key. Old key is immediately invalid.

### 5.4 RFC 8693 Token Exchange - `TokenExchangeEndpoint`

`POST /auth/token` with `grant_type=urn:ietf:params:oauth:grant-type:token-exchange`:
1. Validate `subject_token` as a valid JWT (via `JwtVerifier`, Phase 2).
2. Validate `actor_id` is a known service name from `security.service_accounts` table.
3. Issue a new JWT with: `sub = service:{actor_id}`, `tenant_id` from the subject token, `exp = now() + 5 minutes`, `scope = service`, no `uid` or `gids`.
4. Return the RFC 8693 response body.

**`security.service_accounts` table** (new migration, see §5.5):
- `service_name TEXT PRIMARY KEY, allowed_tenants TEXT[], created_at TIMESTAMPTZ`.
- Seeded with: `synflux-router`, `synflux`, `relix`, `gateway`, `control-plane`.

**Short-lived token cache** (callers, not security service): each service that calls `/auth/token` must cache the returned `access_token` for `expires_in - 30` seconds and request a new one on 401. This is documented as a convention in `shared/common`'s `ServiceTokenProvider` helper class.

### 5.5 PostgreSQL schema additions (Flyway)

`V3__security_api_keys_service_accounts.sql`:
```sql
CREATE TABLE security.api_keys (
  key_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        TEXT NOT NULL,
  subject_id       TEXT NOT NULL,
  key_hash         TEXT NOT NULL,
  key_lookup_hash  TEXT NOT NULL,
  label            TEXT,
  scopes           TEXT[] NOT NULL DEFAULT '{}',
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at       TIMESTAMPTZ,
  revoked_at       TIMESTAMPTZ
);
CREATE INDEX ON security.api_keys (key_lookup_hash) WHERE revoked_at IS NULL;
CREATE INDEX ON security.api_keys (tenant_id);

CREATE TABLE security.service_accounts (
  service_name     TEXT PRIMARY KEY,
  allowed_tenants  TEXT[] NOT NULL DEFAULT '{}',
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO security.service_accounts (service_name, allowed_tenants) VALUES
  ('synflux-router', '{}'),
  ('synflux', '{}'),
  ('relix', '{}'),
  ('gateway', '{}'),
  ('control-plane', '{}');
```

---

## 6. Module Boundaries

| Module | Owns in Phase 3 | Does not own |
|--------|----------------|--------------|
| `security` | `IdentityProviderPort` SPI, `ApiKeyIdentityProvider`, `TokenExchangeEndpoint`, API key CRUD, `ServiceTokenProvider` helper, Flyway `V3__` migration | Token caching in callers (that's each service's responsibility using `ServiceTokenProvider`) |
| `shared/common` | `ServiceTokenProvider` helper (wraps `POST /auth/token` with local cache) | Argon2id, JWT signing |

---

## 7. Prerequisites

| # | Prereq | Home | Notes |
|---|--------|------|-------|
| 1 | `security` Phase 2 DoD met - JWT issuance, validate, refresh working. | - | Non-negotiable. |
| 2 | PostgreSQL `security` schema exists (Phase 2 Flyway migrations applied). | Phase 2 security | V3__ runs after V1__ and V2__. |
| 3 | `de.mkammerer:argon2-jvm:2.11` added to BOM. | `gradle/libs.versions.toml` | Native argon2 bindings. |
| 4 | `com.github.ben-manes.caffeine:caffeine` in BOM (already used by synflux). | shared | Reuse existing dep. |
| 5 | `synflux-router` service name registered in `security.service_accounts` at seed time. | Flyway V3 seed | Part of V3__ migration. |

---

## 8. Task Breakdown

| # | Task | Deliverable | Est. |
|---|------|-------------|------|
| SEC3-1 | Write Flyway `V3__security_api_keys_service_accounts.sql`; apply and verify in dev. | Migration file | 0.5 day |
| SEC3-2 | Define `IdentityProviderPort` interface; refactor Phase 2 JWT logic into `JwtIdentityProvider`. | Interface + refactored class | 1 day |
| SEC3-3 | Implement `ApiKeyIdentityProvider` - BLAKE2b lookup, argon2id verify, Caffeine cache. | Class + unit tests | 1.5 days |
| SEC3-4 | Implement `IdentityProviderDispatcher` - ordered dispatch; update `ValidateEndpoint`. | Class + tests | 0.5 day |
| SEC3-5 | Implement `POST /auth/api-keys`, `GET /auth/api-keys`, `DELETE /auth/api-keys/{id}`, `POST /auth/api-keys/{id}/rotate`. | 4 endpoints + tests | 2 days |
| SEC3-6 | Implement `TokenExchangeEndpoint POST /auth/token` (RFC 8693); unit test with mock `JwtVerifier`. | Endpoint + tests | 1 day |
| SEC3-7 | Implement `ServiceTokenProvider` in `shared/common` - wraps `/auth/token`, caches with TTL-30s refresh. | Helper class + tests | 1 day |
| SEC3-8 | Wire `ServiceTokenProvider` into `synflux-router` for `synflux-router → synflux` call. | Wiring in router | 0.5 day |
| SEC3-9 | Integration test `ApiKeyLifecycleIT` (Testcontainers Postgres): generate key → validate → revoke → validate returns 401. | `ApiKeyLifecycleIT` | 1 day |
| SEC3-10 | Integration test `TokenExchangeIT`: valid caller JWT → exchange → service JWT with `sub=service:synflux-router`. | `TokenExchangeIT` | 0.5 day |

---

## 9. Testing Strategy

- **Unit:** `ApiKeyIdentityProvider` with mock DB. `IdentityProviderDispatcher` asserting correct provider is selected for each prefix. `TokenExchangeEndpoint` with mock `JwtVerifier` and mock service accounts table.
- **Integration:** `ApiKeyLifecycleIT` (Testcontainers Postgres): full round-trip. `TokenExchangeIT`: issue JWT → exchange → verify service JWT claims.
- **Security:** `ApiKeyBruteForceTest` - 1000 validate calls with wrong keys; all return 401 in < 200 ms each (argon2id cost factor bounded by config). `ApiKeyRevocationTest` - verify revoked keys fail immediately after revocation.
- **Regression:** Phase 2 JWT login + validate tests unchanged.

---

## 10. Configuration Surface

```yaml
# security/src/main/resources/application-phase3.yaml
security:
  api-key:
    prefix: "syn_"
    length: 48
    argon2:
      memory-kb: 65536
      iterations: 3
      parallelism: 1
    cache:
      ttl-seconds: 60
      max-size: 10000
  token-exchange:
    service-token-ttl-seconds: 300
    issuer: "https://security.synanton.internal"
  service-accounts:
    seed-on-startup: true   # applies V3 seed data if table is empty
```

---

## 11. Risks and Open Questions

| Risk | Mitigation | Decision |
|------|------------|----------|
| Argon2id verification at 50 ms per call with no cache means 20 RPS max per security instance. | Caffeine cache (TTL 60 s) means only the first request per API key hits argon2id. Cache size 10 000 covers typical tenant key counts. | Cache is mandatory, not optional. |
| Plaintext key shown once - if lost, user must rotate. | Documented clearly in API response. `rotate` endpoint makes this painless. | By design (security requirement). |
| `ServiceTokenProvider` cache in each service means a revoked service account can still call for up to TTL-30 s. | 5-minute service token TTL is short. Forced revocation requires restarting the calling service - acceptable for Phase 3. Phase 4 adds a revocation introspection endpoint. | Accepted for Phase 3. |
| BLAKE2b is not a standard Java library. | Use `org.bouncycastle:bcprov-jdk18on` for BLAKE2b - already likely in BOM for crypto. | Add BouncyCastle if not present. |

---

## 12. Definition of Done (Phase 3)

1. `POST /auth/api-keys` generates a key starting with `syn_`; response contains the plaintext key.
2. `POST /auth/validate` with the generated key returns `identity_profile=API_KEY` and correct `tenant_id`.
3. `DELETE /auth/api-keys/{id}` returns 200; subsequent `POST /auth/validate` with that key returns 401.
4. `POST /auth/api-keys/{id}/rotate` returns a new key; old key is invalid; new key validates.
5. `POST /auth/token` (RFC 8693) with a valid user JWT returns a service JWT with `sub=service:synflux-router` and `exp` ≈ 5 min.
6. `synflux-router → synflux` call uses a service token obtained via RFC 8693 (verified by log entry `Using service token for synflux call`).
7. `ApiKeyLifecycleIT` and `TokenExchangeIT` pass in CI.
8. Phase 2 JWT auth regression tests pass unchanged.
9. Argon2id verification takes ≤ 200 ms per call in CI (bounded by cost factor config).

---

## 13. Follow-on Phases (Signposted)

- **Phase 4** - Revocation introspection endpoint so service token caches can be invalidated without restart.
- **Phase 4** - Fine-grained scope enforcement: `synapt` checks `SubjectAssertion.scopes` contains `search` before executing a search; `ingest` scope required for ingest.
- **Phase 5** - MTLS: `MtlsIdentityProvider` implementation using client certificate extraction from `X-Client-Cert` header (reverse proxy mode) or TLS context.
- **Phase 5** - OIDC federation: external IdP (Google, Azure AD) as an additional `IdentityProviderPort` implementation.
