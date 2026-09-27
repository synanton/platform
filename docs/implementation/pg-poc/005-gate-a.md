# PG-POC-005 — Gate A Outcome: RLS × ANN Composition

**Status:** PASS with recorded caveat (see below)
**Date:** 2026-09-27
**Image:** `pgvector/pgvector:0.8.6-pg16` (PostgreSQL 16.15, pgvector v0.8.6)
**Probe:** `GateAProbeTest` (raw SQL, no adapter) + `GateACorpus` seeder

## Frozen probe query (both legs)

```sql
SELECT chunk_id, tenant_id FROM chunks
ORDER BY embedding <-> '[1,0,...,0]'  -- e1, 384-d
LIMIT 10
```

as the seeded tenant via `SET LOCAL app.tenant_id` in an explicit transaction.

## Corpus shape (adversarial by construction)

| Tenant | Rows | Distance from query | Selectivity | Purpose |
|---|---|---|---|---|
| `tenant_big` | 20,000 near (e1 + σ0.05 noise, d < ~2) | near | — | cross-tenant neighbours: unfiltered top-10 is entirely ineligible |
| `tenant_small` | 20 far (e1 + 10·e2, d = 10) | far | ~0.1% | leg 1: hardest case |
| `tenant_half` | 10,000 near | near | ~33% | leg 2: eligible set too large to brute-force cheaply |

Seed: `Random(0xC0FFEE)`, deterministic. Synchronous superuser cleanup in `@AfterAll`.

## Verbatim evidence

Full `EXPLAIN (ANALYZE, BUFFERS, VERBOSE)` output, unredacted, under
`005-gate-a-plans/`:

- `explain-selective-natural.txt` — leg 1, planner's choice.
- `explain-selective-forced-index.txt` — leg 1, `enable_seqscan = off`.
- `explain-leg2-natural.txt` — leg 2, planner's choice.

Plan shapes (vector literals elided here only; verbatim in the files):

- Leg 1 (both variants): `Limit → Sort → Index Scan using
  chunks_tenant_doc_btree` with `Index Cond: tenant_id =
  current_setting('app.tenant_id', true)::uuid`, 20 rows into the sort, 10 out.
- Leg 2: same shape, 10,000 rows into the sort (8.5 ms), 10 out.

## Outcome decision: 1 (RLS composes pre-ranking) — with caveat

**Behavioral legs (both green):** 10/10 rows returned on each leg, every row
belongs to the eligible tenant. Leakage = fail held. The post-ANN signature
(short/empty result with eligible rows present) did not appear.

**Plan legs:** RLS is an `Index Cond` on the tenant btree — the eligible set
is fixed before the distance sort on both legs. This is pre-ranking
composition at plan level, the property Gate A exists to prove.

**Caveat (recorded, not hidden):** the HNSW index never entered any
tenant-scoped plan — not at 0.1%, not at ~33%, not with seqscan forced off. A
follow-up scratch check (unfiltered `ORDER BY embedding <-> q LIMIT 10` as
superuser, plan since deleted with the scratch test) returns `Index Scan using
chunks_embedding_hnsw`. The index is usable; the miss is **planner cost
preference** — btree-restrict-then-sort is estimated cheaper at 30k rows — not
a structural exclusion. Phase 4 re-verification is therefore actionable: at a
corpus scale where HNSW wins, record whether RLS sits inside the HNSW scan or
after it. If post-ANN filtering appears there, the Outcome 2 machinery
(explicit predicate + two-part proof + `*_predicate_composed` suffixes) applies
unchanged.

**Metric rename (topology in the name):** Phase 2's vector leg measures
btree-restrict-then-sort, not ANN. Its metric is `vec_p95_btree_sort`, never
`vec_p95_ann`. When Phase 4 exercises HNSW at scale, the metric name changes
with it — the two numbers are not comparable without the annotation. Never
compare PG's btree-sort latency against YDB's ANN latency as the same
operation.

**What this closes (precise scope):** the Must — security eligibility
composed into candidate generation before ranking — is satisfied **via the
btree-restrict-then-sort path**. RLS × ANN interaction is unobserved at PoC
scale and deferred to the Phase 4 scale test with Outcome 2 machinery on
standby. Gate A passes; PG Phase 2 remains blocked only on Gate B.

**Collapse scope if re-opened:** PG engine + 028-as-Postgres collapse;
024A/024B unaffected; Phase 6 falls back to Outcomes 1–5 / partial-Postgres
(proposal §8.4).
