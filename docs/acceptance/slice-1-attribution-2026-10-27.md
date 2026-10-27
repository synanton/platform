# Slice 1 attribution acceptance — 2026-10-27

**Status:** local acceptance green. K8s is a separate deployment gate (#473), not part of this record.

- Platform backend: `5a52921` (demo/vertical-slice)
- UI: `78b809c` (ui/token-sweep-demo, PR #24)
- Corpus: `demo-v2`, 20 documents / 634 chunks, digest `5cf5b9553610482b2b5a1db2075def77b62c8cad6c8ddebcf080e6e26fd21e37`
- RRF k = 20; comparator `(final_score DESC, bm25_rank ASC, chunk_id ASC)`
- Endpoint: `POST /v1/demo/search`, demo tenant token required, demo-only.

## Four retrieval queries

| Query | BM25 | Dense | RRF |
|---|---:|---:|---:|
| `pdf005` — Which medications are listed for substance use disorders? | #7 | #2 | #2 |
| `pdf004` — How does the report's glossary define a major depressive episode? | #1 | #4 | #1 |
| `rb003` — In what year was Globex Manufacturing founded and where is it headquartered? | #3 | #1 | #2 |
| `rb010` — IATF 16949 | #1 | — | #1 |

`rb010` raw shape includes literal `"dense_rank":null` and `"dense_contribution":0.0`.

## Measurements and disclosures

- Gold-rank disagreement: 8/25 queries have a ≥2-rank gap or one-leg miss.
- Strict fixed-weight RRF wins over the best single leg: **0/25**.
- RRF ties the best leg on `pdf004`, `pdf005`, `rb010`; sits between on `rb003`; is worse than the best leg on `pdf011`, `pdf012`, `pdf015`, `rb008` when one leg misses.
- Demo copy therefore claims **attribution and robustness**, not ranking improvement.
- Tie-break bias: BM25-present chunks beat Dense-only chunks on final-score ties; deterministic demo ordering, not quality.
- Graph is visualization-only; no graph retrieval contribution. Graph/ontology is #472.

## Verification

- Backend demo tests: 16 (7 controller + 9 service), all green; full `:java:synquest:test` suite green.
- UI: 69 tests pass, 1 todo; typecheck and token conformance green.
- Runner/backend parity: all four queries match on chunk order, `bm25_rank`, `dense_rank`, and `legs_present`.
- `run-query.sh` verifies six unique retrieval+graph queries with fresh demo tokens.

B2–B6 remain paused; no kappa is claimed.
