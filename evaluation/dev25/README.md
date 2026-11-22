# GPU-7 DEV-25 gold dataset

This directory holds the completed and released 25-document DEV gold set (v1,
agent-reviewed) for the GPU-7 Phase 2 evaluation. It is not a production dataset.

**Status:** validated 25/25; pending 0 (derived from `annotation-status.tsv`).

- `manifest.tsv` — exactly 25 selected DEV documents, stable IDs, categories,
  source provenance and SHA-256 checksums.
- `annotation-packets/` — master and per-category annotation packets.
- `annotation-status.tsv` — per-document annotation/review/adjudication status.
- `annotation-provenance.tsv` — annotator/evidence/AI-assistance provenance.
- `gold/` — 25 validated gold records; `PENDING.tsv` is derived from
  `annotation-status.tsv` and is currently empty.
- `gold-release.md` — release record (RELEASED v1).
- `development-touched.tsv` — provenance for the five documents used in the
  #820 contract spike; this is a history flag, not an exclusion decision.
- `check_provenance.py` — read-only drift validator for these declarations.
- `ai-assistance-registration.md` — annotator model/estimate.
- `validate.py` — read-only manifest/gold validation script.
- `checksums.sha256` — artifact checksums.
- `adjudication/` — disagreement/adjudication records template.
- `annotation-guidelines.md` — labeling and ambiguity rules.

All 25 documents are VALIDATED_GOLD. The 11 PDF books are chunk-annotated with
partial coverage; report results with that limitation. No model-generated
labels are treated as gold without agent review.

## Authority model

- `manifest.tsv` is authoritative for document inventory and record existence.
- `annotation-status.tsv` is authoritative for per-document validation.
- `gold-release.md` is authoritative for the release decision; its prose and
  acceptance table must match `annotation-status.tsv`.
- `gold/PENDING.tsv` is derived from `annotation-status.tsv`.

The source documents are referenced in place under `evaluation/corpus`,
`demo-data/documents`, and
`docs/acceptance/artifacts/vllm-production-eval/corpus`; large files were not
duplicated into Git.
