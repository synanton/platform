# YDB PoC — Implementation Plan

**Status:** Draft for review
**Date:** 2026-09-26
**Governing docs:**
- `platform/docs/architecture/synanton-platform-architecture-1.0.md` (capstone, approved)
- `platform/docs/architecture/proposals/Design Proposal Evaluate YDB as a SynvaultSynquest Backend.md` (Rev 6, draft)
- YDB-POC Ticket List (`../ydb-poc/ticket-list.md`, 001–040 — governing tracker)
**Scope:** Ports (`SynvaultStore`, `SynquestEngine`, `SynquestIndexWriter/Admin`) + time-boxed YDB 26.3.x evaluation. No production migration.
**Non-goals:** MinIO / Content Cache 1.26, Relix graph, ClickHouse, PostgreSQL, temporal/graph retrieval evaluation, 1.27/1.32 redefinition.

---

## 1. Ticket review verdict (001–040)

**Verdict: accept as authoritative. Prior corrections closed, 037–040 correctly integrated.**

Verified against proposal Rev 6:

- **004 parallel-fix accepted.** 004 runs parallel with 011, closes after 011. Matches review recommendation.
- **037 blocks 011.** Shared port-types home (`SecurityContext`, `TenantScope`, `PrincipalRef`, `PolicyContext`, IDs, `EmbeddingModelRef`, error types) lands before API extraction — correct order, prevents `synvault-api ↔ synquest-api` cycle.
- **039 as Phase-0 blocker.** Provider selection + startup validation (depends 011, 020) gating 021/024 — correct; closes the wiring gap.
- **038 as must-hold.** §8.3 observability owned; 029/032 now depend on it — correct, freshness/lag becomes a usable hard gate.
- **021/024 deps extended** to 037 + 039 in addition to 001/002/010/011 — correct.
- **Traceability, phased sequencing (0A–0D), critical path, done-criteria** added in the updated list — adopt as-is. Full table in `ticket-list.md`.

Residual notes (non-blocking, for execution):

1. **Critical-path shape.** 037 has no dependencies, so 001 → 002 → 037 is not strictly serial — 037 can start in parallel with 001/002/005. Long pole remains `011 → 021/024 → 028/029 → 032 → 034 → 036`, with 010 and 008 as external decision risks.
2. **033 scope.** 033 depends on 023 + 030 only. If rebuild/replay-under-load needs live consistency context from 029, add an informational link 029 → 033 during execution. Not required now.
3. **039 vs 020 ordering.** 039 depends on 020 (conformance gating), while both derive from 011 — no cycle, but 020 must stay on the Phase-0C path so 039 does not slip.

---

## 2. Ticket plan (40 — full list in `ticket-list.md`)

### Phase 0 — Reconciliation and preconditions (gate: all green before Phase 1/2)

| Ticket | Title | Gate |
|---|---|---|
| YDB-POC-001 | Pin YDB server + Java SDK versions | version manifest, patch-level, SDK support status |
| YDB-POC-002 | Validate §11.1 schema vs pinned release | working DDL, vector/FTS/JSON deltas, dimension constraint |
| YDB-POC-003 | Feature stability inventory | GA/Preview/Beta table, risk flags, re-validation procedure |
| YDB-POC-004 | Freeze feature-parity matrix | zero `Must` TBDs (parallel with 011, closes after 011) |
| YDB-POC-005 | Freeze corpus + golden queries | versioned N/M/K artifact, Recall@10 labels, eligibility fixtures |
| YDB-POC-006 | Cassandra baseline + absolute thresholds | measured p50/p95/p99, Recall, freshness; signed thresholds (after 005) |
| YDB-POC-007 | Scope Cassandra pub-log track | separate estimate, schedule, owner (Decision 6) |
| YDB-POC-008 | Resolve Cassandra atomicity exception | conformance evidence or approved exception (after 007) |
| YDB-POC-009 | Decide cross-tenant relay requirement | decision record; exception path if required |
| YDB-POC-010 | Confirm §14–15 gate | freeze confirmation or throwaway-scope doc (hard gate) |
| YDB-POC-011 | Extract APIs; provider-independent domain | depends 037; no CQL/YQL outside adapters; contract tests green |
| YDB-POC-037 | Shared port-types home | no cross-API dependency; ArchUnit forbids `synvault-api ↔ synquest-api` |
| YDB-POC-039 | Provider selection + startup validation | depends 011, 020; config-driven; fail-fast; active provider visible via 038 |

Phase-0 exit = **001–011 + 037 + 039 closed, 004 closed after 011**.

### Must-holds — implement in Phase 0, verify continuously (fail blocks Phase 6)

| Ticket | Title |
|---|---|
| YDB-POC-012 | ArchUnit capability-boundary rule + CI |
| YDB-POC-013 | `DocumentRevision` atomicity contract tests |
| YDB-POC-014 | `putDocument` metadata-only enforcement |
| YDB-POC-015 | Pre-ranking eligibility; `EligibilityConstraints` ≠ `TemporalExtension` |
| YDB-POC-016 | `ChunkProjection{orderingKey,generationId}` + generation-scoped delete |
| YDB-POC-017 | `EmbeddingModelRef` provenance retention (inv. 31) |
| YDB-POC-018 | Vector dimension-migration constraint doc (after 002) |
| YDB-POC-019 | Semantic-tolerance search contract tests |
| YDB-POC-020 | Capability-by-conformance gating (§9.3) |
| YDB-POC-038 | Adapter observability contract (§8.3): metrics, freshness/lag, tracing, health |

### Phase 1 — Synvault PoC

- **021** Implement `YdbSynvaultStore`. Depends: 001, 002, 010, 011, 037, 039.
- **022** Benchmark vs Cassandra on frozen corpus. Depends: 006, 021.
- **023** Tenant isolation + leakage tests (steady-state and under load). Depends: 021.

### Phase 2 — Synquest PoC (split 024A / 024B — see `024-scope-split.md`)

- **024A** `CassandraSynquestEngine` (new code over the ingestion-cache-backed path). Depends: 001, 002, 010, 011, 037, 039.
- **024B** `YdbSynquestEngine` (original 024 scope). Depends: 001, 002, 010, 011, 037, 039.
- **025** Pre-ranking eligibility proof (B8). Depends: 024A, 024B (both adapters).
- **026** Side-channel eligibility (B11-adjacent). Depends: 024A, 024B.
- **027** Temporal rejection behavior (B11). Depends: 024A, 024B.
- **028** Benchmarks: ingestion-cache baseline (006) vs 024A vs 024B. Depends: 006, 024A, 024B.

### Phase 3 — Projection consistency

- **029** Updates/deletes/retries/replay/rebuild; commit→visible latency. Depends: 024A, 024B, 038.
- **030** Regression prevention (ordering + generation). Depends: 016, 024A, 024B.
- **031** Tenant-scoped `pending()` consumption. Depends: 007, 021.

### Cross-cutting

- **040** Rewire manifest/index call sites off `ingestion-cache`; retire the CQL exception (`011-cql-exception.md`). Owner TBD — assign before Phase 1. Target: 021/024 close. Until then, `ingestion-cache` is bugfix-only and no new production code imports it.

### Phase 4 — Scale, failure, cost

- **032** Scale + failure matrix (§13 Phase 4, §14). Depends: 021, 024A, 024B, 029, 038.
- **033** Leakage + rebuild/replay under load. Depends: 023, 030.
- **034** Cost model (§16.1). Depends: 022, 028, 032.

### Phase 5–6 — Migration tooling and decision (conditional)

- **035** PoC-scope migration + rollback (frozen dataset only). Depends: 021, 024A, 024B, 029, 032. Only on green Phases 1–4.
- **036** Decision package vs Decision-4 checklist; Outcomes 2–5 retained. Depends: 022, 028, 029, 032, 034, 035.

Counts: Phase 0 = 13 (001–011 + 037 + 039) · Must-holds = 10 (012–020 + 038) · P1 = 3 · P2 = 6 (024A/024B + 025–028) · P3 = 3 · P4 = 3 · P5 = 1 · P6 = 1 · Cross-cutting = 1 (040) · **Total 40**.

## 010 status and next-step order (confirmed 2026-09-26)

**010 is unresolved** (1.27/1.32 approved-architecture, unfrozen — see `010-gate-status.md`).
Concretely:

- **020 + 039 proceed now, in parallel with 010 escalation.** Neither depends on 010.
- **021 / 024A / 024B stay gated on 010** (frozen contracts) or explicit throwaway
  re-scoping per §0.1. The provisional markings from 011
  (`011-provisional-followup.md`) are load-bearing until the gate flips — they are
  tracked follow-ups, not footnotes.
- **008 is closed** with option (b) recorded (`008-cassandra-revision-decision.md`):
  Cassandra non-conforming for revision/delete; Outcome 4 qualified; 039 must
  reject Cassandra where revision semantics are required.

---

## 3. Sequencing and critical path

Phased sequencing (from ticket list):

```text
Phase 0A (definition, no code)        001 002 003 005 007 009 010
Phase 0B (interface extraction)       004 ─┐
                                          ├── 011 ──┐
                                       037 ────────┤
                                                   ├── 039
                                        020 ───────┘
Phase 0C (contracts & invariants)     012 013 014 015 016 017 019 020 038
Phase 0D (baseline & estimates)       006 (after 005)   008 (after 007)
Phase 1 (Synvault)                    021 022 023
Phase 2 (Synquest)                    024 025 026 027 028
Phase 3 (Projection)                  029 030 031
Phase 4 (Scale/cost)                  032 033 034
Phase 5 (Migration)                   035
Phase 6 (Decision)                    036
```

Critical path:

```text
001 (pin versions)
  → 002 (validate schema)
    → 037 (shared types)
      → 011 (API extraction)
        → 004 (freeze matrix)
        → 013–020 (must-holds)
        → 038 (observability)
        → 039 (selection + startup validation)
          → 021 (YdbSynvaultStore)
            → 022 (benchmark)
              → 034 (cost) ─┐
                             ├─→ 036 (decision)
          → 024 (YdbSynquestEngine)
            → 028 (benchmark) ─┘
            → 029 (consistency)
              → 032 (scale) ──┘
```

Long pole: `011 → 021/024A/024B → 028/029 → 032 → 034 → 036`. Hard external gates: **010** (§14–15 freeze) blocks Phase 1/2 (020/039 proceed in parallel with escalation); **006** (measured baseline) required before any benchmark conclusion.

---

## 4. Traceability (proposal → tickets)

| Proposal | Tickets |
|---|---|
| §0, §0.1 (dependency, sequencing) | 010 |
| §2.6 (terminology) | 011, 037 |
| §8.2 (module structure) | 011, 037 |
| §8.3 (observability) | 038 |
| §8.4 (security, multi-tenancy) | 015, 023, 025, 026, 033 |
| §9.1 (atomicity, putDocument, pagination) | 013, 014, 021, 037 |
| §9.2, §9.3 (capabilities, conformance) | 012, 020, 039 |
| §10.1 (`EmbeddingModelRef`) | 017, 024, 037 |
| §10.2 (eligibility/temporal) | 015, 025, 027, 037 |
| §10.3 (projection mutation) | 016, 030 |
| §11.1 (schema, dimension) | 002, 018 |
| §11.3, §11.4 (indexes, capabilities) | 024, 025, 026 |
| §12.1 (write path) | 021, 029 |
| §12.2 (publication log, Cassandra) | 007, 008, 009, 031, 037 |
| §12.3, §12.4 (generations, tolerance) | 016, 029, 030 |
| §13 Phase 0 | 001–011, 037, 038, 039 |
| §13 Phases 1–4, §14 matrix | 021–033 |
| §16.1 (cost model) | 034 |
| Decision 3 (conditional PoC) | 001, 003, 005, 006, 010 |
| Decision 4 (production gate) | B items (022, 028, 029, 031–035) |
| Decision 6 (Cassandra track) | 007, 008 |

---

## 5. Risks and done criteria

- **010 / 008** need Architecture decisions — escalate first.
- Preview/Beta features (003) stay a production risk even on PoC pass.
- Fixed vector dimension (018) is Phase-6 decision input, not just documentation.
- Outcomes 2–5 stay reachable; ports + must-holds survive any Phase-6 outcome.
- Blocker closes only on committed artifact + owner sign-off. Must-hold closes on green contract tests across all adapters + CI enforcement, re-verified per phase. Decision-4 items close only in the Phase-6 package against measured thresholds.
