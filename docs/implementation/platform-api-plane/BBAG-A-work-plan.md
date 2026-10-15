# Work Plan — 1.32 Early Implementation: Black Box API Generation A (BBAG-A)

**Status:** Draft · **Version:** 1.0 · **Date:** 2026-10-15
**Owner:** Platform Architecture / API
**Contributors:** Identity, Search, MCP, Security, SRE, Developer Experience

## 1. Purpose

Deliver the first thin external slice of Platform API 1.32 as a stable
black-box contract (consumers see only the public surface; internals stay
replaceable). Generation A covers: Login, Logout, Search API, MCP search.
Ontology API, async operations, admin APIs, and broader MCP tools are
explicitly deferred; 1.32 extends in future generations.

## 2. Scope

In scope: OAuth 2.1/OIDC login+logout (PKCE, refresh rotation, revocation,
RP-initiated logout), Search API over 1.31 (tenant-scoped, permission-filtered),
MCP search adapter over the same contract.
Out of scope: Ontology API (read and write), async/long-running ops (1.27),
full MCP ecosystem, admin/tenant/identity management, ingestion/eventing/
knowledge internals, non-search MCP resources and prompts.
Deferred but reserved: read-only Ontology discovery, more MCP tools,
Operation model for async tasks, device code / CIBA flows.

## 3. Guiding Principles

Contract-first (OpenAPI 3.1 + JSON Schema 2020-12); black-box compatibility;
security by design (OAuth 2.1, OIDC, PKCE, short-lived tokens, rotation,
revocation, audience validation); MCP compliance (JSON-RPC 2.0, Streamable
HTTP, tools/list + tools/call, OAuth 2.1 resource server); multi-tenant
isolation (principal + tenant + permission on every request); observability
(logs, metrics, traces, audit, SLOs from day one); industry standards
(RFC 9457 Problem Details, cursor pagination, rate limiting, idempotency);
minimal surface (smallest useful API, extend without breaking Gen A).

## 4. Architecture Summary

External Consumer / MCP Client → 1.32 External Boundary (Login/Logout,
Search API, MCP Search Adapter) → Identity 1.29 + Search 1.31 + AI Runtime
(adapter only). 1.32 owns the public contract; MCP is an adapter, not an
authority; login/logout use 1.29 semantics; search normalizes 1.31;
Ontology documented only as a future boundary.

## 5. Deliverables

D1 OpenAPI 3.1 spec (API) · D2 OAuth login/logout endpoints (Identity) ·
D3 Search API contract + implementation (Search) · D4 MCP search endpoint
(MCP) · D5 threat model + tests (Security) · D6 dashboards/alerts/SLOs
(SRE) · D7 docs + examples (DX) · D8 contract/integration/E2E tests (QA) ·
D9 release plan + rollout (Platform).

## 6. Workstreams and Tasks

- **WS0 Contract/Governance:** resource model (identity context, search
  request/result, error); OpenAPI 3.1 for login/logout/search; MCP tool
  schema; versioning/deprecation policy; RFC 9457 errors; pagination,
  filtering, sorting, rate-limit headers; Ontology reserved. Acceptance:
  lint passes; Architecture + Security + DX review; MCP schema validated.
- **WS1 Identity:** OAuth 2.1 code+PKCE; discovery + AS metadata endpoints;
  short-lived access + rotating refresh + reuse detection; revocation;
  RP-initiated logout; search/MCP scopes; confidential + public clients;
  audit events. Acceptance: no implicit/ROP grants; audience-bound
  tenant-scoped tokens; logout revokes + terminates; theft/replay/CSRF tests.
- **WS2 Search API:** request (query, filters, sort, page size, cursor);
  response (items, snippets, facets, next cursor); permission + tenant
  filtering at query time; cursor pagination; validation + limits; rate
  limiting + quotas; 1.31 errors → Problem Details; identity-aware caching
  only. Acceptance: no unauthorized results; stable cursors; p95/error SLOs
  defined; contract tests vs 1.31.
- **WS3 MCP Adapter:** Streamable HTTP endpoint; tools/list + tools/call;
  input/output mapping to the 1.32 contract; resource-server token
  validation (audience, scope, tenant); protected resource metadata; no
  passthrough/confused-deputy; MCP rate limits + audit. Acceptance:
  discovery + call work; wrong-audience rejected; same scope as REST.
- **WS4 Security/Privacy/Compliance:** threat model; OWASP ASVS + API Top 10;
  secrets + rotation; TLS/CORS/CSRF/headers; PII redaction; audit retention;
  pen-test before GA; dependency/container scanning in CI. Acceptance: no
  crit/high open; sign-off recorded; queryable audit.
- **WS5 Observability/SRE:** SLIs/SLOs; logs/metrics/traces; dashboards;
  alerts; health/readiness; runbooks; load tests. Acceptance: live before
  beta; runbooks reviewed; load targets met.
- **WS6 Testing/Quality:** unit, contract, integration (1.29/1.31), E2E
  (login→search→logout), negative (expired/revoked/wrong-audience),
  isolation/boundary, fuzz/property, consumer-driven contracts.
  Acceptance: CI gates; staging E2E green; no cross-tenant leakage.
- **WS7 Docs/DX:** reference, quickstart (<30 min to login+search), MCP
  setup, scopes/errors/limits/pagination, curl/JS/Python examples,
  ontology-reserved note, changelog. Acceptance: reviewed, runnable vs staging.
- **WS8 Release/Rollout:** flags, canary, compat checks, notes, rollback
  plan, post-release review + Gen B backlog. Acceptance: no SLO breach;
  rollback tested; Gen A stable.

## 7. Milestones

M0 Contract Freeze (W2: OpenAPI/MCP schema/error model approved) · M1 Auth
MVP (W4) · M2 Search MVP (W5) · M3 MCP Search (W6) · M4 Security Hardening
(W7) · M5 Beta (W8) · M6 GA (W9–10).

## 8. Acceptance Criteria for BBAG-A

OAuth 2.1 + OIDC + PKCE; logout revokes + terminates; tenant-scoped,
permission-filtered, cursor-paginated search; MCP tools/list + tools/call
with audience/scope/tenant validation; RFC 9457 errors; rate limiting,
audit, observability live; ontology deferred-not-implemented; no critical
findings; one external consumer completes E2E.

## 9. Risks and Mitigations

MCP auth complexity → decide early: 1.32 as OAuth AS vs external IdP/gateway.
Unclear search semantics → minimal normalized contract, defer advanced.
Async needs → stay synchronous, defer to 1.27. Ontology creep → reserved
boundary only. Tenant leakage → isolation tests + audit. Token passthrough
→ audience/scope enforcement.

## 10. Dependencies

1.29 identity stable; 1.31 retrieval available; MCP runtime/adapters;
security review capacity; SRE stack; one pilot consumer.

## 11. Future Extensions

Read-only Ontology API; more MCP tools; async via 1.27; admin/tenant APIs;
facets/semantic ranking/query expansion; device code/CIBA/DPoP.

## 12. Definition of Done

Contract frozen + versioned; login/logout/search/MCP search implemented;
Security/SRE/DX sign-offs; incident-free beta; GA with verified rollback;
extensions documented-not-implemented; boundary stable for Generation B.

## Appendix A — Endpoint Sketch

Discovery `GET /.well-known/openid-configuration` + `.../oauth-authorization-server`;
`GET /oauth/authorize`; `POST /oauth/token`; `POST /oauth/revoke`;
`GET|POST /logout`; `POST /v1/search`; `POST /mcp` (JSON-RPC tools/list, tools/call).

## Appendix B — MCP Search Tool Sketch

`search` tool: description + inputSchema {query (required), filters, limit
1–100, cursor}; searches platform content within authenticated tenant/permissions.
