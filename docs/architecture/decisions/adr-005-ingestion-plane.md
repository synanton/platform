# ADR-005: Ingestion Plane

**Status:** Accepted
**Date:** 2026-09-07
**Deciders:** Architecture team
**Design Document:** [synanton-design-1.28.md](../synanton-design-1.28.md)

## Context

Synanton had no dedicated architecture for bringing external content and source events into the platform. Source acquisition existed only as an implicit responsibility of individual connectors and extraction pipelines, with no common contract for source identity, deduplication, incremental synchronization, deletion semantics, security-context preservation, or provenance. This created concrete risks documented in the proposal (duplicate content, ambiguous source identity, uncontrolled retries, lost updates, security-context loss, connector-specific downstream behavior, and inability to replay ingestion or determine why content exists in Synanton).

The proposal (`docs/architecture/proposals/v1.28/`) was developed alongside Designs 1.26 (Content Cache), 1.27 (Eventing and Workflow), 1.29 (Identity), 1.30 (AI Runtime), 1.31 (Search), 1.32 (Platform API), and 1.33 (Kubernetes Lifecycle) as part of the SNTP-14 / GitHub Issue #39 design series. A cumulative cross-plane architecture review (`docs/architecture/proposals/synanton-architecture-review-resolution.md`) evaluated Designs 1.26–1.33 together and recorded two findings specific to this plane:

- **Source/digest ambiguity** — the review confirmed that Design 1.28 explicitly separates `SourceIdentity` (which logical source resource an artifact came from) from content digest (what the acquired bytes hash to). Review of the proposal found this separation already clean throughout (§10–§12, Appendix F): `SourceIdentity` is defined without a digest field, `SourceVersion` carries `content_digest` as a distinct sibling field, and §12 states as a normative rule that "digest equality must not automatically imply source-resource identity." No restructuring was needed; a short clarifying cross-reference was added at the top of §11 to make the §10/§11 relationship explicit at the point where digest is introduced.
- **Eventing should precede later planes** — Design 1.27 is the platform's common asynchronous execution fabric (events, commands, workflows, retries, idempotency, replay) and is placed before 1.28 in the dependency model. The proposal already used Design 1.27's mechanisms (§3.4, §44–§47) without redefining them, but the fold added an explicit normative statement in §3.4 and in the document header to remove any ambiguity that Design 1.28 relies on Design 1.27's async/retry/idempotency contract rather than inventing its own.

Folding the proposal into this architecture document changed the header block (status, document ID, related-design cross-references) and added the two short clarifying notes above. No other substantive content changed — the proposal contained no leftover citation-tool export artifacts, no escaped Markdown, and no broken anchors to clean up.

## Decision

Accept and fold in the Design 1.28 architecture, which establishes ingestion as a first-class Synanton plane responsible for:

- **Durable source identity** — `SourceIdentity` (`tenant_id` + `connector_id` + `source_id` + `resource_id`) as a stable identity independent of ingestion attempt, processing run, or workflow execution (§10, §36)
- **Content identity distinct from source identity** — SHA-256 content digest for byte-equivalence, storage deduplication, integrity verification, and change detection, explicitly not usable to merge independent source resources (§11–§12)
- **Immutable source versions** — a source resource's history as an append-only sequence of immutable versions, with atomic publication so a partially acquired artifact can never become authoritative (§9.3, §20, §72)
- **Idempotent, at-least-once ingestion** — deterministic idempotency identity (`tenant_id + source_id + resource_id + source_version`), duplicate-delivery convergence, and explicit retryable/non-retryable failure classification, built on Design 1.27's event/retry/idempotency contract rather than a competing one (§39–§43, §69)
- **Explicit deletion and reconciliation semantics** — a distinguished `DELETED`/`NOT_VISIBLE`/`NOT_FOUND`/`ACCESS_DENIED`/`SYNC_INCOMPLETE`/`UNKNOWN` taxonomy so incomplete synchronization can never be mistaken for authoritative deletion, plus first-class tombstones, reconciliation, and backfill as distinct operational capabilities (§16–§17, §60, §91–§92)
- **Security-context preservation without weakening** — source ACLs and classification are carried as provenance-aware metadata but never trusted as sufficient for Design 1.23 enforcement; tenant scope is mandatory on every ingestion record and cross-tenant access must fail closed (§23, §33–§35, §54, §116)
- **A clean extraction boundary** — ingestion publishes immutable source artifacts and emits a `SourcePublished` event; it never writes search indexes, owns annotations, or embeds AI Runtime execution semantics directly (§62–§63, §94, §97, invariants §131)
- 20 architectural invariants (§131) and an 8-phase implementation plan (§126) with three incrementally validating vertical slices (§127–§129)

## Consequences

**Enables:**
- A common ingestion contract so a new connector requires no change to annotation, search, knowledge, AI runtime, analytics, extraction, or workflow architecture, and vice versa
- Deterministic tracing from any knowledge object back through processing run, ingestion attempt, ingestion run, source version, and source resource to answer "why does this exist in Synanton?"
- Safe retry and duplicate delivery by construction, rather than by connector-specific defensive coding
- Incremental synchronization, reconciliation, and backfill as first-class, independently testable operational capabilities rather than incidental side effects of normal ingestion

**Requires:**
- Implementation of the core domain model (`Source`, `SourceResource`, `SourceVersion`, `IngestionRun`, `IngestionAttempt`) and its state machine (§121–§122, Phase 1)
- Streaming acquisition with atomic publication and SHA-256 verification before any artifact becomes authoritative (Phase 2)
- Content Cache integration (Design 1.26) so downstream extraction can consume an immutable source version without connector involvement (Phase 3)
- Transactional-outbox eventing integration with Design 1.27 so committed ingestion state reliably produces the corresponding event (Phase 4)
- A connector SPI and conformance test suite, with at least one production-quality and one reference connector passing it before connector breadth expands (Phase 5)
- Security integration with Design 1.23 (tenant enforcement, authorization context, ACL metadata, fail-closed cross-tenant tests) (Phase 7)
- Operational hardening — metrics, tracing, rate limiting, quotas, backpressure, quarantine, administrative operations, and chaos testing (Phase 8)

**Trade-offs:**
- Eight implementation phases and three vertical slices must land before the plane is production-ready; none have started
- Sustained throughput targets (resources/second, MB/s, synchronization lag) are intentionally left workload-dependent pending benchmark data rather than fixed in advance (§105–§106)
- The plane depends on Design 1.27's eventing/workflow contract and Design 1.26's Content Cache contract both being implemented for full end-to-end value; until then, ingestion's domain model and state machine can be built and tested in isolation but cannot be fully exercised end-to-end

## Implementation Status

Not started — architecture accepted, no implementation exists yet.
