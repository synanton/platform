# A2 coverage reconciliation — IT Books v1

**Status:** pending A2 (#898) fixture completion. This records the required alignment now.

A2 annotations must use the P1-confirmed coordinate semantics (UTF-16 offsets into the canonical
structural text; the three geometry states; pinned chunking identity) and this compiler scope.

## Compiler entity scope — 11 targets

`Book`, `Author`, `Chapter`, `Section`, `Algorithm`, `DataStructure`, `Concept`, `Database`,
`DistributedSystem`, `Topic`, `Definition`.

A2 must annotate entity mentions only against this set, or add an explicit mapping/reconciliation
for any extra type. Support classes (`Thing`, `TopicBearing`, `TextDivision`, `DefinedConcept`,
`UsesSubject`, `UsesObject`, `DependencyNode`, `PartOfSubject`, `PartOfObject`) are not A2 entity
targets.

## Compiler relation scope — 8 predicates

`AUTHORED_BY`, `HAS_CHAPTER`, `HAS_SECTION`, `COVERS_TOPIC`, `DEFINES`, `USES`, `DEPENDS_ON`,
`PART_OF`.

A2 expected relations must use these predicates. Marker-expanded domains/ranges are:

| Predicate | Ontology domain/range | Concrete subjects | Concrete objects |
|---|---|---|---|
| `COVERS_TOPIC` | `TopicBearing` / `Topic` | Book, Chapter, Section | Topic |
| `DEFINES` | `TextDivision` / `DefinedConcept` | Chapter, Section | Concept, Definition |
| `USES` | `UsesSubject` / `UsesObject` | Chapter, Section, Algorithm, DataStructure, Concept, Database, DistributedSystem | Algorithm, DataStructure, Concept, Database, DistributedSystem |
| `DEPENDS_ON` | `DependencyNode` / `DependencyNode` | Algorithm, DataStructure, Concept, Database, DistributedSystem | Algorithm, DataStructure, Concept, Database, DistributedSystem |
| `PART_OF` | `PartOfSubject` / `PartOfObject` | Chapter, Section, Topic | Book, Chapter, Section |

`AUTHORED_BY`, `HAS_CHAPTER`, and `HAS_SECTION` are concrete and one-to-one.

## Exit condition

A2 acceptance must state one of:
1. its annotated types/predicates are a subset of this scope, or
2. every extra/omitted construct has an explicit reconciliation and reason.

No annotation may be generated from extraction output; A2 remains the independent authority.
