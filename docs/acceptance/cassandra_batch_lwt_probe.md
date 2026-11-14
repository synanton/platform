# Cassandra batch-LWT ownership probe — 2026-11-14

**Status:** confirmed on `cassandra-0`; implementation input for ADR-029 §12.4.
**Cluster:** Cassandra `4.1.12`; DataStax Java driver `4.17.0`.
**Scope:** prove that a LOGGED batch can atomically combine an unconditional
promotion-row insert with a conditional ownership update in the same partition.

## Driver constraint

The DataStax Java driver `4.17.0` has no `BatchStatement.conditional()` API.
The supported pattern is a `BatchStatement` of type `LOGGED` containing:

1. one unconditional `INSERT` for the promotion row; and
2. one conditional `UPDATE ... IF ...` for the ownership sentinel.

Cassandra treats the batch as a lightweight transaction. If the `IF` condition
fails, the unconditional insert is not applied, the result reports
`[applied]=false`, and no promotion row exists.

## Sentinel and probe CQL

Ownership sentinel, same partition as the promotion rows:

```sql
INSERT INTO ingestion_cache.relation_promotion_results
  (tenant_id, source_version_id, relation_id, owner_producer_id,
   owner_generation, owner_closed, owner_started_at, owner_takeover_count,
   owner_last_write_at)
VALUES (?, ?, '__OWNERSHIP__', ?, 1, false, toTimestamp(now()), 0, toTimestamp(now()))
IF NOT EXISTS;
```

Promotion row plus ownership fence:

```sql
BEGIN BATCH
  INSERT INTO ingestion_cache.relation_promotion_results
    (tenant_id, source_version_id, relation_id, subject_mention_id,
     object_mention_id, predicate, promotion_status, canonical_subject_id,
     canonical_object_id, reason, evidence_json, strategy_version,
     ontology_version, decided_at, claim_status, attempt_count)
  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);

  UPDATE ingestion_cache.relation_promotion_results
  SET owner_last_write_at = toTimestamp(now())
  WHERE tenant_id = ? AND source_version_id = ? AND relation_id = '__OWNERSHIP__'
  IF owner_producer_id = ? AND owner_generation = ? AND owner_closed = false;
APPLY BATCH;
```

Close is a separate sentinel LWT:

```sql
UPDATE ingestion_cache.relation_promotion_results
SET owner_closed = true, owner_closed_at = toTimestamp(now()),
    owner_last_write_at = toTimestamp(now())
WHERE tenant_id = ? AND source_version_id = ? AND relation_id = '__OWNERSHIP__'
IF owner_producer_id = ? AND owner_generation = ? AND owner_closed = false;
```

## Observed results

| Probe case | Batch condition | `wasApplied()` | Stored state |
|---|---|---:|---|
| Matching owner | `owner_producer_id='producer-a' AND owner_generation=1 AND owner_closed=false` | `true` | row inserted; `owner_last_write_at` advanced |
| Mismatched generation | `owner_producer_id='producer-a' AND owner_generation=2 AND owner_closed=false` after takeover to generation 2 | `false` | no promotion row; sentinel unchanged |
| Closed set | `owner_producer_id='producer-a' AND owner_generation=1 AND owner_closed=false` after close | `false` | no promotion row; sentinel remains `owner_closed=true` |

A matching condition returned `true`; a mismatched owner/generation returned
`false`; a closed set returned `false` with no row. In both failing cases the
unconditional row insert rolled back with the batch, so the table never held a
partial promotion row.

## Implementation note

`PromotionOwnershipStore.writeFenced` uses exactly this shape: a LOGGED
`BatchStatement` with the caller-supplied unconditional row `INSERT` and one
conditional sentinel `UPDATE`. It throws `StaleOwnershipException` when
`wasApplied()` is `false`. It does not use `BatchStatement.conditional()`, does
not put `IF` on both statements, and does not issue a separate heartbeat query.
