# A8.1 — Isolated resolution evaluation

**Status:** Evaluation PR; no production integration or graph writes
**Ticket:** #893 Workstream A
**Inputs:** A4.1 candidates, A8 canonical catalog + independent identity judgments
**Report:** `evaluation/it-books/a8-v1/REPORT.md`

## What this establishes

The merged A8 resolver was applied to every A4.1 candidate without modifying the A3/P0 ontology
release, A2 fixture, A4 metrics, or graph state.

- 70 candidates resolved through `DeterministicEntityResolver`.
- 5 RESOLVED (7.14% coverage), 58 UNRESOLVED, 0 AMBIGUOUS, 7 TYPE_MISMATCH.
- Every result carries mention ID, source, proposed type, normalized surface, strategy version,
  catalog version/digest, match method, and reason.
- 15 independently curated candidates were judged; all 15 outcomes matched.
- Reviewed precision 1.0 and recall 1.0; this is a **reviewed-sample** result, not a general
  extraction-quality claim.
- The unjudged 55 candidates remain in coverage and are not filtered.

## Why A2 cannot supply canonical gold

A2's mention IDs are source-span observations, not canonical identities. Using them as canonical
IDs, or deriving the catalog from A2 annotations, would make A8.1 tautological. The catalog and
judgments are separate artifacts:

- `evaluation/it-books/a8-v1/catalog.json`
- `evaluation/it-books/a8-v1/judgments.json`
- `evaluation/it-books/a8-v1/results/`

## Findings

1. Deterministic resolution works where canonical labels/aliases are explicit: RRP, MSP, HHP, and
   their full labels resolve.
2. Type mismatches are valuable: A4.1 emits `Concept` for known authors/topics/books; A8 refuses
   the identity rather than silently accepting it.
3. Low coverage is driven by noisy A4.1 candidates, not by a resolver failure.
4. No ambiguity appeared in this catalog, but the resolver's ambiguity contract is independently
   tested in A8 unit tests.

## Boundaries

No A2/A4/ontology/CSO/graph/GPU changes. No production graph writes. A4 comparison and extraction
quality improvements remain separate.
