# Synanton Design 1.32 — Platform API

> **Document type:** Architecture design document
> **Version:** 1.32
> **Document ID:** `synanton-design-1.32`
> **Date:** 2026-09-07
> **Status:** Approved (architecture) — implementation not started
> **Normative security baseline:** Design 1.23 (see §16, §35, §46, §64, §106)
> **Audience:** Architects, platform engineers, API developers, application developers, integrators, SRE, security engineers, and technical decision makers
> **Related Designs:**
> - [synanton-design-1.23.md](./synanton-design-1.23.md) — security baseline; authorization is governed by 1.23 (§16), and pagination/results must remain security-context aware (§21, §28, §35, §44–§46, §64, §66–§70, §106–§107).
> - [synanton-design-1.27.md](./synanton-design-1.27.md) — owns internal event, command, and workflow semantics; Design 1.32 owns only the *external* Operation/API contract for long-running work (§55, §58–§60, §85–§87, §163). These are distinct layers, not duplicates.
> - [synanton-design-1.29.md](./synanton-design-1.29.md) — semantic authority for the principal, tenant, and delegation model; Design 1.32 defines the public identity-management API surface over that model (§15, §19–§21, §126).
> **Related docs:** [ADR-009](./decisions/adr-009-platform-api-contract.md)

> **Implementation principle:** Design 1.32 extends the existing Synanton architecture as its stable external contract boundary. It does not redefine the identity (1.29), security (1.23), eventing/workflow (1.27), ingestion (1.28), AI runtime (1.30), search (1.31), or analytics semantics established elsewhere in the design series — it exposes them through a versioned, resource-oriented Platform API.

---

# 1. Executive Summary

Synanton is composed of multiple architectural planes:

```text
Identity
Security
Ingestion
Content
Knowledge
Search
AI Runtime
Eventing
Workflow
Analytics
```

Applications should not need to understand the internal topology of those planes.

Design 1.32 defines the **Synanton Platform API** as the stable external integration boundary through which applications, clients, automation, and external systems interact with the platform.

Its central responsibility is:

> **Expose stable, secure, resource-oriented contracts while keeping internal implementation, storage, processing, retrieval, and execution technologies replaceable.**

The Platform API therefore acts as a **contract boundary**, not as a thin HTTP wrapper around internal services.

---

# 2. Core Architectural Thesis

The Platform API establishes:

```text
                    Applications
                         │
                         ▼
                ┌─────────────────┐
                │  Platform API   │
                └────────┬────────┘
                         │
        ┌────────────────┼────────────────┐
        ▼                ▼                ▼
     Identity         Security          Tenant
        │                │                │
        └────────────────┼────────────────┘
                         │
             ┌───────────┼───────────┐
             ▼           ▼           ▼
         Ingestion    Knowledge     Search
             │           │           │
             └───────────┼───────────┘
                         ▼
                    AI / Workflow
```

The API does not expose this internal topology directly.

A client should request:

```text
POST /v1/search
```

rather than knowing which lexical index, vector store, graph store, ranking service, or AI runtime executes the request.

---

# 3. Design Goals

Design 1.32 has the following goals:

1. Provide a stable public platform contract.
2. Hide internal service topology.
3. Provide consistent authentication and authorization integration.
4. Provide explicit tenant scope.
5. Support synchronous and asynchronous operations.
6. Provide resource-oriented APIs.
7. Provide predictable error semantics.
8. Support idempotent mutations.
9. Support pagination and continuation.
10. Support optimistic concurrency.
11. Provide consistent operation tracking.
12. Provide consistent provenance references.
13. Support event-driven integration.
14. Support API evolution without unnecessary breaking changes.
15. Support machine and human clients.
16. Provide observable request semantics.
17. Prevent backend-specific leakage into public APIs.
18. Preserve the architectural boundaries of Designs 1.23–1.31.

---

# 4. Non-Goals

The Platform API does not define:

* a particular HTTP framework;
* a particular programming language;
* a particular API gateway;
* a particular identity provider;
* internal service-to-service protocols;
* database schemas;
* search backend APIs;
* object-storage APIs;
* Kafka/NATS/RabbitMQ APIs;
* GPU runtime internals;
* LLM provider APIs;
* a mandatory UI framework.

---

# 5. API Design Principles

The Platform API follows these principles:

1. **Stable contracts**
2. **Explicit resources**
3. **Explicit tenant scope**
4. **Strong authentication**
5. **Policy-driven authorization**
6. **Idempotent mutations**
7. **Asynchronous execution for long-running work**
8. **Consistent error handling**
9. **Versioned contracts**
10. **Backward-compatible evolution**
11. **No internal topology leakage**
12. **Traceable operations**
13. **Provenance-aware resources**
14. **Security before convenience**

---

# 6. API Is Not Internal Service Routing

The following is explicitly prohibited:

```text
Client
  ↓
/search-service/search
```

or:

```text
Client
  ↓
/kafka/...
```

or:

```text
Client
  ↓
/opensearch/...
```

The public API must expose **Synanton concepts**, not implementation components.

---

# 7. Resource Model

The initial resource model includes:

```text
Tenant
Principal
Source
Source Version
Ingestion Run
Artifact
Knowledge Object
Semantic Chunk
Annotation
Search
Processing Run
Workflow
Operation
AI Execution
```

Analytics resources may be exposed separately where required.

---

# 8. Resource Identity

Every persistent resource must have a stable identifier.

Example:

```text
tenant_id
source_id
knowledge_object_id
chunk_id
operation_id
```

Identifiers must not depend on:

* database primary keys;
* shard identifiers;
* storage locations;
* backend-specific document IDs.

---

# 9. Resource References

Resources should be referenceable without requiring clients to understand storage topology.

Example:

```json
{
  "source_id": "src_01...",
  "version_id": "ver_01..."
}
```

rather than:

```json
{
  "s3_bucket": "...",
  "postgres_row": 18372
}
```

---

# 10. API Versioning

The initial public API uses:

```text
/v1/...
```

Major API versions represent incompatible contract changes.

Minor and additive changes should remain backward compatible within a major version.

---

# 11. Versioning Principle

The API version describes the **client contract**, not the implementation version.

For example:

```text
API v1
Search configuration v17
Embedding generation 42
Knowledge version 183
```

may coexist.

---

# 12. Compatibility

Within `/v1`, compatible changes may include:

* adding response fields;
* adding optional request fields;
* adding new resources;
* adding new enum values where clients are required to tolerate them;
* adding new endpoints.

Breaking changes require a new major version.

---

# 13. HTTP Contract

HTTP/JSON is the initial external API candidate.

The architecture should nevertheless keep the API contract independent of the transport implementation.

Future transports may include:

```text
REST
gRPC
GraphQL
SDK abstractions
event interfaces
```

without redefining the domain model.

---

# 14. API Gateway

A deployment may place an API Gateway in front of Platform API services.

Typical responsibilities:

* TLS termination;
* authentication;
* rate limiting;
* request size limits;
* routing;
* WAF integration;
* observability.

The gateway must not become the source of authorization truth.

---

# 15. Authentication

Authentication follows Design 1.29.

The API accepts an authenticated principal context.

Conceptually:

```text
Credential
   ↓
Authentication
   ↓
Principal
   ↓
Tenant Context
   ↓
Platform API
```

---

# 16. Authorization

Authorization follows Design 1.23.

The API must not assume:

```text
authenticated == authorized
```

Authentication establishes who the caller is.

Authorization establishes what that principal may do.

---

# 17. Tenant Context

Every request must have an effective tenant scope.

The tenant must come from trusted authenticated context or an explicitly authorized tenant-selection mechanism.

The client must not be able to bypass authorization by changing:

```json
{
  "tenant_id": "another-tenant"
}
```

---

# 18. Cross-Tenant Operations

Cross-tenant operations must be explicit and privileged.

They should:

* require administrative authorization;
* be auditable;
* use explicit scope;
* avoid accidental global behavior.

---

# 19. Principal Context

Internally, the request context should contain:

```yaml
principal:
  principal_id: ...
  principal_type: HUMAN | SERVICE | WORKLOAD
  tenant_id: ...
  authentication_context: ...
  delegation_chain: ...
```

Applications normally do not need to construct this object.

---

# 20. Actor vs Executor

The API must preserve the distinction between:

```text
actor
```

and:

```text
executor
```

Example:

```text
User
  ↓
Platform API
  ↓
Ingestion Workflow
  ↓
Connector Service
```

The user remains the initiating actor.

The connector is the execution principal.

---

# 21. Delegation

Delegated operations must preserve:

```text
initiating principal
effective principal
executing service
delegation chain
```

This is essential for audit and provenance.

---

# 22. Request IDs

Clients should be able to supply:

```http
Idempotency-Key: ...
```

for idempotent mutations.

Every request should also receive a server-generated:

```text
request_id
```

---

# 23. Idempotency

Mutation endpoints must define their idempotency behavior.

Examples:

```text
POST /v1/sources
POST /v1/ingestion-runs
POST /v1/ai/executions
```

should support idempotency where duplicate requests could otherwise create duplicate operations.

---

# 24. Idempotency-Key

Conceptually:

```http
POST /v1/ingestion-runs
Idempotency-Key: 6d4...
```

Repeated requests with the same key should converge on the same logical operation within the documented retention window.

---

# 25. Idempotency Scope

Idempotency keys should be scoped to:

```text
tenant + principal + endpoint + key
```

unless a stronger domain-specific identity is required.

---

# 26. Request Correlation

Every request should support:

```text
request_id
trace_id
correlation_id
```

Eventing 1.27 should receive the correlation information where appropriate.

---

# 27. Resource URLs

Resources should use predictable paths.

Examples:

```text
/v1/tenants/{tenant_id}
/v1/sources/{source_id}
/v1/knowledge/{knowledge_object_id}
/v1/operations/{operation_id}
```

---

# 28. Tenant Scoping in URLs

Two models are possible:

```text
/v1/tenants/{tenant_id}/sources
```

or:

```text
/v1/sources
```

with tenant derived from authentication context.

The preferred default is:

> **Tenant scope is derived from trusted request context rather than trusted URL parameters.**

Administrative APIs may explicitly include tenant identifiers.

---

# 29. Source API

Conceptual operations:

```http
POST   /v1/sources
GET    /v1/sources/{source_id}
PATCH  /v1/sources/{source_id}
DELETE /v1/sources/{source_id}
GET    /v1/sources
```

Source lifecycle follows Design 1.28.

---

# 30. Source Version API

Conceptual:

```http
GET /v1/sources/{source_id}/versions
GET /v1/sources/{source_id}/versions/{version_id}
```

Source versions are immutable.

---

# 31. Ingestion API

Conceptual:

```http
POST /v1/ingestion-runs
GET  /v1/ingestion-runs/{run_id}
POST /v1/ingestion-runs/{run_id}/cancel
```

Long-running ingestion should return an operation or run resource rather than block the HTTP connection indefinitely.

---

# 32. Ingestion Response

Example:

```json
{
  "run_id": "ing_01...",
  "status": "RUNNING",
  "operation_id": "op_01...",
  "source_id": "src_01..."
}
```

---

# 33. Content API

The Platform API may expose authoritative content references.

Example:

```http
GET /v1/content/{artifact_id}
```

However, large content should support streaming rather than requiring a large JSON response.

---

# 34. Content Download

Conceptually:

```http
GET /v1/artifacts/{artifact_id}/content
```

The implementation may return:

* streamed content;
* signed temporary URL;
* range-capable response.

The storage backend remains hidden.

---

# 35. Content Security

Content retrieval must apply Design 1.23 authorization.

A valid artifact ID is not itself proof of access.

---

# 36. Knowledge API

Conceptual:

```http
GET /v1/knowledge/{knowledge_object_id}
GET /v1/knowledge
```

Knowledge responses should expose stable domain concepts rather than database records.

---

# 37. Knowledge Versions

Where knowledge is versioned:

```http
GET /v1/knowledge/{id}/versions
GET /v1/knowledge/{id}/versions/{version}
```

Historical versions must remain subject to authorization.

---

# 38. Semantic Chunk API

Chunks may be exposed for retrieval and provenance:

```http
GET /v1/chunks/{chunk_id}
```

The response may include:

```text
knowledge reference
source reference
provenance
position
text representation
classification
```

Only authorized representations are returned.

---

# 39. Annotation API

Annotations are first-class knowledge according to Design 1.25.

Conceptually:

```http
GET /v1/annotations/{annotation_id}
GET /v1/knowledge/{id}/annotations
```

Annotation identity and versioning remain governed by Design 1.25.

---

# 40. Provenance API

Where applications need lineage:

```http
GET /v1/knowledge/{id}/provenance
```

The response may traverse:

```text
Knowledge
↓
Processing Run
↓
Source Version
↓
Source
```

---

# 41. Search API

Search 1.31 is exposed through a stable API.

Primary endpoint:

```http
POST /v1/search
```

---

# 42. Search Request

Example:

```json
{
  "query": "distributed tracing architecture",
  "filters": {
    "language": "en",
    "source_type": "documentation"
  },
  "limit": 20,
  "profile": "balanced"
}
```

Tenant and principal context are not trusted from the body.

---

# 43. Search Response

Example:

```json
{
  "results": [
    {
      "object_id": "chunk_01...",
      "score": 0.92,
      "title": "Distributed Tracing",
      "snippet": "...",
      "provenance_ref": "/v1/knowledge/chunk_01.../provenance"
    }
  ],
  "metadata": {
    "strategy": "hybrid",
    "configuration_generation": 17
  }
}
```

---

# 44. Search Consistency

The API should expose an optional consistency hint:

```json
{
  "consistency": {
    "mode": "EVENTUAL"
  }
}
```

Supported modes may include:

```text
EVENTUAL
SESSION
VERSION
```

depending on implementation capability.

---

# 45. Search Pagination

Large result sets should use continuation tokens.

Example:

```json
{
  "next_page_token": "..."
}
```

The token must be bound to the relevant search and security context.

---

# 46. Search Security

Search API behavior is governed jointly by:

```text
Identity 1.29
+
Security 1.23
+
Search 1.31
```

The API must not create a parallel access-control system.

---

# 47. AI API

AI Runtime 1.30 may be exposed through Platform API operations.

Example:

```http
POST /v1/ai/executions
GET  /v1/ai/executions/{execution_id}
POST /v1/ai/executions/{execution_id}/cancel
```

---

# 48. AI Execution

Example:

```json
{
  "model_id": "model.example",
  "input": "...",
  "options": {
    "max_tokens": 1000
  }
}
```

Identity and authorization context are established by the authenticated request.

---

# 49. AI Execution Response

Long-running AI execution should use:

```json
{
  "execution_id": "exec_01...",
  "status": "ACCEPTED",
  "operation_id": "op_01..."
}
```

The Platform API does not expose GPU Runtime internals.

---

# 50. AI Authorization

Before an AI execution is accepted, the platform must establish that:

* the principal may invoke the model;
* the tenant may use the model;
* the requested operation is permitted;
* required security constraints are satisfied.

---

# 51. RAG API

A higher-level application API may expose retrieval-augmented generation:

```http
POST /v1/answers
```

Conceptually:

```text
Identity
↓
Authorization
↓
Search
↓
Authorized Evidence
↓
AI Runtime
↓
Answer
```

---

# 52. RAG Provenance

An answer response should be able to include evidence references:

```json
{
  "answer": "...",
  "sources": [
    {
      "chunk_id": "chunk_01...",
      "provenance_ref": "..."
    }
  ]
}
```

---

# 53. RAG Security

Search authorization must occur **before** evidence is supplied to AI Runtime.

Filtering generated output after model execution is insufficient.

---

# 54. Workflow API

Long-running workflows may be exposed:

```http
GET  /v1/workflows/{workflow_id}
POST /v1/workflows/{workflow_id}/cancel
```

The generic workflow engine remains internal infrastructure.

---

# 55. Operation Resource

A unified operation resource provides a common asynchronous contract.

```http
GET /v1/operations/{operation_id}
```

Example:

```json
{
  "operation_id": "op_01...",
  "status": "RUNNING",
  "type": "INGESTION",
  "progress": {
    "completed": 73,
    "total": 100
  }
}
```

> **Boundary with Design 1.27:** The Operation resource is the *external*, public-API contract for long-running work — the durable, versioned surface a client polls or subscribes to. It is distinct from, and does not duplicate, Design 1.27's internal event, command, and workflow semantics (queues, retries, saga/workflow state machines, broker delivery guarantees). Design 1.27 governs how work is internally coordinated and recovered; Design 1.32 governs only what a client observes through `/v1/operations/{operation_id}`. See also §85–§87 (API and Eventing; Internal vs External Events) and §163 (Public API Event Lineage).

---

# 56. Operation States

Initial states:

```text
ACCEPTED
QUEUED
RUNNING
SUCCEEDED
FAILED
CANCEL_REQUESTED
CANCELLED
EXPIRED
```

---

# 57. Operation Error

A failed operation should provide structured error information:

```json
{
  "status": "FAILED",
  "error": {
    "code": "SOURCE_ACCESS_DENIED",
    "message": "The source could not be accessed.",
    "retryable": false
  }
}
```

Messages must not expose secrets or sensitive backend details.

---

# 58. Long-Running Operations

The API should return quickly for work that may exceed normal request budgets.

Examples:

```text
ingestion
reindex
recalculation
bulk processing
AI execution
export
```

---

# 59. Cancellation

Cancellation is a request, not necessarily an instantaneous state transition.

Example:

```http
POST /v1/operations/{id}/cancel
```

The operation may move through:

```text
RUNNING
  ↓
CANCEL_REQUESTED
  ↓
CANCELLED
```

---

# 60. Retry

Clients should distinguish:

```text
HTTP request retry
```

from:

```text
operation retry
```

An HTTP timeout does not necessarily mean the operation failed.

Clients should query the operation using its idempotency key or operation ID.

---

# 61. Error Model

The API should use a consistent structured error format.

Example:

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "The request is invalid.",
    "details": [],
    "request_id": "req_01..."
  }
}
```

---

# 62. Error Categories

Initial categories:

```text
VALIDATION_ERROR
AUTHENTICATION_REQUIRED
AUTHORIZATION_DENIED
RESOURCE_NOT_FOUND
RESOURCE_CONFLICT
PRECONDITION_FAILED
RATE_LIMITED
DEPENDENCY_UNAVAILABLE
OPERATION_FAILED
INTERNAL_ERROR
```

---

# 63. Error Codes

Clients should program against stable error codes, not human-readable messages.

Messages may change without constituting an API breaking change.

---

# 64. Authorization Errors

The API should avoid revealing whether an unauthorized resource exists where policy requires indistinguishability.

For example:

```text
404
```

may be preferable to:

```text
403
```

when resource existence itself is sensitive.

This follows Design 1.23 security policy.

---

# 65. Validation

Requests should be validated before expensive downstream execution.

Validation includes:

* schema;
* field constraints;
* tenant scope;
* resource references;
* allowed enum values;
* operation limits.

---

# 66. Pagination

Collection APIs should support cursor-based pagination.

Example:

```text
GET /v1/sources?limit=50&page_token=...
```

The API should avoid exposing backend offset semantics.

---

# 67. Pagination Stability

A continuation token should encapsulate sufficient state to provide stable traversal.

Tokens should expire.

---

# 68. Sorting

Collection endpoints should expose a documented set of sortable fields.

Clients should not directly provide arbitrary database sort expressions.

---

# 69. Filtering

Filters should use a stable API-level model.

Example:

```text
status=ACTIVE
source_type=document
created_after=...
```

Backend-specific query syntax must not leak through.

---

# 70. Field Selection

Optional field selection may reduce payload size:

```text
?fields=id,status,created_at
```

However, field selection must never bypass security filtering.

---

# 71. Optimistic Concurrency

Mutable resources may expose:

```text
ETag
```

or:

```text
version
```

Clients can then use:

```http
If-Match: "..."
```

to prevent lost updates.

---

# 72. Resource Version

Example:

```json
{
  "source_id": "src_01...",
  "version": 7
}
```

The version is a domain/API concurrency concept and should not be confused with source content versions.

---

# 73. PATCH Semantics

PATCH operations should be explicit about:

* mutable fields;
* immutable fields;
* validation;
* concurrency.

Clients must not be able to mutate immutable identifiers or source versions.

---

# 74. Bulk APIs

Bulk operations may be required for:

* ingestion;
* metadata updates;
* deletion;
* reindexing.

Bulk APIs must preserve per-item results.

---

# 75. Bulk Partial Success

Example:

```json
{
  "accepted": 97,
  "rejected": 3,
  "items": [
    {
      "id": "...",
      "status": "REJECTED",
      "error": {
        "code": "VALIDATION_ERROR"
      }
    }
  ]
}
```

---

# 76. Bulk Safety

Bulk operations must not bypass:

* authorization;
* tenant isolation;
* validation;
* audit requirements.

---

# 77. Rate Limiting

Rate limits should exist at multiple levels:

```text
principal
tenant
application
endpoint
operation type
```

---

# 78. Rate Limit Response

The API should provide enough information for clients to retry safely.

Where appropriate:

```http
Retry-After: 30
```

---

# 79. Quotas

Quotas may apply to:

* ingestion volume;
* search requests;
* AI execution;
* storage;
* concurrent operations.

Quota enforcement should remain separate from authentication.

---

# 80. API Security Headers

The HTTP implementation should use appropriate security controls including:

* TLS;
* secure headers;
* content-type validation;
* request size limits;
* authentication validation.

Exact gateway configuration is implementation-specific.

---

# 81. Request Size Limits

Endpoints should define maximum request sizes.

Large content should use dedicated upload/streaming mechanisms rather than unrestricted JSON bodies.

---

# 82. Large File Upload

The API may provide:

```text
upload session
```

rather than embedding large content in API requests.

Example:

```http
POST /v1/uploads
```

followed by:

```http
PUT /v1/uploads/{upload_id}/content
```

---

# 83. Upload Lifecycle

Conceptually:

```text
CREATED
   ↓
UPLOADING
   ↓
UPLOADED
   ↓
VALIDATING
   ↓
COMMITTED
```

An incomplete upload must never become authoritative content.

---

# 84. API and Content Cache

The Platform API should delegate artifact storage to Content Cache 1.26.

It must not expose:

* Cassandra tables;
* object-store buckets;
* internal cache keys.

---

# 85. API and Eventing

The API may publish commands/events through Eventing 1.27.

Example:

```text
API Request
    ↓
Command
    ↓
Workflow
    ↓
Processing
    ↓
Events
```

The client should normally observe the resulting resource/operation, not internal broker messages.

---

# 86. API Events

An optional public event interface may expose durable domain events:

```text
SourceCreated
SourceChanged
SourceDeleted
IngestionCompleted
KnowledgeUpdated
SearchCompleted
AIExecutionCompleted
```

Public event schemas must be governed separately from internal events.

---

# 87. Internal vs External Events

Internal event schemas may contain implementation details.

Public events should contain only stable domain facts.

Therefore:

```text
Internal Event
≠
Public API Event
```

even if they share conceptual meaning.

---

# 88. Webhooks

Future integrations may use webhooks.

Webhook delivery must follow Eventing 1.27 principles:

* at-least-once delivery;
* signed payloads;
* retries;
* idempotency;
* replay protection.

---

# 89. Webhook Security

Webhook consumers must be able to verify authenticity.

Payloads should include:

```text
event_id
event_type
event_version
occurred_at
tenant_id
```

plus an integrity mechanism.

---

# 90. SDKs

Official SDKs may be provided for:

```text
Python
TypeScript
Java
Go
```

SDKs should wrap the stable Platform API contract.

They should not expose internal implementation details.

---

# 91. API Documentation

The API should provide machine-readable OpenAPI definitions.

The generated documentation must correspond to the deployed contract.

---

# 92. OpenAPI Governance

OpenAPI should be treated as a contract artifact.

Changes should be checked for:

* breaking changes;
* incompatible enum changes;
* removed fields;
* changed required fields;
* changed response semantics.

---

# 93. Contract Testing

API contract tests should verify:

* request validation;
* response schema;
* authentication;
* authorization;
* tenant isolation;
* idempotency;
* pagination;
* error behavior.

---

# 94. Consumer-Driven Contracts

Important integrations may use consumer-driven contract tests.

This is particularly useful for:

* SDKs;
* ingestion integrations;
* application services;
* external automation.

---

# 95. API Compatibility Testing

Each release should test:

```text
current server
+
previous client
```

where compatibility is promised.

---

# 96. Deprecation

Deprecated endpoints must provide:

* deprecation notice;
* replacement endpoint;
* migration documentation;
* retirement date where known.

---

# 97. Breaking Changes

Breaking changes require:

```text
new major API version
```

unless an explicit compatibility mechanism exists.

---

# 98. API Lifecycle

The lifecycle should be:

```text
PROPOSED
   ↓
PREVIEW
   ↓
STABLE
   ↓
DEPRECATED
   ↓
RETIRED
```

---

# 99. Preview APIs

Preview APIs may evolve more rapidly.

They should be clearly marked:

```text
/v1beta/...
```

or equivalent contract metadata.

Preview APIs must still obey security requirements.

---

# 100. API Observability

Every request should emit:

```text
request_count
latency
status
tenant
principal_type
endpoint
```

Sensitive values must be excluded from telemetry unless explicitly governed.

---

# 101. Distributed Tracing

API traces should propagate into:

```text
Eventing
Ingestion
Knowledge processing
Search
AI Runtime
```

using the tracing model established across the platform.

---

# 102. Audit

Security-sensitive API actions should produce audit events.

Examples:

```text
principal changes
source deletion
knowledge deletion
credential operations
administrative tenant operations
AI execution
bulk export
```

---

# 103. Audit vs Application Logs

Audit records are not ordinary application logs.

Audit records must have:

* controlled retention;
* integrity requirements;
* access controls;
* clear actor identity.

---

# 104. Privacy

The Platform API should minimize exposure of personal information.

Responses should include only attributes required by the use case.

---

# 105. Query Privacy

Search queries and AI prompts may contain sensitive information.

The API must not automatically expose raw query/prompt content through:

* logs;
* metrics;
* traces;
* audit events.

---

# 106. API Caching

Caching may be used for safe read operations.

Authorization-sensitive resources require cache keys and invalidation semantics compatible with Design 1.23.

---

# 107. ETags

ETags may be used for:

* resource caching;
* optimistic concurrency;
* conditional retrieval.

ETags must not expose internal database identifiers.

---

# 108. Availability

The API should distinguish:

```text
control-plane failure
```

from:

```text
data-plane failure
```

A temporary dependency failure should return a stable error rather than leaking implementation-specific exceptions.

---

# 109. Partial Availability

Some endpoints may remain available when others are degraded.

For example:

```text
GET /sources
```

may remain available while:

```text
POST /ai/executions
```

is temporarily unavailable.

---

# 110. Async-First for Long Work

The API should prefer:

```text
accept
  ↓
operation_id
  ↓
poll / subscribe
```

for work exceeding interactive request budgets.

---

# 111. Synchronous APIs

Synchronous APIs should be reserved for operations expected to complete within the endpoint's latency budget.

Examples:

```text
GET resource
simple metadata update
search
small content retrieval
```

---

# 112. Streaming APIs

Streaming may be appropriate for:

* large content;
* AI token streams;
* event streams.

Streaming must still preserve authentication and authorization semantics.

---

# 113. AI Streaming

An AI response stream must not bypass authorization checks established before execution.

Cancellation and timeout must propagate to AI Runtime.

---

# 114. Search Streaming

Search itself normally returns bounded results.

Streaming should only be introduced where the result model genuinely benefits from it.

---

# 115. API Gateway vs Platform API

The distinction is:

```text
Gateway
  =
transport/security edge

Platform API
  =
domain contract
```

The gateway must not contain the core business semantics of Synanton.

---

# 116. Backend Independence

The Platform API must remain unchanged if Synanton replaces:

```text
Cassandra
OpenSearch
PostgreSQL
Kafka
vector database
LLM provider
GPU runtime
```

provided the architectural contracts remain satisfied.

---

# 117. Failure Translation

Internal errors such as:

```text
ConnectionTimeoutException
KafkaNotAvailableException
OpenSearchRejectedExecutionException
```

must never become public API contracts.

They must map to stable domain/API errors.

---

# 118. API Error Example

Instead of:

```json
{
  "error": "org.elasticsearch..."
}
```

return:

```json
{
  "error": {
    "code": "DEPENDENCY_UNAVAILABLE",
    "message": "Search is temporarily unavailable.",
    "retryable": true
  }
}
```

---

# 119. API Security Boundary

The Platform API is a trust boundary.

All external input must be considered untrusted.

This includes:

* path parameters;
* query parameters;
* request bodies;
* uploaded files;
* metadata;
* search queries;
* AI prompts;
* callback URLs.

---

# 120. SSRF and Callback Safety

Any future API that accepts URLs must validate:

* allowed schemes;
* network destinations;
* private address ranges;
* redirect behavior;
* authentication boundaries.

This is particularly important for connector and ingestion APIs.

---

# 121. File Upload Security

Uploaded content must be treated as untrusted.

Validation may include:

* size limits;
* MIME verification;
* malware scanning;
* decompression limits;
* archive traversal protection;
* parser isolation.

---

# 122. API and Prompt Injection

AI-related API inputs must not be treated as trusted instructions by the platform.

The API establishes:

```text
who requested the operation
```

but does not establish:

```text
whether the prompt's instructions are trustworthy
```

---

# 123. Tenant Isolation Testing

Every endpoint that accepts a resource identifier must test:

```text
tenant A resource
+
tenant B principal
```

and verify that unauthorized access is impossible.

---

# 124. API Rate-Limit Isolation

One tenant must not be able to consume all shared API capacity.

Rate limiting should include tenant-aware controls.

---

# 125. API Resource Quotas

Resource creation should enforce quotas before expensive downstream processing.

---

# 126. Platform API and Identity Lifecycle

Identity lifecycle operations should generally be exposed separately from ordinary application APIs.

Examples:

```text
principal administration
membership administration
credential management
```

must require appropriate administrative authorization.

> **Identity-management API surface:** The tenant, principal, membership/delegation, and federation endpoints referenced in this design (§7 Resource Model; §19–§21 Principal Context, Actor vs Executor, Delegation; Appendix A `/v1/tenants`, `/v1/principals`) constitute the identity-management API surface that Platform API 1.32 exposes. Design 1.29 remains the semantic authority for the underlying principal, tenant-membership, delegation, and federation model; 1.32 does not redefine that model — it exposes it as a stable, authorized, resource-oriented contract, subject to the same administrative-authorization requirement stated above.

---

# 127. Platform API and Security Policy

The API may expose policy evaluation results where appropriate, but it must not allow clients to mutate security policy without explicit administrative capabilities.

---

# 128. Platform API and Search

Search-specific configuration should not become a public backend configuration surface.

A client may select:

```text
profile = balanced
```

but should not submit:

```text
opensearch_query
embedding_index_name
reranker_internal_model_id
```

unless explicitly exposed as a controlled expert API.

---

# 129. Platform API and AI Runtime

The API should expose AI capabilities at a domain level.

The client should not know:

```text
GPU node
pod
vLLM process
artifact cache
lease
```

Those remain AI Runtime implementation details.

---

# 130. Platform API and Ingestion

The API should expose ingestion as a domain operation.

The client should not need to orchestrate:

```text
connector
download
validation
content cache
processing
knowledge
events
```

individually for normal ingestion.

---

# 131. Platform API and Workflow

The API should hide internal workflow topology.

Clients interact with:

```text
operation
run
resource
```

rather than workflow engine implementation details.

---

# 132. Platform API and Analytics

Analytics APIs should expose stable metrics and reports.

They should not expose ClickHouse queries or storage schemas.

---

# 133. Platform API and Provenance

Where provenance is exposed, references should remain stable even if internal storage changes.

---

# 134. API Request Lifecycle

```text
Client
  │
  ▼
API Gateway
  │
  ▼
Authentication
  │
  ▼
Identity Context
  │
  ▼
Tenant Resolution
  │
  ▼
Authorization
  │
  ▼
Validation
  │
  ▼
Domain Operation
  │
  ▼
Workflow / Eventing / Data Plane
  │
  ▼
Response
```

---

# 135. API Response Lifecycle

For synchronous work:

```text
request
  ↓
validate
  ↓
authorize
  ↓
execute
  ↓
return resource/result
```

For asynchronous work:

```text
request
  ↓
validate
  ↓
authorize
  ↓
create operation
  ↓
return operation
  ↓
background execution
```

---

# 136. API Consistency Model

The API must explicitly communicate when operations are:

* strongly consistent;
* transactionally committed;
* eventually consistent;
* asynchronously processed.

Clients should never have to infer consistency behavior from timing.

---

# 137. Resource State vs Operation State

These must remain separate.

Example:

```text
Operation:
RUNNING

Source:
ACTIVE
```

The operation describes work in progress.

The resource describes current domain state.

---

# 138. Idempotent Creation Example

```text
POST /v1/sources
Idempotency-Key: abc
```

may return:

```text
201 Created
```

on the first request and:

```text
200/201 equivalent result
```

on a safe retry, according to the API contract.

The important property is logical convergence.

---

# 139. API Transaction Boundary

A successful API response must correspond to a clearly defined durable state transition.

The API must not return success merely because an internal asynchronous task was submitted to an unreliable in-memory queue.

---

# 140. Durable Acceptance

For asynchronous operations:

```text
HTTP 202 Accepted
```

should mean:

> Synanton durably accepted the operation for processing.

It should not mean:

> The request was placed in memory and may disappear.

---

# 141. Operation Durability

Operation state should survive:

* process restart;
* API instance failure;
* worker failure.

---

# 142. API Retries

Clients should be able to safely retry requests when:

* network connection is lost;
* gateway times out;
* client does not receive a response.

Idempotency and operation lookup make this possible.

---

# 143. API Client Guidance

Official documentation should provide retry guidance:

```text
network failure
  ↓
retry using same Idempotency-Key
  ↓
retrieve operation if accepted
```

---

# 144. API Timeouts

Every endpoint should have an explicit server-side timeout budget.

The API must avoid unbounded requests.

---

# 145. Dependency Timeouts

Downstream calls should have shorter bounded timeouts than the API request itself.

---

# 146. Circuit Breaking

Internal dependency failures may trigger circuit breakers.

Circuit-breaker state must not become part of the public API contract.

---

# 147. API Load Shedding

Under extreme load, the platform may reject new work early.

The rejection must use a stable error such as:

```text
RATE_LIMITED
```

or:

```text
SERVICE_UNAVAILABLE
```

---

# 148. API Performance Targets

Initial targets:

```text
simple metadata API p95 < 200 ms
interactive search p95 < 500 ms
operation submission p95 < 300 ms
```

These are initial engineering targets and should be refined using production workload measurements.

---

# 149. API Capacity

Capacity planning should account for:

```text
requests/sec
concurrent requests
payload size
tenant distribution
long-running operations
search load
AI submission load
```

---

# 150. API Documentation Structure

The public API documentation should contain:

```text
Authentication
Authorization
Tenant Context
Resources
Endpoints
Request Schemas
Response Schemas
Errors
Pagination
Idempotency
Async Operations
Webhooks
Rate Limits
Versioning
Examples
SDKs
```

---

# 151. API Examples

Examples should be:

* complete;
* executable;
* security-aware;
* tenant-aware;
* consistent with current schemas.

Examples must not use fake credentials that could accidentally resemble real secrets.

---

# 152. API Governance

Every public API change should undergo:

1. schema review;
2. security review;
3. compatibility review;
4. observability review;
5. documentation review.

---

# 153. API Ownership

Platform API owns:

* public contracts;
* resource semantics;
* compatibility;
* error semantics;
* API lifecycle;
* contract documentation.

Domain planes own:

* domain behavior;
* internal processing;
* storage;
* execution.

---

# 154. Reference Component Architecture

A reference implementation may contain:

```text
platform-api
├── api-contract
├── authentication-adapter
├── identity-context
├── authorization-adapter
├── tenant-context
├── resource-services
├── operation-service
├── error-mapper
├── pagination
├── idempotency
├── concurrency
├── request-validation
├── event-adapter
├── workflow-adapter
├── search-adapter
├── ai-adapter
├── ingestion-adapter
├── content-adapter
├── provenance-adapter
└── observability
```

---

# 155. Domain Independence

The Platform API implementation should not embed:

```text
database queries
search backend queries
Kafka producers
GPU scheduling
LLM-specific logic
```

These belong behind domain/application boundaries.

---

# 156. API Contract Repository

The API contract should be maintained as a first-class artifact.

Recommended:

```text
docs/api/
contracts/
openapi/
examples/
```

The exact repository structure may evolve.

---

# 157. Contract CI

CI should automatically detect:

```text
breaking schema changes
removed endpoints
changed required fields
incompatible response types
```

before merge.

---

# 158. Security CI

Automated tests should include:

* authentication bypass;
* tenant isolation;
* authorization bypass;
* IDOR;
* malformed input;
* oversized payload;
* replay;
* idempotency abuse;
* rate-limit bypass.

---

# 159. API Fuzzing

Public request parsers should be fuzz-tested for:

* malformed JSON;
* unusual Unicode;
* boundary lengths;
* nested structures;
* invalid encodings.

---

# 160. API Threat Model

Primary threats include:

```text
credential theft
token replay
tenant escape
IDOR
request smuggling
query injection
resource exhaustion
file upload attacks
SSRF
webhook spoofing
information leakage
```

---

# 161. Threat Mitigation

The architecture mitigates these through:

```text
Identity 1.29
Security 1.23
strict validation
tenant context
authorization
bounded resources
signed webhooks
audit
rate limiting
```

---

# 162. API Auditability

A security-sensitive request should be traceable:

```text
request_id
↓
principal
↓
tenant
↓
operation
↓
domain event
↓
result
```

---

# 163. Public API Event Lineage

For asynchronous work:

```text
API Request
    ↓
Operation
    ↓
Command
    ↓
Workflow
    ↓
Domain Events
    ↓
Resource State
```

This connects Platform API 1.32 to Eventing 1.27.

---

# 164. Search Request Lineage

```text
API Request
    ↓
Principal
    ↓
Authorization
    ↓
Search Plan
    ↓
Projection Generation
    ↓
Knowledge
    ↓
Result
```

This connects API 1.32 to Search 1.31.

---

# 165. Ingestion Request Lineage

```text
API Request
    ↓
Principal
    ↓
Authorization
    ↓
Ingestion Run
    ↓
Source Version
    ↓
Content Artifact
    ↓
Knowledge
    ↓
Search
```

---

# 166. AI Request Lineage

```text
API Request
    ↓
Principal
    ↓
Authorization
    ↓
AI Execution
    ↓
AI Runtime
    ↓
Model
    ↓
Result
```

Where RAG is used:

```text
Search
    ↓
Authorized Evidence
    ↓
AI Runtime
```

---

# 167. Public API vs Internal API

The platform may contain many internal APIs.

The distinction is:

```text
Public Platform API
    =
stable external contract

Internal API
    =
implementation contract
```

Internal APIs may evolve faster.

---

# 168. Service Discovery

Clients must not depend on internal service discovery.

The Platform API is the stable entry point.

---

# 169. Multi-Region Considerations

Future multi-region deployments may expose a stable API while routing requests internally.

Clients should not need to know:

```text
region
cluster
pod
database
```

unless region affinity is explicitly part of the product contract.

---

# 170. Data Residency

Where data residency applies, tenant policy may constrain processing location.

The API should expose residency-related constraints only as stable domain concepts.

---

# 171. Export API

Future export operations may provide:

```http
POST /v1/exports
GET  /v1/exports/{export_id}
```

Exports are asynchronous and security-sensitive.

---

# 172. Import API

Import should similarly use an asynchronous operation:

```http
POST /v1/imports
```

Imported content remains subject to ingestion and validation rules.

---

# 173. Administrative APIs

Administrative APIs should be separated logically from ordinary application operations.

Examples:

```text
tenant administration
identity administration
search configuration
platform configuration
```

Administrative APIs require stronger authorization and auditing.

---

# 174. Expert APIs

Some advanced functionality may require expert APIs.

Examples:

```text
search benchmark execution
projection rebuild
index generation management
advanced AI configuration
```

These should not become accidental dependencies for ordinary clients.

---

# 175. Internal Control APIs

Operational APIs such as:

```text
/reindex
/debug
/reconcile
```

must not automatically become public APIs.

Operational control remains an administrative boundary.

---

# 176. API Stability Principle

The Platform API should evolve slower than internal planes.

This is intentional.

```text
Internal implementation
       ↓
can change frequently

Platform API
       ↓
changes deliberately
```

---

# 177. API Contract First

New platform capabilities should preferably be designed as:

```text
domain contract
    ↓
API contract
    ↓
implementation
```

rather than exposing an existing internal implementation directly.

---

# 178. Implementation Phases

## Phase 1 — API Foundation

Implement:

* API versioning;
* authentication context;
* tenant context;
* error model;
* request IDs;
* tracing;
* OpenAPI.

### Exit Criteria

A secure versioned API foundation exists.

---

## Phase 2 — Resource APIs

Implement:

* sources;
* source versions;
* knowledge;
* chunks;
* provenance.

### Exit Criteria

Core platform resources are externally consumable.

---

## Phase 3 — Async Operations

Implement:

* operation resource;
* idempotency;
* cancellation;
* retry semantics;
* durable operation state.

### Exit Criteria

Long-running operations have a consistent API contract.

---

## Phase 4 — Search API

Integrate Search 1.31:

* query API;
* filters;
* pagination;
* search profiles;
* provenance;
* consistency hints.

### Exit Criteria

Applications can use Search without knowing the search backend.

---

## Phase 5 — Ingestion API

Integrate Ingestion 1.28:

* source creation;
* ingestion runs;
* uploads;
* operation tracking.

### Exit Criteria

Applications can submit and monitor ingestion.

---

## Phase 6 — AI API

Integrate AI Runtime 1.30:

* execution;
* status;
* cancellation;
* streaming where required;
* RAG.

### Exit Criteria

Applications can consume AI capabilities without GPU Runtime knowledge.

---

## Phase 7 — Governance

Implement:

* compatibility CI;
* security testing;
* contract testing;
* documentation;
* SDK generation;
* deprecation process.

### Exit Criteria

The API can evolve safely.

---

# 179. Acceptance Criteria

Design 1.32 is implementation-ready when:

### Contract

* API v1 is defined;
* OpenAPI is available;
* resource models are stable;
* error codes are documented;
* versioning rules are defined.

### Security

* authentication integrates with 1.29;
* authorization integrates with 1.23;
* tenant scope is explicit;
* IDOR protection exists;
* sensitive existence leakage is controlled.

### Operations

* async operations are durable;
* idempotency works;
* cancellation semantics are documented;
* retries are safe;
* tracing works.

### Integration

* Search 1.31 is exposed;
* Ingestion 1.28 is exposed;
* Knowledge 1.25 is exposed;
* AI Runtime 1.30 is exposed;
* Eventing 1.27 is integrated.

### Evolution

* breaking-change detection exists;
* deprecation process exists;
* contract tests exist;
* SDK generation is reproducible.

---

# 180. Architectural Invariants

The following invariants are mandatory:

1. **The Platform API is the stable external contract.**
2. **Internal service topology is not part of the public contract.**
3. **Authentication comes from Identity 1.29.**
4. **Authorization follows Security 1.23.**
5. **Authenticated does not imply authorized.**
6. **Every request has explicit tenant scope.**
7. **Tenant scope cannot be trusted from unverified client input.**
8. **Resource identifiers are stable and implementation-independent.**
9. **Long-running operations have durable operation state.**
10. **Mutating APIs support idempotency where required.**
11. **HTTP retries must not accidentally duplicate operations.**
12. **API errors use stable domain error codes.**
13. **Internal exceptions are never public API contracts.**
14. **Search backend syntax is never exposed accidentally.**
15. **GPU Runtime internals are never exposed accidentally.**
16. **Content storage topology is never exposed accidentally.**
17. **Public events are separate contracts from internal events.**
18. **Resource state and operation state remain distinct.**
19. **Asynchronous acceptance means durable acceptance.**
20. **API responses never bypass authorization.**
21. **Pagination tokens are security-context aware.**
22. **Bulk APIs preserve per-item authorization and result state.**
23. **Audit preserves actor and executor identity.**
24. **API observability does not leak sensitive payloads by default.**
25. **Breaking changes require explicit versioning.**
26. **Deprecated APIs have a controlled lifecycle.**
27. **API contracts are tested independently of implementation.**
28. **Platform API clients do not need to know internal storage technology.**
29. **Platform API clients do not need to know internal event brokers.**
30. **Platform API clients do not need to know internal workflow engines.**

---

# Appendix A — Platform API Surface

Initial conceptual surface:

```text
/v1/tenants
/v1/principals
/v1/sources
/v1/sources/{id}/versions
/v1/ingestion-runs
/v1/artifacts
/v1/content
/v1/knowledge
/v1/chunks
/v1/annotations
/v1/search
/v1/ai/executions
/v1/answers
/v1/workflows
/v1/operations
/v1/exports
```

The exact endpoint set should be finalized during API contract design.

---

# Appendix B — Unified Operation Model

```yaml
operation:
  operation_id: op_01...
  type: INGESTION
  status: RUNNING
  requested_by:
    principal_id: prn_01...
  tenant_id: ten_01...
  created_at: ...
  started_at: ...
  completed_at: null
  progress:
    completed: 73
    total: 100
  error: null
```

---

# Appendix C — Unified Error Model

```yaml
error:
  code: AUTHORIZATION_DENIED
  message: Access denied.
  retryable: false
  request_id: req_01...
  details: []
```

---

# Appendix D — Request Context

```yaml
request_context:
  request_id: req_01...
  trace_id: trace_01...
  correlation_id: corr_01...

  principal:
    principal_id: prn_01...
    type: HUMAN

  tenant:
    tenant_id: ten_01...

  authentication:
    method: OIDC
    assurance_level: HIGH

  delegation:
    chain: []
```

---

# Appendix E — Example Ingestion API Flow

```text
POST /v1/sources
        │
        ▼
Source Created
        │
        ▼
POST /v1/ingestion-runs
        │
        ▼
202 Accepted
        │
        ▼
operation_id
        │
        ▼
GET /v1/operations/{id}
        │
        ▼
SUCCEEDED
        │
        ▼
Knowledge Available
        │
        ▼
Search
```

---

# Appendix F — Example Search Flow

```text
POST /v1/search
        │
        ▼
Identity 1.29
        │
        ▼
Security 1.23
        │
        ▼
Search 1.31
        │
        ▼
Authorized Results
```

The client never needs to know whether Search used:

```text
BM25
vector retrieval
RRF
graph retrieval
reranking
OpenSearch
Vespa
another backend
```

---

# Appendix G — Example RAG Flow

```text
POST /v1/answers
        │
        ▼
Identity
        │
        ▼
Authorization
        │
        ▼
Search
        │
        ▼
Authorized Evidence
        │
        ▼
AI Runtime
        │
        ▼
Answer + Sources
```

---

# Appendix H — API Dependency Map

```text
                         Platform API 1.32
                                │
          ┌─────────────────────┼──────────────────────┐
          ▼                     ▼                      ▼
     Identity 1.29        Security 1.23          Eventing 1.27
          │                     │                      │
          └─────────────────────┼──────────────────────┘
                                │
       ┌────────────────────────┼─────────────────────────┐
       ▼                        ▼                         ▼
Ingestion 1.28           Knowledge 1.25             Search 1.31
       │                        │                         │
       ▼                        ▼                         ▼
Content Cache 1.26       Provenance                AI Runtime 1.30
```

---

# Appendix I — Contract Evolution

```text
API v1
│
├── additive field
│      → compatible
│
├── new endpoint
│      → compatible
│
├── optional request field
│      → compatible
│
├── removed response field
│      → breaking
│
├── changed required field
│      → breaking
│
└── changed semantic meaning
        → breaking
```

---

# Appendix J — Final Architectural Thesis

The Platform API is not another domain plane.

It is the **stable boundary through which the domain planes become a platform**.

The separation is:

```text
Identity
    → Who is acting?

Security
    → What may that principal access?

Ingestion
    → How does source material enter Synanton?

Content
    → What source artifacts were acquired?

Knowledge
    → What does Synanton understand?

Search
    → What is relevant to this query?

AI Runtime
    → What computation can be executed?

Eventing / Workflow
    → How is durable work coordinated?

Analytics
    → What happened and how is it measured?

Platform API
    → How do applications interact with all of the above?
```

The most important principle is:

> **The Platform API exposes capabilities, not implementation topology.**

Applications should be able to build against:

```text
sources
knowledge
search
AI
operations
provenance
```

without knowing:

```text
which database
which search engine
which vector store
which broker
which workflow engine
which GPU
which model server
```

implements them.

This gives Synanton a durable architectural boundary:

```text
                  Stable Platform API
                         │
        ┌────────────────┼────────────────┐
        ▼                ▼                ▼
     Clients        Applications       Integrations
                         │
                         ▼
              ┌────────────────────┐
              │   Synanton Planes   │
              └────────────────────┘
                         │
                         ▼
             Replaceable Infrastructure
```

The resulting architecture allows the implementation underneath the API to evolve continuously while preserving application compatibility.

> **Internal architecture may change quickly. The Platform API changes deliberately.**

That is the final boundary required to turn the preceding Synanton designs into a coherent platform rather than a collection of independently accessible services.