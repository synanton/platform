# Concept Identity and Namespace Scheme

**Status:** Draft for ADR review — not an accepted contract  
**Supports:** ONT-ADR-001 / repository ADR-030  
**Baseline:** Domain Ontologies and Cross-Domain Semantic Mapping v0.3 §§5, 20

## Purpose

Define the identity and ownership questions that ADR-030 must resolve before Phase 1 exits. This document is a decision input, not an implementation mandate.

## Required decisions

1. Canonical IRI base and namespace ownership.
2. Ontology identity versus ontology release identity.
3. Concept identifier stability across releases.
4. Ownership and authority for creating, merging, deprecating and retiring concepts.
5. Duplicate concepts across ontologies and the rule that duplicates remain distinct absent an explicit governed mapping.
6. Lifecycle representation and successor semantics.
7. Canonical identity authority, including the Equalix contract and named fallback owner.

## Candidate identifier shape

A candidate pattern for discussion (not yet selected):

```text
{authority-base}/ontology/{ontology-id}/concept/{concept-id}
{authority-base}/ontology/{ontology-id}/version/{release-id}
```

The release IRI must identify an immutable snapshot. A concept IRI must not encode mutable labels or version numbers.

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
- Explicit ownership and authorization matrix.
- Named canonical identity authority and fallback.
- Migration/duplicate handling policy.

## Open questions

No namespace or canonical identity authority is selected by this draft. Those decisions belong to ADR-030.
