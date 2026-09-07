# Synanton Platform Architecture 1.0

**Status:** Architecture Baseline  
**Version:** 1.0  
**Date:** 2026-09-07  
**Purpose:** Consolidated architecture for Synanton Platform, prepared for SNTP-14 / GitHub Issue #39.

> **Issue:** SNTP-14 Synanton Platform Architecture 1.x — complete the design series and create the Synanton Platform architecture document v1.0.

## 1. Executive Summary

Synanton Platform 1.0 consolidates the architecture developed through Designs 1.22–1.33.

Design 1.22 remains the **base architecture document**. Design 1.23 is the **normative security and representation baseline**. Design 1.24 is not published independently; its annotation/derived-knowledge/recalculation content is consolidated into Design 1.25.

Designs 1.26–1.33 extend the base architecture with explicit content-cache, eventing/workflow, ingestion, identity, AI runtime, search, API and Kubernetes lifecycle contracts.

The resulting architecture is:

```text
                          SYNANTON PLATFORM 1.0
┌───────────────────────────────────────────────────────────────────────┐
│                         External Applications                         │
│                              │                                        │
│                         Platform API 1.32                             │
└──────────────────────────────┬────────────────────────────────────────┘
                               │
        ┌──────────────────────┼────────────────────────┐
        ▼                      ▼                        ▼
   Identity 1.29         Search 1.31             AI Runtime 1.30
        │                      │                        │
        └───────────────┬──────┴───────────────┬────────┘
                        ▼                      ▼
                 Knowledge 1.25          Eventing 1.27
                        │                      │
                        ▼                      ▼
                 Content Cache 1.26      Workflow / Equalix
                        ▲                      │
                        │                      │
                  Ingestion 1.28 ◄────────────┘
                        ▲
                        │
                 External Sources

             Analytics / Reporting are derived
             from knowledge and platform events.

             Kubernetes lifecycle surrounds these
             planes through Design 1.33.
```

## 2. Architectural Thesis

Synanton separates:

> **What entered the platform → what Synanton understands → how knowledge is projected → who may access it → how computation is executed → how changes are coordinated → how knowledge is measured → how infrastructure is operated.**

The central authority rule is:

```text
Source state        → Ingestion 1.28
Content artifacts   → Content Cache 1.26
Canonical knowledge→ Knowledge 1.25
Identity            → Identity 1.29
Authorization       → Security 1.23
AI execution        → AI Runtime 1.30
Search projections  → Search 1.31
Async coordination → Eventing/Workflow 1.27
External contract  → Platform API 1.32
Lifecycle           → Kubernetes operators 1.33
Analytics           → derived observation
```

## 3. Document Hierarchy

| Level | Document | Role |
|---|---|---|
| Base | 1.22 | Platform architecture overview and accumulated platform behavior |
| Normative security | 1.23 | Authentication/authorization/classification/masking/tenant/security baseline |
| Knowledge extension | 1.25 | Annotation, derived knowledge, recalculation, analytics/reporting; includes 1.24 |
| Content | 1.26 | Content artifact contract |
| Communication/execution | 1.27 | Events, commands, workflows, retries, recovery |
| Acquisition | 1.28 | Source identity, versioning, synchronization, deletion |
| Trust/control | 1.29 | Principal, federation, tenant membership, delegation |
| AI execution | 1.30 | Model execution contract |
| Retrieval | 1.31 | Lexical/vector/hybrid/graph retrieval and ranking |
| External contract | 1.32 | Stable Platform API and compatibility |
| Deployment lifecycle | 1.33 | Kubernetes readiness and independent operators |

## 4. Normative Invariants

### Security

1. Design 1.23 is normative for security.
2. Authentication never implies authorization.
3. Classification is not authorization.
4. Masking is a representation transformation.
5. Security context cannot be weakened across asynchronous boundaries.
6. Tenant scope is explicit and trusted only after validation.
7. Fail-closed behavior applies to ambiguous security state.
8. Security-sensitive caches/projections are invalidated or reprocessed when assumptions change.

### Data and Knowledge

9. Source identity is distinct from content digest.
10. Canonical knowledge remains authoritative.
11. Search, cache and analytics are derived state.
12. Provenance is mandatory for derived state.
13. Processing Runs record substantial derived processing.
14. Dependencies form an explicit model suitable for impact analysis.
15. Resolutor determines affected derived state.
16. Equalix coordinates controlled recalculation.

### Communication

17. Events are immutable facts.
18. Commands express intent.
19. At-least-once delivery is the default.
20. Consumers are idempotent.
21. Large payloads are referenced.
22. Workflow state is durable.
23. Replay is explicitly classified.

### API

24. Platform API 1.32 is the stable external boundary.
25. Internal storage, broker, workflow and runtime topology are not public contracts.
26. Long-running acceptance means durable Operation state.
27. Resource and Operation state remain distinct.
28. Pagination is security-context aware.
29. Breaking changes require explicit versioning.

### AI and Search

30. Platform owns AI execution semantics; runtime owns execution mechanics.
31. Model version and artifact digest are provenance.
32. Search indexes are derived projections.
33. Security is a prerequisite for search candidate eligibility.
34. Search ranking cannot override authorization.
35. Projection generations are reproducible.
36. Out-of-order events cannot regress search state.

### Kubernetes

37. Domain contracts do not require Kubernetes.
38. Operators manage lifecycle, not business logic.
39. Each independently operated infrastructure plane has one lifecycle authority.
40. CRDs are lifecycle/control-plane resources, not public domain APIs.
41. GPU infrastructure is physically isolated from the Main Platform.
42. Operator reconciliation is idempotent and convergent.

## 5. End-to-End Lifecycle

```text
External Source
   │
   ▼
Ingestion 1.28
   │  SourceIdentity / SourceVersion / provenance
   ▼
Content Cache 1.26
   │
   ▼
Extraction
   │
   ▼
Semantic Content / Chunks
   │
   ▼
Knowledge 1.25
   │
   ├── Annotation
   ├── Derived Knowledge
   ├── Processing Runs
   ├── Dependency DAG
   └── Resolutor / Equalix
   │
   ├──────────────► Search Projections 1.31
   │
   ├──────────────► AI Runtime 1.30
   │
   └──────────────► Analytics
                         │
                         ▼
                 Facts / Aggregates / Metrics
```

Eventing 1.27 connects state changes and workflows across the pipeline.

Identity 1.29 supplies trusted principal/tenant context.

Security 1.23 applies throughout the pipeline.

Platform API 1.32 exposes stable external capabilities.

Design 1.33 defines the future Kubernetes lifecycle envelope.

## 6. Eventing and Workflow Position

Design 1.27 is deliberately the connective execution fabric.

```text
Events       = durable facts
Commands     = requests for work
Workflows    = stateful coordination
Processing Run = record of processing
Provenance   = explanation of derived state
Equalix      = controlled recalculation execution
```

This prevents 1.28–1.31 from inventing independent asynchronous semantics.

## 7. Identity and Security Position

```text
Identity 1.29
    │
    ▼
trusted principal
    │
    ▼
tenant membership / delegation
    │
    ▼
Security 1.23 authorization
    │
    ▼
resource / representation eligibility
```

The same actor chain is propagated into asynchronous operations, AI executions, ingestion and administrative operations.

## 8. AI Runtime Boundary

```text
Platform 1.30
  WHAT / WHY / SECURITY / PROVENANCE
              │
              ▼
Runtime implementation
  HOW / SCHEDULING / HARDWARE
              │
              ▼
GPU / CPU / remote inference
```

The initial GPU Runtime implementation is a separate project and must conform to the Platform-owned contract.

## 9. Search Boundary

Search consumes canonical knowledge and produces derived retrieval results.

```text
Knowledge
   ├── lexical projection
   ├── vector projection
   └── graph projection
             │
             ▼
        Search 1.31
             │
      security-aware
      candidate generation
             │
             ▼
          ranking
```

No search backend becomes authoritative.

## 10. Platform API

The external contract is resource-oriented and versioned.

Initial resources:

```text
tenants
principals
sources
source versions
ingestion runs
artifacts/content
knowledge/chunks/annotations
search
AI executions
workflows
operations
exports
```

OpenAPI/protobuf contracts and compatibility CI are part of the implementation baseline.

## 11. Kubernetes Lifecycle

Kubernetes is an optional deployment substrate.

The planned lifecycle projects are:

```text
synanton-platform-operator
content-extractor-operator
gpu-runtime-operator
```

All operators are Go projects.

There is no requirement for a monolithic operator.

Before implementation, each service must pass the 1.33 Kubernetes compatibility review.

## 12. Compatibility and Conformance

The 1.0 implementation baseline requires:

- contract tests;
- security negative tests;
- idempotency/retry tests;
- event schema compatibility;
- API compatibility CI;
- ingestion/source lifecycle tests;
- content cache conformance;
- search benchmark/evaluation;
- AI runtime conformance;
- operator compatibility review.

## 13. Implementation Sequence

The architecture recommends this order:

1. **Finalize 1.32** and freeze common API/resource/error/Operation contracts.
2. **Freeze 1.27** as the common asynchronous contract.
3. Implement the first vertical slice:
   `Ingestion → Content Cache → Eventing → Knowledge → Search`.
4. Add Identity/security enforcement across the slice.
5. Add AI Runtime contract and GPU Runtime conformance.
6. Add analytics as an asynchronous derived consumer.
7. Execute the Kubernetes compatibility review.
8. Implement independent Go operators after contracts are validated.
9. Run cross-plane integration, failure, recovery and security testing.

## 14. Acceptance Criteria for Platform 1.0

Platform 1.0 is architecturally complete when:

- [ ] 1.22 remains the base architecture.
- [ ] 1.23 is explicitly normative.
- [ ] 1.24 is explicitly consolidated into 1.25.
- [ ] 1.26–1.33 have consistent cross-references and authority boundaries.
- [ ] 1.27 provides common event/workflow semantics.
- [ ] 1.28 defines source identity/version/deletion.
- [ ] 1.29 defines canonical principals and actor chains.
- [ ] 1.30 defines Platform-owned AI execution semantics.
- [ ] 1.31 defines derived retrieval/search.
- [ ] 1.32 defines the stable external API and identity-management API.
- [ ] 1.33 defines Kubernetes readiness without Kubernetes leakage.
- [ ] Cross-plane security tests pass.
- [ ] Cross-plane idempotency/recovery tests pass.
- [ ] Compatibility CI is operational.
- [ ] First end-to-end vertical slice is reproducible.

## 15. Final Thesis

Synanton Platform 1.0 is not a collection of independent services.

It is a set of explicit architectural contracts with clear authority boundaries:

> **Ingestion establishes source truth. Knowledge establishes derived meaning. Identity establishes who acts. Security establishes what may be revealed. Eventing coordinates change. AI Runtime executes computation. Search makes knowledge discoverable. Analytics measures derived state. The Platform API exposes stable capabilities. Kubernetes manages infrastructure lifecycle without becoming part of the domain model.**

The architecture therefore remains:

**security-first, provenance-aware, event-driven, recalculable, storage-independent, runtime-independent, search-derived, API-stable and deployment-independent.**
