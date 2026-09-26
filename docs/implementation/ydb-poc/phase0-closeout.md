# Phase 0 closeout summary (2026-09-26)

## Built (8 modules, all green)

`storage-contract` (shared types, metrics, conformance, `ActiveProviders`) ·
`synvault-api` / `synquest-api` (ports, provider-free) · `storage-testkit`
(contract + gating suites) · `synvault/synquest-inmemory` (full) ·
`synvault-cassandra` (honest subset: revision/delete UNSUPPORTED per 008) ·
`storage-provider` (selection + fail-fast validation).
Suites: 62+ unit green, 12 live-Cassandra green (shared container, PR cadence).

## Decided (records in `ydb-poc/`)

008 option (b) non-conformance · 010 throwaway-scope + escalation (decision
2026-10-03, silence defaults) · 024 split (024A new / 024B YDB) · 040
transitional CQL exception (workstream-owned) · Option A symmetric metrics ·
Option B vector-recall absolute (≥0.95) · Gate 0 eligibility-first with
collapse scoped to 024B (Outcome 5 reachable) · mixing supported · void 0.33ms
hybrid re-measured (7.75ms, full pipeline).

## Deferred (explicit, owned)

021, 024A, 024B + benchmarks (gated on 010) · provisional surfaces (until 010
flips) · 006 sign-off + 040 named confirmation (exit-review checklist) ·
Micrometer backend, relay freshness wiring (029), YDB adapter how-to, operator
guide, results write-up (all Phase 2+).

## Commits (branch `DESIGN-YDB-as-backend-behind-interface`)

f3c8314 (037) · 3733daa (011) · 7ac02c7 (011 closeout) · df4d68f (020+039) ·
6648ec4 (coordination) · 7e07627 (004+006+038) · 3774ea2 (findings) ·
d577565 (anomalies) · 5c47262 (exit checklist) · 2fc8405 + b922435 (benchmark
spec + hardening) · ccfd844 + 7b06eea (pre-flight + clarify).

## Exit state

Engineering complete. Outstanding: 010 decision, 006 sign-off line, 040 named
confirmation. No implementation proceeds (021/024) until 010 flips or throwaway
is formally activated.
