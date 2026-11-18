# Gate 1 follow-up blocker — output validity classification (2026-11-18)

**Scope:** classify invalid candidate outputs from the completed Gate 1 run.
**Source run:** `docs/acceptance/fp8-gate1-performance-run-2026-11-18.md`.
**Result:** performance gate remains PASS; extraction-quality acceptance remains blocked.

## Evidence base

- Candidate summary: `docs/acceptance/artifacts/fp8-gate1/run-2026-11-18/candidate-summary.json`
- Invalid rows table: `docs/acceptance/artifacts/fp8-gate1/run-2026-11-18/candidate-invalid-classification.tsv`
- Invalid aggregate: `docs/acceptance/artifacts/fp8-gate1/run-2026-11-18/candidate-invalid-aggregate.tsv`

## Classified invalid outputs

Candidate invalid outputs are deterministic across all three repetitions on the
same eight ordinals (`252, 387, 423, 442, 453, 508, 520, 524`):

| Class | Count | Ordinals | Interpretation |
|---|---:|---|---|
| `truncation_at_max_tokens` | 6 | `442`, `520` (all 3 reps each) | `finish_reason=length`; generation hit the 4096-token cap before a parseable JSON object could be extracted. |
| `reasoning_preface_multi_object` | 12 | `387`, `423`, `453`, `508` (all 3 reps each) | `finish_reason=stop` but output includes reasoning/preface JSON-like fragments before final body; parser extracts first `{...}` span and fails with `Extra data`. |
| `reasoning_preface_placeholder_tokens` | 6 | `252`, `524` (all 3 reps each) | `finish_reason=stop` but extracted span contains placeholder tokens (for example `...`), causing `Expecting value`. |

Totals: 24/54 invalid (`44.4%`), 30/54 valid (`55.6%`).

## Decision impact

- **Gate 1 performance:** unchanged (`PASS`), because completion/failure/latency/throughput/memory thresholds all passed.
- **Output validity / extraction quality:** **NOT ACCEPTED** from this run.
- **Production rollout:** still not authorized.

## Next blocker (quality path)

1. Validate whether invalid rows are recoverable by extraction-boundary hardening
   (for example parse the final JSON object span instead of first-span parsing)
   without changing model, workload, or prompt artifacts.
2. Investigate the two persistent `length` ordinals (`442`, `520`) separately
   from stop-terminated malformed outputs.
3. Re-run only after pre-registering the quality-evaluation method and keeping
   HOLDOUT sealed.

No production configuration change is authorized by this classification record.
