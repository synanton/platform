# ADR-009: Platform API and Contract Architecture

**Status:** Accepted
**Date:** 2026-09-07
**Deciders:** Architecture team
**Design Document:** [synanton-design-1.32.md](../synanton-design-1.32.md)

## Context

Synanton is composed of multiple independently evolving planes — Identity (1.29), Security (1.23), Ingestion (1.28), Content Cache (1.26), Knowledge/Annotations (1.25), Search (1.31), AI Runtime (1.30), and Eventing/Workflow (1.27). Prior to this design, no single document defined the stable external contract through which applications, clients, automation, and external systems interact with the platform. Without such a boundary, applications risk depending directly on internal service topology (storage engines, brokers, workflow engines, GPU scheduling), which would make replacing or evolving any one plane a breaking change for every integrator.

The cumulative Architecture Review across Designs 1.26–1.33 (dated 2026-09-06, GitHub Issue #39; see `docs/architecture/proposals/synanton-architecture-review-resolution.md`) flagged two risks specific to this plane, both resolved by this design:

- **Identity API deferred** — it was unclear whether the public API surface for tenant/principal/delegation management was defined anywhere, or whether it was implicitly assumed to live inside Design 1.29. Resolution: 1.32 explicitly defines the identity-management API surface (§126, tying to the Tenant/Principal resources of §7 and Appendix A), while 1.29 remains the semantic authority for the underlying principal, tenant, and delegation model.
- **Async semantics duplicated across planes** — it was unclear whether the public `Operation` resource (§55) and Design 1.27's internal event/command/workflow semantics were the same contract described twice, which would create drift risk. Resolution: 1.27 owns internal event, command, and workflow execution; 1.32 owns only the external, durable Operation/API contract a client observes. These are distinct layers, not duplicates (§55, §85–§87, §163).

Review of the proposal (`docs/architecture/proposals/v1.32/Synanton Design 1.32 Proposal Platform API.md`) found it internally consistent, already well-aligned with the Design 1.23 security baseline (authentication/authorization separation, tenant-context trust boundary, security-context-aware pagination, IDOR/authorization-error handling) and with the Platform 1.0 invariants (`synanton-platform-1.0-proposal.md` §10, invariants #24–#29). No citation-tool export artifacts, escaped Markdown, or broken anchors were found in the source proposal, so no cosmetic cleanup beyond the header and the two clarifying notes below was required when folding it into this architecture document; no substantive content changed.

## Decision

Accept and fold in the v1.32 architecture, which establishes:

- **Platform API as the stable external contract boundary** — resource-oriented, versioned (`/v1/...`), and independent of internal service topology, storage technology, brokers, or workflow engines (§1–§20, invariants #1–#2, #28–#30)
- **A resource model** covering Tenant, Principal, Source, Source Version, Ingestion Run, Artifact, Knowledge Object, Semantic Chunk, Annotation, Search, Processing Run, Workflow, Operation, and AI Execution, with stable, implementation-independent identifiers (§7–§9)
- **Identity and security integration without duplication** — authentication follows Design 1.29, authorization follows Design 1.23, tenant scope is derived from trusted context rather than client-supplied parameters, and cross-tenant operations are explicit and privileged (§15–§21, §126)
- **A unified asynchronous Operation contract** (§55–§60) that is durable across process/instance/worker failure (§141), distinct from Resource state (§137), and explicitly layered above — not duplicating — Design 1.27's internal event/command/workflow execution (§85–§87, §163)
- **Idempotency, pagination, optimistic concurrency, and bulk-operation semantics** as first-class contract concerns (§22–§26, §66–§76), with pagination tokens bound to security context (invariant #21)
- **A consistent error model** that never exposes internal exceptions, backend query syntax, or GPU/storage internals (§61–§64, §116–§118, invariants #13–#16)
- **Contract governance** — OpenAPI as a first-class contract artifact, compatibility CI, consumer-driven and cross-version (current-server/previous-client) contract testing, and a defined deprecation lifecycle (§91–§99, §156–§157, invariant #25–#27)
- **A security threat model and CI tier** covering tenant isolation, IDOR, authentication/authorization bypass, SSRF/callback safety, file-upload attacks, and webhook spoofing (§119–§125, §158–§162)
- 30 architectural invariants (§180) and a 7-phase implementation plan (§178) with explicit acceptance criteria (§179)

## Consequences

**Enables:**
- Applications, SDKs, and external integrators can build against a single stable contract while every plane underneath (Identity, Search, AI Runtime, Ingestion, Eventing, storage/broker technology) evolves independently
- Long-running work (ingestion, AI execution, reindexing, export) gets a single, durable, pollable Operation contract instead of each plane inventing its own async pattern
- Safe client retries under network failure, via Idempotency-Key plus operation lookup, without risking duplicate side effects
- Machine-checkable prevention of breaking changes reaching clients, via OpenAPI-based compatibility CI and cross-version contract testing
- A single place to reason about the API attack surface (tenant isolation, IDOR, SSRF, injection) independent of which backend plane ultimately serves a request

**Requires:**
- Building the reference component architecture (§154): api-contract, authentication/authorization adapters, tenant-context, resource-services, operation-service, error-mapper, pagination, idempotency, and per-plane adapters (search/ai/ingestion/content/provenance/event/workflow)
- A durable operation-state store that survives process, API-instance, and worker failure (§141), independent of the underlying workflow engine
- OpenAPI governance and compatibility CI (§92, §157) as a merge gate — this is the "1.32 compatibility CI is mandatory" resolution from the cross-plane review
- A security CI tier (§158) covering authentication bypass, tenant isolation, IDOR, malformed/oversized input, replay, idempotency abuse, and rate-limit bypass
- Coordination with Design 1.29 (identity model), Design 1.23 (authorization), Design 1.27 (internal execution), Design 1.28 (ingestion), Design 1.30 (AI Runtime), and Design 1.31 (Search) as each of those planes exposes its capabilities through this contract, rather than directly

**Trade-offs:**
- Significant new surface area (7 implementation phases) before any capability is externally consumable through a stable contract; Phase 1 (API foundation) has not started
- The Platform API is deliberately slower-moving than internal planes (§176), which means new internal capabilities are not automatically externally visible until explicitly contract-designed (§177) — this is an intentional constraint, not a gap
- Preview (`/v1beta/...`) and administrative/expert API boundaries (§99, §173–§175) still need to be operationally defined; this design establishes the principle but not the full governance tooling

## Implementation Status

Not started — architecture accepted, no implementation exists yet.
