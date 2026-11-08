# Extraction gold schema (v1)

Status: frozen convention for #558
Scope: entity/relationship gold for the extraction evaluation track. Not a PreEntity contract.

## Purpose

A small, human-annotated gold set used to measure the **current deterministic
extractor** (`synflux` rule engine) and, later, the RAKG/LLM pipeline. The gold is
immutable once `evaluation/gold/MANIFEST.sha256` is written.

## Files

```
evaluation/
  corpus/<doc>.txt              # raw text, UTF-8, LF, no BOM
  gold/doc-N.json               # one per corpus document
  gold/MANIFEST.sha256          # sha256 of every corpus/*.txt and gold/doc-*.json
  eval_extraction.py            # baseline runner
```

## JSON document

```json
{
  "document_id": "halcyon-7-directive",
  "text_sha256": "<sha256 of the corpus/<doc>.txt bytes>",
  "entities": [
    {
      "mention_id": "m1",
      "surface_form": "Halcyon-7",
      "char_start": 14,
      "char_end": 23,
      "proposed_type": "MaintenanceSystem",
      "resolution_status": "resolved",
      "canonical_id": "entity-halcyon-7"
    }
  ],
  "relationships": [
    {
      "subject_id": "m1",
      "predicate": "requires_protocol",
      "object_id": "m3",
      "evidence_span_start": 100,
      "evidence_span_end": 150
    }
  ]
}
```

### Offsets

- `char_start` inclusive, `char_end` exclusive.
- Offsets are **Unicode code points** into the decoded UTF-8 text — identical to
  Python `str` indices (`text[char_start:char_end]`).
- `surface_form` must equal `text[char_start:char_end]` exactly (including case).
- No overlapping entity mentions in v1; a nested/overlapping annotation is an
  ambiguity to be resolved during annotation, not encoded.
- `evidence_span_*` use the same convention and must contain the relationship's
  supporting sentence/clause.

### Entities

| Field | Rule |
|---|---|
| `mention_id` | unique within the document (`m1`, `m2`, …), stable for the frozen artifact |
| `surface_form` | exact text span (may be a pronoun/alias, e.g. `the platform`) |
| `proposed_type` | open string; use the documented type set below |
| `resolution_status` | one of `resolved`, `ambiguous`, `unresolved` |
| `canonical_id` | non-null **iff** `resolution_status == "resolved"`; a stable slug (`entity-halcyon-7`) |

`resolution_status` semantics:
- `resolved` — the mention refers to exactly one entity in the document/world and has a canonical id.
- `ambiguous` — the surface form could refer to more than one entity (e.g. `the platform`, `it`) and the document does not disambiguate it.
- `unresolved` — a distinct entity is mentioned but cannot be canonically identified (e.g. an unnamed technician).

Documented `proposed_type` set (extend deliberately, never silently):

`MaintenanceSystem`, `Component`, `Protocol`, `Procedure`, `Material`, `Organization`,
`Person`, `Role`, `Product`, `Document`, `Standard`, `Metric`, `Measurement`,
`Location`, `Time`, `Algorithm`, `DataStructure`, `Concept`, `Event`.

### Relationships

- `subject_id` / `object_id` reference `mention_id`s in the same document.
- `predicate` is a lower_snake_case verb phrase (`requires_protocol`, `has_component`, `performed_by`, `documented_in`, `measured_by`, `part_of`, `implements`, `uses`).
- Every relationship must have at least one evidence span; overlapping spans are allowed.
- No self-relationships (`subject_id != object_id`).
- Only include relationships explicitly supported by the text; do not infer world knowledge.

## Annotation protocol

1. Work from `evaluation/corpus/<doc>.txt` only.
2. Do **not** consult Cassandra, the Lucene index, regex output, or any model output while annotating.
3. Annotate every clearly named entity mention (first occurrence and meaningful aliases);
   do not annotate bare determiners or generic nouns with no referent.
4. Add relationships only where the text states them; ambiguous relations are omitted, not guessed.
5. Freeze: `sha256sum evaluation/corpus/*.txt evaluation/gold/doc-*.json > evaluation/gold/MANIFEST.sha256`.

## Baseline alignment

`eval_extraction.py` aligns the extractor output to the gold by character-span overlap:
a predicted mention matches a gold mention when the spans intersect (strict exact-span
and intersection modes are both reported). Metrics:

- Entity precision / recall / F1 (span match; type-match reported separately).
- Resolution accuracy over matched mentions (`resolved`/`ambiguous`/`unresolved`).
- Relationship precision / recall (both endpoints matched and predicate equal).
- Evidence coverage: fraction of matched relationships whose predicted evidence span
  intersects the gold evidence span.
