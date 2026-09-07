# Synanton Design 1.27 - Eventing and Workflow Plane

**Status:** Proposal
**Version:** 1.27
**Date:** 2026-09-05
**Audience:** Architects, platform engineers, developers, SRE, security engineers, runtime engineers, and system integrators

**Repository:** `synanton/platform`
**Document:** `docs/architecture/synanton-design-1.27.md`

---

# 1. Executive Summary

Synanton Design 1.27 defines the **Eventing and Workflow Plane**.

The Eventing Plane provides durable communication between Synanton's architectural planes.

The Workflow Plane coordinates multi-step processing that cannot be represented as a single request/response operation.

Together they provide the execution fabric connecting:

```text id="n4e7ka"
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

```text id="gq3c9n"
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

```text id="d4s8xn"
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

```text id="7f4qxp"
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

```text id="a1m9h7"
ContentPublished
```

not:

```text id="3x7m2n"
PublishContent
```

The latter is a command.

---

## 6.2 Commands Express Intent

A command requests work.

Example:

```text id="9tr1pw"
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

```text id="5k3m8z"
at-least-once delivery
        +
idempotent processing
        =
effectively-once business behavior
```

where the operation semantics permit it.

---

# 7. Logical Architecture

```text id="4c7j2s"
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

```text id="7x2v8r"
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

```yaml id="9tq1fr"
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

```text id="3b8q4m"
event_id
```

The identity must remain stable across retries.

Republishing the same logical event must not generate a new identity merely because delivery failed.

---

# 10. Event Type

Event types use semantic names.

Examples:

```text id="5r2h0c"
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

```text id="9x6f3b"
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

```text id="q8k3pz"
new event version
```

Consumers should ignore fields they do not understand.

---

# 13. Event Envelope

The event envelope is stable even when the payload evolves.

Recommended structure:

```yaml id="9n1j8k"
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

```text id="1p4r8x"
tenant_id
```

Platform-wide events may use:

```text id="v9k2q1"
tenant_id: system
```

Such events are accessible only to authorized platform operators.

This follows the tenant-scope principle already established for Analytics.

---

# 15. Security Context

Security metadata must survive asynchronous processing.

At minimum:

```text id="d4v6kn"
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

```yaml id="6f2zqk"
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

```text id="c3n7mv"
command_id
```

Retries must reuse the same command identity.

---

# 18. Command Result

Commands should not necessarily return the final business result.

For asynchronous work:

```text id="8h6r1v"
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

```text id="1t8k0n"
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

```text id="2w7q4a"
document_id
processing_run_id
execution_id
workflow_id
```

Global ordering is not required.

---

# 22. Ordering

The architecture distinguishes:

```text id="0x6t8j"
global ordering
```

from:

```text id="r3h7m1"
entity ordering
```

Only entity ordering should normally be required.

Example:

```text id="x5p2c8"
DocumentUpdated
DocumentDeleted
```

must preserve ordering for that document if the consumer depends on it.

---

# 23. Delivery Semantics

Default:

```text id="5j8q1n"
At-least-once
```

Optional implementations may support stronger guarantees.

Consumers must not rely on:

```text id="7b3c5q"
exactly-once broker delivery
```

for correctness.

---

# 24. Consumer Idempotency

A consumer should persist processed event identity.

Logical table:

```text id="4x9s6p"
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

```text id="c1p7w4"
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

```text id="6y2z8c"
database update succeeds
event publish fails
```

or:

```text id="4k7m2a"
event published
database update fails
```

Design 1.27 therefore recommends the **transactional outbox pattern** for state-changing services.

---

# 27. Transactional Outbox

```text id="p4x8n2"
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

```text id="q9c4v7"
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

```text id="w2r6n9"
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

```text id="n5c8r3"
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

```text id="v3x7q2"
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

```text id="y8q2m5"
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

```text id="u5r9k3"
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

```text id="a7v4n2"
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

```text id="h6k9x4"
re-executing an original command
```

Replaying:

```text id="f8q3w1"
EmbeddingGenerated
```

must not accidentally execute the embedding model again.

---

# 39. Event Replay Safety

Each event type should declare whether it is:

```text id="j2v5n8"
REPLAY_SAFE
REPLAY_WITH_POLICY
NOT_REPLAYABLE
```

---

# 40. Workflow

A workflow is a durable state machine coordinating multiple operations.

Example:

```text id="m4x7q2"
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

```text id="p6w3k8"
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

```text id="k9c5v2"
workflow_id
```

One workflow may create many commands and consume many events.

---

# 43. Workflow Version

Workflow definitions are versioned.

Example:

```text id="r4m7x1"
IngestionWorkflow:v1
IngestionWorkflow:v2
```

Existing executions continue under their original workflow version unless explicitly migrated.

---

# 44. Workflow Steps

Each step should define:

```text id="n8q4t6"
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

```yaml id="u2k7m9"
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

```text id="j6q3v9"
ContentPublished
   ├──► Analytics
   ├──► Search
   └──► Audit
```

Example orchestration:

```text id="f2m8x5"
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

```text id="z8v4p1"
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

```text id="h7n2m4"
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

```text id="q3m6v8"
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

```text id="w9c2k5"
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

```text id="x4n7q2"
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

```text id="k8p3w6"
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

```text id="s6v9m2"
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

```text id="p7x2n5"
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

```text id="n3v8q1"
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

```text id="c5m9x2"
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

```text id="v8q4m1"
Domain Event
    ├──► Workflow
    └──► Analytics
```

Analytics failure must not normally block knowledge processing.

---

# 58. Priority

Commands and workflows may have logical priority:

```text id="r2k7p4"
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

```text id="m5x8q3"
tenant-aware concurrency
tenant-aware rate limits
weighted scheduling
```

---

# 61. Correlation

Every event and command should support:

```text id="p9v3k6"
correlation_id
```

A correlation ID identifies a larger business operation.

Example:

```text id="b7x2n4"
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

```text id="h3m7q9"
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

```text id="v5k2m8"
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

```text id="j9q4x2"
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

```text id="t6m3p9"
event
  │
  └── reference → Content Cache / object
```

rather than:

```text id="z4q8n1"
event
  └── entire document
```

---

# 66. Event Payload Size

Events should remain small.

Large artifacts must be stored externally.

Recommended:

```yaml id="c8v2m5"
payload:
  content_ref: ...
```

rather than embedding multi-megabyte content.

---

# 67. Event Ordering and Knowledge Correctness

Ordering requirements must be explicit.

For example:

```text id="m2q7x4"
AnnotationCreated
AnnotationInvalidated
```

must not be processed in reverse order by a consumer that depends on entity ordering.

Where ordering is unnecessary, consumers should remain order-independent.

---

# 68. Eventual Consistency

Event-driven architecture introduces bounded eventual consistency.

For example:

```text id="r8n3v6"
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

```text id="q5m8x2"
STRONG
BOUNDED_EVENTUAL
EVENTUAL
BEST_EFFORT
```

Interactive APIs must not accidentally depend on asynchronous projections being immediately current.

---

# 70. Command Admission

A command should pass:

```text id="x3p7n9"
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

```text id="k4v8m2"
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

```text id="w6q2n8"
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

```text id="f8m3q7"
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

```text id="u4n7x2"
domain state
+
outbox
```

remain durable.

Publication resumes after broker recovery.

---

# 75. Consumer Failure

If a consumer fails after receiving but before durable completion:

```text id="c7m2q9"
event redelivered
```

Idempotency prevents duplicate business effects.

---

# 76. Workflow Failure

Workflow state remains durable.

A failed workflow may be:

```text id="n9x4v6"
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

```text id="m8q3x5"
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

```text id="p4v7n2"
events_published_total
events_consumed_total
events_failed_total
events_retried_total
events_dead_lettered_total
event_processing_duration
event_lag
```

### Workflow

```text id="x8m3q5"
workflows_started_total
workflows_completed_total
workflows_failed_total
workflows_cancelled_total
workflow_duration
workflow_step_duration
workflow_active
```

### Broker

```text id="r6n2k8"
queue_depth
publish_latency
consumer_lag
```

---

# 80. Logging

Logs must include:

```text id="c3x7m9"
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

```text id="v2q8m4"
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

```text id="j5m9x2"
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

```text id="r7x3m8"
ContentPublished
ExecutionCompleted
AnnotationCreated
```

Avoid:

```text id="q2m9v5"
PublishContent
RunExecution
CreateAnnotation
```

The latter are commands.

---

# 86. Workflow Naming

Workflows describe a process:

```text id="x8p4n2"
DocumentIngestionWorkflow
KnowledgeRecalculationWorkflow
ProjectionRebuildWorkflow
```

---

# 87. Event Categories

Recommended categories:

```text id="m3q7v9"
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

```text id="c9x2m6"
AuthorizationDenied
BreakGlassAccessGranted
SecurityClassificationChanged
PolicyUpdated
```

Security events require stronger retention and audit controls.

---

# 89. Operational Events

Examples:

```text id="p7v3n8"
RuntimeUnavailable
StorageDegraded
WorkerDraining
CapacityExhausted
```

Operational events are useful for monitoring and automated response but are not necessarily business facts.

---

# 90. Workflow Events

Examples:

```text id="m4x8q2"
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

```text id="n7q3v9"
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

```text id="v5m2x8"
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

```text id="g8q3m6"
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

```text id="x6n2p8"
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

```text id="q4m8x2"
Vector      COMPLETED
Lexical     COMPLETED
Graph       FAILED
```

The workflow policy decides whether this is:

```text id="v7n3p5"
COMPLETED_WITH_WARNINGS
RETRY
FAILED
```

The result must be explicit.

---

# 96. Workflow Compensation for Projections

Because projections are derived, compensation usually means:

```text id="m2x8q4"
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

```text id="n5q9x3"
EventPublisher
EventConsumer
EventSubscription
EventStore
```

The implementation may map these to:

```text id="c7m2v8"
Kafka
NATS
RabbitMQ
PostgreSQL
cloud messaging
```

---

# 99. Initial Implementation Recommendation

For the first implementation:

```text id="r3x8m5"
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

```text id="y8m3q7"
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

```text id="x4q8m2"
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

```text id="m7n3x9"
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

```text id="q9x3m7"
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

```text id="v5q8m2"
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

```text id="k3m8x5"
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

```text id="p7x2m9"
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

```text id="x4m8q2"
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