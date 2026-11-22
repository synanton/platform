# E1 retry registration — corrected context-budget intervention

**Status:** registration only; not executed.
**Parent protocol:** `docs/acceptance/fp8-gate1-quality-remediation-protocol-2026-11-18.md`.
**Prior result:** E1 `max_tokens=8192` was `STOPPED` by API context-budget
validation (`HTTP 400`), not by model quality.
**Scope:** DEV-only diagnostic set; HOLDOUT sealed; no production change.

## Frozen inputs

- Model: `Qwen/Qwen3-8B-FP8` revision
  `220b46e3b2180893580a4454f21f22d3ebb187d3`.
- Image: `local-registry:5000/vllm-openai@sha256:082ca6f035279109041ffd3fe0695cb568b29bc580b35c4f297a66a08b216c1b`.
- Node: `node3`; `--max-model-len 8192`; `--max-num-seqs 1`.
- Workload: frozen DEV diagnostic set
  `docs/acceptance/artifacts/fp8-gate1/quality-remediation-2026-11-18/diagnostic-set.tsv`.
- Prompt template hash: `8a1a610cddc5e1ed21a54330ce95d5c1e07bddf2a06cfe9a271819c44b443f1e`.
- Diagnostic workload hash used in control/E1:
  `2160d5f058ee56ac23ca5f0777c272bcc81a550e61962fb8f01d85e32d548dea`.

## Corrected intervention (single variable)

- `max_tokens=7168` (previous control `4096`; stopped E1 `8192`).
- Measured control prompt range: `617–862` tokens.
- Maximum allowed check: `862 + 7168 = 8030 <= 8192`; safety margin `162`
  tokens.
- All other request and serving parameters remain frozen: model, image, node,
  prompt, temperature `0`, workload, repetitions.

Budget table:
`docs/acceptance/artifacts/fp8-gate1/quality-remediation-2026-11-18/e1-retry-prompt-budget.tsv`.

## Execution prerequisites

Before starting, re-verify:
1. production is untouched and healthy;
2. node3 is isolated for the experiment;
3. scoped `gate1-registry-cred` is present;
4. prompt/workload/diagnostic hashes match;
5. measured prompt-token budget still satisfies the context inequality;
6. original control evidence and E1 stop evidence remain immutable.

## Success criteria (same as E1)

- Target ordinals `442`, `520`: `finish_reason=length` decreases to `0/6`.
- Target JSON-valid >= `5/6`.
- Control ordinals remain JSON-valid; no new failure class.
- No fatal OOM/crash/timeout; no transport failures.

## Stop conditions

- Any API validation error (including context-budget 400).
- Any fatal runtime failure.
- Any prompt-budget inequality violation.
- Control regression or new parse-failure class.

## Decision boundary

This registration does not authorize execution. A separate execution request is
required. A successful retry would address only the truncation class; it would
not establish overall extraction-quality acceptance or production rollout.
