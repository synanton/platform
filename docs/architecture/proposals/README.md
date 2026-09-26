# Proposals index — YDB PoC branch

Branch: `DESIGN-YDB-as-backend-behind-interface`. Start here if you are picking
up this branch after 010 flips (either way). Orient in ten minutes.

## Status per artifact

| Artifact | Status | Lives at |
|---|---|---|
| YDB backend proposal (Rev 6 + branch amendments) | Draft proposal, subordinate to Arch 1.0 | `Design Proposal Evaluate YDB as a SynvaultSynquest Backend.md` |
| **Provisional surfaces — load-bearing until 010 flips** | **Open, do not close in transition** | `../implementation/ydb-poc/011-provisional-followup.md` |
| **010 escalation (decision by 2026-10-03)** | **Escalated; silence defaults to throwaway** | `../implementation/ydb-poc/010-escalation.md` |
| Phase-0 exit checklist | Engineering done; formalities + gate outstanding | `../implementation/ydb-poc/phase0-exit-checklist.md` |
| Implementation plan + ticket tracker (40 tickets) | Current — status note at top | `../implementation/ydb-poc-implementation-plan.md` |
| Phase-0 closeout summary | Closed | `../implementation/ydb-poc/phase0-closeout.md` |
| Dev guide (running tests) | Current | `../implementation/ydb-poc/dev-guide-tests.md` |
| Port reference | Current | `../implementation/ydb-poc/port-reference.md` |
| Conformance reference | Current | `../implementation/ydb-poc/conformance-reference.md` |
| Provider selection guide | Current | `../implementation/ydb-poc/provider-selection-guide.md` |
| Benchmark execution plan (spec, not authorization) | Specified; executes when 010 opens 021/024 | `../implementation/ydb-poc/benchmark-execution-plan.md` |
| Phase-2 pre-flight (Gate 0 eligibility-first) | Specified | `../implementation/ydb-poc/phase2-preflight.md` |
| Live-test policy | Decided (PR cadence, shared container) | `../implementation/ydb-poc/live-test-policy.md` |

## Supersession chain (newest first)

`phase0-exit-checklist` → `benchmark-execution-plan` hardening → pre-flight →
pre-flight-clarify. Each commit message names its tickets; `git log --oneline`
on this branch is the authoritative order. Docs never supersede silently — a
replaced claim is marked void where it stood (e.g. the 0.33ms hybrid number in
`006-baseline-thresholds.md`).

## What this branch does not touch (by decision)

- Platform README — YDB is a PoC, not a commitment. Current-state backends stand.
- Architecture 1.0 and normative designs — the proposal is subordinate; PoC
  learnings reach architecture only via a Phase-6 change request.
- No YDB adapter how-to (024B doesn't exist), no operator guide (nothing
  deployed), no benchmark results write-up (028 hasn't run).
