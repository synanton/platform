# ADR-008: Search and Retrieval Plane

**Status:** Accepted
**Date:** 2026-09-07
**Deciders:** Architecture team
**Design Document:** [synanton-design-1.31.md](../synanton-design-1.31.md)

## Context

Designs 1.23 (security/classification), 1.25 (knowledge/derived-state) and 1.27 (eventing/workflow) established the platform's authorization model, its canonical semantic knowledge model, and the durable execution fabric for asynchronous, event-driven work. The platform still lacked an architectural contract for **search**: how canonical knowledge is projected into lexical, vector and graph indexes; how those projections are kept current, rebuilt and migrated without downtime; how retrieval, fusion, ranking and reranking are separated and governed; and — most importantly — how search enforces the Design 1.23 authorization model rather than inventing its own.

A cross-plane architecture review of Designs 1.26–1.33 (`docs/architecture/proposals/synanton-architecture-review-resolution.md`, GitHub Issue #39) specifically flagged a **search authorization risk**: a retrieval system that ranks first and filters for authorization afterward risks exposing unauthorized content through the ranked set itself, or through metadata side channels (result counts, facets, autocomplete, highlighting, suggestions, explanations) even when the ranked documents themselves are correctly withheld. The review's resolution records that Design 1.31 closes this risk by making security a **candidate-eligibility prerequisite** — enforced before ranking, not as a filter on ranked output — and by explicitly protecting the metadata side channels enumerated above. The review also established that Eventing (1.27) is the common execution fabric that later planes, including Search, must rely on for asynchronous work (index updates, invalidation, rebuild/migration workflows) rather than each plane inventing its own async primitives, and that Platform API (1.32) compatibility CI covers this plane's public contract.

Review of the proposal (`docs/architecture/proposals/v1.31/`) found it internally consistent, free of citation-tool export artifacts or escaped-Markdown defects, and already largely explicit about the eligibility-before-ranking principle (§29–§33, §60, invariants 8–9, 22–25). One residual ambiguity was identified and clarified when folding: the query-processing pipeline (§40) and the reference search pipeline (§157) each show a security stage both *before* candidate generation/ranking (establishing eligible scope) and *after* ranking/reranking (an "Authorization / Security Validation" step before result construction). Read in isolation, the second stage could be mistaken for the primary enforcement point — a post-filter on ranked results, which is exactly the risk the review flagged. A short clarifying note was added at both diagrams stating that the pre-ranking stage is the primary enforcement point and the post-ranking stage is a defense-in-depth check on an already-eligible set; the diagrams and all other technical content were left unchanged.

## Decision

Accept and fold in the v1.31 proposal, which establishes the **Search and Retrieval Plane** as a derived, non-authoritative retrieval system over canonical knowledge:

- **Search indexes are derived projections, never authoritative knowledge** — lexical, dense-vector, sparse-vector and graph projections of Design 1.25 knowledge, individually versioned via projection generations, rebuildable and migratable without loss of canonical state (§6–§14, §71–§74)
- **Security as a candidate-eligibility prerequisite, not a ranking signal or post-filter** — authorization constraints are pushed into candidate generation wherever the backend supports it, and reranking never sees a candidate that has not already cleared eligibility (§29–§33, §60); the fold adds a short clarification at §40 and §157 that the post-ranking "Security Validation" stage is defense-in-depth, not the primary enforcement point
- **Metadata side-channel protection** — result counts, facet counts, highlights, autocomplete, query suggestions, ranking scores and explanations must not reveal the existence of unauthorized content (§33, §49–§51, §54–§57)
- **Candidate generation separated from ranking**, with Reciprocal Rank Fusion as the initial hybrid fusion strategy and bounded, budget-aware reranking on the fused candidate set (§25–§28, §58–§60)
- **Query planning and a backend-independent query model** — a Search Query Model / Backend Adapter boundary that prevents native backend query syntax (and therefore injection) from reaching domain code, with capability-advertising backends (§41–§44, §110–§112, §127–§130)
- **Eventing-driven, idempotent, version-aware index lifecycle** — incremental updates, explicit delete/tombstone handling, out-of-order-event safety, and atomic generation activation for zero-downtime rebuild/migration, coordinated through Design 1.27 workflows rather than a bespoke async mechanism (§66–§74, §140–§148)
- **Graceful degradation and workload isolation** — defined degraded modes (LEXICAL_ONLY, NO_RERANK, REDUCED_K, etc.), interactive-query budgets, and resource isolation between interactive search and index-build/benchmark workloads (§62–§65, §104–§107, §136)
- **Reproducible evaluation and safe rollout** — versioned benchmark datasets, Recall/NDCG/latency metrics, shadow search, canary rollout and configuration rollback, integrating with the existing SNTP-9 Retrieval Evaluation Benchmark rather than a parallel pipeline (§84–§91, §143–§147, §180–§181)
- 25 architectural invariants (§191) and an 8-phase implementation plan (§189), with Phase 7 dedicated specifically to security hardening and negative-case testing before production evaluation

## Consequences

**Enables:**
- A search implementation that can change embedding models, rerankers, fusion strategies, and even the underlying search/vector engine without ever becoming a second knowledge database or reprocessing canonical content
- Confidence that ranking quality experiments (shadow, canary, A/B) cannot regress authorization behavior, because eligibility is established upstream of ranking and is explicitly required to remain equivalent across experiment arms
- Retrieval-augmented generation that can safely hand AI Runtime (1.30) only already-authorized evidence, with provenance intact, rather than filtering after LLM invocation
- Zero-downtime index rebuild and migration via projection generations and atomic activation, with canonical knowledge as the disaster-recovery source of truth

**Requires:**
- Implementation of the projection/generation lifecycle (build, validate, activate, retire) and idempotent, version-aware projection consumers driven by Design 1.27 eventing
- A Search Query Model / Backend Adapter layer so no backend-native query syntax or vendor semantics leaks into domain code
- Security-negative test coverage (Phase 7) proving that unauthorized candidates and metadata side channels are not observable through any tested search surface, including facets, autocomplete, explanations and reranking
- Query, cache and cursor keys that include all security-relevant context (tenant, principal/authorization context, configuration generation) so a cached or paginated response can never cross a security boundary

**Trade-offs:**
- The plane depends on Design 1.27 (eventing/workflow) and Design 1.30 (AI Runtime, for embeddings/reranking) as execution dependencies before semantic and hybrid retrieval can be delivered; Phase 1–2 (contract + lexical baseline) can ship independently, but later phases cannot
- Index-time vs. query-time security filtering is left as a deployment choice (§37) rather than mandated, meaning some backend/tenant combinations may rely on query-time filtering with more careful side-channel review than an index-time-isolated deployment would require
- Exact SLOs, query limits and degraded-mode thresholds are explicitly left workload-driven (§103, §130, §139) rather than fixed by this architecture, deferring those numbers to benchmark and production data

## Implementation Status

Not started — architecture accepted, no implementation exists yet.
