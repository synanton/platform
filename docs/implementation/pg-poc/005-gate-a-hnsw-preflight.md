# PG-POC-005 follow-up — HNSW-leg re-verification preflight

**Status:** DRAFT / STANDBY — activates when the Phase 4 corpus reaches a
scale where the planner chooses HNSW, or on an explicit pull-forward
decision (which pays large-corpus infra early; default is to wait).
**Parent:** `005-gate-a.md` caveat (2026-09-27). Gate A passed via
btree-restrict-then-sort; RLS × ANN interaction is unobserved, not refuted.

## Question

At a corpus scale where the planner picks `chunks_embedding_hnsw` for a
tenant-scoped `ORDER BY embedding <-> q LIMIT K`: does the RLS predicate
sit **inside** the HNSW scan or **filter after** it?

## Method (frozen before running)

1. Scale `GateACorpus` keeping its adversarial shape (one ~0.1%-selectivity
   tenant with far rows, one ~33% tenant, one large cross-tenant near mass;
   same `Random(0xC0FFEE)` seed family), growing total rows until the
   planner's natural choice for the tenant-scoped probe query is the HNSW
   index — no `enable_seqscan = off` forcing for the decision leg (a forced
   leg may be recorded as a supplement, never as the verdict).
2. Probe query (frozen; 384-d `e1`, same as Gate A):
   ```sql
   EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
   SELECT chunk_id, tenant_id FROM chunks
   ORDER BY embedding <-> '[1,0,...,0]'
   LIMIT 10;
   ```
   as the seeded tenant via `SET LOCAL app.tenant_id` in an explicit
   transaction. Plan stored verbatim under `005-gate-a-plans/`
   (`explain-hnsw-<selectivity>.txt`); literals elided in prose only.
3. Adversarial behavioral leg (Outcome 2 path only): corpus arranged so the
   unfiltered top-K is entirely cross-tenant; adapter must return eligible
   candidates or fail.

## Three outcomes (pre-decided, proposal §8.4)

- **Outcome 1 — RLS inside the HNSW scan.** Predicate appears in the same
  plan node as the index scan; behavioral legs fully eligible. → Proceed.
  Metric: `vec_p95_ann_rls_native`. Closes the 005 caveat.
- **Outcome 2 — RLS filters post-ANN.** Short/empty results with eligible
  rows present, or predicate outside the scan node. → Outcome 2 machinery
  applies unchanged: explicit predicate composition + two-part leakage-proof
  (EXPLAIN node check + adversarial test) + `*_predicate_composed` metric
  suffixes. Never `vec_p95_ann` bare.
- **Outcome 3 — eligible-ID materialize + intersect.** → Thresholds
  re-examined per the YDB Option B rule (absolute-vs-requirement, not
  adapter-vs-baseline) before any Phase 2 benchmark runs.
- **None holds → PG Synquest collapses.** PG engine + 028-as-Postgres
  collapse; 024A/024B unaffected; Phase 6 falls back to Outcomes 1–5 /
  partial-Postgres. Do not benchmark past it.

## Dimension / recall discipline

Vectors are synthetic 384-d (seeded noise around `e1`), same family as
Gate A and the YDB leg. **Recall is unclaimed here in exactly the same
way:** no labeled corpus, no recall number — record that explicitly,
never let a distance-ordering observation promote itself into a recall
claim. A claimed recall needs the 028 corpus with judgments, which is
Phase 6 business, not this probe's.

## Acceptance

- Verbatim plan(s) in `005-gate-a-plans/`; forcing status of each leg labeled.
- Outcome decision recorded (this file flips STANDBY → DECIDED).
- Metric suffix applied per outcome; `vec_p95_btree_sort` vs `vec_p95_ann_*`
  never compared as the same operation.
- `005-gate-a.md` caveat closed (Outcome 1) or escalated (Outcomes 2/3).
