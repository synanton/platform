# GPU-7 Phase 2 — provenance-backed results (partial)

**Status:** DeepSeek complete; GLM partial; no acceptance decision.
**No new inference after the GLM interruption.**
**Provenance:** each call records request ID, doc/chunk ID, model/provider,
settings, usage, response hash and raw response path.

## DeepSeek V4 Pro — 25/25 documents

| Metric | Value |
|---|---:|
| Documents | 25/25 |
| Chunks / valid JSON chunks | 202 / 119 (58.9%) |
| Entity TP / predicted / gold | 2953 / 5307 / 7916 |
| Entity precision / recall / F1 | 0.556 / 0.373 / **0.447** |
| Relation TP / predicted / gold | 282 / 2274 / 2280 |
| Relation precision / recall / F1 | 0.124 / 0.124 / **0.124** |
| Direction errors | 10 |
| Missing relation endpoints | 0 |

## GLM-5.3-Flash — 5/25 documents (partial)

| Metric | Value |
|---|---:|
| Documents | 5/25 (001–005) |
| Chunks / valid JSON chunks | 5 / 5 |
| Entity TP / predicted / gold | 54 / 93 / 70 |
| Entity precision / recall / F1 | 0.581 / 0.771 / **0.663** |
| Relation TP / predicted / gold | 7 / 37 / 44 |
| Relation precision / recall / F1 | 0.189 / 0.159 / **0.173** |
| Direction errors | 0 |
| Missing relation endpoints | 0 |

GLM is **not comparable**: it covers only the five small documents and was
interrupted before the PDFs.

## Spend

- Gateway `p2v2-*` entries: **$1.5230**.
- Gateway total today: **$2.8980**.
- Remaining to $5.00: **$2.1020**.
- No further calls are authorized.

## Interpretation

DeepSeek completed the 25-document set, but structural validity is only 58.9%
of chunks and relation F1 is low. This is not a quality acceptance result.
The relation scorer is the repaired version with regression tests. Raw responses
are preserved under the per-model run directories.

## Limitations

- Chunked books produce partial-coverage annotations; cross-chunk relations are
  not recovered.
- Invalid/truncated chunk responses are excluded rather than attributed.
- GLM is incomplete and must not be compared against DeepSeek.
- No HOLDOUT or production data was used.
