# AAP-1 - Annotation Foundation

**Track:** [Annotation, Derived Knowledge, Recalculation, Analytics & Reporting Plane](./INDEX.md) · **Design ref:** [synanton-design-1.25.md §90 Phase 1](../../architecture/synanton-design-1.25.md), §6-§14, §351

**Enforces invariants:** 2 (canonical knowledge authoritative), 5 (masking rules unchanged), 11 (processing provenance preserved).

---

## Goal

Stand up first-class annotation identity: versioned annotation definitions, an annotation instance store, provenance, processing runs, and an explicit dependency DAG - all built on **post-masking** content so the annotation pipeline never sees an unmasked restricted span (hard dependency on v1.23 SEC-4).

## Work items

1. **New service `java:annotations`** - scaffold like `java/topology` (`build.gradle.kts` with `spring-boot-starter-web/jdbc/validation/actuator`, `flyway-postgresql`, `runtimeOnly(libs.postgresql)`); add to `settings.gradle.kts` include list.
2. **PostgreSQL migration** - `java/annotations/src/main/resources/db/migration/V1__annotation_definitions.sql`:
   - `annotation_definitions` (`definition_id`, `namespace`, `name`, `annotation_type`, `created_at`) - immutable identity, versions live separately.
   - `annotation_definition_versions` (`definition_id`, `version`, `inputs jsonb`, `producer`, `producer_version`, `output_type`, `output_name`, `status` [`draft`/`validated`/`published`/`deprecated`/`retired`], `published_at`) - one row per published version (design §8, §72 lifecycle).
   - `dependency_edges` (`from_definition_id`, `from_version`, `to_definition_id`, `to_version`) with a cycle-rejection check (application-level DAG validation on write - reject per design §10's explicit "circular dependencies are rejected").
   - `processing_runs` (`processing_run_id`, `producer`, `producer_version`, `tenant_id`, `definition_id`, `definition_version`, `scope`, `started_at`, `ended_at`, `status`, `error_summary`, `resource_consumption jsonb`) per design §12.
3. **Domain + repository** - mirror `ClassGrant`/`AclGrant` pattern in `topology`:
   - `AnnotationDefinition`, `AnnotationDefinitionVersion`, `DependencyEdge`, `ProcessingRun` records in `java/annotations/.../domain/model/`.
   - JDBC repositories per record, following `JdbcClassGrantRepository`'s shape.
4. **REST API** - `java/annotations/.../api/`:
   - `POST /definitions`, `POST /definitions/{id}/versions` (immutable once published - a `PUT` after `published` returns 409), `GET /definitions/{id}/versions/{version}`.
   - `POST /definitions/{id}/versions/{version}/dependencies` - registers a dependency edge; validates against existing edges for cycles before insert.
   - `POST /processing-runs`, `PATCH /processing-runs/{id}` (status transitions).
5. **Cassandra migration** - `java/ingestion-cache/src/main/resources/cql/V8__annotations.cql`:
   - New table `annotations` (mirrors `chunks_payload` shape): `tenant`, `annotation_id`, `definition_id`, `definition_version`, `annotation_type`, `namespace`, `name`, `target_type`, `target_id`, `value`, `producer`, `producer_version`, `confidence`, `source_classification`, `representation_used`, `provenance`, `processing_run_id`, `created_at`, `invalidated_at` - full shape per design §7 and §31.
   - Partition/clustering key `(tenant, target_type, target_id, annotation_id)` for per-chunk lookup; secondary lookup path by `processing_run_id` for recalculation (AAP-2).
6. **`synflux` `AnnotationStage`** - `java/synflux/.../pipeline/stage/AnnotationStage.java`, inserted **after** the masking stage from SEC-4, **before** `EmbedStage`:
   - Calls the `annotations` service for the tenant's active (published) definitions.
   - Initial producer: a deterministic rule-engine adapter (reuses `ClassificationDetector`-style regex/dictionary rules from SEC-2, generalized to arbitrary annotation types rather than only security classes).
   - Writes one `annotations` row per match, referencing the current `processing_run_id`.
   - Only ever reads `content_masked` (or the single representation, when masking made no change) - never `content_original` - matching Invariant 5.
7. **Configuration** - `AnnotationsProperties` in `java/annotations` and `SynfluxProperties.Annotation` nested record; `application.yml` defaults (`annotations.enabled: false`, `synflux.annotation.enabled: false`).

## Definition of Done

1. `annotation_definitions`/`annotation_definition_versions` CRUD works via the `annotations` REST API; publishing a version makes it immutable (a second `PUT` on a published version returns `409`).
2. Registering a dependency edge that would create a cycle (`A → B → C → A`) is rejected with a `400` before insert (design §10).
3. `AnnotationStage` runs in the `synflux` pipeline when `synflux.annotation.enabled=true` and writes rows to the new Cassandra `annotations` table, each carrying a non-null `processing_run_id`.
4. A processing run's `started_at`/`ended_at`/`status`/`resource_consumption` are queryable via `GET /processing-runs/{id}` after a pipeline run completes.
5. Ingesting a document with `synflux.classification.enabled=true` (SEC-2) and `synflux.annotation.enabled=true` produces annotation rows whose `source_classification`/`representation_used` match the chunk's SEC-4 masking outcome (Single/Dual/Masked-only) - never the pre-masking original.
6. Unit tests: definition/version lifecycle transitions, dependency-edge cycle rejection, annotation row serialization round-trip, `AnnotationStage` reading only masked content.

## Key files

| File | Change |
|------|--------|
| `settings.gradle.kts` | Add `java:annotations` module |
| `java/annotations/build.gradle.kts` | New module, mirrors `java/topology` |
| `java/annotations/src/main/resources/db/migration/V1__annotation_definitions.sql` | New tables |
| `java/annotations/.../domain/model/AnnotationDefinition.java` | New record |
| `java/annotations/.../domain/model/DependencyEdge.java` | New record + cycle check |
| `java/annotations/.../domain/model/ProcessingRun.java` | New record |
| `java/annotations/.../api/DefinitionController.java` | New REST API |
| `java/annotations/.../api/ProcessingRunController.java` | New REST API |
| `java/ingestion-cache/src/main/resources/cql/V8__annotations.cql` | New table |
| `java/synflux/.../pipeline/stage/AnnotationStage.java` | New stage |
| `java/synflux/.../runner/IngestionJobRunner.java` | Wire stage after masking, before embed |
| `java/synflux/src/main/resources/application.yml` | Defaults |

---

[← Back to INDEX](./INDEX.md) · Next: [AAP-2 Recalculation](./02-recalculation.md)
