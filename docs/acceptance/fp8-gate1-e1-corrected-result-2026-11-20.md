# Corrected E1 result — truncated-output remediation

**Run:** `e1-corrected-2026-11-20-r2`
**Status:** **FAIL** against the registered E1 criteria.
**Scope:** DEV-only diagnostic set; HOLDOUT sealed; no production rollout.

## Result summary

| Metric | Result |
|---|---:|
| Scheduled / completed | 54 / 54 |
| Fatal / transport / API errors | 0 / 0 / 0 |
| JSON-valid responses | 36 / 54 |
| `finish_reason` | `stop=54` |
| `max_tokens` | 7168 |
| Maximum prompt tokens | 862 |
| Context budget check | 862 + 7168 = 8030 ≤ 8192 |
| p95 latency | 131.51 s |
| Completion throughput | 45.22 tokens/s |

The run had no API-validation or truncation termination. The corrected budget
successfully exercised the intervention.

## Registered criteria assessment

| Criterion | Observation | Result |
|---|---|---|
| Targets `442`, `520`: `length` → 0/6 | 0/6 | PASS |
| Targets `442`, `520`: JSON-valid ≥ 5/6 | 6/6 | PASS |
| Control ordinals remain JSON-valid | 3 controls invalid | **FAIL** |
| No new failure class on controls | New invalid outputs on `150`, `427`, `526` | **FAIL** |
| No fatal OOM/crash/timeout | 0 | PASS |

### Target outcomes

- Fixed: `442` `3/3` valid, `520` `3/3` valid; no `length`.
- Also valid: `423`, `508`, `524` (`3/3` each).
- Still invalid: `252`, `387`, `453` (`0/3` each).

### Control regression

Control ordinals `150`, `427`, and `526` became invalid in all 3 repetitions
under `max_tokens=7168`; they were JSON-valid in the 4096 control run. The new
parse failures are `JSONDecodeError` (`Expecting value` / `Extra data`) with
`finish_reason=stop`.

This is a genuine control regression under the registered criteria. E1 therefore
does **not** establish truncation remediation as a safe intervention.

## Attempt separation

- `e1-invalid-workload-order-2026-11-20`: stopped before completion because the
  rebuilt workload was sorted by ordinal; hash was `7412b2ac…`, not the
  registered `2160d5f0…`. No result.
- The earlier maintenance-interrupted corrected attempt is also excluded from
  results.
- The final run used the exact registered target-first workload hash
  `2160d5f058ee56ac23ca5f0777c272bcc81a550e61962fb8f01d85e32d548dea`.

## Frozen identities

- Model: `Qwen/Qwen3-8B-FP8`, revision
  `220b46e3b2180893580a4454f21f22d3ebb187d3`.
- Image: `local-registry:5000/vllm-openai@sha256:082ca6f035279109041ffd3fe0695cb568b29bc580b35c4f297a66a08b216c1b`.
- Prompt template: `8a1a610cddc5e1ed21a54330ce95d5c1e07bddf2a06cfe9a271819c44b443f1e`.
- Serving: node3, `--max-model-len 8192`, `--max-num-seqs 1`, `temperature=0`.

## Production restoration

- vLLM: spec `1`, ready `1`.
- Synflux: spec `2`, ready `2`.
- Production spec unchanged: `spec_equal=true`;
  spec SHA-256 `4be845ae273974d5a66bc2efba6a8c102bc99837691ae9ccc625804770e4e800`.
- Image and args restored unchanged.
- Candidate Pod removed; `synanton-gate1` has no Pods.

## Decision

**E1 FAIL**: the truncation-specific signal is positive for `442`/`520`, but the
registered control non-regression criterion failed. Do not start E2 or E3 from
this result. No extraction-quality acceptance or production authorization
follows.

## Evidence bundle

`docs/acceptance/artifacts/fp8-gate1/quality-remediation-2026-11-18/`:

- `e1-corrected-2026-11-20-r2/` — final summary, request-level results, logs,
  invariants, pre-scale production snapshot.
- `e1-invalid-workload-order-2026-11-20/` — invalid-order attempt, excluded.
