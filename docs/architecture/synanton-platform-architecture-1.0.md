# Synanton Platform Architecture 1.0

> **Document type:** Definitive engineering reference (cumulative/capstone)
> **Version:** 1.0
> **Document ID:** `synanton-platform-architecture-1.0`
> **Date:** 2026-09-07
> **Status:** Approved (architecture) — capstone document; implementation not started for 1.26–1.34, partial for 1.25 (see §15), furthest along for 1.22/1.23
> **Basis:** [SNTP-14 / GitHub Issue #39](https://github.com/synanton/platform/issues/39); promoted from [`docs/architecture/proposals/synanton-platform-1.0-proposal.md`](./proposals/synanton-platform-1.0-proposal.md); extended by SNTP-15 with Design 1.34 (Temporal Versioned Knowledge and Retrieval)
> **Audience:** Architects, module owners, security engineers, SREs, platform engineers, technical decision makers
> **Related docs:** [ADR-011](./decisions/adr-011-platform-architecture-1.0.md), [ADR-012](./decisions/adr-012-temporal-versioned-knowledge-retrieval.md), [Architecture Review Resolution](./proposals/synanton-architecture-review-resolution.md), and every design document listed in §3

> **Implementation principle:** This document does not introduce new architecture. It integrates and cross-references Designs 1.22–1.34, which remain the normative source for their respective planes. Where this document and a plane design document appear to disagree, the plane design document governs for its own domain and this document should be corrected.

---

## 1. Executive Summary

Synanton Platform 1.0 consolidates the architecture developed through Designs 1.22–1.34.

Design [1.22](./synanton-design-1.22.md) remains the **base architecture document**. Design [1.23](./synanton-design-1.23.md) is the **normative security and representation baseline**. Design 1.24 is not published independently; its annotation/derived-knowledge/recalculation content is consolidated into Design [1.25](./synanton-design-1.25.md).

Designs [1.26](./synanton-design-1.26.md)–[1.33](./synanton-design-1.33.md) extend the base architecture with explicit content-cache, eventing/workflow, ingestion, identity, AI runtime, search, API and Kubernetes lifecycle contracts.

Design [1.34](./synanton-design-1.34.md) adds a cross-plane **temporal versioning and point-in-time retrieval** capability — historical source versions, publication/observation/validity semantics, and temporal search eligibility — assigned to the existing owning planes (Ingestion 1.28, Knowledge 1.25, Search 1.31) rather than a new authority (see §10).

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
Canonical knowledge → Knowledge 1.25
Identity            → Identity 1.29
Authorization       → Security 1.23
AI execution        → AI Runtime 1.30
Search projections  → Search 1.31
Async coordination  → Eventing/Workflow 1.27
External contract   → Platform API 1.32
Lifecycle           → Kubernetes operators 1.33
Analytics           → derived observation
```

## 3. Document Hierarchy

| Level | Document | Role |
|---|---|---|
| Base | [1.22](./synanton-design-1.22.md) | Platform architecture overview and accumulated platform behavior |
| Normative security | [1.23](./synanton-design-1.23.md) | Authentication/authorization/classification/masking/tenant/security baseline |
| Knowledge extension | [1.25](./synanton-design-1.25.md) | Annotation, derived knowledge, recalculation, analytics/reporting; includes 1.24 |
| Content | [1.26](./synanton-design-1.26.md) | Content artifact contract |
| Communication/execution | [1.27](./synanton-design-1.27.md) | Events, commands, workflows, retries, recovery — the common execution fabric other planes depend on |
| Acquisition | [1.28](./synanton-design-1.28.md) | Source identity, versioning, synchronization, deletion |
| Trust/control | [1.29](./synanton-design-1.29.md) | Principal, federation, tenant membership, delegation |
| AI execution | [1.30](./synanton-design-1.30.md) | Model execution contract |
| Retrieval | [1.31](./synanton-design-1.31.md) | Lexical/vector/hybrid/graph retrieval and ranking |
| External contract | [1.32](./synanton-design-1.32.md) | Stable Platform API and compatibility |
| Deployment lifecycle | [1.33](./synanton-design-1.33.md) | Kubernetes readiness and independent operators |
| Cross-cutting temporal | [1.34](./synanton-design-1.34.md) | Temporal versioning and point-in-time retrieval; assigns responsibility across 1.28/1.25/1.31 rather than owning new state |

Note on ordering: version numbers are assigned chronologically by when each proposal was authored, not by architectural dependency. Design 1.27 (Eventing and Workflow) is a **dependency of** 1.28–1.31, not a peer that happens to sit between 1.26 and 1.28 — see §6 and §14.

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

### Temporal

Condensed from Design 1.34's full set of 29 invariants (see `synanton-design-1.34.md` §41 for the complete list); these are the ones with the broadest cross-plane consequence.

43. Version-series authority belongs to Ingestion 1.28; Knowledge 1.25 and Search 1.31 consume it rather than owning a competing copy.
44. A source version is immutable; corrections do not mutate it.
45. Publication time, observation time and validity time are distinct and never collapsed into one timestamp.
46. `current` may be a set of multiple independently eligible source versions, not one global version.
47. Overlapping or conflicting validity is retained and flagged, never silently resolved.
48. Corrections are classified as metadata-only or content-affecting before triggering any downstream work; only content-affecting corrections trigger Resolutor/Equalix recalculation.
49. Temporal filtering occurs before ranking, joining security eligibility as a pre-ranking search constraint (invariant 33).
50. Historical queries never silently fall back to current content; a missing or deleted historical state is returned as an explicit failure state.

## 5. End-to-End Lifecycle

```text
External Source
   │
   ▼
Ingestion 1.28
   │  SourceIdentity / SourceVersion / provenance
   │  VersionSeries (1.34): published_at / observed_at / valid_from / valid_to
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

This was the single most important finding of the cross-plane architecture review (2026-09-06, recorded in [`synanton-architecture-review-resolution.md`](./proposals/synanton-architecture-review-resolution.md)): eventing had to be established as the common substrate *before* Ingestion (1.28), Identity (1.29), AI Runtime (1.30) and Search (1.31) could be treated as settled, because each of those planes depends on shared delivery, retry, idempotency and ordering semantics that only 1.27 defines. Their version numbers (1.28–1.31) reflect authoring order, not dependency order; architecturally, 1.27 sits upstream of all four. Design [1.27](./synanton-design-1.27.md) is folded with this framing made explicit, and each of 1.28, 1.29, 1.30 and 1.31 cross-references 1.27 as the async substrate it relies on rather than defining its own.

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

Design 1.29 is the semantic authority for principal, tenant membership, federation and delegation. Design 1.32 owns the public identity-management **API surface** built on that model — the two are not duplicates: 1.29 defines what a principal/tenant/delegation *is*, 1.32 defines how a caller manages one over the network.

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

The initial GPU Runtime implementation is a separate project and must conform to the Platform-owned contract. Design [1.20](./archive/synanton-design-1.20.md) (GPU Execution Plane, archived — folded into the Design 1.22 baseline per `docs/architecture/INDEX.md`) is the prior physical-isolation document that this boundary formalizes: 1.20 established that GPU infrastructure is physically isolated from the Main Platform; 1.30 layers the model-execution contract (registry, versions, provenance, evaluation) on top of that isolation without absorbing runtime scheduling mechanics into the Platform's domain model.

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

No search backend becomes authoritative. Security filtering happens at candidate-generation time — before ranking — not as a post-filter on already-ranked results, and metadata side channels (facet counts, autocomplete, highlighting) are held to the same eligibility rule so they cannot leak the existence or content of material the requester is not authorized to see.

## 10. Temporal Versioning Boundary

Design [1.34](./synanton-design-1.34.md) adds cross-plane temporal semantics — point-in-time retrieval, version series, and correction/lifecycle handling — without introducing a competing ownership model. It assigns responsibility to the same planes that already own the relevant state:

```text
Ingestion 1.28
  owns VersionSeries / SourceVersion, published_at / observed_at / valid_from / valid_to,
  source lifecycle state, and authoritative source-version history
              │
              ▼
Knowledge 1.25
  owns derived knowledge; knowledge remains derived from a specific source version
              │
              ▼
Search 1.31
  owns temporal retrieval behavior; temporal eligibility joins security eligibility
  as a pre-ranking candidate constraint — never a post-ranking filter
```

Three temporal dimensions are kept distinct and never collapsed into one timestamp: `published_at` (when the source declared it), `observed_at` (when Synanton learned of it), and `valid_from`/`valid_to` (when it was applicable). `current` means the **set** of temporally eligible versions, not one global latest document — the same "no single global answer" discipline Design 1.31 already applies to search ranking (§9) and Design 1.25 applies to derived knowledge.

A correction to a source version is classified before it triggers any downstream work: a metadata-only correction (e.g. a corrected `valid_from`) updates the affected search projection in place; a content-affecting correction triggers the full Resolutor/Equalix recalculation path already established by Design 1.25. This keeps temporal corrections from becoming an unnecessary recalculation cost, and keeps recalculation as the single mechanism for correcting derived knowledge rather than adding a second one.

Legal hold and retention remain layered per plane (SourceVersion metadata → Content Cache 1.26 → derived knowledge 1.25 → search projections), consistent with each plane's existing retention/lifecycle ownership; Design 1.34 does not introduce a platform-wide retention authority.

## 11. Platform API

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

The public `Operation` resource (long-running acceptance/status) is the external contract for asynchronous work; it is distinct from — and does not duplicate — Design 1.27's internal event/command/workflow model. A single client-facing `Operation` may be backed by an arbitrarily complex internal workflow without that complexity becoming part of the public contract.

## 12. Kubernetes Lifecycle

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

CRDs are lifecycle/control-plane resources only; no Kubernetes object type appears inside a domain/business-logic contract in any plane document. The operator named `content-extractor-operator` is canonical; no `content_retrieval` operator is introduced anywhere in the design series.

## 13. Compatibility and Conformance

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
- operator compatibility review;
- temporal retrieval/correction tests (version accuracy, temporal recall, temporal contamination rate — see Design 1.34 §38).

Optional capabilities advertised by any plane (e.g. Content Cache 1.26 storage-backend capability flags) MUST be backed by that plane's own conformance test suite before being enabled in production — a capability claim without conformance evidence is not a supported claim.

## 14. Implementation Sequence

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

Steps 1–2 are listed first because they are the two contracts every other plane depends on (external contract and internal async contract, respectively); this is the same dependency reasoning behind placing 1.27 ahead of 1.28–1.31 in §6.

## 15. Current Implementation Status

This section reflects the state of the codebase at the time this document was accepted, not a target state.

| Design | Architecture status | Implementation status |
|---|---|---|
| 1.22 | Approved — base | Furthest along; platform baseline in production use |
| 1.23 | Approved — normative security | In progress |
| 1.25 (incl. 1.24) | Approved | Partial — AAP-1 (annotation foundation) and AAP-2 (Resolutor/Equalix recalculation) landed; AAP-3–AAP-8 not started (see [ADR-002](./decisions/adr-002-annotations-analytics-plane.md)) |
| 1.26 | Approved | Not started |
| 1.27 | Approved | Not started |
| 1.28 | Approved | Not started |
| 1.29 | Approved | Not started |
| 1.30 | Approved | Not started |
| 1.31 | Approved | Not started |
| 1.32 | Approved | Not started |
| 1.33 | Approved (contract/readiness review only) | No operator implementation exists; not in scope for this milestone |
| 1.34 | Approved | Not started; per Design 1.34 §39, begins with a 1.28 (Ingestion) extension and should not precede 1.27/1.28 implementation |

Accepting Designs 1.26–1.34 as architecture does not authorize skipping the implementation sequence in §14. In particular, no plane should begin implementation ahead of the 1.27 (eventing/workflow) and 1.32 (API/Operation) contracts being frozen, per the ordering rationale in §6.

## 16. Acceptance Criteria for Platform 1.0

Platform 1.0 is architecturally complete when:

- [x] 1.22 remains the base architecture.
- [x] 1.23 is explicitly normative.
- [x] 1.24 is explicitly consolidated into 1.25.
- [x] 1.26–1.33 have consistent cross-references and authority boundaries.
- [x] 1.27 provides common event/workflow semantics.
- [x] 1.28 defines source identity/version/deletion.
- [x] 1.29 defines canonical principals and actor chains.
- [x] 1.30 defines Platform-owned AI execution semantics.
- [x] 1.31 defines derived retrieval/search.
- [x] 1.32 defines the stable external API and identity-management API.
- [x] 1.33 defines Kubernetes readiness without Kubernetes leakage.
- [x] 1.34 defines cross-plane temporal versioning and point-in-time retrieval without a competing ownership model.
- [ ] Cross-plane security tests pass.
- [ ] Cross-plane idempotency/recovery tests pass.
- [ ] Compatibility CI is operational.
- [ ] First end-to-end vertical slice is reproducible.

The first twelve criteria are architecture-level and are satisfied by this document and Designs 1.22–1.34 as accepted. The remaining four are implementation-level and remain open — see §15; they are tracked as follow-on implementation work, not as blockers to accepting the architecture itself.

## 17. Final Thesis

Synanton Platform 1.0 is not a collection of independent services.

It is a set of explicit architectural contracts with clear authority boundaries:

> **Ingestion establishes source truth. Knowledge establishes derived meaning. Identity establishes who acts. Security establishes what may be revealed. Eventing coordinates change. AI Runtime executes computation. Search makes knowledge discoverable. Analytics measures derived state. Temporal versioning establishes when it was true. The Platform API exposes stable capabilities. Kubernetes manages infrastructure lifecycle without becoming part of the domain model.**

The architecture therefore remains:

**security-first, provenance-aware, event-driven, recalculable, storage-independent, runtime-independent, search-derived, temporally-aware, API-stable and deployment-independent.**
