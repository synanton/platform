---
title: "OGCE Roadmap and Release Notes (v1.36)"
status: "current"
last_reviewed: "2026-11-22"
---

# OGCE Roadmap and Release Notes (v1.36)

**Decision:** [ADR-044: Ontology-Grounded Chunk Extraction](../../decisions/adr-044-ontology-grounded-chunk-extraction.md)
**Design source:** [v1.36 OGCE proposal](./ontology-grounded-chunk-extraction.md)
**Gate register:** [OGCE acceptance gates](../../../quality/ogce-gates.md)
**Owner:** aminin
**Current status:** **Accepted (design) — implementation pending** (2026-11-22)

Status mirrors ADR-044; see ADR-044 for transition criteria. This roadmap adds no acceptance claim, threshold, or gate status. Every numerical gate remains provisional and is owned by the gate register.

## Release notes

- **2026-11-22 — design accepted.** ADR-044 records the OGCE decision; the v1.36 proposal remains the detailed design source. No runtime implementation, release, or gate attainment is claimed.
- **P0–P3 are an implementation order, not dated commitments.** [#812](https://github.com/synanton/platform/issues/812) tracks P0-2 profile/harness work. [#814](https://github.com/synanton/platform/issues/814) is the separately scoped qualification request; its outcome does not trigger an ADR-044 status transition.

## P0–P3 exit criteria

These criteria describe what must be true to consider a phase complete. They do not replace the gate register and do not mark any gate **Met** or **Missed**.

| Phase | Goal | Exit criteria | Evidence |
|---|---|---|---|
| **P0 — JSONL contract safety** | Stop losing facts to JSON fragility | JSONL is mandatory for multi-fact extraction; each line is validated independently; complete lines survive truncation; malformed lines are quarantined with distinct reason codes; model-generated offsets/IDs are rejected; the legacy Pass-2 path is fixed or retired. | Contract tests and a reviewed implementation PR. P0-relevant gates stay **Provisional** until a pinned-profile measurement is recorded in the gate register. |
| **P1 — Ontology contract** | Compile the pinned ontology into a versioned extraction schema | Syntology publishes a digest-pinned schema artifact (classes, aliases, properties, domain/range); prompts and line schemas are generated from it; replay pins ontology release, schema digest, and prompt/model/tokenizer/validator revisions. | Reproducible schema artifact plus digest; the adapter consumes the artifact without an unversioned-vocabulary fallback. |
| **P2 — Bounded segmentation and scheduling** | Bound extraction fan-out without changing retrieval chunks | 512-token retrieval chunks remain unchanged; 256–384-token extraction segments stay within chunk boundaries; per-document/tenant budgets, GPU-capacity-aware scheduling, deterministic consolidation, and explicit partial coverage exist; 512-token and 256/384-token modes are compared under equal-compute/token budgets. | Reviewed benchmark record linked from the gate register; no gate status changes without a reviewed result. |
| **P3 — Ontology subsetting and full validation** | Select and validate ontology subsets without dropping constraints | Lexical and embedding subset selection, dependency closure, one widened/full-schema retry, batch semantic validation, and promotion-time SHACL are implemented; `OUT_OF_SUBSET` and fallback outcomes are recorded. | Reviewed subset-gate results; fail-closed behavior when the full schema cannot fit. |

## Dependencies and boundaries

- **Status transition:** only ADR-044's evidence criterion triggers a transition. Merging P0/P1 code, shipping behind a flag, successful #814 qualification, or candidate-hardware measurements do not.
- **Legacy drain:** the observable completion definition is tracked separately and is not satisfied by phase completion alone.
- **Quarantine:** data-handling controls are a separate specification; this roadmap does not set retention or access policy.
- **No new deployable:** implementation stays within the existing Syntology, Synflux consumer, extraction-engine, candidate-store, and GraphWriter boundaries (ADR-044 decision 6).
