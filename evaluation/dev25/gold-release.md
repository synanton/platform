# GPU-7 DEV-25 gold dataset — release record

**Status:** RELEASED (v1, agent-reviewed); validated 25/25; pending 0.
**Version:** 1.0.
**Date:** 2026-11-20.

## Scope

25 DEV documents selected from the GPU-5 corpus. All 25 are VALIDATED_GOLD
(validated 25/25; pending 0). The 11 PDF books are chunk-annotated with partial
coverage; this is recorded as a limitation, not an unvalidated state. No
model-generated labels are treated as gold without agent review.

## Contents

- `manifest.tsv` — frozen 25-document selection, provenance, checksums.
- `gold/` — the 25 validated gold records. `PENDING.tsv` is derived from
  `annotation-status.tsv` and is currently empty.
- `annotation-status.tsv` — per-document annotation/review/adjudication status.
- `annotation-provenance.tsv` — annotator/evidence/AI-assistance provenance.
- `annotation-packets/` — per-category annotation packets (templates).
- `development-touched.tsv` — provenance for the five #820 contract-spike docs.
- `check_provenance.py` — drift validator for these declarations.
- `adjudication/` — disagreement records template.
- `annotation-guidelines.md` — the labeling and ambiguity rules.
- `checksums.sha256` — artifact checksums.

## Acceptance status

| Criterion | State |
|---|---|
| 25 manifest documents present | Yes |
| Source checksums match manifest | Yes |
| 25/25 validated gold | Yes |
| All 25 have annotations | Yes |
| 20 new docs reviewed | Yes (agent-authorized review) |
| Pending list | Empty (derived from `annotation-status.tsv`) |
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
