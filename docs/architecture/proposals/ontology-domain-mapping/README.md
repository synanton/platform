# Ontology Domain Mapping — Design Inputs

This directory contains focused design inputs supporting the approved [Domain Ontologies and Cross-Domain Semantic Mapping v0.3 baseline](../ontology-domain-mapping-v0.3.md). These documents are drafts for ADR review; they are not accepted contracts or implementation claims.

## Documents

| Document | Purpose | Decision owner |
|---|---|---|
| [Concept identity and namespace](./identity-and-namespace.md) | Stable concept identity, ontology ownership, immutable releases and import closure | ADR-030 |
| [Context schema and conflicts](./context-schema-and-conflicts.md) | Versioned context assertions, conflict representation and provenance | ADR-031 |
| [Registry API contract](./registry-api-contract.md) | Registry boundary, release operations, resolution and validation contract | ADR-039 |
| [Standards compatibility and interchange](./standards-compatibility.md) | RDF/OWL/SHACL/SKOS/JSON-LD/PROV-O/SSSOM compatibility and draft-standard policy | ADR-030/031/032/037/039 |

## Status discipline

- v0.3 remains the approved architectural baseline and is not edited in place.
- ADR-030–042 remain the authoritative decision records; their current status is shown in [the ADR index](../../decisions/INDEX.md).
- Standards compatibility is a proposal until the relevant ADRs accept the supported profiles and conformance requirements.
- RDF 1.2 and SHACL 1.2 drafts are experimental targets, not production requirements.
- Syntology's existing ontology-management implementation and demo scope are distinct from the proposed cross-domain mapping subsystem.
