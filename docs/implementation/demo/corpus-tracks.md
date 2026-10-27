# Demo corpus tracks

## v1 — shared frozen corpus
- 15 documents, 317 chunks, digest `a993e6a1b13ec3ffc8a35ddaa2e53e6fa3c25d74c03d500e52d1b4b3d0b0096f`.
- Used by the paused validation track and by the demo.
- Do not add documents to this hash; the 735-row pool/calibration artifacts are tied to it.
- The SKS8300 web-interface manual is already included (54 chunks). The `testing/` copy is byte-identical (SHA `a6c929ff…`, 13 pages, 2 model mentions), so it adds no density.

## v2 — created 2026-10-26
- 20 documents, 634 chunks, digest `5cf5b9553610482b2b5a1db2075def77b62c8cad6c8ddebcf080e6e26fd21e37`.
- Adds FileNet SG24-7667 chapters 1–2 (62 pages, 194 chunks) and legal A1/B2/R1/R2 (123 chunks) to frozen v1 (317 chunks).
- Two independent ingests (`demo-v2`, `demo-v2-b`) are chunk-identical; determinism passed.
- Entity density did **not** grow: graph-useful df 3–15 stayed 64; FileNet terms are mostly df>50 or df=1. Adding the API book blindly is not justified; fix entity selection first.
- v1 5-query picks still return relevant top results but their leg ranks changed; re-select demo queries against v2 + dense before shipping Slice 1.

## Slice 2 note
- `pdf015#65` has no graph-useful entity overlap with its BM25 top-20 under the current extractor; it is not the graph-hero query yet.
- Either add a certificate/compliance entity type with a real bridge, or pick a Slice 2 query from the df 3–15 band once v2 exists.
