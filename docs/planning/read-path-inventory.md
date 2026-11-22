# Read-path current-generation inventory

**Status:** Draft — required artifact for #668/#655/#656.
**Rule:** each request resolves the authoritative current generation set per source once, then every subsystem in that request filters against the same set.

| Surface | Backing store | Generation-aware today? | Per-request resolution? | Owning ticket |
|---|---|---|---|---|
| Graph evidence/provenance | Neo4j `:RelationEvidence`/`:REL` | No | No | #655/#656 |
| Entity/assertion views | Neo4j canonical entities/assertions | No | No | #655 |
| Graph traversal/neighborhood API | Neo4j via Relix `GraphReadPort` | No | No | #655/#656 |
| Evidence/provenance panel | Relix API | No | No | #656 |
| Search results/snippets | Synquest/Lucene | No | No | #656 |
| Cached/materialized views | Search/graph projections | No | No | #656 |
| Lifecycle/status endpoint | Cassandra activation record | N/A | N/A | #657/#669 |
| UI Search → Entity → Graph → Evidence | UI over graph/search APIs | No | No | #649b/#658 |

Current user-visible state: a recently superseded source revision may still be visible as current in search, entity/assertion views, and evidence panels. This is the documented temporary-visibility window, operationally unbounded until #655/#656.


Status vocabulary: `ENFORCED` = conformance test exists and passes with a test reference; `UNENFORCED` = in Phase-2b scope, test owed and must reach zero before #658; `DEFERRED` = outside Phase 2b, requiring rationale, owner, revisit trigger, and an exclusion acknowledgment; `DROPPED` = deliberately out of scope, with a decision date.

No read-path clause is DEFERRED or DROPPED: all current rows are Phase-2b scope and UNENFORCED.

## Conformance

| Inventory row | Required test | Status |
|---|---|---|
| Graph evidence/provenance | filters by per-request resolved generation | UNENFORCED (#649b) |
| Entity/assertion views | filters by per-request resolved generation | UNENFORCED (#649b) |
| Traversal/neighborhood API | filters by per-request resolved generation | UNENFORCED (#649b) |
| Evidence/provenance panel | filters by per-request resolved generation | UNENFORCED (#649b) |
| Search results/snippets | filters by per-request resolved generation | UNENFORCED (#649b) |
| Cached/materialized views | filters by per-request resolved generation | UNENFORCED (#649b) |
| Lifecycle/status endpoint | reports activation record, not projection contents | UNENFORCED (#657) |
| UI Search → Entity → Graph → Evidence | same resolved set across graph/search | UNENFORCED (#649b) |

A meta-test must fail if a new inventory row lacks a conformance test (#675).

## Change control

#655/#656 acceptance requires this inventory; owners must review changes and rerun the matrix.
