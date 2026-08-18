---
title: "Phase 5 - Scale + Vision + DR"
status: "planned"
last_reviewed: "2026-08-11"
---

# Phase 5 - Scale + Vision + DR

**Purpose:** Implementation plans for Phase 5 - the last phase of the roadmap. Ship the capabilities designed for operational scale but deliberately deferred earlier: multimodal ingest (vision captioning), long-term storage tiering (HOT → WARM → COLD → Glacier), a Rust hot loop for `synquest` as a drop-in replacement, GDPR erasure cascade end-to-end, cross-region disaster recovery with RTO/RPO SLOs, and `synreview` as the human-in-the-loop review kernel.
**Audience:** Engineers, leads
**Last Updated:** 2026-08-11

## Theme

> Turn the enterprise-hardened Phase 4 platform into a *scale-and-survive* platform: multimodal, tier-aware, regulator-friendly, and recoverable within stated RTO/RPO after a regional loss.

## User-Facing Capability Unlocked

- **Multimodal ingest.** Documents with embedded images are captioned by a vision model; captions become searchable and citable alongside text.
- **Long-term storage.** Content ages from HOT (Cassandra) → WARM (S3 Standard) → COLD (S3 Glacier) automatically per per-tenant `tiering_policy`; searches still work, cold retrievals surface via a documented degraded path.
- **Rust hot loop.** `synquest` served by a Rust drop-in replacement (same `POST /search` API); per-tenant feature flag switches between Java and Rust; benchmark shows ≥ 3× p99 latency reduction on the hot path.
- **GDPR compliance.** `DELETE /content/{id}` triggers a cascade that removes rows from manifest → chunks → embeddings → analysis → graph nodes within p99 ≤ 45 s.
- **Disaster recovery.** Regional failure produces a documented failover to the passive region within `RTO ≤ 4 h`; every backup has a `dr_backup_verified_at` gauge that has fired within its cadence.
- **Human review.** Low-confidence entities, contradictions, PII flags, deep-research gates, and ontology deprecation candidates flow into `synreview`; a 24 h staging queue lets humans override LLM-auto-resolutions before commit.

## Module Plans

| # | Module(s) | Plan | Scope |
|---|-----------|------|-------|
| 01 | `ingestion-cache` EXT | [`01-ingestion-cache.md`](./01-ingestion-cache.md) | Populate `image_caption_cache`, per-tenant staggered vacuum (v1.17), TTLs on all caches |
| 02 | `synvault` EXT | [`02-synvault.md`](./02-synvault.md) | `TierManager` HOT→WARM→COLD→Glacier movement + `rehydrateAsync` for cold retrieval |
| 03 | `synflux` EXT | [`03-synflux.md`](./03-synflux.md) | `VisionCaptioningStage` (Qwen2-VL / LLaVA), two-step chain-of-thought hardening, SHA256 incremental cache |
| 04 | `synquest` EXT (Rust) | [`04-synquest.md`](./04-synquest.md) | Rust `synquest-rs` binary as drop-in replacement; multilingual (bge-m3, CJK bigram); supernode sampling; panic guard |
| 05 | `relix` EXT | [`05-relix.md`](./05-relix.md) | `LouvainCommunityJob`, `community_id` on entities, bounded emulated traversal, cross-connector federated queries |
| 06 | `gateway` EXT | [`06-gateway.md`](./06-gateway.md) | GPU degraded-mode model swap (smaller synthesis model), streaming synthesis via SSE, agent-framework composability primitives |
| 07 | `security` EXT | [`07-security.md`](./07-security.md) | `MtlsIdentityProvider`, cross-region key management (per-region `SYNANTON_SUPPORT_KEY`), prompt/model version tracking wired to `synreview` |
| 08 | `control-plane` EXT | [`08-control-plane.md`](./08-control-plane.md) | GDPR erasure cascade orchestration, `RecrawlAfterRestorationWorkflow` full impl, `BackupVerificationWorkflow`, Deep Research workflow |
| 09 | `synreview` NEW | [`09-synreview.md`](./09-synreview.md) | First real implementation: 7 review-item types, two-tier auto-sweep, 24 h staging, replay CLI |
| 10 | *(cross-cutting DR)* | [`10-dr-runbooks.md`](./10-dr-runbooks.md) | R-DR1 regional failover, R-DR2 failback, `dr.*` config, DR alert wiring |

**Not extended in Phase 5** (`NO-CHANGE` per master plan §9): `shared/common`, `synanton-llm-client`, `synflux-router`, `planner`, `synapt`, `topology`, `syntology`, `synanton-mcp`, `syntology-admin`. Their Phase 4 surfaces continue to serve.

## Phase 5 DoD (Composite)

Derived from `synanton-phases-plan.md` §9 and pulled forward with concrete acceptance signals:

1. **Vision ingest works.** A PDF containing embedded images ingests successfully; captions appear as searchable chunks with `content_type=image`; `synflux_vision_captions_total{tenant,outcome}` increments on the demo run.
2. **Tier movement observable.** A document past `hot_retention_days` moves HOT → WARM automatically within one `synvault.tier.scan_interval_seconds` window; `synvault_tier_moved_bytes_total{tenant,target_tier="WARM"}` increments; `chunks_payload` column is truncated; content still retrievable via `GET /content/{tenant}/{ref}` (transparent).
3. **Cold retrieval within SLO.** `POST /query` touching a cold chunk returns within `gateway.cold_wait_ms` (8 s) or with `X-Synanton-Cold-Rehydration: degraded` header; `cold_retrieval_triggered_total` metric visible.
4. **Rust drop-in verified.** With `synquest.runtime=rust` per-tenant flag, `POST /search` returns identical results to Java runtime on the golden dataset (verified by parity harness), and p99 latency ≥ 3× reduced on the hot path.
5. **GDPR cascade end-to-end.** `DELETE /content/{id}` propagates through manifest → chunks → embeddings → analysis → graph nodes within p99 ≤ 45 s; `content_deletion_latency_seconds` histogram visible.
6. **DR failover drilled.** `R-DR1` runbook executed against dev cluster restores full write traffic in DR region within 4 h target; `dr_failover_runbook_executed_total` counter increments.
7. **Backup verification live.** `dr_backup_verified_at{storage_class}` gauge has fired within its cadence (weekly PostgreSQL, weekly Cassandra, quarterly S3); `DrBackupVerificationOverdue` alert has never fired.
8. **synreview live.** A low-confidence Pass 2 entity from an ingest test creates an `OPEN` row in `synreview.review_items`; two-tier auto-sweep resolves obvious cases; ambiguous ones enter 24 h staging with human override capability.
9. **Louvain communities populated.** After overnight `LouvainCommunityJob` run, every entity in the graph has a `community_id` property; `community_cohesion` metric visible; low-cohesion communities file advisory recommendations.
10. **MTLS profile working.** `OutboundAuthBroker` with `profile=MTLS` successfully calls an mTLS-only third-party service in a smoke test.

## External Dependencies Added

| Dependency | Purpose | First used by |
|---|---|---|
| **vLLM vision model** (Qwen2-VL-7B or LLaVA-1.6) | Image captioning | `synflux` (`03-synflux.md`) |
| **Secondary AWS region** (or equivalent) | Cross-region DR | `10-dr-runbooks.md` |
| **S3 Glacier storage class** | COLD tier | `synvault` (`02-synvault.md`) |
| **Kafka MirrorMaker 2** | Cross-region topic replication | DR (`10-dr-runbooks.md`) |
| **PostgreSQL logical replication** | Cross-region sync of `topology`, `synreview`, `audit`, `jobs`, `cost` schemas | DR |
| **Cassandra multi-DC replication** | Cross-region sync of `ingestion-cache` | DR |
| **Rust toolchain** (`rustc 1.79+`, `cargo`) in CI | `synquest-rs` builds | `synquest` (`04-synquest.md`) |
| **Tantivy** (Rust) + **hnsw-rs** or **usearch** | Rust BM25 + HNSW | `synquest` |
| **JGraphT Louvain implementation** OR **NetworKit sidecar** | Community detection | `relix` (`05-relix.md`) |

Docker Compose additions:

```
deployment/docker/compose.yaml:
  vllm-vision:          image: vllm/vllm-openai:latest  # profile: phase5-vision
  synquest-rs:          build: rust/synquest-rs         # profile: phase5-rust
  kafka-dr:             image: bitnami/kafka:latest     # profile: dr
  postgres-dr:          image: postgres:16              # profile: dr
```

BOM additions:

```
rust/synquest-rs/Cargo.toml:
  tantivy       = "0.22"
  hnsw_rs       = "0.3"
  proptest      = "1.5"
  serde         = "1"
  tokio         = { version = "1", features = ["full"] }
```

## Cross-Plan Dependencies

1. **First wave (foundational):** `01-ingestion-cache`, `02-synvault`, `10-dr-runbooks` (infra prep), `09-synreview` (new module scaffolding).
2. **Second wave (depends on wave 1):** `03-synflux` (needs vision model + `image_caption_cache` populated), `07-security` (needs MTLS deps), `08-control-plane` (needs `synreview.review_items` schema from wave 1).
3. **Third wave:** `04-synquest` (needs no-change interface; ships parallel Rust binary), `05-relix` (needs GDPR CAS from Phase 4; wires community_id).
4. **Fourth wave (top of stack):** `06-gateway` (needs GPU degraded circuit from control-plane; needs streaming from `synanton-llm-client`).

## How to Contribute

Phase 5 plan files follow the naming convention `NN-{module}.md`. When authoring:

1. Update this INDEX and `../synanton-phases-plan.md` §13 Plan File Inventory.
2. Cite `synanton-design-1.19.md` as the authoritative architecture source.
3. New metrics/alerts must be added to `../phase4/15-observability.md` §3 Alert Catalogue and referenced from the plan's `Metrics` section.
4. New config keys are prefixed by module name.
5. Every plan MUST include a Definition of Done with numbered, testable criteria that map back to §12 above.

## Beyond Phase 5

Phase 5 completes the roadmap in the current design. Anything after belongs to one of three buckets (per master plan §10):

- **v1.20+ proposals** - new capabilities requiring their own proposal → design integration → phase plan cycle.
- **Operational maturity** - ongoing observability/cost/capacity tuning; not phased.
- **Deprecation removals** - opportunistic; triggered by usage counter = 0 for ≥ 30 days (see Phase 4 `08-synapt.md` `deprecation-gate`).
