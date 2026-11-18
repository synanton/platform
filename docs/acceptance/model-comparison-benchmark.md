# Model comparison benchmark proposal

**Status:** proposed; no benchmark run, model change, or production change.
**Independent of:** #724 acceptance and #723 execution.
**Baseline:** `qwen3-extraction` / Qwen3-4B-Instruct-2507, regex remains the
production provider.

## Goal

Determine whether a larger model or JSON-Schema constrained decoding materially
improves extraction reliability without collapsing semantic quality. The primary
question is semantic reliability; JSON validity is a separate, lower-level
measure.

## Non-goals

- No production provider, extraction default, chunking, prompt, ontology,
  promotion, filtering, or #723 change.
- No use of HOLDOUT rows.
- No full-document replay as part of this proposal.
- No model replacement before the comparison is reviewed.

## Candidates

| ID | Model | Decoding | Purpose |
|---|---|---|---|
| A | Qwen3-4B-Instruct-2507 | Current prompting | Baseline |
| B | Qwen3-4B-Instruct-2507 | JSON Schema constrained | Isolate constrained decoding |
| C | Qwen3-8B (quantized if required) | JSON Schema constrained | Test larger-model capability |
| D | Qwen3-8B, optional | Current prompting | Separate model from decoding mode |

The current GPU is a 16GB RTX 5060 Ti. The 8B candidates are contingent on a
quantized artifact fitting the GPU at the chosen context/concurrency. No 14B
candidate is proposed for this hardware tier.

## Input set

Use only the existing reviewed **DEV** chunks from the Track A sample; do not
read or use HOLDOUT. Keep the exact chunk bytes, prompt template, ontology
vocabulary, temperature, and output schema identical across configurations.
Record source version, chunk ordinal, prompt SHA-256, model revision, vLLM
version, quantization, context limit, and output cap for every call.

The 20 DEV chunks are sufficient for a controlled, low-cost comparison; this is
not a claim of production-scale representativeness.

## Output contract

Use one JSON Schema for all constrained runs:

```json
{
  "type": "object",
  "properties": {
    "preEntities": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "surfaceForm": {"type": "string"},
          "proposedType": {"type": "string"},
          "charStart": {"type": "integer"},
          "charEnd": {"type": "integer"}
        },
        "required": ["surfaceForm", "proposedType", "charStart", "charEnd"],
        "additionalProperties": false
      }
    },
    "relations": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "subjectForm": {"type": "string"},
          "predicate": {"type": "string"},
          "objectForm": {"type": "string"},
          "evidenceQuote": {"type": "string"}
        },
        "required": ["subjectForm", "predicate", "objectForm", "evidenceQuote"],
        "additionalProperties": false
      }
    }
  },
  "required": ["preEntities", "relations"],
  "additionalProperties": false
}
```

Constrained runs use vLLM JSON-Schema-guided generation if the deployed vLLM
version supports it; otherwise the experiment is blocked on a verified
constrained-decoding path. Constraining syntax must not be counted as evidence
of semantic correctness.

## Procedure

1. Pin and hash the model artifacts, vLLM image, schema, prompt, DEV inputs, and
   decoding parameters.
2. Warm the model, then run each configuration over the same DEV chunks with
   temperature 0.
3. Capture raw responses and `finish_reason`/usage for every call; use the #766
   capture boundary for parse/incomplete failures.
4. Parse and schema-validate before semantic scoring.
5. Score semantic quality separately from syntax.
6. Record GPU memory, wall-clock latency, tokens/s, and any queueing.
7. Repeat a small subset once to expose nondeterminism; do not tune on HOLDOUT.

## Metrics

### Syntax and generation

- JSON parse success rate.
- Schema compliance rate.
- `finish_reason=length` rate.
- Missing-usage frequency.
- Prompt/completion/total tokens.
- Latency, tokens/s, peak GPU memory.

### Semantics

- Entity candidate precision/recall against available DEV labels or a small
  explicitly labelled spot-check.
- Subject/object validity and type accuracy.
- Predicate correctness.
- Evidence-quote sufficiency.
- Relation candidate precision/recall and accepted-relation rate.
- False-positive promotion count where graph validation is exercised.

The relation acceptance rate is an outcome metric, not the sole optimization
target. A model that emits perfect JSON with worse relations has not improved.

## Expected decision

The benchmark produces a comparison report at
`docs/acceptance/model-comparison-results-YYYY-MM-DD.md` and an explicit
recommendation: keep 4B, adopt constrained decoding only, evaluate 8B further,
or stop. No production switch follows automatically from any result.

## Deliverables for this proposal

- This design note only.
- No benchmark execution, model deployment, provider change, or #723 action.
