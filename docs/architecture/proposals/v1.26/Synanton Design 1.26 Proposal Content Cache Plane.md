# Synanton Design 1.26 — Content Cache Plane

**Status:** Proposal
**Version:** 1.26
**Date:** 2026-09-05
**Audience:** Architects, platform engineers, developers, SRE, security engineers, data engineers, and system integrators

**Related Designs:**

* Design 1.23 — Security and Content Representation Model
* Design 1.25 — Annotation, Derived Knowledge, Recalculation, Analytics and Reporting Plane
* SNTP-9 / Issue #14 — RAG A/B Testing: Flattened Text vs Semantic Content Extraction

---

## 1. Executive Summary

Synanton Design 1.26 introduces the **Content Cache Plane**: an implementation-independent contract for storing, retrieving, filtering, and lifecycle-managing content extraction outputs between the Content Extraction Plane and downstream knowledge and search processing.

The Content Cache stores derived extraction artifacts such as:

* flattened text;
* semantic content;
* semantic chunks;
* extraction metadata;
* processing-run metadata;
* provenance;
* classification and security metadata;
* payload references for large content;
* cache lifecycle and retention metadata.

The Content Cache is deliberately defined as an **architectural abstraction**, not as a specific database or storage technology.

Possible implementations include:

* Cassandra;
* PostgreSQL;
* S3-compatible object storage;
* filesystem/object storage;
* distributed key-value stores;
* hybrid metadata database plus object storage;
* future specialized implementations.

The architectural contract remains stable regardless of the implementation.

The resulting architecture is:

```text
                    ┌─────────────────────┐
                    │   Source Content    │
                    └──────────┬──────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │ Content Extraction  │
                    │       Plane         │
                    └──────────┬──────────┘
                               │
             ┌─────────────────┼─────────────────┐
             │                 │                 │
             ▼                 ▼                 ▼
       Flattened Text    Semantic Content      Chunks
             │                 │                 │
             └─────────────────┼─────────────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │    Content Cache    │
                    │        Plane        │
                    └──────────┬──────────┘
                               │
               ┌───────────────┼────────────────┐
               │               │                │
               ▼               ▼                ▼
        Search Projection   Knowledge       Benchmark
        / Indexing         Processing       / Evaluation
               │               │                │
               ▼               ▼                ▼
         Vector / BM25    Annotation /      SNTP-9
         / Graph          Derived Knowledge
```

The Content Cache is therefore a **staging and retrieval plane for derived content**, not the authoritative source-content repository and not a search engine.

The primary architectural principle introduced by Design 1.26 is:

> **The Content Cache provides a stable content-artifact contract while allowing the underlying storage implementation, physical layout, lifecycle mechanism, and deployment topology to evolve independently.**

---

# 2. Relationship to Design 1.25

Design 1.26 extends the architecture established by Design 1.25.

Design 1.25 defines:

> Knowledge is derived state, and analytics is derived state over knowledge and platform activity.

Design 1.26 adds an explicit storage and retrieval abstraction for intermediate derived artifacts produced by extraction.

The resulting architecture becomes:

```text
Source Content
      │
      ▼
Extraction
      │
      ▼
Content Artifacts
      │
      ▼
Content Cache
      │
      ├──────────────► Knowledge Processing
      │
      ├──────────────► Search Projections
      │
      ├──────────────► Vector Projections
      │
      ├──────────────► Graph Projections
      │
      └──────────────► Evaluation / Benchmarking
```

Design 1.26 does not replace Design 1.25.

Instead:

* Design 1.25 remains the architectural foundation for derived knowledge, annotation, provenance, recalculation, projections, analytics, and reporting.
* Design 1.26 defines the content-artifact storage and retrieval contract required by those planes.
* Design 1.23 remains normative for security and representation behavior.

---

# 3. Relationship to Design 1.23

Design 1.26 inherits the security model established by Design 1.23.

The Content Cache **must not introduce a security bypass**.

In particular:

1. tenant isolation remains mandatory;
2. authorization remains mandatory;
3. classification is security-relevant metadata;
4. representation selection occurs before downstream publication;
5. masked representations must not expose prohibited original content;
6. `store_original: false` remains authoritative where required;
7. security-sensitive cache entries must be invalidated or reprocessed when applicable policy changes;
8. cache search must enforce authorization;
9. cache APIs must fail closed on authorization uncertainty;
10. administrative access must be explicitly audited.

The Content Cache therefore participates in the same security pipeline as other derived-state components.

---

# 4. Motivation

The extraction pipeline produces artifacts consumed by multiple downstream systems.

Without an explicit abstraction, implementations tend to become coupled to a particular database.

For example:

```text
Extractor
    │
    ▼
Cassandra
    │
    ├── Search Indexer
    ├── Vector Indexer
    └── Benchmark
```

This creates undesirable coupling:

* extractors become Cassandra-aware;
* indexers become Cassandra-aware;
* benchmarks become Cassandra-aware;
* storage migration becomes expensive;
* large payload handling becomes implementation-specific;
* retention and tiering become inconsistent;
* security semantics become dependent on storage queries.

Design 1.26 replaces this with:

```text
Extractor
    │
    ▼
Content Cache Contract
    │
    ├── Cassandra Adapter
    ├── PostgreSQL Adapter
    ├── S3 Adapter
    ├── Filesystem Adapter
    └── Hybrid Adapter
```

Consumers depend on the contract, not the implementation.

---

# 5. Architectural Principles

## 5.1 Storage Independence

No downstream component may depend directly on a particular Content Cache database.

## 5.2 Representation Independence

Consumers request logical representations rather than storage-specific records.

## 5.3 Atomic Publication

A cache entry becomes visible only after its complete payload and required metadata have been persisted and verified.

## 5.4 Security by Construction

Authorization and classification constraints apply to cache retrieval and search.

## 5.5 Derived-State Semantics

Extraction artifacts stored in the Content Cache are derived from source processing and must retain provenance.

## 5.6 Large-Content Safety

The contract must support content larger than the practical record/blob size of a particular database.

## 5.7 Tier Independence

Hot, warm, and cold storage are implementation details behind a stable logical cache interface.

## 5.8 Deterministic Retrieval

Equivalent queries must produce deterministic ordering and pagination semantics.

## 5.9 Rebuildability

Cache contents should be reproducible from source content and processing configuration where the governing retention policy permits.

## 5.10 Search Separation

Metadata filtering in the Content Cache must not become a replacement for Search Projections.

## 5.11 Lifecycle Independence

Retention enforcement is part of the Content Cache lifecycle contract, but its physical enforcement mechanism is implementation-specific.

---

# 6. Content Cache Responsibilities

The Content Cache is responsible for:

* storing extraction artifacts;
* retrieving artifacts by identity;
* retrieving multiple artifacts efficiently;
* reconstructing documents from chunks;
* metadata filtering;
* time-range filtering;
* representation filtering;
* classification-aware filtering;
* processing-run filtering;
* producer/version filtering;
* retention enforcement;
* tier management;
* invalidation;
* lifecycle state;
* provenance preservation;
* capability discovery;
* operational observability.

The Content Cache is not responsible for:

* semantic ranking;
* BM25 ranking;
* vector similarity;
* hybrid retrieval;
* reranking;
* LLM generation;
* annotation interpretation;
* ontology definition;
* authoritative source-content management.

---

# 7. Stored Content

## 7.1 Flattened Content

Flattened content is a linear representation of extracted source material.

Example:

```text
Document
└── flattened_text
       "Introduction ... Section 1 ... Section 2 ..."
```

Flattened content is useful for:

* traditional RAG;
* baseline benchmarking;
* compatibility processing;
* full-text indexing;
* downstream text processing.

---

## 7.2 Semantic Content

Semantic content preserves document structure and semantic boundaries.

Example:

```text
Document
├── title
├── section
│    ├── paragraph
│    ├── paragraph
│    └── table
├── section
└── image
```

Semantic content is useful for:

* semantic chunking;
* structure-aware indexing;
* advanced retrieval;
* knowledge extraction;
* provenance-aware processing.

---

## 7.3 Chunks

Chunks are independently addressable derived content units.

A chunk SHOULD contain:

```yaml
chunk_id: chunk-417
document_id: document-123
representation: semantic
sequence: 417
content: "..."
content_hash: sha256:...
classification:
  - INTERNAL
provenance:
  processing_run_id: run-2026-09-05-001
  producer: pdf-extractor
  producer_version: 1.8.0
```

Chunks are the preferred unit for:

* retrieval;
* embedding;
* indexing;
* annotation;
* incremental processing.

---

## 7.4 Metadata

Cache metadata SHOULD include:

```yaml
cache_entry_id: ...
tenant_id: ...
document_id: ...
document_version: ...
representation: semantic
content_type: application/json
content_hash: ...
content_size_bytes: ...
classification: INTERNAL
processing_run_id: ...
producer_id: ...
producer_version: ...
schema_version: ...
created_at: ...
processed_at: ...
published_at: ...
expires_at: ...
tier: hot
status: PUBLISHED
payload_location: ...
```

---

# 8. Cache Identity

The Content Cache distinguishes **content identity** from **cache-entry identity**.

A cache entry SHOULD be uniquely determined by the relevant combination of:

```text
tenant
document
document version
representation
processing run
producer/version
content hash
```

Example logical identity:

```text
tenant-A/
document-123/
version-7/
semantic/
run-2026-09-05-001/
```

The implementation MAY use a different physical key.

The physical key must not become part of the public contract.

---

# 9. Provenance

Every derived content artifact SHOULD preserve provenance sufficient to answer:

1. Which document produced this artifact?
2. Which document version was processed?
3. Which extractor produced it?
4. Which extractor version was used?
5. Which processing run produced it?
6. Which representation was generated?
7. Which source/content hash was used?
8. When was the artifact generated?
9. Which upstream dependencies existed?

Example:

```yaml
provenance:
  source_document_id: document-123
  source_version: 7
  processing_run_id: run-2026-09-05-001
  producer:
    id: pdf-extractor
    version: 1.8.0
  schema_version: 1.26
  input_hash: sha256:...
  output_hash: sha256:...
```

---

# 10. Content Cache Contract

The logical API consists of:

```text
get
get_many
put
put_many
get_document
get_chunks
search
invalidate
delete
get_capabilities
get_operation
```

Implementations MAY expose these through REST, gRPC, SDK, or another transport.

The transport is not part of the architectural contract.

---

# 11. Put

Logical operation:

```text
put(entry) -> PutResult
```

A successful `put` MUST atomically publish one cache entry.

The logical lifecycle is:

```text
RECEIVE
   │
   ▼
PERSIST PAYLOAD
   │
   ▼
VERIFY PAYLOAD
   │
   ▼
PERSIST METADATA
   │
   ▼
PUBLISH
   │
   ▼
VISIBLE
```

Consumers MUST NOT observe an entry as `PUBLISHED` before the payload is complete.

---

# 12. Put Many

Logical operation:

```text
put_many(entries[]) -> BatchPutResult
```

Batch writes use **partial-success semantics**.

Example:

```yaml
succeeded:
  - entry_id: chunk-001
  - entry_id: chunk-002

failed:
  - entry_id: chunk-003
    error_code: STORAGE_UNAVAILABLE
    retryable: true
```

The batch itself is not atomic.

However:

> **Each individual entry must be atomically published.**

This allows high-throughput ingestion without requiring distributed transactions across an entire batch.

---

# 13. Get

Logical operation:

```text
get(cache_entry_id) -> CacheEntry | NOT_FOUND
```

The result MUST respect authorization.

An unauthorized protected object MAY be represented as `NOT_FOUND` to prevent existence disclosure.

---

# 14. Get Many

Logical operation:

```text
get_many(ids[]) -> BatchGetResult
```

Example:

```yaml
found:
  - entry_id: chunk-001
  - entry_id: chunk-002

not_found:
  - chunk-003
```

Ordering SHOULD either:

1. preserve requested ID order; or
2. be explicitly documented.

The implementation MUST document its behavior.

---

# 15. Document Retrieval

The cache SHOULD support logical document reconstruction:

```text
get_document(
    document_id,
    representation,
    version
)
```

This operation MAY reconstruct the document from chunks.

For large documents, the implementation SHOULD support streaming or asynchronous retrieval.

---

# 16. Chunk Retrieval

The cache SHOULD support:

```text
get_chunks(
    document_id,
    representation,
    sequence_start,
    sequence_end
)
```

Example:

```text
document_id = document-123
representation = semantic
range = 100..150
```

Chunk retrieval SHOULD be optimized for sequential access.

---

# 17. Search Contract

Cache Search is a **metadata/content-artifact lookup facility**.

Example:

```text
search(
    tenant_id = tenant-A,
    representation = semantic,
    processed_at >= T1,
    processed_at < T2,
    classification = PUBLIC
)
```

Supported filters SHOULD include:

* tenant ID;
* document ID;
* document version;
* representation;
* chunk ID;
* processing run;
* producer;
* producer version;
* schema version;
* classification;
* status;
* content type;
* content hash;
* creation time;
* processing time;
* publication time;
* expiration time.

Optional filters MAY include implementation-defined metadata.

---

# 18. Time-Range Search

The cache SHOULD support deterministic time-range searches:

```text
processed_at >= T1
processed_at < T2
```

The preferred boundary convention is:

```text
[T1, T2)
```

This avoids ambiguity when adjacent windows are queried.

---

# 19. Classification Search

Classification filters MUST participate in authorization.

Example:

```text
classification = PUBLIC
```

does not grant access to PUBLIC content if the caller lacks tenant/resource authorization.

Classification is a constraint, not an authorization substitute.

---

# 20. Pagination

Large cache searches MUST support cursor-based pagination where practical.

Example:

```yaml
search:
  filter:
    tenant_id: tenant-A
    representation: semantic

page:
  limit: 100
  cursor: "..."
```

Ordering MUST be deterministic.

A recommended ordering is:

```text
processed_at ASC
cache_entry_id ASC
```

or the implementation-defined equivalent.

Offset-only pagination SHOULD NOT be the only mechanism for large deployments.

---

# 21. What Cache Search Is Not

The Content Cache must not become an accidental search engine.

Cache Search answers:

> Which cached content artifacts satisfy these metadata constraints?

Examples:

```text
Which documents were processed yesterday?

Which chunks belong to document X?

Which artifacts were produced by extractor version 7?

Which semantic representations belong to processing run R?

Which PUBLIC chunks exist for tenant T?
```

Search Projection answers:

> Which content is relevant to this query?

Examples:

```text
Find documents discussing Kubernetes security.

Find semantically similar passages.

Perform BM25 retrieval.

Perform hybrid vector + lexical retrieval.

Rerank retrieved candidates.
```

Architectural boundary:

```text
Content Cache
     │
     │ metadata filtering
     ▼
Candidate IDs
     │
     ▼
Search Projection
     │
     ├── lexical
     ├── vector
     ├── hybrid
     └── graph
     │
     ▼
Ranking
```

The Content Cache MUST NOT be treated as the authoritative ranking system.

---

# 22. Capability Discovery

Every conforming implementation MUST expose logical capability discovery.

Logical operation:

```text
get_capabilities() -> CacheCapabilities
```

A REST implementation MAY expose:

```http
GET /capabilities
```

Example:

```yaml
api_version: "1.26"

implementation:
  name: cassandra
  version: "1.x"

capabilities:
  get_by_id: true
  batch_get: true
  document_reconstruction: true
  chunk_range: true
  metadata_search: true
  time_range_search: true
  representation_filter: true
  classification_filter: true
  processing_run_filter: true
  pagination: true
  streaming: false
  async_operations: true
  operation_polling: true
  operation_callbacks: false
  hot_tier: true
  warm_tier: true
  cold_tier: false

limits:
  max_batch_size: 1000
  max_inline_payload_bytes: 1048576
  max_search_limit: 1000
  max_document_reconstruction_bytes: 52428800

latency_profiles:
  hot:
    target_p95_ms: 50
  warm:
    target_p95_ms: 250
  cold:
    target_p95_ms: 1000
```

---

# 23. Required Capabilities

A conforming implementation MUST support:

* `get`;
* `get_many`;
* `put`;
* `put_many`;
* invalidation;
* tenant isolation;
* authorization;
* provenance;
* atomic publication;
* representation identification;
* classification metadata;
* deterministic retrieval;
* capability discovery.

Other capabilities may be optional depending on deployment profile.

---

# 24. Optional Capabilities

Optional capabilities include:

* streaming;
* asynchronous operations;
* cold tier;
* warm tier;
* bulk export;
* bulk import;
* advanced metadata search;
* document reconstruction;
* chunk range retrieval;
* server-side filtering;
* prefetch;
* operation callbacks.

Consumers must discover optional capabilities rather than assuming them.

---

# 25. Large Content

The cache must support payloads larger than the practical record size of an implementation.

Three logical storage forms are supported.

## 25.1 Inline

Small payload:

```yaml
payload:
  inline: "..."
```

Suitable for:

* metadata;
* small chunks;
* small flattened documents;
* small semantic objects.

## 25.2 Object Reference

Large payload:

```yaml
payload:
  location:
    type: object
    uri: ...
    hash: sha256:...
    size_bytes: 124000000
```

The metadata record remains small while the payload is stored externally.

## 25.3 Chunked Payload

Large content may be split:

```text
document
├── payload-001
├── payload-002
├── payload-003
└── payload-004
```

The logical document remains one cache artifact.

The physical representation is implementation-specific.

---

# 26. Recommended Payload Strategy

Implementations SHOULD use:

```text
small payload
    -> inline

medium/large payload
    -> object reference

very large/sequential payload
    -> chunked or streaming
```

The threshold must be configurable.

The architectural contract must not assume that a complete document can fit into one database record.

---

# 27. Tiering

The Content Cache supports three logical tiers:

```text
HOT
│
▼
WARM
│
▼
COLD
```

## 27.1 Hot

Optimized for:

* low latency;
* active indexing;
* recent extraction;
* benchmark workloads;
* interactive processing.

Typical storage:

* SSD;
* distributed database;
* memory-assisted store.

## 27.2 Warm

Optimized for:

* less frequently accessed content;
* reconstruction;
* reprocessing;
* operational workflows.

## 27.3 Cold

Optimized for:

* long-term retention;
* infrequent retrieval;
* large artifacts;
* historical processing.

Typical implementation:

* object storage;
* large HDD pool;
* archival storage.

---

# 28. Tier Promotion

Implementations MAY promote content:

```text
COLD -> WARM
WARM -> HOT
```

Promotion must not change the logical identity of the cache entry.

A consumer must not need to know where the content physically resides.

---

# 29. Eviction

Eviction is an operational storage decision.

Example:

```text
HOT
└── evict
      ▼
WARM
```

Eviction is not equivalent to deletion.

An entry can leave the hot tier while remaining retained in warm or cold storage.

---

# 30. Retention

The cache defines a logical `RetentionPolicy`.

Example:

```yaml
policy_id: standard-90d

hot_retention: 7d
warm_retention: 30d
cold_retention: 90d

minimum_retention: 30d
delete_after_expiration: true
legal_hold: false

version: 3
```

Retention policy is governance state.

The cache enforces the operational consequences.

---

# 31. Retention Policy Enforcement

Retention policy is part of the Content Cache lifecycle contract.

A conforming **production implementation** MUST ensure that artifacts are not retained beyond their applicable `expires_at` unless a legal hold or another explicitly authorized retention override applies.

The mechanism used to enforce expiration is implementation-specific.

A cache implementation MAY use:

* an internal background expiration/sweeper process;
* scheduled lifecycle workers;
* storage-native lifecycle policies;
* event-driven expiration;
* an external lifecycle manager invoking the cache API;
* a combination of these mechanisms.

The architectural contract specifies the required outcome, not a particular mechanism.

A production implementation MUST NOT rely solely on consumers remembering to call `delete` in order for retention to be enforced.

Recommended model:

```text
                    Retention Policy
                           │
                           ▼
                    expires_at
                           │
              ┌────────────┴────────────┐
              │                         │
         Legal Hold?                No Hold
              │                         │
             YES                       ▼
              │                  Expiration Due
              │                         │
              │                         ▼
              │                 Lifecycle Action
              │                         │
              │              ┌──────────┴──────────┐
              │              ▼                     ▼
              │          Delete                  Move
              │                                  Tier
              │
              └──────────────► Retained
```

Expiration processing SHOULD be idempotent.

If expiration processing fails temporarily, the implementation SHOULD retry and expose the failure through operational telemetry.

The cache MUST distinguish:

* `expires_at` — policy-defined retention boundary;
* `evicted_at` — removal from a particular performance tier;
* `deleted_at` — physical/logical deletion;
* `legal_hold` — explicit retention override.

An implementation MAY perform expiration asynchronously.

Therefore:

> `expires_at` defines when an artifact becomes eligible for expiration; it does not necessarily define the exact physical deletion timestamp.

---

# 32. Expiration Visibility

An artifact whose retention period has expired MUST NOT be returned to normal consumers, even if physical deletion has not yet completed.

Therefore:

```text
expires_at reached
       │
       ▼
LOGICALLY EXPIRED
       │
       ├── GET      -> RETENTION_EXPIRED
       ├── SEARCH   -> excluded
       └── DELETE   -> lifecycle processing
```

Physical deletion MAY occur asynchronously.

This prevents lifecycle-processing delays from becoming retention-policy violations.

---

# 33. Legal Hold

A legal hold MUST prevent retention-driven deletion.

Example:

```yaml
legal_hold:
  enabled: true
  reason: "incident-2026-123"
  created_at: ...
```

Legal hold state must be auditable.

---

# 34. Security

## 34.1 Tenant Isolation

Every cache entry MUST have an explicit tenant scope.

```yaml
tenant_id: tenant-A
```

Platform-wide entries MAY use a reserved administrative scope.

Cross-tenant access is prohibited unless explicitly authorized.

---

# 35. Classification

Classification belongs to the content artifact.

Example:

```yaml
classification:
  - PUBLIC
```

or:

```yaml
classification:
  - INTERNAL
  - CONFIDENTIAL
```

Classification filtering does not replace authorization.

---

# 36. Masking

Masking is a transformation.

The cache must distinguish:

```text
original representation
masked representation
```

If Design 1.23 requires:

```yaml
store_original: false
```

the original content MUST NOT exist in the cache.

A masked-only implementation must not accidentally retain an unmasked temporary artifact.

---

# 37. Normal Authorization

Normal retrieval evaluates:

```text
tenant authorization
        AND
resource authorization
        AND
classification policy
        AND
representation policy
```

Unauthorized access must fail closed.

---

# 38. Break-Glass Administration

Normal applications should avoid revealing whether a protected object exists.

Therefore:

```text
unauthorized request
        │
        ▼
NOT_FOUND
```

An explicitly authorized administrative operation MAY distinguish:

```text
EXISTS_BUT_FORBIDDEN
```

only when:

```yaml
break_glass: true
reason: "incident-2026-123"
permission: cache_break_glass
```

Every break-glass operation MUST be audited.

Audit record:

```yaml
event: cache.break_glass
timestamp: ...
principal: ...
tenant_scope: ...
resource: ...
operation: get
reason: incident-2026-123
policy: ...
result: EXISTS_BUT_FORBIDDEN
correlation_id: ...
```

---

# 39. Consistency and Publication

Atomic publication is a core invariant.

The following state transition is normative:

```text
RECEIVED
   │
   ▼
PERSISTING
   │
   ▼
VERIFIED
   │
   ▼
PUBLISHED
```

Only `PUBLISHED` artifacts are visible to normal consumers.

Invalid states such as:

```text
metadata = PUBLISHED
payload = incomplete
```

must be impossible through the public API.

This prevents downstream consumers from indexing incomplete content.

---

# 40. Failure Semantics

Errors must be classified.

Recommended categories:

```text
NOT_FOUND
FORBIDDEN
INVALID_REQUEST
CONFLICT
PAYLOAD_TOO_LARGE
UNSUPPORTED_CAPABILITY
STORAGE_UNAVAILABLE
TIMEOUT
INTEGRITY_FAILURE
RETENTION_EXPIRED
LEGAL_HOLD
INTERNAL_ERROR
```

Each error SHOULD indicate whether retry is appropriate.

Example:

```yaml
error_code: STORAGE_UNAVAILABLE
retryable: true
```

versus:

```yaml
error_code: INVALID_REQUEST
retryable: false
```

---

# 41. Integrity

Every payload SHOULD have a content hash.

Example:

```yaml
content_hash:
  algorithm: sha256
  value: ...
```

The cache SHOULD verify:

```text
stored payload hash == declared payload hash
```

before publication.

Integrity failures MUST prevent publication.

---

# 42. Asynchronous Operations

Large operations SHOULD use an asynchronous API.

Logical model:

```text
submit operation
      │
      ▼
operation_id
      │
      ▼
get operation status
      │
      ├── PENDING
      ├── RUNNING
      ├── COMPLETED
      ├── FAILED
      └── CANCELLED
```

A conforming implementation supporting asynchronous operations MUST provide a way for consumers to retrieve operation status.

The recommended logical interface is:

```text
submit_operation(request)
    -> operation_id

get_operation(operation_id)
    -> OperationStatus
```

A REST implementation SHOULD expose:

```http
GET /operations/{operation_id}
```

Example:

```json
{
  "operation_id": "op-123",
  "type": "document_reconstruction",
  "status": "RUNNING",
  "created_at": "2026-09-05T10:00:00Z",
  "started_at": "2026-09-05T10:00:01Z",
  "progress": {
    "completed": 417,
    "total": 1000
  }
}
```

On completion:

```json
{
  "operation_id": "op-123",
  "type": "document_reconstruction",
  "status": "COMPLETED",
  "completed_at": "2026-09-05T10:01:32Z",
  "result": {
    "location": "..."
  }
}
```

Consumers SHOULD use polling with bounded intervals and exponential backoff.

Callbacks/webhooks are NOT required by Design 1.26.

A future implementation MAY provide callbacks, events, or webhooks as an optional capability.

Operation status records MUST themselves be subject to tenant isolation and authorization.

---

# 43. Performance Contract

Performance targets are deployment-profile targets rather than universal architectural guarantees.

Initial PoC targets:

| Operation                    | Hot p95 | Warm p95 |                 Cold p95 |
| ---------------------------- | ------: | -------: | -----------------------: |
| `get` metadata/small payload |  <50 ms |  <250 ms |                     <1 s |
| `get_many` typical batch     | <100 ms |  <500 ms | implementation-dependent |
| metadata search              | <100 ms |  <500 ms | implementation-dependent |
| chunk range                  | <100 ms |  <500 ms | implementation-dependent |

These values MUST be validated against real workloads.

---

# 44. Throughput

Capacity planning SHOULD track:

* writes/sec;
* reads/sec;
* batch operations/sec;
* chunks/sec;
* bytes written/sec;
* bytes read/sec;
* document reconstruction throughput;
* cold-tier retrieval throughput.

Initial planning should target:

```text
sustained_capacity
    >=
expected_peak_workload × 1.5
```

The actual numerical workload target must be established through benchmark measurements.

---

# 45. Batch Performance

Batch operations SHOULD provide substantially better throughput than equivalent individual operations.

Implementations SHOULD optimize:

```text
put_many(1000)
```

rather than requiring:

```text
put()
put()
put()
...
```

for large ingestion workloads.

Partial success semantics must remain intact.

---

# 46. Observability

The Content Cache MUST expose operational telemetry.

Recommended metrics:

```text
cache_requests_total
cache_request_duration
cache_bytes_read
cache_bytes_written
cache_chunks_read
cache_chunks_written
cache_hits
cache_misses
cache_evictions
cache_expirations
cache_storage_bytes
cache_errors
cache_expiration_due_total
cache_expiration_completed_total
cache_expiration_failed_total
cache_expiration_lag_seconds
```

---

# 47. Metrics Labels

Metrics must avoid uncontrolled cardinality.

In particular, implementations SHOULD NOT expose arbitrary tenant IDs as high-cardinality Prometheus labels.

Tenant-specific diagnostics should instead use:

* structured logs;
* traces;
* controlled dimensions;
* administrative analytics.

---

# 48. Logging

Cache logs SHOULD include:

```text
timestamp
operation
cache_entry_id
tenant scope
processing run
implementation
tier
latency
result
error code
correlation ID
```

Payload content itself should not be logged by default.

---

# 49. Tracing

Distributed operations SHOULD propagate correlation and trace identifiers.

Example:

```text
Extraction
    │ trace-123
    ▼
Content Cache
    │ trace-123
    ▼
Indexer
    │ trace-123
    ▼
Vector Store
```

This allows end-to-end latency analysis.

---

# 50. Storage Implementations

Design 1.26 does not mandate a storage engine.

## 50.1 Cassandra

Cassandra is a valid adapter where:

* distributed writes are required;
* predictable key-based access is important;
* horizontal scaling is required.

However, large content SHOULD NOT be assumed to fit safely into one Cassandra value.

The adapter SHOULD use:

```text
Cassandra
├── metadata
├── chunk records
└── object references
```

with large payloads placed in object storage where appropriate.

Cassandra remains an implementation choice, not an architectural dependency.

---

## 50.2 PostgreSQL

PostgreSQL is a suitable adapter for small and medium deployments.

Possible schema:

```text
cache_entries
cache_chunks
cache_metadata
cache_processing_runs
```

Range partitioning MAY be used for time-oriented workloads.

PostgreSQL is particularly attractive where:

* operational simplicity is important;
* transactional publication is valuable;
* workload scale is moderate;
* SQL metadata queries are useful.

---

## 50.3 S3-Compatible Object Storage

Object storage is suitable for:

* large semantic documents;
* flattened documents;
* extraction artifacts;
* cold tier;
* immutable payloads.

Object storage SHOULD normally be paired with a metadata index when complex cache searches are required.

Example:

```text
Metadata DB
     │
     │ payload_ref
     ▼
Object Storage
```

---

## 50.4 Filesystem

Filesystem storage may be used for:

* local development;
* PoC;
* small deployments;
* test fixtures;
* deterministic benchmark environments.

It is not assumed to provide distributed durability.

---

## 50.5 Hybrid Implementation

The preferred production architecture for many workloads may be:

```text
             Content Cache API
                    │
          ┌─────────┴─────────┐
          │                   │
          ▼                   ▼
    Metadata Store       Object Storage
          │                   │
        metadata          large payloads
          │                   │
          └─────────┬─────────┘
                    │
                    ▼
              Logical Entry
```

This separates:

* metadata/query workload;
* payload storage;
* hot/cold lifecycle.

---

# 51. Implementation Comparison

The following is an implementation guide, not an architectural ranking.

| Implementation | Best Fit                                  | Large Payloads                 | Metadata Search             | Distributed Scale | Typical Profile |
| -------------- | ----------------------------------------- | ------------------------------ | --------------------------- | ----------------- | --------------- |
| Cassandra      | high-volume distributed metadata/chunks   | external reference recommended | data-model dependent        | high              | Medium/Large    |
| PostgreSQL     | transactional metadata and moderate scale | external reference recommended | strong                      | moderate          | Small/Medium    |
| S3-compatible  | large immutable payloads                  | excellent                      | weak without metadata layer | high              | Warm/Cold       |
| Filesystem     | local PoC/testing                         | good                           | limited                     | low               | Small           |
| Hybrid         | mixed metadata + payload workloads        | excellent                      | strong                      | high              | Medium/Large    |

The contract deliberately allows the implementation to evolve independently of consumers.

---

# 52. Deployment Profiles

## 52.1 Small

Characteristics:

* single cache service;
* local or PostgreSQL metadata;
* local filesystem/object storage;
* SSD;
* limited replication.

Suitable for:

* development;
* CI;
* PoC;
* small benchmark datasets.

---

## 52.2 Medium

Characteristics:

* replicated metadata store;
* object storage;
* hot + warm tiers;
* dedicated extraction workers;
* backup and recovery.

Suitable for:

* integration;
* sustained benchmarks;
* development environments;
* smaller production installations.

---

## 52.3 Large

Characteristics:

* distributed metadata service;
* replicated object storage;
* independent hot/warm/cold tiers;
* dedicated cache nodes;
* independent extraction workers;
* horizontal scaling;
* automated lifecycle management;
* observability and SRE controls.

Suitable for:

* production-scale workloads;
* large document collections;
* high-throughput extraction;
* continuous reprocessing.

---

# 53. Recalculation Integration

Design 1.25 defines:

```text
Rule / Model / Dictionary / Source Change
                    │
                    ▼
                Resolutor
                    │
                    ▼
            Dependency Analysis
                    │
                    ▼
          Recalculation Plan
                    │
                    ▼
                 Equalix
```

The Content Cache participates as an artifact source.

Example:

```text
Extractor V7
     │
     ▼
Cache Artifact A
     │
     ├── Vector Projection
     ├── Annotation
     └── Benchmark
```

If extractor V7 changes:

```text
Extractor V8
     │
     ▼
new processing run
     │
     ▼
new cache artifacts
```

Old artifacts may remain available according to retention policy.

---

# 54. Search and Index Integration

Indexers consume the cache through the contract.

Example:

```text
Content Cache
      │
      ▼
Indexer
      │
      ├── lexical index
      ├── vector index
      └── graph projection
```

The indexer must not directly access:

```text
Cassandra tables
S3 buckets
filesystem paths
```

It accesses:

```text
Content Cache API
```

This is essential for storage independence.

---

# 55. Search Projection Lifecycle

A typical flow is:

```text
Extractor
   │
   ▼
Content Cache
   │
   ▼
Candidate selection
   │
   ▼
Projection builder
   │
   ├── lexical
   ├── vector
   └── graph
   │
   ▼
Search
```

The cache remains the artifact retrieval layer.

---

# 56. Issue #14 / SNTP-9 Integration

SNTP-9 compares:

```text
Flattened Text
vs
Semantic Content
```

The Content Cache becomes the common artifact interface.

Benchmark flow:

```text
                    Source Documents
                           │
                           ▼
                    Content Extractor
                           │
             ┌─────────────┴─────────────┐
             ▼                           ▼
      Flattened Text               Semantic Content
             │                           │
             └─────────────┬─────────────┘
                           ▼
                    Content Cache
                           │
             ┌─────────────┴─────────────┐
             ▼                           ▼
         Index A                       Index B
             │                           │
             └─────────────┬─────────────┘
                           ▼
                    Same Query Set
                           │
                           ▼
                     RAG Evaluation
                           │
                           ▼
                  Recall / MRR / nDCG
                  Precision / Latency
                  Chunk Statistics
```

The benchmark must ensure that:

* the same documents are used;
* the same query set is used;
* the same embedding model is used;
* the same vector dimensions are used;
* the same vector store is used;
* the same similarity metric is used;
* the same retrieval K is used;
* the same query preprocessing is used;
* the same reranking configuration is used;
* only representation/chunking differs.

The benchmark therefore measures the effect of content representation rather than infrastructure differences.

Issue #14 MUST consume cached extraction artifacts through the Content Cache contract rather than directly accessing a storage implementation.

---

# 57. Migration from Cassandra-Coupled Design

Existing benchmark and prototype components may currently access Cassandra directly.

Migration should proceed incrementally.

## Phase 1 — Define Contract

Implement:

```text
ContentCache
```

interface.

## Phase 2 — Cassandra Adapter

Move existing Cassandra access behind:

```text
CassandraContentCache
```

## Phase 3 — Update Consumers

Change:

```text
Indexer -> Cassandra
```

to:

```text
Indexer -> ContentCache
```

and:

```text
Benchmark -> Cassandra
```

to:

```text
Benchmark -> ContentCache
```

## Phase 4 — Large Payload Support

Introduce:

```text
metadata DB
+
object payload
```

where required.

## Phase 5 — Alternative Adapter

Implement PostgreSQL, S3, or hybrid storage.

## Phase 6 — Benchmark

Run identical workloads against each implementation.

---

# 58. Home-Lab Reference Deployment

The current Synanton laboratory can be mapped to the Content Cache architecture as follows:

```text
                    node0
              Kubernetes Control Plane
                 Monitoring / Control
                       │
        ┌──────────────┼──────────────┐
        │              │              │
        ▼              ▼              ▼
     node1           node2          node3
   Storage/DB      GPU Worker      GPU Worker
   12 TB HDD       GPU            GPU
   SSD             10 Gbit        10 Gbit
        │              │              │
        └──────────────┼──────────────┘
                       │
                  Content Cache
```

## node0

Primary role:

* Kubernetes control plane;
* monitoring;
* orchestration.

It should not be the primary cache data node.

## node1

Primary roles:

* metadata database;
* object storage;
* large/cold artifacts;
* backups.

The 12 TB HDD is appropriate for large/cold payloads.

Active database metadata should preferably remain on SSD.

## node2 / node3

Primary roles:

* content extraction;
* GPU-assisted processing;
* embeddings;
* indexing;
* benchmark workloads.

The 10 Gbit network should be used for high-volume movement of extraction artifacts.

---

# 59. Home-Lab Persistence and Replication

The following settings are recommended for a serious home-lab Content Cache deployment.

They are **not architectural requirements**.

For a Cassandra-based metadata adapter:

```text
Replication Factor: 3
Replica placement: node1 + node2 + node3

Critical read consistency: QUORUM
Critical write consistency: QUORUM
```

The exact consistency level MAY be changed for benchmark experiments, but the selected value MUST be documented because consistency settings materially affect latency and availability measurements.

For PostgreSQL:

```text
Primary:
  SSD-backed data directory

Optional replica:
  separate node/storage

Backup:
  independent storage
```

The PostgreSQL deployment SHOULD use persistent SSD-backed storage for active metadata rather than the large HDD.

For object storage:

```text
Hot payload:
  SSD-backed storage where practical

Warm/cold payload:
  HDD-backed object storage

Backup:
  independent storage target
```

If a single-node object-storage deployment is used, it MUST be considered a PoC or non-HA deployment unless an independent backup/recovery mechanism is provided.

The home-lab deployment therefore provides two distinct durability models:

1. **replicated metadata**, where the metadata database supports replication;
2. **object durability**, where large payloads are protected by storage replication and/or independent backup.

These mechanisms are implementation-specific and do not alter the Content Cache contract.

---

# 60. Home-Lab Reference Topology

A practical initial implementation is:

```text
node1
├── PostgreSQL or Cassandra
│     └── Cache Metadata
│
└── S3-compatible Object Storage
       └── Large Content

node2
├── Content Extractor
├── Embedding Worker
└── Benchmark Worker

node3
├── Content Extractor
├── Embedding Worker
└── Benchmark Worker

node0
├── Kubernetes
├── Monitoring
└── Operations
```

This is a reference deployment only.

The architecture does not require these machines or technologies.

---

# 61. Conformance Test Suite

The Content Cache conformance suite defines the behavioral requirements of Design 1.26.

Conformance has three levels.

## 61.1 Core Conformance

A lightweight or PoC implementation claiming **Core Conformance** MUST pass tests for:

* identity;
* `put`;
* `get`;
* atomic publication;
* content integrity;
* tenant isolation;
* authorization;
* basic classification handling;
* provenance;
* required error semantics;
* capability discovery.

Core Conformance is intended for:

* development adapters;
* CI implementations;
* experimental storage backends;
* benchmark PoCs.

---

## 61.2 Extended Conformance

An implementation claiming **Extended Conformance** MUST additionally pass:

* `put_many`;
* `get_many`;
* metadata search;
* time-range search;
* deterministic pagination;
* chunk range retrieval;
* invalidation;
* retention behavior;
* tier lifecycle;
* large-content handling;
* asynchronous operations where advertised.

---

## 61.3 Full Conformance

An implementation claiming **Full Design 1.26 Conformance** MUST pass the complete conformance suite, including:

* identity;
* publication;
* integrity;
* batch semantics;
* retrieval;
* search;
* security;
* tenant isolation;
* classification;
* masking;
* break-glass administration;
* lifecycle;
* retention;
* legal hold;
* large content;
* tiering;
* asynchronous operations;
* observability;
* failure and recovery behavior.

An implementation MUST NOT advertise support for an optional capability unless the corresponding capability-specific tests pass.

Capability discovery therefore determines which extended tests apply to the implementation.

Example:

```yaml
capabilities:
  streaming: false
  cold_tier: false
  async_operations: true
```

This does not require cold-tier or streaming tests, but the implementation MUST pass asynchronous-operation tests.

A production deployment SHOULD require Full Conformance.

---

# 62. Conformance Test Categories

The suite SHOULD be organized into the following categories:

### Identity

* deterministic identity;
* duplicate handling;
* content hash verification.

### Publication

* incomplete payload is invisible;
* failed publication is not visible;
* published entry is complete.

### Batch

* partial success;
* individual entry atomicity;
* retryable failures.

### Retrieval

* get;
* batch get;
* document reconstruction;
* chunk range.

### Search

* tenant filtering;
* time range;
* representation;
* classification;
* processing run;
* deterministic pagination.

### Security

* tenant isolation;
* unauthorized retrieval;
* classification enforcement;
* masked representation;
* break-glass audit.

### Lifecycle

* hot eviction;
* warm migration;
* retention expiration;
* legal hold.

### Large Content

* inline;
* object reference;
* chunked payload;
* streaming where supported.

### Async Operations

* operation creation;
* status polling;
* completion;
* failure;
* cancellation where supported.

### Observability

* request metrics;
* latency;
* errors;
* expiration metrics;
* trace propagation.

---

# 63. Acceptance Criteria

Design 1.26 is considered implementation-ready when:

1. a stable Content Cache contract exists;
2. no consumer depends directly on Cassandra;
3. capability discovery is implemented;
4. `put_many` supports partial success;
5. individual entries have atomic publication;
6. large payloads do not require a single database record;
7. tenant isolation is enforced;
8. classification filtering is security-aware;
9. break-glass access is audited;
10. retention is modeled separately from tier eviction;
11. production implementations have an enforceable retention mechanism;
12. expired artifacts are logically invisible before physical deletion completes;
13. deterministic pagination is supported;
14. cache search is explicitly separated from semantic search;
15. asynchronous operations provide status polling where supported;
16. operational metrics and traces are available;
17. at least one production-oriented adapter exists;
18. at least one lightweight PoC adapter exists;
19. Issue #14 can consume the cache exclusively through the contract;
20. Core/Extended/Full conformance levels are defined;
21. at least one implementation achieves the appropriate declared conformance level;
22. migration from the current Cassandra-coupled benchmark is complete.

---

# 64. Non-Goals

Design 1.26 does not define:

* a mandatory database;
* a mandatory object store;
* a mandatory cache technology;
* a universal ontology;
* an LLM provider;
* a vector database;
* a search engine;
* a graph database;
* a BI platform;
* a dashboard framework;
* a specific Kubernetes topology;
* a mandatory cloud provider;
* source-of-truth document storage.

---

# 65. Architectural Invariants

The following invariants are normative.

## Invariant 1 — Storage Independence

> Consumers depend on the Content Cache contract, not its storage implementation.

## Invariant 2 — Atomic Publication

> A cache artifact is not visible until its payload and required metadata are complete and verified.

## Invariant 3 — Security Preservation

> The Content Cache cannot weaken the security model inherited from Design 1.23.

## Invariant 4 — Explicit Tenant Scope

> Every cache artifact has an explicit tenant scope.

## Invariant 5 — Provenance

> Every derived artifact retains sufficient provenance to identify its source processing lineage.

## Invariant 6 — Representation Identity

> Flattened, semantic, and chunk representations are independently identifiable.

## Invariant 7 — Large-Content Independence

> No implementation may require all logical content to fit into a single physical database record.

## Invariant 8 — Search Separation

> Cache metadata search is not semantic relevance search.

## Invariant 9 — Deterministic Retrieval

> Paginated retrieval has deterministic ordering.

## Invariant 10 — Retention Separation

> Tier eviction and retention expiration are separate lifecycle concepts.

## Invariant 11 — Enforceable Retention

> Production implementations must provide a mechanism that enforces retention expiration independently of consumer behavior.

## Invariant 12 — Expiration Visibility

> Expired artifacts are excluded from normal retrieval and search even when physical deletion is asynchronous.

## Invariant 13 — Rebuildability

> Cache artifacts are derived state and should be reproducible when their upstream inputs remain available.

## Invariant 14 — Implementation Replaceability

> Replacing Cassandra with PostgreSQL, object storage, or another implementation must not require architectural changes to extraction, indexing, or benchmarking consumers.

## Invariant 15 — Capability Transparency

> Consumers must discover optional implementation capabilities rather than assuming them.

## Invariant 16 — Auditable Administrative Access

> Break-glass access must be explicitly authorized and auditable.

---

# 66. Recommended Initial Implementation

For the first implementation, the recommended architecture is:

```text
                    Content Cache API
                           │
               ┌───────────┴───────────┐
               │                       │
               ▼                       ▼
        Metadata Storage          Object Storage
               │                       │
        ┌──────┴──────┐                │
        │             │                │
        ▼             ▼                ▼
     Metadata       Chunks         Large Payloads
        │             │                │
        └─────────────┴────────────────┘
                       │
                       ▼
                 Content Cache
```

For a home-lab PoC:

```text
PostgreSQL/Cassandra
        +
S3-compatible object storage
        +
Content Cache API
```

is preferable to making Cassandra responsible for every payload size.

Cassandra may remain the first adapter where existing implementation work makes it practical, but it is not part of the architectural contract.

---

# 67. Implementation Phases

## Phase 1 — Contract

Define:

* API;
* identity;
* metadata;
* lifecycle;
* capabilities;
* errors;
* consistency/publication semantics.

## Phase 2 — Minimal Adapter

Implement:

* put;
* put_many;
* get;
* get_many;
* search;
* invalidation.

## Phase 3 — Security

Implement:

* tenant isolation;
* classification;
* authorization;
* break-glass;
* audit.

## Phase 4 — Large Content

Implement:

* inline payloads;
* object references;
* chunked payloads.

## Phase 5 — Lifecycle

Implement:

* hot/warm/cold;
* retention;
* expiration;
* eviction;
* legal hold.

## Phase 6 — Observability

Implement:

* metrics;
* logs;
* traces.

## Phase 7 — Async Operations

Implement:

* operation submission;
* operation IDs;
* status polling;
* progress reporting;
* failure handling.

## Phase 8 — Consumers

Migrate:

* indexers;
* vector builders;
* benchmark;
* knowledge processors.

## Phase 9 — Conformance

Run:

* Core tests;
* Extended tests;
* Full tests where applicable.

## Phase 10 — Production Hardening

Implement:

* failure recovery;
* backups;
* performance testing;
* capacity planning;
* disaster recovery;
* lifecycle monitoring.

---

# 68. Final Architectural Model

Design 1.26 establishes the following separation:

```text
┌───────────────────────────────────────────────────────────┐
│                     Source Content                        │
│              Authoritative source systems                 │
└──────────────────────────┬────────────────────────────────┘
                           │
                           ▼
┌───────────────────────────────────────────────────────────┐
│                  Content Extraction Plane                  │
│       PDF / HTML / DOC / Audio / Image / Video            │
└──────────────────────────┬────────────────────────────────┘
                           │
                           ▼
┌───────────────────────────────────────────────────────────┐
│                    Content Cache Plane                     │
│                                                           │
│  flattened text │ semantic content │ chunks │ metadata    │
│                                                           │
│  provenance │ classification │ lifecycle │ retention      │
└───────────────┬──────────────────────┬────────────────────┘
                │                      │
                ▼                      ▼
┌────────────────────────┐   ┌───────────────────────────────┐
│   Knowledge Plane      │   │       Search Projections      │
│                        │   │                               │
│ Annotation             │   │ lexical                      │
│ Derived Knowledge      │   │ vector                       │
│ Recalculation          │   │ graph                        │
└───────────┬────────────┘   └──────────────┬────────────────┘
            │                               │
            └───────────────┬───────────────┘
                            ▼
                  ┌────────────────────┐
                  │    Applications    │
                  │    / RAG / MCP     │
                  └────────────────────┘
```

The architectural boundary is:

> **Extraction determines what content artifacts are produced.**

> **The Content Cache determines how those artifacts are durably staged, retrieved, filtered, secured, and lifecycle-managed.**

> **Knowledge processing determines what Synanton understands from those artifacts.**

> **Search projections determine how that knowledge is retrieved and ranked.**

> **Analytics determines how knowledge and platform activity are measured.**

---

# 69. Final Thesis

The Content Cache is not merely a replacement for Cassandra.

It establishes an architectural boundary between **derived content production** and **derived content consumption**.

This boundary provides:

* storage independence;
* large-content safety;
* security consistency;
* explicit lifecycle management;
* predictable retrieval semantics;
* reproducible benchmarking;
* independent search projections;
* independent knowledge processing;
* implementation replaceability;
* operational scalability.

The resulting principle is:

> **Content artifacts are a stable logical interface, not a database schema.**

Cassandra, PostgreSQL, S3, filesystem storage, or a future storage technology may implement that interface.

The architecture remains unchanged.

---

# Appendix A — Terminology

| Term                 | Definition                                                      |
| -------------------- | --------------------------------------------------------------- |
| Content Cache        | Logical service for storing and retrieving extraction artifacts |
| Cache Entry          | Independently addressable cached artifact                       |
| Flattened Content    | Linearized text representation                                  |
| Semantic Content     | Structure-preserving extracted representation                   |
| Chunk                | Independently addressable semantic content unit                 |
| Representation       | Logical form of extracted content                               |
| Processing Run       | Execution instance that produced derived artifacts              |
| Provenance           | Lineage connecting an artifact to its source and processing     |
| Classification       | Security classification associated with content                 |
| Tier                 | Logical storage-performance class                               |
| Hot                  | Low-latency active storage                                      |
| Warm                 | Intermediate storage                                            |
| Cold                 | Low-frequency archival storage                                  |
| Eviction             | Removal or movement from a performance tier                     |
| Retention Expiration | Point after which policy permits/remands deletion               |
| Legal Hold           | Explicit retention override preventing policy-driven deletion   |
| Break-Glass          | Explicit audited administrative access                          |
| Cache Search         | Metadata/content-artifact filtering                             |
| Search Projection    | Derived retrieval structure used for relevance search           |
| Atomic Publication   | Visibility only after complete verified persistence             |
| Core Conformance     | Minimum implementation contract                                 |
| Extended Conformance | Core plus extended capabilities                                 |
| Full Conformance     | Complete Design 1.26 behavioral compliance                      |

---

# Appendix B — Example Logical API

```text
get(id)

get_many(ids[])

put(entry)

put_many(entries[])

get_document(
    tenant_id,
    document_id,
    representation,
    version
)

get_chunks(
    tenant_id,
    document_id,
    representation,
    range
)

search(
    filter,
    sort,
    page
)

invalidate(
    selector
)

delete(
    selector
)

get_capabilities()

submit_operation(
    request
)

get_operation(
    operation_id
)
```

---

# Appendix C — Example Cache Entry

```yaml
cache_entry_id: ce-01JXYZ

tenant_id: tenant-A

document:
  id: document-123
  version: 7

representation:
  type: semantic
  schema_version: "1.26"

payload:
  storage: object
  location: object://cache/tenant-A/document-123/v7/semantic.json
  size_bytes: 18423000
  hash:
    algorithm: sha256
    value: ...

processing:
  run_id: run-2026-09-05-001
  producer:
    id: pdf-extractor
    version: "1.8.0"

classification:
  - INTERNAL

lifecycle:
  status: PUBLISHED
  tier: HOT
  created_at: 2026-09-05T10:00:00Z
  published_at: 2026-09-05T10:00:02Z
  expires_at: 2026-12-04T10:00:00Z
  legal_hold: false

provenance:
  source_hash: ...
```

---

# Appendix D — Example Batch Result

```yaml
succeeded:
  - entry_id: chunk-001
  - entry_id: chunk-002
  - entry_id: chunk-004

failed:
  - entry_id: chunk-003
    error_code: PAYLOAD_TOO_LARGE
    retryable: false

  - entry_id: chunk-005
    error_code: STORAGE_UNAVAILABLE
    retryable: true
```

---

# Appendix E — Example Capability Response

```yaml
api_version: "1.26"

implementation:
  name: hybrid
  version: "0.1"

capabilities:
  get_by_id: true
  batch_get: true
  document_reconstruction: true
  chunk_range: true
  metadata_search: true
  time_range_search: true
  representation_filter: true
  classification_filter: true
  processing_run_filter: true
  pagination: true
  streaming: true
  async_operations: true
  operation_polling: true
  operation_callbacks: false
  hot_tier: true
  warm_tier: true
  cold_tier: true

limits:
  max_batch_size: 1000
  max_inline_payload_bytes: 1048576
  max_search_limit: 1000

latency_profiles:
  hot:
    target_p95_ms: 50
  warm:
    target_p95_ms: 250
  cold:
    target_p95_ms: 1000
```

---

# Appendix F — Example Async Operation

Submission:

```json
{
  "type": "document_reconstruction",
  "document_id": "document-123",
  "representation": "semantic",
  "version": 7
}
```

Response:

```json
{
  "operation_id": "op-123",
  "status": "PENDING"
}
```

Polling:

```http
GET /operations/op-123
```

Running:

```json
{
  "operation_id": "op-123",
  "status": "RUNNING",
  "progress": {
    "completed": 417,
    "total": 1000
  }
}
```

Completed:

```json
{
  "operation_id": "op-123",
  "status": "COMPLETED",
  "result": {
    "location": "..."
  }
}
```

---

# Appendix G — Conformance Matrix

| Capability           |   Core   |    Extended   |      Full     |
| -------------------- | :------: | :-----------: | :-----------: |
| Identity             | Required |    Required   |    Required   |
| Put/Get              | Required |    Required   |    Required   |
| Atomic publication   | Required |    Required   |    Required   |
| Integrity            | Required |    Required   |    Required   |
| Tenant isolation     | Required |    Required   |    Required   |
| Authorization        | Required |    Required   |    Required   |
| Provenance           | Required |    Required   |    Required   |
| Capability discovery | Required |    Required   |    Required   |
| Batch operations     |     —    |    Required   |    Required   |
| Metadata search      |     —    |    Required   |    Required   |
| Pagination           |     —    |    Required   |    Required   |
| Chunk range          |     —    |    Required   |    Required   |
| Large content        |     —    |    Required   |    Required   |
| Retention            |     —    |    Required   |    Required   |
| Tiering              |     —    |    Required   |    Required   |
| Async operations     |     —    | If advertised | If advertised |
| Masking              |     —    |       —       |    Required   |
| Break-glass          |     —    |       —       |    Required   |
| Legal hold           |     —    |       —       |    Required   |
| Observability        |     —    |       —       |    Required   |
| Failure/recovery     |     —    |       —       |    Required   |

---

# Appendix H — Architectural Decision Summary

| Decision                    | Design 1.26                                                 |
| --------------------------- | ----------------------------------------------------------- |
| Cache abstraction           | Mandatory                                                   |
| Cassandra                   | Adapter only                                                |
| PostgreSQL                  | Valid adapter                                               |
| S3/object storage           | Valid payload/tier implementation                           |
| Filesystem                  | PoC/small implementation                                    |
| Large payload               | Object/chunk/reference supported                            |
| Batch writes                | Partial success                                             |
| Individual write            | Atomic publication                                          |
| Capability discovery        | Mandatory                                                   |
| Tenant scope                | Mandatory                                                   |
| Classification              | Security-aware                                              |
| Break-glass                 | Explicit + audited                                          |
| Retention                   | Explicit policy                                             |
| Retention enforcement       | Mandatory for production; mechanism implementation-specific |
| Expiration visibility       | Logically expired before physical deletion                  |
| Hot/warm/cold               | Logical tiers                                               |
| Cache Search                | Metadata filtering                                          |
| Semantic search             | Search Projection                                           |
| Async operations            | Status polling when supported                               |
| Callbacks                   | Optional/future                                             |
| Metrics                     | Required                                                    |
| Provenance                  | Required                                                    |
| Design 1.23 security        | Normative                                                   |
| Design 1.25 knowledge model | Extended, not replaced                                      |
| SNTP-9                      | Cache-backed benchmark integration                          |
| Conformance                 | Core / Extended / Full                                      |

---

# Appendix I — Design 1.26 In One Sentence

> **Synanton Design 1.26 defines a secure, provenance-aware, storage-independent Content Cache contract that decouples extraction artifacts from their physical storage and provides a stable foundation for knowledge processing, search projections, recalculation, lifecycle management, and RAG benchmarking.**