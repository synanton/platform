# #699 visibility backfill switchover — evidence package

**Status:** switchover executed; fail-closed runtime enabled; evidence complete.
**Tenant:** `demo`
**Deployed image:** `local-registry:5000/synanton/synquest@sha256:5490e9f690776d84685875039ef0d2efceb527b25d41a1e7c620a3ff1198b1cc` (commit `31c2f13d`)
**Runtime:** `SYNQUEST_SEARCH_VISIBILITY_FAIL_OPEN_UNMAPPED=false`

## Gate results

| Gate | Result | Evidence |
|---|---|---|
| 1 — apply | PASS: 23 approved sources, 23 `source_current` rows, no duplicates | [`pre-apply.txt`](pre-apply.txt), [`apply.txt`](apply.txt) |
| 2 — post-apply audit | PASS: `repairable=0`, `visible_current=31`, `non_latest_current=0`, `total_indexed_chunks=19127` unchanged | [`post-apply-audit.txt`](post-apply-audit.txt) |
| 3 — deploy gated image | PASS: registry digest = deployment image = pod image ID; fail-open still `true` | [`deploy-digest.txt`](deploy-digest.txt) |
| 4 — flip | PASS: runtime env = `false` | [`runtime-config.txt`](runtime-config.txt) |
| 5 — metric | PASS with caveat: `hidden_unmapped` meter absent/0 increments; audit `hidden_unmapped=0` | [`metrics.txt`](metrics.txt) |
| 6 — search spot checks | PASS after reindex: A/B/D present, C absent | [`search-spot-checks.md`](search-spot-checks.md) |
| 7 — evidence | This package | all files here |

## Apply

The tool applied only `APPROVE` dispositions. The pre-apply audit showed 23 approved (12 lifecycle-completed, 11 legacy with published `SourcePublished` evidence), 0 excluded, 0 investigate. Apply wrote 23 rows with no duplicate source IDs and no errors.

## Count reconciliation (important)

The gate text expected `would_hide = 19,096 − 27 = 19,069`. That arithmetic double-subtracts the 27 repairable chunks: the pre-apply report's `would_hide_after_backfill=19096` already excluded the 27 repairable chunks, which were counted separately. After apply:

```text
visible_current = 31          (4 pre-existing + 27 repairable)
hidden_superseded = 19,096    (unchanged)
total_indexed_chunks = 19,127 (31 + 19,096)
```

The five Gate 2 invariants that matter — `repairable=0`, `visible=31`, `non_latest_current=0`, total unchanged, and every hidden chunk being a superseded revision — pass. The `19,069` figure is an arithmetic error in the gate, not a data discrepancy.

## Reindex deviation

Gate 6 case B (a repaired `books-demo` candidate) initially returned zero hits. The chunk existed in Cassandra and its `source_current` pointer was set, but it was missing from the live Lucene projection. A full tenant reindex was required:

```text
POST /v1/search-projection/rebuild
{"status":"done","tenant_id":"demo","chunk_count":19127,"strategy":"bm25"}
```

After the reindex, B passed; C remained hidden and A/D remained visible. The reindex is now a documented prerequisite for legacy candidates: repairing `source_current` does not retroactively project chunks into Lucene.

The reindex increased `/index/stats` `doc_count` from 19,136 to 38,257, which suggests the rebuild retained old chunk documents. No duplicate hits appeared in the four spot checks, but this is a separate index-health finding and was filed as #706. It does not invalidate the fail-closed visibility checks, but it should be investigated before relying on index-size or ranking metrics.

## Files

- [`pre-apply.txt`](pre-apply.txt) — pre-apply audit (disposition + conditional count)
- [`apply.txt`](apply.txt) — apply output (23 rows)
- [`post-apply-audit.txt`](post-apply-audit.txt) — post-apply audit
- [`deploy-digest.txt`](deploy-digest.txt) — registry digest, deployment image, pod image ID
- [`runtime-config.txt`](runtime-config.txt) — runtime env false
- [`metrics.txt`](metrics.txt) — metric probe and audit counts
- [`search-spot-checks.md`](search-spot-checks.md) — four cases and reindex note
- [`reindex.json`](reindex.json), [`index-stats.txt`](index-stats.txt) — reindex result and index stats
- [`search/`](search/) — raw A/B/C/D responses
- [`audit-disposition.txt`](audit-disposition.txt) — per-source disposition used for apply

## Boundaries

- No graph/evidence retirement (#655); no search failure-model work (#656).
- No #654 protocol changes.
- Extraction remains `EXTRACTION_PROVIDER=regex`; `vllm-extraction` remains `0/0`.
- The reindex index-size anomaly is a follow-up, not a reason to re-open the 23-source disposition.
