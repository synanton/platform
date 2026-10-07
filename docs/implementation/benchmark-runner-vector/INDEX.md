# Benchmark Runner + Vector Engine Selection — Implementation Plan

**Status:** Draft for review · **Owners:** TBD (Track A, Track B, Docs — Week 1 gate)
**Scope:** 48 canonical tasks, 12 weeks · **Location:** `docs/implementation/benchmark-runner-vector/`
**Convention:** `<prefix>-<phase>.<n>` where BR = Benchmark Runner, VEC = Vector Engine, DOC = Documentation
**Format per task:** Description · Acceptance · Evidence · Estimate · Depends on

Related: [Benchmark Runner proposal](../../architecture/proposals/Benchmark-runner/proposal-benchmark-runner.md) ·
[Retrieval evaluation benchmark plan](../../research/retrieval-evaluation-benchmark-plan.md) ·
[Benchmark tracker](../benchmark-tracker.md)

---

## §0. Task Summary (canonical: 48)

| Track | Tasks | Phases | File |
|---|---|---|---|
| BR — Benchmark Runner | 12 | A0–A3 | [track-a-benchmark-runner.md](./track-a-benchmark-runner.md) |
| VEC — Vector Engine (SYN-VECTOR-001) | 24 | B0–B6 | [track-b-vector-engine.md](./track-b-vector-engine.md) |
| DOC — Documentation (cross-track) | 12 | D0–D4 | [track-doc-documentation.md](./track-doc-documentation.md) |
| **Total** | **48** | — | — |

2 execution tracks (BR, VEC) + 1 cross-cutting track (DOC).
Documentation is first-class: it ships alongside code, with its own owner and cadence.

### Reconciliation note (why 48, not 54 / ~65)

The input draft cited "~48 tasks" in coverage, "54" in the summary table (18+24+12),
and listed 65 detailed items on count (BR 17 + VEC 35 + DOC 13).
Canonical is **48 = 12 + 24 + 12**. The 17-task delta is folded by merge, not by deletion:

| Track | Listed | Canonical | How folded |
|---|---|---|---|
| BR | 17 | 12 | ResultSink abstraction + FileSink merged (old A1.2+A1.3); metric collection + topology propagation merged (old A2.2+A2.3); Kafka + ClickHouse merged into one optional pluggable-sink task (old A3.2+A3.3); RunCompletedEvent folded into sink contract; runner runbook counted once under DOC-D1.1 (cross-ref from BR-A3) |
| VEC | 35 | 24 | B0: deployment + managed-availability merged; hardware + scalability + ops-complexity merged; profiles + cross-context recommendations merged (8→5). B1: port + DTOs merged (5→4). B2: validation + default-preservation merged (4→3). B3: Lucene-standalone + PgVector reference merged; conditional 3rd-party parked, not counted (5→4). B4: sequential + per-query override merged (3→2). B5: three isolation analyses merged into one (6→4). B6: matrix + profiles merged; publish + follow-ons merged (4→2) |
| DOC | 13 | 12 | D4 hygiene + review cadence merged into one ongoing task (2→1) |

Full old-ID → canonical-ID mapping lives in each track file. No scope was dropped;
parked items (conditional 3rd-party adapter, UI API extras) are marked Optional/Parked.

### Reconciliation update 2026-10-01 (Rust as service; JNI out of scope)

| Item | Change | Reason |
|---|---|---|
| Tantivy/Quickwit rows | Added to B0 matrices | Adjacent-engine assessment; unmeasured, Phase-5 candidate |
| VEC-B6.2-FOLLOWON | Expanded trigger | Names two service paths; excludes JNI |
| Dev-guide rule | New rule | Cross-runtime boundaries are services |

## §1. Ownership

| Role | Scope | Named? |
|---|---|---|
| Track A owner | Benchmark Runner: schema, sinks, API, runbook | ✅ Andrei Minin |
| Track B owner | SYN-VECTOR-001: matrix, ports, adapters, synthesis | ✅ Andrei Minin |
| Docs owner | Cross-track documentation, kept current | ✅ Andrei Minin |
| Legal reviewer | License column sign-off | ⬜ TBD |
| Architecture reviewer | Port decomposition + framework publication | ⬜ TBD |

Week 1 gate: closed 2026-10-01 — all three track owners named. Week-2 schema-freeze schedulable.

## §2. Sequencing and Gates

```text
Week:        1    2    3    4    5    6    7    8    9   10   11   12
Track A      A0─────────A1─────────A2─────────A3──────────────────
Track B      B0─────────B1─────────B2────B3─────────B4────B5────────B6
Track DOC    D0────D1───────────────D2───────────────D3────────────────
             ▲     ▲    ▲          ▲          ▲               ▲
             │     │    │          │          │               └─ D3 (ships with B6)
             │     │    │          │          └─ D2 (ships with B2/B3)
             │     │    │          └─ D1 (ships with A2)
             │     │    └─ A1, B1 gates
             │     └─ D0 (ships with A0 schema freeze)
             └─ Owners named, plan starts
```

Coupling:

- Week 2 — Schema freeze (BR-A0.2, BR-A0.3 + VEC-B0 requirements).
- Week 6 — Runner executes small 6-composition set (A2 gate) = B5 pre-check.
- Week 8 — All adapters ready; runner ready for B5.
- Week 9–10 — B5 completes (runner as executor).
- Week 12 — Decision framework published.

Phase gates:

- Gate A0: Manifest + Result schemas + metric taxonomy frozen, cross-track sign-off.
- Gate A1: Trivial manifest executes end-to-end; all controls fire correctly.
- Gate A2: 6-composition spec runs on a small corpus; 6 valid artifacts (B5 pre-check).
- Gate A3: Runner usable by anyone with a manifest; B5 is a validated client.
- Gate B0: Non-functional decision matrix drafted; per-context defaults named.
- Gate B1: Pure refactor, zero behavior change, all contract tests green.
- Gate B2: Composition selectable in config; single-provider default preserved.
- Gate B3: Milvus + Qdrant pass contract tests; R3 attribution ambiguity resolved.
- Gate B4: Both execution modes produce identical result shapes; crossover documented.
- Gate B5: All 6 compositions have artifacts; hard gates pass; effects isolated.
- Gate B6: Decision framework published; follow-ons filed.

## §3. Acceptance Criteria (aggregate)

Track A — Benchmark Runner:

- [ ] Manifest + Result schemas frozen and documented.
- [ ] Runner executes any manifest describing N compositions.
- [ ] Reproducibility verification enforced.
- [ ] ResultSink abstraction with FileResultSink (+ optional Kafka/ClickHouse).
- [ ] Runbook exists; new user can run end-to-end.
- [ ] First reference workload (B5) validated.

Track B — SYN-VECTOR-001:

- [ ] Decision matrix complete (non-functional + measured functional).
- [ ] Port decomposition is a pure refactor.
- [ ] Composition configurable; single-provider default preserved.
- [ ] Milvus and Qdrant pass contract tests.
- [ ] 6 compositions executed; effects isolated.
- [ ] Decision framework published; per-context recommendations named with evidence.

Track DOC — Documentation:

- [ ] Every schema has a human-readable reference.
- [ ] Runner has a runbook.
- [ ] Composition guide covers all 6 configurations.
- [ ] Decision framework published with sources.
- [ ] Dev-guide updated with new disciplines.
- [ ] Pattern library updated with new findings.
- [ ] Cross-references maintained at each gate.

## §4. Risks

| Risk | Mitigation |
|---|---|
| Owners not named in Week 1 | Explicit gate; escalate to workstream lead |
| Schema freeze slips | Fixed Week-2 date; both tracks synchronize the same day |
| Adapters ready before runner (or vice versa) | Fallback: B5 via RunLeg; A2 executes subset |
| Docs drift behind code | Docs owner reviews at each gate; DOC items on gate checklists |
| Legal review delayed | License column marked pending until reviewed; framework publishes with flag |
| No clear winner in evaluation | Valid outcome; framework says "context-dependent" with named defaults |
| Scope creep (adjacent engines) | Summary cards only, unless a context gap justifies promotion |
| Metric-definition divergence | Taxonomy frozen at A0; shared vocabulary enforced |
| Cross-runtime adapter proposed via JNI | Dev-guide rule + architecture review requirement |

## §5. Immediate Next Steps (Week 1, in order)

1. Name the three owners — Track A, Track B, Docs. Only blocker to starting.
2. Track A owner schedules the A0 requirements session (BR-A0.1) with Track B.
3. Track B owner starts VEC-B0.1 (license matrix) immediately — no dependency on Track A.
4. Docs owner begins DOC-D0.1–D0.3 in parallel with the schema freeze.

Week 2 gate: Manifest + Result schemas frozen; metric taxonomy published; both tracks sign off.
