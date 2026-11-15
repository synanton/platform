# Ontology Registry API Contract

**Status:** Draft for ADR review — not an accepted API contract  
**Supports:** ONT-ADR-010 / repository ADR-039  
**Baseline:** Domain Ontologies and Cross-Domain Semantic Mapping v0.3 §§5, 15, 20  
**Standards alignment:** See [Standards Compatibility and Interchange](./standards-compatibility.md).

## Purpose

Define the Phase 1 registry and concept-resolution boundary without pre-empting ADR-030, ADR-031 or ADR-039. The registry owns canonical ontology metadata, immutable releases and concept lookup; it does not own experimental mapping algorithms or make a triple store implementation part of the public contract.

## Proposed operations

| Operation | Purpose |
|---|---|
| `getOntology(ontologyId)` | Retrieve ontology identity and ownership metadata |
| `listOntologyVersions(ontologyId)` | List immutable releases and lifecycle status |
| `getOntologyRelease(ontologyId, releaseRef)` | Retrieve release manifest, artifacts and pinned import closure |
| `getConcept(conceptId, ontologyVersion)` | Retrieve a concept from a pinned release |
| `resolveConcept(expression, contextRef, sourceEvidence, resolutionPolicy)` | Resolve an expression, returning candidates and ambiguity explicitly |
| `validateOntologyRelease(ontologyId, releaseRef, validationProfile)` | Validate a candidate release before publication |
| `exportOntology(ontologyId, releaseRef, syntax)` | Export a pinned release in a supported RDF serialization |
| `getMapping(mappingId, mappingVersion)` | Retrieve a governed mapping assertion (subject to mapping contract ownership) |

These names are illustrative. ADR-039 owns endpoint names, request/response schemas and compatibility guarantees. Mapping APIs and candidate generation remain outside the Phase 1 registry boundary unless separately accepted.

## Required contract properties

- Stable concept identifiers and immutable release references.
- Tenant/organization authorization derived from verified identity, not caller-supplied context.
- Explicit ambiguity and abstention; no forced single match.
- Resolution result includes context version/hash, ontology release and import-closure digests, policy version, evidence and provenance.
- Deterministic behavior for pinned inputs and deterministic policy/algorithm versions where promised.
- Defined error taxonomy, idempotency, retry semantics and concurrency behavior.
- Release publication is atomic: a release is either fully validated and addressable or not published.
- Content digest and artifact media type are returned for immutable artifacts.
- No mapping-engine endpoints or strategy plugin API in Phase 1.
- Import resolution is pinned and auditable; the registry does not fetch mutable external imports during ordinary reads.

## Interchange and validation boundary

The registry should support a deliberately small, tested RDF interchange profile before adding more syntaxes or reasoning features:

- RDF 1.1-compatible graph model and established serialization(s) selected by ADR-039.
- OWL 2 ontology documents only within a declared profile; no assumption that every OWL 2 ontology is tractable for the selected reasoner.
- SHACL Core validation using a pinned shapes graph and validator implementation.
- SKOS vocabularies/mappings only where their semantics are compatible with the Synanton mapping relation.
- JSON-LD 1.1 as an optional API-facing representation, not a replacement for RDF/OWL semantics.

RDF 1.2 and SHACL 1.2 support must be capability-negotiated and opt-in while those specifications remain drafts. Unsupported syntax or features must be rejected explicitly, not silently degraded.

A validation report must identify the data graph, shapes graph, validation profile, validator/version, severity policy and result. SHACL conformance is not proof of OWL consistency, truth, completeness, or equivalence between concepts.

## Resolution policy

The field-level `resolutionPolicy` schema is owned by ADR-039. It must define candidate sources, lexical/structural signals, context requirements, ambiguity thresholds, abstention and permitted external services/data classes.

## Acceptance

ADR-039 must freeze the registry/API boundary, supported interchange profile, release manifest and identify the owning implementation module. This draft does not claim these APIs exist.
