# Design Proposal: Evaluate YDB as a Synvault/Synquest Backend and Hide Storage Behind Stable Interfaces

**Status:** Final Draft for Architecture Review
**Scope:** Synanton platform — storage/search layer
**Candidate:** YDB 26.3.x; exact server and Java SDK versions must be pinned for the PoC
**Related components:** `Synvault`, `Synquest`, `Relix`, `extraction-gateway`
**Non-goals:** Replacing MinIO object storage, replacing the graph engine, or replacing all PostgreSQL/other service databases.

------

## 1. Summary

This proposal has two related but independent objectives.

### Objective A — Establish stable storage/search ports

Define stable Synanton interfaces for:

1. **Synvault** — persistent storage of semantic documents, chunks, metadata, provenance, and versions.
2. **Synquest** — lexical, vector, and hybrid search over semantic chunks.

The domain and application layers must depend on these interfaces rather than on Cassandra, YDB, or another storage/search implementation.

The proposed architecture is defined in §8, with in-memory implementations for tests and lightweight development.

### Objective B — Evaluate YDB as one implementation

Evaluate YDB as a candidate implementation for Synvault and Synquest.

The proposal does **not** make a production migration decision. The YDB PoC must establish whether YDB meets Synanton requirements for:

- transactional metadata/document storage;
- lexical search;
- vector search;
- hybrid search;
- filtered enterprise search;
- index update behavior;
- recall;
- latency and throughput;
- operational characteristics;
- cost.

The primary architectural decision is therefore:

> **Define Synvault/Synquest ports first, then evaluate YDB as one adapter behind those ports.**

YDB is not proposed as a universal storage replacement. MinIO remains object storage, and `Relix` remains a specialized graph engine.

------

## 2. Existing Synanton Architectural Precedent

The storage abstraction follows an existing Synanton platform pattern rather than introducing a new architectural style.

The proposal should be consistent with the existing `gpu-runtime` and `content-extractor` components:

text

```
GpuRuntime
  -> CudaGpuRuntime / RocmGpuRuntime / InMemoryGpuRuntime

ContentExtractor
  -> TikaContentExtractor / PdfBoxContentExtractor / ...

SynvaultStore
  -> CassandraSynvaultStore / YdbSynvaultStore / InMemorySynvaultStore

SynquestEngine
  -> CassandraSynquestEngine / YdbSynquestEngine / InMemorySynquestEngine
```



The existing components establish the intended platform convention:

- stable domain-facing ports;
- provider-specific implementations in separate modules;
- provider selection through configuration;
- capability discovery for infrastructure validation;
- testable implementations behind common contracts.

`SynvaultStore` and `SynquestEngine` should follow the same module naming and provider-selection conventions. Any lessons learned from the existing capability-discovery implementations should be applied consistently rather than creating a storage-specific mechanism.

------

## 3. Motivation

Today, Cassandra is used for content metadata, semantic chunks, and hybrid search indexing. This creates several architectural concerns:

- Domain services may depend on Cassandra-specific concepts.
- Search logic is coupled to Cassandra's vector/search implementation.
- Consistency semantics are backend-specific.
- Analytical workloads may require a separate backend.
- Replacing Cassandra later would require invasive changes.

The architectural response should be independent of the YDB decision:

> **The domain should depend on Synanton storage/search semantics, not on the physical database.**

YDB is a candidate because current YDB releases provide distributed transactional storage together with native full-text, vector, and hybrid-search capabilities.

However, feature availability alone does not establish suitability for Synanton. The PoC must validate the actual workload.

------

## 4. Goals

### 4.1 Architecture

- Define `SynvaultStore` and `SynquestEngine` interfaces.
- Refactor Cassandra access behind adapters.
- Add in-memory implementations for tests.
- Add YDB implementations as PoC candidates.
- Ensure domain/application modules have no direct Cassandra or YDB dependencies.
- Provide contract tests shared by all implementations.
- Prevent CQL/YQL leakage outside adapter modules.

### 4.2 YDB evaluation

Evaluate:

- document and chunk CRUD;
- transactional document revisions;
- metadata filtering;
- provenance;
- versioning;
- lexical/BM25 search;
- vector ANN search;
- hybrid search;
- tenant filtering;
- complex metadata filtering;
- index update latency;
- recall@10;
- p50/p95/p99 latency;
- throughput;
- resource consumption;
- operational complexity;
- total cost of ownership.

### 4.3 Migration readiness

If the PoC succeeds:

- document Cassandra → YDB migration tooling;
- define shadow-read or dual-write strategy;
- document rollback;
- preserve the Cassandra adapter until migration is complete.

------

## 5. Non-Goals

- Replacing MinIO or S3-compatible object storage.
- Replacing `Relix` graph computation.
- Migrating all PostgreSQL or other service databases.
- Deciding that YDB replaces ClickHouse.
- Using YDB as the primary message queue or stream processor.
- Committing to production YDB before PoC results.
- Designing the entire Synanton persistence model around YDB-specific features.

------

## 6. Architecture Principles

### 6.1 Synvault is authoritative; Synquest is a derived projection

The preferred model is:

text

```
                 Synvault
              source of truth
                   |
             committed revision
                   |
                 outbox
                   |
             projection/indexer
                   |
                   v
                Synquest
             derived search index
```



A search index must not become a second source of truth.

This provides a clean consistency model:

- Synvault owns document/chunk state.
- Synquest owns search projections.
- Search visibility can be eventually consistent.
- Projection updates are idempotent.
- Rebuilding Synquest does not require changing source data.

### 6.2 Ports before providers

Domain code must not know whether the implementation is:

- Cassandra;
- YDB;
- an in-memory implementation;
- or a future dedicated vector/search engine.

### 6.3 Capability discovery must not become backend leakage

Capabilities are useful for deployment validation, diagnostics, and feature negotiation.

They must not turn into pervasive application code such as:

java

```
if (store.capabilities().supportsX()) {
    // Cassandra-specific/YDB-specific behavior
}
```



Core domain semantics should remain common across implementations.

------

## 7. Candidate Evaluation: Cassandra vs YDB

| Criterion        | Cassandra (current)                         | YDB (candidate)                               | Evaluation                                   |
| ---------------- | ------------------------------------------- | --------------------------------------------- | -------------------------------------------- |
| Data model       | Wide-column NoSQL                           | Relational + JSON                             | Validate fit for Synvault                    |
| Consistency      | Existing Cassandra semantics                | Transactional/strong consistency capabilities | Validate required guarantees                 |
| Transactions     | Limited/lightweight transaction model       | Distributed transactions                      | Test document-revision atomicity             |
| Full-text        | Existing/custom implementation              | Native full-text/BM25 capabilities            | Compare relevance and latency                |
| Vector search    | Current HNSW implementation                 | Native vector ANN                             | Compare recall/latency                       |
| Hybrid search    | Custom composition                          | Native hybrid ranking capabilities            | Validate against Synquest semantics          |
| Filtering        | Existing Cassandra/query model              | SQL/YQL + metadata filtering                  | Benchmark enterprise-style filters           |
| Scaling          | Known current behavior                      | Distributed sharding/rebalancing              | Benchmark target topology                    |
| Analytics        | Separate analytical backend may be required | OLAP capabilities exist                       | Outside current migration decision           |
| Ecosystem        | Mature CQL ecosystem                        | YQL/YDB SDK ecosystem                         | Evaluate migration effort                    |
| Operational risk | Known current behavior                      | New platform dependency                       | PoC required                                 |
| Vendor lock-in   | Existing dependency                         | New dependency                                | Ports/adapters mitigate application coupling |

### 7.1 Important distinction

The PoC must distinguish:

1. **Feature exists**
2. **Feature implements the required semantics**
3. **Feature performs adequately at Synanton scale**
4. **Feature is operationally acceptable**

Passing (1) does not imply passing (2)–(4).

### 7.2 Alternatives considered

YDB is the candidate being PoC'd now because the evaluation can test a potential consolidation of transactional storage and search capabilities behind the same Synanton ports. The architecture review should also recognize established alternatives such as PostgreSQL + pgvector, Elasticsearch/OpenSearch, Qdrant, Weaviate, Milvus, and Vespa, as well as keeping Cassandra plus a dedicated search/vector backend. These alternatives are not evaluated in this PoC; if YDB fails a required gate, the same ports provide the boundary for a subsequent workload-specific comparison.

------

## 8. Proposed Architecture: Hide Storage Implementation

### 8.1 Layering

Use a ports-and-adapters / hexagonal architecture.

text

```
+------------------------------------------------------+
|                  Synanton Domain                     |
|                                                      |
|  DocumentRevision     Chunk     SearchRequest        |
+----------------------------+-------------------------+
                             |
                 +-----------+-----------+
                 |                       |
          SynvaultStore           SynquestEngine
                 |                       |
        +--------+--------+     +--------+--------+
        |        |        |     |        |        |
    Cassandra   YDB    InMemory Cassandra  YDB  InMemory
      adapter  adapter  adapter   adapter adapter adapter
```



### 8.2 Module Structure

text

```
synanton-synvault-api
synanton-synvault-cassandra
synanton-synvault-ydb
synanton-synvault-inmemory

synanton-synquest-api
synanton-synquest-cassandra
synanton-synquest-ydb
synanton-synquest-inmemory

synanton-storage-testkit
```



Rules:

- `*-api` contains interfaces, domain-facing DTOs, capabilities, and exceptions.
- `*-cassandra`, `*-ydb`, and `*-inmemory` contain implementation-specific code.
- Domain modules depend only on `*-api`.
- No CQL outside Cassandra adapters.
- No YQL outside YDB adapters.
- Provider selection happens at startup/configuration.
- Contract tests run against every implementation.

### 8.3 Observability and Operational Contract

Every storage/search adapter must expose enough operational information to identify the active provider and diagnose failures without inspecting provider-specific internals. The minimum contract is:

- active adapter/provider name and implementation version;
- request/write/read error counts and latency distributions;
- Synvault transaction failures and retries;
- outbox backlog depth and oldest-event age;
- Synvault commit-to-Synquest search-visible freshness/lag;
- index build/rebuild status and duration;
- tracing that can follow a revision through `Synvault → outbox → indexer → Synquest`;
- adapter-specific health/readiness signals, including dependency connectivity and schema/index readiness.

Metrics and traces should use provider-neutral names at the platform boundary; provider-specific diagnostics may be added inside adapters.

### 8.4 Security and Multi-Tenancy

Tenant isolation is a platform invariant, not merely a query convention. Each adapter must document where isolation is enforced:

- adapter-level authorization/query construction;
- schema/key-space/index isolation; or
- an equivalent database-native isolation mechanism, if available.

The minimum security contract includes:

- encryption in transit for all adapter/database connections;
- encryption at rest according to the deployment's security baseline;
- audit logging for security-relevant reads, writes, deletes, and administrative/index operations where required by the platform;
- explicit data-residency constraints and deployment-region requirements where applicable;
- cross-tenant leakage tests covering reads, writes, lexical/vector/hybrid search, filtering, and index rebuild/replay paths.

Security controls must be identified as enforced by the platform, adapter, or infrastructure rather than assumed to be equivalent across providers.

------

## 9. Interface Design

### 9.1 SynvaultStore

Synvault is responsible for authoritative semantic-document state.

Responsibilities:

- store documents;
- store chunks;
- store metadata;
- store provenance;
- manage document revisions;
- retrieve by ID;
- retrieve chunks by document;
- support required filtering;
- provide the transaction boundary required by the domain.

A preferred interface shape is:

java

```
public interface SynvaultStore {

    CompletionStage<Document> putDocument(
        Document document,
        WriteOptions options
    );

    CompletionStage<Optional<Document>> getDocument(
        DocumentId id
    );

    CompletionStage<Void> deleteDocument(
        DocumentId id
    );

    CompletionStage<List<Chunk>> getChunks(
        DocumentId documentId,
        ChunkQuery query
    );

    CompletionStage<Void> putDocumentRevision(
        DocumentRevision revision,
        WriteOptions options
    );

    StoreCapabilities capabilities();
}
```



The transaction boundary is part of the domain contract; it must not be left for each adapter to define.

#### Required atomicity contract

The minimum `SynvaultStore` contract is:

text

```
putDocumentRevision(
    document,
    chunks,
    provenance,
    outbox event
) is atomic.
```



Specifically:

- The document revision, its chunks, provenance records, and the corresponding projection/outbox event are committed atomically when the selected backend supports the required transaction semantics.
- Chunk-level writes that are not part of a document revision are not required to be atomic with each other.
- Cross-document writes are not part of the base atomicity contract.
- An adapter that cannot satisfy the required atomicity contract must report incompatibility during startup validation; it must not silently weaken the domain contract.
- On commit failure or client disconnect mid-commit, the result must be either a fully committed revision or no revision at all. Partial persistence of a revision is a contract violation.

This is important because otherwise the least capable adapter establishes the effective platform semantics and YDB's stronger transaction model cannot be used safely.

A document revision may contain:

text

```
Document
  + metadata
  + chunks
  + provenance
  + version
  + outbox event
```



`putDocument` is a convenience operation for document state that is not creating a revision with chunks/provenance. It must not be used as an implicit alternative to the revision transaction contract; revision writes use `putDocumentRevision` and the atomic boundary defined above.

The adapter must implement this transaction boundary or fail startup validation; it must not redefine the atomicity semantics.

### 9.2 StoreCapabilities

Capabilities should describe infrastructure characteristics, not expose provider-specific behavior.

java

```
public record StoreCapabilities(
    boolean supportsTransactions,
    ConsistencyLevel consistency,
    boolean supportsJsonFilters,
    boolean supportsVersioning,
    boolean supportsProvenance
) {}
```



Capabilities should primarily be used for:

- startup validation;
- diagnostics;
- contract-test selection;
- deployment compatibility checks.

Capability enforcement requires a mechanism, not only a policy:

- an ArchUnit (or equivalent) rule must prohibit domain/application modules from calling `capabilities()` outside designated `*Configuration` / `*Provider` classes;
- contract tests must execute for every capability combination claimed by each adapter;
- provider-selection/configuration code is responsible for rejecting an incompatible adapter at startup;
- the code-review checklist must include a check that capability checks have not been introduced into domain behavior.

Capabilities must not become a mechanism for embedding backend-specific branches in domain logic.

------

## 10. Synquest Interface

### 10.1 Search API

`SynquestEngine` represents the search semantics exposed to the platform.

java

```
public interface SynquestEngine {

    CompletionStage<SearchResult> search(
        SearchRequest request
    );

    CompletionStage<Void> delete(
        Collection<ChunkId> ids
    );

    SearchCapabilities capabilities();
}
```



`SearchRequest`:

java

```
public record SearchRequest(
    String queryText,
    Optional<float[]> queryEmbedding,
    Optional<String> embeddingModelId,
    SearchMode mode,          // LEXICAL, VECTOR, HYBRID
    Map<String, Object> filters,
    int topK,
    double minScore
) {}
```



`SearchCapabilities`:

java

```
public record SearchCapabilities(
    boolean lexical,
    boolean vector,
    boolean hybrid,
    boolean filters,
    boolean highlights,
    boolean explanation // true only when explanation is part of the approved feature-parity contract
) {}
```



### 10.2 Separate index administration from search semantics

Physical index creation and lifecycle should not be part of the core search port.

Where required, use a separate administrative interface:

java

```
public interface SynquestIndexAdmin {

    CompletionStage<Void> ensureSchema(
        SchemaOptions options
    );

    CompletionStage<Void> index(
        List<Chunk> chunks,
        IndexOptions options
    );

    CompletionStage<Void> rebuild(
        RebuildOptions options
    );

    CompletionStage<IndexStatus> status();
}
```



This keeps:

- search semantics in `SynquestEngine`;
- physical index lifecycle in infrastructure/operations.

------

## 11. YDB Implementation Sketch

### 11.1 Candidate Schema

A starting point for the PoC is:

sql

```
CREATE TABLE documents (
    tenant_id Utf8,
    doc_id Utf8,
    source_uri Utf8,
    title Utf8,
    metadata Json,
    version Uint64,
    created_at Timestamp,
    updated_at Timestamp,
    PRIMARY KEY (tenant_id, doc_id)
);

CREATE TABLE chunks (
    tenant_id Utf8,
    chunk_id Utf8,
    doc_id Utf8,
    ordinal Uint32,
    text Utf8,
    token_count Uint32,
    metadata Json,
    embedding ...,
    PRIMARY KEY (tenant_id, chunk_id)
);

CREATE TABLE provenance (
    tenant_id Utf8,
    chunk_id Utf8,
    extractor Utf8,
    page Uint32,
    start_offset Uint32,
    end_offset Uint32,
    PRIMARY KEY (tenant_id, chunk_id)
);
```



The exact YDB vector column/index syntax and parameters must be finalized against the selected YDB 26.3.x release during the PoC. The selected vector type, dimension, distance metric, index kind, and relevant index parameters must be recorded explicitly; dimension must match the benchmark embedding model. Whether the dimension is fixed at table creation (and therefore constrained during embedding-model migrations) must also be documented.

The PoC must record the selected YDB Java SDK version and its maturity/support status.

The PoC must also measure index creation strategy, online/offline build behavior where applicable, initial build duration, rebuild duration, resource consumption during builds, and operational impact on concurrent search/write traffic.

The schema above is therefore a **PoC starting point, not production DDL**.

### 11.2 Indexes to Validate

The PoC must validate:

- full-text index on `chunks.text`;
- vector ANN index on `chunks.embedding`;
- secondary/index access by `doc_id`;
- metadata/JSON filtering;
- tenant filtering;
- interaction between filtering and vector/hybrid search.

### 11.3 Search Capabilities to Validate

The YDB PoC should explicitly test:

- lexical BM25/full-text relevance;
- vector ANN search;
- hybrid ranking;
- reciprocal-rank fusion where applicable;
- weighted/linear fusion where applicable;
- metadata filters;
- highlights;
- returned scores;
- explanations, where explanations are part of the approved Synquest feature-parity matrix.

The existence of these features is not sufficient: Synquest must verify that their semantics and performance meet platform requirements.

------

## 12. Consistency Strategy

The preferred model is:

text

```
                 one authoritative transaction
                           |
             +-------------+-------------+
             |             |             |
          document       chunks       metadata
             |             |             |
             +-------------+-------------+
                           |
                        outbox
                           |
                           v
                    Synquest indexer
                           |
                      idempotent
                           |
                           v
                     search index
```



The write path should:

1. create/update the document revision;
2. write chunks and metadata;
3. write provenance as required;
4. record an outbox event;
5. commit atomically.

The indexer then:

1. reads the event;
2. loads the required authoritative state;
3. updates the search projection;
4. records successful processing;
5. retries failures safely.

### 12.1 Outbox ownership

The outbox is a separate architectural concern and should not be hidden accidentally inside `SynvaultStore`.

Prefer a dedicated port:

java

```
public interface SynvaultOutbox {
    CompletionStage<Void> publish(DocumentRevisionEvent event);
    CompletionStage<List<DocumentRevisionEvent>> readBatch(OutboxCursor cursor, int limit);
    CompletionStage<Void> acknowledge(EventId eventId);
}
```



The implementation may use:

- a transactionally coupled backend outbox when the storage backend supports it;
- a dedicated Kafka/Pulsar-style stream;
- another durable event mechanism.

For YDB, the preferred PoC implementation is a transactionally coupled outbox record so the revision and event share the same atomic transaction.

For Cassandra, if the existing implementation cannot provide the same atomicity, the adapter must expose that limitation explicitly and the architecture must define the accepted delivery semantics rather than silently claiming equivalent guarantees.

The platform contract should therefore distinguish **authoritative revision commit** from **event delivery semantics**.

**Scope note:** `SynvaultOutbox` is new infrastructure. Its introduction may require new work in the existing Cassandra adapter; this is not assumed to be a pure refactor. Cassandra outbox implementation is tracked separately from the YDB implementation and must be explicitly scoped and estimated in Phase 0 before implementation begins. This work is independent of the YDB PoC decision.

The design must tolerate:

- duplicate events;
- out-of-order events;
- retries;
- indexer restarts;
- partial search-index updates;
- complete index rebuilds.

------

## 13. YDB PoC Plan

### Phase 0 — Interface Extraction

- Create `synanton-synvault-api`.
- Create `synanton-synquest-api`.
- Move Cassandra implementation into adapters.
- Add in-memory implementations.
- Add contract tests.
- Remove Cassandra dependencies from domain/application modules.
- Scope and estimate the new `SynvaultOutbox` work for the Cassandra adapter separately from the YDB PoC.

**Deliverables:**

- domain code is provider-independent;
- current Synquest feature-parity matrix;
- frozen benchmark corpus and golden-query set;
- agreed acceptance thresholds;
- YDB feature stability inventory;
- scoped and estimated Cassandra `SynvaultOutbox` work plan.

#### Synquest feature-parity matrix

Before implementing the YDB search adapter, document the actual current Cassandra/Synquest behavior:

| Feature                      | Current behavior                | Requirement | YDB behavior | Status |
| ---------------------------- | ------------------------------- | ----------- | ------------ | ------ |
| BM25/scoring formula         | TBD from current implementation | Must        | TBD          | TBD    |
| Highlight offsets            | TBD                             | Should      | TBD          | TBD    |
| Sparse+dense fusion          | TBD: RRF/weighted/etc.          | Must        | TBD          | TBD    |
| Per-tenant index isolation   | TBD                             | Must        | TBD          | TBD    |
| Metadata operators           | eq/in/range/etc.                | Must        | TBD          | TBD    |
| Score normalization          | TBD                             | Must        | TBD          | TBD    |
| Result ordering/tie-breaking | TBD                             | Must        | TBD          | TBD    |
| Delete semantics             | TBD                             | Must        | TBD          | TBD    |
| Update semantics             | TBD                             | Must        | TBD          | TBD    |
| Explainability               | TBD                             | May         | TBD          | TBD    |

The PoC cannot declare YDB a replacement until every `Must` capability has either equivalent semantics or an explicitly approved architectural alternative.

#### Frozen benchmark corpus

Phase 0 must define a reproducible corpus:

- N documents;
- M chunks;
- K golden queries;
- relevance labels for Recall@10 evaluation;
- representative tenant distribution;
- representative metadata cardinalities;
- representative embedding model and dimension.

The corpus must be versioned so Cassandra and YDB comparisons are reproducible.

#### YDB feature stability inventory

For every YDB feature used by the PoC, record:

- exact YDB release;
- SDK version;
- GA / Preview / Beta / Experimental status;
- production-readiness implications;
- known limitations;
- upgrade compatibility expectations.

A PoC that depends on Preview/Beta/Experimental search functionality must explicitly flag that dependency as a production risk.

The feature-stability inventory must be re-validated at the end of the PoC, including if the YDB server or Java SDK version changes during the evaluation.

#### Initial benchmark thresholds

Final thresholds should be approved in Phase 0, but the PoC starts with these strawman relative targets:

- p95 lexical latency: no worse than current Cassandra p95 × 1.20;
- p95 vector latency: no worse than current Cassandra p95 × 1.20;
- p95 hybrid latency: no worse than current Cassandra p95 × 1.20;
- Recall@10: no worse than current implementation by more than 2 percentage points;
- index freshness: no worse than the agreed current baseline by more than 20%;
- error rate: no higher than the current implementation under equivalent load.

Absolute targets should be added once the current production/test baseline is measured.

### Phase 1 — YDB Synvault PoC

Implement:

- document CRUD;
- chunks;
- metadata;
- provenance;
- document revisions;
- transactional writes;
- versioning;
- tenant isolation;
- representative filtering.

Compare against Cassandra for:

- write throughput;
- read latency;
- update latency;
- transaction behavior;
- resource consumption.

### Phase 2 — YDB Synquest PoC

Implement and benchmark:

- lexical search;
- vector search;
- hybrid search;
- tenant filtering;
- metadata filtering;
- filtered vector search;
- filtered hybrid search.

Compare against the current implementation for:

- Recall@10;
- p50/p95/p99 latency;
- QPS;
- ranking quality;
- index build time;
- index update latency.

### Phase 3 — Projection Consistency

Test:

text

```
source update
    ↓
Synvault commit
    ↓
outbox
    ↓
indexer
    ↓
search-visible update
```



Measure:

**Synvault commit → Synquest search-visible latency**

under concurrent writes and searches.

Test:

- updates;
- deletes;
- retries;
- stale events;
- out-of-order events;
- replay;
- reprocessing.

### Phase 4 — Scale, Failure, and Cost Testing

Use a representative Synanton workload.

The benchmark definition should include:

text

```
documents
chunks
average chunks/document
embedding dimension
metadata cardinality
tenant count
write QPS
search QPS
update/delete rate
topK
filter selectivity
```



Test:

- steady state;
- ingestion bursts;
- concurrent search;
- node failure;
- restart/recovery;
- index rebuild;
- high-cardinality tenants;
- highly selective filters (approximately 0.1% and 1% where the corpus permits);
- medium/low-selectivity filters (approximately 10% and 100% where the corpus permits).

Produce the cost model defined in §16.1.

### Phase 5 — PoC Migration Tooling

Only if YDB passes the preceding functional/performance gates, implement limited PoC-scope migration tooling:

- Cassandra → YDB migration for the frozen benchmark dataset;
- validation/checksum tooling;
- shadow-read mode where useful;
- optional dual-write experiment;
- rollback procedure for the PoC;
- operational runbook draft.

This phase does **not** constitute approval for production migration.

### Phase 6 — Decision

Possible outcomes:

1. YDB becomes the implementation for both Synvault and Synquest.
2. YDB becomes the implementation for Synvault only.
3. YDB becomes the implementation for metadata while search remains separate.
4. Cassandra remains the implementation.
5. A dedicated search/vector backend remains necessary for part of Synquest.

The decision must be based on measured results rather than feature availability alone.

------

## 14. Benchmark Methodology

The benchmark must not reduce the evaluation to raw vector-search latency.

Enterprise search behavior is highly dependent on filtering, tenant isolation, ingestion concurrency, and index freshness.

The minimum matrix should include:

| Search  | Filter   | Selectivity   | Metrics                |
| ------- | -------- | ------------- | ---------------------- |
| Lexical | None     | —             | p50/p95/p99, Recall@10 |
| Lexical | Tenant   | 0.1/1/10/100% | p50/p95/p99, Recall@10 |
| Lexical | Metadata | 0.1/1/10/100% | p50/p95/p99, Recall@10 |
| Vector  | None     | —             | p50/p95/p99, Recall@10 |
| Vector  | Tenant   | 0.1/1/10/100% | p50/p95/p99, Recall@10 |
| Vector  | Metadata | 0.1/1/10/100% | p50/p95/p99, Recall@10 |
| Hybrid  | None     | —             | p50/p95/p99, Recall@10 |
| Hybrid  | Tenant   | 0.1/1/10/100% | p50/p95/p99, Recall@10 |
| Hybrid  | Metadata | 0.1/1/10/100% | p50/p95/p99, Recall@10 |

Filter selectivity must be tested at representative levels, including approximately 0.1%, 1%, 10%, and 100% of the candidate population where the workload permits.

Also measure:

- ingestion throughput;
- update throughput;
- delete throughput;
- index build time;
- index update time;
- search-visible update latency;
- CPU;
- memory;
- storage;
- network;
- operational overhead.

------

## 15. Acceptance Criteria

### Architecture

- All domain modules compile without Cassandra or YDB dependencies.
- CQL exists only in Cassandra adapters.
- YQL exists only in YDB adapters.
- Contract tests pass for all three implementations: Cassandra, YDB, and in-memory.
- Search contract tests use semantic tolerances rather than requiring byte-for-byte result parity.
- Provider selection is configuration-driven.
- The defined ArchUnit/equivalent capability-boundary rule passes, and startup validation rejects adapters whose claimed capabilities do not satisfy the required contract.
- Current Synquest feature-parity matrix has no unresolved `Must` capability.

### Synvault

- Document revision writes satisfy the defined atomicity requirements.
- Versioning semantics are deterministic.
- Tenant isolation is demonstrated.
- Cross-tenant leakage tests pass.
- Required metadata/provenance queries are supported.
- The required document-revision atomicity contract is demonstrated.

### Synquest

- Lexical search satisfies the required relevance behavior.
- Vector search meets the defined Recall@10 target.
- Hybrid search meets the defined Recall@10 target.
- Filtered vector/hybrid search meets the defined latency target.
- Search results expose the required score/metadata fields.
- Search-visible update latency is within the defined target.

### Performance

The PoC must establish explicit thresholds for:

- p95 search latency;
- p99 search latency;
- ingestion throughput;
- update throughput;
- Recall@10;
- index update latency;
- resource utilization.

Thresholds should be agreed before final benchmark interpretation.

### Operations

- Active adapter/provider identity is visible.
- Required adapter metrics, tracing, freshness/lag, and health/readiness signals are available.
- Recovery behavior is documented.
- Index rebuild is documented and tested.
- PoC-scope migration and rollback are documented.
- Failure/retry behavior is tested.
- Operational monitoring requirements are identified.

### Cost

- The §16.1 cost model is produced for the target workload.
- Cost per stored document, write, and search is compared against the current Cassandra implementation at equivalent scale.

------

## 16. Risks and Mitigations

| Risk                                                         | Mitigation                                                   |
| ------------------------------------------------------------ | ------------------------------------------------------------ |
| YDB search features differ from required Synquest semantics  | Validate exact behavior in PoC                               |
| Vector recall/latency is insufficient                        | Benchmark against current HNSW implementation                |
| Filtering significantly changes vector/hybrid performance    | Include filtered workloads in benchmark matrix               |
| Index update latency is too high                             | Measure commit-to-search-visible latency                     |
| Search projection becomes inconsistent                       | Authoritative Synvault + transactional outbox + idempotent indexer |
| YQL migration effort is high                                 | Isolate YDB behind adapter and use contract tests            |
| YDB operational model is unfamiliar                          | Run failure/recovery and operational PoC                     |
| Cost is uncertain                                            | Measure resource consumption and build TCO model (§16.1)     |
| Backend-specific features leak into domain                   | Enforce module dependency rules                              |
| Capability API becomes backend leakage                       | Enforce the mechanism defined in §9.2                        |
| Cassandra adapter outbox implementation is more work than expected | Track it separately from the YDB PoC, scope/estimate it explicitly in Phase 0, and do not assume it is a pure refactor |
| YDB cannot satisfy all search requirements                   | Keep Cassandra and/or allow dedicated search implementation  |
| Analytics requirements are conflated with storage decision   | Keep ClickHouse replacement explicitly outside current decision |
| Vendor lock-in                                               | Ports/adapters and provider-independent domain model         |

### 16.1 Cost model

The PoC should produce a normalized cost sketch for the target workload, at minimum:

- cost per 1M documents stored per month;
- cost per 1M writes/updates;
- cost per 1M searches, split by lexical/vector/hybrid where materially different;
- storage growth and replication overhead;
- compute required for indexing, ingestion, and search;
- operational/management overhead where it materially differs from Cassandra, including operational headcount implications where relevant.

The model should state the assumed topology, retention, replication, workload rates, and pricing basis so the comparison is reproducible.

------

## 17. Decisions Requested

Architecture review should approve the following independently.

### Decision 1 — Stable ports

Approve:

text

```
SynvaultStore
SynquestEngine
```



as platform-facing abstractions.

### Decision 2 — Adapter architecture

Approve:

text

```
synanton-synvault-api
synanton-synvault-cassandra
synanton-synvault-ydb
synanton-synvault-inmemory

synanton-synquest-api
synanton-synquest-cassandra
synanton-synquest-ydb
synanton-synquest-inmemory
```



### Decision 3 — YDB PoC

Approve a time-boxed evaluation of YDB 26.3.x as an implementation candidate, with the exact YDB server and Java SDK versions pinned and recorded in the PoC plan.

### Decision 4 — No production migration yet

Production migration remains blocked until all of the following criteria are satisfied:

- □  

  required transactional semantics;

- □  

  acceptable metadata performance;

- □  

  acceptable lexical/vector/hybrid search quality;

- □  

  acceptable filtered-search latency;

- □  

  acceptable index freshness;

- □  

  acceptable operational characteristics;

- □  

  security, encryption, audit, data-residency, and tenant-isolation requirements are satisfied and tested;

- □  

  cost model produced and acceptable relative to current implementation;

- □  

  feasible PoC-scope migration and rollback.

------

## 18. Explicitly Out of Scope for This Decision

The following must not be inferred from a successful YDB Synvault/Synquest PoC:

text

```
YDB succeeds for Synvault
        ≠
YDB succeeds for Synquest

YDB succeeds for Synquest
        ≠
YDB replaces Cassandra for Synvault

YDB succeeds for Synvault
        ≠
YDB replaces every PostgreSQL database

YDB succeeds for Synquest
        ≠
YDB replaces every search engine

YDB supports OLAP
        ≠
YDB replaces ClickHouse

YDB stores metadata
        ≠
YDB replaces MinIO

YDB supports graph-related data
        ≠
YDB replaces Relix
```



Each of these would require a separate workload-specific architectural evaluation.

------

## 19. Recommendation

Approve a **time-boxed YDB PoC behind stable Synvault/Synquest interfaces**.

The architectural abstraction should proceed independently of the YDB decision because it reduces future storage/search coupling regardless of the PoC outcome.

The YDB decision should remain empirical.

The critical evaluation is not whether YDB has the required feature checklist. It is whether YDB can provide Synanton's actual enterprise-knowledge workload with acceptable:

- correctness;
- transactional semantics;
- filtered lexical/vector/hybrid search;
- Recall@10;
- p95/p99 latency;
- ingestion and update throughput;
- index freshness;
- operational complexity;
- cost.

If YDB passes these tests, it can become an implementation of Synvault and/or Synquest without requiring another domain-level redesign.

If it does not, the same ports allow Synanton to retain Cassandra or introduce another specialized implementation without coupling the domain model to the decision.