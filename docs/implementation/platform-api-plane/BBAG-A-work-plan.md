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
knowledge internals, non-search MCP resources and prompts, filter semantics
(owned by 1.31 — 1.32 passes filters through opaquely).
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

## Plan Amendments (BBAG-A review, 2026-10-15)

### A1. WS0/M0 entry condition + 1.27 gate answer

- Entry condition: the 1.32 freeze packet (`docs/implementation/design-1-32-freeze-packet.md`)
  landed or explicitly scheduled. Until then M0–M2 dates are aspirational.
- Split milestone: **M0-entry** (freeze packet landed/scheduled) vs
  **M0-contract-freeze** (BBAG-A OpenAPI + MCP schema + error model approved).
- 1.27 gate answer: BBAG-A is synchronous-only
  by scope (§2.2, WS2) — no 1.27 content gates it functionally. 1.27 is
  co-scheduled, not co-frozen for this generation; async-contract slippage
  cannot float BBAG-A. The Operation model stays reserved (future extension).
- Confirmed (review): asymmetric — 1.27 does not gate BBAG-A entry or exit,
  but BBAG-A outputs are advisory inputs to 1.27 contract design (a future
  Operation wrapping BBAG-A search takes the 1.32 response shape as input).
  One-directional, recorded.

### A2. WS2 evidence citation + risk downgrade

- Rationale cites: q2 case (dense-#3 buried by RRF — benchmark tracker F-tune)
  and corpus-dependent engine rankings (framework §1b-ii) as the measured
  basis for the minimal normalized contract.
- Risk row "Search semantics unclear / contract churn" downgraded to
  **monitored via corpus benchmarks** (demo n=60 + RAGMC n=1216 legs rerun
  per release; any ranking inversion reopens the row).
- Revisit trigger (review): if the RRF-burial rate exceeds 5% of gold-bearing
  queries on any release corpus, escalate to Generation B advanced-semantics
  scoping. (Calibrate: set at 1.5× the observed burial rate on the release
  corpus; 5% is provisional until the first calibration run.)
- Two failure modes distinguished: dense-#3 burial is a *ranking* failure
  (RRF math discards leg strength); corpus-dependent rankings are a
  *generalization* failure (no engine dominates both corpora). Both justify
  minimalism; they map to different future work (fusion design vs engine
  selection evidence).

### A3. WS3 audit gate + reframe

- Reframe WS3 from "Implement MCP endpoint" to "Harden and conform MCP module".
- Timeboxed audit gate before M3 sizing — answer three spec questions:
  (a) Streamable HTTP vs older transport; (b) protected resource metadata
  published per the MCP authorization spec; (c) token validation depth
  (audience binding, scope, tenant, explicit passthrough rejection).
  Timebox: 3–5 working days with a written audit note as the deliverable
  (D10 in §5, making the M3 date defensible in review); if the audit
  cannot answer (a)/(b)/(c) in the window, default is refactor, not rebuild.
- Forced decision: integrate-and-conform / refactor / rebuild. Not a
  discovery project.
- Spec revisions for the audit: pin the MCP spec revision specifically (dated
  revision — the MCP spec has moved several times; an unpinned reference
  drifts between audit and implementation), alongside the authorization-spec
  sections and retrieval date.

### A4. WS6 corpus pointer + tenancy-mapping caveat

- Isolation/boundary tests reuse the RAGMC 5-enterprise corpus + staged
  tenant infra (`docs/benchmark/datasets.md`) instead of building fixtures.
- Caveat: benchmark tenancy semantics must be mapped to the 1.29
  tenant/principal model in a validation step — the corpus is authoritative
  for leakage testing but must not silently redefine tenant boundaries.
  The mapping is a reviewed artifact (Identity reviews before isolation
  tests are trusted), not a code comment.

### A5. RAG API deferral line (§2.3)

- Add to Deferred but Reserved: "RAG API (1.32 §§51–52) deferred."
- Authority question open for Generation B scoping: §51 frames it as a
  higher-level *application* API over Search, which reads 1.30-owned and
  would make the deferral trivial by authority; if ruled 1.32-owned, the
  deferral is a real scope decision. Owner: Architecture; decision due
  before M0-contract-freeze (cheap now, shapes the boundary later).

### A6. p95 evidence note (WS2/WS5)

- WS2/WS5 acceptance records bench evidence (tens-of-ms across all legs)
  as the basis for the <150ms SLO; the WS5 load test remains the validator
  (bench legs aren't production-under-load). Supported by evidence, not
  proven — headroom kept, number not tightened. The load test defines its
  envelope explicitly (target RPS + concurrency); SLO review triggers if
  headroom drops below 2x measured. The trigger gates GA: headroom below 2x
  blocks the GA declaration until re-validated, not merely noted.

### A7. Appendix and acceptance refinements

- Appendix B: `filters` is an opaque key-value object — clients must not
  guess its schema; server ignores unknown keys (fail-closed on malformed).
  Explicit non-goal: filter semantics are owned by 1.31; 1.32 passes them
  through opaquely (consumers must not expect 1.32 to reject bad filters).
- Appendix A: `/logout` is GET (OIDC RP-Initiated Logout 1.0 uses GET to the
  end-session endpoint); drop the POST alternative at contract freeze unless
  a second spec requires it. Confirm OIDC discovery advertises the
  end_session_endpoint with a matching method.
- WS1 acceptance: refresh-token reuse detection revokes the entire token
  family, not just the reused token (concrete negative case — exercised in
  WS6 negative tests, not just unit tests).
- WS8 beta acceptance: "no critical incidents and error budget burn within
  policy" (GA keeps the stricter "no SLO breach").
