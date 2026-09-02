# AAP-7 - Production Hardening

**Track:** [Annotation, Derived Knowledge, Recalculation, Analytics & Reporting Plane](./INDEX.md) · **Design ref:** [synanton-design-1.25.md §90 Phase 7](../../architecture/synanton-design-1.25.md), §59-§65, §78, §83-§86, §91-§93, Appendix D

**Enforces invariants:** 10 (storage replaceable), 12 (background recalc cannot starve interactive workloads).

---

## Goal

Everything needed before the Analytics Plane can be trusted at production scale: the ClickHouse PoC evaluation, storage sizing, retention automation, backup/restore, disaster recovery, alerting, and load testing - all workload-dependent values resolved through measurement rather than guessed (design Appendix D).

## Work items

1. **ClickHouse PoC evaluation** (design §91) - run against the AAP-4/AAP-6 workload (first report + representative insert/aggregate/dashboard-query mix):
   - Record `expected_peak_eps`, `target_sustained_eps` (`≥ 1.5 × expected_peak_eps`), `observed_sustained_eps`.
   - Measure event size, compressed size, compression ratio, storage growth, node-failure/restart/recovery behaviour, backup/restore.
2. **Event volume and retention** (design §59) - per-fact-type retention classes (`Operational events → short`, `Processing facts → medium`, `Business metrics → long`, `Audit/security facts → policy-defined`) implemented as automatic, observable ClickHouse TTL/partitioning policies.
3. **Late and out-of-order events** (design §60) - implement the 24-hour default late-event window; late events update affected aggregates, are observable via a `late_event_rate` metric, and contribute to alerting (work item 6). Large historical backfills get an explicit rebuild mechanism (replay from `analytics_events`).
4. **Error handling** (design §61) - transient errors retry with exponential backoff; permanent processing errors go to a dead-letter queue + alert; schema errors quarantine + operator intervention; security policy errors fail closed with immediate failure, no automatic retry, and a security alert.
5. **Operational isolation** (design §62) - CPU/memory/I/O limits, query timeouts, result-size limits, workload priorities and resource pools for ClickHouse, coordinated with Equalix (AAP-2) so interactive workloads keep priority over historical analytical jobs.
6. **Alerting** (design §63) - Prometheus rules for: consumer lag > 5 min, event loss rate > 0.1%, query latency p95 > 1s, storage capacity > 80%, aggregate freshness > 2x expected, security policy failure, schema validation failure, dead-letter queue growth, late-event rate anomaly.
7. **Deployment model + runbook** (design §64, §92) - production topology (cluster size, replication factor, sharding, Keeper topology) selected from PoC evidence; operational runbook covering deployment, scaling, partition management, merge behaviour, replication, Keeper, backup, restore, capacity planning, incident response, schema migrations, retention, DR.
8. **Performance validation** (design §78, §93) - dashboard queries target p95 < 500ms under the reference workload; long-running queries are bounded, observable, cancellable, resource-controlled.
9. **Data quality** (design §86) - detection of missing events, duplicate events, invalid dimensions, impossible values, stale aggregates, broken lineage, schema violations - surfaced as observable quality metrics.

## Definition of Done

1. `expected_peak_eps`/`target_sustained_eps`/`observed_sustained_eps` are recorded (not placeholders) from a real PoC run against the first-report workload, meeting or exceeding the 1.5x headroom target - or an explicit documented shortfall with a remediation plan.
2. Retention TTLs are active and observable per fact-type class; a scripted check confirms data older than the configured retention is actually purged.
3. A synthetic late event (arriving 12h after its nominal window, within the 24h default) updates the correct aggregate and increments the late-event metric.
4. A forced ClickHouse node restart during ingestion produces zero data loss (verified via `analytics_events` replay) and the consumer resumes without manual intervention.
5. Every alert in the design §63 list has a wired Prometheus rule with a tested firing condition.
6. Operational runbook exists and has been exercised at least once for backup, restore, and one schema migration end-to-end.
7. Dashboard query load test meets p95 < 500ms under the defined reference workload; a long-running analytical query does not push interactive dashboard p95 above that bound (operational isolation proof).

## Key files

| File | Change |
|------|--------|
| `docs/implementation/annotations-analytics-plane/clickhouse-poc-results.md` | New - recorded PoC measurements (Appendix D values) |
| `docs/observability/alerts/analytics.yml` | New alert rules |
| `docs/runbooks/clickhouse-operations.md` | New operational runbook |
| `deployment/docker/compose.yaml` | ClickHouse resource limits, backup volume config |
| `java/analytics/.../quality/DataQualityMonitor.java` | New - quality metric surfacing |
| `scripts/run-analytics-load-test.sh` | New - load test harness |

---

[← AAP-6 Reporting](./06-reporting.md) · [Back to INDEX](./INDEX.md) · Next: [AAP-8 MCP / External Integration](./08-mcp-integration.md)
