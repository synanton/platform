# PG-POC-006 — Gate B Outcome: Comparator Validity

**Status:** CLOSED 2026-09-29 — R3-CONDITIONAL-PASS satisfies the 024A leg;
R4 routes PG Phase 2 open. (Supersedes the 2026-09-27 "pending YDB 028" note
below, preserved as history.)
**Date:** 2026-09-27 (inputs) / 2026-09-29 (closure)

## Gate B inputs

| Input | Owner | Status |
|---|---|---|
| PG tie-break determinism (013) | PG | ✅ Closed — `013-tie-break.md`; post-retrieval sort is a PG-POC-007 obligation |
| `028-convergence-config.yaml` 3→4 legs | PG (this ticket) | ✅ Done — tolerances unchanged, PG rules appended (metric name, mechanism suffix, tie-break) |
| 024A-vs-baseline convergence | YDB 028 → R3 | ✅ Closed by R3 (2026-09-29, corpus `ydb-poc-corpus-v1`, frozen tolerances): identity 0 fails on cassandra(=024A) and ydb(=024B) legs; lexical overlap 1.0 both legs; vector/hybrid overlap divergences pre-declared semantic (vector spaces, fusion semantics), not defect. Machine verdict `docs/implementation/r3-verdict.json` (R3-CONDITIONAL-PASS); full reports `runs/cassandra-v1-convergence.md`, `runs/ydb-v1-convergence.md`; routing `docs/implementation/r4-decision.md` ("R3 passes → PG Phase 2 opens. Selected."). |

## Baseline availability check (2026-09-27)

The baseline is the in-JVM `BaselineBench` harness (seeded Lucene index,
production `HybridSearcher` + `RrfFusion`), not a daemon — there is no service
to be "up." What Gate B needs is a 024A-vs-baseline convergence *run* on the
frozen corpus with pre-declared tolerances, which is YDB-track 028 execution
work, not PG work. No baseline process is currently running; nothing is broken.

## Verdict (closed 2026-09-29)

R3 ran the 024A-vs-baseline leg on corpus `ydb-poc-corpus-v1` with frozen
tolerances and returned CONDITIONAL-PASS (hard gates green; vector/hybrid
overlap divergences pre-declared semantic); R4 selected "R3 passes → PG
Phase 2 opens." **PG Phase 2 (PG-POC-004/007) is therefore open.** The
2026-09-27 "blocked, not annotated" verdict below is superseded history.

Framing guard (unchanged): Phase 6 must never distinguish adapter overhead
from mirror drift after the fact — the pre-declared semantic annotations in
`r3-verdict.json` are the record of what was declared before running.

## Verdict (2026-09-27, superseded — preserved as history)

Per proposal §10/§11: the 024A-vs-baseline leg must pass before PG Phase 2
begins. It has not run, so **PG Phase 2 is blocked, not annotated**. Phase
0A–0D are closed, Gate A evidence is complete, 013 is closed. The branch is
handoff-ready: when YDB 028 executes and the 024A leg passes, PG-POC-004
(Phase 1) and PG-POC-007 (Phase 2) open with no further pre-work.

If the 024A leg fails, the framing is revised before any PG benchmark — Phase 6
must never distinguish adapter overhead from mirror drift after the fact.
