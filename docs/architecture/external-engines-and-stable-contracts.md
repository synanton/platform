# External Engines and Stable Contracts

**Document type:** Architecture proposal / technology adoption guide  
**Status:** Draft for review — no new technology adoption is approved by this document  
**Date:** 2026-11-21  
**Scope:** synanton/platform  
**Audience:** Platform architects, module owners, implementers, security engineers, SRE

> **Decision principle:** Synanton should reuse mature engines, standards, and specialist tools for commodity capabilities. It should own the domain contracts, semantic lifecycle, correctness rules, provenance, dependency model, and cross-engine coordination that create the product.

This is a reviewable technology shortlist, not an implementation plan or a claim that every listed tool has been evaluated. Existing capabilities, architectural decisions, candidates, and open questions are deliberately distinguished. Production adoption still requires a focused ADR, compatibility tests, operational evaluation, and licensing/security review.

---

## 1. Executive position

Synanton should be a **composable knowledge-engineering and knowledge-operations platform**, not a replacement implementation for every parser, graph database, ontology editor, matching algorithm, policy engine, workflow runtime, or protocol.

The platform's differentiation is not the ability to call those tools. It is the ability to coordinate them while preserving:

- stable semantic identity and domain ownership;
- the distinction between extracted candidates and accepted knowledge;
- versioned ontology and mapping governance;
- source-to-assertion-to-answer provenance;
- tenant isolation, classification, and authorization;
- dependency-aware impact analysis and selective recalculation;
- reproducible processing and versioned projections;
- explicit partial-failure, retry, reconciliation, and supersession semantics.

The architecture already establishes important boundaries:

- Platform Architecture 1.0 assigns authority to the source, content, knowledge, workflow, graph, identity, authorization, AI, search, and analytics planes.
- Design 1.25 defines annotations, provenance, processing runs, dependencies, Resolutor, Equalix, and derived knowledge.
- Design 1.27 defines common event/command/workflow semantics; it does not mandate a particular broker or workflow engine.
- Design 1.23 is the normative security and representation baseline.
- Design 1.31 treats search as a derived retrieval projection.
- The graph-storage abstraction makes Neo4j the current implementation, not a domain-level dependency.
- The ontology-domain-mapping v0.3 baseline separates the stable semantic core from replaceable strategies and keeps candidates separate from governed mappings.

This proposal must be read consistently with those authorities. It does not supersede them.

## 2. Adoption vocabulary

| Status | Meaning |
|---|---|
| **Existing** | Already used by the current implementation or established as the current implementation choice. This does not imply every planned capability is complete. |
| **Adopt standard** | Reuse an interchange format, protocol, or event vocabulary. Synanton still owns its domain semantics and validation. |
| **Evaluate** | Candidate for a bounded proof of compatibility or benchmark. Not approved for production. |
| **Integrate selectively** | Use a bounded capability behind an adapter, while retaining Synanton authority over domain decisions. |
| **Defer** | Do not introduce until a concrete requirement and acceptance test justify it. |
| **Not selected** | Explicitly not part of the proposed architecture at this time. |

A tool being open-source, popular, or easy to integrate is not sufficient reason to adopt it. A candidate must remove meaningful commodity engineering without importing an incompatible data model, operational burden, or authority boundary.

## 3. Technology shortlist

| Capability | Existing / candidate technology | Proposed disposition | Synanton-owned boundary |
|---|---|---|---|
| Document parsing and layout extraction | Existing structured content extraction; evaluate Docling as an additional parser | **Existing + evaluate** | Parsed document, content blocks, tables, coordinates, source references |
| Ontology authoring | Protégé | **Integrate selectively** | Ontology source artifacts, ownership, release and review lifecycle |
| Ontology build and release | ROBOT | **Evaluate** for CI/release automation | Immutable ontology release, pinned imports, artifact digests |
| RDF graph model and SHACL | Apache Jena (already used in Syntology's Jena TDB2-based design/implementation); RDF4J only if a measured need appears | **Existing; do not add a second engine now** | Validation request/profile, report, shapes version, supported feature declaration |
| Ontology interchange | RDF, OWL 2 within a declared profile, SHACL Core, SKOS where semantics match, optional JSON-LD | **Adopt standards selectively** | Synanton semantic core and compatibility profile |
| Cross-ontology mapping interchange | SSSOM | **Evaluate / adopt format if compatible** | Mapping identity, direction, evidence, applicability, status, governance |
| Probabilistic entity matching | Splink; compare Zingg only if its workflow capabilities address a measured gap | **Evaluate** | Resolution candidate, decision, evidence, canonical identity and supersession |
| Graph storage | Neo4j current adapter; graph storage abstraction | **Existing** | Logical graph, projection identity, graph operations, capability declaration |
| Lexical / vector / hybrid retrieval | Existing Synquest BM25 + dense + RRF path | **Existing; improve before replacing** | Retrieval request, authorized evidence set, ranking trace, projection generation |
| Policy decision engine | Open Policy Agent (OPA) | **Evaluate** for policy decision evaluation | Principal/resource/action/context, policy version, decision and obligations |
| Relationship authorization | OpenFGA | **Defer / evaluate only if relationship tuples become a demonstrated need** | Relationship authorization semantics and principal/resource identifiers |
| Processing lineage interoperability | OpenLineage | **Adopt event vocabulary selectively** | Processing run, input/output artifact references, event correlation |
| Durable workflow runtime | Existing eventing/workflow contracts and Resolutor/Equalix responsibilities; Temporal only as a future candidate | **Existing + defer** | Domain dependency graph, recalculation plan, task semantics, durable outcome |
| Agent/tool protocol | Official Model Context Protocol (MCP) SDK for the implementation language | **Adopt SDK** | Synanton domain operations, authorization, stable resource/tool schemas |
| Analytics processing/storage | Existing analytics design; Flink/Spark/Iceberg remain separate technology choices | **Evaluate against workload**, not as default dependencies | Analytical event/fact contracts, lineage, tenant/security boundary |

### 3.1 Document parsing: Docling is an adapter candidate

The current structured-content extraction path should remain the authoritative integration point. Docling is worth benchmarking for formats or document features where it can reduce custom parsing work: layout, reading order, tables, OCR, and structured output.

Compare it against the current parser on the actual Synanton corpus:

- supported file types and malformed-file behavior;
- table structure and cell/span preservation;
- page, block, and character-offset fidelity;
- OCR language and quality coverage;
- repeatability where deterministic output is promised;
- throughput, memory, cold-start cost, and deployment footprint;
- preservation of source identity, tenant metadata, classification, and content references;
- licensing, model/runtime dependencies, and security posture.

The adapter must translate parser-specific structures into Synanton's content representation. Parser output must not become the identity authority for source versions, chunks, mentions, or assertions.

### 3.2 Ontology engineering: use standard artifacts and build tooling

Protégé can remain an authoring environment for ontology engineers. ROBOT is a candidate for repeatable ontology build, validation, and release automation. Neither should own the runtime registry or application lifecycle.

Synanton should own ontology identity and domain ownership; immutable release references and artifact digests; a pinned import closure; supported RDF/OWL/SHACL profiles; validation reports with validator and profile versions; and publication, deprecation, replacement, and compatibility policy.

The ontology registry API document is explicitly a draft for ADR review, not an accepted or implemented API. This proposal does not approve that API.

### 3.3 RDF and SHACL: keep the current Jena path

Jena is already part of the Syntology design/implementation history, including Jena TDB2. The first priority is to document and test the exact supported behavior of the current validator, rather than introduce another RDF engine.

The contract should declare:

- RDF serialization and profile;
- OWL profile, if any reasoning is performed;
- SHACL Core features supported;
- whether SHACL-SPARQL or SHACL-AF is supported (do not imply support without tests);
- inference regime and whether inference occurs before validation;
- shapes graph and data graph identity/digests;
- validator version and severity policy;
- deterministic report format and error taxonomy.

RDF4J is not a parallel adoption recommendation. Revisit it only if a concrete requirement cannot be met acceptably with Jena.

**Validation is not authorization.** SHACL conformance does not establish truth, completeness, OWL consistency, access rights, or permission to disclose a graph assertion.

### 3.4 Mapping: adopt interchange, keep governance in Synanton

SSSOM may provide a useful interchange representation for mappings between ontology resources. Check it against the Synanton mapping model before adoption, especially relation direction, applicability/context, evidence, negative mappings, provenance, and lifecycle status.

Equalix and the ontology mapping subsystem must not delegate canonical identity or promotion policy to an external matcher. Candidate generation and scoring may be replaceable; the meaning and governance of an accepted mapping are not.

The v0.3 ontology mapping baseline explicitly distinguishes mapping candidates from governed mappings. The proposed registry API and mapping subsystem remain subject to their own ADR decisions and implementation gates.

### 3.5 Entity resolution: benchmark Splink before integrating it

Splink is a candidate for probabilistic record linkage and pairwise/entity matching. Zingg may be considered if active-learning or human-review workflow is a material requirement. Do not adopt both by default.

Compare against Equalix's current candidate generation and decision flow. Measure candidate recall/precision at fixed review budgets; false-merge rate across tenant/domain boundaries; calibration and abstention; explainability of match features and evidence; missing, conflicting, and multilingual attributes; deterministic replay; incremental update cost; operational footprint; and license obligations.

The adapter should emit candidate evidence, not mutate canonical identity. Equalix/Synanton remains responsible for acceptance, rejection, review, merge/split history, and downstream recalculation.

### 3.6 Graph and retrieval: stabilize the existing abstractions

Neo4j remains the current graph implementation. The graph storage abstraction is the intended long-term boundary; it does not justify adding a second production graph engine now.

Synquest already has a BM25 + dense + RRF path. Improve evaluation, projection versioning, authorization consistency, graph-aware retrieval, and evidence traces before considering a replacement.

Any graph or search backend must preserve tenant scope, deterministic object identity, projection generation, idempotency, provenance references, and honest capability reporting. Backend-specific query languages and result objects must not leak into domain contracts.

### 3.7 Authorization: evaluate OPA only behind the normative security model

OPA is a candidate policy decision engine, not a replacement for Synanton's identity, tenant, classification, representation, or authorization model. It may evaluate policy-as-code decisions from trusted principal, resource, action, purpose, and context attributes.

OpenFGA should remain deferred until Synanton has a concrete relationship-authorization use case that cannot be served cleanly by the existing security model and a policy evaluator. If both are eventually used, their authorities and composition rules must be explicit; avoid two independently authoritative policy stores.

The enforcement point must be inside the trusted query/retrieval path. A policy result applied after restricted evidence has entered an LLM prompt is not access control. The system must fail closed on missing, stale, or ambiguous security context and must not trust caller-supplied tenant identifiers.

Current platform-wide enforcement is still tracked separately (including #339). This document does not claim a complete pre-retrieval policy filter or OPA integration exists.

### 3.8 Processing lineage: OpenLineage complements provenance

OpenLineage can provide interoperability for job/run/dataset events. It does not express the complete Synanton knowledge provenance model.

Synanton still needs assertion-level links such as:

**source version → content reference → chunk/span → extracted mention → resolution decision → canonical assertion → validation result → projection → retrieval evidence.**

Use OpenLineage where its job/run/dataset vocabulary helps integrate processing systems. Do not flatten domain provenance into generic lineage events or make an external lineage service authoritative for knowledge identity.

### 3.9 Workflow and recalculation: retain domain ownership

Design 1.27 defines event, command, retry, idempotency, and workflow semantics without mandating a broker or workflow engine. Resolutor owns dependency-aware impact analysis and recalculation planning; Equalix provides controlled execution/resource coordination under the current platform architecture.

Temporal is a possible future runtime candidate, not a default dependency. Evaluate it only if durable workflow requirements exceed the existing execution model and after defining how its workflow state maps to Synanton's authoritative task, event, lease, retry, fencing, and terminal-outcome contracts.

Do not move semantic dependency analysis into a generic workflow engine. Do not claim exactly-once external side effects merely because a workflow runtime supports durable replay.

### 3.10 MCP: adopt the SDK, keep domain logic outside the protocol

Use the official MCP SDK for the implementation language where practical. MCP should expose Synanton operations as tools/resources with stable schemas. It remains a thin protocol adapter over Synanton APIs.

The MCP layer must not own graph query semantics, ontology validation, identity resolution, authorization policy, or provenance. Every tool invocation must carry trusted principal/tenant context through the same authorization path as the corresponding API operation.

---

## 4. Stable contract principles

Every external engine must sit behind a Synanton-owned port or adapter. The contract is the compatibility boundary; the external product is an implementation detail.

### 4.1 Domain types, not vendor types

Public and cross-module contracts must not expose Jena/RDF4J objects, Neo4j nodes/sessions/Cypher records, Splink/Zingg dataframe structures, OPA-specific response objects, parser-specific document classes, or workflow-runtime-specific histories.

Adapters translate vendor types to versioned Synanton domain types. Vendor-specific configuration belongs in adapter configuration, not durable domain records.

### 4.2 Identity and versioning

Every result that can affect derived knowledge must identify the versions that influenced it. Depending on the operation, record:

- source identity and immutable source version;
- content artifact reference/digest and chunk identity;
- ontology release and pinned import-closure digest;
- shapes/rules and validation profile version;
- extraction, matching, embedding, reranking, and model artifact versions;
- adapter/engine name and version;
- relevant configuration digest;
- policy version and trusted principal context where applicable;
- projection identity and generation;
- processing run and correlation/causation identifiers.

Do not use timestamps as substitutes for immutable version identity. Do not silently reinterpret an old result after a model, rule, ontology, or configuration change.

### 4.3 Candidates, decisions, and canonical state are separate

- Parsed content is not a source version.
- An extracted mention is not a canonical entity.
- A candidate relation is not a canonical relation.
- A match score is not an identity decision.
- A mapping candidate is not a governed mapping.
- SHACL conformance is not truth or authorization.
- A search result is not authoritative knowledge.
- A policy decision is not enforcement until the trusted enforcement point applies it.

Promotion must be explicit, auditable, and idempotent. Rejected or ambiguous candidates must remain representable where required for review and reproducibility.

### 4.4 Capability negotiation and honest degradation

Each adapter should publish a versioned capability descriptor. Capabilities are explicit and testable, not inferred from product names.

Examples: supported input formats and offset fidelity; RDF/OWL/SHACL profile and inference behavior; transaction and consistency guarantees; graph query/traversal features; matching evidence types and deterministic replay class; policy input attributes and decision obligations; batch size, concurrency, and cancellation support; lineage event facets and schema versions.

If an engine lacks a required capability, the adapter must reject the operation or return a typed unsupported-capability result. It must not silently weaken validation, authorization, consistency, or provenance.

### 4.5 Reliability and operational behavior

Every adapter contract must define timeout/cancellation semantics; retryable versus terminal errors; idempotency key and duplicate behavior; partial-result semantics; concurrency and fencing where writes can race; backpressure and bounded resource use; health/readiness and capability discovery; metrics/logs/trace correlation; data retention/deletion; and recovery/reconciliation requirements.

Cross-store atomicity must never be implied. A partial commit must be observable and recoverable or surfaced as a contradiction.

### 4.6 Security and data handling

Adapters inherit Design 1.23 and the platform security model. They must preserve tenant scope, principal context, classification, representation policy, and audit metadata across synchronous and asynchronous boundaries.

Before an external service receives data, declare permitted data classifications; whether data may leave the deployment boundary; retention and training-use restrictions; tenant isolation; credential handling; encryption; audit; and deletion behavior.

The default for an undeclared or unsupported security capability is fail closed.

### 4.7 Observability and provenance

Each external execution should produce a processing-run record or equivalent trace with stable run identity; producer and adapter version; input/output artifact references; source/ontology/policy/model/configuration versions; start/end and outcome; resource usage/cost where available; errors/retries/partial outcomes; and correlation/causation identifiers.

Generic job lineage is useful but does not replace finer-grained knowledge provenance.

### 4.8 Contract conformance tests

A port is not stable merely because an interface exists. Each adapter must pass a shared conformance suite covering:

1. valid and invalid inputs;
2. identity preservation and deterministic IDs;
3. version pinning and replay;
4. tenant isolation and negative authorization cases;
5. idempotent duplicate calls;
6. concurrent conflicting operations;
7. partial failure and recovery;
8. capability negotiation and unsupported features;
9. schema evolution and compatibility;
10. provenance completeness.

Use an in-memory/fake adapter for domain tests and the real adapter for integration tests. A mock-only suite is not proof of backend conformance.

---

## 5. Proposed Synanton-owned contract map

Names below are conceptual boundaries, not a commitment to introduce these exact interfaces or APIs. Reuse existing contracts where they already cover the responsibility.

| Boundary | Responsibility | Candidate adapter(s) | Authority that remains in Synanton |
|---|---|---|---|
| Content extraction | Convert source artifacts into structured content | Existing extractor; optional Docling adapter | Source/version identity, content references, offsets, classification |
| Ontology artifacts | Load, validate, package, and export ontology artifacts | Jena; ROBOT build tooling; Protégé-authored artifacts | Ontology ownership, release identity, import closure, publication |
| Ontology validation | Validate a pinned graph against pinned shapes/profile | Existing Jena-based Syntology path | Validation profile, report semantics, promotion policy |
| Mapping strategy | Generate/evaluate mapping candidates | Internal strategies; future SSSOM adapters | Mapping semantics, evidence, governance, acceptance |
| Entity matching | Generate entity-match candidates | Existing Equalix path; optional Splink/Zingg adapter | Canonical identity, decision, merge/split history |
| Graph storage | Persist and query logical graph projections | Existing Neo4j adapter | Logical graph/projection identity, graph semantics, lifecycle |
| Retrieval engine | Retrieve and rank candidate evidence | Existing Synquest BM25/dense/RRF adapters | Authorized evidence semantics, provenance, evaluation |
| Policy decision | Evaluate trusted authorization inputs | Existing security implementation; optional OPA adapter | Identity, tenant authority, policy composition, enforcement |
| Processing lineage | Emit interoperable run/job/dataset events | OpenLineage emitter/adapter | Assertion-level provenance and dependency graph |
| Execution | Execute durable tasks and controlled recalculation | Existing eventing/workflow + Resolutor/Equalix | Dependency analysis, task identity, retry/fencing/outcome semantics |
| Agent protocol | Expose platform operations to agent clients | MCP SDK adapter | Domain APIs, authorization, audit, stable tool semantics |

Before adding an interface, inspect the current code and architecture for an existing port with the same responsibility. Prefer evolving an existing contract over introducing synonyms or a second abstraction layer.

## 6. What Synanton must own

### 6.1 Knowledge lifecycle

Synanton owns the state transition from discovered evidence through candidate generation, validation, review, promotion, publication, supersession, and retraction. The exact state machine is domain-specific and must remain explicit.

### 6.2 Semantic identity and mapping governance

Synanton owns stable concept/entity identity, domain ownership, contextual applicability, mapping relation semantics, conflict handling, evidence, and promotion decisions. Algorithms can be replaced; accepted semantic meaning cannot be vendor-defined.

### 6.3 Provenance and explainability

Synanton owns the durable trace from source revision and content span to extracted mention, resolution decision, canonical assertion, validation, projection, retrieval evidence, and agent response. Provenance is part of the knowledge model, not optional citation decoration.

### 6.4 Dependency-aware change management

Synanton owns the dependency graph and the answer to: what became stale when a source, ontology, model, mapping, rule, policy, or pipeline changed? Resolutor plans affected work; execution infrastructure runs it under explicit safety and recovery semantics.

### 6.5 Policy-aware knowledge access

Synanton owns the security model, trusted context propagation, policy composition, enforcement location, and fail-closed behavior. External policy engines may evaluate decisions but cannot define platform identity or tenant authority.

### 6.6 Reproducible knowledge snapshots

Synanton should evolve toward an immutable knowledge release/snapshot manifest that pins relevant source versions, ontology releases/imports, extraction and resolution artifacts, validation profiles/reports, mappings, projection generations, and dependency metadata. This is a product-level integration capability, not a feature to outsource to one engine.

---

## 7. Recommended adoption sequence

Do not turn this shortlist into a multi-product integration project. Adopt only when a current vertical-slice requirement or measured gap justifies the work.

### P0 — stabilize contracts and verify current capabilities

1. Inventory current ports/adapters and identify duplicate or leaky abstractions.
2. Document the actual Jena/SHACL feature profile and add conformance tests for the supported subset.
3. Align existing graph and search adapters with the approved logical graph/projection and retrieval contracts.
4. Ensure the extracted-mention contract preserves source version, content reference, chunk identity, and span semantics.
5. Define adapter capability descriptors and a shared error/retry/idempotency vocabulary.
6. Keep security gaps visible; do not claim policy-aware evidence filtering until negative tests prove it.

### P1 — bounded evaluations

1. Benchmark Docling against the existing structured extraction pipeline on representative, permitted documents.
2. Evaluate ROBOT for ontology CI/release automation without changing runtime registry semantics.
3. Evaluate SSSOM import/export against v0.3 mapping semantics and evidence.
4. Benchmark Splink against the current Equalix resolution pipeline using a frozen, separately governed evaluation set.
5. Prototype an OPA adapter only against the existing security model and trusted authorization inputs.
6. Evaluate OpenLineage event emission for processing runs and data artifacts.

Each evaluation needs an owner, dataset, baseline, metrics, resource budget, security classification, acceptance threshold, and stop condition. Evaluation corpora must remain isolated from production/customer indexes. Hold-out data must remain untouched until the agreed final evaluation.

### P2 — requirement-driven extensions

1. Consider OpenFGA only if relationship-based authorization has a demonstrated gap.
2. Consider Temporal only if durable workflow execution requirements exceed the existing contracts and implementation.
3. Consider alternate graph/search engines only after adapter conformance and workload benchmarks identify a limitation.
4. Add Flink/Spark/Iceberg integrations when analytical scale, streaming, or lakehouse interoperability requires them—not simply to increase the technology count.

---

## 8. Technology selection scorecard

Every candidate should be scored against the same dimensions. A high feature count cannot compensate for a broken authority or security boundary.

| Dimension | Questions |
|---|---|
| Contract fit | Can it implement an existing Synanton port without leaking vendor types? |
| Semantic fit | Does its data model preserve identity, context, evidence, and lifecycle semantics? |
| Correctness | Are determinism, consistency, transaction, and failure guarantees explicit and testable? |
| Security | Can tenant, principal, classification, and data-residency requirements be enforced? |
| Provenance | Can inputs, outputs, versions, and decisions be traced? |
| Replaceability | Can the implementation be swapped without changing persisted domain meaning? |
| Operational maturity | Are deployment, upgrades, backup/restore, observability, and incident response understood? |
| Performance | Does it meet representative latency, throughput, memory, and cost targets? |
| Ecosystem | Does it integrate with the current language/runtime and deployment model? |
| Licensing | Are code, transitive dependencies, models, and hosted-service terms acceptable? |
| Exit cost | Can data and configuration be exported in documented, tested formats? |
| Maintenance burden | Does it reduce total ownership cost, or add another service and failure mode? |

A candidate should not proceed to production unless it has a named owner, accepted ADR, tested adapter, security review, operational runbook, migration/exit plan, and measured benefit.

## 9. Risks and anti-patterns

| Anti-pattern | Why it is harmful | Required guardrail |
|---|---|---|
| Adopting a full RAG framework as platform architecture | Couples extraction, retrieval, graph, prompt, and persistence models to one framework | Use bounded libraries/adapters; retain Synanton contracts |
| Letting a matcher merge canonical entities | A probabilistic score is not a governance decision | Candidate-only adapter; explicit Synanton promotion |
| Treating SHACL validation as authorization | Structural conformance does not grant access | Separate validation and authorization contracts |
| Filtering after LLM context construction | Restricted evidence has already crossed the trust boundary | Enforce before evidence is supplied to the model |
| Exposing vendor objects in domain APIs | Makes replacement a breaking rewrite | Translate to versioned Synanton types |
| Introducing multiple policy authorities | Creates contradictory decisions and unclear precedence | One explicit policy composition and enforcement model |
| Introducing a second graph/search/workflow engine preemptively | Adds operational complexity without demonstrated value | Benchmark against an explicit requirement |
| Treating generic lineage as assertion provenance | Loses the evidence chain needed to explain knowledge | Keep assertion-level provenance in Synanton |
| Assuming exactly-once side effects | Durable replay does not make external writes atomic | Idempotency, fencing, reconciliation, explicit outcomes |
| Silently degrading unsupported capabilities | Weakens correctness/security without operator visibility | Capability negotiation and typed failure |
| Turning evaluation data into production knowledge | Pollutes indexes and invalidates benchmark results | Isolated benchmark tenant and pinned manifests |

## 10. Open decisions for review

1. **Document extraction:** Is Docling materially better than the current structured content extractor on Synanton's corpus, or does it duplicate an existing capability?
2. **Ontology release tooling:** Is ROBOT needed in CI, and which checks should be mandatory before a release is publishable?
3. **SHACL profile:** Which SHACL Core features are implemented and tested today? Is SHACL-SPARQL required? Is SHACL-AF explicitly out of scope for the first supported profile?
4. **Mapping interchange:** Does SSSOM preserve Synanton's direction convention, context, evidence, negative mappings, and governance lifecycle without lossy extensions?
5. **Entity resolution:** What measured weakness in the current Equalix path would justify Splink or Zingg?
6. **Authorization:** Does OPA fit the existing Design 1.23 model, or would it duplicate policy state? What exact enforcement point and negative tests are required?
7. **Processing lineage:** Which OpenLineage events/facets provide value beyond existing Processing Run and provenance contracts?
8. **Workflow:** What concrete requirement would justify Temporal over the current eventing/workflow and Resolutor/Equalix model?
9. **MCP:** Which operations are safe and stable to expose, and which should remain internal or human-reviewed?
10. **Contract ownership:** Which module owns each port, schema, conformance suite, and compatibility policy?

## 11. Acceptance criteria for this document

Before approval:

- [ ] Every technology row is classified as Existing, Adopt standard, Evaluate, Integrate selectively, Defer, or Not selected.
- [ ] Existing implementation claims are checked against current code and authoritative status documents.
- [ ] No candidate is presented as approved for production without an ADR.
- [ ] Each proposed port maps to an existing contract or has an explicit reason to be added.
- [ ] Security, provenance, versioning, idempotency, failure, and capability requirements are reviewed by relevant owners.
- [ ] Evaluation proposals include representative data, baselines, thresholds, isolation, and hold-out protection.
- [ ] Licensing, model/data terms, deployment, and exit strategy are checked for each selected candidate.
- [ ] Architecture index and implementation tracking are updated only after review resolves the open decisions.

## 12. References

Internal architecture and contract sources:

- [Synanton Platform Architecture 1.0](./synanton-platform-architecture-1.0.md)
- [Design 1.23 — Security and Representation](./synanton-design-1.23.md)
- [Design 1.25 — Annotations, Derived Knowledge, Recalculation, Analytics](./synanton-design-1.25.md)
- [Design 1.27 — Eventing and Workflow](./synanton-design-1.27.md)
- [Design 1.31 — Search and Retrieval](./synanton-design-1.31.md)
- [Graph Storage Abstraction and Logical Projection Architecture](./proposals/v1.35/graph-storage-abstraction-logical-projection-architecture.md)
- [Domain Ontologies and Cross-Domain Semantic Mapping v0.3](./proposals/ontology-domain-mapping-v0.3.md)
- [Ontology Registry API Contract — draft for ADR review](./proposals/ontology-domain-mapping/registry-api-contract.md)
- [Extracted-Mention Graph Contract](../planning/extracted-mention-graph-contract.md)
- [ADR-029 — Source Change Lifecycle](./decisions/adr-029-source-change-lifecycle.md)
- [Documentation index](../INDEX.md)

External project references (for evaluation, not endorsement):

- [Docling](https://github.com/docling-project/docling)
- [Protégé](https://protege.stanford.edu/)
- [ROBOT](https://robot.obolibrary.org/)
- [Apache Jena](https://jena.apache.org/)
- [RDF4J](https://rdf4j.org/)
- [SSSOM](https://mapping-commons.github.io/sssom/)
- [Splink](https://github.com/moj-analytical-services/splink)
- [Zingg](https://github.com/zinggAI/zingg)
- [Open Policy Agent](https://www.openpolicyagent.org/)
- [OpenFGA](https://openfga.dev/)
- [OpenLineage](https://openlineage.io/)
- [Temporal](https://temporal.io/)
- [Model Context Protocol](https://modelcontextprotocol.io/)

---

**Review posture:** This proposal favors reusing engines behind stable Synanton contracts. It does not authorize broad dependency expansion, change the current vertical-slice scope, or imply production readiness for any proposed extension.
