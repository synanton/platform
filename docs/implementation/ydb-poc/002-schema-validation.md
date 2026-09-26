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
