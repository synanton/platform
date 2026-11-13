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
