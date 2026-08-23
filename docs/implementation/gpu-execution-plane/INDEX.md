---
title: "GPU Execution Plane — Implementation Plan"
status: "planned"
last_reviewed: "2026-08-20"
---

# GPU Execution Plane — Implementation Plan

**Purpose:** Implementation plan for the v1.20 GPU Execution Plane architectural change. Introduces a physically isolated GPU cluster connected to the primary Synanton platform through the versioned `synanton.gpu.v1` gRPC contract.
**Architecture reference:** `docs/architecture/synanton-design-1.20.md` §50–§64 (Part VIII) — still the authoritative text for the GPU Execution Plane. The current design entry point is `synanton-design-1.21.md`, which restates Part VIII as unchanged; v1.21 adds no GPU-plane requirements.
**Proposal source:** `docs/architecture/proposals/Synanton_v1.20_Proposal_GPU_Workload_Isolation.md`
**Audience:** Architects, module owners, GPU infrastructure engineers, SREs
**Last Updated:** 2026-08-20

---

## Theme

> Isolate GPU workloads from the primary Synanton cluster behind a strict, versioned gRPC contract. The primary platform decides what should run. The GPU Execution Plane decides how GPU work is executed. Kubernetes decides where it runs.

---

## User-Facing Capability Unlocked

- GPU synthesis, embedding, and reranking run in a physically separate cluster without affecting the primary platform's availability.
- Primary platform degrades gracefully when the GPU plane is unavailable (CPU fallback, partial results, or structured failure — all configured per tenant).
- GPU infrastructure (model serving, Kubernetes, vLLM) can be deployed, scaled, and upgraded independently of the primary platform release cycle.
- Duplicate GPU execution is prevented on retry via a durable, fail-closed idempotency store.
- GPU model cold starts queue requests instead of returning thundering-herd rejections.

---

## Phased Delivery

The GPU execution plane is delivered in five phases. Phases GPU-1 through GPU-3 are the critical path to production. GPU-4 is required before serving real tenant traffic. GPU-5 is conditional on operational evidence.

| Phase | Name | Owner | Status |
|-------|------|-------|--------|
| GPU-1 | Contract | Platform team | ✅ Done |
| GPU-2 | GPU Execution Plane | GPU infra team | ✅ Done |
| GPU-3 | Primary Platform Integration | Platform team | ✅ Done |
| GPU-4 | Production Hardening | Platform + GPU infra teams | Planned |
| GPU-5 | Optional Scheduling (Equalix) | GPU infra team | Conditional |

---

## Phase GPU-1 — Contract

**Goal:** Define and validate the complete `synanton.gpu.v1` gRPC contract before any server implementation starts. Consumer-driven contract tests must exist so that primary-platform client compatibility can be verified independently of the GPU plane during release.

### Deliverables

| # | Deliverable | Repo | Notes |
|---|-------------|------|-------|
| 1 | `synanton.gpu.v1` protobuf definitions (`Execute`, `Cancel`, `GetStatus`, `GetCapacity`) | `synanton/gpu-execution-plane` | Initial version; MUST be backward-compatible going forward |
| 2 | PGV (protoc-gen-validate) rules for all request messages | `synanton/gpu-execution-plane` | Follows §61 validation conventions |
| 3 | Structured error catalogue (`ErrorInfo` with `reason` codes) | `synanton/gpu-execution-plane` | All 11 error categories from §61 |
| 4 | Execution identity semantics documented and enforced in proto | `synanton/gpu-execution-plane` | `request_id` mandatory; `execution_id` Gateway-generated |
| 5 | Authentication/authorization field semantics (`tenant_id` as assertion, not credential) | `synanton/gpu-execution-plane` | Validated in Gateway, not in proto |
| 6 | Generated gRPC client/server bindings (Java) | Both repos | Published as a versioned artifact |
| 7 | Consumer-driven contract tests | `synanton/platform` | Primary-platform client tests against a mock GPU Gateway |
| 8 | Contract CI pipeline | `synanton/gpu-execution-plane` | Fails if proto changes break generated bindings |

### Definition of Done — GPU-1

1. `buf build` passes with zero errors on `synanton.gpu.v1`.
2. PGV rules reject: missing `request_id`, unknown `operation`, `model` exceeding length limits, `tenant_id` patterns that violate the format spec.
3. All 11 error `reason` codes are present in the proto `ErrorInfo` message and documented in a code catalogue.
4. Consumer-driven contract tests in `synanton/platform` compile and pass against the mock GPU Gateway.
5. Contract CI pipeline is green and blocks merges that break the generated bindings.

---

## Phase GPU-2 — GPU Execution Plane

**Goal:** Stand up `synanton/gpu-execution-plane` as an independently deployable repository containing the GPU Gateway, model serving integration, execution lifecycle, and durable idempotency store.

### Deliverables

| # | Deliverable | Notes |
|---|-------------|-------|
| 1 | `synanton/gpu-execution-plane` repository created | Independent CI/CD, Kubernetes manifests, own Dockerfile |
| 2 | GPU Gateway service implementing `GPUExecutionService` | All four RPCs: `Execute`, `Cancel`, `GetStatus`, `GetCapacity` |
| 3 | mTLS configuration and certificate loading | Uses same CA as primary platform internal services |
| 4 | Authorization assertion validation middleware | Validates `tenant_id` against authenticated mTLS service principal scope |
| 5 | `DirectDispatcher` implementation | Default dispatch; delegates to Kubernetes Service; no Equalix dependency |
| 6 | Model-serving integration (vLLM) | Gateway dispatches to vLLM pods via Kubernetes Service |
| 7 | Model readiness and cold-start queuing (§54.2) | `MODEL_NOT_READY` triggers async load + request queue; no thundering-herd |
| 8 | Execution lifecycle tracking (`QUEUED → RUNNING → SUCCESS/FAILED/CANCELLED/TIMEOUT`) | In-memory + durable state |
| 9 | Durable idempotency store (PostgreSQL) | `request_id → execution_id + response`; unique constraint on `request_id`; fail-closed |
| 10 | Cancellation (`Cancel()` best-effort) | Returns structured result: accepted / completed / not-applicable |
| 11 | `GetStatus()` authoritative after stream termination | State persisted before responding; survives GPU Gateway restart |
| 12 | Usage telemetry emission | Duration, GPU class, model, token counters, outcome |
| 13 | Kubernetes manifests for GPU Gateway deployment | Namespace, service, deployment, HPA, PodDisruptionBudget |
| 14 | Integration tests against the public contract | Full round-trip: primary-platform client → GPU Gateway → model serving |

### Definition of Done — GPU-2

1. GPU Gateway starts, serves mTLS, and rejects unauthenticated connections.
2. `Execute()` with a valid `request_id` returns a successful `ExecutionResponse` containing `execution_id`.
3. Duplicate `Execute()` with the same `request_id` returns the stored `ExecutionResponse` without re-executing (idempotency store hit confirmed by `gpu_idempotency_hit_total` metric).
4. Sending `Execute()` and immediately closing the gRPC stream; subsequent `GetStatus(execution_id)` returns the completed result — the underlying GPU operation was not cancelled.
5. `MODEL_NOT_READY` is returned when a valid-but-not-loaded model is requested; a second `Execute()` with the same `request_id` within the queue window returns the result after the model loads (no duplicate execution).
6. Idempotency store made unhealthy → Gateway returns `5xx` on `Execute()` (fail-closed); restore → Gateway resumes normal operation.
7. `Cancel()` returns `ACCEPTED` for a running execution; `NOT_APPLICABLE` for a completed execution.
8. All integration tests pass in CI.
9. `buf lint` and `buf breaking` pass on all proto changes against the GPU-1 baseline.

---

## Phase GPU-3 — Primary Platform Integration

**Goal:** Connect the primary platform (`gateway`) to the GPU Execution Plane via the `synanton.gpu.v1` client. Replace direct GPU adapter paths with the remote execution boundary. Preserve all existing business-layer behaviour.

### Deliverables

| # | Deliverable | Module | Notes |
|---|-------------|--------|-------|
| 1 | GPU execution client implementation | `gateway` | Wraps `synanton.gpu.v1` gRPC stub; mTLS configured |
| 2 | `ModelServingDirectory` constraint enforcement | `control-plane` | MUST NOT resolve pod IPs, Kubernetes pods, or vLLM instances; resolves logical endpoint only |
| 3 | GPU-backed execution plan dispatch | `gateway` | Planner-produced plans route GPU steps to GPU execution client |
| 4 | Degraded-mode orchestration | `gateway` | CPU fallback / partial result / retry / fail — per-tenant policy |
| 5 | `MODEL_NOT_READY` retry with exponential backoff + jitter | `gateway` | Max attempts and backoff base configurable (see §23 config table) |
| 6 | `Execute()` timeout → `GetStatus()` reconciliation | `gateway` | After deadline, calls `GetStatus(execution_id)` to resolve outcome |
| 7 | Cross-cluster trace context propagation | `gateway` | OpenTelemetry `traceparent` header forwarded in `ExecutionRequest.trace_context` |
| 8 | `gateway.gpu.enabled` feature flag (default `false`) | `gateway` | Enables GPU client; falls back to v1.19 CPU path when false |
| 9 | Config keys from §23 wired to application config | `gateway` | Endpoint, TLS paths, timeouts, retry params |
| 10 | Metrics wired (see §45) | `gateway` | `gpu_execute_total`, `gpu_execute_duration_seconds`, etc. |
| 11 | Alerts wired (see §45) | Observability | `GpuExecutionErrorRate`, `GpuModelNotReadySpike`, `GpuAdmissionRejectionHigh`, `GpuIdempotencyStoreUnhealthy` |

### Definition of Done — GPU-3

1. `gateway.gpu.enabled=false` (default) → full v1.19 query path works unchanged; no GPU dependency in startup.
2. `gateway.gpu.enabled=true` → `POST /search` with a GPU-backed model returns `QueryResponse.answer` synthesised by the GPU plane.
3. GPU plane taken offline → gateway applies degraded-mode fallback; primary platform health endpoint remains healthy; no cascading failure.
4. `Execute()` timeout simulation → `GetStatus(execution_id)` is called; result is correctly reconciled; no duplicate execution in idempotency store.
5. `MODEL_NOT_READY` response → gateway retries with exponential backoff; `gpu_model_not_ready_total` counter increments; eventually succeeds or applies degraded mode.
6. `gpu_execute_total`, `gpu_execute_duration_seconds` metrics visible in Prometheus after a successful execution.
7. `GpuExecutionErrorRate` alert fires under a 10% error injection test; clears when injection stops.
8. Trace context visible in GPU Gateway logs for a request initiated from the primary platform (correlation by `request_id` and `trace_id`).
9. Consumer-driven contract tests from GPU-1 still pass against the live GPU Gateway.

---

## Phase GPU-4 — Production Hardening

**Goal:** Validate security, resilience, observability, and cost attribution at production scale before serving real tenant traffic.

### Deliverables

| # | Deliverable | Owner |
|---|-------------|-------|
| 1 | Security tests — mTLS rejection of self-signed certs, `tenant_id` injection attempts, unauthorized service principals | Security team |
| 2 | Failure injection tests — GPU plane crash, idempotency store unavailability, network partition, model crash | Platform + GPU infra |
| 3 | Network partition tests — `Execute()` sent, partition induced, `GetStatus()` reconciliation verified | Platform team |
| 4 | Duplicate-request / idempotency tests — concurrent identical `request_id` submissions; exactly one execution confirmed | GPU infra team |
| 5 | GPU runtime failure tests — vLLM pod crash mid-inference; Gateway reports failure; primary platform applies degraded mode | GPU infra team |
| 6 | Capacity / admission tests — `GPU_CAPACITY_EXCEEDED` under sustained load; primary falls back | GPU infra team |
| 7 | Observability dashboards — GPU execution latency, queue depth, model readiness, idempotency hit rate, error rates | SRE |
| 8 | Cost attribution validation — GPU usage events correlated to primary-platform `request_id`; tenant attribution verified | Platform team |
| 9 | Load test — sustained p50/p95/p99 GPU execution latency within SLO targets | Performance team |
| 10 | Runbook authoring — all runbooks listed in §47 of `synanton-design-1.20.md` authored and reviewed | SRE |

### Definition of Done — GPU-4

1. Security test suite passes; zero bypasses of mTLS authentication or tenant assertion validation.
2. `tenant_id` injection (caller-supplied tenant not in authenticated principal's scope) rejected at Gateway with `TENANT_NOT_ALLOWED`.
3. Idempotency store made permanently unavailable → Gateway returns `5xx` on all `Execute()` calls (fail-closed); no executions proceed without the idempotency check.
4. Under a 30-minute partition between CPU and GPU clusters → zero duplicate executions in the idempotency store after partition heals.
5. All six runbooks exist, are reviewed, and have a linked alert.
6. GPU execution latency p99 < 30 s under steady-state load (model warm, no cold start).
7. Cost attribution: 100% of completed GPU executions have a corresponding usage record attributable to the originating `request_id`.
8. Load test: primary platform error rate < 0.5% during sustained GPU load at 2× expected peak.

---

## Phase GPU-5 — Optional Scheduling (Equalix)

**Trigger:** Phase GPU-5 is ONLY initiated after Phase GPU-4 operational data demonstrates measurable contention, fairness violations, or priority starvation that `DirectDispatcher` cannot address.

**Goal:** Introduce `EqualixScheduler` as an optional dispatch strategy inside the GPU Gateway. `DirectDispatcher` is retained as the minimal fallback.

### Deliverables

| # | Deliverable | Notes |
|---|-------------|-------|
| 1 | Operational evidence report | Documents contention metrics that justify Equalix |
| 2 | Equalix scheduling policy definition | Fairness, quotas, priorities, tenant-aware admission, GPU-class-aware scheduling |
| 3 | `EqualixScheduler` implementation | Schedules **requests**, not Kubernetes nodes or physical GPUs |
| 4 | `DirectDispatcher` vs `EqualixScheduler` benchmark | Latency, throughput, fairness metrics under mixed-tenant load |
| 5 | `EqualixScheduler` enabled by configuration flag | Default remains `DirectDispatcher` |
| 6 | Integration tests with Equalix enabled | Full round-trip; fairness guarantees validated |

### Definition of Done — GPU-5

1. Operational evidence report demonstrates a measurable improvement in fairness metric (e.g., Jain's fairness index) with Equalix vs DirectDispatcher under mixed-tenant load.
2. A low-priority tenant cannot starve a high-priority tenant for more than `equalix.starvation.max-wait-ms` (configurable, default 10 s).
3. Quota enforcement: a tenant exceeding its GPU quota is admitted at reduced rate; `gpu_admission_rejected_total{reason="QUOTA_EXCEEDED"}` is non-zero.
4. `DirectDispatcher` benchmark as baseline: no regression in latency or throughput when `EqualixScheduler` is disabled.
5. Single-tenant load: `EqualixScheduler` introduces ≤ 5 ms p99 overhead vs `DirectDispatcher`.

---

## External Dependencies

| Dependency | Purpose | Phase |
|------------|---------|-------|
| **protoc-gen-validate (PGV)** | gRPC request validation | GPU-1 |
| **buf CLI** | Protobuf lint, breaking-change detection, build | GPU-1 |
| **PostgreSQL** (GPU plane) | Idempotency store (separate from primary platform DB) | GPU-2 |
| **vLLM** | Model serving runtime in GPU cluster | GPU-2 |
| **Kubernetes** (GPU cluster) | Pod scheduling, service discovery, GPU allocation | GPU-2 |
| **NVIDIA Container Toolkit** | GPU node runtime | GPU-2 |
| **OpenTelemetry SDK** | Cross-cluster trace context propagation | GPU-2, GPU-3 |
| **Prometheus** | GPU execution metrics | GPU-3 |
| **Equalix** | Optional request scheduler | GPU-5 only |

---

## Cross-Plan Dependencies

### GPU-2 depends on GPU-1

- GPU-2 implementation requires the finalised `synanton.gpu.v1` proto and generated bindings.
- Changes to the proto after GPU-2 begins must go through the breaking-change detection pipeline.

### GPU-3 depends on GPU-1 (client bindings) and GPU-2 (live Gateway for integration tests)

- GPU-3 can proceed in parallel with GPU-2 using the mock Gateway from GPU-1 consumer contract tests.
- Full integration (DoD items 2–8) requires a running GPU-2 deployment.

### GPU-4 depends on GPU-3

- GPU-4 hardening tests require the full integrated stack (primary platform + GPU plane).

### GPU-5 depends on GPU-4

- No Equalix work begins without the Phase GPU-4 operational evidence report.

---

## Build Order (Critical Path)

```
GPU-1 (contract)
    │
    ├──► GPU-2 (GPU plane) ──────────────────────────┐
    │                                                  │
    └──► GPU-3 client stub (mock-based DoD items 1,8) │
                                                       │
    ┌──────────────────────────────────────────────────┘
    │
    └──► GPU-3 full integration (DoD items 2–7)
              │
              └──► GPU-4 (hardening)
                        │
                        └──► GPU-5 (optional, data-gated)
```

---

## How to Contribute

Plan files in this directory follow the naming convention `NN-{component}.md` for per-component detailed plans (created when a phase begins implementation).

When authoring or updating a plan:

1. Update this INDEX and `docs/implementation/synanton-phases-plan.md` GPU section.
2. Every plan file MUST cite `docs/architecture/synanton-design-1.20.md` §50–§64 as the authoritative architecture source for the GPU Execution Plane and name the specific section(s) it implements. Cite v1.20 rather than the current entry point (`synanton-design-1.21.md`) because v1.20 holds the Part VIII text; cite the current entry point only for sections it actually restates.
3. When a plan introduces a new metric or alert, add it to `docs/implementation/phase4/15-observability.md` §3 Alert Catalogue.
4. When a plan introduces a new config key, prefix it with the module name (`gateway.gpu.*`, `gpu-gateway.*`).
5. Every plan file MUST have a numbered, testable Definition of Done that maps back to this INDEX.
6. All proto changes must pass `buf breaking` against the GPU-1 baseline before merging.
