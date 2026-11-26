# A9.1 — Candidate diagnostic report

**Status:** Stage 1 diagnostic; implementation not authorized
**Input:** immutable A4.1 candidate export
**Artifacts:** `evaluation/it-books/a9-v1/diagnostic.json`, `diagnostic.md`
**A2 role:** exact-span/type comparison only; no A2 identity is used as an extraction rule

## Summary

The 70 A4.1 candidates classify into overlapping primary categories:

| Category | Count |
|---|---:|
| `NON_KNOWLEDGE_TEXT` | 40 |
| `SPAN_GRANULARITY_ERROR` | 22 |
| `ENTITY_TYPE_ERROR` | 3 |
| `NONE_CORRECT` | 2 |
| `NONE_UNJUDGED` | 3 |

Additional signals:

- 30 candidates lie inside HTML comments (licence/source metadata).
- 6 candidates match A2 exact spans: 2 correct, 4 type mismatches.
- 64 candidates are unjudged; they are not judged-negative.
- All 3 relation candidates use `is_a` and are outside A1 (`UNKNOWN_PREDICATE`).
- All entity/relation evidence spans were resolved in A4.1; evidence binding is not the failure.

## Candidate-level attribution

Every row in `diagnostic.json` records:

- candidate ID, source, exact span, surface and predicted type;
- A2 match/judgment status without turning A2 into extraction rules;
- primary and secondary categories, root-cause stage and expected correction.

Major root causes:

1. **Structural parsing:** HTML comments and source/licence metadata enter the candidate text.
   This is the strongest noise source (30 candidates).
2. **Span construction:** headings are emitted as fragments, nested phrases, or cross-line joins
   instead of complete structural spans.
3. **Typing:** author/topic/book spans that are correctly located are still typed `Concept`.
4. **Relation extraction:** copula cues produce `is_a`, which is not in the A1 vocabulary.

## Fixture ambiguity requiring review

Candidate 37 (`Computer Networks`, span 57:74) matches A2 `m-cn-topic`, but the span lies inside
an HTML comment in the source. This is a source/gold boundary ambiguity, not a licence to keep
comment text in extraction. A2 must not be changed here; a reviewer must decide the fixture
boundary before A9.2 filters comments.

## Proposed A9.2 changes (not implemented)

1. Mask HTML comments/source metadata while preserving offsets.
2. Extract complete headings from structural cues:
   - book title → `Book`;
   - explicit `Chapter N` → `Chapter`;
   - explicit section heading → `Section`;
   - otherwise do not force a heading type.
3. Conservative typing:
   - explicit `Author:`/`Authors:` cues;
   - definition term/definition pairs only when the line provides the pattern;
   - acronyms only with explicit expansion/acronym evidence;
   - never use capitalization alone to assign `Concept`.
4. Relation emission:
   - predicate must exist in the pinned A1 release;
   - textual evidence must support the predicate meaning;
   - omit unsupported relation candidates and record omission reasons.
5. Evaluate against unchanged A2 using the A4 matching/scoring protocol; report denominators,
   exact vs partial spans, judged vs unjudged, type accuracy, relation precision/ontology-valid
   counts, and no-regression checks.

## Boundaries

No extraction code, A2, ontology release, A4/A4.1 artifact, A8/A8.1 result, graph state, or
evaluation gate was changed. A9.2 requires separate authorization.
