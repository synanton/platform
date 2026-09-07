# Synanton Design 1.33 — Kubernetes Lifecycle, Compatibility and Multi-Operator Architecture

> **Document type:** Architecture design document
> **Version:** 1.33
> **Document ID:** `synanton-design-1.33`
> **Date:** 2026-09-06
> **Status:** Approved (architecture) — implementation not started (no operator implementation exists; this document defines the contract/readiness review only)
> **Extends:** [synanton-design-1.25.md](./synanton-design-1.25.md) — Annotation, Derived Knowledge, Recalculation, Analytics and Reporting Plane
> **Normative security baseline:** [synanton-design-1.23.md](./synanton-design-1.23.md) — Classification-Aware Semantic Search (see §17)
> **Related Designs:**
> - [synanton-design-1.20.md](./archive/synanton-design-1.20.md) — GPU Execution Plane (folded into the Design 1.22 baseline and archived, per `docs/architecture/INDEX.md`); this document's GPU Runtime Operator (§10) manages lifecycle for the physically isolated plane 1.20 defines, without altering its isolation invariants
> - [synanton-design-1.26.md](./synanton-design-1.26.md) — Content Cache / Structured Content Extraction Plane; this document's Content Extractor Operator (§9) manages deployment topology for the extraction contract 1.26 defines, without changing that contract
> - [synanton-design-1.32.md](./synanton-design-1.32.md) — Platform API; cross-cluster service contracts (§5.7, §11) remain the stable API surface these operators must not bypass
> - [ADR-010](./decisions/adr-010-kubernetes-operator-readiness.md) — decision record for this design
> **Related projects:** `synanton/platform`, `synanton/content_extractor`, `synanton/gpu-runtime`
> **Planned operator projects:** `synanton-platform-operator`, `content-extractor-operator`, `gpu-runtime-operator`
> **Operator implementation language:** Go

---

## 1. Executive Summary

Design 1.33 introduces Kubernetes lifecycle and compatibility as a
  first-class architectural concern of Synanton without making Kubernetes
  a dependency of the Synanton domain model.

The objective is not to immediately migrate Synanton to Kubernetes and
  not to create one monolithic `synanton-k8s-operator`.

The objective is to make Synanton **Kubernetes-ready by architecture**,
  so that a future platform operator can be introduced without forcing
  redesign of service boundaries, configuration, storage, security,
  observability, workload lifecycle, or inter-service contracts.

Design 1.33 establishes three independent operator domains:

1. **Synanton Platform Operator** — future operator responsible for

    lifecycle of the core Synanton platform.

2. **Content Extractor Operator** — separate operator responsible for

    lifecycle of the Structured Content Extraction Plane.

3. **GPU Runtime Operator** — separate operator responsible for

    lifecycle of the physically isolated GPU Execution Plane.

All three operators are proposed as **Go projects**, while the
  underlying Synanton services remain free to use their existing
  implementation languages.

The central architectural rule is:

> **Kubernetes operators own infrastructure lifecycle; Synanton services own domain behavior.**

A second rule follows:

> **One independently operated infrastructure plane should have one lifecycle authority.**

This makes Content Extractor and GPU Runtime separate operator projects
  even when they are used together by one Synanton installation.

---

## 2. Relationship to Design 1.25

[Design 1.25](./synanton-design-1.25.md) defines the knowledge, annotation, derived-state,
  recalculation, analytics and reporting architecture.

Its primary lifecycle is:

```
Source Content → Extraction → Semantic Content → Semantic Chunks → Annotation → Derived Knowledge → Search / Applications → Analytics
```

Design 1.33 adds a deployment and lifecycle dimension around these
  planes:

```text
  Deployment substrate
      │
      ▼
  Service lifecycle
      │
      ▼
  Synanton processing planes
      │
      ▼
  Knowledge architecture
  ```

Design 1.33 does **not** change:

-  annotation identity;
  -  annotation definitions and versions;
  -  provenance;
  -  processing runs;
  -  dependency DAGs;
  -  Resolutor;
  -  Equalix;
  -  security classification;
  -  representation selection;
  -  tenant isolation;
  -  canonical knowledge ownership;
  -  analytics semantics.

Kubernetes is a deployment substrate and lifecycle mechanism, not a new
  knowledge layer.

Design 1.25 already separates extraction from knowledge processing and
  treats operational scalability, workload isolation, deployment,
  monitoring, upgrades and schema migration as operational concerns.
  Design 1.33 makes the deployment/lifecycle contract explicit and extends
  it to future Kubernetes operators.

---

## 3. Why Design 1.33 Is Needed

Introducing Kubernetes only after Synanton services have accumulated
  implicit assumptions about hosts, filesystems, process lifecycle,
  configuration and deployment topology can create expensive refactoring.

The design therefore introduces a compatibility review **before**
  implementation of the platform operator.

The review covers:

-  configuration;
  -  lifecycle;
  -  readiness and health;
  -  persistent state;
  -  networking;
  -  security;
  -  resources;
  -  observability;
  -  upgrade compatibility;
  -  migration;
  -  failure recovery;
  -  cross-cluster operation.

The purpose is not to make every service Kubernetes-specific.

The purpose is to ensure that the same service contract can be
  implemented as:

```text
  process
  container
  Kubernetes workload
  ```

without changing domain semantics.

---

## 4. Architectural Decision

### 4.1 Separate operators

The following projects are proposed:

| Plane | Existing service/project | Operator project | Language |
|---|---|---|---|
| Core Synanton Platform | `synanton/platform` | `synanton-platform-operator` | Go |
| Structured Content Extraction | `synanton/content_extractor` | `content-extractor-operator` | Go |
| GPU Execution | `synanton/gpu-runtime` | `gpu-runtime-operator` | Go |

The operators are independent repositories and release independently.

### 4.2 No monolithic operator

A single operator containing platform, extraction and GPU lifecycle
  logic is rejected.

Reasons:

-  different lifecycle domains;
  -  different scaling characteristics;
  -  different operational ownership;
  -  different failure domains;
  -  different release cadence;
  -  GPU-specific security and scheduling requirements;
  -  independent deployment clusters;
  -  independent adoption;
  -  independent testing.

### 4.3 Optional future composition layer

A future `synanton-deployment-operator` may compose these domains.

Its role would be declarative composition:

```text
  Synanton Deployment
  ├── Core Platform
  ├── Content Extraction
  └── GPU Runtime
  ```

It must not absorb the lifecycle responsibilities of the three domain
  operators.

This is explicitly deferred.

---

## 5. Architectural Principles

### 5.1 Kubernetes is a deployment substrate

Synanton domain APIs must not require Kubernetes.

### 5.2 Operators are lifecycle adapters

An operator translates desired infrastructure state into Kubernetes
  resources and supported platform lifecycle actions.

### 5.3 Domain services remain authoritative

Operators must not implement:

-  extraction semantics;
  -  annotation semantics;
  -  knowledge semantics;
  -  inference admission semantics;
  -  recalculation semantics;
  -  authorization semantics;
  -  analytics semantics.

### 5.4 No direct database mutation

Operators must not modify application databases directly.

Schema migrations remain owned by the application/service and may be
  orchestrated through explicit migration Jobs or supported administrative
  APIs.

### 5.5 Idempotent reconciliation

Reconciliation must be:

-  level-triggered;
  -  idempotent;
  -  retry-safe;
  -  observable;
  -  convergence-oriented.

This follows the Kubernetes operator model: an operator is a controller
  that continuously makes actual state converge toward desired state.

### 5.6 Kubernetes API leakage is prohibited

Domain APIs must not expose Kubernetes implementation types such as:

-  Pod;
  -  Deployment;
  -  StatefulSet;
  -  Node;
  -  PVC;
  -  Service;
  -  Namespace.

### 5.7 Stable service contracts

Cross-cluster communication must use stable service contracts such as
  gRPC/HTTP/protobuf rather than Kubernetes object discovery.

### 5.8 Security remains inherited from Design 1.23

Kubernetes RBAC is infrastructure authorization. It does not replace
  Synanton tenant authorization, classification, masking, ACLs or
  representation rules.

---

## 6. Kubernetes Compatibility Review

Before implementation of the platform operator, every relevant Synanton
  service must pass a Kubernetes compatibility review.

### 6.1 Configuration contract

For each service document:

-  configuration sources;
  -  environment variables;
  -  configuration files;
  -  defaults;
  -  immutable values;
  -  reloadable values;
  -  secret values;
  -  external endpoints;
  -  timeouts;
  -  resource-sensitive settings.

Avoid accidental dependence on host-specific paths or mutable local
  filesystems.

### 6.2 Lifecycle contract

Every service must define:

-  startup;
  -  startup dependencies;
  -  readiness;
  -  liveness;
  -  graceful shutdown;
  -  termination timeout;
  -  restart behavior;
  -  recovery after process loss.

Readiness means:

> The service is safe to receive production work.

It must not merely mean that the JVM or process has started.

### 6.3 Persistent state contract

For every persistent dataset identify:

-  authoritative owner;
  -  cache versus durable state;
  -  durability requirement;
  -  backup requirement;
  -  restore requirement;
  -  migration strategy;
  -  filesystem assumptions.

Container-local filesystem must never accidentally become authoritative
  persistent state.

### 6.4 Networking contract

Document:

-  inbound APIs;
  -  outbound dependencies;
  -  endpoint configuration;
  -  service discovery;
  -  DNS assumptions;
  -  TLS/mTLS;
  -  connection retry;
  -  timeouts;
  -  connection pooling;
  -  cross-cluster requirements.

### 6.5 Resource contract

Every workload must define or measure:

-  CPU request;
  -  CPU limit policy;
  -  memory request;
  -  memory limit;
  -  ephemeral storage;
  -  concurrency model;
  -  GPU requirement where applicable.

### 6.6 Observability contract

Every service should expose:

-  health;
  -  readiness;
  -  structured logs;
  -  metrics;
  -  correlation/request identifiers;
  -  OpenTelemetry integration where appropriate.

### 6.7 Security contract

Review:

-  non-root execution;
  -  Linux capabilities;
  -  filesystem permissions;
  -  service identity;
  -  secret handling;
  -  TLS;
  -  NetworkPolicy;
  -  image provenance;
  -  dependency security.

### 6.8 Upgrade contract

Every service must define:

-  rolling upgrade behavior;
  -  API compatibility;
  -  database migration behavior;
  -  configuration compatibility;
  -  rollback constraints;
  -  state migration requirements.

---

## 7. Operator Responsibility Boundary

| Concern | Operator | Service / Plane |
|---|---|---|
| Deployment lifecycle | Yes | No |
| Replica lifecycle | Yes | No |
| Pod recovery | Kubernetes/operator | No |
| Service wiring | Yes | No |
| Persistent volume wiring | Yes | No |
| Secret references | Yes | No |
| Resource configuration | Yes | No |
| Domain processing | No | Yes |
| Extraction semantics | No | Content Extractor |
| GPU execution semantics | No | GPU Runtime |
| Annotation semantics | No | Synanton Platform |
| Recalculation | No | Resolutor / Equalix |
| Analytics semantics | No | Analytics Plane |
| Database schema | No | Service |
| Database migration artifact | No | Service |
| Database migration orchestration | Yes | Service-owned migration |
| Tenant authorization | No | Synanton Platform |
| Kubernetes RBAC | Yes | No |

---

## 8. Future Synanton Platform Operator

The future platform operator manages the lifecycle of the core Synanton
  installation.

Initial responsibilities:

-  deploy platform components;
  -  configure service relationships;
  -  create service identities;
  -  connect persistent storage;
  -  reference secrets;
  -  expose platform APIs;
  -  configure observability;
  -  manage compatible upgrades;
  -  orchestrate controlled migrations;
  -  expose lifecycle status.

It must not become a second implementation of Synanton application
  logic.

### 8.1 Candidate primary CRD

```
SynantonPlatform
```

Illustrative:

```yaml
  apiVersion: platform.synanton.io/v1alpha1
  kind: SynantonPlatform
  metadata:
   name: synanton
  spec:
   version: "1.x"
   components:
    replicas:
     api: 2
     workers: 2
   storage:
    className: fast-ssd
   security:
    tls:
     enabled: true
  ```

The exact CRD is deliberately not frozen by Design 1.33. It must follow
  the compatibility review and service lifecycle matrix.

---

## 9. Content Extractor Operator

The Content Extractor repository defines the Structured Content
  Extraction Plane as a boundary between raw content and structured
  content. It explicitly treats the extraction contract as
  deployment-neutral and supports embedded, co-located, clustered and
  distributed deployment modes.

This is a strong architectural reason for a separate operator.

The operator manages the **deployment topology**, while the Content
  Extractor contract remains unchanged.

Potential responsibilities:

-  extraction API/gateway;
  -  modality worker deployments;
  -  worker scaling;
  -  queue integration;
  -  object-storage configuration;
  -  processor artifacts;
  -  resource profiles;
  -  GPU-capable worker placement where required;
  -  rollout;
  -  readiness;
  -  status.

The operator must not turn the extraction plane into a
  knowledge-processing service.

The Content Extractor API must remain unaware of:

-  Pod names;
  -  node names;
  -  Kubernetes scheduler details;
  -  physical GPU topology;
  -  internal worker implementation;
  -  queue implementation details.

---

## 10. GPU Runtime Operator

The GPU Runtime repository explicitly defines a physically isolated,
  on-premise optimized GPU Execution Plane.

Its architectural invariant is:

```text
  Synanton Platform determines WHAT should execute.
  GPU Runtime determines HOW it executes.
  Kubernetes determines WHERE it executes.
  ```

The Main Platform is explicitly prohibited from directly discovering or
  accessing Pods, GPU nodes, physical GPUs or vLLM endpoints. The GPU
  Gateway is the sole network entry point.

Design 1.33 preserves this invariant.

The GPU Runtime Operator therefore owns infrastructure lifecycle
  **inside the dedicated GPU cluster**.

Potential responsibilities:

-  GPU Gateway lifecycle;
  -  runtime deployment;
  -  model-serving workloads;
  -  model-cache storage;
  -  GPU scheduling configuration;
  -  node selectors;
  -  taints/tolerations;
  -  affinity;
  -  NetworkPolicy;
  -  monitoring;
  -  rollout;
  -  capacity status.

It must not expose physical GPU topology through the public GPU Runtime
  contract.

---

## 11. Cross-Cluster Architecture

A valid production topology is:

```text
  Cluster A
  ────────────────────────────────
  Synanton Platform
      |
      | gRPC / mTLS
      v
  Cluster B
  ────────────────────────────────
  Content Extractor

Cluster C
  ────────────────────────────────
  GPU Runtime
      ^
      |
      +---- authorized platform/extractor clients
  ```

The clusters may also be combined for smaller installations.

The logical contracts remain the same.

The main platform must communicate with Content Extractor and GPU
  Runtime through their service contracts, not through Kubernetes objects.

---

## 12. GPU Runtime and Content Extractor Relationship

Content Extractor may require GPU acceleration for capabilities such as
  OCR, VLM enrichment or multimodal processing.

That does not justify coupling its operator to the GPU Runtime operator.

The distinction is:

```text
  Content Extractor Operator
    manages extraction infrastructure

GPU Runtime Operator
    manages GPU Runtime infrastructure
  ```

Cross-plane invocation remains an API concern.

A future deployment composition layer may express that a deployment
  requires both, but the two lifecycle authorities remain separate.

---

## 13. CRD Design Rules

CRDs represent desired state, not implementation details.

Good:

```yaml
  spec:
   replicas: 3
   version: "2.4"
  ```

Bad:

```yaml
  spec:
   deploymentName: extractor-7d6f9
   podTemplateHash: ...
  ```

Status reports observed state:

```yaml
  status:
   observedGeneration: 4
   conditions:
    - type: Ready
     status: "True"
     reason: Reconciled
  ```

Conditions should be stable and actionable.

---

## 14. Versioning and Compatibility

Operator API versions and application versions are independent.

CRDs should progress through:

```text
  v1alpha1
    ↓
  v1beta1
    ↓
  v1
  ```

Only stable APIs receive long-term compatibility commitments.

Each operator must maintain a compatibility matrix:

| Operator | Application | Kubernetes | CRD |
|---|---|---|---|
| x.y | a.b | supported versions | v1alpha1 |

The matrix must be tested in CI and documented for every production
  release.

---

## 15. Upgrade Strategy

Every operator must implement controlled upgrades:

1. inspect current state;
2. validate target version;
3. execute preflight checks;
4. apply compatible resources;
5. execute service-owned migrations where necessary;
6. roll workloads;
7. verify readiness;
8. update status.

Rollback must explicitly be classified as:

-  safe;
  -  conditionally safe;
  -  unsupported.

Database migrations must never assume rollback is universally possible.

---

## 16. Failure Model

Operators must distinguish:

-  transient Kubernetes failure;
  -  workload failure;
  -  dependency failure;
  -  invalid configuration;
  -  incompatible version;
  -  persistent storage failure;
  -  operator failure.

Status should expose actionable conditions:

```text
  Ready=False
  Reason=DependencyUnavailable
  Message=GPU Runtime endpoint is unreachable
  ```

Permanent configuration errors must not cause infinite resource
  recreation loops.

---

## 17. Security Architecture

Security is layered:

1. Kubernetes/cluster security;
2. operator/service identity;
3. Synanton application security.

[Design 1.23](./synanton-design-1.23.md) remains authoritative for:

-  ACL;
  -  class grants;
  -  classification;
  -  masking;
  -  representation;
  -  fail-closed behavior;
  -  security-sensitive cache invalidation.

Operator RBAC follows least privilege.

GPU Runtime requires a stricter security review because GPU
  infrastructure may require additional node-level integration.

---

## 18. Observability

Each operator should expose:

-  reconciliation count;
  -  reconciliation duration;
  -  reconciliation errors;
  -  managed-resource health;
  -  work-queue depth;
  -  API errors;
  -  status transitions.

Operators do not replace application telemetry.

Service metrics remain owned by the services.

Observability must distinguish:

```text
  platform health
  extraction health
  GPU Runtime health
  operator health
  security health
  analytics health
  ```

---

## 19. Testing Strategy

### Unit tests

-  reconciliation;
  -  desired-state generation;
  -  validation;
  -  status transitions;
  -  error classification.

### API tests

-  CRD schema;
  -  validation;
  -  defaulting;
  -  version conversion.

### Integration tests

-  Kubernetes API;
  -  resource lifecycle;
  -  restart behavior;
  -  status convergence.

### E2E tests

-  installation;
  -  upgrade;
  -  failure recovery;
  -  persistence;
  -  security;
  -  uninstall;
  -  cross-cluster integration.

### Conformance tests

Cross-plane contracts must verify:

-  service discovery;
  -  authentication;
  -  TLS/mTLS;
  -  version compatibility;
  -  failure behavior;
  -  tenant/security invariants where applicable.

---

## 20. Go Implementation Decision

All three operator projects are proposed in **Go**.

This is an implementation decision for the operators only. It does not
  require rewriting existing Java services.

Recommended technology baseline:

-  Go;
  -  controller-runtime;
  -  Kubebuilder;
  -  Kubernetes APIs;
  -  Prometheus metrics;
  -  OpenTelemetry where appropriate;
  -  OCI container images;
  -  Helm and/or OCI-based packaging.

The Kubernetes project documents the Operator pattern as controllers
  that reconcile Custom Resources and application state. Kubernetes also
  lists Kubebuilder among the established tools for implementing
  operators. Current Kubernetes documentation and a recent Kubernetes
  engineering article describe Go with Kubebuilder/controller-runtime as a
  common operator implementation path.

Go is preferred because:

-  Kubernetes APIs have strong Go support;
  -  controller-runtime provides the reconciliation framework;
  -  operators compile to small self-contained binaries;
  -  deployment is simple;
  -  resource overhead is low;
  -  Kubernetes ecosystem integration is strong.

This decision applies to the **operator repositories**, not to
  `platform`, `content_extractor`, or `gpu-runtime` service implementation
  languages.

---

## 21. Repository Model

Proposed repositories:

```text
  synanton/platform
  synanton/content_extractor
  synanton/gpu-runtime

  synanton/synanton-platform-operator
  synanton/content-extractor-operator
  synanton/gpu-runtime-operator
  ```

Do not initially place all operators into one repository.

Shared cross-plane contracts should remain in stable
  API/protobuf/contract modules.

---

## 22. Implementation Plan

### Phase 0 — Kubernetes Compatibility Review

Review:

-  platform;
  -  content extractor;
  -  GPU Runtime.

Deliver:

-  service lifecycle matrix;
  -  configuration matrix;
  -  storage matrix;
  -  dependency matrix;
  -  resource profiles;
  -  security review;
  -  observability review;
  -  upgrade/rollback review.

### Phase 1 — Operator Foundations

Create three Go repositories.

Implement:

-  CI;
  -  linting;
  -  unit-test framework;
  -  container build;
  -  CRD generation;
  -  RBAC;
  -  metrics;
  -  health endpoints.

### Phase 2 — Content Extractor Operator

Implement:

-  CRD;
  -  single-instance lifecycle;
  -  workers;
  -  resource profiles;
  -  storage/networking;
  -  status.

### Phase 3 — GPU Runtime Operator

Implement:

-  gateway lifecycle;
  -  runtime lifecycle;
  -  model/cache lifecycle;
  -  GPU scheduling;
  -  security;
  -  status.

### Phase 4 — Platform Operator

Implement:

-  core service lifecycle;
  -  storage;
  -  configuration;
  -  secrets;
  -  migrations;
  -  status;
  -  observability.

### Phase 5 — Cross-Plane Integration

Validate:

-  platform → extractor;
  -  platform → GPU Runtime;
  -  extractor → GPU Runtime where applicable;
  -  TLS/mTLS;
  -  authentication;
  -  compatibility;
  -  failure recovery.

### Phase 6 — Production Hardening

Add:

-  upgrade testing;
  -  rollback testing;
  -  chaos/failure tests;
  -  backup/restore;
  -  security testing;
  -  performance testing;
  -  operational runbooks.

### Phase 7 — Optional Composition

Only after the independent operators are production-ready consider a
  higher-level deployment/composition operator.

---

## 23. Acceptance Criteria

Design 1.33 is implementation-ready when:

-  all major services have a Kubernetes compatibility assessment;
  -  lifecycle semantics are documented;
  -  persistent state ownership is explicit;
  -  configuration contracts are explicit;
  -  readiness semantics are defined;
  -  upgrade compatibility is defined;
  -  operator boundaries are approved;
  -  no domain contract requires Kubernetes;
  -  Content Extractor has an independent operator boundary;
  -  GPU Runtime has an independent operator boundary;
  -  Platform Operator remains a separate lifecycle authority;
  -  Go is approved as the operator implementation language;
  -  cross-cluster deployment is supported by contract;
  -  Kubernetes security and Synanton security responsibilities are
    explicit;
  -  migration and rollback constraints are documented.

---

## 24. Risks and Mitigations

### Kubernetes leaks into domain APIs

**Mitigation:** deployment-neutral service contracts.

### Monolithic operator becomes a second platform

**Mitigation:** strict lifecycle boundaries.

### Operators duplicate business logic

**Mitigation:** operator code is restricted to lifecycle/reconciliation
  concerns.

### CRDs become unstable application APIs

**Mitigation:** deliberate API review and versioning.

### GPU operator requires excessive privileges

**Mitigation:** dedicated cluster and least-privilege RBAC.

### Operator upgrade breaks application state

**Mitigation:** compatibility matrix and explicit migration phases.

### Cross-cluster networking becomes implicit

**Mitigation:** explicit endpoint, identity, TLS and failure contracts.

---

## 25. Architectural Invariants Added by Design 1.33

### Invariant 16 — Deployment Independence

Synanton domain contracts do not require Kubernetes.

### Invariant 17 — Independent Lifecycle Authority

Each independently operated infrastructure plane has one authoritative
  operator.

### Invariant 18 — Operator/Service Separation

Operators manage lifecycle; services implement domain behavior.

### Invariant 19 — No Kubernetes Leakage

Kubernetes implementation objects are not exposed through Synanton
  domain contracts.

### Invariant 20 — Cross-Cluster Transparency

A service contract remains valid regardless of whether the provider is
  local, co-located, clustered or remote.

### Invariant 21 — Physical GPU Isolation

The Main Platform never directly accesses physical GPU infrastructure.

### Invariant 22 — Operator Idempotency

Operator reconciliation must converge safely after repeated invocation,
  restart or partial failure.

---

## 26. Final Architectural Thesis

Synanton should not become "a Kubernetes application."

It should become a **deployment-independent platform that is
  Kubernetes-native when deployed on Kubernetes**.

The intended architecture is:

```text
               SYNANTON
                │
       ┌─────────────────┼─────────────────┐
       │         │         │
       ▼         ▼         ▼
     Platform     Extraction    GPU Runtime
      Plane        Plane       Plane
       │         │         │
       ▼         ▼         ▼
     Platform     Content Extractor  GPU Runtime
     Operator       Operator     Operator
       │         │         │
     Cluster A     Cluster B     Cluster C
  ```

Kubernetes provides lifecycle, scheduling and infrastructure
  reconciliation.

Synanton provides knowledge, extraction, inference, security,
  recalculation and analytics semantics.

The separation is intentional and must remain stable.
