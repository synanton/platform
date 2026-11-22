# Gate 1 FP8 performance run — 2026-11-18

**Status:** Completed (performance gate scope only).
**Terminal gate decision (performance only):** PASS (provisional for Gate 1 only).
**Quality decision:** pending (not accepted by this run).

## Scope and control

- Baseline: Qwen3-4B (`gate1-baseline`), candidate: Qwen3-8B-FP8 (`gate1-fp8-8b`).
- Runtime image digest: `local-registry:5000/vllm-openai@sha256:082ca6f035279109041ffd3fe0695cb568b29bc580b35c4f297a66a08b216c1b`.
- Serving parameters: `--max-model-len 8192`, `--max-num-seqs 1`, `temperature=0`, `max_tokens=4096`.
- Workload: 18 DEV chunks × 3 repetitions = 54 measured requests/model (+1 warm-up/model).
- HOLDOUT remained excluded from evaluation labels/decision.

## Frozen artifacts and hashes

- Workload manifest: `docs/acceptance/artifacts/fp8-gate1/dev-workload.tsv`.
  - SHA-256 (CRLF canonical form in approval packet): `9af1938a56c2be0a27d70c31b0d3adfaafbb55357c761845b01b733d0c0725ef`.
  - SHA-256 (repository LF form): `8c65c1ccb8392c6208bf7b4a47c04747de799b585433a74fd1deca4d7e2ba834`.
- Prompt template hash (`extraction-v1.txt`): `8a1a610cddc5e1ed21a54330ce95d5c1e07bddf2a06cfe9a271819c44b443f1e`.
- Rendered prompt hash (run config): `6901b657abe50ad9994a6df294bb322834f93186c524c117183dbc8c670e159f`.

## Performance result

| Metric | Baseline | Candidate | Threshold | Result |
|---|---:|---:|---:|---|
| Completion | 54/54 | 54/54 | 54/54 | PASS |
| Fatal failures | 0 | 0 | 0 | PASS |
| p95 latency (s) | 52.92 | 91.19 | <= 105.84 | PASS |
| Completion tokens/s | 47.15 | 45.41 | >= 23.58 | PASS |
| Peak GPU used (MiB) | 12979 | 12521 | <= 15495 | PASS |

Candidate vs baseline:

- p95 latency change: +72.3%
- Throughput change: -3.7%

## Output validity signal (quality-pending evidence)

- Baseline JSON-valid: 54/54.
- Candidate JSON-valid: 30/54; JSON-invalid: 24/54.
- Candidate finish reasons: `stop=48`, `length=6`.
- Candidate invalid by finish reason: `length=6`, `stop=18`.
- Candidate parse-failure class: `JSONDecodeError` for all 24 invalid outputs.
- Invalid ordinals were deterministic across all 3 repetitions: `252, 387, 423, 442, 453, 508, 520, 524` (with `length` at `442` and `520`).

This run supports **performance gate PASS** only. It does not establish extraction-quality acceptance.

## Operational restore

- Production vLLM was scaled down for isolated execution and restored to 1/1.
- Post-restore checks: vLLM `/health=200`, Synflux 2/2.

## Evidence bundle

- Run bundle: `docs/acceptance/artifacts/fp8-gate1/run-2026-11-18`.
- Request-level table: `docs/acceptance/artifacts/fp8-gate1/run-2026-11-18/request-level-results.tsv`.
- Candidate invalids table: `docs/acceptance/artifacts/fp8-gate1/run-2026-11-18/candidate-invalid-responses.tsv`.
- Comparison summary: `docs/acceptance/artifacts/fp8-gate1/run-2026-11-18/comparison-summary.json`.
