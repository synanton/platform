# DeepSeek Phase 2 reconstruction report

**Status:** reconstruction attempted; metrics NOT accepted.
**New inference:** none.
**Scorer:** fixed and regression-tested.

## What was persisted

- 93 successful `p2-*` DeepSeek executions are present in the gateway database.
- Only **52/93** responses parse to valid candidate JSON.
- 41 responses are truncated/malformed and cannot be re-scored.
- No request→document/chunk mapping was persisted with the executions. The only
  available mapping is inferred from entity-surface overlap with gold documents;
  it is not reliable enough for final metrics.

## Scorer defect

The original scorer compared a predicted object **mention ID** with a gold
object **surface**, producing relation F1 = 0 for every document. This was a
scoring defect, not a model result.

Fixed in `evaluation/dev25/relation_scoring.py`:

- resolve both subject and object IDs to normalized surfaces;
- normalize CamelCase predicates (`hasSupplier` → `has_supplier`);
- deduplicate repeated triples;
- count missing endpoints;
- count direction-reversal errors.

Five regression tests pass:
`matching`, `incorrect endpoint`, `direction mismatch`, `duplicate mentions`,
`missing endpoint`.

## Re-scoring outcome

The existing persisted outputs cannot support a defensible 14-document relation
metric:

- 41/93 chunk responses are unparseable;
- request→document identity is missing;
- inferred mappings misassign some chunks across documents;
- no new inference was performed to repair those gaps.

Entity metrics from the original logs are not affected by the relation bug, but
they are also incomplete for the missing 11 documents.

## Execution status (unchanged)

| Item | Status |
|---|---|
| DeepSeek V4 Pro | 14/25 documents attempted |
| GLM-5.3-Flash | 0/25 |
| Evaluation spend | $0.7602 |
| Remaining ceiling | $3.6250 |
| Relation metrics | invalid / not reportable |
| New inference | not authorized |

## Missing work

- 11 DeepSeek documents: `g7-dev-015` through `g7-dev-025`.
- All 25 GLM documents.
- DeepSeek also has 41 unusable chunk responses within the 14 attempted docs.

## Recommendation

Do not report relation metrics from this run. A reproducible rerun needs:

1. persist request→doc/chunk and model IDs with every execution;
2. save raw responses separately;
3. use the fixed scorer;
4. rerun DeepSeek (all 25) and GLM (all 25) in bounded batches.

Estimated incremental spend: roughly **$2.0–2.4**, within the remaining
$3.625 ceiling, but this estimate is not an authorization to spend it.
