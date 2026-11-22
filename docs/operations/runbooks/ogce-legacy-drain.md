---
title: "OGCE Legacy Pass-2 Drain"
status: "current"
last_reviewed: "2026-11-22"
---

# OGCE Legacy Pass-2 Drain

**Decision:** [ADR-044: Ontology-Grounded Chunk Extraction](../../architecture/decisions/adr-044-ontology-grounded-chunk-extraction.md)
**Design source:** [v1.36 OGCE proposal §9](../../architecture/proposals/v1.36/ontology-grounded-chunk-extraction.md#9-phased-rollout-and-legacy-migration)
**Gate register:** [OGCE acceptance gates](../../quality/ogce-gates.md)
**Owner:** andreminin

Status mirrors ADR-044; see ADR-044 for transition criteria. This runbook defines the drain observables only. It does not claim the drain has occurred, authorize decommissioning, or change any gate status.

## Scope

The legacy EnrichStage Pass-2 path is compatibility-only for already-running legacy jobs. It is not a fallback for newly scheduled RAKG work, and its output is never promoted into the RAKG candidate generation. Decommissioning requires all three observables below to hold for the agreed observation window.

## Observable triple

The drain is complete only when all three observables are true simultaneously and continuously for **14 days**.

| # | Observable | Required state | Emitter | Observer |
|---|---|---|---|---|
| 1 | Queued legacy jobs | Zero queued legacy EnrichStage Pass-2 jobs | Extraction-consumer queue metric and job-state transition event | On-call dashboard plus the daily drain review |
| 2 | Legacy cache namespaces | Every legacy cache namespace expired or marked non-readable by version | Cache namespace/version invalidation job | Cache-namespace audit at the start and end of the window |
| 3 | Legacy scheduling | No new legacy job scheduled for 14 consecutive days | Scheduler/admission metric tagged with the legacy path | 14-day window query reviewed before decommission sign-off |

Rules:

- Any non-zero queue, readable legacy namespace, or newly scheduled legacy job resets the 14-day window.
- The window is wall-clock and tenant-scoped. A tenant with no traffic counts as "no legacy scheduling" only when the scheduler reports the legacy path as disabled, not merely idle.
- Metrics must distinguish "legacy path disabled" from "legacy path idle"; an idle path is not evidence of drain.

## Rollback rule

- If the drain is incomplete or any observable regresses, **do not remove legacy code, cache readers, or the compatibility flag**.
- Rollback targets the last-known-good **RAKG** adapter/configuration. Do not route new RAKG work through legacy Pass-2.
- If no safe RAKG fallback exists, pause affected extraction and re-enqueue after remediation. Do not reactivate the defective Pass-2 aggregation path.
- Decommissioning is a separate implementation PR after 14 clean days and owner sign-off on the migration metrics.

## Related

- [RAKG Phase 2 asynchronous extraction consumer](../../planning/extraction-consumer-phase2.md)
- [ADR-022: Pluggable Knowledge Extraction Pipeline](../../architecture/decisions/adr-022-extraction-engine-architecture.md)
