# Synanton Design 1.28 — Ingestion Plane

**Status:** Proposal
**Version:** 1.28
**Date:** 2026-09-05
**Audience:** Architects, platform engineers, developers, SRE, security engineers, integration engineers, and technical decision makers

---

## 1. Executive Summary

Synanton requires a first-class ingestion architecture for bringing external content and events into the platform in a controlled, durable, observable, secure, and repeatable manner.

This document defines **Design 1.28 — Ingestion Plane**.

The Ingestion Plane is responsible for acquiring source material and source events, validating and normalizing them, establishing source identity and provenance, applying ingestion-time security controls, and publishing immutable ingestion results into the downstream Synanton processing pipeline.

The Ingestion Plane does **not** interpret content, perform semantic annotation, execute AI inference, implement search indexing, or serve as the canonical knowledge store.

Its responsibility is narrower and fundamental:

> **Ingestion establishes what entered Synanton, where it came from, when it entered, under whose authority it entered, and which immutable source representation downstream processing may consume.**

The resulting architectural flow is:

```text
External Sources
      │
      ▼
Source Connectors
      │
      ▼
Acquisition
      │
      ▼
Validation
      │
      ▼
Normalization
      │
      ▼
Source Identity / Deduplication
      │
      ▼
Security Context
      │
      ▼
Ingestion Record
      │
      ├──────────────► Content Cache 1.26
      │
      └──────────────► Events 1.27
                              │
                              ▼
                    Extraction / Processing
                              │
                              ▼
                       Knowledge 1.25
```

The Ingestion Plane therefore forms the controlled boundary between the outside world and Synanton's internal data and execution planes.

---

# 2. Design Position

Design 1.28 introduces a dedicated ingestion architecture rather than treating ingestion as an implementation detail of individual connectors or extraction pipelines.

The architectural principle is:

> **Connectors acquire source material; the Ingestion Plane establishes durable source identity and ingestion state; downstream processing interprets the acquired content.**

This separation is essential because acquisition and interpretation evolve at different rates.

A new connector must not require a change to:

* annotation architecture;
* search architecture;
* knowledge representation;
* AI runtime;
* analytics storage;
* extraction implementation;
* workflow engine.

Likewise, changing an extraction or interpretation model must not require reimplementing source acquisition.

---

# 3. Relationship to Other Designs

Design 1.28 builds on and depends upon the existing Synanton architecture.

### 3.1 Design 1.23 — Security

Design 1.23 remains normative for:

* tenant isolation;
* resource authorization;
* classification;
* masking;
* representation selection;
* fail-closed behavior;
* security propagation;
* authorization assertions.

The Ingestion Plane must establish the security context required by downstream processing.

Ingestion must never weaken security classification or authorization.

### 3.2 Design 1.25 — Knowledge and Analytics

Design 1.25 defines:

* semantic chunks;
* annotations;
* derived knowledge;
* dependency graphs;
* processing runs;
* provenance;
* search projections;
* analytics.

Ingestion provides the source boundary from which these derived states originate.

The Ingestion Plane does not become the knowledge plane.

### 3.3 Design 1.26 — Content Cache

Design 1.26 defines the Content Cache as the stable logical interface for extracted and normalized content artifacts.

Ingestion may publish acquired source representations into Content Cache, but:

> **Content Cache is the authoritative content-artifact interface; Ingestion is authoritative for ingestion state and source acquisition state.**

These are different responsibilities.

### 3.4 Design 1.27 — Eventing and Workflow

Design 1.27 defines:

* events;
* commands;
* workflows;
* delivery semantics;
* retries;
* idempotency;
* replay;
* workflow state.

Ingestion uses these mechanisms but does not redefine them.

A successful ingestion normally emits durable events such as:

```text
SourceDiscovered
IngestionStarted
SourceAcquired
SourceNormalized
IngestionCompleted
IngestionFailed
```

### 3.5 Design 1.30 — AI Runtime

AI Runtime is a downstream execution capability.

Ingestion may initiate workflows that eventually invoke AI Runtime, but ingestion must remain independent of:

* a particular LLM;
* GPU runtime;
* inference provider;
* embedding model;
* model-serving technology.

---

# 4. Problem Statement

External content enters Synanton through heterogeneous mechanisms:

* filesystem;
* HTTP;
* APIs;
* object storage;
* databases;
* message queues;
* email;
* enterprise repositories;
* document management systems;
* web sources;
* manually uploaded files;
* streaming sources;
* scheduled synchronization;
* event-driven integrations.

These sources differ in:

* identity models;
* authentication;
* update semantics;
* timestamps;
* metadata;
* content formats;
* failure modes;
* rate limits;
* consistency;
* deletion semantics;
* security models.

Without a dedicated ingestion architecture, these differences leak into downstream processing.

This creates several risks:

1. duplicate content;
2. ambiguous source identity;
3. inconsistent provenance;
4. uncontrolled retries;
5. repeated downloads;
6. lost updates;
7. security-context loss;
8. connector-specific downstream behavior;
9. inability to replay ingestion;
10. inability to determine why content exists in Synanton;
11. accidental reprocessing;
12. poor operational observability.

Design 1.28 establishes common contracts that prevent these problems.

---

# 5. Goals

The Ingestion Plane has the following goals.

## 5.1 Source Independence

Support heterogeneous sources through a common ingestion contract.

## 5.2 Durable Source Identity

Every ingested source representation must have a stable identity.

## 5.3 Idempotent Ingestion

Repeated delivery of the same source state must not unintentionally create duplicate logical content.

## 5.4 Strong Provenance

Every ingested artifact must be traceable to its source and acquisition operation.

## 5.5 Security Preservation

Tenant, authorization, and classification context must survive ingestion.

## 5.6 Incremental Synchronization

Sources should be synchronized incrementally whenever the source supports reliable change detection.

## 5.7 Failure Isolation

A failing connector or source must not compromise unrelated ingestion.

## 5.8 Event-Driven Processing

Successful ingestion should integrate naturally with Design 1.27 eventing.

## 5.9 Replayability

Historical source states and ingestion operations should be recoverable where retention policy permits.

## 5.10 Operational Visibility

Operators must be able to determine:

* what was ingested;
* from where;
* when;
* by which connector;
* under which tenant;
* with which result;
* why an operation failed;
* whether it was retried;
* whether it produced a new source version.

---

# 6. Non-Goals

Design 1.28 does not define:

* universal document extraction;
* OCR implementation;
* audio transcription;
* video analysis;
* semantic annotation;
* ontology;
* knowledge representation;
* vector search;
* graph storage;
* search ranking;
* LLM architecture;
* GPU scheduling;
* analytics database;
* BI dashboards;
* a mandatory message broker;
* a mandatory object store;
* a mandatory database;
* a mandatory connector technology.

These remain separate architectural concerns.

---

# 7. Architectural Principles

## 7.1 Acquisition Is Not Interpretation

Ingestion determines what was acquired.

Extraction determines what the source contains.

Interpretation determines what Synanton understands.

These stages must remain independently replaceable.

```text
Acquire
   ↓
Preserve
   ↓
Normalize
   ↓
Extract
   ↓
Interpret
   ↓
Project
```

## 7.2 Source State Is Not Knowledge

A source document and an annotation describing that document are different objects.

Changing an annotation definition must not require reacquiring the original source.

## 7.3 Every Source Representation Has Identity

A source must be addressable independently from:

* connector execution;
* ingestion attempt;
* processing run;
* workflow execution.

## 7.4 Ingestion Is Idempotent

Retries are expected.

Duplicate delivery is expected.

The architecture must therefore make repeated ingestion safe.

## 7.5 Immutable Source Versions

A previously acquired source version must not silently change.

If the source changes, a new source version is created.

## 7.6 Provenance Is Mandatory

Ingestion without provenance is considered incomplete.

## 7.7 Security Context Is Data

Security context must not exist solely in connector runtime memory.

It must be represented in durable ingestion state.

## 7.8 Events Describe Facts

An ingestion event describes what happened.

It does not replace the authoritative ingestion record.

---

# 8. Ingestion Plane Architecture

The logical architecture is:

```text
                    ┌──────────────────────┐
                    │   External Sources   │
                    └──────────┬───────────┘
                               │
                               ▼
                    ┌──────────────────────┐
                    │   Source Connector   │
                    └──────────┬───────────┘
                               │
                               ▼
                    ┌──────────────────────┐
                    │ Acquisition Gateway  │
                    └──────────┬───────────┘
                               │
                  ┌────────────┴────────────┐
                  ▼                         ▼
          ┌───────────────┐        ┌────────────────┐
          │   Validation  │        │ Source Metadata│
          └───────┬───────┘        └───────┬────────┘
                  │                         │
                  └────────────┬────────────┘
                               ▼
                    ┌──────────────────────┐
                    │   Source Identity    │
                    │   / Versioning       │
                    └──────────┬───────────┘
                               │
                               ▼
                    ┌──────────────────────┐
                    │ Security Context     │
                    └──────────┬───────────┘
                               │
                               ▼
                    ┌──────────────────────┐
                    │ Ingestion State      │
                    └──────────┬───────────┘
                               │
                   ┌───────────┴────────────┐
                   ▼                        ▼
          ┌─────────────────┐       ┌─────────────────┐
          │ Content Cache   │       │ Eventing 1.27   │
          │ 1.26            │       │                 │
          └────────┬────────┘       └────────┬────────┘
                   │                         │
                   └────────────┬────────────┘
                                ▼
                       Processing Workflows
```

---

# 9. Core Concepts

## 9.1 Source

A logical external origin.

Examples:

```text
filesystem:/documents
sharepoint:site/document
s3://bucket/object
https://example/document/123
database:customer:123
```

A source is not necessarily a single physical file.

## 9.2 Source Resource

An individually addressable resource exposed by a source.

Examples:

* document;
* email;
* web page;
* image;
* audio recording;
* video;
* database record.

## 9.3 Source Version

An immutable representation of a source resource at a particular point in source history.

Conceptually:

```text
Source Resource
      │
      ├── Version 1
      ├── Version 2
      └── Version 3
```

## 9.4 Ingestion Attempt

A single attempt to acquire a source resource.

Multiple attempts may correspond to one successful source version.

## 9.5 Connector

An implementation responsible for interacting with a specific source system.

A connector translates external source semantics into the ingestion contract.

## 9.6 Ingestion Run

A bounded execution that processes one or more source resources.

Examples:

* synchronization run;
* scheduled import;
* manual upload;
* event-triggered ingestion.

## 9.7 Ingestion Manifest

A durable description of resources discovered or processed during a run.

It enables:

* reconciliation;
* auditing;
* incremental synchronization;
* retry;
* reporting.

---

# 10. Source Identity

Source identity is one of the most important contracts in the architecture.

A logical source identity should include sufficient information to distinguish:

```text
tenant
+
source_id
+
resource_id
```

A source version is identified separately.

A conceptual model is:

```text
SourceIdentity {
    tenant_id
    connector_id
    source_id
    resource_id
}
```

Version identity may include:

```text
SourceVersion {
    source_identity
    version_id
    content_digest
    source_revision
    observed_at
}
```

The exact persistence representation is implementation-defined.

---

# 11. Content Identity

Content digesting provides a stable mechanism for identifying byte-equivalent representations.

Recommended digest:

```text
SHA-256
```

The digest must be calculated over a precisely defined representation.

For example:

```text
raw source bytes
```

must not be confused with:

```text
normalized text
```

or:

```text
extracted semantic content
```

These are different artifacts and require different identities.

---

# 12. Identity vs Deduplication

Content equality and source identity are different concepts.

Two source resources may contain identical bytes:

```text
A/document.pdf
B/document.pdf
```

They may still represent different logical resources.

Therefore:

> **Digest equality must not automatically imply source-resource identity.**

Content digest may be used for:

* storage deduplication;
* integrity verification;
* change detection;
* cache reuse.

It must not silently merge independent source resources.

---

# 13. Version Detection

Connectors should expose the strongest reliable source version signal available.

Preferred signals include:

1. source-native revision;
2. immutable object version;
3. ETag;
4. modification timestamp plus size;
5. content digest;
6. connector-specific change token.

Weak signals must not be treated as authoritative when stronger signals are available.

A connector should declare its version-detection strategy.

---

# 14. Incremental Synchronization

Sources should support incremental synchronization where possible.

A connector may maintain a synchronization cursor:

```text
source
   │
   ▼
change cursor
   │
   ▼
changed resources
   │
   ▼
ingestion
```

Examples of cursors:

* API continuation token;
* modification timestamp;
* sequence number;
* source revision;
* event offset.

The cursor itself is durable state.

It must not exist only in connector process memory.

---

# 15. Synchronization Semantics

A synchronization run should produce an explicit result:

```text
SyncResult {
    discovered
    changed
    unchanged
    deleted
    failed
    skipped
}
```

This allows operators to distinguish:

* source was checked and nothing changed;
* source was changed;
* source could not be checked;
* source reported deletion;
* ingestion failed.

These states must not be collapsed into a generic "success/failure" result.

---

# 16. Deletion Semantics

Deletion is a first-class ingestion event.

A source resource disappearing from a synchronization result must not automatically mean that the resource was deleted.

The connector must distinguish:

```text
DELETED
NOT_VISIBLE
NOT_FOUND
ACCESS_DENIED
SYNC_INCOMPLETE
UNKNOWN
```

This prevents incomplete synchronization from accidentally causing destructive downstream actions.

---

# 17. Tombstones

Where deletion is authoritative, ingestion should produce a durable tombstone.

Conceptually:

```text
Source Resource
      │
      ▼
Tombstone
      │
      ├── Content Cache invalidation
      ├── Search projection removal
      ├── Knowledge invalidation
      └── Analytics event
```

A tombstone must retain sufficient identity and provenance to allow downstream systems to determine what was deleted.

---

# 18. Acquisition

Acquisition is the act of obtaining source material.

The acquisition layer must support:

* streaming;
* bounded buffering;
* resumable transfer where available;
* checksum verification;
* content-length validation;
* timeout;
* cancellation;
* rate limiting;
* source-specific authentication.

Large content must not require loading the entire payload into process memory.

---

# 19. Streaming

The default architecture should support streaming acquisition.

Conceptually:

```text
Source
  │
  ▼
Input Stream
  │
  ├── Digest
  ├── Size
  ├── Validation
  └── Durable Write
         │
         ▼
      Artifact
```

This permits ingestion of large:

* PDFs;
* videos;
* audio files;
* archives;
* datasets.

---

# 20. Atomic Publication

A source artifact must not become visible as complete before its content is successfully written and verified.

Recommended sequence:

```text
Acquire
   ↓
Write temporary artifact
   ↓
Calculate / verify digest
   ↓
Verify metadata
   ↓
Commit metadata
   ↓
Atomically publish
```

A partially downloaded artifact must never appear as a valid source version.

---

# 21. Content Storage

The Ingestion Plane should not impose a single physical storage technology.

It may use:

* object storage;
* filesystem;
* Content Cache;
* database metadata plus object storage;
* another durable artifact store.

Design 1.26 remains responsible for the logical Content Cache contract.

---

# 22. Metadata

Every successful ingestion should capture metadata appropriate to the source.

Common fields include:

```text
tenant_id
source_id
resource_id
connector_id
content_type
content_length
content_digest
source_revision
source_modified_at
observed_at
ingested_at
filename
source_uri
security_context
```

Metadata must distinguish:

* source-provided metadata;
* connector-generated metadata;
* Synanton-generated metadata.

---

# 23. Metadata Trust

Source metadata must not automatically be trusted as authoritative security metadata.

For example:

```text
source_metadata.classification = PUBLIC
```

must not override Synanton policy.

Metadata should therefore have an explicit trust/provenance model.

Conceptually:

```text
Metadata {
    value
    origin
    observed_at
    confidence
}
```

---

# 24. MIME and Content-Type Validation

Declared content type and detected content type may differ.

The ingestion pipeline should preserve both:

```text
declared_content_type
detected_content_type
```

Where practical, content sniffing should be used to detect obvious inconsistencies.

Security-sensitive formats must fail closed when validation cannot establish an acceptable representation.

---

# 25. Archive and Container Handling

Archives require explicit policy.

Ingestion should record:

* archive type;
* entry count;
* total uncompressed size;
* nesting depth;
* suspicious entries;
* extraction policy.

Protection against decompression bombs and excessive nesting is mandatory for production deployments.

Archive extraction itself may be delegated to downstream extraction workflows.

---

# 26. Validation

Validation occurs before downstream publication.

Validation should include, where applicable:

* size;
* digest;
* content type;
* encoding;
* structural integrity;
* source authorization;
* tenant mapping;
* required metadata;
* source revision;
* connector contract.

Validation failures must produce explicit failure reasons.

---

# 27. Normalization

Normalization produces a stable representation without attempting semantic interpretation.

Examples:

* canonical metadata encoding;
* line-ending normalization where explicitly defined;
* Unicode normalization where required;
* filename normalization;
* canonical timestamp representation.

Normalization must not silently destroy source fidelity.

---

# 28. Raw vs Normalized Representation

Where normalization changes content, both representations may need to be retained:

```text
Raw Source Artifact
        │
        ▼
Normalized Artifact
```

The raw representation remains the strongest provenance anchor.

Normalization must therefore be:

* deterministic;
* versioned;
* reproducible.

---

# 29. Connector Contract

A connector should expose capabilities conceptually similar to:

```text
discover()
acquire(resource)
get_version(resource)
list_changes(cursor)
acknowledge(cursor)
```

Optional capabilities include:

```text
watch()
delete()
restore()
get_acl()
get_metadata()
```

The exact API is implementation-defined.

---

# 30. Connector Capability Discovery

Connectors should declare supported capabilities.

Example:

```yaml
connector:
  id: sharepoint
  capabilities:
    incremental_sync: true
    change_notifications: true
    source_versions: true
    deletion_events: true
    acl_metadata: true
    resumable_download: true
```

This allows orchestration to choose the appropriate synchronization strategy.

---

# 31. Connector Isolation

Connectors must be isolated from the core ingestion service.

A faulty connector must not be able to:

* corrupt global ingestion state;
* bypass tenant isolation;
* publish arbitrary security classifications;
* consume unbounded resources;
* block unrelated connectors.

Isolation may be implemented using:

* processes;
* containers;
* worker pools;
* resource quotas;
* network policies;
* execution sandboxes.

---

# 32. Authentication

Connector authentication is source-specific.

Supported mechanisms may include:

* OAuth;
* service accounts;
* API keys;
* mTLS;
* signed requests;
* filesystem credentials;
* workload identity.

Secrets must not be stored in ingestion records.

Secrets belong to the platform secret-management mechanism.

---

# 33. Authorization Context

Every ingestion operation must have an explicit authorization context.

Conceptually:

```text
IngestionRequest {
    tenant_id
    actor
    source
    authorization_assertion
    requested_scope
}
```

The connector must not manufacture tenant identity.

Tenant context must originate from trusted platform control-plane state.

---

# 34. Security Classification

Where source classification is available, ingestion should preserve it as source metadata.

Synanton may additionally determine or validate classification.

However:

> **Ingestion must not assume that source-provided classification is sufficient for Design 1.23 security enforcement.**

Classification remains subject to the normative security model.

---

# 35. ACL Acquisition

Connectors that can retrieve source ACLs should preserve them as source security metadata.

Example:

```text
Source ACL
   │
   ▼
Normalized Authorization Metadata
   │
   ▼
Design 1.23 Security Mapping
```

ACL acquisition must not be confused with authorization enforcement.

---

# 36. Provenance

The minimum provenance chain should allow:

```text
Source
  ↓
Source Resource
  ↓
Source Version
  ↓
Ingestion Run
  ↓
Ingestion Attempt
  ↓
Artifact
  ↓
Processing Run
  ↓
Knowledge
```

This allows downstream systems to answer:

> "Why does this knowledge object exist?"

by tracing it back to the acquired source representation.

---

# 37. Processing Run Relationship

Ingestion and processing runs are separate.

Example:

```text
Ingestion Run 100
        │
        ▼
Source Version A
        │
        ├── Processing Run 201
        ├── Processing Run 202
        └── Processing Run 203
```

Reprocessing the same source must not require another source acquisition unless the source itself changed.

---

# 38. Ingestion State Machine

A conceptual state machine is:

```text
DISCOVERED
    │
    ▼
SCHEDULED
    │
    ▼
ACQUIRING
    │
    ├──────────────► FAILED
    │
    ▼
VALIDATING
    │
    ├──────────────► FAILED
    │
    ▼
PUBLISHED
    │
    ▼
COMPLETED
```

Additional states may include:

```text
CANCELLED
SKIPPED
UNCHANGED
DELETED
QUARANTINED
```

State transitions must be durable.

---

# 39. Idempotency

Every ingestion request must have an idempotency identity.

Conceptually:

```text
tenant_id
+
source_id
+
resource_id
+
source_version
```

If the source does not expose a reliable version, the connector must use an explicitly documented change-detection strategy.

---

# 40. Duplicate Delivery

If the same source version is delivered multiple times:

```text
attempt 1 ──┐
attempt 2 ──┼──► same logical source version
attempt 3 ──┘
```

the platform should converge on one authoritative source version.

Attempts remain observable.

---

# 41. Retry Policy

Retries should distinguish:

### Retryable

* network timeout;
* temporary source unavailable;
* rate limiting;
* transient storage failure;
* temporary broker failure.

### Non-retryable

* invalid credentials;
* unsupported format;
* permanently forbidden resource;
* malformed source data;
* policy violation.

Retry decisions must be explicit rather than based only on exception type.

---

# 42. Backoff

Retries should use bounded exponential backoff with jitter.

Conceptually:

```text
attempt 1 → short delay
attempt 2 → longer delay
attempt 3 → longer delay
...
attempt N → dead-letter / quarantine
```

Maximum retry duration must be configurable.

---

# 43. Dead-Letter and Quarantine

Permanent ingestion failures must be retained for operational inspection.

Two concepts should remain distinct:

### Dead Letter

The ingestion operation could not complete and requires investigation or retry.

### Quarantine

The source artifact was acquired but must not be published downstream because of a security, validation, malware, or policy concern.

---

# 44. Event Model

Design 1.28 integrates with Design 1.27 through durable events.

Recommended event types include:

```text
SourceDiscovered
SourceChanged
SourceUnchanged
SourceDeleted
IngestionStarted
SourceAcquired
SourceValidated
SourcePublished
IngestionCompleted
IngestionFailed
SourceQuarantined
```

Events are facts, not commands.

---

# 45. Event Ordering

Consumers must not assume global event ordering.

Ordering may be guaranteed only within an explicitly defined scope, such as:

```text
tenant + source resource
```

Consumers must therefore tolerate:

* retries;
* duplication;
* delayed delivery;
* replay.

---

# 46. Transactional Publication

Where ingestion state and event publication must be coordinated, the architecture should use the Design 1.27 transactional outbox pattern.

Conceptually:

```text
Ingestion State Change
        │
        ├── Ingestion Record
        │
        └── Outbox Event
                  │
                  ▼
                Broker
```

This prevents:

```text
state committed
event lost
```

and:

```text
event published
state lost
```

---

# 47. Workflow Integration

Long-running ingestion should be represented as a workflow when appropriate.

Example:

```text
Start Sync
    ↓
Discover
    ↓
Acquire
    ↓
Validate
    ↓
Publish
    ↓
Emit Event
    ↓
Complete
```

Design 1.27 workflow infrastructure should coordinate this execution.

The Ingestion Plane owns ingestion semantics; the workflow engine owns workflow execution.

---

# 48. Manual Upload

Manual upload is a connector-independent ingestion source.

The logical flow is:

```text
User
↓
Upload
↓
Ingestion Request
↓
Validation
↓
Source Identity
↓
Artifact Publication
↓
Ingestion Event
```

Manual upload must use the same ingestion contracts as automated sources.

It must not create a special security model.

---

# 49. Batch Ingestion

Batch ingestion should support large imports without requiring one workflow execution per entire dataset.

Conceptually:

```text
Batch
│
├── Resource A
├── Resource B
├── Resource C
└── ...
```

Each resource should retain independent state.

A failure of Resource B must not invalidate successful acquisition of Resource A.

---

# 50. Partial Success

Batch ingestion must explicitly support partial success.

Example:

```text
1000 discovered
  ↓
970 acquired
  ↓
960 published
  ↓
10 quarantined
  ↓
10 failed
```

The batch must not simply be marked "failed."

---

# 51. Rate Limiting

Rate limits may exist at multiple levels:

```text
global
tenant
connector
source
remote endpoint
```

The architecture should support independent policies for each level.

This prevents one source from exhausting global ingestion capacity.

---

# 52. Backpressure

The ingestion plane must provide backpressure when downstream systems cannot keep up.

For example:

```text
Source
  ↓
Ingestion
  ↓
Eventing
  ↓
Processing
```

If processing capacity falls, ingestion should not necessarily continue without bounds.

Backpressure mechanisms may include:

* queue depth limits;
* concurrency limits;
* source polling reduction;
* workflow throttling;
* tenant quotas.

---

# 53. Resource Limits

Connectors and ingestion workers should enforce limits for:

* maximum artifact size;
* maximum metadata size;
* maximum concurrent downloads;
* maximum request duration;
* maximum decompression ratio;
* maximum batch size;
* maximum retry count.

These are security and availability controls.

---

# 54. Tenant Isolation

Every ingestion record must have an explicit tenant scope.

Conceptually:

```text
IngestionRecord {
    tenant_id
    ...
}
```

A connector must never infer tenant identity from untrusted source content.

Cross-tenant access must be impossible by default.

---

# 55. Cross-Tenant Connectors

Some administrative connectors may serve multiple tenants.

Such connectors must still create tenant-scoped ingestion records.

A connector's administrative scope must not become the data scope.

---

# 56. Observability

Every ingestion operation should provide:

* trace ID;
* correlation ID;
* causation ID;
* ingestion run ID;
* source identity;
* tenant ID;
* connector ID.

Metrics should include:

```text
ingestion_requests_total
ingestion_success_total
ingestion_failure_total
ingestion_bytes_total
ingestion_duration
ingestion_retries_total
ingestion_quarantine_total
ingestion_duplicates_total
ingestion_lag
```

---

# 57. Operational Metrics

Important service-level indicators include:

### Acquisition latency

Time from acquisition request to source bytes available.

### Publication latency

Time from source acquisition to durable publication.

### End-to-end ingestion latency

Time from source discovery to completed ingestion.

### Failure rate

Failed ingestion operations divided by attempted operations.

### Duplicate rate

Repeated deliveries of already-known source versions.

### Connector health

Success/failure and latency per connector.

---

# 58. Ingestion Lag

For synchronizing sources, ingestion lag should be measurable:

```text
current source state
        -
latest successfully ingested state
```

For event-driven sources:

```text
source event timestamp
        -
Synanton ingestion timestamp
```

This allows operational detection of stale synchronization.

---

# 59. Auditability

The platform should allow operators to answer:

1. What source produced this artifact?
2. Which connector acquired it?
3. Which tenant owns it?
4. Which actor authorized ingestion?
5. When was it acquired?
6. What source version was observed?
7. What content digest was calculated?
8. Was the artifact retried?
9. Was it quarantined?
10. Which processing runs consumed it?

---

# 60. Reconciliation

Connectors should support reconciliation where practical.

Reconciliation compares:

```text
source state
    vs
Synanton ingestion state
```

It can detect:

* missing resources;
* stale resources;
* unexpected deletions;
* failed ingestion;
* cursor corruption;
* connector bugs.

Reconciliation should be an explicit operational capability rather than an accidental side effect of normal ingestion.

---

# 61. Source of Truth

The architecture defines clear ownership:

| Concern                  | Authoritative Plane            |
| ------------------------ | ------------------------------ |
| Source acquisition state | Ingestion                      |
| Source version identity  | Ingestion                      |
| Acquired artifact        | Content Cache / artifact store |
| Extraction result        | Processing / Content Cache     |
| Knowledge                | Knowledge Plane                |
| Search index             | Search projection              |
| Analytics facts          | Analytics Plane                |
| Workflow execution state | Workflow Plane                 |
| AI execution state       | AI Runtime                     |
| Authorization policy     | Security / Control Plane       |

No plane should silently become authoritative for another plane's state.

---

# 62. Content Cache Integration

The recommended publication model is:

```text
Ingestion
    │
    ▼
Source Artifact
    │
    ▼
Content Cache
    │
    ├── raw/source representation
    ├── normalized representation
    └── metadata/provenance
```

The exact artifact classes depend on Design 1.26 implementation.

The key requirement is that downstream extraction can reference an immutable source version.

---

# 63. Extraction Boundary

Ingestion should emit an event such as:

```text
SourcePublished
```

The extraction workflow can then consume it.

```text
SourcePublished
      │
      ▼
Extraction Workflow
      │
      ▼
Semantic Content
      │
      ▼
Semantic Chunks
      │
      ▼
Knowledge
```

This prevents connectors from invoking extraction implementations directly.

---

# 64. Change Propagation

When a source changes:

```text
Source Version N
       │
       ▼
Source Version N+1
       │
       ▼
SourceChanged
       │
       ▼
Extraction
       │
       ▼
Knowledge recalculation
       │
       ▼
Search / Analytics
```

The new source version becomes the input to downstream processing.

Existing knowledge derived from the old version remains traceable until invalidated or replaced according to Design 1.25 policy.

---

# 65. Source Deletion Propagation

A deletion event may initiate:

```text
SourceDeleted
      │
      ├── Content Cache tombstone
      ├── Search invalidation
      ├── Knowledge invalidation
      └── Analytics update
```

The deletion workflow must preserve provenance.

Deleting current source availability must not necessarily destroy historical audit information.

Retention and legal-hold policy governs that distinction.

---

# 66. Security Reclassification

If source security metadata changes without content changing:

```text
Content unchanged
Security state changed
```

the platform should not reacquire or re-extract content unnecessarily.

Instead:

```text
Security change
      ↓
Policy / security propagation
      ↓
Affected derived state
      ↓
Invalidate / recalculate as required
```

This is consistent with the separation established in Designs 1.23 and 1.25.

---

# 67. Replay

Ingestion replay must be explicit.

Possible replay targets include:

```text
reacquire source
republish existing artifact
re-emit ingestion event
re-run downstream processing
```

These operations are not equivalent.

A replay request must specify which stage is being replayed.

---

# 68. Replay Safety

Every ingestion event consumer should have an explicit replay-safety classification:

```text
SAFE
SAFE_WITH_IDEMPOTENCY
REQUIRES_RECONCILIATION
NOT_REPLAYABLE
```

For example:

* indexing an existing source version may be safe;
* sending an external notification may require special handling;
* destructive source-side operations should not be replayed automatically.

---

# 69. Exactly-Once vs At-Least-Once

The architecture should prefer:

> **At-least-once delivery + deterministic identity + idempotent consumers**

over attempting to implement global exactly-once semantics.

Exactly-once effects may be achieved locally through:

* unique identities;
* transactions;
* deduplication;
* idempotency keys.

Global exactly-once delivery is not an architectural requirement.

---

# 70. Failure Model

Failures are expected at every boundary:

```text
source
connector
network
authentication
download
storage
validation
broker
workflow
consumer
```

The architecture must therefore converge after failure rather than assuming failure-free execution.

---

# 71. Crash Recovery

A worker crash must leave durable state sufficient to determine:

```text
what was being processed
what was successfully committed
what remains incomplete
whether retry is safe
```

Transient process memory must not be required for recovery.

---

# 72. Exactly One Artifact Publication

For a logical source version, the system should converge on one published artifact identity even when acquisition occurs multiple times.

Conceptually:

```text
download 1 ─┐
download 2 ─┼──► content digest X
download 3 ─┘
                  │
                  ▼
           one logical artifact
```

Temporary artifacts may exist during failed attempts but must not become authoritative.

---

# 73. Connector Scheduling

Connector scheduling may be:

* periodic;
* event-driven;
* manually triggered;
* workflow-triggered;
* hybrid.

Scheduling belongs to execution/orchestration.

Connector implementations should not independently create arbitrary scheduling infrastructure.

---

# 74. Connector Health

Each connector should expose health information such as:

```text
availability
authentication status
last successful synchronization
last failure
current lag
rate-limit state
```

Connector health should be visible independently from global ingestion health.

---

# 75. Configuration

Connector configuration should distinguish:

### Static configuration

* connector type;
* source endpoint;
* capability flags.

### Secret configuration

* credentials;
* tokens;
* certificates.

### Runtime policy

* schedule;
* concurrency;
* limits;
* retry policy.

### Tenant policy

* allowed source types;
* retention;
* classification policy;
* quotas.

---

# 76. Configuration Versioning

Configuration changes affecting ingestion behavior should be versioned.

An ingestion run should record the configuration version under which it executed.

This improves reproducibility.

---

# 77. Schema Versioning

Ingestion records and events must be versioned independently.

Example:

```text
IngestionEvent v1
IngestionEvent v2
```

Consumers should be able to tolerate compatible schema evolution.

Breaking changes require explicit version migration.

---

# 78. API Contract

A conceptual ingestion command is:

```text
IngestSourceResource
```

with fields such as:

```text
tenant_id
source_id
resource_id
source_version
connector_id
authorization_context
idempotency_key
requested_at
```

The response should identify the durable ingestion operation rather than requiring synchronous completion.

---

# 79. Asynchronous Operations

Large ingestion operations should be asynchronous.

Example:

```text
POST /ingestions
        │
        ▼
   operation_id
        │
        ▼
GET /ingestions/{operation_id}
```

Possible states:

```text
QUEUED
RUNNING
COMPLETED
PARTIAL
FAILED
CANCELLED
```

This is consistent with the asynchronous operation model established in Design 1.26.

---

# 80. Cancellation

Cancellation must distinguish:

```text
cancel requested
```

from:

```text
cancel completed
```

A worker may already have committed an artifact when cancellation arrives.

Therefore cancellation must converge through durable state rather than relying on process interruption.

---

# 81. Priority

Ingestion may support priority classes:

```text
interactive
high
normal
bulk
reconciliation
```

Priority must not bypass tenant or security controls.

Priority is an execution concern, not an authorization mechanism.

---

# 82. Quotas

The platform should support configurable quotas for:

* bytes per tenant;
* resources per tenant;
* concurrent acquisitions;
* requests per connector;
* processing backlog.

Quota enforcement should occur before unbounded resource consumption.

---

# 83. Cost Attribution

Ingestion should expose sufficient metrics for cost attribution:

```text
bytes downloaded
requests
execution duration
connector operations
storage consumed
retry count
```

Analytics may aggregate these into cost metrics.

The ingestion plane remains responsible only for producing authoritative operational facts.

---

# 84. Data Quality

Ingestion should expose quality indicators where measurable.

Examples:

```text
content_complete
metadata_complete
source_revision_known
digest_verified
content_type_verified
acl_available
```

Quality indicators are metadata, not semantic judgments.

---

# 85. Security and Malware Scanning

Where required by deployment policy, acquired artifacts may pass through a security scanning stage:

```text
Acquire
   ↓
Security Scan
   │
   ├── CLEAN ─────► Publish
   │
   └── SUSPICIOUS ─► Quarantine
```

Security scanning must not be assumed to provide complete content validation.

---

# 86. Untrusted Content

All external content must be treated as untrusted input.

This includes:

* filenames;
* metadata;
* document contents;
* embedded URLs;
* archive entries;
* markup;
* scripts;
* prompts embedded in documents.

Ingestion must not execute source content.

Prompt injection defense is primarily a downstream AI/runtime concern, but ingestion must preserve source provenance so downstream systems can identify untrusted content.

---

# 87. Network Security

Connector execution should use:

* outbound network policy;
* endpoint allowlists where appropriate;
* TLS validation;
* certificate verification;
* credential isolation;
* SSRF protection.

Source-controlled URLs must not automatically grant arbitrary network access.

---

# 88. Multi-Stage Trust Boundary

The architecture should model:

```text
Untrusted Source
      ↓
Connector
      ↓
Validated Artifact
      ↓
Trusted Internal Representation
      ↓
Processing
```

Validation and publication form a trust boundary.

---

# 89. Retention

Ingestion retention must be aligned with Design 1.26 retention and legal-hold policy.

Retention may differ for:

* raw source artifacts;
* normalized artifacts;
* ingestion metadata;
* tombstones;
* audit records;
* failed attempts.

A failed attempt may require shorter retention than a successfully published source version.

---

# 90. Legal Hold

When legal hold applies:

```text
retention expiry
      ≠
automatic deletion
```

Ingestion artifacts subject to legal hold must remain protected from normal retention cleanup.

---

# 91. Backfill

Backfill is the controlled ingestion of historical source data.

Backfills should support:

* bounded date ranges;
* source filters;
* tenant scope;
* concurrency controls;
* checkpointing;
* pause/resume;
* progress reporting.

Backfill must not starve interactive ingestion.

---

# 92. Reconciliation vs Backfill

These are different operations.

### Backfill

Intentionally imports historical source state.

### Reconciliation

Detects divergence between source state and Synanton state.

They may share infrastructure but must remain separate operational concepts.

---

# 93. Ingestion and Analytics

Analytics should consume ingestion events asynchronously.

Useful analytical facts include:

* resources discovered;
* resources ingested;
* bytes acquired;
* ingestion duration;
* connector failures;
* retries;
* quarantine rate;
* synchronization lag.

Analytics must not become the operational source of truth.

---

# 94. Ingestion and Search

Search indexes consume successfully published and processed artifacts.

Ingestion must not write directly into search indexes.

The correct flow is:

```text
Ingestion
   ↓
Source Artifact
   ↓
Extraction / Knowledge
   ↓
Search Projection
```

This preserves search replaceability.

---

# 95. Ingestion and Knowledge

Knowledge must reference the source version from which it was derived.

Example:

```text
Knowledge Object
      │
      └── provenance
             │
             └── Source Version
                    │
                    └── Ingestion Record
```

This enables recalculation when source content changes.

---

# 96. Ingestion and Equalix

Equalix remains responsible for controlled recalculation where defined by Design 1.25.

A source change may initiate:

```text
SourceChanged
      ↓
Impact Resolution
      ↓
Equalix
      ↓
Recalculation Plan
      ↓
Processing
```

The Ingestion Plane must not duplicate Equalix's dependency analysis.

---

# 97. Ingestion and AI Runtime

AI Runtime may be invoked downstream for:

* OCR;
* transcription;
* classification;
* summarization;
* semantic enrichment;
* embedding generation;
* other AI processing.

The Ingestion Plane only records the source artifact and initiates downstream processing.

It must not embed AI execution semantics into the connector model.

---

# 98. Example: PDF Ingestion

```text
PDF Source
    │
    ▼
Connector
    │
    ▼
Acquire bytes
    │
    ▼
SHA-256
    │
    ▼
Validate PDF
    │
    ▼
Create Source Version
    │
    ▼
Publish artifact
    │
    ▼
SourcePublished
    │
    ▼
PDF Extraction
    │
    ▼
Semantic Content
    │
    ▼
Semantic Chunks
    │
    ▼
Knowledge
```

The PDF extractor is not part of the connector.

---

# 99. Example: Changed Document

```text
Document revision 41
        │
        ▼
Already ingested
        │
        ▼
No new source version
```

Later:

```text
Document revision 42
        │
        ▼
SourceChanged
        │
        ▼
Acquire
        │
        ▼
Source Version 42
        │
        ▼
Downstream processing
```

---

# 100. Example: Failed Download

```text
Discover
   ↓
Acquire
   ↓
Timeout
   ↓
Retry
   ↓
Timeout
   ↓
Retry
   ↓
Success
   ↓
Publish
```

The ingestion record should retain the attempt history.

---

# 101. Example: Quarantined Artifact

```text
Acquire
   ↓
Validate
   ↓
Security Scan
   ↓
Suspicious
   ↓
Quarantine
```

The artifact must not emit a normal `SourcePublished` event.

Instead:

```text
SourceQuarantined
```

is emitted.

---

# 102. Example: Source Deletion

```text
Connector synchronization
        │
        ▼
Authoritative deletion
        │
        ▼
SourceDeleted
        │
        ├── Content Cache tombstone
        ├── Search invalidation
        └── Knowledge invalidation
```

A failed synchronization must not generate this event.

---

# 103. Example: Multi-Tenant Batch

```text
Batch
│
├── Tenant A → 100 resources
├── Tenant B → 200 resources
└── Tenant C → 150 resources
```

Each resource remains independently tenant-scoped.

A batch-level administrative identity must never replace resource-level tenant scope.

---

# 104. Operational Isolation

The Ingestion Plane must be isolated from interactive workloads.

Recommended resource pools:

```text
interactive ingestion
bulk ingestion
reconciliation
backfill
```

Bulk imports must not exhaust all acquisition workers.

---

# 105. Capacity Planning

Initial implementation should establish workload-based targets for:

* resources/second;
* MB/s;
* concurrent connectors;
* concurrent acquisitions;
* maximum artifact size;
* event throughput;
* synchronization lag.

Targets should be derived from workload modelling rather than arbitrary technology limits.

---

# 106. Initial Performance Targets

For initial production planning:

* p95 ingestion control-plane operation: **< 500 ms** excluding source transfer;
* sustained acquisition throughput: **workload-dependent**;
* event publication: sufficient capacity for peak ingestion event rate plus **50% headroom**;
* no single tenant should be able to exhaust global worker capacity;
* large artifacts must use streaming or bounded buffering.

The exact sustained MB/s and resources/second targets must be established from benchmark data.

---

# 107. Storage Abstraction

The Ingestion Plane must not depend architecturally on a particular storage engine.

The logical contracts are:

```text
IngestionStateStore
ArtifactStore
OutboxStore
ConnectorStateStore
```

Physical implementations may differ by deployment.

---

# 108. Database Responsibilities

A relational database is a suitable initial implementation candidate for:

* ingestion state;
* source identity;
* synchronization cursors;
* idempotency;
* operation state;
* connector configuration metadata;
* outbox records.

Large content should generally remain in artifact/object storage rather than relational rows.

---

# 109. Object Storage Responsibilities

Object storage is appropriate for:

* large source artifacts;
* immutable source versions;
* large normalized artifacts;
* quarantine artifacts.

The object key must not be the sole source of identity.

Identity remains a logical platform concept.

---

# 110. Event Broker

The event broker remains abstract according to Design 1.27.

Possible implementations include:

* Kafka;
* NATS;
* RabbitMQ;
* cloud-managed event systems.

The Ingestion Plane must not expose broker-specific semantics as its domain model.

---

# 111. API vs Eventing

Commands and events have different purposes.

Example:

```text
POST /ingestions
```

means:

> Please ingest this source.

Whereas:

```text
SourcePublished
```

means:

> This source version has been published.

The two concepts must remain distinct.

---

# 112. Idempotency Keys

API clients may provide explicit idempotency keys.

The platform should persist them for the configured retention period.

Repeated commands with the same idempotency identity should converge to the same logical operation.

---

# 113. Security of Idempotency

Idempotency keys must be scoped appropriately.

A key belonging to Tenant A must not collide with or access the operation of Tenant B.

Conceptually:

```text
tenant_id + idempotency_key
```

rather than globally:

```text
idempotency_key
```

---

# 114. Audit Events

Operational audit events should be distinguishable from domain ingestion events.

For example:

```text
IngestionCompleted
```

describes domain state.

Whereas:

```text
OperatorRetriedIngestion
```

describes an administrative action.

Both may be needed for compliance.

---

# 115. Administrative Operations

Operators should be able to:

* retry failed ingestion;
* pause a connector;
* resume a connector;
* trigger reconciliation;
* start backfill;
* inspect quarantined artifacts;
* inspect source versions;
* inspect provenance;
* cancel active operations.

Administrative operations must remain authorization-controlled.

---

# 116. Break-Glass Access

Where Design 1.23 permits break-glass administrative access, ingestion must preserve:

* actor identity;
* reason;
* timestamp;
* scope;
* approval context where applicable.

Break-glass access must not silently modify tenant ownership.

---

# 117. Testing Strategy

The Ingestion Plane requires multiple test levels.

## Unit

Test:

* identity;
* version detection;
* state transitions;
* retry decisions;
* deletion classification;
* metadata normalization.

## Contract

Test connector contracts against representative source behavior.

## Integration

Test:

* artifact storage;
* database;
* event broker;
* security mapping.

## End-to-End

Test:

```text
source
→ ingestion
→ artifact
→ event
→ processing
→ knowledge
```

## Failure Testing

Test:

* worker crash;
* network timeout;
* partial download;
* duplicate delivery;
* broker outage;
* storage outage;
* connector failure;
* cursor corruption.

---

# 118. Conformance Test Suite

A connector is conformant only if it passes the relevant connector contract tests.

Minimum tests include:

1. stable source identity;
2. version detection;
3. idempotent repeated acquisition;
4. correct content digest;
5. explicit failure classification;
6. deletion semantics;
7. authorization context;
8. tenant isolation;
9. pagination/change cursor behavior;
10. cancellation;
11. retry classification;
12. provenance completeness.

Capability-specific tests apply only where a connector declares support.

---

# 119. Security Testing

Required security tests include:

* cross-tenant access attempts;
* forged tenant identifiers;
* forged source classifications;
* unauthorized connector use;
* credential leakage;
* SSRF;
* malicious filenames;
* archive traversal;
* decompression bombs;
* oversized payloads;
* partial artifact publication;
* replay abuse;
* broken access-control metadata.

---

# 120. Chaos Testing

Production readiness should include controlled failure testing for:

```text
connector crash
worker crash
database restart
object-store outage
broker outage
network partition
credential expiration
rate limiting
partial download
duplicate event delivery
```

The expected result is convergence to a consistent durable state.

---

# 121. Implementation Architecture

A reference implementation should use clear boundaries:

```text
ingestion-domain
ingestion-application
connector-spi
connector-runtime
artifact-store
ingestion-store
event-publisher
workflow-adapter
security-adapter
observability
```

Domain logic must remain independent of framework and infrastructure.

---

# 122. Domain Model

The initial domain model should include:

```text
Source
SourceResource
SourceVersion
IngestionRun
IngestionAttempt
IngestionOperation
SynchronizationCursor
IngestionManifest
ArtifactReference
IngestionFailure
QuarantineRecord
```

The exact persistence model is implementation-defined.

---

# 123. Connector SPI

A connector SPI should define the minimum contract.

Conceptually:

```text
Connector {
    discover(...)
    acquire(...)
    capabilities()
}
```

Optional capabilities are exposed explicitly rather than through null behavior.

---

# 124. Connector Runtime

The connector runtime is responsible for:

* lifecycle;
* concurrency;
* resource limits;
* credentials;
* retries;
* tracing;
* cancellation;
* connector isolation.

Connector business logic should remain in the connector implementation.

---

# 125. Workflow Runtime

Long-running ingestion workflows should use Design 1.27 workflow infrastructure.

The workflow runtime manages:

* retries;
* durable state;
* timeouts;
* cancellation;
* recovery.

The ingestion domain manages:

* source state;
* source identity;
* publication semantics;
* ingestion correctness.

---

# 126. Implementation Phases

## Phase 1 — Core Domain

Implement:

* Source;
* SourceResource;
* SourceVersion;
* IngestionAttempt;
* IngestionRun;
* state machine;
* identity;
* idempotency.

### Exit Criteria

All state transitions are deterministic and tested.

---

## Phase 2 — Artifact Acquisition

Implement:

* streaming acquisition;
* SHA-256 digest;
* temporary storage;
* atomic publication;
* artifact metadata.

### Exit Criteria

No partial artifact can become authoritative.

---

## Phase 3 — Content Cache Integration

Integrate Design 1.26.

Implement:

* source artifact publication;
* immutable source references;
* metadata;
* provenance.

### Exit Criteria

A published source version can be consumed by downstream processing without connector involvement.

---

## Phase 4 — Eventing Integration

Integrate Design 1.27.

Implement:

* outbox;
* ingestion events;
* correlation;
* causation;
* idempotent consumers.

### Exit Criteria

A committed ingestion state reliably produces the corresponding event.

---

## Phase 5 — Connector SDK

Implement the connector SPI and conformance suite.

Initial connector candidates should be selected based on actual Synanton workloads.

### Exit Criteria

At least one production-quality connector and one reference/test connector pass the conformance suite.

---

## Phase 6 — Synchronization

Implement:

* cursors;
* incremental synchronization;
* manifests;
* reconciliation;
* deletion semantics.

### Exit Criteria

A representative source can be synchronized repeatedly without duplicate logical source versions.

---

## Phase 7 — Security

Integrate Design 1.23.

Implement:

* tenant enforcement;
* authorization context;
* source ACL metadata;
* security classification handling;
* audit;
* connector isolation.

### Exit Criteria

Cross-tenant and forged-security-context tests fail closed.

---

## Phase 8 — Operational Hardening

Implement:

* metrics;
* tracing;
* rate limiting;
* quotas;
* backpressure;
* quarantine;
* administrative operations;
* chaos tests.

### Exit Criteria

The system can operate continuously under representative failure and load scenarios.

---

# 127. Initial Vertical Slice

The first end-to-end implementation should be deliberately small:

```text
Local / HTTP Source
       ↓
Reference Connector
       ↓
Ingestion
       ↓
SHA-256
       ↓
Artifact Store
       ↓
Ingestion Record
       ↓
Outbox
       ↓
SourcePublished
       ↓
Content Cache
```

This establishes the architectural contracts before connector breadth expands.

---

# 128. Second Vertical Slice

The second slice should add:

```text
incremental synchronization
        +
source revision detection
        +
SourceChanged
        +
downstream processing
```

This validates the relationship between Ingestion, Eventing, Content Cache, and Knowledge.

---

# 129. Third Vertical Slice

The third slice should add:

```text
deletion
+
tombstone
+
security propagation
+
knowledge invalidation
```

This validates the most important lifecycle boundary.

---

# 130. Acceptance Criteria

Design 1.28 is considered implementation-ready when:

### Identity

* every source resource has stable identity;
* source versions are immutable;
* duplicate delivery converges.

### Acquisition

* large artifacts are streamed or bounded;
* digests are verified;
* partial artifacts cannot be published.

### Synchronization

* incremental sync is supported where available;
* cursors are durable;
* deletions are explicit;
* incomplete synchronization cannot cause accidental deletion.

### Security

* tenant scope is explicit;
* authorization context is preserved;
* source ACL metadata is provenance-aware;
* cross-tenant tests fail closed.

### Eventing

* ingestion state and events are reliably coordinated;
* events are versioned;
* consumers are idempotent.

### Provenance

* every artifact can be traced to source and ingestion operation;
* processing can reference source version identity.

### Operations

* retries are bounded;
* quarantine exists;
* reconciliation exists;
* metrics and tracing exist;
* failures are diagnosable.

---

# 131. Architectural Invariants

The following invariants are mandatory:

1. **A connector never owns downstream knowledge.**
2. **Ingestion never directly writes search indexes.**
3. **Ingestion never directly owns annotations.**
4. **Source identity is independent of ingestion attempt identity.**
5. **Source versions are immutable.**
6. **Duplicate acquisition must converge.**
7. **Partial artifacts are never authoritative.**
8. **Tenant scope is explicit on every ingestion record.**
9. **Events are facts, not authoritative state.**
10. **Event delivery is assumed to be at-least-once.**
11. **Consumers must be idempotent.**
12. **Source deletion must be explicit.**
13. **Incomplete synchronization must not imply deletion.**
14. **Security context cannot be weakened by source metadata.**
15. **Ingestion must not depend on AI Runtime implementation.**
16. **Ingestion must not depend on a particular broker.**
17. **Ingestion must not depend on a particular artifact store.**
18. **Processing must be able to reference an immutable source version.**
19. **Bulk ingestion must not starve interactive workloads.**
20. **Every published source version must have provenance.**

---

# 132. Architectural Separation

The complete Synanton architecture can now be expressed as:

```text
                    ┌──────────────────────┐
                    │    External World    │
                    └──────────┬───────────┘
                               │
                               ▼
                    ┌──────────────────────┐
                    │   Ingestion 1.28     │
                    │ acquisition / source │
                    │ identity / lifecycle │
                    └──────────┬───────────┘
                               │
                               ▼
                    ┌──────────────────────┐
                    │   Content Cache 1.26 │
                    │ canonical artifacts  │
                    └──────────┬───────────┘
                               │
                               ▼
                    ┌──────────────────────┐
                    │ Extraction / Process │
                    └──────────┬───────────┘
                               │
                               ▼
                    ┌──────────────────────┐
                    │ Knowledge 1.25       │
                    │ annotations / derived│
                    │ knowledge / lineage  │
                    └──────────┬───────────┘
                               │
                ┌──────────────┼──────────────┐
                ▼              ▼              ▼
             Search         Analytics       Apps

      ┌──────────────────────────────────────────────┐
      │              Eventing 1.27                   │
      │ events / commands / workflows / retries      │
      └──────────────────────────────────────────────┘

      ┌──────────────────────────────────────────────┐
      │              Security 1.23                   │
      │ identity / policy / classification / ACL     │
      └──────────────────────────────────────────────┘

      ┌──────────────────────────────────────────────┐
      │              AI Runtime 1.30                 │
      │ inference / GPU execution / AI workloads     │
      └──────────────────────────────────────────────┘
```

---

# 133. Relationship to the Overall Synanton Architecture

Design 1.28 establishes a clean architectural distinction:

```text
What entered Synanton?
        ↓
      Ingestion

What does the source contain?
        ↓
      Extraction

What does Synanton understand?
        ↓
      Knowledge

How is knowledge accessed?
        ↓
      Search / Applications

How is knowledge measured?
        ↓
      Analytics

How is work coordinated?
        ↓
      Eventing / Workflow

How is AI computation executed?
        ↓
      AI Runtime
```

This separation is intentional.

---

# 134. Final Thesis

The Ingestion Plane is the **external-system boundary of Synanton**.

Its purpose is not to make content intelligent.

Its purpose is to make external content **durable, identifiable, traceable, secure, and available for controlled downstream processing**.

The central architectural rule is:

> **Acquire once, identify explicitly, preserve immutably, publish durably, and interpret independently.**

Design 1.28 therefore establishes ingestion as a first-class platform plane rather than a collection of connector-specific integrations.

Combined with the surrounding designs:

```text
1.23 Security
      ↓
1.28 Ingestion
      ↓
1.26 Content Cache
      ↓
1.25 Knowledge
      ↓
1.27 Eventing / Workflow
      ↓
1.30 AI Runtime
      ↓
Search / Applications / Analytics
```

the architecture provides a controlled path from heterogeneous external sources to durable Synanton knowledge and applications without coupling acquisition, interpretation, execution, or analytics.

---

# Appendix A — Recommended Initial Event Set

```text
SourceDiscovered
SourceChanged
SourceUnchanged
SourceDeleted

IngestionStarted
SourceAcquired
SourceValidated
SourcePublished
IngestionCompleted

IngestionFailed
SourceQuarantined
IngestionCancelled
```

---

# Appendix B — Recommended Ingestion Record

Conceptual model:

```yaml
ingestion:
  operation_id: ...
  tenant_id: ...
  ingestion_run_id: ...
  connector_id: ...

source:
  source_id: ...
  resource_id: ...
  source_revision: ...
  source_uri: ...

artifact:
  artifact_id: ...
  content_digest: ...
  content_type: ...
  content_length: ...
  storage_reference: ...

security:
  authorization_context: ...
  classification: ...
  acl_reference: ...

execution:
  status: ...
  attempt_count: ...
  started_at: ...
  completed_at: ...

provenance:
  connector_version: ...
  configuration_version: ...
  actor: ...
  trace_id: ...
```

---

# Appendix C — Recommended Failure Taxonomy

```text
SOURCE_UNAVAILABLE
AUTHENTICATION_FAILED
AUTHORIZATION_DENIED
RATE_LIMITED
NETWORK_TIMEOUT
CONTENT_TOO_LARGE
CONTENT_INVALID
CONTENT_TYPE_MISMATCH
DIGEST_MISMATCH
SECURITY_POLICY_VIOLATION
MALWARE_SUSPECTED
STORAGE_FAILURE
EVENT_PUBLICATION_FAILURE
CONNECTOR_FAILURE
CANCELLED
UNKNOWN
```

Failure classification should determine retry behavior.

---

# Appendix D — Design 1.28 Dependency Summary

| Design      | Relationship                                         |
| ----------- | ---------------------------------------------------- |
| Design 1.23 | Normative security model                             |
| Design 1.25 | Knowledge and analytics consume ingestion outputs    |
| Design 1.26 | Canonical content-artifact interface                 |
| Design 1.27 | Eventing, retry, workflow and asynchronous execution |
| Design 1.28 | Ingestion / source acquisition plane                 |
| Design 1.30 | Downstream AI execution capability                   |

---

# Appendix E — Key Design Decisions

| Decision            | Choice                             |
| ------------------- | ---------------------------------- |
| Delivery            | At-least-once                      |
| Consumer semantics  | Idempotent                         |
| Source versions     | Immutable                          |
| Content integrity   | SHA-256 recommended                |
| Large artifacts     | Streaming / object storage         |
| State coordination  | Transactional outbox               |
| Source deletion     | Explicit                           |
| Synchronization     | Incremental where supported        |
| Security            | Inherited from Design 1.23         |
| Content authority   | Design 1.26                        |
| Knowledge authority | Design 1.25                        |
| Workflow authority  | Design 1.27                        |
| Broker              | Abstract                           |
| Artifact store      | Abstract                           |
| AI dependency       | None                               |
| Global exactly-once | Not required                       |
| Connector isolation | Required                           |
| Provenance          | Mandatory                          |
| Tenant scope        | Mandatory                          |
| Replay              | Explicit and classified            |
| Reconciliation      | First-class capability             |
| Backfill            | First-class operational capability |

---

# Appendix F — Implementation Readiness Checklist

Before implementation begins:

* [ ] Define canonical `SourceIdentity`.
* [ ] Define `SourceVersion` semantics.
* [ ] Define content digest representation.
* [ ] Define ingestion state machine.
* [ ] Define idempotency contract.
* [ ] Define artifact publication contract.
* [ ] Define Content Cache integration.
* [ ] Define ingestion event schemas.
* [ ] Define transactional outbox strategy.
* [ ] Define connector SPI.
* [ ] Define connector capability model.
* [ ] Define synchronization cursor model.
* [ ] Define deletion semantics.
* [ ] Define quarantine model.
* [ ] Define tenant/security context.
* [ ] Define provenance schema.
* [ ] Define retry taxonomy.
* [ ] Define operational metrics.
* [ ] Define conformance test suite.
* [ ] Select first reference connector.
* [ ] Establish throughput workload model.
* [ ] Establish failure/chaos test scenarios.
* [ ] Establish retention and legal-hold behavior.

---

**Status:** Proposal

**Architectural conclusion:**

> **Design 1.28 makes ingestion a first-class Synanton plane responsible for turning heterogeneous external source state into immutable, identifiable, secure, provenance-preserving internal source versions. It deliberately stops before extraction, knowledge interpretation, search projection, analytics, and AI execution.**