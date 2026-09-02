# AAP-3 - Knowledge Projections

**Track:** [Annotation, Derived Knowledge, Recalculation, Analytics & Reporting Plane](./INDEX.md) · **Design ref:** [synanton-design-1.25.md §90 Phase 3](../../architecture/synanton-design-1.25.md), §15-§18

**Enforces invariants:** 5 (masking rules unchanged), 9 (recalculable), 11 (provenance preserved).

---

## Goal

Tag the three existing knowledge projections (reverse index, vector, graph) with annotation/definition-version provenance so Resolutor/Equalix (AAP-2) can determine which projected artefacts are stale after a change, and so every projection remains explainable back to the annotation and processing run that produced it.

## Work items

1. **Reverse index provenance** - `synquest`:
   - Extend the Lucene document schema (`java/synquest/.../service/LuceneIndexBuilder.java`) with filterable `annotation_definition_id`/`annotation_definition_version` fields alongside the existing `classification` field from SEC-1.
   - Index build/rebuild reads the new Cassandra `annotations` table (AAP-1) in addition to `chunks_payload`.
   - Preserve the SEC-3 representation contract unchanged (design §16 - "the reverse index... must preserve the representation contract inherited from Design 1.23").
2. **Vector projection provenance** - `synflux` `EmbedStage` + `ingestion-cache`:
   - Extend the embedding cache key (already class/representation-aware from SEC-5) with an `embedding_model_version` component so an embedding-model change is independently detectable by Resolutor (design §17, §51's "Embedding model" row).
   - Selective re-embedding: only chunks whose `RecalculationPlan` (AAP-2) marks `VECTOR` as affected are re-embedded - not the full corpus.
   - Classified original vectors remain isolated from masked vectors (unchanged invariant from SEC-5/Design 1.23 §17).
3. **Graph projection provenance** - `relix`:
   - Extend `Entity`/edge DTOs and the Neo4j/Nebula/in-memory connector adapters with `annotation_definition_id`, `annotation_definition_version`, `processing_run_id` properties (building on the `classification`/`representation` properties added in SEC-5).
   - Graph rebuild triggered by Equalix for entries whose `RecalculationPlan` marks `GRAPH` as affected.
4. **Projection rebuild coordination** - `annotations` service:
   - `ProjectionRebuildDispatcher` consumes `PROJECTION_REBUILD`-class work items from Equalix (AAP-2) and fans out to `synquest`/`relix`/embedding-cache rebuild endpoints, analogous to `TopologyOutboxDispatcher`'s fan-out pattern.

## Definition of Done

1. A Lucene document for an annotated chunk exposes `annotation_definition_id`/`annotation_definition_version` as filterable fields, without changing existing SEC-1..SEC-6 classification/representation query behaviour.
2. Bumping an embedding model version triggers re-embedding only for chunks whose Resolutor plan marks `VECTOR` affected; unaffected chunks' cache entries are untouched (verified by cache-hit-rate assertion in an integration test).
3. A graph entity/edge produced from an annotated chunk carries `annotation_definition_id`/`annotation_definition_version`/`processing_run_id`, queryable via the existing `POST /graph/query` shapes.
4. `ProjectionRebuildDispatcher` fans out a `PROJECTION_REBUILD` work item to the correct subset of `{synquest, relix, embedding-cache}` based on the plan's `affectedProjections` set from AAP-2 - not all three unconditionally.
5. Integration test: definition version bump → Resolutor plan → Equalix `PROJECTION_REBUILD` → updated Lucene doc + graph entity + (if model changed) re-embedded vector, all carrying matching `processing_run_id`.

## Key files

| File | Change |
|------|--------|
| `java/synquest/.../service/LuceneIndexBuilder.java` | Add annotation provenance fields |
| `java/synflux/.../pipeline/stage/EmbedStage.java` | Model-version-aware cache key, selective re-embed |
| `java/relix/.../api/dto/Entity.java` | Add annotation provenance properties |
| `java/relix/.../connector/*Connector.java` | Persist new properties per backend |
| `java/annotations/.../projection/ProjectionRebuildDispatcher.java` | New - fan-out coordinator |

---

[← AAP-2 Recalculation](./02-recalculation.md) · [Back to INDEX](./INDEX.md) · Next: [AAP-4 Analytics PoC](./04-analytics-poc.md)
