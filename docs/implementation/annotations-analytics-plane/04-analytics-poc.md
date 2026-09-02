# AAP-4 - Analytics PoC

**Track:** [Annotation, Derived Knowledge, Recalculation, Analytics & Reporting Plane](./INDEX.md) · **Design ref:** [synanton-design-1.25.md §90 Phase 4](../../architecture/synanton-design-1.25.md), §22-§25, §32-§40

**Enforces invariants:** 1 (analytics is derived state), 5 (masking rules unchanged), 10 (storage replaceable), 11 (provenance preserved).

---

## Goal

Stand up the Analytics Plane's durable event boundary and initial storage: a Kafka `analytics_events` topic fed only from **after** the protected knowledge boundary (post-masking, post-classification), an Analytics Storage Contract with a ClickHouse adapter, the initial `analytical_facts` family of tables, and the first aggregates/metrics from design §67. No security enforcement or reporting UI yet - that is AAP-5/AAP-6.

## Work items

1. **New service `java:analytics`** - scaffold as a Spring Boot service (mirrors `java/control-plane`'s admin-API shape); add to `settings.gradle.kts`.
2. **`analytics_events` Kafka topic** - producers emit only after the protected-knowledge boundary (design §24 - prohibited: pre-classification path):
   - `synflux`: `AnnotationStage` (AAP-1) and `PersistStage` emit processing/annotation events.
   - `gateway`/`synquest`: search events (query count, latency, result count - design §35).
   - `relix`: graph projection events.
   - `topology`: security events (classification distribution, masking outcomes, authorization decisions - design §33).
   - Each event carries a stable `analytics_event_id`, schema version, tenant scope, and (where applicable) `source_classification`/`representation_used` - never the original restricted literal for a Masked-only source.
3. **Analytics Storage Contract** - `java/analytics/.../storage/`:
   - `AnalyticsStorage` interface: `writeFacts`, `queryAggregate`, `queryMetric` - no ClickHouse-specific method leaks through (design §38-39).
   - `ClickHouseAnalyticsStorage` adapter implementing it.
   - Add `clickhouse` service to `deployment/docker/compose.yaml` (PoC profile, single node).
4. **`analytical_facts` schema** - ClickHouse DDL in `java/analytics/src/main/resources/clickhouse/V1__analytical_facts.sql` (or module-appropriate migration mechanism - evaluate `clickhouse-migrations`/Flyway-ClickHouse-community driver vs. a bespoke bootstrap script; record the choice here once made):
   - Initial tables per design §32: `analytics_events`, `content_facts`, `chunk_facts`, `annotation_facts`, `processing_facts`, `projection_facts`, `search_facts`, `security_facts`, `recalculation_facts`, `cost_facts`.
   - `annotation_facts` schema follows design §31's `AnalyticalFact` shape exactly (`fact_id`, `tenant_id`, `source_id`, `chunk_id`, `definition_id`, `definition_version`, `annotation_type`, `namespace`, `name`, `value`, `producer`, `producer_version`, `target_type`, `target_id`, `confidence`, `processing_duration`, `evaluation_run_id`, `source_classification`, `representation_used`, `provenance`, `observed_at`, `invalidated_at`).
5. **Idempotent materialization** - `AnalyticsEventConsumer` deduplicates on `analytics_event_id` before writing facts (design §84) - at-least-once delivery + deterministic processing + idempotent materialization = effectively-once (design §85).
6. **Initial aggregates + metrics** - design §67's Processing/Annotation/Search/Knowledge groups only (Security/Cost/Recalculation metrics land in AAP-5/AAP-6 once security enforcement exists): `documents_processed`, `documents_failed`, `processing_latency`, `annotations_created`, `annotation_rate`, `queries`, `latency`, `chunks_created`.
7. **Configuration** - `AnalyticsProperties` (`analytics.enabled`, ClickHouse connection, Kafka consumer group).

## Definition of Done

1. An ingested document produces `analytics_events` rows only after `AnnotationStage`/masking complete - a test asserts no event is emitted from the pre-classification stages.
2. Replaying the same `analytics_event_id` twice produces exactly one `annotation_facts` row, not two (idempotency test per design §84).
3. `documents_processed`, `annotations_created`, and `queries` compute correctly against seeded fixture events in ClickHouse.
4. The `AnalyticsStorage` interface has at least one alternative-adapter unit test double (e.g. an in-memory fake) proving the contract doesn't leak ClickHouse-specific types into calling code (design §38-39, Invariant 10).
5. `./scripts/run-extract-index-poc.sh`-style end-to-end script variant demonstrates ingest → annotate → analytics event → fact → aggregate for at least `documents_processed` and `annotations_created`.
6. No Masked-only original literal appears in any `analytical_facts` table for the SEC negative corpus (`employee-jordan.md`) - reuses the SEC-4 negative-test approach against the new ClickHouse store.

## Key files

| File | Change |
|------|--------|
| `settings.gradle.kts` | Add `java:analytics` module |
| `java/analytics/build.gradle.kts` | New module |
| `java/analytics/.../storage/AnalyticsStorage.java` | New - storage contract |
| `java/analytics/.../storage/ClickHouseAnalyticsStorage.java` | New - ClickHouse adapter |
| `java/analytics/.../consumer/AnalyticsEventConsumer.java` | New - idempotent Kafka consumer |
| `java/analytics/src/main/resources/clickhouse/V1__analytical_facts.sql` | New - fact tables |
| `java/synflux/.../pipeline/stage/AnnotationStage.java` | Emit `analytics_events` |
| `java/gateway/.../query/*` | Emit search facts |
| `deployment/docker/compose.yaml` | Add `clickhouse` service |

---

[← AAP-3 Knowledge Projections](./03-knowledge-projections.md) · [Back to INDEX](./INDEX.md) · Next: [AAP-5 Analytics Security](./05-analytics-security.md)
