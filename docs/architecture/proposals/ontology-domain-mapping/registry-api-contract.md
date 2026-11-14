# Ontology Registry API Contract

**Status:** Draft for ADR review — not an accepted API contract  
**Supports:** ONT-ADR-010 / repository ADR-039  
**Baseline:** Domain Ontologies and Cross-Domain Semantic Mapping v0.3 §§5, 15, 20

## Purpose

Define the Phase 1 registry and concept-resolution boundary without pre-empting ADR-030, ADR-031 or ADR-039.

## Proposed operations

| Operation | Purpose |
|---|---|
| `getOntology(ontologyId)` | Retrieve ontology identity and ownership metadata |
| `listOntologyVersions(ontologyId)` | List immutable releases and lifecycle status |
| `getConcept(conceptId, ontologyVersion)` | Retrieve a concept from a pinned release |
| `resolveConcept(expression, contextRef, sourceEvidence, resolutionPolicy)` | Resolve an expression, returning candidates and ambiguity explicitly |
| `validateOntologyRelease(ontologyId, releaseRef)` | Validate a candidate release before publication |

These names are illustrative. ADR-039 owns endpoint names, request/response schemas and compatibility guarantees.

## Required contract properties

- Stable concept identifiers and immutable release references.
- Tenant/organization authorization derived from verified identity, not caller-supplied context.
- Explicit ambiguity and abstention; no forced single match.
- Resolution result includes context version/hash, ontology release, policy version, evidence and provenance.
- Deterministic behavior for pinned inputs and deterministic policy/algorithm versions where promised.
- Defined error taxonomy, idempotency and retry semantics.
- No mapping-engine endpoints or strategy plugin API in Phase 1.

## Resolution policy

The field-level `resolutionPolicy` schema is owned by ADR-039. It must define candidate sources, lexical/structural signals, context requirements, ambiguity thresholds, abstention and permitted external services/data classes.

## Acceptance

ADR-039 must freeze the registry/API boundary and identify the owning implementation module. This draft does not claim these APIs exist.
