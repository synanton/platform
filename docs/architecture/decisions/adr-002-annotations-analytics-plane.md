# ADR-002: Annotations, Derived Knowledge, Recalculation and Analytics/Reporting Plane

**Status:** Accepted
**Date:** 2026-09-01
**Deciders:** Architecture team
**Design Document:** [synanton-design-1.25.md](../synanton-design-1.25.md)

## Context

Design 1.23 made classification and representation first-class at chunk granularity, but the platform still lacked a first-class model for **interpretation** of that content: annotations (tags, classifications, entities, attributes, signals) existed only as ad hoc outputs of rules, models and LLMs, with no versioned identity, no explicit dependency graph, and no controlled way to recalculate them when a rule, model, dictionary, source or policy changed.

Separately, the platform had no durable, security-consistent way to measure its own knowledge state and activity: annotation quality, processing throughput, search behavior, cost, and security posture were not observable as governed, tenant-isolated, replayable data.

Two proposals — tracked at the proposal stage as `synanton-design-1.24` (annotation/derived-knowledge/recalculation) and `synanton-design-1.25` (analytics/reporting) — were developed together and consolidated into a single proposal (`docs/architecture/proposals/v1.24-1.25/`) because analytics is defined as observing the same annotation and recalculation model 1.24 introduces, and both must inherit the Design 1.23 security and representation contract without weakening it.

Review of the consolidated proposal found it internally consistent and correctly inheriting the Design 1.23 security model (`resource_acl ∧ class_grants`, chunk-level classification, Single/Dual/Masked-only representations, `store_original: false`, fail-closed defaults, query-side sanitization, `test:security`). Two cosmetic defects — leftover citation-tool artifacts and escaped Markdown syntax carried over from document export — were cleaned up when folding the proposal into this architecture document; no substantive content changed.

## Decision

Accept and fold in the v1.24/1.25 consolidated architecture, which introduces:

- **Annotation as first-class knowledge** — semantic identity (not producer alone), versioned annotation definitions, explicit dependency DAGs distinct from taxonomy, processing runs and provenance (§6–§14)
- **Resolutor** — deterministic impact analysis for a given dependency graph and change set (§49)
- **Equalix** — priority/resource-controlled execution of recalculation and other background workloads, so background maintenance never starves incremental/interactive work (§50, §62)
- **An Analytics and Reporting Plane** downstream of the protected knowledge layer: analytics events emitted only after classification/masking (§24), analytical facts/aggregates/metrics/reports with inherited and propagated classification (§25–§29), an Analytics Registry governing metric/report lifecycle (§71–§73)
- **Explicit tenant/system scope separation**, with `tenant_id = system` reserved, access-restricted, and excluded from tenant-scoped APIs and aggregates (§46)
- **Aggregate side-channel protection** (minimum group size, suppression, rounding, restricted dimensions) as a centrally registered, tenant-strengthenable policy (§28–§29, §70)
- **A replaceable analytics storage contract**, with ClickHouse as the initial implementation candidate and `analytics_events` as the durable, replayable rebuild boundary (§38–§40, §77)
- 15 architectural invariants (§89) and an 8-phase implementation plan (§90), with workload-dependent values (ingestion targets, retention, topology) explicitly deferred to production validation rather than guessed (Appendix D)

## Consequences

**Enables:**
- Independently addressable, versioned, explainable annotations that can be selectively recalculated instead of requiring full reprocessing
- Direct comparison between annotation definition versions for model/rule evaluation and regression analysis
- Platform and business measurement (processing, annotation, search, security, cost) without analytics ever becoming an alternate source of truth or a security side channel
- Deterministic replay/rebuild of all derived analytical state from a durable event boundary, independent of the underlying analytics database

**Requires:**
- Implementation of annotation identity, definition versioning, dependency graph storage and provenance tracking
- Building Resolutor (impact analysis) and Equalix (controlled execution) as new platform components
- An analytics event boundary strictly downstream of the Design 1.23 classification/masking decision, with a ClickHouse adapter behind the storage contract
- An Analytics Registry with metric/report lifecycle (Draft → Validated → Published → Deprecated → Retired) and centrally governed aggregate protection policies
- An `analytics-security` CI tier extending `test:security` (tenant isolation, classification propagation, aggregate suppression, MCP authorization, platform-scope isolation)

**Trade-offs:**
- Significant new surface area (8 implementation phases) before any analytics capability ships; Phase 1 (annotation foundation) has not started
- Production sizing for ClickHouse (ingestion targets, replication, retention) is intentionally left open pending workload modelling and a PoC — not resolved by this architecture decision
- Historical/current security semantics for reclassification (§54) require the more complex `valid_from`/`valid_to` + current-policy model rather than simple invalidation, to satisfy audit/compliance needs

## Implementation Status

Not started. This ADR records architectural acceptance only; see [synanton-design-1.25.md](../synanton-design-1.25.md) §90 (Implementation Phases) and Appendix F (Implementation Readiness — "Conditional" for Performance and Production Adoption pending PoC, load testing, and security validation).
