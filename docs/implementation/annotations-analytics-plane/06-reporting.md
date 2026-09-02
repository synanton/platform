# AAP-6 - Reporting

**Track:** [Annotation, Derived Knowledge, Recalculation, Analytics & Reporting Plane](./INDEX.md) · **Design ref:** [synanton-design-1.25.md §90 Phase 6](../../architecture/synanton-design-1.25.md), §41, §66, §70-§73

**Enforces invariants:** 6 (aggregates cannot bypass security), 13 (no bypassing the canonical query pipeline).

---

## Goal

Add the Analytics Registry (governance for metrics, reports, fact schemas, aggregate policies, freshness, retention), the metric/report lifecycle, freshness enforcement, and ship the first end-to-end report (`daily-platform-processing`, design §66) - on top of the security guarantees from AAP-5.

## Work items

1. **Analytics Registry schema** - Postgres tables in `java/analytics/src/main/resources/db/migration/V1__analytics_registry.sql` (governance-weight, mirrors the `topology` pattern rather than living in ClickHouse):
   - `metric_definitions` (`metric_id`, `version`, `source_facts jsonb`, `dimensions jsonb`, `aggregation`, `freshness`, `security_policy_id`, `aggregate_policy_id`, `status`).
   - `report_definitions` (`report_id`, `version`, `metric_refs jsonb` - explicit metric **versions**, not latest-always, `dimensions jsonb`, `refresh`, `security jsonb`, `status`).
   - `aggregate_policies` (matches the design §28 YAML shape).
   - `freshness_requirements`, `retention_policies`.
2. **Metric/report lifecycle** - `Draft → Validated → Published → Deprecated → Retired` (design §72-73):
   - `MetricLifecycleService`/`ReportLifecycleService` enforce transition rules; a `Published` definition is immutable, changes create a new version (same immutability rule as AAP-1's annotation definitions).
   - Registry validation on publish: a metric cannot declare itself `PUBLIC` if its source facts violate the applicable sharing policy (design §29) - calls into AAP-5's `AggregatePolicyEngine`/`ClassificationPropagator`.
3. **Freshness enforcement** - `analytics.../freshness/FreshnessMonitor.java`:
   - Freshness classes: `Real-time`, `Near-real-time`, `Hourly`, `Daily` (design §41).
   - Observable freshness metric per report/metric, included as an acceptance-criteria check, not just documentation.
4. **First report** - implement `daily-platform-processing` exactly per design §66's YAML (metrics: `documents_processed`, `documents_failed`, `annotations_created`, `processing_latency_p95`; dimensions: `tenant`, `media_type`, `error_type`, `annotation_type`; `refresh: daily`; `security: {tenant_isolated: true, classification_aware: true}`).
5. **Dashboards/API** - `java/analytics/.../api/MetricController.java`, `ReportController.java`:
   - `GET /metrics/{id}`, `GET /reports/{id}`, `GET /reports/{id}/data` - all routed through AAP-5's tenant/aggregate/sanitisation enforcement, never a raw ClickHouse passthrough.
6. **Governance auditing** - metric creation, report publication, policy changes recorded in an audit trail (design §81, §87) - reuse `topology`'s `admin_audit` pattern if schema-compatible, otherwise a parallel `analytics_audit` table.

## Definition of Done

1. Publishing a metric whose source facts are classified above `PUBLIC` while the metric itself declares `security_policy: PUBLIC` is rejected by the registry (design §29).
2. `daily-platform-processing` v1 runs end-to-end: platform activity → events → facts → aggregates → metrics → report → authorization, matching design §66's validation chain.
3. A report references explicit metric **versions**; bumping a metric's version does not silently change a published report's output until the report itself is republished against the new version.
4. `FreshnessMonitor` exposes an observable "age" per metric/report that can be asserted against its declared freshness class in a test.
5. `GET /reports/daily-platform-processing/data` for a tenant-scoped caller never returns another tenant's rows or `system`-scope rows (delegates to AAP-5's `TenantScopeGuard`).
6. Governance actions (metric publish, report publish, policy change) appear in the audit trail with actor, timestamp, and before/after version.

## Key files

| File | Change |
|------|--------|
| `java/analytics/src/main/resources/db/migration/V1__analytics_registry.sql` | New - registry tables |
| `java/analytics/.../registry/MetricLifecycleService.java` | New |
| `java/analytics/.../registry/ReportLifecycleService.java` | New |
| `java/analytics/.../freshness/FreshnessMonitor.java` | New |
| `java/analytics/.../api/MetricController.java` | New REST API |
| `java/analytics/.../api/ReportController.java` | New REST API |
| `java/analytics/src/main/resources/reports/daily-platform-processing.yaml` | First report definition |

---

[← AAP-5 Analytics Security](./05-analytics-security.md) · [Back to INDEX](./INDEX.md) · Next: [AAP-7 Production Hardening](./07-production-hardening.md)
