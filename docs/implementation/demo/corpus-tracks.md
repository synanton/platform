# Demo corpus tracks

## v1 — shared frozen corpus
- 15 documents, 317 chunks, digest `a993e6a1b13ec3ffc8a35ddaa2e53e6fa3c25d74c03d500e52d1b4b3d0b0096f`.
- Used by the paused validation track and by the demo.
- Do not add documents to this hash; the 735-row pool/calibration artifacts are tied to it.
- The SKS8300 web-interface manual is already included (54 chunks). The `testing/` copy is byte-identical (SHA `a6c929ff…`, 13 pages, 2 model mentions), so it adds no density.

## v2 — not created
- No additional local PDF adds usable demo density without crossing into benchmark corpora.
- `bench-chunknorris-acl2025`, `PIRE`, `OHR-Bench`, and `RAG-Multi-Corpus` are validation material (#470), not demo material.
- To create v2, use vendor-published, redistribution-safe manuals (e.g. Edge-Core/Lantronix/WatchGuard) or an explicitly licensed internal set. Do not ingest manymanuals copies without a license decision.
- v2 must be hashed, determinism-checked, and re-run through the 5 Slice 1 queries before use.

## Slice 2 note
- `pdf015#65` has no graph-useful entity overlap with its BM25 top-20 under the current extractor; it is not the graph-hero query yet.
- Either add a certificate/compliance entity type with a real bridge, or pick a Slice 2 query from the df 3–15 band once v2 exists.
