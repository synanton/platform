# Gate 1 quality remediation — control PASS and E1 STOPPED evidence

**Status:** evidence record only; no retry or remediation execution is authorized.
**Scope:** DEV-only diagnostic set; HOLDOUT sealed; production restored.

## Decision summary

| Record | Classification | Interpretation |
|---|---|---|
| Control retry | **PASS** | Frozen baseline configuration completed the diagnostic set successfully. |
| Attempt #1 | **INFRASTRUCTURE_ABORTED** | Dropped port-forward caused transport `Connection refused`; excluded from model-quality scoring. |
| E1 (`max_tokens=8192`) | **STOPPED** | Registered stop condition: all requests returned HTTP 400 API validation errors. |
| Production restoration | **PASS** | vLLM 1/1, health 200, Synflux 2/2, real inference succeeded, spec unchanged. |
| Overall quality decision | **PENDING** | E1 did not exercise the intervention; no quality/remediation conclusion. |

## Control retry — PASS

Run: `e1-control-2026-11-18-retry-01`.

- 54/54 measured requests completed.
- 0 fatal failures; 0 transport errors.
- 54/54 JSON-valid responses (diagnostic-set surface validity only).
- Full coverage: all 18 ordinals completed 3 repetitions each.
- Warmup recorded separately and excluded from measured metrics.
- Frozen artifact/invariant checks passed:
  - prompt hash `8a1a610c...`
  - diagnostic workload hash `2160d5f0...`
  - exact image digest and node3 unchanged
  - control `max_tokens=4096`; no remediation active

This establishes a valid control baseline for the diagnostic set. It is not a
quality-acceptance result for the candidate.

## Attempt #1 — INFRASTRUCTURE_ABORTED

Run: `e1-control-2026-11-18-attempt-01`.

- A dropped `kubectl port-forward` produced `URLError: Connection refused`.
- Preserved separately; not merged with the successful retry; not a model-quality
  failure.

## E1 — STOPPED (API validation / context budget)

Run: `e1-max8192-2026-11-18`.

- Registered intervention: increase `max_tokens` from 4096 to 8192; all other
  parameters frozen.
- All 54 measured requests plus warmup returned `HTTP 400 Bad Request`.
- Model maximum context: 8192 tokens.
- Requested output budget: 8192 tokens.
- Prompt is non-empty; `prompt_tokens + max_tokens` exceeds the context budget.
- API error: `BadRequestError`, `param=input_text`, code 400.
- No OOM, crash, or model-load failure was observed.
- Classification: **STOPPED**, not a quality failure; E1 intervention was not
  successfully exercised.
- Exact E1 client response body was not retained by the harness; the
  representative exact validation body is preserved in the evidence bundle and
  the server logs record 400.

## Production restoration — PASS

Verified at `2026-11-18T18:40:38Z`:

1. Production vLLM: spec replicas 1, ready 1.
2. Production `/health`: HTTP 200.
3. Synflux: spec replicas 2, ready 2.
4. Real inference request: HTTP 200, `finish_reason=stop`.
5. Candidate Pod removed; `synanton-gate1` has no Pods; candidate tunnel stopped.
6. Production spec matches pre-experiment snapshot
   (`sha256=4be845ae...`, `spec_equal=true`); image/args unchanged.

## Protocol implication

Before any E1 retry, register:
- measured prompt-token budget for each diagnostic request;
- a `max_tokens` value satisfying `prompt_tokens + max_tokens <= 8192`;
- an explicit upper bound/tested value, without changing other parameters.

No retry is registered by this evidence record. E2/E3 remain unstarted.

## Evidence bundle

`docs/acceptance/artifacts/fp8-gate1/quality-remediation-2026-11-18/` contains:

- `control-retry-01/` — summary, `request-stream.jsonl`, GPU samples, invariants.
- `attempt-01-infrastructure-abort/` — preserved aborted attempt, `request-stream.jsonl`.
- `e1-max8192-stop/` — summary, `request-stream.jsonl`, server startup/400 logs, HTTP 400 evidence.
- `production-deploy-before-e1.yaml` — pre-scale production spec.
- `restoration-verification.txt` — restoration checks.
- `comparison-summary.json` — control/E1 aggregate accounting.
