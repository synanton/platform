# Ontology Standards Compatibility and Interchange

**Status:** Draft for ADR review — proposed compatibility policy, not an accepted implementation contract  
**Scope:** Ontology registry, Syntology, concept identity, context, mapping assertions and provenance  
**Review date:** 2026-11-15  
**Baseline:** Domain Ontologies and Cross-Domain Semantic Mapping v0.3  
**Related decisions:** ADR-030, ADR-031, ADR-032, ADR-037, ADR-039, ADR-040 and ADR-042

## 1. Purpose

Define a standards-aware boundary for Synanton's ontology work without turning the internal semantic model into a thin wrapper around one vendor, serialization, triple store or reasoner.

The design principle is:

> Standards define interoperable representations and semantics. Synanton owns its operational contracts, governance, versioning, provenance and computation lifecycle.

This document is a decision input. It does not amend the approved v0.3 baseline, accept an ADR, or claim that every listed format is already implemented.

## 2. Recommended compatibility tiers

| Tier | Specifications | Intended use | Policy |
|---|---|---|---|
| Production baseline | RDF 1.1; Turtle 1.1; N-Triples/N-Quads; OWL 2; SHACL 1.0; SKOS; JSON-LD 1.1; PROV-O | Portable ontology, validation, vocabulary, API and provenance exchange | Select and test a minimal supported subset |
| Mapping interchange | SSSOM | Exchange curated or generated concept correspondences and their metadata | Adopt as an interchange profile after field/semantic mapping is specified |
| Experimental compatibility | RDF 1.2 family; SHACL 1.2 family | Evaluate quoted/triple terms, updated graph semantics, inference rules and UI-related shapes | Opt-in only; never required for canonical persistence or production interoperability yet |
| Vendor / implementation-specific | Databricks Genie Ontology, AWS Context Ontology Accelerator, Snowflake Business Ontology, Neo4j Relate | External integration and comparative research | Adapters only; no vendor model becomes Synanton's canonical semantic contract |

The production baseline is intentionally conservative. The exact syntax list, OWL profile, SHACL feature subset and conformance suite are ADR-039/ADR-037 decisions.

## 3. Standards and their responsibilities

### RDF 1.1 — graph interchange model

Use RDF as the interoperable graph data model. Keep internal domain objects and APIs independent of a particular RDF library or triple store. Preserve named-graph boundaries where required for release, tenant, provenance and validation isolation.

RDF serialization is not ontology semantics. Turtle, RDF/XML, N-Triples, N-Quads and JSON-LD are alternative representations with different operational characteristics.

### OWL 2 — formal ontology semantics

Use OWL 2 for class/property axioms and formal semantics, with an explicitly selected reasoning profile. Evaluate OWL 2 EL, QL and RL against actual ontology shapes, query patterns and throughput requirements before selecting a profile.

Do not treat OWL reasoning as graph validation. Do not treat an OWL entailment as proof that a source assertion is true. Do not use `owl:sameAs` for class or concept mapping.

### SHACL 1.0 — graph constraints

Use SHACL Core for structural and value constraints over a specified data graph. Pin the shapes graph and validator version; retain the validation report and the exact input release.

Keep validation outcomes separate from OWL consistency and mapping acceptance:
- SHACL conformance means the selected shapes did not report a violation under the selected validator/configuration.
- It does not prove ontology logical consistency, data truth, completeness, class equivalence or source authority.
- SHACL-SPARQL is an extension and must be advertised as a capability, not assumed from SHACL Core support.

### SKOS — taxonomies and controlled vocabularies

Use SKOS for concept schemes, preferred/alternative labels, definitions and hierarchical/associative vocabulary relations. Use mapping predicates such as `skos:exactMatch`, `skos:closeMatch`, `skos:broadMatch`, `skos:narrowMatch` and `skos:relatedMatch` only with their defined semantics.

Do not equate:
- `skos:exactMatch` with `owl:equivalentClass`;
- `skos:closeMatch` with formal equivalence;
- `owl:sameAs` with any class-to-class mapping.

SKOS mapping predicates have different symmetry/transitivity and entailment characteristics. Translation must be explicit and tested; never map all Synanton relation types to a generic SKOS predicate.

### JSON-LD 1.1 — linked-data API representation

Support JSON-LD as a possible application-facing representation where JSON ergonomics are useful. Keep the JSON-LD context controlled, versioned and integrity-checked. Remote contexts are dependencies and must not be mutable hidden runtime inputs.

JSON-LD is a serialization/linked-data mechanism, not a replacement for OWL, SHACL or a context-resolution policy.

### PROV-O — interoperable provenance vocabulary

Use PROV-O as a mapping target for provenance entities, activities and agents. Model source artifacts, extraction runs, resolution activities, validation runs, review decisions, mapping assertions and published graph projections with traceable derivation and attribution.

PROV-O does not by itself define Synanton's evidence model, confidence calibration, authorization, tenant isolation, retention or source-span addressing. Those remain explicit Synanton contracts.

### SSSOM — ontology mapping interchange

Evaluate SSSOM as the preferred interchange format for mapping sets. Its subject/predicate/object mapping core and metadata for justification, author, confidence and provenance align with the mapping research domain.

Define a Synanton profile before adoption. It must specify:
- mapping predicate vocabulary and direction convention;
- source/target ontology release identifiers;
- mapping assertion identity/version;
- mapping justification, evidence and provenance;
- applicability/context and validity;
- negative/rejected mapping representation;
- review/governance status;
- extension handling and round-trip preservation.

SSSOM is an interchange format, not Synanton's canonical mapping lifecycle, promotion protocol or authorization system. Unsupported Synanton fields must not be dropped on export/import without an explicit loss report.

## 4. RDF 1.2 and SHACL 1.2 — watch, prototype, do not require

As of 2026-11-15:
- RDF 1.2 Concepts and Abstract Data Model is a W3C Candidate Recommendation Draft dated 2026-11-13. The RDF 1.2 syntax documents (including Turtle and TriG) remain Working Drafts. RDF 1.2 introduces triple terms and directional language-tagged strings.
- SHACL 1.2 Core and related specifications are Working Drafts. The family includes separate work on SHACL Rules/inference, SPARQL-related features, node expressions and user interfaces.

These drafts are moving specifications, not stable production dependencies. The registry should expose capability/version information and allow isolated experiments, but canonical releases must remain readable using the production baseline.

### Triple-term / statement metadata policy

RDF 1.2 triple terms may offer a concise representation for metadata about a triple. However:
- do not make RDF 1.2 triple-term support mandatory for assertion identity;
- do not assume every RDF 1.1 store, serializer, query engine or downstream client preserves quoted-triple semantics;
- retain a portable assertion resource or named-graph representation as the canonical interchange fallback;
- record the asserted triple, assertion ID, source, time, evidence, confidence/epistemic status and provenance independently of serialization.

This avoids coupling Synanton's provenance and evidence model to an evolving syntax feature.

### SHACL Rules policy

Treat SHACL Rules as a distinct, experimental inference capability, not as ordinary SHACL Core validation. If prototyped, rules must run in a bounded, deterministic execution profile with:
- pinned rule set and engine version;
- explicit input and output graphs;
- iteration/termination limits;
- provenance for each derived assertion;
- cycle and duplicate handling;
- isolation from canonical facts until the derived result is validated and promoted.

Do not conflate SHACL Rules with OWL 2 reasoning or mapping-strategy execution.

## 5. Canonical assertion and provenance model

Every semantically meaningful assertion that may be reviewed, disputed, retracted or used in downstream computation should have a stable assertion identity independent of its RDF serialization.

At minimum, the assertion envelope should be able to reference:

- assertion ID and immutable assertion version;
- subject, predicate/relation and object;
- asserted-by agent and source artifact/span;
- ontology release(s) and context version;
- valid-time interval and recorded/observed time where available;
- evidence and justification;
- confidence or epistemic status, with the producing method/version;
- validation profile, validator version and report;
- governance state and review decision;
- derivation/provenance links;
- supersession/retraction relationship.

The exact schema belongs to the relevant ADRs. Confidence is not a substitute for evidence, authority or governance. A mapping candidate is not a canonical assertion.

## 6. Ontology imports and dependency management

Treat imported ontologies as locked dependencies:
- resolve imports at ingestion/publication time, not on every reasoning request;
- pin ontology release IRI, artifact digest, license and attribution;
- retain the resolved transitive import closure;
- reject or explicitly quarantine unresolved, mutable or policy-prohibited imports;
- validate namespace collisions and incompatible declarations;
- record import-closure changes as a new release and an impact-analysis event.

A release must be reproducible from its manifest and pinned artifacts without relying on current contents at a remote URL.

## 7. Interoperability rules for Synanton mapping relations

Synanton's canonical mapping relation vocabulary remains authoritative. External mappings are translated through an explicit, versioned mapping table, not by name similarity.

At minimum, distinguish:
- instance identity (`owl:sameAs`);
- class/property equivalence (`owl:equivalentClass`, `owl:equivalentProperty`);
- vocabulary exact/close correspondence (`skos:exactMatch`, `skos:closeMatch`);
- directional hierarchy correspondence (`skos:broadMatch`, `skos:narrowMatch`);
- associative correspondence (`skos:relatedMatch`);
- lexical similarity, analogy and relatedness that do not assert equivalence.

The Synanton direction convention is preserved at the API/core layer. Each export/import adapter must explicitly map direction and verify it with asymmetric test cases. A failed or lossy translation must return a structured diagnostic, not silently coerce the relation.

## 8. Conformance and compatibility tests

Before claiming support for a format or profile, add executable fixtures for:

1. Parse and serialize round trips for the declared RDF syntaxes.
2. Stable ontology, release and concept IRIs across releases.
3. Import closure resolution from pinned local artifacts.
4. OWL profile validation and selected reasoning tasks.
5. SHACL Core positive and negative validation fixtures.
6. Explicit proof that SHACL conformance is not reported as OWL consistency.
7. SKOS mapping predicate semantics, including direction, symmetry and transitivity edge cases.
8. PROV-O export retaining assertion, activity and agent links.
9. SSSOM export/import preserving all supported mapping metadata and reporting unsupported extensions.
10. JSON-LD context pinning and offline processing.
11. RDF 1.2/SHACL 1.2 experimental fixtures isolated from production baseline.
12. Deterministic digest and reproducible validation for an immutable ontology release.

A capability is advertised only when the corresponding conformance tests pass for the deployed adapter/version.

## 9. Decisions and ownership

| Decision | Owner ADR | Required outcome |
|---|---|---|
| Namespace, identity and release manifest | ADR-030 | IRI policy, release identity and import closure |
| Context and provenance references | ADR-031 | Context assertions, conflicts and immutable references |
| Mapping relation semantics | ADR-032 | Canonical relation-to-standard translation rules |
| OWL profile | ADR-037 | Selected profile, reasoner scope and performance envelope |
| Registry API | ADR-039 | Supported syntax/profile matrix, capability negotiation and error behavior |
| Strategy outputs and experiment records | ADR-040/041 | Candidate metadata, reproducibility and provenance |
| Mapping path composition | ADR-042 | Safe relation composition and path provenance |

This document does not accept these decisions. Owners must be assigned through the existing ADR process before implementation tickets are created.

## 10. References

- [RDF 1.1 Concepts](https://www.w3.org/TR/rdf11-concepts/)
- [RDF 1.2 Concepts — Candidate Recommendation Draft, 2026-11-13](https://www.w3.org/TR/2026/CRD-rdf12-concepts-20261111/)
- [W3C RDF and SPARQL standards/drafts index](https://www.w3.org/TR/)
- [OWL 2 Overview](https://www.w3.org/TR/owl2-overview/)
- [OWL 2 Profiles](https://www.w3.org/TR/owl2-profiles/)
- [SHACL Recommendation](https://www.w3.org/TR/shacl/)
- [SHACL 1.2 Core — Working Draft](https://www.w3.org/TR/shacl12-core/)
- [SHACL 1.2 Rules — Working Draft](https://www.w3.org/TR/shacl12-rules/)
- [SKOS Reference](https://www.w3.org/TR/skos-reference/)
- [JSON-LD 1.1](https://www.w3.org/TR/json-ld11/)
- [PROV-O Recommendation](https://www.w3.org/TR/prov-o/)
- [SSSOM specification and model](https://mapping-commons.github.io/sssom/)
