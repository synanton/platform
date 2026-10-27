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

## Query sanitization policy

The platform's Jackson `SanitizingStringDeserializer` HTML-escapes incoming strings (`'` → `&#39;`). The demo service:

- unescapes the query for cache lookup and query-type classification;
- sends the **post-sanitizer, as-received** form to BM25, matching `/v1/search`;
- reads the dense cache against the unescaped question.

The BM25 index is built from the unescaped corpus text. `report's` was verified through both the runner and `/v1/demo/search`. `AT&T` and `VLAN < 100` are not yet exercised through the endpoint because it only serves cached query embeddings; add them to the demo cache before claiming that edge is covered.

## Remaining local gate

Browser smoke (load, four queries, em-dash, query-type badge, forced-token-expiry retry, refresh) was not performed in this CLI environment. API 401→fresh-token→200 was verified with real security issuance; UI retry is unit-tested and logs `401 → minted fresh token → retry`.


## Browser smoke (headless Chromium via CDP)

Passed: four retrieval queries with expected strips, `Dense —` on rb010, query-type badge, forced 401 → fresh token → success, browser refresh → search success.
Script: `scripts/demo/browser-smoke.py`; operator script/checklist: `scripts/demo/start-browser-demo.sh` and `docs/implementation/demo/browser-demo.md`.

## Demo endpoint scope

Slice 1 demo uses **option A: cached queries only**. Fixed four queries are reliable; impromptu queries use the local runner. Live-TEI fallback (option C) is a follow-up and must not be claimed yet.
