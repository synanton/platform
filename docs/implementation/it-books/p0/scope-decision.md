# IT Books v1 P0 scope decision

**Release:** `it-books-v1` · `releaseId=47fe38f8-9f72-481b-826a-6c5b12a6027a`
**Release digest:** `3a4a5edf876792ac92fa63494bd7181e4d8eb3826fb70e6fe4112d796be3831c`
**Decision version:** `it-books-v1-support-v1`

This decision is part of the compiled contract. It is versioned with the release and is not an
informal table in a PR description.

## Extraction targets — 11 classes

`Book`, `Author`, `Chapter`, `Section`, `Algorithm`, `DataStructure`, `Concept`, `Database`,
`DistributedSystem`, `Topic`, `Definition`.

These are the only classes the P0 template may ask extraction to produce.

## Support/infrastructure — 9 classes

`Thing`, `TopicBearing`, `TextDivision`, `DefinedConcept`, `UsesSubject`, `UsesObject`,
`DependencyNode`, `PartOfSubject`, `PartOfObject`.

These encode union domains/ranges. They are not extraction targets, are not emitted as entity
targets, and must not be annotated by A2 as fixture entities. Relations using them are expanded to
the concrete extraction classes shown in the construct report.

## Relations — 8 predicates

`AUTHORED_BY`, `HAS_CHAPTER`, `HAS_SECTION`, `COVERS_TOPIC`, `DEFINES`, `USES`, `DEPENDS_ON`,
`PART_OF`.

All eight are first-class relation targets. The first three are concrete one-to-one. The last five
are marker-expanded as recorded in `it-books.report.json`.

## Why this is load-bearing

P3/P4 must use this scope split when interpreting candidates and fixtures. If A2 annotates a support
class as an entity, or omits a class/relation outside a documented reconciliation, the evaluation
scope and compiler scope have diverged.
