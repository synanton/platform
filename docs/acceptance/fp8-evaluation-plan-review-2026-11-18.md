# Review: controlled FP8 evaluation proposal (#775)

**Mode:** review-only. No benchmark, GPU probe, execution, or merge.
**Reviewed:** PR #775, commit `7db9b9ab`.
**Conclusion:** The three-gate separation and safety boundaries are sound. The
plan needs decision-grade acceptance criteria and ownership fields before it is
approved as an executable plan.

## Findings

| Severity | Finding | Recommendation |
|---|---|---|
| Medium | Gate 1 says every call “completes or is classified,” while also requiring no OOM, crash, or timeout. A classified failure must not be counted as a passing performance run. | Define failure semantics: any crash/OOM/timeout makes the run `FAILED` or `INCONCLUSIVE`; classification is reporting, not acceptance. |
| Medium | Gates 1–3 describe evidence to collect but do not state the pre-registered pass/decision rule. Performance has no baseline comparison rule; quality has no non-inferiority/quality threshold; operational readiness has no measurable rollback/health criterion. | Add a per-gate decision table approved before execution. If a gate is intentionally report-only, label it report-only rather than “acceptance.” |
| Medium | Named execution and restoration owners are required in the prose but no fields or assignment mechanism exist. | Add an approval-record template with named execution owner, restoration owner, independent verifier, service owner, window, and rollback deadline. |
| Low | Gate progression is implied but not explicitly ordered. | State: plan approval → per-gate authorization → execution → report/review; passing one gate never authorizes the next. |
| Low | Credential handling says “scoped,” but does not explicitly forbid copying the production registry secret (the #773 lesson). | Add: no production registry secret in the probe namespace; use a dedicated scoped pull credential or explicitly approve and disclose an exception. |
| Low | Gate 3 lists monitoring/rollback but no measurable success criteria. | Require evidence of health/readiness, isolation, memory headroom, rollback completion, post-restore baseline, and named verifier sign-off. |
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

## Disposition

The proposal should be revised before approval. No execution is authorized by
this review. #775 remains open and unmerged. Production remains vLLM `8192/4`,
Synflux cap `2048`, `eval-smoke=regex`, two INFO replicas.
