# M0-Contract-Freeze Declaration (BBAG-A)

**Status:** Draft — review date TBD; last amended 2026-10-15 (A1: gate restructure; D10 complete). **Owner:** Andrei Minin. **Authors:** OpenAPI spec (D1) — Andrei Minin; MCP schema (D4) — Andrei Minin, D10 audit complete 2026-10-15 (decision: refactor), awaiting product call on schema shape; error model (RFC 9457 normalization) — Andrei Minin. **Sign-off authorities:** Architecture, Security, DX (per WS0 acceptance).

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
