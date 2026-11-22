# RAKE — ontology and graph retrieval proposal

**Status:** engineering proposal only; no inference or spend authorized.
**Baseline evidence:** PR #799 local 3-document pilot.
**Baseline model:** local 4B production model; keep model and inference settings fixed.
**External provider:** GPU-7 remains stopped.

## 1. Problem

The local pilot shows JSON generation is not the main blocker. The dominant
failures are:

- entity grounding: `SURFACE_NOT_FOUND`, `AMBIGUOUS_SURFACE`;
- relationship grounding: `AMBIGUOUS_OR_MISSING_ENDPOINT` (22 failures for 4B,
  12 for 8B FP8 in the 3-doc pilot).

Retrieval can improve semantic classification and relationship interpretation,
but it cannot repair missing source evidence or invent a valid mention.
Deterministic anchoring and endpoint validation remain authoritative.

## 2. Scope

Test whether retrieving a small ontology subset, and optionally a graph
neighbourhood, improves relation proposals while keeping the 4B model and all
other inference settings fixed.

Arms:

1. Baseline: existing semantic prompt, no retrieval.
2. Ontology-only: retrieve relevant ontology types/definitions/predicate
   contracts for the chunk.
3. Ontology+graph: ontology context plus a bounded graph neighbourhood of
   already-canonical entities.

No model-size change, no external provider, no automatic fallback.

## 3. Context contract

- Retrieve a bounded context (default ≤1,500 tokens) per chunk.
- Provide validated entity candidates with stable mention IDs and types.
- Require relation proposals to reference **accepted mention IDs**, not free
  surface strings.
- Keep source evidence independent of retrieved graph context. Retrieved
  context may guide interpretation; it cannot replace the evidence quote from
  the source chunk.
- Preserve ambiguity and unresolved references.

## 4. Retrieval provenance

Record for every request:

- retrieval arm and index/version hash;
- query text and top-k;
- retrieved ontology/graph object IDs, scores and context hashes;
- source document/chunk ID, offsets and checksum;
- model revision, prompt version, schema version and generation settings;
- immutable request ID and parent/retry IDs;
- raw response and response hash.

## 5. Offline-first work

Before any model call:

1. Define an ontology subset manifest and a graph-neighbourhood query fixture.
2. Add deterministic context-budget and context-provenance tests.
3. Add tests that relation proposals referencing unknown mention IDs are
   rejected.
4. Add tests that retrieved context cannot substitute for source evidence.
5. Verify all 25 DEV gold v1.0 documents still chunk, anchor and validate.

No inference until the offline suite and a separate experiment registration are
reviewed.

## 6. Evaluation design (after separate approval)

- Small representative DEV subset first; controls included.
- Fixed 4B model, prompt, chunking, temperature, max tokens and hardware.
- Compare baseline, ontology-only and ontology+graph arms.
- Measure JSON/schema validity, entity F1, relation F1, endpoint correctness,
  evidence grounding, latency, throughput and GPU peak memory.
- Preserve per-document results and rejection categories.
- No HOLDOUT access.

## 7. Acceptance criteria for the first RAKE pilot

- 100% JSON/schema validity.
- Zero unsupported endpoint relations in accepted output.
- Zero evidence-not-found accepted relations.
- No control regressions.
- Relation F1 and endpoint grounding improve over the fixed 4B baseline.
- Context provenance and request IDs are complete.
- GPU peak memory and latency are recorded.

## 8. Evidence gaps to close

- GPU peak allocated/reserved memory was not captured in PR #799.
- Stable request/document/chunk/retry IDs were not persisted in the local pilot.
- Both must be implemented and tested before a larger evaluation.

## 9. Authorization boundary

This proposal authorizes nothing. PR #799 remains the pilot evidence record;
this RAKE work is offline-first and requires a separate execution registration,
window, budget and review before any model calls. No acceptance or ranking claim
is made.
