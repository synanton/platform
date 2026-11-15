# #699 search spot checks — 2026-11-15

Pod: synquest `31c2f13d`, fail-closed runtime `SYNQUEST_SEARCH_VISIBILITY_FAIL_OPEN_UNMAPPED=false`.

| Case | Query | Expected | Observed | Result |
|---|---|---|---|---|
| A — previously visible | `beta695final1794732035` | present | 1 hit, source `acceptance-695e-1794732035` | PASS |
| B — newly visible (repaired candidate) | `TensorFlow` | present | 1 hit, source `books-demo`, object `29890128-88d8-5ac4-8ce6-c7ccebed0a6c:0` | PASS |
| C — superseded | `Review & Self Study` | absent | 0 hits | PASS |
| D — unaffected current | `beta695run1794731388` | present | 1 hit, source `acceptance-695c-1794731388` | PASS |

Raw responses: [`search/A.json`](search/A.json), [`search/B.json`](search/B.json), [`search/C.json`](search/C.json), [`search/D.json`](search/D.json).

## Reindex deviation

Case B initially returned 0 hits after the apply and fail-closed flip. The candidate chunk existed in Cassandra (`chunks_payload` for `29890128...`) and its source_current pointer was set, but it was not in the live Lucene projection. A full tenant reindex was required:

```text
POST /v1/search-projection/rebuild
{"status":"done","tenant_id":"demo","chunk_count":19127,"strategy":"bm25"}
```

After the reindex, case B passed. This is a required step: repairing `source_current` does not retroactively project legacy chunks into the Lucene index.

## Index-health follow-up

`GET /index/stats?tenant=demo` after the reindex:

```text
doc_count=38257
index_generation=176
status=ready
vector_docs=9
missing_vectors=19118
section_docs=15603
```

Before the reindex, `doc_count` was 19,136 (~19,127 chunks + 9 entity docs). The full rebuild increased it to 38,257, suggesting the rebuild retained old chunk documents instead of replacing them. The four spot checks show no duplicate hits, but the index-size behavior is a follow-up finding; see #706.

## Metric

`GET /actuator/metrics/search.visibility.hidden_unmapped` returned HTTP 404: the counter has zero increments because no hidden-unmapped chunk was encountered. The post-apply audit reports `hidden_unmapped=0` and `hidden_superseded=19096`. The audit count is the total; the runtime counter is per-query and only exists after an increment.
