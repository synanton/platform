# YDB-POC ticket list (governing tracker, 001–040)

Supersedes all chat-posted lists. Status as of Phase-0 engineering complete.
IDs 001–039 per the updated list; 024 split 024A/024B (no new number); 040 added.

Gate format: every gate line names what was tested (scope, harness, date) —
never just "green". See the evidence-scope rule in `dev-guide-tests.md`.

## Phase 0A — definition (no code except 037)

| ID | Title | Status |
|---|---|---|
| 001 | Pin YDB server (26.3.1.16, pre-release) + SDK (v2.4.11) | Closed → `001-version-manifest.md` |
| 002 | Validate §11.1 schema vs pinned release | Closed with follow-ups → `002-schema-validation.md` |
| 003 | Feature stability inventory | Closed → `003-stability-inventory.md` |
| 005 | Freeze corpus + golden queries (v1) | Closed → `005-corpus-definition.md` |
| 007 | Scope Cassandra pub-log track (~16d) | Closed → `007-cassandra-publog-scope.md` |
| 009 | Cross-tenant relay decision (none; exception path defined) | Closed → `009-relay-decision.md` |
| 010a | Gate determination (unfrozen → throwaway-scoped) | Closed → `010-gate-status.md` |
| 010b | Escalation — **resolved throwaway-scoped (2026-09-26)** | Closed → `010-escalation.md` |
| 037 | Shared port-types home (`java/storage-contract`) | Closed |

## Phase 0B/C — extraction + must-holds

| ID | Title | Status |
|---|---|---|
| 011 | Extract synvault-api/synquest-api; provider-independent domain | Closed (provisional file load-bearing) |
| 004 | Freeze parity matrix | Closed → `004-parity-matrix.md` (parallel with 011) |
| 012–019 | ArchUnit rule, atomicity/provenance/putDocument/eligibility/ordering/model-ref/dimension/tolerance contracts | Closed |
| 020 | Conformance gating (machine-readable matrix) | Closed |
| 038 | Observability contract | Closed → `038-observability-contract.md` |
| 039 | Provider selection + startup validation | Closed |

## Phase 0D — baseline

| ID | Title | Status |
|---|---|---|
| 006 | Baseline thresholds (measured absolutes; sign-off at exit review) | Closed → `006-baseline-thresholds.md` |
| 008 | Cassandra revision-path decision (option b: non-conforming) | Closed → `008-cassandra-revision-decision.md` |

021, 022, 023 · 024A, 024B, 025, 026, 027, 028 · 029, 030, 031 · 032, 033, 034 ·
035 · 036. Cross-cutting: 040 (owner assigned, target 021/024 close).

Status: 021 closed; 022 write-path bench measured (search legs await 024A/024B);
024A implemented + contract/gating green (benchmark legs pending); 024B lexical +
vector + hybrid implemented, contract/gating green (Gate 0 passed).
032 write-path subtests green: 20-way burst 77.6 rps all-complete; container
restart preserves committed revisions + pending publications (genuine recovery:
restart landed mid-test, reads after).
029 opened: tenant-scoped relay seam (`pendingPublications`/`markPublished`) +
harness relay test — first freshness number `commit_to_visible_ms=487,
pending=0` (single update, steady-state, single-tenant single-writer — NOT
p50/p95, NOT the 1.27 path; real path adds client+bus+delivery lag, re-validate
on 1.27 landing; freshness gate explicitly unclaimed until then — see 006).
030 covered by contract ordering/generation suites on both adapters + orderingKey
== per-doc `storage_revision` monotonic counter (not timestamp) asserted in the
relay test; relay restart resumes from the pending-set scan (no cursor), and
reprocessing is idempotent via the ordering guard. Cursorless is a resilience
property, not a simplification: no cursor state exists to lose on relay
restart; resume position derives from committed state.
031 covered structurally (no unscoped `pending` overload exists — cross-tenant
scan is inexpressible, the strongest form; future tenant-scoped interfaces
follow this pattern) and behaviorally (tenant-scoped pending test).

034 input (filed for Phase 4, not Phase 2/3): Cassandra metadata-write benchmark
on the same shapes as the 022 YDB numbers — required for the §16.1 cost model.
Without it YDB metadata writes have no comparator. Not urgent; must exist
before 034 closes.

## Phase 5 — closed (no baseline needed)

035 migration tooling green (round-trip, checksums, rollback) + SchemaInstaller
root fix + inventory guard. Branch state: **handoff-ready, awaiting baseline
for 028**. Next commit on this branch should be the baseline's arrival, not
more polish.

## Baseline contact

**No contact established; escalation needed.** The baseline service has no
confirmed owner, timeline, or ping target known to this branch. If you are
reading this in three weeks wondering who to ping: that question is still open —
treat it, not the engineering, as the critical path. Owner to resolve:
PoC workstream.
