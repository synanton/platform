# ADR-011: Synanton Platform Architecture 1.0 (Consolidation of Designs 1.22–1.33)

**Status:** Accepted
**Date:** 2026-09-07
**Deciders:** Architecture team
**Design Document:** [synanton-platform-architecture-1.0.md](../synanton-platform-architecture-1.0.md)

## Context

By 2026-09-06, eight proposal-stage design documents existed for planes 1.26 (Content Cache), 1.27 (Eventing and Workflow), 1.28 (Ingestion), 1.29 (Identity), 1.30 (AI/Model Runtime), 1.31 (Search), 1.32 (Platform API) and 1.33 (Kubernetes Operator Readiness), sitting on top of the already-accepted baseline of 1.22 (base architecture), 1.23 (normative security) and 1.25 (knowledge, incl. 1.24). SNTP-14 / GitHub Issue #39 asked for the design series to be completed and for a single Synanton Platform Architecture v1.0 document to be produced showing how all planes fit together.

A cumulative architecture review (2026-09-06) was run across the eight proposals before acceptance and found them internally coherent but identified eleven cross-plane findings, recorded in `docs/architecture/proposals/synanton-architecture-review-resolution.md`. The most significant finding — raised explicitly by the requesting stakeholder — was that Design 1.27 (Eventing and Workflow) needed to be established as the platform's common asynchronous execution fabric ahead of 1.28–1.31, because each of those four planes depends on shared event delivery, retry, idempotency and ordering semantics; without that dependency being made explicit, each plane risked inventing its own execution semantics independently. The other ten findings covered: the identity-management API surface being owned by 1.32 rather than 1.29; operator naming standardization (`content-extractor-operator`, not `content_retrieval`); Kubernetes CRD/domain-contract leakage; the AI Platform/GPU Runtime execution boundary; search authorization/candidate-eligibility and metadata side-channel risk; SourceIdentity vs. content-digest separation in ingestion; analytics remaining asynchronous derived observation rather than becoming an operational authority; avoiding duplicated async semantics between 1.27 (internal) and 1.32 (external Operation contract); and requiring conformance evidence for advertised optional capabilities.

All eleven findings were resolved at the proposal level before folding, and each of the eight plane design documents (1.26–1.33) was folded into its accepted form under `docs/architecture/synanton-design-1.2X.md` with the corresponding resolution applied — see ADR-003 (1.26) through ADR-010 (1.33) for the per-plane decisions. This ADR covers only the capstone integration document, `synanton-platform-architecture-1.0.md`, which was previously drafted at the proposal stage (`docs/architecture/proposals/synanton-platform-1.0-proposal.md`) and is now promoted to accepted status with corrected cross-references to the final design filenames.

## Decision

Accept `synanton-platform-architecture-1.0.md` as the definitive cumulative architecture document for the Synanton Platform, integrating Designs 1.22, 1.23, 1.25, and 1.26–1.33. The document:

- Establishes 1.22 as the base architecture, 1.23 as the normative security baseline, and 1.25 as the knowledge extension (consolidating 1.24).
- Establishes 1.27 as the common execution/eventing fabric that 1.28, 1.29, 1.30 and 1.31 depend on, explicit about the fact that this is a dependency relationship, not a peer relationship implied by adjacent version numbers.
- States 42 normative invariants spanning security, data/knowledge, communication, API, AI/search and Kubernetes concerns, carried over unchanged from the accepted proposal draft.
- Defines the end-to-end lifecycle, the identity/security actor chain, the AI Runtime and Search authority boundaries, the Platform API resource surface, and the Kubernetes lifecycle envelope.
- Records the current implementation status per plane (§14) so that accepting the architecture is not mistaken for authorizing implementation to begin out of sequence.
- Recommends an implementation sequence that freezes the Platform API (1.32) and the eventing/workflow contract (1.27) before any vertical-slice implementation begins.

## Consequences

**Enables:**
- A single, cross-referenced entry point for understanding how all eleven design documents (1.22, 1.23, 1.25, 1.26–1.33) fit together, replacing the need to reconstruct the dependency graph from eight independent proposals.
- A documented rationale for why 1.27 must be treated as upstream of 1.28–1.31 despite version-number adjacency, preventing future implementers from building async semantics into those planes independently.
- A single record of which cross-plane review findings were resolved and where (per-plane ADRs 003–010), so the resolution isn't only implicit in the proposal text.

**Requires:**
- Every future plane-level design change to keep this document's Document Hierarchy (§3), Normative Invariants (§4) and Implementation Sequence (§13) in sync, or explicitly supersede them.
- Treating this document as non-authoritative for any single plane's internal detail — per its header, the plane design document governs and this document should be corrected if it drifts.

**Trade-offs:**
- This document does not itself add new technical content; it is integration and cross-reference work. All plane-level architectural risk is inherited from Designs 1.26–1.33 and their respective ADRs (003–010).
- The Implementation Status table (§14) will go stale quickly given none of 1.26–1.33 has started implementation; it must be revisited at the start of each implementation phase rather than trusted as current indefinitely.

## Implementation Status

Not started. This is an architecture-acceptance milestone, not an implementation milestone. Per §13–§14 of the design document, the first implementation steps are freezing 1.32 (Platform API) and 1.27 (Eventing/Workflow), followed by the vertical slice `Ingestion → Content Cache → Eventing → Knowledge → Search`. As of this ADR, Design 1.22 is in production use, 1.23 is in progress, 1.25 has partial implementation (AAP-1/AAP-2), and 1.26–1.33 have no implementation.
