# Concept Identity and Namespace Scheme

**Status:** Draft for ADR review — not an accepted contract  
**Supports:** ONT-ADR-001 / repository ADR-030  
**Baseline:** Domain Ontologies and Cross-Domain Semantic Mapping v0.3 §§5, 20  
**Standards alignment:** See [Standards Compatibility and Interchange](./standards-compatibility.md).

## Purpose

Define the identity and ownership questions that ADR-030 must resolve before Phase 1 exits. This document is a decision input, not an implementation mandate. It separates Synanton's canonical identity model from RDF serialization, ontology-language semantics and external identifier schemes.

## Required decisions

1. Canonical IRI base, namespace ownership and persistence policy.
2. Ontology identity versus ontology release identity.
3. Concept identifier stability across releases.
4. Ownership and authority for creating, merging, deprecating and retiring concepts.
5. Duplicate concepts across ontologies and the rule that duplicates remain distinct absent an explicit governed mapping.
6. Lifecycle representation and successor semantics.
7. Canonical identity authority, including the Equalix contract and named fallback owner.
8. RDF/OWL representation profile and export/import conformance requirements.
9. External ontology import policy, including pinned release closure and license/attribution metadata.

## Candidate identifier shape

A candidate pattern for discussion (not yet selected):

```text
{authority-base}/ontology/{ontology-id}
{authority-base}/ontology/{ontology-id}/version/{release-id}
{authority-base}/ontology/{ontology-id}/concept/{concept-id}
```

The ontology IRI identifies the ontology independently of a release. The release IRI identifies an immutable published snapshot. A concept IRI identifies the concept across releases while that concept remains semantically continuous. IRIs must not encode mutable labels, display names or a release number in the concept identifier.

The authority base and identifier allocation policy remain ADR-030 decisions. Examples in this document are illustrative, not reserved namespaces.

## RDF and OWL representation boundary

- RDF is the interchange graph model; ontology documents may be serialized using Turtle, RDF/XML, JSON-LD or another explicitly supported RDF syntax.
- OWL 2 axioms express formal class/property/individual semantics. They are not interchangeable with Synanton's operational mapping records.
- RDFS/OWL labels and annotations are descriptive metadata, not identity keys.
- `owl:sameAs` is reserved for individual identity, not concept/class correspondence.
- `owl:equivalentClass` and `owl:equivalentProperty` assert strong extensional equivalence and must not be generated from lexical similarity or a high model score alone.
- SKOS concepts and SKOS mapping predicates may be used for controlled vocabularies and terminology alignment, subject to the translation rules in the standards compatibility document.

Synanton's canonical concept identity remains independent of the chosen RDF serialization and reasoner.

## Versioning and import closure

An ontology release must be immutable and addressable. A release manifest should record:

- ontology IRI and release IRI;
- version label and publication timestamp;
- content digest(s) for each artifact;
- syntax and media type;
- declared imports and the exact imported release/digest for each;
- license, attribution and source metadata;
- validation profile and validator version;
- publication/approval record.

Import resolution must use a pinned, auditable dependency closure. Runtime validation or reasoning must not silently fetch a mutable remote `owl:imports` target. Missing, changed or unauthorized imports fail closed or produce an explicit unresolved-dependency result; they must not be silently substituted.

A new release may add, deprecate or correct concepts, but must not mutate a prior release. Concept continuity across releases is an explicit governance decision; a successor is not automatically identical to its predecessor.

## Lifecycle and mappings

Proposed lifecycle: `Proposed → Active → Deprecated → Retired`. A `replacedBy` relationship indicates a successor, not automatic identity or equivalence. Duplicate concepts across ontologies remain distinct until an explicit governed relationship is established.

Ontology class/property mappings are separate resources with their own identifiers, versions, evidence, applicability and provenance. Do not collapse them into instance identity or directly rewrite external ontology IRIs.

## Equalix inspection

The ADR must record the actual Equalix contract and distinguish at least:

- equality of entity instances;
- resolution of an instance to a canonical entity;
- equivalence of ontology classes/concepts;
- correspondence between concepts in independently owned ontologies.

If Equalix does not own class identity/equivalence, ADR-030 must name the ontology registry/resolution service that does.

## Required evidence and acceptance

- Example IRIs for two independent ontologies and two releases.
- Demonstration that a label change does not change concept identity.
- Demonstration that a new release does not mutate the old release.
- Import-closure test proving that a pinned build is reproducible without mutable network resolution.
- Explicit ownership and authorization matrix.
- Named canonical identity authority and fallback.
- Migration/duplicate handling policy.
- RDF/OWL export-import round trip with declared supported profile and no loss of identity/version metadata.

## Open questions

No namespace, RDF serialization default, OWL profile or canonical identity authority is selected by this draft. Those decisions belong to ADR-030 and the standards compatibility decision recorded alongside it.
