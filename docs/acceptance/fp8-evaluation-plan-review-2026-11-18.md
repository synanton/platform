# Review: controlled FP8 evaluation proposal (#775)

**Mode:** review-only. No benchmark, GPU probe, execution, or merge.
**Reviewed:** PR #775, commit `7db9b9ab`.
**Conclusion:** The three-gate separation and safety boundaries are sound. The
plan has now been revised with decision-grade criteria and ownership fields and
is ready for normal reviewer approval. It is still not authorization to execute.

## Findings

| Severity | Finding | Recommendation |
|---|---|---|
| Medium | Gate 1 failure semantics | **Resolved** in #775 `f257d980`: PASS/FAIL/BLOCKED/INCONCLUSIVE semantics; OOM/timeout/crash are failures, never passes; bounded retry policy and full reporting required. |
| Medium | Pre-registered gate decisions | **Resolved** in #775 `f257d980`: explicit thresholds for performance, 5-point quality non-inferiority, and operational readiness/rollback criteria, all subject to pre-execution approval. |
| Medium | Named execution/restoration owners | **Resolved** in #775 `f257d980`: approval-record template requires named execution owner, restoration owner, independent verifier, service owner, window, scope, retry policy, and rollback deadline. |
| Low | Gate ordering | **Resolved**: sequential execution order is explicit; authorization remains independent and non-carrying. |
| Low | Registry credentials | **Resolved**: production pull secrets are explicitly prohibited; dedicated scoped credentials are mandatory; exceptions require approval/disclosure. |
| Low | Operational/rollback criteria | **Resolved**: 60-second readiness, 15-minute rollback, cleanup, baseline restoration, and independent verifier are explicit PASS/FAIL criteria. |
| None | DEV-only scope and sealed HOLDOUT are explicit. | Keep. |
| None | Compatibility is not treated as performance, quality, operational readiness, or production approval. | Keep. |
| None | The three gates are explicitly independent and cannot substitute for each other. | Keep. |

## Required plan corrections

1. **Gate 1 decision rule:** failure/OOM/timeout = non-pass; no production
   capacity claim; all measurements reported with raw counts and variance.
2. **Gate 2 decision rule:** pre-register parse/schema and semantic thresholds
   (or mark report-only), with DEV-only data; HOLDOUT remains closed unless a
   separate blind-evaluation authorization opens it.
3. **Gate 3 decision rule:** measurable isolation, health, monitoring, rollback,
   and post-restore evidence; a separate production-change decision remains
   mandatory.
4. **Ownership block:** named execution owner, restoration owner, independent
   verifier, service owner, approved window, hard stop, and rollback deadline.
5. **Credential control:** dedicated scoped registry credential; no production
   registry pull secret; any exception requires explicit approval and disclosure.
6. **Gate ordering:** approval and results are per-gate; no automatic carry-over.

## Re-review status

All #776 findings are resolved in #775 `f257d980`. The plan is now reviewable
as a decision-grade proposal. Normal reviewer approval and merge remain
required.

No experiment is authorized by this review. #775 remains open and unmerged.
Production remains vLLM `8192/4`, Synflux cap `2048`, `eval-smoke=regex`, two
INFO replicas.
