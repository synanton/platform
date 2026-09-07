---
title: "Architecture Decision Records"
status: "current"
last_reviewed: "2026-09-07"
---

# Architecture Decision Records (ADRs)

**Purpose:** Captures significant architectural decisions with their context, rationale, and consequences.
**Audience:** Architects, module owners
**Last Updated:** 2026-09-07

## Quick Links

| Document | Decision |
|----------|---------|
| [ADR-011: Synanton Platform Architecture 1.0](./adr-011-platform-architecture-1.0.md) | Capstone consolidation of Designs 1.22–1.33 into a single integration document (approved 2026-09-07) |
| [ADR-010: Kubernetes Operator Readiness](./adr-010-kubernetes-operator-readiness.md) | Kubernetes lifecycle/compatibility contract and independent operators (`synanton-platform-operator`, `content-extractor-operator`, `gpu-runtime-operator`); no operator implementation (v1.33 approved 2026-09-07) |
| [ADR-009: Platform API & Contract Architecture](./adr-009-platform-api-contract.md) | Stable, versioned, resource-oriented external Platform API; Operation/API semantics distinct from internal 1.27 eventing (v1.32 approved 2026-09-07) |
| [ADR-008: Search and Retrieval Plane](./adr-008-search-retrieval-plane.md) | Lexical/vector/hybrid/graph retrieval with security as a candidate-eligibility prerequisite, not a post-filter (v1.31 approved 2026-09-07) |
| [ADR-007: AI and Model Runtime Plane](./adr-007-ai-model-runtime-plane.md) | Platform-owned model execution contract (WHAT/WHY/SECURITY/PROVENANCE) vs. GPU Runtime execution mechanics (HOW/SCHEDULING/HARDWARE) (v1.30 approved 2026-09-07) |
| [ADR-006: Identity, Tenant & Policy Plane](./adr-006-identity-tenant-policy-plane.md) | Principal, tenant membership, delegation and federation semantic authority; public identity-management API deferred to 1.32 (v1.29 approved 2026-09-07) |
| [ADR-005: Ingestion Plane](./adr-005-ingestion-plane.md) | Source identity, versioning, synchronization and deletion, with SourceIdentity kept distinct from content digest (v1.28 approved 2026-09-07) |
| [ADR-004: Eventing and Workflow Plane](./adr-004-eventing-workflow-plane.md) | Common asynchronous execution fabric (events, commands, workflows, retries, recovery) positioned ahead of 1.28–1.31 (v1.27 approved 2026-09-07) |
| [ADR-003: Content Cache Plane](./adr-003-content-cache-plane.md) | Implementation-independent content-artifact storage/retrieval/lifecycle contract, with conformance evidence required for advertised optional capabilities (v1.26 approved 2026-09-07) |
| [ADR-002: Annotations, Derived Knowledge, Recalculation & Analytics/Reporting Plane](./adr-002-annotations-analytics-plane.md) | First-class annotations with Resolutor/Equalix recalculation, plus a security-consistent Analytics & Reporting Plane (v1.25, consolidates 1.24; approved 2026-09-01) |
| [ADR-001: Classification-Aware Search & Semantic Chunking](./adr-001-classification-aware-search.md) | Sub-document sensitivity via chunk-level ACLs and masked representations (v1.23 approved 2026-08-30) |

## How to Contribute

Create a new ADR file named `adr-NNN-short-description.md` following the template:

```markdown
# ADR-NNN: Title

**Status:** Proposed | Accepted | Superseded | Deprecated
**Date:** YYYY-MM-DD
**Deciders:** [names or roles]

## Context
[What is the issue that motivates this decision?]

## Decision
[What is the decision?]

## Consequences
[What becomes easier or harder as a result?]
```
