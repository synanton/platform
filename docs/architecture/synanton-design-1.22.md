# Synanton Platform - Architecture (v1.22)

> **Document type:** Definitive engineering reference
> **Version:** 1.22
> **Date:** 2026-08-28
> **Status:** Current
> **Audience:** Architects, module owners, SREs, security engineers, partner connector authors, UI/frontend leads, DevOps/platform engineers
> **Philosophy:** Clean-slate · zero legacy · single API surface · no compatibility shims

This document is the **single authoritative engineering reference** for the Synanton platform. It is a full merged reprint of the 1.19–1.22 lineage:

| Part | Content | Folded from |
|---|---|---|
| I–VII (§1–§49) | Foundation, processing flows, modules, contracts, data model, cross-cutting concerns, operations | v1.19 (itself a merge of v1.7 → v1.18) |
| VIII (§50–§64) | GPU Execution Plane isolation | v1.20 |
| IX | Structured Content Extraction Plane | v1.21 |
| X | Semantic Content Structuring / Chunking | v1.22 |
| Appendices A–D | Capacity, dependencies, migration process, `synanton-ops` distribution | v1.19, extended by v1.20 |

Sections that v1.20 extended carry a **GPU Execution Plane additions *(v1.20)*** subsection at the end of the host section, reproducing the v1.20 text verbatim rather than rewriting the v1.19 text around it.

Versions 1.19, 1.20 and 1.21 are no longer separate live documents. They are retained as lineage under [`../archive/architecture/`](archive/architecture/) and this document defers to none of them.

Where prior drafts split content across enhancement proposals, reviews, and risk dossiers, this document presents them as a single coherent surface: **principles → topology → end-to-end processing flows → module-by-module specifications → SPIs → data model → cross-cutting concerns → operations → GPU execution → structured extraction → semantic chunking**.

## What's new in v1.22

v1.22 adds a **Semantic Content Structuring / Chunking** layer that operates on the normalized structured representation from the extraction plane-not on `flattenedText`. The same extracted document can be chunked differently for vector search, RAG, summarization, or entity extraction without re-running extraction.

| # | Change | Home |
|---|---|---|
| 1 | Chunking operates on structured `elements`, not flat text | Part X below; `SemanticChunkStage` |
| 2 | Structure builder converts flat elements → hierarchical section tree | Part X; `synflux` |
| 3 | Semantic boundaries first; token/size limits as fallback only | Part X chunker config |
| 4 | Tables are atomic first-class chunks with structured row/column content | Part X; chunk `type=table` |
| 5 | Every chunk carries `sectionPath`, `sourceElements`, `pageStart`/`pageEnd` | Part X; synquest citation fields |
| 6 | Chunking layer is separate from the extraction plane (architectural invariant) | Part X §8.2 |

v1.21 extraction contract remains in force. `synanton.extraction.v1` is mirrored byte-for-byte between `platform` and `content_extractor` (`scripts/verify-contract-mirror.sh`). v1.20 GPU isolation unchanged: `synanton.gpu.v1` mirrored with `gpu-runtime` (`scripts/verify-gpu-contract-mirror.sh`).

## What's new in v1.21

v1.21 adds a **Structured Content Extraction Plane** behind `synanton.extraction.v1`. Topology (embedded, sidecar, or cluster) MUST NOT change the contract.

| # | Change | Home |
|---|---|---|
| 1 | Extraction is a platform contract, not a processor | Part IX below; `content_extractor` |
| 2 | Byte-identical proto mirror between `platform` and `content_extractor` | `scripts/verify-contract-mirror.sh` |
| 3 | Feature state is explicit (`APPLIED` / `NOT_APPLICABLE` / `UNSUPPORTED` / `FAILED`) | extraction error catalogue |
| 4 | Ingest PoC: ExtractSync → semantic chunks with page/section → BM25 index | `scripts/run-extract-index-poc.sh` |

v1.20 GPU isolation remains in force. `synanton.gpu.v1` is mirrored byte-for-byte between `platform` and `gpu-runtime` (`scripts/verify-gpu-contract-mirror.sh`). Java package is `org.synanton.gpu.v1`; RPCs are `Execute`, `Cancel`, `GetStatus`, `GetCapacity`; errors are the `ErrorReason` catalogue. Until that mirror holds, gpu-runtime must not be treated as a platform GPU server.

## What's new in v1.20

Version 1.20 introduces a strict architectural boundary between the **primary Synanton platform** and a separate **GPU Execution Plane**. The changes are architecturally significant but non-breaking to the primary-platform public API surface: no public REST/gRPC contracts change, no modules are renamed, no Kafka/Cassandra/S3 schema migrations are introduced.

| # | Change | Home in v1.20 |
|---|--------|---------------|
| 1 | New `GPU Execution Plane` - physically isolated cluster responsible for GPU-specific execution (model serving, admission, dispatch, runtime lifecycle, GPU capacity, execution telemetry) | New §50–§64 (Part VIII) |
| 2 | New `synanton.gpu.v1` gRPC contract - `Execute`, `Cancel`, `GetStatus`, `GetCapacity` RPCs with PGV validation and structured error categories | §57 |
| 3 | New `GPU Gateway` - execution-plane boundary service with mTLS, authorization assertion validation, idempotency store, and dispatch strategy | §55 |
| 4 | `ModelServingDirectory` refined - resolves logical GPU execution endpoints only; MUST NOT resolve GPU pod IPs, Kubernetes pods, or vLLM instances | §54 |
| 5 | `gateway` extended - uses GPU execution client rather than direct GPU runtime access | §53 (v1.20 callout) |
| 6 | `security` extended - GPU Gateway becomes an independent authenticated service boundary; mTLS required between CPU and GPU clusters | §60 |
| 7 | Deployment split - GPU infrastructure moves to `synanton/gpu-execution-plane` repository; `synanton/platform` no longer owns the production GPU runtime | §53 |
| 8 | Observability - trace context crosses CPU/GPU cluster boundary; new low-cardinality GPU execution attributes | §62 |
| 9 | Cost model extended - GPU usage is reported by the execution plane; primary platform owns tenant attribution and billing policy | §63 |
| 10 | `DirectDispatcher` (default) and optional `EqualixScheduler` introduced as dispatch strategies inside the GPU plane | §59 |

### Compatibility statement (v1.20)

v1.20 introduces no breaking changes to the primary-platform public API surface. The following are new or modified surfaces:

- **New repository:** `synanton/gpu-execution-plane` - independently deployable; MUST NOT depend on `synanton/platform` internals.
- **New gRPC service:** `synanton.gpu.v1.GPUExecutionService` - exposed only to primary-platform GPU execution clients, not to external API consumers.
- **New primary-platform client:** GPU execution client inside `gateway` module - calls `synanton.gpu.v1` over mTLS.
- **Modified `ModelServingDirectory`:** resolves logical GPU execution endpoints; pod-level resolution removed (no schema migration required - this is a constraint tightening, not a schema change).
- **New config keys** (all with safe defaults): `gateway.gpu.*`, `gpu-gateway.*` (see §55, §57).
- **New metrics:** `gpu_execute_total`, `gpu_execute_duration_seconds`, `gpu_admission_rejected_total`, `gpu_model_not_ready_total`, `gpu_idempotency_hit_total` (see §62).
- **No Kafka schema changes.**
- **No Cassandra schema changes.**
- **No PostgreSQL schema changes in `synanton/platform`** (the idempotency store lives in the GPU plane's own PostgreSQL instance).

Rolling upgrade from v1.19 is safe: the GPU execution client is disabled by default until `gateway.gpu.enabled=true` is set; GPU-backed operations fall back to the existing v1.19 CPU path until the GPU plane is deployed and the flag is enabled.

## What's new in v1.19

Version 1.19 folds in the changes from the v1.19 proposal (v2). The changes are **strictly additive** to v1.18 - **no public contracts broken, no modules renamed, no SPI version bump, no Kafka/Cassandra/S3 schema migrations**.

Two new modules are introduced (`helper` and `wizard`), both delivered as a single Go binary (`synanton-ops`) wrapped by the existing `synctl` CLI. One new role (`support_admin`) is added to the `security` module. A set of new internal admin API endpoints is added to `synapt` and `control-plane`.

| # | Change | Home in v1.19 |
|---|--------|---------------|
| 1 | New `helper` module - operational day-2 CLI that executes support tasks exclusively via the platform's internal admin API (authenticated as `support_admin`) | New §26b |
| 2 | New `wizard` module - offline deployment-artifact generator (Terraform / K8s Helm / Docker Compose / `.env`) requiring zero live-cluster credentials | New §26c |
| 3 | New `support_admin` RBAC role - reserved for automated support tooling and break-glass accounts; routes all helper actions through `admin_audit` | §26 `security` |
| 4 | New internal admin API endpoints (`/admin/_internal/*`) on `synapt` and `control-plane` | §24 `synapt`, §27 `control-plane` |
| 5 | `SYNANTON_SUPPORT_KEY` credential lifecycle - argon2id hashing, 90-day (STANDARD) / 30-day (HIGH_SECURITY) rotation; wired to §26a API Key Lifecycle | §26a API Key Lifecycle |
| 6 | Phase plan updated - `helper` and `wizard` appear in all five phases | §48 Implementation Phases |
| 7 | New `helper_operation_total` metric and associated audit table wiring | §45 Observability |

Source: `docs/proposals/v1.19/Synanton Platform Version 1.19 Proposal.md`.

### Compatibility statement (v1.19)

v1.19 introduces no breaking changes. The following are new, additive surfaces:

- **New modules:** `helper` and `wizard` (Go binary `synanton-ops`, distributed via `synctl`). No existing module identity changes.
- **New role:** `support_admin` in `security.roles` table. Not assignable to human users through normal IdP flows.
- **New Postgres rows:** `security.role_assignments` gains a `role` column with the `support_admin` value; `admin_audit` gains `before_state_hash` and `after_state_hash` columns.
- **New internal API routes** (not part of the public contract, accessible only to `support_admin` service principals):
  - `GET /admin/_internal/status`
  - `POST /admin/_internal/bundle`
  - `POST /admin/_internal/clean`
  - `POST /admin/_internal/delete`
  - `POST /admin/_internal/recrawl` / `GET /admin/_internal/recrawl/{tenant}`
  - `POST /admin/_internal/workflow/cancel` / `POST /admin/_internal/workflow/retry`
- **New config keys** (all with safe defaults): `synapt.admin.internal.*`, `synctl.helper.*`, `synctl.wizard.*`.
- **New metrics:** `helper_operation_total{command, tenant, outcome}`.
- **New alerts:** `HelperDestructiveOpsRate`, `HelperAuthFailureSpike`.
- **New audit events:** `admin_audit` table gains events from all `helper` write operations.

Rolling upgrade from v1.18 is safe: the new modules are opt-in binaries; existing modules gain new internal routes that are unreachable without the `support_admin` role.

## What's new in v1.18

Version 1.18 folds in seven additive changes surveyed in the v1.18 proposal, all focused on closing systematic data-validation and Cross-Site Scripting (XSS) protection gaps at the REST, gRPC, and UI ingress points. The changes are strictly additive to v1.17 - **no public contracts broken, no modules renamed, no SPI version bump, no Kafka/Cassandra/S3 schema migrations**. No new modules are introduced; instead, `§24 synapt` and the Part IV SPIs (§28-§32) gain validation capabilities, `§45 Observability` gains sanitisation and validation metrics, and two new cross-cutting sections are appended (`§48b UI Security Guidelines`, `§49 Infrastructure Security Headers`).

| # | Change | Home in v1.18 |
|---|--------|---------------|
| 1 | Global JSON sanitisation - OWASP HTML sanitizer applied via custom Jackson deserialiser to all string fields, with `@AllowHtml` opt-out | §24 `synapt` |
| 2 | Jakarta Validation (JSR-380) on all public DTOs (`@NotBlank`, `@Size`, `@Pattern`, `@URL`, `@Email`) with global `@RestControllerAdvice` translation to structured 400 responses | §24 `synapt` |
| 3 | gRPC validation via `protoc-gen-validate` (PGV) rules on `.proto` files + `ServerInterceptor` returning `INVALID_ARGUMENT` on violation | §28-§32 Part IV contracts |
| 4 | Content Security Policy (CSP) header + `X-Content-Type-Options: nosniff` + `X-Frame-Options: DENY` served with the admin UI | New §49 |
| 5 | Frontend security guidelines - DOMPurify for `dangerouslySetInnerHTML`, URL scheme allow-list, HttpOnly refresh cookies, `rel="noopener noreferrer"` | New §48b |
| 6 | Sanitisation/validation metrics + `SynaptSanitizationHighRate` alert | §45 Observability |
| 7 | Per-tenant sanitiser configuration (allowed tags/attrs, strict/lenient toggle, feature flag) | §24 `synapt`, `topology.tenant_policy.security_sanitizer_overrides` |

### Compatibility statement (v1.18)

v1.18 introduces no breaking changes. The following are new, additive surfaces:

- New `synapt` DTO annotations: `@AllowHtml` marks a field as HTML-bearing (skips sanitisation but retains structural validation). All other Jakarta Validation annotations (`@NotBlank`, `@Size`, `@Pattern`, `@URL`, `@Email`, `@Min`, `@Max`) are compile-time constraints, not runtime schema changes.
- New Postgres column: `topology.tenant_policy.security_sanitizer_overrides JSONB NULL` (per-tenant allowed tags / attributes / strictness).
- New config keys under `synapt.sanitizer.*` and `synapt.validation.*` (see §24), and `ui.security.csp.*` (see §49).
- New response headers on UI / static asset delivery: `Content-Security-Policy`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`.
- New metrics - all under existing naming convention: `synapt_sanitization_applied_total`, `synapt_sanitization_skipped_total`, `synapt_validation_rejected_total`, `grpc_validation_failed_total`.
- New alert: `SynaptSanitizationHighRate`.
- Global feature flag `synapt.validation.strict = false` (default) preserves v1.17 lenient behaviour during migration; flipping to `true` activates rejection.

Rolling upgrade from v1.17 is safe: sanitisation and validation are enforced only when the feature flag is enabled per tenant, and the `@AllowHtml` annotation preserves rich-text field behaviour. The default sanitiser is idempotent on safe text - clients that already send well-formed inputs observe no change.

## What's new in v1.17

Version 1.17 folds in fifteen additive changes surveyed in the v1.17 proposal. The changes are strictly additive to v1.16 - **no public contracts broken, no modules renamed, no SPI version bump**. No new modules are introduced; instead, thirteen existing modules/sections gain new capabilities, one new module subsection is added (`§26a` API key lifecycle), one new operations section is added (`§47a` Disaster Recovery), and three new appendices are appended (`§A` Capacity Planning, `§B` Module Dependency Diagram, `§C` Migration Process).

| # | Change | Home in v1.17 |
|---|--------|---------------|
| 1 | GPU degraded mode & recrawl workflow | §7 Query Flow, §17 `synflux`, §27 `control-plane`, new §47a DR |
| 2 | Cassandra vacuum staggering | §18 `ingestion-cache` |
| 3 | Cross-region latency tuning + follow-the-sun serving | §22 `planner`, §27 `control-plane`, §43 Cross-Region |
| 4 | Cold retrieval rehydration for synthesis | §9 Tier Movement, §23 `gateway` |
| 5 | `synreview` enhancements - versioning, audit, replay, staging | §27a `synreview` |
| 6 | Shard version routing during rebalancing cooldown | §20 `synquest` |
| 7 | Deep Research SLO clarification (machine vs human time) | §27 `control-plane` |
| 8 | Disaster Recovery - RTO/RPO per storage, cross-region DR | New §47a |
| 9 | API deprecation policy (3-release / 6-month window) | §24 `synapt` |
| 10 | Kafka retention flexibility (warn-and-start default) | §17 `synflux`, §37 Kafka Topics |
| 11 | Cascade delete CAS on `source_ref_count` | §10 GDPR, §21 `relix` |
| 12 | LLM client provider negotiation (set of formats) | §27c `synanton-llm-client` |
| 13 | Testing discipline - staging endpoint, request cache, path gating | §48a Testing Discipline |
| 14 | Security - IdP amortization staleness metric + API key lifecycle | §26 `security`, new §26a |
| 15 | Documentation gaps - capacity, module deps, migration | New Appendices §A, §B, §C |

### Compatibility statement (v1.17)

v1.17 introduces no breaking changes. The following are new, additive surfaces:

- New manifest column: `synflux.manifest.embedding_quality` (`FULL` default, `DEGRADED` under degraded mode) and `degraded_restored_at TIMESTAMP NULL`.
- New Postgres column: `topology.tenant_policy.cross_region_penalty_ms JSONB NULL`.
- New Cassandra column: `ingestion-cache.embedding_cache.shard_version INT DEFAULT 1`.
- New synreview columns: `synreview.item.prompt_version`, `synreview.item.model_version`, `synreview.item.staging_expires_at`.
- New response header on synthesis: `X-Synanton-Cold-Rehydration: pending|degraded`.
- New metrics - all under existing naming convention: `synflux_degraded_ingest_total`, `synflux_degraded_recrawl_progress`, `synflux_degraded_recrawl_duration_seconds`, `gateway_degraded_mode_active`, `vacuum_progress_ratio`, `cold_retrieval_triggered_total`, `security_idp_amortization_stale_seconds`, `synapt_deprecated_field_usage_total`.
- New config keys (all with defaults compatible with v1.16 behaviour) - see §17, §18, §22, §23, §27a, §37, §48a, §43.

Rolling upgrade from v1.16 is safe: all new columns/config keys default to values that reproduce v1.16 behaviour.

------

# Table of Contents

**Part I - Foundation**
1. Executive Summary *(extended in v1.20)*
2. Architectural Principles
3. Glossary *(extended in v1.20)*
4. System Topology *(extended in v1.20)*
5. Module Map *(extended in v1.20)*

**Part II - End-to-End Processing**
6. Ingestion Flow
7. Query Flow (Hybrid Search)
8. GraphRAG Flow
9. Tier Movement Flow
10. GDPR Erasure Cascade
11. ACL Propagation Flow
12. Cost Attribution & Forecast Flow
13. Predictive Auto-Scaling Loop
14. Anomaly Detection Loop
15. GitOps Reconciliation Loop

**Part III - Modules (Detailed)**
16. `synvault` - Content Store + Tier Manager
17. `synflux` - Ingestion Engine + Router
18. `ingestion-cache` - Cassandra/ScyllaDB Artifact Cache
19. `syntology` - Ontology Service
20. `synquest` - Hybrid Search Kernel
21. `relix` - GraphRAG Engine + MCP/ACP
22. `planner` - Search Planner
23. `gateway` - Query Gateway *(extended in v1.20)*
24. `synapt` - Public API *(extended in v1.19: internal admin routes)*
25. `topology` - Authoritative Org/ACL/Policy Store
26. `security` - AuthN/Z + Outbound Broker *(extended in v1.19: support_admin role; v1.20: GPU mTLS)*
26a. API Key Lifecycle *(new in v1.17; extended in v1.19: SYNANTON_SUPPORT_KEY)*
26b. `helper` - Operational Day-2 CLI *(new in v1.19)*
26c. `wizard` - Deployment Setup Builder *(new in v1.19)*
27. `control-plane` - Admin, AI-Ops, Forecast, Anomaly, GitOps *(extended in v1.19: internal admin routes)*
27a. `synreview` - Human-in-the-Loop Review System *(v1.16; extended in v1.17)*
27b. `synanton-mcp` - MCP Protocol Bridge
27c. `synanton-llm-client` - Provider-Agnostic LLM Client *(extended in v1.17)*

**Part IV - Contracts & SPIs**
28. Relix Graph Connector SPI v1.0 *(extended in v1.1)*
29. Content Adapter SPI *(companion WebSearchAdapter SPI added in v1.1)*
30. Reranker Port
31. Identity Provider Port + Outbound Auth Broker
32. ACL Propagation Port
33. Module Capability Descriptor
34. Long-Running Task Framework (`JobHandle`)

**Part V - Data Model**
35. PostgreSQL Schema (`topology`, audit, jobs, cost) *(extended in v1.19)*
36. Cassandra/ScyllaDB Schema (`ingestion-cache`)
37. Kafka Topics & Compatibility Rules
38. Redis Keyspaces
39. Object Storage Layout (S3 / Glacier)

**Part VI - Cross-Cutting Concerns**
40. Identity, ACL, and Compile-Time Injection
41. Multi-Tenancy and Isolation Tiers
42. Schema Migration Discipline (N-2)
43. Cross-Region & Data Residency
44. Cost Awareness & Budget Caps
45. Observability - Metrics, Alerts, SLOs, Traces *(extended in v1.19, v1.20)*
46. Deployment Profiles (Full, Standalone, Embedded)
46a. Future UI Addenda *(v1.1)*

**Part VII - Operations & Plan**
47. Failure Modes & Runbooks *(extended in v1.20)*
47a. Disaster Recovery - RTO/RPO & Cross-Region DR *(new in v1.17)*
48. Implementation Phases *(extended in v1.19: helper + wizard rows; v1.20: GPU track)*
48a. Testing Discipline *(v1.16; extended in v1.17)*
48b. UI Security Guidelines *(new in v1.18)*
49. Infrastructure Security Headers *(new in v1.18)*


**Part VIII - GPU Execution Plane *(new in v1.20)***
50. GPU Execution Plane Overview
51. Goals and Non-Goals
52. Architectural Boundary
53. Physical Topology and Repository Split
54. Model Serving Abstraction
55. GPU Gateway
56. Identity and Authorization
57. Execution Contract (`synanton.gpu.v1`)
58. Execution Identity and Lifecycle
59. Scheduling and Dispatch
60. Network and Trust Boundary
61. Error Contract and Validation
62. Observability
63. Cost and Usage
64. Failure Model and Degraded Mode

**Part IX - Structured Content Extraction Plane *(new in v1.21)***
Extraction as a platform contract (`synanton.extraction.v1`), explicit feature state, topology independence

**Part X - Semantic Content Structuring / Chunking *(new in v1.22)***
Structure builder, semantic chunker, atomic table chunks, provenance on every chunk

**Appendices *(new in v1.17; extended in v1.19)***
A. Capacity Planning Guide
B. Module Dependency Diagram *(extended in v1.20)*
C. Migration Process
D. `synanton-ops` Binary - Build & Distribution *(new in v1.19)*

------

# Part I - Foundation

------

## 1. Executive Summary

**Synanton** is a polyglot, high-performance, multi-tenant, federation-native enterprise knowledge platform. It unifies three traditionally siloed capabilities - full-text retrieval, dense semantic retrieval, and knowledge-graph reasoning - into a single open-source engine accessible through human APIs (REST/gRPC), agent APIs (MCP), and agent-to-agent APIs (ACP).

### What the platform does
- **Ingests** documents from heterogeneous sources (S3, FileNet, SharePoint, RDBMS, filesystems, Kafka CDC, webhooks) through a pluggable content adapter SPI.
- **Transforms** content through a staged pipeline (parse → chunk → enrich → embed) with a write-through Cassandra artifact cache for crash safety.
- **Indexes** chunks into a hybrid search kernel (BM25 + HNSW) and a pluggable graph backend (Neo4j, Neptune, or in-memory) via an idempotent SPI.
- **Serves** hybrid queries with optional cross-encoder reranking, GraphRAG synthesis, federation across external search/graph clusters, region-aware data residency enforcement, and per-tenant ACL physical isolation.
- **Operates** itself adaptively: predictive auto-scaling, anomaly detection, automated data tiering, materialized graph views, cross-tenant synthesis cache, GitOps-driven policy reconciliation, per-tenant cost attribution and budget enforcement.

### Design stance
This document is greenfield. There are **no production workloads**, **no shipped contracts**, and **no external consumers** to grandfather. Every interface is designed once, correctly, at its final shape:
- One module identity (no internal/external duality).
- One unversioned public API surface.
- One SPI version per port (no `v1alpha`, no compatibility modes).
- One CLI (`synctl`) regardless of deployment profile.
- One naming convention end-to-end (`synflux_*`, `relix_*`, `synvault_*` …).

### Concerns absorbed from prior drafts
Every architectural concern raised across v1.7 → v1.14 reviews is closed in this document. Mappings live inline at the point of resolution; an exhaustive list appears in §47 (Failure Modes & Runbooks).

### GPU Execution Plane additions *(v1.20)*


> **[v1.20]** The executive summary below extends the v1.19 summary. All v1.19 content remains valid.

**Synanton** is a polyglot, high-performance, multi-tenant, federation-native enterprise knowledge platform. It unifies full-text retrieval, dense semantic retrieval, and knowledge-graph reasoning into a single open-source engine. *(See v1.19 §1 for the full summary.)*

**v1.20 addition:** Synanton now delegates GPU inference and embedding to a physically isolated **GPU Execution Plane** connected over a versioned gRPC contract (`synanton.gpu.v1`). The primary platform retains ownership of business intent, tenant identity, authorization policy, model selection, execution planning, workflow state, degraded-mode orchestration, and cost attribution. The GPU Execution Plane owns GPU-specific execution: model serving, GPU admission, request dispatch, runtime lifecycle, GPU capacity, and execution telemetry.

The central invariant:

> **Synanton decides what should run. The GPU Execution Plane decides how GPU work is executed. Kubernetes decides where the workload runs.**

------

## 2. Architectural Principles

Seven immutable principles bind the design. They are **constraints**, not aspirations.

### P1. Unified Identity
A module has a single immutable identifier (`synflux`, `relix`, `synapt`…) used in code packages, metrics, config keys, CRDs, MCP tool prefixes, and dashboards. No "internal vs external" name duality. Renaming is breaking; we name once.

### P2. Bidirectional Failure Isolation
Ingestion is shielded from downstream outage (Synflux Router). Downstream lag is shielded from cascade correctness (tombstones, manifest dispatch state). Reranker failure does not cascade into search outage (fail-open to un-reranked hits). Federation adapter outage does not destabilise the gateway (per-target timeouts + circuit breakers).

### P3. Identity Correctness Inbound AND Outbound
Inbound IdP authentication and outbound RFC 8693 token exchange share the same subject-assertion machinery. Outbound calls to federated engines default to `ExternalAclTrust = DUAL` (both Synanton's ACL and the upstream system's must agree).

### P4. Cost as a First-Class Signal
Every GPU-bound or long-running operation attributes its cost to `(tenant_id, user_subject?)`. Cost is joinable with quality, gates budget caps, and feeds forecast alerts. Cost attribution defaults to **tenant rollup only**; per-user attribution is explicit opt-in.

### P5. Adaptive Elasticity
Scaling is forecast-driven, not reactive. Predicted load 15 minutes ahead drives KEDA replicas *before* lag arrives. p95 stays stable across daily peaks.

### P6. Data Gravity Awareness
Data lives where it is cheapest to store and most frequently accessed. Automated tiering moves cold data to S3 / Glacier without disturbing the `Synvault` abstraction. Region pinning is a first-class policy.

### P7. Honest Capability Surfacing
Every module publishes a machine-readable `ModuleCapabilities` descriptor. Every graph connector publishes a `PatternCoverage` matrix declaring `NATIVE / FALLBACK / EMULATED` per ISO GQL pattern. Planner uses *measured* costs, not nominal multipliers. The platform never silently degrades - it surfaces the degradation.

------

## 3. Glossary

| Term | Definition |
|------|------------|
| **ACL** | Access Control List. A tuple of `(org_id, space_id, project_id, group_id, folder_id, user_id?)`. |
| **ACP** | Agent Communication Protocol - agent-to-agent endpoints. |
| **BM25** | Probabilistic lexical relevance ranking function. |
| **Cuckoo Filter** | Probabilistic set membership with O(1) deletion - replaces Bloom filters. |
| **GraphRAG** | Two-step retrieval: semantic candidate selection + graph subgraph expansion. |
| **HNSW** | Hierarchical Navigable Small World - graph index for approximate nearest neighbour. |
| **MCP** | Model Context Protocol - agent-tool interface. |
| **MGV** | Materialized Graph View - persistent, incrementally-refreshed subgraph. |
| **PatternCoverage** | Per-graph-pattern declaration of `NATIVE`, `FALLBACK`, or `EMULATED` coverage by a connector. |
| **RFC 8693** | OAuth 2.0 Token Exchange - used by outbound auth broker. |
| **RRF** | Reciprocal Rank Fusion - combines lexical and semantic result sets. |
| **ScopeBundle** | Materialised ACL hierarchy for a `(subject, request)`. |
| **SPI** | Service Provider Interface - pluggable port contract. |
| **Tombstone** | Logical deletion marker that propagates through the streaming bus before physical deletion. |
| **Topology** | Authoritative store of organisations, spaces, users, groups, folders, ACLs, policies. |
| **VET** | Variable Embedding Throttle - backpressure mechanism for GPU-bound embedding. |
| **vLLM** | Open-source LLM inference server used for embedding, synthesis, reranking. |

### GPU Execution Plane additions *(v1.20)*


> **[v1.20]** The following terms are added to the v1.19 glossary.

| Term | Definition |
|------|------------|
| **GPU Execution Plane** | The physically isolated cluster responsible for GPU-specific execution, accessed only through the GPU Gateway. |
| **GPU Gateway** | The sole execution-plane boundary exposed to Synanton; handles authentication, authorization assertion validation, admission, dispatch, idempotency, and telemetry. |
| **GPU Execution Client** | The primary-platform component (inside `gateway`) that calls `synanton.gpu.v1` over mTLS. |
| **`synanton.gpu.v1`** | The versioned gRPC contract between the primary platform and the GPU Execution Plane. |
| **`request_id`** | The primary platform's originating request/workflow identity, used as the GPU plane idempotency key. |
| **`execution_id`** | The GPU Gateway-generated identity for a single GPU execution attempt. |
| **DirectDispatcher** | The default GPU dispatch strategy; routes to the Kubernetes service using standard load balancing. |
| **EqualixScheduler** | Optional fairness/quota/priority scheduler; introduced only when operational evidence requires it. |
| **`ModelServingDirectory`** | Primary-platform abstraction resolving logical model + version → logical execution endpoint (never physical GPU/pod identities). |
| **MODEL_NOT_READY** | GPU Gateway error indicating the requested model is approved but not currently loaded into GPU memory; triggers asynchronous model load and request queuing. |
| **Idempotency store** | Durable PostgreSQL store in the GPU plane mapping `request_id → execution_id + ExecutionResponse`; must be fail-closed. |

------

## 4. System Topology

```
                              ┌──────────────────────────────────────────────────────┐
                              │                  control-plane                       │
                              │  Admin API · Web Console · GitOps Reconciler         │
                              │  Temporal · Prometheus · Grafana · Alertmanager      │
                              │  Cost Aggregator · Anomaly Detector · Forecast Engine│
                              │  ModelServingDirectory (per-region vLLM resolver)    │
                              └──────────────────────────┬───────────────────────────┘
                                                         │
┌────────────────────────────────────────────────────────────────────────────────────┐
│                              External World                                        │
│   S3 · FileNet · RDBMS · Filesystem · SharePoint · Kafka CDC · Webhooks · IdP      │
└──────────────────────────┬─────────────────────────────────────────────────────────┘
                           ▼
                    synvault  (Content Store + Tier Manager)
                           │
                           ▼
                    synflux   (Acquire → Parse → Chunk → Enrich → Embed)
                           │
                           ├──► Cassandra (synchronous write-through, ingestion-cache)
                           │
                           ▼
                    Kafka topic `synflux_enriched_chunks`  (≥ 30-day retention floor)
                           │
                           ▼
                    Synflux Router  (tombstone-aware, forecast-parallelized)
                    ┌───────────────────┬──────────────────┐
                    ▼                   ▼                  ▼
                 synquest            relix              GDPR drain acks
                 (search kernel)    (graph, SPI v1.0)
                    │                   │
                    ▼                   ▼
            Coordinator + Cuckoo    GraphConnectorService
            ACL filter             (Neo4j / Neptune / in-mem)
                    │                   │
                    └──────────┬────────┘
                               ▼
                       planner  (region-aware cost estimator + anomaly hints)
                               │
                               ▼
                       gateway  (compile-time ACL injection · reranker ·
                                 cross-tenant cache · anomaly streaming ·
                                 runtime cancellation · LLM-context allowlist)
                               │
                  ┌────────────┼────────────┬───────────────┐
                  ▼            ▼            ▼               ▼
                synapt       UI BFF     MCP / ACP        synanton-mcp
                                        (Relix native)   (protocol bridge)

       ╔══════════════════════════════════════════════════════════════════════╗
       ║  topology    Org · Space · Project · Folder · User · Group · ACL    ║
       ║              data_residency · tiering · rerank · budget · outbound  ║
       ║              regulatory_profile · cost_privacy                       ║
       ╚══════════════════════════════════════════════════════════════════════╝

       ╔══════════════════════════════════════════════════════════════════════╗
       ║  security    IdP amortization · RFC 8693 broker · Outbox dispatch  ║
       ║              Background MCP revalidation worker · support_admin (v1.19)║
       ╚══════════════════════════════════════════════════════════════════════╝

       ╔══════════════════════════════════════════════════════════════════════╗
       ║  synreview  (v1.1)   review queue · rule sweep · LLM sweep · human ║
       ║             sources: synflux (new entity types, contradictions,    ║
       ║             PII flags), syntology (duplicate merges), control-plane║
       ║             (deep-research gate, ontology lint suggestions)         ║
       ╚══════════════════════════════════════════════════════════════════════╝

       ╔══════════════════════════════════════════════════════════════════════╗
       ║  helper / wizard  (v1.19)   synanton-ops Go binary via `synctl`    ║
       ║  helper  → /admin/_internal/* API (support_admin auth, always online)║
       ║  wizard  → offline artifact generator (Terraform / K8s / Compose)  ║
       ╚══════════════════════════════════════════════════════════════════════╝
```

### Trust zones
- **Control-plane** is admin-only - strictly RBAC-gated.
- **Data-plane** (synflux, synquest, relix, gateway, synapt) is tenant-isolated by physical ACL.
- **Storage** (synvault, ingestion-cache, topology) sits behind data-plane.
- **Security** is the only module allowed to mint subject assertions.
- **Topology** is the only module allowed to write authoritative ACL rows; everyone else reads through it.
- **`helper`** (v1.19) is admin-only, service-principal-authenticated (`support_admin`), and routes every action through `synapt`/`control-plane` internal admin APIs - never touches storage directly.
- **`wizard`** (v1.19) is a pure code generator - no live-cluster access at generation time.

### GPU Execution Plane additions *(v1.20)*


> **[v1.20]** The physical topology is extended to include the GPU cluster. The v1.19 CPU cluster topology is unchanged.

```
                     SYNANTON PRIMARY PLATFORM
                          CPU CLUSTER
┌──────────────────────────────────────────────────────────────────┐
│                                                                  │
│  External API                                                    │
│       │                                                          │
│       ▼                                                          │
│    synapt ───── authentication / tenant context                  │
│       │                                                          │
│       ▼                                                          │
│    planner ───── execution planning                              │
│       │                                                          │
│       ▼                                                          │
│    gateway ───── query/workflow execution                        │
│       │                                                          │
│       ├──── ModelServingDirectory                                │
│       │       logical model → logical execution endpoint         │
│       │                                                          │
│       └──── GPU Execution Client                                 │
│                     │                                            │
└─────────────────────┼────────────────────────────────────────────┘
                      │
                      │ gRPC + mTLS
                      │ synanton.gpu.v1
                      ▼
               GPU EXECUTION PLANE
                  GPU CLUSTER
┌──────────────────────────────────────────────────────────────────┐
│                                                                  │
│                      GPU Gateway                                 │
│                           │                                      │
│                  ┌────────┴─────────┐                            │
│                  │                  │                            │
│           DirectDispatcher   EqualixScheduler                    │
│             (default)          (optional)                        │
│                  │                  │                            │
│                  └────────┬─────────┘                            │
│                           ▼                                      │
│                   Kubernetes Service                             │
│                           │                                      │
│                           ▼                                      │
│                       vLLM pods                                  │
│                           │                                      │
│                           ▼                                      │
│                       GPU nodes                                  │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

The primary platform MUST NOT directly discover GPU pods, nodes, GPUs, or vLLM instances.

------

## 5. Module Map

| Module | Public Brand | Layer | Stack | Primary Role |
|--------|-------------|-------|-------|---------------|
| `synvault` | Synvault | Storage | Java 21, Spring Boot | Content abstraction + Tier Manager |
| `synflux` | Synflux | Data-plane | Java 21, Spring Boot, Reactor | Ingestion pipeline + Router |
| `ingestion-cache` | - | Storage | Cassandra / ScyllaDB | Artifact write-through cache |
| `syntology` | Syntology | Data-plane | Java 21 | Ontology service |
| `synquest` | Synquest | Data-plane | Rust, Tantivy, SIMD | Hybrid search kernel |
| `relix` | Relix | Data-plane | Java 21, Spring Boot | GraphRAG engine + MCP/ACP server |
| `planner` | - | Data-plane | Java 21 | Search query planner |
| `gateway` | - | Data-plane | Java 21, Spring Boot, DuckDB | Query gateway |
| `synapt` | Synapt | Edge | Java 21, Spring Boot | Public REST/gRPC API |
| `topology` | Topology | Storage | Java 21, PostgreSQL | Org/User/ACL/Policy authoritative store |
| `security` | Security | Storage | Java 21 | AuthN/Z + Outbound broker |
| `control-plane` | Control Plane | Control | Java 21, Temporal | Admin, AI-ops, forecast, anomaly, GitOps |
| `synreview` | Synreview | Data-plane | Java 21, Spring Boot, PostgreSQL | Human-in-the-loop review queue + auto-sweep *(v1.1)* |
| `synanton-mcp` | - | Edge | Node.js 20 (TypeScript) | MCP protocol adapter over synapt public API *(v1.1)* |
| `synanton-llm-client` | - | Library | Java 21 (also TS build) | Provider-agnostic LLM wire-format translation *(v1.1)* |
| `helper` | synctl helper | CLI | Go 21 | Operational day-2 CLI via internal admin API *(new in v1.19)* |
| `wizard` | synctl wizard | CLI | Go 21 | Offline deployment artifact generator *(new in v1.19)* |

### GPU Execution Plane additions *(v1.20)*


> **[v1.20]** The following entries are added to the v1.19 module map.

| Module | Role | Repo | Status |
|--------|------|------|--------|
| `gpu-gateway` | GPU execution boundary: mTLS auth, admission, dispatch, idempotency, telemetry | `synanton/gpu-execution-plane` | v1.20 new |
| `gpu-execution-client` | Primary-platform gRPC client for `synanton.gpu.v1` (inside `gateway`) | `synanton/platform` | v1.20 new |
| `synanton.gpu.v1` protobuf | Versioned gRPC contract between CPU and GPU planes | `synanton/gpu-execution-plane` | v1.20 new |

------

# Part II - End-to-End Processing

This section traces each major operational flow through the system, naming the modules and contracts involved at each step.

------

## 6. Ingestion Flow

**Goal:** Take a raw content reference (URI in a source system) and produce indexed chunks searchable by both lexical and semantic queries, traversable in the knowledge graph.

```
External Source ─► synvault ─► synflux ─► Cassandra ─► Kafka ─► synflux-router ─► synquest + relix
```

### Steps

1. **Discovery.** An external source emits a content reference (Kafka CDC, webhook, or scheduled crawl). `synvault.ContentPullPort` resolves the adapter and acquires the raw bytes. Adapters are cursor-resumable; a partial pull restarts from the last consistent point.

2. **Acquisition.** `synflux` writes the raw blob via `synvault.ContentPushPort` (idempotent on `content_ref_id`). The manifest row is created with `state = ACQUIRED`.

3. **Parse.** `synflux.Parser` extracts text + structured metadata (PDF, DOCX, HTML, mailbox, OCR fallback). The parsed artifact is cached with `schema_version` in `ingestion_cache_chunks`.

4. **Chunk.** `synflux.Chunker` applies the tenant's chunking strategy (semantic, fixed-token, or sentence-based). Each chunk gets a stable `chunk_text_hash` (canonical, normalised). `manifest.chunk_strategy_version` records the strategy used.

5. **Enrich.** Optional pipeline stage: entity extraction, language detection, PII redaction, ontology mapping, and **two-step chain-of-thought LLM analysis** (v1.1). The stage runs as sub-stages:
    - **5a - Content dedup gate.** `synflux.IngestCache` computes `sha256(raw_bytes)` for the source and short-circuits the entire pipeline if the digest is already present in `ingestion_cache_source_digests`. Cache hit ⇒ manifest re-points to existing artifacts; no LLM call is made.
    - **5b - Pass 1 (Analysis).** vLLM (via `synanton-llm-client`, §27c) receives the parsed content and emits a structured analysis: candidate entities, concepts, arguments, cross-references to already-indexed content, contradictions with existing knowledge, recommended structure. Cached in `ingestion_cache_analysis` keyed by `(tenant_id, sha256(canonical_text), analysis_model_id)`.
    - **5c - Pass 2 (Generation).** A second LLM call consumes Pass 1 output and emits: ontology-mapped entity/relation candidates for `syntology`, indexing hints for `synquest`, and any review items (new entity types, unresolved contradictions, low-confidence chunks, PII flags) which flow to `synreview` (§27a).
    - **5d - Vision captioning (optional).** For images extracted from PDFs/DOCX, the `synflux.VisionCaptioner` re-encodes each image to PNG, hashes it, and consults `ingestion_cache_image_captions` keyed by SHA256. On miss, a vision model (resolved through `ModelServingDirectory`) emits a factual 2-4 sentence caption. Captions are embedded alongside the image chunk.
    - Output cached as `ENRICHED` artifact; the Pass 1 analysis is preserved independently for downstream use (contradiction surfacing, review items, cross-reference graphs).

6. **Embed.** `synflux.Embedder` calls vLLM through `ModelServingDirectory` (region-resolved). Embedding is cached by `chunk_text_hash` in `embedding_content_cache` with LRU + 30-day TTL - no reference counters (Cassandra counter drift caused too many false evictions). On cache hit, the GPU call is skipped.

7. **Cassandra commit.** All artifact rows (parsed, chunked, embedded) are written through Cassandra with quorum. **This commit must succeed before Kafka publish.** Ordering invariant: cache before bus.

8. **Kafka publish.** The enriched chunk envelope is published to `synflux_enriched_chunks`. Topic retention is **≥ 30 days** - the platform refuses to start otherwise.

9. **Router fan-out.** `synflux-router` (separate Spring Boot service) consumes the topic and dispatches to:
    - `synquest.IndexPort.upsert(chunk)` - for lexical + vector indexing.
    - `relix.GraphConnectorService.ExecuteBulkMutation` - for graph node/edge upsert.
    - Idempotency key: `sha256(content_ref_id || chunk_index || mutation_op)`.
    - Manifest per-target state: `dispatched_to_synquest_at`, `dispatched_to_relix_at`.

10. **Tombstone handling.** If `manifest.erase_state = DELETED`, the router emits a `TOMBSTONE` envelope instead of a chunk envelope, allowing downstreams to drain the deletion safely.

11. **Predictive parallelization.** The router reads `forecast_lag_15m` from the control-plane and increases `fetch.max.parallelism` 10 minutes before retention threat materialises.

12. **Catchup.** If `relix` was offline beyond a target's lag horizon, the operator runs `synctl synflux router catchup --target relix --from-manifest` and the router reconstructs dispatches from the Cassandra manifest.

### Failure semantics
- Cassandra write fails → no Kafka publish, no inconsistency.
- Kafka publish fails after Cassandra → outbox-style retry; the chunk eventually publishes; downstream dedups via idempotency key.
- Embedding GPU saturation → VET backpressure on the embed stage; ingestion slows but does not lose data.
- Router consumer crash → resumes from offset; per-target manifest dedups duplicate dispatches.

### SLOs
- End-to-end (acquire → indexed) p95: < 90 s for hot content, < 5 min for cold.
- Cassandra write: p99 < 50 ms.
- Router dispatch latency: p95 < 2 s per target.

------

## 7. Query Flow (Hybrid Search)

**Goal:** Return ranked, ACL-filtered, optionally reranked, residency-respecting hits with full execution trace.

```
synapt ─► gateway ─► planner ─► [synquest + relix] ─► gateway (rerank, cache) ─► synapt
```

### Steps

1. **API ingress.** Client calls `POST /search` on `synapt` with a `SearchQuery` body (query string, federation strategy, residency, rerank policy, timeout). `synapt` validates the JWT/API key and resolves `tenant_id`, `user_subject`.

2. **Compile.** `gateway` translates the natural-language intent → `SearchQuery` DSL. ACL is **injected at compile time** as explicit `Must` / `TermFilter` clauses (org_id, space_id, …). This makes ACL first-class so the planner sees its cardinality.

3. **LLM-context sanitisation.** `llmContext.customMetadata` is allowlisted at the gateway boundary: keys `[a-zA-Z0-9_-]{1,64}`, values ≤ 256 chars, public-API keys from a pre-approved set. `systemPromptOverrides` injection rejected and audited.

4. **Cache check.** `gateway` consults `synthesis_cache` (cross-tenant, ACL-mask keyed). On hit: serve immediately (≈ 60 % GPU savings on public-like queries). On miss: continue.

5. **Plan.** `planner` decomposes the query into an `ExecutionPlan` DAG following the **canonical 4-phase model** (v1.1):
    - **Phase 1 - Lexical.** `synquest` BM25 with word tokenisation + stop-word removal + CJK bigram tokenisation. Title-match bonus (+10). ACL clauses pre-applied (see step 2).
    - **Phase 1.5 - Semantic.** `synquest` HNSW. Merged with Phase 1 via Reciprocal Rank Fusion (`FusionConfig.k` default 60).
    - **Phase 2 - Graph expansion.** Optional `relix` GraphRAG leg using Phase 1 top-K as seed nodes; 4-signal edge relevance model (see §21).
    - **Phase 3 - Budget control.** Candidate set trimmed to fit the query's `ContextBudget` (see §22). Rankings preserved; hits beyond budget dropped with a warning.
    - **Optional rerank node.** After Phase 3 trim, per tenant rerank policy.

    Scores are unified across phases via a single `CandidateScore { lexical_score, semantic_score, graph_score, combined_score }` type carried through the DAG. Phase-crossing comparisons are meaningful because RRF normalises rank-based scores before combination.

6. **Region & residency.** `planner` reads the effective residency policy (query-level overrides tenant default). Searchers whose `region` is not in `allowed_regions`:
    - `fail_closed = true` → return `ERR_DATA_RESIDENCY_VIOLATION`.
    - `fail_closed = false` → drop the offender, emit warning header.

7. **Cost estimate.** `planner.CostEstimator` reads `ConnectorCostProfile` (measured per-connector) and tags the plan with `estimated_gpu_ms` and `estimated_cross_region_bytes`.

8. **Anomaly probe.** `planner` calls `AnomalyDetectorPort` with `(tenant, endpoint, time_of_day, query_signature)`. If a slow signature matches, the planner inflates the timeout by 2 s and emits a `query_prediction_slow` event.

9. **Execute.** Plan runs reactively. `synquest` enforces ACL via Cuckoo filter pre-filter for HIGH_SECURITY tenants; final-trim at gateway is defence-in-depth.

10. **Fuse.** RRF combines lexical and semantic hits; `FusionConfig.k` parameter controllable per query.

11. **Rerank (optional).** `gateway.RerankerPort` invokes:
    - `bge-reranker-v2` (vLLM, first-party), or
    - `CohereRerankAdapter`, or
    - `VoyageRerankAdapter`.
    Outbound credentials via `OutboundAuthBroker` (RFC 8693). On failure: return un-reranked hits, increment `gateway_reranker_fallback_total`, set warning header. **Reranker outage never cascades.**

12. **Final ACL trim.** `gateway` applies a final-pass ACL check on the top-N as defence-in-depth.

13. **Stream anomaly trace.** Execution trace `(latency, hit_count, gql_patterns, errors)` streamed to `synanton_anomaly` Kafka topic for offline analysis.

14. **Cost emission.** `gateway` emits an `api_usage` Avro event with `embedder_gpu_ms`, `synthesis_gpu_ms`, `reranker_gpu_ms`, `federation_targets`, `cross_region_bytes`, `cache_hits`. `user_subject` is null unless tenant opted in to per-user attribution.

15. **Response.** `synapt` returns `{hits, execution_trace, warnings}`. `execution_trace` includes plan, cost, rerank trace, and `patterns_used` with optimization levels.

### Runtime safety
- Soft `maxHits` (default 10 000) signals cancellation.
- Hard breaker at `maxHits × 1.5` throws `HardResourceExceededException`.
- DuckDB `cache_lookup` UDF carries `AtomicLong` row counter; > 10 000 gRPC fan-outs without admin scope cancels with `ERR_HIGH_CARDINALITY_EXCEEDED`.

### GPU degraded mode *(v1.17)*

The query path integrates with the platform-wide **GPU degraded mode** circuit (activated by `control-plane` - see §27). When `gateway_degraded_mode_active == 1`:

- **Step 5 - Plan** skips Phase 1.5 (Semantic) if the embedding model is unavailable at ingest quality and no cached semantic hits are usable; otherwise runs with the fallback embedding model (`all-MiniLM-L6-v2` by default).
- **Step 11 - Rerank** is disabled; the gateway returns un-reranked hits and sets response header `X-Synanton-Degraded: rerank-disabled`.
- **Synthesis** (GraphRAG or Deep Research summary) is disabled and returns `ERR_SYNTHESIS_UNAVAILABLE` with retry-after guidance; the gateway sets response header `X-Synanton-Degraded: synthesis-disabled`.
- Results carrying `manifest.embedding_quality = DEGRADED` are annotated in the response `execution_trace.warnings` list so callers can decide whether to retry after restoration.

Degraded mode is orthogonal to `fail_closed` residency behaviour and to reranker fallback (`gateway_reranker_fallback_total`) - an outage of a single reranker does not trigger platform degraded mode; only GPU cluster saturation does.

### SLOs
- Hot p95 < 200 ms; cold-tier rehydration p95 < 500 ms.
- Reranker availability > 99.9 % (excluding declared degraded-mode windows).
- Translation overhead (ISO GQL → native) < 2 % per query.
- Degraded-mode query success (lexical-only) > 99.5 % of nominal traffic during a declared window.

------

## 8. GraphRAG Flow

GraphRAG is a specialised query path that combines vector retrieval with graph traversal for richer context.

```
gateway ─► planner ─► relix.SemanticReporter ─► [synquest seed search] ─► relix subgraph expansion ─► relix synthesis ─► gateway
```

### Steps

1. **Seed.** Vector search in `synquest` returns top-K candidate chunks.
2. **Anchor extraction.** `relix.SemanticReporter` maps chunks to graph entities (via `syntology` ontology mapping).
3. **Subgraph expansion.** Issue an ISO GQL query (variable-length path) against the configured connector. The query expands `k` hops, bounded by `max_subgraph_nodes` (default 5 000).
4. **Supernode safety.** `TOP_K_RELEVANCE_SAMPLING` prunes high-degree nodes: only edges whose targets are in the top-k chunk set are traversed. Supernode is included as anchor even if no relevant edges exist. Truncations counted in `relix_supernode_truncation_total{tenant,label}`.
5. **Emulated fallback.** If the connector reports `EMULATED` for variable-length paths, `relix` runs BFS with **level batching** - one GQL call per depth using `IN ($frontier_ids)`. Per-traversal `emulated_total_timeout` default 4 s. Partial-result vs error is caller-configurable.
6. **MGV short-circuit.** If a `@synanton.relix.ext.materialized_view` hint matches a registered view, `relix` serves from view storage (separate Neo4j keyspace or Redis cache). On view lag > `max_allowed_lag_ms`, transparently falls back to live traversal.
7. **Synthesis.** Subgraph + chunks fed to vLLM synthesis model. Result cached in `synthesis_cache` keyed by `(normalised_query, ontology_version, model, locale, max_acl_mask)`.
8. **Cross-tenant cache.** ACL-mask logic in §10 (gateway) determines if a hit can be served across tenants.

### Synchronous MCP rejection
Synchronous MCP calls that would trigger GraphRAG synthesis return `ERR_REQUIRES_STREAMING`. Synthesis is only available on async/SSE paths to prevent GPU latency contamination of synchronous tool calls.

------

## 9. Tier Movement Flow

```
ingestion-cache (hot) ─► synvault.TierManager ─► S3 Standard (warm) ─► Glacier (cold)
```

### Steps

1. **Daemon scan.** `synvault.TierManager` daemon scans `ingestion_cache_manifest` for rows where `ingested_at + hot_retention_days < now()`.
2. **Copy.** `COPY (SELECT payload FROM ingestion_cache_chunks WHERE …) TO 's3://…'`.
3. **Manifest update.** `manifest.storage_tier = WARM`, `archive_location = s3://…`.
4. **Cassandra truncate.** Payload column truncated to free heap.
5. **Glacier transition.** After `warm_retention_days`, S3 lifecycle policy moves to `GLACIER` or `DEEP_ARCHIVE`.
6. **Retrieval.** `ContentPullPort.read(chunk_id)` checks tier:
    - HOT → Cassandra direct.
    - WARM → S3 fetch, transparent (may set `X-Synanton-Warning: cold-retrieval` header).
    - COLD → S3 Glacier; if retrieval > 30 s expected, returns `202 Accepted` + `Location` for async poll.
7. **Metric.** `synvault_tier_moved_bytes_total{tenant,target_tier}`.

### Cold-retrieval rehydration for synthesis *(v1.17)*

The v1.16 flow served cold chunks with a `202 Accepted` for asynchronous retrieval. This works for interactive search - the user sees a "results loading" indicator - but breaks synthesis: GraphRAG and Deep Research require *the full document context in the LLM's context window* before generation can begin, so `202` translates to a broken UX.

**v1.17 behaviour** when a synthesis query (see §8, §23) touches at least one cold chunk:

1. `gateway` detects the cold-chunk presence during the plan-execute phase (chunks carry their `storage_tier`).
2. Gateway triggers **background rehydration** via `synvault.rehydrateAsync(content_ref_id)` - Glacier expedited retrieval (typically 1-5 minutes).
3. Gateway checks the `cold_rehydration_cache` (Redis, 1 h TTL, cross-tenant-safe under the same ACL-mask rules as the synthesis cache) for a preserved full-body copy from a recent rehydration. If found → serve immediately.
4. Otherwise, the gateway waits up to `gateway.cold_wait_ms` (default 8 000 ms). If rehydration completes → proceed with full-context synthesis and populate `cold_rehydration_cache`.
5. On timeout, gateway falls back to a **degraded synthesis path**: uses the abstract/summary field stored in the manifest (`manifest.abstract_text`, populated by `synflux` Pass 2 for all documents at ingest), sets response header `X-Synanton-Cold-Rehydration: degraded`, and includes a warning in `execution_trace.warnings`. Rehydration continues in the background and populates the cache for the next request.

**Cache design.** `cold_rehydration_cache` lives in Redis (see §38) under key `synanton:rehydrate:{sha256(content_ref_id)}`. Values are the full parsed text (not the raw S3 blob). TTL is 1 h from first miss; the entry is refreshed on hit. Sized-bounded by tenant to `gateway.cold_rehydration_cache_max_mb` (default 512).

**Failure semantics.**
- Glacier retrieval fails → the degraded synthesis path is used and the failure is logged; no new attempts for the same content_ref for `gateway.cold_rehydration_backoff_seconds` (default 300).
- Timeout during rehydration wait → served degraded; **the synthesis result is not persisted to `synthesis_cache`** so that the next request re-attempts full-context synthesis.

### Failure semantics
- S3 transient failure → daemon retries with exponential backoff.
- Manifest update fails after S3 copy → next daemon pass detects the orphan and re-attempts.
- No daemon movement for 4 h despite pending data → `TierMoveStalled` warning.

------

## 10. GDPR Erasure Cascade

**Goal:** Erase a content_ref from all data planes within 45 s p99 - verifiable, cancellation-safe, and idempotent.

End-to-end SLO: **p99 ≤ 45 s** (widened from 30 s to accommodate the router drain step).

### Sequence

1. **Gateway invalidates session caches** *synchronously* in Redis.
2. **Synflux injects `TOMBSTONE`** on `synflux_enriched_chunks` for the content_ref.
3. **Router drain.** `synflux-router` must acknowledge the tombstone for **all** active targets before cascade proceeds. Drain timeout governed by tenant `regulatory_profile`:
    - `STANDARD` → 30 s
    - `FINANCIAL` → 20 s
    - `HEALTHCARE` → 15 s
4. **Synquest Tantivy** issues `DeleteDocument(content_ref_id)`; `tombstone_fragmentation_ratio` > 30 % triggers compaction.
5. **Relix cascade with entity reference count** *(v1.1; hardened with CAS in v1.17)*. For every entity referenced by the deleted `content_ref`, `relix` performs a **compare-and-swap conditional delete** inside a single graph transaction:
    - **CAS decrement.** `UPDATE ENTITY SET source_ref_count = source_ref_count - 1 WHERE entity_id = ? AND source_ref_count > 0 AND ? IN sources RETURNING source_ref_count`.
    - CAS returning 0 rows → this deletion has already been applied (another concurrent cascade beat us to it). Skip; emit `relix_cascade_cas_noop_total`.
    - CAS returning a row with `new_count > 0` → remove only this source's reference edge. Entity, and other sources' edges to it, remain. Emit `relix_source_ref_decremented_total`.
    - CAS returning a row with `new_count = 0` → full `DETACH DELETE` for the entity (batches of 1000), **still inside the same transaction that performed the CAS**. Emit `relix_graph_node_deleted_total` and `relix_entity_deleted_total`.
    - If the deletion empties the last instance of an ontology type, the workflow files a `TYPE_DEPRECATION_CANDIDATE` review item in `synreview` (not automatic - deprecation requires human confirmation).

    Rationale: without CAS, two concurrent GDPR cascades targeting content_refs A and B that both cite entity E can race - both read `source_ref_count = 2`, both decrement to 1, and E ends up orphaned (count = 1 but zero remaining sources). CAS forces the decrement to observe the same value it will write; whichever transaction commits second observes the updated count and cascades correctly. Cannot be inferred safely by the router alone - needs graph-level knowledge.

    Connectors that do not natively support CAS on properties must emulate it via a compound key on the source-list (e.g. Neo4j: `MATCH (e) WHERE ? IN e.sources SET e.sources = [s IN e.sources WHERE s <> ?] RETURN size(e.sources) AS new_count`); the SPI (§28) declares CAS as a `NATIVE | EMULATED` pattern feature.
6. **Ingestion-cache** deletes manifest + chunk partitions; LRU collects orphan embeddings within TTL.
7. **Synthesis-cache** invalidates entries whose `source_subgraph_refs ∋ content_ref_id`.
8. **Synvault** removes from hot/warm tier; Glacier deletion deferred to compliance window (records remain in immutable archive per regulation, marked `ERASE_PENDING_RETENTION`).

### Observability
- `content_deletion_latency_seconds` histogram (gateway → Tantivy commit).
- `tombstone_fragmentation_ratio` per Tantivy segment.
- `relix_graph_node_deleted` counter.

### Failure
- Terminal failure → `ERR_GDPR_CASCADE_RETRY_EXHAUSTED` with `ERASE_INCOMPLETE` marker; runbook publishes a manual cleanup path.
- Stuck drain (router can't ack a target for 60 s) → page operator.

------

## 11. ACL Propagation Flow

**Goal:** Propagate ACL grants/revokes from `topology` to `synquest` (Cuckoo filter), `gateway` (cache invalidation), `relix` (projection refresh) - without holding the topology transaction open on slow consumers.

```
topology ─(post-commit outbox)─► synquest + gateway + relix
```

### Steps

1. **Mutation.** Admin calls `TopologyMutationApi.grant(...)`. `topology` writes the grant row and an outbox row in a single PostgreSQL transaction.
2. **Commit.** Transaction commits. Caller gets `202 Accepted` with `propagation_id`.
3. **Outbox dispatch.** A dedicated post-commit worker reads outbox rows and fans out gRPC notifications to consumers. **gRPC fan-out happens OUTSIDE the transaction.**
4. **HIGH_SECURITY two-phase.** For HIGH_SECURITY tenants, the worker waits up to 50 ms for all acks:
    - All ack → mark `PROPAGATED`.
    - Any fail → mark `PENDING_PROPAGATION`. Reconciler retries every 5 s for 5 min.
    - 3 consecutive reconciler runs unresolved → `AclStuckGrant` page.
5. **Cuckoo update.** `synquest` updates its Cuckoo ACL filter atomically (O(1) - no Bloom rebuild). p99 update latency < 300 ms SLO.
6. **Projection lag fallback.** If Neo4j projection lag > 5 s, `gateway.resolveUserScope` falls back to authoritative PostgreSQL until 3 consecutive intervals return under SLO.

### IdP amortization
A single thundering herd of token validation never reaches the IdP. `security.IdpStatusAmortizationCache` caches active status:
- 5 s window for HIGH_SECURITY.
- 60 s for STANDARD.
- SCIM events evict explicitly.

### Worker token renewal
At `subject_assertion.exp − 10 min`, long-running workers call `security.IssueWorkerAssertion(job_id)`. Security re-checks subject validity. On revocation: `ERR_SUBJECT_REVOKED` + compensation rollback. Long jobs cannot outlive their identity.

### MCP session revalidation
A background virtual-thread `RevalidationWorker` re-validates open MCP sessions on a sliding 15-min schedule (per-tier override 5/15/60 min). Exponential backoff on IdP unavailability - transient IdP blips don't kill user sessions.

------

## 12. Cost Attribution & Forecast Flow

```
gateway (per request) ─► api_usage Kafka ─► cost-aggregator ─► cost_attribution_daily PG ─► forecast-engine ─► budget alerts + 429
```

### Steps

1. **Emission.** Every `synapt` request emits an `api_usage` Avro event with `embedder_gpu_ms`, `synthesis_gpu_ms`, `reranker_gpu_ms`, `federation_targets`, `cross_region_bytes`, `cache_hits`. `user_subject` is null by default; tenant opt-in via `cost_privacy.attribute_per_user = true`.
2. **Aggregation.** `control-plane.cost-aggregator` consumes `api_usage`, rolls up 5-min windows, writes to PostgreSQL `cost_attribution_daily`.
3. **Forecast.** `control-plane.forecast-engine` runs Prophet (with linear fallback) and updates `control_forecast_exhaustion_days{tenant}`.
4. **Alerts.**
    - `ForecastCostOverrunWarning` < 7 days.
    - `ForecastCostOverrunCritical` < 3 days (page).
    - `TenantBudgetExhausted` at 100 % consumption.
5. **Enforcement.** At 100 % monthly consumption, `synapt` returns HTTP `429 Too Many Requests` with `Retry-After`. Lower thresholds (70 %, 90 %) emit warnings but don't block.
6. **Dashboard.** `/admin/cost/chargeback` returns rollups by tenant/day; Grafana dashboard renders.

------

## 13. Predictive Auto-Scaling Loop

```
Prometheus history ─► forecast-engine ─► KEDA / ConfigMap ─► HPA replicas
```

### Steps

1. **Read.** `control-plane.forecast-engine` queries Prometheus for trailing 14-day history of:
    - `synflux_router_lag` per target.
    - `vllm_queue_depth_seconds` per model.
    - `gateway_qps` per tenant tier.
2. **Forecast.** Generate 15-min-ahead forecast (Prophet primary, ARIMA fallback). Tagged with confidence interval.
3. **Write.** Update `forecast_lag_15m` gauge + write `recommended_parallelism` to a ConfigMap (or directly invoke a KEDA `ScaledObject`).
4. **Scale.** KEDA HPA adjusts replicas. p95 latency stays stable across daily business peaks because replicas come up *before* lag arrives.
5. **Accuracy SLO.** Forecast ± 20 %, 90 % of 2-h windows. Below SLO → `ForecastAccuracyDegraded` warning.

------

## 14. Anomaly Detection Loop

```
gateway ─► anomaly Kafka topic ─► control-plane.anomaly-detector ─► recommendations table ─► /admin/anomalies/recommendations
```

### Steps

1. **Stream.** `gateway` streams `(tenant, endpoint, latency_ms, hit_count, gql_patterns, errors, residency_filtered_count)` per query to `synanton_anomaly` Kafka topic.
2. **Detect.** `control-plane.anomaly-detector` runs Isolation Forest on `(latency, error_rate, gql_pattern_vector)`. DBSCAN clusters slow query signatures.
3. **Record.** Pattern repeats > 3 times / hour → write recommendation to `recommendations` table:
    > "Consider adding an index on `Concept.id` for tenant X; observed 47 slow queries matching pattern Y in the last hour."
4. **Advisory only.** Recommendations are surfaced via `/admin/anomalies/recommendations` and Slack/email. **They are never auto-applied.** Human-in-the-loop.
5. **Real-time inflation.** When `planner` queries `AnomalyDetectorPort` with a matching `(tenant, endpoint, time_of_day, query_signature)`, it inflates timeout by 2 s preemptively.

------

## 15. GitOps Reconciliation Loop

```
Git repo (tenant policies as CRDs) ─► control-plane.gitops-reconciler ─► topology policy tables
```

### Steps

1. **Watch.** `control-plane.gitops-reconciler` watches a configured Git repo (webhook + 60 s poll fallback).
2. **Apply.** Parses Kubernetes CRDs (or Terraform provider state) and upserts:
    - `organizations.data_residency_policy`
    - `organizations.tiering_policy`
    - `organizations.rerank_policy`
    - `organizations.budget_policy`
    - `organizations.outbound_auth_profiles`
    - `organizations.regulatory_profile`
    - `organizations.cost_privacy`
3. **Audit.** Every reconcile writes an entry to `admin_audit` (actor = git commit author).
4. **Drift detection.** If `topology` state diverges from Git for > 5 min → `GitOpsReconcileFailed` page.
5. **Safety.** `fail_closed = true` policy changes require an explicit admin override marker in the commit (prevents accidental tenant lockout).

------

# Part III - Modules (Detailed)

For each module: role, responsibilities, interfaces, data model, key algorithms, configuration, metrics, alerts, and failure modes.

------

## 16. Module: `synvault` (Content Store + Tier Manager)

### Role
Unified abstraction over heterogeneous content sources (S3, FileNet, RDBMS, filesystem, SharePoint, …). Provides pluggable adapters and an automated tiering daemon.

### Responsibilities
- Resolve content references to byte streams via adapter SPI.
- Persist content for re-acquisition (push port).
- Track tiering state per content reference.
- Move cold content to S3 Standard / Glacier transparently.
- Rehydrate cold content on demand.

### Interfaces

**Inbound (synchronous):**
- `ContentPullPort.read(content_ref_id) → Stream<bytes>`
- `ContentPullPort.list(query_cursor) → Page<content_ref_id>`
- `ContentPushPort.write(content_ref_id, bytes) → void`
- `ContentAdapterRegistry.register(adapter)`

**Outbound:**
- `ContentEvent(CREATED | UPDATED | DELETED)` on `synvault_content_events` Kafka topic.

### Adapter SPI
Implementations: `S3Adapter`, `FileNetAdapter`, `RdbmsAdapter`, `FilesystemAdapter`, `SharePointAdapter`, `KafkaCdcAdapter`, `WebhookAdapter`. Cursor-resumable: every adapter returns a `ContentCursor` that captures pagination state and source-system high-water marks.

### Data model

`ingestion_cache_manifest` (Cassandra):
```
PRIMARY KEY ((tenant_id, content_ref_id))
COLUMNS:
  ingested_at         timestamp
  schema_version      int
  chunk_strategy      text
  chunk_strategy_version int
  state               text  -- ACQUIRED | PARSED | CHUNKED | ENRICHED | EMBEDDED | INDEXED
  erase_state         text  -- ACTIVE | TOMBSTONE | DELETED
  storage_tier        text  -- HOT | WARM | COLD
  archive_location    text
  dispatched_to_synquest_at timestamp
  dispatched_to_relix_at    timestamp
```

### Tier Manager

```
loop forever:
  rows = scan(manifest WHERE storage_tier = HOT AND ingested_at < now - hot_retention_days)
  for row in rows:
    s3_uri = COPY chunks_payload FROM cassandra TO s3
    UPDATE manifest SET storage_tier = WARM, archive_location = s3_uri
    TRUNCATE chunks_payload IN cassandra
  sleep(scan_interval)
```

### Configuration
- `synvault.tier.scan_interval_seconds` (default 300)
- `synvault.tier.parallelism` (default 8)
- `synvault.tier.s3_part_size_mb` (default 16)
- `synvault.adapter.{name}.config_path` (per-adapter)

### Metrics
- `synvault_tier_moved_bytes_total{tenant,target_tier}`
- `synvault_tier_scan_duration_seconds`
- `synvault_adapter_pull_duration_seconds{adapter}`
- `synvault_cold_retrieval_total{tenant,target_tier}`

### Alerts
- `TierMoveStalled` - no movement in 4 h with pending rows.
- `ColdRetrievalSpike` - > 10× baseline cold retrievals.

### Failure modes
- S3 transient failure → exponential backoff retry. Manifest unchanged.
- Adapter cursor expiry → `ContentCursor.resume()` returns adapter to last consistent point.
- Glacier retrieval slow → `synapt` returns `202 Accepted` + `Location`.

------

## 17. Module: `synflux` (Ingestion Engine + Router)

### Role
Sole owner of the content transformation pipeline. Two deployable components: ingestion core (Spring Boot service) and router (separate Spring Boot service).

### Responsibilities (core)
- Acquire raw content via `synvault.ContentPullPort`.
- Parse → chunk → enrich → embed.
- Write artifacts through Cassandra (synchronous).
- Publish to `synflux_enriched_chunks` Kafka topic.

### Responsibilities (router)
- Consume `synflux_enriched_chunks`.
- Dispatch to `synquest` and `relix` with idempotency keys.
- Honor tombstones (GDPR).
- Adapt parallelism using `forecast_lag_15m`.

### Pipeline
```
Acquire → SHA256 dedup gate → Parse → Chunk →
  Enrich [Analysis (Pass 1) → Generation (Pass 2) → Vision (opt.)] →
  Embed → IndexDispatch (Kafka publish)
```

**Ordering invariant:** Cassandra write **MUST** complete before Kafka publish. No exceptions.

### GPU degraded mode *(v1.17)*

When `control-plane` (§27) has declared degraded mode (`gateway_degraded_mode_active == 1`), the pipeline switches deterministically to a GPU-lite path so ingestion can continue at reduced quality rather than blocking:

- **Enrich** - the two-step chain-of-thought pass is skipped; only Parse and Chunk run. `synreview` review-item generation is deferred (the row is marked and re-considered on restoration).
- **Embed** - falls back to `synflux.degraded.embedding_fallback_model` (default `all-MiniLM-L6-v2`, CPU-compatible). If CPU cannot sustain the fallback (queue depth > `degraded.cpu_max_queue_seconds`, default 30 s), embedding is skipped entirely; lexical-only indexing proceeds.
- **Vision captioning** - disabled; images are dropped with `synflux_vision_dropped_total{reason="degraded_mode"}`.
- **Manifest annotation** - every row ingested during a degraded window is written with `embedding_quality = DEGRADED` (default `FULL`). The column is a strict enum: `FULL | DEGRADED | LEXICAL_ONLY`.
- **Metrics** - `synflux_degraded_ingest_total{tenant, quality}` counter increments per chunk; `synflux_embedder_gpu_ms_total` is not emitted while degraded.

The pipeline does **not** attempt in-band recovery. It emits degraded rows and lets the `Recrawl-After-Restoration` Temporal workflow (owned by `control-plane`, see §27) fix them once the GPU cluster is healthy. This preserves the single-writer property of the enrichment pipeline and avoids double-dispatch races.

**Manifest schema addition:**
```sql
ALTER TABLE ingestion_cache.manifest
  ADD embedding_quality TEXT DEFAULT 'FULL',   -- FULL | DEGRADED | LEXICAL_ONLY
  ADD degraded_restored_at TIMESTAMP NULL;
CREATE INDEX manifest_by_quality
  ON manifest(tenant_id, embedding_quality, state)
  WHERE embedding_quality != 'FULL';
```

The partial index bounds recrawl scan cost - the workflow only reads rows currently degraded.

### Kafka retention flexibility *(v1.17)*

v1.16 refused to start when Kafka `retention.ms` was configured below the 30-day platform floor. This produced a deadlock scenario: an operator whose disk had filled had no way to loosen retention to recover without also disabling the safety check. v1.17 relaxes this:

- **Default behaviour** - `synflux` starts with `retention.ms < 30d`, logs an audited warning, and emits a **continuous** `synflux_router_short_retention` gauge (1 while below floor). The `SynfluxRouterShortRetention` alert fires and stays firing.
- **Strict mode** - operators can set `synflux.router.strict_retention = true` to restore v1.16 behaviour (refuse to start). Recommended for production tenants under regulatory audit; disabled by default for embedded/standalone profiles.
- **Rationale** - see §37 for the topic-side flexibility rules. A hard-refuse-to-start policy conflicts with disaster recovery: an operator must be able to boot the platform to drain the very topics whose retention needs adjusting.

### Two-step chain-of-thought enrichment *(v1.1)*

The Enrich stage is decomposed into two sequential LLM passes rather than one:

- **Pass 1 - Analysis.** Consumes parsed content plus a compact snapshot of already-linked entities (via `syntology.resolveContext`). Emits structured JSON: `{entities[], concepts[], arguments[], cross_refs[], contradictions[], recommended_structure}`. Cached by `sha256(canonical_text)` in `ingestion_cache_analysis` (Cassandra) with 90-day TTL. **The analysis artifact is retained even after generation completes** - it is queried directly to surface contradictions in the `synreview` UI and to seed community-detection insight jobs.

- **Pass 2 - Generation.** Consumes Pass 1 analysis + parsed content. Emits: (a) `syntology` ontology candidates - new entity/relation types, which flow through `synreview` for approval before commit; (b) `synquest` indexing hints (title-match weight bumps, section-header anchors); (c) review items for human-in-the-loop dispatch.

Rationale (from LLM Wiki review §1): a single LLM call cannot simultaneously parse, reason about context, and generate well-structured output within a bounded context window. Splitting improves quality, makes analysis independently reusable, and creates a natural review checkpoint.

**Failure semantics:**
- Pass 1 fails → DLQ with `poison_reason = ANALYSIS_FAILED`. No generation attempted.
- Pass 2 fails but Pass 1 succeeded → chunk still indexed with parsed content only; review item raised of type `GENERATION_INCOMPLETE`; retry via `synctl synflux enrich retry`.

### SHA256 incremental cache *(v1.1)*

Two SHA256-keyed dedup caches sit ahead of expensive stages:

| Cache | Key | Purpose | TTL |
|-------|-----|---------|-----|
| `ingestion_cache_source_digests` | `sha256(raw_bytes)` | Skip re-ingest of identical source files | never - evicted only on source delete |
| `ingestion_cache_analysis` | `(tenant_id, sha256(canonical_text), analysis_model_id)` | Skip Pass 1 for identical parsed content | 90 days |
| `ingestion_cache_image_captions` | `sha256(png_bytes)` | Skip vision-model captioning for duplicate images (logos, chart templates) | configurable (default 365 days) |

**Design note:** the source-digest cache is tenant-partitioned (`tenant_id, sha256`). The image-caption cache is optionally cross-tenant when `cost_privacy.share_image_captions = true` (default false) - captions are factual and low-sensitivity, so cross-tenant sharing is safe under explicit opt-in.

### Vision captioning stage *(v1.1)*

For content types containing embedded images (PDF, DOCX, PPTX, HTML with `<img>`):

1. `ImageExtractor` walks the parsed document, extracting each embedded image.
2. Each image is normalised - re-encoded to PNG, stripped of EXIF metadata (PII risk).
3. `sha256(png_bytes)` is computed; `ingestion_cache_image_captions` consulted.
4. On miss, `VisionCaptioner` calls the vision model via `ModelServingDirectory` with a factual-caption prompt (2-4 sentences, verbatim any embedded text, no speculation).
5. Caption is stored, and a `synflux_image_chunk` is emitted alongside the containing text chunk with `chunk_type = IMAGE_CAPTION`.

Vision model is configured separately from the analysis model: `synflux.vision.model_family` (default `qwen2-vl-7b`). Failures are non-fatal - the image is dropped from the index with `synflux_vision_dropped_total{tenant,reason}` incremented.

### Algorithms

**Idempotency key:**
```
sha256(content_ref_id || ':' || chunk_index || ':' || mutation_op)
```

**Predictive parallelization:**
```
poll forecast_lag_15m every 60s
if forecast_lag > retention_days * 0.5:
  fetch.max.parallelism *= 1.5
```

**Tombstone-aware drain:**
```
on consume(envelope):
  if envelope.type == TOMBSTONE:
    for target in [synquest, relix]:
      target.delete(content_ref_id)
      mark dispatched_to_target_at = now
  else:
    target.upsert(envelope.chunk, idempotency_key=envelope.idempotency_key)
```

### Configuration
- `synflux.embedder.model` (default `bge-small-en-v1.5`)
- `synflux.embedder.batch_size` (default 32)
- `synflux.chunker.strategy` (`SEMANTIC | FIXED_TOKEN | SENTENCE`)
- `synflux.chunker.window_tokens` (default 512)
- `synflux.router.fetch_max_parallelism` (auto, default 8)
- `synflux.router.allow_short_retention` (default false; emits audited warning if set)
- `synflux.enrich.analysis_model` (v1.1, default `synanton-analysis-mid`, resolved via ModelServingDirectory)
- `synflux.enrich.generation_model` (v1.1, default `synanton-analysis-mid`)
- `synflux.enrich.analysis_cache_ttl_days` (v1.1, default 90)
- `synflux.enrich.max_analysis_context_tokens` (v1.1, default 32000)
- `synflux.vision.enabled` (v1.1, default true)
- `synflux.vision.model_family` (v1.1, default `qwen2-vl-7b`)
- `synflux.vision.caption_cache_ttl_days` (v1.1, default 365)
- `synflux.ingest_cache.share_image_captions` (v1.1, default false; tenant opt-in)
- `synflux.degraded.embedding_fallback_model` (v1.17, default `all-MiniLM-L6-v2`)
- `synflux.degraded.cpu_max_queue_seconds` (v1.17, default 30)
- `synflux.degraded.disable_synthesis` (v1.17, default true; false only for chaos-test rigs)
- `synflux.router.strict_retention` (v1.17, default false; if true, refuse to start when Kafka `retention.ms < 30d`)
- `recrawl.batch_size` (v1.17, default 100)
- `recrawl.concurrent_tenants` (v1.17, default 4)
- `recrawl.schedule` (v1.17, `RESTORE_IMMEDIATE | MANUAL`, default `RESTORE_IMMEDIATE`)
- `recrawl.priority` (v1.17, `AGE_ASC | AGE_DESC | RECENCY_WEIGHTED`, default `RECENCY_WEIGHTED` - newer degraded docs first, on the assumption that they are more likely to be queried)

### Metrics
- `synflux_router_lag{target,tenant}`
- `synflux_router_tombstone_skipped_total`
- `synflux_router_retention_threatened`
- `synflux_embedder_gpu_ms_total{tenant,model}`
- `synflux_chunker_chunks_per_doc{tenant,strategy}`
- `synflux_enrich_analysis_gpu_ms_total{tenant,model}` *(v1.1)*
- `synflux_enrich_generation_gpu_ms_total{tenant,model}` *(v1.1)*
- `synflux_enrich_analysis_cache_hit_total{tenant}` *(v1.1)*
- `synflux_source_digest_cache_hit_total{tenant}` *(v1.1)*
- `synflux_vision_captions_total{tenant,cache}` *(v1.1)*
- `synflux_vision_dropped_total{tenant,reason}` *(v1.1)*
- `synflux_degraded_ingest_total{tenant, quality}` *(v1.17)*
- `synflux_degraded_recrawl_progress{tenant}` - 0..1 ratio, exported by the recrawl workflow *(v1.17)*
- `synflux_degraded_recrawl_duration_seconds{tenant}` - histogram, sample per completed content_ref_id *(v1.17)*
- `synflux_router_short_retention{topic}` - 1 when `retention.ms < 30d`, 0 otherwise *(v1.17)*

### Alerts
- `SynfluxRouterRetentionThreatened` (page) - lag/retention > 50 % over 15 min.
- `SynfluxRouterShortRetention` (warn) - `synflux_router_short_retention == 1` for > 5 min *(v1.17)*.
- `SynfluxEmbedderGpuSaturated` (warn) - vLLM queue p99 > 5 s.
- `SynfluxDegradedRecrawlStalled` (page) *(v1.17)* - `synflux_degraded_recrawl_progress` < 0.10 over 2 h for any tenant with `embedding_quality = DEGRADED` rows.

### Failure modes
- Cassandra write fails → no Kafka publish; client/source-system retry.
- Embed fails → DLQ to `synflux_dlq` with poison reason.
- Router crashes mid-dispatch → resumes from Kafka offset; idempotency key dedups.

### Catchup CLI
```
synctl synflux router catchup --target relix --from-manifest \
  --since 2026-06-20T00:00:00Z
```
Reads Cassandra manifest, replays missing dispatches to the named target.

------

## 18. Module: `ingestion-cache` (Cassandra / ScyllaDB)

### Role
Write-through artifact cache for chunks, embeddings, synthesis results, reranker reorderings.

### Tables

| Table | PK | Purpose | Notes |
|-------|----|----|------|
| `manifest` | `(tenant_id, content_ref_id)` | Per-content lifecycle | See §16 |
| `chunks` | `(tenant_id, content_ref_id, chunk_index)` | Chunk payloads | Truncated on tier move |
| `embedding_content_cache` | `(tenant_id, chunk_text_hash)` | Dense vectors | LRU + 30-day TTL, **no counters** |
| `reranker_cache` | `(tenant_id, query_hash, hit_id_hash, model)` | Reorderings | TTL 30 min |
| `synthesis_cache` | `(tenant_id, fingerprint, ontology_version, model)` | GraphRAG synthesis | Cross-tenant via ACL mask |
| `source_digests` | `(tenant_id, source_sha256)` | Skip re-ingest of identical sources *(v1.1)* | No TTL; evicted on source delete |
| `analysis_cache` | `(tenant_id, canonical_sha256, analysis_model)` | Two-step ingest Pass 1 output *(v1.1)* | TTL 90 days; retained beyond generation |
| `image_caption_cache` | `sha256(png_bytes)` *(optionally cross-tenant)* | Vision captions *(v1.1)* | TTL 365 days |

### Embedding cache vacuum
**Why no counters?** v1.7 design used Cassandra `counter` columns for `reference_count`. Counters drift under network partitions, causing premature evictions and GPU regeneration storms. Counters are abandoned.

**Vacuum design:**
1. LRU bumped via debounced `last_used_at` update (max once per 5 min per hash, async, fire-and-forget).
2. Nightly vacuum: full scan, throttled to 10 % of Cassandra IOps.
3. Vacuum builds a per-tenant Cuckoo Filter of active `chunk_text_hash` values from `manifest`.
4. Orphans (in `embedding_content_cache` but not in Cuckoo) are deleted - **zero-false-negative** by construction.
5. For tenants > 10M chunks, rotation schedule (weekly per partition).
6. Status surfaced via `synctl cache embedding vacuum status`.

### Per-tenant staggered vacuum *(v1.17)*

Motivation: on clusters with many tenants, a naive "nightly vacuum at 02:00 UTC" schedule concentrates a full-partition scan into a single window, producing IOPS spikes that degrade concurrent read/write performance and, in the worst case, cascade into `synflux` ingestion latency.

**Design.** Each tenant is assigned a **vacuum slot** computed at admission time:
```
slot_index          = xxhash64(tenant_id) mod ingestion_cache.vacuum.slots  -- default 96
slot_start_utc_hour = (slot_index * 24) / ingestion_cache.vacuum.slots
```
With 96 slots per day (default), each 15-minute window admits roughly `tenant_count / 96` tenants. Slot assignment is stable - the same tenant runs in the same slot every day - which keeps cache footprint predictable.

**Progress monitoring.**
- `vacuum_progress_ratio{tenant}` - 0..1, sampled every 5 min while the vacuum is running.
- `vacuum_last_completed_at{tenant}` - timestamp gauge.
- `vacuum_iops_pressure_ratio` - sampled Cassandra IOPS during vacuum vs baseline; > 1.3 pauses subsequent slots until it drops below 1.1.

**CLI:**
```
synctl cache embedding vacuum status                        # cluster-wide slot progress
synctl cache embedding vacuum status --tenant=<id>          # single-tenant view
synctl cache embedding vacuum run   --tenant=<id> --now     # manual, bypasses slot
synctl cache embedding vacuum pause --duration=1h           # global pause; auto-lifts after duration
synctl cache embedding vacuum move  --tenant=<id> --slot=42 # reassign a slot (audited)
```

**Configuration (new in v1.17):**
- `ingestion_cache.vacuum.slots` (default 96)
- `ingestion_cache.vacuum.max_iops_pressure_ratio` (default 1.3)
- `ingestion_cache.vacuum.concurrent_tenants_per_slot` (default 8)
- `ingestion_cache.vacuum.stall_after_seconds` (default 7200; fires `VacuumStalled` alert)

**Alerts:**
- `VacuumStalled` (warn) - a tenant's vacuum has been running for more than `stall_after_seconds` with `vacuum_progress_ratio` unchanged.
- `VacuumIopsPressureHigh` (warn) - `vacuum_iops_pressure_ratio > 1.3` for > 10 min.

### Cross-tenant synthesis cache
```
cache_key = sha256(
    QueryNormaliser.canonicalise(query, ACL_FIELD_SET) ||
    ontology_version ||
    vllm_model || locale ||
    max_acl_mask
)
```

`QueryNormaliser` strips a **published constant** `ACL_FIELD_SET` (org_id, space_id, project_id, group_id, folder_id). Fingerprint is invariant across ACL-injection rendering. Cache hit can serve across tenants if `caller_acl ⊆ cached.acl_mask`.

### Metrics
- `embedding_cache_vacuum_progress_ratio`
- `embedding_cache_orphans_deleted_total`
- `synthesis_cache_hit_total{cross_tenant_bool}`
- `reranker_cache_hit_total`
- `vacuum_progress_ratio{tenant}` *(v1.17)*
- `vacuum_last_completed_at{tenant}` *(v1.17)*
- `vacuum_iops_pressure_ratio` *(v1.17)*
- `cold_retrieval_triggered_total{tenant,mode}` - `mode ∈ {sync_wait, degraded_serve, background_rehydrate}` *(v1.17)*
- `cold_retrieval_cache_hit_total{tenant}` - served from 1 h rehydration cache *(v1.17)*

------

## 19. Module: `syntology` (Ontology Service)

### Role
Semantic schema resolver. Owns the entity/relation type system, ontology versions, and session pinning.

### Responsibilities
- Resolve `(label, version)` → entity type descriptor.
- Resolve `(predicate, version)` → relation type descriptor.
- Maintain ontology version history.
- Pin sessions to ontology versions for compatibility during rollout.

### Interfaces
- `OntologyService.resolveEntity(label, version) → EntityType`
- `OntologyService.bumpVersion() → new_version`
- `OntologyService.expirePinnedSessions(version)`
- `OntologyService.mergeEntities(source_id, target_id, approving_review_id)` *(v1.1)* - executed only after `synreview` approval; refuses ungated calls.
- `OntologyService.deprecateType(type_uri, reason)` *(v1.1)* - flags an ontology type as deprecated after ontology lint suggests it (no instances remain).

### Schema migration
Ontology mutations traverse the Relix SPI v1.0. `PatternCoverage` shows operators when `batch_refactoring` runs natively (APOC) vs emulated.

### Session pinning
When ontology version bumps, sessions opened under the old version see compatibility projections. New sessions use new version. Admins can force-terminate pinned sessions via `POST /admin/sessions/expire-pinned` during maintenance windows.

### Configuration
- `syntology.cache.entity_ttl_seconds` (default 600)
- `syntology.session_pin.max_age_hours` (default 24)

### Metrics
- `syntology_resolve_duration_seconds`
- `syntology_pinned_sessions_count{version}`

------

## 20. Module: `synquest` (Hybrid Search Kernel)

### Role
Hybrid lexical + semantic search engine. Written in Rust with Tantivy (BM25) and a SIMD-optimised HNSW index. Owns Cuckoo ACL filtering for HIGH_SECURITY tenants.

### Responsibilities
- BM25 lexical retrieval.
- HNSW dense semantic retrieval.
- Cuckoo ACL pre-filtering for HIGH_SECURITY.
- Per-shard region capability publishing.
- Recall sampling.
- Hot-shard rebalancing.
- Multilingual tokenisation, including CJK bigram fallback *(v1.1)*.
- Isolate third-party parser panics via `panic_guard` *(v1.1)*.

### Interfaces

**Inbound:**
- `IndexPort.upsert(chunk, idempotency_key)`
- `IndexPort.delete(content_ref_id)`
- `SearchPort.lexical(query, acl_clauses, top_k)`
- `SearchPort.semantic(query_vec, acl_clauses, top_k)`
- `CapabilityPort.descriptor() → ShardDescriptor{region, hnsw_params, …}`

**Outbound:** none (kernel is leaf).

### Cuckoo ACL filter
Replaces Bloom filters everywhere. O(1) atomic deletion - no rebuild on user revocation. HIGH_SECURITY tenants get synchronous Cuckoo updates on revocation; p99 filter-update latency < 300 ms SLO. Nightly rebuild keeps fragmentation ≤ 1 %.

### Region awareness
Every shard publishes `region` in its descriptor. The kernel does **not** enforce data residency - that is solely `planner`'s job (single-owner principle). Shards in disallowed regions are dropped by the planner before query dispatch.

### Recall monitoring
`RecallSampler` job samples shadow exhaustive search results vs. HNSW returns. `synquest_recall_below_slo` fires on 7-day rolling drop. Per-tenant `hnsw.ef_search` tuned by the `RecallTuner` Temporal workflow - never hand-edited.

### Hot-shard rebalancing
`synflux-rebalancer` sidecar runs everywhere (Full and Standalone). `HotShardDetector` triggers dual-write reshard.

**v1.16 baseline** used a "dual-write, single-read" model - writes went to both old and new shards during a 10-minute cooldown, and queries were expected to route to the new shard. In practice, this left ambiguous query routing during cooldown: which shard is authoritative for a document written 8 minutes ago? Answer depended on rebalancer timing rather than an explicit contract.

**v1.17 - Shard version routing.** Each shard carries an explicit `shard_version` integer, and every ingested chunk carries a `written_at_version` written by the router. The rebalance protocol becomes:

1. **Spin-up.** New shard is created with `shard_version = current + 1`. Both shards register with `synquest.SearchPort` capability descriptor advertising their version.
2. **Write cut-over.** Router flips write dispatch to the new shard **only**. Old shard receives no new writes from this instant. `written_at_version` on all new chunks is `current + 1`.
3. **Cooldown (default 10 min).** Reads fan out to both shards; results are merged at the kernel level. The kernel de-duplicates by `content_ref_id` preferring the higher `written_at_version` when both shards return a hit (which is normal for chunks upserted just before the cut-over - the old shard has the pre-cutover copy, the new shard has the post-cutover copy).
4. **Drain.** Rebalancer copies remaining documents from old → new shard. Progress emitted as `synquest_shard_drain_progress_ratio{from_version,to_version}`.
5. **Retire.** When drain reaches 100 % and no query has hit the old shard for `synquest.shard.retire_idle_seconds` (default 60), old shard is dropped. No per-range tombstones.

**Query request carries an explicit `shard_version_min`** (optional, default null). If the caller cached results at `version=7` and wants monotone reads, it can pass `shard_version_min=7` and any shard on `version < 7` is excluded from the fan-out. This is the primitive used by session-consistent MCP tools.

**Failure modes:**
- New shard cannot be created (capacity, permissions) → rebalance aborts; old shard continues serving reads and writes; alert.
- Drain stalls (progress unchanged for > 15 min) → alert `HotShardDrainStalled`; operator can extend cooldown or roll back with `synctl synquest rebalance abort --shard=<id>`.
- Reads during cooldown timing out on one shard → returned results merged from the other, with `X-Synanton-Warning: partial-shard-read` header.

### Supernode sampling
Pre-filters above cardinality cap invoke `TOP_K_RELEVANCE_SAMPLING`:
- Only edges whose target appears in top-k chunks are traversed.
- Supernode included as anchor even if no relevant edges exist.
- `synquest_supernode_truncation_total{tenant,label}` surfaces every truncation.

### Multilingual tokenisation *(v1.1)*

Tantivy field analysers are configured per language family with an explicit fallback chain:
- **Latin / general.** Standard tokeniser + Unicode stop-word list + optional stemming.
- **CJK.** `CJKBigramTokenizer` emits overlapping character bigrams - no dictionary dependency, no per-language configuration.
- **Auto-detect.** A lightweight language detector runs at index time; the emitted `analyzer_id` is stored on the chunk so query-time tokenisation matches.

Query strings pass through the same tokenisation pipeline as indexed text. Cross-lingual queries (e.g. Latin query text over CJK corpus) fall back to bigram matching on the CJK portion.

Configuration extends per-tenant, since customers in EU / Asia-Pacific often need multiple language handlers active simultaneously.

### Rust panic guard *(v1.1)*

Every third-party parser call (Tantivy document ingestion, format-specific extractors, any FFI boundary) is wrapped in `panic_guard::run_guarded`:

```rust
pub fn run_guarded<T>(name: &str, f: impl FnOnce() -> Result<T, String>) -> Result<T, String> {
    std::panic::catch_unwind(AssertUnwindSafe(|| f()))
        .unwrap_or_else(|_| Err(format!("{name} panicked")))
}
```

Purpose: a single malformed or malicious document must not crash the search kernel. Panics are converted to `Err(String)` and emit a structured log event with `(document_id, tenant_id, guard_name, error_summary)` for forensic analysis. The offending document is DLQ'd with `poison_reason = PARSER_PANIC`.

Motivation: in a multi-tenant deployment, one crashed process denies service to every tenant sharing that shard replica. The guard is a one-file, one-function mitigation.

### Configuration
- `synquest.hnsw.ef_search` (per-tenant tier)
- `synquest.hnsw.m` (default 16)
- `synquest.cuckoo.bucket_size` (default 4)
- `synquest.shard.region` (per-shard)
- `synquest.recall.sample_size_per_day` (default 1000)
- `synquest.tokenizer.default_analyzer` (v1.1, default `standard`)
- `synquest.tokenizer.cjk.enabled` (v1.1, default true)
- `synquest.tokenizer.auto_detect_language` (v1.1, default true)
- `synquest.panic_guard.enabled` (v1.1, default true; disabling requires config override + audit event)
- `synquest.shard.cooldown_seconds` (v1.17, default 600)
- `synquest.shard.retire_idle_seconds` (v1.17, default 60)
- `synquest.shard.drain_stall_alert_seconds` (v1.17, default 900)
- `synquest.shard.merge_dedup_prefer_higher_version` (v1.17, default true)

### Metrics
- `synquest_recall_below_slo`
- `synquest_supernode_truncation_total{tenant,label}`
- `synquest_cuckoo_update_latency_p99`
- `synquest_hot_shard_split_total`
- `synquest_panic_guard_triggered_total{guard_name,tenant}` *(v1.1)*
- `synquest_tokenizer_language_distribution{tenant,analyzer}` *(v1.1)*
- `synquest_shard_drain_progress_ratio{from_version,to_version}` *(v1.17)*
- `synquest_shard_dedup_hit_total{shard_id}` *(v1.17)* - reads that returned the same content_ref_id from both shards during cooldown
- `synquest_shard_version_stale_reads_total` *(v1.17)* - reads excluded by `shard_version_min`

------

## 21. Module: `relix` (GraphRAG Engine + MCP/ACP)

### Role
GraphRAG engine, MCP/ACP server, and host for the Relix Graph Connector SPI v1.0.

### Responsibilities
- Map chunks → graph entities via `syntology`.
- Execute ISO GQL queries via SPI.
- Maintain Materialized Graph Views with incremental refresh.
- Run bounded emulated traversal for `EMULATED` patterns.
- Serve as MCP tool surface and ACP agent endpoint.
- Synthesize answers using vLLM.
- Compute and publish **multi-signal edge relevance** on every edge upsert *(v1.1)*.
- Run **Louvain community detection** as a nightly background job, publish `community_id` on nodes *(v1.1)*.
- Maintain per-entity **source reference counts** so GDPR cascade can distinguish "source-only" from "entity" deletion *(v1.1)*.

### Interfaces

**Inbound (control):**
- `RelixAdmin.registerConnector(connector_id, config)`
- `RelixAdmin.probeCoverage(connector_id) → PatternCoverage`

**Inbound (data):**
- `RelixQuery.graphRag(query, acl_clauses, params) → SynthesisResult`
- `RelixQuery.subgraph(seed_entity_ids, depth, filters) → Subgraph`

**Outbound (SPI v1.0):**
- `GraphConnectorService.ExecuteGraphQuery`
- `GraphConnectorService.ExecuteBulkMutation`
- `GraphConnectorService.GetEngineDescriptor`

### Pluggable connectors
First-party: `Neo4jConnector` (Cypher/Bolt), `NeptuneConnector` (Gremlin/TinkerPop), `InMemoryConnector` (heap-resident, for tests and embedded). Third-party connectors implement the SPI directly; no compatibility mode.

### Continuous probing
`control-plane` polls each connector's `GetEngineDescriptor` every 60 s. Coverage cache staleness bounded to 90 s. Planner reads watched cache.

### Cost calibration
Each connector self-measures and emits `ConnectorCostProfile{p50_ns, p99_ns}` per `(pattern, payload_size)` bin. Planner uses *real* numbers, not nominal `NATIVE × 1.0 / FALLBACK × 2.0 / EMULATED × 8.0` ratios. Calibration drifts with workload; planner adapts.

### Internal Representation
`SemanticReporter` emits abstract ISO/IEC 39075 GQL. Connectors translate through cached ANTLR ASTs per `(gql_hash, connector_id)`. Translation overhead benchmarked < 2 %; regression breaks CI.

### Materialized Graph Views (MGV)
```sql
CREATE MATERIALIZED VIEW org_hierarchy AS
MATCH (org:Organization)-[:HAS_CHILD*1..5]->(child)
RETURN org, child
WITH REFRESH INTERVAL 1 MINUTE;
```

Refresh planner computes deltas from every mutation; delta applies to dedicated view storage (separate Neo4j keyspace or Redis cache). Queries referencing the view served directly. **Fallback:** if MGV lag > `max_allowed_lag_ms`, planner transparently routes to live traversal.

### Bounded Emulated Traversal
For `EMULATED` patterns, `relix` runs BFS with **level batching** - one GQL call per depth using `IN ($frontier_ids)`. Per-traversal `emulated_total_timeout` defaults 4 s. Partial results tagged in response; caller policy decides error-vs-partial.

### Multi-signal edge relevance *(v1.1)*

Every edge upserted through the SPI carries a composite `edge_relevance` computed from four weighted signals, plus per-signal scores retained for downstream analysis. The signal set is fixed; the weights are tenant-tunable via `topology`.

| Signal | Default weight | Definition |
|--------|----------------|------------|
| Direct link (`direct_link`) | ×3.0 | Explicit entity-to-entity link stated in source (e.g. wikilinks, RDF triples, structured JSON refs) |
| Source overlap (`source_overlap`) | ×4.0 | Two entities share ≥ 1 source `content_ref_id` - highest-quality signal, prioritised in initial topology scoring |
| Adamic-Adar proxy (`co_occurrence`) | ×1.5 | Common neighbours weighted by inverse degree; in practice: co-occurrence in the same enriched chunk |
| Type affinity (`type_affinity`) | ×1.0 | Same `rdfs:type` / `owl:Class` in `syntology`; modest bonus |

`edge_relevance = Σ signal_i × weight_i`.  Weights are surfaced through the SPI `EngineDescriptor.edge_signal_weights` map. The MCP tools `synanton.relix.subgraph` and `synanton.relix.graph_rag` accept an optional `edge_signal_filter` to restrict traversal (e.g. "only follow `source_overlap` edges above 0.4"). This lets agents reason about relationship strength.

### Louvain community detection *(v1.1)*

A nightly `LouvainCommunityJob` (Temporal workflow, hosted in `control-plane`, dispatches into `relix`) runs the Louvain modularity-optimising algorithm over the full entity graph. Alternative: Leiden - configurable via `relix.community.algorithm`.

Outputs:
- `community_id` written onto every entity node (updated in place; no schema break).
- `community_cohesion` (intra-community edge density) computed per community.
- Communities with `cohesion < 0.15` and `size ≥ 3` are flagged as knowledge gaps and emitted to `control-plane.graph-insights` (§27) for the Admin Console.

Uses:
- **MGV refresh scoping.** A mutation that touches nodes in community `C` triggers MGV refresh only within `C` plus edges bridging to adjacent communities. Cuts refresh cost roughly proportional to `size(C) / size(graph)`.
- **Search filter.** `community_id` is projected into the `synquest` index as a filter field to enable "search within this cluster" queries.
- **Insight surfacing.** Low-cohesion communities and bridge nodes are advisory signals in the Admin Console (§27).

### Entity source reference counting *(v1.1; CAS in v1.17)*

Each entity node carries `source_ref_count` - the number of distinct source `content_ref_id`s that assert its existence. Maintained atomically via SPI on upsert / delete_edge operations.

Purpose: GDPR cascade (§10) must distinguish two cases:
- **Reference decrement.** The deleted source contributed one of N ≥ 2 references - only the source reference is removed, entity remains.
- **Full deletion.** The deleted source contributed the sole reference - entity is fully deleted.

The counter is authoritative in `relix`; the GDPR workflow reads it before deciding cascade behaviour.

**v1.17 - Compare-and-Swap decrement.** The v1.16 flow was:
```
1. read source_ref_count
2. decide (decrement-only vs full-delete)
3. write
```
Two concurrent cascades targeting different sources of the same entity could interleave between steps 1 and 3 and both decide "full-delete" or both decide "decrement-only", producing either double-deletion or an orphaned entity.

v1.17 makes the decrement a CAS in a single graph-connector statement:

```sql
-- Postgres-flavour illustration (see §28 SPI for the connector-side contract)
UPDATE relix.entity
   SET source_ref_count = source_ref_count - 1,
       sources = array_remove(sources, :content_ref_id)
 WHERE entity_id = :entity_id
   AND :content_ref_id = ANY(sources)
   AND source_ref_count > 0
RETURNING source_ref_count AS new_count;
```

The CAS is `NATIVE` for Neo4j (via `SET e.sources = [s IN e.sources WHERE s <> $ref]`), Neptune Gremlin (via `not(within('sources'))` guarded property update), and any RDBMS-backed connector. Connectors that cannot express CAS on properties must implement it as a `MERGE`-based emulation and declare `EMULATED` in their `PatternCoverage` for the `cas_property_update` feature.

Behaviour by CAS outcome:
- `new_count = null` (0 rows returned) → another cascade already handled this (source_ref_id, entity_id) pair. No-op; increment `relix_cascade_cas_noop_total`.
- `new_count > 0` → remove only this source's reference edge; entity remains.
- `new_count = 0` → transactionally follow up with `DETACH DELETE` for the entity in the **same transaction** as the CAS. This keeps the invariant "count = 0 iff entity is gone" strict.

Zero-cost invariant: no counter drift possible because writes happen inside the graph-connector transaction alongside the edge delete, and concurrent writers cannot race past the CAS.

### MCP/ACP
- MCP tools prefixed `synanton.relix.*` (search, semantic_reporter, ontology, materialised_views).
- ACP endpoints expose agent-to-agent contracts for multi-agent orchestration.
- Synchronous MCP calls that would route to SemanticReporter return `ERR_REQUIRES_STREAMING`.

### Configuration
- `relix.subgraph.cypher_timeout_ms` (default 2000)
- `relix.subgraph.emulated_total_timeout_ms` (default 4000)
- `relix.subgraph.max_subgraph_nodes` (default 5000, tunable per tenant)
- `relix.mgv.max_allowed_lag_ms` (default 200)
- `relix.mcp.session.revalidation_interval_minutes` (default 15)
- `relix.edge_signal.weight.direct_link` (v1.1, default 3.0)
- `relix.edge_signal.weight.source_overlap` (v1.1, default 4.0)
- `relix.edge_signal.weight.co_occurrence` (v1.1, default 1.5)
- `relix.edge_signal.weight.type_affinity` (v1.1, default 1.0)
- `relix.community.algorithm` (v1.1, `LOUVAIN | LEIDEN`, default `LOUVAIN`)
- `relix.community.min_cohesion` (v1.1, default 0.15)
- `relix.community.job_cron` (v1.1, default `0 2 * * *`)

### Metrics
- `relix_emulated_duration_seconds`
- `relix_emulated_partial_results_total`
- `relix_materialized_view_lag_seconds`
- `relix_supernode_truncation_total{tenant,label}`
- `relix_graph_node_deleted_total`
- `relix_connector_cost_p99_ns{connector,pattern}`
- `relix_community_job_duration_seconds{algorithm}` *(v1.1)*
- `relix_community_count{tenant}` *(v1.1)*
- `relix_low_cohesion_communities_total{tenant}` *(v1.1)*
- `relix_source_ref_decremented_total{tenant}` *(v1.1)*
- `relix_entity_deleted_total{tenant}` *(v1.1)*
- `relix_cascade_cas_noop_total{tenant}` *(v1.17)* - concurrent-cascade collision safely absorbed by CAS
- `relix_cascade_cas_retry_total{tenant}` *(v1.17)* - CAS retries under connector-level abort (should be near-zero)

### Alerts
- `RelixEmulatedFallbackHigh` - > 5 % GraphRAG queries emulated / 15 min.
- `RelixMgvLagHigh` - MGV lag > `max_allowed_lag_ms × 5`.

------

## 22. Module: `planner` (Search Planner)

### Role
Translate `SearchQuery` into an `ExecutionPlan` DAG, enforce residency, integrate anomaly hints, and dispatch to executors.

### Responsibilities
- Decompose query into lexical + semantic + graph legs (canonical 4-phase model, §7).
- Read `ConnectorCostProfile` from continuous probing cache.
- Enforce `data_residency_policy` (query-level overrides tenant default).
- Insert rerank step per policy.
- Probe `AnomalyDetectorPort` for slow-query inflation.
- Apply high-cardinality reorder rule.
- Compute per-query `ContextBudget` and dispatch trimmed candidate set to fusion / rerank / synthesis stages *(v1.1)*.
- Merge `synquest` and `relix` result sets via Reciprocal Rank Fusion using unified `CandidateScore` *(v1.1)*.

### Interfaces

**Inbound:**
- `PlannerPort.plan(SearchQuery) → ExecutionPlan`
- `PlannerPort.execute(ExecutionPlan) → ResultSet`

**Outbound:**
- `synquest.SearchPort.lexical/semantic`
- `relix.RelixQuery.subgraph`
- `gateway.AnomalyDetectorPort.probe`
- `gateway.RerankerPort.rerank`

### Algorithms

**Cost estimation:**
```
estimated_gpu_ms = sum(per-leg gpu_ms)
                 + (rerank ? rerank.gpu_ms : 0)
                 + cross_region_penalty(leg.local_region, leg.target_region)
estimated_cross_region_bytes = sum(legs with foreign region)
```

**Cross-region penalty (v1.17).** v1.16 used a single scalar `planner.cross_region_penalty_ms` (default 50 ms) for every foreign-region hop. This under-weighted transatlantic and transpacific paths (US-East ↔ EU-West is typically 90-110 ms one-way; US-West ↔ AP-Southeast is 150-200 ms) and over-weighted intra-continental hops (US-East ↔ US-West is 60-70 ms).

v1.17 introduces a per-region-pair map, sourced from `topology.tenant_policy.cross_region_penalty_ms JSONB`:
```json
{
  "us-east-1": { "us-west-2": 60, "eu-west-1": 90,  "ap-southeast-1": 210 },
  "us-west-2": { "us-east-1": 60, "eu-west-1": 145, "ap-southeast-1": 130 },
  "eu-west-1": { "us-east-1": 90, "us-west-2": 145, "ap-southeast-1": 175 },
  "ap-southeast-1": { "us-east-1": 210, "us-west-2": 130, "eu-west-1": 175 }
}
```

Resolution algorithm:
```
penalty(local, target) =
    tenant.cross_region_penalty_ms[local][target]
      ?? platform_default.cross_region_penalty_ms[local][target]
      ?? planner.cross_region_penalty_ms_scalar   // 50, v1.16 compatibility fallback
```

The **platform default** map is bootstrapped from continuous p95 RTT measurements between `synquest` shards, refreshed hourly by `control-plane` (see `ModelHealthProber` in §27; the same prober collects region-to-region ping data). Operators can override per tenant for measured-in-production tuning or for regulatory scenarios where a tenant's traffic is routed through a specific set of intermediate PoPs.

**Follow-the-sun serving.** `ModelServingDirectory` (§27) replicates model families to regions that carry active tenant traffic during their business hours. Planner reads the model replicas list, picks the cheapest-penalty replica meeting residency constraints, and - for stateless inference (embedding, reranking) - is free to switch replicas mid-shift. Stateful synthesis sessions that span multiple LLM turns stay pinned to their initial replica for the duration of the session to avoid KV-cache invalidation. This is enforced by the `synanton-llm-client` `SessionAffinity` header (see §27c).

**Residency enforcement:**
```
effective_policy = query.residency.override OR tenant.data_residency_policy
for leg in plan:
  if leg.shard.region NOT IN effective_policy.allowed_regions:
    if effective_policy.fail_closed:
      return ERR_DATA_RESIDENCY_VIOLATION
    else:
      drop leg; add warning
```

**High-cardinality reorder:**
```
if ACL Must clauses have estimated cardinality > 1M:
  reorder plan to apply Must clauses first
  (avoids HNSW pre-filter against millions of docs)
```

**Context budget allocation** *(v1.1):*

Per-query token budget is divided proportionally by concern, keyed off the resolved `intent_type`:

| intent_type | retrieved_chunks | graph_expansion | history | system | free |
|-------------|------------------|-----------------|---------|--------|------|
| `LOOKUP`    | 70%              | 5%              | 5%      | 15%    | 5%   |
| `SYNTHESIS` | 55%              | 15%             | 15%     | 10%    | 5%   |
| `RESEARCH`  | 40%              | 25%             | 10%     | 15%    | 10%  |

Total budget resolved from tenant tier: `STANDARD` = 32K, `HIGH_SECURITY` = 16K (default; overridable per-tenant `budget_policy.max_context_tokens`). Budget can be raised per-request up to a hard cap (`planner.context_budget.hard_cap_tokens`, default 1M).

Selected candidates ranked by `combined_score = w_lex·lex + w_sem·sem + w_graph·graph` (weights per intent). Truncation warnings appear in the response `warnings[]` and are logged as `planner_budget_truncated_total{tenant,intent,phase}`.

`context_tokens_used` is appended to the `api_usage` cost event alongside the existing `embedder_gpu_ms`, `synthesis_gpu_ms`, `reranker_gpu_ms` fields.

**Reciprocal Rank Fusion** *(v1.1):*

RRF is the single canonical fusion function. It runs at the planner in one place - no per-service fusion. Formal:
```
score(doc) = Σ_leg 1 / (k + rank_leg(doc))
```
`k` defaults to 60. Each leg contributes its own rank without needing normalised scores. Ties broken by `combined_score` (weighted linear).

### Configuration
- `planner.cross_region_penalty_ms_scalar` (default 50; v1.16-compat fallback when no map is available)
- `planner.cross_region_penalty_ms_map_refresh_seconds` (v1.17, default 3600)
- `planner.follow_the_sun.enabled` (v1.17, default true - read replicas list from ModelServingDirectory)
- `planner.follow_the_sun.session_affinity_ttl_seconds` (v1.17, default 900 - keep stateful synthesis sessions pinned to their initial replica)
- `planner.high_cardinality_threshold` (default 1_000_000)
- `planner.anomaly_inflation_ms` (default 2000)
- `planner.context_budget.default_lookup_tokens` (v1.1, default 32000)
- `planner.context_budget.default_synthesis_tokens` (v1.1, default 32000)
- `planner.context_budget.default_research_tokens` (v1.1, default 128000)
- `planner.context_budget.hard_cap_tokens` (v1.1, default 1_048_576)
- `planner.rrf.k` (v1.1, default 60)
- `planner.rrf.weights.lexical` (v1.1, default 1.0)
- `planner.rrf.weights.semantic` (v1.1, default 1.0)
- `planner.rrf.weights.graph` (v1.1, default 0.8)

### Metrics
- `planner_residency_filtered_total{tenant}`
- `planner_cost_estimate_gpu_ms_histogram`
- `planner_anomaly_inflation_total`
- `planner_budget_truncated_total{tenant,intent,phase}` *(v1.1)*
- `planner_context_tokens_used{tenant,intent}` histogram *(v1.1)*
- `planner_rrf_fusion_leg_count` histogram *(v1.1)*

------

## 23. Module: `gateway` (Query Gateway)

### Role
Intent translation, result formatting, session management, reranker invocation, ACL injection, cross-tenant cache routing, anomaly streaming, LLM-context sanitisation, runtime memory protection.

### Responsibilities
- Translate natural-language intent → `SearchQuery` DSL.
- Inject ACL clauses at compile time.
- Consult `synthesis_cache` with ACL-mask logic.
- Invoke reranker per policy.
- Sanitise `llmContext.customMetadata`.
- Enforce runtime memory limits.
- Stream anomaly telemetry.
- Classify query intent (`LOOKUP | SYNTHESIS | RESEARCH`) and attach `ContextBudget` for the planner *(v1.1)*.
- Enforce final context assembly under budget, emit truncation warnings *(v1.1)*.

### Compile-time ACL injection
ACLs emitted as `Must` / `TermFilter` clauses during translation. Planner sees their cardinality and applies the high-cardinality reorder rule. Final-trim at gateway is defence-in-depth.

### Reranker
Implements `RerankerPort`. Adapters at GA:
- `VllmCrossEncoderRerankAdapter` (first-party, `bge-reranker-v2`)
- `CohereRerankAdapter`
- `VoyageRerankAdapter`

Selection per tenant `rerank_policy = {model, policy, candidate_pool_size, top_n}`.
- `policy = ALWAYS | SCORE_GAP_TRIGGERED | CALLER_REQUESTED`

Outbound credentials via `OutboundAuthBroker` (RFC 8693). **Failure path:** return un-reranked hits, increment `gateway_reranker_fallback_total`, set warning header.

Result cache: 30 min TTL, keyed by `(query_text, hit_ids, model, ontology_version, locale)`. Invalidated by `ContentEvent` overlap on hit IDs.

### Cross-tenant cache router
1. Miss → compute, store with `acl_mask = intersection(caller_acl, source_doc_acls)`.
2. Hit → check `caller_acl ⊆ cached.acl_mask`. Serve if true; recompute if false.
3. `ContentEvent` for any source doc → invalidate `(source_subgraph_refs ∋ content_ref_id)`.

### Runtime memory protection
- Lazy DuckDB materialisation; `cache_lookup` UDF carries `AtomicLong` row counter.
- > 10 000 gRPC fan-out without admin scope → UDF cancels query with `ERR_HIGH_CARDINALITY_EXCEEDED`.
- Soft `maxHits` (default 10 000) signals cancellation.
- Hard breaker at `maxHits × 1.5` throws `HardResourceExceededException`.

### LLM-context sanitisation
- Keys: `[a-zA-Z0-9_-]{1,64}`.
- Values: ≤ 256 chars.
- Public-API (synapt): pre-approved key set only.
- `systemPromptOverrides` injection attempts rejected and audited.

### Intent classification & context budget *(v1.1)*

The gateway classifies each query into one of three intent types before invoking the planner. Classification uses a fast local model (`intent-classifier-small`, resolved via ModelServingDirectory) with fallback rules on latency budget breach.

| Intent | Definition | Default budget |
|--------|------------|----------------|
| `LOOKUP` | Point-answer or single-document retrieval | `planner.context_budget.default_lookup_tokens` |
| `SYNTHESIS` | Multi-document reasoning; GraphRAG likely | `planner.context_budget.default_synthesis_tokens` |
| `RESEARCH` | Cross-corpus exploration; Deep Research eligible | `planner.context_budget.default_research_tokens` |

The budget is attached to the plan as a `ContextBudget { total_tokens, allocation_by_concern }` message and enforced at the planner (§22). Gateway performs the final assembly-time check: if trimmed chunks + graph expansion + history + system prompt exceeds `total_tokens`, the lowest-scoring chunk is dropped and a `context-budget-truncated` warning is added to the response.

Cost attribution: `context_tokens_used` field is added to the `api_usage` Avro event.

### Anomaly streaming
Streams `{tenant, endpoint, latency_ms, hit_count, gql_patterns, errors, residency_filtered_count}` per query to `synanton_anomaly` Kafka topic.

### Cold-tier rehydration for synthesis *(v1.17)*

When a synthesis query touches at least one chunk stored in a cold tier, the gateway follows the flow documented in §9:

- Detect cold chunks after Phase 3 (budget control) by inspecting `chunk.storage_tier`.
- Consult `cold_rehydration_cache` (Redis; §38); serve immediately if all cold chunks are present.
- Otherwise call `synvault.rehydrateAsync(content_ref_id)` for each missing chunk and wait up to `gateway.cold_wait_ms`. Rehydrated content populates the cache with 1 h TTL.
- On timeout: serve using `manifest.abstract_text`, set `X-Synanton-Cold-Rehydration: degraded`, and skip persisting the synthesis result to `synthesis_cache`.
- Emit `cold_retrieval_triggered_total{tenant, mode}` for each request that engages the rehydration path.

This is a **synthesis-only** path - hot-tier synthesis and LOOKUP-intent queries are unaffected.

### Configuration
- `gateway.maxHits` (default 10000)
- `gateway.hard_breaker_multiplier` (default 1.5)
- `gateway.synthesis_cache.ttl_seconds` (default 3600)
- `gateway.rerank.cache_ttl_seconds` (default 1800)
- `gateway.intent.model_family` (v1.1, default `intent-classifier-small`)
- `gateway.intent.classification_timeout_ms` (v1.1, default 60; falls back to rule-based on breach)
- `gateway.cold_wait_ms` (v1.17, default 8000 - max time to wait for cold rehydration before degraded fallback)
- `gateway.cold_rehydration_cache_max_mb` (v1.17, default 512 - per-tenant)
- `gateway.cold_rehydration_backoff_seconds` (v1.17, default 300)
- `gateway.cold_degraded_use_abstract` (v1.17, default true; if false, cold miss returns `ERR_COLD_SYNTHESIS_UNAVAILABLE`)

### Metrics
- `gateway_reranker_gpu_ms_total{tenant,model}`
- `gateway_reranker_fallback_total{adapter,reason}`
- `gateway_cross_tenant_cache_hit_total`
- `gateway_query_runtime_cancelled_total`
- `gateway_llm_context_rejected_total{reason}`
- `gateway_intent_classification_total{tenant,intent,source}` *(v1.1)* - source ∈ `MODEL | RULE_FALLBACK`
- `gateway_context_budget_truncated_total{tenant,intent}` *(v1.1)*
- `cold_retrieval_triggered_total{tenant,mode}` *(v1.17)* - `mode ∈ {sync_wait, degraded_serve, background_rehydrate}`
- `gateway_cold_synthesis_degraded_total{tenant}` *(v1.17)*
- `gateway_degraded_mode_active` - mirrors §27 for locality *(v1.17)*

### Alerts
- `RerankerFallbackHigh` - > 1 % rerank requests fall back / 15 min.
- `ColdSynthesisDegradedRateHigh` *(v1.17)* - > 5 % of synthesis queries take the degraded path for > 30 min (indicates Glacier throughput problem or misplaced tiering policy).

### GPU Execution Plane additions *(v1.20)*


> **[v1.20]** The gateway module is extended to include the GPU execution client. The v1.19 gateway specification is unchanged for all non-GPU paths.

The gateway module acquires a new internal component: **GPU Execution Client**. When the execution plan produced by `planner` requires GPU synthesis, embedding, or reranking, the gateway dispatches the operation to the GPU Execution Plane via `synanton.gpu.v1.GPUExecutionService.Execute()` over mTLS, rather than calling a GPU runtime adapter directly.

**New gateway responsibilities:**
- Resolve logical model endpoint from `ModelServingDirectory`.
- Construct the `ExecutionRequest` (providing `request_id`; the GPU Gateway generates `execution_id`).
- Handle `MODEL_NOT_READY` with exponential backoff and jitter.
- On `Execute()` timeout or stream close, call `GetStatus(execution_id)` to reconcile the outcome.
- Apply degraded-mode policy when GPU execution is unavailable (fall back to CPU path, return partial result, or fail).
- Propagate trace context across the cluster boundary.

**Config keys added:**

| Key | Default | Description |
|-----|---------|-------------|
| `gateway.gpu.enabled` | `false` | Enables GPU execution client; falls back to CPU path when false |
| `gateway.gpu.endpoint` | - | GPU Gateway address (host:port) |
| `gateway.gpu.tls.cert-path` | - | Client mTLS certificate path |
| `gateway.gpu.tls.key-path` | - | Client mTLS key path |
| `gateway.gpu.tls.ca-path` | - | CA certificate for server validation |
| `gateway.gpu.timeout-ms` | `120000` | Execute() deadline; after expiry, GetStatus() is called |
| `gateway.gpu.retry.max-attempts` | `3` | Max retry attempts for MODEL_NOT_READY |
| `gateway.gpu.retry.backoff-base-ms` | `500` | Base backoff for MODEL_NOT_READY retries |

------

## 24. Module: `synapt` (Public API)

### Role
Definitive REST/gRPC ingress for external clients. **One contract, one shape, forever.**

### Responsibilities
- Validate JWT/API key.
- Resolve `(tenant_id, user_subject)`.
- Forward to `gateway`.
- Format response (REST or gRPC).
- Emit `api_usage` event.
- Enforce per-tenant budget caps with HTTP 429.

### Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/search` | POST | Hybrid + GraphRAG search |
| `/ingest` | POST | Submit content for ingestion |
| `/admin/tenants/*` | GET/PUT | Tenant-scoped admin (via control-plane proxy) |
| `/admin/_internal/status` *(v1.19)* | GET | Aggregated cluster health for `helper` (§26b) |
| `/admin/_internal/bundle` *(v1.19)* | POST | Generate support diagnostic tarball (pre-signed S3 URL) |
| `/admin/_internal/clean` *(v1.19)* | POST | Purge caches / orphan chunks (dry-run supported) |
| `/admin/_internal/delete` *(v1.19)* | POST | Destructive content/tenant deletion (gated by `confirm: "I_AM_SURE"`) |
| `/admin/_internal/recrawl` *(v1.19)* | POST | Start / pause / resume tenant recrawl workflow |
| `/admin/_internal/recrawl/{tenant}` *(v1.19)* | GET | Recrawl progress for tenant |
| `/admin/_internal/workflow/cancel` *(v1.19)* | POST | Cancel Temporal workflow by id |
| `/admin/_internal/workflow/retry` *(v1.19)* | POST | Retry failed Temporal workflow by id |

### Internal admin routes *(v1.19)*

The `/admin/_internal/*` route family is **not part of the public API contract**. It is reserved for the `helper` CLI (see §26b) and is only reachable by service principals carrying the `support_admin` role (§26). Every call:

- Requires `Authorization: Bearer <SYNANTON_SUPPORT_KEY>` and passes the same JWT/API key path as the public surface.
- Rejects any principal that lacks the `support_admin` role with `403 Forbidden` (audited).
- Traverses the same circuit breakers, timeouts, idempotency keys, and per-tenant scoping as production traffic - there is no "back door" that bypasses ACL or tenant isolation.
- Emits an `admin_audit` row before the state-changing operation completes (with `before_state_hash` and `after_state_hash` columns populated for `clean`, `delete`, `recrawl`).
- Increments `helper_operation_total{command, tenant, outcome}` (see §45).

Destructive endpoints (`/admin/_internal/delete`, `/admin/_internal/clean` when `dry_run = false`) require an additional `confirm: "I_AM_SURE"` request-body field. The controller rejects the call with `400 Bad Request` if the field is missing.

The handlers reuse the existing internal service clients (`IngestionCacheClient`, `TopologyClient`, `WorkflowClient`) - no new data-plane code paths are introduced.

**OpenAPI snippet (illustrative).**

```yaml
# GET /admin/_internal/status
paths:
  /admin/_internal/status:
    get:
      security: [ { SupportAdminApiKey: [] } ]
      responses:
        '200':
          content:
            application/json:
              schema:
                type: object
                properties:
                  cluster_health: { type: string, enum: [HEALTHY, DEGRADED, UNHEALTHY] }
                  modules:
                    type: array
                    items:
                      type: object
                      properties:
                        id:      { type: string }
                        status:  { type: string, enum: [UP, DEGRADED, DOWN] }
                        version: { type: string }
                  storage_backends:
                    type: object
                    additionalProperties: { type: string }
                  degraded_mode:   { type: boolean }
                  model_health:
                    type: object
                    additionalProperties: { type: string, enum: [HEALTHY, DEGRADED, UNAVAILABLE] }

# POST /admin/_internal/clean
  /admin/_internal/clean:
    post:
      security: [ { SupportAdminApiKey: [] } ]
      requestBody:
        content:
          application/json:
            schema:
              type: object
              required: [ resource ]
              properties:
                tenant:  { type: string }
                resource: { type: string, enum: [embedding_cache, synthesis_cache, orphan_chunks, tenant_caches] }
                dry_run:  { type: boolean, default: true }
                confirm:  { type: string, description: "must be 'I_AM_SURE' when dry_run=false" }
      responses:
        '200':
          content:
            application/json:
              schema:
                type: object
                properties:
                  affected_rows: { type: integer }
                  dry_run_results:
                    type: array
                    items:
                      type: object
                      properties:
                        table:  { type: string }
                        key:    { type: string }
                        action: { type: string }
```

**Never callable by public clients.** Kubernetes `NetworkPolicy` on the `synapt` deployment restricts `/admin/_internal/*` egress to the operator-VPN CIDR block and the CI/support jump host. External ingress paths (Cloudflare, ALB) explicitly deny the path prefix.

### Unified API surface
No `/v1`, no `/v2`, no `Sunset` headers. Evolution is additive only, governed by `BACKWARD_TRANSITIVE` Avro/JSON Schema compatibility on Kafka topics and JSON Schema on the public surface.

### API deprecation policy *(v1.17)*

Although the surface never versions, individual **fields** can be marked deprecated and eventually removed. v1.17 fixes the removal cadence so integrators have a predictable planning window.

**Lifecycle.**

1. **Mark.** A field is annotated `@deprecated(since="1.17", removal_earliest="1.20", replacement="context_budget.retrieved_chunks_tokens")` in the JSON Schema (`x-deprecated` extension keyword) and gRPC `.proto` (`option deprecated = true;` plus a `[deprecated_since]` custom option). Release notes list all mark transitions.
2. **Coexist.** Deprecated field remains functional for **at least 3 minor releases OR 6 months, whichever is longer** (`removal_earliest` is a floor, not a promise to remove on that date). Both new and old fields are accepted; server writes both on responses when both are meaningful.
3. **Warn.** Server emits a `Warning: 299 - "synanton: field 'X' is deprecated since 1.17; use 'Y'; scheduled removal >= 1.20"` HTTP header on every response that touched the field, and increments `synapt_deprecated_field_usage_total{tenant, field, since}` metric.
4. **Remove.** Field is removed in a release ≥ `removal_earliest`, only after `synapt_deprecated_field_usage_total{field}` has been `0` across the whole fleet for at least 30 days. Removal is announced in the corresponding release notes and in the changelog appended to `MEMORY.md`-style deprecation register at `synanton-support/deprecations.md`.

**Removal gate.** The removal is gated by CI: the `deprecation-gate` job in the release pipeline queries the fleet metrics via Prometheus federation and fails the release if any deprecated-field usage is observed within the 30-day quiet window.

**Never-deprecated surfaces.** These fields carry a stability guarantee and cannot be deprecated:
- `tenant_id`, `content_ref_id`, `chunk_id`, `entity_id` - identity primitives.
- `residency.allowed_regions`, `residency.fail_closed` - regulatory-critical.
- `execution_trace.warnings[]` - the escape hatch itself.

**Client SDKs.** Auto-generated SDKs (`synanton-llm-client` bindings, gRPC stubs) surface deprecation as source-level `@Deprecated` (Java), `deprecated` (Rust), `@deprecated` JSDoc (TypeScript). SDK build breaks are not treated as breaking API changes.

### Global JSON sanitisation *(v1.18)*

`synapt` is the systematic XSS boundary for REST ingress. All incoming JSON string fields are passed through an OWASP HTML sanitizer via a custom Jackson `JsonDeserializer<String>` that is registered globally on the request `ObjectMapper`. The sanitiser is idempotent on safe text - it escapes/removes only dangerous tags, attributes, and CSS constructs.

**Dependency.** `com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer:20240325.1`.

**Wiring.** A `Jackson2ObjectMapperBuilderCustomizer` bean installs the sanitising deserialiser via `SimpleModule.addDeserializer(String.class, new SanitizingStringDeserializer(policy))`. The `policy` is a `PolicyFactory` composed from `Sanitizers.FORMATTING`, `Sanitizers.LINKS`, and per-tenant overrides (see below).

**Opt-out (`@AllowHtml`).** Fields that legitimately carry rich HTML (e.g. `rich_description`, `html_body`, `review_note`) are annotated with `@AllowHtml`. The custom deserialiser reads the target field's annotation via Jackson's `BeanProperty` and skips sanitisation for that field. Structural validation (`@Size`, etc.) still applies to `@AllowHtml` fields.

**Where sanitisation runs.** REST boundary only. gRPC internal calls are trusted (same trust zone, see §4) and are not double-sanitised - PGV structural validation runs at that boundary instead (see §28-§32). External gRPC callers, if any, are treated as untrusted and pass through both PGV and, on any REST-transitive path, JSON sanitisation.

**Configuration keys (new).**

```yaml
synapt:
  sanitizer:
    enabled: true                       # global kill-switch
    allowed-tags: [p, b, i, u, a, ul, ol, li, blockquote, strong, em]
    allowed-attributes: [href, target, rel, class]
    strip-unsafe-css: true
    allow-relative-links: false
  validation:
    strict: false                       # false = warn+accept; true = 400 on any violation
    max-string-length-hard-cap: 65536   # absolute upper bound regardless of DTO @Size
```

Per-tenant overrides are read from `topology.tenant_policy.security_sanitizer_overrides JSONB NULL`; missing keys fall through to the global defaults. Overrides can tighten (not loosen) the global allow-lists.

### Jakarta Validation (JSR-380) on public DTOs *(v1.18)*

All public request DTOs receive Jakarta Validation annotations. Enforcement uses Spring's `@Valid` on controller parameters plus a global `@RestControllerAdvice` that translates `MethodArgumentNotValidException` and `ConstraintViolationException` into a structured `400 Bad Request` payload:

```json
{
  "error": "validation_failed",
  "field_errors": [
    { "field": "title", "code": "Size", "message": "size must be between 0 and 100" },
    { "field": "externalUrl", "code": "URL", "message": "must be a valid URL" }
  ],
  "trace_id": "…"
}
```

**Annotations by category.**

- `@NotBlank` on required string fields (title, tenant_id, user_subject).
- `@Size(min, max)` on all string fields - explicit maxima per tenant tier, hard-capped by `synapt.validation.max-string-length-hard-cap`.
- `@Pattern(regexp = …)` for identifiers, slugs, and IDs. Canonical patterns:
  - `tenant_id`, `user_subject`: `^[a-zA-Z0-9_-]{1,64}$`.
  - `content_ref_id`, `chunk_id`, `entity_id`: `^[a-fA-F0-9-]{36}$` (UUID).
- `@URL` on outbound-link fields (`externalUrl`, `webhookUrl`, `image_url`), with scheme constraint enforced at controller level to `http`, `https` only.
- `@Email` on email fields.
- `@Min` / `@Max` on numeric fields (`top_k`, `timeout_ms`, `top_n`).

**Example DTO.**

```java
public class CreateReviewRequest {
    @NotBlank @Size(max = 100)
    @Pattern(regexp = "^[\\p{L}\\p{N} .,'\\-]{1,100}$")
    private String title;

    @Size(max = 5000) @AllowHtml
    private String description;      // sanitisation skipped; size still enforced

    @URL
    private String externalUrl;
}
```

**Feature flag.** `synapt.validation.strict` gates whether violations are rejected (`true`) or downgraded to a warning header + metric increment (`false`, default). Tenants opt in to strict mode via `topology.tenant_policy.security_sanitizer_overrides.strict = true`, enabling gradual rollout without a global cutover.

**Metrics.** Every deserialisation and every controller invocation increments per-field counters (see §45):
- `synapt_sanitization_applied_total{tenant, field}`
- `synapt_sanitization_skipped_total{tenant, field}` (for `@AllowHtml` hits)
- `synapt_validation_rejected_total{tenant, field, error}`

**Backward compatibility.** Sizes and patterns are chosen to accept every payload that v1.17 accepted in practice. The strict-mode flag makes the migration explicit; in lenient mode, violations are logged and metered but not rejected.

### Request schema (`POST /search`)
```json
{
  "query": "supply chain risks",
  "tenant": "acme",
  "federation": {
    "lexical_target": "elastic-eu",
    "vector_target":  "qdrant-eu",
    "fusion":         { "method": "RRF", "k": 60 },
    "acl_handling":   "INJECT"
  },
  "residency": { "allowed_regions": ["eu-west-1"], "fail_closed": true },
  "rerank":    { "model": "bge-reranker-v2", "policy": "SCORE_GAP_TRIGGERED", "top_n": 20 },
  "explain":   true,
  "timeout_ms": 5000
}
```

### Response
```json
{
  "hits": [...],
  "execution_trace": {
    "plan":           [...],
    "cost":           { "gpu_ms": 42, "cross_region_bytes": 0 },
    "rerank_trace":   { "model": "bge-reranker-v2", "cached": false },
    "patterns_used":  [{ "pattern": "var_len_path", "level": "NATIVE" }]
  },
  "warnings": ["residency-filtered: qdrant-us"]
}
```

### Budget enforcement
- At 100 % monthly consumption → HTTP 429 + `Retry-After: 86400`.
- Thresholds 70 %, 90 % → warnings only.
- Forecast-driven alerts < 7 days / < 3 days.

### Configuration
- `synapt.timeout_ms_default` (default 5000)
- `synapt.timeout_ms_max` (default 30000)
- `synapt.rate_limit_per_tenant_qps` (default 100)
- `synapt.sanitizer.enabled` *(v1.18)* (default `true`)
- `synapt.sanitizer.allowed-tags` *(v1.18)* (default `[p, b, i, u, a, ul, ol, li, blockquote, strong, em]`)
- `synapt.sanitizer.allowed-attributes` *(v1.18)* (default `[href, target, rel, class]`)
- `synapt.sanitizer.strip-unsafe-css` *(v1.18)* (default `true`)
- `synapt.sanitizer.allow-relative-links` *(v1.18)* (default `false`)
- `synapt.validation.strict` *(v1.18)* (default `false`)
- `synapt.validation.max-string-length-hard-cap` *(v1.18)* (default `65536`)

------

## 25. Module: `topology` (Authoritative Org/ACL/Policy Store)

### Role
Single authoritative store for organisations, spaces, projects, folders, users, groups, permission grants, and tenant policies.

### Responsibilities
- Persist org hierarchy.
- Persist ACL grants.
- Persist tenant policies (residency, tiering, rerank, budget, outbound auth, regulatory profile, cost privacy).
- Emit `topology_events` for downstream consumers.
- Project to Neo4j for fast traversal (`gateway` reads projection; falls back to PG on lag).
- Sole acceptor of writes via `TopologyMutationApi` (no direct DB access from `security`).

### Data model

`organizations` (PostgreSQL):
```sql
CREATE TABLE organizations (
  org_id                   UUID PRIMARY KEY,
  name                     TEXT NOT NULL,
  data_residency_policy    JSONB,  -- {allowedRegions, failClosed, appliesTo}
  tiering_policy           JSONB,  -- {hotDays, warmDays, coldClass}
  rerank_policy            JSONB,  -- {modelId, policy, candidatePoolSize, topN}
  budget_policy            JSONB,  -- {monthlyGpuMinutes, alertThresholds}
  outbound_auth_profiles   JSONB,  -- [USER_SUBJECT, SERVICE_ACCOUNT, MTLS, API_KEY]
  regulatory_profile       TEXT,   -- STANDARD | FINANCIAL | HEALTHCARE
  cost_privacy             JSONB,  -- {attribute_per_user: bool}
  created_at               TIMESTAMPTZ NOT NULL,
  updated_at               TIMESTAMPTZ NOT NULL
);
```

`acl_grants`:
```sql
CREATE TABLE acl_grants (
  grant_id                 UUID PRIMARY KEY,
  org_id                   UUID NOT NULL,
  subject_id               UUID NOT NULL,
  subject_type             TEXT NOT NULL,  -- USER | GROUP
  resource_id              UUID NOT NULL,
  resource_type            TEXT NOT NULL,  -- SPACE | PROJECT | FOLDER | DOCUMENT
  permission               TEXT NOT NULL,
  propagation_state        TEXT NOT NULL,  -- PROPAGATED | PENDING_PROPAGATION | STUCK
  created_at               TIMESTAMPTZ NOT NULL,
  propagated_at            TIMESTAMPTZ
);
```

`topology_outbox`:
```sql
CREATE TABLE topology_outbox (
  outbox_id                UUID PRIMARY KEY,
  event_type               TEXT NOT NULL,
  payload                  JSONB NOT NULL,
  created_at               TIMESTAMPTZ NOT NULL,
  dispatched_at            TIMESTAMPTZ,
  ack_state                JSONB  -- {synquest: ts, gateway: ts, relix: ts}
);
```

### Mutation API
```
TopologyMutationApi.grant(grant_request) → propagation_id
TopologyMutationApi.revoke(grant_id) → propagation_id
TopologyMutationApi.upsertPolicy(org_id, policy_name, value) → version
```

Single PostgreSQL transaction: write row + write outbox. Caller gets `202 Accepted` with `propagation_id`.

### Outbox dispatcher
Separate worker; reads outbox rows; fans out gRPC notifications **outside** the transaction. Two-phase logic for HIGH_SECURITY (see §11).

### Neo4j projection
For fast `resolveUserScope` calls. SLO p95 < 500 ms lag, p99 < 2 s. On lag > 5 s, `gateway.resolveUserScope` falls back to authoritative PostgreSQL.

### Configuration
- `topology.outbox.dispatch_interval_ms` (default 100)
- `topology.high_security.ack_deadline_ms` (default 50)
- `topology.high_security.reconciler_max_attempts` (default 60, i.e. 5 min)
- `topology.projection.max_lag_fallback_seconds` (default 5)

### Metrics
- `security_acl_pending_propagation`
- `topology_outbox_lag_seconds`
- `topology_projection_lag_seconds`

### Alerts
- `AclStuckGrant` - 3 consecutive reconciler runs unresolved.
- `TopologyProjectionStale` - projection lag > 5 s.

------

## 26. Module: `security` (AuthN/Z + Outbound Broker)

### Role
Identity abstraction (inbound), outbound auth broker (outbound), background MCP session revalidation, topology outbox dispatch.

### Responsibilities
- Resolve external IdP identities to internal subject assertions (`IdentityProviderPort`).
- Issue worker assertions for long-running jobs.
- RFC 8693 token exchange for outbound calls.
- Cache IdP status to prevent thundering herds.
- Re-validate open MCP sessions periodically.
- Dispatch topology outbox events.

### Interfaces

**Inbound:**
- `IdentityProviderPort.authenticate(token) → SubjectAssertion`
- `IdentityProviderPort.validate(subject) → ValidationResult`
- `WorkerAssertion.issue(job_id, subject) → assertion`
- `WorkerAssertion.renew(job_id) → assertion | ERR_SUBJECT_REVOKED`

**Outbound:**
- `OutboundAuthBroker.exchange(subject, audience, scopes) → token`

### IdP amortization
`IdpStatusAmortizationCache`:
- 5 s window for HIGH_SECURITY tenants.
- 60 s for STANDARD.
- SCIM `topology_events` evict explicitly.

Eliminates IdP login storms during mass worker token renewal (e.g., overnight reindex).

**v1.17 - Staleness observability.** The cache trades freshness for stability, which means it may occasionally serve `ACTIVE` for a subject that was revoked seconds ago. That trade-off is deliberate but must be measurable:

- `security_idp_amortization_stale_seconds{tenant, tier}` histogram - for every eviction (whether by TTL or SCIM), record `now() - last_idp_ping_time`. Distribution shows how stale the cache was allowed to become before it was checked.
- `security_idp_amortization_stale_authz_total{tenant, tier}` counter - increments when a cached-`ACTIVE` decision is later found to have been wrong at eviction time (the subject was actually revoked upstream). This is a *safety* metric: it must remain in single-digits-per-day per tenant; sustained non-zero values indicate the amortization window is too wide or SCIM eviction isn't wired up.

**Alert:**
- `IdpAmortizationStaleAuthzHigh` *(v1.17)* - `security_idp_amortization_stale_authz_total` > 5 in any 1 h window for a HIGH_SECURITY tenant (page).

### MCP session revalidation
Background `RevalidationWorker` (virtual thread, sliding 15 min default; per-tier override 5/15/60). Calls `security.ValidateToken` for every active session. Exponential backoff on IdP unavailability (3 retries / 5 min). Transient IdP blips don't kill sessions.

### Worker token renewal
`AssertionRenewalPattern`: at `exp − 10 min`, active task requests `IssueWorkerAssertion(job_id)`. Security re-checks subject validity against IdP. On revocation: `ERR_SUBJECT_REVOKED` + compensation rollback in the calling workflow. Long-running reindex / synthesis no longer outlives its identity.

### Outbound Auth Broker
RFC 8693 token exchange. Profiles:
- `USER_SUBJECT` - propagate the calling user's identity to the federated system.
- `SERVICE_ACCOUNT` - use a tenant service account.
- `MTLS` - use mutual TLS with a per-tenant client cert.
- `API_KEY` - use a tenant-managed API key.

**SLO:** p99 exchange ≤ 100 ms. On breach, broker denies the outbound call rather than block the gateway thread. Mitigates a runaway IdP from cascading into platform-wide latency.

**Token cache:** per `(subject, audience, scopes)`; max TTL = token expiry − 30 s. HIGH_SECURITY tenants set `security.outbound.cache_max_age_seconds = 0` to disable.

**Audit:** every exchange writes a `security_outbound_audit` event.

### Topology outbox dispatcher
Post-commit worker for `topology_outbox`. Fans out gRPC notifications outside the topology transaction boundary.

### External ACL trust
`ExternalAclTrust` enum on federation adapters:
- `ENFORCE_LOCAL_ONLY` - trust only Synanton ACL injection.
- `TRUST_EXTERNAL` - trust the upstream system's RBAC.
- `DUAL` (default) - both must agree (safest).

### `support_admin` role *(v1.19)*

A new RBAC role, `support_admin`, is added to the `security.roles` table. It is the identity used by the `helper` CLI (§26b) and by designated break-glass accounts during incidents.

**Grants.** Principals with `support_admin` can:
- Call the `/admin/_internal/*` endpoint family on `synapt` (§24) and `control-plane` (§27).
- Read any tenant's metadata (identity primitives, policy rows, module health, workflow state).
- Trigger recrawl, cache-clean, and destructive delete operations - every call is authenticated, audited, and gated by `confirm: "I_AM_SURE"` for destructive verbs.

**Denials.** Principals with `support_admin` **cannot**:
- Read tenant *content bodies* (chunk payloads, source documents) unless explicitly granted per-tenant `content:read`.
- Assume a tenant user's subject for outbound token exchange (RFC 8693 `USER_SUBJECT` profile is refused if the calling principal is `support_admin`).
- Bypass residency (`fail_closed`) or budget (429) enforcement.

**Assignment constraints.**
- The role is **not** assignable to human users through normal IdP flows (SCIM push and OIDC-role-claim mapping refuse the role name on ingest).
- Only two mechanisms may bind the role:
  1. **Service principals** created by `topology.ServicePrincipalApi.create(...)` with an explicit `role=support_admin` argument, callable only by the platform superadmin.
  2. **Break-glass accounts**, provisioned in the `security` module with a mandatory expiry ≤ 24 h and a `justification` field; automatically revoked at expiry.
- The `admin_audit` row for a `support_admin` binding always carries `actor = platform_superadmin` or `actor = break_glass_pipeline` - a support principal cannot mint another support principal.

**Credential.** Service principals with `support_admin` authenticate via `SYNANTON_SUPPORT_KEY`, subject to the rotation cadence and argon2id hashing described in §26a.

### Configuration
- `security.outbound.exchange_p99_slo_ms` (default 100)
- `security.outbound.cache_max_age_seconds` (default 3600; 0 for HIGH_SECURITY)
- `security.mcp.session.revalidation_interval_minutes` (default 15)
- `security.worker.renewal_lead_time_minutes` (default 10)

### Metrics
- `security_outbound_exchange_p99`
- `security_outbound_exchange_denied_total{reason}`
- `security_idp_amortization_hit_total`
- `security_mcp_session_revalidation_total{result}`
- `security_idp_amortization_stale_seconds{tenant, tier}` histogram *(v1.17)*
- `security_idp_amortization_stale_authz_total{tenant, tier}` *(v1.17)*
- `security_api_key_active_total{tenant}` *(v1.17)* - active (non-revoked, non-expired) API keys per tenant
- `security_api_key_rotated_total{tenant, reason}` *(v1.17)* - rotation events, `reason ∈ {scheduled, ad_hoc, incident}`

### Alerts
- `OutboundTokenSlaBreached` - p99 > 100 ms over 5 min.
- `IdpUnavailable` - sustained validation failures > 5 min.
- `IdpAmortizationStaleAuthzHigh` *(v1.17)* - see IdP amortization above.
- `ApiKeyPastExpiry` *(v1.17)* - any tenant has ≥1 API key ≤ 30 days from expiry with no successor issued.

### GPU Execution Plane additions *(v1.20)*


> **[v1.20]** The GPU Gateway becomes an independent authenticated service boundary. The v1.19 security specification is unchanged for all non-GPU paths.

The mTLS certificate pair for the GPU execution client is issued by the same CA infrastructure used for internal service certificates. The GPU Gateway is registered as a distinct service principal. Certificate rotation follows the existing rotation cadence defined in §26a.

------

## 26a. API Key Lifecycle *(new in v1.17)*

API keys are the identity primitive for machine-to-machine (M2M) callers of `synapt` - CI systems, batch jobs, third-party integrations. v1.16 defined only the request-side handling (`synapt` validates a key on ingress). v1.17 formalises the full lifecycle so keys cannot become the platform's weakest link.

### Ownership & scope

Each API key belongs to a `(tenant_id, service_principal_id)` pair, never to a human user. Human-authored requests always flow through the OIDC/OAuth IdP path (§26). Attempting to bind an API key to a human subject is refused at creation time.

Every key carries:
- `scopes[]` - subset of the tenant's grantable scopes; must be strictly narrower than the enclosing service principal's scopes.
- `residency_pin` - optional; the key can only originate requests from the listed regions.
- `ip_allowlist` - optional CIDR list, matched at TLS-terminating edge before `synapt` sees the request.

### Generation

Keys are generated by `security.ApiKeyService.generate(tenant_id, service_principal_id, scopes, ttl)`:

1. Generate 256 bits of CSPRNG entropy → base64url encode → 43-char string.
2. Prefix with a public identifier `syn_<tenant_slug>_<yyMM>_` so a leaked key is easy to attribute in log tools that support prefix search.
3. Compute `argon2id(secret, salt=key_id, m=64MB, t=3, p=1)` → **only the hash is stored in PostgreSQL**. The plain-text secret is returned exactly once to the caller and then discarded from server memory.
4. Persist `(key_id, tenant_id, service_principal_id, hash, scopes, residency_pin, ip_allowlist, created_at, expires_at, revoked_at, last_used_at, last_used_ip_cidr)` to `security.api_keys`.

**No shortcuts:** SHA-256 is not acceptable for at-rest hashing - an offline attacker with the DB dump would recover keys at ~10^10 hashes/sec/GPU. argon2id at m=64MB / t=3 puts the wall-clock cost per candidate around 100 ms on the same GPU, making offline brute-force impractical.

### Rotation

Rotation policy per tier:

| Tier | Default TTL | Grace period | Notification |
|------|-------------|--------------|--------------|
| STANDARD | 365 d | 60 d | Email at T-60/T-30/T-14/T-7 |
| HIGH_SECURITY | 90 d | 14 d | Email at T-14/T-7/T-3/T-1 + PagerDuty T-3 |
| FINANCIAL | 90 d | 14 d | Email + audit-log line, per-key |
| HEALTHCARE | 60 d | 7 d | Email + audit-log line, per-key |

Rotation is **overlap-based**: the new key is issued before the old one is revoked. During the grace period both keys work; the old key emits a `X-Synanton-Warning: 299 api-key-deprecated` header with the expiry date. On expiry, requests return `401 Unauthorized` with body `{"error": "ERR_API_KEY_EXPIRED", "expired_at": "..."}` - never `500`.

**Ad hoc rotation.** `POST /admin/api-keys/{key_id}/rotate` immediately issues a successor, sets `revoked_at = now() + rotation_grace_period` on the predecessor. Emergency rotation with `?grace=0` revokes immediately (audited, requires `admin:security_incident` scope).

### Validation & authorisation

On each request, `synapt` extracts the key from the `Authorization: Bearer syn_…` header, then:
1. Look up `key_id` from the prefix (fast lookup via a `key_id` column, not a table scan).
2. `argon2id.verify(hash, presented_secret)` - constant-time.
3. Check `expires_at > now()` and `revoked_at IS NULL OR revoked_at > now()`.
4. Check `ip_allowlist` (if set) and `residency_pin` (against the request's origin region).
5. Emit `security_api_key_use_total{tenant, key_id_prefix, outcome}` and update `last_used_at`/`last_used_ip_cidr` (debounced 60 s to avoid write amplification).

Failed validations increment `security_api_key_use_failed_total{reason}`; too many from a single `key_id` within `security.api_keys.brute_force_window_seconds` (default 60) trip a per-key throttle for `security.api_keys.brute_force_lockout_seconds` (default 300). Rate limits are per-key, not per-tenant, so one abused key doesn't lock out others.

### Revocation

- **Explicit** - `POST /admin/api-keys/{key_id}/revoke`. Idempotent; propagates via `topology_events` to every module that caches auth decisions.
- **Cascading** - revoking the service principal revokes all its keys.
- **Automatic** - key is auto-revoked if `security_api_key_use_failed_total` for it exceeds a threshold within a short window (default: 100 failures in 5 min triggers auto-revoke; requires manual `unrevoke` to restore). This is the same shape as the offline brute-force defense but at a coarser threshold.

### Audit

Every generation, rotation, and revocation writes to `security_outbound_audit` topic (§37) with the actor subject, the target key_id, the action, and the pre/post state fingerprint. Retention 1 y; longer for regulated tiers.

### Data model

```sql
CREATE TABLE security.api_keys (
  key_id                UUID PRIMARY KEY,
  tenant_id             UUID NOT NULL,
  service_principal_id  UUID NOT NULL REFERENCES topology.service_principals(id),
  hash                  BYTEA NOT NULL,           -- argon2id encoded output (includes salt+params)
  scopes                TEXT[] NOT NULL,
  residency_pin         TEXT[],                    -- e.g. ['eu-west-1']
  ip_allowlist          CIDR[],
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by_subject    UUID NOT NULL,
  expires_at            TIMESTAMPTZ NOT NULL,
  revoked_at            TIMESTAMPTZ,
  revoked_by_subject    UUID,
  revoke_reason         TEXT,
  successor_key_id      UUID REFERENCES security.api_keys(key_id),
  last_used_at          TIMESTAMPTZ,
  last_used_ip_cidr     CIDR
);
CREATE INDEX api_keys_active ON security.api_keys(tenant_id, expires_at)
  WHERE revoked_at IS NULL;
```

### Configuration
- `security.api_keys.default_ttl_days` (per tier - see rotation table above)
- `security.api_keys.rotation_grace_period_days` (per tier)
- `security.api_keys.argon2.memory_kb` (default 65536)
- `security.api_keys.argon2.iterations` (default 3)
- `security.api_keys.brute_force_window_seconds` (default 60)
- `security.api_keys.brute_force_lockout_seconds` (default 300)
- `security.api_keys.notification_channel` (`EMAIL | PAGERDUTY | WEBHOOK`, default `EMAIL`)

### Metrics (referenced from §26)
- `security_api_key_active_total{tenant}`
- `security_api_key_rotated_total{tenant, reason}`
- `security_api_key_use_total{tenant, key_id_prefix, outcome}`
- `security_api_key_use_failed_total{tenant, key_id_prefix, reason}`
- `security_api_key_auto_revoked_total{tenant, reason}`

### Failure modes
- Argon2 hash verification cannot complete in time → return `503 Retry-After: 5`, never `401`. A slow crypto path is not an authorisation failure.
- PostgreSQL replica lag hides a revocation → `synapt` reads from primary for authorisation decisions, never from replica.

### `SYNANTON_SUPPORT_KEY` credential *(v1.19)*

`SYNANTON_SUPPORT_KEY` is the API key issued to service principals carrying the `support_admin` role (§26). It shares the entire lifecycle machinery above - argon2id at-rest hashing, prefix-based lookup, IP allowlist, rotation grace, brute-force auto-revoke - with three tightened parameters:

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| **TTL - STANDARD tier** | **90 d** (vs 365 d default) | Support keys are high-privilege; shorter TTL bounds blast radius of leak. |
| **TTL - HIGH_SECURITY / FINANCIAL / HEALTHCARE** | **30 d** | Same rationale, further tightened for regulated tiers. |
| **Grace period** | **7 d** (all tiers) | Short overlap; forces prompt rotation in incident tooling. |
| **Notification** | Email at T-14/T-7/T-3/T-1 + **PagerDuty T-3** (all tiers) | A stale support key is a live incident, not a paper cut. |
| **`ip_allowlist`** | **Required** - key generation is refused without at least one CIDR entry. | Compensates for the elevated scope; keeps the key useless outside operator networks. |

**Prefix.** Support keys use the `syn_support_<yyMM>_` prefix (in place of the tenant-slug prefix) so leaked keys are unambiguously identifiable in log-scanning tools without knowing the operator's tenant slug.

**CLI warning.** The `helper` CLI (§26b) queries the key's `expires_at` on every invocation via the `/admin/_internal/status` response and prints a red banner if the key is within 7 days of expiry. Callers can also run `synctl helper key expiry` for an explicit check.

**Rotation flow.** The platform-superadmin (never a `support_admin`) initiates rotation:

```
synctl helper key rotate --key-id syn_support_2607_abc123
  → POST /admin/api-keys/{key_id}/rotate
  → response: { successor_key: "syn_support_2607_xyz789", grace_expires_at: "..." }
  → operator updates ~/.synanton/credentials with the new key
  → predecessor auto-revokes at grace_expires_at
```

Emergency `?grace=0` rotation is available and audited; it is expected during suspected key-leak incidents.

**Restrictions.**
- `SYNANTON_SUPPORT_KEY` **cannot** be used with the RFC 8693 outbound broker `USER_SUBJECT` profile (see §26).
- The key is **never** issued to a break-glass account - those receive a short-lived (≤ 24 h) OIDC token instead, so revocation is intrinsic to the token expiry.
- `security.api_keys.brute_force_lockout_seconds` is elevated to **1800** (30 min) for the `syn_support_*` prefix - a brute-force attempt on a support key is treated as a paging incident.

**Metric.** `security_api_key_active_total{tenant}` breaks out support keys via a label `key_class = "support"`. The `ApiKeyPastExpiry` alert (§26) fires at T-14 (not T-30) for `key_class = "support"`.

------

## 26b. Module: `helper` - Operational Day-2 CLI *(new in v1.19)*

### Role

`helper` is the operator-facing CLI for day-2 support tasks: cluster status, cache cleanup, orphan reconciliation, tenant recrawl, workflow retries, destructive content/tenant deletion. It runs on a live cluster and executes **exclusively** through the platform's internal admin API (§24 `synapt` + §27 `control-plane`).

Direct database, Kubernetes, or Kafka access is explicitly out of scope. This is not an ergonomic accident - it is the security posture (see §4 Trust zones).

### Delivery

- Ships as part of the single Go binary `synanton-ops` (see Appendix D), invoked through the existing `synctl` wrapper: `synctl helper <command>`.
- No new packaging surface. Included in the standard release tarball.
- Runs on macOS and Linux; the CI harness cross-compiles both.

### Authentication

The CLI resolves credentials in this order:

1. `SYNANTON_API_ENDPOINT` env var (default `https://synanton.internal`).
2. `SYNANTON_SUPPORT_KEY` (or `SYNANTON_SUPPORT_TOKEN`) env var.
3. `~/.synanton/credentials` (INI, with `[default]` and named profiles). Selectable via `--profile` or `SYNANTON_PROFILE`.

Every request carries `Authorization: Bearer <SYNANTON_SUPPORT_KEY>`. The receiving admin endpoint validates:

1. Token signature and expiry.
2. Principal has the `support_admin` role.
3. Action is within the principal's tenant scope (where scoped).

If the cluster is unreachable, the CLI errors out with `cluster unreachable - refusing to fall back to local access` and exit code `2`. **There is no local fallback path**; this prevents split-brain operations and side-channel writes to storage.

### Command surface

| `synctl helper` command | Underlying API endpoint | Method | Body / params |
|-------------------------|-------------------------|--------|---------------|
| `status` | `GET /admin/_internal/status` | GET | none |
| `bundle` | `POST /admin/_internal/bundle` | POST | `{"include_logs_hours": 24, "anonymize": true}` - response includes pre-signed S3 URL |
| `clean tenant --tenant X --cache embedding` | `POST /admin/_internal/clean` | POST | `{"tenant": "X", "resource": "embedding_cache", "dry_run": false, "confirm": "I_AM_SURE"}` |
| `clean orphans --dry-run` | `POST /admin/_internal/clean` | POST | `{"resource": "orphan_chunks", "dry_run": true}` |
| `delete content --ref Y --cascade --confirm` | `POST /admin/_internal/delete` | POST | `{"resource": "content", "ref": "Y", "cascade": true, "confirm": "I_AM_SURE"}` |
| `delete tenant --tenant X --purge-data --confirm` | `POST /admin/_internal/delete` | POST | `{"resource": "tenant", "tenant": "X", "purge_data": true, "confirm": "I_AM_SURE"}` |
| `recrawl start --tenant X` | `POST /admin/_internal/recrawl` | POST | `{"tenant": "X", "action": "start", "priority": "recency_weighted"}` |
| `recrawl status --tenant X` | `GET /admin/_internal/recrawl/X` | GET | none |
| `recrawl pause --tenant X` | `POST /admin/_internal/recrawl` | POST | `{"tenant": "X", "action": "pause"}` |
| `workflow cancel --id W` | `POST /admin/_internal/workflow/cancel` | POST | `{"workflow_id": "W"}` |
| `workflow retry --id W` | `POST /admin/_internal/workflow/retry` | POST | `{"workflow_id": "W"}` |
| `key expiry` | `GET /admin/_internal/status` | GET | reads `support_key.expires_at`; prints red banner if < 7 d |

Every destructive verb (`delete content`, `delete tenant`, `clean tenant` without `--dry-run`) requires **two** confirmations:

1. `--confirm` CLI flag (fail fast at parse time if missing).
2. `confirm: "I_AM_SURE"` field in the JSON request body (fail fast at server if missing).

The two-layer gate prevents pipe-and-shell accidents (`echo yes | synctl ...`) and paste-into-jump-host mishaps.

### Idempotency

Every state-changing call includes an `Idempotency-Key` header derived from `sha256(command || argv || SYNANTON_SUPPORT_KEY_ID || wall_clock_minute)`. The server dedups within a 24-hour window. Retrying the exact same command within that window yields the same result without a duplicate side-effect.

### Output

Two modes, selectable via `--output`:

- `--output=human` (default) - colourised, table-formatted, spinner for long-running calls.
- `--output=json` - one JSON object per line, machine-parseable; suitable for jq/CI usage.

Output redaction:

- The `bundle` command never inlines the S3 tarball into stdout; only the pre-signed URL is printed. Tarball generation strips JWT secrets, DB passwords, and per-tenant PII by tag rules configured in `helper.bundle.redaction_rules` (see below).
- The `status` command surfaces service *names* and health states only. Internal connection strings, private IPs, and Kubernetes secret names are never rendered.

### Configuration (client-side)

```yaml
synctl:
  helper:
    api_endpoint: "https://synanton.internal"      # overridable by SYNANTON_API_ENDPOINT
    request_timeout_seconds: 30                    # overall HTTP timeout
    retry:
      max_attempts: 3
      backoff_seconds: [1, 3, 7]                   # exponential-ish; only on 5xx and connection errors
    output: "human"                                # human | json
    key_expiry_warn_days: 7                        # warn when SYNANTON_SUPPORT_KEY expires within N days
    bundle:
      include_logs_hours: 24
      anonymize: true
      redaction_rules_path: "~/.synanton/bundle-redactions.yaml"
    audit_local_log: "~/.synanton/helper-audit.jsonl"   # every call is also appended locally
```

### Configuration (server-side)

- `synapt.admin.internal.enabled` (default `true`).
- `synapt.admin.internal.allowed_cidrs[]` - operator VPN and CI jump-host CIDRs (no default; must be set at install time).
- `control-plane.admin.internal.enabled` (default `true`).
- `control-plane.admin.internal.allowed_cidrs[]` - same shape as above.

### Metrics

- `helper_operation_total{command, tenant, outcome}` - counter, one increment per API call. `outcome ∈ {success, denied, error, dry_run_success}`.
- `helper_operation_duration_seconds{command, tenant}` - histogram; captures end-to-end wall-clock of the API call including server-side work.
- `helper_auth_failure_total{reason}` - counter; `reason ∈ {invalid_key, expired_key, wrong_role, ip_not_allowlisted}`.
- `helper_destructive_ops_total{command, tenant}` - dedicated counter for the delete/clean-tenant surface, so the security team's dashboards render destructive activity without label-cardinality risk.

### Alerts

- `HelperDestructiveOpsRate` - `helper_destructive_ops_total` sum over 15 min > 10 (page). Someone is doing a lot of deleting; confirm the incident is sanctioned.
- `HelperAuthFailureSpike` - `helper_auth_failure_total` sum over 5 min > 20 for any `reason ∈ {invalid_key, wrong_role}` (page). Possible credential leak or misuse; oncall investigates.

### Audit

- Every write call writes an `admin_audit` row with:
  - `actor_type = "support_admin"`, `actor_id = support_principal_id`.
  - `action`, `resource_kind`, `resource_id`, `tenant_id`.
  - `before_state_hash`, `after_state_hash` - SHA-256 of a canonical serialisation of the affected resource, captured inside the same DB transaction as the mutation.
  - `request_correlation_id` - the client-side `X-Request-Id` header, joinable to the CLI's local audit log.
- The CLI also appends every call to `~/.synanton/helper-audit.jsonl` (see `audit_local_log`). This is a client convenience; the authoritative record is `admin_audit`.

### Failure modes

- **Cluster unreachable** → exit code `2`, no side-effects. Retry after network is restored.
- **Key expired** → server returns `401 ERR_API_KEY_EXPIRED`. CLI prints the expiry timestamp and the rotation command.
- **Wrong role** → server returns `403 support_admin required`. Almost always indicates the wrong `SYNANTON_PROFILE`.
- **Confirm mismatch** → server returns `400`; CLI reprints the exact command with the missing flag.
- **Idempotency replay** → server returns `200` with `"idempotent_replay": true`. CLI notes it in the human output and exits `0`.

### Non-goals

- No direct database or Kafka access.
- No local-only "offline mode" - that is the `wizard`'s role (see §26c).
- No embedded interactive REPL - the CLI is one-shot per invocation to keep audit reasoning simple.
- No ability to grant, revoke, or mint credentials. Credential management lives in the platform superadmin path (see §26).

------

## 26c. Module: `wizard` - Deployment Setup Builder *(new in v1.19)*

### Role

`wizard` is the day-0 deployment artifact generator. It runs entirely offline, on a developer's laptop or in a CI pipeline, and emits Terraform / Kubernetes Helm / Docker Compose / `.env` artifacts sized to the tenant's declared profile.

`wizard` **never contacts a live cluster** at generation time. Optional `apply` invokes `terraform apply` in a shell that already carries the operator's cloud credentials - the wizard binary itself does not read those credentials.

### Delivery

- Ships in the same `synanton-ops` binary as `helper` (see Appendix D), invoked as `synctl wizard <command>`.
- Templates live under `~/.synanton/templates/` (user override) and bundled defaults ship inside the binary as `embed.FS`.

### Security model

- **Zero credentials required.** The binary refuses to accept cloud provider credentials directly; if the operator wants to `apply`, they must have the credentials in their shell environment.
- Generated files include **placeholders** for secrets (`RANDOM_PASSWORD_PLACEHOLDER`, `AWS_ACCESS_KEY_ID_PLACEHOLDER`, …). The generator emits a `SECRETS.md` alongside artifacts explaining how to populate them from the operator's secret manager.
- No network calls to any cloud API at `init`, `generate`, or `validate`. `apply` shells out to `terraform`, which then talks to the cloud provider - `wizard` itself remains passive.

### Command surface

| `synctl wizard` command | Purpose |
|-------------------------|---------|
| `init` | Interactive questionnaire → writes `deployment-config.yaml` (profile, cloud, region, capacity tier, HA topology, DR posture). |
| `generate --config deploy.yaml [--output-dir ./out]` | Reads the config and emits the full artifact set (Terraform, K8s, Docker Compose, `.env`). |
| `validate --config deploy.yaml` | Validates the YAML against `wizard/schema/v1.json`. No files emitted. |
| `apply --config deploy.yaml` | Runs `terraform init && terraform apply` in the generated directory. Requires cloud credentials in the shell. |
| `diff --config deploy.yaml --previous ./out-prev` | Renders a unified diff between two generated artifact trees - helpful for reviewing config drift. |

### Artifacts generated

- **Terraform** (AWS / GCP / Azure): VPC, subnets, IAM roles, RDS/PostgreSQL, Cassandra or Amazon Keyspaces, MSK/Kafka, ElastiCache/Redis, S3/MinIO, EKS/ECS/GKE, autoscaling groups.
- **Kubernetes**: Helm charts for `synflux`, `synquest`, `relix`, `gateway`, `synapt`, `topology`, `control-plane`, `security`, `synreview`, plus Kustomize overlays for `dev/staging/prod`. Includes the CSP + companion security headers (see §49).
- **Docker Compose**: Full `docker-compose.yml` for local development, with per-service volume mounts, health checks, and dependency ordering that mirrors Kubernetes probes.
- **Per-module `application-*.yml`**: populated with the generated endpoint URIs and tuned CPU/memory limits derived from Appendix A (Capacity Planning).
- **`SECRETS.md`**: enumerates every placeholder secret and provides copy-paste `aws secretsmanager` / `gcloud secrets` / `az keyvault` commands.

### Configuration

```yaml
synctl:
  wizard:
    templates_path: "~/.synanton/templates/"          # user-supplied overrides (optional)
    defaults_path: "~/.synanton/wizard-defaults.yaml" # preferred defaults (cloud, region, capacity)
    schema_version: "v1"                              # locks against wizard/schema/v1.json
    output_default: "./synanton-deployment"
    render:
      terraform: true
      kubernetes: true
      docker_compose: true
      env_files: true
```

### Templates & overrides

- The binary ships embedded Go text/template files (`embed.FS`) covering the full default set.
- Operators can override any template by dropping a file with the same relative path under `~/.synanton/templates/`. The wizard resolves user overrides first, then falls back to embedded defaults.
- `wizard/schema/v1.json` is the JSON Schema for `deployment-config.yaml`. `validate` uses it verbatim; `init` uses it to drive the interactive prompts.

### Non-goals

- **No live-cluster reads.** The wizard never queries an existing cluster to "inherit" state. That would violate the offline promise.
- **No credential storage.** The binary refuses to read `~/.aws/credentials`, `~/.config/gcloud/*`, or `~/.azure/*`. `apply` inherits credentials from the parent shell only.
- **No mutation of an existing deployment.** For that, use `helper` (§26b) or the platform's live admin API - the wizard emits, it doesn't reconcile.

### Failure modes

- **Schema violation on `deployment-config.yaml`** → exit code `1`, points at the failing key with a JSON Pointer.
- **Template missing** → the wizard falls back to the embedded default and prints a warning if the operator's override was resolved but syntactically invalid.
- **`apply` invoked without terraform in `$PATH`** → exit code `3`, prints the install-terraform hint.

------

## 27. Module: `control-plane`

### Role
Admin API, Web Console, Temporal workflow host, GitOps reconciler, cost aggregator, anomaly detector, forecast engine, **ModelServingDirectory**.

### Responsibilities
- Provide admin UX (Web Console, CLI, REST).
- Host Temporal workflows for long-running operations (reindex, ontology migration, cascade reconciliation).
- Reconcile tenant policies from Git.
- Aggregate cost per tenant.
- Detect query anomalies.
- Forecast load and budget burn.
- Resolve `(model_family, region) → vLLM cluster endpoint`.

### Admin API

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/admin/tenants/{id}/residency` | GET/PUT | Data residency policy |
| `/admin/tenants/{id}/tiering` | GET/PUT | Storage tiering policy |
| `/admin/tenants/{id}/rerank` | GET/PUT | Reranker policy |
| `/admin/tenants/{id}/budget` | GET/PUT | Monthly budget + alert thresholds |
| `/admin/tenants/{id}/regulatory` | GET/PUT | `STANDARD | FINANCIAL | HEALTHCARE` |
| `/admin/tenants/{id}/cost-privacy` | GET/PUT | Per-user attribution opt-in |
| `/admin/cost/chargeback` | GET | Rollup by tenant/day |
| `/admin/gitops/apply` | POST | Trigger reconciliation from Git |
| `/admin/anomalies/recommendations` | GET | Slow-query suggestions (advisory) |
| `/admin/relix/connectors` | GET/POST | Register connectors |
| `/admin/relix/connectors/{id}/probe` | POST | Manual coverage probe |
| `/admin/sessions/expire-pinned` | POST | Force-terminate ontology-pinned sessions |
| `/admin/models/serving` | GET | View ModelServingDirectory resolution table |
| `/admin/jobs` | GET | List long-running jobs |
| `/admin/jobs/{id}/cancel` | POST | Cancel a job |
| `/admin/insights` | GET | Graph insights (gaps, surprising connections, bridge nodes) *(v1.1)* |
| `/admin/insights/{id}/research` | POST | Trigger Deep Research on a gap *(v1.1)* |
| `/admin/research` | GET | List research workflows and their status *(v1.1)* |
| `/admin/tenants/{id}/research-policy` | GET/PUT | Deep Research policy *(v1.1)* |
| `/admin/ontology-lint/findings` | GET | Lint findings (orphans, duplicates, broken refs) *(v1.1)* |
| `/admin/ontology-lint/run` | POST | Manually trigger lint workflow *(v1.1)* |
| `/admin/_internal/recrawl` *(v1.19)* | POST | Support-scoped recrawl start/pause/resume (mirrored by §24 synapt) |
| `/admin/_internal/recrawl/{tenant}` *(v1.19)* | GET | Support-scoped recrawl progress query |
| `/admin/_internal/workflow/cancel` *(v1.19)* | POST | Cancel Temporal workflow by id (see §26b `helper`) |
| `/admin/_internal/workflow/retry` *(v1.19)* | POST | Retry failed Temporal workflow by id (see §26b `helper`) |

### Internal admin routes *(v1.19)*

`control-plane` exposes a subset of the `/admin/_internal/*` route family used by the `helper` CLI (§26b). The routes are strictly **support-scoped** - the same operations exist under the human-facing `/admin/...` prefix for interactive operators, and both surfaces converge on the same handlers, workflows, and audit rows. Duplicating the paths under `/_internal/` is deliberate: it lets network policies allow the operator VPN to reach *only* the support surface while blocking human-facing admin ingress from the same subnet.

**Shared behaviour with §24 synapt.**
- Only callable by service principals with the `support_admin` role.
- Every state-changing call writes `admin_audit` with `before_state_hash` and `after_state_hash`.
- Increments `helper_operation_total{command, tenant, outcome}` (§45).
- `workflow.cancel` and `workflow.retry` operate on the same Temporal client the human `/admin/jobs/*` endpoints use - no separate Temporal namespace.

**Kubernetes NetworkPolicy.** The control-plane `Ingress` restricts `/admin/_internal/*` to the same CIDR list as `synapt` (operator VPN + CI/support jump host). All other `/admin/*` routes remain reachable from the console-VPN network only.

### ModelServingDirectory
Resolves `(model_family, region) → vLLM cluster endpoint`. Reranker, embedder, and synthesis adapters route through it. Closes the previously-deferred cross-region synthesis cluster limitation. Per-region vLLM clusters are first-class.

**v1.17 - Provider set:** the directory returns a `List<ProviderType>` rather than a single provider type. `synanton-llm-client` (§27c) inspects the list and negotiates the best supported wire format on its side, so endpoints that speak multiple formats (vLLM OpenAI-compatible + native, or vLLM + tgi) don't require the caller to pick blindly. See §27c for the negotiation algorithm.

**v1.17 - Follow-the-sun serving:** the directory also carries a `replicas[]` list per model family, one entry per active region. The `ModelHealthProber` (see below) marks entries `HEALTHY | DEGRADED | UNAVAILABLE` on a 30 s cadence. Planner (§22) reads this table to pick the closest healthy replica for each execution step, honouring residency constraints. Fallbacks cascade in this order: same-region → adjacent-region (per `cross_region_penalty_ms` map) → any-region-with-residency-permit → error.

### GPU degraded mode circuit *(v1.17)*

`control-plane` owns the platform-wide GPU degraded-mode signal. The circuit is a three-state Petri-net encoded in a Postgres row (`platform_state.gpu_degraded`) with fields `state`, `activated_at`, `restored_at`, `activator (auto | operator | test)`.

**Activation.** The `ModelHealthProber` polls each vLLM endpoint every 30 s and computes:
- `queue_depth_seconds` - running estimate from `vllm_queue_time_seconds_p95`.
- `error_rate` - sliding 60 s window.
- `available_replicas` - from Kubernetes readiness gates.

The circuit trips to `DEGRADED` when **any** of:
- `queue_depth_seconds > 5` for 3 consecutive minutes for the primary embedding model in a region, OR
- `error_rate > 0.5` for 60 s on the primary synthesis model, OR
- Operator sets `gateway.degraded_mode = true` via `POST /admin/degraded-mode`.

On trip, `control-plane` writes the new row and publishes on `synanton_platform_state` Kafka topic. `gateway`, `synflux`, `synapt` subscribe and adjust behaviour (see their respective sections).

**Restoration.** The prober requires `queue_depth_seconds < 2` AND `error_rate < 0.05` for 5 consecutive minutes before flipping to `RESTORED`. On restoration:
1. Publish `platform_state.restored` on `synanton_platform_state`.
2. Trigger `RecrawlAfterRestorationWorkflow` (unless `recrawl.schedule = MANUAL`).
3. Wait `recrawl.warmup_seconds` (default 60 s) before the workflow starts issuing GPU work, so any spillover from the incident quiesces.

**Admin endpoints:**

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/admin/degraded-mode` | GET | Current state + trigger reason |
| `/admin/degraded-mode` | POST | Manual set/clear (audited; requires `admin:platform`) |
| `/admin/recrawl/{tenant_id}/status` | GET | Recrawl progress for the given tenant |
| `/admin/recrawl/{tenant_id}/pause` | POST | Pause recrawl (e.g. during a maintenance window) |
| `/admin/recrawl/{tenant_id}/resume` | POST | Resume paused recrawl |

### RecrawlAfterRestorationWorkflow *(v1.17)*

Temporal workflow, one instance per tenant. Fired by the restoration signal, or manually via `synctl recrawl start --tenant`.

**Algorithm:**
```
work_set ← SELECT (tenant_id, content_ref_id, generation)
           FROM manifest
           WHERE embedding_quality IN ('DEGRADED', 'LEXICAL_ONLY')
             AND state = 'INDEXED'
           ORDER BY recrawl_priority(recrawl.priority);   -- default RECENCY_WEIGHTED

for batch in work_set.chunks(recrawl.batch_size):        -- default 100
    per-content activities, concurrency ≤ recrawl.concurrent_tenants × 4:
      1. re-enrich (reuse analysis_cache if hash matches; else full Pass 1 + Pass 2)
      2. re-embed with primary model (skip if embedding_cache hit)
      3. re-index at generation + 1  → synquest+relix see two generations briefly
      4. UPDATE manifest SET embedding_quality='FULL',
                              degraded_restored_at=now(),
                              generation=generation+1
         WHERE content_ref_id=? AND embedding_quality != 'FULL';

heartbeat every 30 s → progress metric.
on activity failure: retry with backoff (10/60/300 s); after 3 permanent failures per
                     content_ref_id, park to synflux_dlq with poison_reason=RECRAWL_FAILED.
```

**Idempotency & ordering** - each activity is keyed by `sha256(tenant_id || content_ref_id || target_generation)` and coordinated through the same idempotency machinery as the router; concurrent recrawl and normal ingest of the same content_ref_id are guaranteed to converge on the higher `generation`.

**Backpressure** - recrawl reads `synflux_embedder_gpu_queue_seconds` before each batch dispatch; if > 3 s, the workflow sleeps 30 s and retries. This prevents recrawl from re-triggering degraded mode.

**Configuration** - governed by the `recrawl.*` keys documented in §17.

### GitOps Reconciler

### GitOps Reconciler
Watches Git repo (webhook + 60 s poll fallback). Applies CRDs / Terraform state to `topology` policy tables. On drift > 5 min → `GitOpsReconcileFailed` page. `fail_closed = true` changes require an explicit admin override marker in the commit.

### Forecast Engine
Prophet primary, ARIMA fallback. Reads Prometheus history; produces 15-min-ahead forecast; updates `forecast_lag_15m` gauge + `recommended_parallelism` ConfigMap; can directly invoke KEDA `ScaledObject`.

### Anomaly Detector
Isolation Forest on `(latency, error_rate, gql_pattern_vector)`. DBSCAN clusters slow query signatures. Recommendations advisory only.

### Cost Aggregator
Consumes `api_usage` Kafka topic. 5-min rollups to PostgreSQL `cost_attribution_daily`. Per-tenant per-day granularity (per-user only if opted in).

### Temporal Workflows
- `OntologyMigrationWorkflow`
- `ReindexWorkflow`
- `GdprCascadeWorkflow`
- `HotShardSplitWorkflow`
- `RecallTunerWorkflow`
- `BackupAndRestoreWorkflow`
- `BackupVerificationWorkflow` *(v1.17)* - weekly restore-into-scratch test; see §47a.
- `LouvainCommunityWorkflow` *(v1.1)* - nightly community detection over the relix graph.
- `GraphInsightsWorkflow` *(v1.1)* - runs after Louvain; emits gaps + surprising connections.
- `OntologyLintWorkflow` *(v1.1)* - dead-link, orphan, duplicate-entity detection.
- `DeepResearchWorkflow` *(v1.1; SLO clarified in v1.17)* - autonomous web research when a knowledge gap is triggered.
- `RecrawlAfterRestorationWorkflow` *(v1.17)* - re-enrich degraded documents once the GPU cluster recovers.

### Graph Insights *(v1.1)*

`GraphInsightsWorkflow` runs after each Louvain community detection batch (Temporal signal dependency) and analyses the graph for:

- **Surprising connections.** Cross-community edges, cross-type links (e.g. `Person→CorporateEvent`), peripheral↔hub couplings. Scored by a composite surprise signal: `1 - modularity_contribution × type_agreement_prior × degree_normalisation`.
- **Knowledge gaps.** Isolated entities (degree ≤ 1), sparse communities (`cohesion < 0.15`, `size ≥ 3`), bridge nodes joining ≥ 3 communities.

Emits events onto the `synanton_anomaly` Kafka topic with `insight_type ∈ {CONTENT_GAP, SURPRISING_CONNECTION, BRIDGE_NODE}`, surfaced in the Admin Console's knowledge health dashboard. Users can trigger `DeepResearchWorkflow` from a `CONTENT_GAP` insight to fill the gap automatically.

Distinguished from the query anomaly detector (§14): that detector watches infrastructure and query patterns; this one watches the knowledge graph itself.

### Deep Research *(v1.1)*

`DeepResearchWorkflow` is triggered either automatically (by `CONTENT_GAP` insights, if `research_policy.autonomous_gap_fill = true`) or manually (from `POST /admin/insights/{id}/research`). Steps:

1. LLM generates a set of web-optimised search queries (distinct from the semantic-similarity queries used inside Synanton).
2. `WebSearchAdapter` SPI (v1.1) dispatches to a configured provider - first-party adapters: Tavily, SerpApi, SearXNG. Additional adapters via the same SPI shape as `ContentAdapter` (§29).
3. Full content extraction (no truncation) via `synvault`'s adapter registry.
4. LLM synthesises findings into a research document with cross-references to existing entities in `syntology`.
5. **Human review gate.** Research topic + queries proposed as a review item (`synreview`, §27a) with action `APPROVE | EDIT | REJECT`. On approval, workflow continues.
6. Research document is ingested as a first-class source through `synflux`, tagged `source_type: web_research`, with a `confidence_tier` (`HIGH | MEDIUM | LOW`) derived from source diversity and citation count.
7. Domain-aware seeding: workflow reads tenant `research_policy.domain_scope_uri` (points to an ontology "purpose" node) to bias queries toward relevant topics.

Queue-based; concurrent research task limit per tenant (`research_policy.concurrent_tasks_max`, default 3).

**SLO (clarified in v1.17).** The v1.16 "p95 end-to-end < 10 min" was ambiguous because it conflated LLM/web fetch time with the human review gate. v1.17 separates the two:

| Metric | Kind | Budget | Rationale |
|--------|------|--------|-----------|
| `deep_research_machine_time_p95` | SLO | ≤ 10 min (shallow); ≤ 60 min (deep) | Excludes wall time spent waiting for human approval in the `DEEP_RESEARCH_GATE` review item. Measured from workflow start to the moment the workflow enters `AWAITING_REVIEW` state, and again from `REVIEW_APPROVED` to `INGESTED`. |
| `deep_research_human_expected_time` | Advisory | 4 h business hours | Median wall time in the review gate. Not an SLO - not on-call actionable. Surfaced as an operator visibility metric only. |
| `deep_research_end_to_end_p95` | Advisory | not enforced | Sum of the above. Rendered in dashboards for capacity planning only. |

**Background queue.** Research tasks now have a `priority` field (`INTERACTIVE | BACKGROUND`, default `INTERACTIVE`). `BACKGROUND` tasks bypass the concurrency limit above but are served out of a separate low-priority queue (`research_policy.background_concurrent_max`, default 1) and are pre-empted by any `INTERACTIVE` task in the same tenant. Use `BACKGROUND` for scheduled autonomous gap-fill; use `INTERACTIVE` for user-triggered research from the Admin Console.

### Ontology Lint *(v1.1)*

`OntologyLintWorkflow` runs after each `synflux` ingest batch (or on demand). It detects content-quality issues that SHACL does not cover:

- **Orphaned entities.** Entity nodes with no incident edges in the `relix` graph.
- **Duplicate entities.** Distinct entity nodes with high cosine similarity between their embeddings but different labels (heuristic: same `rdfs:type`, cosine ≥ 0.92, labels within edit distance 3 OR share ≥ 2 source `content_ref_id`s).
- **Broken references.** `syntology` `rdfs:subClassOf` chains that reference non-existent types.
- **Missing frontmatter.** Entities from `synflux` Pass 2 output missing required ontology-mapped fields.

For duplicate detection, the workflow uses `synanton-llm-client` (§27c) to call an LLM with the candidate pair for a semantic merge judgement, then files a review item in `synreview` (§27a). Human approves → `syntology` executes the merge → `relix` re-computes affected community memberships lazily.

Findings surfaced in the future `syntology-admin` UI's entity health panel.

### Configuration
- `control_plane.forecast.window_minutes` (default 15)
- `control_plane.anomaly.repeat_threshold` (default 3 in 1 h)
- `control_plane.gitops.poll_interval_seconds` (default 60)
- `control_plane.cost.rollup_window_minutes` (default 5)
- `control_plane.insights.job_cron` (v1.1, default `30 2 * * *`; runs 30 min after nightly Louvain)
- `control_plane.insights.publish_kafka_topic` (v1.1, default `synanton_anomaly`)
- `control_plane.research.autonomous_gap_fill_default` (v1.1, default false - per-tenant opt-in)
- `control_plane.research.concurrent_tasks_max` (v1.1, default 3)
- `control_plane.ontology_lint.duplicate_cosine_threshold` (v1.1, default 0.92)
- `control_plane.ontology_lint.trigger` (v1.1, `POST_INGEST_BATCH | HOURLY | MANUAL`, default `POST_INGEST_BATCH`)
- `control_plane.degraded_mode.embed_queue_trip_seconds` (v1.17, default 5)
- `control_plane.degraded_mode.embed_queue_trip_consecutive_minutes` (v1.17, default 3)
- `control_plane.degraded_mode.restore_dwell_seconds` (v1.17, default 300)
- `control_plane.degraded_mode.warmup_seconds` (v1.17, default 60)
- `control_plane.model_health.probe_interval_seconds` (v1.17, default 30)
- `control_plane.research.background_concurrent_max` (v1.17, default 1)
- `control_plane.research.machine_time_p95_shallow_seconds` (v1.17, default 600)
- `control_plane.research.machine_time_p95_deep_seconds` (v1.17, default 3600)

### Metrics
- `control_forecast_exhaustion_days{tenant}`
- `control_anomaly_detection_events{tenant}`
- `control_gitops_reconcile_duration_seconds`
- `control_temporal_workflow_state{workflow,state}`
- `control_insights_emitted_total{tenant,insight_type}` *(v1.1)*
- `control_research_workflow_duration_seconds{outcome}` *(v1.1)*
- `control_research_documents_ingested_total{tenant,confidence_tier}` *(v1.1)*
- `control_ontology_lint_findings_total{tenant,finding_type}` *(v1.1)*
- `gateway_degraded_mode_active` - gauge 0/1, cluster-wide *(v1.17)*
- `control_degraded_mode_transitions_total{from,to,activator}` *(v1.17)*
- `control_model_health_state{model_family,region}` - `1=HEALTHY, 2=DEGRADED, 3=UNAVAILABLE` *(v1.17)*
- `control_recrawl_queue_depth{tenant}` *(v1.17)*
- `deep_research_machine_time_seconds{tenant,depth}` - histogram *(v1.17)*
- `deep_research_gate_wait_seconds{tenant}` - histogram; advisory *(v1.17)*

### Alerts
- `ForecastCostOverrunWarning` - < 7 days exhaustion.
- `ForecastCostOverrunCritical` - < 3 days exhaustion (page).
- `TenantBudgetExhausted` (page).
- `GitOpsReconcileFailed` (page).
- `AnomalyDetectorHighRecall` - > 10 % queries flagged / 1 h.
- `ForecastAccuracyDegraded` - below SLO over rolling 2 h.
- `GraphInsightsBacklog` *(v1.1)* - > 100 unresolved gap insights per tenant.
- `DeepResearchQueueSaturated` *(v1.1)* - 3 concurrent tasks reached and queue depth > 10.
- `OntologyLintDuplicateSpike` *(v1.1)* - 5× baseline duplicate findings in a batch.
- `PlatformDegradedModeActive` *(v1.17)* - `gateway_degraded_mode_active == 1` for > 15 min (warn); > 4 h (page).
- `ModelReplicaUnavailable` *(v1.17)* - `control_model_health_state{...} == 3` for > 5 min for any primary model family in an active region.
- `RecrawlBacklog` *(v1.17)* - `control_recrawl_queue_depth{tenant}` > 100k for > 6 h with `synflux_degraded_recrawl_progress` making forward progress (informational - sizing hint).
- `DeepResearchMachineTimeSloBreach` *(v1.17)* - `deep_research_machine_time_seconds` p95 exceeds configured budget over rolling 1 h.

------

## 27a. Module: `synreview` (Human-in-the-Loop Review System) *(new in v1.1)*

### Role
Central queue for content-quality decisions that require human judgement or LLM-arbitrated auto-resolution. Sits between automation-emitting producers (`synflux`, `syntology`, `control-plane`) and human reviewers, with a two-tier automated sweep to keep the human queue small.

### Motivation
For an enterprise knowledge platform handling regulated content, automated content decisions without review checkpoints are a compliance liability. `synreview` is also the natural integration point for the future `syntology-admin` UI's ontology-editor workflow - new entity/relation type suggestions from `synflux` Pass 2 must flow through review before being committed to `syntology`.

### Responsibilities
- Persist review items with typed action metadata.
- Run a rule-based auto-sweep to resolve trivially clear-cut items.
- Run an LLM-arbitrated auto-sweep to resolve ambiguous but high-confidence items.
- Expose the residual queue to human reviewers via `synapt`.
- Emit ACKs back to the producer when items are resolved (either automatically or manually).

### Review item taxonomy

| Type | Producer | Action set |
|------|----------|-----------|
| `NEW_ENTITY_TYPE` | `synflux` (Pass 2) | `APPROVE_ONTOLOGY_ADD | REJECT` |
| `DUPLICATE_ENTITY_MERGE` | `control-plane` (OntologyLintWorkflow) | `MERGE | KEEP_DISTINCT` |
| `CONTRADICTION` | `synflux` (Pass 1 analysis) | `PREFER_A | PREFER_B | KEEP_BOTH | NEEDS_RESEARCH` |
| `LOW_CONFIDENCE_CHUNK` | `synflux` (Pass 2) | `ACCEPT | RE_ENRICH | DROP` |
| `PII_FLAG` | `synflux` (enrichment / vision) | `REDACT | ALLOW | QUARANTINE` |
| `DEEP_RESEARCH_GATE` | `control-plane` (DeepResearchWorkflow) | `APPROVE | EDIT_QUERIES | REJECT` |
| `TYPE_DEPRECATION_CANDIDATE` | `control-plane` (OntologyLintWorkflow) | `DEPRECATE | RETAIN` |

Producers extend the taxonomy by publishing a `review_item_schema` at module capabilities time - `control-plane` aggregates.

### Two-tier auto-sweep

**Tier 1 - Rule sweep** (fast, deterministic):
- `DUPLICATE_ENTITY_MERGE` with cosine ≥ 0.98 AND identical `rdfs:type` AND ≥ 3 shared sources → auto-`MERGE`.
- `LOW_CONFIDENCE_CHUNK` with confidence ≥ 0.85 AND no PII flags → auto-`ACCEPT`.
- `NEW_ENTITY_TYPE` that duplicates an existing type by URI → auto-`REJECT`.

**Tier 2 - LLM sweep** (semantic, uses `synanton-llm-client`, §27c):
- Items with `auto_sweep_eligible = true` (default true; disabled for `HIGH_SECURITY` tenants and `PII_FLAG` items) receive an LLM prompt with structured context and the action taxonomy.
- LLM returns action + confidence.
- Confidence ≥ `sweep_confidence_threshold` (default 0.9) → auto-resolve **into a 24 h staging queue** (v1.17), not directly into producer callbacks. See below.
- Below threshold → route to human queue.

**Non-blocking.** The auto-sweep is asynchronous. Items queue immediately; ingest never stalls waiting on review.

### Prompt & model versioning *(v1.17)*

Every LLM sweep records the exact prompt + model identity so drift is detectable and past decisions are auditable:

- `prompt_version` - semantic version of the sweep prompt template. Bumped whenever the template changes; templates are stored under source control in `synreview/prompts/` and loaded via a registry at startup. Runtime never uses an unregistered template.
- `model_version` - resolved model identifier from `ModelServingDirectory` at call time, including the underlying weight hash where the provider exposes one (`sha256:…` for vLLM-served models, provider-supplied version tags otherwise).
- `prompt_text_hash`, `response_text_hash` - SHA-256 hashes of the actual serialised prompt (post-template rendering) and the raw response body.
- Full prompt/response text - persisted to S3 (`synreview_audit/` prefix, lifecycle rule: hot 90 d, then Glacier). PostgreSQL row holds only the S3 URI + hashes for storage sanity.

**Retention.** Prompt/response audit records are kept for the tenant's `regulatory_profile.audit_retention` (defaults: `STANDARD` = 1 y, `FINANCIAL` = 7 y, `HEALTHCARE` = 10 y). Hashes stay in PostgreSQL indefinitely so replay can verify the archived object is intact.

**Replay.** Operators can replay a past sweep against a newer model/prompt to detect regression:
```
synctl synreview replay --item-id=<uuid> \
  --prompt-version=v3.2.0 \
  --model-family=synanton-analysis-mid
```
Replay never mutates the production item - it emits a new `replay_events` row and returns a diff report.

### 24-hour staging queue *(v1.17)*

An auto-resolved item does not fire its producer callback immediately. Instead:

1. Item transitions `OPEN → AUTO_STAGED` with `staging_expires_at = now() + synreview.staging.window_hours` (default 24 h).
2. During the staging window, human reviewers can `OVERRIDE` any staged decision. Override transitions to `HUMAN_RESOLVED` with `overridden_action` recorded.
3. On `staging_expires_at`, a Temporal timer fires `commitStaged(item_id)` which transitions to `AUTO_RESOLVED_LLM`/`AUTO_RESOLVED_RULE` and invokes the producer callback.
4. Producer callbacks are the same as v1.16 - the change is only in *when* they fire.

**Fast-path exemptions.**
- Rule-sweep decisions that are wholly reversible (e.g. `LOW_CONFIDENCE_CHUNK → ACCEPT` where the underlying chunk remains recoverable) may bypass staging by setting `synreview.staging.exempt_reversible = true` at the rule level. Default false - safer to stage everything.
- `PII_FLAG` items - never bypass staging. Never even eligible for auto-sweep in the first place.

**Metrics:**
- `synreview_staging_open{tenant,item_type}` gauge - staging queue depth.
- `synreview_override_total{tenant,item_type,orig_action,override_action}` - how often humans reverse the sweep, per-type. High values indicate the LLM prompt or confidence threshold needs tuning.

**Configuration:**
- `synreview.staging.window_hours` (v1.17, default 24)
- `synreview.staging.exempt_reversible` (v1.17, default false)
- `synreview.staging.on_expiry_notify` (v1.17, default `EMAIL`, one of `EMAIL | WEBHOOK | NONE`)

### Interfaces

**Inbound (producer):**
- `SynreviewApi.submit(review_item) → item_id`
- `SynreviewApi.batchSubmit(items[]) → item_ids[]`
- `SynreviewApi.query(tenant, filters, cursor) → Page<ReviewItem>`
- `SynreviewApi.resolve(item_id, action, actor_subject, notes?) → resolution_id`

**Outbound:**
- `ReviewResolutionEvent(item_id, item_type, action, resolver_type: RULE | LLM | HUMAN, actor_subject?)` on `synreview_events` Kafka topic.
- Producer-specific callbacks (e.g. `syntology.mergeEntities` fires on `DUPLICATE_ENTITY_MERGE.MERGE`).

### Data model

```sql
-- schema: synreview (PostgreSQL)
CREATE TABLE review_items (
  item_id             UUID PRIMARY KEY,
  tenant_id           UUID NOT NULL,
  producer_module     TEXT NOT NULL,       -- 'synflux' | 'syntology' | 'control-plane'
  item_type           TEXT NOT NULL,
  payload             JSONB NOT NULL,      -- typed by item_type
  candidate_actions   TEXT[] NOT NULL,
  auto_sweep_eligible BOOLEAN NOT NULL DEFAULT TRUE,
  status              TEXT NOT NULL,       -- OPEN | AUTO_STAGED | AUTO_RESOLVED_RULE | AUTO_RESOLVED_LLM | HUMAN_RESOLVED | REJECTED
  priority            INT NOT NULL DEFAULT 0,
  created_at          TIMESTAMPTZ NOT NULL,
  resolved_at         TIMESTAMPTZ,
  resolver_type       TEXT,                -- RULE | LLM | HUMAN
  actor_subject       UUID,
  action              TEXT,
  overridden_action   TEXT,                -- v1.17 - original auto-decision, if a human overrode during staging
  notes               TEXT,
  -- v1.17 additions --
  prompt_version      TEXT,                -- semver of the sweep prompt template
  model_version       TEXT,                -- resolved model id (may include weight hash)
  prompt_text_hash    BYTEA,               -- sha256 of rendered prompt (32 bytes)
  response_text_hash  BYTEA,               -- sha256 of raw response body
  audit_s3_uri        TEXT,                -- s3://.../synreview_audit/<tenant>/<item_id>.jsonl
  staging_expires_at  TIMESTAMPTZ          -- non-null only while status = AUTO_STAGED
);
CREATE INDEX review_items_open_by_tenant ON review_items(tenant_id, status, priority DESC, created_at)
  WHERE status = 'OPEN';
CREATE INDEX review_items_staging_by_expiry ON review_items(staging_expires_at)
  WHERE status = 'AUTO_STAGED';

CREATE TABLE review_replay_events (
  replay_id           UUID PRIMARY KEY,
  original_item_id    UUID NOT NULL REFERENCES review_items(item_id),
  replayed_at         TIMESTAMPTZ NOT NULL,
  actor_subject       UUID NOT NULL,
  prompt_version      TEXT NOT NULL,
  model_version       TEXT NOT NULL,
  replay_action       TEXT NOT NULL,
  original_action     TEXT NOT NULL,
  diverged            BOOLEAN NOT NULL,
  audit_s3_uri        TEXT NOT NULL
);
```

### Configuration
- `synreview.rule_sweep.enabled` (default true)
- `synreview.llm_sweep.enabled` (default true)
- `synreview.llm_sweep.model_family` (default `synanton-analysis-mid`)
- `synreview.llm_sweep.confidence_threshold` (default 0.9)
- `synreview.human_queue.max_open_per_tenant` (default 10000; back-pressures producers when reached)
- `synreview.high_security.disable_llm_sweep` (default true)

### Metrics
- `synreview_open_items{tenant,item_type}` gauge
- `synreview_resolution_total{tenant,item_type,resolver_type,action}`
- `synreview_llm_sweep_confidence_histogram{item_type}`
- `synreview_sweep_latency_seconds{tier}`
- `synreview_staging_open{tenant,item_type}` gauge *(v1.17)*
- `synreview_override_total{tenant,item_type,orig_action,override_action}` *(v1.17)*
- `synreview_replay_diverged_total{tenant,item_type}` *(v1.17)*
- `synreview_audit_write_failures_total{tenant}` *(v1.17)* - S3 audit persistence failures; must be zero-tolerance

### Alerts
- `SynreviewHumanBacklogHigh` - > 1000 open human items for > 4 h.
- `SynreviewSweepConfidenceLow` - LLM sweep mean confidence < 0.7 for > 1 h (may indicate model drift).
- `SynreviewStagingBacklog` *(v1.17)* - > 5000 items in `AUTO_STAGED` for a tenant (indicates humans aren't reviewing before commit).
- `SynreviewOverrideRateHigh` *(v1.17)* - `override / staging_commit` ratio > 15 % over 24 h for a single item_type (prompt drift signal).
- `SynreviewAuditWriteFailure` *(v1.17)* - `synreview_audit_write_failures_total > 0`.

### Failure modes
- Rule sweep worker crash → items sit in `OPEN`; no data loss.
- LLM sweep failure → items retain `OPEN` status; retried with exponential backoff; three consecutive failures escalate to human queue.
- Human resolver acts on stale item (e.g. entities merged before human decision) → producer callback returns `ERR_STALE_REVIEW`; item marked `REJECTED` with reason.

------

## 27b. Module: `synanton-mcp` (MCP Protocol Bridge) *(new in v1.1)*

### Role
A thin protocol adapter that exposes Synanton's public capabilities as MCP tools by calling the `synapt` public API - **no business logic in the bridge itself**. Runs as a separate Node.js process (packaged as an npm-published binary), independent of the JVM data plane.

### Motivation
`relix` already hosts native MCP/ACP endpoints for graph-specific tools. `synanton-mcp` is complementary - it exposes broader platform capabilities (search, entity read, review workflow, capability discovery) to agents that speak MCP but not gRPC/REST. Bridge pattern gains:
- Search, graph, and review endpoints exposed to agents through the same authoritative code path as human users - no ranking or ACL divergence.
- MCP tool versioning and lifecycle decoupled from core service versions.
- Node.js process boundary shields core services from MCP protocol churn.

### Tool surface
| Tool | Underlying synapt call |
|------|------------------------|
| `synanton_search` | `POST /search` |
| `synanton_graph` | `POST /search` with `{"legs": ["graph"]}` |
| `synanton_read_entity` | `GET /entities/{id}` |
| `synanton_reviews` | `GET /reviews`, `POST /reviews/{id}/resolve` (subject-scoped) |
| `synanton_capabilities` | `GET /capabilities` (aggregate across modules) |
| `synanton_ingest_submit` | `POST /ingest` (subject-scoped, subject to synapt rate limits) |

### Auth
The bridge does not mint credentials. Every MCP request carries an inbound token that the bridge forwards on to `synapt`; ACL, tenant resolution, budget, and audit are enforced by `synapt` unchanged. On session revocation, the standard MCP session revalidation worker in `security` (§26) invalidates the bridge session identically to any other MCP session.

### Deployment
Full and Standalone profiles ship `synanton-mcp` as a first-party sidecar. Embedded profile omits it (host applications register their own MCP tools with the embedded library directly).

### Configuration
- `synanton_mcp.synapt.base_url` (per environment)
- `synanton_mcp.timeouts.default_ms` (default 5000)
- `synanton_mcp.rate_limit.per_session_qps` (default 20)
- `synanton_mcp.log_level` (default `info`)

### Metrics
- `synanton_mcp_tool_calls_total{tool,outcome}`
- `synanton_mcp_upstream_latency_seconds{tool}`
- `synanton_mcp_session_count{state}`

### Non-goals
- No caching (`gateway` already handles it).
- No response reshaping beyond the JSON/MCP-envelope translation.
- No auth logic (fully delegated to `security` / `synapt`).

------

## 27c. Library: `synanton-llm-client` (Provider-Agnostic LLM Client) *(new in v1.1)*

### Role
Shared library that centralises wire-format translation across LLM providers. Consumed by every service that emits an LLM call (`synflux` Pass 1/2, `synreview` LLM sweep, `gateway` synthesis, `control-plane` Deep Research, `syntology` merge judgement).

### Motivation
`ModelServingDirectory` (§27) resolves `(model_family, region) → endpoint`, but that is a service-directory abstraction - it does not solve the **wire-format** problem at call time. Each of OpenAI-compatible vLLM, Anthropic Messages API, Google Vertex AI, and Azure OpenAI expects different request shapes (e.g. Gemini nests under `generationConfig`; Azure OpenAI uses query-parameter auth). Without a shared library, this translation would be re-implemented inconsistently across services.

### API (Java surface; a companion TypeScript build ships for `synanton-mcp` and future UI use)

```java
public interface LlmClient {
    ChatResponse chat(ChatRequest req);
    Stream<ChatChunk> streamChat(ChatRequest req);
    EmbedResponse embed(EmbedRequest req);
}

public record ChatRequest(
    String tenantId,
    String userSubject,             // null if cost_privacy disables
    String modelFamily,             // resolved via ModelServingDirectory
    List<Message> messages,
    RequestOverrides overrides
) {}

public record RequestOverrides(
    Double temperature,
    Double topP,
    Integer maxTokens,
    Integer thinkingBudgetTokens,   // Anthropic extended thinking / OpenAI reasoning_effort mapping
    Set<String> stopSequences,
    List<ToolDefinition> tools,
    Map<String, Object> providerHints  // escape hatch, ignored by other providers
) {}
```

### Provider translators
First-party translators:
- `VllmOpenAiCompatibleTranslator`
- `AnthropicMessagesTranslator`
- `GoogleVertexAiTranslator`
- `AzureOpenAiTranslator` (query-parameter auth handled here, not by service callers)

Each translator maps `RequestOverrides` into its native wire format and returns a normalised `ChatResponse` / `ChatChunk`.

### Provider negotiation *(v1.17)*

v1.16 assumed a **single** `provider_type` per endpoint. In practice, many endpoints expose more than one format - a vLLM cluster typically speaks both `VllmOpenAiCompatible` and `VllmNative`; some deployments front vLLM with an Anthropic-compatible adapter; managed services (Azure OpenAI, Google Vertex) sometimes expose two spellings of the same API for migration windows.

v1.17 `ModelServingDirectory.resolve()` returns a **set** of supported provider types plus an optional preference order, and `synanton-llm-client` negotiates the best match on its side:

```java
public record ModelEndpoint(
    String endpoint,
    URI    baseUri,
    List<ProviderType> supportedProviders,     // ordered preference from the directory
    Optional<String>   modelWeightsHash,       // sha256:... where available
    List<String>       replicaRegions,         // v1.17 follow-the-sun
    HealthStatus       health                  // HEALTHY | DEGRADED | UNAVAILABLE
) {}

// Negotiation, resolved once per (client-instance, endpoint):
ProviderType pick = supportedProviders.stream()
    .filter(client::hasTranslator)              // client-side capability
    .filter(p -> client.featuresRequired(req)   // e.g. streaming, tool use, thinking
                       .stream()
                       .allMatch(f -> providerSupports(p, f)))
    .findFirst()
    .orElseThrow(() -> new NoCompatibleProviderException(supportedProviders));
```

**Feature negotiation.** For each call, the client computes the required feature set from the `ChatRequest` (needs streaming? tool use? extended thinking?) and drops providers that cannot meet it. The `ProviderCapabilityMatrix` (constant per provider type, published alongside the translator) is the source of truth.

**Caching.** Successful negotiations are cached for `synanton_llm_client.provider_cache_ttl_seconds` (default 300) per `(endpoint, feature_set)`. Cache is invalidated on `HealthStatus` transition on the endpoint.

**Failure semantics.**
- No compatible provider → `ERR_NO_COMPATIBLE_PROVIDER` returned to caller. Alert `LlmClientNoCompatibleProvider` if this fires more than once per tenant per hour.
- Provider fails mid-call → circuit-breaker trips per-endpoint; on subsequent calls the client falls back to the next provider in `supportedProviders`.
- All providers fail → circuit-breaker trips for the whole endpoint; caller sees `ERR_ENDPOINT_UNAVAILABLE`. `synanton-llm-client` does **not** cross-endpoint failover - that decision belongs to the planner (§22 follow-the-sun) and cost accounting layer.

**Session affinity.** For stateful calls (multi-turn synthesis, Deep Research turns after the first), the client sends a `X-Synanton-Session-Affinity: <replica_id>` header; endpoints that honour it pin the session to the same underlying replica, preserving KV cache. The header is generated on first call and stored in the caller's `SessionAffinity` context (populated by `planner` per §43).

### Reasoning-block detection
The library detects `<think>` blocks (DeepSeek, QwQ) and Anthropic extended-thinking blocks in streaming responses and routes them to a separate `thinking_chunks[]` channel of the `ChatChunk`. Callers may forward these to the future `syntology-admin` UI (see §46a) or drop them.

Cost attribution: thinking-token counts are surfaced via `ChatResponse.thinking_tokens`, added to the `api_usage` event as a distinct field (billed separately by some providers).

### Configuration
- `synanton_llm_client.default_timeout_ms` (default 30000)
- `synanton_llm_client.stream_read_timeout_ms` (default 60000)
- `synanton_llm_client.circuit_breaker.failure_threshold` (default 5)
- `synanton_llm_client.provider_cache_ttl_seconds` (v1.17, default 300)
- `synanton_llm_client.session_affinity.enabled` (v1.17, default true)
- Per-provider retry policies configured via `retry_policy.{provider}` entries.

### Metrics (emitted by callers)
Since the library is embedded, it emits metrics under the caller's module prefix. Standard fields include: `model_family`, `provider_type`, `outcome`, `input_tokens`, `output_tokens`, `thinking_tokens`, `latency_ms`.

### Testing
`synanton-llm-client` ships with:
- Unit tests (mocked HTTP) covering wire-format round-trips for every translator.
- Real-LLM integration tests (see §48a) that hit live endpoints from CI on merge to main.

------

# Part IV - Contracts & SPIs

------

## 28. Relix Graph Connector SPI v1.0

**There is no `v1alpha`, no `v1alpha2`, no compatibility mode.** Every connector - first-party or third-party - implements v1.0.

**Validation *(v1.18)*.** All Part IV `.proto` files carry `protoc-gen-validate` (PGV) rules on string, numeric, and repeated fields. A shared `PgvValidatingServerInterceptor` (`synanton-grpc-validation` library) is registered on every gRPC server built from this SPI - it invokes the generated `Validator.check()` on each incoming message and short-circuits with `Status.INVALID_ARGUMENT` when a constraint fails, attaching the failing field path and rule via `com.google.rpc.Status.details`. The same interceptor is registered on outbound-call `Channel`s in test builds so that internal violations surface during acceptance testing. See `§28-§32 note` below.

### Protocol

```protobuf
syntax = "proto3";
package synanton.relix.spi.v1;
import "validate/validate.proto";

service GraphConnectorService {
  rpc ExecuteGraphQuery   (GraphQueryRequest)        returns (GraphQueryResponse);
  rpc ExecuteBulkMutation (stream MutationRequest)   returns (MutationResponse);
  rpc GetEngineDescriptor (DescriptorRequest)        returns (EngineDescriptor);
}

message MutationRequest {
  string  tenant_id        = 1 [(validate.rules).string.pattern = "^[a-zA-Z0-9_-]{1,64}$"];
  string  idempotency_key  = 2 [(validate.rules).string = {min_len: 1, max_len: 256}];  // REQUIRED
  oneof op {
    UpsertNode upsert_node = 10;
    UpsertEdge upsert_edge = 11;
    DeleteNode delete_node = 12;
    DeleteEdge delete_edge = 13;
  }
}

message EngineDescriptor {
  string                region                   = 1;  // REQUIRED
  PatternCoverage       patterns                 = 2;  // REQUIRED
  bool                  idempotent_bulk_mutation = 3;  // MUST be true
  ConnectorCostProfile  measured_cost            = 4;  // Continuous self-measurement
  string                connector_version        = 5;
  map<string, double>   edge_signal_weights      = 6;  // v1.1 - direct_link, source_overlap, co_occurrence, type_affinity
  bool                  supports_community_id    = 7;  // v1.1 - connector persists community_id property
  bool                  supports_source_ref_count = 8; // v1.1 - connector maintains source_ref_count atomically
}

message PatternCoverage {
  map<string, PatternLevel> by_pattern = 1;  // pattern_name → NATIVE/FALLBACK/EMULATED
}

enum PatternLevel {
  PATTERN_LEVEL_UNSPECIFIED = 0;
  NATIVE                    = 1;
  FALLBACK                  = 2;
  EMULATED                  = 3;
}

message ConnectorCostProfile {
  map<string, CostBucket> by_pattern = 1;
}

message CostBucket {
  int64 p50_ns_per_op = 1;
  int64 p99_ns_per_op = 2;
  int64 sample_count  = 3;
}
```

### Acceptance test suite
Shipped as `relix_connector_acceptance_tests.jar`. Third-party authors run it locally and in CI. Tests verify:
- Idempotency under duplicate `idempotency_key`.
- Crash recovery (kill mid-stream; rerun; no double writes).
- Pattern coverage honesty (declared NATIVE patterns produce expected query shapes).
- Cost profile emission.
- Region declaration.
- *(v1.1)* Edge-signal metadata persistence: if `edge_signal_weights` non-empty, upserts read back with `edge_relevance` and per-signal scores.
- *(v1.1)* Community-id property: if `supports_community_id = true`, node property survives round-trip.
- *(v1.1)* Source ref count atomicity: property-based test exercises N concurrent grants/revokes; count never drifts.

**Backwards compatibility.** The three new `EngineDescriptor` fields default to zero/false - v1.0 connectors continue to satisfy the SPI. Relix treats missing capabilities as "compute in relix" (edge signals) or "unavailable, log-and-degrade" (community id, source_ref_count).

### First-party connectors at GA
- `Neo4jConnector` (Cypher/Bolt).
- `NeptuneConnector` (Gremlin/TinkerPop).
- `InMemoryConnector` (Java heap, for tests and embedded).

------

## 29. Content Adapter SPI

```java
public interface ContentAdapter {
    AdapterDescriptor descriptor();
    Stream<byte[]> pull(ContentRef ref, ContentCursor cursor);
    void push(ContentRef ref, byte[] payload);  // optional
    ContentCursor resume(ContentCursor cursor);
}

public record AdapterDescriptor(
    String adapterId,
    Set<String> supportedSchemes,   // "s3", "filenet", "https", "sharepoint"
    boolean supportsPush,
    boolean supportsCdc
) {}

public record ContentCursor(
    String adapterId,
    String continuationToken,
    Instant highWaterMark
) {}
```

First-party adapters: `S3Adapter`, `FileNetAdapter`, `RdbmsAdapter`, `FilesystemAdapter`, `SharePointAdapter`, `KafkaCdcAdapter`, `WebhookAdapter`.

### WebSearchAdapter SPI *(v1.1)*

A parallel SPI for the Deep Research workflow. Same shape as `ContentAdapter` for consistency; separate namespace to keep the ingest and research code paths clean.

```java
public interface WebSearchAdapter {
    WebSearchDescriptor descriptor();
    List<WebSearchResult> query(WebSearchRequest req);
    Stream<byte[]> fetchFull(String url);  // no truncation
}

public record WebSearchRequest(
    String tenantId,
    String query,
    int maxResults,
    Optional<String> domainScopeUri
) {}

public record WebSearchResult(
    String url,
    String title,
    String snippet,
    Optional<Instant> publishedAt
) {}
```

First-party adapters: `TavilyAdapter`, `SerpApiAdapter`, `SearxngAdapter`. Additional adapters implement the SPI directly - no compatibility mode.

------

## 30. Reranker Port

```java
public interface RerankerPort {
    RerankResult rerank(RerankRequest req);
    RerankerDescriptor descriptor();
}

public record RerankRequest(
    String tenantId,
    String userSubject,   // null if cost_privacy disables
    String query,
    List<Hit> candidates,
    String modelId,
    int topN
) {}

public record RerankResult(
    List<Hit> reorderedHits,
    boolean cached,
    long gpuMs,
    String model
) {}
```

First-party adapters: `VllmCrossEncoderRerankAdapter`, `CohereRerankAdapter`, `VoyageRerankAdapter`.

------

## 31. Identity Provider Port + Outbound Auth Broker

```java
public interface IdentityProviderPort {
    SubjectAssertion authenticate(InboundToken token);
    ValidationResult validate(SubjectAssertion assertion);
    WorkerAssertion issueWorker(String jobId, SubjectAssertion subject);
    WorkerAssertion renewWorker(String jobId);  // may throw ERR_SUBJECT_REVOKED
}

public interface OutboundAuthBroker {
    OutboundToken exchange(
        SubjectAssertion subject,
        String audience,
        Set<String> scopes,
        OutboundAuthProfile profile
    );
}

public enum OutboundAuthProfile {
    USER_SUBJECT,      // RFC 8693 with calling user identity
    SERVICE_ACCOUNT,   // Tenant SA
    MTLS,              // Mutual TLS
    API_KEY            // Tenant-managed API key
}

public enum ExternalAclTrust {
    ENFORCE_LOCAL_ONLY,  // Trust only Synanton ACL
    TRUST_EXTERNAL,      // Trust upstream RBAC
    DUAL                 // Both must agree (default)
}
```

------

## 32. ACL Propagation Port

```java
public interface AclPropagationPort {
    void notify(AclEvent event);  // OUT-OF-TRANSACTION ONLY
}

public record AclEvent(
    UUID grantId,
    String orgId,
    String subjectId,
    String resourceId,
    String permission,
    EventType type    // GRANTED | REVOKED
) {}
```

Consumers: `synquest`, `gateway`, `relix`.

------

### §28-§32 note: gRPC validation with protoc-gen-validate *(v1.18)*

The four gRPC-backed SPIs (`§28 Relix Graph Connector`, `§29 Content Adapter` and companion `WebSearchAdapter`, `§30 Reranker Port`, `§31 Identity Provider Port + Outbound Auth Broker`, `§32 ACL Propagation Port`) all adopt `protoc-gen-validate` (PGV) as the canonical structural-validation mechanism at the gRPC boundary. This complements - it does not replace - the REST-boundary JSON sanitisation defined in §24.

**Rule catalogue.** The proposal codifies the following canonical rules; every message in the Part IV surface that mentions one of these logical field names MUST carry the corresponding rule:

| Logical field | PGV rule |
|---|---|
| `tenant_id`, `user_subject` | `(validate.rules).string.pattern = "^[a-zA-Z0-9_-]{1,64}$"` |
| `content_ref_id`, `chunk_id`, `entity_id`, `grant_id` | `(validate.rules).string.uuid = true` |
| `idempotency_key` | `(validate.rules).string = {min_len: 1, max_len: 256}` |
| `query_text` | `(validate.rules).string.max_len = 10000` |
| Free-text (`display_name`, `title`) | `(validate.rules).string.max_len = 1024` |
| Bag-o-strings (`allowed_regions`, `scopes[]`) | `(validate.rules).repeated = {min_items: 0, max_items: 64, unique: true}` |
| URLs | `(validate.rules).string.uri = true` |
| Emails | `(validate.rules).string.email = true` |
| Integer paging (`top_n`, `page_size`) | `(validate.rules).int32 = {gte: 1, lte: 10000}` |

**Interceptor.** All gRPC servers register the shared `PgvValidatingServerInterceptor` from the `synanton-grpc-validation` library. Behaviour:

1. Read the incoming message.
2. If the message class implements the PGV-generated `Validator` interface, invoke `Validator.check(message)`.
3. On success, forward to the next handler.
4. On failure, respond with `Status.INVALID_ARGUMENT`, attach `com.google.rpc.BadRequest` with `field_violations[]` (field path + rule + human message), and increment `grpc_validation_failed_total{service, method, field, error}`.

**No `@AllowHtml` at the gRPC boundary.** Sanitisation is a REST-only concern (§24) because internal gRPC callers are in the trusted zone (see §4 trust zones). External gRPC callers, if any, are constrained by PGV's structural rules; any string that will subsequently be rendered in the admin UI passes through a UI-side DOMPurify layer (see §48b).

**Feature flag.** `grpc.validation.enabled` (default `true`) can disable the interceptor per-service for debugging. Test builds always run with it enabled; the acceptance-test suite in §28 verifies PGV rules are declared for every string field.

**Metrics & alerts.** See §45; the observability suite gains `grpc_validation_failed_total` and an aggregated alert `GrpcValidationBurst` (`> 100 failures / minute across all services`) to catch broken clients or attempted probing.

**Backward compatibility.** Every rule listed above is at or above the constraints already enforced informally by consumers. The Relix connector acceptance-test suite (§28) is extended with a `pgv_rule_compliance` case that fails a connector build if the `.proto` misses any required rule.

------

## 33. Module Capability Descriptor

Every module exposes `GET /capabilities`:
```json
{
  "module_id": "relix",
  "display_name": "Relix",
  "module_version": "1.15.0",
  "schemas": {
    "input":  [{"name": "MutationRequest", "version": "1.0", "compat": "BACKWARD_TRANSITIVE"}],
    "output": [{"name": "MutationResponse", "version": "1.0"}]
  },
  "features": {
    "graph_rag":           {"enabled": true, "since": "1.0.0"},
    "materialized_views":  {"enabled": true, "since": "1.13.0"},
    "emulated_traversal":  {"enabled": true, "since": "1.13.0"}
  },
  "deprecated": []
}
```

Control-plane aggregates capabilities on a 30 s cadence; detects version skew, removed-without-deprecation, schema drift. Enables agents and planners to discover features dynamically.

------

## 34. Long-Running Task Framework (`JobHandle`)

All long-running operations (reindex, synthesis, content pulls, DLQ retries, ontology migrations) surface the same contract:

```java
public interface JobHandle {
    String jobId();
    JobState state();   // PENDING | RUNNING | COMPLETED | FAILED | CANCELLED
    double progress();  // 0.0 .. 1.0
    void cancel();
    Optional<JobError> error();
}
```

Per-tenant quotas: 8 concurrent jobs, 3 per user, 64 queued. Idempotency-key dedup on `canonical_payload + tenant_id + acl_scope` eliminates duplicate work from network retries.

------

# Part V - Data Model

------

## 35. PostgreSQL Schema (`topology`, audit, jobs, cost)

### `topology` schema
- `organizations`
- `spaces`
- `projects`
- `folders`
- `users`
- `groups`
- `group_members`
- `acl_grants`
- `topology_outbox`

### `audit` schema
- `admin_audit` - every admin API call (actor, action, resource, before/after, timestamp). *(v1.19 adds `before_state_hash BYTEA` and `after_state_hash BYTEA` columns, populated for every state-changing `/admin/_internal/*` call - see §26b `helper`.)*
- `security_outbound_audit` - every RFC 8693 exchange.

### `security` schema *(v1.19)*
- `roles` - RBAC role catalogue. v1.19 adds the `support_admin` row; it is a well-known constant, not a customer-assignable role. See §26 for the grant/deny matrix.
- `role_assignments` - `(principal_id, role, assigned_by, assigned_at, expires_at, justification)`. The `role` column has `CHECK (role IN ('tenant_admin', 'tenant_user', 'ops_admin', 'support_admin', 'break_glass'))`. Break-glass rows are required to carry `expires_at IS NOT NULL AND expires_at <= now() + interval '24 hours'`.
- `api_keys` - see §26a; unchanged shape in v1.19 (support keys are distinguished by prefix, not by column).

### `jobs` schema
- `long_running_jobs` - `JobHandle` persistence.

### `cost` schema
- `cost_attribution_daily(tenant_id, user_subject?, date, embedder_gpu_ms, synthesis_gpu_ms, reranker_gpu_ms, cross_region_bytes, cache_hits, context_tokens_used, thinking_tokens_used, …)` *(v1.1 adds token fields)*
- `budget_state(tenant_id, period, consumed, cap, alerted_thresholds[])`

### `synreview` schema *(v1.1)*
- `review_items` - see §27a. Indexed for fast open-queue queries per tenant.
- `review_resolutions` - per-resolution audit trail (resolver_type, before/after state, timestamp).

### `insights` schema *(v1.1)*
- `graph_insights` - persistent history of surfaced insights (`insight_type`, `graph_snapshot_ref`, `resolved_at`).
- `research_workflows` - Deep Research task registry (`workflow_id`, `trigger`, `queries`, `confidence_tier`, `outcome`).
- `ontology_lint_findings` - durable log of lint findings and their disposition.

### Backup
- pg_basebackup nightly + continuous WAL to S3. RPO 15 min.
- `BackupAndRestoreWorkflow` validated weekly with restore dry-run.

------

## 36. Cassandra / ScyllaDB Schema (`ingestion-cache`)

| Keyspace | Table | PK | TTL |
|----------|-------|-----|-----|
| `ingestion_cache` | `manifest` | `(tenant_id, content_ref_id)` | none |
| `ingestion_cache` | `chunks` | `(tenant_id, content_ref_id, chunk_index)` | truncated on tier move |
| `ingestion_cache` | `embedding_content_cache` | `(tenant_id, chunk_text_hash)` | 30 days LRU |
| `ingestion_cache` | `reranker_cache` | `(tenant_id, query_hash, hit_id_hash, model)` | 30 min |
| `ingestion_cache` | `synthesis_cache` | `(tenant_id, fingerprint, ontology_version, model)` | 1 h |
| `ingestion_cache` | `source_digests` *(v1.1)* | `(tenant_id, source_sha256)` | none (evicted on delete) |
| `ingestion_cache` | `analysis_cache` *(v1.1)* | `(tenant_id, canonical_sha256, analysis_model)` | 90 d |
| `ingestion_cache` | `image_caption_cache` *(v1.1)* | `sha256(png_bytes)` (optionally shared) | 365 d |

### Backup
- `nodetool snapshot` + incremental backups to S3 nightly. RPO 30 min.

------

## 37. Kafka Topics & Compatibility Rules

| Topic | Producer | Consumer | Compatibility | Retention |
|-------|----------|----------|----------------|-----------|
| `synvault_content_events` | synvault | synflux | FULL | 7 d |
| `synflux_enriched_chunks` | synflux | synflux-router | FULL | **≥ 30 d recommended (soft floor)** |
| `synflux_dlq` | synflux | operator tooling | FULL | 30 d |
| `topology_events` | topology | synquest, gateway, relix | FULL | 7 d |
| `synanton_anomaly` | gateway | control-plane | BACKWARD_TRANSITIVE | 7 d |
| `api_usage` | gateway, synapt | control-plane | BACKWARD_TRANSITIVE | 30 d |
| `module_capabilities` | every module | control-plane | BACKWARD_TRANSITIVE | 30 d |
| `security_outbound_audit` | security | audit sink | FULL | 1 y |
| `synreview_events` *(v1.1)* | synreview | producers (synflux, syntology, control-plane) | BACKWARD_TRANSITIVE | 30 d |
| `synanton_insights` *(v1.1)* | control-plane | Admin Console, `synreview` | BACKWARD_TRANSITIVE | 30 d |
| `synanton_platform_state` *(v1.17)* | control-plane | gateway, synflux, synapt | BACKWARD_TRANSITIVE | 7 d |

### Retention floor policy *(v1.17)*

v1.16 refused to start when `synflux_enriched_chunks` retention fell below the 30-day floor unless `synflux.router.allow_short_retention = true`. That produced a bootstrap deadlock: an operator with a full disk needed to bring the platform up to drain the topic, but couldn't bring it up to loosen retention.

v1.17 relaxes this to a **warn-and-start default**:
- Default (`synflux.router.strict_retention = false`) - platform starts with `retention.ms < 30d`, emits an audited startup warning and the continuous alert `SynfluxRouterShortRetention` (§17) until the floor is restored. `synflux_router_short_retention{topic}` gauge exposes the current state per topic.
- Strict (`synflux.router.strict_retention = true`) - restore v1.16 behaviour. Recommended for regulated tenants where audit expects the floor.
- `synflux.router.allow_short_retention` remains as a v1.16-compat alias for the strict path inverse; deprecated in v1.17 with removal earliest 1.20 (see §24 policy).

**Recommended per-topic minimums** (violations only warn, do not refuse):

| Topic | Rationale | Minimum |
|-------|-----------|---------|
| `synflux_enriched_chunks` | Router replay horizon; longer minimum survives longest downstream outage | 30 d |
| `security_outbound_audit` | Compliance | 1 y |
| `synreview_events` | Sweep replay / audit | 30 d |
| others | ~7 d suffices for reactive consumers | 7 d |

------

## 38. Redis Keyspaces

| Keyspace | Purpose | TTL |
|----------|---------|-----|
| `session:{session_id}` | MCP/ACP session state | 24 h |
| `scope_bundle:{subject}` | Materialised ScopeBundle | 5 min |
| `idp_status:{subject}` | IdP amortization cache | 5 / 60 s |
| `mgv:{tenant_id}:{view_name}` | MGV delta cache | per-view |
| `cuckoo:{tenant_id}:{shard_id}` | In-memory Cuckoo filters | persistent |

GDPR: `gateway` invalidates `session:*` and `scope_bundle:*` synchronously on cascade.

------

## 39. Object Storage Layout (S3 / Glacier)

```
s3://synanton-warm/{tenant_id}/{content_ref_id}/{chunk_index}.bin
s3://synanton-cold/{tenant_id}/{content_ref_id}/{chunk_index}.bin   (Glacier-class)
s3://synanton-backups/postgres/{cluster}/{date}/wal/...
s3://synanton-backups/cassandra/{cluster}/{date}/snapshot/...
```

Lifecycle policies move `synanton-warm` → `synanton-cold` after `warm_retention_days`.

------

# Part VI - Cross-Cutting Concerns

------

## 40. Identity, ACL, and Compile-Time Injection

### Three-layer ACL enforcement
1. **Compile-time injection (gateway).** ACL clauses materialized as `Must` / `TermFilter` clauses at intent → SearchQuery translation.
2. **Pre-filter (synquest).** Cuckoo filter ACL pre-filter for HIGH_SECURITY (O(1) update on revocation).
3. **Final-trim (gateway).** Defence-in-depth check on top-N hits.

### Synthesis cache invariance
`QueryNormaliser` strips a published `ACL_FIELD_SET` constant before hashing. Fingerprint is invariant across injection modes; regression test enforces parity.

### Inbound IdP
Single `IdentityProviderPort`. Composite providers (`CompositeIdentityProvider`) allowed (e.g., federated + local). **Security never writes topology directly.**

### Outbound auth
`OutboundAuthBroker` resolves credentials per `OutboundAuthProfile`. `ExternalAclTrust = DUAL` is default safe mode.

------

## 41. Multi-Tenancy and Isolation Tiers

### Tiers
- `STANDARD` - logical isolation; cross-tenant cache enabled.
- `HIGH_SECURITY` - physical isolation; Cuckoo ACL pre-filter mandatory; synchronous propagation; outbound cache disabled.

### Resource isolation
- Per-tenant Kafka partitions.
- Per-tenant Cassandra keyspaces (HIGH_SECURITY) or shared with tenant-prefixed PKs (STANDARD).
- Per-tenant vLLM model serving allowed (control-plane resolves via ModelServingDirectory).

### Cost isolation
- Per-tenant budget caps.
- Forecast burn warnings (7 d / 3 d).
- HTTP 429 at 100 %.

------

## 42. Schema Migration Discipline (N-2)

Every schema change traverses three releases:
- **N** - dual-write enabled.
- **N+1** - backfill complete; reads switchable.
- **N+2** - old shape removed.

**CI gate `schema-diff`** rejects destructive DDL on Cassandra and Neo4j alike. Session-pinned `ontology_version` allows agents holding open MCP sessions to see compatibility projections through the rollout. `POST /admin/sessions/expire-pinned` force-terminates pinned sessions for maintenance windows.

------

## 43. Cross-Region & Data Residency

### Policy
Per tenant: `data_residency_policy = {allowedRegions, failClosed, appliesTo}`. Query-level override via `SearchQuery.residency`.

### Enforcement
- **Planner-side** (single owner). `synquest` shards publish region; planner drops or fails per policy.
- `ERR_DATA_RESIDENCY_VIOLATION` with audit trail on `failClosed = true`.
- Warning header `residency-filtered: {target}` on `failClosed = false`.

### Cross-region latency map *(v1.17)*

The planner's cost estimator no longer assumes a uniform 50 ms per hop. The **cross-region penalty map** is stored per-tenant in `topology.tenant_policy.cross_region_penalty_ms JSONB` and resolved with the layering rules in §22. The platform-default map is refreshed hourly from measured p95 RTTs between `synquest` shards:

| From ↓ / To →  | us-east-1 | us-west-2 | eu-west-1 | ap-southeast-1 |
| -------------- | --------- | --------- | --------- | -------------- |
| us-east-1      | -         | 60        | 90        | 210            |
| us-west-2      | 60        | -         | 145       | 130            |
| eu-west-1      | 90        | 145       | -         | 175            |
| ap-southeast-1 | 210       | 130       | 175       | -              |

Symmetric entries are stored explicitly rather than derived - asymmetric routing (e.g. via customer-owned MPLS backbones) is realistic and must be representable.

Consequences for other subsystems:
- **Budget forecasting.** `estimated_cross_region_bytes × penalty_ms` becomes part of the p95-latency budget cost model in §44.
- **Federation adapter selection.** When multiple federated targets can serve a query, the planner picks the one with the lowest resolved penalty from the caller's active region.
- **Ingestion.** `synflux` router prefers same-region embedder replicas by default (`synflux.embedder.region_pin = SAME_REGION`) unless embedding capacity in-region is saturated, in which case the router falls back through the penalty ladder.

### Follow-the-sun model serving *(v1.17)*

`ModelServingDirectory` (§27) now maintains a `replicas[]` list per `(model_family)` with one entry per active region. Replicas are added or removed by the AI-Ops operator or by an autoscaler policy that observes tenant traffic distribution.

- **Stateless inference** - embedding, reranking, one-shot generation - routed to the lowest-penalty *healthy* replica meeting residency constraints.
- **Stateful sessions** - multi-turn synthesis, Deep Research - pinned to the initial replica for `planner.follow_the_sun.session_affinity_ttl_seconds` (default 15 min) via the `SessionAffinity` header (§27c). If the pinned replica goes `DEGRADED` or `UNAVAILABLE`, the session falls back to the next replica in the penalty ladder and starts a fresh KV-cache (cost recorded as `synanton_session_reaffinity_total`).

### vLLM cross-region
`ModelServingDirectory` resolves `(model_family, region) → replicas[]`. Reranker, embedder, synthesis all route through it. No global "the vLLM cluster" assumption. See §27 for the directory's data model and §27c for how clients consume it.

------

## 44. Cost Awareness & Budget Caps

### Per-request emission
Every `synapt` request emits `api_usage` Avro:
```
embedder_gpu_ms, synthesis_gpu_ms, reranker_gpu_ms,
federation_targets, cross_region_bytes, cache_hits,
user_subject (null unless opt-in)
```

### Aggregation
Control-plane cost-aggregator → `cost_attribution_daily` (5-min rollups).

### Forecast
Prophet model updates `control_forecast_exhaustion_days{tenant}`.

### Enforcement
- HTTP 429 + `Retry-After` at 100 %.
- 70 %, 90 % thresholds emit warnings.

### Privacy default
Tenant-rollup only. Per-user attribution requires explicit `cost_privacy.attribute_per_user = true`.

------

## 45. Observability - Metrics, Alerts, SLOs, Traces

### Metric naming
All metrics prefixed with module ID. No `synanton_*` legacy.

### Alerts table

| Alert | Condition | Severity |
|-------|-----------|----------|
| `ForecastCostOverrunWarning` | < 7 d exhaustion | Warning |
| `ForecastCostOverrunCritical` | < 3 d exhaustion | Page |
| `TenantBudgetExhausted` | 100 % consumption | Page |
| `TierMoveStalled` | No movement 4 h with pending | Warning |
| `GitOpsReconcileFailed` | CRD apply fails > 5 min | Page |
| `AnomalyDetectorHighRecall` | > 10 % queries flagged / 1 h | Warning |
| `RelixEmulatedFallbackHigh` | > 5 % GraphRAG emulated / 15 min | Warning |
| `RelixMgvLagHigh` | MGV lag > 5× threshold | Warning |
| `SynfluxRouterRetentionThreatened` | Lag/retention > 50 % / 15 min | Page |
| `AclStuckGrant` | 3 reconciler runs unresolved | Page |
| `OutboundTokenSlaBreached` | p99 > 100 ms / 5 min | Warning |
| `TopologyProjectionStale` | Projection lag > 5 s | Warning |
| `RerankerFallbackHigh` | > 1 % fallbacks / 15 min | Warning |
| `SearchRecallBelowSLO` | 7-day rolling under SLO | Warning |
| `ForecastAccuracyDegraded` | Below SLO over 2 h | Warning |
| `IdpUnavailable` | Sustained validation failures | Page |
| `SynaptSanitizationHighRate` *(v1.18)* | `rate(synapt_sanitization_applied_total[15m])` > tenant baseline × 5 | Warning |
| `SynaptValidationRejectSpike` *(v1.18)* | `rate(synapt_validation_rejected_total[5m])` > 10 % of request rate | Warning |
| `GrpcValidationBurst` *(v1.18)* | `sum(rate(grpc_validation_failed_total[1m]))` > 100 across services | Warning |
| `CspViolationBurst` *(v1.18)* | `rate(ui_csp_violation_report_total[15m])` > 10 / min | Warning |
| `HelperDestructiveOpsRate` *(v1.19)* | `sum(rate(helper_destructive_ops_total[15m]))` > 10 | Page |
| `HelperAuthFailureSpike` *(v1.19)* | `sum(rate(helper_auth_failure_total{reason=~"invalid_key\|wrong_role"}[5m]))` > 20 | Page |

### v1.19 metric catalogue (helper / wizard operational CLI)

The v1.19 additions all live under the `helper_*` prefix; `wizard` is offline and emits no runtime metrics (its telemetry, if opted in, is a single anonymous invocation counter in CI logs).

- `helper_operation_total{command, tenant, outcome}` - one increment per API call originating from the `helper` CLI. `outcome ∈ {success, denied, error, dry_run_success, idempotent_replay}`.
- `helper_operation_duration_seconds{command, tenant}` - histogram; end-to-end wall-clock of the API call.
- `helper_auth_failure_total{reason}` - `reason ∈ {invalid_key, expired_key, wrong_role, ip_not_allowlisted}`.
- `helper_destructive_ops_total{command, tenant}` - dedicated counter for `delete content`, `delete tenant`, and `clean tenant` (non-dry-run) surfaces.

Alerts:

- `HelperDestructiveOpsRate` - bursts of destructive activity warrant an immediate operator check (page).
- `HelperAuthFailureSpike` - sustained auth failures for the `invalid_key` / `wrong_role` reasons often indicate a leaked or misconfigured support key (page).

`admin_audit` wiring: every increment of `helper_operation_total` corresponds to at least one `admin_audit` row (writes) or a read-audit hint (reads that materially expose tenant state). The `request_correlation_id` label joins metric samples to audit rows in Grafana.

### v1.18 metric catalogue (data validation & XSS protection)

The v1.18 additions maintain the naming convention (`<module>_<subject>_total`, unit-suffixed histograms):

- `synapt_sanitization_applied_total{tenant, field}` - a REST string field was materially rewritten by the OWASP sanitizer (dangerous tag/attribute/scheme removed).
- `synapt_sanitization_skipped_total{tenant, field}` - the field carried `@AllowHtml`; sanitisation deliberately bypassed.
- `synapt_validation_rejected_total{tenant, field, error}` - Jakarta Validation raised a constraint violation; `error` is the annotation short name (`Size`, `Pattern`, `URL`, `Email`, `NotBlank`).
- `synapt_validation_lenient_warning_total{tenant, field, error}` - same violation observed while `synapt.validation.strict = false`; request accepted but a warning header emitted.
- `grpc_validation_failed_total{service, method, field, error}` - PGV interceptor short-circuited a gRPC call.
- `ui_csp_violation_report_total{directive, blocked_uri}` - reported by browsers via the CSP `report-to` endpoint served by `synapt` (see §49).

Alert thresholds are baseline-relative: the `SynaptSanitizationHighRate` alert uses per-tenant rolling baselines (30-day median), so a tenant that legitimately posts a lot of formatted text does not trip alarms.

### SLOs

| Service Path | SLI | Objective |
|-------------|-----|-----------|
| GDPR Cascade | End-to-end p99 | ≤ 45 s |
| Search (hot) | p95 query latency | < 200 ms |
| Search (cold-tier rehydration) | p95 query latency | < 500 ms |
| MGV freshness | Mutation → view update p95 | < 200 ms |
| Forecast accuracy | Load forecast ± 20 % | 90 % over 2 h |
| Reranker availability | (2xx + fallback) / total | > 99.9 % |
| Outbound token exchange | p99 latency | ≤ 100 ms |
| HIGH_SECURITY ACL filter update | p99 | < 300 ms |
| Recall (top-k vs exhaustive) | 7-day rolling | per-tenant tier |
| Projection lag | p95 | ≤ 500 ms |
| Translation overhead (ISO GQL → native) | Per query | < 2 % |
| Synflux end-to-end (acquire → indexed) | p95 hot / cold | 90 s / 5 min |

### Distributed traces
OpenTelemetry spans propagated end-to-end through `synapt → gateway → planner → synquest/relix`. Each span carries `(tenant_id, trace_id, parent_span)` plus cost contribution.

### GPU Execution Plane additions *(v1.20)*


> **[v1.20]** The following metrics are added to the v1.19 observability specification.

### GPU Execution Metrics

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `gpu_execute_total` | Counter | `model`, `model_version`, `outcome` | Total GPU execution attempts |
| `gpu_execute_duration_seconds` | Histogram | `model`, `gpu_type`, `execution_class` | GPU execution latency |
| `gpu_admission_rejected_total` | Counter | `model`, `reason` | Requests rejected at GPU admission |
| `gpu_model_not_ready_total` | Counter | `model` | MODEL_NOT_READY responses received |
| `gpu_model_load_duration_seconds` | Histogram | `model` | GPU model cold-start load time |
| `gpu_idempotency_hit_total` | Counter | `model` | Execute() calls served from idempotency store |
| `gpu_capacity_queue_depth` | Gauge | `model`, `gpu_type` | Current GPU request queue depth |
| `gpu_cancel_total` | Counter | `model`, `outcome` | Cancellation attempts and outcomes |

### Alerts Added

| Alert | Condition | Severity |
|-------|-----------|----------|
| `GpuExecutionErrorRate` | `gpu_execute_total{outcome="error"}` / total > 5% over 5m | warning |
| `GpuModelNotReadySpike` | `gpu_model_not_ready_total` rate > 10/s over 2m | warning |
| `GpuAdmissionRejectionHigh` | `gpu_admission_rejected_total` rate > 20/s | critical |
| `GpuIdempotencyStoreUnhealthy` | `gpu_idempotency_store_healthy == 0` | critical |

------

## 46. Deployment Profiles

Three profiles. **One CLI (`synctl`).** Module boundaries identical.

| Profile | Use Case | Notes |
|---------|----------|-------|
| **Full** | Kubernetes + GitOps | Default; all features on |
| **Standalone** | systemd + Ansible (no K8s) | Same images; `synflux-rebalancer` sidecar; GitOps via local Git checkout |
| **Embedded** | Library inside host app | Read-side zero-downtime migration via shadow index + WAL replay; offline schema migration via `synctl --profile embedded migrate` |

`synctl-embedded` does not exist as a separate binary.

### Embedded live migration
1. Shadow index in temporary directory.
2. Host continues serving reads from old index.
3. Writes buffered to WAL.
4. Atomic pointer switch after WAL replay (< 5 ms downtime).
5. `.migration.lock` file detects incomplete state; rolls back or replays.

### Standalone hot-resharding
`synflux-rebalancer` sidecar runs in Standalone too. `HotShardDetector` triggers dual-write reshard automatically.

------

## 46a. Future UI Addenda *(v1.1)*

The GA plan does not include a user-facing query UI beyond the Admin Console - `synapt` is the definitive public API and third parties (or `syntology-admin`, when built) own the UX. This section records design commitments for those UIs so they land correctly when built.

### Reasoning / thinking block streaming

For LLMs that emit `<think>` blocks (DeepSeek, QwQ) or Anthropic extended thinking, the client library `synanton-llm-client` (§27c) separates thinking chunks from response chunks. Any Synanton-owned UI (Admin Console synthesis views, future `syntology-admin` query panel) must:
- Render thinking chunks in a rolling 5-line window with opacity fade during generation.
- Collapse thinking blocks by default after generation completes.
- Never persist raw thinking blocks alongside the response (they are a runtime artifact, not documented content).
- Include thinking-token counts in cost/usage displays (billed separately by some providers).

### i18n parity testing

Any Synanton-owned UI that ships internationalised strings **must** ship with an automated parity test verifying that every translation file has identical dot-path keys to the reference locale (`en`). The test is ~30 lines; adding it as a post-ship cleanup task is prohibited by this document. Missing translations must fail CI, not fall back to English silently at runtime.

Rationale: silent translation gaps produce a degraded but non-erroring UX that is nearly invisible to English-speaking maintainers. This has been a persistent bug source in comparable products.

------

# Part VII - Operations & Plan

------

## 47. Failure Modes & Runbooks

This table consolidates every concern raised in prior reviews against its present-design resolution.

| Failure / Concern | Origin | Resolution |
|-------------------|--------|------------|
| Topology DB pool exhaustion on ACL propagation | v1.7 | §11, §25 - outbox dispatcher; gRPC fan-out outside transaction |
| security ↔ topology circular dependency | v1.7 | §26 - security forbidden from writing topology; flows through `TopologyMutationApi` |
| Neo4j supernode unbounded subgraph | v1.7 | §8, §20 - `TOP_K_RELEVANCE_SAMPLING` + per-pattern `max_subgraph_nodes` |
| Cassandra counter drift evicting live embeddings | v1.7 | §18 - counters removed; LRU + 30-day TTL + Cuckoo vacuum |
| Sampled vacuum under-counts references | v1.7 | §18 - full-scan, throttled, zero-false-negative |
| Neo4j projection lag without circuit breaker | v1.7 | §25, §15 - fallback to PostgreSQL on lag > 5 s |
| `llmContext.customMetadata` prompt injection | v1.7 | §23 - strict allowlist at gateway |
| Hot-shard query ambiguity during reshard | v1.7 | §20 - `generation` field routing + cool-down drop |
| DuckDB complexity estimator false positives | v1.8 | §23 - runtime interruption layer (`AtomicLong` in `cache_lookup` UDF) |
| 24-h MCP session blind to IdP revocation | v1.8 | §26 - 15-min background revalidation worker |
| Worker token expiry mid-long-job | v1.8 | §26 - `AssertionRenewalPattern` 10 min pre-expiry |
| GDPR cascade contract unspecified | v1.8 | §10 - formal cascade across all data planes |
| Embedded profile schema migration | v1.8 | §46 - offline `synctl --profile embedded migrate` |
| HIGH_SECURITY ACL alert flapping | v1.8 | §25, §45 - 5-min window + 3-run "stuck" ladder |
| Supernode truncation invisible | v1.8 | §45 - `relix_supernode_truncation_total` + dashboard |
| Bloom rebuild on ACL revocation | v1.10 | §20 - Cuckoo filters O(1) delete |
| DuckDB morsel parallelism OOM | v1.10 | §23 - soft + hard maxHits breakers |
| Schema migration without N-2 | v1.10 | §42 - N-2 cycle CI-enforced |
| Embedded downtime on migration | v1.10 | §46 - shadow index + WAL replay |
| IdP login storm during mass renewal | v1.10 | §26 - `IdpStatusAmortizationCache` |
| gRPC slowness blocks ACL writes | v1.10 | §25 - outbox + circuit breaker; 202 Deferred |
| GDPR cascade race with router | v1.12 | §10 - pre-cascade `TOMBSTONE` + router drain ack |
| Synthesis cache fingerprint ambiguity | v1.12 | §18 - `QueryNormaliser` strips published `ACL_FIELD_SET` |
| Emulated traversal latency unbounded | v1.12 | §21 - per-traversal `emulated_total_timeout` + level batching |
| Federation adapter auth unspecified | v1.12 | §31 - `OutboundAuthProfile` + `ExternalAclTrust = DUAL` default |
| ISO GQL coverage gaps invisible | v1.12 | §28 - `PatternCoverage{NATIVE/FALLBACK/EMULATED}` mandatory |
| `synflux_enriched_chunks` retention undefined | v1.12 | §17, §37 - 30-day floor + RetentionThreatened alert |
| Router idempotency across stores | v1.12 | §17, §28 - `idempotency_key` REQUIRED in SPI |
| Cross-region vLLM deferred | v1.13 | §27, §43 - `ModelServingDirectory` resolves per region |
| Reranker model diversity | v1.13 | §23 - Cohere + Voyage + vLLM at GA |
| GQL Coverage Matrix staleness | v1.13 | §21 - continuous 60 s probing |
| Outbound token exchange SLA | v1.13 | §26 - p99 ≤ 100 ms with deny-on-breach |
| Cost-attribution privacy default | v1.13 | §44 - tenant rollup only unless opt-in |
| Drain timeout per-tenant free knob | v1.13 | §10 - bound to `regulatory_profile` enum |
| Compatibility-mode silent risk | v1.13 | §28 - compat mode removed entirely |
| Standalone hot-resharding limitation | v1.14 | §20, §46 - `synflux-rebalancer` sidecar everywhere |
| `synctl-embedded` separate CLI | v1.14 | §46 - single `synctl` with `--profile` flag |
| Reranker availability cascade | v1.14 | §23 - fallback to un-reranked never cascades |
| Single-pass LLM ingest quality ceiling | LLM-wiki review 2026-07-01 | §17 - two-step chain-of-thought ingest (Pass 1 analysis + Pass 2 generation) |
| Silent context truncation as corpora grow | LLM-wiki review 2026-07-01 | §22, §23 - `ContextBudget` with proportional allocation by query intent |
| Uniform graph edges producing noisy traversal | LLM-wiki review 2026-07-01 | §21, §28 - multi-signal edge relevance (direct/source/co-occurrence/type) |
| MGV refresh cost proportional to graph size | LLM-wiki review 2026-07-01 | §21 - Louvain community detection scopes MGV refresh to affected community |
| No quality gate on regulated content ingest | LLM-wiki review 2026-07-01 | §27a - `synreview` module with rule + LLM auto-sweep + human queue |
| Content anomalies invisible to platform observability | LLM-wiki review 2026-07-01 | §27 - `GraphInsightsWorkflow` surfaces gaps and surprising connections |
| Wire-format drift across LLM providers | LLM-wiki review 2026-07-01 | §27c - `synanton-llm-client` centralises translation |
| PDF/figure content invisible to search | LLM-wiki review 2026-07-01 | §17 - vision captioning stage with SHA256 image dedup |
| Rust parser panic denial-of-service | LLM-wiki review 2026-07-01 | §20 - `panic_guard` wraps every third-party parser call |
| Multilingual (CJK) recall gap | LLM-wiki review 2026-07-01 | §20 - CJK bigram tokenisation in Tantivy analyser chain |
| Entity orphaning / over-deletion on source erase | LLM-wiki review 2026-07-01 | §10, §21 - entity source reference counting in cascade |
| No differentiation of same-source vs same-content ingest | LLM-wiki review 2026-07-01 | §17 - SHA256 source-digest + analysis + image-caption caches |
| Duplicate entities and dead ontology links accumulate silently | LLM-wiki review 2026-07-01 | §27 - `OntologyLintWorkflow`; results route through §27a review |
| No mechanism to fill known knowledge gaps | LLM-wiki review 2026-07-01 | §27 - `DeepResearchWorkflow` with WebSearchAdapter SPI |
| MCP protocol layer coupled to core service versions | LLM-wiki review 2026-07-01 | §27b - `synanton-mcp` bridge over synapt public API |
| Thinking blocks conflated with response content in UI | LLM-wiki review 2026-07-01 | §27c, §46a - separate channel in client + rolling window in UI |
| i18n regressions ship silently | LLM-wiki review 2026-07-01 | §46a - parity test mandatory alongside first translation file |

### Operator runbooks (summary)

**Runbook R1: `SynfluxRouterRetentionThreatened`**
1. Identify target lagging behind: `synctl synflux router status`.
2. Increase parallelism: `synctl synflux router scale --target relix --replicas 4`.
3. If target unrecoverable: enable catchup-via-cassandra: `synctl synflux router catchup --target relix --from-manifest`.

**Runbook R2: `AclStuckGrant`**
1. Identify grant: query `topology_outbox` for `STUCK` rows.
2. Identify failing consumer: inspect `ack_state` JSON.
3. Restart consumer; reconciler will retry automatically.
4. If consumer permanently down: manually mark grant `PROPAGATED` after verification.

**Runbook R3: `OutboundTokenSlaBreached`**
1. Identify slow IdP: `security_outbound_exchange_p99_by_audience`.
2. Switch affected tenants to `SERVICE_ACCOUNT` profile temporarily.
3. Engage IdP vendor.

**Runbook R4: `RelixEmulatedFallbackHigh`**
1. Identify connector: `relix_emulated_duration_seconds` by `connector_id`.
2. Check `PatternCoverage`: is the pattern marked `EMULATED` legitimately?
3. If a NATIVE-capable connector should be available, swap via `/admin/relix/connectors`.

**Runbook R5: `TenantBudgetExhausted`**
1. Confirm cap: `synctl admin tenants get {id} --policy budget`.
2. Engage tenant; offer top-up.
3. Once confirmed: `synctl admin tenants update {id} --policy budget --bump $X`.

**Runbook R6: `PlatformDegradedModeActive`** *(v1.17)*
1. Confirm cause: `synctl platform-state get` → shows trip reason.
2. Inspect model health: `synctl models health` → identifies saturated model family/region.
3. Add replicas: `synctl models scale --family <f> --region <r> --replicas +N` (KEDA will also react to `vllm_queue_time_seconds`).
4. Once healthy, restoration is automatic after `restore_dwell_seconds` (default 300 s). Recrawl workflow auto-fires - monitor at `/admin/recrawl/<tenant>/status`.
5. If auto-restore doesn't fire: `POST /admin/degraded-mode` with `{ "state": "RESTORED" }` (audited).

**Runbook R7: `SynfluxDegradedRecrawlStalled`** *(v1.17)*
1. Inspect progress: `synctl recrawl status --tenant <id>`.
2. Check GPU pressure - recrawl backs off when embedder queue > 3 s.
3. If pressure ok but progress stalled: check `synflux_dlq` for `poison_reason=RECRAWL_FAILED`.
4. Bump concurrency: `synctl recrawl set-config --concurrent-tenants 8`.

**Runbook R8: `ApiKeyPastExpiry`** *(v1.17)*
1. Query keys near expiry: `synctl admin api-keys list --expiring-in 30d`.
2. Notify owner (email routed automatically at T-30/T-14/T-7 - verify they were sent).
3. If unresponsive after T-7 and key is business-critical, escalate via tenant admin contact.

### GPU Execution Plane additions *(v1.20)*


> **[v1.20]** The following GPU-specific failure modes are added to the v1.19 runbook table.

| Failure | Symptom | Runbook |
|---------|---------|---------|
| GPU plane unavailable | `gateway` returns GPU degraded fallback; `gpu_execute_total{outcome="error"}` spikes | `docs/operations/runbooks/gpu-plane-unavailable.md` |
| GPU model not loaded | `MODEL_NOT_READY` responses; cold-start queue filling | `docs/operations/runbooks/gpu-model-cold-start.md` |
| Idempotency store unhealthy | `GpuIdempotencyStoreUnhealthy` alert; Gateway blocks all executions (fail-closed) | `docs/operations/runbooks/gpu-idempotency-store.md` |
| GPU admission capacity exceeded | `GPU_CAPACITY_EXCEEDED` errors; primary platform falls back to CPU path | `docs/operations/runbooks/gpu-capacity.md` |
| mTLS certificate expiry | GPU execution client fails to connect to GPU Gateway | `docs/operations/runbooks/gpu-mtls-cert.md` |
| Network partition CPU↔GPU | `Execute()` timeout; primary platform reconciles via `GetStatus()` | `docs/operations/runbooks/gpu-network-partition.md` |

------

## 47a. Disaster Recovery - RTO/RPO & Cross-Region DR *(new in v1.17)*

v1.16 documented individual failure runbooks (§47) but never named a coherent recovery-time / recovery-point commitment for each storage class. v1.17 fixes that with an explicit RTO/RPO matrix, cross-region DR procedures, and a scheduled backup-verification workflow.

### RTO/RPO per storage class

| Storage | Data | RPO (max data loss) | RTO (max recovery time) | Backup mechanism | Verification cadence |
|---------|------|---------------------|-------------------------|------------------|----------------------|
| **PostgreSQL** (`topology`, `synreview`, `audit`, `jobs`, `cost`) | Authoritative org, ACLs, policies, review items, audit trail | **15 min** | **30 min** | Continuous WAL streaming to `s3://synanton-backups/postgres/` + hourly base backup | **Weekly** - restore into scratch cluster, run smoke suite |
| **Cassandra / ScyllaDB** (`ingestion-cache`) | Chunks, embeddings, manifests, synthesis cache | **30 min** | **1 h** | Incremental snapshots every 15 min to `s3://synanton-backups/cassandra/`; snapshot retention 30 d | **Weekly** - snapshot restore into isolated keyspace |
| **S3 Standard / Glacier** (`synvault-warm`, `synvault-cold`) | Full document bodies (warm, cold, archive) | **0** (S3 multi-AZ) | Variable - Standard: minutes; Glacier: 1-5 min expedited, up to 12 h standard | S3 cross-region replication (CRR) to secondary region | Quarterly - random-sample retrieval test |
| **Kafka** | In-flight events (7-30 d retention) | **0** (mirror-maker to peer cluster in DR region) | **15 min** to cut over consumers | MirrorMaker 2 to `kafka-<region>-dr` cluster; identical topic config | Monthly - chaos test cutting one consumer group to DR cluster |
| **Neo4j / graph connector** (via SPI) | GraphRAG projection; **not authoritative** | ≤ 5 min lag from `topology` | **2 h** - rebuild from `topology_events` + `synflux_enriched_chunks` replay | Reproducible from upstream sources; no separate backup required | Monthly - verify rebuild time from cold |
| **Redis** (sessions, scope bundles, IdP cache) | Ephemeral | **N/A** (session loss acceptable) | **5 min** to warm | AOF persistence + cross-AZ replicas; no cross-region | Monthly - failover to replica |

**RTO clock** starts at the incident-declared timestamp (not at detection). **RPO** is measured against successful, verified backups - an unverified backup does not count toward the RPO guarantee.

### Cross-region DR - active-passive topology

v1.17 defines an **active-passive** cross-region DR posture. Multi-master is out of scope for GA.

- **Active region.** Runs the full stack; serves 100 % of live traffic.
- **Passive region (DR region).** Runs read-only projections continuously fed by:
  - PostgreSQL logical replication (with lag < 1 min SLO).
  - Cassandra multi-DC replication (`replication_factor` in DR is same as active).
  - Kafka MirrorMaker 2 mirroring all v1.17 topics.
  - S3 CRR for `synanton-warm` and `synanton-backups`.
- **Passive-region control plane** runs the same Temporal cluster in warm-standby mode (no workflows executing, just polling).

### DR runbook R-DR1 - regional failover

Preconditions: active region unavailable (declared) or facing extended degradation. Rehearsed quarterly.

1. **Freeze active writes.** Set `synapt` in active region to `503 Service Unavailable` for write endpoints; reads continue if they can be served.
2. **Confirm passive-region lag.** `synctl dr lag` - must be within RPO. Abort if not; escalate.
3. **Promote passive region.**
   - Promote PostgreSQL logical subscriber to primary; disable subscription.
   - Enable Cassandra writes in the DR DC; set consistency to `LOCAL_QUORUM` from `EACH_QUORUM`.
   - Cut MirrorMaker 2 to inbound-only for the DR cluster.
   - Reverse S3 CRR direction (DR region becomes source).
4. **Rebuild graph projection.** Start `RelixProjectionRebuildJob` (Temporal) using `topology_events` on the DR side. SLO: ≤ 2 h to converge for the largest tenant.
5. **Warm caches.** Prime Redis with the top-1000 subject `scope_bundle:*` entries and the top-1000 hot `synthesis_cache:*` entries from S3 audit archives (best-effort).
6. **Cut traffic.** Update DNS (or global load balancer) to point `synapt.synanton.internal` at DR region. TTL should be ≤ 60 s in normal operation.
7. **Announce.** Post `X-Synanton-DR-Failover-Complete: true` header + status page update.

**RTO target for full failover:** ≤ 2 h to serve reads at nominal quality; ≤ 4 h to serve full write traffic including new ingestion.

### DR runbook R-DR2 - failback

Once the original active region is verified healthy:

1. Reverse-replicate PostgreSQL/Cassandra changes from DR back to origin (staged; can take hours for a large delta - do not rush).
2. When lag < RPO on the origin side, run **R-DR1 in the opposite direction** during a scheduled maintenance window.
3. **Never** attempt failback without a maintenance window - the risk of double-write conflicts during promotion is real.

### `BackupVerificationWorkflow`

Owned by `control-plane`. Fires on the cadence in the RTO/RPO table above. Each run:

1. Reserves a scratch environment (Kubernetes namespace + storage-class quota).
2. Restores the *most recent* backup for the target storage class.
3. Runs the class-specific smoke suite (row-count sanity, schema-version check, replay of a fixed audit content_ref through synflux).
4. On success: emits `dr_backup_verified_at{storage_class}` gauge to `now()`.
5. On failure: fires `DrBackupVerificationFailed` (page) and files an incident with the collected diagnostics.

The **RPO guarantee is void** if `dr_backup_verified_at` is older than the cadence - operators may not report an RPO to auditors based on an unverified backup.

### Configuration
- `dr.postgres.rpo_seconds` (default 900)
- `dr.postgres.rto_seconds` (default 1800)
- `dr.cassandra.rpo_seconds` (default 1800)
- `dr.cassandra.rto_seconds` (default 3600)
- `dr.s3.crr_region` (default `us-west-2`; per-deployment)
- `dr.kafka.mirror_target` (per-deployment)
- `dr.backup_verify.postgres_cron` (default `0 4 * * 1`)
- `dr.backup_verify.cassandra_cron` (default `0 5 * * 1`)
- `dr.backup_verify.s3_cron` (default `0 6 1 */3 *`)
- `dr.failover.dns_ttl_seconds` (default 60)

### Metrics
- `dr_replication_lag_seconds{storage_class}` gauge
- `dr_backup_verified_at{storage_class}` gauge (unix seconds of last verification)
- `dr_backup_verification_duration_seconds{storage_class}` histogram
- `dr_backup_verification_failures_total{storage_class}` counter
- `dr_failover_runbook_executed_total{runbook}` counter (should be 0 outside drills)

### Alerts
- `DrReplicationLagHigh` - any storage class > 1.5× its RPO.
- `DrBackupVerificationFailed` (page) - any verification run returns failure.
- `DrBackupVerificationOverdue` (page) - `now() - dr_backup_verified_at > 1.5 × cadence`.
- `DrDrillOverdue` (warn) - no `dr_failover_runbook_executed_total` increment in > 1 quarter.

------

## 48. Implementation Phases

The v1.1 phase table extends the v1.0 sequence with LLM Wiki-derived deliverables. Total duration remains ~26 weeks - new work is packed into the existing phases (no phase widening; concurrency inside phases increases). Where a deliverable is v1.1-specific it is annotated `[v1.1]`.

| Phase | Weeks | Focus | Deliverables |
|-------|-------|-------|--------------|
| **1** | 4 | Core scaffolding | Module skeletons, SPI v1.0, config/metrics naming, CRD definitions, **`synanton-llm-client` skeleton with vLLM + Anthropic translators `[v1.1]`** |
| **2** | 4 | Ingestion + storage | `synvault`, `synflux` pipeline (parse/chunk/enrich/embed), Tier Manager, Kafka tiered storage, **SHA256 source-digest cache + two-step chain-of-thought enrichment `[v1.1]`**, **`synreview` skeleton `[v1.1]`** |
| **3** | 6 | Search + graph | `synquest` kernel (Cuckoo, recall sampling, Top-K relevance), **CJK bigram tokeniser + panic guard `[v1.1]`**, `relix` SPI + MGV, **multi-signal edge relevance + source_ref_count `[v1.1]`**, planner cost estimator with measured profiles, **4-phase retrieval + RRF + context budget `[v1.1]`**, **vision captioning stage `[v1.1]`** |
| **4** | 5 | Security + federation | `security` (broker, outbox, revalidation), cross-region routing, ModelServingDirectory, all three reranker adapters, **`synreview` two-tier auto-sweep + first producer integrations (synflux Pass 2, syntology merges) `[v1.1]`** |
| **5** | 4 | Ops + DX | `control-plane` (GitOps, forecast, anomaly), single `synctl`, all dashboards/alerts, **LouvainCommunityWorkflow + GraphInsightsWorkflow + OntologyLintWorkflow + DeepResearchWorkflow `[v1.1]`**, **`synanton-mcp` bridge `[v1.1]`** |
| **6** | 3 | Hardening | Predictive-scaling tuning, integration + load testing, GDPR cascade fuzz testing (**including reference-count cascade correctness `[v1.1]`**), **real-LLM + property-based test suite gating GA `[v1.1]`** |

### Module × Phase matrix - v1.19 additions

The two new v1.19 modules (`helper` and `wizard`) are layered onto the existing six-phase plan. `NEW` = first appearance, `EXT` = additive extension, `NO-CHANGE` = no work in that phase.

| Module | Phase 1 | Phase 2 | Phase 3 | Phase 4 | Phase 5 |
|--------|---------|---------|---------|---------|---------|
| `helper` *(v1.19)* | **NEW** - skeleton CLI, `status` via `/admin/_internal/status`, `bundle`, `clean orphans --dry-run`; `SYNANTON_API_ENDPOINT` + `SYNANTON_SUPPORT_KEY` auth | **EXT** - `recrawl start/status/pause`, `clean tenant` | NO-CHANGE | **EXT** - `delete content`, `delete tenant` (destructive; two-layer confirm), `workflow cancel/retry`; wire `helper_operation_total` metric and `HelperDestructiveOpsRate` / `HelperAuthFailureSpike` alerts | **EXT** - `status` surfaces DR replication lag; `recrawl` supports multi-tenant batch operations |
| `wizard` *(v1.19)* | **NEW** - `init`, `validate`, `generate` for Docker Compose (Full profile, single-node); `.env` and `application-*.yml` emission | **EXT** - Terraform generators for AWS/GCP/Azure; Kubernetes Helm chart generation; optional `apply` (shells out to `terraform`) | NO-CHANGE | **EXT** - CSP + companion security headers (§49) rendered in reverse-proxy templates | **EXT** - multi-region DR generation (CRR, MirrorMaker 2, multi-DC Cassandra); Glacier lifecycle rules; `BackupVerificationWorkflow` CronJob manifests |

**Total:** ~26 weeks to GA - unchanged. `helper` and `wizard` fit inside the existing phases; the Phase 1 `synanton-ops` binary skeleton absorbs the initial engineering cost.

### Acceptance criteria for GA
- Relix SPI acceptance test suite passes on all three first-party connectors (including v1.1 edge-signal, community-id, and source_ref_count checks).
- GDPR cascade end-to-end p99 ≤ 45 s under load, **including mixed-mode cascades where some entities decrement and others fully delete**.
- Forecast accuracy SLO met for 4 consecutive weeks.
- All alerts have associated runbooks and Grafana dashboards.
- Schema-diff CI gate green on three migration cycles.
- Cuckoo ACL filter p99 update < 300 ms under HIGH_SECURITY synthetic revocation load.
- Reranker fallback never cascades into search outage (chaos test).
- Cross-region federation enforces residency in all `failClosed = true` test scenarios.
- **`synanton-llm-client` wire-format round-trip tests pass against every first-party provider translator.** *(v1.1)*
- **`synreview` two-tier auto-sweep resolves ≥ 80 % of items without human intervention on a corpus-representative sample.** *(v1.1)*
- **Real-LLM integration tests pass on `main`; property-based tests pass in every CI run.** *(v1.1)*
- **`synctl helper` end-to-end tests exercise every `/admin/_internal/*` endpoint against a Testcontainers cluster; destructive verbs are covered under two-layer confirm and idempotency-replay.** *(v1.19)*
- **`synctl wizard generate --config …` produces artifacts that `terraform validate` and `helm lint` pass cleanly for every supported cloud × profile matrix cell.** *(v1.19)*

### GPU Execution Plane additions *(v1.20)*


> **[v1.20]** The GPU Execution Plane implementation track is added. See `docs/implementation/gpu-execution-plane/INDEX.md` for the detailed implementation plan.

| Phase | Theme | GPU plane work |
|-------|-------|----------------|
| GPU-1 | Contract | `synanton.gpu.v1` protobuf, PGV rules, error catalogue, consumer-driven contract tests |
| GPU-2 | GPU Execution Plane | `synanton/gpu-execution-plane` repo, GPU Gateway, mTLS, DirectDispatcher, model serving, idempotency store |
| GPU-3 | Primary Platform Integration | GPU execution client in `gateway`, `ModelServingDirectory` refinement, degraded mode, cross-cluster tracing |
| GPU-4 | Production Hardening | Security tests, failure injection, idempotency tests, observability dashboards, cost attribution validation |
| GPU-5 | Optional Scheduling | `EqualixScheduler` only after operational evidence of contention |

------

## 48a. Testing Discipline *(v1.16; extended in v1.17)*

The GA acceptance suite formalises four test tiers with distinct running conditions. This section is normative - modules that skip a tier without documented justification fail code review.

### Tiers

| Tier | What it validates | Where it runs | When |
|------|-------------------|---------------|------|
| `test:unit` | Business logic, wire-format serialisation, pure functions. Mocked I/O. | Every developer machine, every CI job. | Always (fast - < 5 min per module). |
| `test:integration` | Real I/O against Testcontainers (Cassandra, PostgreSQL, Kafka, Neo4j). Contracts between modules. | CI on every PR. | Every PR. |
| `test:llm` | Real-LLM behaviour: prompt regressions in two-step ingest, `synreview` sweeps, gateway synthesis, `synanton-llm-client` translators. Requires `SYNANTON_LLM_API_KEY` set in CI secrets; loaded via `.env.test.local` for developers. | CI on merge to `main` only (cost / rate limit), with path-gating (v1.17). | Merges to main *(v1.16)*. Path-gated per-PR *(v1.17)*. |
| `test:property` | Invariants under randomised inputs. Frameworks: `proptest` (Rust) and `jqwik` (Java). Priority coverage: `synquest` search ranking properties, `topology` ACL grant/revoke sequences, `synreview` state-machine invariants, path normalisation, `relix.source_ref_count` under concurrent mutation. | CI on every PR (fast) + nightly extended runs. | Every PR + nightly. |

### Non-negotiables
- **Mocking LLMs is insufficient for prompt-quality regressions.** Any module that shells out to an LLM must land at least one `test:llm` case exercising the prompt shape used in production. Reviewers reject PRs that add LLM calls without them.
- **Property tests are required for state machines and index rankings.** These are the surfaces where hand-written test cases systematically miss edge cases.
- Test data with real PII is prohibited outside the `test:llm` tier's sanctioned fixtures (audited).

### CI stages
```
[unit] ────► [integration] ────► [property] ────► [llm (path-gated or main only)]
     fast          medium              fast            slow / paid
```
A red `test:llm` result blocks the merge marker and files a `LlmTestRegressed` incident. A red `test:property` result blocks the PR.

### `test:llm` refinements *(v1.17)*

Running real-LLM tests on every `main` merge produced sustained CI costs that scaled linearly with team size. v1.17 makes three changes:

**1. Staging model endpoint.** `test:llm` targets `models.staging.synanton.internal` - a lower-priority, cheaper vLLM cluster with the *same weights hash* as production (`ModelServingDirectory` verifies at CI start-up). Staging carries a coarser rate limit than production but has no user-facing SLO, so CI throttling never bleeds into user impact. Production endpoints are used only for GA acceptance sign-off runs (weekly).

**2. Request-level caching.** The test harness ships a `LlmRequestCache` keyed by `(model_family_version, sha256(rendered_prompt), sha256(request_overrides_json))`. Hits are served from a filesystem cache (`~/.cache/synanton-test-llm/`) or S3 (`s3://synanton-ci/test-llm-cache/`) - whichever is available. Cache TTL is bound to `(model_family_version, prompt_version_of_test)` - bumping either invalidates. Tests that specifically want to exercise non-determinism opt out via `@NonCachedLlmCall`.

**3. Path-based gating.** `test:llm` runs on a PR only when the diff touches LLM-sensitive paths, computed by the CI orchestrator:
```
llm_paths = [
  "**/synflux/enrich/**",
  "**/synreview/prompts/**",
  "**/gateway/synthesis/**",
  "**/relix/subgraph_synthesis/**",
  "**/synanton-llm-client/**",
  "**/DeepResearchWorkflow/**",
  "docs/architecture/platform/**/*.md"     // spec changes that could imply prompt changes
]
```
If no touched file matches, `test:llm` is skipped with an explicit "skipped-by-path-gate" job outcome (not "no tests"). If any matches, the tier runs. Merges to `main` always run the full suite as a belt-and-braces backstop.

**Governance.**
- Staging endpoint version drift vs production is monitored by `test_llm_weights_hash_mismatch` gauge (0 = match, 1 = mismatch); a mismatch stops `test:llm` runs until resolved.
- Cache hit rate `test_llm_cache_hit_ratio` surfaced on the dev-productivity dashboard - target > 60 % after week 2 of a feature.
- CI cost per week broken down by `(test_tier, cache_hit_bool)` in `deep_research_gate_wait_seconds`-style advisory metrics.

**Configuration:**
- `test.llm.endpoint` (default `models.staging.synanton.internal`)
- `test.llm.cache.dir` (default `~/.cache/synanton-test-llm/`)
- `test.llm.cache.s3_bucket` (default `s3://synanton-ci/test-llm-cache/`)
- `test.llm.path_gate.enabled` (default true; set false to force full runs)
- `test.llm.weights_hash_verify` (default true)

------

## 48b. UI Security Guidelines *(new in v1.18)*

Non-normative but enforceable. The React admin UI and any future first-party frontends MUST follow these practices; they are validated by ESLint rules, pre-commit hooks, and code review. Violations block CI on the `ui-security-gate` job.

### 48b.1 Sanitising `dangerouslySetInnerHTML`

Any use of React's `dangerouslySetInnerHTML` MUST pipe its input through DOMPurify with a project-wide policy. The wrapper component `<SafeHtml />` is the canonical entry point; direct `dangerouslySetInnerHTML` calls in feature code fail linting.

```jsx
import DOMPurify from 'dompurify';

const SANITIZE_OPTIONS = {
  FORBID_TAGS: ['script', 'style', 'iframe', 'object', 'embed', 'link', 'meta'],
  FORBID_ATTR: ['onerror', 'onload', 'onclick', 'onmouseover', 'onfocus', 'onblur'],
  ALLOW_DATA_ATTR: false,
  USE_PROFILES: { html: true },
};

export function SafeHtml({ content }) {
  return (
    <div dangerouslySetInnerHTML={{ __html: DOMPurify.sanitize(content, SANITIZE_OPTIONS) }} />
  );
}
```

Rich-text fields returned from `synapt` that were server-tagged `@AllowHtml` (see §24) are still routed through `<SafeHtml />` - defence in depth. The two sanitisers use disjoint allow-lists; content that survives both is guaranteed script-free.

### 48b.2 URL scheme validation

Any dynamic `href` / `src` MUST pass through the `assertSafeUrl(url)` helper before being rendered. Allowed schemes:

- `http`, `https` - unconditionally allowed.
- `mailto`, `tel` - allowed only in explicit contact widgets.
- `data:image/(png|jpeg|gif|webp);base64,…` - allowed only for `<img>` `src`.
- Everything else (including `javascript:`, `vbscript:`, `file:`, `data:text/html`) - blocked. A blocked URL renders as inert text and increments `ui_url_blocked_total{scheme}` via the client-side metrics beacon (§45).

### 48b.3 Token storage

- **Access tokens** live in an in-memory closure inside the auth provider. They are never written to `localStorage` or `sessionStorage`.
- **Refresh tokens** live in an `HttpOnly; Secure; SameSite=Strict` cookie set by `synapt` on login. The UI never reads them; refresh happens via a `POST /auth/refresh` that the browser attaches the cookie to automatically.
- **CSRF defence.** The refresh endpoint is double-submit-cookie protected: a same-origin `X-Csrf-Token` header must match a non-`HttpOnly` cookie set alongside the refresh cookie.

### 48b.4 External links

All external anchors MUST render with `rel="noopener noreferrer"` and `target="_blank"` when they open in a new tab. Lint rule `react/jsx-no-target-blank` is set to `error` and the `SafeExternalLink` component is the sanctioned wrapper.

### 48b.5 Third-party scripts

Every third-party script (analytics, feature flags, error reporting) is:

1. Loaded via Subresource Integrity (`integrity=sha384-…`).
2. Enumerated in `ui/vendor-inventory.yaml` with owner, purpose, and next audit date.
3. Reviewed on quarterly cadence by the security engineering group.

Any new third-party script requires an ADR (Architectural Decision Record) and a CSP directive update (§49).

### 48b.6 Trusted Types

The CSP requires `require-trusted-types-for 'script'` (see §49). The UI ships a compatibility polyfill for browsers without native support, and all sink-adjacent code paths (`innerHTML` assignments, `document.write`, `eval`) are routed through named Trusted Type policies (`synanton#html`, `synanton#script`). ESLint's `no-restricted-globals` rule blocks direct sink usage.

### 48b.7 Enforcement

- **ESLint rules:** `no-unsafe-innerhtml`, `no-target-blank`, `no-restricted-imports` (blocks direct DOMPurify calls outside `<SafeHtml />`), custom `synanton/url-must-be-validated` rule.
- **Pre-commit hook:** `ui-security-check` runs the above lint set on staged files.
- **CI gate:** `ui-security-gate` job in the release pipeline. Fails the release on any lint error, missing SRI hash, or CSP-directive drift vs `ui/csp-policy.yaml`.
- **Runtime:** browsers post CSP violations to the `POST /csp-report` endpoint on `synapt`; aggregated by the `CspViolationBurst` alert (§45).

------

## 49. Infrastructure Security Headers *(new in v1.18)*

**Scope.** Every HTTP response served by the reverse proxy / API gateway (`synapt`'s embedded static-file handler or the fronting Spring Cloud Gateway) MUST carry the header set defined here for HTML and JSON responses. Static assets (JS bundles, CSS, images) receive the same set.

### 49.1 Content Security Policy

The canonical policy is set by `ui.security.csp.policy`, versioned in `ui/csp-policy.yaml`, and validated at build time against the actual asset manifest (any script/style origin not declared in the policy fails the build).

```
Content-Security-Policy:
  default-src 'self';
  script-src 'self';
  style-src 'self' 'unsafe-inline';
  img-src 'self' data:;
  connect-src 'self';
  font-src 'self';
  base-uri 'self';
  object-src 'none';
  form-action 'self';
  frame-ancestors 'none';
  require-trusted-types-for 'script';
  report-uri /csp-report;
  report-to csp-endpoint
```

**Trade-off note.** `style-src 'unsafe-inline'` is retained because the current admin UI uses runtime CSS-in-JS. A follow-up (tracked in §C migration register) will refactor to nonce-injected styles, at which point the directive tightens to `style-src 'self' 'nonce-{RANDOM}'`.

**Reporting.** A `Report-To` header names a group `csp-endpoint` pointing at `synapt`'s `POST /csp-report`. Reports are aggregated via `ui_csp_violation_report_total` (§45).

### 49.2 Companion headers

```
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Referrer-Policy: strict-origin-when-cross-origin
Permissions-Policy: camera=(), microphone=(), geolocation=(), interest-cohort=()
Strict-Transport-Security: max-age=31536000; includeSubDomains; preload
Cross-Origin-Opener-Policy: same-origin
Cross-Origin-Resource-Policy: same-origin
```

`Strict-Transport-Security` is emitted only when the response arrives over TLS; the reverse proxy is responsible for TLS termination.

### 49.3 API responses

For pure JSON responses (Content-Type `application/json`), the browser does not apply CSP, but the same headers are emitted for uniformity. `X-Content-Type-Options: nosniff` in particular prevents MIME-sniff-driven XSS if a JSON payload is ever mis-delivered as HTML.

### 49.4 Configuration

- `ui.security.csp.enabled` (default `true`)
- `ui.security.csp.policy` (default: canonical policy from `ui/csp-policy.yaml`)
- `ui.security.csp.report_uri` (default `/csp-report`)
- `ui.security.headers.hsts_max_age_seconds` (default `31536000`; disable for local dev)
- `ui.security.headers.frame_options` (default `DENY`)
- `ui.security.headers.referrer_policy` (default `strict-origin-when-cross-origin`)

### 49.5 Deployment surface

The security-header emission is layered:

1. **`synapt`** installs a Spring `WebFilter` that sets the header set for every response. This is the source of truth in development and single-node deployments.
2. **Reverse proxy (Nginx / Envoy / Spring Cloud Gateway)** re-asserts the same headers, protecting against any bypass if a backend service forgets. On conflict, the reverse-proxy value wins (`add_header` in Nginx replaces backend headers).
3. **CDN** (if any) is configured to pass through Response headers unmodified; the CDN's own security headers, if configured, MUST NOT override.

### 49.6 Rollout

- The header set is enforced from day 1 with `Content-Security-Policy-Report-Only` in a canary window (2 weeks) to collect violations without blocking legitimate traffic.
- After the canary, the header switches to enforcing `Content-Security-Policy`. `ui.security.csp.mode` (`enforce` | `report_only`) controls the switch and can be flipped per-environment.
- CI includes a `csp-smoke-test` that renders every UI route in a headless browser and asserts zero CSP violations before merge.

### 49.7 Metrics & alerts

- `ui_csp_violation_report_total{directive, blocked_uri}` (see §45).
- Alert `CspViolationBurst` fires on sustained violation reports, flagging either a broken third-party dependency or an active XSS probe.

------

# Part VIII - GPU Execution Plane

*(new in v1.20)*

---

## §50 GPU Execution Plane Overview

Synanton v1.20 introduces a strict architectural boundary between the **primary Synanton platform** and a separate **GPU Execution Plane**.

The primary platform remains responsible for business intent, tenant identity, authorization policy, model selection, execution planning, workflow state, degraded-mode orchestration, and cost attribution.

The GPU Execution Plane is responsible for GPU-specific execution: model serving, GPU admission, request dispatch, runtime lifecycle, GPU capacity, and execution telemetry.

The two planes communicate through a narrow, versioned gRPC contract (`synanton.gpu.v1`).

The GPU Execution Plane is not another Synanton control plane. It is a remotely executable infrastructure capability behind a strict contract.

This separation keeps GPU-specific infrastructure out of the primary platform, allows GPU capacity to evolve independently, and prevents the primary platform from acquiring dependencies on Kubernetes, GPU drivers, model-serving runtimes, or GPU scheduling internals.

---

## §51 Goals and Non-Goals

### Goals

1. Physically isolate GPU workloads from the primary Synanton cluster.
2. Keep GPU infrastructure independently deployable and scalable.
3. Keep the primary platform independent of GPU-specific infrastructure.
4. Preserve `ModelServingDirectory` as a logical model-to-endpoint abstraction.
5. Provide a stable execution contract for synthesis, embedding, and reranking.
6. Support multiple GPU execution strategies without changing the primary platform.
7. Make Equalix optional rather than a mandatory dependency.
8. Preserve Synanton's existing authentication, authorization, validation, observability, and error conventions.
9. Support graceful degradation when GPU execution is unavailable.
10. Avoid distributed transactions across the CPU/GPU boundary.
11. Preserve clear ownership of business state versus GPU execution state.

### Non-Goals

v1.20 does **not** introduce:

- a new business-logic engine;
- a replacement for Resolutor;
- a replacement for Commitix;
- a GPU resource-management API in the primary platform;
- exactly-once execution;
- distributed transactions / 2PC;
- GPU-node scheduling in Synanton;
- Kubernetes control from the primary platform;
- model artifact management in the primary platform;
- a new identity system;
- tenant identity based solely on a caller-supplied `tenant_id`.

Equalix remains an optional scheduling component and is not required for the first GPU implementation.

---

## §52 Architectural Boundary

### §52.1 Primary Synanton Platform

The primary platform owns:

- external API ingress;
- authentication;
- authenticated service identity;
- tenant identity and tenant policy;
- authorization;
- model logical identity;
- approved model/version selection;
- execution planning;
- business/workflow state;
- request lifecycle;
- degraded-mode orchestration and policy;
- cost attribution;
- audit;
- cross-platform tracing context.

Relevant existing components include:

- `synapt`;
- `security`;
- `topology`;
- `planner`;
- `gateway`;
- `control-plane`;
- `ModelServingDirectory`.

`control-plane` remains one component of the primary platform. It is **not** synonymous with the entire CPU platform.

### §52.2 GPU Execution Plane

The GPU Execution Plane owns:

- GPU Gateway;
- GPU-specific request validation at the execution boundary;
- service authentication;
- authorization assertion validation;
- GPU admission;
- request dispatch;
- model-serving runtime;
- GPU capacity;
- GPU-specific execution state;
- execution telemetry;
- GPU usage reporting;
- runtime health;
- Kubernetes deployment and scheduling.

The GPU plane MUST NOT own business workflow state or tenant policy.

---

## §53 Physical Topology and Repository Split

The physical topology is defined in §4 of this document.

### Repository Boundary

The GPU execution implementation lives in:

```
synanton/gpu-execution-plane
```

The primary platform remains:

```
synanton/platform
```

The repositories are intentionally separate.

The primary reason for the repository split is **source-level dependency isolation**:

> GPU execution implementation must not acquire dependencies on primary-platform internals.

Additional benefits include:

- independent CI/CD;
- independent GPU-specific release lifecycle;
- independent infrastructure configuration;
- independent Kubernetes deployment;
- clearer ownership;
- reduced coupling between CPU and GPU runtime concerns.

The GPU repository may depend on the **versioned public execution contract** (`synanton.gpu.v1`), but MUST NOT depend on internal classes from `synanton/platform`.

### Documentation Alignment

The README for `synanton/platform` MUST:

- Explicitly link to `synanton/gpu-execution-plane` for production GPU runtime configuration and deployment.
- No longer imply that the primary platform repository owns the production GPU runtime.
- Distinguish clearly between local/demo GPU execution (Phase 2 vLLM containers) and the production GPU Execution Plane.

---

## §54 Model Serving Abstraction

`ModelServingDirectory` remains a primary-platform abstraction.

It resolves:

```text
logical model + model version
        ↓
logical execution endpoint
```

It MUST NOT resolve:

- GPU pod IPs;
- Kubernetes pods;
- Kubernetes nodes;
- individual GPUs;
- vLLM instances.

The primary platform therefore remains independent of the physical GPU topology.

### §54.1 Model Lifecycle Ownership

| Concern | Owner |
|---------|-------|
| Logical model identity | Primary Platform |
| Model/version approval | Primary Platform |
| Tenant/model policy | Primary Platform |
| Logical endpoint mapping | Primary Platform |
| Model deployment | GPU Execution Plane |
| Runtime configuration | GPU Execution Plane |
| Replica lifecycle | GPU Execution Plane |
| GPU placement | Kubernetes |
| Runtime health | GPU Execution Plane |

The GPU Execution Plane MUST NOT deploy a model/version that has not been approved and made available through the primary platform's logical model configuration.

### §54.2 Model Readiness and Cold Start

The GPU Gateway may return `MODEL_NOT_READY` if the requested model is approved but not currently loaded into GPU memory.

To prevent a thundering herd of retries from the primary platform, the Gateway:

- MUST queue the request and trigger an asynchronous model load if one is not already in progress;
- MAY serve queued requests once the model becomes ready, subject to a configurable maximum queue time;
- MUST return `MODEL_NOT_READY` only if the model cannot be loaded within the configured timeout or if the queue is full.

The primary platform's retry policy for `MODEL_NOT_READY` MUST include exponential backoff and jitter. The Gateway MUST NOT rely on the primary platform to poll for readiness - queuing and deferred execution are the preferred failure-avoidance mechanisms.

---

## §55 GPU Gateway

The GPU Gateway is the execution-plane boundary.

It exists because the GPU cluster is an independent **trust, infrastructure, and execution boundary**, not merely because a remote endpoint is required.

The Gateway is responsible for:

- mTLS/service authentication;
- authorization assertion validation;
- request validation (PGV);
- admission;
- execution ID generation;
- dispatch;
- cancellation;
- execution status (`GetStatus`);
- observability;
- GPU usage reporting;
- durable idempotency store management.

The Gateway MUST NOT become a second business-logic engine. It MUST NOT:

- select enterprise business workflows;
- own tenant policy;
- own business state;
- perform primary-platform query planning;
- make independent model policy decisions.

### §55.1 Idempotency Store

The Gateway maintains a durable store mapping:

```
request_id → execution_id + serialized ExecutionResponse
```

When an `Execute()` request arrives:

1. The Gateway checks the store for the provided `request_id`.
2. If found, the Gateway returns the previously stored `ExecutionResponse` without re-executing.
3. If not found, the Gateway generates a new `execution_id`, executes the operation, stores the result, and returns the response.

The idempotency store MUST be backed by a durable database (PostgreSQL) with a unique constraint on `request_id`. Ephemeral caches such as Redis are insufficient as the sole source of truth.

**Critical invariant:**

> The idempotency store must be **fail-closed**. If the store is unhealthy or unreachable, the Gateway MUST return a `5xx` error and block processing. It MUST NOT pass the request through without the idempotency check.

The store retains entries for a configurable retention window aligned with the primary platform's maximum retry horizon (e.g., 24 hours).

---

## §56 Identity and Authorization

### §56.1 Authentication

The GPU Gateway authenticates the calling service using mTLS.

Transport identity answers: *Who is calling?*

The authenticated service identity is established independently from the request's `tenant_id`.

### §56.2 Tenant Context

A request may contain a tenant context assertion.

> `tenant_id` is an authorization context assertion, not an authentication credential.

The GPU Gateway MUST validate that the asserted tenant is within the authenticated service principal's permitted scope.

The Gateway MUST NOT trust an arbitrary caller-supplied `tenant_id`.

The intended flow is:

```text
External caller
      │
      ▼
   synapt
      │
      │ authenticated tenant/service identity
      ▼
Primary platform
      │
      │ authorized execution request
      ▼
GPU Gateway
      │
      ├── authenticate service (mTLS)
      ├── validate tenant assertion
      ├── authorize operation/model
      └── execute
```

---

## §57 Execution Contract (`synanton.gpu.v1`)

The public contract is versioned under `synanton.gpu.v1`.

The contract uses gRPC with PGV validation.

```protobuf
service GPUExecutionService {
    rpc Execute(ExecutionRequest) returns (ExecutionResponse);
    rpc Cancel(CancelRequest) returns (CancelResponse);
    rpc GetStatus(GetStatusRequest) returns (ExecutionStatus);
    rpc GetCapacity(GetCapacityRequest) returns (CapacityResponse);
}
```

### §57.1 Execution Request Fields

| Field | Required | Owner | Description |
|-------|----------|-------|-------------|
| `request_id` | Yes | Primary Platform | Originating request identity; idempotency key |
| `tenant_id` | Yes | Primary Platform | Tenant context assertion (validated by Gateway) |
| `model` | Yes | Primary Platform | Logical model name |
| `model_version` | Yes | Primary Platform | Approved model version |
| `operation` | Yes | Primary Platform | `SYNTHESIZE` \| `EMBED` \| `RERANK` |
| `execution_class` | No | Primary Platform | Execution priority/class hint |
| `payload` | Yes | Primary Platform | Operation-specific input |
| `trace_context` | No | Primary Platform | OpenTelemetry trace context |

The caller MUST NOT generate or control the GPU execution ID. The Gateway generates `execution_id` and returns it in `ExecutionResponse`.

### §57.2 Long-Running Execution Semantics

The `Execute()` RPC is intended to be long-lived and may block for the duration of GPU inference (which can exceed typical gRPC timeouts).

If the primary platform's configured deadline elapses before a final `ExecutionResponse` is received:

- the primary platform MUST call `GetStatus(execution_id)` to reconcile the eventual outcome;
- the GPU Gateway MUST NOT terminate the underlying GPU operation solely because the initiating gRPC stream closed;
- the Gateway MUST persist execution state such that `GetStatus()` remains authoritative after the stream terminates.

### §57.3 Advisory Capacity

`GetCapacity()` is an **advisory observability API**, not a reservation API.

- A successful capacity response MUST NOT reserve GPU capacity.
- Admission remains authoritative at execution time.
- The primary platform MUST NOT implement correctness assumptions based on a previous `GetCapacity()` result.

---

## §58 Execution Identity and Lifecycle

### Identity

Three identities are distinguished:

| Identity | Owner | Purpose |
|----------|-------|---------|
| `request_id` | Primary Platform | Original request/workflow identity |
| `execution_id` | GPU Gateway | GPU execution attempt identity |
| Runtime request ID | GPU runtime | Internal runtime identity |

GPU execution state MUST NOT become business state.

### Execution Lifecycle

The GPU Execution Plane may represent states:

```text
QUEUED
  ↓
RUNNING
  ├── SUCCESS
  ├── FAILED
  ├── CANCELLED
  └── TIMEOUT
```

The primary platform may maintain a separate business lifecycle correlated through `request_id` and `execution_id`. The boundary does not imply distributed transactional state. The primary platform uses `GetStatus()` to reconcile any ambiguous outcomes following a network interruption or timeout.

### Cancellation

`Cancel()` is best-effort.

- Cancellation MUST NOT imply rollback of business state.
- If a GPU execution is cancelled, the primary platform determines the resulting business/workflow state.
- No distributed transaction is initiated.
- The Gateway MUST return a structured result indicating whether cancellation was accepted, completed, or could not be applied.

---

## §59 Scheduling and Dispatch

There are three distinct layers:

### §59.1 Execution Planning - Synanton

*What should happen?*

The primary platform decides: operation, model, model version, policy, execution class, fallback/degraded behavior.

### §59.2 Request Scheduling - Optional Equalix

*Which eligible request should happen next?*

Equalix may provide: fairness, quotas, priorities, tenant-aware scheduling, GPU-class-aware admission, queue management.

Equalix is optional. The initial implementation SHOULD use `DirectDispatcher`. Equalix SHOULD be introduced only when measurable contention, fairness, quota, or priority requirements justify it.

### §59.3 Infrastructure Scheduling - Kubernetes

*Where should the workload run?*

Kubernetes owns: pod placement, node selection, GPU allocation, replica scheduling, infrastructure lifecycle.

Synanton and Equalix MUST NOT schedule Kubernetes nodes directly.

### Dispatch Strategy

```text
ExecutionDispatcher
        │
        ├── DirectDispatcher (default)
        │     Delegates to Kubernetes Service; no global scheduling
        │
        └── EqualixScheduler (optional)
              Schedules requests by policy before dispatching
```

---

## §60 Network and Trust Boundary

The CPU/GPU boundary is a trust boundary.

Required characteristics:

- private network connectivity;
- mTLS;
- authenticated service identity;
- authorization validation;
- explicit endpoint configuration;
- no direct pod/node access from the primary platform.

The primary platform MUST communicate only with the GPU Gateway.

The GPU Gateway MAY communicate with Kubernetes services and GPU runtimes inside the GPU execution plane.

### Security Invariants

The following invariants are mandatory:

1. The primary platform never accesses GPU pods directly.
2. The primary platform never depends on Kubernetes GPU APIs.
3. The GPU plane never becomes the source of tenant policy.
4. `tenant_id` alone never authenticates a request.
5. The GPU Gateway authenticates the calling service via mTLS.
6. Tenant assertions are authorized against authenticated identity.
7. GPU runtime credentials never cross into external API clients.
8. Model deployment follows approved model/version configuration.
9. GPU execution state is not treated as business state.
10. User-facing business errors are rendered by the primary platform.

---

## §61 Error Contract and Validation

### Error Categories

```text
UNAUTHORIZED
TENANT_NOT_ALLOWED
INVALID_REQUEST
MODEL_NOT_FOUND
MODEL_NOT_READY
MODEL_LOAD_TIMEOUT
GPU_UNAVAILABLE
GPU_CAPACITY_EXCEEDED
EXECUTION_TIMEOUT
EXECUTION_CANCELLED
EXECUTION_FAILED
```

Each error defines whether it is retryable. The primary platform decides user-facing behavior and business-level fallback. The GPU Gateway MUST NOT inject user-facing business messages into the primary platform's response model.

### Validation

The GPU API MUST follow existing Synanton gRPC validation conventions:

- PGV is the canonical structural validation mechanism;
- standard Synanton validation interceptors are reused;
- validation rules are defined in the protobuf contract;
- GPU-specific validation MUST NOT introduce an independent validation framework.

---

## §62 Observability

The GPU execution plane participates in the existing Synanton observability model. Cross-cluster requests propagate trace context.

The GPU plane SHOULD expose low-cardinality execution attributes:

```text
operation
model
model_version
gpu_type
execution_class
```

Tenant identity MUST NOT be placed in ordinary trace attributes unless explicitly required by the existing observability policy. Tenant-aware usage and billing dimensions belong in appropriate metrics/audit mechanisms rather than unrestricted high-cardinality tracing.

Cross-cluster telemetry uses explicitly configured infrastructure endpoints. Application-level service discovery is not required for telemetry export.

Metrics catalogue: see §45 of this document.

---

## §63 Cost and Usage

The GPU Execution Plane reports usage facts:

- execution duration;
- GPU class;
- model;
- model version;
- token/usage counters where available;
- execution outcome.

The primary platform owns:

- tenant attribution;
- business cost policy;
- budgeting;
- billing interpretation.

The GPU plane reports measurements; it does not define enterprise billing policy.

---

## §64 Failure Model and Degraded Mode

The architecture assumes independent failure of the two planes.

### Failure Scenarios

| Scenario | Behavior |
|----------|----------|
| **Primary platform unavailable** | GPU execution may continue internally, but no new business requests are admitted through Synanton. |
| **GPU plane unavailable** | Primary platform remains available. GPU-dependent operations fail or degrade according to primary-platform policy. |
| **Network partition** | Caller may not know whether execution was accepted. Primary platform reconciles ambiguous executions via `GetStatus(execution_id)`. No exactly-once semantics inferred from synchronous transport. |
| **GPU runtime crash** | GPU plane reports failure and may recover/retry per its execution policy. Primary platform determines resulting business state. |
| **Kubernetes scheduling failure** | GPU plane reports capacity/admission failure. Primary platform may retry, fall back, or fail. |
| **Idempotency store unavailable** | Gateway returns `5xx` and blocks all executions (fail-closed). Primary platform receives error; applies degraded-mode policy. |

### Degraded Mode

GPU execution is an optional execution capability. The primary platform MUST be able to degrade gracefully when the GPU plane is unavailable, unhealthy, overloaded, missing the requested model, or unable to satisfy the requested GPU class.

```text
GPU unavailable
      │
      ├── fallback to CPU implementation
      ├── return partial result
      ├── retry
      └── fail request
```

The GPU plane reports structured execution failure. It does not decide the business-level fallback strategy.

------

# Part IX - Structured Content Extraction Plane

*(new in v1.21)*

## Part IX - Structured Content Extraction Plane (summary)

**Invariant:** the platform specifies *what* to extract and under *what constraints*. The plane specifies *how*. Parsers, OCR sidecars, GPUs, queues, and worker topology MUST NOT appear on the contract.

```text
Object store (raw bytes)
        │
        ▼
synanton.extraction.v1  (ExtractSync / async later)
        │
        ▼
DocumentPayload (elements, headings, tables, page boxes)
        │
        ▼
synflux SemanticChunkStage → persist (page/section) → synquest
```

**In scope for the current PoC:** `ExtractSync`, `GetCapabilities`, MinIO source reads, text + PDF adapters, fail-open Tika in synflux when the plane is down or declines a type.

**Out of scope for the PoC (still planned):** async operations, PostgreSQL operation store, dedicated `extraction-client`, SCEP-6 topology proof.

Full contract text: [`../proposals/v1.21/Synanton_v1.21_Structured_content_extraction_plane.md`](proposals/v1.21/Synanton_v1.21_Structured_content_extraction_plane.md).  
Implementation plan: [`../implementation/content-extraction-plane/INDEX.md`](../implementation/content-extraction-plane/INDEX.md).

------

# Part X - Semantic Content Structuring / Chunking

*(new in v1.22)*

## Part X - Semantic Content Structuring / Chunking (summary)

**Invariant:** structured extraction is the canonical input to semantic chunking. Chunk boundaries SHOULD follow document semantics-section hierarchy, lists, tables, figures-while token/size limits provide a secondary constraint and fallback. `flattenedText` MUST NOT be the only input available to the chunking stage. The chunking logic MUST NOT reside inside the extraction plane.

```text
Object store (raw bytes)
        │
        ▼
synanton.extraction.v1  (ExtractSync / async later)
        │
        ▼
DocumentPayload (elements, headings, tables, page boxes)
        │
        ▼
Structure Builder → Section Tree
        │
        ▼
Semantic Chunker → chunks (sectionPath, sourceElements, page/bbox)
        │
        ├───────────────────────┬───────────────────────┐
        ▼                       ▼                       ▼
 Embedding chunks      Summarization context    Search metadata
        │
        ▼
 persist → synquest (BM25 + optional HNSW)
```

**Separation of concerns:**

| Layer | Responsibility |
|---|---|
| Extraction Plane (v1.21) | “What is in this document and what is its structure?” |
| Chunking Layer (v1.22) | “How should this structure be represented for a particular downstream task?” |
| Knowledge Processing | “What does this mean in the Synanton domain?” |

**Core chunking principles:**

1. **Structured input only** - chunk boundary decisions use the normalized `elements` collection, not `flattenedText`.
2. **Semantic boundaries first** - sections, subsections, lists, tables, and figures define boundaries; pure token splitting is a final fallback.
3. **Hierarchical, not one-heading-per-chunk** - large coherent sections split at paragraph/list boundaries when they exceed the token budget.
4. **First-class tables** - tables MUST NOT be split arbitrarily; they are atomic chunks with structured content and an embedding-friendly projection.
5. **`sectionPath` on every chunk** - heading hierarchy (e.g. `["3. GPU Execution Plane", "3.1 GPU Gateway"]`) travels with each chunk for retrieval and citation.
6. **Provenance preservation** - `sourceElements`, `pageStart`, and `pageEnd` link every chunk back to extraction evidence.

**In scope for the current PoC:** structure-aware document chunking in `synflux` (`SemanticChunkStage`), heading hierarchy via `section_path`, atomic table chunks, provenance fields on persisted chunks, BM25 index with citation metadata.

**Out of scope for the PoC (still planned):** multimodal chunking (audio turn-based, image OCR/description, video scene/clip), chunking tags on the extraction request, summarization hierarchy built from chunk tree, dedicated `semantic-chunking` service boundary.

Full design text: [`../proposals/v1.22/Synanton v1.22  Structured Content Semantic Chunking Design Proposal.md`](proposals/v1.22/Synanton%20v1.22%20%20Structured%20Content%20Semantic%20Chunking%20Design%20Proposal.md).  
Implementation plan: [`../implementation/semantic-chunking/INDEX.md`](../implementation/semantic-chunking/INDEX.md).

------

# Appendices *(new in v1.17)*

------

## Appendix A - Capacity Planning Guide

This appendix consolidates the storage, compute, and network sizing rules-of-thumb that operators need when standing up a new Synanton deployment or planning tenant growth. All numbers assume the v1.17 default configuration; adjust proportionally for tuned deployments.

### A.1 Per-document footprint (median, English text)

Assumes a "typical enterprise document" of 8 KB parsed text, 20 chunks of ~400 tokens each, one embedded image, no external attachments.

| Storage class | Bytes per document | Notes |
|---------------|--------------------:|-------|
| `manifest` row (PostgreSQL/Cassandra) | ~1.2 KB | id, ACL, generation, tier, embedding_quality, timestamps |
| `chunks` rows (Cassandra hot) | ~10 KB | 8 KB raw + parse overhead |
| `analysis_cache` (Cassandra) | ~3 KB | Pass-1 JSON |
| `image_caption_cache` | ~0.5 KB | median caption |
| `embedding_content_cache` | ~24 KB | 20 chunks × 1024-dim × float16 = 40 KB, then LZ4 ≈ 24 KB |
| `synquest` HNSW index footprint | ~35 KB | 20 vectors × 40 bytes bookkeeping + graph edges |
| `synquest` Tantivy inverted index | ~4 KB | after posting-list compression |
| `relix` graph nodes+edges (avg) | ~5 KB | 3 entities × 400 B + 6 edges × 300 B |
| `synvault-warm` full body (S3) | ~8 KB | raw bytes |
| **Total, hot path** | **~90 KB** | before cross-tenant synthesis cache |

**Per 1M documents (hot path):** ~90 GB across all stores. Add 30 % for indexing overhead, WAL, replication factor 3 on Cassandra → **~350 GB steady-state per 1M docs**.

### A.2 GPU hours per 1M documents

Assumes v1.17 defaults: `bge-small-en-v1.5` embed, two-step chain-of-thought enrichment at `synanton-analysis-mid`, no vision on 80 % of docs.

| Stage | GPU-hours per 1M | Notes |
|-------|------------------:|-------|
| Embed (`bge-small-en-v1.5`) | ~35 | Batch size 32 on A10G |
| Pass-1 Analysis | ~180 | Long-context prompt; cheaper on A100 |
| Pass-2 Generation | ~120 | Shorter output; cache-friendly |
| Vision captioning (20 % of docs) | ~40 | `qwen2-vl-7b` at 5 sec/image |
| Reranker (Query-side, amortised) | ~10 | Assumes 100k queries/day/1M docs |
| Synthesis (query-side, amortised) | ~50 | GraphRAG usage rate ~5 % of queries |
| **Total ingest + steady-state query** | **~435 GPU-hours** per 1M docs (first-pass) |

**Ongoing steady-state query GPU:** dominated by reranker + synthesis; assume ~5 GPU-hours per day per 100k live queries.

### A.3 Network & cross-region

Cross-region bytes-per-query - small (a hit list of 20 hits × ~2 KB each ≈ 40 KB request response). The bottleneck is not bandwidth but the RTT penalty in the map at §22/§43. Rule of thumb: assume 5 % of queries cross-region for HIGH_SECURITY, 15 % for STANDARD (many tenants have data anchored to a specific region).

Cross-region ingestion transfer is dominated by `synflux_enriched_chunks` mirroring:
- **Per 1M docs mirrored:** ~15 GB Kafka wire (chunks + enrichment metadata).
- **Per day steady-state:** budget 500 MB - 5 GB depending on ingest rate.

### A.4 Sizing formulas

```
storage_gb_hot_per_tenant   = docs_millions × 350
gpu_hours_first_pass        = docs_millions × 435
kafka_wire_gb_per_day       = docs_per_day × 15e-6         // 15 μB per doc
pg_wal_gb_per_day           = writes_per_day × 4e-6        // includes replication overhead
redis_gb                    = active_sessions × 15e-6 + top_k_cache_gb   // ~15 KB per session
```

Multiply by replication factor (3 default) and add 30 % headroom for compaction, WAL, and incident overhead. Aim for < 70 % steady-state utilisation on all classes - the platform's adaptive backpressure assumes headroom.

### A.5 Recommended cluster shapes

For **1M live documents**, single-region deployment:

- **PostgreSQL** - 2 × 8 vCPU / 32 GB RAM / 500 GB gp3, HA pair.
- **Cassandra / Scylla** - 6 × 16 vCPU / 64 GB RAM / 2 TB NVMe.
- **Kafka** - 3 × 8 vCPU / 32 GB RAM / 1 TB NVMe.
- **synquest shards** - 4 × 32 vCPU / 128 GB RAM.
- **vLLM GPU cluster** - 4 × A10G for embed + reranker; 2 × A100-80GB for synthesis.
- **Redis** - 2 × 4 vCPU / 16 GB RAM, HA pair.

For **10M live documents**, scale linearly on Cassandra and synquest; double the vLLM footprint; PostgreSQL usually still fits on the same nodes (metadata scales sub-linearly).

### A.6 When to add capacity

- `synflux_embedder_gpu_ms_total` rate > 60 % of GPU-hours-available → add embed replicas.
- `synquest_shard_disk_used_ratio` > 0.65 → schedule shard split (§20).
- Any `synflux_router_short_retention == 1` → increase Kafka disk (before it becomes an incident).
- `pg_wal_growth_gb_per_hour > 5` sustained → check for hot loops.
- `dr_replication_lag_seconds > 0.5 × RPO` → investigate before it becomes an RPO violation.

------

## Appendix B - Module Dependency Diagram

A **compile-time** dependency graph. At runtime, additional cross-module traffic exists via Kafka; those are event-flow dependencies documented in Part II. Arrows point from consumer to provider.

```
                                              ┌──────────────────┐
                                              │  synanton-mcp    │
                                              │  (Node.js bridge)│
                                              └────────┬─────────┘
                                                       │  HTTP
                                                       ▼
             ┌─────────────────────────────┐   ┌──────────────┐
             │      Admin Console UI       │   │    synapt    │◀──── external clients
             └────────────────┬────────────┘   └──────┬───────┘        (REST/gRPC)
                              │  HTTP                 │
                              ▼                       ▼
                       ┌───────────────────────────────────────┐
                       │              gateway                  │
                       │  (ACL inject, cache, rerank, synth)   │
                       └───┬──────────────┬─────────────┬──────┘
                           │              │             │
                           ▼              ▼             ▼
                    ┌──────────┐   ┌────────────┐  ┌──────────┐
                    │ planner  │──▶│  security  │  │synanton- │◀── used by:
                    └────┬─────┘   │(broker,    │  │ llm-client│    synflux, gateway,
                         │         │ IdP amort.)│  │ (library) │    synreview,
             ┌───────────┼─────────┴────┬───────┘  └──────────┘    control-plane
             ▼           ▼              ▼
         ┌────────┐  ┌────────┐   ┌──────────┐
         │synquest│  │ relix  │──▶│ syntology│
         │(Rust)  │  │        │   │ (ontology)│
         └────┬───┘  └───┬────┘   └────┬─────┘
              │          │             │
              │          ▼             │
              │   ┌────────────┐       │
              │   │  synreview │◀──────┴──── producers:
              │   │            │             synflux, syntology,
              │   └─────┬──────┘             control-plane
              │         │
              ▼         ▼
         ┌────────────────────┐        ┌──────────────────┐
         │  ingestion-cache   │◀───────│      synflux     │
         │  (Cassandra/Scylla)│        │  (ingest+router) │
         └─────────┬──────────┘        └────┬─────────────┘
                   │                        │
                   │                        ▼
                   │                 ┌────────────┐
                   │                 │  synvault  │◀── content adapters (SPI)
                   │                 │(tier mgr)  │
                   │                 └─────┬──────┘
                   │                       │
                   ▼                       ▼
                 ┌─────────────────────────────────┐
                 │           topology              │◀── control-plane
                 │  (Postgres: orgs, ACLs, jobs)   │      GitOps reconciler
                 └─────────────────────────────────┘
```

**Non-obvious relationships:**

- **`control-plane`** is *not* on the query hot path. It talks to every module via Admin APIs and Kafka, but no synchronous query traffic flows through it.
- **`synanton-llm-client`** is a *library*, embedded in every service that talks to an LLM. It is not a network hop.
- **`security`** is called synchronously by `gateway` (ACL resolve) and asynchronously by `topology` (outbox dispatch). Its outbound broker is called by any adapter making a federated call.
- **`syntology`** is read-heavy from `relix`, `synflux`, and `synreview`. Writes go through `synreview` gate (v1.16+).
- **`synquest`** and **`relix`** are peers under `planner`; neither depends on the other.
- **`synanton-mcp`** talks to Synanton **only through `synapt`** - no direct dependency on internals. This is enforced by module packaging.

Circular dependencies at the package level are not permitted; the CI job `dep-graph-lint` fails builds that introduce them.

### GPU Execution Plane additions *(v1.20)*


```text
synanton/platform
    │
    ├── gateway
    │     └── GPU Execution Client ──────────────────────┐
    │                                                     │
    └── ModelServingDirectory                             │ gRPC + mTLS
                                                          │ synanton.gpu.v1
                                                          ▼
                                              synanton/gpu-execution-plane
                                                    │
                                                    └── GPU Gateway
                                                          ├── DirectDispatcher
                                                          ├── EqualixScheduler (optional)
                                                          ├── Idempotency Store (PostgreSQL)
                                                          └── Kubernetes Service → vLLM → GPU nodes
```

------

## Appendix C - Migration Process

This appendix documents the process for evolving the platform between versions. It is a **process** document, not a diff - the individual changes in v1.17 are catalogued in the "What's new" table at the top of this document.

### C.1 Change categories

Every proposed change falls into one of three categories. The category determines the migration protocol.

1. **Additive** - new field, new column, new topic, new module. Old callers keep working unchanged. This category dominates v1.17 (all 15 changes are additive).
2. **Schema-migrating** - changes an existing column's type, renames a topic, tightens a constraint. Requires the N/N+1/N+2 discipline in §42.
3. **Breaking** - removes a field, changes an SPI, renames a module. **Not permitted in the merged-reference lineage.** These require a documented exception, executive sign-off, and a customer-communication plan; there have been zero across v1.7 → v1.17.

### C.2 Proposal → Design → Implementation

1. **Proposal.** Author drafts a proposal document (see `Synanton Platform Version 1.17 Proposal.md` as canonical shape). Proposal must state:
   - Motivation (what breaks or under-serves today).
   - Change category (§C.1).
   - Impact on each affected section of the merged design.
   - Migration effort estimate (Low / Medium / High).
   - Rollback plan.
2. **Review.** 1-week open comment period. Architects, module owners, SREs, and security engineers must ack. Silence past the deadline is not consent - a live owner must ack.
3. **Detailed design.** 2 weeks to fold the proposal into a draft update of the merged design document. Section-by-section deltas. This is the artefact under review in the final approval meeting.
4. **Implementation.** 4 weeks typical. Includes CI updates, dashboards, alert-runbook pairs, and migration tests.
5. **Validation.** 1 week. Integration + chaos tests. Every new alert must fire in a synthetic test at least once before GA.
6. **Doc finalisation.** 1 week. Merged design bumped to next version; proposal moved to `docs/architecture/platform/proposals/` archive.

### C.3 Schema migration protocol (recap of §42)

For schema-migrating changes:

- **N - Dual-write.** Add the new shape; both old and new fields are written. Reads still prefer old.
- **N+1 - Backfill + read-switch.** Backfill the new field for all historical rows. Reads switch to the new field. Old field is still written for one more release.
- **N+2 - Remove old.** Drop the old field. CI job `schema-diff` verifies no live traffic uses it (see §24 deprecation policy).

Never skip an N. A "small refactor" that combines N and N+2 in the same release is the most common cause of migration incidents.

### C.4 Rolling upgrade procedure

Applies to every release, minor or patch:

1. **Pre-flight.** `synctl release preflight <version>` runs schema-diff CI, verifies feature flags default sensible, verifies alert runbooks exist for every new alert.
2. **Canary.** Deploy new version to one region carrying 5 % of traffic (or an internal-tenant-only region). Bake for 24 h with a specific canary dashboard focused on the deltas from the release.
3. **Percentage roll-out.** 5 % → 25 % → 50 % → 100 %, 6 h dwell between steps, automatic rollback if any error-budget-relevant SLO breaches.
4. **Post-flight.** After 100 % for 48 h, disable the release-flag gate. Deprecated shims from earlier releases that satisfied the "still in use" gate check now become eligible for removal in future N+2 waves.

### C.5 Rollback

Every release must be rollback-safe for at least 7 days after 100 % rollout:

- No schema-migrating change can be introduced without a documented rollback playbook, verified in a rollback drill.
- Additive changes are trivially rollback-safe as long as the corresponding *disable-feature* config lands as part of the release (not gated behind further deploys).
- If a rollback is required, `synctl release rollback` is a single command; it is expected to succeed within 15 minutes.

### C.6 Feature flags

Every non-trivial feature ships behind a flag in `topology.tenant_policy.feature_flags JSONB`. Flags follow lifecycle:

- **`disabled`** - Off by default; opt-in for beta tenants. Default state at release.
- **`enabled_default_off`** - Available; individual tenants can turn on.
- **`enabled_default_on`** - On by default; individual tenants can turn off.
- **`always_on`** - Cannot be disabled. Reached after 2 quarters at `enabled_default_on` with no rollback events.
- **`removed`** - Flag removed; code path is unconditional.

The v1.17 flags start at `disabled` or `enabled_default_off` depending on risk; see the compatibility statement at the top of this document for the concrete defaults chosen.

### C.7 Communicating breaking changes to consumers

Since the platform commits to no breaking API changes, this section only applies to the SPI (`relix graph connector SPI`, `Content Adapter SPI`, `WebSearchAdapter SPI`, `Reranker port`, `Identity Provider port`, `ACL Propagation port`). SPI evolution obeys the same N/N+1/N+2 discipline as schema, with these extra rules:

- New required fields must have defaults (implicit `null`) that produce v1.16 behaviour.
- Deprecation must be announced via the `synapt_deprecated_field_usage_total` mechanism (§24) at least one release before enforcement.
- First-party connector implementations must publish a compatibility matrix in their README showing which SPI versions they support.

Third-party connector authors who cannot keep pace with SPI evolution can pin to an older release train - Synanton commits to security-patch backports for the two prior minor releases (i.e. at v1.17 GA, v1.15 and v1.16 remain patchable for critical security issues for one quarter).

------

## Appendix D - `synanton-ops` Binary - Build & Distribution *(new in v1.19)*

The `synanton-ops` Go binary carries both the `helper` (§26b) and `wizard` (§26c) command trees. It is the sole runtime artefact for v1.19's operational CLI surface. `synctl` continues to be the single user-facing CLI entrypoint - `synctl helper …` and `synctl wizard …` are thin exec wrappers that hand off to `synanton-ops` with the remaining arguments.

### D.1 Language & toolchain

- **Language:** Go 1.22 (LTS). Chosen for single-binary distribution, static linking, straightforward cross-compilation, and mature CLI ergonomics (Cobra + Viper).
- **Modules:** `github.com/synanton/synanton-ops`.
- **Formatting / linting:** `gofmt`, `goimports`, `golangci-lint` (with `errcheck`, `gosec`, `staticcheck`, `revive` enabled).
- **Vulnerability scan:** `govulncheck` on every CI run.

### D.2 Repository layout

```
synanton-ops/
├── cmd/
│   └── synanton-ops/
│       └── main.go                     # single entrypoint; dispatches to helper/ or wizard/
├── internal/
│   ├── helper/                         # §26b command tree
│   │   ├── auth/                       # SYNANTON_SUPPORT_KEY resolution + rotation warning
│   │   ├── client/                     # HTTP client with retry/backoff/idempotency-key
│   │   ├── audit/                      # ~/.synanton/helper-audit.jsonl writer
│   │   └── commands/                   # status/bundle/clean/delete/recrawl/workflow/key
│   └── wizard/                         # §26c command tree
│       ├── schema/                     # embed.FS: wizard/schema/v1.json
│       ├── templates/                  # embed.FS: default Terraform/Helm/Compose templates
│       ├── prompt/                     # interactive TUI for `wizard init`
│       └── commands/                   # init/generate/validate/apply/diff
└── pkg/
    └── api/                            # generated OpenAPI clients for /admin/_internal/*
```

- `internal/helper` and `internal/wizard` **share nothing**: no shared globals, no shared config parsing, no shared filesystem writes. The only overlap is `internal/version`, which prints the same version banner.
- Templates and JSON Schemas are embedded via Go's `embed.FS` so a released binary is fully self-contained and works air-gapped.

### D.3 Build

```
# Local dev
make build

# Multi-arch release
make release
# → dist/synanton-ops_<version>_linux_amd64
# → dist/synanton-ops_<version>_linux_arm64
# → dist/synanton-ops_<version>_darwin_amd64
# → dist/synanton-ops_<version>_darwin_arm64
# → dist/checksums.txt (SHA256)
# → dist/checksums.txt.sig (cosign)
```

- **`CGO_ENABLED=0`** - pure Go build, no glibc dependency.
- **`-trimpath`** - reproducible builds; no local paths embedded.
- **`-ldflags="-s -w -X main.version=<tag> -X main.commit=<sha>"`** - strip symbols, embed release identity.
- **Reproducibility:** CI verifies that building the same tag twice produces byte-identical binaries.

### D.4 Signing & verification

- **cosign** signs `checksums.txt` at release time.
- Each release attaches an SLSA-level-3 provenance attestation (built from a hermetic GitHub Actions workflow with no network access after checkout).
- `synctl release verify <version>` verifies the signature and provenance before extracting the binary.

### D.5 Distribution

The binary is distributed through three channels; all three receive the exact same artefact set:

1. **Release tarball** (`synanton-<version>.tar.gz`) - includes `synanton-ops`, `synctl`, JSON Schemas, and default templates. Landing here is the canonical path for regulated deployments.
2. **Homebrew tap** (`brew install synanton/tap/synanton-ops`) - for macOS operators.
3. **Container image** (`ghcr.io/synanton/synanton-ops:<version>`) - for CI use. Distroless base image; no shell. Signed with the same cosign key as the tarball.

Package installers unpack `synanton-ops` into `$PREFIX/libexec/synanton/` and wire `synctl` (the wrapper) into `$PREFIX/bin/`. `synctl` shells out with:

```
exec "$SYNANTON_LIBEXEC/synanton-ops" "$@"
```

### D.6 Versioning

- The binary's version follows the platform release cadence (`v1.19.0`, `v1.19.1`, …).
- Both command trees ship in lockstep with the platform: a v1.19 binary talks to a v1.19 cluster's `/admin/_internal/*` API.
- On startup, `helper` records the negotiated API server version (from the `Server` header on `/admin/_internal/status`) and refuses to proceed if the server major version is behind the binary's expected minimum. `wizard` is version-agnostic - it emits artefacts targeting the platform version declared in `deployment-config.yaml`.

### D.7 Test posture

- `test:unit` - Go unit tests for all command handlers. Command execution is table-driven; HTTP client is mocked via `httptest.Server`.
- `test:integration` - Testcontainers-Go spins up a mock `synapt` + `control-plane` pair with fixture-backed handlers; verifies the end-to-end call path for every `helper` command including idempotency-replay.
- `test:template` - `wizard` generates artifacts for every cloud × profile combination in a matrix job; the resulting files are validated with `terraform validate`, `helm lint`, and `docker-compose config`.
- `test:cli` - `bats-core` shell tests exercise the `synctl` wrapper (env-var handling, profile selection, exit codes).
- No `test:llm` tier applies - neither `helper` nor `wizard` calls an LLM.

### D.8 Release cadence

`synanton-ops` is released whenever the platform releases. Patch releases can ship independently for the CLI if a critical bug is found - the CLI's contract with the server (via OpenAPI clients under `pkg/api/`) is backwards-compatible within a minor line, so a newer patch talks to an older cluster in the same minor.

------

# Compatibility and Version Lineage

## Rolling upgrades

- Rolling from 1.21: extraction client/URL unset keeps Tika-only ingest; when extraction succeeds, chunking uses structure when elements are present, flat-text fallback otherwise.
- Rolling from 1.20: GPU client remains off until `gateway.gpu.enabled=true` **and** a gpu-runtime that serves the mirrored `synanton.gpu.v1`.
- Rolling from 1.19: Relix graph backends are selected with `relix.graph.connector` (`memory` | `neo4j` | `nebula`); this is an adapter swap, not a design-version break.

## Contract mirrors

`synanton.extraction.v1` is mirrored byte-for-byte between `platform` and `content_extractor` (`scripts/verify-contract-mirror.sh`). `synanton.gpu.v1` is mirrored with `gpu-runtime` (`scripts/verify-gpu-contract-mirror.sh`). Java package for the GPU contract is `org.synanton.gpu.v1`; RPCs are `Execute`, `Cancel`, `GetStatus`, `GetCapacity`; errors are the `ErrorReason` catalogue. Until a mirror holds, the counterpart repository must not be treated as a platform server for that contract.

## Design version lineage

| Version | Content it owned | Where it lives now |
|---|---|---|
| 1.22 | Semantic Content Structuring / Chunking (Part X) | **this document** |
| 1.21 | Structured Content Extraction Plane (Part IX) | Part IX here; original at [`../archive/architecture/synanton-design-1.21.md`](archive/synanton-design-1.21.md) |
| 1.20 | GPU Execution Plane (Part VIII) | Part VIII here; original at [`../archive/architecture/synanton-design-1.20.md`](archive/synanton-design-1.20.md) |
| 1.19 | Parts I–VII baseline + Appendices A–D | Parts I–VII here; original at [`../archive/architecture/synanton-design-1.19.md`](archive/synanton-design-1.19.md) |
| ≤1.18 | Superseded lineage | [`../archive/architecture/`](archive/architecture/) |

Full proposal texts remain under [`../proposals/`](../proposals/): [`v1.20/`](proposals/v1.20/) (GPU isolation), [`v1.21/`](proposals/v1.21/) (extraction plane), [`v1.22/`](proposals/v1.22/) (semantic chunking). Implementation plans live under [`../implementation/`](../implementation/), including [`content-extraction-plane/INDEX.md`](../implementation/content-extraction-plane/INDEX.md) and [`semantic-chunking/INDEX.md`](../implementation/semantic-chunking/INDEX.md).

## Open design work

The classification-aware search work in [`synanton-design-1.23.md`](./synanton-design-1.23.md) extends this document. §20, §23, §25, §26, §40 and §41 here describe the enforcement model as it stands in v1.22; v1.23 documents chunk-level class grants, compile-time class filtering, and ingest-time masking. Implementation: [`../implementation/classification-aware-search/INDEX.md`](../implementation/classification-aware-search/INDEX.md). Demo: [`../demos/classification-aware-semantic-search-demo.md`](../demos/classification-aware-semantic-search-demo.md).

## How "current" is managed

`docs/VERSION` is `1.22`. [`INDEX.md`](./INDEX.md) names this file as authoritative. This is the only live architecture document; 1.19, 1.20 and 1.21 are archived lineage, not pointers to follow.

------

**End of Synanton Platform Architecture Design Document (Merged Reference) - Version 1.22.**
