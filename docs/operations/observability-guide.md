# Observability guide

## Adding a metric

Emit Micrometer counters/timers from the owning module, then reference the series in `deployment/observability/prometheus/rules/*.yml` if it needs an alert.

## Adding an alert

1. Add a rule to the matching file under `deployment/observability/prometheus/rules/`.
2. Add a runbook at `docs/operations/runbooks/{alert-name}.md` with Symptom, Diagnosis, Mitigation, Rollback, Follow-up.
3. Ensure Alertmanager severity routes (`warn` → Slack, `page` → PagerDuty) still apply.

## Checking rules

```
promtool check rules deployment/observability/prometheus/rules/*.yml
```

Dev stack:

```
docker compose --profile observability up prometheus alertmanager grafana jaeger
```
