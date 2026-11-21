# GPU-7 DEV-25 gold dataset — release record

**Status:** NOT RELEASED (preparation only).
**Version:** 0-pre.
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
| All 25 have annotations | No (11 PDF pending) |
| 20 new docs human-reviewed | 9 reviewed (11 PDF pending) |
| Disagreements adjudicated | No |
| Evidence spans validated | Partial (14 gold) |
| Ambiguous/unresolvable represented | Partial (14 gold) |
| Gold checksums frozen | No |

This dataset must not be used for Phase 2 evaluation until all criteria pass and
a versioned release is recorded.

## Limitations

A 25-document set supports a controlled comparative pilot, not the original
40–50-document statistical claim. Results must be reported as a 25-document DEV
evaluation with explicit uncertainty.
