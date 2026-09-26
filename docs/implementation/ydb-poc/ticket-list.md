# YDB-POC ticket list (governing tracker, 001–040)

Supersedes all chat-posted lists. Status as of Phase-0 engineering complete.
IDs 001–039 per the updated list; 024 split 024A/024B (no new number); 040 added.

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
| 010b | Escalation (decision by 2026-10-03) | Escalated → `010-escalation.md` |
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

## Phase 1–6 — gated on 010 (not started)

021, 022, 023 · 024A, 024B, 025, 026, 027, 028 · 029, 030, 031 · 032, 033, 034 ·
035 · 036. Cross-cutting: 040 (owner assigned, target 021/024 close).
