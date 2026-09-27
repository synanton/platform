# PG-POC-006 — Gate B Outcome: Comparator Validity

**Status:** PG-owned inputs complete; 024A-vs-baseline leg pending YDB 028 — PG Phase 2 BLOCKED (clean pause, not a failure)
**Date:** 2026-09-27

## Gate B inputs

| Input | Owner | Status |
|---|---|---|
| PG tie-break determinism (013) | PG | ✅ Closed — `013-tie-break.md`; post-retrieval sort is a PG-POC-007 obligation |
| `028-convergence-config.yaml` 3→4 legs | PG (this ticket) | ✅ Done — tolerances unchanged, PG rules appended (metric name, mechanism suffix, tie-break) |
| 024A-vs-baseline convergence | YDB 028 | ⏳ Not executed — no automated runner exists yet; `BaselineBench` is an on-demand harness (`-Dydb.bench=true`), not a running service |

## Baseline availability check (2026-09-27)

The baseline is the in-JVM `BaselineBench` harness (seeded Lucene index,
production `HybridSearcher` + `RrfFusion`), not a daemon — there is no service
to be "up." What Gate B needs is a 024A-vs-baseline convergence *run* on the
frozen corpus with pre-declared tolerances, which is YDB-track 028 execution
work, not PG work. No baseline process is currently running; nothing is broken.

## Verdict

Per proposal §10/§11: the 024A-vs-baseline leg must pass before PG Phase 2
begins. It has not run, so **PG Phase 2 is blocked, not annotated**. Phase
0A–0D are closed, Gate A evidence is complete, 013 is closed. The branch is
handoff-ready: when YDB 028 executes and the 024A leg passes, PG-POC-004
(Phase 1) and PG-POC-007 (Phase 2) open with no further pre-work.

If the 024A leg fails, the framing is revised before any PG benchmark — Phase 6
must never distinguish adapter overhead from mirror drift after the fact.
