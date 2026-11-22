# OGCE JSONL slice — variance addendum

**Run:** 2026-11-22T15:40:04Z  
**Scope:** exploratory contract variance; not F1, not G-10 p95, not profile qualification.

## Primary document — n=10 latency

- Segment 1 (285 tokens)
- p50 **18.2517 s**, p95 **18.2585 s** (min 18.2459, max 18.2585)
- Unique output hashes: 1; format signatures equal: True
- Seed runs: [{"seed": 42, "output_sha256": "dc4ff1a837cbd52ae3d2da44532f0459a1b94b7acae108b1faba71b06c4f59c4", "format_equal_to_seed_42": true, "latency_s": 18.2537}, {"seed": 43, "output_sha256": "dc4ff1a837cbd52ae3d2da44532f0459a1b94b7acae108b1faba71b06c4f59c4", "format_equal_to_seed_42": true, "latency_s": 18.2509}, {"seed": 44, "output_sha256": "dc4ff1a837cbd52ae3d2da44532f0459a1b94b7acae108b1faba71b06c4f59c4", "format_equal_to_seed_42": true, "latency_s": 18.2511}]
- Tier 2 JSONL grammar enforced: **True**

## DEV document runs (text only; no labels)

| Doc | Stratum | Tokens | Chunks | Segments | Accepted | Quarantined | E1 | E2 | E3 | E5 | p50 | p95 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| g7-dev-011 | relationship_heavy | 327 | 1 | 1 | 3 | 3 | True | True | True | True | 4.8975 | 7.9085 |
| g7-dev-013 | relationship_heavy | 521 | 2 | 3 | 7 | 4 | True | True | True | True | 3.6492 | 10.1315 |
| g7-dev-016 | cross_document_references | 334 | 1 | 1 | 3 | 0 | True | True | True | True | 4.9018 | 5.0117 |
| g7-dev-018 | cross_document_references | 498 | 1 | 2 | 6 | 8 | True | True | True | True | 8.2757 | 15.1363 |
| g7-dev-023 | tables_structured | 66 | 1 | 1 | 6 | 0 | True | True | True | True | 6.0386 | 6.0625 |

**Aggregate latency:** p50 **8.1994 s**, p95 **18.2551 s** over 34 calls.

## Limitations

- Five non-frozen DEV documents, not the full 25-document matrix. The frozen ADR-022 five are excluded.
- DEV labels are not used; no F1, gate, or profile claim is made.
- The 3-class/2-relation slice schema does not match every DEV document's type vocabulary; quarantine counts reflect that mismatch.
- n=10 / n=3 latencies are exploratory. G-10 still requires a pinned benchmark protocol, repeated runs, and review on production modules.
- E6 gold-span alignment is deferred: `evaluation/dev25/README.md`, `gold-release.md`, and `manifest.tsv` disagree on the 20 DEV annotations.
