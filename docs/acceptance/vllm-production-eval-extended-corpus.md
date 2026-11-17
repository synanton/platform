# #723 extended corpus assembly — 2026-11-17

**Manifest:** [`vllm-production-eval-corpus.tsv`](./vllm-production-eval-corpus.tsv)
**Added:** 30 unique real technical PDFs (rows `ext-01` through `ext-30`).
**Corpus total:** 56 unique documents (25 original + 1 real large PDF + 30 extended).
**Staging:** `docs/acceptance/artifacts/vllm-production-eval/corpus/` (ignored;
PDFs are not committed).

## Coverage

- All additions are real technical/enterprise PDFs with extractable text,
  50–209 pages, sourced from the operator's technical library.
- Per-document SHA-256, byte count, page count, and approximate entity count are
  in the manifest.
- Existing 3 negative controls and 15 new-entity candidates are unchanged.
- Formats retain the existing PDF/EPUB/TXT mix; the new documents are PDF.
- Two generated large PDFs plus the real 104-page Google report remain.

## Provenance caveat

The new rows use `source=local-library:books/...` because these are local
technical-library copies, not public URLs. They are suitable for an internal
evaluation but not for a public reproducibility claim. Replace with
publicly-linkable sources before publishing the evaluation dataset.

The 30-document sourcing target is met without synthetic filler. Extended
observation still needs to run 100+ ingestions; assembly alone does not start
#723.
