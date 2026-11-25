# A2/P1/P3a evaluation harness review

**Baseline:** PR #919, result `f087ce6f4d5edb289c3a20e15cf0eedbc9aeaa03a99674341fd2689766bbcece`
**Scope:** read-only review of the existing harness; no metric recalculation.

## Verified

- A2 accepted/rejected/unjudged are read distinctly; unjudged is not negative.
- Entity and relation metrics are separate.
- Unsupported entity types/predicates are reported, not silently normalized.
- Evidence binding reports RESOLVED/UNMAPPED/MISBOUND separately and uses `STRUCTURAL_CHUNK` only.
- A2 and P0/P1/P3a input identities are pinned; run manifests include output hashes and configuration.
- Two identical runs are distinguished from model variability by hashing results.
- Metric definitions are recorded in the run manifest.

## Risks and recommendations

| Risk | Recommendation |
|---|---|
| Greedy one-to-one entity matching depends on A2 iteration order. | Sort both sides by source, span, type, and ID before greedy matching. |
| Unresolved predictions are excluded from entity TP/FN denominators but counted as relation unknown FP. | Define one policy and report unresolved predictions in a separate denominator. |
| No per-type entity or per-predicate relation breakdown. | Add aggregate arrays while retaining the baseline calculation. |
| Approximate/document-scope evidence is not exercised by the current Markdown A2 fixture. | Keep separate fields and require a PDF fixture before claiming those classes. |
| A malformed chunk is a failed chunk, not a quarantined line. | Report failed chunk IDs and model response digests explicitly. |
| Unsupported relations are counted as unknown FP without a predicate-level reason. | Add predicate/reason breakdown; do not coerce predicates. |
| LLM runs can differ between repetitions. | Keep result hashes per repetition and explicitly report variability; never require byte identity for LLM output. |

No harness change is included in this review. Any change must preserve the PR #919 baseline artifacts and A2 annotations.
