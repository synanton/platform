# Extraction error taxonomy

**Diagnostic basis:** PR #919 conservative results, not a metric recalculation.
**Baseline result:** `f087ce6f4d5edb289c3a20e15cf0eedbc9aeaa03a99674341fd2689766bbcece`

## Entity errors

| Code | Definition | Diagnostic count in #919 |
|---|---|---:|
| `ENTITY_MISSING` | Accepted A2 mention not extracted. | 10 FN |
| `ENTITY_WRONG_TYPE` | Span extracted but type differs from A2. | Included in non-TP predictions; exact count not separately reported |
| `ENTITY_WRONG_LABEL` | Span matches but canonical surface/label is incorrect. | Not measured separately |
| `ENTITY_DUPLICATE` | Same mention span emitted more than once. | 0 observed |
| `ENTITY_AMBIGUOUS` | Same surface maps to multiple unresolved mentioned types. | Not measured; recommended diagnostic |
| `ENTITY_UNSUPPORTED_TYPE` | Type outside the active IT Books contract. | 1 `UNKNOWN` mention |
| `ENTITY_BAD_SPAN` | Span cannot bind or does not match the canonical text. | 0; all resolved |

## Relation errors

| Code | Definition | Diagnostic count in #919 |
|---|---|---:|
| `RELATION_MISSING` | Accepted A2 relation not extracted. | 8 FN |
| `RELATION_WRONG_PREDICATE` | Extracted relation uses a different predicate. | Included in 8 unsupported/unknown predictions |
| `RELATION_WRONG_ENDPOINT` | Subject or object is not the A2 entity. | Included in unknown relation count |
| `RELATION_DOMAIN_RANGE` | Predicate endpoints violate the allowed domain/range. | Not separately measured against A2 |
| `RELATION_UNSUPPORTED_PREDICATE` | Predicate outside the eight A1 predicates. | 8 (`hassupplier` 2, `partoforder` 3, `records` 1, `requires` 2) |
| `RELATION_SEMANTIC` | Syntactically valid but factually incorrect relation. | Not separately adjudicated |
| `RELATION_BAD_EVIDENCE` | Evidence span missing or incorrect. | 0 unmapped/misbound; all evidence bound |

## Infrastructure/provenance errors

| Code | Definition | Diagnostic count in #919 |
|---|---|---:|
| `MODEL_MALFORMED` | Response cannot be parsed under the extraction schema. | 1 chunk per run; fail-closed |
| `EVIDENCE_MISSING` | Candidate has no bindable source span. | 0 |
| `EVIDENCE_INVALID_OFFSET` | Span outside the chunk/document. | 0 |
| `EVIDENCE_UNMAPPED` | No structural mapping. | 0 |
| `EVIDENCE_MISBOUND` | Mapping violates structural invariants. | 0 |
| `GATEWAY_FAILURE` | Gateway unavailable, timeout, or refusal. | 0 observed |
| `IDENTITY_MISMATCH` | Tenant/artifact/model/release mismatch. | 0 observed |

## Rules

- Taxonomy counts are diagnostics, not a replacement for A2 metrics.
- Missing output is not automatically a negative judgment.
- Unsupported predicates/types remain visible; never silently normalized.
- Any future gold expansion for newly observed CSO or model concepts must be separately versioned and annotated.
