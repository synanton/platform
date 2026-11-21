# Proposal: TrustGraph Evaluation Against Synanton's Current Contract Framework

**Issue:** [synanton/platform#789](https://github.com/synanton/platform/issues/789)  
**Review date:** 2026-11-21  
**Status:** revised proposal for review — evaluation only; no production dependency or technology adoption approved  
**Scope:** assess TrustGraph as an external knowledge-graph / GraphRAG platform and reference architecture against Synanton's current contract framework

> **Status of the framework.** The current `docs/architecture/external-engines-and-stable-contracts.md` is marked **Draft for review — no new technology adoption is approved by this document**. This proposal uses its vocabulary and principles as the intended evaluation framework; it does not treat any technology disposition as an approved architecture decision. In particular, OPA is a candidate to evaluate, not Synanton's approved authorization engine.

> **Evidence note.** TrustGraph capability descriptions below preserve the source proposal's review-date findings and should be rechecked against the exact TrustGraph release/documentation before any future prototype or adoption decision. Synanton contract references are grounded in the current draft framework, especially §§3.3, 3.5–3.7, 4.3–4.6, 6.3, and 7. This proposal is not a substitute for a version-pinned technical benchmark.

## 1. Executive recommendation

**Do not integrate TrustGraph as a runtime or introduce any of its platform dependencies into the current vertical slice.** TrustGraph is a substantial knowledge-graph / GraphRAG platform rather than a small library or drop-in service. Its graph, retrieval, orchestration, and deployment assumptions overlap with capabilities Synanton already owns or is stabilizing.

The useful outcomes are narrower:

1. **PROV-O:** retain TrustGraph's use of PROV-O named graphs as an interoperability reference for extraction lineage and retrieval traces. Synanton continues to own its assertion-level provenance model.
2. **OPA:** evaluate OPA separately as a candidate policy-decision engine for trusted pre-retrieval authorization. This is an evaluation question from Synanton's contract framework, not a TrustGraph integration recommendation.
3. **Ontology-guided extraction:** defer as a possible RAKG research question. It is not part of the current P1 evaluation list.
4. **Guided traversal and cross-encoder reranking:** defer as possible improvements to existing Relix/Synquest capabilities. They are not current P1 commitments.

No TrustGraph service, graph store, messaging/workflow dependency, or replacement of RAKG, Syntology, Synquest, Relix, Resolutor, or Equalix is proposed.

## 2. TrustGraph capability review

### 2.1 Architecture and deployment

TrustGraph describes itself as a context-orchestration platform combining Graph RAG, Ontology RAG, and Document RAG. The reviewed architecture is modular and service-oriented, with an event backbone and separate graph, vector, object-storage, and observability components. The original assessment identified Pulsar, Cassandra, Qdrant, Garage, and Grafana/Loki in the reviewed deployment profile, with Compose/Podman, Minikube, and Kubernetes deployment paths.

This is a platform-scale operational footprint, not an embeddable dependency. Exact component requirements are release-sensitive and must be checked against the pinned version before any renewed assessment. The current recommendation remains not to introduce a second graph, retrieval, messaging, or workflow stack.

### 2.2 Ingestion and extraction

The reviewed TrustGraph pipeline uses Docling for document decoding, then chunks content and uses LLM-based extraction to produce entities and relationships. Ontology RAG adds ontology-guided entity and relationship discovery, with domain/range and subclass constraints used to check candidate triples.

The Docling decoder is separable in principle and overlaps with a candidate already named in the current framework's P1 list. Any Docling comparison belongs in that bounded P1 evaluation against Synanton's existing structured-content extraction path—not in a TrustGraph runtime integration.

Ontology-guided extraction is a potentially useful research question for RAKG, but it is **Defer** under this proposal. It must not be treated as a current P1 evaluation or prototype commitment. Promotion to Evaluate requires a named RAKG gap, an explicit owner, a baseline, and a bounded benchmark with acceptance and stop criteria.

### 2.3 Ontology and semantic modeling

The reviewed documentation describes OWL ontology import and constraint checks over extracted triples, and states that TrustGraph is not a full OWL reasoner. These capabilities do not amount to Synanton's ontology governance, release lifecycle, or Syntology validation responsibilities.

**Proposed disposition: Not selected as a Syntology replacement.** TrustGraph's extraction-time ontology constraints may remain an external architectural observation. Synanton retains ontology ownership, pinned releases/imports, validation profiles, and promotion policy. The current framework's §3.3 explicitly distinguishes SHACL validation from truth and authorization.

### 2.4 Entity and relation identity

The reviewed model describes collection-scoped graph entities and RDF triples. The proposal did not find documented semantics equivalent to Synanton's governed cross-source canonical identity, resolution decisions, or merge/split lifecycle.

**Proposed disposition: Not selected as a canonical identity or resolution authority.** Any future adapter would have to emit candidate mentions/relations for Synanton's resolution and governance flow; it must not create or promote Synanton canonical entities.

### 2.5 Graph storage and query

The reviewed TrustGraph material describes multiple graph-store backends and SPARQL-oriented query interfaces. Synanton already has a graph-storage abstraction with Neo4j as its current implementation and graph/query responsibilities in Relix.

**Proposed disposition: Not selected as a second production graph engine.** The current framework's §3.6 says to stabilize existing graph and retrieval abstractions rather than add a second graph engine without a demonstrated limitation. TrustGraph's SPARQL interface and neighbourhood-query patterns may inform design discussion, but they do not justify importing its storage or query runtime.

### 2.6 Retrieval and GraphRAG

The reviewed TrustGraph capabilities include graph traversal, ontology-guided retrieval, hybrid lexical/vector retrieval, cross-encoder reranking, seed-based traversal, and per-hop constraints.

Synquest already has BM25 + dense retrieval with RRF fusion. Relix provides graph reasoning and navigation. Cross-encoder reranking and guided traversal therefore describe **possible future improvements to existing Synanton capabilities**, not replacements or approved integrations.

**Proposed disposition: Existing** for Synanton's current BM25 + dense + RRF baseline; **Defer** cross-encoder reranking and guided traversal. A future move from Defer to Evaluate requires a named quality or product gap, a baseline against the current implementation, a bounded benchmark, and a named owner. Any traversal evaluation must demonstrate **generation-consistent reads**, not merely correct traversal over a static graph.

### 2.7 Provenance and evidence

The reviewed TrustGraph design separates knowledge data, extraction provenance, and retrieval traces using named graphs, including PROV-O for provenance.

This is the strongest interoperability reference in the assessment. Synanton already owns durable provenance from source revision and content span through extracted mention, resolution decision, canonical assertion, validation, projection, and retrieval evidence.

**Proposed disposition: Adopt standard as an interoperability reference only.** PROV-O can inform exchange and mapping of processing/provenance events; it does not replace Synanton's assertion-level provenance, dependency graph, or lifecycle semantics. No named-graph storage redesign or TrustGraph import is approved by this proposal.

### 2.8 Workflow, retries, and incremental updates

The reviewed TrustGraph HyperFlows model chains processors and agent-oriented operations through its own orchestration and messaging assumptions. This differs from Synanton's knowledge-lifecycle model: Resolutor plans dependency-aware impact and recalculation; Equalix coordinates controlled execution under Synanton's task and lifecycle semantics.

**Proposed disposition: Not selected as a replacement workflow or recalculation runtime.** Event-driven decoupling may be a design observation, but no TrustGraph workflow or messaging dependency is proposed.

### 2.9 Authorization, tenant isolation, and data handling

The reviewed TrustGraph material describes workspaces, collections, Context Cores, JWT-derived context, and SHACL-AF-based node filtering with tri-state outcomes.

This capability must be described precisely:

- SHACL-AF can express policy-like filtering in TrustGraph's implementation.
- That does **not** make SHACL-AF an appropriate authorization authority for Synanton.
- Synanton's SHACL validation in Syntology and authorization are separate concerns. Validation is not authorization (§3.3); a policy decision is not enforcement (§4.3).
- Synanton's trusted query/retrieval path must enforce authorization before restricted evidence is supplied to downstream consumers, with fail-closed behavior for missing, stale, or ambiguous security context (§§3.7 and 4.6).

**Proposed disposition: Not selected as Synanton's authorization engine.** TrustGraph's SHACL-AF implementation is an external architectural observation only. It is not an adoption recommendation, and it must not be conflated with Syntology validation.

The separate OPA question is: **can OPA support node-level filtering and explainability inside Synanton's trusted query path using trusted principal, resource, action, purpose, and context attributes?** The current contract framework §3.7 identifies OPA as a candidate to evaluate, not an approved authorization engine. That evaluation belongs to the OPA workstream and must test enforcement location, negative authorization cases, trusted context propagation, and fail-closed behavior. It is not a TrustGraph integration.

### 2.10 Capability descriptors

TrustGraph's workspaces, collections, and Context Cores describe TrustGraph's own isolation and context model. They must not be treated as equivalents of Synanton's capability descriptors under §4.4.

If a future adapter is justified, it must publish **Synanton-owned, versioned capability descriptors** that state the actual supported operations, consistency, security, provenance, and degradation behavior. It must not simply expose or rename TrustGraph capability concepts. Unsupported requirements must fail explicitly or return a typed unsupported-capability result; an adapter must not silently weaken guarantees.

### 2.11 Operational, licensing, and maintenance posture

The source proposal assessed TrustGraph as Apache-2.0 licensed and described an active release cadence and broad integration surface. It also identified a substantial operational footprint, including additional graph/vector/storage and messaging components in the reviewed deployment.

These claims are time- and release-sensitive. They are useful screening inputs, not sufficient evidence for adoption. Any future evaluation must pin the TrustGraph release and dependencies, verify license obligations across transitive components, and measure deployment, upgrade, backup/restore, observability, security, and exit costs against a named use case.

## 3. Capability-to-contract disposition matrix

The statuses below are **proposed evaluation dispositions**, not approved technology decisions. They use the vocabulary in the current draft contract framework.

| Capability area | TrustGraph observation | Synanton-owned boundary | Proposed disposition | Constraint / rationale |
|---|---|---|---|---|
| Document decoding | Docling-based decoding in the reviewed pipeline | Existing structured-content extraction path | **Evaluate** only through the already-listed Docling P1 benchmark | Compare Docling with the current extractor; no TrustGraph runtime or sidecar commitment |
| Ontology-guided extraction | OWL-guided candidate extraction and constraint checks | RAKG extracted-mention flow; Syntology governance | **Defer** | Research question only; requires named gap, owner, baseline, bounded benchmark, and acceptance/stop criteria |
| Ontology governance and validation | Ontology import and extraction-time checks | Syntology / Jena + SHACL | **Not selected** as replacement | Does not replace Synanton ontology lifecycle, validation profile, or promotion policy |
| Entity/relation identity | Collection-scoped graph identity | Equalix and canonical graph identity | **Not selected** | No substitution for canonical identity, resolution decisions, or merge/split governance |
| Graph storage/query | Multiple graph stores and SPARQL interface | Existing graph abstraction / Neo4j / Relix | **Not selected** | No second graph engine absent a measured limitation and conformance proof |
| Retrieval baseline | Hybrid retrieval and GraphRAG modes | Synquest BM25 + dense + RRF; Relix | **Existing** | Preserve and improve current Synanton path |
| Cross-encoder reranking | Reranking in TrustGraph Document RAG | Synquest ranking contract | **Defer** | Future improvement; not part of current P1 list |
| Guided graph traversal | Seeded traversal and per-hop constraints | Relix graph operations and generation-aware reads | **Defer** | Future improvement; any evaluation must prove generation-consistent reads |
| Provenance | PROV-O and named-graph separation | Synanton assertion-level provenance and dependencies | **Adopt standard** as interoperability reference only | No replacement of Synanton provenance model or storage design |
| Workflow and incremental updates | HyperFlows and event-driven orchestration | Resolutor / Equalix | **Not selected** | Different authority and lifecycle model; no additional runtime |
| Authorization | SHACL-AF node filtering and TrustGraph context model | Synanton security model and trusted query path | **Not selected** as authorization authority | SHACL-AF is an external observation only; OPA is evaluated separately under §3.7 |
| OPA policy decision | Not a TrustGraph integration recommendation | Trusted pre-retrieval authorization | **Evaluate** separately under the existing P1 OPA candidate | Test trusted inputs, node-level filtering, explainability, enforcement point, negative cases, and fail-closed behavior |
| Capability negotiation | TrustGraph-native workspace/collection/context concepts | Synanton adapter capability descriptors | **Integrate selectively** only if a future adapter exists | Adapter must surface Synanton descriptors and typed degradation, not TrustGraph concepts |
| Messaging, storage, observability stack | TrustGraph platform services | Existing Synanton deployment and operations | **Not selected** | Avoid duplicate infrastructure without quantified benefit |

## 4. Compatibility assessment

### Data model and identity

TrustGraph's RDF substrate and named-graph model are compatible with standards-based exchange in principle. Its collection-scoped graph model does not directly map to Synanton's canonical identity and dependency-aware derived state. A mapping layer would be non-trivial and would not confer canonical authority on TrustGraph data.

### Provenance

PROV-O is a useful interoperability reference. Synanton remains responsible for the durable knowledge trace and for linking provenance to identity, validation, projections, retrieval evidence, and dependency-aware recalculation (§6.3). Generic job lineage or a retrieval trace alone is not a substitute.

### Security and policy

Synanton owns identity, tenant authority, policy composition, trusted context propagation, enforcement location, and fail-closed behavior. TrustGraph's SHACL-AF implementation is not an authorization recommendation for Synanton. OPA evaluation is separate and must be conducted against the current framework's security boundaries.

### Capability reporting

TrustGraph's feature inventory cannot be used as a proxy for Synanton adapter capabilities. Any future adapter must explicitly declare supported features and guarantees in Synanton's versioned descriptor (§4.4), including security, consistency, generation semantics, provenance, and unsupported operations.

## 5. Evaluation boundaries and priority

This proposal does not expand the P1 list in §7 of the current contract framework. That list names only:

1. Docling benchmark against the existing structured extraction pipeline.
2. ROBOT evaluation for ontology CI/release automation.
3. SSSOM import/export evaluation against mapping semantics and evidence.
4. Splink benchmark against the current Equalix resolution pipeline.
5. OPA adapter prototype against the existing security model and trusted authorization inputs.
6. OpenLineage event-emission evaluation for processing runs and data artifacts.

TrustGraph-specific ontology-guided extraction, cross-encoder reranking, and guided traversal are **Defer**, not additional P1 evaluations. Moving any one of them from Defer to Evaluate requires all of the following: a named gap, a named owner, a baseline, and a bounded benchmark with explicit acceptance and stop criteria. It must be separately justified and tracked; this issue does not authorize that scope.

For every P1 evaluation, record the fields enumerated in §7 exactly:

- **Owner**
- **Dataset**
- **Baseline**
- **Metrics**
- **Resource budget**
- **Security classification**
- **Acceptance threshold**
- **Stop condition**

Evaluation corpora must be isolated from production/customer indexes. Hold-out data must remain untouched until the agreed final evaluation. These are mandatory evaluation controls, not optional recommendations.

The vertical-slice stabilization work remains ahead of these future improvements. In particular, lifecycle status/recovery, generation-consistent reads, conformance/change control, maintenance end-to-end coverage, Neo4j restart behavior, RBAC audit, and the unified local demo retain priority. This TrustGraph document must not alter their implementation scope or sequencing.

## 6. Recommendation and no-go conditions

**Recommendation: accept this evaluation after revision, with no direct integration.**

The useful outcomes are limited to:

- PROV-O as an interoperability reference for provenance exchange;
- OPA evaluation as a separate candidate for trusted pre-retrieval authorization;
- ontology-guided extraction as a deferred RAKG research question;
- guided traversal and cross-encoder reranking as deferred improvements to existing Synanton capabilities.

Any future integration or experiment must remain behind Synanton-owned contracts and must not weaken identity, provenance, security, generation consistency, or capability reporting.

**No-go conditions:**

- Requires adding TrustGraph's graph store, messaging backbone, retrieval stack, or workflow runtime to Synanton.
- Requires replacing Syntology, canonical identity, Synquest/Relix ownership, Resolutor, or Equalix semantics.
- Treats TrustGraph workspaces, collections, or Context Cores as Synanton capability descriptors.
- Uses SHACL-AF as Synanton's authorization authority or moves enforcement outside the trusted query/retrieval path.
- Cannot preserve source/version identity, provenance, tenant scope, classification, and generation semantics.
- Fails adapter conformance or security tests.
- Shows no measurable benefit against a named baseline sufficient to justify total operational and maintenance cost.

## 7. Acceptance checklist for this revised deliverable

- [ ] Every capability row uses only **Existing**, **Adopt standard**, **Evaluate**, **Integrate selectively**, **Defer**, or **Not selected**.
- [ ] The current contract framework is identified as **Draft for review**; no technology disposition is described as approved.
- [ ] SHACL-AF is retained only as an external architectural observation, not recommended as Synanton authorization.
- [ ] OPA is described as a candidate to evaluate separately, not as an adopted authorization engine.
- [ ] The distinction between validation, policy decision, and enforcement is explicit, including trusted-path and fail-closed requirements.
- [ ] TrustGraph-native workspaces, collections, and Context Cores are not conflated with Synanton capability descriptors.
- [ ] Any future adapter is required to publish Synanton-owned capability descriptors and honest degradation behavior.
- [ ] PROV-O is an interoperability reference; Synanton retains assertion-level provenance and lifecycle authority.
- [ ] Ontology-guided extraction, cross-encoder reranking, and guided traversal are Defer, not current P1 work.
- [ ] Any move from Defer to Evaluate requires a named gap, owner, baseline, and bounded benchmark with acceptance/stop criteria.
- [ ] P1 evaluation plans enumerate Owner, Dataset, Baseline, Metrics, Resource budget, Security classification, Acceptance threshold, and Stop condition.
- [ ] Evaluation corpora are isolated from production/customer indexes and hold-out data remains untouched until final evaluation.
- [ ] Any traversal evaluation demonstrates generation-consistent reads.
- [ ] No implementation scope or vertical-slice priority is changed by this issue.

## 8. References

### Synanton contract framework

- [External Engines and Stable Contracts](../architecture/external-engines-and-stable-contracts.md) — current draft for review.
  - §3.3 — RDF and SHACL; validation is not authorization.
  - §3.5 — entity resolution candidate evaluation.
  - §3.6 — graph and retrieval stabilization.
  - §3.7 — OPA as an authorization candidate to evaluate.
  - §4.3 — candidates, decisions, canonical state, and enforcement.
  - §4.4 — capability negotiation and honest degradation.
  - §4.6 — security and data handling.
  - §6.3 — provenance and explainability.
  - §7 — P0/P1/P2 sequence and bounded evaluation requirements.

### TrustGraph source material

The original assessment's TrustGraph architecture and capability claims must be pinned to the exact release and official documentation before any future technical benchmark. The current document preserves the review-date findings as a screening assessment, not a verified compatibility certification.

---

**Review posture:** This document is an evaluation artifact only. It does not approve TrustGraph adoption, authorize a prototype, add a P1 item, or change Synanton's vertical-slice implementation scope.
