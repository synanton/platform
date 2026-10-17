---
title: "Architecture Decision Records"
status: "current"
last_reviewed: "2026-10-17"
---

# Architecture Decision Records (ADRs)

**Purpose:** Captures significant architectural decisions with their context, rationale, and consequences.
**Audience:** Architects, module owners
**Last Updated:** 2026-10-17

## Quick Links

| Document | Decision |
|----------|---------|
| [ADR-020: Operation Partial Outcome](./adr-020-operation-partial-outcome.md) | Additive `outcome: FULL \| PARTIAL` on the Operation resource; partial extraction success is first-class, design doc stays frozen (Accepted 2026-10-17) |
| [ADR-019: Downstream Trust Predicate](./adr-019-downstream-trust-predicate.md) | Signed short-lived context for downstream trust (slice); mTLS as target state; Wave 1 interfaces landed (Accepted 2026-10-17) |
| [ADR-018: OAuth Surface — AS Facade](./adr-018-oauth-facade.md) | Platform is an OAuth 2.1 authorization-server facade — fronts `/oauth/*` and discovery, federates to the external security service (Accepted 2026-10-16) |
| [ADR-017: Validation Tooling](./adr-017-validation-tooling.md) | Vacuum for OpenAPI 3.1 lint; networknt JSON-Schema validator for MCP schema (Accepted 2026-10-16) |
| [ADR-016: MCP Downstream Auth](./adr-016-mcp-downstream-auth.md) | Service identity, not token exchange — the caller bearer is no longer forwarded downstream (Accepted 2026-10-16) |
| [ADR-015: Gen-A API Code Home](./adr-015-gen-a-api-code-home.md) | New `java/platform-api` module; `synapt` frozen for the generation-A slice (Accepted 2026-10-16) |
| [ADR-014: RAG API Authority](./adr-014-rag-api-authority.md) | RAG §§51–52 are 1.30-owned, 1.32-normalized exposure; BBAG-A deferral trivial by authority (decided 2026-10-15) |
| [ADR-013: Vector Engine Selection per Deployment Context](./adr-013-vector-engine-selection.md) | Per-context engine defaults (PG+pgvector managed, Qdrant self-host/docker/on-prem, Lucene embedded) with rationale + revisit triggers — Accepted 2026-10-11 |
| [ADR-012: Temporal Versioned Knowledge and Retrieval](./adr-012-temporal-versioned-knowledge-retrieval.md) | Cross-plane temporal semantics — version series owned by Ingestion (1.28), `current` as a set, correction classification, temporal eligibility as a pre-ranking search constraint (v1.34 approved 2026-09-12) |
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

Create `adr-NNN-short-description.md` following the shape of ADR-014 (the live
precedent for ADR-014 through ADR-020):

- Title carries the decision: `# ADR-NNN: <decision>`
- `**Status:**` and `**Basis:**` inline, dated. No separate `**Date:**` or
  `**Deciders:**` field.
- `## Decision` first — numbered decisions, with `Consequences:` as flat
  bullets inside the section, each with a bold lead-in (`**M0:**`, `**Clients:**`).
- `## Context` last — why the decision was needed.
- No Alternatives / Follow-ups / References sections; rejected options go in
  Consequences as `**Rejected:**` bullets.
- Accepted ADRs are not rewritten; corrections are dated inline annotations
  (see ADR-016's handler-count correction, ADR-018's consequence addition).

```markdown
# ADR-NNN: [decision — one line]

**Status:** Proposed | Accepted | Superseded | Deprecated [YYYY-MM-DD]
**Basis:** [what motivates the decision]

## Decision

1. [decision]
Consequences:
- **[lead-in]:** [effect]
- **Rejected:** [option and why]

## Context

[why this decision was needed, what it replaces]
```
