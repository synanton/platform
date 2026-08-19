# Synanton Platform — Polyglot Report

## 1. Executive Summary

**Synanton** is an open-source, AI-native enterprise knowledge platform that unifies full-text search, dense vector search, knowledge-graph reasoning, and  ontology management into a single modular system. This report evaluates the platform's current multi-language  ("polyglot") capabilities and provides a strategic roadmap for evolving  it into a truly polyglot-ready system that can be seamlessly consumed,  extended, and operated by teams using diverse technology stacks.

**Current State:** The platform is predominantly Java-based (688KB+ of Java code), with  TypeScript for the admin frontend and shell scripts for operations. While architecturally sound, its polyglot readiness is limited—clients  are effectively constrained to the JVM ecosystem for deep integration.

**Target State:** A platform where:

- Any language can query, ingest, and manage knowledge via idiomatic SDKs
- Components can be implemented in the most suitable language for each concern
- Data flows across language boundaries with zero-copy efficiency
- Business logic can be extended without JVM recompilation

## 2. Current Polyglot Capabilities

### 2.1 What Works Today

| Capability               | Status | Evidence                                     |
| ------------------------ | ------ | -------------------------------------------- |
| **REST API**             | ✅ Full | All services expose REST endpoints           |
| **gRPC API**             | ✅ Full | `synapt` provides gRPC ingress               |
| **MCP Integration**      | ✅ Full | `synanton-mcp` bridges to MCP clients        |
| **Agent-to-Agent (ACP)** | ✅ Full | ACP interface for AI agents                  |
| **TypeScript Frontend**  | ✅ Full | `syntology-admin` React SPA                  |
| **Contract-First SPIs**  | ✅ Full | Hexagonal architecture with clear boundaries |

### 2.2 What's Missing

| Gap                           | Impact                                                       |
| ----------------------------- | ------------------------------------------------------------ |
| **No official SDKs**          | Python/Node/Go teams must hand-roll REST clients             |
| **Java-only internal logic**  | Enrichment, reranking, ontology validation locked to JVM     |
| **No Arrow/Flight export**    | Large subgraphs can't be efficiently consumed by data scientists |
| **No polyglot rule engine**   | Business logic changes require code changes and redeploys    |
| **Monolithic language stack** | All backend services are Spring Boot                         |

### 2.3 Technology Stack Breakdown

Based on the repository structure:

text

```
┌─────────────────────────────────────────────────────┐
│  Java (Spring Boot)     ████████████████████████  │
│  TypeScript/React       ████                      │
│  Shell                  █                         │
│  Dockerfile             █                         │
│  CQL (Cassandra)        █                         │
└─────────────────────────────────────────────────────┘
```



## 3. Polyglot Strategy by Layer

### 3.1 Network Layer — Universal Access

**Current:** REST + gRPC via `synapt`.

**Recommendation:**

- **Publish a Protobuf IDL** for all services (Synflux, Synquest, Relix, Syntology)
- **Generate idiomatic SDKs** using `protoc` plugins:
  - Python (`grpcio-tools`)
  - TypeScript/Node (`ts-proto`)
  - Go (`protoc-gen-go-grpc`)
- **Maintain OpenAPI 3.0 specs** for REST fallback

**Why:** A single source of truth for API contracts enables automatic SDK generation across all major languages.

### 3.2 Data Layer — Zero-Copy Transport

**Current:** JSON over HTTP/gRPC for all data exchange.

**Recommendation:**

- **Implement Apache Arrow Flight** endpoints for:
  - Subgraph exports from Relix
  - Bulk embedding exports from Synflux
  - Large result sets from Synquest
- **Use Arrow RecordBatch** as the canonical format for:
  - Node/edge lists with properties
  - Search result chunks with vectors
  - Ontology term dumps

**Why:** Arrow enables zero-copy data transfer to Python (Pandas), R, Julia, and Go—eliminating serialization overhead for large datasets.

### 3.3 Compute Layer — Embedded Polyglot

**Current:** All enrichment, reranking, and synthesis logic runs in Java.

**Recommendation:**

- **Leverage GraalVM Polyglot** (`org.graalvm.polyglot`) to embed:
  - **Python** for custom enrichment scripts (using `sentence-transformers`, `NetworkX`)
  - **JavaScript** for lightweight reranking rules
  - **R** for statistical analysis in the Gateway
- **Cache polyglot contexts** to eliminate per-request startup overhead

**Why:** Data scientists can write enrichment logic in Python without leaving the platform; no microservice hops required.

### 3.4 Logic Layer — Dynamic Rules

**Current:** SHACL validation in `syntology`, POSIX-backed ACLs.

**Recommendation:**

- **Integrate Open Policy Agent (OPA)** with Rego for:
  - Dynamic access control during graph traversal
  - Tenant-specific ingestion policies
  - Audit rule versioning
- **Support WebAssembly (WASM)** modules for:
  - Custom ontology validation rules
  - Document classification logic
  - Entity resolution strategies
- **Hot-load** rules from the control plane without restart

**Why:** Security teams can audit Rego policies; business analysts can write  WASM rules in Rust/Go/AssemblyScript—all without touching Java code.

### 3.5 Index Layer — Language-Agnostic Search

**Current:** Lucene BM25 + HNSW hybrid in `synquest`.

**Recommendation:**

- **Expose the index as a gRPC service** (Index-as-a-Service)
- **Support multiple index backends** via SPI:
  - Lucene (Java) — current
  - Tantivy (Rust) — for memory-constrained deployments
  - Elasticsearch/OpenSearch — for existing ELK stacks
- **Use per-field analyzers** for multi-language content:
  - `title_en`, `title_zh`, `content_fr` with appropriate tokenizers
- **Provide language detection** at query time to route to correct fields

**Why:** Teams can choose the index backend that fits their infrastructure; multi-language content is properly tokenized and ranked.

### 3.6 Configuration Layer — Declarative Everything

**Current:** Java annotations and XML for configuration.

**Recommendation:**

- **Adopt HCL (HashiCorp Configuration Language)** or **JSONnet** for:
  - Ontology definitions (Syntology)
  - Ingestion pipeline definitions (Synflux)
  - Query plan templates (Planner)
- **Store configurations in Git** with the control plane
- **Support hot-reload** of configurations via the admin API

**Why:** DevOps teams can version-control platform configuration using standard GitOps workflows.

## 4. Module-by-Module Polyglot Roadmap

### Phase 1 — Foundation (Current)

| Module            | Language         | Polyglot Readiness |
| ----------------- | ---------------- | ------------------ |
| `synvault`        | Java             | ❌ Internal only    |
| `synflux`         | Java             | ❌ Internal only    |
| `ingestion-cache` | Java/Cassandra   | ❌ Internal only    |
| `synquest`        | Java/Lucene      | ❌ Internal only    |
| `relix`           | Java/JGraphT     | ❌ Internal only    |
| `syntology`       | Java/Jena        | ❌ Internal only    |
| `planner`         | Java             | ❌ Internal only    |
| `gateway`         | Java             | ❌ Internal only    |
| `synapt`          | Java             | ⚠️ REST/gRPC only   |
| `syntology-admin` | TypeScript/React | ✅ Frontend         |

### Phase 2 — API-First (Next)

| Module         | Action               | Deliverable                       |
| -------------- | -------------------- | --------------------------------- |
| All services   | Publish Protobuf IDL | `.proto` files in `docs/api/`     |
| All services   | Generate SDKs        | Python, TypeScript, Go packages   |
| `synapt`       | Add OpenAPI 3.0      | Swagger UI at `/docs`             |
| `synanton-mcp` | Expand MCP tools     | All platform actions as MCP tools |

### Phase 3 — Data Polyglot (Soon)

| Module     | Action              | Deliverable              |
| ---------- | ------------------- | ------------------------ |
| `relix`    | Arrow Flight export | Subgraph as RecordBatch  |
| `synquest` | Arrow Flight export | Results as RecordBatch   |
| `synflux`  | Arrow Flight import | Bulk embedding ingestion |

### Phase 4 — Compute Polyglot (Future)

| Module      | Action                   | Deliverable               |
| ----------- | ------------------------ | ------------------------- |
| `synflux`   | GraalVM Python embedding | Custom enrichment scripts |
| `gateway`   | GraalVM JS embedding     | Custom reranking rules    |
| `syntology` | WASM validation          | Hot-loaded ontology rules |

### Phase 5 — Policy Polyglot (Future)

| Module          | Action          | Deliverable             |
| --------------- | --------------- | ----------------------- |
| `topology`      | OPA integration | Rego policies for ACLs  |
| `security`      | OPA integration | Dynamic authorization   |
| `control-plane` | GitOps config   | HCL/JSONnet definitions |

## 5. Implementation Guide

### 5.1 Quick Wins (0–3 Months)

1. **Publish Protobuf IDL** — Extract existing gRPC definitions into a shared `proto/` directory
2. **Generate Python SDK** — Use `grpcio-tools` to generate a `synanton` PyPI package
3. **Add OpenAPI Spec** — Annotate REST endpoints with Swagger annotations
4. **Document MCP Tools** — Publish the full MCP tool surface

### 5.2 Medium-Term (3–9 Months)

1. **Arrow Flight for Relix** — Add `/graph/export` endpoint returning Arrow
2. **GraalVM Python Pilot** — Embed Python for one enrichment step in Synflux
3. **OPA Pilot** — Replace one ACL check with OPA Rego policy

### 5.3 Long-Term (9–18 Months)

1. **WASM Rules Engine** — Support hot-loaded validation modules
2. **Multiple Index Backends** — Add Tantivy via JNI or as separate service
3. **Full GitOps Configuration** — All platform configs in HCL

## 6. Comparative Analysis

| Capability           | Synanton (Current) | Competitor A (Elastic) | Competitor B (Neo4j) |
| -------------------- | ------------------ | ---------------------- | -------------------- |
| REST API             | ✅                  | ✅                      | ✅                    |
| gRPC                 | ✅                  | ❌                      | ❌                    |
| Python SDK           | ❌                  | ✅                      | ✅                    |
| TypeScript SDK       | ❌                  | ✅                      | ✅                    |
| MCP Integration      | ✅                  | ❌                      | ❌                    |
| Arrow Export         | ❌                  | ❌                      | ❌                    |
| Polyglot Rules       | ❌                  | ❌                      | ❌                    |
| Multi-Language Index | ❌                  | ✅                      | N/A                  |

**Differentiator:** Synanton's MCP/ACP integrationand gRPC supportare ahead of competitors. The polyglot roadmap would make it the most accessible platform for AI-native teams.

## 7. Risk Assessment

| Risk                     | Mitigation                                                   |
| ------------------------ | ------------------------------------------------------------ |
| **GraalVM complexity**   | Start with one Python use case; measure before expanding     |
| **Arrow adoption**       | Provide fallback JSON for clients not ready for Arrow        |
| **WASM security**        | Use sandboxed WASM runtimes with resource limits             |
| **SDK maintenance**      | Generate SDKs from Protobuf; avoid hand-written code         |
| **Performance overhead** | Cache polyglot contexts; use native image for critical paths |

## 8. Conclusion

Synanton's current architecture is **polyglot-ready by design**—hexagonal services with contract-first SPIs, gRPC/REST/MCP/ACP interfaces, and a clear module map. The foundation is solid.

What's needed is **execution**:

1. **Publish the contracts** (Protobuf, OpenAPI)
2. **Generate the SDKs** (Python, TypeScript, Go)
3. **Add the data pipes** (Arrow Flight)
4. **Enable the extensions** (GraalVM, OPA, WASM)

With these investments, Synanton can evolve from a **Java-native platform** into a **true polyglot knowledge mesh**—where Rust handles graph math, Python drives embeddings, JavaScript tunes  business rules, and Java orchestrates transactional integrity, all  within a single cohesive deployment.