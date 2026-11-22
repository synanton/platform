# 32K truncation pilot — registration only

**Status:** REGISTERED, NOT EXECUTED. No spend or inference authorized.
**Purpose:** test whether increasing `max_tokens` from 8192 to 32768 removes
`finish_reason=length` truncation without degrading regression controls.

## Frozen inputs

- Source: `evaluation/dev25/extracted/` (DEV only; no HOLDOUT).
- Selection: `evaluation/dev25/experiments/truncation-pilot-selection.tsv`
  (12 failed chunks across 11 documents + 3 successful controls).
- Prompt: `evaluation/prompts/llm_extraction_system.txt`
  (`92ab28a86ce777e792dbe0177b3c9bc50e55bb388416aae8b464d20ed69cd382`).
- Schema: extraction JSON object; schema hash
  `f0a3b5a893c61e1f68583faf8be56c9444f65aae52c05dae8b22e25f9f567f6c`.
- Model: `deepseek-v4-pro`, provider `opencode`, external-only.
- Model revision: not exposed by the provider; record model ID, upstream request
  IDs and response fingerprints for every call.
- Temperature 0; thinking disabled / `reasoning_effort=low`; identical chunks and
  settings except `max_tokens: 8192 → 32768`.
- No fallback model.

## Mandatory pre-inference gate

Before every call, use the PR #794 preflight:

- verify the provider's advertised maximum output from live provider metadata;
- require the source string to be recorded;
- fail closed if the advertised limit is absent, invalid, below 32768, or if any
  selected chunk would exceed it;
- record diagnostics with chunk ID, required tokens and provider cap;
- do not substitute a lower limit silently.

## Selection

- 12 failed chunks from `g7-dev-006/007/008/009/010/019/020/021/022/024/025`
  (one document contributes two chunks).
- 3 successful regression controls from `g7-dev-001/002/003`.
- Exact offsets and chunk hashes are frozen in the selection TSV.

## Cost estimate

- Input tokens (selected): 41,428.
- Worst-case failed output: 12 × 32,768 = 393,216 tokens.
- Control output observed: 3,556 tokens.
- At $0.30 / $1.20 per million input/output: **$0.489**; +20% retry allowance =
  **$0.586**.
- Proposed hard cap for the pilot: **$0.75**.
- Current remaining ceiling: $2.1001; this pilot is not authorized to spend it.

## Acceptance criteria

| Metric | Target |
|---|---|
| `finish_reason=length` | 0/12 pilot cases |
| JSON validity | 12/12 failed cases + 3/3 controls |
| Schema validity | 15/15 |
| Control regressions | 0 |
| Endpoint/evidence validity | No degradation vs controls |
| Spend | ≤ $0.75 |

Also record output tokens, latency, cost per valid extraction, and relation
precision/recall separately. Larger outputs must not be counted as success if
they add unsupported triples.

## Stop conditions

- Provider limit below 32768 or not verifiable.
- Any preflight failure.
- Any control regression or schema failure.
- Spend reaches $0.75.
- Any HOLDOUT or production access.

## Expansion

Phase 2 (retesting all 82 failures) is not authorized. It requires a separate
decision after the pilot is scored, including the observed truncation rate,
relation/evidence effects and remaining budget.

## Authorization boundary

No model calls, spend, HOLDOUT access, production change, or acceptance/ranking
claim is authorized by this registration. PR #794 remains open and unmerged.
