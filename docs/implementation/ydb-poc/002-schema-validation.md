# YDB-POC-002 — Schema Validation vs Pinned Release (26.3.1.16)

**Status:** Closed with follow-ups (Phase 0A)
**Depends on:** YDB-POC-001
**Proposal ref:** §11.1 (candidate schema is a PoC starting point, not production DDL)

## Construct-by-construct check

| §11.1 construct | YDB 26.x support | Verdict |
|---|---|---|
| `Utf8`, `Uint64/Uint32`, `Timestamp`, `Json` column types | Stable YQL types | ✅ OK |
| `PRIMARY KEY (tenant_id, doc_id)` composite keys | Stable | ✅ OK |
| Secondary index / `doc_id` lookup | Stable (`CREATE INDEX` / global async index) | ✅ OK |
| `metadata Json` filtering | JSON functions stable; deep-filter performance must be measured (Phase 1/2) | ⚠️ Measure |
| Full-text index on `chunks.text` | Available in recent releases; exact syntax must be confirmed against a live 26.3 instance | ⚠️ Confirm live |
| Vector ANN index on `chunks.embedding` | Available; index kind, distance metric, and parameters are release-sensitive | ⚠️ Confirm live |
| `embedding ...` placeholder type/dimension | Must be pinned to the concrete 26.3 vector type before Phase 1 | 🔴 Pin in 011/021 |
| `published_at Timestamp NULL` nullable column | Stable | ✅ OK |

## Follow-ups (owners in Phase 0B/Phase 1)

1. **Live-DDL confirmation** (YDB-POC-021 pre-req): run the §11.1 DDL plus the FTS/vector index statements against a real 26.3.1.16 instance; commit the working DDL and record every deviation from §11.1.
2. **Vector dimension pin** (feeds YDB-POC-018): record concrete vector type, dimension, distance metric, index kind; document whether dimension is fixed at table creation and how embedding-model migration would work.
3. **JSON-filter selectivity** (feeds §14 matrix): 0.1%/1%/10%/100% filter measurements happen in Phase 2/4 — no action now.

## Acceptance

Working DDL is **not** produced here (no live instance in Phase 0A); the acceptance for 002-close is this validation table plus the three tracked follow-ups above. Full 002 acceptance (working DDL committed) completes before YDB-POC-021 starts.

## Gate 0 probe findings (2026-09-26, live 26.3 — closes the vector/FT syntax follow-ups)

- Vector: `GLOBAL USING vector_kmeans_tree ON (embedding)`, filtered
  `ON (tenant, embedding)` with mandatory tenant equality; query via
  `VIEW` + 2-arg `Knn::CosineSimilarity`; writes via
  `Untag(Knn::ToBinaryStringFloat([CASTs]), 'FloatVector')` (`String` ≠ `Utf8`
  params matter). Build index AFTER representative data (empty-table indexes
  degrade to scans); ANN approximate (`KMeansTreeSearchTopSize` tunes recall).
- Full-text: `GLOBAL USING fulltext_relevance ON (body)` +
  `tokenizer=standard, use_filter_lowercase=true`; alias-form
  `FulltextScore` (repeated calls unsupported); `VIEW` mandatory (optimizer
  won't auto-select). Filtered (prefixed) FT is **flag-disabled** on this
  build — tenant scoping via unfiltered index + equality works. Integer-PK
  tables avoid `__ydb_row_id` machinery. `BulkUpsert` unsupported with FT.
- Open for 024B: 26.3 hybrid search exists but is **disabled by default**
  (feature flag); confirm enablement path before the hybrid leg. Vector
  dimension fixed per index (`vector_dimension`) — model-migration constraint
  stands (018).

## Hybrid flag resolution (2026-09-26 — go, with a production note)

- The flag is **cluster-level**: `table_service_config: enable_hybrid_search: true`
  in the cluster YAML. Enabled on the PoC container (config survives restart);
  `HybridRank` with tenant equality returns eligible-only ranked rows.
- GA status: 26.3 RC lists hybrid under "Disabled functionality — not enabled by
  default" → treat as **non-GA-by-default**. Production deployments must set the
  flag explicitly (recorded in 003 as a production-readiness item, not a blocker).
- Hybrid composes with `WHERE tenant=…` on the base table (no `VIEW`; indexes
  resolve from `HybridRank` args). Prefixed vector indexes unsupported by
  `HybridRank` — 024B uses non-prefixed vector index + predicate, as probed.

## Cluster flags required on the PoC build (2026-09-26, all proven)

```yaml
table_service_config:
  enable_hybrid_search: true
feature_flags:
  enable_fulltext_index_row_id: true   # FT on non-integer-PK tables
  enable_fulltext_index_prefix: true   # filtered (prefixed) FT indexes
  enable_add_unique_index: true        # __ydb_row_id provisioning machinery
  enable_online_add_unique_index: true
```

Without the row-id/unique pair, FT creation on composite-PK tables fails
(`Auto-provisioning '__ydb_row_id' ... requires the unique-index feature`);
without the prefix flag, filtered FT fails. All four set via container config
+ restart; config survives restart. Any fresh PoC container needs the same
four lines — see `dev-guide-tests.md`.

## Knowledge vs adapter-requirement marking

Recorded knowledge (this doc): syntax ladder results, flag names, format quirks.
Adapter-contract requirements for 024B (must be encoded as tests, not re-read
from here): `Untag(ToBinaryStringFloat)` writes; `String`≠`Utf8` params;
`VIEW`-mandatory + alias-form `FulltextScore`; unfiltered-FT + equality
fallback as default with logged path; indexes built AFTER data (Suite asserts
usage, see preflight); `KMeansTreeSearchTopSize` value recorded with recall
runs; hybrid flag presence asserted at suite setup.
