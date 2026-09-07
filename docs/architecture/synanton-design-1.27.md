# Synanton Design 1.27 - Eventing and Workflow Plane

> **Document type:** Architecture design document
> **Version:** 1.27
> **Document ID:** `synanton-design-1.27`
> **Date:** 2026-09-05
> **Status:** Approved (architecture) — implementation not started
> **Normative security baseline:** Design 1.23 (see §2.1)
> **Audience:** Architects, platform engineers, developers, SRE, security engineers, runtime engineers, and system integrators
> **Related Designs:**
> - [synanton-design-1.23.md](./synanton-design-1.23.md) — security baseline; normative for tenant isolation, authorization, classification, and audit that must survive asynchronous boundaries (see §2.1)
> - [synanton-design-1.25.md](./synanton-design-1.25.md) — Knowledge / derived-state model (Annotation, Resolutor, Equalix, Processing Runs); this document provides the communication and orchestration mechanism for those processes (see §2.2)
> - Designs 1.28 (Ingestion), 1.29 (Identity), 1.30 (AI Runtime) and 1.31 (Search) depend on **this** document for their asynchronous, event, command, retry, idempotency and workflow semantics — they do not define independent async contracts of their own; those designs are still tracked as proposals under `docs/architecture/proposals/vX.Y/` pending their own folds
> - [ADR-004](./decisions/adr-004-eventing-workflow-plane.md) — decision record for this design

> **Repository:** `synanton/platform`
> **Document:** `docs/architecture/synanton-design-1.27.md`

> **Implementation principle:** Design 1.27 extends the existing Synanton architecture as the platform's common execution fabric. It does not replace the security contract established by Design 1.23, and it does not duplicate the domain dependency-analysis logic owned by Resolutor/Equalix in Design 1.25.

---

## Role in the Design Series

Design 1.27 is numbered between 1.26 (Content Cache) and 1.28 (Ingestion), but its **architectural role is not sequential — it is foundational**. Design 1.27 defines the common events/commands/retry/idempotency/workflow contract that the platform's later planes are built on top of, and it is positioned *ahead of* Designs 1.28 (Ingestion), 1.29 (Identity), 1.30 (AI Runtime) and 1.31 (Search) in the dependency model.

Without this document in place first, each of Ingestion, Identity, AI Runtime and Search would risk inventing its own independent asynchronous semantics — its own queues, retries, correlation IDs, idempotency rules, and workflow state — instead of building on one shared contract. This is the resolution of the cross-plane architecture review finding "Eventing should precede later planes" (see `docs/architecture/proposals/synanton-architecture-review-resolution.md`), and it is treated as load-bearing for the rest of the 1.28–1.31 series: those documents integrate with Design 1.27 rather than re-deriving its semantics.

---

# 1. Executive Summary

Synanton Design 1.27 defines the **Eventing and Workflow Plane**.

The Eventing Plane provides durable communication between Synanton's architectural planes.

The Workflow Plane coordinates multi-step processing that cannot be represented as a single request/response operation.

Design 1.27 is the platform's **common execution fabric**: it is deliberately positioned ahead of Designs 1.28 (Ingestion), 1.29 (Identity), 1.30 (AI Runtime) and 1.31 (Search) in the dependency model, so that those planes consume one shared asynchronous contract rather than each inventing its own. See "Role in the Design Series" above.

Together they provide the execution fabric connecting:

```text
Source / Ingestion
       │
       ▼
Extraction
       │
       ▼
Content Cache 1.26
       │
       ▼
Knowledge 1.25
       │
       ▼
AI Runtime 1.30
       │
       ├───────────────┐
       ▼               ▼
Search            Analytics
       │
       ▼
Recalculation / Equalix
```

The central architectural principle is:

> **Events communicate durable facts; commands request work; workflows coordinate stateful multi-step execution.**

Design 1.27 deliberately separates these concepts.

An event says:

> **Something happened.**

A command says:

> **Please perform this operation.**

A workflow says:

> **Coordinate these operations until the required business outcome is reached.**

---

# 2. Relationship to Other Designs

## 2.1 Relationship to Design 1.23

Design 1.23 remains normative for security.

Eventing and workflow must preserve:

* tenant isolation
* authorization
* classification
* security context
* masking rules
* auditability
* fail-closed behavior

Security metadata must not disappear when work crosses an asynchronous boundary.

---

## 2.2 Relationship to Design 1.25

Design 1.25 defines:

* Knowledge
* Annotation
* Derived Knowledge
* Processing Runs
* Dependencies
* Resolutor
* Equalix
* projections
* analytics

Design 1.27 provides the communication and orchestration mechanism for those processes.

Example:

```text
Model Version Changed
        │
        ▼
       Event
        │
        ▼
     Resolutor
        │
        ▼
Recalculation Plan
        │
        ▼
      Workflow
        │
        ▼
      Equalix
        │
        ▼
AI Runtime / Knowledge
```

---

## 2.3 Relationship to Design 1.26

Content Cache lifecycle changes may generate events:

```text
Content Published
Content Invalidated
Content Expired
Content Deleted
Content Recalculated
```

Consumers must react through the Eventing contract rather than coupling directly to the cache implementation.

---

## 2.4 Relationship to Design 1.30

AI Runtime executions generate execution-state events:

```text
Execution Accepted
Execution Started
Execution Completed
Execution Failed
Execution Cancelled
```

The runtime remains responsible for execution.

The Eventing Plane transports the resulting facts.

---

# 3. Motivation

Without a common eventing model, individual Synanton components would independently invent:

* queues
* retry mechanisms
* event schemas
* correlation IDs
* idempotency
* dead-letter handling
* ordering
* delivery guarantees
* workflow state
* timeout semantics

This creates hidden architectural coupling.

Design 1.27 establishes one common model.

---

# 4. Design Goals

1. Durable asynchronous communication
2. Explicit event semantics
3. Explicit command semantics
4. Reliable delivery
5. Idempotent consumption
6. Tenant isolation
7. Security propagation
8. Schema evolution
9. Correlation and tracing
10. Retry semantics
11. Dead-letter handling
12. Ordering where required
13. Replay where safe
14. Workflow orchestration
15. Failure recovery
16. Operational observability
17. Storage/broker independence
18. Integration with Processing Runs
19. Integration with Equalix
20. Integration with AI Runtime

---

# 5. Non-Goals

Design 1.27 does not mandate:

* Kafka
* NATS
* RabbitMQ
* Pulsar
* Redis Streams
* PostgreSQL as a message broker
* a particular workflow engine
* Kubernetes
* exactly-once physical delivery
* global ordering
* event sourcing for every domain

These are implementation choices.

---

# 6. Architectural Principles

## 6.1 Events Are Facts

An event represents something that has already happened.

Example:

```text
ContentPublished
```

not:

```text
PublishContent
```

The latter is a command.

---

## 6.2 Commands Express Intent

A command requests work.

Example:

```text
GenerateEmbedding
```

The command does not claim that the embedding was generated.

---

## 6.3 Events Are Immutable

Once published, an event must not be modified.

Corrections are represented by subsequent events.

---

## 6.4 Consumers Are Idempotent

Delivery may occur more than once.

Therefore:

> **At-least-once delivery is the default reliability model.**

Consumers must safely process duplicate deliveries.

---

## 6.5 Broker Delivery Is Not Business Exactly-Once

The architecture does not require physical exactly-once delivery.

Instead:

```text
at-least-once delivery
        +
idempotent processing
        =
effectively-once business behavior
```

where the operation semantics permit it.

---

# 7. Logical Architecture

```text
                     Event / Command API
                            │
                            ▼
                 ┌────────────────────┐
                 │   Eventing Plane   │
                 │                    │
                 │ Event Store/Broker │
                 │ Schema Registry    │
                 │ Retry / DLQ        │
                 └─────────┬──────────┘
                           │
             ┌─────────────┼─────────────┐
             ▼             ▼             ▼
        Knowledge       AI Runtime     Analytics
             │             │             │
             ▼             ▼             ▼
          Workflow       Workflow      Consumers
             │
             ▼
          Equalix
```

---

# 8. Event

An event contains:

```text
event identity
event type
event version
occurred timestamp
producer
tenant scope
correlation
causation
payload
security context
```

Example:

```yaml
event_id: evt-123
event_type: ContentPublished
event_version: 1
occurred_at: 2026-09-05T12:00:00Z

producer:
  service: content-cache
  version: 1.26

tenant_id: tenant-a

correlation_id: corr-456
causation_id: cmd-789

payload:
  entry_id: content-123
  document_id: doc-42
  content_hash: sha256:...
```

---

# 9. Event Identity

Every event has a globally unique:

```text
event_id
```

The identity must remain stable across retries.

Republishing the same logical event must not generate a new identity merely because delivery failed.

---

# 10. Event Type

Event types use semantic names.

Examples:

```text
ContentPublished
ContentInvalidated
ContentExpired

ProcessingRunStarted
ProcessingRunCompleted
ProcessingRunFailed

ExecutionAccepted
ExecutionStarted
ExecutionCompleted
ExecutionFailed

AnnotationCreated
AnnotationInvalidated

ProjectionPublished
ProjectionInvalidated

ModelVersionActivated
ModelVersionDeprecated
```

---

# 11. Event Version

Event schemas evolve independently from service versions.

Example:

```text
ContentPublished.v1
ContentPublished.v2
```

A producer must never silently change the meaning of an existing event version.

---

# 12. Schema Evolution

Compatible changes may include:

* optional fields
* additional metadata
* new enum values where consumers tolerate them

Breaking changes require:

```text
new event version
```

Consumers should ignore fields they do not understand.

---

# 13. Event Envelope

The event envelope is stable even when the payload evolves.

Recommended structure:

```yaml
event_id: ...
event_type: ...
event_version: ...
occurred_at: ...

producer:
  service: ...
  version: ...

tenant:
  id: ...

correlation_id: ...
causation_id: ...

security:
  classification: ...
  policy_version: ...

payload: {}
```

---

# 14. Tenant Scope

Every tenant-scoped event must explicitly identify:

```text
tenant_id
```

Platform-wide events may use:

```text
tenant_id: system
```

Such events are accessible only to authorized platform operators.

This follows the tenant-scope principle already established for Analytics.

---

# 15. Security Context

Security metadata must survive asynchronous processing.

At minimum:

```text
tenant
principal / service identity
classification
policy context
```

A consumer must not assume:

> "Internal event = trusted event."

Authorization remains mandatory at the processing boundary.

---

# 16. Commands

Commands represent requests.

Example:

```yaml
command_id: cmd-123
command_type: GenerateEmbedding
command_version: 1

tenant_id: tenant-a

target:
  document_id: doc-42

model:
  id: embedding
  version: "4"
```

Commands require idempotency semantics.

---

# 17. Command Identity

Every command has:

```text
command_id
```

Retries must reuse the same command identity.

---

# 18. Command Result

Commands should not necessarily return the final business result.

For asynchronous work:

```text
Command
  │
  ▼
accepted
  │
  ▼
operation_id
  │
  ▼
events
  │
  ▼
completed / failed
```

This aligns with asynchronous operation semantics in Designs 1.26 and 1.30.

---

# 19. Event vs Command

| Concept  | Meaning                             |
| -------- | ----------------------------------- |
| Event    | Something happened                  |
| Command  | Request to do something             |
| Query    | Request for current state           |
| Workflow | Coordination of multiple operations |

This distinction is normative.

---

# 20. Topic / Stream Model

Logical event streams may be organized by domain:

```text
content.*
knowledge.*
processing.*
runtime.*
projection.*
analytics.*
security.*
workflow.*
```

The physical broker may implement these as:

* topics
* queues
* streams
* database-backed channels

---

# 21. Partitioning

Where the underlying broker supports partitioning, the preferred partition key is the smallest identity that requires ordered processing.

Examples:

```text
document_id
processing_run_id
execution_id
workflow_id
```

Global ordering is not required.

---

# 22. Ordering

The architecture distinguishes:

```text
global ordering
```

from:

```text
entity ordering
```

Only entity ordering should normally be required.

Example:

```text
DocumentUpdated
DocumentDeleted
```

must preserve ordering for that document if the consumer depends on it.

---

# 23. Delivery Semantics

Default:

```text
At-least-once
```

Optional implementations may support stronger guarantees.

Consumers must not rely on:

```text
exactly-once broker delivery
```

for correctness.

---

# 24. Consumer Idempotency

A consumer should persist processed event identity.

Logical table:

```text
processed_events
---------------
consumer_id
event_id
processed_at
result
```

The processing transaction should coordinate state changes and idempotency where possible.

---

# 25. Idempotency Pattern

```text
Receive event
     │
     ▼
Check event_id
     │
┌───┴────┐
│        │
seen    unseen
│        │
▼        ▼
ignore   process
          │
          ▼
       record event
```

---

# 26. Transactional Publication

A critical problem is:

```text
database update succeeds
event publish fails
```

or:

```text
event published
database update fails
```

Design 1.27 therefore recommends the **transactional outbox pattern** for state-changing services.

---

# 27. Transactional Outbox

```text
BEGIN
  │
  ├── update domain state
  │
  └── insert outbox event
  │
COMMIT
  │
  ▼
Outbox Publisher
  │
  ▼
Event Broker
```

The database transaction establishes durable intent to publish.

---

# 28. Outbox Record

Recommended:

```text
outbox_events
-------------
event_id
event_type
event_version
tenant_id
aggregate_type
aggregate_id
payload
created_at
published_at
attempt_count
last_error
```

---

# 29. Outbox Publisher

The publisher:

1. reads unpublished events
2. publishes them
3. verifies broker acknowledgement
4. records publication
5. retries failures

Publishing must be idempotent where supported.

---

# 30. Inbox Pattern

Consumers may use an inbox table:

```text
inbox_events
------------
consumer_id
event_id
received_at
processed_at
status
```

This supports durable deduplication.

---

# 31. Retry

Retryable failures should use bounded exponential backoff.

Example:

```text
1s
2s
4s
8s
16s
...
```

A maximum retry policy must exist.

---

# 32. Retry Classification

Errors should be classified:

```text
TRANSIENT
PERMANENT
SECURITY
INVALID
DEPENDENCY
TIMEOUT
RESOURCE
```

Only appropriate categories should be retried.

---

# 33. Dead-Letter Queue

After retry exhaustion:

```text
Event
  │
  ▼
Retry
  │
  ▼
Retry exhausted
  │
  ▼
Dead Letter
```

Dead-letter records must retain the original event identity.

---

# 34. Dead-Letter Operations

Operators must be able to:

* inspect
* classify
* replay
* discard
* quarantine

Replay must preserve:

```text
original_event_id
```

and add a new replay context.

---

# 35. Poison Events

An event that repeatedly causes deterministic failure must not block unrelated events.

The implementation must isolate poison events through:

* retry limits
* dead-letter handling
* partition isolation
* consumer backpressure

---

# 36. Backpressure

Consumers must be able to slow intake.

The system should expose:

```text
queue depth
processing rate
lag
failure rate
```

A producer must not assume consumers can process events at unlimited speed.

---

# 37. Event Retention

Event retention is distinct from domain-data retention.

Retention policy must consider:

* operational replay
* audit
* compliance
* storage cost
* debugging
* security

Not every event needs indefinite retention.

---

# 38. Event Replay

Replay is supported where the event represents a durable historical fact and consumers can safely reconstruct state.

Replay must not be confused with:

```text
re-executing an original command
```

Replaying:

```text
EmbeddingGenerated
```

must not accidentally execute the embedding model again.

---

# 39. Event Replay Safety

Each event type should declare whether it is:

```text
REPLAY_SAFE
REPLAY_WITH_POLICY
NOT_REPLAYABLE
```

---

# 40. Workflow

A workflow is a durable state machine coordinating multiple operations.

Example:

```text
Document Ingestion Workflow

RECEIVED
   │
   ▼
EXTRACTING
   │
   ▼
CACHED
   │
   ▼
CLASSIFYING
   │
   ▼
ANNOTATING
   │
   ▼
INDEXING
   │
   ▼
COMPLETED
```

---

# 41. Workflow State

Workflow state must be durable.

Recommended:

```text
workflow_id
workflow_type
workflow_version
tenant_id
state
created_at
updated_at
started_at
completed_at
current_step
correlation_id
failure_code
failure_message
```

---

# 42. Workflow Identity

A workflow is identified independently of individual commands and events.

```text
workflow_id
```

One workflow may create many commands and consume many events.

---

# 43. Workflow Version

Workflow definitions are versioned.

Example:

```text
IngestionWorkflow:v1
IngestionWorkflow:v2
```

Existing executions continue under their original workflow version unless explicitly migrated.

---

# 44. Workflow Steps

Each step should define:

```text
step_id
command
input
expected events
timeout
retry policy
compensation
```

---

# 45. Workflow State Machine

A workflow should be modeled explicitly.

Example:

```yaml
workflow: document-ingestion
version: 1

states:
  RECEIVED:
    on:
      EXTRACTION_ACCEPTED: EXTRACTING

  EXTRACTING:
    on:
      CONTENT_PUBLISHED: CACHED
      EXTRACTION_FAILED: FAILED

  CACHED:
    on:
      CLASSIFICATION_COMPLETED: CLASSIFIED

  CLASSIFIED:
    on:
      ANNOTATION_COMPLETED: ANNOTATED

  ANNOTATED:
    on:
      PROJECTIONS_COMPLETED: COMPLETED
```

---

# 46. Orchestration vs Choreography

Synanton should use **orchestration for long-running business workflows**.

Event choreography remains appropriate for independent reactions.

Example choreography:

```text
ContentPublished
   ├──► Analytics
   ├──► Search
   └──► Audit
```

Example orchestration:

```text
Ingestion Workflow
   ├── Extract
   ├── Classify
   ├── Annotate
   ├── Index
   └── Complete
```

---

# 47. Why Not Pure Choreography

Pure choreography makes complex dependencies difficult to understand.

For example:

```text
extract
→ classify
→ annotate
→ embed
→ index
```

requires a durable state owner.

The Workflow Plane provides that owner.

---

# 48. Compensation

Not all operations are reversible.

Workflow steps therefore classify compensation as:

```text
REVERSIBLE
COMPENSATABLE
INVALIDATABLE
IRREVERSIBLE
```

For derived knowledge, invalidation/recalculation is often preferable to destructive rollback.

---

# 49. Timeout

Every long-running workflow step must have a timeout.

Timeout must produce explicit state:

```text
TIMEOUT
```

The workflow must then decide:

* retry
* compensate
* wait
* fail
* escalate

---

# 50. Workflow Cancellation

Cancellation is cooperative.

```text
Cancel Workflow
      │
      ▼
Stop scheduling new steps
      │
      ▼
Cancel active operations
      │
      ▼
Persist CANCELLED
```

Already completed steps are not magically undone.

---

# 51. Workflow Recovery

After workflow-engine restart:

```text
load workflow
    │
    ▼
read durable state
    │
    ▼
identify active step
    │
    ▼
reconcile outstanding operation
    │
    ▼
continue / retry / fail
```

In-memory workflow state alone is prohibited for durable workflows.

---

# 52. Workflow and Processing Runs

A Processing Run and Workflow are related but distinct.

```text
Workflow
    │
    ├── Processing Run A
    ├── Processing Run B
    └── Processing Run C
```

Workflow answers:

> How do we coordinate this business process?

Processing Run answers:

> What processing execution occurred?

---

# 53. Workflow and Equalix

Equalix coordinates controlled recalculation.

Design 1.27 allows Equalix to execute as a workflow participant.

```text
Resolutor
   │
   ▼
Recalculation Plan
   │
   ▼
Workflow
   │
   ▼
Equalix
   │
   ├── Content
   ├── Knowledge
   ├── Embeddings
   └── Projections
```

Equalix remains the domain coordinator for recalculation.

The generic Workflow Plane must not duplicate its dependency-analysis logic.

---

# 54. Workflow and AI Runtime

A workflow may submit model execution:

```text
Workflow
   │
   ▼
GenerateEmbedding Command
   │
   ▼
AI Runtime
   │
   ▼
ExecutionCompleted
   │
   ▼
Workflow
```

The workflow waits for the durable execution result rather than holding an in-memory request open.

---

# 55. Workflow and Content Cache

Example:

```text
Extract
  │
  ▼
Put Content
  │
  ▼
ContentPublished
  │
  ▼
Workflow continues
```

Content Cache remains authoritative for its own artifact state.

---

# 56. Workflow and Search

Indexing is asynchronous:

```text
KnowledgePublished
       │
       ▼
Index Command
       │
       ▼
Search Projection
       │
       ▼
ProjectionPublished
```

---

# 57. Workflow and Analytics

Analytics should normally consume events rather than synchronously participating in workflows.

```text
Domain Event
    ├──► Workflow
    └──► Analytics
```

Analytics failure must not normally block knowledge processing.

---

# 58. Priority

Commands and workflows may have logical priority:

```text
LOW
NORMAL
HIGH
CRITICAL
```

Priority must not bypass security or tenant policy.

---

# 59. Tenant Quotas

Event and workflow processing may be subject to:

* event rate
* concurrent workflows
* command rate
* retry budget
* storage
* execution capacity

Quota enforcement belongs at the appropriate admission boundary.

---

# 60. Fairness

A single tenant must not be able to consume all shared asynchronous capacity.

Implementations should support:

```text
tenant-aware concurrency
tenant-aware rate limits
weighted scheduling
```

---

# 61. Correlation

Every event and command should support:

```text
correlation_id
```

A correlation ID identifies a larger business operation.

Example:

```text
Document Ingestion
      │
      ├── Extraction
      ├── Classification
      ├── Embedding
      └── Indexing
```

All can share one correlation ID.

---

# 62. Causation

`causation_id` identifies the event or command that caused the current event.

Example:

```text
Command: GenerateEmbedding
       │
       ▼
ExecutionCompleted
       │
       ▼
EmbeddingPublished
```

The chain becomes auditable.

---

# 63. Trace Context

OpenTelemetry trace context should propagate through:

```text
HTTP
gRPC
commands
events
workflow steps
```

Trace IDs are observability identifiers, not business identifiers.

---

# 64. Event Lineage

The system should make it possible to reconstruct:

```text
source change
   ↓
command
   ↓
event
   ↓
workflow
   ↓
processing run
   ↓
AI execution
   ↓
knowledge
   ↓
projection
```

This complements the provenance model in Design 1.25.

---

# 65. Event Security

Events containing sensitive information must follow security policy.

Avoid putting large or sensitive payloads directly into events.

Prefer:

```text
event
  │
  └── reference → Content Cache / object
```

rather than:

```text
event
  └── entire document
```

---

# 66. Event Payload Size

Events should remain small.

Large artifacts must be stored externally.

Recommended:

```yaml
payload:
  content_ref: ...
```

rather than embedding multi-megabyte content.

---

# 67. Event Ordering and Knowledge Correctness

Ordering requirements must be explicit.

For example:

```text
AnnotationCreated
AnnotationInvalidated
```

must not be processed in reverse order by a consumer that depends on entity ordering.

Where ordering is unnecessary, consumers should remain order-independent.

---

# 68. Eventual Consistency

Event-driven architecture introduces bounded eventual consistency.

For example:

```text
Knowledge Published
      │
      ├── event
      │
      └──► Search Projection
               │
               ▼
        eventually searchable
```

The architecture must not claim that all projections update synchronously.

---

# 69. Consistency Classes

Operations should declare consistency expectations:

```text
STRONG
BOUNDED_EVENTUAL
EVENTUAL
BEST_EFFORT
```

Interactive APIs must not accidentally depend on asynchronous projections being immediately current.

---

# 70. Command Admission

A command should pass:

```text
authentication
      ↓
authorization
      ↓
tenant policy
      ↓
validation
      ↓
quota
      ↓
idempotency
      ↓
accepted
```

Only then should asynchronous processing begin.

---

# 71. Event Publication

The recommended lifecycle is:

```text
DOMAIN CHANGE
    │
    ▼
TRANSACTION
    │
    ├── state update
    └── outbox insert
    │
    ▼
COMMIT
    │
    ▼
PUBLISH
    │
    ▼
CONSUME
```

---

# 72. Event Consumption

Consumer lifecycle:

```text
RECEIVED
   │
   ▼
VALIDATED
   │
   ▼
AUTHORIZED
   │
   ▼
DEDUPLICATED
   │
   ▼
PROCESSED
   │
   ▼
ACKNOWLEDGED
```

Acknowledge only after the durable processing result has been established.

---

# 73. Failure Model

Failures include:

```text
producer unavailable
broker unavailable
consumer unavailable
schema mismatch
authorization failure
transient dependency failure
permanent processing failure
timeout
poison event
storage failure
```

Each requires explicit handling.

---

# 74. Broker Unavailability

A producer should not silently discard events because the broker is unavailable.

With transactional outbox:

```text
domain state
+
outbox
```

remain durable.

Publication resumes after broker recovery.

---

# 75. Consumer Failure

If a consumer fails after receiving but before durable completion:

```text
event redelivered
```

Idempotency prevents duplicate business effects.

---

# 76. Workflow Failure

Workflow state remains durable.

A failed workflow may be:

```text
RETRYABLE
FAILED
CANCELLED
WAITING
ESCALATED
```

---

# 77. Operational Intervention

Authorized operators may:

* retry workflow
* cancel workflow
* replay event
* move event to quarantine
* resume workflow
* override a blocked step

All such actions require audit.

---

# 78. Break-Glass

Break-glass event/workflow administration follows Design 1.23 and the administrative pattern established in Design 1.26.

Required:

```text
permission
reason
principal
timestamp
target
operation
correlation ID
audit record
```

---

# 79. Observability

Required metrics include:

### Eventing

```text
events_published_total
events_consumed_total
events_failed_total
events_retried_total
events_dead_lettered_total
event_processing_duration
event_lag
```

### Workflow

```text
workflows_started_total
workflows_completed_total
workflows_failed_total
workflows_cancelled_total
workflow_duration
workflow_step_duration
workflow_active
```

### Broker

```text
queue_depth
publish_latency
consumer_lag
```

---

# 80. Logging

Logs must include:

```text
event_id
command_id
workflow_id
correlation_id
causation_id
tenant-safe context
event_type
state
failure_code
```

Sensitive payloads must not be logged.

---

# 81. Tracing

Recommended span hierarchy:

```text
Workflow
├── Command
│    └── Runtime Execution
│
├── Command
│    └── Knowledge Operation
│
└── Command
      └── Projection
```

---

# 82. Schema Registry

A schema registry or equivalent schema-management mechanism should maintain:

```text
event type
version
schema
compatibility rule
owner
status
```

The initial implementation may use repository-managed schemas.

A centralized registry becomes useful as event volume and producer count grow.

---

# 83. Ownership

Every event type must have an owner.

Example:

| Event                  | Owner                |
| ---------------------- | -------------------- |
| ContentPublished       | Content Cache        |
| ProcessingRunCompleted | Processing subsystem |
| ExecutionCompleted     | AI Runtime           |
| AnnotationCreated      | Knowledge subsystem  |
| ProjectionPublished    | Projection subsystem |
| WorkflowCompleted      | Workflow subsystem   |

Consumers do not own producer event semantics.

---

# 84. Event Contract Governance

Before introducing an event:

1. define semantic meaning
2. define owner
3. define version
4. define tenant scope
5. define security classification
6. define replay behavior
7. define retention
8. define compatibility rules

---

# 85. Event Naming

Use past-tense names.

Preferred:

```text
ContentPublished
ExecutionCompleted
AnnotationCreated
```

Avoid:

```text
PublishContent
RunExecution
CreateAnnotation
```

The latter are commands.

---

# 86. Workflow Naming

Workflows describe a process:

```text
DocumentIngestionWorkflow
KnowledgeRecalculationWorkflow
ProjectionRebuildWorkflow
```

---

# 87. Event Categories

Recommended categories:

```text
Domain Events
Lifecycle Events
Processing Events
Security Events
Operational Events
Workflow Events
```

Domain events describe business state.

Operational events describe infrastructure conditions.

The two should not be conflated.

---

# 88. Security Events

Examples:

```text
AuthorizationDenied
BreakGlassAccessGranted
SecurityClassificationChanged
PolicyUpdated
```

Security events require stronger retention and audit controls.

---

# 89. Operational Events

Examples:

```text
RuntimeUnavailable
StorageDegraded
WorkerDraining
CapacityExhausted
```

Operational events are useful for monitoring and automated response but are not necessarily business facts.

---

# 90. Workflow Events

Examples:

```text
WorkflowStarted
WorkflowStepStarted
WorkflowStepCompleted
WorkflowStepFailed
WorkflowCompleted
WorkflowCancelled
```

---

# 91. Recalculation Workflow

A canonical recalculation workflow:

```text
Rule / Model / Source Change
          │
          ▼
        Event
          │
          ▼
       Resolutor
          │
          ▼
Recalculation Plan
          │
          ▼
       Workflow
          │
          ▼
       Equalix
          │
    ┌─────┼─────┐
    ▼     ▼     ▼
Knowledge Embedding Projection
    │     │     │
    └─────┼─────┘
          ▼
      Completion
```

---

# 92. Document Ingestion Workflow

Canonical example:

```text
SourceRegistered
       │
       ▼
ExtractContent
       │
       ▼
ContentPublished
       │
       ▼
ClassifyContent
       │
       ▼
CreateAnnotations
       │
       ▼
GenerateEmbeddings
       │
       ▼
PublishKnowledge
       │
       ▼
UpdateProjections
       │
       ▼
IngestionCompleted
```

---

# 93. Workflow Parallelism

Independent steps should execute in parallel.

Example:

```text
KnowledgePublished
       │
       ├──► Vector Projection
       ├──► Reverse Index
       ├──► Graph Projection
       └──► Analytics
```

The workflow should not serialize independent work unnecessarily.

---

# 94. Join Semantics

A workflow may wait for multiple branches.

Example:

```text
       ┌──► Vector
       │
Knowledge
       ├──► Lexical
       │
       └──► Graph
              │
              ▼
         All complete
              │
              ▼
          Published
```

The join condition must be explicit.

---

# 95. Partial Success

A workflow may allow partial completion.

Example:

```text
Vector      COMPLETED
Lexical     COMPLETED
Graph       FAILED
```

The workflow policy decides whether this is:

```text
COMPLETED_WITH_WARNINGS
RETRY
FAILED
```

The result must be explicit.

---

# 96. Workflow Compensation for Projections

Because projections are derived, compensation usually means:

```text
invalidate projection
       +
rebuild projection
```

rather than attempting transactional rollback across heterogeneous stores.

---

# 97. Workflow Persistence

Workflow state must be recoverable independently of the workflow process.

A recommended initial implementation may use PostgreSQL.

This is an implementation choice, not a Platform requirement.

---

# 98. Broker Abstraction

The Platform should define a logical interface:

```text
EventPublisher
EventConsumer
EventSubscription
EventStore
```

The implementation may map these to:

```text
Kafka
NATS
RabbitMQ
PostgreSQL
cloud messaging
```

---

# 99. Initial Implementation Recommendation

For the first implementation:

```text
PostgreSQL
   │
   ├── domain state
   ├── outbox
   ├── workflow state
   └── inbox
```

combined with a broker only when throughput or decoupling requirements justify it.

This prevents premature infrastructure expansion.

---

# 100. When a Dedicated Broker Becomes Necessary

A dedicated broker becomes justified when measurements demonstrate requirements such as:

* sustained high event throughput
* large consumer fan-out
* long retention/replay requirements
* cross-service asynchronous isolation
* consumer lag management
* independent scaling
* partition-based parallelism

The architecture does not prescribe the technology in advance.

---

# 101. Conformance

An Eventing implementation must pass:

### Core

* event identity
* event immutability
* event versioning
* command identity
* tenant scope
* security context
* idempotent consumption
* failure semantics

### Extended

* outbox
* inbox
* retries
* dead letters
* replay
* ordering
* workflow state
* cancellation
* timeout
* recovery

### Production

* observability
* audit
* schema governance
* operational recovery
* load testing
* security testing

---

# 102. Acceptance Criteria

Design 1.27 is ready for implementation when:

* [ ] Event envelope is defined
* [ ] Command envelope is defined
* [ ] Event/version rules are defined
* [ ] Tenant semantics are defined
* [ ] Security propagation is defined
* [ ] At-least-once semantics are established
* [ ] Consumer idempotency is mandatory
* [ ] Transactional outbox is defined
* [ ] Inbox pattern is defined
* [ ] Retry policy is defined
* [ ] Dead-letter semantics are defined
* [ ] Replay rules are defined
* [ ] Ordering requirements are defined
* [ ] Workflow state model is defined
* [ ] Workflow recovery is defined
* [ ] Cancellation is defined
* [ ] Timeout semantics are defined
* [ ] Equalix integration is defined
* [ ] AI Runtime integration is defined
* [ ] Content Cache integration is defined
* [ ] Processing Run integration is defined
* [ ] Observability is defined
* [ ] Conformance tests are defined

---

# 103. Architectural Invariants

The following are normative.

1. **Events represent facts, not requests.**

2. **Commands represent requests, not facts.**

3. **Events are immutable.**

4. **Event identity is stable across delivery retries.**

5. **Consumers must be idempotent.**

6. **At-least-once delivery is the default reliability model.**

7. **Business correctness must not depend on physical exactly-once broker delivery.**

8. **State-changing producers should use transactional outbox semantics.**

9. **Long-running workflows must have durable state.**

10. **Workflow recovery must not depend on process memory.**

11. **Security context must survive asynchronous boundaries.**

12. **Tenant scope must be explicit.**

13. **Large payloads must not be unnecessarily embedded in events.**

14. **Event replay must be explicitly classified for safety.**

15. **Workflow versions must be immutable for existing executions.**

16. **A workflow must not silently duplicate domain-specific dependency logic owned by Equalix.**

17. **Analytics must not become a synchronous dependency of core knowledge processing merely because it consumes events.**

18. **Broker technology is an implementation decision.**

---

# 104. Implementation Phases

## Phase 1 — Event Contract

Define:

* envelope
* event IDs
* command IDs
* correlation
* causation
* schema versioning
* tenant scope

## Phase 2 — Reliable Publication

Implement:

* outbox
* publisher
* retry
* publication monitoring

## Phase 3 — Reliable Consumption

Implement:

* inbox
* deduplication
* acknowledgement
* retry
* dead letters

## Phase 4 — Workflow Foundation

Implement:

* workflow definition
* workflow state
* durable execution
* timeout
* cancellation
* recovery

## Phase 5 — Platform Integration

Integrate:

* Content Cache
* Processing Runs
* Knowledge
* Equalix
* AI Runtime
* Search projections
* Analytics

## Phase 6 — Production Hardening

Add:

* security
* quotas
* backpressure
* observability
* audit
* replay controls
* load testing

---

# 105. Recommended First Vertical Slice

Implement one complete flow:

```text
ContentPublished
      │
      ▼
Outbox
      │
      ▼
Event Publisher
      │
      ▼
Consumer
      │
      ▼
GenerateEmbedding Command
      │
      ▼
AI Runtime
      │
      ▼
ExecutionCompleted
      │
      ▼
Workflow
      │
      ▼
EmbeddingPublished
```

This validates the architecture end-to-end.

---

# 106. Recommended Second Vertical Slice

Implement recalculation:

```text
ModelVersionActivated
        │
        ▼
Resolutor
        │
        ▼
Recalculation Plan
        │
        ▼
Workflow
        │
        ▼
Equalix
        │
        ▼
AI Runtime
        │
        ▼
Knowledge Updated
        │
        ▼
Projection Updated
```

This validates the most important cross-plane use case.

---

# 107. Recommended Third Vertical Slice

Implement failure recovery:

```text
Command
  │
  ▼
Workflow
  │
  ▼
Runtime
  │
  X
failure
  │
  ▼
retry
  │
  ▼
runtime
  │
  ▼
completed
```

Then test process restart at every stage.

---

# 108. Platform-Wide Event Flow

The resulting Synanton architecture becomes:

```text
                         CONTROL PLANE
                              │
               Identity / Policy / Configuration
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                     EVENTING / WORKFLOW                     │
│                         DESIGN 1.27                         │
│                                                             │
│  Events │ Commands │ Outbox │ Inbox │ Retry │ Workflow      │
└──────┬────────────┬────────────┬────────────┬───────────────┘
       │            │            │            │
       ▼            ▼            ▼            ▼
Extraction     Knowledge     AI Runtime   Projections
       │            │            │            │
       ▼            ▼            ▼            ▼
Content Cache   Equalix      Models       Search
       │            │            │            │
       └────────────┴────────────┴────────────┘
                              │
                              ▼
                          Analytics
```

---

# 109. Architectural Separation

Synanton now has a clean distinction:

```text
DATA PLANES
───────────
Content
Knowledge
Search
Analytics

EXECUTION PLANES
────────────────
AI Runtime
Workflow
Equalix

CONTROL PLANES
──────────────
Identity
Policy
Configuration

COMMUNICATION PLANE
───────────────────
Eventing
```

This separation is intentional.

---

# 110. Final Architectural Model

The complete processing architecture becomes:

```text
                     SOURCE
                       │
                       ▼
                   INGESTION
                       │
                       ▼
                  EXTRACTION
                       │
                       ▼
              CONTENT CACHE 1.26
                       │
                       ▼
                KNOWLEDGE 1.25
                       │
             ┌─────────┼─────────┐
             │         │         │
             ▼         ▼         ▼
          AI Runtime  Rules   Annotations
           1.30
             │
             └─────────┬─────────┘
                       ▼
                  KNOWLEDGE
                       │
                       ▼
              ┌────────────────┐
              │   EVENTING     │
              │    1.27        │
              └───────┬────────┘
                      │
          ┌───────────┼───────────┐
          ▼           ▼           ▼
       Search       Graph      Analytics
      Projection   Projection
          │           │
          └───────────┼───────────┘
                      ▼
                 APPLICATIONS

                 WORKFLOW
                    │
                    ▼
                 EQUALIX
                    │
                    ▼
             CONTROLLED
             RECALCULATION
```

---

# 111. Final Thesis

The Eventing and Workflow Plane is the **connective execution fabric of Synanton**.

It prevents individual architectural planes from developing incompatible asynchronous semantics.

The fundamental model is:

```text
EVENT
  = something happened

COMMAND
  = please do something

WORKFLOW
  = coordinate something

PROCESSING RUN
  = record what processing occurred

PROVENANCE
  = explain why the resulting knowledge exists
```

Together:

> **Events provide durable facts, commands provide intent, workflows provide coordination, Processing Runs provide execution history, and provenance provides explainability.**

This creates the missing connective layer between Synanton's data and execution planes without making any particular message broker or workflow engine an architectural dependency.

The final boundary is:

```text
                    WHAT HAPPENED
                         │
                         ▼
                       EVENT
                         │
                         ▼
                    WHAT TO DO
                       COMMAND
                         │
                         ▼
                   HOW TO COORDINATE
                      WORKFLOW
                         │
                         ▼
                 WHERE TO EXECUTE
                Runtime / Equalix
                         │
                         ▼
                  WHAT WAS PRODUCED
                 Knowledge / Projection
```

**Design 1.27 establishes the asynchronous backbone required for Synanton to scale from a collection of processing components into a coordinated platform.**