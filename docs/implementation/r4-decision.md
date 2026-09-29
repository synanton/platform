# R4 Decision — PG Phase 2 Routing

**Status:** Decided (2026-09-29, routing act — no judgment call)
**Input:** `r3-verdict.json` (R3-CONDITIONAL-PASS)

## Routing (pre-specified before R3 ran)

- Hard-gate fail on any leg → halt PG Phase 2. Not applicable: 0 identity
  fails on both legs; lexical 1.0 on both.
- Both legs fail hard → framing revision. Not applicable.
- R3 passes → PG Phase 2 opens. **Selected.**

## Decision

PG Phase 2 (PG-POC-004/007) opens per proposal §0.2 Decision 3. The remaining
judgment (close at three legs vs continue to four) is recorded, not resolved
here: the proposal commits to four legs, so Phase 2 opens; the workstream may
explicitly pause it and record R3 as the decision point.

## Evidence pointers

- `r3-verdict.json` (machine verdicts, frozen tolerances, semantic annotations)
- `runs/{baseline,cassandra,ydb}-v1-convergence.md` (full per-leg reports)
- 24 structural empties identical across all three legs (cross-validity)
