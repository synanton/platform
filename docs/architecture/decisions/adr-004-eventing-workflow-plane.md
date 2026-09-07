# ADR-004: Eventing and Workflow Plane

**Status:** Accepted
**Date:** 2026-09-07
**Deciders:** Architecture team
**Design Document:** [synanton-design-1.27.md](../synanton-design-1.27.md)

## Context

Synanton's architectural planes — Content Cache (1.26), Knowledge (1.25), and the planes then still at proposal stage (Ingestion 1.28, Identity 1.29, AI Runtime 1.30, Search 1.31) — all need durable asynchronous communication: events that record facts, commands that request work, and workflows that coordinate multi-step processing that cannot be expressed as a single request/response call. Design 1.25 already introduced Processing Runs, Resolutor and Equalix as the domain model for dependency-aware recalculation, but it deliberately left the underlying communication and orchestration mechanism to a separate design.

A cross-plane architecture review of Designs 1.26–1.33 (dated 2026-09-06, tracked as GitHub Issue #39; see `docs/architecture/proposals/synanton-architecture-review-resolution.md`) identified this as the most consequential open finding in the series: **"Eventing should precede later planes."** The review's resolution states it directly:

> 1.27 is explicitly the common execution fabric and is placed before 1.28–1.31 in the dependency model. Without this, each of Ingestion (1.28), Identity (1.29), AI Runtime (1.30), and Search (1.31) risks inventing its own independent async/event/retry/idempotency semantics instead of using one shared contract.

Design 1.27's version number sits between 1.26 and 1.28 for series continuity, but its *architectural* role is that of foundational infrastructure: a shared events/commands/retry/idempotency/workflow contract that every downstream-of-ingestion plane must build on rather than reinvent. The `synanton-platform-1.0-proposal.md` integration document independently reaches the same conclusion (§6, "Eventing and Workflow Position"): Design 1.27 "is deliberately the connective execution fabric," and this "prevents 1.28–1.31 from inventing independent asynchronous semantics." Folding 1.27 ahead of, and with explicit precedence over, 1.28–1.31 is therefore required before those later planes can be folded without each having to separately justify its own async model.

Review of the proposal found it internally consistent and correctly building on the Design 1.23 security baseline (security context, tenant scope, and audit must survive asynchronous boundaries) and the Design 1.25 knowledge/derived-state model (Resolutor, Equalix, Processing Runs, provenance), without re-deriving or duplicating either. One cosmetic defect — leftover citation-tool export artifacts (stray `id="..."` attributes on fenced code blocks, carried over from document export tooling) — was cleaned up when folding the proposal into this architecture document; no substantive content changed. A short "Role in the Design Series" framing note and reinforcing language in the Executive Summary were added per the review resolution above to make the fabric-before-1.28–1.31 relationship explicit in the document itself, since this was the review's central call-out for this plane.

## Decision

Accept and fold in the v1.27 proposal, which introduces:

- **A normative Event/Command/Workflow distinction** — events communicate durable, immutable facts; commands request work; workflows coordinate stateful multi-step execution (§1, §6, §19)
- **At-least-once delivery as the default reliability model**, with idempotent consumption required so that at-least-once delivery plus idempotent processing yields effectively-once business behavior (§6.4–6.5, §23–§25)
- **Transactional outbox and inbox patterns** for reliable publication and deduplicated consumption, so domain state changes and event publication cannot silently diverge (§26–§30)
- **Explicit retry, dead-letter, poison-event and backpressure semantics**, with error classification (`TRANSIENT`, `PERMANENT`, `SECURITY`, `INVALID`, `DEPENDENCY`, `TIMEOUT`, `RESOURCE`) governing what may be retried (§31–§36)
- **A durable Workflow model** — orchestration for long-running business processes, choreography for independent reactions, explicit compensation classes (`REVERSIBLE`, `COMPENSATABLE`, `INVALIDATABLE`, `IRREVERSIBLE`), mandatory timeouts, cooperative cancellation, and crash-safe recovery from durable state rather than process memory (§40–§51)
- **Security and tenant propagation across asynchronous boundaries** — security context, classification, and tenant scope must survive event/command transport, and "internal event" must never be treated as "trusted event" (§14–§15, §65)
- **Correlation, causation and trace-context propagation** for auditable, reconstructible event lineage across commands, events, workflows, and processing runs (§61–§64)
- **Broker/workflow-engine technology independence** — a logical `EventPublisher`/`EventConsumer`/`EventSubscription`/`EventStore` interface, with an initial PostgreSQL-backed implementation recommended and a dedicated broker deferred until measured requirements justify it (§98–§100)
- 18 architectural invariants (§103) and a 6-phase implementation plan with 3 recommended vertical slices (§104–§107)
- Explicit positioning as the platform's common execution fabric, placed ahead of Designs 1.28 (Ingestion), 1.29 (Identity), 1.30 (AI Runtime) and 1.31 (Search) in the dependency model, per the cross-plane architecture review resolution

## Consequences

**Enables:**
- Designs 1.28–1.31 (and any future plane requiring asynchronous work) can integrate with one shared event/command/workflow contract instead of each defining independent queues, retries, correlation schemes, and workflow state machines
- Durable, auditable cross-plane coordination (e.g. ingestion → extraction → classification → annotation → indexing) without holding synchronous connections open across long-running or multi-service operations
- Controlled recalculation (Equalix) and AI Runtime execution can run as workflow participants without the generic Workflow Plane duplicating Equalix's domain-specific dependency-analysis logic
- Deterministic failure recovery: workflow and event state are durable, so process or engine restart does not lose in-flight coordination state

**Requires:**
- A transactional outbox implementation for every state-changing producer that publishes events
- An inbox/deduplication mechanism for every consumer requiring idempotent processing
- A durable workflow state store (initial recommendation: PostgreSQL) supporting crash-safe recovery, timeout, and cancellation
- Schema governance for event types (owner, version, compatibility rule, replay classification) before those event types are introduced
- Retrofitting or aligning Designs 1.28, 1.29, 1.30 and 1.31 to this contract rather than to independently designed async mechanisms, as those proposals are folded

**Trade-offs:**
- No broker or workflow engine technology is mandated by this architecture; the initial PostgreSQL-only recommendation defers the decoupling and throughput benefits of a dedicated broker until measured need is demonstrated, which means early implementations carry migration risk if that need arrives sooner than expected
- The event/command/workflow distinction is normative but requires discipline from every producing team (e.g. correct past-tense event naming, correct outbox usage) that is not mechanically enforced by this document alone
- Positioning 1.27 ahead of 1.28–1.31 in the dependency model means those designs cannot be considered architecturally complete, independent of this document, until their own folds explicitly reconcile with the semantics defined here

## Implementation Status

Not started — architecture accepted, no implementation exists yet.
