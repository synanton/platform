# 11 - control-plane - Phase 4 - GitOps, Forecast, Anomaly, ACL Reconciliation, Ontology Lint

**Version:** 1.0
**Date:** 2026-08-11
**Status:** Draft for review
**Depends on:** Phase 3 `control-plane` DoD (Admin API, `ModelServingDirectory`). Phase 4 `topology`, `security`, `synquest`, `relix`.
**Scope:** Give operators the full v1.19 control surface: a GitOps reconciler that reads tenant policy from Git and writes it to `topology`, a Prophet-based Forecast Engine feeding budget-warning alerts, an Anomaly Detector emitting slow-query recommendations, an ACL propagation reconciler that catches STUCK grants beyond topology's own retry window, and the Ontology Lint Workflow that files review items in `synreview`.

---

## 1. Context and Document Alignment

| Source | Role in this plan |
|--------|-------------------|
| [synanton-design-1.19.md](../../architecture/synanton-design-1.19.md) §27 `control-plane` (Admin API, GPU degraded, RecrawlAfterRestoration, GitOps, Forecast, Anomaly, Ontology Lint) | Production target |
| [synanton-design-1.19.md](../../architecture/synanton-design-1.19.md) §15 GitOps Reconciliation Loop | Loop semantics |
| [synanton-design-1.19.md](../../architecture/synanton-design-1.19.md) §14 Anomaly Detection Loop | Detector semantics |
| [synanton-design-1.19.md](../../architecture/synanton-design-1.19.md) §13 Predictive Auto-Scaling Loop | Forecast semantics |
| [synanton-design-1.19.md](../../architecture/synanton-design-1.19.md) §27 Ontology Lint | Duplicate/orphan detection |
| [10-topology.md](./10-topology.md) | Consumer of GitOps writes and STUCK-grant scans |
| [15-observability.md](./15-observability.md) | Metrics/alerts wiring |

**Explicit non-goals for Phase 4:**

- No DR runbook automation (Phase 5).
- No BackupVerificationWorkflow (Phase 5).
- No `synreview` implementation - Phase 4 lint files review items into a placeholder table; UI/queue is Phase 5.
- No GPU degraded-mode restoration full recrawl automation - Phase 4 wires the *trigger*; the `RecrawlAfterRestorationWorkflow` is a stubbed Temporal workflow that logs its intended actions.

---

## 2. Phase 4 in One Sentence

> Deliver the four background loops (GitOps reconcile, forecast, anomaly, ACL reconcile) plus the Ontology Lint Workflow so operators run the platform declaratively, get budget warnings before they blow the cap, and never lose a stuck ACL grant.

---

## 3. Target Architecture

```mermaid
flowchart TD
  GIT[Git repo: /tenants/*.yaml] -->|poll every 60s| REC[GitOpsReconciler]
  REC -->|diff vs topology| MUT[topology.UpsertPolicy]
  PROM[Prometheus TSDB] --> FC[ForecastEngine Prophet]
  FC -->|forecast_lag_15m| GAUGE[Prometheus gauge]
  FC -->|budget forecast| ALERT_FC[ForecastCostOverrunWarning]
  PROM --> ANO[AnomalyDetector Isolation Forest + DBSCAN]
  ANO --> REC_ADV[/admin/anomalies/recommendations]
  TOPO_STUCK[topology.acl_grants WHERE STUCK] --> ACL_REC[AclReconciler cron every 5m]
  ACL_REC --> RETRY[re-dispatch outbox]
  LINT[OntologyLintWorkflow Temporal] --> SYNREV[synreview.review_items]
  DEG[GPU degraded mode trip circuit] --> RECRAWL[RecrawlAfterRestorationWorkflow trigger]
```

---

## 4. Data Contracts

### 4.1 GitOps repository layout

```
gitops/
  tenants/
    demo.yaml
    demo2.yaml
  common/
    defaults.yaml
```

Per-tenant YAML:

```yaml
tenant_id: demo
tier: STANDARD
data_residency_policy:
  allowed_regions: [us-east-1, us-west-2]
budget_policy:
  monthly_usd_cap: 5000
  weight: 100
  max_concurrent_ingest_jobs: 8
rerank_policy:
  mode: ALWAYS
  model_family: bge-reranker-large
  candidate_pool_size: 100
  top_n: 20
cross_region_penalty_ms:
  us-east-1: { us-west-2: 60, eu-west-1: 90 }
regulatory_profile: null
```

Reconciler config:

```yaml
control_plane.gitops:
  enabled: true
  repo_url: "https://github.com/example/synanton-tenants.git"
  branch: "main"
  poll_interval_seconds: 60
  path_prefix: "tenants/"
  auth: { type: "ssh", key_env: "GITOPS_SSH_KEY" }
```

Endpoints:

- `POST /admin/gitops/apply` - forces immediate reconcile (bypasses poll interval).
- `GET /admin/gitops/status` - shows last successful reconcile time, current commit SHA, and diff pending.

### 4.2 Forecast Engine

`forecast_lag_15m` gauge (already used by `synflux-router` for adaptive parallelism in `03-synflux-router.md`). New:

```
prom_metric: control_forecast_budget_days_remaining{tenant}
prom_metric: control_forecast_error_pct{tenant, metric}
```

Config:

```yaml
control_plane.forecast:
  window_minutes: 15
  history_days: 30
  primary: prophet
  fallback: arima
  refresh_interval_minutes: 10
```

Alerts: `ForecastCostOverrunWarning` (`days_remaining < 7`), `ForecastCostOverrunCritical` (`< 3`, page), `ForecastAccuracyDegraded` (`error_pct > 20%` over 2 h).

### 4.3 Anomaly Detector

Runs every 5 min against last 24 h of query metrics. Emits recommendations to `admin_anomaly_recommendations`:

```sql
CREATE TABLE control_plane.admin_anomaly_recommendations (
  rec_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  detected_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  category     TEXT NOT NULL,        -- SLOW_QUERY | LOAD_SPIKE | RECALL_DROP
  tenant_id    TEXT,
  payload      JSONB NOT NULL,
  applied      BOOLEAN NOT NULL DEFAULT false,
  applied_by   TEXT,
  applied_at   TIMESTAMPTZ
);
```

Never auto-applied. Available via `GET /admin/anomalies/recommendations?tenant=demo&category=SLOW_QUERY`.

### 4.4 ACL propagation reconciler

Cron every 5 min: scan `topology.acl_grants WHERE propagation_state = 'STUCK' AND propagated_at IS NULL`. For each row: call `topology.outbox.redispatch(outbox_id)`. If a row is stuck longer than `control_plane.acl.stuck_page_minutes=30`, page.

This runs *in addition to* topology's own 60-s reconciler (see `10-topology.md`) - control-plane's version is the outer safety net for cross-region or infrastructure-level failures that break topology's inner loop.

### 4.5 Ontology Lint Workflow

Temporal workflow triggered post-ingestion batch or hourly (config):

```
1. Scan all entities in the tenant's graph.
2. Orphans:    no incident edges → file review item OrphanedEntity.
3. Duplicates: cosine ≥ config.duplicate_cosine_threshold (0.92)
              AND same type
              AND (edit_distance(labels) ≤ 3 OR shared_sources ≥ 2)
              → LLM merge judgement via synanton-llm-client → file DUPLICATE_ENTITY_MERGE.
4. Broken refs: rdfs:subClassOf points to missing entity → file BrokenReference.
5. Missing frontmatter: entity missing required properties → file MissingFrontmatter.
```

Placeholder table (until `synreview` ships in Phase 5):

```sql
CREATE TABLE synreview.review_items (
  item_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id     TEXT NOT NULL,
  item_type     TEXT NOT NULL,
  producer      TEXT NOT NULL,
  payload       JSONB NOT NULL,
  status        TEXT NOT NULL DEFAULT 'OPEN',  -- OPEN | AUTO_STAGED | RESOLVED_AUTO | RESOLVED_HUMAN
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Config:

```yaml
control_plane.ontology_lint:
  duplicate_cosine_threshold: 0.92
  trigger: POST_INGEST_BATCH   # POST_INGEST_BATCH | HOURLY | MANUAL
  llm_family: synanton-analysis-mid
```

### 4.6 GPU degraded mode circuit (from Phase 4 §17/§27)

State stored in `platform_state.gpu_degraded`:

```sql
CREATE TABLE control_plane.platform_state_gpu_degraded (
  singleton   BOOL PRIMARY KEY DEFAULT true,
  state       TEXT NOT NULL DEFAULT 'RESOLVED',   -- ACTIVE | RESOLVED
  activated_at TIMESTAMPTZ,
  restored_at  TIMESTAMPTZ,
  activator    TEXT                                -- auto | operator | test
);
```

Trip conditions:

1. `synflux_embedder_gpu_queue_seconds > 5` for 3 consecutive minutes.
2. `synanton_llm_error_rate > 0.5` for 60 s.
3. Operator: `POST /admin/degraded-mode { "state": "ACTIVE", "reason": "..." }`.

Restore: `queue_seconds < 2 AND error_rate < 0.05` for 5 consecutive minutes → publish `platform_state.restored` on Kafka; trigger `RecrawlAfterRestorationWorkflow` (Phase 4 stub logs intent; Phase 5 fully implemented).

Metric: `gateway_degraded_mode_active` (0/1), `control_degraded_mode_transitions_total{from,to,activator}`.

### 4.7 Admin API (Phase 3 continued)

New endpoints:

- `GET /admin/gitops/status` (see §4.1).
- `POST /admin/gitops/apply`.
- `GET /admin/anomalies/recommendations`.
- `POST /admin/degraded-mode`.
- `GET /admin/degraded-mode`.
- `POST /admin/ontology-lint/run?tenant=demo`.
- `GET /admin/sessions/expire-pinned` (from Phase 3, kept).

All new endpoints require `support_admin` role (see `09-security.md`) and are also mirrored under `/admin/_internal/*` on synapt (see `08-synapt.md`).

---

## 5. Implementation Design

### 5.1 `GitOpsReconciler`

- Uses JGit to poll the repo; caches the last-applied commit SHA in `control_plane.gitops_state`.
- Diff strategy: parse every `tenants/*.yaml`; compute canonical JSON; compare hash against `topology.organizations.updated_at`-linked snapshot.
- Applies changes via `TopologyMutationApi.UpsertPolicy`; each policy field is a separate call so partial success is tolerable.
- Failure: `GitOpsReconcileFailed` alert page; last-good state preserved.
- Endpoint `POST /admin/gitops/apply` calls the reconciler synchronously (5-min timeout).

### 5.2 `ForecastEngine`

- Python or Java? For Phase 4 use `com.github.facebook.prophet-java` (JavaCPP binding) - avoids polyglot deployment complexity. If binding blocks, spin a sidecar Python container (`prophet-sidecar`) with gRPC surface. Decision recorded in ADR.
- Job runs every `refresh_interval_minutes=10`; fits per-metric per-tenant on 30 d history; produces 15-min ahead prediction plus daily-remaining-budget forecast.
- Writes to Postgres `control_plane.forecast_snapshots` (small table, replaced each run).

### 5.3 `AnomalyDetector`

- Reads a fixed feature set from Prometheus over the last 24 h: `latency_p95`, `error_rate`, `query_pattern_hash_top20`.
- Isolation Forest via `smile-core` (Java ML). Flags points with anomaly score > `control_plane.anomaly.iforest_threshold=0.7`.
- DBSCAN clustering to group repeat slow-query signatures.
- If pattern repeats > `control_plane.anomaly.repeat_threshold=3` in 1 h, write `admin_anomaly_recommendations` row with suggested `hnsw.ef_search` adjustment or index hint.

### 5.4 `AclReconciler`

Cron every 5 min. Query `topology.acl_grants WHERE propagation_state='STUCK'` and for each: call `topology.OutboxWorker.redispatch(outbox_id)`. Track retries in Redis key `control_plane:acl_reconcile:{outbox_id}` with 24 h TTL. Alert at 30 min stuck.

### 5.5 `OntologyLintWorkflow`

Temporal workflow with activities:

- `ScanOrphans` - Neo4j MATCH query with limit.
- `ScanDuplicates` - two-phase: candidate generation via embedding neighbours (calls `relix.similarEntities`), then per-pair LLM judgement via `synanton-llm-client`.
- `ScanBrokenReferences` - Cypher traversal.
- `EmitReviewItems` - INSERT into `synreview.review_items`.

Idempotency: workflow keyed by `sha256(tenant_id || batch_id || workflow_start_hour)`. Duplicate emission prevented by `synreview.review_items` unique constraint on `(tenant_id, item_type, sha256(payload))`.

### 5.6 GPU degraded mode circuit worker

Runs every 30 s. Reads `synflux_embedder_gpu_queue_seconds` and `synanton_llm_error_rate` from Prometheus. Trips state when thresholds met; publishes `platform_state.gpu_degraded` change to Kafka `platform_state` topic. `synflux-router` consumes to drop RECRAWL_BACKGROUND (see `03-synflux-router.md`); `gateway` consumes for trace annotations.

Restore: monitors metrics for stability; on restore, kicks off `RecrawlAfterRestorationWorkflow` (Phase 4 stub).

Config:

```yaml
control_plane.degraded_mode:
  embed_queue_trip_seconds: 5
  embed_queue_trip_consecutive_minutes: 3
  synthesis_error_rate_trip: 0.5
  synthesis_error_rate_trip_seconds: 60
  restore_queue_seconds: 2
  restore_error_rate: 0.05
  restore_dwell_seconds: 300
  warmup_seconds: 60
```

Alert: `PlatformDegradedModeActive` - warn if > 15 min, page if > 4 h.

---

## 6. Module Boundaries

| Module | Owns in Phase 4 | Does not own |
|---|---|---|
| `control-plane` | GitOps reconciler, Forecast Engine, Anomaly Detector, ACL reconciler cron, Ontology Lint Workflow, GPU degraded mode circuit, admin API endpoints | Applying policy (topology owns); consuming events for degraded mode (synflux-router, gateway own) |
| `topology` | `UpsertPolicy`, `redispatch(outbox_id)`, `topology_events` | GitOps semantics |
| `synreview` | Review items table (schema only in Phase 4); UI/workflows Phase 5 | Emitting items (control-plane does) |
| `synanton-llm-client` | LLM merge judgement in ontology lint | Lint orchestration |

---

## 7. Prerequisites

| # | Prereq | Home | Notes |
|---|---|---|---|
| 1 | Phase 3 `control-plane` DoD met (Admin API, `ModelServingDirectory`) | phase3/08 | Non-negotiable |
| 2 | `topology.UpsertPolicy` gRPC available and PGV-validated | `10-topology.md` | Yes |
| 3 | `topology.OutboxWorker.redispatch(outbox_id)` RPC | `10-topology.md` | Yes |
| 4 | Prophet Java binding OR Python sidecar container decided (ADR) | ADR | ADR before start |
| 5 | Temporal server available (introduced in Phase 3 workflows if any; else added in Phase 4) | ops | Yes |
| 6 | `smile-core:3.x` in BOM for Isolation Forest | gradle | Yes |
| 7 | Prometheus TSDB reachable for forecast history reads | `15-observability.md` | Yes |
| 8 | `synreview.review_items` schema created (Flyway) | schema | Yes |

---

## 8. Task Breakdown

| # | Task | Deliverable | Est. |
|---|---|---|---|
| CP4-1 | Flyway V2: `control_plane.gitops_state`, `platform_state_gpu_degraded`, `admin_anomaly_recommendations`, `forecast_snapshots`, `synreview.review_items` | Migration files | 1 day |
| CP4-2 | ADR: Prophet-Java binding vs Python sidecar; implement chosen path | ADR + impl | 2 days |
| CP4-3 | Implement `GitOpsReconciler` (JGit, diff, apply loop) | Class + tests | 2 days |
| CP4-4 | Implement `GET /admin/gitops/status`, `POST /admin/gitops/apply` | Controllers + tests | 0.5 day |
| CP4-5 | Implement `ForecastEngine` (per-metric per-tenant fitting, snapshot writes) | Class + tests | 2 days |
| CP4-6 | Emit `control_forecast_budget_days_remaining{tenant}`, `control_forecast_error_pct{tenant,metric}` gauges | Micrometer wiring | 0.5 day |
| CP4-7 | Implement `AnomalyDetector` (Isolation Forest, DBSCAN, recommendation writer) | Class + tests | 2 days |
| CP4-8 | Implement `GET /admin/anomalies/recommendations` | Controller + tests | 0.5 day |
| CP4-9 | Implement `AclReconciler` cron 5 min + Redis retry counter | Class + tests | 1 day |
| CP4-10 | Implement `OntologyLintWorkflow` Temporal + activities (Scan{Orphans,Duplicates,BrokenReferences,MissingFrontmatter}) | Workflow + activities + tests | 3 days |
| CP4-11 | Implement `POST /admin/ontology-lint/run` endpoint | Controller + tests | 0.5 day |
| CP4-12 | Implement GPU degraded mode circuit worker (Prometheus poll + Postgres state row + Kafka publish) | Worker + tests | 1.5 days |
| CP4-13 | Implement `GET/POST /admin/degraded-mode` endpoints | Controllers + tests | 0.5 day |
| CP4-14 | Stub `RecrawlAfterRestorationWorkflow` (logs intent; full impl Phase 5) | Workflow stub | 0.5 day |
| CP4-15 | Extend admin API auth: all new endpoints require `support_admin` role | Filter wiring | 0.25 day |
| CP4-16 | Metrics: `control_gitops_reconcile_success_ratio`, `control_forecast_error_pct`, `control_anomaly_recommendations_open`, `control_acl_reconciler_stuck_total`, `control_ontology_lint_items_emitted`, `control_degraded_mode_transitions_total` | Micrometer | 0.5 day |
| CP4-17 | Integration test `GitOpsReconcileIT`: seed repo commit → policy applied in topology within 60 s | `GitOpsReconcileIT` | 1 day |
| CP4-18 | Integration test `ForecastAccuracyIT`: seed synthetic time series → forecast within ± 20 % error | `ForecastAccuracyIT` | 1 day |
| CP4-19 | Integration test `AclReconcilerIT`: force STUCK grant; reconciler resolves within 6 min | `AclReconcilerIT` | 0.5 day |
| CP4-20 | Integration test `OntologyLintIT`: seed duplicates; workflow files review items | `OntologyLintIT` | 1 day |
| CP4-21 | Integration test `DegradedModeCircuitIT`: inject high queue → trip; drop queue → restore | `DegradedModeCircuitIT` | 1 day |

---

## 9. Testing Strategy

- **Unit:** GitOps diff canonicalisation. Isolation Forest threshold. Duplicate cosine + edit-distance rule. Circuit trip state machine.
- **Integration:** All `*IT` above with Testcontainers Postgres + Redis + Kafka + WireMock-Neo4j for lint duplicate scan.
- **Regression:** Phase 3 admin API tests (`ModelServingDirectory` resolution, tenant CRUD) unchanged.
- **Chaos:** `GitOpsRepoOutageIT` - simulate repo unreachable; reconciler alerts but preserves last-good state.

---

## 10. Configuration Surface

```yaml
# control-plane/src/main/resources/application-phase4.yaml
control_plane:
  gitops:
    enabled: true
    poll_interval_seconds: 60
    reconcile_timeout_seconds: 300
  forecast:
    window_minutes: 15
    history_days: 30
    primary: prophet
    fallback: arima
    refresh_interval_minutes: 10
    accuracy_slo_error_pct: 20
  anomaly:
    scan_interval_minutes: 5
    iforest_threshold: 0.7
    repeat_threshold: 3
  acl_reconciler:
    scan_interval_minutes: 5
    stuck_page_minutes: 30
  ontology_lint:
    duplicate_cosine_threshold: 0.92
    trigger: POST_INGEST_BATCH
    llm_family: synanton-analysis-mid
    llm_timeout_seconds: 30
  degraded_mode:
    poll_interval_seconds: 30
    embed_queue_trip_seconds: 5
    embed_queue_trip_consecutive_minutes: 3
    synthesis_error_rate_trip: 0.5
    synthesis_error_rate_trip_seconds: 60
    restore_queue_seconds: 2
    restore_error_rate: 0.05
    restore_dwell_seconds: 300
    warmup_seconds: 60
```

---

## 11. Risks and Open Questions

| Risk | Mitigation | Decision |
|---|---|---|
| Prophet-Java binding brittleness | ADR captures decision; if binding fails, fall back to Python sidecar with gRPC interface (documented alternate path) | ADR |
| GitOps reconcile applies bad policy → tenant outage | Every apply is validated by `topology.UpsertPolicy` PGV rules; on validation error, reconcile aborts and pages `GitOpsReconcileFailed` | Validation |
| Anomaly recommendations spam operators | Recommendations deduped by `sha256(category, tenant, payload)`; only new signatures written | Dedup |
| Ontology lint LLM cost | Per-tenant daily cap `control_plane.ontology_lint.max_llm_calls_per_day=100`; excess deferred to next day | Cap |
| Degraded-mode trip too sensitive → flapping | Restore-dwell 300 s prevents flapping; only paged if `> 4h` sustained | Dwell |
| ACL reconciler duplicates topology's inner loop | Coordination via Redis lock `topology:reconciler_lock`; control-plane version defers if topology's lock is held | Lock |
| GitOps `apply` synchronous timeout blocks operator | 5-min bounded; returns 202 with poll URL after that | Bound |

---

## 12. Definition of Done (Phase 4)

1. `GitOpsReconcileIT`: repo commit changes `budget_policy` → `topology.organizations` reflects it within 60 s; `control_gitops_reconcile_success_ratio` gauge visible.
2. `ForecastAccuracyIT`: forecast within ± 20 % of ground truth on synthetic series; `control_forecast_budget_days_remaining{tenant="demo"}` gauge visible.
3. Alert `ForecastCostOverrunWarning` (< 7 days remaining) fires in dev when budget nearly exhausted.
4. `AnomalyDetector` writes at least one `admin_anomaly_recommendations` row against seeded slow-query pattern in `AnomalyDetectorIT`.
5. `AclReconcilerIT`: STUCK grant resolved within 6 min by control-plane's outer reconciler (topology's inner loop disabled for test).
6. `OntologyLintIT`: seeded duplicate entities produce a `DUPLICATE_ENTITY_MERGE` row in `synreview.review_items`.
7. `DegradedModeCircuitIT`: injected high queue → `platform_state_gpu_degraded.state='ACTIVE'` within 3 min; injected recovery → `state='RESOLVED'` after 5 min dwell.
8. All new admin endpoints require `support_admin` and write `admin_audit` rows.
9. Phase 3 admin API tests pass unchanged.

---

## 13. Follow-on Phases (Signposted)

- **Phase 5** - `RecrawlAfterRestorationWorkflow` full implementation with backpressure.
- **Phase 5** - DR runbooks automated (`R-DR1` regional failover, `R-DR2` failback, `BackupVerificationWorkflow`).
- **Phase 5** - `synreview` implementation (HITL UI + 24 h staging + LLM sweep).
- **Phase 5** - GitOps 2-way reconciliation (writes back to Git when operators use `PATCH /admin/tenants/...`).
- **Phase 5** - Cost aggregator per-tenant chargeback report generator.
