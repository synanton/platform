# #723 production evaluation — pre-flight record

**Status:** BLOCKED — no #723 evaluation ingestion started.
**Code-complete:** 9/11 (pieces 1–4 merged).
**Operationally verified:** 8/11 (live deploy, V33, and registry polling verified; scrape and corpus gaps remain).
**Plan:** [`vllm-production-eval-plan.md`](./vllm-production-eval-plan.md)
**Manifest:** [`vllm-production-eval-corpus.tsv`](./vllm-production-eval-corpus.tsv)
**New-entity list:** [`vllm-production-eval-new-entities.tsv`](./vllm-production-eval-new-entities.tsv)
**Umbrella:** #728

## 11-item pre-flight checklist

Code-complete means the implementation and tests are merged. Operationally
verified means the item has been exercised against the deployed environment.

| # | Item | Code-complete | Operationally verified | Blocker |
|---|---|---|---|---|
| 1 | Corpus staged + SHA-256 verified | ✅ | ✅ | — |
| 2 | 15 new-entity candidates pre-identified | ✅ | ✅ | — |
| 3 | Large-PDF timeout path checked | ✅ | ✅ | — |
| 4 | Tenant-configurable architecture (Design A) | ✅ | ✅ (design confirmed) | — |
| 5 | Eval tenants provisionable | ✅ (CLI) | ✅ (eval-smoke) | Actual eval tenant set not provisioned |
| 6 | Provider overrides per tenant | ✅ (piece 1) | ✅ (eval-smoke regex) | LLM override not live-tested |
| 7 | Retry provider consistency | ✅ (piece 1.5) | ❌ | No live retry exercise |
| 8 | Tenant-aware metric capture | ✅ (piece 3) | ✅ (actuator + Prometheus) | — |
| 9 | Multi-tenant polling | ✅ (piece 4) | ✅ (eval-smoke + demo) | — |
| 10 | Real ≥100-page PDF | ❌ | ❌ | Source procurement (#724) |
| 11 | Extended observation corpus (30–50 docs) | ❌ | ❌ | Source procurement |

Pieces 1–4 change the code-complete count from 5/11 to 9/11 and the operational
count to 8/11. Remaining operational gates: a live retry-provider exercise,
the real ≥100-page PDF (#724), and the 30–50 extended-observation documents.
The eval cannot start until operational is 11/11.

## Remaining blockers

- **Retry provider consistency:** implemented and unit-tested, but not yet
  exercised against a live failed extraction and retry.
- **Metrics scrape:** resolved by #736; the minimal Prometheus target is `up`
  and `extraction_completed_total` is queryable. Historical retention is 15 days.
- **Corpus gaps:** #724 real PDF and 30–50 extended-observation documents remain
  unsourced.
- **Actual eval tenants:** `eval-smoke` is provisioned as the step-8 smoke
  tenant; the planned eval tenant set still needs provisioning.

## Resolved since the original blocker

- Per-tenant provider selection and persistence are implemented (pieces 1 and
  1.5): `tenant_provider_config`, `source_versions.extraction_provider`,
  fail-closed resolution, and retry reuse of the persisted provider.
- Provisioning/cleanup CLI is implemented (piece 2) with ordered steps, audit
  output, and partial-failure tests.
- Tenant-aware logs and bounded metrics are implemented (piece 3), including
  `demo | eval | other` classes and per-stage durations.
- Registered-tenant polling is implemented (piece 4); extraction fans out per
  tenant, and the other consumers poll per tenant.

## Deploy gates — pieces 1–4 and V33

Pieces 1–4 deploy as one image (synflux, synquest, relix). V33 adds `tenant_provider_config` and
`source_versions.extraction_provider`.

Post-deploy order:

1. V33 migration completes: `kubectl logs deploy/synflux | grep -iE 'V33|migration'`.
2. Pod ready: `kubectl wait --for=condition=Ready pod -l app=synflux`.
3. `/actuator/health` returns 200.
4. `/v1/search` without a tenant/token still returns 401 (filter not over-widened).
5. `GET /actuator/prometheus` returns 200 with expected metric names.
6. Prometheus discovers synflux with `health=up` (#736; currently unavailable).
7. Every query in [`vllm-metrics-queries.md`](./vllm-metrics-queries.md) is non-empty.
8. Demo search still returns existing results.
9. A newly registered eval tenant is polled by extraction (`EXTRACTION_CONSUMER_TENANT=*`) without a demo tenant restart.

Rollback: redeploy the previous image. V33's new table/column are additive and
inert to old code, so no destructive schema rollback is required. Existing
`source_versions` rows and the `tenant_registry` contents must be preserved.

## Live verification — 2026-11-16

- V33 present: `tenant_provider_config` table and
  `source_versions.extraction_provider` column.
- Existing data preserved: 153 total `source_versions` (152 demo + 1
  eval-smoke smoke document); `tenant_registry` = `demo`, `eval-smoke`.
- Filter still blocks: `/actuator/env` and `/v1/search` without auth return 401.
- `/actuator/prometheus` returns 200; no `tenant_id`, `source_version_id`, or
  `document_id` labels.
- `eval-smoke` ingestion completed through the registry-mode consumer:
  `extraction_completed` with `tenant_class=eval`; metrics include
  `extraction_completed_total{tenant_class="eval",provider="regex",outcome="COMPLETED"}`.
- Demo search still returns 8 results.
- Prometheus scrape is live (#736): target `synflux` is `up` and
  `extraction_completed_total` is queryable with 15-day retention.

## Extended observation corpus

- Unique corpus: 25 documents.
- Planned idempotency ingestions: 25 × 4 = 100 ingestions across the three scopes.
- Additional real-world documents: 30–50 not yet sourced. Without them, Stage 5
  measures idempotency and the same controlled corpus, not coverage.

## Metric capture

- Piece 3 emits `extraction_completed` JSON and bounded Micrometer metrics.
- The endpoint is exposed and filter-scoped, but not scraped: no Prometheus
  deployment exists in the cluster (#736).
- The canary runner is still demo/port-hardcoded and has not been exercised on
  an eval tenant; do that after provisioning.

## #724 large PDF

- Generated, extractable-text PDFs: `large-final-100p.pdf` (102 pages),
  `large-final-120p.pdf` (120 pages).
- No real ≥100-page PDF exists in the repository. One real production-like PDF
  must be sourced before #724 can close.

## Wall-clock budget

| Stage | Estimate |
|---|---|
| Regex baseline (25 docs) | 2–4 hours |
| LLM comparison (25 docs) | 4–8 hours |
| #724 + #725 sub-checks | 0.5–1 day |
| Extended observation | ≥1 week / ≥100 ingestions |
| Report | 0.5 day |
| Total to report | ~10 working days if no new blocker |

## Environment

- `EXTRACTION_PROVIDER=regex` everywhere; no provider flag changed for ingestion.
- vLLM Ready 1/1.
- Demo data untouched; no eval ingestion started.
- Corpus staged under `docs/acceptance/artifacts/vllm-production-eval/corpus/`
  (ignored; hashes in the manifest).
