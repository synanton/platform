# AI-assisted annotation — execution registration (preparation only)

**Status:** PREPARATION. No AI-assisted annotation calls are authorized by this
document. A separate, narrow authorization is required.

## Purpose

Reduce manual effort for the 20 UNLABELED DEV documents by producing candidate
annotations. The AI output is never a gold label.

## Proposed annotator model

- Provider: OpenCode Zen (`opencode` arm), external-only, no local fallback.
- Proposed model ID: `gemini-3.5-flash` (a non-candidate model).
- Restriction: must NOT be a Phase 2 candidate (`deepseek-v4-pro` or
  `glm-5.3-flash`). Reject any accidental overlap with the evaluation set.
- Final model ID and revision must be recorded at call time from `GET /models`.

## Prompts and settings

- Base extraction prompt: `evaluation/prompts/llm_extraction_system.txt`
  (hash `92ab28a86ce777e792dbe0177b3c9bc50e55bb388416aae8b464d20ed69cd382`).
- Annotator wrapper: `evaluation/dev25/ai-annotation-prompt.txt` (preparation).
- Output contract: the extraction JSON object; schema hash
  `f0a3b5a893c61e1f68583faf8be56c9444f65aae52c05dae8b22e25f9f567f6c`.
- Settings: `temperature=0`, thinking disabled / `reasoning_effort=low`,
  `max_tokens=2048` per document chunk.
- Schema version and prompt version are recorded per request.

## Token and cost estimate (20 unlabeled documents)

Assumes PDF text extraction produces ~1,500 input tokens and ~600 output tokens
per document (conservative, chunked):

- Input: 20 × 1,500 = 30,000 tokens.
- Output: 20 × 600 = 12,000 tokens.
- At a conservative $0.15 / $0.50 per million tokens:
  - input ≈ $0.005, output ≈ $0.006, total ≈ **$0.011**.
- Proposed spend cap for AI assistance: **$0.05**.
- These are estimates only; actual cost is recorded from provider usage.

## Limits and stop conditions

- Stop if estimated or actual spend exceeds $0.05.
- Stop if any candidate output fails schema validation.
- Stop if the annotator model equals a Phase 2 candidate.
- Stop on any HOLDOUT access or production change.
- Stop if PDF text extraction cannot be reproduced deterministically.
- No gold mutation; proposals are stored separately and never imported.

## Provenance required per call

Model/provider, model ID, model revision (if exposed), prompt version, schema
version, generation settings, timestamp, and request IDs.
