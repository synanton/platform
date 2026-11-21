# AI-assisted annotation — execution registration (preparation only)

**Status:** PREPARATION. No AI-assisted annotation calls are authorized by this
document. A separate, narrow authorization is required.

## Purpose

Reduce manual effort for the 20 UNLABELED DEV documents by producing candidate
annotations. The AI output is never a gold label.

## Proposed annotator model

- Provider: OpenCode Zen (`opencode` arm), external-only, no local fallback.
- Proposed model ID: `deepseek-v4.1-flash` (a non-candidate model, cheaper).
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
- Annotator pricing (DeepSeek V4.1 Flash, off-peak): $0.15 / $0.60 per million tokens.
- Estimated for the 9 small text/EPUB documents (~24k chars input, ~6k output):
  input ≈ $0.001, output ≈ $0.004, total ≈ **$0.005**.
- The 11 PDFs (2.23M chars) require chunking and a separate estimate; annotating
  them fully exceeds the $0.05 cap and is deferred.
- Proposed spend cap for AI assistance: **$0.05** (not to be exceeded).
- These are estimates only; actual cost is recorded from provider usage.

## Actual usage so far (completed)

- All 20 unlabeled documents annotated (9 small + 11 PDF, chunked) and reviewed
  into validated gold; the full set is now 25/25 validated.
- Recorded annotator tokens: 535,967 input / 875,776 output.
- Estimated annotator cost: **$0.606** (under the $2.00 approved budget).
- The 11 PDFs are full books annotated chunk-by-chunk; coverage is partial
  rather than exhaustive and is documented in the release record.

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
