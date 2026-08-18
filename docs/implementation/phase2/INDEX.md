---
title: "Phase 2 - LLM Online"
status: "current"
last_reviewed: "2026-07-21"
---

# Phase 2 - LLM Online

**Purpose:** Implementation plans for Phase 2 - adds LLM enrichment, embeddings, natural-language synthesis, authentication, and the first real implementations of `security`, `topology`, and `syntology`.
**Audience:** Engineers, leads
**Last Updated:** 2026-07-21

## Theme

> Add LLM enrichment (two-pass), dense embeddings, and a natural-language answer synthesis step. Add JWT-based authentication. Stand up real `security` and `topology` services. Integrate `syntology` entity type resolution with the ingestion pipeline.

## User-Facing Capability Unlocked

`POST /search` now returns a natural-language answer alongside ranked hits. Users log in with real credentials. Ingested documents have typed entities resolved against the ontology. Operators can browse the corpus and verify enrichment output in the UI.

## Module Plans

| Module | Plan | Status |
|--------|------|--------|
| `synflux` + `ingestion-cache` + `synanton-llm-client` | [`01-ingestion-pipeline.md`](./01-ingestion-pipeline.md) | LLM enrichment + embeddings |
| `planner` | [`02-planner.md`](./02-planner.md) | LLM-driven intent classification (flag-gated) |
| `gateway` | [`03-gateway.md`](./03-gateway.md) | LLM synthesis step - adds `answer` to `QueryResponse` |
| `synapt` | [`04-synapt.md`](./04-synapt.md) | JWT/API-key auth, X-Trace-Id propagation, error redaction |
| `security` | [`05-security.md`](./05-security.md) | First real: JWT issuance (RS256), htpasswd, local IdP |
| `topology` | [`06-topology.md`](./06-topology.md) | First real: PostgreSQL schema, Flyway, demo tenant seeded |
| `syntology` | [`07-syntology.md`](./07-syntology.md) | Pass-2 entity type resolution, `POST /entities/resolve` |
| `syntology-admin` (UI) | [`08-syntology-admin.md`](./08-syntology-admin.md) | Login screen, corpus browser tab |

## Phase 2 DoD (Composite)

Per master plan §6:

- `POST /search` returns `QueryResponse.answer` ≥ 20 words, non-empty, coherent with the hits.
- Users authenticate with a real JWT; unauthenticated requests return 401.
- Ingestion Phase 2 DoD met: `state=EMBEDDED`, real embeddings, real Pass-2 entities.
- `syntology-admin` corpus browser shows canonical entity types.

## External Dependencies Added

- vLLM × 2 (LLM on GPU-0: Llama 3.1 8B AWQ, embedding on GPU-1: BGE-base-en-v1.5)
- PostgreSQL - first real use (topology schema)

## How to Contribute

Phase 2 plan files follow the naming convention `NN-{module}.md`. When a plan is authored, update this INDEX and the master [`../synanton-phases-plan.md`](../synanton-phases-plan.md) Plan File Inventory (§13).
