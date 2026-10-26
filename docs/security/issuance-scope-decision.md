# Issuance scope decision — demo auth vs production IdP

**Decision date:** 2026-10-25. **Decision:** #340 is **demo issuance only**;
production identity is split to #436.
**Implementation status:** demo issuer + fail-closed audit implemented; HTTP/UI
switch and durable production keys remain out of scope.

## Why

The claim-to-capability mapping and `ServicePrincipal` contract are implemented
and ready for a small issuer. RS256/JWKS, key rotation, denylist revocation,
refresh-cookie flows, and real-IdP integration are production identity work and
would turn #340 into a different project. The demo path only needs a
replaceable, short-lived issuer that produces the same principal the facade
already accepts.

## Demo scope (#340)

| Concern | Demo decision |
|---|---|
| Token | Tenant-bound access token, target 15-minute TTL; HS256/dev key or equivalent demo provider |
| Revocation | TTL only; no denylist, no rotation, no refresh-token flow. **Coupling:** token-carried claims mean revocation cannot be immediate without a per-request lookup path; that is a different token design and belongs to #436. |
| Claims | `iss`, `aud`, `sub`, `exp`, `nbf`, `jti`, `scope`, `tenant_id`, `token_type=tenant_access` |
| Capabilities | Claims select only from the compiled `ServiceCapability` set via `ServicePrincipal.fromClaims`; blank/unknown claim fails at issuance |
| Principal | Same `ServicePrincipal` type the facade accepts; no parallel principal |
| Issuance audit | **Required:** principal, tenant, `jti`, issuer, iat/exp, request context, outcome; no secrets |
| Provider | Configuration-driven and replaceable by the production issuer without changing the `ServicePrincipal` contract |

Not in #340: RS256/JWKS, 90-day rotation, denylist, refresh cookies,
production CORS/CSRF/CSP, real IdP integration. Those are #436.

## Non-negotiable

Issuance audit is the security-load-bearing piece in this slice. A minted token
with no record is not acceptable even in the demo. The claim mapping is already
closed and tested; the issuer must use it rather than re-deriving capabilities.

## Implemented 2026-10-25

- `DemoTenantTokenIssuer` (security module): HS256, 15-minute max TTL,
  tenant-bound claims, `ServicePrincipal.fromClaims` mapping, issuance audit
  before token return.
- `IssuanceAudit` port + `JdbcIssuanceAudit` + `security.issuance_audit`
  migration.
- Fail-closed tests: audit write failure → `IssuanceAuditException`, no token;
  unknown claim fails before audit; TTL > 900 rejected.
- Not yet: HTTP `POST /v1/auth/tenant` exposure, UI switch from Vite dev auth,
  refresh/rotation/denylist (#436).

## Definition of done for #340

1. Demo issuer mints a tenant-bound token from configuration.
2. Claims map to the same `ServicePrincipal`; unknown/blank claims fail at
   issuance.
3. Issuance event is audited.
4. Tests cover mint, claim mapping/rejection, TTL expiry, and audit.
5. No production key/rotation/revocation claims.
