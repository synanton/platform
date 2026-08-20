# Synanton v1.20 — GPU Execution Plane

**Status:** Final
**Supersedes:** GPU-related assumptions in Synanton Platform v1.19
**Scope:** GPU execution isolation and the contract between the primary Synanton platform and the GPU execution plane

---

## 1. Executive Summary

Synanton v1.20 introduces a strict architectural boundary between the **primary Synanton platform** and a separate **GPU Execution Plane**.

The primary platform remains responsible for business intent, tenant identity, authorization policy, model selection, execution planning, workflow state, degraded-mode orchestration, and cost attribution.

The GPU Execution Plane is responsible for GPU-specific execution: model serving, GPU admission, request dispatch, runtime lifecycle, GPU capacity, and execution telemetry.

The two planes communicate through a narrow, versioned gRPC contract.

The central invariant is:

> **Synanton decides what should run. The GPU Execution Plane decides how GPU work  is executed. Kubernetes decides where the workload runs.**

The GPU Execution Plane is not another Synanton control plane. It is a  remotely executable infrastructure capability behind a strict contract.

This separation keeps GPU-specific infrastructure out of the primary  platform, allows GPU capacity to evolve independently, and prevents the primary platform from acquiring dependencies on Kubernetes, GPU drivers, model-serving runtimes, or GPU scheduling internals.

------

# 2. Goals

v1.20 has the following goals:

1. Physically isolate GPU workloads from the primary Synanton cluster.
2. Keep GPU infrastructure independently deployable and scalable.
3. Keep the primary platform independent of GPU-specific infrastructure.
4. Preserve `ModelServingDirectory` as a logical model-to-endpoint abstraction.
5. Provide a stable execution contract for synthesis, embedding, and reranking.
6. Support multiple GPU execution strategies without changing the primary platform.
7. Make Equalix optional rather than a mandatory dependency.
8. Preserve Synanton's existing authentication, authorization, validation, observability, and error conventions.
9. Support graceful degradation when GPU execution is unavailable.
10. Avoid distributed transactions across the CPU/GPU boundary.
11. Preserve clear ownership of business state versus GPU execution state.

------

# 3. Non-Goals

v1.20 does **not** introduce:

- a new business-logic engine;
- a replacement for Resolutor;
- a replacement for Commitix;
- a GPU resource-management API in the primary platform;
- exactly-once execution;
- distributed transactions / 2PC;
- GPU-node scheduling in Synanton;
- Kubernetes control from the primary platform;
- model artifact management in the primary platform;
- a new identity system;
- tenant identity based solely on a caller-supplied `tenant_id`.

Equalix remains an optional scheduling component and is not required for the first GPU implementation.

------

# 4. Architectural Boundary

## 4.1 Primary Synanton Platform

The primary platform owns:

- external API ingress;
- authentication;
- authenticated service identity;
- tenant identity and tenant policy;
- authorization;
- model logical identity;
- approved model/version selection;
- execution planning;
- business/workflow state;
- request lifecycle;
- degraded-mode orchestration and policy;
- cost attribution;
- audit;
- cross-platform tracing context.

Relevant existing components include:

- `synapt`;
- `security`;
- `topology`;
- `planner`;
- `gateway`;
- `control-plane`;
- `ModelServingDirectory`.

`control-plane` remains one component of the primary platform. It is **not** synonymous with the entire CPU platform.

## 4.2 GPU Execution Plane

The GPU Execution Plane owns:

- GPU Gateway;
- GPU-specific request validation at the execution boundary;
- service authentication;
- authorization assertion validation;
- GPU admission;
- request dispatch;
- model-serving runtime;
- GPU capacity;
- GPU-specific execution state;
- execution telemetry;
- GPU usage reporting;
- runtime health;
- Kubernetes deployment and scheduling.

The GPU plane MUST NOT own business workflow state or tenant policy.

------

# 5. Physical Topology

text

```
                         SYNANTON PRIMARY PLATFORM
                              CPU CLUSTER
┌──────────────────────────────────────────────────────────────────┐
│                                                                  │
│  External API                                                    │
│       │                                                          │
│       ▼                                                          │
│    synapt ───── authentication / tenant context                  │
│       │                                                          │
│       ▼                                                          │
│    planner ───── execution planning                              │
│       │                                                          │
│       ▼                                                          │
│    gateway ───── query/workflow execution                        │
│       │                                                          │
│       ├──── ModelServingDirectory                                │
│       │       logical model → logical execution endpoint         │
│       │                                                          │
│       └──── GPU Execution Client                                 │
│                     │                                            │
└─────────────────────┼────────────────────────────────────────────┘
                      │
                      │ gRPC + mTLS
                      │ versioned contract
                      ▼
                 GPU EXECUTION PLANE
                    GPU CLUSTER
┌──────────────────────────────────────────────────────────────────┐
│                                                                  │
│                      GPU Gateway                                 │
│                           │                                      │
│                  ┌────────┴─────────┐                            │
│                  │                  │                            │
│           DirectDispatcher   EqualixScheduler                    │
│             (default)          (optional)                        │
│                  │                  │                            │
│                  └────────┬─────────┘                            │
│                           ▼                                      │
│                   Kubernetes Service                             │
│                           │                                      │
│                           ▼                                      │
│                       vLLM pods                                  │
│                           │                                      │
│                           ▼                                      │
│                       GPU nodes                                  │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

The primary platform MUST NOT directly discover GPU pods, nodes, GPUs, or vLLM instances.

The GPU Gateway is the sole execution-plane boundary exposed to Synanton.

------

# 6. Repository Boundary

The GPU execution implementation lives in:

```
synanton/gpu-execution-plane
```

The primary platform remains:

```
synanton/platform
```

The repositories are intentionally separate.

The primary reason for the repository split is **source-level dependency isolation**:

> GPU execution implementation must not acquire dependencies on primary-platform internals.

Additional benefits include:

- independent CI/CD;
- independent GPU-specific release lifecycle;
- independent infrastructure configuration;
- independent Kubernetes deployment;
- clearer ownership;
- reduced coupling between CPU and GPU runtime concerns.

The GPU repository may depend on the **versioned public execution contract**, but MUST NOT depend on internal classes from `synanton/platform`.

------

# 7. Model Serving Abstraction

`ModelServingDirectory` remains a primary-platform abstraction.

It resolves:

```text
logical model + model version
        ↓
logical execution endpoint
```

It MUST NOT resolve:

- GPU pod IPs;
- Kubernetes pods;
- Kubernetes nodes;
- individual GPUs;
- vLLM instances.

The primary platform therefore remains independent of the physical GPU topology.

## 7.1 Model Lifecycle Ownership

| Concern                  | Owner               |
| ------------------------ | ------------------- |
| Logical model identity   | Primary Platform    |
| Model/version approval   | Primary Platform    |
| Tenant/model policy      | Primary Platform    |
| Logical endpoint mapping | Primary Platform    |
| Model deployment         | GPU Execution Plane |
| Runtime configuration    | GPU Execution Plane |
| Replica lifecycle        | GPU Execution Plane |
| GPU placement            | Kubernetes          |
| Runtime health           | GPU Execution Plane |

The GPU Execution Plane MUST NOT deploy a model/version that has not been  approved and made available through the primary platform's logical model configuration.

## 7.2 Model Readiness and Cold Start

The GPU Gateway may return `MODEL_NOT_READY` if the requested model is approved but not currently loaded into GPU memory.

To prevent a thundering herd of retries from the primary platform, the Gateway:

- MUST queue the request and trigger an asynchronous model load if one is not already in progress;
- MAY serve queued requests once the model becomes ready, subject to a configurable maximum queue time;
- MUST return `MODEL_NOT_READY` only if the model cannot be loaded within the configured timeout or if the queue is full.

The primary platform's retry policy for `MODEL_NOT_READY` MUST include exponential backoff and jitter. The Gateway MUST NOT rely  on the primary platform to poll for readiness—queuing and deferred  execution are the preferred failure-avoidance mechanisms.

------

# 8. GPU Gateway

The GPU Gateway is the execution-plane boundary.

It exists because the GPU cluster is an independent **trust, infrastructure, and execution boundary**, not merely because a remote endpoint is required.

The Gateway is responsible for:

- mTLS/service authentication;
- authorization assertion validation;
- request validation;
- admission;
- execution ID generation;
- dispatch;
- cancellation;
- execution status;
- observability;
- GPU usage reporting.

The Gateway MUST NOT become a second business-logic engine.

It MUST NOT:

- select enterprise business workflows;
- own tenant policy;
- own business state;
- perform primary-platform query planning;
- make independent model policy decisions.

------

# 9. Identity and Authorization

## 9.1 Authentication

The GPU Gateway authenticates the calling service using mTLS.

Transport identity answers:

> Who is calling?

The authenticated service identity is established independently from the request's `tenant_id`.

## 9.2 Tenant Context

A request may contain a tenant context assertion.

However:

> `tenant_id` is an authorization context assertion, not an authentication credential.

The GPU Gateway MUST validate that the asserted tenant is within the authenticated service principal's permitted scope.

The Gateway MUST NOT trust an arbitrary caller-supplied `tenant_id`.

The intended flow is:

```text
External caller
      │
      ▼
   synapt
      │
      │ authenticated tenant/service identity
      ▼
Primary platform
      │
      │ authorized execution request
      ▼
GPU Gateway
      │
      ├── authenticate service
      ├── validate tenant assertion
      ├── authorize operation/model
      └── execute
```

------

# 10. Execution Contract

The public contract is versioned under:

```text
synanton.gpu.v1
```

The contract uses gRPC.

The initial API is:

```protobuf
service GPUExecutionService {
    rpc Execute(ExecutionRequest) returns (ExecutionResponse);
    rpc Cancel(CancelRequest) returns (CancelResponse);
    rpc GetStatus(GetStatusRequest) returns (ExecutionStatus);
    rpc GetCapacity(GetCapacityRequest) returns (CapacityResponse);
}
```

## 10.1 Execution Request

The caller provides the originating request identity (`request_id`). This is mandatory and serves as the primary key for server-side idempotency.

The Gateway generates the GPU execution identity.

Conceptually:

```text
request_id (provided by primary)
    │
    ▼
GPU Gateway
    │
    └── execution_id generated here
```

The caller MUST NOT generate or control the GPU execution ID.

An execution ID uniquely identifies one GPU execution attempt.

## 10.2 Long-Running Execution Semantics

The `Execute()` RPC is intended to be long-lived and may block for the duration of GPU inference (which can exceed typical gRPC timeouts).

If the primary platform's configured deadline elapses before a final `ExecutionResponse` is received, the primary platform MUST NOT assume that execution has terminated. Instead:

- the primary platform MUST call `GetStatus(execution_id)` to reconcile the eventual outcome;
- the GPU Gateway MUST NOT terminate the underlying GPU operation solely because the initiating gRPC stream closed;
- the Gateway MUST persist execution state such that `GetStatus()` remains authoritative after the stream terminates.

This design acknowledges that network ambiguity is inherent in distributed systems and avoids false failure assumptions.

------

# 11. Execution Identity

Three identities are distinguished:

| Identity           | Owner            | Purpose                            |
| ------------------ | ---------------- | ---------------------------------- |
| `request_id`       | Primary Platform | Original request/workflow identity |
| `execution_id`     | GPU Gateway      | GPU execution attempt identity     |
| Runtime request ID | GPU runtime      | Internal runtime identity          |

The primary platform owns business/workflow lifecycle.

The GPU Execution Plane owns execution lifecycle.

GPU execution state MUST NOT become business state.

------

# 12. Execution Lifecycle

The GPU Execution Plane may represent states such as:

```text
QUEUED
  ↓
RUNNING
  ├── SUCCESS
  ├── FAILED
  ├── CANCELLED
  └── TIMEOUT
```

The primary platform may maintain a separate business lifecycle:

```text
business workflow
      │
      └── GPU execution attempt
              │
              ├── queued
              ├── running
              ├── completed
              └── failed
```

The two lifecycles are correlated through `request_id` and `execution_id`.

The boundary does not imply distributed transactional state. The primary platform uses `GetStatus()` to reconcile any ambiguous outcomes following a network interruption or timeout.

------

# 13. Cancellation

`Cancel()` is best-effort.

Cancellation MUST NOT imply rollback of business state.

If a GPU execution is cancelled:

- the GPU execution may terminate;
- the primary platform determines the resulting business/workflow state;
- no distributed transaction is initiated.

The Gateway MUST return a structured result indicating whether cancellation was accepted, completed, or could not be applied.

------

# 14. Advisory Capacity

`GetCapacity()` is an **advisory observability API**, not a reservation API.

It may expose information such as:

- available GPU classes;
- estimated capacity;
- queue depth;
- model availability;
- execution class;
- health.

A successful capacity response MUST NOT reserve GPU capacity.

Admission remains authoritative at execution time.

The primary platform MUST NOT implement correctness assumptions based on a previous `GetCapacity()` result.

------

# 15. Scheduling and Dispatch

There are three distinct layers:

### 15.1 Execution Planning — Synanton

Answers:

> What should happen?

The primary platform decides:

- operation;
- model;
- model version;
- policy;
- execution class;
- fallback/degraded behavior.

### 15.2 Request Scheduling — Optional Equalix

Answers:

> Which eligible request should happen next?

Equalix may provide:

- fairness;
- quotas;
- priorities;
- tenant-aware scheduling;
- GPU-class-aware admission;
- queue management.

Equalix is optional.

### 15.3 Infrastructure Scheduling — Kubernetes

Answers:

> Where should the workload run?

Kubernetes owns:

- pod placement;
- node selection;
- GPU allocation;
- replica scheduling;
- infrastructure lifecycle.

Synanton and Equalix MUST NOT schedule Kubernetes nodes directly.

------

# 16. Dispatch Strategy

The Gateway uses an execution dispatch abstraction:

```text
ExecutionDispatcher
        │
        ├── DirectDispatcher
        │
        └── EqualixScheduler
```

## 16.1 DirectDispatcher

The default implementation.

It delegates to the GPU runtime/service using normal Kubernetes service discovery and load balancing.

It does not implement global resource scheduling.

## 16.2 EqualixScheduler

Optional.

It schedules requests according to explicit scheduling policy before dispatching them to the runtime.

Equalix MUST schedule **requests**, not Kubernetes nodes or physical GPUs.

The initial implementation SHOULD use `DirectDispatcher`.

Equalix SHOULD be introduced only when measurable contention, fairness, quota, or priority requirements justify it.

------

# 17. Error Contract

The GPU Execution Plane returns structured errors.

Initial error categories include:

```text
UNAUTHORIZED
TENANT_NOT_ALLOWED
INVALID_REQUEST
MODEL_NOT_FOUND
MODEL_NOT_READY
MODEL_LOAD_TIMEOUT
GPU_UNAVAILABLE
GPU_CAPACITY_EXCEEDED
EXECUTION_TIMEOUT
EXECUTION_CANCELLED
EXECUTION_FAILED
```

Each error defines whether it is retryable.

The primary platform decides user-facing behavior and business-level fallback.

The GPU Gateway MUST NOT inject user-facing business messages into the primary platform's response model.

If an internal diagnostic `message` is present in `ErrorInfo`, it is diagnostic metadata only.

The primary platform owns user-facing error rendering.

------

# 18. Validation

The GPU API MUST follow existing Synanton gRPC validation conventions.

In particular:

- PGV is the canonical structural validation mechanism;
- standard Synanton validation interceptors are reused;
- validation rules are defined in the protobuf contract;
- GPU-specific validation MUST NOT introduce an independent validation framework.

Validation occurs at the GPU Gateway boundary before execution.

------

# 19. Idempotency and Delivery Semantics

The GPU Execution Plane does not provide exactly-once execution. However,  it MUST prevent duplicate execution when the primary platform retries a  request due to network timeouts, client timeouts, or process crashes.

The Gateway uses the primary platform's `request_id` as the idempotency key.

### 19.1 Idempotency Store

The Gateway maintains a durable store mapping:

```text
request_id → execution_id + serialized ExecutionResponse
```

When an `Execute()` request arrives:

1. The Gateway checks the store for the provided `request_id`.
2. If found, the Gateway returns the previously stored `ExecutionResponse` without re-executing.
3. If not found, the Gateway generates a new `execution_id`, executes the operation, stores the result, and returns the response.

### 19.2 Durability and Fail-Closed Behavior

The idempotency store MUST be backed by a durable database (e.g., PostgreSQL) with a unique constraint on `request_id`. Ephemeral caches such as Redis are insufficient as the sole source of  truth, as demonstrated by eviction and out-of-memory failure modes.

**Critical invariant:**

> The idempotency store must be **fail-closed**. If the store is unhealthy or unreachable, the Gateway MUST return a `5xx` error and block processing. It MUST NOT pass the request through without the idempotency check.

A degraded guard is worse than no guard. Passing a request without the  idempotency check allows duplicate execution during precisely the  failure scenario (network partition, store outage) where duplicates are  most likely.

### 19.3 Retention

The store retains entries for a configurable retention window aligned with  the primary platform's maximum retry horizon (e.g., 24 hours). After the retention window expires, the primary platform is responsible for  treating subsequent requests as new logical operations.

The architecture does not require distributed transactions or two-phase commit.

------

# 20. Observability

The GPU execution plane participates in the existing Synanton observability model.

Cross-cluster requests propagate trace context.

The GPU plane SHOULD expose low-cardinality execution attributes such as:

```text
operation
model
model_version
gpu_type
execution_class
```

Tenant identity MUST NOT be placed in ordinary trace attributes unless explicitly required by the existing observability policy.

Tenant-aware usage and billing dimensions belong in appropriate metrics/audit  mechanisms rather than unrestricted high-cardinality tracing.

The GPU Execution Plane exports telemetry to the configured observability infrastructure.

Cross-cluster telemetry uses explicitly configured infrastructure endpoints.  Application-level service discovery is not required for telemetry  export.

------

# 21. Cost and Usage

The GPU Execution Plane reports usage facts.

Examples include:

- execution duration;
- GPU class;
- model;
- model version;
- token/usage counters where available;
- execution outcome.

The primary platform owns:

- tenant attribution;
- business cost policy;
- budgeting;
- billing interpretation.

The GPU plane reports measurements; it does not define enterprise billing policy.

------

# 22. Degraded Mode

GPU execution is an optional execution capability.

The primary platform MUST be able to degrade gracefully when the GPU plane is:

- unavailable;
- unhealthy;
- overloaded;
- missing the requested model;
- unable to satisfy the requested GPU class.

Degraded behavior is orchestrated by the primary platform.

Possible outcomes include:

```text
GPU unavailable
      │
      ├── fallback to CPU implementation
      ├── return partial result
      ├── retry
      └── fail request
```

The GPU plane reports structured execution failure.

It does not decide the business-level fallback strategy.

------

# 23. Network and Trust Boundary

The CPU/GPU boundary is a trust boundary.

Required characteristics:

- private network connectivity;
- mTLS;
- authenticated service identity;
- authorization validation;
- explicit endpoint configuration;
- no direct pod/node access from the primary platform.

The primary platform MUST communicate only with the GPU Gateway.

The GPU Gateway MAY communicate with Kubernetes services and GPU runtimes inside the GPU execution plane.

------

# 24. Security Invariants

The following invariants are mandatory:

1. The primary platform never accesses GPU pods directly.
2. The primary platform never depends on Kubernetes GPU APIs.
3. The GPU plane never becomes the source of tenant policy.
4. `tenant_id` alone never authenticates a request.
5. The GPU Gateway authenticates the calling service.
6. Tenant assertions are authorized against authenticated identity.
7. GPU runtime credentials never cross into external API clients.
8. Model deployment follows approved model/version configuration.
9. GPU execution state is not treated as business state.
10. User-facing business errors are rendered by the primary platform.

------

# 25. Failure Model

The architecture assumes independent failure of the two planes.

### Primary platform unavailable

GPU execution may continue internally, but no new business requests are admitted through Synanton.

### GPU plane unavailable

Primary platform remains available.

GPU-dependent operations fail or degrade according to primary-platform policy.

### Network partition

The caller may not know whether execution was accepted.

The architecture therefore does not infer exactly-once semantics from  synchronous transport behavior. The primary platform is expected to  reconcile ambiguous executions via `GetStatus(execution_id)` using the `execution_id` returned in any partial or timed-out response.

### GPU runtime crash

The GPU plane reports failure and may recover/retry according to its execution policy.

The primary platform determines the resulting business state.

### Kubernetes scheduling failure

The GPU plane reports capacity/admission failure.

The primary platform may retry, fall back, or fail.

------

# 26. v1.19 → v1.20 Architectural Changes

The v1.19 architecture remains the baseline for all areas not explicitly changed here.

The following areas change:

| v1.19 area                       | v1.20 change                                                 |
| -------------------------------- | ------------------------------------------------------------ |
| Reranker Port                    | GPU implementation moves behind the GPU execution boundary   |
| LLM Client                       | GPU-backed model execution may use the remote GPU contract   |
| Embedding/model-serving adapters | GPU execution moves behind the GPU execution boundary        |
| ModelServingDirectory            | Resolves logical GPU execution endpoints, never physical instances |
| Gateway                          | Uses GPU execution client rather than direct GPU runtime access |
| Security                         | GPU Gateway becomes an independent authenticated service boundary |
| Observability                    | Trace context crosses CPU/GPU cluster boundary               |
| Cost model                       | GPU usage is reported by the execution plane                 |
| Deployment                       | GPU infrastructure moves to a separate repository/cluster    |
| Repository architecture          | `gpu-execution-plane` becomes independently deployable       |

No unrelated v1.19 architecture is changed by v1.20.

------

# 27. Repository and Documentation Changes

The v1.20 implementation requires:

### `synanton/platform`

Update:

- README, explicitly linking to `synanton/gpu-execution-plane` for production GPU runtime configuration and deployment;
- architecture documentation;
- module dependency diagrams;
- GPU adapter/client documentation;
- `ModelServingDirectory` documentation;
- observability documentation;
- security documentation;
- deployment documentation.

The README MUST no longer imply that the primary platform repository owns the production GPU runtime.

The existing Phase 2 GPU wording should be clarified so that local/demo GPU execution is distinguished from the production GPU Execution Plane.

### `synanton/gpu-execution-plane`

Create:

- GPU Gateway;
- `synanton.gpu.v1` protobuf contract;
- model-serving deployment definitions;
- GPU runtime configuration;
- Kubernetes manifests;
- observability configuration;
- GPU health/capacity reporting;
- execution lifecycle implementation;
- integration tests against the public contract.

------

# 28. Implementation Sequence

## Phase 1 — Contract

1. Define `synanton.gpu.v1`.
2. Define PGV validation rules.
3. Define structured errors.
4. Define execution identity.
5. Define authentication/authorization semantics.
6. Generate client/server bindings.
7. Implement consumer-driven contract tests (e.g., gRPC reflection or Pact) to  validate primary client compatibility against the GPU Gateway during  independent release cycles.

## Phase 2 — GPU Execution Plane

1. Create `synanton/gpu-execution-plane`.
2. Implement GPU Gateway.
3. Implement mTLS.
4. Implement authorization assertion validation.
5. Implement `DirectDispatcher`.
6. Implement model-serving integration.
7. Implement model readiness and cold-start queuing (Section 7.2).
8. Implement execution lifecycle.
9. Implement durable `request_id` idempotency store with fail-closed behavior.
10. Implement cancellation.
11. Implement status reporting.
12. Implement usage telemetry.

## Phase 3 — Primary Platform Integration

1. Implement GPU execution client.
2. Integrate with `ModelServingDirectory`.
3. Replace direct GPU adapter paths with the remote execution boundary.
4. Preserve primary-platform workflow ownership.
5. Add degraded-mode behavior.
6. Add cross-cluster tracing.
7. Implement client-side reconciliation (`GetStatus` polling) for timed-out or ambiguous `Execute()` responses.

## Phase 4 — Production Hardening

1. Security tests.
2. Failure-injection tests.
3. Network-partition tests.
4. Duplicate-request/idempotency tests.
5. GPU runtime failure tests.
6. Capacity/admission tests.
7. Observability dashboards.
8. Cost attribution validation.

## Phase 5 — Optional Scheduling

Only after production measurements demonstrate a requirement:

1. define Equalix scheduling policy;
2. implement `EqualixScheduler`;
3. introduce fairness/quota/priority behavior;
4. benchmark against `DirectDispatcher`;
5. retain `DirectDispatcher` as the minimal execution strategy.

------

# 29. Acceptance Criteria

v1.20 is architecturally complete when:

### Boundary

- GPU workloads run outside the primary platform cluster.
- Primary platform communicates only with GPU Gateway.
- Primary platform has no Kubernetes GPU dependency.

### Security

- GPU Gateway authenticates the calling service.
- `tenant_id` is never treated as authentication.
- Tenant authorization is validated against authenticated identity.
- mTLS is mandatory for production communication.

### Contract

- `synanton.gpu.v1` is versioned.
- PGV validation is implemented.
- Structured errors are implemented.
- Execution IDs are Gateway-owned.
- Capacity remains advisory.
- Consumer-driven contract tests exist and pass in CI.

### Execution

- `DirectDispatcher` works without Equalix.
- GPU model serving is independently deployable.
- Model cold-start requests are queued and deferred, not rejected into busy retry loops.
- Execution state is independently tracked.
- Cancellation is best-effort and does not imply business rollback.
- `request_id` idempotency store prevents duplicate execution on retry.
- Idempotency store is durable and fail-closed.

### Resilience

- GPU unavailability does not take down the primary platform.
- Primary platform can apply degraded-mode policy.
- Network ambiguity does not claim exactly-once execution.
- Primary platform reconciles ambiguous `Execute()` calls via `GetStatus()`.
- No distributed transaction is required.

### Observability

- Trace context crosses the CPU/GPU boundary.
- GPU execution metrics are available.
- GPU usage can be attributed to the primary-platform request identity.
- High-cardinality tenant data is not placed indiscriminately into traces.

### Documentation

- v1.20 becomes the authoritative architecture after implementation.
- README accurately describes the repository boundary and links to the GPU repository.
- GPU-specific architecture is documented in `synanton/gpu-execution-plane`.
- v1.19 remains the historical baseline for unchanged areas.

------

# 30. Architectural Decision

Synanton v1.20 adopts a **separate GPU Execution Plane**.

The primary platform owns **intent, identity, policy, planning, workflow, and business decisions**.

The GPU Execution Plane owns **GPU execution, runtime lifecycle, capacity, dispatch, and GPU telemetry**.

Kubernetes owns **infrastructure scheduling**.

Equalix is an **optional request scheduler** introduced only when operational evidence requires it.

The architecture deliberately avoids:

- GPU infrastructure leaking into the primary platform;
- direct pod discovery;
- business policy in the GPU Gateway;
- tenant identity based on untrusted request fields;
- distributed transactions;
- exactly-once claims;
- premature scheduling complexity;
- idempotency stores that are not durable or fail-closed;
- rejecting cold-model requests without queuing.

The resulting boundary is:

```text
                 WHAT SHOULD HAPPEN?
                         │
                         ▼
                SYNANTON PLATFORM
              intent / policy / plan
                         │
                         │
                         ▼
                 GPU EXECUTION API
                         │
                         ▼
              GPU EXECUTION PLANE
             execution / dispatch / GPU
                         │
                         ▼
                    KUBERNETES
                 infrastructure
```

> **Synanton decides what should run.
> The GPU Execution Plane decides how GPU work is executed.
> Kubernetes decides where it runs.**
