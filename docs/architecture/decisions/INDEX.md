---
title: "Architecture Decision Records"
status: "current"
last_reviewed: "2026-11-14"
---

# Architecture Decision Records (ADRs)

**Purpose:** Captures significant architectural decisions with their context, rationale, and consequences.
**Audience:** Architects, module owners
**Last Updated:** 2026-11-14

## Quick Links

| Document | Decision |
|----------|---------|
| [ADR-042: Mapping path composition and provenance](./adr-042-mapping-path-composition.md) | **Proposed** — ONT-ADR-013; before agent reasoning |
| [ADR-041: Mapping experiment reproducibility](./adr-041-mapping-experiment-reproducibility.md) | **Proposed** — ONT-ADR-012; Phase 2 gate |
| [ADR-040: Mapping strategy abstraction](./adr-040-mapping-strategy-abstraction.md) | **Proposed** — ONT-ADR-011; Phase 2 gate |
| [ADR-039: Ontology registry and API contracts](./adr-039-ontology-registry-api-contracts.md) | **Proposed** — ONT-ADR-010; Phase 1 exit / Phase 2 |
| [ADR-038: Historical concept and instance versioning](./adr-038-historical-concept-instance-versioning.md) | **Proposed** — ONT-ADR-009; Phase 1 exit |
| [ADR-037: OWL 2 reasoning profile](./adr-037-owl2-reasoning-profile.md) | **Proposed** — ONT-ADR-008; Phase 3 |
| [ADR-036: Foundational ontology scope](./adr-036-foundational-ontology-scope.md) | **Proposed** — ONT-ADR-007; Phase 3 |
| [ADR-035: Cross-domain inference policy](./adr-035-cross-domain-inference-policy.md) | **Proposed** — ONT-ADR-006; Phase 4 |
| [ADR-034: Domain hierarchy and composition](./adr-034-domain-hierarchy-composition.md) | **Proposed** — ONT-ADR-005; Phase 2 |
| [ADR-033: Mapping governance and promotion policy](./adr-033-mapping-governance-promotion-policy.md) | **Proposed** — ONT-ADR-004; Phase 2 gate |
| [ADR-032: Mapping semantics and reasoning permissions](./adr-032-mapping-semantics-reasoning-permissions.md) | **Proposed** — ONT-ADR-003; Phase 2 gate |
| [ADR-031: Context schema and conflict resolution](./adr-031-context-schema-conflict-resolution.md) | **Proposed** — ONT-ADR-002; Phase 1 exit |
| [ADR-030: Concept identity, namespace and ownership](./adr-030-concept-identity-namespace-ownership.md) | **Proposed** — ONT-ADR-001; Phase 1 exit |
| [ADR-022: Pluggable Knowledge Extraction Pipeline (RAKG)](./adr-022-extraction-engine-architecture.md) | `ExtractionEngineAdapter` port: regex baseline + out-of-process Qwen3-4B vLLM; sentence-aligned input, surface-form offset recovery, synthetic endpoints, versioned prompt, frozen-gold metrics (Accepted 2026-11-08) |
| [ADR-024: glibc-based runtime images for native dependencies](./adr-024-glibc-runtime-images.md) | Native gRPC/Netty/JNI services use glibc JRE images; Alpine only with zero native dependencies; CI checks Dockerfiles (Accepted 2026-11-11) |
| [ADR-025: Canonical graph bootstrap is additive, never destructive](./adr-025-graph-bootstrap-additive.md) | Tenant loads are upsert-only and transactional; restarts/partial snapshots cannot delete canonical graph data (Accepted 2026-11-11) |
| [ADR-026: One active canonical graph projection per tenant](./adr-026-canonical-graph-projection-scope.md) | Tenant-scoped canonical graph identity with an authoritative projection version; resolver enforces zero-or-multiple-graph failure; no per-node backfill (Accepted 2026-11-11) |
| [ADR-027: Projection provisioning is a separate lifecycle](./adr-027-projection-provisioning-lifecycle.md) | Runtime graph access consumes `GraphIdentity` and fails closed; provisioning takes an explicit tenant, obtains Syntology authority, and establishes metadata under additive/no-overwrite/concurrency-safe rules (Accepted 2026-11-12) |
| [ADR-029: Source-change lifecycle](./adr-029-source-change-lifecycle.md) | Immutable revisions + generation fencing; provenance-aware assertions; staged conditional projection commit; revision-aware search replacement; explicit STALE failure state (Proposed 2026-11-12) |
| [ADR-021: Pluggable Search Engine Boundary](./adr-021-pluggable-search-engine.md) | Projection writes go through a domain `SearchEngineAdapter`; streaming rebuild, incremental `updateDocument`, atomic `SearcherManager` refresh; read-path DSL staged (Accepted 2026-11-08) |
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

## Ontology and Cross-Domain Mapping ADR Register

The ontology proposal uses stable proposal-local identifiers **ONT-ADR-001–013**. They map to repository-wide **ADR-030–042** because ADR-001–029 are already allocated to platform decisions. The proposal-local IDs remain useful for discussion; repository ADR numbers are canonical for files, links and tracking.

| Proposal ID | Repository ADR | Decision | Status | Phase gate |
|---|---|---|---|---|
| ONT-ADR-001 | [ADR-030](./adr-030-concept-identity-namespace-ownership.md) | Concept identity, namespace, ownership and canonical identity authority | Proposed | Phase 1 exit |
| ONT-ADR-002 | [ADR-031](./adr-031-context-schema-conflict-resolution.md) | Context schema, composition and conflict resolution | Proposed | Phase 1 exit |
| ONT-ADR-003 | [ADR-032](./adr-032-mapping-semantics-reasoning-permissions.md) | Mapping relation semantics, direction and reasoning permissions | Proposed | Phase 2 gate |
| ONT-ADR-004 | [ADR-033](./adr-033-mapping-governance-promotion-policy.md) | Mapping governance, risk classification and promotion policy | Proposed | Phase 2 gate |
| ONT-ADR-005 | [ADR-034](./adr-034-domain-hierarchy-composition.md) | Domain hierarchy and composition | Proposed | Phase 2 |
| ONT-ADR-006 | [ADR-035](./adr-035-cross-domain-inference-policy.md) | Cross-domain inference policy | Proposed | Phase 4 |
| ONT-ADR-007 | [ADR-036](./adr-036-foundational-ontology-scope.md) | Foundational ontology scope | Proposed | Phase 3 |
| ONT-ADR-008 | [ADR-037](./adr-037-owl2-reasoning-profile.md) | OWL 2 reasoning profile | Proposed | Phase 3 |
| ONT-ADR-009 | [ADR-038](./adr-038-historical-concept-instance-versioning.md) | Historical concept and instance versioning | Proposed | Phase 1 exit |
| ONT-ADR-010 | [ADR-039](./adr-039-ontology-registry-api-contracts.md) | Ontology registry and API contracts | Proposed | Phase 1 exit / Phase 2 |
| ONT-ADR-011 | [ADR-040](./adr-040-mapping-strategy-abstraction.md) | Mapping strategy abstraction and capability contract | Proposed | Phase 2 gate |
| ONT-ADR-012 | [ADR-041](./adr-041-mapping-experiment-reproducibility.md) | Experiment reproducibility and benchmark governance | Proposed | Phase 2 gate |
| ONT-ADR-013 | [ADR-042](./adr-042-mapping-path-composition.md) | Mapping path composition and provenance | Proposed | Before agent reasoning |

The ontology ADRs are **Proposed**, not accepted. The status and phase-gate columns above are the register's tracking view; each ADR remains the authoritative decision record. Owners are intentionally not invented; ADR work tickets must not be created until decision owners are assigned.

**Blocked — owner needed:** the benchmark dataset workstream, Equalix contract review, and candidate/governed-store separation spike remain unowned. These are explicit ownership blockers, not accepted or silently deferred scope.

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
