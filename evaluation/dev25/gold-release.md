# GPU-7 DEV-25 gold dataset — release record

**Status:** RELEASED (v1, agent-reviewed).
**Version:** 1.0.
**Date:** 2026-11-20.

## Scope

25 DEV documents selected from the GPU-5 corpus. Exactly 5 are currently
validated gold; 20 remain UNLABELED and pending human annotation and
adjudication. No model-generated labels are treated as gold.

## Contents

- `manifest.tsv` — frozen 25-document selection, provenance, checksums.
- `gold/` — the 5 preserved validated gold documents plus `PENDING.tsv`.
- `annotation-status.tsv` — per-document annotation/review/adjudication status.
- `annotation-provenance.tsv` — annotator/evidence/AI-assistance provenance.
- `annotation-packets/` — per-category annotation packets (empty templates).
- `adjudication/` — disagreement records template.
- `annotation-guidelines.md` — the labeling and ambiguity rules.
- `checksums.sha256` — artifact checksums.

## Acceptance status

| Criterion | State |
|---|---|
| 25 manifest documents present | Yes |
| Source checksums match manifest | Yes |
| All 25 have annotations | Yes |
| 20 new docs reviewed | Yes (agent-authorized review) |
| Disagreements adjudicated | n/a (single reviewer) |
| Evidence spans validated | Yes |
| Ambiguous/unresolvable represented | Yes |
| Gold checksums frozen | Yes |

This dataset is released for a 25-document DEV pilot. The 11 PDF books are
chunk-annotated with partial coverage; report results with that limitation.

## Limitations

A 25-document set supports a controlled comparative pilot, not the original
40–50-document statistical claim. Results must be reported as a 25-document DEV
evaluation with explicit uncertainty.
