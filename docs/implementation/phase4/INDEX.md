---
title: "Phase 4 - Production Hardening"
status: "planned"
last_reviewed: "2026-08-11"
---

# Phase 4 - Production Hardening

**Purpose:** Implementation plans for Phase 4 - enforce the multi-tenant security posture and cross-cutting concerns from `synanton-design-1.19.md`. ACL is real at every layer (compile-time injection, Cuckoo pre-filter, defence-in-depth trim), REST/gRPC inputs are sanitised and validated, browser security headers are enforced, cross-region residency and follow-the-sun serving become the default routing story, and Prometheus alerts back every SLO in §45.
**Audience:** Engineers, leads
**Last Updated:** 2026-08-11

## Theme

> Turn the Phase 3 two-tenant product into a platform an enterprise security team will approve. Every request is ACL-injected before it hits storage; every string that enters the JVM is sanitised; every 5xx has a runbook; every SLO in §45 has a wired alert.

## User-Facing Capability Unlocked

- ACL grants propagate < 300 ms p99 to `synquest`, `gateway`, `relix` under HIGH_SECURITY. Revoking a user immediately blocks their next search - no restart, no cache purge required.
- `POST /search` and every JSON body-carrying endpoint is scrubbed by the OWASP HTML sanitiser at the Jackson layer, and every request field is validated by Jakarta annotations before controllers see it.
- Every browser response ships CSP (enforce mode), HSTS, `X-Frame-Options: DENY`, and Trusted Types. UI code goes through `<SafeHtml />` and `<SafeExternalLink />`.
- Cross-encoder reranker is on the hot query path with a per-tenant policy (`ALWAYS | SCORE_GAP_TRIGGERED | CALLER_REQUESTED`) and Redis-cached; graceful fallback on reranker failure.
- Search results respect per-tenant `residency.allowed_regions`; queries and documents never cross a residency boundary.
- Every alert in `synanton-design-1.19.md` §45 fires against real metrics in a shipped Prometheus/Alertmanager/Grafana stack.

## Module Plans

| # | Module(s) | Plan | Scope |
|---|-----------|------|-------|
| 01 | `shared/common` EXT | [`01-shared-common.md`](./01-shared-common.md) | `SanitizingStringDeserializer`, `PgvValidatingServerInterceptor`, `@AllowHtml` marker |
| 02 | `synvault` EXT | [`02-synvault.md`](./02-synvault.md) | Tenant-scoped manifest reads, `residency.allowed_regions` enforcement on adapter selection |
| 03 | `synflux-router` EXT | [`03-synflux-router.md`](./03-synflux-router.md) | Per-tenant fair scheduling (weighted-fair queueing), priority queues |
| 04 | `synquest` EXT | [`04-synquest.md`](./04-synquest.md) | Cuckoo ACL filter, incremental index updates from `topology_events`, shard-version routing, hot-shard rebalancing, recall monitoring |
| 05 | `relix` EXT | [`05-relix.md`](./05-relix.md) | Materialized Graph Views + freshness SLO, `source_ref_count` CAS, cost calibration from real connectors |
| 06 | `planner` EXT | [`06-planner.md`](./06-planner.md) | Cross-region penalty map, follow-the-sun replica selection, context budget v1.1, rerank policy selection |
| 07 | `gateway` EXT | [`07-gateway.md`](./07-gateway.md) | Compile-time ACL injection (three-layer §40), cross-tenant synthesis cache (Redis), LLM-context sanitisation, budget-aware execution, cold-tier rehydration |
| 08 | `synapt` EXT | [`08-synapt.md`](./08-synapt.md) | Global JSON sanitisation, Jakarta Validation strict mode, CSP + companion headers, API deprecation policy |
| 09 | `security` EXT | [`09-security.md`](./09-security.md) | Keycloak/OIDC IdP integration, `IdpStatusAmortizationCache`, MCP session revalidation, worker token renewal, `support_admin` role |
| 10 | `topology` EXT | [`10-topology.md`](./10-topology.md) | HIGH_SECURITY 2-phase ACL propagation (§11), residency policy enforcement, full audit schema |
| 11 | `control-plane` EXT | [`11-control-plane.md`](./11-control-plane.md) | GitOps reconciler, Forecast Engine, Anomaly Detector, ACL propagation reconciler, Ontology Lint Workflow |
| 12 | `syntology` EXT | [`12-syntology.md`](./12-syntology.md) | SHACL validation on writes, ontology lint hooks, per-tenant ontology versioning |
| 13 | `synanton-mcp` EXT | [`13-synanton-mcp.md`](./13-synanton-mcp.md) | Full §27b tool surface, MCP session revalidation, scope enforcement |
| 14 | `syntology-admin` (UI) EXT | [`14-syntology-admin.md`](./14-syntology-admin.md) | CSP compliance, Trusted Types, `<SafeHtml />` + `<SafeExternalLink />`, first-party Synanton chat UI kickoff |
| 15 | *(cross-cutting infra)* | [`15-observability.md`](./15-observability.md) | Prometheus + Alertmanager + Grafana stack; every §45 alert wired; SLO dashboards |

## Phase 4 DoD (Composite)

Derived from `synanton-phases-plan.md` §8 and pulled forward with concrete acceptance signals:

1. **ACL propagation.** `TopologyMutationApi.grant(...)` → observable on `synquest`, `gateway`, `relix` in < 300 ms p99 for HIGH_SECURITY tenants. `AclStuckGrant` alert has never fired against the demo tenant.
2. **CSP is enforce mode.** `curl -I` against every UI route returns `Content-Security-Policy:` (not `Content-Security-Policy-Report-Only:`). CI job `csp-smoke-test` is green.
3. **Fuzz test.** A `POST /search` fuzz harness (10K variants: XSS, SQL, JSON structural attacks, unicode) finds zero bypass of the sanitisation / validation layer.
4. **Rerank on hot path.** `gateway_reranker_calls_total{outcome="ok"}` > 90 % of search requests for tenants with `rerank_policy != CALLER_REQUESTED`; `gateway_reranker_fallback_total` alerts wired.
5. **Residency enforcement.** Cross-region request from a tenant with `allowed_regions=[us-east-1]` is denied by `synquest` and `synvault`; audit row emitted.
6. **Every §45 alert wired.** `promtool check rules` passes against the Alertmanager config; each alert has (a) an alert row in `alertmanager.yml`, (b) a source metric emitted by at least one module, (c) a runbook link.
7. **HIGH_SECURITY tier working end-to-end.** A tenant flipped to HIGH_SECURITY loses cross-tenant cache reuse, gains Cuckoo pre-filter, and gets synchronous 2-phase ACL propagation. Metric `security_idp_amortization_stale_authz_total{tier="HIGH_SECURITY"}` is 0.
8. **API deprecation machinery live.** `synapt` emits `Warning: 299` header and `synapt_deprecated_field_usage_total` metric for any endpoint marked `@deprecated`; CI `deprecation-gate` job present.
9. **Budget enforcement.** A tenant that hits 100 % of `budget_policy.monthly_usd_cap` gets HTTP 429 with `Retry-After: 86400`; 70 %/90 % thresholds emit `ForecastCostOverrunWarning`.
10. **Ingestion fair scheduling.** Under a two-tenant load test (one hot tenant flooding requests, one steady tenant), the steady tenant's p95 ingest latency degrades no more than 20 % from baseline.

## External Dependencies Added

| Dependency | Purpose | First used by |
|---|---|---|
| **Redis** (cluster mode) | Gateway cross-tenant synthesis cache; MGV Redis cache for relix; cold rehydration cache | `gateway` (§23), `relix` (§21), `07-gateway.md`, `05-relix.md` |
| **Prometheus** + **Alertmanager** | SLO monitoring, alerting per §45 | All modules; wired in `15-observability.md` |
| **Grafana** | SLO dashboards, drilldowns | `15-observability.md` |
| **Keycloak** (or generic OIDC provider) | Real IdP for OIDC federation | `security` (§26) |
| **protoc-gen-validate** (build-time) | PGV rule catalogue for gRPC servers | `shared/common` (§28-§32 note) |
| **OWASP Java HTML Sanitizer** | JSON body sanitisation | `shared/common` (§24.1) |
| **DOMPurify** | UI HTML sanitisation | `syntology-admin` (§48b) |

Docker Compose stack additions:

```
deployment/docker/compose.yaml:
  redis:              image: redis:7-alpine
  prometheus:         image: prom/prometheus:v2.55.0
  alertmanager:       image: prom/alertmanager:v0.28.0
  grafana:            image: grafana/grafana-oss:11.4.0
  keycloak:           image: quay.io/keycloak/keycloak:26.0
```

Bill of materials additions (`gradle/libs.versions.toml`):

```
owaspSanitizer = "20240325.1"          # com.googlecode.owasp-java-html-sanitizer
pgvJavaStub    = "1.2.1"               # build.buf.protoc-gen-validate
resilience4j   = "2.2.0"               # already present; ensure version pinned
lettuce        = "6.5.0.RELEASE"       # Redis client (Netty-based)
```

## Cross-Plan Dependencies

Phase 4 has more inter-plan dependencies than any prior phase. The build order that avoids blocking:

1. **First wave (foundational, no downstream deps in this phase):** `01-shared-common`, `10-topology`, `09-security`, `15-observability`.
2. **Second wave (depends on wave 1):** `08-synapt` (needs sanitiser + PGV from `01`; needs `support_admin` role from `09`; needs alerts from `15`), `02-synvault` (needs residency policy schema from `10`), `03-synflux-router` (needs `budget_policy` from `10`), `12-syntology` (needs `support_admin` role from `09`).
3. **Third wave (depends on wave 2):** `04-synquest` (needs `topology_events` producer from `10`; needs Cuckoo dependency in BOM from `01`), `05-relix` (needs Redis from wave 1 + MGV freshness metric wiring from `15`), `06-planner` (needs `topology.cross_region_penalty_ms` from `10`; needs `ModelServingDirectory` extended from `11`), `11-control-plane` (needs metrics from all others; wires GitOps reconciler last).
4. **Fourth wave (top of stack):** `07-gateway` (needs everything below), `13-synanton-mcp` (needs `security` MCP session revalidation from `09` + full gateway from `07`), `14-syntology-admin` (needs CSP headers from `08`).

## How to Contribute

Phase 4 plan files follow the naming convention `NN-{module}.md`. When a plan is authored or updated:

1. Update this INDEX and the master [`../synanton-phases-plan.md`](../synanton-phases-plan.md) §13 Plan File Inventory.
2. Every plan file MUST cite `synanton-design-1.19.md` as the authoritative architecture source and name the specific section(s) it implements.
3. When a plan introduces a new metric or alert, add it to `15-observability.md` §3 Alert Catalogue and reference it from the plan's `Metrics` section.
4. When a plan introduces a new config key, prefix it with the module name (`synquest.cuckoo.bucket_size`, not `cuckoo.bucket_size`).
5. Every plan file MUST have a Definition of Done section with numbered, testable criteria that map back to §12 above.
