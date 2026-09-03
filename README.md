# Synanton

**Synanton** is an open-source, AI-native enterprise knowledge platform. It unifies full-text search, dense vector search, knowledge-graph reasoning and ontology management into a single modular system - ingesting enterprise content from heterogeneous sources, enriching it with LLMs and exposing it through REST, gRPC, MCP and agent-to-agent interfaces.

---

## What it does

```
         Documents / APIs / Databases / S3
                          │
                          ▼
┌───────────────────────────────────────────────────────────┐
│  Synvault (content store + tier manager)                  │
│  Synflux  (acquire/extract/chunk/enrich/embed/persist)    │
│  ingestion-cache (Cassandra artifact cache)               │
└───────────────────────────────────────────────────────────┘
                          │
                          ▼
┌───────────────────────────────────────────────────────────┐
│  Synquest  BM25 + HNSW hybrid search kernel               │
│  Relix     GraphRAG engine (entity/relation graph)        │
│  Syntology ontology management (SHACL + versioning)       │
└───────────────────────────────────────────────────────────┘
                          │
                          ▼
┌───────────────────────────────────────────────────────────┐
│  Planner  intent classification + query plan generation   │
│  Gateway  plan execution + LLM synthesis + reranking      │
│  Synapt   public REST/gRPC ingress + auth + rate limits   │
└───────────────────────────────────────────────────────────┘
                          │
              REST / gRPC / MCP / ACP
```

A single query (`POST /search`) traverses the full stack: content is retrieved from the hybrid index, graph context is woven in by Relix and the Gateway synthesises a natural-language answer citing the source chunks - all within per-tenant ACL boundaries.

---

## Purpose

Enterprise knowledge sits scattered across document stores, wikis, databases and file shares. Extracting signal from it requires stitching together a search engine, an embedding pipeline, a graph database and an LLM - each maintained separately and integrated ad-hoc.

Synanton provides a single coherent platform for this problem:

- **Ingest once.** A pluggable content adapter SPI handles S3, local filesystems, SharePoint, FileNET, OpenText, RDBMS, Kafka CDC feeds and webhooks. Raw content moves through a staged pipeline that extracts structure, semantically chunks, enriches (two-pass LLM chain-of-thought) and embeds every document.
- **Query flexibly.** Hybrid BM25 + HNSW search, GraphRAG traversal, ontology-guided entity resolution and cross-encoder reranking are combined by a planner into the optimal query plan for each question.
- **Answer accurately.** The Gateway synthesises a natural-language answer grounded in retrieved chunks, with citations, confidence scores and execution traces attached.
- **Stay governed.** Per-tenant isolation, POSIX-backed ACL enforcement, GDPR erasure cascade, SHACL ontology validation and per-tenant cost attribution are first-class concerns - not afterthoughts.

---

## Who it is for

| Use target | What Synanton provides |
|---|---|
| **Enterprise search teams** | A drop-in search backend that combines keyword and semantic retrieval without maintaining three separate systems. |
| **RAG application developers** | A managed ingestion pipeline (parse, chunk, enrich, embed) with a write-through artifact cache, plus a ready-made query-and-synthesis API. |
| **Knowledge management platforms** | Ontology management (Syntology), versioned schemas, SHACL validation and a graph-based entity/relation store (Relix). |
| **AI agent builders** | An MCP-compatible tool surface (Synanton-MCP) and an agent-to-agent API (ACP), so Synanton becomes a callable knowledge tool for Claude, GPT-based agents and custom agent frameworks. |
| **Security-conscious enterprises** | POSIX-backed file permissions, JWT-gated REST APIs, compile-time ACL injection, multi-tenant isolation tiers and a full audit trail. |
| **Platform engineers** | A monorepo of independent Spring Boot services with hexagonal architecture, contract-first SPIs, Docker Compose and Kubernetes deployment profiles and a GitOps-driven control plane. |

---

## Module map

| Module | Role | Status |
|---|---|---|
| `shared/common` | JWT verifier, error model, `TenantContext`, `MockTenantFilter` | ✅ Done |
| `ingestion-cache` | Cassandra DAO library - manifest (incl. `ingest_usage`), chunks, analysis, embeddings | ✅ Done |
| `synanton-llm-client` | Provider-agnostic LLM + embedding client (OpenAI-compat) | ✅ Done |
| `synvault` | Content store + adapter registry + MinIO/S3 facade | ✅ Done |
| `synflux` | Ingestion pipeline - acquire → extract → semantic chunk → enrich → embed → persist; `UsageAccumulator` rolls ingest cost to search hits | ✅ Done (Phase 1+2; extraction client + semantic chunking v1.22) |
| Lucentrix (sibling CLI) | Pluggable crawl CLI (web/dummy) that pushes raw bytes into synvault | ✅ Sibling repo |
| `security` | AuthN/Z, JWT issuance, htpasswd IdP, `FsPermissionGuard` | ✅ Done |
| `topology` | Org/ACL/policy store, `FilesystemAclSeeder`, JDBC repos | ✅ Done |
| `syntology` | Ontology service - REST API, Jena storage, HCL→JSON IR→SHACL, versioning | ✅ Done (standalone) |
| `syntology-admin` | React SPA - graph editor, login, grants view | ✅ Done (standalone) |
| `synquest` | Hybrid search kernel - BM25 + HNSW + RRF; hits expose citation + `ingest_usage`; `query_usage` on response | ✅ Done (Phase 1) |
| `relix` | GraphRAG engine - `GraphConnector` adapters (JGraphT / Neo4j / Nebula), MCP tools | ✅ Done (Phase 1; Neo4j/Nebula selectable) |
| `planner` | Intent classifier + query plan generator | ✅ Done (Phase 1) |
| `gateway` | Plan DAG executor + LLM synthesis + reranker + GPU execution client | ✅ Done (Phase 1+2; GPU client v1.20) |
| `synapt` | Public REST/gRPC ingress, rate limiting, sanitisation | ✅ Done (Phase 1+2) |
| `synflux-router` | Kafka-driven work distribution across synflux workers | ✅ Done (Phase 3) |
| `control-plane` | Admin API, forecast engine, anomaly detection, GitOps | ✅ Done (Phase 3 - admin API + ModelServingDirectory) |
| `synanton-mcp` | MCP protocol bridge - exposes platform tools to MCP clients | ✅ Done (Phase 3) |
| `synreview` | Human-in-the-loop review queue for low-confidence entities | 🔲 Phase 5 |
| `annotations` | Annotation registry - definitions, versions, dependency DAG, provenance, processing runs, Resolutor, Equalix | 🔶 AAP-1 + AAP-2 done (v1.24/1.25); analytics phases pending |
| `analytics` | Analytics Plane - events, analytical facts, ClickHouse adapter, Analytics Registry, metrics/reports | 🔲 Not started (v1.24/1.25) |

**GPU Execution Plane** (modules `java/gpu-contract` + `java/gpu-gateway` in this repo; extracted to `synanton/gpu-execution-plane` for independent deployment):

> Production GPU workloads (model serving, embedding, reranking) run in a physically isolated GPU cluster connected to this platform via the `synanton.gpu.v1` gRPC contract.

| Component | Role | Status |
|-----------|------|--------|
| `java/gpu-contract` | `synanton.gpu.v1` protobuf - `Execute`, `Cancel`, `GetStatus`, `GetCapacity`; `org.synanton.gpu.v1`; `ErrorReason` catalogue; **byte-identical** with `gpu-runtime` | ✅ GPU-1 + mirror |
| Contract mirror check | `scripts/verify-gpu-contract-mirror.sh` + `verifyGpuContractMirror`, wired into `check` and CI | ✅ Done |
| `java/gpu-gateway` | GPU Gateway service - mTLS boundary, field validation, tenant assertion, idempotency store (PostgreSQL, fail-closed), `DirectDispatcher` → vLLM, execution lifecycle, Micrometer metrics | ✅ GPU-2 done |
| `gateway` GPU client | `GpuExecutionClient` + `GpuSynthesisAdapter` - primary platform gRPC client + synthesis adapter with `MODEL_NOT_READY` retry, CPU degraded fallback, trace context propagation; `ModelServingDirectory` `isGpuBacked()` / `getGpuModels()` | ✅ GPU-3 done |
| Consumer contract tests | `GpuContractTest` - in-process gRPC tests verifying all 4 RPCs, error shapes and `MODEL_NOT_READY` retryable flag | ✅ GPU-1 done |
| `EqualixScheduler` | Optional fairness/quota scheduler (data-gated on GPU-4 evidence) | 🔲 GPU Phase 5 |

**Structured Content Extraction Plane** (module `java/extraction-contract` in this repo; implementation in `synanton/content_extractor`):

> Content extraction (PDF/text/EPUB/HTML now; audio, image and video later) runs behind the `synanton.extraction.v1` gRPC contract. Deployment topology - embedded, co-located, or an independently scaled cluster - is a scaling concern that does not change the contract.

| Component | Role | Status |
|-----------|------|--------|
| `java/extraction-contract` | `synanton.extraction.v1` protobuf contract - 9 RPCs (submit, batch, sync, status poll, cursor completion poll, result, cancel, capacity, estimate, capabilities); 13-code error catalogue; explicit feature-state model; generated gRPC stubs | ✅ SCEP-1 done |
| Contract mirror check | `scripts/verify-contract-mirror.sh` + `verifyContractMirror` Gradle task, wired into `check` and CI - fails when the protos diverge from `content_extractor` | ✅ SCEP-1 done |
| Consumer contract tests | 43 tests: in-process gRPC round trip, idempotency replay, cursor polling, validation rules and descriptor-level black-box enforcement (`ContractOpacityTest`) | ✅ SCEP-1 done |
| `content_extractor` gateway | Sync + async extraction, Flyway operation store, worker leases, Prometheus metrics | ✅ SCEP-2/4 done |
| `java/extraction-client` | Dedicated client - sync/async extract, reconcile-after-timeout, `ExtractionFallbackPolicy`, local Tika fallback, metrics | ✅ SCEP-5 done |
| `synflux` extraction wiring | `ExtractionStage` via `ExtractionPlaneClient`; `SemanticChunkStage`; provenance + `ingest_usage` on persist/index | ✅ Done |

---

## Roadmap

The platform is being built in five phases, with a GPU execution plane track (v1.20), structured content extraction (v1.21) and semantic chunking (v1.22). **Current design pointer:** `docs/architecture/synanton-design-1.22.md` (`docs/VERSION` = 1.22). Each phase ships a runnable demo and is fully additive - Phase N never breaks Phase N-1.

### Phase 1 - Foundation *(complete)*

**Delivers:** crawl a local folder → hybrid + graph search → return ranked hits (no LLM yet).

- [x] Monorepo scaffolding, shared library, ingestion-cache Cassandra schema
- [x] `synvault` - `FilesystemAdapter`, MinIO object store, manifest REST API
- [x] `synflux` - acquire → parse → chunk → persist pipeline, CLI + REST trigger
- [x] `synquest` - Lucene BM25 + HNSW hybrid search, boot-time index build
- [x] `relix` - `POST /graph/query`, three query shapes, `relix.graph.connector` (`memory` default, `neo4j`, `nebula`)
- [x] `planner` - heuristic classifier (T1–T4), 4 plan templates, entity trie, `POST /plan`
- [x] `gateway` - plan DAG executor, parallel step dispatch, RRF fusion, `POST /query`
- [x] `synapt` - thin REST ingress, `MockTenantFilter`, Jakarta Validation, `POST /search`

**DoD:** `curl -X POST :8080/search -d '{"query":"who supplies Acme?"}'` returns ranked hits.

### Phase 2 - LLM online *(complete)*

**Delivers:** same query now returns an LLM-synthesised answer citing the hits.

- [x] `synanton-llm-client` - OpenAI-compat translator, retry logic, JSON schema validation
- [x] `synflux` - `EnrichStage` (Pass 1 per-chunk + Pass 2 per-doc) + `EmbedStage` (batch 32)
- [x] `ingestion-cache` - `analysis_cache`, `embedding_content_cache` schema + DAO extensions
- [x] `planner` - optional LLM-driven intent classification (feature-flagged)
- [x] `gateway` - LLM synthesis step, `answer` field in `QueryResponse` (enabled via `phase2` profile)
- [x] `synapt` - JWT/API-key auth seams, `X-Trace-Id` propagation, mock-tenant filter
- [x] `security` - first real implementation: JWT issuance, htpasswd backend, `FsPermissionGuard`
- [x] `topology` - first real implementation: PostgreSQL schema, `FilesystemAclSeeder`, `TopologyMutationApi`

**DoD:** `POST /search` returns `QueryResponse.answer` ≥ 20 words, coherent with hits.

### Phase 3 - Multi-tenant, auth, router *(complete)*

**Delivers:** multiple tenants, real users, Kafka-decoupled ingestion, MCP surface.

- [x] Kafka-driven `synflux-router` - replaces inline crawl with topic-based dispatch
- [x] `security` - RFC 8693 outbound auth broker, API key lifecycle (`ApiKeyController`, `TokenExchangeController`)
- [x] `topology` - grant/revoke API, outbox dispatcher (`TopologyOutboxDispatcher`, `JdbcGrantRepository`)
- [x] `control-plane` - first real implementation: admin API, `ModelServingDirectory`
- [x] `synanton-mcp` - MCP STREAMABLE_HTTP: `search`, `graph_query`, `ontology_resolve`

**DoD:** two-tenant demo, one API key per tenant, external MCP client (Claude Desktop) returns tenant-scoped hits.

### Phase 4 - Production hardening 

**Delivers:** enterprise security posture: ACL enforced everywhere, XSS protection, reranking, rate limits.

- [x] Compile-time ACL injection (three-layer enforcement per §40)
- [x] Global JSON sanitisation (OWASP Jackson deserialiser)
- [x] CSP headers + `X-Frame-Options: DENY`
- [x] Cross-encoder reranker (BGE reranker base, `VllmCrossEncoderRerankAdapter`)
- [x] `synquest` - Cuckoo ACL filter, incremental index updates, hot-shard rebalancing
- [x] Prometheus + Alertmanager + Grafana dashboards

### Phase 5 - Scale + vision + DR *(planned)*

**Delivers:** multimodal ingest, long-term storage tiering, Rust hot loop, GDPR erasure, disaster recovery.

- Vision captioning stage (LLaVA / Qwen2-VL)
- HOT → WARM → COLD → Glacier tier movement in `synvault`
- `synquest` Rust hot loop migration (JNI boundary, drop-in replacement)
- GDPR erasure cascade - tombstone + `source_ref_count` CAS
- `synreview` - human-in-the-loop review queue, 24-hour staging, replay
- Cross-region DR within RTO/RPO SLOs

### GPU Execution Plane track - v1.20 *(in progress, separate repository)*

**Delivers:** physically isolated GPU cluster with a versioned gRPC contract between the primary platform and GPU infrastructure.

> **Note:** The Phase 2 Quick Start below runs vLLM locally for development and demo purposes. That is **not** the production GPU Execution Plane. Production GPU workloads use the `synanton/gpu-execution-plane` repository and connect via `synanton.gpu.v1` over mTLS.

- **GPU-1** ✅ - `synanton.gpu.v1` (`java/gpu-contract`), mirrored in `gpu-runtime`, `ErrorReason` catalogue, `GpuContractTest`, `GpuExecutionClient`
- **GPU-2** ✅ - in-repo `java/gpu-gateway` (`GpuExecutionServiceImpl`, `DirectDispatcher`, PostgreSQL idempotency). Production binary is `gpu-runtime`, which implements the same proto
- **GPU-3** ✅ - `GpuSynthesisAdapter` (retry + CPU degraded fallback), `ModelServingDirectory` GPU flags, `gateway.gpu.*`
- **GPU-4** 🔶 - gpu-runtime can serve platform clients (contract unified). Ingest embeddings still use HTTP `HttpLlmClient`. Security/hardening/dashboards still open
- **GPU-5** 🔲 *(conditional)* - `EqualixScheduler` - only after GPU-4 operational evidence

See `docs/implementation/gpu-execution-plane/INDEX.md` for the detailed implementation plan.

### Structured Content Extraction Plane track - v1.21 *(in progress, separate repository)*

**Delivers:** structured content extraction behind a deployment-neutral contract. Consumers receive reading order, headings, tables and page provenance instead of a flat string and never reparse the source to obtain text.

> **Architectural decision:** the extraction plane is a black box. The platform specifies *what* to extract and under *what constraints*; the plane decides *how*. Which parser runs, whether OCR is local or remote and whether work is CPU-, GPU-, or accelerator-backed are invisible through the contract - so the implementation can grow from an embedded processor into a distributed cluster without a platform API change.

- **SCEP-1** ✅ - `synanton.extraction.v1` contract (`java/extraction-contract`), byte-identical mirror in `synanton/content_extractor` with a drift check wired into `check` and CI, 13-code error catalogue with retryability verdicts, explicit feature-state model, 43 consumer contract tests
- **SCEP-2** ✅ - Sync path DoD: pre-download reject, adapter timeout, ArchUnit domain isolation, Prometheus metrics, honest text feature states
- **SCEP-3** 🔶 - PDF via OpenDataLoader HTTP sidecar; feature states derived from produced output (OCR/scene_analysis honest reporting)
- **SCEP-4** ✅ - Async operations (Flyway + PostgreSQL idempotency store, worker with leases, full gRPC surface)
- **SCEP-5** ✅ - `java/extraction-client` + synflux `ExtractionStage` with fallback policies
- **SCEP-6** 🔲 - Topology equivalence proof + security hardening
- **SCEP-7** 🔲 *(post-v1.21)* - Audio, image and video extraction

See `docs/implementation/content-extraction-plane/INDEX.md` for the detailed implementation plan and `docs/implementation/content-extraction-plane/error-catalogue.md` for the error contract.

### Semantic Content Structuring / Chunking track - v1.22 *(in progress)*

**Delivers:** structure-aware chunks with heading hierarchy, atomic tables and full provenance for retrieval and citation-operating on normalized extraction output, not `flattenedText`.

> **Architectural decision:** chunking is a separate layer from extraction. The extraction plane produces structured `elements`; `SemanticChunkStage` in `synflux` builds a section tree and emits semantically bounded chunks with `sectionPath`, `sourceElements` and page coordinates.

- **SC-1** ✅ - Structure builder (`DocumentStructureBuilder`: elements → section tree)
- **SC-2** ✅ - Semantic chunker (section boundaries, list/figure atomicity, `sectionPath` embed prefix)
- **SC-3** 🔶 - Table and figure chunk types (atomic tables; figure+caption; structured table rows still thin)
- **SC-4** ✅ - Persist + index citation fields (`section_path`, `source_elements`, `token_count`, `structured_content`, `is_partial_section`) and `ingest_usage` on search hits
- **SC-5** 🔲 *(post-v1.22)* - Summarization hierarchy from chunk tree
- **SC-6** 🔲 *(post-v1.22)* - Multimodal chunking (audio, image, video)

See `docs/implementation/semantic-chunking/INDEX.md` for the detailed implementation plan and `docs/architecture/synanton-design-1.22.md` for Part X.

### Annotation, Derived Knowledge, Recalculation, Analytics & Reporting Plane track - v1.24/1.25 *(in progress)*

**Delivers:** first-class, versioned annotations with explicit dependencies and provenance; dependency-aware recalculation (**Resolutor** determines impact, **Equalix** executes it under priority/resource controls); and a downstream **Analytics and Reporting Plane** (events, facts, aggregates, metrics, reports).

> **Architectural decision:** analytics is strictly downstream of the Design 1.23 classification/masking boundary - analytics events are emitted only after that decision, never before it. Analytics observes canonical knowledge and platform activity; it never becomes authoritative knowledge or a security side channel.

- **AAP-1** 🔶 - Annotation foundation: new `annotations` service (definitions, versions, dependency DAG, processing runs), new Cassandra `annotations` table, `synflux` `AnnotationStage` (flag-gated, `synflux.pipeline.annotation-enabled: false` by default)
- **AAP-2** 🔶 - Recalculation: Resolutor (impact analysis) + Equalix (priority-scheduled controlled execution) in `annotations`, `POST /recalculate`; only definition-publish events are wired end-to-end so far
- **AAP-3** 🔲 - Knowledge projections: annotation/definition-version provenance in `synquest`, embedding cache, `relix`
- **AAP-4** 🔲 - Analytics PoC: `analytics_events` Kafka topic, new `analytics` service, ClickHouse adapter, initial facts/aggregates/metrics
- **AAP-5** 🔲 - Analytics security: classification propagation, tenant/system scope isolation, aggregate protection, `test:analytics-security` CI tier
- **AAP-6** 🔲 - Reporting: Analytics Registry, metric/report lifecycle, first end-to-end report (`daily-platform-processing`)
- **AAP-7** 🔲 - Production hardening: ClickHouse PoC evaluation, retention, backup/restore, alerting, load testing
- **AAP-8** 🔲 - MCP / external integration: `synanton-mcp` analytics tools, public Analytics API via `synapt`

See `docs/implementation/annotations-analytics-plane/INDEX.md` for the detailed implementation plan and `docs/architecture/synanton-design-1.25.md` for the full design.

---

## Quick start

### Ingest → extract → index (PoC)

The ingest → extract → index path is wired end to end. **Full Docker image builds were not verified in every environment** - run the script locally to confirm.

**What it does**

- Starts Cassandra, MinIO, `extraction-gateway`, synvault, synflux and synquest.
- Ingests `demo-data/documents` (markdown/text plus a sample PDF and a heading-structured markdown file).
- Reindexes synquest and runs a search whose hits can include `source_uri`, `section_path`, `source_elements` and `ingest_usage`; the response may include `query_usage`.

**Extraction plane (`content_extractor`).** Serves sync and async extraction over `synanton.extraction.v1`, reads objects from MinIO, routes by media type and enforces size/time/payload limits. Plain text and markdown use the Tika adapter with honest feature states. PDF uses the OpenDataLoader HTTP sidecar when `EXTRACTION_OPENDATALOADER_BASE_URL` is set; otherwise the PDF adapter reports unsupported and synflux applies the configured fallback policy (`FALLBACK_LOCAL_TIKA` by default).

**Platform client.** `java/extraction-client` wraps the gRPC contract with `ExtractionFallbackPolicy`, reconcile-after-timeout on async submit and Micrometer metrics. Configure via `synanton.extraction.client.*` in synflux `application.yml`.

**Chunking and search.** Synflux skips redundant Tika when structured extraction succeeds. `SemanticChunkStage` chunks from `elements` (not flat text). Chunks persist `page_start`, `page_end`, `section_path`, `chunk_type`, `heading`, `source_elements`, `token_count` and table `structured_content`. Manifests store a document-level `ingest_usage` JSON rollup (wall time, CPU time, model chars/tokens per stage - a benchmark ledger, not billing). Synquest indexes those fields with BM25; HNSW is optional. Search does not fail if query embedding is down; hits carry citation and usage metadata.

```bash
# From this repository (Docker + Java 21)
./scripts/run-extract-index-poc.sh
```

Optional PDF OCR/structure sidecar:

```bash
export EXTRACTION_OPENDATALOADER_BASE_URL=http://opendataloader:8080
./scripts/run-extract-index-poc.sh
```

GPU runtime is **not** on this path. Ingest embeddings still use `HttpLlmClient` when a GPU is present. Production GPU inference uses `synanton.gpu.v1` (mirrored with `gpu-runtime`); see GPU track below.

### Content extractor standalone (separate cluster)

`extraction-gateway` lives in the sibling `content_extractor` repo and is built automatically as part of the demos above. Deployment topology - embedded, co-located, or an independently scaled cluster - is a scaling concern that doesn't change the `synanton.extraction.v1` contract, so it can also be built and run on its own, against its own Postgres/MinIO and network:

```bash
# From the content_extractor repo root (sibling of this repo)
cd ../content_extractor
docker build -f deployment/docker/extraction-gateway.Dockerfile -t synanton/extraction-gateway .

docker run --rm -p 8092:8092 -p 9091:9091 \
  -e EXTRACTION_DB_URL=jdbc:postgresql://<postgres-host>:5432/<db> \
  -e EXTRACTION_DB_USER=<user> \
  -e EXTRACTION_DB_PASSWORD=<password> \
  -e EXTRACTION_OBJECTSTORE_ENDPOINT=http://<minio-host>:9000 \
  -e EXTRACTION_OBJECTSTORE_ACCESS_KEY=<key> \
  -e EXTRACTION_OBJECTSTORE_SECRET_KEY=<secret> \
  synanton/extraction-gateway
```

Point synflux at it with `EXTRACTION_ENDPOINT=<host>:9091`.

### Graph engines (Relix)

Relix query shapes (`entity_lookup`, `one_hop`, `k_hop_path`) go through a `GraphConnector` port. Switch backends without changing executors:

| `relix.graph.connector` / `RELIX_GRAPH_CONNECTOR` | Adapter | Notes |
|---|---|---|
| `memory` (default) | `InMemoryGraphConnector` | JGraphT, hydrated from Pass-2 Cassandra rows |
| `neo4j` | `Neo4jGraphConnector` | Bolt/Cypher; requires `NEO4J_URI` |
| `nebula` | `NebulaGraphConnector` | nGQL; requires a `NebulaSession` bean (hosts via `NEBULA_GRAPHD_HOSTS`) |

```yaml
relix:
  graph:
    connector: memory   # or neo4j | nebula
    neo4j:
      uri: ${NEO4J_URI:}
      username: ${NEO4J_USERNAME:neo4j}
      password: ${NEO4J_PASSWORD:}
```

### Ingestion demo (Phase 1 - no GPU required)

```bash
# Prerequisites: Docker, Java 21, Gradle
cp .env.example .env

# Start Cassandra + MinIO + extraction-gateway + synvault + synflux
docker compose -f deployment/docker/compose.yaml up -d --build \
  cassandra minio minio-init extraction-gateway synvault synflux

# Ingest demo-data/documents/
./scripts/run-ingestion-demo.sh --phase=1

# Inspect the manifest
curl http://localhost:8091/manifest/demo | python3 -m json.tool
```

### Ingestion demo with LLM enrichment (Phase 2 - requires 2× 8 GB GPU)

> **Note:** This demo runs vLLM locally in Docker for development and evaluation only. Production GPU inference uses the separate `synanton/gpu-execution-plane` repository connected via `synanton.gpu.v1` over mTLS.

```bash
# Download models (~6 GB, needs HF token for Llama 3.1)
export HF_TOKEN=<your_token>
docker compose -f deployment/docker/compose.yaml --profile phase2 pull

./scripts/run-ingestion-demo.sh --phase=2
```

### Syntology standalone demo (ontology admin)

```bash
./scripts/setup-dev.sh
./gradlew :java:syntology:bootRun
# Open http://localhost:8080
```

### Full demo stack (security + topology + syntology + admin UI)

```bash
cp .env.example .env   # set SYNANTON_JWT_SECRET and POSTGRES_PASSWORD
./scripts/run-demo.sh  # docker compose up --build
# Open http://localhost:8080
# Login as alice (writes allowed) or bob (read-only)
```

---

## Repository layout

```
java/
  shared/common/        JWT verifier, error model, TenantContext (shared library)
  ingestion-cache/      Cassandra DAO library - manifest, chunks, analysis, embeddings
  synanton-llm-client/  Provider-agnostic LLM + embedding HTTP client
  synvault/             Content store - FilesystemAdapter, MinIO facade, manifest REST
  synflux/              Ingestion pipeline - stages, job runner, CLI, REST trigger
  security/             AuthN/Z service - htpasswd + JWT issuance + FsPermissionGuard
  topology/             Org/user/ACL store - JDBC repos, Flyway, FilesystemAclSeeder
  syntology/            Ontology service - REST API + Jena TDB2 + SHACL
  synquest/             Hybrid search kernel (Lucene 9 BM25 + HNSW)
  relix/                GraphRAG engine (`GraphConnector` adapters, MCP tools)
  planner/              Query intent classifier + plan generator
  gateway/              Plan DAG executor + LLM synthesis
  synapt/               Public REST/gRPC ingress, rate limiting
  control-plane/        Admin API, forecast, anomaly, GitOps
  synflux-router/       Kafka-driven ingestion work distributor
  synanton-mcp/         MCP protocol bridge
  gpu-contract/         synanton.gpu.v1 protobuf (mirrored in gpu-runtime)
  gpu-gateway/          in-repo GPU gateway (not a substitute for gpu-runtime)
  extraction-contract/  synanton.extraction.v1 protobuf (mirrored in content_extractor)
  extraction-client/      Extraction plane client - sync/async, fallback policies, metrics
  annotations/          Annotation registry - definitions, dependency DAG, Resolutor, Equalix (AAP-1+AAP-2 done, v1.24/1.25)
  analytics/            Analytics Plane - events, facts, ClickHouse adapter, registry (planned, v1.24/1.25)

rust/                   Future Rust components (synquest hot loop - Phase 5)

ui/
  syntology-admin/      React SPA - graph editor, login, grants view, SHACL panel

deployment/
  docker/               Docker Compose stack and Dockerfiles for all services

docs/
  architecture/         Current design 1.22; 1.21 extraction; 1.20 GPU Part VIII; 1.19 baseline
  implementation/       Phase-by-phase implementation plans
  proposals/            Versioned change proposals (v1.20 GPU, v1.21 extraction, v1.22 chunking)

scripts/
  setup-dev.sh          Toolchain check (Java 21, Docker, Node 20)
  run-demo.sh           Full demo stack via docker compose
  run-extract-index-poc.sh  Ingest → extract → index → search (compose)
  run-ingestion-demo.sh Ingestion-only demo (Phase 1 or Phase 2)
  build-all.sh          Build every active module
  verify-contract-mirror.sh      synanton.extraction.v1 vs content_extractor
  verify-gpu-contract-mirror.sh  synanton.gpu.v1 vs gpu-runtime

demo-data/
  documents/            10 synthetic markdown/text files for ingestion demo
  ontologies/           Sample Turtle/RDF files for syntology demo
  users/                htpasswd seed file (alice, bob, admin)

test/
  e2e/                  Playwright acceptance tests (planned - Phase 1 DoD)
```

---

## Architecture overview

Synanton follows a **hexagonal (ports-and-adapters)** architecture. Each module owns:

- An **inbound port** - the REST/gRPC/MCP surface the module exposes.
- A set of **outbound ports** - SPIs the module calls without knowing the implementation.
- **Adapters** - concrete implementations of those SPIs (e.g. `FilesystemAdapter`, `MinioObjectStoreAdapter`, `JenaTdb2OntologyAdapter`).

This means every adapter is swappable without touching domain logic. `FilesystemAdapter` is replaced by `S3Adapter` by configuration alone. Relix graph backends swap the same way: `relix.graph.connector=memory|neo4j|nebula` selects `InMemoryGraphConnector`, `Neo4jGraphConnector`, or `NebulaGraphConnector`. Phase 3 may still move that Java port behind gRPC; the in-process adapters do not wait on that work.

**Key SPIs defined:**

| SPI | Where | Purpose |
|---|---|---|
| `ContentAdapter` | `synvault` | Pluggable document source (filesystem, S3, SharePoint, webhooks) |
| `OntologyAdapter` | `syntology` | Pluggable RDF store (Jena TDB2, GraphDB, RDF4J) |
| `GraphConnector` | `relix` | Pluggable graph backend (`memory`, `neo4j`, `nebula`; add an adapter for Neptune/others) |
| `RerankerPort` | `gateway` | Cross-encoder reranker (vLLM, Cohere, custom) |
| `IdentityProviderPort` | `security` | IdP backend (htpasswd, Keycloak, OIDC) |
| `LlmClient` | `synanton-llm-client` | LLM provider (vLLM, OpenAI, Anthropic, Bedrock) |

**Cross-cutting concerns built in from day one:**
- Multi-tenancy via `TenantContext` thread-local propagated on every request.
- Compile-time ACL injection - search and graph queries carry a tenant-scope filter before reaching storage.
- Per-tenant cost attribution - LLM calls, object-store bytes and index queries are metered and budgeted. Ingest path records a `ResourceUsage` benchmark ledger on each manifest (`ingest_usage` JSON) and copies it onto search hits; billing/rate cards are a later consumer of the same numbers.
- GDPR erasure cascade - tombstoning a document propagates through manifest, chunks, embeddings, analysis and graph nodes.

---

## Development

### Prerequisites

- Java 21 (Temurin recommended)
- Docker 24+ with Compose V2
- Node 20 + pnpm 9 (UI only)
- NVIDIA Container Toolkit (Phase 2 GPU pipeline only)
- `content_extractor` checked out as a sibling directory of this repo (`../content_extractor`) - `compose.yaml`'s `extraction-gateway` service builds from that checkout via a relative build context (`../../../content_extractor`)

### Build

```bash
./gradlew build           # all active Java modules
cd ui/syntology-admin && pnpm install && pnpm build
```

### Test

```bash
./gradlew test            # unit tests (no Docker required)
./gradlew acceptanceTest  # acceptance tests (requires Docker)
```

### Environment

Copy `.env.example` and set at minimum:

```
SYNANTON_JWT_SECRET=<at-least-32-random-bytes>
POSTGRES_PASSWORD=<your-choice>
MINIO_ROOT_PASSWORD=<your-choice>
```

---

## Documentation

| Document | Location |
|---|---|
| Platform architecture (**current** 1.22) | `docs/architecture/synanton-design-1.22.md` |
| Structured Content Extraction Plane (1.21 Part IX) | `docs/architecture/synanton-design-1.21.md` |
| Semantic chunking (1.22 Part X) | `docs/implementation/semantic-chunking/INDEX.md` |
| Annotation, recalculation, analytics & reporting plane (1.24/1.25) | `docs/implementation/annotations-analytics-plane/INDEX.md` |
| GPU Execution Plane (1.20 Part VIII) | `docs/architecture/synanton-design-1.20.md` |
| Core baseline (1.19, superseded pointer) | `docs/architecture/synanton-design-1.19.md` |
| Extraction plane implementation plan | `docs/implementation/content-extraction-plane/INDEX.md` |
| GPU Execution Plane implementation plan | `docs/implementation/gpu-execution-plane/INDEX.md` |
| Phases master plan | `docs/implementation/synanton-phases-plan.md` |
| Phase 1 ingestion plan | `docs/implementation/phase1/01-ingestion-pipeline.md` |
| Phase 2 LLM enrichment plan | `docs/implementation/phase2/01-ingestion-pipeline.md` |
| Syntology standalone demo | `docs/implementation/demo/standalone-syntology-demo.md` |
| Lucentrix ingest CLI | `docs/implementation/lucentrix-ingest-cli.md` |

---

## Contact

- **Research & general inquiries:** research@synanton.org
- **Security reports:** security@synanton.org

---

## License

Apache 2.0 - see [LICENSE](LICENSE).
