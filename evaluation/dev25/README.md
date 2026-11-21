# GPU-7 DEV-25 dataset preparation

This directory holds the T3 preparation artifacts for the GPU-7 Phase 2
evaluation. It is not a completed gold dataset.

- `manifest.tsv` — exactly 25 selected DEV documents, stable IDs, categories,
  source provenance and SHA-256 checksums.
- `annotation-packets/` — master and per-category empty annotation packets.
- `annotation-status.tsv` — per-document annotation/review/adjudication status.
- `annotation-provenance.tsv` — annotator/evidence/AI-assistance provenance.
- `gold/` — preserved 5 validated gold documents + pending list.
- `gold-release.md` — release record (NOT RELEASED).
- `ai-assistance-registration.md` — annotator model/estimate (preparation only).
- `validate.py` — read-only manifest/gold validation script.
- `checksums.sha256` — artifact checksums.
- `adjudication/` — disagreement/adjudication records template.
- `annotation-guidelines.md` — labeling and ambiguity rules.

Only the five documents with `annotation_status=VALIDATED_GOLD` are currently
validated. The other 20 are `UNLABELED`; no model-generated labels may be
substituted.

The source documents are referenced in place under `evaluation/corpus`,
`demo-data/documents`, and
`docs/acceptance/artifacts/vllm-production-eval/corpus`; large files were not
duplicated into Git.
