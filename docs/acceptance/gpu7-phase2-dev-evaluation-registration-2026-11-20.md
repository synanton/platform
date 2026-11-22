# GPU-7 Phase 2 — DEV evaluation registration

**Status:** REGISTERED, NOT EXECUTED.
**Reason for hold:** the 25-document DEV set is selected, but only 5 documents
are currently validated gold; 20 are unlabeled. No paid calls are authorized
until all 25 labels are human-reviewed/adjudicated and the spent cap is approved.

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

- Target: **25 DEV documents per model**.
- Selection manifest: `evaluation/dev25/manifest.tsv`
  (`sha256 e01f01d54fb8361cd60749f526dcb4b20c06b235509b886fbd818812497cc8ca`).
- Current validated gold: **5 documents**.
- Current unlabeled preparation set: **20 documents**.
- **Execution is BLOCKED**: all 20 remaining documents need two independent
  annotations plus adjudication, a versioned gold checksum, and separate
  budget/run approval.
- The documents must be public/DEV only; HOLDOUT remains sealed.
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
Phase 2 requires the remaining 20 documents to be annotated and adjudicated.

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
1. The 25 selected DEV documents are present and hash-pinned.
2. All 20 unlabeled documents have completed, adjudicated labels, and the full
   25-document gold set is versioned.
3. A monetary cap and execution window are approved.
4. GPU-7 external-only/no-fallback configuration is re-verified.
5. The pilot artifacts remain unchanged.

## Deliverables after execution

Comparative quality report, cost-efficiency report, failure ledger, and a
model-allocation recommendation by semantic operation. No production rollout
is implied.
