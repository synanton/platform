# A4.1 — Manual inspection of the regex baseline

**Status:** Diagnostic complete; not an A2/A4 change
**Inputs:** A4 merged baseline (`8c9c3a2b…`) + optional candidate export + A2 annotations
**Tool:** `tools/it-books-inspect/inspect_a4.py`
**Report:** `evaluation/it-books/p3b-p4/a4-inspection-v1/report.md`

## Scope

This is a read-only inspection of the A4 regex candidates, not a new extraction or evaluation run.
It selects false-positive mentions, false-negative mentions, exact-span matches, type mismatches,
and all three relation candidates. Historical prompts and model responses are explicitly
unavailable for the regex path; the tool does not reconstruct them.

## Observed failure taxonomy

| Bucket | Count | Observed pattern |
|---|---:|---|
| Predicted mentions unmatched to A2 | 64 | SPDX/license metadata, version strings, section numbers, incomplete heading fragments, and generic capitalized phrases |
| A2 mentions not matched | 16 | Full headings/titles such as `Chapter 1: The way of the program` were not emitted at the exact boundary |
| Exact-span matches | 6 | Mention boundaries agree for several titles/authors/acronyms |
| Exact-span type mismatches | 4 | Regex assigned `Concept` where A2 expects `Author` or `Topic` |
| Relation candidates | 3 | All are `is_a`, derived from copula cues; none is in the A1 vocabulary |

## Findings

1. **Source-prep / chunk-boundary leakage.** The regex sees comment headers such as
   `SPDX-License-Identifier`, `CC-BY-NC-3.0`, source paths, and version numbers. These are
   extraction false positives caused by treating metadata/headings as ordinary text, not evidence
   of missing ontology concepts.
2. **Heading boundary mismatch.** A2 expects the complete heading span
   (`Chapter 1: The way of the program`, `1.1 What is a program?`), while regex emits shorter
   capitalized fragments or different boundaries. This is a mention-detection failure.
3. **Generic type assignment.** Even exact spans lose type precision: authors and topics are
   emitted as `Concept`. A4’s mention F1 is therefore not a type-quality measure.
4. **Ontology-invalid relations.** All three relation candidates use `is_a` from copula cues. A5
   rejects them; the graph therefore receives no regex relation edges.
5. **Evidence is not the bottleneck.** All 70 entity and 3 relation evidence spans resolve. The
   failure is candidate selection/type/relation semantics, not evidence binding.
6. **No causal prompt/model conclusion is possible.** The regex path has no prompt or raw model
   response. The observed problems are artifact-level; attributing them to prompt construction or
   model behavior would be false.

## A8 implications

A8 should not try to rescue these mention and relation defects with fuzzy matching. It should:

- normalize labels deterministically;
- resolve exact canonical labels and explicit aliases;
- return explicit `UNRESOLVED` rather than forcing a canonical identity;
- test hard negatives and near-duplicate labels;
- keep mention detection, canonical resolution, relation validation, and graph persistence
  metrics separate.

A4.1 does not change A2 gold labels, A4 metrics, or graph state. The A4 baseline remains
`8c9c3a2bca29abb12c6309783120f448aaa10be7d2ba102c9b90ed0ad5394eeb`.
