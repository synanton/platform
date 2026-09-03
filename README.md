# Synanton Platform

**Open-source systems research for enterprise knowledge and reliable business execution.**

Synanton is an AI-native enterprise knowledge platform exploring how enterprise knowledge can be **ingested, structured, secured, derived, searched, reasoned over and recalculated** as a coherent system rather than as a collection of disconnected AI components.

The platform is built as a modular set of services and libraries. The architecture is deliberately experimental: major capabilities are introduced as versioned designs, implemented incrementally and validated through runnable demos, contract tests, benchmarks and failure/security tests.

> **Current architecture:** Design 1.25  
> **Current focus:** annotations, derived knowledge, dependency-aware recalculation, analytics and reporting.

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
| `synapt` | Public REST/gRPC ingress | ✅ |
| `security` | Authentication, authorization and filesystem ACL enforcement | ✅ |
| `topology` | Organization, grants and policy storage | ✅ |
| `synflux-router` | Kafka-based ingestion distribution | ✅ |
| `control-plane` | Administration and model-serving directory | ✅ |
| `synanton-mcp` | MCP protocol bridge | ✅ |
| `annotations` | Annotation registry, provenance and recalculation foundation | 🔶 In progress |
| `analytics` | Events, facts, aggregates, metrics and reports | 🔲 Planned |
| `gpu-contract` | Versioned GPU execution protobuf contract | ✅ |
| `gpu-gateway` | GPU boundary and execution lifecycle | ✅ |
| `extraction-contract` | Versioned structured extraction contract | ✅ |
| `extraction-client` | Platform client and fallback policies | ✅ |
| `synreview` | Human review of low-confidence knowledge | 🔲 Planned |

Supporting components live in sibling repositories, including the GPU execution plane, structured content extractor and Lucentrix ingestion CLI.

---

## Architecture evolution

Synanton intentionally evolves through versioned architecture documents.

| Version | Focus |
|---|---|
| 1.19 | Baseline platform architecture |
| 1.20 | Isolated GPU Execution Plane |
| 1.21 | Structured Content Extraction Plane |
| 1.22 | Semantic Content Structuring / Chunking |
| 1.23 | Secure semantic representations |
| 1.24 | Annotation foundation |
| **1.25** | **Annotations, derived knowledge, recalculation, analytics and reporting** |

The architecture documents are part of the project, not merely implementation notes. They record decisions, constraints, interfaces, failure models, security boundaries and evaluation criteria.

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

GPU runtime is **not** on this path. Ingest embeddings still use `HttpLlmClient` when a GPU is present. Production GPU inference uses `synanton.gpu.v1` (mirrored with `gpu-runtime`); see "Isolated AI execution" above.

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
  control-plane/
  synflux-router/
  synanton-mcp/
  annotations/
  analytics/
  gpu-contract/
  gpu-gateway/
  extraction-contract/
  extraction-client/

ui/
  syntology-admin/

deployment/
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

- [Architecture — Synanton Design 1.25](docs/architecture/synanton-design-1.25.md)
- [Annotations, Recalculation, Analytics & Reporting](docs/implementation/annotations-analytics-plane/INDEX.md)
- [Semantic Chunking](docs/implementation/semantic-chunking/INDEX.md)
- [Structured Content Extraction](docs/implementation/content-extraction-plane/INDEX.md)
- [GPU Execution Plane](docs/implementation/gpu-execution-plane/INDEX.md)

The broader project documentation is maintained separately and explains the architecture, concepts, use cases, operations, integrations and design history.

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

The core ingestion, retrieval, graph, ontology, security, MCP, GPU-contract, extraction-contract and semantic-chunking foundations are implemented to varying degrees. Annotation/recalculation work is underway, while the analytics and reporting plane is the next major implementation area.

The project is not presented as a finished enterprise product. The repository is intentionally used to explore architecture, implementation techniques, operational boundaries and measurable trade-offs.

---

## Related projects

- **Lucentrix** — ingestion/crawling and distributed-search experiments
- **Resolutor** — dependency-aware conflict and recalculation planning
- **Equalix** — fair scheduling and resource-controlled execution
- **Commitix** — durable execution and reliable business workflows
- **GPU Execution Plane** — isolated GPU infrastructure for Synanton
- **Structured Content Extractor** — deployment-neutral structured document extraction

---

## License

Apache 2.0 — see [LICENSE](LICENSE).

## Contact

- Research & general inquiries: research@synanton.org
- Security reports: security@synanton.org
