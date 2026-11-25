# IT Books Ontology v1

**Issue:** #894 (A1) · **Program:** #892
**Artifact:** `schemas/it-books/v1/schema.hcl`
**Status:** proposed for review; not activated globally.

## Scope

This is the first versioned IT Books ontology. It defines bibliographic structure
and computing knowledge while reusing the existing Syntology HCL → OWL/SHACL
pipeline. It does not change the Supply Chain ontology, active-release selection,
or generation provenance.

## Entity types

| Canonical type | Purpose |
|---|---|
| `Book` | A published technical book. |
| `Author` | A person or organization credited with a book. |
| `Chapter` | A top-level division of a book. |
| `Section` | A subdivision of a chapter. |
| `Algorithm` | A computing algorithm. |
| `DataStructure` | A computing data structure. |
| `Concept` | A technical concept. |
| `Database` | A database system or family. |
| `DistributedSystem` | A distributed system or architecture. |
| `Topic` | A knowledge organization topic. |
| `Definition` | A definition of a concept. |

Abstract marker classes are not instance entity types. They encode the union
domains/ranges proposed in #894 using the existing single-domain/single-range
validator:

| Marker | Members |
|---|---|
| `TopicBearing` | Book, Chapter, Section |
| `TextDivision` | Chapter, Section |
| `DefinedConcept` | Definition, Concept |
| `UsesSubject` | Chapter, Section, Algorithm, DataStructure, Database, DistributedSystem |
| `UsesObject` | Algorithm, DataStructure, Concept, Database, DistributedSystem |
| `DependencyNode` | Algorithm, DataStructure, Concept, Database, DistributedSystem |
| `PartOfSubject` | Chapter, Section, Topic |
| `PartOfObject` | Book, Chapter, Section |

This preserves the proposed unions without accepting unrelated types. The
validator follows `rdfs:subClassOf`, so a `Book` matches a `TopicBearing`
domain and a `Concept` matches a `DefinedConcept` range.

## Predicates and domain/range

| Predicate | Encoded domain | Encoded range | Proposed union represented |
|---|---|---|---|
| `AUTHORED_BY` | `Book` | `Author` | direct |
| `HAS_CHAPTER` | `Book` | `Chapter` | direct |
| `HAS_SECTION` | `Chapter` | `Section` | direct |
| `COVERS_TOPIC` | `TopicBearing` | `Topic` | Book/Chapter/Section → Topic |
| `DEFINES` | `TextDivision` | `DefinedConcept` | Chapter/Section → Definition/Concept |
| `USES` | `UsesSubject` | `UsesObject` | Chapter/Section/Algorithm/DataStructure/Database/DistributedSystem → Algorithm/DataStructure/Concept/Database/DistributedSystem |
| `DEPENDS_ON` | `DependencyNode` | `DependencyNode` | Algorithm/DataStructure/Concept/Database/DistributedSystem on both sides |
| `PART_OF` | `PartOfSubject` | `PartOfObject` | Chapter/Section/Topic → Book/Chapter/Section |

Rationale: the existing `relation` block has exactly one domain and one range.
Abstract marker supertypes are the smallest extension that preserves the
reviewed unions and the validator's existing semantics. No predicate is left
unconstrained, and no predicate is widened to `Thing`.

## Four layers — mandatory distinction

These are different records. The ontology defines types for the canonical graph;
it does not turn source mentions into entities.

1. **Mention**
   - A source span plus chunk/source identity and context.
   - It is not an ontology entity, not a canonical ID, and not a graph edge.
   - Multiple mentions may resolve to one canonical entity; all source evidence
     remains attached to the mentions.

2. **Canonical entity**
   - A resolved, typed identity in the knowledge graph.
   - It is created by resolution/canonicalization, never by extraction alone.

3. **Relationship candidate**
   - An extracted assertion `(subject mention, predicate, object mention,
     evidence)`.
   - It is not an accepted graph edge, even when it uses an ontology predicate.

4. **Accepted relationship**
   - A candidate validated against a specific ontology release and eligible for
     graph persistence.
   - Acceptance requires the release identity used for validation; graph writes
     must not imply ontology acceptance.

`Mention` is deliberately absent from the canonical class list. Existing
contracts (`docs/planning/extracted-mention-graph-contract.md`, ADR-029) remain
authoritative for mention/candidate/canonical/accepted boundaries.

## Valid and invalid examples

These examples are inputs for A2/A5; they do not implement A2.

| Predicate | Valid example | Invalid example | Expected rejection |
|---|---|---|---|
| `AUTHORED_BY` | Book → Author | Chapter → Author | `domain_mismatch` |
| `HAS_CHAPTER` | Book → Chapter | Book → Section | `range_mismatch` |
| `HAS_SECTION` | Chapter → Section | Book → Section | `domain_mismatch` |
| `COVERS_TOPIC` | Book → Topic | Author → Topic | `domain_mismatch` |
| `DEFINES` | Chapter → Concept | Book → Concept | `domain_mismatch` |
| `USES` | Algorithm → DataStructure | Book → Algorithm | `domain_mismatch` |
| `DEPENDS_ON` | Database → DistributedSystem | Book → Database | `domain_mismatch` |
| `PART_OF` | Chapter → Book | Book → Chapter | `domain_mismatch` |

Additional range checks are expected for `COVERS_TOPIC` (Book → Concept) and
`DEPENDS_ON` (Database → Book): `range_mismatch`.

## Deferred

- `PREREQUISITE_FOR` and a prerequisite entity type are explicitly deferred.
- A2/A3/A5 implementation and release publication are out of scope.
- No global activation of IT Books is performed by this artifact.

## Compatibility and review decisions

- Uses the existing HCL schema bundle format and Syntology load/validation path.
- Abstract marker classes are additive and do not alter Supply Chain.
- Human review must confirm the marker-class mapping before A3 publication.
- The release identity will be the normal immutable ontology release ID/digest
  produced by the existing #868 mechanism; this document does not invent a
  parallel release format.
