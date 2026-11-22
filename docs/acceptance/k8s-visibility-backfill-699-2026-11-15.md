# #699 visibility backfill dry-run — 2026-11-15

**Status:** dry-run complete; **fail-closed switch NOT flipped**.
**Tenant:** `demo`
**Cluster:** homelab, namespace `synanton`
**Artifacts:** [`artifacts/k8s-visibility-backfill-699-2026-11-15/`](artifacts/k8s-visibility-backfill-699-2026-11-15/)

## What was run

```bash
kubectl -n synanton port-forward svc/cassandra 29042:9042
CASSANDRA_HOST=localhost CASSANDRA_PORT=29042 CASSANDRA_LOCAL_DC=datacenter1 \
  ./gradlew :java:ingestion-cache:runVisibilityBackfill \
  --args='--tenant=demo --dry-run' --no-configuration-cache
```

Tool: `SourceVisibilityBackfill` (read-only dry-run; `--apply` is the only write mode).
The fail-closed flag/metric is implemented in `LuceneKnowledgeSearchBackend` but remains at its default `fail-open-unmapped=true`.

## Result

```text
tenant=demo
sources_total=37
sources_with_current=7
repairable_sources=23
total_indexed_chunks=19127
visible_current=4
repairable_by_backfill=27
would_hide_after_backfill=19096
  hidden_unmapped=0
  hidden_deleted=0
  hidden_no_current=0
  hidden_superseded=19096
```

Top hidden examples (all `superseded`):

```text
b7487374-ece9-5b89-be52-e895f40eb13d:0 source=ui-upload-browser reason=supporting revision is not current
b7487374-ece9-5b89-be52-e895f40eb13d:1 source=ui-upload-browser reason=supporting revision is not current
...
fab654b2-30c5-5e07-a43c-848a3406c2aa:0 source=acceptance-695c-1794731388 reason=supporting revision is not current
6d7dae1c-c428-52a9-a790-382e4e1f21c0:0 source=acceptance-695e-1794732035 reason=supporting revision is not current
```

Full output: [`dry-run.txt`](artifacts/k8s-visibility-backfill-699-2026-11-15/dry-run.txt).

## Interpretation

- The first dry-run classified 19,121 chunks as `no_current`. That was too coarse: legacy source-version rows have `generation=null`, null graph status, and no activation rows, so the original rule could not identify a legacy current candidate.
- The refined rule treats the **highest observed indexed revision** as the legacy current candidate when a source has no activation history; a source with activation rows but no `CURRENT` remains excluded (failed/in-progress). This reclassified the backlog as `superseded` relative to a chosen candidate.
- After refinement there are **zero unmapped, zero deleted, and zero no-current** chunks. Every would-be-hidden chunk is an older version of a source that has a current pointer or a legacy candidate.
- The 27 repairable chunks are the candidate revisions of 23 legacy sources missing a `source_current` row. Applying the backfill would create those 23 rows and move 27 chunks from `repairable` to `visible`.
- The 19,096 `superseded` chunks are older revisions; hiding them is the intended effect of current-revision filtering. The volume is large because the legacy search index accumulated many versions.

## Decision

**Do not flip fail-closed yet.** The dry-run is the review artifact. The volume is explainable but large enough to require explicit review of the legacy-candidate rule and a sample of legacy source histories before applying the 27 repairs and flipping the switch.

If approved:

1. Re-run with `--apply` to create the 23 missing `source_current` rows (idempotent, checkpointed).
2. Re-run the dry-run: `repairable_by_backfill` should become 0 and `visible_current` should become 31.
3. Set `SYNQUEST_SEARCH_VISIBILITY_FAIL_OPEN_UNMAPPED=false` on synquest and verify `search.visibility.hidden_unmapped` matches the reviewed `superseded` count for the queried scope.
4. Record the before/after counts and the metric in this document.

## Test coverage added

`LuceneKnowledgeSearchBackendTest.failClosedHidesUnmappedChunksAndCountsThem` proves an unmapped chunk is hidden when the flag is false and the `search.visibility.hidden_unmapped{reason=unmapped}` counter increments.

## Boundaries

- This is a search-visibility data operation; it does not change the #654 ownership protocol.
- It does not retire graph evidence (#655).
- It does not define the #656 failure model.
