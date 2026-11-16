# #723 production evaluation — pre-flight record

**Status:** BLOCKED — no ingestion started.
**Code-complete:** 8/11 (pieces 1–3 implemented; piece 3 pending merge).
**Operationally verified:** 5/11 (deploy, scrape, and eval tenant provisioning still pending).
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
| 5 | Eval tenants provisionable | ✅ (CLI) | ❌ | Provision after V33 deploy |
| 6 | Provider overrides per tenant | ✅ (piece 1) | ❌ | Piece 1 image not deployed |
| 7 | Retry provider consistency | ✅ (piece 1.5) | ❌ | Piece 1.5 image not deployed |
| 8 | Tenant-aware metric capture | ✅ (piece 3) | ❌ | No Prometheus consumer (#736) |
| 9 | Multi-tenant polling | ❌ | ❌ | Piece 4 |
| 10 | Real ≥100-page PDF | ❌ | ❌ | Source procurement (#724) |
| 11 | Extended observation corpus (30–50 docs) | ❌ | ❌ | Source procurement |

The fifth operationally verified item is the recorded wall-clock budget; it is
not one of the 11 product/eval gates.

Pieces 1–3 change the code-complete count from 5/11 to 8/11. The operational
count remains 5/11 until the pieces are deployed, V33 is verified, eval tenants
are provisioned, and metrics are scraped. The eval cannot start until
operational is 11/11.

## Remaining blockers

- **Piece 4 (multi-tenant polling):** consumers still use a single configured
  tenant. Piece 4 adds authoritative registry polling, true concurrent
  multi-tenant execution, and contamination tests.
- **Eval tenant provisioning:** piece 2's CLI exists, but no eval tenant has
  been provisioned. The live schema predates V33.
- **Metrics scrape:** piece 3 exposes `/actuator/prometheus`, but the cluster has
  no Prometheus pod, ServiceMonitor, or monitoring CRD. Follow-up: #736. Until
  it lands, metrics are exposed but not scraped.
- **Corpus gaps:** #724 real PDF and 30–50 extended-observation documents remain
  unsourced.

## Resolved since the original blocker

- Per-tenant provider selection and persistence are implemented (pieces 1 and
  1.5): `tenant_provider_config`, `source_versions.extraction_provider`,
  fail-closed resolution, and retry reuse of the persisted provider.
- Provisioning/cleanup CLI is implemented (piece 2) with ordered steps, audit
  output, and partial-failure tests.
- Tenant-aware logs and bounded metrics are implemented (piece 3), including
  `demo | eval | other` classes and per-stage durations.

## Deploy gates — pieces 1–3 and V33

Pieces 1–3 deploy as one image. V33 adds `tenant_provider_config` and
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

Rollback: redeploy the previous image. V33's new table/column are additive and
inert to old code, so no destructive schema rollback is required. Existing
`source_versions` rows and the `tenant_registry` contents must be preserved;
verify with `SELECT COUNT(*) FROM source_versions` and
`SELECT tenant_id FROM tenant_registry` before and after deploy.

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
