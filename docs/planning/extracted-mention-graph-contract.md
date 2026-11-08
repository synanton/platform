# Extracted-mention graph contract (PR #3)

**Status:** Pinned 2026-11-08
**Issue:** #560 (RAKG)
**Scope:** persist extracted mentions and validated candidate relations. Canonical
entity resolution is PR #4.

## Two-stage boundary

| Graph data | PR #3 | PR #4 |
|---|---|---|
| `:ExtractedMention` nodes | Persist | Reuse |
| `:EXTRACTED_RELATION` edges (validated) | Persist | Reuse |
| `:Entity` canonical nodes | Do not create | Create/link |
| `:RESOLVES_TO` edges | Do not create | Create |

PR #3 never writes `:Entity`, `:REL`, or `:RESOLVES_TO`. Existing canonical graph
queries must not see extracted mentions.

## Nodes

`:ExtractedMention` properties:

| Property | Meaning |
|---|---|
| `mention_id` | deterministic hash (below) |
| `tenant_id` | verified tenant |
| `source_version_id` | source version being extracted |
| `content_ref_id` | content-addressed artifact |
| `chunk_id` | `content_ref_id:chunk_ordinal` |
| `chunk_offset_start` / `chunk_offset_end` | character span **within the chunk** |
| `document_offset_start` / `document_offset_end` | character span in the parsed document; `-1` when the chunker cannot supply it |
| `surface_form` | exact text span |
| `proposed_type` | LLM/ontology type label before canonical resolution |
| `confidence` | reserved (1.0 for PR #3) |
| `extracted_at` | write timestamp |

`:EXTRACTED_RELATION` edge properties:

| Property | Meaning |
|---|---|
| `relation_id` | deterministic hash (below) |
| `tenant_id` | verified tenant |
| `predicate` | normalized lower_snake_case predicate |
| `evidence_chunk_id` | chunk that supplied the evidence |
| `evidence_chunk_offset_start` / `end` | evidence span within the chunk |
| `evidence_document_offset_start` / `end` | evidence span in the document (`-1` when unknown) |
| `source_version_id` | source version |
| `validated_at` | validation/commit timestamp |

## Deterministic IDs

Use SHA-256 hex, not model-generated ids:

```text
mention_id = sha256(
  tenant_id + "|" + source_version_id + "|" + chunk_id + "|" +
  chunk_offset_start + "|" + chunk_offset_end + "|" + surface_form
)

relation_id = sha256(
  tenant_id + "|" + source_version_id + "|" +
  subject_mention_id + "|" + predicate + "|" + object_mention_id
)
```

Re-running the same chunk must MERGE the same node/edge (write twice → one
node/edge). The LLM adapter pins `temperature: 0`; deterministic ids are the
idempotency guarantee even if the model output drifts.

## Offset semantics

- `chunk_offset_*` are relative to `TextSegment.text()` as sent to the adapter.
- `document_offset_*` are computed at write time as
  `chunk.document_offset_start + chunk_offset`; the sentence chunker records the
  exact parsed-document span. Other chunkers may record `-1` until they expose a
  document offset; consumers must not guess.
- The graph contract must not claim original-PDF offsets when the pipeline only
  has parsed-document offsets. `content_ref_id` + `source_version_id` remain the
  provenance anchor.

## Validation contract (Syntology)

`POST /api/v1/ontology/validate-relation` (tenant from verified context):

```json
{"subjectType":"Product","predicate":"hasSupplier","objectType":"Supplier","ontologyVersion":"1.0.0"}
```

```json
{"conformant":true,"violations":[],"subject_type":"Product","predicate":"hasSupplier","object_type":"Supplier","version":"1.0.0"}
```

Rejected relations carry structured `{code,message,path}` violations. Domain/range
checks come from the TBox; SHACL property-shape violations for the predicate are
included when present.

## Status (do not overload `extraction_status`)

`extraction_status` remains `PENDING|EXTRACTING|KNOWLEDGE_READY|EXTRACTION_FAILED`
(extraction + per-chunk persistence). `graph_status` is separate:
`PENDING|VALIDATING|GRAPH_COMMITTED|PARTIAL|FAILED`, with
`graph_validation_counters` (mentions/relations accepted/rejected) and
`graph_commit_at`.

## Explicit non-goals

Canonical `:Entity` creation, `:RESOLVES_TO`, retrieval-assisted resolution,
DLQ/stale-generation discard, per-tenant GPU fairness, and Relix canonical graph
rebuild are out of scope.
