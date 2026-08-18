# Synanton Platform - Implementation Phases Master Plan

**Version:** 1.0
**Date:** 2026-07-19
**Status:** Draft for review
**Audience:** Architects, module owners, engineering leads, roadmap planners

This is the master roadmap that stitches every module's phase-by-phase plan into one coherent picture. It answers three questions at a glance:

1. **What does each phase deliver?** - user-facing capability at each milestone.
2. **What is every module doing in each phase?** - including stub / no-change entries, so nothing is silently ignored.
3. **Where does each module's per-phase plan live?** - links to the dedicated plan file when one exists, or an in-doc paragraph when the module is a stub for that phase.

**Not in scope for this doc:** implementation details - those live in the individual plan files linked below. This doc is orientation, not execution.

---

## 1. Guiding Principles

- **Additive phases.** No phase removes capability. Later phases layer on top of earlier ones. A running Phase-N system continues to work while Phase-N+1 is under construction.
- **Every module has a plan every phase.** If a module is not being touched, its Phase-N status is `NO-CHANGE` and this doc records that explicitly. If it doesn't exist yet, its status is `STUB` (with a note about what fake it depends on) or `DEFERRED` (with a note about when it starts).
- **Stub-first.** Any module that will be needed later gets a stub deliberately, on schedule. Stubs return `501 Not Implemented` behind their public interface OR are covered by a fake elsewhere (e.g. `MockTenantFilter` filling in for `security`). This keeps callers honest - they must depend on the interface, not on the presence of behaviour.
- **Design-doc alignment.** Every phase honours [synanton-design-1.18.md](../architecture/platform/synanton-design-1.18.md). Where a phase deliberately deviates from the design (e.g. Phase 1 synquest is Java + Lucene rather than Rust), the module's plan file states the deviation and when it converges.
- **Dedicated plan files for real work.** When a module gets `NEW` or `EXT` status in a phase, that phase gets its own plan file (e.g. `01-synquest-Phase1.md`). When it's `STUB` or `NO-CHANGE`, the entry in this master doc is the plan.

---

## 2. Module Inventory

Eighteen modules across four kinds of artefact. Every one appears in every phase table below.

| # | Module | Kind | Design-doc §  | One-line role |
|---|--------|------|---------------|---------------|
| 1 | `shared/common` | Java library | shared plumbing | Types + filters shared across all Java services (`TenantContext`, `ProblemDetail`, `MockTenantFilter`, `SubjectAssertion`) |
| 2 | `ingestion-cache` | Java library | §18 | DAO + Cassandra schema for manifest, chunks, analysis, embeddings |
| 3 | `synanton-llm-client` | Java library | §27c | Provider-agnostic client for LLM + embedding endpoints |
| 4 | `synvault` | Java service | §16 | Content store, adapter registry, tier manager, MinIO/S3 facade |
| 5 | `synflux` | Java service | §17 | Ingestion pipeline (acquire → parse → chunk → enrich → embed → persist) |
| 6 | `synflux-router` | Java service | §17 (router) | Kafka-driven work distribution across synflux workers |
| 7 | `synquest` | Java (→ Rust) service | §20 | Hybrid search kernel (BM25 + dense HNSW + Cuckoo ACL) |
| 8 | `relix` | Java service | §21 | GraphRAG engine, entity/relation graph, MCP tools |
| 9 | `planner` | Java service | §22 | Query intent classification + plan generation |
| 10 | `gateway` | Java service | §23 | Plan execution, fusion, reranker, LLM synthesis, ACL injection |
| 11 | `synapt` | Java service | §24 | Public REST/gRPC ingress, auth, budget, sanitisation |
| 12 | `security` | Java service | §26 | AuthN/Z, JWT, IdP port, outbound auth broker, API key lifecycle |
| 13 | `topology` | Java service | §25 | Authoritative org/ACL/policy store; outbox dispatcher |
| 14 | `control-plane` | Java service | §27 | Admin, forecast, anomaly, GitOps, DR runbooks |
| 15 | `synreview` | Java service | §27a | Human-in-the-loop review + staging queue |
| 16 | `syntology` | Java service | §19 | Ontology management, entity/relation resolution, versioning |
| 17 | `synanton-mcp` | Java service | §27b | MCP protocol bridge - exposes platform tools to external MCP clients |
| 18 | `syntology-admin` | React UI | §46a | Ontology admin frontend (embedded in syntology JAR in early phases) |

The infrastructure containers Synanton runs against - Cassandra, PostgreSQL, MinIO, Kafka, Redis, Neo4j, vLLM - are not modules and are tracked by phase in each service's plan.

---

## 3. Phase Overview (Themes)

| Phase | Theme | User-facing capability unlocked |
|-------|-------|---------------------------------|
| **1** | Foundation - the demo skeleton | Crawl a folder, run hybrid + graph search, return ranked hits |
| **2** | LLM online | Meaningful enriched results + natural-language synthesised answers |
| **3** | Multi-tenant, auth, router | Real users, real tenants, decoupled ingestion via Kafka |
| **4** | Production hardening | ACL enforcement, cross-region routing, sanitisation, rerank, rate limits |
| **5** | Scale + vision + DR | Tier movement, vision captions, Rust hot loop, DR runbooks, GDPR cascade |

Each phase's target duration and staffing is out of scope for this doc; capacity planning lives in [synanton-design-1.18.md Appendix A](../architecture/platform/synanton-design-1.18.md).

---

## 4. Module × Phase Matrix

Compact status per module per phase. Legend below.

| Module | Phase 1 | Phase 2 | Phase 3 | Phase 4 | Phase 5 |
|--------|:-------:|:-------:|:-------:|:-------:|:-------:|
| shared/common | NEW | EXT | EXT | EXT | NO-CHANGE |
| ingestion-cache | NEW | EXT | EXT | NO-CHANGE | EXT |
| synanton-llm-client | STUB | NEW | EXT | NO-CHANGE | NO-CHANGE |
| synvault | NEW | NO-CHANGE | NO-CHANGE | EXT | EXT |
| synflux | NEW | EXT | EXT | NO-CHANGE | EXT |
| synflux-router | STUB | STUB | NEW | EXT | NO-CHANGE |
| synquest | NEW | NO-CHANGE | NO-CHANGE | EXT | EXT (Rust) |
| relix | NEW | NO-CHANGE | EXT | EXT | EXT |
| planner | NEW | EXT | EXT | EXT | NO-CHANGE |
| gateway | NEW | EXT | EXT | EXT | EXT |
| synapt | NEW | EXT | EXT | EXT | NO-CHANGE |
| security | STUB | EXT | EXT | EXT | EXT |
| topology | STUB | EXT | EXT | EXT | NO-CHANGE |
| control-plane | STUB | STUB | EXT | EXT | EXT |
| synreview | STUB | STUB | STUB | STUB | NEW |
| syntology | STANDALONE | EXT | EXT | EXT | NO-CHANGE |
| synanton-mcp | STUB | STUB | NEW | EXT | NO-CHANGE |
| syntology-admin (UI) | STANDALONE | EXT | EXT | EXT | NO-CHANGE |

**Legend.**

- `NEW` - first real implementation of this module ships in this phase.
- `EXT` - module already exists; this phase adds material capabilities.
- `NO-CHANGE` - deliberately untouched this phase; ensures ongoing stability.
- `STUB` - dummy placeholder in place; behaviour comes from a fake elsewhere (documented per cell below).
- `DEFERRED` - module intentionally does not yet exist; nothing to stub against.
- `STANDALONE` - module runs from its own standalone-demo plan on a parallel track (currently just `syntology`).

---

## 5. Phase 1 - Foundation

**Theme.** Prove the pipeline shape end-to-end. Ingest a folder, index, search, return hits.

**Delivers to the user.** From a fresh clone: `docker compose up`, `curl -X POST :8080/search -d '{"query":"who supplies Acme?"}'`, get ranked hits + graph result + trace back. No LLM answer yet, no auth, no multi-tenancy.

**External dependencies added.** Cassandra (single-node), PostgreSQL (empty for this phase), MinIO.

**Modules in Phase 1:**

| Module | Status | What ships in Phase 1 | Plan |
|--------|--------|-----------------------|------|
| `shared/common` | NEW | `TenantContext`, `ProblemDetail`, `MockTenantFilter`, `SubjectAssertion` (stub). Foundation library every service depends on. | This doc (thin - see prereqs in ingestion & query-path plans) |
| `ingestion-cache` | NEW | Cassandra keyspace + `manifest`, `chunks_payload`, `jobs` tables; `IngestionCacheClient` DAO. | [ingestion-pipeline-Phase1.md §4.3](./ingestion-pipeline-Phase1.md) |
| `synanton-llm-client` | STUB | Module does not exist yet - no LLM in Phase 1. Placeholder ADR in the module inventory acknowledges the future dependency. Callers that will need it (planner, gateway) do not depend on it in Phase 1. | This doc |
| `synvault` | NEW | `ContentAdapter` SPI, `FilesystemAdapter` (crawls local dir), `MinioObjectStoreAdapter` (S3 facade), `GET /manifest/{tenant}`, `GET /content/{tenant}/{ref}`. | [ingestion-pipeline-Phase1.md §4.1](./ingestion-pipeline-Phase1.md) |
| `synflux` | NEW | Acquire → Parse → Chunk → NoOp-Enrich → NoOp-Embed → Persist pipeline. CLI + REST `POST /ingest/run`. | [ingestion-pipeline-Phase1.md §4.2](./ingestion-pipeline-Phase1.md) |
| `synflux-router` | STUB | Module folder empty (`.gitkeep`). Synflux core runs the crawl inline; no work distribution yet. Callers rely on `synflux.POST /ingest/run` directly. | This doc |
| `synquest` | NEW | Java + Lucene 9.11 (HNSW + BM25 + RRF), `POST /search`, boot-time index build from `embedding_content_cache`. Wait - Phase 1 has no embeddings yet. See note below. | [01-synquest-Phase1.md](./01-synquest-Phase1.md) |
| `relix` | NEW | JGraphT in-memory graph loaded from `analysis_cache` Pass 2. `POST /graph/query` with three shapes. See note below on data availability. | [02-relix-Phase1.md](./02-relix-Phase1.md) |
| `planner` | NEW | Heuristic 7-rule classifier, 4 plan templates, entity-label trie cached from relix. `POST /plan`. | [03-planner-Phase1.md](./03-planner-Phase1.md) |
| `gateway` | NEW | Plan DAG executor, parallel step dispatch, `content_ref_intersection_first_then_rrf` fusion. `POST /query`. | [04-gateway-Phase1.md](./04-gateway-Phase1.md) |
| `synapt` | NEW | Thin REST `POST /search`, Jakarta Validation seams pre-placed for v1.18 sanitiser, `MockTenantFilter` tenant defaulting. | [05-synapt-Phase1.md](./05-synapt-Phase1.md) |
| `security` | STUB | Module folder empty (`.gitkeep`). Tenant resolution done by `MockTenantFilter` in `shared/common`. `SubjectAssertion` stub returns a fixed identity. No JWT, no IdP. | This doc |
| `topology` | STUB | Module folder empty (`.gitkeep`). Tenant `"demo"` is hard-coded across services. No ACL grants. No `topology_events` topic. | This doc |
| `control-plane` | STUB | Module folder empty (`.gitkeep`). No admin API, no forecast, no anomaly. Operators use direct `docker compose` and DB SQL. | This doc |
| `synreview` | STUB | Module folder empty (`.gitkeep`). No HITL. Low-confidence Pass-2 entities from relix are silently accepted (i.e. the corpus is trusted). | This doc |
| `syntology` | STANDALONE | Runs from its own single-JAR standalone-demo plan track. Not yet integrated with the ingestion pipeline; entity types produced by Pass-2 are ad-hoc strings, not resolved against a syntology ontology version. | [standalone-syntology-demo.md](./standalone-syntology-demo.md) |
| `synanton-mcp` | STUB | Module folder empty (`.gitkeep`). No MCP surface yet. External MCP clients cannot talk to Synanton in Phase 1. | This doc |
| `syntology-admin` (UI) | STANDALONE | Ships in the syntology single JAR at `:8080/`. No general Synanton UI yet; searching from Phase 1 is via curl or Postman. | [standalone-syntology-demo.md](./standalone-syntology-demo.md) |

**Note on Phase 1 sequencing gap.** Two of the query-path plans (`01-synquest`, `02-relix`) formally depend on ingestion Phase 2 output (`embedding_content_cache`, `analysis_cache`). In pure phase-order execution, they cannot run against real data until Phase 2 ships. Two acceptable resolutions:

1. **Run Phase 1 query-path against fixtures.** Both plans' Testcontainers component tests seed synthetic manifest+chunks+embeddings+analysis rows - Phase 1 acceptance is on those fixtures, not on live ingestion output.
2. **Interleave Phase 1 and Phase 2.** Ingestion Phase 2 can start in parallel with the query-path Phase 1 work, since they touch disjoint modules. The combined-DoD "full stack demo" fires when both are done.

The master doc treats "Phase 1" as the intersection of ingestion Phase 1 + query-path Phase 1 + query-path fixtures. The "full stack demo" (real crawl → real search) is realistically Phase 2's DoD.

---

## 6. Phase 2 - LLM Online

**Theme.** Add LLM enrichment (two-pass), embeddings, and a natural-language answer synthesis step. Enough LLM ops to make results meaningful.

**Delivers to the user.** Same `POST /search` now returns not just hits but an LLM-synthesised answer citing the hits. Ingestion produces typed entities and dense vectors. Real end-to-end: crawl a folder → answer questions about it.

**External dependencies added.** vLLM × 2 (LLM on GPU-0, embedding on GPU-1) sized to a 16 GB / 2-card PoC rig.

**Modules in Phase 2:**

| Module | Status | What ships in Phase 2 | Plan |
|--------|--------|-----------------------|------|
| `shared/common` | EXT | Add `LlmMetricsCollector` interface, `TraceIdFilter` for request propagation. | This doc |
| `ingestion-cache` | EXT | `V2__enrichment_and_embeddings.cql` migration → `analysis_cache`, `embedding_content_cache`, `image_caption_cache` (created but unused). Manifest gets `embedding_quality`, `enrichment_model_id` columns. | [ingestion-pipeline-Phase2.md §5](./ingestion-pipeline-Phase2.md) |
| `synanton-llm-client` | NEW | First real implementation. `LlmClient` interface, `OpenAiCompatTranslator`, `HttpLlmClient`, retry + JSON schema validation. | [ingestion-pipeline-Phase2.md §6.1](./ingestion-pipeline-Phase2.md) |
| `synvault` | NO-CHANGE | Continues to serve Phase 1 endpoints. Adding enrichment/embedding surfaces would be tempting but is deferred to Phase 4 alongside the query-path Ph2 changes. | This doc |
| `synflux` | EXT | `EnrichStage` (Pass 1 per-chunk + Pass 2 per-doc) and `EmbedStage` (batch-32) replace Phase 1's no-ops. New counters, new job states. | [ingestion-pipeline-Phase2.md §6.2](./ingestion-pipeline-Phase2.md) |
| `synflux-router` | STUB | Still no router - synflux core runs the pipeline in-process. Callers rely on `synflux.POST /ingest/run`. | This doc |
| `synquest` | NO-CHANGE | Index build now consumes real Phase 2 embeddings instead of test fixtures - but the code path is unchanged. | This doc |
| `relix` | NO-CHANGE | Loader now consumes real Pass 2 output instead of test fixtures - but the code path is unchanged. | This doc |
| `planner` | EXT | Optional LLM-driven intent classification behind a feature flag; fallback to Phase 1 heuristics on timeout. | Phase 2 plan file to be authored |
| `gateway` | EXT | LLM synthesis step - calls `synanton-llm-client` with retrieved hits + graph result → produces a natural-language answer. `answer` field added to `QueryResponse`. | Phase 2 plan file to be authored |
| `synapt` | EXT | JWT/API-key auth (real), `X-Trace-Id` propagation to gateway, error-response redaction for external callers. `security` module dependency starts here. | Phase 2 plan file to be authored |
| `security` | EXT | First real implementation. `POST /auth/login` (htpasswd backend), JWT issuance (RS256), `IdentityProviderPort` local impl. FS permission guard stub. | Phase 2 plan file to be authored |
| `topology` | EXT | First real implementation. PostgreSQL schema (`organizations`, `acl_grants`, `topology_outbox`), Flyway migrations, `TopologyMutationApi` (limited to policy read; no outbox dispatcher yet). Tenant `demo` seeded. | Phase 2 plan file to be authored |
| `control-plane` | STUB | Still no admin API. Operator uses SQL for tenant/policy edits. Documented as an accepted rough edge. | This doc |
| `synreview` | STUB | Still no HITL. Low-confidence entities silently pass through. | This doc |
| `syntology` | EXT | First integration with Pass 2 output - Pass 2 typed-entity strings resolved against the syntology ontology; unknown types flagged. Adds `POST /entities/resolve` endpoint. | Phase 2 plan file to be authored |
| `synanton-mcp` | STUB | Still no MCP surface. | This doc |
| `syntology-admin` (UI) | EXT | Adds a "corpus browser" view listing manifest + Pass 2 entities per doc; still primarily the ontology admin. General Synanton search UI is deferred. | Phase 2 plan file to be authored |

**Phase 2 DoD (composite).** [ingestion-pipeline-Phase2.md §13](./ingestion-pipeline-Phase2.md) + a `POST /search` that returns a `QueryResponse.answer` string ≥ 20 words, non-empty, coherent with the hits.

---

## 7. Phase 3 - Multi-tenant, Auth, Router

**Theme.** Turn the PoC into a small-team-usable product. Real users, real tenants, decoupled ingestion via Kafka, first version of the MCP surface for external tool integration.

**Delivers to the user.** Multiple tenants can be provisioned (via SQL or a partial admin API), users log in with real credentials, ingestion jobs are enqueued (not run inline), and external MCP clients can invoke a curated subset of tools.

**External dependencies added.** Kafka (single-broker), optional Neo4j (as a first-party relix connector).

**Modules in Phase 3:**

| Module | Status | What ships in Phase 3 | Plan |
|--------|--------|-----------------------|------|
| `shared/common` | EXT | Add Kafka client wrapper, structured request context (tenant, user, trace_id) with `MDC` binding. | This doc |
| `ingestion-cache` | EXT | Kafka outbox pattern from Cassandra manifest transitions. Cassandra keyspace gets a `manifest_transitions_outbox` table. | Phase 3 plan file to be authored |
| `synanton-llm-client` | EXT | Add a second provider translator (Bedrock or Anthropic-direct) so the module's provider negotiation (§27c) is exercised. | Phase 3 plan file to be authored |
| `synvault` | NO-CHANGE | Continues to serve Phase 1-2 endpoints. Tier movement remains Phase 5. | This doc |
| `synflux` | EXT | Worker mode - reads `ingestion_events` topic, runs pipeline, publishes `ingestion_completed` events. `/ingest/run` remains as a fallback for manual runs. | Phase 3 plan file to be authored |
| `synflux-router` | NEW | First real implementation. Reads `ingestion_requests` topic (produced by `synapt` or `control-plane`), partitions work, writes to `ingestion_events` for workers. | Phase 3 plan file to be authored |
| `synquest` | NO-CHANGE | Still Java + Lucene. Cuckoo ACL and shard rebalancing are Phase 4. | This doc |
| `relix` | EXT | gRPC transport for the connector SPI (§28) - `InMemoryConnector` moves behind gRPC. `Neo4jConnector` added as second first-party connector. | Phase 3 plan file to be authored |
| `planner` | EXT | Cost estimation node - per-engine cost model, plan comparison, cheapest-plan selection. | Phase 3 plan file to be authored |
| `gateway` | EXT | Reranker port integration (`VllmCrossEncoderRerankAdapter` per §30 SPI). Circuit breakers (Resilience4j) per engine. | Phase 3 plan file to be authored |
| `synapt` | EXT | Rate limiting per tenant (`synapt.rate_limit_per_tenant_qps`), budget enforcement (HTTP 429 + `Retry-After`) reading from `topology.budget_policy`. | Phase 3 plan file to be authored |
| `security` | EXT | Outbound Auth Broker (RFC 8693), `USER_SUBJECT` / `SERVICE_ACCOUNT` / `MTLS` / `API_KEY` profiles. API key lifecycle (§26a) - generation, rotation, revocation. | Phase 3 plan file to be authored |
| `topology` | EXT | `TopologyMutationApi.grant`, `TopologyMutationApi.revoke`, outbox dispatcher fans out to `synquest`, `gateway`, `relix`. Neo4j projection for fast `resolveUserScope`. | Phase 3 plan file to be authored |
| `control-plane` | EXT | First real implementation. Admin API for tenant CRUD + policy edits. `ModelServingDirectory`. No forecast/anomaly/GitOps yet. | Phase 3 plan file to be authored |
| `synreview` | STUB | Still no HITL. Documented as an accepted rough edge. | This doc |
| `syntology` | EXT | Session pinning, ontology versioning per tenant, capability matrix expansion. | Phase 3 plan file to be authored |
| `synanton-mcp` | NEW | First real implementation. Tool surface exposes `search`, `graph_query`, `ontology_resolve`. STREAMABLE_HTTP transport. Auth via API key from `security`. | Phase 3 plan file to be authored |
| `syntology-admin` (UI) | EXT | Auth added (login screen). Multi-tenant awareness. Still ontology-focused; a general Synanton search UI remains Phase 4 or later. | Phase 3 plan file to be authored |

**Phase 3 DoD (composite).** Two-tenant demo: two `X-Tenant`s see disjoint results, one API key per tenant, ingestion happens via Kafka enqueue not inline invocation, and one external MCP client (e.g. Claude Desktop) successfully calls `search` and gets tenant-scoped hits.

---

## 8. Phase 4 - Production Hardening

**Theme.** Enforce the security posture and cross-cutting concerns from design v1.17 + v1.18. Multi-tenant ACL is real, cross-region routing works, JSON is sanitised, CSP is on, rerank is on the hot path.

**Delivers to the user.** Enterprise-grade posture: multi-tenant ACL enforced at every layer, XSS/injection protection at REST and gRPC ingress, browser security headers, rate limits per tenant with predictable degradation, reranked search quality.

**External dependencies added.** Redis (for gateway cross-tenant synthesis cache), Prometheus + Alertmanager, Grafana dashboards.

**Modules in Phase 4:**

| Module | Status | What ships in Phase 4 | Plan |
|--------|--------|-----------------------|------|
| `shared/common` | EXT | Sanitisation library (`SanitizingStringDeserializer`) shared across REST-boundary services. PGV validating interceptor for gRPC services. | Phase 4 plan file to be authored |
| `ingestion-cache` | NO-CHANGE | Schema stable. | This doc |
| `synanton-llm-client` | NO-CHANGE | Interface stable. | This doc |
| `synvault` | EXT | Tenant-scoped `manifest` reads; `residency.allowed_regions` enforcement on adapter selection. | Phase 4 plan file to be authored |
| `synflux` | NO-CHANGE | Pipeline stable. | This doc |
| `synflux-router` | EXT | Per-tenant fair scheduling, priority queues. | Phase 4 plan file to be authored |
| `synquest` | EXT | Cuckoo ACL filter (compile-time from `topology`), incremental index updates driven by `topology_events`, distributed shard layout. Recall monitoring + hot-shard rebalancing. | Phase 4 plan file to be authored |
| `relix` | EXT | Materialized Graph Views (MGV) with periodic refresh, MGV freshness SLO, cost calibration on real connectors. `source_ref_count` CAS. | Phase 4 plan file to be authored |
| `planner` | EXT | Cross-region penalty map, follow-the-sun serving, context budget (§22 v1.1), rerank policy selection. | Phase 4 plan file to be authored |
| `gateway` | EXT | Compile-time ACL injection (three-layer enforcement per §40), cross-tenant synthesis cache (Redis), LLM-context sanitisation, budget-aware execution. | Phase 4 plan file to be authored |
| `synapt` | EXT | Global JSON sanitisation (v1.18 §24.1) - wire the OWASP Jackson deserialiser; DTOs already prepared. CSP headers (v1.18 §49) via `WebFilter`. Deprecation policy machinery (§24 v1.17). | Phase 4 plan file to be authored |
| `security` | EXT | Full IdP integration (Keycloak/OIDC), IdP amortisation cache, `MCP session revalidation`, worker token renewal. | Phase 4 plan file to be authored |
| `topology` | EXT | HIGH_SECURITY 2-phase ACL propagation (`§11 ACL Propagation Flow`), residency policy enforcement, full audit schema. | Phase 4 plan file to be authored |
| `control-plane` | EXT | GitOps reconciler, forecast engine, anomaly detector, ACL propagation reconciliation. | Phase 4 plan file to be authored |
| `synreview` | STUB | Still no HITL - a design deferral. Consumers accepting low-confidence entities need explicit `warnings` in `execution_trace`; that annotation ships now, not the review UI. | This doc |
| `syntology` | EXT | SHACL validation, ontology lint (§27 v1.1), ontology versioning across tenants. | Phase 4 plan file to be authored |
| `synanton-mcp` | EXT | Full tool surface per §27b, MCP session revalidation. | Phase 4 plan file to be authored |
| `syntology-admin` (UI) | EXT | Full CSP compliance (Trusted Types, no inline styles), `<SafeHtml />` wrapper for any rich-text field, first-party synanton chat UI kicks off here (§46a). | Phase 4 plan file to be authored |

**Phase 4 DoD (composite).** Every alert in [synanton-design-1.18.md §45 Alerts table](../architecture/platform/synanton-design-1.18.md) has a wired rule; CSP is `enforce` mode (not `report_only`); a fuzz test against `POST /search` finds no XSS bypass; ACL grants propagate < 300 ms p99.

---

## 9. Phase 5 - Scale + Vision + DR

**Theme.** Everything that matters at operational scale but was deferred earlier. Tier movement, vision captioning, Rust hot loop, GDPR erasure, DR runbooks.

**Delivers to the user.** Multimodal ingest (documents with images), long-term storage (HOT → WARM → COLD → Glacier), regulatory-grade erasure, disaster recovery within SLOs.

**External dependencies added.** Second AWS region (or equivalent), Glacier, second vLLM instance for vision (LLaVA or Qwen2-VL).

**Modules in Phase 5:**

| Module | Status | What ships in Phase 5 | Plan |
|--------|--------|-----------------------|------|
| `shared/common` | NO-CHANGE | Interface stable. | This doc |
| `ingestion-cache` | EXT | `image_caption_cache` populated. Vacuum staggering (§18 v1.17) turned on. TTLs on caches. | Phase 5 plan file to be authored |
| `synanton-llm-client` | NO-CHANGE | Interface stable - vision models use the same OpenAI-compat surface (`content_type: image`). | This doc |
| `synvault` | EXT | Tier Manager: HOT → WARM → COLD → Glacier movement per `tiering_policy`. Glacier retrieval flow. | Phase 5 plan file to be authored |
| `synflux` | EXT | Vision captioning stage (LLaVA or Qwen2-VL). Two-step chain-of-thought enrichment optionally enabled. | Phase 5 plan file to be authored |
| `synflux-router` | NO-CHANGE | Router stable. | This doc |
| `synquest` | EXT | Rust migration of the hot loop (JNI or gRPC-native boundary). Multilingual tokenisation stack (bge-m3). Supernode sampling. | Phase 5 plan file to be authored |
| `relix` | EXT | Louvain community detection, `community_id` property, cross-connector federated queries. Bounded emulated traversal. | Phase 5 plan file to be authored |
| `planner` | NO-CHANGE | Plan model stable. | This doc |
| `gateway` | EXT | Cold-tier rehydration for synthesis (§23 v1.17), `X-Synanton-Cold-Rehydration` response header. GPU degraded mode branching. | Phase 5 plan file to be authored |
| `synapt` | NO-CHANGE | Interface stable. | This doc |
| `security` | EXT | Prompt/model version tracking with `synreview` (§27a v1.17). Cross-region key management. | Phase 5 plan file to be authored |
| `topology` | NO-CHANGE | Schema stable. | This doc |
| `control-plane` | EXT | DR runbooks (§47a) automated. `BackupVerificationWorkflow`. GDPR erasure cascade (§10). `RecrawlAfterRestorationWorkflow`. | Phase 5 plan file to be authored |
| `synreview` | NEW | First real implementation. HITL for low-confidence Pass-2 entities, prompt/model versioning, 24-hour staging queue (§27a v1.17), replay support. | Phase 5 plan file to be authored |
| `syntology` | NO-CHANGE | Ontology surface stable. | This doc |
| `synanton-mcp` | NO-CHANGE | MCP surface stable. | This doc |
| `syntology-admin` (UI) | NO-CHANGE | UI stable. Chat UI iterates independently after Phase 4. | This doc |

**Phase 5 DoD (composite).** Regional failover completes within SLO (§47a RTO/RPO table); Cold-tier retrieval within `< 500 ms p95` after warm-up; GDPR erasure cascade end-to-end p99 ≤ 45 s.

---

## 10. Beyond Phase 5

Phase 5 is where the architecture in [synanton-design-1.18.md](../architecture/platform/synanton-design-1.18.md) is fully realised. Anything beyond falls into three buckets:

- **v1.19+ proposals.** New capabilities not in the current design (e.g. streaming graphs, agent frameworks, structured LLM tool-use). Each requires its own proposal document → design integration → phase plan.
- **Operational maturity.** Ongoing observability polish, cost tuning, capacity planning refresh. Not phased.
- **Deprecation removals.** Per §24 v1.17, deprecated field removals happen when a field's usage counter is `0` for ≥ 30 days. Not phased; opportunistic.

---

## 11. How To Use This Doc

- **Kicking off work on a module in a phase.**
  1. Find the row for that module in Section 4.
  2. Check the status column for the target phase.
  3. If linked, open the dedicated plan file (`NN-{module}-PhaseM.md`) and follow it.
  4. If the cell is `STUB` or `NO-CHANGE`, this doc is the plan - read the phase-N section for that module carefully.
- **Reviewing whether the platform is on track for Phase N.**
  1. Read Section 3 for the theme.
  2. Read the "Delivers to the user" line for Phase N.
  3. Cross-check the module status list against actual repo state.
  4. Verify the Phase N composite DoD at the end of the phase section.
- **Adding a new module.**
  1. Add it to Section 2 with kind, design-doc §, and one-line role.
  2. Add a row to the Section 4 matrix.
  3. Add its per-cell status + plan to every phase section (5 through 9).
  4. Author a Phase-N plan file when its status becomes `NEW` or `EXT`.
- **Adding a new phase.**
  1. Add a row to Section 3 with theme + user-facing capability.
  2. Add a column to the Section 4 matrix.
  3. Add a new section for the phase, following the shape of Sections 5-9.
  4. For each module, decide its status in the new phase.

---

## 12. Cross-Phase Risks

| Risk | Mitigation | Phase(s) exposed |
|------|------------|------------------|
| A module's real implementation ships in Phase N but its consumers still depend on the Phase N-1 stub behaviour. | Every `NEW` cell requires a migration note in the module's plan file (what stub it replaces, what consumers must change). | Every phase transition |
| The `STUB` label lulls consumers into treating a fake as real. | Stubs return `501 Not Implemented` or throw `UnsupportedOperationException` on any method not documented as available. `MockTenantFilter` is documented as fake-tenant-only. | Phases 1-3 |
| Phase order in this doc drifts from actual delivery order. | The Section 3 theme line is the anchor; if reality diverges, either move work between phases (edit this doc) or rename phases. Do not let the doc silently rot. | Any |
| Design-doc changes (v1.19+) invalidate a phase plan. | Every plan file's "Context and Document Alignment" table names the design-doc revision it aligns with. Design revs trigger a plan review. | Any |
| The syntology `STANDALONE` track never merges into the main phase track. | Phase 2 integrates syntology with the ingestion pipeline (Pass-2 entity resolution). If that slips, syntology becomes a permanent parallel track and the two grow apart. Flagged as a decision point at the start of Phase 2. | Phase 2 |
| `synreview` sits at `STUB` through Phase 4. Low-confidence entities silently leak into user-facing results. | Phase 4 gateway adds explicit `warnings` in `execution_trace` for low-confidence graph promotions; UI in Phase 4 renders them. `synreview` NEW in Phase 5 replaces the warnings with review-and-hold. | Phases 1-4 |
| Rust migration of synquest (Phase 5) is a big-bang rewrite. | The Java-side interface (`POST /search`, request/response shapes) is stable from Phase 1. Rust ships as a drop-in replacement; a feature flag switches per-tenant. Phase 5 plan authors the flag scheme. | Phase 5 |

---

## 13. Existing Plan File Inventory

Table shows all plan files and which phase-cells they satisfy. Updated 2026-07-24.

### Phase 1

| Plan file | Covers modules |
|-----------|----------------|
| [phase1/01-ingestion-pipeline.md](./phase1/01-ingestion-pipeline.md) | synvault, synflux, ingestion-cache |
| [phase1/01-synquest.md](./phase1/01-synquest.md) | synquest |
| [phase1/02-relix.md](./phase1/02-relix.md) | relix |
| [phase1/03-planner.md](./phase1/03-planner.md) | planner |
| [phase1/04-gateway.md](./phase1/04-gateway.md) | gateway |
| [phase1/05-synapt.md](./phase1/05-synapt.md) | synapt |
| [demo/standalone-syntology-demo.md](./demo/standalone-syntology-demo.md) | syntology, syntology-admin (STANDALONE track) |

### Phase 2

| Plan file | Covers modules |
|-----------|----------------|
| [phase2/01-ingestion-pipeline.md](./phase2/01-ingestion-pipeline.md) | synflux, ingestion-cache, synanton-llm-client |
| [phase2/02-planner.md](./phase2/02-planner.md) | planner |
| [phase2/03-gateway.md](./phase2/03-gateway.md) | gateway |
| [phase2/04-synapt.md](./phase2/04-synapt.md) | synapt |
| [phase2/05-security.md](./phase2/05-security.md) | security (first real implementation) |
| [phase2/06-topology.md](./phase2/06-topology.md) | topology (first real implementation) |
| [phase2/07-syntology.md](./phase2/07-syntology.md) | syntology (Pass-2 entity type resolution) |
| [phase2/08-syntology-admin.md](./phase2/08-syntology-admin.md) | syntology-admin UI (login + corpus browser) |

### Module-level plans (cross-phase)

| Plan file | Covers modules |
|-----------|----------------|
| [modules/helper.md](./modules/helper.md) | helper (v1.19 - phases 1-5) |
| [modules/wizard.md](./modules/wizard.md) | wizard (v1.19 - phases 1-5) |

### Phase 3

| Plan file | Covers modules |
|-----------|----------------|
| [phase3/01-ingestion-pipeline.md](./phase3/01-ingestion-pipeline.md) | shared/common (Kafka), ingestion-cache, synflux (worker mode), synflux-router (NEW), synanton-llm-client (second provider) |
| [phase3/02-relix.md](./phase3/02-relix.md) | relix (gRPC connector SPI, Neo4j connector) |
| [phase3/03-planner.md](./phase3/03-planner.md) | planner (cost estimation, plan comparison) |
| [phase3/04-gateway.md](./phase3/04-gateway.md) | gateway (reranker port, circuit breakers) |
| [phase3/05-synapt.md](./phase3/05-synapt.md) | synapt (rate limiting, budget enforcement, Kafka enqueue) |
| [phase3/06-security.md](./phase3/06-security.md) | security (RFC 8693 outbound broker, API key lifecycle) |
| [phase3/07-topology.md](./phase3/07-topology.md) | topology (grant/revoke API, outbox dispatcher, Neo4j projection) |
| [phase3/08-control-plane.md](./phase3/08-control-plane.md) | control-plane (first real implementation - admin API, ModelServingDirectory) |
| [phase3/09-syntology.md](./phase3/09-syntology.md) | syntology (session pinning, per-tenant versioning) |
| [phase3/10-synanton-mcp.md](./phase3/10-synanton-mcp.md) | synanton-mcp (NEW - STREAMABLE_HTTP, three tools, API key auth) |
| [phase3/11-syntology-admin.md](./phase3/11-syntology-admin.md) | syntology-admin UI (tenant switcher, admin panel, MCP config panel) |

### Plan files not yet authored

- Phase 4: all `NEW`/`EXT` cells across the matrix
- Phase 5: all `NEW`/`EXT` cells across the matrix

Plan files follow the naming convention `phase{N}/NN-{module}.md`. New plan files should reference this master doc's phase section as their "Context" anchor.

---

## 14. Change Log for This Doc

| Version | Date | Change |
|---------|------|--------|
| 1.0 | 2026-07-19 | Initial master phases plan. Phase 1 and ingestion Phase 2 plans referenced. Phases 3-5 directional only. |
| 1.1 | 2026-07-21 | Completed Phase 2 plan file inventory: added plans for planner, gateway, synapt, security, topology, syntology, syntology-admin. Updated file references to new flat naming convention (`phase{N}/NN-{module}.md`). Added module-level plans for helper and wizard (v1.19). |
| 1.2 | 2026-07-24 | Completed Phase 3 plan file inventory: 11 plan files covering all EXT/NEW modules. Kafka topics, gRPC SPI, RFC 8693 auth broker, API key lifecycle, Neo4j connector, synanton-mcp first real implementation. |
