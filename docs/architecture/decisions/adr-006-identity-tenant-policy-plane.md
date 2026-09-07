# ADR-006: Identity, Tenant & Policy Plane

**Status:** Accepted
**Date:** 2026-09-07
**Deciders:** Architecture team
**Design Document:** [synanton-design-1.29.md](../synanton-design-1.29.md)

## Context

Synanton's platform planes (Ingestion 1.28, Content Cache 1.26, Knowledge 1.25, Eventing/Workflow 1.27, AI Runtime 1.30, Search 1.31, and the normative Security baseline 1.23) all need a consistent answer to a foundational question: **who or what is performing an operation?** Prior to this design, there was no canonical, provider-independent principal model — human users, services, workloads, connectors, agents and system actors were represented ad hoc, with no stable internal identifier, no explicit tenant-membership contract, and no defined way to preserve the original actor across asynchronous, delegated or impersonated execution.

Design 1.29 was developed as part of the 1.26–1.33 proposal series and reviewed under the 2026-09-06 cumulative architecture review (GitHub Issue #39), whose resolutions are recorded in `docs/architecture/proposals/synanton-architecture-review-resolution.md`. Two resolutions are directly load-bearing for this plane:

- **Eventing should precede later planes.** Design 1.27 is the common execution fabric; 1.29 relies on it for asynchronous actor-chain propagation and identity lifecycle events rather than inventing independent async semantics (see design §5, §34, §47, §62, §83, §121).
- **Identity API deferred.** Design 1.32 now explicitly defines the public identity-management API surface, while 1.29 remains the semantic authority for principal, tenant-membership, delegation and federation semantics — not the public API definition.

Review of the proposal found it internally consistent, cleanly separating identity from authorization (Design 1.23) and from content classification, and already respecting the eventing-precedence resolution (its relationship to 1.27 is treated as a dependency, not a duplicate mechanism). One boundary was ambiguous at fold time: §88 ("API Model") listed conceptual REST-shaped routes (`GET /principals/{id}`, `POST /tenants/{id}/members`, etc.) without stating that this is an internal domain-operation shape rather than the public API contract. A fold-time clarifying note was added at §88 (and cross-referenced from the header and Appendix E/F) stating that Design 1.32 owns the public identity-management API surface and that 1.29 supplies the semantic model 1.32's API exposes — consistent with the review's "Identity API deferred" resolution. No other substantive content changed; the document had no citation-tool export artifacts, escaped Markdown, or broken anchors to clean up.

## Decision

Accept and fold in the v1.29 architecture, which introduces:

- **A canonical Synanton Principal model** with explicit, non-string principal types — `USER`, `SERVICE`, `WORKLOAD`, `CONNECTOR`, `AGENT`, `SYSTEM` — and a defined lifecycle (`PROVISIONED → ACTIVE ⇄ SUSPENDED → DISABLED → DELETED`) with disable-before-delete and non-reusable stable identifiers (§12–§22, §118)
- **Identity federation** via an `(issuer, subject) → principal_id` mapping contract, with explicit, non-automatic identity linking and fail-closed collision handling (§23–§25, §95–§97, §119)
- **Explicit tenant membership and tenant context**, where tenant claims are never self-authoritative from client input and cross-tenant confusion is a fail-closed security-critical failure (§37–§40, §101)
- **Authentication/session/assurance separation**, with token validation requirements (issuer, audience, signature, expiry, tenant mapping) and an explicit authentication-assurance level distinct from authorization (§26–§31, §90, §93)
- **Explicit delegation, impersonation and actor-chain preservation** for asynchronous and multi-hop execution (initiator/delegator/executor), prohibiting implicit impersonation and confused-deputy execution (§32–§34, §61–§63, §102, Appendix B)
- **Service and workload identity** distinct from process instances, with short-lived workload credentials preferred over static shared credentials (§15–§17, §53–§60, §104)
- **Identity lifecycle eventing built on Design 1.27** — `PrincipalCreated/Disabled/...`, `TenantMembershipGranted/Revoked`, `ExternalIdentityLinked/Unlinked`, delivered via outbox, versioned, replay-safe without triggering external side effects (§47–§49, §83, §121, Appendix C)
- **A clear ownership split**: the Identity Plane owns principal identity, external mapping, lifecycle and tenant-membership identity relationships; the Authorization Plane (Design 1.23) owns policy, grants, roles and ACL evaluation; Design 1.32 owns the public identity-management API surface (§86–§88, Appendix E–F)
- **20 mandatory security invariants** (§130) and an 8-phase implementation plan with three vertical slices (§125–§128) validating federated auth, service/workload identity, and asynchronous actor-chain preservation end-to-end

## Consequences

**Enables:**
- A single, stable, tenant-scoped principal reference usable by every platform plane for authorization input, provenance, audit and eventing, without planes re-deriving their own identity semantics
- Deterministic external-identity resolution that survives IdP migration, renames and directory changes, with collisions failing closed rather than silently merging identities
- Auditable delegation and impersonation, so asynchronous workflows (Design 1.27), ingestion (1.28) and AI/agent execution (1.30) can distinguish initiator from executor without losing accountability
- Service-to-service and workload authentication without shared static credentials, reducing a major class of lateral-movement risk

**Requires:**
- Implementation of the canonical Principal domain model, lifecycle, and stable-identifier scheme (Phase 1) before any dependent plane can rely on canonical principal IDs
- An identity-provider abstraction and issuer/subject mapping store with deterministic, fail-closed linking (Phase 2)
- Validated authentication context, assurance levels and identity propagation, integrated with Design 1.23 authorization (Phases 3, 7)
- Service/workload identity issuance with short-lived credentials (Phase 4), and delegation/actor-chain support wired through Design 1.27's outbox and event model (Phases 5–6)
- Coordination with Design 1.32 so the public identity-management API it defines stays aligned with the semantic model owned here, rather than the two planes diverging on principal/tenant/delegation semantics

**Trade-offs:**
- Significant upfront modeling work (8 phases, three vertical slices) before any dependent plane can assume canonical identity is available; no implementation exists yet
- The API-ownership split between 1.29 (semantics) and 1.32 (public surface) requires ongoing cross-design coordination to avoid semantic drift between the two documents
- Workload and connector identity provisioning strategy (short-lived credential issuance, attestation) is left implementation-defined pending platform infrastructure decisions

## Implementation Status

Not started — architecture accepted, no implementation exists yet.
