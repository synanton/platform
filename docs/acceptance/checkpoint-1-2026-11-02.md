# Checkpoint 1 scope and evidence (2026-11-02)

## Panel scope (option a)

Checkpoint 1 ontology panel: renders the 273 committed entities in categories and filters by `df_band` (`graph_useful` / `all`). Click-to-filter-search is deferred to checkpoint 2. Checkpoint 1 does **not** include the graph UI panel; it includes the graph API only.

Constants before panel code: total 273, hard 198, soft_glossary 72, soft_doctrinal 3, graph_useful 31, graph_useful_hard 31, graph_useful_soft 0.

## Checkpoint evidence

1. Deploy the pinned platform/UI SHAs.
2. Wait for `/health` ready.
3. Reindex `demo-v2`; verify `/v1/demo/search` returns 200 before launching the UI.
4. Run and record:
   ```bash
   SYNANTON_SYNQUEST_URL=http://<node>:<port> \
   SYNANTON_SECURITY_URL=http://<node>:<port> \
   ./scripts/demo/check-graph-neighbors.sh
   ```
   Expected: `rb010` 12/14/16, empty 0, unknown 404.
5. Verify ontology API:
   - `?df_band=all` → 273
   - `?df_band=graph_useful` → 31
   - `?df_band=graph_useful&entity_class=hard` → 31
   - `?df_band=graph_useful&entity_class=soft_glossary` → 0
- `?df_band=pair` → 38
- `?df_band=pair&entity_class=hard` → 30
- `?df_band=pair&entity_class=soft_glossary` → 8
- `?df_band=pair&entity_class=soft_doctrinal` → 0
6. Load the panel; verify render + df-band filter and record the deployed SHAs.

## Merge gate

#469/UI #24 merge only if both PR CI runs are green at the current head, no review comments remain, and the 10-31 checkpoint-1 dry run passes. If the dry run fails, defer the merge or update the pinned SHA to the fix commit.
