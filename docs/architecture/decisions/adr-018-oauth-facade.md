# ADR-018: OAuth Surface — Platform as Authorization-Server Facade

**Status:** Proposed 2026-10-16
**Basis:** W4 cannot be drafted without the top-level OAuth shape. BBAG-A WS1 specifies POST /oauth/token, POST /oauth/revoke, discovery and AS metadata, confidential and public clients, and refresh rotation with reuse detection — an authorization-server surface. A W4 draft modeled as a pure relying party was blocked for omitting all of it. Separately, the MCP filter delegates token validation outward (/auth/validate-header), which a self-contained AS would not do. Both facts are consistent with one shape: the platform fronts an AS, backed by the external security service.

## Decision

1. Platform is an OAuth 2.1 authorization-server facade. It exposes the issuance surface (/oauth/token, /oauth/revoke, discovery) and federates user authentication to the external security service. It does not maintain its own credential store.
2. Validation delegation is unchanged. /auth/validate-header remains as-is. Issuance and validation are separate concerns meeting at the same boundary.
3. Endpoint families (paths finalized in W4 against the /v1/ convention; protocol endpoints unprefixed):
   - /v1/auth/login, /v1/auth/callback, /v1/auth/logout — platform UI flow, retained
   - /oauth/token, /oauth/revoke — protocol endpoints, platform-chosen, unprefixed
   - /.well-known/oauth-authorization-server, /.well-known/openid-configuration — protocol-fixed, root-anchored

Consequences:
- W4 shape: covers both families. The draft modeled only the first.
- Filter unchanged: no code change to McpAuthFilter from this decision.
- F3 re-enters: token-exchange support (RFC 8693) becomes relevant to /oauth/token's grant types — a separate question from 2a's downstream-credential F3, same external inquiry. Two dependents become three.
- Sub-choices flagged, not decided: (a) does /oauth/authorize coexist with /auth/login or replace it; (b) is /oauth/token pass-through, platform-issued, or exchange. Both shape the spec; neither blocks this ADR.
- Rejected: pure RP (fails WS1); full AS (contradicts outward validation delegation).
- Neutral: W1, W2, W3 unaffected.

## Context

The W4 draft inferred relying-party shape from the validation-delegation pattern and did not check it against WS1's endpoint list. The reconciliation — facade, not pure RP, not pure AS — preserves both signals. W4 proceeds against this shape once accepted.
