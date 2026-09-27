# PG-POC-017 — R0 Pre-Flight Report (Runbook Execution)

**Status:** R0 partial — 6/7 green; R1 blocked on two pre-existing YDB-track gaps (recorded, not worked around)
**Date:** 2026-09-27
**Machine:** captured below (R0.1–R0.2)

## R0 verdicts

| Step | Check | Verdict |
|---|---|---|
| R0.1 | `java -version` | ✅ OpenJDK 21.0.12.1 (matches 006 "baseline ran OpenJDK 21") |
| R0.2 | Machine shape | ✅ i9-12900T, 24 CPU, 62 GiB RAM, 1.8T disk (61% used) |
| R0.3 | v1-corpus checksum vs frozen reference | ❌ **BLOCKED — Gap 1**: corpus exists as definition only (`005-corpus-definition.md`); generator was to land with testkit and is absent from `storage-testkit` fixtures; no checksum reference exists |
| R0.4 | `:java:synquest-cassandra:test` | ✅ green |
| R0.5 | `-Dydb.bench=true` flag path | ✅ accepted and executes (tiny-param probe reached the planting guard — harness runs; guard is by design, not a defect) |
| R0.6 | Config pin | ✅ `028-convergence-config.yaml` sha256 `d52191bd…7443` (4-leg extension committed) |
| R0.7 | `:java:synanton-bench-convergence:test` | ✅ 8/8 green |

## R1 blockers (both YDB-track, both pre-date PG)

**Gap 1 — no runnable v1 corpus.** 028's slice (`first-2000-docs`) and golden-K
assume a generator that was never committed. BaselineBench is self-contained
synthetic (seed 42, 2k docs default) — a different corpus, not v1.

**Gap 2 — no Q3 emitter on either leg.** BaselineBench prints aggregate BENCH
lines only (no per-query top-K, no eligible sets). 024A has no bench driver at
all. The comparator (017) is ready but has no inputs to consume. Additionally,
BaselineBench is single-tenant: eligibility-filtered legs cannot come from it
in any case — the 028 eligibility legs need a multi-tenant harness that does
not exist yet.

## Routing

Both gaps are YDB 028-track design work (corpus generator + multi-tenant Q3
harness), not PG work and not comparator work. PG built everything buildable:
comparator, config extension, Gate A/B evidence. No PG-side workaround is
proposed — synthesizing a PG-local corpus or single-tenant Q3 would produce
numbers incomparable with the frozen 028 frame, the exact failure Gate B exists
to prevent.

**Next:** YDB 028 lands generator + harness → R0.3 goes green → R1–R5 execute
per the runbook → R4 routes. PG Phase 2 stays blocked until then.
