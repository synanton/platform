# PG-POC-017 R0 Escalation — 028 Is a Build, Not a Run

**To:** PoC workstream (YDB 028 owner)
**From:** PG track, `DESIGN-PostgreSQL`, R0 evidence (`017-r0-report.md`)
**Date:** 2026-09-27

## Finding

The runbook assumed runnable artifacts; R0 proves they do not exist. 028 was
scoped as "run the benchmark." It is "build the benchmark" — weeks, not a run.

| Unbuilt artifact | Owner | Evidence |
|---|---|---|
| v1 corpus generator | YDB 028 | 005 is a definition doc; no generator in `storage-testkit` fixtures |
| BaselineBench on v1 + multi-tenant | YDB 028 | Self-contained seed-42, single-tenant, aggregate BENCH lines only |
| Q3 emitter, baseline leg | YDB 028 | No per-query top-K or eligible sets emitted |
| Q3 emitter, 024A | PG/YDB 028 | 024A has no bench driver |
| Q3 emitter, 024B | YDB | Not reported as existing |

None of these is on the YDB ticket list. Request: 028 be re-scoped as build
work with estimates before anyone schedules "the run."

## Threshold integrity question (answer before the build begins)

006's frozen thresholds (lex p95 ≤ 0.72 ms, vec p95 ≤ 9.5 ms, hybrid p95 ≤
9.3 ms, lex Recall@10 ≥ 0.98) were measured by `BaselineBench` at
`-Dydb.bench.docs=20000 -Dydb.bench.chunks=8`, seed 42, **40** golden queries,
**single tenant**. 005's v1 corpus is 20,000 docs, **120** golden queries,
**50 Zipf tenants** with eligibility fixtures. Same chunk count (160k),
different corpus: 006 binds to BaselineBench-seed42, not to v1.

Which corpus does the 4-leg benchmark run on?

- **BaselineBench's corpus** → 005 is irrelevant to 028; say so, and the
  eligibility legs need a multi-tenant harness regardless.
- **v1 generator output** → 006's thresholds were measured on the wrong corpus
  and must be re-measured and re-frozen before 028 runs (same class as
  "vector recall unclaimed on synthetic" — a threshold gating a corpus it was
  never measured on).

Either answer is acceptable. An unanswered question is not — it determines
what gets built.
