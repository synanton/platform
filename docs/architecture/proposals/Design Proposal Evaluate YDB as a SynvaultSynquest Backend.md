# Design Proposal: Evaluate YDB as a Synvault/Synquest Persistence Backend

**Status:** Draft for Architecture Review (Revision 6 — final polish)
**Governing document:** Synanton Platform Architecture 1.0 (Approved — capstone)
**Scope:** Persistence port and YDB adapter for Knowledge 1.25 and Search 1.31
**Candidate:** YDB 26.3.x; exact server and Java SDK versions to be pinned in Phase 0
**Related platform designs:** 1.22 (original definitions), 1.23 (Security), 1.25 (Knowledge), 1.26 (Content Cache), 1.27 (Eventing), 1.28 (Ingestion), 1.29 (Identity), 1.31 (Search), 1.32 (Operation/error contracts), 1.34 (Temporal Versioning)
**Non-goals:** Replacing MinIO object storage, replacing the graph engine, replacing all PostgreSQL/other service databases, redefining ownership or semantics established by the designs above.

------

## 0. Architectural Dependency

This proposal is an **implementation/persistence-layer proposal** under Synanton Platform Architecture 1.0. It does not redefine ownership or semantics established by Designs 1.22, 1.23, 1.25, 1.26, 1.27, 1.28, 1.29, 1.31, 1.32, or 1.34.

Where this proposal concerns security, eventing, canonical knowledge, source versioning, temporal semantics, or search behavior, **the corresponding normative platform design governs**.

In case of conflict between this proposal and any approved platform design, the approved design prevails.

### 0.1 Sequencing constraint (Architecture 1.0 §14–15)

Architecture 1.0 §14–15 states that no plane may implement ahead of frozen **1.27 (Eventing)** and **1.32 (Operation/error contracts)**. This proposal acknowledges that constraint and structures its phases accordingly:

- **Phase 0 (interface extraction)** may proceed now, but any API surface that touches Eventing 1.27 or the Operation/error contract 1.32 must be treated as **provisional** and may change once 1.27/1.32 are frozen.
- **Phase 1–2 (YDB adapters)** must not start until:
  - 1.27 event schema is frozen;
  - 1.32 Operation/error contracts are frozen.
- If 1.27/1.32 are not frozen when the PoC would otherwise begin, the PoC is either (a) deferred, or (b) explicitly scoped as **throwaway** with no pre-commitment of domain APIs.

This constraint is a **hard gate** on the PoC, not a soft preference.

------

## 1. Summary

This proposal has two related but independent objectives.

### Objective A — Establish stable persistence and retrieval ports

Define stable Synanton interfaces for:

1. **`SynvaultStore`** — a **persistence port** used by Knowledge 1.25 to persist canonical knowledge (documents, chunks, metadata, provenance, storage revisions).
2. **`SynquestEngine`** — a **retrieval port** used by Search 1.31 for lexical, vector, and hybrid retrieval over derived search projections.
3. **`SynquestIndexWriter`** and **`SynquestIndexAdmin`** — projection-mutation and index-lifecycle ports used by Search 1.31.

The domain and application layers must depend on these ports rather than on Cassandra, YDB, or another storage/search implementation.

### Objective B — Evaluate YDB as one implementation

Evaluate YDB as a candidate implementation behind these ports.

This proposal does **not** make a production migration decision. The YDB PoC must establish whether YDB meets Synanton requirements for:

- transactional persistence of canonical knowledge;
- lexical, vector, and hybrid retrieval;
- **pre-ranking security and tenant eligibility**;
- index update behavior;
- recall, latency, throughput;
- operational characteristics;
- cost.

The primary architectural decision is:

> **Define persistence and retrieval ports first, then evaluate YDB as one adapter behind those ports.**

YDB is not proposed as a universal storage replacement. MinIO remains object storage; `Relix` remains the graph engine; Eventing 1.27 remains the async fabric.

------

## 2. Relationship to Platform Architecture 1.0

### 2.1 Plane mapping

| Proposal component                           | Architecture plane                          | Authority                                                    |
| -------------------------------------------- | ------------------------------------------- | ------------------------------------------------------------ |
| `SynvaultStore`                              | Knowledge 1.25 (persistence)                | Persists canonical knowledge owned by Knowledge 1.25. Does not own source-version authority. |
| `SynquestEngine`                             | Search 1.31 (retrieval)                     | Reads derived search projections owned by Search 1.31. Never authoritative. |
| `SynquestIndexWriter` / `SynquestIndexAdmin` | Search 1.31 (projection mutation/lifecycle) | Applies derived-projection changes consumed from Eventing 1.27. |
| Publication-log mechanism (internal)         | Adapter-internal                            | Bridges a Synvault persistence transaction to Eventing 1.27. Not a competing event substrate. |

### 2.2 Ownership statement

> **`SynvaultStore` is a persistence port, not a competing domain authority.** It persists state owned by the relevant platform plane. Source-version authority remains with **Ingestion 1.28**; canonical knowledge remains owned by **Knowledge 1.25**; derived search projections remain owned by **Search 1.31**.

Search remains a **derived projection**: never a second source of truth.

### 2.3 Eventing is authoritative

Event delivery, retry, ordering, replay classification, and consumer idempotency are governed by **Design 1.27**. This proposal does not define an independent asynchronous contract.

### 2.4 Security is authoritative

Tenant scope and authorization derive from a **validated identity context** (Identity 1.29 + Security 1.23). Storage-level tenant keys are an implementation mechanism, not the source of authorization truth. Search eligibility is enforced **before ranking**, as required by Search 1.31.

### 2.5 Temporal semantics are authoritative

Where Knowledge 1.25 persists versioned state, temporal semantics follow **Design 1.34** (source version, version series, publication time, observation time, validity intervals, multiple simultaneously eligible current versions). Storage revisions in this proposal are an internal optimistic-concurrency mechanism and are **not** the platform's semantic version.

### 2.6 Terminology reconciliation with Design 1.22

Design 1.22 (`synanton-design-1.22.md:558,562`) used the names `synvault` and `synquest` with different meanings:

| Name       | Design 1.22 meaning                                          | This proposal's meaning                                      |
| ---------- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| `synvault` | Storage content-abstraction + tiering (S3/Glacier). Part of the object-storage / content-cache evolution. | Persistence **port** used by Knowledge 1.25 for canonical knowledge. **Does not include object storage.** |
| `synquest` | Rust/Tantivy search kernel. A concrete search component.     | Retrieval **port** used by Search 1.31. Implementation-agnostic. |

**Why the boundary moved.** Architecture 1.0 reassigned content-artifact responsibility to **Content Cache 1.26** and separated canonical knowledge (1.25) from retrieval (1.31). The original `synvault` (object storage) and `synquest` (Tantivy kernel) no longer describe the platform's ownership model:

- Object storage is now Content Cache 1.26 and is explicitly **out of scope** for this proposal (non-goal).
- The retrieval kernel is now one possible implementation behind `SynquestEngine`; a Rust/Tantivy kernel could still exist as an adapter.

**Recommendation on naming.** To avoid further drift, this proposal uses `SynvaultStore` and `SynquestEngine` (suffixed) to make clear these are **ports**, not the historical components. If the architecture review prefers, they may be renamed (e.g., `KnowledgePersistencePort` / `RetrievalPort`) in a follow-up editorial pass without changing semantics.

------

## 3. Existing Synanton Architectural Precedent

The ports/adapters approach follows an established Synanton platform pattern:

```text
GpuRuntime
  -> CudaGpuRuntime / RocmGpuRuntime / InMemoryGpuRuntime

ContentExtractor
  -> TikaContentExtractor / PdfBoxContentExtractor / ...

SynvaultStore
  -> CassandraSynvaultStore / YdbSynvaultStore / InMemorySynvaultStore

SynquestEngine
  -> CassandraSynquestEngine / YdbSynquestEngine / InMemorySynquestEngine
```



Conventions carried over:

- stable domain-facing ports;
- provider-specific implementations in separate modules;
- provider selection through configuration;
- capability discovery for infrastructure validation;
- testable implementations behind common contracts.

Any lessons learned from the existing capability-discovery implementations should be applied consistently rather than creating a storage-specific mechanism.

------

## 4. Motivation

Today, Cassandra is used for content metadata, semantic chunks, and hybrid search indexing. This creates several architectural concerns:

- Domain services may depend on Cassandra-specific concepts.
- Search logic is coupled to Cassandra's vector/search implementation.
- Consistency semantics are backend-specific.
- Replacing Cassandra later would require invasive changes.

The architectural response is independent of the YDB decision:

> **The domain should depend on Synanton storage/search semantics, not on the physical database.**

YDB is a candidate because current YDB releases combine distributed transactional storage with native full-text, vector, and hybrid-search capabilities in a single system, which maps unusually well to the specific experiment of consolidating Synvault and Synquest persistence behind one operational platform.

Feature availability alone does not establish suitability. The PoC must validate the actual workload.

------

## 5. Goals

### 5.1 Architecture

- Define `SynvaultStore`, `SynquestEngine`, `SynquestIndexWriter`, `SynquestIndexAdmin` ports.
- Refactor Cassandra access behind adapters.
- Add in-memory implementations for tests.
- Add YDB implementations as PoC candidates.
- Ensure domain/application modules have no direct Cassandra or YDB dependencies.
- Provide contract tests shared by all implementations.
- Prevent CQL/YQL leakage outside adapter modules.

### 5.2 YDB evaluation

Evaluate:

- document and chunk persistence;
- transactional document revision commits;
- metadata filtering;
- provenance storage;
- storage-revision concurrency;
- lexical/BM25 retrieval;
- vector ANN retrieval;
- hybrid retrieval;
- **pre-ranking security and tenant eligibility**;
- complex metadata filtering;
- index update latency;
- recall@10;
- p50/p95/p99 latency;
- throughput;
- resource consumption;
- operational complexity;
- total cost of ownership.

### 5.3 Scope boundary

The YDB `SynquestEngine` PoC evaluates only the **lexical/vector/hybrid subset** of Search 1.31.

**Explicitly out of scope for this PoC:**

- **graph retrieval** (owned by Search 1.31 and delegated to `Relix`);
- **temporal retrieval** (owned by Search 1.31 under Design 1.34);
- **version-series eligibility** as a search candidate constraint beyond the extension point defined in §10.2.

The `SynquestEngine` port is designed to accommodate graph and temporal retrieval without redesign; see §10.2.

### 5.4 Migration readiness

If the PoC succeeds:

- document Cassandra → YDB migration tooling;
- define shadow-read or dual-write strategy;
- document rollback;
- preserve the Cassandra adapter until migration is complete.

------

## 6. Non-Goals

- Replacing MinIO or S3-compatible object storage.
- Replacing Content Cache 1.26.
- Replacing `Relix` graph computation.
- Migrating all PostgreSQL or other service databases.
- Deciding that YDB replaces ClickHouse.
- Redefining Eventing 1.27, Identity 1.29, Security 1.23, or Temporal Versioning 1.34 semantics.
- Redefining the Operation/error contract (Design 1.32).
- Committing to production YDB before PoC results.
- Designing the entire Synanton persistence model around YDB-specific features.
- Implementing any adapter ahead of frozen 1.27 and 1.32 contracts (see §0.1).

------

## 7. Architecture Principles

### 7.1 Ownership follows Architecture 1.0

```text
Ingestion 1.28
    owns SourceVersion / VersionSeries
             ↓
Knowledge 1.25
    owns canonical knowledge / chunks / derived knowledge
             ↓
Synvault persistence port
    physical persistence implementation
             ↓
Cassandra / YDB / ...
             ↓
Search 1.31
    owns derived search projections
             ↓
Synquest retrieval port
```



**Note on the diagram.** The vertical arrangement above shows ownership, not data flow. Data flow from Synvault to Search is **asynchronous and mediated by Eventing 1.27**: Synvault commits a durable publication record, Eventing 1.27 delivers events, and the projection consumer applies them through `SynquestIndexWriter`. There is no synchronous Synvault → Search call path. See §7.2.

### 7.2 Synvault is authoritative for persisted Knowledge state; Synquest is derived

```text
                 Synvault (Knowledge 1.25 persistence)
              source of truth for canonical state
                   |
             committed storage revision
                   |
             durable publication record
                   |
                 Eventing 1.27
                   |
             projection consumer
                   |
                   v
             Synquest (Search 1.31 projection)
```



A search index must not become a second source of truth. Search visibility may be eventually consistent.

### 7.3 Ports before providers

Domain code must not know whether the implementation is Cassandra, YDB, in-memory, or a future dedicated vector/search engine.

### 7.4 Capability discovery must not become backend leakage

Capabilities are claims, not descriptive metadata. They are used for deployment validation, diagnostics, and feature negotiation, and are subject to the conformance principle in §9.3.

------

## 8. Proposed Architecture: Hide Storage Implementation

### 8.1 Layering

```text
+------------------------------------------------------+
|                  Synanton Domain                     |
|  (Knowledge 1.25 / Search 1.31 application layers)   |
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

```text
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

- `*-api` contains ports, domain-facing DTOs, capability contracts, and exceptions.
- `*-cassandra`, `*-ydb`, and `*-inmemory` contain implementation-specific code.
- Domain modules depend only on `*-api`.
- No CQL outside Cassandra adapters.
- No YQL outside YDB adapters.
- Provider selection happens at startup/configuration.
- Contract tests run against every implementation.

### 8.3 Observability and Operational Contract

Every adapter must expose:

- active adapter/provider name and implementation version;
- request/write/read error counts and latency distributions;
- Synvault transaction failures and retries;
- publication-record backlog depth and oldest-record age;
- Synvault commit → Synquest search-visible freshness/lag;
- index build/rebuild status and duration;
- tracing that follows a revision through `Synvault → Eventing 1.27 → indexer → Synquest`;
- adapter-specific health/readiness signals.

Metrics and traces use provider-neutral names at the platform boundary.

### 8.4 Security and Multi-Tenancy

`SynvaultStore` and `SynquestEngine` **consume an already validated security and tenant context** defined by Identity 1.29 and Security 1.23. Storage-level tenant keys are an implementation mechanism, not the source of authorization truth.

Invariants:

- Security context cannot weaken across async boundaries (per Architecture 1.0).
- **Security eligibility is applied during candidate generation, before ranking.** The YDB adapter must not rely on post-ranking filtering.
- Ranking cannot override authorization.
- Metadata side channels (highlights, facets, autocomplete, counts) obey the same eligibility rule.
- Temporal eligibility (when in scope) is a pre-ranking constraint, not a post-filter.
- Security context is **explicit in the API surface** of both `SynvaultStore` and `SynquestEngine`; it is not inferred from ambient state.

Each adapter must document where isolation is enforced:

- eligibility predicate composed into the query before ranking;
- schema/keyspace/index isolation; or
- an equivalent database-native mechanism aligned with the above.

Minimum security contract:

- encryption in transit for all adapter/database connections;
- encryption at rest per deployment baseline;
- audit logging for security-relevant reads/writes/deletes/index operations;
- explicit data-residency constraints;
- cross-tenant leakage tests covering reads, writes, lexical/vector/hybrid search, filtering, side channels, index rebuild, and replay paths, **under load**.

------

## 9. Interface Design

### 9.1 SynvaultStore

`SynvaultStore` is the persistence port used by Knowledge 1.25.

Responsibilities:

- persist documents, chunks, metadata, provenance, storage revisions;
- retrieve by ID;
- retrieve chunks by document, **with explicit pagination**;
- support required filtering;
- provide the transaction boundary required by the domain;
- consume an explicit, validated security context.

```java
public interface SynvaultStore {

    CompletionStage<Document> putDocument(
        SecurityContext context,
        Document document,
        DocumentWriteOptions options
    );

    CompletionStage<Optional<Document>> getDocument(
        SecurityContext context,
        DocumentId id
    );

    CompletionStage<Void> deleteDocument(
        SecurityContext context,
        DocumentId id
    );

    CompletionStage<ChunkPage> getChunks(
        SecurityContext context,
        DocumentId documentId,
        ChunkQuery query,
        PageRequest page
    );

    CompletionStage<Void> putDocumentRevision(
        SecurityContext context,
        DocumentRevision revision,
        RevisionWriteOptions options
    );

    StoreCapabilities capabilities();
}
```



#### DocumentRevision is the atomicity unit

The atomicity contract is enforced at the **type level**, not in prose. `DocumentRevision` is defined as:

```java
public record DocumentRevision(
    Document document,
    List<Chunk> chunks,
    List<ProvenanceRecord> provenance,   // mandatory, non-empty
    RevisionMetadata metadata,           // storageRevision, authoring identity, etc.
    PublicationIntent publication        // durable publication record payload
) {}
```



The atomicity contract:

```text
putDocumentRevision(context, revision, options) is atomic over:
    revision.document
  + revision.chunks
  + revision.provenance
  + revision.publication
```



- All four are committed atomically when the backend supports the required transaction semantics.
- **Provenance is mandatory** (Architecture 1.0 invariant 12). A `DocumentRevision` with empty provenance is rejected by the port contract and by all adapters.
- Chunk-level writes not part of a revision are not required to be atomic with each other.
- Cross-document writes are not part of the base atomicity contract.
- An adapter that cannot satisfy the required atomicity contract must report incompatibility during startup validation; it must not silently weaken the domain contract.
- On commit failure or client disconnect mid-commit, the result is either a fully committed revision or no revision at all.

#### `putDocument` is metadata-only

`putDocument` is a **distinct, narrow operation**. It:

- writes **only** document-level metadata (title, source reference, non-derived attributes);
- **never** writes chunks, provenance, or a publication record;
- **never** substitutes for `putDocumentRevision`;
- **never** triggers downstream projection or events.

Any write that produces derived knowledge, chunks, or provenance **must** go through `putDocumentRevision`. This restriction is enforced at the port level (no overload that accepts chunks) and validated by contract tests. Adapters must not implement `putDocument` as a degenerate `putDocumentRevision` with empty chunks or provenance.

#### Pagination

`getChunks` returns a `ChunkPage` and requires an explicit `PageRequest`. The contract requires stable ordering and cursor-based pagination so that invariant 28 (query robustness) can be verified.

### 9.2 StoreCapabilities

```java
public record StoreCapabilities(
    boolean supportsTransactions,
    ConsistencyLevel consistency,
    boolean supportsJsonFilters,
    boolean supportsStorageRevisions,
    boolean supportsProvenance,
    boolean supportsCursorPagination
) {}
```



Capability enforcement requires a mechanism, not only a policy:

- an ArchUnit (or equivalent) rule must prohibit domain/application modules from calling `capabilities()` outside designated `*Configuration` / `*Provider` classes;
- contract tests must execute for every capability combination claimed by each adapter;
- provider-selection/configuration code rejects an incompatible adapter at startup;
- the code-review checklist checks that capability checks have not been introduced into domain behavior.

### 9.3 Capability claims require conformance evidence

Consistent with Platform Architecture 1.0 §13:

> **A capability claim without conformance evidence is not a supported claim.**

Each capability flag is a claim gated by the adapter's own conformance/contract test suite before production enablement. Until conformance evidence exists, the flag must be reported as `false` (or `UNVERIFIED`, if the record supports it).

------

## 10. Synquest Interface

### 10.1 Search API

`SynquestEngine` is the retrieval port used by Search 1.31. It is **purely query-facing**.

```java
public interface SynquestEngine {

    CompletionStage<SearchResult> search(
        SecurityContext context,
        SearchRequest request
    );

    SearchCapabilities capabilities();
}
```



`SearchRequest`:

```java
public record SearchRequest(
    String queryText,
    Optional<float[]> queryEmbedding,
    Optional<EmbeddingModelRef> embeddingModelRef,  // model id + version + digest
    SearchMode mode,                                 // LEXICAL, VECTOR, HYBRID
    EligibilityConstraints eligibility,              // pre-ranking, mandatory (see §10.2)
    RelevanceFilters filters,                        // metadata, optional, ranking-time
    TemporalExtension temporal,                      // temporal pre-ranking constraints (§10.3)
    int topK,
    double minScore
) {}
```



`EmbeddingModelRef` carries model id, version, and content digest; the digest is retained as provenance per invariant 31.

### 10.2 Eligibility and temporal composition

The proposal separates three concerns that were previously blurred:

1. **Security eligibility** — tenant membership, principal scope, delegated authority. Mandatory, pre-ranking.
2. **Temporal eligibility** — version-series and validity constraints (Design 1.34). Optional in this PoC, pre-ranking when present.
3. **Relevance filters** — metadata filters that influence ranking and result selection but do not affect candidate eligibility.

Two records model this cleanly:

```java
public record EligibilityConstraints(
    TenantScope tenantScope,             // from validated Identity 1.29 context
    List<PrincipalRef> principals,       // delegations, service identities
    PolicyContext policy,                // Security 1.23 policy reference
    boolean requireExplicitAuthorization // when true, unproven eligibility is a hard failure
) {}

public record TemporalExtension(
    Optional<Instant> asOfPublication,      // published_at
    Optional<Instant> asOfObservation,      // observed_at
    Optional<Interval> validAt,             // [valid_from, valid_to]
    Optional<VersionSeriesId> versionSeriesId,
    Optional<SourceVersionId> sourceVersionId,
    boolean includeAllEligibleVersions      // "current" is a set, not one version
) {}
```



**Composition rules:**

- `EligibilityConstraints` is **security-only**. It never carries temporal fields.
- `TemporalExtension` is **separate**. It is optional in this PoC and empty by default.
- Both are **pre-ranking** constraints. Adapters must compose them into candidate generation before ranking, not as post-filters.
- Adapters that do not support temporal retrieval (`capabilities().temporal() == false`) must **reject** a non-empty `TemporalExtension` at query time rather than silently ignoring it.
- A post-ranking implementation of either constraint is a contract violation.

This composition is the extension point required by §5.3 and satisfies the "no 1.34 redesign" requirement: a later temporal PoC can populate `TemporalExtension` without changing the `SynquestEngine` port shape.

### 10.3 Projection mutation is a separate port

```java
public record ChunkProjection(
    ChunkId chunkId,
    DocumentId documentId,
    String text,
    Map<String, Object> metadata,
    List<Float> embedding,
    EmbeddingModelRef embeddingModelRef,
    long orderingKey,        // monotonic per (tenant, doc, chunk)
    GenerationId generationId // projection generation identifier
) {}

public interface SynquestIndexWriter {

    CompletionStage<Void> upsert(
        List<ChunkProjection> projections
    );

    CompletionStage<Void> delete(
        GenerationId generationId,
        Collection<ChunkId> ids
    );
}

public interface SynquestIndexAdmin {

    CompletionStage<Void> ensureSchema(SchemaOptions options);
    CompletionStage<Void> rebuild(RebuildOptions options);
    CompletionStage<IndexStatus> status();
}
```



- `ChunkProjection` carries both the **monotonic ordering key** and the **generation ID** required by §12.3. The writer can therefore discard out-of-order updates and scope mutations to a specific generation without additional parameters.
- `delete` is generation-scoped, so a rebuild cannot be corrupted by late deletes from the previous generation.
- Consumers read from Eventing 1.27 and apply changes through `SynquestIndexWriter`. Index lifecycle is administered through `SynquestIndexAdmin`.

------

## 11. YDB Implementation Sketch

### 11.1 Candidate schema

```sql
CREATE TABLE documents (
    tenant_id Utf8,
    doc_id Utf8,
    source_uri Utf8,                 -- reference into Content Cache 1.26 or external source
    title Utf8,
    metadata Json,
    storage_revision Uint64,         -- internal optimistic-concurrency, not semantic version
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
    embedding ...,                   -- type/dimension pinned in Phase 0
    PRIMARY KEY (tenant_id, chunk_id)
);

CREATE TABLE provenance (
    tenant_id Utf8,
    chunk_id Utf8,
    extractor Utf8,
    source_version_id Utf8,          -- references Ingestion 1.28 SourceVersion
    embedding_model_ref Json,        -- model id + version + digest
    page Uint32,
    start_offset Uint32,
    end_offset Uint32,
    PRIMARY KEY (tenant_id, chunk_id)
);

CREATE TABLE publication_log (
    tenant_id Utf8,
    revision_id Utf8,
    payload Json,
    created_at Timestamp,
    published_at Timestamp NULL,     -- NULL until successfully handed to Eventing 1.27
    PRIMARY KEY (tenant_id, revision_id)
);
```



Notes:

- `storage_revision` is an **internal persistence/concurrency number**, not the platform semantic version. Semantic versioning (`SourceVersionId`, `VersionSeriesId`, `published_at`, `observed_at`, `valid_from`, `valid_to`) is owned by Ingestion 1.28 / Knowledge 1.25 / Design 1.34 and persisted as domain fields, not by this table.
- The exact YDB vector column/index syntax, dimension, distance metric, index kind, and parameters must be finalized against the selected YDB 26.3.x release in Phase 0. Whether the dimension is fixed at table creation (and therefore constrains embedding-model migrations) must be documented and carried into acceptance.
- The YDB Java SDK version and its maturity/support status must be recorded.
- Index creation strategy, online/offline build behavior, build/rebuild duration, resource consumption, and impact on concurrent traffic must be measured.

This schema is a **PoC starting point, not production DDL**.

### 11.2 Boundary with Content Cache 1.26

`SynvaultStore` persists canonical knowledge (chunks, metadata, provenance, storage revisions). It does **not** store original binary artifacts — those remain in Content Cache 1.26. `source_uri` is a reference, not a duplicate; provenance links chunks back to the Content Cache artifact and to the Ingestion 1.28 `SourceVersion` they were derived from.

### 11.3 Indexes to validate

- full-text index on `chunks.text`;
- vector ANN index on `chunks.embedding`;
- secondary/index access by `doc_id`;
- metadata/JSON filtering;
- **eligibility-filtered** vector/hybrid retrieval;
- interaction between eligibility and ranking.

### 11.4 Retrieval capabilities to validate

- lexical BM25/full-text relevance;
- vector ANN retrieval;
- hybrid ranking;
- reciprocal-rank fusion where applicable;
- weighted/linear fusion where applicable;
- metadata filters;
- **pre-ranking eligibility enforcement** (security + tenant);
- **side-channel eligibility** (highlights/counts);
- returned scores;
- explanations, where part of the approved 1.31 parity matrix.

The existence of a feature is not sufficient. Semantics and performance must meet platform requirements.

------

## 12. Consistency Strategy

### 12.1 Write path

```text
       one authoritative Synvault transaction
                     |
       +-------------+-------------+
       |             |             |
    document       chunks       provenance
       |             |             |
       +-------------+-------------+
                     |
         durable publication record
                     |
              Eventing 1.27
                     |
             projection consumer
                     |
              Synquest projection
```



Steps:

1. create/update the document revision;
2. write chunks and metadata;
3. write provenance (mandatory);
4. write a **durable publication record**;
5. commit atomically.

The publication record is then handed to **Eventing 1.27**, which publishes the immutable event. Eventing 1.27 — not this proposal — defines delivery, retry, ordering, replay, and consumer idempotency.

### 12.2 Publication log is an adapter mechanism, not a platform port

The publication log is **not** a domain-facing port and **not** a competing event substrate. It is an adapter-internal mechanism that bridges a Synvault persistence transaction to Eventing 1.27.

Illustrative shape (internal to the persistence adapter):

```java
interface PublicationLog {
    CompletionStage<Void> record(PublicationIntent intent); // transactional

    // Tenant-scoped to preserve SecurityContext across the async boundary.
    // Cross-tenant scanning is not permitted by this interface.
    CompletionStage<List<PublicationIntent>> pending(
        TenantScope tenantScope,
        int limit
    );

    CompletionStage<Void> markPublished(
        TenantScope tenantScope,
        PublicationId id
    );
}
```



- `pending` is **tenant-scoped** by construction. This preserves the security context across the async boundary (Architecture 1.0 invariant 5) and prevents a relay from accidentally crossing tenants.
- If a deployment requires a cross-tenant relay (e.g., for operational reasons), it must be an explicit, separately-authorized component that (a) carries a service-level `SecurityContext`, (b) is audited, and (c) is documented as an architectural exception. The default interface does not permit it.
- A separate **Eventing 1.27 client** consumes `pending(tenantScope, limit)` and publishes. Delivery semantics, retry, ordering, replay classification, and idempotent consumption remain governed by Design 1.27.

For Cassandra, if the existing implementation cannot provide the same atomicity between authoritative state and the publication record, the adapter must expose that limitation explicitly, and the architecture must define accepted delivery semantics rather than silently claiming equivalent guarantees.

**Scope note:** the publication-log mechanism is new infrastructure. Its introduction may require new work in the existing Cassandra adapter; this is not assumed to be a pure refactor. Cassandra publication-log implementation is tracked as a **separate track** with its own estimate and schedule. The YDB PoC's success does not depend on it, and the current Cassandra behavior must be baselined before any change.

### 12.3 Projection generations and regression prevention

Consistent with Architecture 1.0 invariants 35–36:

- **Projection generations are reproducible.** Each index build/rebuild is identified by a **generation ID**. A rebuild produces a new generation; a generation is fully derived from authoritative Synvault state and can be reproduced deterministically.
- **Out-of-order events cannot regress search state.** Each projection update carries a monotonic ordering key per `(tenant, doc, chunk)`. The projection writer applies an update only if the incoming ordering key is greater than the currently applied key. Events with an older key are discarded as no-ops.

Both `orderingKey` and `generationId` are **fields of `ChunkProjection`** (see §10.3), so the port itself carries the information required to enforce regression prevention. Regression prevention is therefore testable at the port contract level, not only inside an adapter.

The ordering key is derived from the Synvault commit sequence (publication record), not from wall-clock time.

### 12.4 Tolerance requirements

The design tolerates:

- duplicate events (idempotent projection writer);
- out-of-order events (monotonic ordering key);
- retries (idempotent);
- indexer restarts (resumable cursor over Eventing 1.27);
- partial projection updates (generation-scoped rebuild);
- complete index rebuilds (new generation).

------

## 13. YDB PoC Plan

### Phase 0 — Interface Extraction and Reconciliation

- Create `synanton-synvault-api`, `synanton-synquest-api`.
- Move Cassandra implementation into adapters.
- Add in-memory implementations.
- Add contract tests.
- Remove Cassandra dependencies from domain/application modules.
- Scope and estimate the new publication-log work for the Cassandra adapter separately from the YDB PoC.

**Phase 0 exit criteria (must be met before Phase 1 begins):**

- domain code is provider-independent;
- **Synquest feature-parity matrix frozen** (no remaining TBDs);
- **frozen benchmark corpus and golden-query set**;
- **absolute acceptance thresholds agreed** (the relative strawman targets below must be replaced by measured baselines);
- **YDB feature stability inventory** complete;
- **scoped and estimated Cassandra publication-log work plan**;
- **Architecture 1.0 §14–15 gate**: confirm 1.27 event schema and 1.32 Operation/error contracts are frozen, or explicitly scope this PoC as throwaway (see §0.1).

#### Feature-parity matrix (to be frozen at Phase 0 exit)

| Feature                              | Current behavior | Requirement  | YDB behavior | Status |
| ------------------------------------ | ---------------- | ------------ | ------------ | ------ |
| BM25/scoring formula                 | TBD              | Must         | TBD          | TBD    |
| Highlight offsets (eligibility-safe) | TBD              | Must         | TBD          | TBD    |
| Sparse+dense fusion                  | TBD              | Must         | TBD          | TBD    |
| Per-tenant index isolation           | TBD              | Must         | TBD          | TBD    |
| **Pre-ranking eligibility**          | TBD              | Must         | TBD          | TBD    |
| Metadata operators                   | TBD              | Must         | TBD          | TBD    |
| Score normalization                  | TBD              | Must         | TBD          | TBD    |
| Result ordering/tie-breaking         | TBD              | Must         | TBD          | TBD    |
| Delete semantics                     | TBD              | Must         | TBD          | TBD    |
| Update semantics                     | TBD              | Must         | TBD          | TBD    |
| Explainability                       | TBD              | May          | TBD          | TBD    |
| Graph retrieval                      | N/A              | Out of scope | —            | —      |
| Temporal retrieval                   | N/A              | Out of scope | —            | —      |

The PoC cannot declare YDB a replacement until every `Must` capability has equivalent semantics or an explicitly approved architectural alternative.

#### Frozen benchmark corpus

- N documents; M chunks; K golden queries;
- relevance labels for Recall@10;
- representative tenant distribution;
- representative metadata cardinalities;
- representative embedding model and dimension;
- **eligibility fixtures**: validated security/tenant contexts for cross-tenant, side-channel, rebuild, and replay tests under load.

The corpus is versioned so Cassandra and YDB comparisons are reproducible.

#### YDB feature stability inventory

For every YDB feature used: exact YDB release, SDK version, GA/Preview/Beta/Experimental status, production-readiness implications, known limitations, upgrade compatibility. Preview/Beta/Experimental dependencies are flagged as production risk.

Re-validated at the end of the PoC, including if the YDB server or Java SDK version changes during the evaluation.

#### Initial benchmark thresholds (strawman — to be replaced at Phase 0 exit)

- p95 lexical latency: ≤ measured Cassandra p95 × 1.20;
- p95 vector latency: ≤ measured Cassandra p95 × 1.20;
- p95 hybrid latency: ≤ measured Cassandra p95 × 1.20;
- Recall@10: no worse than measured baseline by more than 2 percentage points;
- index freshness: no worse than measured baseline by more than 20%;
- error rate: no higher than measured baseline under equivalent load.

### Phase 1 — YDB Synvault PoC

**Precondition:** Phase 0 exit criteria met, including Architecture 1.0 §14–15 gate.

Implement:

- document metadata CRUD (`putDocument` path);
- chunks;
- metadata;
- provenance (mandatory);
- document revisions (atomic per §9.1);
- transactional writes;
- storage revisions (optimistic concurrency);
- tenant isolation via explicit `SecurityContext`;
- pagination (cursor-based);
- representative filtering.

Compare against Cassandra for write throughput, read latency, update latency, transaction behavior, resource consumption.

### Phase 2 — YDB Synquest PoC

**Precondition:** Phase 0 exit criteria met, including Architecture 1.0 §14–15 gate.

Implement and benchmark:

- lexical retrieval;
- vector retrieval;
- hybrid retrieval;
- **pre-ranking eligibility enforcement** (security + tenant);
- metadata filtering;
- eligibility-filtered vector/hybrid retrieval;
- **side-channel eligibility** (highlights/counts);
- temporal extension point rejection behavior (must reject non-empty `TemporalExtension` when `capabilities().temporal() == false`).

Compare against current implementation for Recall@10, p50/p95/p99 latency, QPS, ranking quality, index build time, index update latency.

### Phase 3 — Projection Consistency

Test:

```text
source update
    ↓
Synvault commit
    ↓
durable publication record
    ↓
Eventing 1.27
    ↓
projection consumer
    ↓
search-visible update
```



Measure: **Synvault commit → Synquest search-visible latency** under concurrent writes and searches.

Test:

- updates, deletes;
- retries, stale events, out-of-order events;
- replay, reprocessing;
- generation-scoped rebuild;
- regression prevention (older events discarded);
- tenant-scoped publication-log consumption (no cross-tenant scanning).

### Phase 4 — Scale, Failure, and Cost Testing

Benchmark definition includes: documents, chunks, chunks/document, embedding dimension, metadata cardinality, tenant count, write QPS, search QPS, update/delete rate, topK, eligibility cardinality, filter selectivity.

Test: steady state; ingestion bursts; concurrent search; node failure; restart/recovery; index rebuild; high-cardinality tenants; highly selective filters (≈0.1%, 1%); medium/low-selectivity filters (≈10%, 100%); **cross-tenant leakage under load**; **rebuild/replay paths under load**.

Produce the cost model defined in §15.1.

### Phase 5 — PoC Migration Tooling

Only if YDB passes functional/performance gates:

- Cassandra → YDB migration for the frozen benchmark dataset;
- validation/checksum tooling;
- shadow-read mode where useful;
- optional dual-write experiment;
- rollback procedure for the PoC;
- operational runbook draft.

This phase does not constitute approval for production migration.

### Phase 6 — Decision

Possible outcomes (all retained as explicit options):

1. YDB becomes the implementation for both Synvault and Synquest.
2. YDB becomes the implementation for Synvault only.
3. YDB becomes the implementation for persistence while retrieval remains separate.
4. Cassandra remains the implementation.
5. A dedicated search/vector backend remains necessary for part of Synquest.

Decision based on measured results, not feature availability. Outcomes 2–5 remain explicitly available.

------

## 14. Benchmark Methodology

| Search  | Filter      | Selectivity   | Metrics                |
| ------- | ----------- | ------------- | ---------------------- |
| Lexical | None        | —             | p50/p95/p99, Recall@10 |
| Lexical | Eligibility | 0.1/1/10/100% | p50/p95/p99, Recall@10 |
| Lexical | Metadata    | 0.1/1/10/100% | p50/p95/p99, Recall@10 |
| Vector  | None        | —             | p50/p95/p99, Recall@10 |
| Vector  | Eligibility | 0.1/1/10/100% | p50/p95/p99, Recall@10 |
| Vector  | Metadata    | 0.1/1/10/100% | p50/p95/p99, Recall@10 |
| Hybrid  | None        | —             | p50/p95/p99, Recall@10 |
| Hybrid  | Eligibility | 0.1/1/10/100% | p50/p95/p99, Recall@10 |
| Hybrid  | Metadata    | 0.1/1/10/100% | p50/p95/p99, Recall@10 |

Additional required runs (outside the main matrix):

- **cross-tenant leakage under load** — eligibility-filtered search with concurrent writes and rebuilds;
- **rebuild/replay paths under load** — generation rebuild while search traffic is active;
- **side-channel checks** — highlight/count eligibility under the same conditions.

Also measure: ingestion throughput; update throughput; delete throughput; index build time; index update time; search-visible update latency; CPU; memory; storage; network; operational overhead.

------

## 15. Acceptance Criteria

### Architecture

- All domain modules compile without Cassandra or YDB dependencies.
- CQL exists only in Cassandra adapters; YQL exists only in YDB adapters.
- Contract tests pass for Cassandra, YDB, and in-memory implementations.
- Search contract tests use semantic tolerances rather than byte-for-byte parity.
- Provider selection is configuration-driven.
- Capability-boundary rule passes; startup validation rejects adapters whose claimed capabilities do not satisfy the required contract.
- Feature-parity matrix has no unresolved `Must` capability.
- **Security context is an explicit parameter on all `SynvaultStore` and `SynquestEngine` methods.**
- **`DocumentRevision` type enforces the atomicity unit; provenance mandatory at type level.**
- **`putDocument` is metadata-only; it never writes chunks, provenance, or a publication record.**
- **`EligibilityConstraints` is security-only; `TemporalExtension` is separate and pre-ranking.**
- **`ChunkProjection` carries `orderingKey` and `generationId`; regression prevention is testable at the port.**
- **`PublicationLog.pending` is tenant-scoped by construction.**
- **Architecture 1.0 §14–15 gate satisfied (1.27 and 1.32 frozen) or PoC explicitly scoped as throwaway.**

### Synvault

- Document revision writes satisfy the atomicity contract.
- Storage revisions are deterministic.
- Tenant isolation is demonstrated.
- Cross-tenant leakage tests pass (reads, writes, search, side channels, rebuild, replay) under load.
- Required metadata/provenance queries are supported.
- Provenance is mandatory and validated.
- Cursor-based pagination works with stable ordering.
- `putDocument` cannot write chunks, provenance, or publication records in any adapter.

### Synquest

- Lexical retrieval satisfies required relevance behavior.
- Vector retrieval meets Recall@10 target.
- Hybrid retrieval meets Recall@10 target.
- Eligibility-filtered vector/hybrid retrieval meets latency target.
- Search results expose required score/metadata fields.
- Search-visible update latency is within target.
- Highlights and side channels obey eligibility.
- `EmbeddingModelRef` (model id + version + digest) is retained as provenance per invariant 31.
- Non-empty `TemporalExtension` is rejected cleanly when `capabilities().temporal() == false`.
- Vector dimension migration constraints documented.

### Performance

Explicit thresholds for: p95/p99 search latency; ingestion throughput; update throughput; Recall@10; index update latency; resource utilization. Thresholds agreed before final interpretation.

### Operations

- Active adapter identity is visible.
- Required adapter metrics, tracing, freshness/lag, and health signals are available.
- Recovery behavior documented.
- Index rebuild documented and tested.
- PoC-scope migration and rollback documented.
- Failure/retry behavior tested.
- Operational monitoring requirements identified.

### Cost

- The §15.1 cost model is produced for the target workload.
- Cost per stored document, write, and search is compared against the current Cassandra implementation at equivalent scale.

### Deferred requirements (explicitly out of scope for this PoC)

- Temporal retrieval and correction semantics (Design 1.34) — extension point exists (§10.2).
- Graph retrieval (Search 1.31 / `Relix`).
- Version-series eligibility beyond the extension point.
- ClickHouse replacement.
- PostgreSQL migration.
- MinIO / Content Cache 1.26 replacement.

------

## 16. Risks and Mitigations

| Risk                                                        | Mitigation                                                   |
| ----------------------------------------------------------- | ------------------------------------------------------------ |
| YDB search semantics differ from required Synquest behavior | Validate exact behavior in PoC                               |
| Vector recall/latency insufficient                          | Benchmark against current HNSW implementation                |
| Eligibility filtering changes vector/hybrid performance     | Include eligibility workloads in benchmark matrix            |
| Index update latency too high                               | Measure commit-to-search-visible latency                     |
| Projection regresses due to out-of-order events             | Monotonic ordering key + generation-scoped rebuild; enforced via `ChunkProjection` fields |
| YQL migration effort high                                   | Isolate YDB behind adapter; contract tests                   |
| YDB operational model unfamiliar                            | Failure/recovery and operational PoC                         |
| Cost uncertain                                              | Measure resource consumption; build TCO model (§16.1)        |
| Backend-specific features leak into domain                  | Enforce module dependency rules                              |
| Capability API becomes backend leakage                      | Enforce mechanism in §9.2; §9.3 conformance principle        |
| Cassandra publication-log work exceeds estimate             | Track separately from YDB PoC; scope/estimate in Phase 0     |
| YDB cannot satisfy all search requirements                  | Retain Outcomes 2–5 as explicit Phase 6 options              |
| Analytics conflated with storage decision                   | Keep ClickHouse replacement explicitly out of scope          |
| Vendor lock-in                                              | Ports/adapters; provider-independent domain model            |
| Proposal drifts from Architecture 1.0                       | §0 dependency statement; normative references throughout     |
| PoC pre-commits APIs before 1.27/1.32 frozen                | §0.1 hard gate; Phase 0 exit criteria; throwaway fallback    |
| Terminology drift vs Design 1.22                            | §2.6 reconciliation note; port suffix disambiguation         |
| Cross-tenant scanning via publication relay                 | Tenant-scoped `pending`; cross-tenant relay requires explicit exception |

### 16.1 Cost model

Produce a normalized cost sketch for the target workload:

- cost per 1M documents stored per month;
- cost per 1M writes/updates;
- cost per 1M searches, split by lexical/vector/hybrid where materially different;
- storage growth and replication overhead;
- compute required for indexing, ingestion, and retrieval;
- operational/management overhead where it materially differs from Cassandra, including headcount implications where relevant.

State assumed topology, retention, replication, workload rates, and pricing basis so the comparison is reproducible.

------

## 17. Alternatives Considered

YDB is the **first candidate** because it combines, in one operational platform:

- transactional distributed storage;
- native full-text retrieval;
- native vector ANN retrieval;
- hybrid ranking;
- distributed deployment.

This combination maps unusually well to the specific experiment of consolidating Synvault persistence and Synquest lexical/vector/hybrid retrieval behind one backend.

Established alternatives (PostgreSQL + pgvector, Elasticsearch/OpenSearch, Qdrant, Weaviate, Milvus, Vespa, and Cassandra plus a dedicated search/vector backend) are **not** evaluated in this PoC. They remain fallback comparison candidates if YDB fails a required gate. If YDB fails, the same ports provide the boundary for a subsequent workload-specific comparison without redesigning the domain model.

A full competitive benchmark is out of scope for this document.

------

## 18. Decisions Requested

### Decision 1 — Stable ports

Approve `SynvaultStore`, `SynquestEngine`, `SynquestIndexWriter`, and `SynquestIndexAdmin` as **persistence, retrieval, projection-mutation, and index-lifecycle ports** under Architecture 1.0, with the ownership framing in §0 and §2, including the terminology reconciliation in §2.6.

### Decision 2 — Adapter architecture

Approve the module structure in §8.2.

### Decision 3 — YDB PoC (conditional)

Approve a time-boxed evaluation of YDB 26.3.x as an implementation candidate behind the ports, **conditional on**:

- exact YDB server and Java SDK versions pinned and recorded in Phase 0;
- YDB feature stability inventory completed;
- frozen benchmark corpus and absolute thresholds agreed at Phase 0 exit;
- pre-ranking eligibility and freshness/lag metrics treated as hard gates;
- Architecture 1.0 §14–15 gate satisfied (1.27 and 1.32 frozen) or PoC explicitly scoped as throwaway.

### Decision 4 — No production migration yet

Production migration remains blocked until all of the following criteria are satisfied:

- [ ] required transactional semantics;
- [ ] acceptable persistence performance;
- [ ] acceptable lexical/vector/hybrid retrieval quality;
- [ ] acceptable eligibility-filtered retrieval latency;
- [ ] acceptable index freshness;
- [ ] acceptable operational characteristics;
- [ ] security, encryption, audit, data-residency, and tenant-isolation requirements satisfied and tested, including under load;
- [ ] pre-ranking eligibility enforcement demonstrated;
- [ ] provenance-mandatory and regression-prevention invariants demonstrated;
- [ ] tenant-scoped publication-log consumption demonstrated (no cross-tenant scanning);
- [ ] temporal extension point demonstrated to reject unsupported queries cleanly;
- [ ] cost model produced and acceptable relative to current implementation;
- [ ] feasible PoC-scope migration and rollback;
- [ ] Architecture 1.0 §14–15 gate satisfied for the production path.

### Decision 5 — Relationship to Platform Architecture 1.0

Acknowledge that this proposal is subordinate to Platform Architecture 1.0 and its normative designs (1.22, 1.23, 1.25, 1.26, 1.27, 1.28, 1.29, 1.31, 1.32, 1.34), and that conflicts are resolved in favor of those designs.

### Decision 6 — Cassandra publication-log track

Approve the Cassandra publication-log work as a **separate track** with its own estimate and schedule, independent of the YDB PoC. Approve baselining current Cassandra behavior before any change.

### Decision 7 — Phase 6 outcome options

Retain Outcomes 2–5 (§13 Phase 6) as explicit, independently selectable options at Phase 6, including retaining Cassandra and/or a dedicated vector backend.

------

## 19. Explicitly Out of Scope for This Decision

```text
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

YDB succeeds for Synquest (lexical/vector/hybrid)
        ≠
YDB evaluated for graph or temporal retrieval

YDB supports OLAP
        ≠
YDB replaces ClickHouse

YDB stores metadata
        ≠
YDB replaces MinIO

YDB supports graph-related data
        ≠
YDB replaces Relix

YDB stores chunks
        ≠
YDB replaces Content Cache 1.26

YDB publication log
        ≠
YDB defines event-delivery semantics; Eventing 1.27 governs

YDB PoC approval
        ≠
Pre-commitment of domain APIs before 1.27/1.32 are frozen
```



Each of these would require a separate workload-specific architectural evaluation.

------

## 20. Recommendation

Approve the ports and module split (Decision 1, Decision 2) now, subject to the terminology reconciliation in §2.6.

Approve the YDB 26.3.x PoC (Decision 3) **conditionally**, with the Phase 0 exit criteria and Architecture 1.0 §14–15 gate as hard preconditions. Pin server and SDK versions, freeze the corpus and thresholds at Phase 0 exit, and treat pre-ranking eligibility and freshness/lag metrics as hard gates.

Keep Outcomes 2–5 (Decision 7) as explicit options at Phase 6 — split Synvault/Synquest decisions, retain Cassandra and/or a dedicated vector backend as fallbacks.

The YDB decision should remain empirical. The critical evaluation is whether YDB can provide Synanton's enterprise-knowledge workload with acceptable:

- correctness;
- transactional persistence semantics;
- pre-ranking eligibility enforcement;
- filtered lexical/vector/hybrid retrieval;
- Recall@10;
- p95/p99 latency;
- ingestion and update throughput;
- index freshness and regression prevention;
- operational complexity;
- cost.

If YDB passes these tests, it can become an implementation behind `SynvaultStore` and/or `SynquestEngine` without domain-level redesign.

If it does not, the same ports allow Synanton to retain Cassandra or introduce another specialized implementation without coupling the domain model to the decision.