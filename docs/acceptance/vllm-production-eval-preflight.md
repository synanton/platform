# #723 production evaluation — pre-flight record

**Status:** BLOCKED — no ingestion started.
**Plan:** [`vllm-production-eval-plan.md`](./vllm-production-eval-plan.md)
**Manifest:** [`vllm-production-eval-corpus.tsv`](./vllm-production-eval-corpus.tsv)
**New-entity list:** [`vllm-production-eval-new-entities.tsv`](./vllm-production-eval-new-entities.tsv)
**Blocker:** #728 (tenant/provider-per-tenant execution support)

## Five pre-flight items

| # | Item | Result | Evidence |
|---|---|---|---|
| 1 | Tenant architecture (Design A: provider per tenant) | Decided, **not supported** | Provider is deployment-global (`EXTRACTION_PROVIDER`); consumers poll one tenant each |
| 2 | Extended observation corpus | **Partial** | 25 unique + planned 4× re-ingest = 100 ingestions; 30–50 additional documents not sourced |
| 3 | Metric capture script | **Not ready** | Canary runner exists but is not tenant-aware or tested on an eval tenant |
| 4 | #724 real PDF | **Blocked** | Repo has no real ≥100-page PDF; 102/120-page PDFs are generated |
| 5 | #725 15 candidates | **Ready** | 15 pre-identified entities in `vllm-production-eval-new-entities.tsv` |

## 11-item pre-flight checklist

- [x] Tenant architecture decided (Design A: provider per tenant)
- [ ] Tenant-level provider override confirmed working
- [ ] Eval tenants provisioned (`tenant-regex`, `tenant-llm`, `tenant-negative-controls`)
- [x] Corpus staged locally with SHA-256 verified (25/25)
- [ ] Metric capture script tested on one document
- [x] Large-PDF timeout path checked (per-call LLM timeout 120s; ingestion is async; no 30-minute document-level timeout found; budget recorded)
- [ ] Extended observation corpus sourced (25 re-ingest planned; 30–50 new documents not sourced)
- [ ] #724 real PDF source identified
- [x] #725 15 new-entity candidates listed
- [x] Wall-clock budget recorded
- [ ] All eval tenants start empty

**Green: 5/11. Blocked: 5. Partial: 1.** The smoke test cannot start until the tenant/provider blocker and eval provisioning are resolved.

## Blocker — tenant architecture and provider override

The plan's Design A requires two eval tenants to run different providers on identical inputs while demo/production stay on regex. Current code is single-tenant and deployment-global:

- `java/synflux/src/main/resources/application.yml:56` — `provider: ${EXTRACTION_PROVIDER:regex}` (one provider per deployment).
- `java/synflux/src/main/java/org/synanton/synflux/config/ExtractionConsumerProperties.java` — a single `String tenant`.
- `java/synflux/src/main/resources/application.yml:63` — `tenant: ${EXTRACTION_CONSUMER_TENANT:demo}`.
- `java/synquest/src/main/resources/application.yml:51,86` — single seed/demo tenant and single resolution consumer tenant.
- `java/relix/src/main/resources/application.yml:54` — single promotion consumer tenant.
- Live deployments: `EXTRACTION_CONSUMER_TENANT=demo`, `RESOLUTION_CONSUMER_TENANT=demo`, `PROMOTION_CONSUMER_TENANT=demo`, `SYNQUEST_SEED_DEMO_TENANT=demo`.

Eval tenants additionally need an active ontology (Syntology), canonical graph metadata/provisioning (Relix), and a search index/projection (Synquest); none of the eval tenants are provisioned.

Options:

- **A (preferred):** add per-tenant provider selection and multi-tenant consumer polling, then provision the eval tenants. One deployment, clean isolation.
- **B (fallback):** separate eval deployments per provider/tenant (`synflux-eval-regex`, `synflux-eval-llm`, plus matching Synquest/Relix consumers), each with its own tenant and provider. More resource footprint, but no code change to provider selection.

Either way, the eval tenants must be isolated from demo/production indexes and data.

## Extended observation corpus

- Unique corpus: 25 documents.
- Planned idempotency ingestions: 25 × 4 = 100 ingestions across the three scopes.
- Additional real-world documents: 30–50 not yet sourced. Without them, Stage 5 measures idempotency and the same controlled corpus, not coverage. This remains open under #723.

## Metric capture

The canary runner exists (`/tmp/opencode/canary_run.py` during the canary) but is hardcoded to the demo tenant and port. It must be adapted to take an eval tenant/base URL and emit the per-document JSON, then tested on one eval document. Blocked by #728.

## #724 large PDF

- Generated, extractable-text PDFs: `large-final-100p.pdf` (102 pages), `large-final-120p.pdf` (120 pages).
- No real ≥100-page PDF exists in the repository. One real production-like PDF must be sourced before #724 can close; otherwise the sub-check measures generated-document throughput only.

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
- Corpus staged under `docs/acceptance/artifacts/vllm-production-eval/corpus/` (ignored; hashes in the manifest).
