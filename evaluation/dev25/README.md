# GPU-7 DEV-25 dataset preparation

This directory holds the T3 preparation artifacts for the GPU-7 Phase 2
evaluation. It is not a completed gold dataset.

- `manifest.tsv` — exactly 25 selected DEV documents, stable IDs, categories,
  source provenance and SHA-256 checksums.
- `annotation-packets/` — master and per-category empty annotation packets.
- `adjudication.tsv` — disagreement/adjudication template.
- `ANNOTATION_GUIDELINES.md` — labeling and ambiguity rules.

Only the five documents with `annotation_status=VALIDATED_GOLD` are currently
validated. The other 20 are `UNLABELED`; no model-generated labels may be
substituted.

The source documents are referenced in place under `evaluation/corpus`,
`demo-data/documents`, and
`docs/acceptance/artifacts/vllm-production-eval/corpus`; large files were not
duplicated into Git.
