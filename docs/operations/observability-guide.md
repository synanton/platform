# Observability guide

## Homelab Kubernetes logs

Stage 0 inventory and design for the centralized Alloy + Loki + Grafana stack is in
[`docs/implementation/observability/924-stage0-inventory-and-design.md`](../implementation/observability/924-stage0-inventory-and-design.md).
It is design-only; storage, ingress, authentication, and cleanup prerequisites must be
resolved before deployment.

## Adding a metric

Emit Micrometer counters/timers from the owning module, then reference the series in `deployments/observability/prometheus/rules/*.yml` if it needs an alert.

## Adding an alert

1. Add a rule to the matching file under `deployments/observability/prometheus/rules/`.
2. Add a runbook at `docs/operations/runbooks/{alert-name}.md` with Symptom, Diagnosis, Mitigation, Rollback, Follow-up.
3. Ensure Alertmanager severity routes (`warn` → Slack, `page` → PagerDuty) still apply.

## Checking rules

```
promtool check rules deployments/observability/prometheus/rules/*.yml
```

Dev stack:

```
docker compose --profile observability up prometheus alertmanager grafana jaeger
```
