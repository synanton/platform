---
title: "Architecture"
status: "current"
last_reviewed: "2026-09-07"
---

# Architecture

**Purpose:** Long-lived design decisions, Architecture Decision Records (ADRs), and the current authoritative design document for the Synanton platform.
**Audience:** Architects, module owners, security engineers
**Last Updated:** 2026-09-07

> **Synanton Platform Architecture 1.0 (capstone):** [`synanton-platform-architecture-1.0.md`](./synanton-platform-architecture-1.0.md) — the cumulative document showing how Designs 1.22–1.33 fit together. Read this first for the platform-wide picture; each plane document below remains authoritative for its own domain.
> **Current approved design (base):** [`synanton-design-1.22.md`](./synanton-design-1.22.md) (platform baseline)
> **v1.23 (in progress):** [`synanton-design-1.23.md`](./synanton-design-1.23.md) — classification-aware semantic search; **normative for security platform-wide**
> **v1.25 (approved, architecture-only; consolidates 1.24):** [`synanton-design-1.25.md`](./synanton-design-1.25.md) — annotations, derived knowledge, recalculation, analytics & reporting
> **v1.26–v1.33 (approved, architecture-only; not yet implemented):** Content Cache ([1.26](./synanton-design-1.26.md)), Eventing and Workflow ([1.27](./synanton-design-1.27.md) — the common asynchronous fabric 1.28–1.31 depend on), Ingestion ([1.28](./synanton-design-1.28.md)), Identity/Tenant/Policy ([1.29](./synanton-design-1.29.md)), AI/Model Runtime ([1.30](./synanton-design-1.30.md)), Search and Retrieval ([1.31](./synanton-design-1.31.md)), Platform API ([1.32](./synanton-design-1.32.md)), Kubernetes Operator Readiness ([1.33](./synanton-design-1.33.md))
> Extraction plane (Part IX): [`synanton-design-1.21.md`](./archive/synanton-design-1.21.md)
> GPU Execution Plane detail: [`synanton-design-1.20.md`](./archive/synanton-design-1.20.md)
> 1.19 is the merged baseline for unchanged core sections - **not** the live pointer.

## Quick Links

| Document | Description |
|----------|-------------|
| [`synanton-platform-architecture-1.0.md`](./synanton-platform-architecture-1.0.md) | **Approved (architecture) — capstone** — cumulative Synanton Platform Architecture 1.0, integrating 1.22–1.33 |
| [`synanton-design-1.33.md`](./synanton-design-1.33.md) | **Approved (architecture)** - Kubernetes operator readiness and lifecycle contract (v1.33; no operator implementation) |
| [`synanton-design-1.32.md`](./synanton-design-1.32.md) | **Approved (architecture)** - stable Platform API and compatibility (v1.32) |
| [`synanton-design-1.31.md`](./synanton-design-1.31.md) | **Approved (architecture)** - lexical/vector/hybrid/graph retrieval and ranking (v1.31) |
| [`synanton-design-1.30.md`](./synanton-design-1.30.md) | **Approved (architecture)** - AI/model runtime execution contract (v1.30) |
| [`synanton-design-1.29.md`](./synanton-design-1.29.md) | **Approved (architecture)** - principal, federation, tenant membership, delegation (v1.29) |
| [`synanton-design-1.28.md`](./synanton-design-1.28.md) | **Approved (architecture)** - source identity, versioning, synchronization, deletion (v1.28) |
| [`synanton-design-1.27.md`](./synanton-design-1.27.md) | **Approved (architecture)** - events, commands, workflows, retries, recovery; the common execution fabric 1.28–1.31 depend on (v1.27) |
| [`synanton-design-1.26.md`](./synanton-design-1.26.md) | **Approved (architecture)** - content artifact storage/retrieval/lifecycle contract (v1.26) |
| [`synanton-design-1.25.md`](./synanton-design-1.25.md) | **Approved (architecture)** - annotations, derived knowledge, recalculation, analytics & reporting plane (v1.25, consolidates 1.24) |
| [`synanton-design-1.23.md`](./synanton-design-1.23.md) | **In progress** - classification-aware semantic search (v1.23) |
| [`synanton-design-1.22.md`](./synanton-design-1.22.md) | **Current baseline** - semantic chunking + pointers to 1.21 extraction / 1.20 GPU / 1.19 baseline |
| [`archive/synanton-design-1.21.md`](./archive/synanton-design-1.21.md) | Structured Content Extraction Plane (Part IX) |
| [`archive/synanton-design-1.20.md`](./archive/synanton-design-1.20.md) | GPU Execution Plane (Part VIII) |
| [`archive/synanton-design-1.19.md`](./archive/synanton-design-1.19.md) | Superseded pointer; still the merged Parts I–VII baseline |
| [`syntology/ontology-management.md`](./syntology/ontology-management.md) | Syntology ontology management |
| [`decisions/`](./decisions/) | Architecture Decision Records (ADRs) |

## Sub-directories

| Directory | Purpose |
|-----------|---------|
| `syntology/` | Sub-domain design: Ontology Management |
| `decisions/` | ADRs for significant architectural choices |

## Design Version History

| Version | File | Status |
|---------|------|--------|
| 1.0 | [`synanton-platform-architecture-1.0.md`](./synanton-platform-architecture-1.0.md) | **Approved (architecture) — capstone**; integrates 1.22–1.33; see [ADR-011](./decisions/adr-011-platform-architecture-1.0.md) |
| 1.33 | [`synanton-design-1.33.md`](./synanton-design-1.33.md) | Approved (architecture) - Kubernetes operator readiness/lifecycle contract only, no operator implementation; see [ADR-010](./decisions/adr-010-kubernetes-operator-readiness.md) |
| 1.32 | [`synanton-design-1.32.md`](./synanton-design-1.32.md) | Approved (architecture) - implementation not started; see [ADR-009](./decisions/adr-009-platform-api-contract.md) |
| 1.31 | [`synanton-design-1.31.md`](./synanton-design-1.31.md) | Approved (architecture) - implementation not started; see [ADR-008](./decisions/adr-008-search-retrieval-plane.md) |
| 1.30 | [`synanton-design-1.30.md`](./synanton-design-1.30.md) | Approved (architecture) - implementation not started; see [ADR-007](./decisions/adr-007-ai-model-runtime-plane.md) |
| 1.29 | [`synanton-design-1.29.md`](./synanton-design-1.29.md) | Approved (architecture) - implementation not started; see [ADR-006](./decisions/adr-006-identity-tenant-policy-plane.md) |
| 1.28 | [`synanton-design-1.28.md`](./synanton-design-1.28.md) | Approved (architecture) - implementation not started; see [ADR-005](./decisions/adr-005-ingestion-plane.md) |
| 1.27 | [`synanton-design-1.27.md`](./synanton-design-1.27.md) | Approved (architecture) - implementation not started; common eventing/workflow fabric for 1.28-1.31; see [ADR-004](./decisions/adr-004-eventing-workflow-plane.md) |
| 1.26 | [`synanton-design-1.26.md`](./synanton-design-1.26.md) | Approved (architecture) - implementation not started; see [ADR-003](./decisions/adr-003-content-cache-plane.md) |
| 1.25 | [`synanton-design-1.25.md`](./synanton-design-1.25.md) | Approved (architecture) - implementation phased (AAP-1/AAP-2 landed; annotations, recalculation, analytics/reporting; consolidates 1.24) |
| 1.23 | [`synanton-design-1.23.md`](./synanton-design-1.23.md) | In progress (classification-aware search) |
| 1.22 | [`synanton-design-1.22.md`](./synanton-design-1.22.md) | **CURRENT baseline** |
| 1.21 | [`archive/synanton-design-1.21.md`](archive/synanton-design-1.21.md) | Extraction plane (Part IX; folded, archived) |
| 1.20 | [`archive/synanton-design-1.20.md`](archive/synanton-design-1.20.md) | GPU plane (folded, archived; still the Part VIII text) |
| 1.19 | [`archive/synanton-design-1.19.md`](archive/synanton-design-1.19.md) | Superseded as current; baseline for core modules |
| 1.18 | [`../archive/architecture/synanton-design-1.18.md`](archive/architecture/synanton-design-1.18.md) | Superseded |
| 1.17 | [`../archive/architecture/synanton-design-1.17.md`](archive/architecture/synanton-design-1.17.md) | Superseded |
| ≤1.16 | [`../archive/architecture/`](archive/architecture/) | Archived |

## How to Contribute

To propose a design change: create a new proposal in `../proposals/vX.Y/`, get approval, then update the current design file here (and this `INDEX.md` plus `../VERSION`). Move only fully replaced documents to `../archive/architecture/`.
