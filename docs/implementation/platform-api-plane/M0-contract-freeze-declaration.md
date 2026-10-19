# M0-Contract-Freeze Declaration (BBAG-A)

**Status:** Frozen 2026-10-19 — all exit rows signed (row 6 record below). Last amended 2026-10-19 (row 6 sign-off; audience residual named in ADR-019). **Owner:** Andrei Minin. **Authors:** OpenAPI spec (D1) — Andrei Minin; MCP schema (D4) — Andrei Minin, D10 audit complete 2026-10-15 (decision: refactor), awaiting product call on schema shape; error model (RFC 9457 normalization) — Andrei Minin. **Sign-off authorities:** Architecture, Security, DX (per WS0 acceptance).

## Exit criteria (WS0)

1. **GATE — Q3-CRITICAL (D10): fail-open scope closed.** The `!scopes.isEmpty()`
   short-circuit in `McpEndpoint` L91 is removed, so `scopeEnforcer.allowed()`
   runs on every tools/call. Closes when, against a live token:
   - absent/empty scope set → **denied** (the exact fail-open shape)
   - unparseable scopes claim (non-textual, non-array) → **denied**
   - insufficient scope for the requested tool → **denied**
   - valid scope for tool A used against tool B → denied for B, **permitted for A**
   Evidence: test names + run output attached to the blocker, reviewed by Security.
   Not tradeable against criteria 3–5. While open, M0 does not freeze regardless
   of D1/D4 status.
2. 1.32 freeze packet landed (done — packet §4 executed, freeze-commit recorded).
3. A5 resolved (ADR-014: RAG 1.30-owned, 1.32-normalized).
4. OpenAPI spec passes linting; MCP tool schema validated against the pinned MCP
   revision (A3/D10); error model approved.
5. Contract review approved by Architecture, Security, DX.

Q3-HIGH (passthrough / wildcard) is tracked as an open security item but is not a
freeze gate; it may be sequenced against criteria 4–5.

**Not gating M0:** Q2 (discovery — no `oauth-protected-resource`, `GET /mcp`
pre-auth catalog; D10 work-item 3) is deliberately non-gating:
discovery, not enforcement. `*` issuance control is security-service side,
uninspectable from this repo per the audit scope; routed to blocker 2's cluster
via the D10 item-5 pointer. `contains("*")` in `ScopeEnforcer.allowed()`
correctly remains. Both tracked; neither blocks freeze.

## Blocker 1 — evidence (Q3-CRITICAL, fail-open scope)

**Reviewed by:** Andrei Minin, 2026-10-16

| Bullet | Test | Pre-fix | Post-fix |
|---|---|---|---|
| 1 — absent/empty scope → denied | `toolsCall_emptyScopeSet_denied` | FAIL — NeverWantedButInvoked: invoke | PASS |
| 2 — unparseable scopes → denied | `toolsCall_unparseableScopes_denied` | FAIL — NeverWantedButInvoked: invoke | PASS |
| 3 — insufficient scope → denied | `toolsCall_insufficientScope_denied` | PASS | PASS |
| 4a — scope A vs tool B → denied | `toolsCall_scopeForToolA_deniedForToolB` | PASS | PASS |
| 4b — positive control | `toolsCall_scopeForToolA_permittedForToolA` | PASS | PASS |

**Fix:** PR 211 (`f640817`) — `McpEndpoint` L91 `!scopes.isEmpty()` short-circuit
deleted; `scopeEnforcer.allowed()` runs on every `tools/call`.
**Precondition:** `allowed(tool, emptySet) == false` (`ScopeEnforcer` L11),
independently verified.
**Intent:** L91 traces to migration commit `8f4cd8d2` (2026-08-18), no recorded
purpose; no allow-list required.
**Runs:** pre-fix 5 run / 2 failed; post-fix 5 run / 0 failed. Full module suite green.
**Blast radius:** sole instance module-wide — search run; no sibling short-circuit
guards an authorization call.
**Boundary:** L98 passthrough (2a) out of scope; Q2 non-gating and `*` issuance
mapping acknowledged.

**Outcome:** Closed.

## Row 5 — error model approved

**Approved by:** Andrei Minin, 2026-10-16
**Artifact:** W2 — `docs/api/error-model.md` + `docs/api/rest/components/Problem.yaml`
(RFC 9457 + the 10 categories from Design 1.32 §62; extension fields per Appendix C).
**Consumed by:** W3 (search spec), W4 (auth spec) — `$ref`, not redefinition.
**Slice/hardening boundary recorded:** `type` URI registry and per-category
`details` schemas are hardening, not gate.

## Row 6 — Architecture + Security + DX sign-off

**Signed by:** Andrei Minin, 2026-10-19 (single reviewer holds A, S, and DX —
stated as a fact of the record, not a blocker).
**Implementation reference:** #252 / SNTP-032 — predicate implemented in
[#322](https://github.com/synanton/platform/pull/322) (green, CLEAN; the
predicate is `SecurityEnvelopeFilter` + `HttpEnvelopeVerifier` in
`shared/common`, registered in synapt and relix; 14-case matrix).

### Evidence package (verified against the security-provider, 2026-10-19)

| Citation | What it proves | Implementation match |
|---|---|---|
| `docs/api.md` § Conventions | tenant scope is an untrusted hint; mismatch fails closed `TENANT_DENIED` | filter returns 403 on `X-Tenant-ID` / `?tenant_id=` mismatch; tenant bound only from the validated context |
| `docs/design.md` § Security context schema (frozen) | canonical claims; forbidden `mask*`/`represent*`/`display*`/`classification*`/`redact*` rejected on server and consumer | verifier parses the documented shape; consumer mirrors the claim-smuggling rejection |
| `docs/api.md` § Consumer middleware | missing/invalid → 401; tenant hint mismatch → 403; **`X-User-ID` disagreeing with the validated principal → 403** | all three implemented — the third was found by this citation check and fixed in #322 |
| `README` § 9.1 (local verification) | TTL bounds (≤5m high-risk / ≤15m standard); centralized-only operation classes | predicate validates centrally via HTTP; identity cache TTL 60s — within the centralized bound |
| `README` § 11.2 (revocation SLO) | centralized ≤60s; local 5m/15m | cache TTL 60s matches the centralized revocation SLO exactly |

### Residuals — stated, not implied

1. **Audience binding is structural.** The envelope carries a single shared
   `aud: "synanton-internal"`; the provider's verifier does not enforce it and
   could not distinguish services even if it did. Cross-service replay
   protection rests on token lifetime and per-consumer authorization, not the
   envelope. Recorded in ADR-019's dated correction as a design property.
2. **Provider development configuration is a deployment item, not a predicate
   gap.** `configs/local.yaml` boots with `providers: []` — validation works,
   but minting an envelope needs an auth provider in the provider's dev stack.
   The predicate is executable today against a running provider.
3. **Single-reviewer sign-off.** A, S, and DX are held by one person; this row
   is a self-review. Stated so the record is honest.

**Cross-check surface verified complete (2026-10-19).** `pkg/middleware`'s
`http.go` reads exactly: `Authorization` (the token), tenant hints (default
`X-Tenant-ID`, extensible via `WithTenantHeaders`), `?tenant_id=`, and
`X-User-ID`. No other headers are compared against the validated context. The
Java predicate implements all four for the default configuration — the
citation check's finding was the complete gap, not one instance of a larger
class.

**Outcome:** Closed — M0 gate rows 1–6 closed; F2's predicate implemented
(its gate remains "hardening, not gating M0"; #322 closes it on merge).

## Coupling note (vertical slice)

BBAG-A search is structurally complete but semantically empty until the
vertical slice (ingestion → content cache → eventing → knowledge → search)
lands content — freeze the boundary first, then fill it. BBAG-A search ships
against fixtures; consumer-ready declaration waits for slice content.
1.27 remains co-scheduled, non-gating.

## On declaration

M0-contract-freeze declared → WS1–WS8 execute in parallel against the frozen contract. D10 audit complete 2026-10-15 (decision: refactor); it gated D4/M3, and its Q3-CRITICAL finding is now a freeze gate in its own right — see Exit criteria 1.

## External dependency (auth boundary)

Audience/issuer/expiry/tenant validation is delegated to `/auth/validate-header`.
D10 records this as the one remaining architectural unknown (not inspectable from
this repo). Blocker 1's fix presumes correctly-derived scopes; if that validation
is incomplete, scope enforcement is applied to tokens that should not be trusted.
To be confirmed by Security before blocker 6 sign-off — it does not block 2–5,
but blocker 6 must not close with it unresolved or explicitly accepted.

## Documented limitations (blocker 2c)

**Revocation window.** Validated identities are cached for up to
`authCache.ttlSeconds` (default 60s; configurable, default fallback in code).
Because the cache is keyed on the raw bearer (`McpAuthFilter` L57 / L72), a
token revoked upstream remains usable here until its entry expires — no
per-token invalidation is possible, only flush-on-expiry. The window is
bounded by TTL and accepted as-is; operators may lower `ttlSeconds` at the
cost of throughput against `/auth/validate-header`.

**Cache key.** The key is the raw bearer token, not a hash or identifier. This
is a separate hardening concern from the window itself — a heap dump or local
disclosure exposes live credentials. Tracked as blocker 2f, open; not gating
M0.

## Wildcard issuance (2b scope boundary)

`*` in `ScopeEnforcer.allowed()` is a grant mechanism, not a policy: issuance
policy (whether `*` is normally issueable, to whom, under what conditions) is
security-service side and uninspectable from this repo. Repo-side `contains("*")`
remains. Confirmation that `*` is not issueable in normal operation is Security's,
due with the 2b workstream — no repo action, tracked here so the grant path is
not mistaken for a reviewed issuance decision.
