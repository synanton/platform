# #736 Prometheus scrape acceptance

**Date:** 2026-11-17
**Deployment:** `deployments/homelab/k8s/local-real-demo/prometheus.yaml`
**Image:** `local-registry:5000/prometheus/prometheus:v2.55.1`
**Retention:** 15 days; 10Gi Longhorn PVC (`prometheus-data`).

## Scrape target

```text
job=synflux health=up lastScrape=2026-11-17T07:36:15Z
scrapeUrl=http://synflux:8090/actuator/prometheus
```

## Query evidence

After one eval-smoke extraction:

```text
extraction_completed_total{
  __name__="extraction_completed_total",
  instance="synflux:8090",
  job="synflux",
  outcome="COMPLETED",
  provider="regex",
  tenant_class="eval"
} 1
```

The target and series are available through `prometheus:9090` inside the
cluster and via an operator port-forward. The metrics query surface is
documented in [`vllm-metrics-queries.md`](./vllm-metrics-queries.md).

## Scope

- Scrapes synflux only. Synquest and Relix actuator Prometheus endpoints are not
  exposed yet; their bounded counters are not part of this acceptance.
- Prometheus uses a static target, not annotation discovery or a ServiceMonitor.
- This closes the "exposed but not scraped" limitation for the synflux eval
  metrics.
