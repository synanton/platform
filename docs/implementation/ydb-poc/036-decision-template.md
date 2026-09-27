# Phase 6 (036) decision package template — evidence slots, no conclusions yet

Fill when 028 + 034 search-side land. The package must answer per capability,
not as a single verdict.

## Per-leg threshold gates (from 006, frozen)

| Capability | Threshold | Baseline | 024A | 024B | Pass? |
|---|---|---|---|---|---|
| Lexical p95 | ≤ 0.72ms | _pending 028_ | _pending_ | _pending_ | _ |
| Vector p95 | ≤ 9.5ms (+topology + dims annotated) | _ | _ | _ | _ |
| Hybrid p95 | ≤ 9.3ms (flagged build) | _ | _ | _ | _ |
| Lexical Recall@10 | ≥ 0.98 (fully-matching queries) | _ | _ | _ | _ |
| Vector Recall@10 | ≥ 0.95 absolute (pipeline-gated) | n/a (unmeasured) | n/a | _pending pipeline_ | _ |

## Conformance-adjusted comparison (mutually supported ops only)

_met PLUS: capability-cost annotation for YDB-only ops (atomic revisions)._

## Must-row disposition (from 004)

_Each Must: met / approved-alternative / open with owner. No silent closes._

## Outcomes 1–5 selection

_Selected outcome + which evidence forced it. If 028 never ran (baseline
unavailable): decide on bounded evidence with the gap documented, not fabricated._

## Baseline-unavailable fallback (if 028 cannot run)

Concludable without three legs: ports stable, contracts green, atomicity cost
measured (022/032), Gate 0 passed, migration feasible (035). NOT concludable:
any cross-system latency/recall comparison, cost-per-query, or "faster/slower"
claim. Phase 6 then selects on architecture-fit + risk, explicitly not on
performance comparison.
