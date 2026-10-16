# D10 Audit Note — synanton-mcp conformance (WS3 gate)

**Status:** Complete 2026-10-15. **Forced decision: refactor** (structure sound, auth path needs work).
**Scope:** McpEndpoint, McpAuthFilter, McpSessionStore, ScopeEnforcer,
ToolRegistry, ToolHandler, SearchToolHandler + app wiring (commit
`8f4cd8d2f1a6bf7c622b77e12f5824d5d0ad7487`). `/auth/validate-header`
(external security service) not inspected.
**Spec pin:** MCP Streamable HTTP per protocol revision 2025-03-26 (as
declared by the module); authorization-spec sections per audit start
2026-10-15. Re-pin if the module moves revision.

## Q1 — Transport: not Streamable HTTP

Stateless JSON-RPC-over-HTTP; no SSE, no session management (McpSessionStore
orphaned — unreachable from endpoint, filter, or app config), no initialize
negotiation, no Mcp-Session-Id. Declared 2025-03-26 overstates compliance;
current spec removed sessions entirely. Recommend: declare stateless,
delete or explicitly mark the store unused (it has latent defects: no TTL,
unbounded growth, no fixation protection, unvalidated tenant/scopes).

## Q2 — Protected resource metadata: FAIL

No `/.well-known/oauth-protected-resource` mapping; 401s lack
`WWW-Authenticate` (discovery flow cannot trigger). GET /mcp bypasses auth
and serves the full tool catalog pre-auth (low severity, unmodeled surface).
Verify actuator exposure separately.

## Q3 — Token validation: CRITICAL + HIGH findings

- **Fail-open scope (CRITICAL):** empty scope set skips the guard — delete
  the `!scopes.isEmpty()` clause.
- **Wildcard scope (HIGH):** `*` grants — confirm not issueable normally.
> **Wildcard** relocated 2026-10-15 → same open security item as work-item 5.
> Tracked, not gating. See `M0-contract-freeze-declaration.md` exit criteria 4–5.
- **Passthrough confirmed (HIGH):** raw bearer forwarded verbatim by
  SearchToolHandler — largest refactor item.
- **Revocation window (MEDIUM):** 60s cache TTL — document or tighten.
- **Audience/issuer/expiry/tenant (OPEN):** fully delegated to
  `/auth/validate-header` — the one architectural unknown left.
- **Error semantics (MEDIUM–HIGH):** downstream failures delivered as
  success content (`isError: false`, "Error:"-prefixed text); exception
  messages leak internals. Propagate `isError: true`, generic messages.

## Q4 — Tool surface exceeds BBAG-A scope

Registry aggregates all handlers (search, graph_query, ontology_resolve,
synthesise, ingest_document, list_tenants, list_content, execution_trace,
usage_summary) with no profile gate. D4 input: config-driven curation
keyed to BBAG-A (search only).

## Q5 — D4 baseline differs from plan draft

Handler shape is `{query, top_k}`; plan sketched `{query, filters, limit,
cursor}`. Recommendation: conform D4 to the existing shape for BBAG-A,
defer filters/cursor to Generation B. Product call pending.

## Q6 — tools/list is static

Fixed per-handler schemas; freezable. Confirmed.

## Work items, in order

Work items: 7 identified, 5 remaining in this list, 2 relocated 2026-10-15
(item 2 → M0 gate; item 5 → open security item).

**Note (2026-10-15, A1):** Items 2 and 5 have been relocated out of this list.
Item 2 (fail-open, Q3-CRITICAL) is now **M0 freeze gate 1** in
`M0-contract-freeze-declaration.md` (Exit criteria 1) — it is not a D10 refactor
task and is not sequenced against items 1, 3, 4, 6, 7. Item 5 (passthrough /
wildcard, Q3-HIGH) is relocated as an open security item under the same
declaration; it is tracked, not gating. Numbering is retained so cross-references
stay stable; the list is not renumbered.

1. Session posture: declare stateless, remove/mark store, fix version pin.
2. ~~Fail-open scope guard; reject unauthenticated (drop `demo` default).~~ **MOVED 2026-10-15 → M0 freeze gate 1.**
   Not a D10 work item. See `M0-contract-freeze-declaration.md` Exit criteria 1.
3. WWW-Authenticate + resource metadata; close GET bypass.
4. Error semantics + message hygiene.
5. ~~Token passthrough (Q3-HIGH).~~ **MOVED 2026-10-15 → open security item.**
   Wildcard is tracked alongside it (see Q3 body pointer). Tracked, not gating.
   Sequenced against `M0-contract-freeze-declaration.md` exit criteria 4–5.
6. Tool curation for BBAG-A.
7. D4 schema reconciliation (product call).

## Remaining open (not closable from this repo)

`/auth/validate-header` behavior (Q3-e), `*` issuance policy (Q3-b),
actuator exposure, D4 product call (Q5).
