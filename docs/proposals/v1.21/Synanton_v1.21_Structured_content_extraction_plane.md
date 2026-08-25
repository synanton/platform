# Synanton v1.21 Design Proposal — Structured Content Extraction Plane

**Document ID:** SNTP-6-PROP-1.21-SCEP-REV1  
**Date:** 2026-08-24  
**Status:** PROPOSED — ARCHITECTURAL DESIGN IN PROGRESS  
**Amends:** Part IX (§65–§79) of `docs/architecture/synanton-design-1.21.md`  
**Scope:** Structured content extraction architecture and external contract

---

## 1. Executive Summary

Synanton v1.21 is still in architectural development. This proposal establishes a **Structured Content Extraction Plane** as the platform boundary for content extraction without prescribing its internal implementation.

The central architectural decision is:

> **Deployment topology is an implementation and scaling concern. The extraction contract MUST remain unchanged whether extraction runs inside Synanton, as a co-located component, or as an independently deployed extraction cluster.**

The Structured Content Extraction Plane is therefore a black box from the Synanton platform perspective.

Synanton specifies:

- the extraction request contract;
- content/object references;
- metadata and business tags;
- extraction options;
- priority;
- idempotency;
- expiration and lifetime semantics;
- capacity and admission semantics;
- synchronous and asynchronous operation semantics;
- operation status and progress;
- structured result contract;
- error semantics;
- compatibility and fallback behavior.

Synanton does **not** specify:

- which parser or extraction library is used;
- how processors are selected internally;
- whether processing is CPU, GPU, or accelerator based;
- whether OCR is performed locally or remotely;
- whether the extractor redirects work to another extractor;
- whether queues, brokers, schedulers, workers, or caches are used;
- how the extraction cluster is partitioned or scaled.

The extraction plane MAY internally route a request to any implementation capable of satisfying the contract.

For example:

```text
Synanton
   |
   | Structured Extraction Contract
   v
Structured Content Extraction Plane
   |
   +--> PDF extractor
   +--> Tika
   +--> OpenDataLoader
   +--> OCR service
   +--> transcription service
   +--> image analysis
   +--> video analysis
   +--> another extraction cluster
```

The internal topology is invisible to Synanton.

---

# 2. Architectural Position

The extraction plane sits between raw content storage and knowledge processing.

```text
                    Raw Content
                         |
                         v
                 Object Storage / S3
                         |
                         v
             +-------------------------+
             | Structured Content      |
             | Extraction Plane        |
             |                         |
             |      BLACK BOX          |
             +------------+------------+
                          |
                          v
                 Structured Payload
                          |
              +-----------+-----------+
              |                       |
              v                       v
        flattenedText          structured data
              |                       |
              +-----------+-----------+
                          |
                          v
                  Knowledge Processing
```

The extraction plane answers:

> **What can be deterministically or computationally extracted from this content?**

Knowledge processing answers:

> **What does the extracted content mean?**

The extraction plane MUST NOT become an ontology, entity-resolution, ranking, or knowledge-graph service.

---

# 3. Core Design Principle

## 3.1 Contract over topology

The Structured Content Extraction Contract is the architectural boundary.

These deployments are contractually equivalent:

```text
Mode A — Embedded

Synanton
   |
   +--> extractor
```

```text
Mode B — Co-located

Synanton
   |
   +--> extraction component
```

```text
Mode C — Cluster

Synanton
   |
   +--> extraction API
            |
            +--> workers
```

```text
Mode D — Distributed / delegated

Synanton
   |
   +--> extraction API
            |
            +--> extractor A
            |       |
            |       +--> extractor B
            |
            +--> extractor C
```

The external contract MUST NOT change between these modes.

Deployment topology is therefore explicitly classified as:

> **Scaling and implementation detail, not an API concern.**

---

# 4. Black-Box Boundary

The Structured Content Extraction Plane MUST be treated as a black box by Synanton.

Synanton MAY know:

- supported content types;
- supported extraction options;
- accepted request size;
- capacity/admission information where exposed;
- operation status;
- operation progress;
- result descriptors;
- error categories.

Synanton MUST NOT depend on:

- extractor implementation classes;
- parser libraries;
- internal queues;
- worker identities;
- internal routing;
- internal retry mechanisms;
- internal processor topology;
- CPU/GPU assignment;
- downstream extractor identities.

The plane MAY use any implementation that satisfies the external contract.

---

# 5. Content Object Boundary

Large content SHOULD be referenced through object storage rather than transported through the extraction API.

Illustrative contract:

```java
public record ObjectReference(
    String bucket,
    String key,
    String version,
    String sha256,
    long size
) {}
```

The extraction request references the source object:

```java
public record ExtractionRequest(
    UUID contentRefId,
    ObjectReference source,
    String mediaType,
    ExtractionOptions options,
    Map<String, String> metadata,
    ExtractionPriority priority,
    Instant expiresAt
) {}
```

The object reference identifies the source artifact.

The extraction plane MUST NOT modify the source artifact.

Raw source bytes remain the authoritative source artifact.

---

# 6. Extraction Request

An extraction request contains four conceptually separate groups.

## 6.1 Source

```text
contentRefId
object reference
media type
size
checksum
```

## 6.2 Business metadata

Business metadata allows applications to associate extracted content with their own domain objects.

Examples:

```text
ticketId
caseId
storyId
projectId
customerReference
```

The extraction plane MUST treat these values as opaque metadata.

It MUST NOT introduce domain-specific semantics for `ticketId`, `caseId`, or similar fields.

---

# 7. Tags

The extraction request SHOULD support application-defined tags.

Tags serve two different purposes and SHOULD be distinguished.

## 7.1 Routing / extraction tags

These affect extraction behavior.

Examples:

```text
ocr=include
ocr=exclude
transcription=required
layout=required
tables=include
images=include
scene-analysis=include
language=en
```

These tags are **requests**, not implementation commands.

For example:

```text
ocr=include
```

means:

> Produce OCR-derived content where applicable.

It does not mean:

> Invoke implementation X.

The extraction plane remains free to decide how to satisfy the request.

## 7.2 Business tags

These are carried for downstream use.

Examples:

```text
department=legal
document-type=contract
ticket=T-100
classification=internal
```

The extraction plane SHOULD preserve these tags but SHOULD NOT interpret business semantics unless explicitly required by the extraction contract.

---

# 8. Extraction Options

For options that have defined semantics, a typed option model SHOULD be preferred over arbitrary strings.

Illustrative model:

```java
public record ExtractionOptions(
    Boolean ocr,
    Boolean transcription,
    Boolean layout,
    Boolean tables,
    Boolean embeddedImages,
    Boolean sceneAnalysis,
    String language,
    Boolean preflight
) {}
```

The initial implementation does not need to support every option.

Unsupported options MUST be reported explicitly.

The contract SHOULD distinguish:

```text
requested
supported
applied
not-applicable
failed
```

For example:

```text
ocr = requested
ocr = applied
```

versus:

```text
ocr = requested
ocr = unsupported
```

This prevents a request from appearing successful when a requested extraction feature was silently ignored.

---

# 9. Priority

Extraction requests MAY carry an externally assigned priority.

Priority is an input to the extraction plane, not an implementation-defined scheduling API.

Two forms are acceptable:

```text
priorityClass = HIGH
```

or:

```text
priority = 80
```

The preferred v1.21 contract is a bounded priority class or externally defined priority key.

For example:

```text
LOW
NORMAL
HIGH
CRITICAL
```

The extraction plane MAY map these values to its internal scheduling policy.

Synanton MUST NOT assume that:

```text
HIGH
```

means a specific queue, worker pool, CPU allocation, or scheduling algorithm.

Priority is therefore an **external scheduling intent**, not a topology contract.

---

# 10. Pre-Flight Estimation

The extraction plane SHOULD optionally expose a pre-flight estimator.

The estimator answers:

> **What can the extraction plane predict about the cost or feasibility of this request before starting extraction?**

Illustrative API:

```text
estimate(request)
```

Possible result:

```java
public record ExtractionEstimate(
    boolean accepted,
    Duration estimatedDuration,
    long estimatedMemoryBytes,
    CapacityClass capacityClass,
    List<String> warnings
) {}
```

The estimate MAY include:

- estimated processing time;
- estimated resource class;
- estimated memory;
- expected output size;
- whether requested options are supported;
- whether capacity is currently available;
- warnings.

The estimator MUST be advisory.

It MUST NOT become a guarantee.

For example:

```text
estimatedDuration = 42s
```

does not guarantee completion in 42 seconds.

Pre-flight estimation SHOULD be optional because some implementations may not have a useful estimator.

---

# 11. Capacity

Capacity is an external concern because the extraction workload can be computationally expensive.

The contract SHOULD define admission behavior without exposing internal topology.

A request MAY receive:

```text
ACCEPTED
```

or:

```text
REJECTED_CAPACITY
```

or:

```text
DEFERRED
```

The extraction plane MAY expose coarse capacity information:

```text
AVAILABLE
LIMITED
SATURATED
```

It SHOULD NOT expose worker-level topology unless required for operations.

Synanton MUST NOT depend on the number of workers, queue names, CPU cores, GPU models, or other implementation details.

---

# 12. Expiration

Every asynchronous extraction request SHOULD support an expiration time.

```java
Instant expiresAt
```

Expiration defines the latest point at which the operation remains useful to the caller.

The extraction plane MUST define behavior for:

```text
request expires before execution
request expires while queued
request expires while running
request expires after completion
```

Recommended semantics:

### Before execution

Cancel without starting expensive work.

```text
EXPIRED
```

### While queued

Remove from admission/scheduling where possible.

```text
EXPIRED
```

### While running

The plane MAY allow the current operation to finish if cancellation is unsafe or more expensive than completion.

The final status SHOULD distinguish:

```text
COMPLETED
EXPIRED
CANCELLED
```

rather than pretending an expired request failed technically.

Expiration is a lifecycle constraint, not a scheduling implementation.

---

# 13. Idempotency

Idempotency is REQUIRED for asynchronous extraction.

A client MUST be able to safely retry a request after network failure without unintentionally creating duplicate expensive extraction work.

Illustrative:

```text
Idempotency-Key: <client supplied key>
```

The extraction plane MUST associate the key with the logical extraction request.

For an idempotent retry:

```text
same key
same request
        |
        v
same operation
```

The plane SHOULD reject reuse of an idempotency key with materially different request parameters.

Idempotency is especially important for OCR, transcription, image analysis, and video processing where duplicate work can be expensive.

---

# 14. Operation Model

The fundamental asynchronous unit is an `ExtractionOperation`.

```java
public record ExtractionOperation(
    UUID operationId,
    ExtractionStatus status,
    double progress,
    Instant createdAt,
    Instant expiresAt
) {}
```

For batches, one operation may contain multiple extraction items.

```text
Operation
 |
 +-- item A
 +-- item B
 +-- item C
```

This allows an application to submit a group of artifacts associated with one business action.

---

# 15. Operation Status

The minimum lifecycle SHOULD support:

```text
ACCEPTED
QUEUED
RUNNING
COMPLETED
PARTIAL
FAILED
CANCELLED
EXPIRED
```

The exact internal state machine is implementation-specific.

The externally visible state MUST be stable and documented.

---

# 16. Progress

Progress SHOULD be normalized:

```text
0.0 <= progress <= 1.0
```

Progress is advisory.

It MAY be based on:

- pages;
- bytes;
- audio duration;
- video duration;
- processing stages;
- implementation-specific estimation.

Progress MUST NOT be interpreted as a precise scheduling, billing, or capacity metric.

For batches, both operation-level and item-level progress MAY be exposed.

---

# 17. Status Polling

Two polling mechanisms SHOULD be supported.

## 17.1 Known operation IDs

```text
GET /extraction/operations/status?ids=A,B,C
```

This is intended for clients that already know the operations they submitted.

## 17.2 Cursor-based completion polling

```text
GET /extraction/operations/completed?cursor=...
```

This returns completed operations since a cursor position.

Example:

```json
{
  "operations": [
    {
      "operationId": "A",
      "status": "COMPLETED"
    },
    {
      "operationId": "B",
      "status": "FAILED"
    }
  ],
  "nextCursor": "..."
}
```

The cursor mechanism SHOULD support high-throughput consumers without requiring polling of every operation individually.

---

# 18. Webhooks

Webhooks are explicitly OUT OF SCOPE for v1.21.

The initial contract uses:

```text
operation ID
+
status polling
+
cursor-based completion polling
```

A future notification mechanism MAY be introduced without changing the extraction contract.

---

# 19. Synchronous API

A synchronous API MAY be provided for:

- prototyping;
- demonstrations;
- small content;
- interactive tooling;
- development.

It MUST use the same semantic extraction contract as asynchronous processing.

Conceptually:

```text
sync request
    |
    v
create extraction operation
    |
    v
wait
    |
    v
return result
```

Synchronous processing MUST NOT introduce a second extraction implementation or a second result model.

---

# 20. Structured Payload Result

Successful extraction produces a structured result.

```java
public record StructuredPayload(
    PayloadDescriptor descriptor,
    PayloadReference content
) {}
```

The payload MAY represent:

```text
PDF structure
HTML structure
audio timeline
transcription
image/OCR structure
video scene structure
```

The Synanton core MUST NOT interpret modality-specific payload contents.

The payload descriptor identifies the representation.

```java
public record PayloadDescriptor(
    String schemaId,
    String schemaVersion,
    String processorId,
    String processorVersion,
    SerializationFormat format,
    String schemaDigest,
    String payloadDigest
) {}
```

Processor version and schema version remain independent.

---

# 21. Flattened Text Compatibility Projection

For content for which textual extraction is meaningful, the result SHOULD provide:

```text
flattenedText
```

This remains the compatibility projection for generic text consumers.

A structured consumer that is unavailable or incompatible MUST NOT cause the raw source to be reprocessed merely to obtain text.

The extraction plane and consumer fallback remain separate concerns.

---

# 22. Internal Redirection

The extraction plane MAY redirect processing internally.

Examples:

```text
PDF
 |
 +--> OpenDataLoader
```

or:

```text
PDF
 |
 +--> detector
       |
       +--> OCR extractor
       +--> PDF parser
       +--> external extraction service
```

or:

```text
audio
 |
 +--> transcription cluster
       |
       +--> GPU workers
```

or:

```text
video
 |
 +--> scene extraction
       |
       +--> vision service
       +--> speech service
       +--> OCR service
```

These redirections MUST NOT be visible in the Synanton contract unless surfaced as diagnostic information.

The extraction plane owns the decision of how to satisfy the requested extraction capabilities.

---

# 23. MIME and Capability Handling

The extraction plane MAY use:

- declared MIME type;
- content sniffing;
- file extension;
- magic bytes;
- metadata;
- business tags;
- extraction options;
- processor capabilities.

Synanton SHOULD perform basic ingestion validation before submitting work.

The extraction plane remains authoritative for whether it can actually process the content.

Unsupported content SHOULD be rejected early:

```text
UNSUPPORTED_MEDIA_TYPE
```

rather than consuming expensive extraction resources.

---

# 24. Error Model

Errors SHOULD distinguish at least:

```text
INVALID_REQUEST
INVALID_OBJECT_REFERENCE
OBJECT_NOT_FOUND
OBJECT_CHANGED
UNSUPPORTED_MEDIA_TYPE
UNSUPPORTED_OPTION
REJECTED_CAPACITY
EXPIRED
TIMEOUT
EXTRACTION_FAILED
PARTIAL_EXTRACTION
PAYLOAD_INVALID
INTERNAL_ERROR
```

Errors MUST NOT expose internal implementation details as contractual semantics.

For example, this is an implementation detail:

```text
tika-parser-null-pointer
```

while this is a contract-level error:

```text
EXTRACTION_FAILED
```

Diagnostics MAY contain implementation-specific information for operators.

---

# 25. Result Integrity

The extraction result SHOULD contain enough information to establish its relationship to the source artifact.

At minimum:

```text
contentRefId
source checksum
payload digest
schema ID
schema version
processor version
```

This permits downstream consumers to determine whether a result belongs to the expected source.

---

# 26. Security Boundary

The extraction plane processes untrusted content.

It MUST enforce:

- source object access controls;
- maximum object size;
- maximum processing time;
- payload size limits;
- extraction option validation;
- resource limits;
- safe parser execution;
- safe serialization;
- tenant isolation where applicable.

The extraction plane MUST NOT execute arbitrary code supplied by the source content.

---

# 27. Resource and Capacity Requirements

The implementation is intentionally unspecified, but the contract MUST support resource constraints.

The plane MUST be able to enforce:

```text
maximum object size
maximum operation duration
maximum output size
maximum concurrent work
expiration
tenant/application quotas
```

Where applicable, requests SHOULD identify resource intent through options or priority rather than hardware requirements.

For example:

```text
priority = HIGH
ocr = required
```

is valid.

This is intentionally different from:

```text
gpuCount = 2
workerPool = "ocr-gpu-7"
```

which leaks topology and MUST NOT be part of the Synanton contract.

---

# 28. Multi-Tenant and Business Isolation

The extraction contract SHOULD allow an application or tenant identity to be associated with an operation.

The extraction plane MAY use this information for:

- quotas;
- capacity allocation;
- priority;
- fairness;
- auditing;
- billing;
- isolation.

However, these policies remain internal implementation decisions.

The external contract expresses **intent and constraints**, not the scheduling algorithm.

---

# 29. Observability

The extraction plane SHOULD expose operational metrics at the contract level.

Examples:

```text
extraction_requests_total
extraction_operations_total
extraction_completed_total
extraction_failed_total
extraction_expired_total
extraction_duration
extraction_queue_delay
extraction_payload_bytes
extraction_fallback_total
```

Useful dimensions include:

```text
mediaType
processorId
schemaId
schemaVersion
status
priorityClass
```

High-cardinality identifiers such as content IDs, object keys, payload digests, and arbitrary business tags SHOULD NOT be metric labels.

---

# 30. Architectural Invariants

The following are normative.

### §67.1 — Contract/topology separation

Deployment topology MUST NOT change the extraction contract.

### §67.2 — Black-box extraction

Synanton MUST NOT depend on internal extraction implementation.

### §67.3 — Raw source authority

Raw source content remains the authoritative source artifact.

### §67.4 — Structured payload extensibility

`StructuredPayload` MUST support modality-specific representations without extending the core document model.

### §67.5 — CanonicalDocument scope

`CanonicalDocument` remains a supported document/block representation and is not the universal multimodal representation.

### §67.6 — Idempotency

Asynchronous extraction MUST support idempotent request submission.

### §67.7 — Expiration

Asynchronous operations MUST support expiration semantics.

### §67.8 — Capacity

The extraction plane MUST be able to reject or defer work when capacity constraints prevent safe admission.

### §67.9 — External priority

Requests MAY provide priority intent. Priority MUST NOT expose internal scheduling topology.

### §67.10 — Extraction options

Extraction options MUST express requested capabilities such as OCR without prescribing implementation.

### §67.11 — Business metadata opacity

Business metadata and tags MUST remain opaque to the extraction implementation unless explicitly defined as extraction options.

### §67.12 — Pre-flight estimation

Pre-flight estimation MAY be provided and MUST be advisory rather than contractual timing guarantees.

### §67.13 — Async first-class

Asynchronous extraction MUST be a first-class contract, not an alternative implementation with different semantics.

### §67.14 — Batch operations

The contract MUST support processing multiple content references under one operation.

### §67.15 — No webhook dependency

v1.21 MUST NOT require webhooks for operation completion.

### §67.16 — No topology leakage

The contract MUST NOT expose worker pools, queues, hardware allocation, internal routing, or downstream extractor topology as required API concepts.

### §67.17 — No consumer reparse

Unavailable structured consumers MUST use an existing compatibility projection rather than triggering another extraction.

### §67.18 — Extraction/knowledge boundary

Extraction produces content structure. Knowledge processing interprets that structure.

---

# 31. Backward Compatibility

Because v1.21 is not yet committed, this proposal is part of the v1.21 design rather than a post-release compatibility amendment.

The preferred rollout is:

### Phase 1 — Contract

Define:

- extraction request;
- operation;
- status;
- priority;
- expiration;
- idempotency;
- tags/options;
- structured payload;
- result descriptor.

### Phase 2 — Embedded implementation

Implement the contract with the existing PDF/Tika/OpenDataLoader path.

The implementation remains replaceable.

### Phase 3 — Async operation model

Introduce:

- operation IDs;
- batch operations;
- status polling;
- cursor polling;
- expiration;
- idempotency.

### Phase 4 — External extraction deployment

Move the implementation behind the same contract.

No Synanton API redesign should be required.

### Phase 5 — Scaling

Scale the extraction implementation independently according to workload.

This may include:

```text
CPU workers
GPU workers
specialized extractors
external services
regional clusters
tenant-specific capacity
```

None of these changes the contract.

---

# 32. Validation Plan

## 32.1 Topology equivalence

Run the same request against:

```text
embedded
```

and:

```text
cluster
```

Expected:

- equivalent request semantics;
- equivalent result semantics;
- equivalent error categories;
- equivalent payload descriptors.

Implementation-specific diagnostics may differ.

## 32.2 Idempotency

Submit the same request repeatedly with the same idempotency key.

Expected:

```text
one logical operation
```

## 32.3 Expiration

Test expiration:

- before admission;
- while queued;
- while running.

Verify documented lifecycle behavior.

## 32.4 Capacity

Exhaust available capacity.

Verify:

- rejection/defer behavior;
- no silent loss;
- operation status remains observable.

## 32.5 Options

Test:

```text
ocr=include
ocr=exclude
```

and verify that the result explicitly records whether the requested capability was applied, unsupported, or failed.

## 32.6 Priority

Submit identical work at different priority classes.

Verify that priority is accepted as external intent without exposing internal scheduling topology.

## 32.7 Batch

Submit multiple objects in one operation.

Verify:

- one operation ID;
- individual item states;
- aggregate progress;
- partial completion semantics.

## 32.8 Pre-flight

Verify that estimation:

- does not start extraction;
- is advisory;
- reports unsupported options where possible;
- does not guarantee execution time.

## 32.9 Internal redirection

Route a request through multiple internal extractor implementations.

Verify that Synanton observes only the contract-level result.

---

# 33. Roadmap Impact

## v1.21

The v1.21 target SHOULD establish:

- Structured Content Extraction Plane contract;
- deployment-neutral extraction boundary;
- `ExtractionRequest`;
- `ExtractionOperation`;
- batch processing;
- synchronous and asynchronous APIs;
- idempotency;
- expiration;
- priority;
- extraction options/tags;
- optional pre-flight estimation;
- capacity/admission semantics;
- status polling;
- cursor-based completion polling;
- `StructuredPayload`;
- `PayloadDescriptor`;
- flattened-text compatibility projection;
- no webhook dependency;
- no dependency on extraction implementation topology.

The internal implementation MAY initially remain the existing document processor/Tika/OpenDataLoader implementation.

## Future v1.x

Potential implementations include:

- independent extraction cluster;
- OCR workers;
- transcription workers;
- image analysis;
- video analysis;
- GPU-backed extraction;
- external specialized extractors;
- independently scalable capacity pools.

These are implementation evolutions and SHOULD NOT require changes to the extraction contract.

---

# 34. Architectural Outcome

The resulting v1.21 architecture is:

```text
                         Raw Content
                              |
                              v
                       Object Storage
                              |
                              v
                  +----------------------+
                  | Synanton              |
                  | Extraction Contract  |
                  +----------+-----------+
                             |
                             v
             +-------------------------------+
             | Structured Content             |
             | Extraction Plane               |
             |                                |
             |          BLACK BOX              |
             |                                |
             |  routing / scheduling /        |
             |  workers / parsers / OCR /     |
             |  GPU / external services       |
             +---------------+----------------+
                             |
                             v
                    StructuredPayload
                             |
                    +--------+--------+
                    |                 |
                    v                 v
             flattenedText     modality structure
                    |                 |
                    +--------+--------+
                             |
                             v
                    Knowledge Processing
```

The architectural property to preserve is:

> **The extraction plane may evolve arbitrarily behind the contract. Scaling from an embedded processor to a distributed cluster, adding GPU processing, introducing OCR, redirecting requests to specialized extractors, or changing internal scheduling MUST NOT require a change to the Synanton extraction contract.**

---

# 35. Decision

### Recommendation

**ADOPT THE STRUCTURED CONTENT EXTRACTION PLANE AS PART OF v1.21.**

Specifically:

1. Treat extraction as a platform contract, not a processor implementation.
2. Treat deployment topology as a scaling concern.
3. Keep the extraction implementation black-box.
4. Keep `StructuredPayload` as the generic result carrier.
5. Keep `CanonicalDocument` as one supported document representation.
6. Use S3/object references for large source content.
7. Make asynchronous operations first-class.
8. Support batch operations.
9. Require idempotency.
10. Define expiration semantics.
11. Define capacity/admission semantics.
12. Support externally supplied priority.
13. Support extraction options and tags such as OCR inclusion/exclusion.
14. Provide optional pre-flight estimation.
15. Provide operation status polling.
16. Provide cursor-based completion polling.
17. Do not require webhooks in v1.21.
18. Allow arbitrary internal routing and delegation.
19. Keep CPU/GPU/accelerator choices entirely behind the extraction boundary.
20. Preserve the extraction/knowledge-processing separation.
21. Do not make Equalix or any other scheduler an architectural dependency; expose scheduling intent only.
22. Validate topology equivalence before considering v1.21 complete.

This proposal intentionally defines **what the extraction plane guarantees, not how it works**. That separation gives Synanton room to evolve from a local document processor into a high-throughput multimodal extraction platform without changing the platform contract.
