# Ontology-context evaluation design (A/B/C)

**Status:** design only. Execution is BLOCKED pending separate authorization.
**Model:** local 4B production model, fixed across all arms.
**Data:** DEV gold v1.0 only; HOLDOUT sealed.
**External provider:** GPU-7 stopped.

## Purpose

Isolate whether ontology retrieval improves semantic extraction and, later,
whether validated graph context adds a second measurable increment. Do not
confound retrieval with model size or generation settings.

## Arms

| Arm | Context supplied |
|---|---|
| A — Baseline | No retrieval |
| B — Ontology-only | Tenant/version-pinned ontology classes, predicates, constraints |
| C — Ontology + Graph | Ontology context plus validated graph facts (future; not implemented) |

## Frozen settings

- Model: local 4B (`qwen3-extraction`), production revision as deployed.
- Temperature: 0.
- Max output: 4096 tokens.
- Chunking: `plan_boundary_chunks`, 3,000-char target, 200 overlap.
- Validation: `ModelProposal` → `ValidatedExtraction`; deterministic anchoring,
  endpoint and evidence checks.
- One request per chunk; max 3 documents per batch.
- No external fallback.

## Prompt templates

### Arm A — baseline

`evaluation/dev25/prompts/semantic-extraction-v1.txt`, unchanged.

### Arm B — ontology-only

Same template, with a separate trusted context block inserted before `TEXT`:

```text
ONTOLOGY CONTEXT (semantic guidance only; never source evidence):
{ontology_context}

TEXT:
{chunk_text}
```

Rules:

- `{ontology_context}` comes only from `ontology_context.retrieve_ontology`,
  tenant/version pinned, bounded by `max_context_chars`.
- The model must not cite ontology context as evidence. Evidence must be an exact
  substring of `TEXT`.
- Allowed types/predicates remain the source contract; ontology context can
  clarify but not expand them.
- Context hash and object IDs are persisted separately from source evidence.

### Arm C — future

Same as B plus a validated graph-context block. Not implemented; requires a
separate graph-retrieval proposal and review.

## Initial sample

- Diagnostic docs: `g7-dev-001`, `g7-dev-002`, `g7-dev-003` (small, diverse).
- Include the existing baseline results as Arm A control.
- No model call until a separate execution registration is approved.

## Metrics

- Entity precision, recall and F1.
- Relation candidates, precision, recall and F1.
- Endpoint correctness against accepted mention IDs.
- Evidence grounding against source text.
- Hallucination rate: proposed entities/relations not supported by source.
- JSON/schema validity.
- Retrieval context size, latency and retrieval provenance completeness.
- Inference latency, throughput and GPU peak memory.
- Request/document/chunk/retry IDs.

## Acceptance criteria (pilot)

- 100% JSON and schema validity.
- Zero accepted relations with missing/unsupported endpoints.
- Zero accepted relations with evidence not found in source.
- No control regression.
- Relation F1 or endpoint grounding improves over Arm A.
- Hallucination rate does not increase.
- Full request and retrieval-context provenance.
- GPU peak memory captured.

## Evidence gaps to resolve before execution

- Add GPU peak allocated/reserved memory sampling per run.
- Add immutable request/document/chunk/retry IDs to the local runner.
- Ensure context snapshots are serialized and stored with each request.

## Authorization boundary

No inference, spend, HOLDOUT access or production change is authorized by this
design. PR #799 remains the frozen 3-doc pilot; PR #800 remains the RAKE
proposal. Execution requires a separate window, spend/resource approval and
review.
