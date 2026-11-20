# GPU-7 Phase 2 — DEV evaluation registration

**Status:** REGISTERED, NOT EXECUTED.
**Reason for hold:** the required 40–50 gold-labeled DEV documents do not yet
exist; current frozen DEV gold has 5 documents. No paid calls are authorized
until the label set and budget are approved.

## Candidates (only two)

| Candidate | Provider ID | Status |
|---|---|---|
| DeepSeek V4 Pro | `deepseek-v4-pro` | Verified live in OpenCode Zen |
| GLM-5.3-Flash | `glm-5.3-flash` | Verified live in OpenCode Zen |

No free models, Qwen, Claude, Gemini, GPT, or fallback candidates are part of
Phase 2. No implicit fallback is allowed.

## Frozen controls

- Transport: GPU-7 external-only over mTLS; no local/GPU-5 fallback.
- Prompt: `evaluation/prompts/llm_extraction_system.txt`
  (`92ab28a86ce777e792dbe0177b3c9bc50e55bb388416aae8b464d20ed69cd382`).
- Output contract: the extraction JSON object defined by the prompt; schema
  hash `f0a3b5a893c61e1f68583faf8be56c9444f65aae52c05dae8b22e25f9f567f6c`.
- Dev gold manifest: `evaluation/gold/MANIFEST.sha256`
  (`a988e7440223a89e7aafab46f2a2a9bbac06d560d60221cf290aae6dcbdc0029`).
- Temperature: `0`.
- Max output: `4096` tokens for both candidates.
- Reasoning controls: thinking disabled / `reasoning_effort=low` for both.
- One request per document per model; no retries on content failures.
- Test ceiling: must be separately approved before execution; the pilot used a
  temporary $1.00 guard but no Phase 2 budget is approved.

## Required DEV data

- Target: **40–50 DEV documents per model**.
- Current frozen gold: **5 documents**.
- **Execution is BLOCKED**: the 40–50-document versioned gold set, ambiguity
  adjudication rules, and budget must be created and reviewed first.
- The additional documents must be public/DEV only; HOLDOUT remains sealed.
- Semantic P/R/F1 must use adjudicated labels. Unlabeled documents may be used
  only for structural/cost/reliability metrics and must be labeled as such.

## Operations and measurements

For both models, identical inputs and task instructions:

1. Entity/mention extraction.
2. Relationship extraction and direction correctness.
3. Pre-entity representation and valid offsets.
4. Entity-resolution proposals.
5. Ontology typing/classification.
6. Contradiction detection.
7. Evidence grounding/provenance.
8. JSON/schema validity.

Report separately: structural validity, semantic correctness, evidence
grounding, direction accuracy, hallucination rate, ambiguity preservation, and
failure classification.

## Pre-registered thresholds

- JSON/schema validity: **≥98%**
- Entity F1: **≥85%**
- Relationship F1: **≥80%**
- Evidence precision: **≥95%**
- Relationship direction accuracy: **≥95%**

With only five current gold documents, these thresholds cannot be claimed;
Phase 2 requires the expanded label set.

## Cost and reliability metrics

Per model:

- cost per document;
- cost per valid output;
- cost per accepted entity;
- cost per accepted relationship;
- cost per correctly grounded relationship;
- p50/p95 latency;
- provider failure rate and retry rate.

JSON validity alone is not an acceptance criterion.

## Approval gates

No execution until:
1. 40–50 DEV documents are selected and hash-pinned.
2. Gold/adjudication labels are versioned and reviewed.
3. A monetary cap and execution window are approved.
4. GPU-7 external-only/no-fallback configuration is re-verified.
5. The pilot artifacts remain unchanged.

## Deliverables after execution

Comparative quality report, cost-efficiency report, failure ledger, and a
model-allocation recommendation by semantic operation. No production rollout
is implied.
