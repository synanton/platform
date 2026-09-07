# ADR-010: Kubernetes Lifecycle, Compatibility and Multi-Operator Architecture

**Status:** Accepted
**Date:** 2026-09-07
**Deciders:** Architecture team
**Design Document:** [synanton-design-1.33.md](../synanton-design-1.33.md)

## Context

Synanton's core platform, Content Extractor and GPU Runtime services have accumulated implicit assumptions about hosts, filesystems, process lifecycle, configuration and deployment topology as they were built. Introducing Kubernetes only after those assumptions harden would create expensive refactoring, so the platform needs a Kubernetes-readiness contract defined **before** any operator is implemented, not discovered afterward.

Design 1.33 was reviewed as part of the cumulative cross-plane architecture review covering Designs 1.26–1.33 (`docs/architecture/proposals/synanton-architecture-review-resolution.md`, GitHub Issue #39). That review raised two findings specific to this design:

- **Operator naming ambiguity** — earlier drafting risked introducing an inconsistently named `content_retrieval`-style operator alongside the canonical `content-extractor-operator`. Design 1.33 was checked against the platform-wide operator naming already fixed in `docs/architecture/proposals/synanton-platform-1.0-proposal.md` §11 (`synanton-platform-operator`, `content-extractor-operator`, `gpu-runtime-operator`). No `content_retrieval` naming was found in the proposal; the review resolution is recorded as already satisfied. One internal inconsistency was found and fixed during the fold: §21's repository list named the platform operator's repository `synanton/platform-operator`, which drops the `synanton-` prefix used everywhere else for that operator (including the platform-1.0 proposal); it now reads `synanton/synanton-platform-operator`.
- **Kubernetes leakage risk** — the proposal already treats CRDs as lifecycle/control-plane resources only (§13 CRD Design Rules; §8.1's illustrative `SynantonPlatform` CRD is explicitly not frozen) and explicitly forbids Kubernetes implementation types (Pod, Deployment, StatefulSet, Node, PVC, Service, Namespace) from appearing in domain APIs (§5.6, reinforced by §9's "Content Extractor API must remain unaware of Pod names, node names..." and Invariant 19 in §25). No leakage into domain/business-logic contracts was found in the reviewed text; the review resolution is recorded as already satisfied by the design as written.

Review of the proposal found it internally consistent with the platform-wide operator/repository model and the Design 1.23 security baseline it explicitly defers to (§5.8, §17). Cosmetic defects — citation-tool export artifacts (zero-width spaces, backslash-escaped Markdown headings/bullets/blockquotes/fences, a triple-hyphen em-dash substitute, three tables mangled by docx→Markdown conversion, and one repository-name inconsistency) — were cleaned up when folding the proposal into this architecture document; no substantive technical content changed.

## Decision

Accept and fold in the Design 1.33 Kubernetes Lifecycle, Compatibility and Multi-Operator Architecture, which introduces:

- **Three independent operator domains**, each a separate Go repository with its own lifecycle authority: `synanton-platform-operator` (core Synanton platform), `content-extractor-operator` (Structured Content Extraction Plane), and `gpu-runtime-operator` (physically isolated GPU Execution Plane) — with a monolithic `synanton-k8s-operator` explicitly rejected (§4.1, §4.2)
- **A mandatory Kubernetes Compatibility Review** preceding any platform-operator implementation, covering configuration, lifecycle, persistent state, networking, resources, observability, security and upgrade contracts for every relevant service (§6)
- **An explicit operator/service responsibility boundary** (§7): operators own deployment lifecycle, replica lifecycle, service wiring, persistent volume wiring, secrets, resource configuration and Kubernetes RBAC; they never own domain processing, extraction/annotation/recalculation/analytics semantics, database schema, or tenant authorization
- **A prohibition on Kubernetes API leakage into domain contracts** (§5.6): Pod, Deployment, StatefulSet, Node, PVC, Service and Namespace must never appear in Synanton domain APIs; cross-cluster communication uses stable gRPC/HTTP/protobuf contracts instead of Kubernetes object discovery (§5.7)
- **CRD design rules** treating CRDs as desired/observed state only, never implementation details such as pod template hashes or deployment names (§13), with CRD API versions (`v1alpha1` → `v1beta1` → `v1`) tracked independently of application versions via a per-operator compatibility matrix (§14)
- **Idempotent, convergence-oriented reconciliation** as a hard requirement for every operator (§5.5), with an explicit failure model distinguishing transient Kubernetes failure, workload failure, dependency failure, invalid configuration and operator failure, and a rule against infinite resource-recreation loops on permanent configuration errors (§16)
- **Preservation of the GPU Runtime physical-isolation invariant**: the Main Platform is explicitly prohibited from directly discovering or accessing Pods, GPU nodes, physical GPUs or vLLM endpoints — the GPU Gateway remains the sole network entry point (§10)
- **7 architectural invariants** (§25, Invariants 16–22: Deployment Independence, Independent Lifecycle Authority, Operator/Service Separation, No Kubernetes Leakage, Cross-Cluster Transparency, Physical GPU Isolation, Operator Idempotency) and an 8-phase implementation plan (§22, Phase 0–7)

## Consequences

**Enables:**
- A path to Kubernetes-native deployment for the platform, Content Extractor and GPU Runtime planes without retrofitting domain services around Kubernetes-specific assumptions
- Independent release cadences, failure domains and operational ownership for the platform, extraction and GPU lifecycle concerns, instead of one operator becoming a second, informally-specified implementation of platform logic
- A stable point-in-time compatibility contract (service lifecycle matrix, configuration matrix, storage matrix, security review) that later operator implementations must satisfy rather than reverse-engineer
- Deployment substrate flexibility: the same service contract remains valid whether the provider is a bare process, a container, or a Kubernetes workload — locally, co-located, clustered or cross-cluster (§2, §11, Invariant 20)

**Requires:**
- Execution of Phase 0 (Kubernetes Compatibility Review) across `synanton/platform`, `synanton/content_extractor` and `synanton/gpu-runtime` before any operator code is written — none of this review work has started
- Creation of three new Go repositories (`synanton-platform-operator`, `content-extractor-operator`, `gpu-runtime-operator`) with independent CI, CRD generation, RBAC and metrics scaffolding (Phase 1)
- Ongoing enforcement that CRDs stay lifecycle/control-plane resources and never become an alternate, unversioned domain API surface (§13, §24 "CRDs become unstable application APIs")
- A compatibility matrix (operator version × application version × supported Kubernetes versions × CRD API version) tested in CI and documented for every production release of every operator (§14)

**Trade-offs:**
- Three independent operator repositories and release trains (plus an explicitly deferred optional `synanton-deployment-operator` composition layer, §4.3) instead of one, trading implementation simplicity for lifecycle-domain isolation
- The exact `SynantonPlatform` CRD schema is deliberately left unfrozen (§8.1) pending the compatibility review, so no operator implementation can begin from this document alone
- GPU Runtime operator work carries additional security review burden due to node-level integration and stricter isolation requirements (§17)

## Implementation Status

Not started — no operator implementation exists; this is a readiness/contract review only, explicitly not an implementation. No `synanton-platform-operator`, `content-extractor-operator` or `gpu-runtime-operator` repository exists yet, and Phase 0 (Kubernetes Compatibility Review) has not been executed against `platform`, `content_extractor` or `gpu-runtime`. This document defines the contract that a future implementation must satisfy; see Design 1.33 §22 for the full 8-phase plan (Phase 0 Compatibility Review through Phase 7 Optional Composition).
