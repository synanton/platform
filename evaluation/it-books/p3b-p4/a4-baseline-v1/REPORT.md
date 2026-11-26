# A4 regex baseline — report

**Run ID:** `regex-baseline-a4-v1`
**Status:** Baseline complete for regex path; canonical resolution is an explicit A8 gap
**Provider:** `RegexExtractorAdapter` only
**Fixture:** `it-books-a2-v1`
**Ontology:** IT Books v1 `47fe38f8…` / digest `3a4a5edf…`
**Evidence contract:** `p3a-evidence-binding-v1`
**Graph:** isolated test only; no production writes

## Metrics by stage

### 1. Mention extraction

| Metric | Result |
|---|---:|
| Predicted mentions | 70 |
| A2 mentions | 22 |
| Exact source + document-span matches | 6 |
| Missing A2 mentions | 16 |
| Unmatched predicted mentions | 64 |
| Precision | 0.086 |
| Recall | 0.273 |
| F1 | 0.130 |
| Exact span and type | 2 |
| Type accuracy among matched | 0.333 |

Matching is one-to-one on exact source + UTF-16 document span; type is scored separately.

### 2. Canonical resolution

Canonical resolution is **not implemented** (A8 pending). The baseline records:

- mapped: 0
- unresolved: 70
- incorrect: 0
- mention-to-annotation matches: 6

This stage is not collapsed into mention extraction or relation F1.

### 3. Relation candidates and A5 validation

| Metric | Result |
|---|---:|
| Predicted relation candidates | 3 |
| A2 accepted relations | 8 |
| Matched accepted | 0 |
| Matched rejected | 0 |
| Matched unjudged | 0 |
| False positives | 3 |
| False negatives | 8 |
| Relation precision / recall / F1 | 0 / 0 / 0 |
| A5 ontology-valid candidates | 0 |
| Unknown predicates | 3 |
| Domain/range violations | 0 |
| Missing endpoints | 0 |

All three regex predicates are `is_a`, which is outside the A1 predicate vocabulary and is
rejected by the independent A5 matrix. Rejected and unjudged A2 judgments remain distinct.

### 4. Isolated graph persistence

- Regex A5-valid candidates: 0
- Regex persisted edges: 0
- Regex rejected candidates: 3
- Production graph writes: none

`A4IsolatedGraphPersistenceTest` additionally proves, with an in-memory GraphWriter test, that an
A5-valid candidate is persisted with provenance while an invalid candidate is excluded. This is
not a production graph write and does not create canonical identity.

### 5. Evidence and reproducibility

- Entity evidence spans resolved: 70 / 70
- Relation evidence spans resolved: 3 / 3
- Two independent runs produced byte-identical results:
  - `resultsSha256=8c9c3a2bca29abb12c6309783120f448aaa10be7d2ba102c9b90ed0ad5394eeb`
  - `resultsIdentical=true`
- Run manifest pins A2, P0/compiler, P3a, provider/configuration, and git commit.

## Interpretation

The regex path is deterministic and evidence-binding works, but it does not produce the A1
vocabulary, types, or canonical resolution required for acceptance. This is a measurement
baseline, not a quality acceptance claim. A8 should implement the minimal deterministic
resolution strategy next; A7 must wait for A4/A8 boundaries.

## Boundaries

No LLM, GPU, Gateway, fallback, production graph write, canonical assignment, A2 change, ontology
release change, #919 baseline change, CSO change, Docling, P2, or F1/gold/gate/profile/ADR-044
change is included.
