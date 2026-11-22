# Gate 1 quality remediation protocol (pre-registered)

**Status:** registration only; execution not started.
**Depends on:** `docs/acceptance/fp8-gate1-performance-run-2026-11-18.md`,
`docs/acceptance/fp8-gate1-quality-blocker-2026-11-18.md`.
**Scope:** targeted DEV-only remediation experiments for deterministic invalid
candidate outputs.

## Decision boundary

- Gate 1 performance remains `PASS` (provisional).
- Output quality remains `NOT ACCEPTED`.
- This protocol does not authorize production rollout or production changes.

## Frozen baseline and artifacts

The following are frozen for all remediation experiments unless a specific
experiment declares one pre-registered change:

- Candidate model: `Qwen/Qwen3-8B-FP8` (revision
  `220b46e3b2180893580a4454f21f22d3ebb187d3`).
- Runtime image digest:
  `local-registry:5000/vllm-openai@sha256:082ca6f035279109041ffd3fe0695cb568b29bc580b35c4f297a66a08b216c1b`.
- Serving parameters baseline: `--max-model-len 8192`, `--max-num-seqs 1`,
  `temperature=0`, `max_tokens=4096`.
- Workload manifest hash (CRLF canonical):
  `9af1938a56c2be0a27d70c31b0d3adfaafbb55357c761845b01b733d0c0725ef`.
- Prompt template hash (`extraction-v1.txt`):
  `8a1a610cddc5e1ed21a54330ce95d5c1e07bddf2a06cfe9a271819c44b443f1e`.
- Rendered prompt hash used in Gate 1 run:
  `6901b657abe50ad9994a6df294bb322834f93186c524c117183dbc8c670e159f`.
- Original Gate 1 run result is immutable and retained as control evidence.

## Diagnostic set (DEV only)

- Target + control set:
  `docs/acceptance/artifacts/fp8-gate1/quality-remediation-2026-11-18/diagnostic-set.tsv`.
- Targets: 8 deterministic-failure ordinals (`252, 387, 423, 442, 453, 508,
  520, 524`).
- Controls: 10 unaffected ordinals from the same frozen 18-chunk DEV workload.
- HOLDOUT remains sealed and out of scope.

## Failure classes and independent experiments

Do not combine fixes in one revision. Run independent interventions so causal
effects remain attributable.

### E1 — truncation_at_max_tokens

- **Targets:** ordinals `442`, `520`.
- **Hypothesis:** these failures are primarily output-budget exhaustion.
- **Allowed change (only):** increase `max_tokens` for this experiment arm.
- **Fixed controls:** model, prompt, parsing, and all other serving params.
- **Success criteria:**
  - `finish_reason=length` rate on target ordinals decreases to `0/6`.
  - JSON-valid on target ordinals is at least `5/6`.
  - Control ordinals do not regress in JSON-valid rate.
- **Stop conditions:**
  - Any fatal runtime failure (OOM/crash/timeout).
  - p95 latency on controls exceeds 2.0x baseline control p95.

### E2 — reasoning_preface_multi_object

- **Targets:** ordinals `387`, `423`, `453`, `508`.
- **Hypothesis:** outputs include reasoning/preamble segments that contaminate
  first-object extraction.
- **Allowed change (only):** extraction-boundary strategy (single change in
  response-object extraction logic or strict single-object output enforcement at
  request/decoder layer).
- **Fixed controls:** `max_tokens=4096`, model, prompt text, and other params.
- **Success criteria:**
  - JSON-valid on this class reaches at least `10/12`.
  - `Extra data` parse failures for this class drop by at least 80%.
  - Control ordinals remain JSON-valid.
- **Stop conditions:**
  - Any fatal runtime failure.
  - New parse-failure class introduced on control ordinals.

### E3 — reasoning_preface_placeholder_tokens

- **Targets:** ordinals `252`, `524`.
- **Hypothesis:** placeholder tokens/ellipses in pseudo-JSON are causing parse
  failure despite stop termination.
- **Allowed change (only):** single targeted anti-placeholder intervention
  (prompt-contract addition or strict output sanitization rule), without
  combining with E1/E2 changes.
- **Fixed controls:** model, parser boundary from control arm, and serving
  parameters.
- **Success criteria:**
  - JSON-valid on this class reaches at least `5/6`.
  - `Expecting value` parse failures on this class are reduced to `0/6`.
  - Controls remain stable.
- **Stop conditions:**
  - Any fatal runtime failure.
  - New invalid-output class appears on controls.

## Protocol correction after E1 stop

E1 (`max_tokens=8192`) was stopped because vLLM rejects requests when
`prompt_tokens + max_tokens` exceeds the 8192-token model context. This is an
API validation constraint, not a model-quality result.

Before any E1 retry:

- measure the rendered prompt token count for every diagnostic request;
- choose a registered output budget satisfying
  `max_prompt_tokens + max_tokens <= 8192` with a stated safety margin;
- keep all other parameters frozen.

The corrected retry registration is separate:
`docs/acceptance/fp8-gate1-e1-retry-registration-2026-11-18.md`.

## Execution protocol (for future run)

1. Re-confirm frozen hashes and runtime identity.
2. Execute control pass on the diagnostic set with no remediation change.
3. Execute E1, E2, E3 independently (separate run IDs, separate evidence).
4. Preserve per-request records (latency, finish reason, usage, parse class,
   content SHA-256).
5. Compare each arm against its control subset and against original Gate 1 run.

## Reporting contract

Each experiment arm must produce:

- run metadata (model/image/hash/config),
- per-request table,
- aggregate by ordinal and failure class,
- PASS/FAIL/INCONCLUSIVE for that arm only,
- explicit statement: remediation-arm success is not full quality acceptance and
  not production authorization.

No experiment execution is authorized by this registration document alone.
