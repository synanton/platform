# GPU-7 DEV-25 annotation guidelines

**Status:** preparation; only the five existing `evaluation/gold` documents are validated.
**No model-generated annotation is ground truth.**

## Scope

25 DEV documents, 5 validated gold + 20 to be annotated and adjudicated. HOLDOUT
is not included. Annotators must not see candidate model outputs or model
identities.

## Annotation tasks

For each document record:

1. **Entities / mentions**
   - `surface_form`: exact text span.
   - `char_start`, `char_end`: 0-indexed, end-exclusive offsets in the provided text.
   - `proposed_type`: allowed ontology type; do not invent types.
   - `resolution_status`: `resolved`, `ambiguous`, or `unresolvable`.
   - `canonical_id`: only when resolved; otherwise blank.
2. **Relationships**
   - `subject_surface`, `predicate`, `object_surface`.
   - `direction`: `subject_to_object` or `object_to_subject`; required.
   - `subject_type`, `object_type`.
   - `evidence_start`, `evidence_end`, `evidence_quote`.
   - `relation_status`: `accepted`, `ambiguous`, or `unresolvable`.
3. **Ambiguity / contradictions**
   - Record ambiguous spans and the competing interpretation.
   - Record contradiction candidates and the conflicting evidence spans.
   - Do not force a resolution; preserve ambiguity explicitly.

## Rules

- Offsets refer to the supplied extracted text, not the source PDF page.
- Surface forms must match the text exactly.
- Evidence must support the relationship; world knowledge is not evidence.
- Use only the allowed ontology types and predicates.
- Unresolvable cases must be recorded, not dropped.
- Do not edit source documents or prior gold records.
- If a document is not suitable, mark it `excluded` with a reason; do not silently
  replace it.

## Adjudication

- Two independent annotators per document are required for the 20 unlabeled docs.
- Disagreements go to an adjudicator using `adjudication.tsv`.
- Record the resolution and final label; preserve both original annotations.
- The five existing gold documents remain the initial validated subset.

## Acceptance gate

Phase 2 inference remains blocked until all 25 documents have completed,
human-reviewed labels, a versioned gold checksum, and a separately approved
spend cap/run registration.
