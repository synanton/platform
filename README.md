# Synanton Platform

**Open-source systems research for enterprise knowledge and reliable business execution.**

Synanton is an AI-native enterprise knowledge platform exploring how enterprise knowledge can be **ingested, structured, secured, derived, searched, reasoned over and recalculated** as a coherent system rather than as a collection of disconnected AI components.

The platform is built as a modular set of services and libraries. The architecture is deliberately experimental: major capabilities are introduced as versioned designs, implemented incrementally and validated through runnable demos, contract tests, benchmarks and failure/security tests.

> **Current architecture:** [Synanton Platform Architecture 1.0](docs/architecture/synanton-platform-architecture-1.0.md) integrates Designs 1.22–1.34, all **Approved (design)**. Approval records architectural decisions; it does not imply implementation or production readiness. The per-design status and evidence are in [§15](docs/architecture/synanton-platform-architecture-1.0.md#15-current-implementation-status).
> **Current implementation focus (2026-10-22):** implementation is partial across 1.22/1.23/1.25/1.26; 1.32 contracts were frozen 2026-10-17 (#246) and generation-A is in progress. The 1.27 contract was frozen 2026-10-17 (#247), but implementation has not started; EWP-0 reconciliation is underway in [#375](https://github.com/synanton/platform/issues/375); 1.28–1.31 also remain unstarted. See the [architecture status table](docs/architecture/INDEX.md#design-version-history) and [documentation inventory](docs/DOCUMENTATION-INVENTORY.md).

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

---

## What Synanton is investigating

Enterprise AI systems increasingly combine search, vector retrieval, knowledge graphs, LLMs, agents and analytics. The difficult part is not adding another component. The difficult part is making the whole system **consistent, explainable, secure and recalculable** when its inputs and rules change.

Synanton investigates several related questions:

- How should heterogeneous enterprise content become structured, reusable knowledge?
- How should semantic chunks preserve structure, provenance, citations and security?
- How can keyword, vector, graph, ontology and LLM-based retrieval coexist behind one query-planning model?
- How should derived knowledge be represented so that changes in models, rules, dictionaries, policies, or source data can trigger controlled recalculation?
- How can analytics observe knowledge and platform activity without becoming a security side channel or a second source of truth?
- How should expensive AI workloads be isolated from the CPU/control plane while preserving a stable execution contract?
- How can enterprise security remain a property of the entire knowledge lifecycle rather than only of the API boundary?

The project treats architecture as a research artifact: **design → implementation → experiment → evidence → architectural revision**.

---

## Core model

The current architecture treats knowledge as **derived state**.

```text
                 SOURCE WORLD
                     │
                     ▼
        ┌─────────────────────────┐
        │ Acquisition / Extraction│
        └────────────┬────────────┘
                     ▼
        ┌─────────────────────────┐
        │ Semantic Content        │
        │ + Semantic Chunks       │
        └────────────┬────────────┘
                     ▼
        ┌─────────────────────────┐
        │ Security Classification │
        │ + Representation Policy │
        └────────────┬────────────┘
                     ▼
        ┌─────────────────────────┐
        │ Annotation              │
        │ Provenance              │
        │ Dependencies            │
        └────────────┬────────────┘
                     ▼
        ┌─────────────────────────┐
        │ Derived Knowledge       │
        └──────┬──────┬──────┬───┘
               │      │      │
               ▼      ▼      ▼
          Search    Graph   Vector
               │      │      │
               └──────┴──────┘
                      │
                      ▼
                 Query / RAG
                      │
                      ▼
                 Applications


      Platform activity + knowledge
                      │
                      ▼
          Protected Analytics Boundary
                      │
                      ▼
          Events → Facts → Aggregates
                      │
                      ▼
             Metrics → Reports
```

A central architectural rule is:

> **Knowledge is derived state and analytics is derived state over knowledge and platform activity.**

This makes recalculation a first-class architectural concern rather than an operational afterthought.

---

## Knowledge lifecycle

The current lifecycle is:

```text
Source Content
    ↓
Extraction
    ↓
Semantic Content
    ↓
Semantic Chunks
    ↓
Security Classification
    ↓
Annotation
    ↓
Provenance / Processing Run / Dependencies
    ↓
Derived Knowledge
    ↓
Search / Vector / Graph Projections
    ↓
Applications
    ↓
Protected Analytics Boundary
    ↓
Analytics Events
    ↓
Analytical Facts
    ↓
Aggregates
    ↓
Metrics / Reports
```

Changes to source content, annotation definitions, models, dictionaries, policies or other dependencies can trigger:

```text
Change
  ↓
Resolutor
  ↓
Dependency / Impact Analysis
  ↓
Recalculation Plan
  ↓
Equalix
  ↓
Controlled Execution
  ↓
Updated Derived Knowledge
  ↓
Updated Projections / Analytics
```

This separation is one of the main research directions of the platform.

---

## Architecture

Synanton is organized around independently testable services and explicit contracts.

```text
 Sources
   │
   ▼
┌──────────────────────────────────────────────────────────────┐
│ Ingestion                                                    │
│                                                              │
│  Synvault ── content storage / adapters                      │
│  Synflux  ── acquire / extract / chunk / enrich / embed      │
│  Extraction Plane ── structured content extraction           │
└──────────────────────────────┬───────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────┐
│ Knowledge                                                    │
│                                                              │
│  Synquest   ── BM25 + vector retrieval                       │
│  Relix      ── graph reasoning / GraphRAG                    │
│  Syntology  ── ontology / SHACL / versioning                 │
│  Annotations ── definitions / provenance / dependencies      │
└──────────────────────────────┬───────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────┐
│ Query & Execution                                             │
│                                                              │
│  Planner ── intent classification / query plans              │
│  Gateway ── plan execution / synthesis / reranking           │
│  Synapt  ── REST / gRPC ingress / authentication             │
│  MCP     ── external tool surface                            │
└──────────────────────────────────────────────────────────────┘

                    ┌──────────────────────┐
                    │ GPU Execution Plane  │
                    │ isolated GPU cluster │
                    └──────────┬───────────┘
                               │
                         synanton.gpu.v1
                               │
                               ▼
                         model serving


                    ┌──────────────────────┐
                    │ Analytics Plane      │
                    │ events / facts /     │
                    │ aggregates / reports │
                    └──────────────────────┘
```

The CPU/control plane and production GPU workloads are separated by a versioned gRPC contract. This allows GPU capacity, scaling, scheduling and failure domains to evolve independently.

---

## Security

The shared contract across `synanton/platform`, `synanton/ui`, and `synanton/security-provider` is [docs/security/security-model.md](docs/security/security-model.md). Design 1.23 requires Platform to enforce tenant scope. The proven Phase 1 demo path rejects caller-supplied tenant headers/body fields; broader data-layer enforcement is still tracked by [#339](https://github.com/synanton/platform/issues/339).

- **Demo-path evidence (2026-10-21):** the Phase 1 ingestion/search request path rejects `X-Tenant-ID` and body `tenant_id` with 400 (#347, ADR 0007); scope in that demo is taken from its validated token. This is not evidence of platform-wide tenant enforcement.
- **Platform-wide enforcement — In progress (#339):** RLS on tenant-scoped tables; transaction-scoped `SET LOCAL`; tenant-scoped repositories; tenant-prefixed cache keys; permanent cross-tenant and no-context deny tests. Do not describe these controls as complete until the tracked acceptance evidence exists.
- **Target invariants:** fail closed (no default tenant or full-table scan); anti-oracle 403/404 semantics; service accounts are a separate authorization axis. See the shared model for the approved contract and current implementation scope.
- **Anti-oracle error semantics.** 403 when the tenant is in the caller's own token; uniform 404 when naming a tenant that might not be theirs.
- **Service accounts are a separate authorization axis.** Not modeled as tenant membership; scoped, auditable, and time-bound. [#339] — **Design fixed**.
- **Deployment topology** (shared vs dedicated) is a separate decision. [#339]

Platform does not own identity verification or issuance (security-provider #340), the identity model (#341, Identity 1.29), or client-side token isolation (UI ADR 0006).

## Research areas

### 1. Structured content and semantic chunking

Traditional RAG pipelines often flatten documents into text and then apply fixed-size chunking.

Synanton instead separates:

```text
raw source
   ↓
structured extraction
   ↓
semantic content
   ↓
semantic chunking
```

Semantic chunks retain information such as:

- heading hierarchy
- section paths
- page coordinates
- source elements
- tables and figures
- token counts
- partial-section state
- provenance

The goal is to make the retrieval unit useful not only for embeddings, but also for citation, annotations, graph relationships, security, provenance and recalculation.

---

### 2. Hybrid and graph retrieval

`Synquest` provides the search kernel, combining lexical and semantic retrieval.

`Relix` provides graph reasoning through a connector abstraction.

`Syntology` provides ontology management and validation.

The query layer can therefore combine:

```text
BM25
  +
Vector retrieval
  +
Graph traversal
  +
Ontology resolution
  +
Reranking
  +
LLM synthesis
```

The research question is not whether each technique works independently, but how they can be composed into a predictable query plan.

---

### 3. Secure derived knowledge

Enterprise ACLs do not end at the source document.

Security must survive:

```text
source
 → extraction
 → chunking
 → annotation
 → embedding
 → graph
 → search
 → cache
 → aggregation
 → reporting
```

The architecture therefore treats classification, representation selection, storage, indexing, query planning, caching, aggregation and reporting as parts of one security pipeline.

This is particularly important for semantic representations: an embedding or derived artifact must not become a new path around the original authorization boundary.

---

### 4. Annotations and derived knowledge

Annotations are becoming first-class, versioned objects with:

- definitions
- versions
- dependency DAGs
- processing runs
- provenance
- derived outputs

The important architectural distinction is between **canonical source knowledge** and **derived knowledge**.

Derived knowledge can be regenerated.

That enables controlled evolution when:

- annotation definitions change
- models change
- dictionaries change
- security policies change
- source content changes
- dependencies change

---

### 5. Dependency-aware recalculation

Synanton uses two dedicated components for this problem:

- **Resolutor** — determines what is affected by a change and produces a dependency-aware recalculation plan.
- **Equalix** — executes recalculation under priority, resource and fairness constraints.

The objective is to turn:

> “Something changed; rebuild everything.”

into:

> “Something changed; determine exactly what became invalid and execute the required work under controlled resource constraints.”

---

### 6. Analytics as derived state

Analytics is deliberately downstream of the knowledge/security boundary.

```text
Knowledge + Platform Activity
             │
             ▼
   Protected Analytics Boundary
             │
             ▼
      Analytics Events
             │
             ▼
    Analytical Facts
             │
             ▼
        Aggregates
             │
             ▼
     Metrics / Reports
```

Analytics must:

- preserve tenant and classification boundaries
- avoid becoming authoritative knowledge
- prevent aggregate-based security leakage
- support idempotent processing
- remain independently scalable
- expose controlled APIs and MCP tools

The current design evaluates ClickHouse as a candidate analytical backend.

---

### 7. Isolated AI execution

Production GPU workloads run in a separate execution plane.

The platform communicates through `synanton.gpu.v1`, providing an explicit boundary for:

- model execution
- embeddings
- reranking
- tenant assertions
- idempotency
- execution status
- capacity
- cancellation
- observability

The architecture is designed so GPU infrastructure can scale independently from the CPU/control plane.

---

## Modules

| Module | Responsibility | Status |
|---|---|---|
| `synvault` | Content storage and source adapters | ✅ |
| `synflux` | Ingestion, extraction, chunking, enrichment, embeddings | ✅ |
| `synquest` | Hybrid lexical/vector search | ✅ |
| `relix` | GraphRAG and graph connectors | ✅ |
| `syntology` | Ontology management and SHACL validation | ✅ |
| `planner` | Query intent classification and plan generation | ✅ |
| `gateway` | Query execution, synthesis and reranking | ✅ |
| `synapt` | Public REST/gRPC ingress | ✅ (frozen for generation-A — ADR-015) |
| `security` | Authentication, authorization and filesystem ACL enforcement | ✅ |
| `topology` | Organization, grants and policy storage | ✅ |
| `synflux-router` | Kafka-based ingestion distribution | ✅ |
| `control-plane` | Administration and model-serving directory | ✅ |
| `synanton-mcp` | MCP protocol bridge | ✅ |
| `platform-api` | Generation-A external API surface (`/v1/` routes, Operation model, envelope trust interfaces) | 🔶 In progress (contracts frozen 2026-10-17; #246) |
| `annotations` | Annotation registry, provenance and recalculation foundation | 🔶 In progress |
| `content-cache-api` | Content Cache port (ContentStore, v1 references), in-memory and filesystem fakes | ✅ |
| `content-cache-postgres` | JDBC ContentStore adapter with testcontainers contract tests | ✅ |
| `content-cache-grpc` | gRPC ContentStore client (explicit-unsupported lifecycle ops) | ✅ |
| `analytics` | Events, facts, aggregates, metrics and reports | 🔲 Planned |
| `gpu-contract` | Versioned GPU execution protobuf contract | ✅ |
| `gpu-client` | Shared GPU-plane client: mTLS channel, canonical error codes, fail-closed tenant-aware EMBED client (`gpu-plane` profile in `synquest`/`synflux`) | ✅ |
| `gpu-gateway` | GPU boundary and execution lifecycle | ✅ |
| `extraction-contract` | Versioned structured extraction contract | ✅ |
| `extraction-client` | Platform client and fallback policies | ✅ |
| `synreview` | Human review of low-confidence knowledge | 🔲 Planned |

Status key: this table is a **component/module inventory**; it does not roll up to a versioned design plane's implementation status. Component code may exist while the corresponding plane remains not started in the architecture table. For example, a `synflux` module entry does not mean Design 1.28's full ingestion plane is implemented. See Architecture 1.0 §15 for plane status. **Implemented** means code/contracts exist in this repository; it does not mean all planned capabilities, production deployment, or end-to-end validation are complete. **In progress** and **planned** identify implementation state, not architecture approval. Phase-specific end-to-end validation is recorded separately above and in `docs/acceptance/`.

Supporting components live in sibling repositories, including the GPU execution plane, structured content extractor and Lucentrix ingestion CLI. Their documentation status is tracked in the [component documentation workstreams](docs/DOCUMENTATION-INVENTORY.md#component-workstreams).

---

## Architecture evolution

Synanton intentionally evolves through versioned architecture documents.

| Version | Focus | Status |
|---|---|---|
| 1.19 | Baseline platform architecture | Superseded; folded into 1.22 as the Parts I–VII baseline |
| [1.20](docs/architecture/archive/synanton-design-1.20.md) | Isolated GPU Execution Plane | Folded into 1.22 (Part VIII); generalized as a Platform/Runtime contract by 1.30 |
| [1.21](docs/architecture/archive/synanton-design-1.21.md) | Structured Content Extraction Plane | Folded into 1.22 (Part IX) |
| [1.22](docs/architecture/synanton-design-1.22.md) | Semantic Content Structuring / Chunking | **Current baseline**; furthest along in implementation |
| [1.23](docs/architecture/synanton-design-1.23.md) | Secure semantic representations | Approved; normative security baseline for every later plane; implementation in progress |
| 1.24 | Annotation foundation | Consolidated into 1.25 (no separate document was ever published) |
| [1.25](docs/architecture/synanton-design-1.25.md) | Annotations, derived knowledge, recalculation, analytics and reporting | Approved (architecture); partial implementation — AAP-1/AAP-2 landed, AAP-3–AAP-8 not started |
| [1.26](docs/architecture/synanton-design-1.26.md) | Content Cache Plane | Approved (architecture); implementation in progress (reference service + Java adapters) |
| [1.27](docs/architecture/synanton-design-1.27.md) | Eventing and Workflow Plane — common execution fabric for 1.28–1.32 | Approved (architecture); contract frozen 2026-10-17 (#247); implementation not started — see [implementation plan](docs/implementation/eventing-workflow-plane/INDEX.md) |
| [1.28](docs/architecture/synanton-design-1.28.md) | Ingestion Plane | Approved (architecture); implementation not started |
| [1.29](docs/architecture/synanton-design-1.29.md) | Identity, Tenant & Policy Plane | Approved (architecture); implementation not started |
| [1.30](docs/architecture/synanton-design-1.30.md) | AI and Model Runtime Plane Contract | Approved (architecture); implementation not started |
| [1.31](docs/architecture/synanton-design-1.31.md) | Search and Retrieval Plane | Approved (architecture); implementation not started |
| [1.32](docs/architecture/synanton-design-1.32.md) | Platform API | Approved (architecture); **contracts frozen 2026-10-17** (#246); generation-A implementation in progress — see [implementation plan](docs/implementation/platform-api-plane/INDEX.md) |
| [1.33](docs/architecture/synanton-design-1.33.md) | Kubernetes Lifecycle, Compatibility and Multi-Operator Architecture | Approved (architecture — contract/readiness review only; no operator implementation exists) |
| [1.34](docs/architecture/synanton-design-1.34.md) | Temporal Versioned Knowledge and Retrieval | Approved (architecture); implementation not started |
| **[1.0](docs/architecture/synanton-platform-architecture-1.0.md)** | **Platform Architecture 1.0 — capstone integrating 1.22–1.34** | **Approved (architecture)**; see its §15 for the authoritative per-design implementation status |

The architecture documents are part of the project, not merely implementation notes. They record decisions, constraints, interfaces, failure models, security boundaries and evaluation criteria. Accepting Designs 1.26–1.34 as architecture does not authorize skipping the implementation sequence: each plane follows its frozen contracts. **Where that stands (2026-10-21):** the 1.32 contracts are frozen (#246) and generation-A implementation is in progress under BBAG-A amendment A1, which split *M0-entry* from *M0-freeze*. The 1.27 contract is also frozen (#247, 2026-10-17), but its implementation has not started; 1.28–1.31 remain not started.

---

### Annotation, Derived Knowledge, Recalculation, Analytics & Reporting Plane track - v1.24/1.25 *(in progress)*

Synanton is intended to produce evidence, not just architecture diagrams.

Current validation mechanisms include:

- runnable Docker-based demonstrations
- unit and integration tests
- gRPC contract tests
- contract-mirror checks between repositories
- security-focused test tiers
- ingestion usage / benchmark ledgers
- failure and degraded-mode tests
- search and retrieval experiments
- scalability and load evaluation
- analytics correctness and isolation tests
- architecture invariants

A useful experiment should answer a concrete question and produce evidence that can influence the next architecture revision.

---

## Quick start

### Accepted demo milestone: Phase 1 BM25 upload → search (2026-10-21)

**Proven and accepted:** BM25 ingestion and UI search with provenance on the
homelab cluster. Path: **UI upload / Lucentrix → `POST /v1/ingestion-runs` →
extraction-gateway → synflux (content cache + Cassandra) → synquest (BM25) → UI
search and provenance.** The slice uses the MinIO hot bucket for extraction
handoff; the full stack's synvault/content-cache remains the canonical artifact
store.

This is the accepted user-facing demo milestone. It is separate from the
historical module-phase numbering in
[`docs/implementation/synanton-phases-plan.md`](docs/implementation/synanton-phases-plan.md)
and from the local Docker profiles below.

Operational constraints:

- **Sync extraction ceiling:** `EXTRACTION_MAX_SYNC_OBJECT_BYTES` (default
  10 MiB). Larger files require the asynchronous extraction path/configuration.
- **Cassandra chunk batching:** chunk writes are bounded at 10 chunks per batch
  (#365).
- **Tenant security:** the tenant comes only from the verified token;
  `X-Tenant-ID` and body `tenant_id` are rejected with 400 (#347, ADR 0007).
- **Out of scope:** vector leg (gpu-runtime #23, decision 2026-10-31), demo
  auth placeholder (#340), public page ranges (#338).

Evidence: [`docs/demo/demo-script.md`](docs/demo/demo-script.md) and
[`docs/acceptance/p0-ingestion-search-2026-10-21.md`](docs/acceptance/p0-ingestion-search-2026-10-21.md).
The separate platform five-check passed criteria 1–5 after #357 (2026-10-21);
the BM25 demo acceptance does not assert vector retrieval or platform-wide
tenant enforcement.

### Local Docker ingestion PoC

This local ingest → extract → index path is wired end to end. **Full Docker
image builds were not verified in every environment** - run the script locally
to confirm. It is a local PoC, distinct from the accepted cluster demo above.

**What it does**

- Starts Cassandra, MinIO, `extraction-gateway`, synvault, synflux and synquest.
- Ingests `demo-data/documents` (markdown/text plus a sample PDF and a heading-structured markdown file).
- Reindexes synquest and runs a search whose hits can include `source_uri`, `section_path`, `source_elements` and `ingest_usage`; the response may include `query_usage`.

**Extraction plane (`content_extractor`).** Serves sync and async extraction over `synanton.extraction.v1`, reads objects from MinIO, routes by media type and enforces size/time/payload limits. Plain text and markdown use the Tika adapter with honest feature states. PDF extraction uses the embedded `org.opendataloader:opendataloader-pdf-core` library in-process (no external sidecar to run) and is on by default; toggle it with `EXTRACTION_OPENDATALOADER_ENABLED` (default `true`) - when disabled, the PDF adapter reports unsupported and synflux applies the configured fallback policy (`FALLBACK_LOCAL_TIKA` by default).

**Platform client.** `java/extraction-client` wraps the gRPC contract with `ExtractionFallbackPolicy`, reconcile-after-timeout on async submit and Micrometer metrics. Configure via `synanton.extraction.client.*` in synflux `application.yml`. Known limitation: the client only consumes inline payloads (`StructuredPayload.inline_content`) - if the gateway ever returns a large payload by reference (`PayloadReference`) instead, it currently falls back to Tika rather than fetching the referenced object; not hit by this demo's file sizes.

**Chunking and search.** Synflux skips redundant Tika when structured extraction succeeds. `SemanticChunkStage` chunks from `elements` (not flat text). Chunks persist `page_start`, `page_end`, `section_path`, `chunk_type`, `heading`, `source_elements`, `token_count` and table `structured_content`. Manifests store a document-level `ingest_usage` JSON rollup (wall time, CPU time, model chars/tokens per stage - a benchmark ledger, not billing). Synquest indexes those fields with BM25; HNSW is optional. Search does not fail if query embedding is down; hits carry citation and usage metadata.

```bash
# From this repository (Docker + Java 21)
./scripts/run-extract-index-poc.sh
```

To exercise the Tika fallback path instead of PDF extraction, disable it before running:

```bash
export EXTRACTION_OPENDATALOADER_ENABLED=false
./scripts/run-extract-index-poc.sh
```

GPU runtime is **not** on this path. Ingest embeddings still use `HttpLlmClient` when a GPU is present. Production GPU inference uses `synanton.gpu.v1` (mirrored with `gpu-runtime`); see "Isolated AI execution" above.

### Content extractor standalone (separate cluster)

`extraction-gateway` lives in the sibling `content_extractor` repo and is built automatically as part of the demos above. Deployment topology - embedded, co-located, or an independently scaled cluster - is a scaling concern that doesn't change the `synanton.extraction.v1` contract, so it can also be built and run on its own, against its own Postgres/MinIO and network:

```bash
# From the content_extractor repo root (sibling of this repo)
cd ../content_extractor
docker build -f deployments/docker/extraction-gateway.Dockerfile -t synanton/extraction-gateway .

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

### Local ingestion demo (Docker; no GPU required)

This local compose profile is a developer demo, not the accepted homelab Phase 1
acceptance record above.

```bash
# Prerequisites: Docker, Java 21, Gradle
cp .env.example .env

# Start Cassandra + MinIO + extraction-gateway + synvault + synflux
docker compose -f deployments/docker/compose.yaml up -d --build \
  cassandra minio minio-init extraction-gateway synvault synflux

# Ingest demo-data/documents/
./scripts/run-ingestion-demo.sh --phase=1

# Inspect the manifest
curl http://localhost:8091/manifest/demo | python3 -m json.tool
```

### Local LLM enrichment profile (Docker; requires 2× 8 GB GPU)

> **Note:** This demo runs vLLM locally in Docker for development and evaluation only. Production GPU inference uses the separate `synanton/gpu-execution-plane` repository connected via `synanton.gpu.v1` over mTLS.

```bash
# Download models (~6 GB, needs HF token for Llama 3.1)
export HF_TOKEN=<your_token>
docker compose -f deployments/docker/compose.yaml --profile phase2 pull

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

```text
java/
  shared/common/
  ingestion-cache/
  synanton-llm-client/
  synvault/
  synflux/
  security/
  topology/
  syntology/
  synquest/
  relix/
  planner/
  gateway/
  synapt/
  platform-api/
  control-plane/
  synflux-router/
  synanton-mcp/
  annotations/
  analytics/
  content-cache-api/
  content-cache-postgres/
  content-cache-grpc/
  gpu-contract/
  gpu-gateway/
  extraction-contract/
  extraction-client/

ui/
  syntology-admin/

deployments/
  docker/

docs/
  architecture/
  implementation/
  proposals/

scripts/
demo-data/
test/
```

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

Start here:

- [Architecture — Synanton Platform Architecture 1.0 (capstone)](docs/architecture/synanton-platform-architecture-1.0.md)
- [Architecture — Synanton Design 1.25](docs/architecture/synanton-design-1.25.md)
- [Annotations, Recalculation, Analytics & Reporting](docs/implementation/annotations-analytics-plane/INDEX.md)
- [Semantic Chunking](docs/implementation/semantic-chunking/INDEX.md)
- [Structured Content Extraction](docs/implementation/content-extraction-plane/INDEX.md)
- [GPU Execution Plane](docs/implementation/gpu-execution-plane/INDEX.md)
- [Eventing and Workflow Plane (v1.27) — contract frozen, EWP-0 in progress, implementation not started](docs/implementation/eventing-workflow-plane/INDEX.md)
- [Platform API Plane (v1.32) — implementation plan, contracts frozen, generation-A in progress](docs/implementation/platform-api-plane/INDEX.md)
- [Vertical slice — trust bar, gap register, and diagnostic runbook](docs/implementation/vertical-slice/INDEX.md) — distinguishes #253 demo acceptance from the broader open Architecture 1.0 §16 criterion
- [Cross-plane Architecture Review Resolution (1.26–1.33)](docs/architecture/proposals/synanton-architecture-review-resolution.md)

Designs 1.26–1.34 (content cache, eventing/workflow, ingestion, identity, AI runtime, search, platform API, Kubernetes lifecycle, temporal versioning) are **Approved (design)**. Implementation differs by plane: 1.26 has a reference service and Java adapters at differing stages; 1.27's contract is frozen (#247, 2026-10-17), with implementation not started; 1.32's contract set is frozen (#246) and generation-A implementation is in progress; 1.28–1.31 are not started; 1.33 has no operator implementation; 1.34 is not started. The companion ADRs and implementation plans are linked from [`docs/architecture/INDEX.md`](docs/architecture/INDEX.md).

The documentation map and document dispositions are in [`docs/INDEX.md`](docs/INDEX.md) and [`docs/DOCUMENTATION-INVENTORY.md`](docs/DOCUMENTATION-INVENTORY.md).

For the research program and longer-term direction, see the [Synanton Roadmap](https://github.com/synanton/.github/blob/main/ROADMAP.md).

---

## Design principles

### Knowledge is derived state

Derived knowledge should be reproducible from its inputs, definitions, dependencies and processing rules.

### Security is a pipeline property

Authorization must survive every transformation and projection of knowledge.

### Contracts define boundaries

Service and execution boundaries use explicit contracts so implementations can evolve independently.

### Prefer incremental recomputation

A change should invalidate the smallest correct portion of derived state rather than trigger unnecessary global rebuilds.

### Make expensive work observable

AI and distributed processing should expose execution metadata, resource usage, provenance and failure state.

### Architecture must be testable

Important architectural claims should have executable tests, contract checks, benchmarks, or other observable evidence.

### Components should remain replaceable

Storage engines, graph implementations, LLM providers, extraction implementations and execution backends should be replaceable behind stable ports or contracts.

---

## Project status

Synanton is an **active open-source research and engineering project**.

Architecturally, the design series is now well ahead of implementation: Designs 1.22–1.34 are all **Approved (architecture)**, integrated by the [Synanton Platform Architecture 1.0 capstone document](docs/architecture/synanton-platform-architecture-1.0.md). Implementation, however, remains concentrated where it has always been furthest along — the 1.22 baseline, the 1.23 security model, and 1.25's annotation foundation and recalculation (AAP-1/AAP-2) — plus the 1.26 Content Cache reference implementation now in progress. The core ingestion, retrieval, graph, ontology, security, MCP, GPU-contract, extraction-contract and semantic-chunking foundations are implemented to varying degrees. Of the nine newly approved planes, seven (1.27 eventing/workflow, 1.28 ingestion, 1.29 identity, 1.30 AI runtime, 1.31 search/retrieval, 1.33 Kubernetes lifecycle, 1.34 temporal versioning) have **no implementation started**; 1.27's contract is frozen (#247) but its implementation has not started; 1.32's contract set is frozen (#246) and its generation-A slice is in progress. The architecture sequence and remaining slice criterion are in capstone §14/§16; see [Eventing and Workflow Plane](docs/implementation/eventing-workflow-plane/INDEX.md) and [Platform API Plane](docs/implementation/platform-api-plane/INDEX.md).

**Contract gate status:** the 1.27 eventing/workflow contract is frozen (#247, 2026-10-17), but implementation has not started. The 1.32 contract is frozen and its generation-A slice is in progress ([Platform API Plane](docs/implementation/platform-api-plane/INDEX.md)). The 1.28–1.31 planes remain unstarted; their sequence and the broader vertical-slice acceptance criterion are in Architecture 1.0 §14/§16.

The project is not presented as a finished enterprise product. The repository is intentionally used to explore architecture, implementation techniques, operational boundaries and measurable trade-offs.

---

## Status snapshot — Platform API findings (2026-10-17)

The Platform API contract freeze completed this pass (#246). Five artifacts are frozen: identity context (W1), search model (W3), error model (W2), Operation model (PAPI-3), and the OAuth auth spec (W4). Six ADRs were accepted during the pass — 014 (RAG authority), 015 (Gen-A module home), 016 (MCP downstream auth), 017 (validation tooling), 018 (OAuth AS facade), 019 (downstream trust predicate) — plus ADR-020 (Operation partial outcome).

**Tenant propagation defect found and fixed.** A valid, correctly scoped tenant-A request could be scoped to `demo` downstream: MCP sent `X-Tenant-Id`, `synapt` and `relix` read `X-Tenant`, and both fell back to a default tenant. No attacker was required. Corrected across all four hops — tenant now comes only from verified identity, no hop reads or forwards a client-supplied tenant header, and missing context fails closed.

**MCP had a fail-open scope guard.** An `!scopes.isEmpty()` short-circuit skipped scope enforcement entirely for absent, empty, or unparseable scope claims. Fixed and covered by five tests; a module-wide search found it was the single instance.

**Open:** downstream trust predicate (signed short-lived context) awaits the security-provider envelope wire format; the vertical slice integration test awaits the ingestion/retrieval inputs. See the [Platform API Plane plan](docs/implementation/platform-api-plane/INDEX.md).

---

## Current status & recent findings (2026-09-20)

A cross-repo debugging and benchmarking pass this session (SNTP-9 retrieval evaluation work) surfaced several real, verified findings worth recording here rather than only in individual PR history.

**`content_extractor` had a real, silent extraction bug — now fixed.** `extraction-gateway` failed every text/markdown extraction with a `NoSuchMethodError` (Spring Boot 3.3.5's dependency-management BOM was force-downgrading `commons-lang3` to `3.14.0`, below what Tika's parser modules actually need), and the failure was completely silent — `ExtractSyncService` had no logger anywhere, so nothing showed up without a manual gRPC probe. Fixed in `content_extractor`: a `commons-lang3.version` BOM override (same mechanism already used there for `testcontainers.version`), a new logger on every failure path, a regression test reproducing the exact failure, a real `docker build` validation (worked around this sandbox's flaky large-file-download network path once, via a one-off BuildKit cache seed — the committed fix is a portable `--mount=type=cache` Gradle cache, not the seed itself), and a new CI job (`docker-smoke-test`) that builds the image and runs a real extraction call, so a runtime-only classpath bug like this can't silently pass CI again.

**Known limitation, not a bug: `content_extractor`'s text/markdown adapter never parses markdown headings.** It's a generic Tika `AutoDetectParser` that splits on blank lines into flat paragraphs — confirmed via direct gRPC probe against `structured-supply-chain.md` (the demo corpus's one hand-written file with real `#`/`##`/`###` headings): extraction succeeds, but every element comes back `PARAGRAPH`, none `HEADING`. PDF extraction (OpenDataLoader-backed) does **not** have this limitation. This means `SemanticChunkStage` can only ever produce real structure-aware chunks for PDF documents in this corpus today, not `.md`/`.txt` files — regardless of `extraction-gateway`'s health.

**Corpus enriched with 3 real, structurally-rich PDFs** to make that distinction demonstrable: `demo-data/documents/{mental-health-report-2010,outsourcing-agreement,sks8300-web-interface-manual}.pdf` (11/47/4 real headings respectively, plus tables/lists/images) — sourced from the public `OHR-Bench` dataset and a user-provided technical manual. The corpus's original `quarterly-report.pdf` remains a known, separate, pre-existing defect (missing `xref`/`startxref` — invalid per spec) and is left as-is; it's a `platform` demo-data issue, not a `content_extractor` bug.

**New tools:**
- [`tools/retrieval-eval`](tools/retrieval-eval/README.md) — the SNTP-9 retrieval benchmark harness (config, metrics, ingest/query wrappers, CLI). Phase B0 (harness + gold-chunk annotation) and Phase B1's T01 (BM25, flat chunking) / T04 (hybrid-labeled, real structural chunking) baseline runs are done — see [the research plan](docs/research/retrieval-evaluation-benchmark-plan.md) and its [manual QA reproduction guide](docs/demos/retrieval-benchmark-b0-b1-demo.md).
- [`tools/extraction-probe`](tools/extraction-probe/README.md) — a standalone CLI to upload a document to `extraction-gateway` and save its textual/structured response, independent of the platform's ingestion pipeline. Useful for diagnosing extraction issues directly.

**Real finding: dense/hybrid retrieval is unreachable in the Phase 1 stack.** `synquest`'s embedding client has no fallback; it always calls the vLLM embedding service that only exists behind `docker compose --profile phase2` (2×8GB GPUs), which isn't running in the Phase 1 demo. Every search reports `query_usage.embed_skipped=true`. T02 (dense-only) and T03 (hybrid) from the benchmark's test matrix are **blocked, not run**, pending that profile. (Superseded 2026-09-25: see the GPU plane update below; dense/hybrid now goes through GPU-7 first.)

**Decided: the homelab [k8s cluster](docs/demos/cluster-as-built.md) will be created as a cluster dedicated to Synanton's GPU plane**. 4-node hardware (`node0` control-plane, no GPU; `node1`/`node2`/`node3` workers with one GPU each — GTX 1650 4GB, RTX 4060 Ti 16GB, RTX 5060 Ti 16GB; as-built reference: `docs/demos/cluster-as-built.md`), reproducing its proven bootstrap shape (Calico CNI, NVIDIA GPU Operator device plugin + `RuntimeClass nvidia`, Longhorn + `local-ssd` storage, a local registry at `local-registry:5000`). The sibling `gpu-runtime` repo (GPU-1 through GPU-3 complete, GPU-4 contract-unified-but-optional, **GPU-5 — Kubernetes deployment**) is what gets deployed there, to give `platform` a real, isolated embedding/inference endpoint instead of relying on the Docker Phase 2 profile.

The full ticket backlog for this is written down, not left as an open question: [`gpu-runtime/deployments/homelab/gpu-5-implementation-plan.md`](../gpu-runtime/deployments/homelab/gpu-5-implementation-plan.md) (phased GPU-5 bring-up, decisions D1–D7, acceptance) and [`docs/research/gpu-plane-integration-tickets.md`](docs/research/gpu-plane-integration-tickets.md) (what unblocks in `platform` once it lands). Three items were independent of the cluster and proceeded in the meantime: writing gold queries against the 3 newly-added PDFs (§ above), adding `OHR-Bench` PDFs as permanent `content_extractor` test fixtures, and B2 continuation on the retrieval benchmark (hierarchical chunking, reranker, graph rank-fusion).

**GPU plane update (2026-09-25, gpu-runtime PR #15):** the platform↔GPU-plane transport is **gRPC `synanton.gpu.v1` over mTLS** (Deployment Plan v3.1.0 §4, §13). The byte-identical contract mirrored here now includes `ExecuteStream`, the Responses API (`Operation.RESPOND`, `GetResponse`, `DeleteResponse`), the admin `GPUControlService`, `upstream_request_id`, `ErrorInfo.code` and `ExecutionRequest.data_tags`.

| | GPU-5 (homelab k8s) | GPU-7 (external providers) |
| --- | --- | --- |
| Deployment contract | Defined | Defined (v3.1.0) |
| Implementation | One workload per GPU (node1 TEI/BGE-base embedding, node2 vLLM Qwen3-Reranker, node3 vLLM Qwen3-4B); mTLS; execution-JWT signing + JWKS (T-K8S-6a) | **Complete:** mTLS + tenant authorization, provider registry and rewrite, streaming, Responses API, circuit breaker, health, cost ledger, budget, sensitivity, runtime kill switch, multi-provider failover |
| Acceptance | **Cluster phase 5 passed (2026-09-25):** EMBED/SYNTHESIZE/stream/RERANK through Gateway → Envoy → GPUs; load baselines pending | **Passing** (acceptance suite 31/31, packaged smoke, live OpenRouter free-model check, §46 checklist); freeze attestation pending sign-off |

**GPU-5 cluster phase 5 run (2026-09-25).** Single requests go through Gateway (gRPC mTLS, principal `synanton-platform`) → Envoy (ES256 execution JWT verified) → the GPU backends. Gateway image `gpu-gateway:0.1.0@sha256:8a747eb…`.

| Operation | Backend | Result | Latency |
|---|---|---|---|
| EMBED | TEI `synanton-bge-base-embedding` (node1, GTX 1650) | 2 vectors × 768 dim | 166 ms |
| SYNTHESIZE | vLLM `synanton-qwen3-4b-synthesis` (node3, RTX 5060 Ti) | "OK", logical model ID returned, usage 12 in / 2 out | 91 ms |
| ExecuteStream | vLLM `synanton-qwen3-4b-synthesis` (node3) | 12 data chunks + exactly 1 SUCCESS terminal | 282 ms |
| RERANK | vLLM `synanton-qwen3-reranker-0.6b` (node2, RTX 4060 Ti) | correct order: Paris 0.98 > bananas 0.898 | 74 ms |
| Negative | Envoy / NetworkPolicy | unsigned request → 401; TEI, vLLM and JWKS unreachable from a non-Envoy pod | — |

Idle VRAM after model load: node1 471 / 4096 MiB, node2 6229 / 16380 MiB, node3 13689 / 16311 MiB (vLLM preallocates KV cache at `--gpu-memory-utilization 0.90`). Load baselines are pending (`deployments/homelab/gpu-5-implementation-plan.md` §11).

Platform client (`java/gateway/.../gpu/GpuExecutionClient`): mTLS channel (`GPU_TLS_ENABLED`, `GPU_TLS_CA_PATH`, `GPU_TLS_CERT_PATH`, `GPU_TLS_KEY_PATH`; principal `synanton-platform`), `executeStream()`, canonical error codes, and no retry of non-retryable denials (`tenant_not_allowed`, `budget_exceeded`, …). Adapters do not yet set `data_tags` from the platform's classification (a platform-side follow-up).

Retrieval benchmark impact: **dense and hybrid runs are planned against GPU-7 on OpenRouter free embedding models** ([benchmark plan §6 Phase B1-G](docs/research/retrieval-evaluation-benchmark-plan.md)). These are new rows T02-G/T03-G/T04-G, separate from bge-base.
- `synquest`/`synflux` still embed over HTTP, while the GPU plane is gRPC-only. The decided fix is a shared, fail-closed gRPC `LlmClient`, selected by an opt-in `gpu-plane` profile.
- Embedding dimension is configurable (`EMBED_DIM`, `EMBED_TRUNCATE_DIM`; the free models are 2048-dim and Lucene 9.11's cap is 1024). `synquest` truncates at index and query time, and `/index/stats` reports vector coverage.
- The GPU-7 catalog has all three free embedding arms, and a least-privilege `synanton-benchmark` principal covers the six benchmark tenants (G3). The harness paces searches, enforces a daily request budget, checks spend via gpu-runtime, and refuses to report invalid runs. synquest caches query embeddings (G4).
- **Dense and hybrid runs have happened on GPU-5** (benchmark plan §6 Phase B1-K), with bge-base via Gateway → Envoy → TEI:
  - T02 dense: Recall@10 0.833, NDCG@10 0.736;
  - T03 hybrid: 0.900 / **0.789**;
  - T04-v2 semantic + hybrid: 0.900 / 0.789.
  - Hybrid beats BM25 (T01, NDCG 0.756) and dense alone.
  - **B2.1 reranking (T10):** synquest reranks fused candidates with `synanton-qwen3-reranker-0.6b` on GPU-5 (Qwen3 prompt template required). NDCG@10 rises from 0.859 to **0.893** (fixed) and from 0.856 to 0.871 (semantic), and PDF MRR from 0.875 to 0.967. The cost is about 1.5 s per query for 50 candidates.
  - **T-INT-3 (25 queries incl. 15 on the PDFs):** hybrid is best under both chunking strategies (NDCG@10 0.859 fixed / 0.856 semantic). Fixed vs semantic is within noise; the differing queries point at hierarchical chunking (T05) and reranking (T10).
  - The reranker rows (T10/T11) need the B2 `RerankerPort`; the GPU-5 reranker itself works.
- The old `results/T03.yaml` (all 0.0) is superseded. Model state: Qwen3 weights in place; `bge-base-en-v1.5` and the `bge-small-en-v1.5` fallback complete on all nodes.

---

## Retrieval benchmark evidence (2026-10-11)

Measured on live stacks (TEI bge-base-768d / bge-small-384d on the GPU plane;
Lucene, Milvus, Qdrant, PG+pgvector, YDB backends; RRF hybrid). Recall@10:

**Demo corpus** — 16 docs, 28 human-confirmed queries:

| Engine | bge-base 768d | bge-small 384d |
|---|---|---|
| Lucene-hybrid (service) | 0.9226 | 0.9167 |
| Lucene-vector (service) | — | 0.7917 |
| Lucene-standalone | 0.7976 | 0.8274 |
| Milvus / Qdrant HNSW | 0.8452 | 0.8810 |
| PG+pgvector / YDB-hybrid | — (384d lock-in) | 0.9821 |

**RAG-Multi-Corpus** — 236 synthetic enterprise docs, 1216 upstream gold queries:

| Engine (bge-small 384d) | Recall@10 |
|---|---|
| Lucene-hybrid | 0.9778 |
| Milvus / Qdrant | 0.9572 |
| Lucene-standalone | 0.9490 |
| PG / YDB | 0.7944 |

Readings (scoped, provisional): engine ranking is corpus-dependent (PG/YDB
lead demo, trail RAGMC); Milvus=Qdrant byte-identical on all corpora;
integration effect zero (service-vector == standalone adapter); blind RRF
burries single-leg hits (tunable union boost, default off; score-fusion
deferred to n≥50). Full record: [selection framework](docs/architecture/vector-engine-selection.md)
(ADR-013 Accepted), [benchmark tracker](docs/implementation/benchmark-tracker.md),
[dataset registry](docs/benchmark/datasets.md).

## Related projects

- **Lucentrix** — ingestion/crawling and distributed-search experiments
- **Resolutor** — dependency-aware conflict and recalculation planning
- **Equalix** — fair scheduling and resource-controlled execution; see the
  [family comparison](https://github.com/synanton/.github/blob/main/profile/experiments/equalix-family-comparison.md)
  for the Spring Boot / Go / Micronaut characterization study
- **Commitix** — durable execution and reliable business workflows
- **GPU Execution Plane** — isolated GPU infrastructure for Synanton
- **Structured Content Extractor** — deployment-neutral structured document extraction

---

## License

Apache 2.0 — see [LICENSE](LICENSE).

## Contact

- Research & general inquiries: research@synanton.org
- Security reports: security@synanton.org
