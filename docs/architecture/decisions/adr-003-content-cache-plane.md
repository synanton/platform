# ADR-003: Content Cache Plane

**Status:** Accepted
**Date:** 2026-09-07
**Deciders:** Architecture team
**Design Document:** [synanton-design-1.26.md](../synanton-design-1.26.md)

## Context

Design 1.25 established that knowledge and analytics are derived state, but neither it nor Design 1.23 defined how the intermediate artifacts produced by content extraction — flattened text, semantic content, semantic chunks, extraction metadata and provenance — should be durably staged, retrieved, filtered and lifecycle-managed before they reach knowledge processing, search projections, and benchmarking (SNTP-9 / Issue #14).

Without an explicit abstraction, extractors, indexers and benchmarks were becoming directly coupled to a single storage technology (Cassandra), which made storage migration expensive, made large-payload handling implementation-specific, and made retention/tiering inconsistent across consumers.

A proposal (`docs/architecture/proposals/v1.26/`) was developed to define the Content Cache as an implementation-independent architectural contract sitting between the Content Extraction Plane and downstream Knowledge Processing (1.25) and Search Projections, inheriting the Design 1.23 security model without weakening it.

The proposal was reviewed as part of the cumulative cross-plane architecture review (2026-09-06, GitHub Issue #39) covering Designs 1.26–1.33 (see `docs/architecture/proposals/synanton-architecture-review-resolution.md`). That review's finding for this plane was that capability claims were not consistently tied to conformance evidence: the proposal's conformance suite (§61.3) stated that an implementation "MUST NOT advertise support for an optional capability unless the corresponding capability-specific tests pass," but that statement was scoped under Full Conformance rather than applying uniformly to every advertised optional capability regardless of the conformance level claimed. This was resolved when folding the proposal into this architecture document by adding an explicit, generalized conformance-evidence requirement (design doc §24), and by cross-referencing it from the Full Conformance section.

Review of the proposal also found it internally consistent and correctly inheriting the Design 1.23 security model (tenant isolation, authorization, classification-aware search, fail-closed defaults, masked/original representation separation, break-glass audit) and the Design 1.25 derived-state model. One structural cosmetic defect (an inconsistent heading level on the Executive Summary) was corrected, and a short clarifying note was added distinguishing this plane's plane-local asynchronous-operation tracking model (§42) from the cross-plane eventing/workflow substrate that Design 1.27 is establishing, since the platform-wide architectural outcome of the review assigns asynchronous coordination authority to 1.27. No other substantive content changed.

## Decision

Accept and fold in the v1.26 proposal, which introduces:

- **The Content Cache as an architectural abstraction, not a database** — a stable contract (`get`, `get_many`, `put`, `put_many`, `get_document`, `get_chunks`, `search`, `invalidate`, `delete`, `get_capabilities`, `get_operation`) that Cassandra, PostgreSQL, S3-compatible object storage, filesystem, or hybrid metadata+object implementations may satisfy without changing consumer code (§4–§10, §50–§51)
- **Atomic publication** as a core invariant — a cache entry is never observable as published before its payload and metadata are complete and verified (§11, §39, Invariant 2)
- **Content identity vs. cache-entry identity**, with a documented logical identity (tenant/document/version/representation/processing-run) and provenance sufficient to trace every derived artifact back to its source, extractor and processing run (§8–§9)
- **Large-content safety** through inline, object-reference, and chunked payload forms, so no implementation may require a full document to fit in a single physical record (§25–§26, Invariant 7)
- **Explicit tier and retention lifecycle** — hot/warm/cold as logical tiers distinct from retention expiration and legal hold, with production implementations required to provide an enforceable expiration mechanism independent of consumer behavior (§27–§33, §31 Retention Policy Enforcement, Invariants 10–12)
- **Security inheritance from Design 1.23** — mandatory tenant isolation, fail-closed authorization, classification as a non-authorization-substitute filter, masked/original representation separation, and audited break-glass administrative access (§3, §34–§38)
- **Cache Search as metadata filtering, explicitly separated from semantic/relevance search** performed by downstream Search Projections (§17–§21, Invariant 8)
- **Capability discovery and conformance tiers (Core / Extended / Full)**, with the fold adding an explicit, generalized rule that any optional capability advertised via `get_capabilities()` must be backed by passing conformance tests regardless of the implementation's overall claimed conformance level (§22–§24, §61–§62, Invariants 15)
- **A phased, incremental migration path off direct Cassandra coupling** for existing benchmark/prototype components (§57, §66–§67)
- 16 architectural invariants (§65) and a 10-phase implementation plan (§67), with performance/throughput targets explicitly left as deployment-profile targets pending real-workload validation (§43–§45)

## Consequences

**Enables:**

- Extraction, indexing, and benchmark (SNTP-9 / Issue #14) consumers depending on one stable contract instead of a specific database, so the underlying storage technology can be replaced without architectural change
- Deterministic, paginated, security-aware retrieval and metadata search that cannot be mistaken for a semantic search engine
- Reproducible RAG benchmarking (flattened text vs. semantic content) because both representations are served through the same cache contract, controlling for infrastructure differences
- Independent evolution of hot/warm/cold storage tiering and retention enforcement behind a stable logical interface
- A clear, testable boundary for which optional capabilities a given implementation may claim in production

**Requires:**

- Implementation of the Content Cache contract itself (Phase 1) before any adapter work can proceed
- A Cassandra adapter migration path (Phases 2–3) for existing directly-coupled benchmark/indexer components
- Large-payload support (inline / object-reference / chunked) and a lifecycle subsystem (retention, tiering, legal hold, expiration enforcement) as first-class implementation work, not an afterthought (Phases 4–5)
- A conformance test suite (Core / Extended / Full) that must exist and pass before any implementation may advertise the corresponding capabilities in production (Phase 9)
- Coordination with Design 1.27 for any Content Cache operation that needs to participate in cross-plane asynchronous workflows, since the plane-local `submit_operation`/`get_operation` model introduced here is not a substitute for the eventing/workflow substrate

**Trade-offs:**

- Significant new surface area (10 implementation phases) before any storage-independent capability ships; no implementation exists yet
- Production sizing (throughput, batch performance, latency targets in §43–§45) is intentionally left open pending real-workload benchmarking, not resolved by this architecture decision
- The recommended initial implementation (metadata store + object storage, e.g., PostgreSQL/Cassandra + S3-compatible storage) requires operating two storage systems rather than one, in exchange for storage independence and large-content safety

## Implementation Status

Not started — architecture accepted, no implementation exists yet.
