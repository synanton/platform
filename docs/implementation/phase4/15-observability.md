# 15 - Observability - Phase 4 - Prometheus + Alertmanager + Grafana, §45 Alert Catalogue Wired End-to-End

**Version:** 1.0
**Date:** 2026-08-11
**Status:** Draft for review
**Depends on:** Phase 4 metrics emissions from every module plan (`01-shared-common.md` .. `14-syntology-admin.md`).
**Scope:** Ship the cross-cutting observability infrastructure so every SLO in `synanton-design-1.19.md` §45 is visible on a dashboard and every alert row has a wired rule with a runbook link. This is not a module; it is the infrastructure and glue that makes the rest of Phase 4 provable.

---

## 1. Context and Document Alignment

| Source | Role in this plan |
|--------|-------------------|
| [synanton-design-1.19.md](../../architecture/synanton-design-1.19.md) §45 Observability - Metrics, Alerts, SLOs, Traces | Production target - alert catalogue and SLO table |
| [synanton-design-1.19.md](../../architecture/synanton-design-1.19.md) §45 v1.18 metric catalogue (data validation & XSS) | Metrics from `08-synapt.md` + `01-shared-common.md` |
| [synanton-design-1.19.md](../../architecture/synanton-design-1.19.md) §45 v1.19 metric catalogue (helper / wizard) | Metrics from `08-synapt.md` and `synanton-ops` CLI |
| [synanton-design-1.19.md](../../architecture/synanton-design-1.19.md) §47a DR runbook table | Runbook links from alert rules |
| All Phase 4 module plans | Metric emitters |

**Explicit non-goals for Phase 4:**

- No self-hosted Loki / logs stack - modules ship structured logs to stdout; production overlay routes to whatever the operator already has (documented as "bring your own").
- No distributed-tracing back-end deployment - OpenTelemetry OTLP endpoint is a config knob; dev overlay ships Jaeger, production overlay is BYO.
- No SLO error-budget policy automation (deferred).

---

## 2. Phase 4 in One Sentence

> Deploy Prometheus + Alertmanager + Grafana in the compose/K8s stack, ship a versioned rules bundle that turns every §45 alert row into a live rule with a runbook link, and publish four SLO dashboards (query path, ingest path, security, ops) plus per-module drilldowns.

---

## 3. Alert Catalogue Wiring

Every alert in `synanton-design-1.19.md` §45 must have:

1. A **source metric** emitted by at least one module (see Alert Catalogue table below).
2. A **rule** in `deployment/observability/prometheus/rules/*.yml`.
3. A **runbook link** in `docs/operations/runbooks/{alert-name}.md`.
4. A **route** in `alertmanager.yml` (severity-based: `page` → PagerDuty, `warn` → Slack `#synanton-warn`).

### 3.1 Alert Catalogue (Phase 4 target set)

| Alert | Severity | Source metric | Emitter module | Rule file | Runbook |
|---|---|---|---|---|---|
| `ForecastCostOverrunWarning` | warn | `control_forecast_budget_days_remaining < 7` | control-plane | `budget.yml` | `budget-overrun.md` |
| `ForecastCostOverrunCritical` | page | `control_forecast_budget_days_remaining < 3` | control-plane | `budget.yml` | `budget-overrun.md` |
| `TenantBudgetExhausted` | page | `gateway_budget_denied_total > 0` (per tenant, 5m) | gateway | `budget.yml` | `budget-exhausted.md` |
| `GitOpsReconcileFailed` | page | `control_gitops_reconcile_success_ratio == 0` (last 3 attempts) | control-plane | `gitops.yml` | `gitops-reconcile.md` |
| `AnomalyDetectorHighRecall` | warn | `control_anomaly_recommendations_open > 20` for 1 h | control-plane | `anomaly.yml` | `anomaly-review.md` |
| `SynfluxRouterShortRetention` | warn | `synflux_router_short_retention == 1` | synflux-router | `ingest.yml` | `router-retention.md` |
| `SynfluxDegradedRecrawlStalled` | page | `synflux_degraded_recrawl_progress` unchanged 30 min | synflux | `degraded.yml` | `degraded-mode.md` |
| `PlatformDegradedModeActive` | warn (>15 m) / page (>4 h) | `gateway_degraded_mode_active == 1` | control-plane | `degraded.yml` | `degraded-mode.md` |
| `AclStuckGrant` | page | `topology_grant_state_total{state="STUCK"} > 0` for 30 m | topology, control-plane | `acl.yml` | `acl-stuck.md` |
| `TopologyProjectionStale` | warn | `topology_projection_lag_seconds > 5` | topology | `topology.yml` | `topology-projection.md` |
| `OutboundTokenSlaBreached` | warn | `security_outbound_deny_slo_total > 5` in 5 m | security | `security.yml` | `outbound-slo.md` |
| `IdpAmortizationStaleAuthzHigh` | page | `security_idp_amortization_stale_authz_total{tier="HIGH_SECURITY"} > 5` in 1 h | security | `security.yml` | `idp-amortization.md` |
| `McpSessionRevalidationLag` | warn | `synanton_mcp_session_active` / `synanton_mcp_session_revalidations_total` ratio | synanton-mcp | `mcp.yml` | `mcp-revalidation.md` |
| `McpScopeDeniedSpike` | warn | `synanton_mcp_scope_denied_total > 20/min` | synanton-mcp | `mcp.yml` | `mcp-scope.md` |
| `RerankerFallbackHigh` | warn | `gateway_reranker_fallback_total` rate > 5% for 15m | gateway | `gateway.yml` | `reranker-fallback.md` |
| `ColdSynthesisDegradedRateHigh` | warn | `gateway_cold_synthesis_degraded_total / total_synthesis > 5%` for 30m | gateway | `gateway.yml` | `cold-rehydration.md` |
| `SynaptSanitizationHighRate` | warn | `synapt_sanitization_applied_total` rate > 10/s | synapt | `security.yml` | `sanitization.md` |
| `GrpcValidationBurst` | warn | `grpc_validation_failed_total > 100/min` across services | shared/common | `security.yml` | `grpc-validation.md` |
| `CspViolationBurst` | warn | `ui_csp_violation_report_total > 20 in 5 min` | synapt | `csp.yml` | `csp-violations.md` |
| `HelperDestructiveOpsRate` | page | `helper_destructive_ops_total > 10 in 15 min` | synapt / control-plane | `admin.yml` | `helper-destructive.md` |
| `HelperAuthFailureSpike` | page | `helper_auth_failure_total{reason="invalid_key" OR "wrong_role"} > 20 in 5 min` | synapt / control-plane | `admin.yml` | `helper-auth.md` |
| `ApiKeyPastExpiry` | warn | `security_api_key_active_total{key_class="support",days_to_expiry <= 14}` | security | `security.yml` | `api-key-expiry.md` |
| `synquest_recall_below_slo` | warn | `synquest_recall_at_10 < 0.90` 7d rolling | synquest | `query.yml` | `synquest-recall.md` |
| `relix_mgv_lag_seconds` | page | `relix_mgv_lag_seconds > 30` | relix | `query.yml` | `mgv-lag.md` |
| `SupportAdminOnBehalfOfRate` | page | `admin_audit` rows `on_behalf_of != actor.tenant_id` > 20/h | synvault / topology | `admin.yml` | `support-admin-review.md` |
| `SynfluxRouterFairShareStuck` | warn | `synflux_router_fair_share_used_ratio == 0` for tenant with weight>0 > 5m | synflux-router | `ingest.yml` | `router-fairshare.md` |
| `SynfluxRouterBackpressureDrops` | warn | `synflux_router_backpressure_drops_total > 100/min` | synflux-router | `ingest.yml` | `router-backpressure.md` |
| `DeprecationGateProbeFailed` | warn | `deprecation_gate_prometheus_unreachable` for 1h | synapt CI hook | `admin.yml` | `deprecation-gate.md` |

### 3.2 Rule file layout

```
deployment/observability/prometheus/
  prometheus.yml
  rules/
    budget.yml
    gitops.yml
    anomaly.yml
    ingest.yml
    degraded.yml
    acl.yml
    topology.yml
    security.yml
    mcp.yml
    gateway.yml
    csp.yml
    admin.yml
    query.yml
    slo.yml           # SLO burn-rate rules
alertmanager/
  alertmanager.yml
grafana/
  provisioning/
    datasources/
      prometheus.yml
    dashboards/
      slo-query.json
      slo-ingest.json
      slo-security.json
      slo-ops.json
      per-module/
        gateway.json
        synquest.json
        topology.json
        synflux-router.json
        control-plane.json
        security.json
        synanton-mcp.json
```

Example rule (`budget.yml`):

```yaml
groups:
  - name: budget
    rules:
      - alert: ForecastCostOverrunWarning
        expr: control_forecast_budget_days_remaining < 7
        for: 10m
        labels: { severity: warn }
        annotations:
          summary: "Tenant {{ $labels.tenant }} budget forecast < 7 days"
          runbook: "https://docs.synanton.org/runbooks/budget-overrun"
      - alert: ForecastCostOverrunCritical
        expr: control_forecast_budget_days_remaining < 3
        for: 5m
        labels: { severity: page }
        annotations:
          summary: "Tenant {{ $labels.tenant }} budget forecast < 3 days"
          runbook: "https://docs.synanton.org/runbooks/budget-overrun"
```

---

## 4. SLO Table (from §45; enforced here)

| Domain | SLO | Metric | Dashboard |
|---|---|---|---|
| Query hot path | p95 `POST /query` < 200 ms | `http_server_requests_seconds_bucket{uri="/query",...}` | `slo-query.json` |
| Query cold rehydration | p95 < 500 ms | `gateway_cold_retrieval_p95_ms` | `slo-query.json` |
| MGV freshness | p95 < 200 ms | `relix_mgv_freshness_p95_ms` | `slo-query.json` |
| Reranker availability | > 99.9 % (2xx + fallback) | `gateway_reranker_calls_total{outcome!="error"} / total` | `slo-query.json` |
| Outbound token exchange | p99 ≤ 100 ms | `security_outbound_exchange_seconds_bucket` | `slo-security.json` |
| ACL propagation HIGH_SECURITY | p99 < 300 ms | `topology_grant_ack_lag_ms` | `slo-security.json` |
| Ingest queue lag | Steady tenant p95 latency ≤ 1.2× baseline | `synflux_router_scheduler_wait_ms{tenant}` | `slo-ingest.json` |
| Cascade GDPR (Phase 5 preview) | p99 ≤ 45 s | `relix_cascade_end_to_end_seconds_bucket` | `slo-ops.json` |

Each SLO is wired via a burn-rate alert (`slo.yml`):

```yaml
- alert: QuerySloBurnFast
  expr: |
    (
      sum(rate(http_server_requests_seconds_count{uri="/query",outcome!~"SUCCESS"}[5m]))
      / sum(rate(http_server_requests_seconds_count{uri="/query"}[5m]))
    ) > (14.4 * (1 - 0.999))
  for: 5m
  labels: { severity: page }
```

---

## 5. Grafana Dashboards

Four top-level dashboards match the SLO domains:

- **SLO Query** - end-to-end query latency, cache hit ratio, rerank pipeline, MGV freshness, cold rehydration.
- **SLO Ingest** - fair-share heatmap per tenant, router lag, degraded-mode indicator, RECRAWL vs INTERACTIVE partition throughput.
- **SLO Security** - ACL propagation lag, IdP amortisation stale ratio, outbound token SLO, MCP session revalidation lag, sanitizer/validation rejection rates, CSP violations.
- **SLO Ops** - GitOps reconcile status, forecast accuracy, anomaly recommendations open, admin_audit rate, degraded-mode circuit state.

Per-module drilldowns (`per-module/*.json`) provide operator-level detail: request rate, error rate, histogram buckets, GC pauses.

All dashboards versioned in-repo; loaded by Grafana via provisioning. Every dashboard's URL is stable (`/d/{uid}/...`).

---

## 6. Structured Logs & Trace Propagation

- Every module logs JSON to stdout via Logback+`logstash-logback-encoder`.
- Log fields include `tenant_id`, `subject_id`, `trace_id`, `span_id`, `service`, `env`.
- OpenTelemetry auto-instrumentation exports via OTLP (env: `OTEL_EXPORTER_OTLP_ENDPOINT`).
- Dev overlay includes Jaeger; production overlay is BYO.

`TraceIdFilter` from Phase 2 continues to propagate `X-Trace-Id` in HTTP/gRPC headers.

---

## 7. Deployment Surface

`deployment/docker/compose.yaml` (excerpt):

```yaml
services:
  prometheus:
    image: prom/prometheus:v2.55.0
    volumes:
      - ./observability/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml
      - ./observability/prometheus/rules/:/etc/prometheus/rules/
    ports: ["9090:9090"]
  alertmanager:
    image: prom/alertmanager:v0.28.0
    volumes:
      - ./observability/alertmanager/alertmanager.yml:/etc/alertmanager/alertmanager.yml
    ports: ["9093:9093"]
  grafana:
    image: grafana/grafana-oss:11.4.0
    volumes:
      - ./observability/grafana/provisioning:/etc/grafana/provisioning
      - ./observability/grafana/dashboards:/var/lib/grafana/dashboards
    ports: ["3000:3000"]
  jaeger:
    image: jaegertracing/all-in-one:1.60
    ports: ["16686:16686","4317:4317","4318:4318"]
```

Kubernetes overlay (`deployment/k8s/observability/`) ships equivalent manifests with:

- Prometheus ServiceMonitor CRDs for every module.
- Alertmanager routes to real PagerDuty / Slack integrations (secrets from external secret manager).
- Grafana ingress with OIDC login through Keycloak (`09-security.md`).

---

## 8. Module Boundaries

| Module | Owns in Phase 4 | Does not own |
|---|---|---|
| `15-observability` (this plan) | Prometheus deployment, alert rules, dashboards, Alertmanager routes, runbook stubs, OTel wiring | Metric emission (each module owns its own) |
| All emitting modules (§01..§14) | Their metrics, per their plans' Metrics sections | Rule wiring |
| Operators | Filling in real PagerDuty routing keys and Slack webhooks in prod | Dev secrets |

---

## 9. Prerequisites

| # | Prereq | Home | Notes |
|---|---|---|---|
| 1 | Every Phase 4 module plan emits its declared metrics | modules 01-14 | Non-negotiable |
| 2 | Docker Compose supports adding 4 new services (Prometheus, Alertmanager, Grafana, Jaeger) | ops | Yes |
| 3 | K8s cluster has PVC available for Prometheus TSDB (production) | ops | Yes |
| 4 | External PagerDuty account + Slack workspace with `#synanton-warn` channel | ops | Yes (production only) |

---

## 10. Task Breakdown

| # | Task | Deliverable | Est. |
|---|---|---|---|
| OB4-1 | Bring up Prometheus/Alertmanager/Grafana/Jaeger in compose profile `observability` | compose + smoke script | 1 day |
| OB4-2 | Write scrape configs for every module (`prometheus.yml`) | Config file | 0.5 day |
| OB4-3 | Author all 15 rule files (`budget.yml` .. `slo.yml`) | Rule files | 2 days |
| OB4-4 | Author `alertmanager.yml` with severity-based routes (warn → Slack, page → PagerDuty) | Config file | 0.5 day |
| OB4-5 | Author 4 SLO dashboards (`slo-query`, `slo-ingest`, `slo-security`, `slo-ops`) | Dashboard JSON | 2 days |
| OB4-6 | Author 7 per-module drilldown dashboards | Dashboard JSON | 2 days |
| OB4-7 | Author runbook stubs (one MD per alert, ~20 files) with symptom / diagnosis / mitigation / rollback template | Runbook files | 2 days |
| OB4-8 | Wire OpenTelemetry OTLP exporter into every module via `shared/common` auto-config | Auto-config | 1 day |
| OB4-9 | Add K8s overlays: ServiceMonitor CRDs, Alertmanager routes, Grafana OIDC ingress | K8s manifests | 1 day |
| OB4-10 | CI job `promtool check rules` on the rules directory | Workflow | 0.25 day |
| OB4-11 | CI job `promtool test rules` with per-rule unit tests | Rule test files + workflow | 1 day |
| OB4-12 | Integration test `AlertFiresIT`: seed a metric that violates a rule; assert Alertmanager receives it | `AlertFiresIT` (uses Testcontainers Prometheus + Alertmanager) | 1 day |
| OB4-13 | Documentation `docs/operations/observability-guide.md` (adding a new metric/alert/dashboard) | Doc | 0.5 day |

---

## 11. Testing Strategy

- **Static:** `promtool check rules` on every commit; `promtool test rules` with synthetic time series proving each rule triggers/resolves as designed.
- **Integration:** `AlertFiresIT` seeds a metric into a real Prometheus + Alertmanager stack (Testcontainers); asserts the alert reaches a Webhook receiver.
- **Runbook drills:** Each new alert requires a runbook file to exist (CI gate: `runbook-required` job scans rule files and verifies runbook exists).
- **Dashboard smoke:** `grafana-render` job renders each dashboard PNG in CI (via `grafana-image-renderer`); fails if a panel returns an error.
- **Cross-plan gate:** `alert-coverage-gate` CI job asserts every metric declared in a Phase 4 module plan appears in either Prometheus (via `up`+relabelling) or a rule file.

---

## 12. Configuration Surface

Provisioning is code-owned (in-repo). Runtime overrides limited to secrets:

```yaml
# deployment/observability/prometheus/prometheus.yml
global:
  scrape_interval: 15s
  evaluation_interval: 15s
rule_files:
  - /etc/prometheus/rules/*.yml
scrape_configs:
  - job_name: 'synanton'
    kubernetes_sd_configs:
      - role: pod
    relabel_configs:
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape]
        action: keep
        regex: 'true'
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_path]
        action: replace
        target_label: __metrics_path__
        regex: (.+)
```

Alertmanager receivers via `SECRET_PAGERDUTY_KEY`, `SECRET_SLACK_WEBHOOK` env vars.

---

## 13. Risks and Open Questions

| Risk | Mitigation | Decision |
|---|---|---|
| Alert fatigue if too many warn rules | Every warn rule requires a Slack channel routing; sustained warn frequency reviewed monthly; noisy rules re-tuned | Process |
| Prometheus retention outgrows PVC on production | Production overlay uses remote-write to Thanos/Mimir (documented, not shipped in Phase 4); dev uses default 15 d retention | Doc |
| Runbook stubs stay stubs | CI gate requires each new alert file to have a runbook file present; content quality is a review concern (not enforceable in code) | Gate presence |
| Metric cardinality explosion (per-tenant labels) | Recording rules aggregate to platform-wide series where per-tenant is unnecessary; documented naming convention | Convention |
| PagerDuty misconfig routes real pages to test env | Route keys env-scoped; dev alertmanager uses a null receiver by default | Env scope |
| Grafana OIDC login blocks operators on Keycloak outage | Grafana admin user break-glass with rotating password in secret manager | Break-glass |

---

## 14. Definition of Done (Phase 4)

1. Prometheus, Alertmanager, Grafana, Jaeger all running in `deployment/docker/compose.yaml` under `--profile observability`.
2. `promtool check rules deployment/observability/prometheus/rules/` returns 0.
3. `promtool test rules` passes for every rule.
4. Every alert in §3.1 has (a) a source metric emitted, (b) a rule file entry, (c) a runbook file in `docs/operations/runbooks/`, (d) an Alertmanager route.
5. `alert-coverage-gate` CI job asserts no orphaned metrics or missing rules.
6. Four SLO dashboards (`slo-query`, `slo-ingest`, `slo-security`, `slo-ops`) render without errors in dev.
7. `AlertFiresIT` passes: seeded metric violation reaches a Webhook receiver via Alertmanager.
8. OpenTelemetry traces from `synapt → gateway → synquest` are stitched end-to-end in Jaeger for a `POST /search` call.
9. Runbook template documents at minimum: **Symptom**, **Diagnosis (queries to run)**, **Mitigation (immediate)**, **Rollback**, **Follow-up**.
10. Documentation `docs/operations/observability-guide.md` shipped.

---

## 15. Follow-on Phases (Signposted)

- **Phase 5** - Thanos/Mimir remote-write for long-term storage.
- **Phase 5** - SLO error-budget policy automation (deploy freezes when budget exhausted).
- **Phase 5** - Log aggregation stack integration (Loki or BYO Splunk).
- **Phase 5** - RED (Rate/Errors/Duration) auto-dashboards generated from every registered gRPC/HTTP endpoint.
