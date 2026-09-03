# Synanton Platform - Final Architecture Design (Merged Reference)

> **Document type:** Definitive engineering reference
> **Version:** 1.20
> **Date:** 2026-08-20
> **Status:** Folded into the lineage. **Not** the current pointer - see [`synanton-design-1.21.md`](synanton-design-1.21.md). This file remains the Part VIII (GPU Execution Plane) text.
> **Audience:** Architects, module owners, SREs, security engineers, partner connector authors, UI/frontend leads, DevOps/platform engineers
> **Philosophy:** Clean-slate · zero legacy · single API surface · no compatibility shims

This document is the **single authoritative engineering reference** for the Synanton platform for version 1.20. It builds on v1.19 as the complete baseline and introduces the GPU Execution Plane as a strictly isolated execution boundary. All content from v1.19 that is not explicitly modified here remains authoritative and unchanged; readers of unchanged areas should use this document's §1–§49 as the definitive reference (those sections are identical to v1.19 unless otherwise noted by an in-section v1.20 callout).

The GPU Execution Plane architecture is specified in full in the new Part VIII (§50–§64) of this document.

Source proposal: `docs/architecture/proposals/Synanton_v1.20_Proposal_GPU_Workload_Isolation.md` (2026-08-20).

---

## What's new in v1.20

Version 1.20 introduces a strict architectural boundary between the **primary Synanton platform** and a separate **GPU Execution Plane**. The changes are architecturally significant but non-breaking to the primary-platform public API surface: no public REST/gRPC contracts change, no modules are renamed, no Kafka/Cassandra/S3 schema migrations are introduced.

| # | Change | Home in v1.20 |
|---|--------|---------------|
| 1 | New `GPU Execution Plane` - physically isolated cluster responsible for GPU-specific execution (model serving, admission, dispatch, runtime lifecycle, GPU capacity, execution telemetry) | New §50–§64 (Part VIII) |
| 2 | New `synanton.gpu.v1` gRPC contract - `Execute`, `Cancel`, `GetStatus`, `GetCapacity` RPCs with PGV validation and structured error categories | §57 |
| 3 | New `GPU Gateway` - execution-plane boundary service with mTLS, authorization assertion validation, idempotency store, and dispatch strategy | §55 |
| 4 | `ModelServingDirectory` refined - resolves logical GPU execution endpoints only; MUST NOT resolve GPU pod IPs, Kubernetes pods, or vLLM instances | §54 |
| 5 | `gateway` extended - uses GPU execution client rather than direct GPU runtime access | §53 (v1.20 callout) |
| 6 | `security` extended - GPU Gateway becomes an independent authenticated service boundary; mTLS required between CPU and GPU clusters | §60 |
| 7 | Deployment split - GPU infrastructure moves to `synanton/gpu-execution-plane` repository; `synanton/platform` no longer owns the production GPU runtime | §53 |
| 8 | Observability - trace context crosses CPU/GPU cluster boundary; new low-cardinality GPU execution attributes | §62 |
| 9 | Cost model extended - GPU usage is reported by the execution plane; primary platform owns tenant attribution and billing policy | §63 |
| 10 | `DirectDispatcher` (default) and optional `EqualixScheduler` introduced as dispatch strategies inside the GPU plane | §59 |

### Compatibility statement (v1.20)

v1.20 introduces no breaking changes to the primary-platform public API surface. The following are new or modified surfaces:

- **New repository:** `synanton/gpu-execution-plane` - independently deployable; MUST NOT depend on `synanton/platform` internals.
- **New gRPC service:** `synanton.gpu.v1.GPUExecutionService` - exposed only to primary-platform GPU execution clients, not to external API consumers.
- **New primary-platform client:** GPU execution client inside `gateway` module - calls `synanton.gpu.v1` over mTLS.
- **Modified `ModelServingDirectory`:** resolves logical GPU execution endpoints; pod-level resolution removed (no schema migration required - this is a constraint tightening, not a schema change).
- **New config keys** (all with safe defaults): `gateway.gpu.*`, `gpu-gateway.*` (see §55, §57).
- **New metrics:** `gpu_execute_total`, `gpu_execute_duration_seconds`, `gpu_admission_rejected_total`, `gpu_model_not_ready_total`, `gpu_idempotency_hit_total` (see §62).
- **No Kafka schema changes.**
- **No Cassandra schema changes.**
- **No PostgreSQL schema changes in `synanton/platform`** (the idempotency store lives in the GPU plane's own PostgreSQL instance).

Rolling upgrade from v1.19 is safe: the GPU execution client is disabled by default until `gateway.gpu.enabled=true` is set; GPU-backed operations fall back to the existing v1.19 CPU path until the GPU plane is deployed and the flag is enabled.

---

## What's new in v1.19

*(Unchanged from v1.19. See `docs/archive/architecture/synanton-design-1.19.md` for the original v1.19 document.)*

Version 1.19 introduced `helper` and `wizard` operational CLI modules. See v1.19 for the full change log.

## What's new in v1.18, v1.17

*(Unchanged. See v1.19 document for cumulative history.)*

---

## Table of Contents

**Part I - Foundation**
1. Executive Summary *(v1.20 update: GPU Execution Plane added to system overview)*
2. Architectural Principles
3. Glossary *(v1.20 update: new terms added)*
4. System Topology *(v1.20 update: GPU cluster added)*
5. Module Map *(v1.20 update: GPU execution plane components added)*

**Part II - End-to-End Processing**
6. Ingestion Flow
7. Query Flow (Hybrid Search) *(v1.20 update: GPU synthesis path via execution contract)*
8. GraphRAG Flow
9. Tier Movement Flow
10. GDPR Erasure Cascade
11. ACL Propagation Flow
12. Cost Attribution & Forecast Flow *(v1.20 update: GPU usage reporting integrated)*
13. Predictive Auto-Scaling Loop
14. Anomaly Detection Loop
15. GitOps Reconciliation Loop

**Part III - Modules (Detailed)**
16. `synvault` - Content Store + Tier Manager
17. `synflux` - Ingestion Engine + Router
18. `ingestion-cache` - Cassandra/ScyllaDB Artifact Cache
19. `syntology` - Ontology Service
20. `synquest` - Hybrid Search Kernel
21. `relix` - GraphRAG Engine + MCP/ACP
22. `planner` - Search Planner
23. `gateway` - Query Gateway *(v1.20 update: GPU execution client integrated)*
24. `synapt` - Public API *(extended in v1.19: internal admin routes)*
25. `topology` - Authoritative Org/ACL/Policy Store
26. `security` - AuthN/Z + Outbound Broker *(v1.20 update: GPU service boundary)*
26a. API Key Lifecycle *(new in v1.17; extended in v1.19)*
26b. `helper` - Operational Day-2 CLI *(new in v1.19)*
26c. `wizard` - Deployment Setup Builder *(new in v1.19)*
27. `control-plane` - Admin, AI-Ops, Forecast, Anomaly, GitOps
27a. `synreview` - Human-in-the-Loop Review System
27b. `synanton-mcp` - MCP Protocol Bridge
27c. `synanton-llm-client` - Provider-Agnostic LLM Client

**Part IV - Contracts & SPIs**
28. Relix Graph Connector SPI v1.0
29. Content Adapter SPI
30. Reranker Port *(v1.20 note: GPU-backed reranker implementation moves behind execution boundary)*
31. Identity Provider Port + Outbound Auth Broker
32. ACL Propagation Port
33. Module Capability Descriptor
34. Long-Running Task Framework (`JobHandle`)

**Part V - Data Model**
35. PostgreSQL Schema (`topology`, audit, jobs, cost)
36. Cassandra/ScyllaDB Schema (`ingestion-cache`)
37. Kafka Topics & Compatibility Rules
38. Redis Keyspaces
39. Object Storage Layout (S3 / Glacier)

**Part VI - Cross-Cutting Concerns**
40. Identity, ACL, and Compile-Time Injection
41. Multi-Tenancy and Isolation Tiers
42. Schema Migration Discipline (N-2)
43. Cross-Region & Data Residency
44. Cost Awareness & Budget Caps
45. Observability - Metrics, Alerts, SLOs, Traces *(v1.20 update: GPU execution metrics)*
46. Deployment Profiles (Full, Standalone, Embedded) *(v1.20 update: GPU plane deployment)*
46a. Future UI Addenda

**Part VII - Operations & Plan**
47. Failure Modes & Runbooks *(v1.20 update: GPU plane failure modes)*
47a. Disaster Recovery - RTO/RPO & Cross-Region DR
48. Implementation Phases *(v1.20 update: GPU execution plane phases)*
48a. Testing Discipline
48b. UI Security Guidelines
49. Infrastructure Security Headers

**Appendices**
A. Capacity Planning Guide
B. Module Dependency Diagram *(v1.20 update: GPU execution plane added)*
C. Migration Process
D. `synanton-ops` Binary - Build & Distribution

**Part VIII - GPU Execution Plane** *(new in v1.20)*
50. GPU Execution Plane Overview
51. Goals and Non-Goals
52. Architectural Boundary
53. Physical Topology and Repository Split
54. Model Serving Abstraction
55. GPU Gateway
56. Identity and Authorization
57. Execution Contract (`synanton.gpu.v1`)
58. Execution Identity and Lifecycle
59. Scheduling and Dispatch
60. Network and Trust Boundary
61. Error Contract and Validation
62. Observability
63. Cost and Usage
64. Failure Model and Degraded Mode

---

# Parts I–VII

*Parts I through VII of this document are identical to Synanton Platform Architecture v1.19. Where a v1.20 change affects a specific section, an inline callout is placed at the beginning of that section. The full text of unchanged sections is not repeated here to avoid duplication; the v1.19 document remains the normative source for unchanged content until a full merged reprint is issued.*

*Inline callouts use the following convention:*

> **[v1.20]** - This section is modified in v1.20. The change description below supersedes or extends the v1.19 text at this point.

---

## §1 Executive Summary

> **[v1.20]** The executive summary below extends the v1.19 summary. All v1.19 content remains valid.

**Synanton** is a polyglot, high-performance, multi-tenant, federation-native enterprise knowledge platform. It unifies full-text retrieval, dense semantic retrieval, and knowledge-graph reasoning into a single open-source engine. *(See v1.19 §1 for the full summary.)*

**v1.20 addition:** Synanton now delegates GPU inference and embedding to a physically isolated **GPU Execution Plane** connected over a versioned gRPC contract (`synanton.gpu.v1`). The primary platform retains ownership of business intent, tenant identity, authorization policy, model selection, execution planning, workflow state, degraded-mode orchestration, and cost attribution. The GPU Execution Plane owns GPU-specific execution: model serving, GPU admission, request dispatch, runtime lifecycle, GPU capacity, and execution telemetry.

The central invariant:

> **Synanton decides what should run. The GPU Execution Plane decides how GPU work is executed. Kubernetes decides where the workload runs.**

---

## §3 Glossary

> **[v1.20]** The following terms are added to the v1.19 glossary.

| Term | Definition |
|------|------------|
| **GPU Execution Plane** | The physically isolated cluster responsible for GPU-specific execution, accessed only through the GPU Gateway. |
| **GPU Gateway** | The sole execution-plane boundary exposed to Synanton; handles authentication, authorization assertion validation, admission, dispatch, idempotency, and telemetry. |
| **GPU Execution Client** | The primary-platform component (inside `gateway`) that calls `synanton.gpu.v1` over mTLS. |
| **`synanton.gpu.v1`** | The versioned gRPC contract between the primary platform and the GPU Execution Plane. |
| **`request_id`** | The primary platform's originating request/workflow identity, used as the GPU plane idempotency key. |
| **`execution_id`** | The GPU Gateway-generated identity for a single GPU execution attempt. |
| **DirectDispatcher** | The default GPU dispatch strategy; routes to the Kubernetes service using standard load balancing. |
| **EqualixScheduler** | Optional fairness/quota/priority scheduler; introduced only when operational evidence requires it. |
| **`ModelServingDirectory`** | Primary-platform abstraction resolving logical model + version → logical execution endpoint (never physical GPU/pod identities). |
| **MODEL_NOT_READY** | GPU Gateway error indicating the requested model is approved but not currently loaded into GPU memory; triggers asynchronous model load and request queuing. |
| **Idempotency store** | Durable PostgreSQL store in the GPU plane mapping `request_id → execution_id + ExecutionResponse`; must be fail-closed. |

---

## §4 System Topology

> **[v1.20]** The physical topology is extended to include the GPU cluster. The v1.19 CPU cluster topology is unchanged.

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
                      │ synanton.gpu.v1
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

---

## §5 Module Map

> **[v1.20]** The following entries are added to the v1.19 module map.

| Module | Role | Repo | Status |
|--------|------|------|--------|
| `gpu-gateway` | GPU execution boundary: mTLS auth, admission, dispatch, idempotency, telemetry | `synanton/gpu-execution-plane` | v1.20 new |
| `gpu-execution-client` | Primary-platform gRPC client for `synanton.gpu.v1` (inside `gateway`) | `synanton/platform` | v1.20 new |
| `synanton.gpu.v1` protobuf | Versioned gRPC contract between CPU and GPU planes | `synanton/gpu-execution-plane` | v1.20 new |

---

## §23 `gateway` - Query Gateway

> **[v1.20]** The gateway module is extended to include the GPU execution client. The v1.19 gateway specification is unchanged for all non-GPU paths.

The gateway module acquires a new internal component: **GPU Execution Client**. When the execution plan produced by `planner` requires GPU synthesis, embedding, or reranking, the gateway dispatches the operation to the GPU Execution Plane via `synanton.gpu.v1.GPUExecutionService.Execute()` over mTLS, rather than calling a GPU runtime adapter directly.

**New gateway responsibilities:**
- Resolve logical model endpoint from `ModelServingDirectory`.
- Construct the `ExecutionRequest` (providing `request_id`; the GPU Gateway generates `execution_id`).
- Handle `MODEL_NOT_READY` with exponential backoff and jitter.
- On `Execute()` timeout or stream close, call `GetStatus(execution_id)` to reconcile the outcome.
- Apply degraded-mode policy when GPU execution is unavailable (fall back to CPU path, return partial result, or fail).
- Propagate trace context across the cluster boundary.

**Config keys added:**

| Key | Default | Description |
|-----|---------|-------------|
| `gateway.gpu.enabled` | `false` | Enables GPU execution client; falls back to CPU path when false |
| `gateway.gpu.endpoint` | - | GPU Gateway address (host:port) |
| `gateway.gpu.tls.cert-path` | - | Client mTLS certificate path |
| `gateway.gpu.tls.key-path` | - | Client mTLS key path |
| `gateway.gpu.tls.ca-path` | - | CA certificate for server validation |
| `gateway.gpu.timeout-ms` | `120000` | Execute() deadline; after expiry, GetStatus() is called |
| `gateway.gpu.retry.max-attempts` | `3` | Max retry attempts for MODEL_NOT_READY |
| `gateway.gpu.retry.backoff-base-ms` | `500` | Base backoff for MODEL_NOT_READY retries |

---

## §26 `security` - AuthN/Z + Outbound Broker

> **[v1.20]** The GPU Gateway becomes an independent authenticated service boundary. The v1.19 security specification is unchanged for all non-GPU paths.

The mTLS certificate pair for the GPU execution client is issued by the same CA infrastructure used for internal service certificates. The GPU Gateway is registered as a distinct service principal. Certificate rotation follows the existing rotation cadence defined in §26a.

---

## §45 Observability

> **[v1.20]** The following metrics are added to the v1.19 observability specification.

### GPU Execution Metrics

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `gpu_execute_total` | Counter | `model`, `model_version`, `outcome` | Total GPU execution attempts |
| `gpu_execute_duration_seconds` | Histogram | `model`, `gpu_type`, `execution_class` | GPU execution latency |
| `gpu_admission_rejected_total` | Counter | `model`, `reason` | Requests rejected at GPU admission |
| `gpu_model_not_ready_total` | Counter | `model` | MODEL_NOT_READY responses received |
| `gpu_model_load_duration_seconds` | Histogram | `model` | GPU model cold-start load time |
| `gpu_idempotency_hit_total` | Counter | `model` | Execute() calls served from idempotency store |
| `gpu_capacity_queue_depth` | Gauge | `model`, `gpu_type` | Current GPU request queue depth |
| `gpu_cancel_total` | Counter | `model`, `outcome` | Cancellation attempts and outcomes |

### Alerts Added

| Alert | Condition | Severity |
|-------|-----------|----------|
| `GpuExecutionErrorRate` | `gpu_execute_total{outcome="error"}` / total > 5% over 5m | warning |
| `GpuModelNotReadySpike` | `gpu_model_not_ready_total` rate > 10/s over 2m | warning |
| `GpuAdmissionRejectionHigh` | `gpu_admission_rejected_total` rate > 20/s | critical |
| `GpuIdempotencyStoreUnhealthy` | `gpu_idempotency_store_healthy == 0` | critical |

---

## §47 Failure Modes & Runbooks

> **[v1.20]** The following GPU-specific failure modes are added to the v1.19 runbook table.

| Failure | Symptom | Runbook |
|---------|---------|---------|
| GPU plane unavailable | `gateway` returns GPU degraded fallback; `gpu_execute_total{outcome="error"}` spikes | `docs/operations/runbooks/gpu-plane-unavailable.md` |
| GPU model not loaded | `MODEL_NOT_READY` responses; cold-start queue filling | `docs/operations/runbooks/gpu-model-cold-start.md` |
| Idempotency store unhealthy | `GpuIdempotencyStoreUnhealthy` alert; Gateway blocks all executions (fail-closed) | `docs/operations/runbooks/gpu-idempotency-store.md` |
| GPU admission capacity exceeded | `GPU_CAPACITY_EXCEEDED` errors; primary platform falls back to CPU path | `docs/operations/runbooks/gpu-capacity.md` |
| mTLS certificate expiry | GPU execution client fails to connect to GPU Gateway | `docs/operations/runbooks/gpu-mtls-cert.md` |
| Network partition CPU↔GPU | `Execute()` timeout; primary platform reconciles via `GetStatus()` | `docs/operations/runbooks/gpu-network-partition.md` |

---

## §48 Implementation Phases

> **[v1.20]** The GPU Execution Plane implementation track is added. See `docs/implementation/gpu-execution-plane/INDEX.md` for the detailed implementation plan.

| Phase | Theme | GPU plane work |
|-------|-------|----------------|
| GPU-1 | Contract | `synanton.gpu.v1` protobuf, PGV rules, error catalogue, consumer-driven contract tests |
| GPU-2 | GPU Execution Plane | `synanton/gpu-execution-plane` repo, GPU Gateway, mTLS, DirectDispatcher, model serving, idempotency store |
| GPU-3 | Primary Platform Integration | GPU execution client in `gateway`, `ModelServingDirectory` refinement, degraded mode, cross-cluster tracing |
| GPU-4 | Production Hardening | Security tests, failure injection, idempotency tests, observability dashboards, cost attribution validation |
| GPU-5 | Optional Scheduling | `EqualixScheduler` only after operational evidence of contention |

---

---

# Part VIII - GPU Execution Plane

*(new in v1.20)*

---

## §50 GPU Execution Plane Overview

Synanton v1.20 introduces a strict architectural boundary between the **primary Synanton platform** and a separate **GPU Execution Plane**.

The primary platform remains responsible for business intent, tenant identity, authorization policy, model selection, execution planning, workflow state, degraded-mode orchestration, and cost attribution.

The GPU Execution Plane is responsible for GPU-specific execution: model serving, GPU admission, request dispatch, runtime lifecycle, GPU capacity, and execution telemetry.

The two planes communicate through a narrow, versioned gRPC contract (`synanton.gpu.v1`).

The GPU Execution Plane is not another Synanton control plane. It is a remotely executable infrastructure capability behind a strict contract.

This separation keeps GPU-specific infrastructure out of the primary platform, allows GPU capacity to evolve independently, and prevents the primary platform from acquiring dependencies on Kubernetes, GPU drivers, model-serving runtimes, or GPU scheduling internals.

---

## §51 Goals and Non-Goals

### Goals

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

### Non-Goals

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

---

## §52 Architectural Boundary

### §52.1 Primary Synanton Platform

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

### §52.2 GPU Execution Plane

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

---

## §53 Physical Topology and Repository Split

The physical topology is defined in §4 of this document.

### Repository Boundary

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

The GPU repository may depend on the **versioned public execution contract** (`synanton.gpu.v1`), but MUST NOT depend on internal classes from `synanton/platform`.

### Documentation Alignment

The README for `synanton/platform` MUST:

- Explicitly link to `synanton/gpu-execution-plane` for production GPU runtime configuration and deployment.
- No longer imply that the primary platform repository owns the production GPU runtime.
- Distinguish clearly between local/demo GPU execution (Phase 2 vLLM containers) and the production GPU Execution Plane.

---

## §54 Model Serving Abstraction

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

### §54.1 Model Lifecycle Ownership

| Concern | Owner |
|---------|-------|
| Logical model identity | Primary Platform |
| Model/version approval | Primary Platform |
| Tenant/model policy | Primary Platform |
| Logical endpoint mapping | Primary Platform |
| Model deployment | GPU Execution Plane |
| Runtime configuration | GPU Execution Plane |
| Replica lifecycle | GPU Execution Plane |
| GPU placement | Kubernetes |
| Runtime health | GPU Execution Plane |

The GPU Execution Plane MUST NOT deploy a model/version that has not been approved and made available through the primary platform's logical model configuration.

### §54.2 Model Readiness and Cold Start

The GPU Gateway may return `MODEL_NOT_READY` if the requested model is approved but not currently loaded into GPU memory.

To prevent a thundering herd of retries from the primary platform, the Gateway:

- MUST queue the request and trigger an asynchronous model load if one is not already in progress;
- MAY serve queued requests once the model becomes ready, subject to a configurable maximum queue time;
- MUST return `MODEL_NOT_READY` only if the model cannot be loaded within the configured timeout or if the queue is full.

The primary platform's retry policy for `MODEL_NOT_READY` MUST include exponential backoff and jitter. The Gateway MUST NOT rely on the primary platform to poll for readiness - queuing and deferred execution are the preferred failure-avoidance mechanisms.

---

## §55 GPU Gateway

The GPU Gateway is the execution-plane boundary.

It exists because the GPU cluster is an independent **trust, infrastructure, and execution boundary**, not merely because a remote endpoint is required.

The Gateway is responsible for:

- mTLS/service authentication;
- authorization assertion validation;
- request validation (PGV);
- admission;
- execution ID generation;
- dispatch;
- cancellation;
- execution status (`GetStatus`);
- observability;
- GPU usage reporting;
- durable idempotency store management.

The Gateway MUST NOT become a second business-logic engine. It MUST NOT:

- select enterprise business workflows;
- own tenant policy;
- own business state;
- perform primary-platform query planning;
- make independent model policy decisions.

### §55.1 Idempotency Store

The Gateway maintains a durable store mapping:

```
request_id → execution_id + serialized ExecutionResponse
```

When an `Execute()` request arrives:

1. The Gateway checks the store for the provided `request_id`.
2. If found, the Gateway returns the previously stored `ExecutionResponse` without re-executing.
3. If not found, the Gateway generates a new `execution_id`, executes the operation, stores the result, and returns the response.

The idempotency store MUST be backed by a durable database (PostgreSQL) with a unique constraint on `request_id`. Ephemeral caches such as Redis are insufficient as the sole source of truth.

**Critical invariant:**

> The idempotency store must be **fail-closed**. If the store is unhealthy or unreachable, the Gateway MUST return a `5xx` error and block processing. It MUST NOT pass the request through without the idempotency check.

The store retains entries for a configurable retention window aligned with the primary platform's maximum retry horizon (e.g., 24 hours).

---

## §56 Identity and Authorization

### §56.1 Authentication

The GPU Gateway authenticates the calling service using mTLS.

Transport identity answers: *Who is calling?*

The authenticated service identity is established independently from the request's `tenant_id`.

### §56.2 Tenant Context

A request may contain a tenant context assertion.

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
      ├── authenticate service (mTLS)
      ├── validate tenant assertion
      ├── authorize operation/model
      └── execute
```

---

## §57 Execution Contract (`synanton.gpu.v1`)

The public contract is versioned under `synanton.gpu.v1`.

The contract uses gRPC with PGV validation.

```protobuf
service GPUExecutionService {
    rpc Execute(ExecutionRequest) returns (ExecutionResponse);
    rpc Cancel(CancelRequest) returns (CancelResponse);
    rpc GetStatus(GetStatusRequest) returns (ExecutionStatus);
    rpc GetCapacity(GetCapacityRequest) returns (CapacityResponse);
}
```

### §57.1 Execution Request Fields

| Field | Required | Owner | Description |
|-------|----------|-------|-------------|
| `request_id` | Yes | Primary Platform | Originating request identity; idempotency key |
| `tenant_id` | Yes | Primary Platform | Tenant context assertion (validated by Gateway) |
| `model` | Yes | Primary Platform | Logical model name |
| `model_version` | Yes | Primary Platform | Approved model version |
| `operation` | Yes | Primary Platform | `SYNTHESIZE` \| `EMBED` \| `RERANK` |
| `execution_class` | No | Primary Platform | Execution priority/class hint |
| `payload` | Yes | Primary Platform | Operation-specific input |
| `trace_context` | No | Primary Platform | OpenTelemetry trace context |

The caller MUST NOT generate or control the GPU execution ID. The Gateway generates `execution_id` and returns it in `ExecutionResponse`.

### §57.2 Long-Running Execution Semantics

The `Execute()` RPC is intended to be long-lived and may block for the duration of GPU inference (which can exceed typical gRPC timeouts).

If the primary platform's configured deadline elapses before a final `ExecutionResponse` is received:

- the primary platform MUST call `GetStatus(execution_id)` to reconcile the eventual outcome;
- the GPU Gateway MUST NOT terminate the underlying GPU operation solely because the initiating gRPC stream closed;
- the Gateway MUST persist execution state such that `GetStatus()` remains authoritative after the stream terminates.

### §57.3 Advisory Capacity

`GetCapacity()` is an **advisory observability API**, not a reservation API.

- A successful capacity response MUST NOT reserve GPU capacity.
- Admission remains authoritative at execution time.
- The primary platform MUST NOT implement correctness assumptions based on a previous `GetCapacity()` result.

---

## §58 Execution Identity and Lifecycle

### Identity

Three identities are distinguished:

| Identity | Owner | Purpose |
|----------|-------|---------|
| `request_id` | Primary Platform | Original request/workflow identity |
| `execution_id` | GPU Gateway | GPU execution attempt identity |
| Runtime request ID | GPU runtime | Internal runtime identity |

GPU execution state MUST NOT become business state.

### Execution Lifecycle

The GPU Execution Plane may represent states:

```text
QUEUED
  ↓
RUNNING
  ├── SUCCESS
  ├── FAILED
  ├── CANCELLED
  └── TIMEOUT
```

The primary platform may maintain a separate business lifecycle correlated through `request_id` and `execution_id`. The boundary does not imply distributed transactional state. The primary platform uses `GetStatus()` to reconcile any ambiguous outcomes following a network interruption or timeout.

### Cancellation

`Cancel()` is best-effort.

- Cancellation MUST NOT imply rollback of business state.
- If a GPU execution is cancelled, the primary platform determines the resulting business/workflow state.
- No distributed transaction is initiated.
- The Gateway MUST return a structured result indicating whether cancellation was accepted, completed, or could not be applied.

---

## §59 Scheduling and Dispatch

There are three distinct layers:

### §59.1 Execution Planning - Synanton

*What should happen?*

The primary platform decides: operation, model, model version, policy, execution class, fallback/degraded behavior.

### §59.2 Request Scheduling - Optional Equalix

*Which eligible request should happen next?*

Equalix may provide: fairness, quotas, priorities, tenant-aware scheduling, GPU-class-aware admission, queue management.

Equalix is optional. The initial implementation SHOULD use `DirectDispatcher`. Equalix SHOULD be introduced only when measurable contention, fairness, quota, or priority requirements justify it.

### §59.3 Infrastructure Scheduling - Kubernetes

*Where should the workload run?*

Kubernetes owns: pod placement, node selection, GPU allocation, replica scheduling, infrastructure lifecycle.

Synanton and Equalix MUST NOT schedule Kubernetes nodes directly.

### Dispatch Strategy

```text
ExecutionDispatcher
        │
        ├── DirectDispatcher (default)
        │     Delegates to Kubernetes Service; no global scheduling
        │
        └── EqualixScheduler (optional)
              Schedules requests by policy before dispatching
```

---

## §60 Network and Trust Boundary

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

### Security Invariants

The following invariants are mandatory:

1. The primary platform never accesses GPU pods directly.
2. The primary platform never depends on Kubernetes GPU APIs.
3. The GPU plane never becomes the source of tenant policy.
4. `tenant_id` alone never authenticates a request.
5. The GPU Gateway authenticates the calling service via mTLS.
6. Tenant assertions are authorized against authenticated identity.
7. GPU runtime credentials never cross into external API clients.
8. Model deployment follows approved model/version configuration.
9. GPU execution state is not treated as business state.
10. User-facing business errors are rendered by the primary platform.

---

## §61 Error Contract and Validation

### Error Categories

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

Each error defines whether it is retryable. The primary platform decides user-facing behavior and business-level fallback. The GPU Gateway MUST NOT inject user-facing business messages into the primary platform's response model.

### Validation

The GPU API MUST follow existing Synanton gRPC validation conventions:

- PGV is the canonical structural validation mechanism;
- standard Synanton validation interceptors are reused;
- validation rules are defined in the protobuf contract;
- GPU-specific validation MUST NOT introduce an independent validation framework.

---

## §62 Observability

The GPU execution plane participates in the existing Synanton observability model. Cross-cluster requests propagate trace context.

The GPU plane SHOULD expose low-cardinality execution attributes:

```text
operation
model
model_version
gpu_type
execution_class
```

Tenant identity MUST NOT be placed in ordinary trace attributes unless explicitly required by the existing observability policy. Tenant-aware usage and billing dimensions belong in appropriate metrics/audit mechanisms rather than unrestricted high-cardinality tracing.

Cross-cluster telemetry uses explicitly configured infrastructure endpoints. Application-level service discovery is not required for telemetry export.

Metrics catalogue: see §45 of this document.

---

## §63 Cost and Usage

The GPU Execution Plane reports usage facts:

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

---

## §64 Failure Model and Degraded Mode

The architecture assumes independent failure of the two planes.

### Failure Scenarios

| Scenario | Behavior |
|----------|----------|
| **Primary platform unavailable** | GPU execution may continue internally, but no new business requests are admitted through Synanton. |
| **GPU plane unavailable** | Primary platform remains available. GPU-dependent operations fail or degrade according to primary-platform policy. |
| **Network partition** | Caller may not know whether execution was accepted. Primary platform reconciles ambiguous executions via `GetStatus(execution_id)`. No exactly-once semantics inferred from synchronous transport. |
| **GPU runtime crash** | GPU plane reports failure and may recover/retry per its execution policy. Primary platform determines resulting business state. |
| **Kubernetes scheduling failure** | GPU plane reports capacity/admission failure. Primary platform may retry, fall back, or fail. |
| **Idempotency store unavailable** | Gateway returns `5xx` and blocks all executions (fail-closed). Primary platform receives error; applies degraded-mode policy. |

### Degraded Mode

GPU execution is an optional execution capability. The primary platform MUST be able to degrade gracefully when the GPU plane is unavailable, unhealthy, overloaded, missing the requested model, or unable to satisfy the requested GPU class.

```text
GPU unavailable
      │
      ├── fallback to CPU implementation
      ├── return partial result
      ├── retry
      └── fail request
```

The GPU plane reports structured execution failure. It does not decide the business-level fallback strategy.

---

## Appendix B (v1.20 update) - Module Dependency Diagram

```text
synanton/platform
    │
    ├── gateway
    │     └── GPU Execution Client ──────────────────────┐
    │                                                     │
    └── ModelServingDirectory                             │ gRPC + mTLS
                                                          │ synanton.gpu.v1
                                                          ▼
                                              synanton/gpu-execution-plane
                                                    │
                                                    └── GPU Gateway
                                                          ├── DirectDispatcher
                                                          ├── EqualixScheduler (optional)
                                                          ├── Idempotency Store (PostgreSQL)
                                                          └── Kubernetes Service → vLLM → GPU nodes
```

---

*End of Synanton Platform Architecture v1.20*
