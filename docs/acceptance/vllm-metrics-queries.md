# #723 evaluation metrics and log query surface

## Structured extraction log

Emitted as `extraction_completed` JSON after every extraction run:

```json
{
  "event": "extraction_completed",
  "tenant_id": "eval-llm",
  "tenant_class": "eval",
  "source_version_id": "...",
  "document_id": "...",
  "engine": "LlmExtractionAdapter",
  "provider": "llm",
  "provider_source": "tenant-override",
  "duration_ms": 12450,
  "durations_ms": {"extraction": 12450, "graph": 400, "indexing": 200, "promotion": 0, "activation": 0},
  "entity_mentions": 8,
  "normalized_entities": 8,
  "relation_candidates": 3,
  "relations_accepted": 2,
  "relations_rejected": 1,
  "rejection_reason_codes": ["unknown_predicate"],
  "processing_outcome": "COMPLETED"
}
```

`tenant_id`, `source_version_id`, and document identity stay in logs, not metric
labels. `tenant_class` is bounded to `demo`, `eval`, or `other`.

`durations_ms` uses non-negative integers. `extraction` and `graph` are measured
in this consumer; `indexing` is `search_projected_at - observed_at` (0 when
unknown); `promotion` and `activation` are 0 here because they occur after this
event and are owned by later lifecycle events.

## Metrics

| Metric | Labels | Type | Purpose |
|---|---|---|---|
| `extraction_completed_total` | `provider`, `tenant_class`, `outcome` | Counter | Completion volume by provider/class/outcome |
| `extraction_duration_seconds` | `provider` | Timer/histogram | Extraction + graph latency |
| `extraction_entities_count` | `provider` | Distribution summary | Entity count |
| `extraction_relations_candidates_count` | `provider` | Distribution summary | Candidate count |
| `relation_acceptance_ratio` | `provider` | Gauge | Last accepted/candidates ratio per provider |
| `lifecycle_regressions_total` | `type`, `provider` | Counter | Lifecycle regressions (hard gate = 0) |
| `attribution_errors_total` | `error_type`, `provider` | Counter | #720-style attribution errors |

No `tenant_id` or `source_version_id` metric labels. Do not add them; query logs
for per-tenant/per-document detail.

## Eval-report query patterns

- Completion volume: `sum by (provider, outcome) (increase(extraction_completed_total[7d]))`
- Acceptance rate: `avg by (provider) (relation_acceptance_ratio)` (gauge is per provider; use the log events for per-document ratios)
- Latency: `histogram_quantile(0.95, sum by (provider, le) (rate(extraction_duration_seconds_bucket[7d])))`
- Lifecycle gate: `sum(increase(lifecycle_regressions_total[7d]))` must be `0`
- Attribution gate: `sum by (provider) (increase(attribution_errors_total[7d]))`
- Human precision/recall sample: log query `event=extraction_completed AND tenant_class=eval`, take per-document entity lists and counts.
- Per-document rejection detail: log query `event=extraction_completed AND tenant_class=eval` and read `rejection_reason_codes`.

Rejection codes are the actual validation vocabulary, not a parallel taxonomy:
`domain_mismatch`, `range_mismatch`, `unknown_subject_type`,
`unknown_object_type`, `unknown_predicate` from the relation validator;
`MISSING_ENDPOINT` and `ONTOLOGY_INVALID` from graph commit. Promotion-consumer
reasons (`EXTRACTED_EVIDENCE_NOT_FOUND`, `MISSING_ACTIVE_RESOLUTION`,
`MULTIPLE_ACTIVE_RESOLUTIONS`, `EXTRACTED_RELATION_NOT_FOUND`) belong to later
lifecycle logs.

The structured logs are the source of truth for per-tenant/per-version attribution;
the metrics are for bounded aggregate gates.

## Scrape status

As of 2026-11-17, a minimal Prometheus deployment (`prometheus:9090`) scrapes
`synflux:8090/actuator/prometheus` with a static target. The target is `up` and
`extraction_completed_total{tenant_class="eval"}` is queryable. See
[`prometheus-scrape-736.md`](./prometheus-scrape-736.md).
