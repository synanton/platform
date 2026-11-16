# ADR-043: vLLM extraction provider status — broad rollout permitted, default switch deferred

**Status:** Accepted — 2026-11-16
**Basis:** The #718 → controlled canary → #713 sequence completed the quality and lifecycle-safety gates for vLLM extraction. The evidence supports using vLLM as an explicitly configured provider; it does not yet support replacing regex as the default.

## Decision

1. **Broad rollout is permitted.** vLLM may be used as an explicitly configured extraction provider in any environment. It is production-ready for tenants that opt in.
2. **The default switch is not yet justified.** Regex remains the default provider in production, demo, and staging. No environment silently defaults to vLLM.
3. **The default switch is a separate decision.** It requires its own ADR, its own evidence review, and its own staged rollout. It is not implied by "broad rollout permitted."
4. **Opt-in is a configuration choice.** A tenant can select vLLM per ingestion while the default stays regex; the rollback path is the same single flag change exercised in the canary.
5. **Reassessment is evidence-driven.** The default switch requires the criteria below, not a fixed date or a green canary alone.

Consequences:
- **Opt-in tenants:** get LLM extraction with per-run provider attribution and a proven regex rollback.
- **Default tenants:** keep the deterministic regex path until coverage and throughput gaps close.
- **Operators:** can permit LLM use without changing the platform default or demo reference behavior.
- **Rejected:** switching the global default now; switching the demo default now; treating "LLM is viable" as "LLM is superior at scale."

## Evidence

| Criterion | Evidence | Status |
|---|---|---|
| Extraction precision | 95.8% (23/24 reviewed sample) | Strong |
| Extraction recall | 100% (24/24 expected) | Strong |
| Determinism | 3/3 identical normalized runs | Pass |
| Promotion safety | #713 live failed-assertion verification | Pass |
| Graph lifecycle | 0 ACTIVE relation regressions in the canary | Pass |
| Negative controls | 4 candidates proposed, 0 accepted; no false graph facts | Pass |
| Attribution (#720) | 10% recurrence on the canary | Known limitation |
| Large-PDF throughput | Not established | Open |
| New-entity resolution | Not established; canary used existing canonical entities | Open |

Canary report: [`docs/acceptance/vllm-canary-2026-11-16.md`](../../acceptance/vllm-canary-2026-11-16.md).

## Rollout policy

| Environment | Default provider | Config override |
|---|---|---|
| Production (all tenants) | `regex` | Per-tenant/per-ingestion opt-in to `llm` |
| Demo | `regex` | Configurable |
| Staging | `regex` | Configurable |

vLLM is available everywhere but is the default nowhere.

## Telemetry required before default reassessment

- Per-document rejection rate by reason code.
- #720 attribution-defect recurrence rate.
- Large-PDF ingestion latency (p50/p95) and convergence time.
- New-entity extraction and resolution rate.
- GPU utilization and queue depth under concurrent ingestion.
- Rollback frequency and reasons.

## Production evaluation (step 2)

The production evaluation is **step 2 of this decision**, not a separate initiative. It is tracked by #723 and is the only production-evaluation ticket. #724 and #725 are required sub-checks of #723, not parallel duplicate evaluations.

Initial scope:

- 20–30 documents, then continue to ≥100 documents or one week of telemetry.
- ≥3 tenants and ≥3 document types.
- ≥2 large PDFs (≥100 pages), via #724.
- ≥3 new-entity documents, via #725.
- ≥3 negative controls.
- Per-document precision/recall, rejection by layer, latency p50/p95, #720 recurrence, lifecycle regressions, and rollback frequency.
- Report at `docs/acceptance/vllm-production-eval-YYYY-MM-DD.md`.

The default-switch ADR is blocked until #723 closes and every open row in the evidence matrix is closed or explicitly deferred with a reason.

## Reassessment criteria for the default switch

The default-switch ADR may be written only after:

- At least 100 documents ingested with `provider=llm` across at least 3 tenants.
- #720 recurrence is stable or reduced from the canary's 10%.
- Large-PDF latency is measured and documented.
- New-entity extraction rate is measured and documented.
- No lifecycle regressions attributable to LLM extraction.
- Operational rollback has been exercised at least once in production.

## Reversal conditions for opt-in tenants

| Condition | Action |
|---|---|
| Rejection rate exceeds 50% for more than 24 hours | Per-tenant alert; suggest rollback |
| Any lifecycle regression caused by invalid assertions | Immediate rollback of that tenant to `regex` |
| GPU saturation affects p95 latency | Route new tenants to `regex` until capacity recovers |
| #720 defect causes graph pollution | Roll back and file an incident |

## Follow-up tickets

- #723 — **Production evaluation (step 2 of this decision).** 20–30 documents initially, ≥3 tenants, ≥3 types, ≥3 negative controls, then ≥1 week/≥100 documents. This is the single production-evaluation ticket; do not file a duplicate "real-world extraction evaluation".
- #724 — Large-PDF throughput/quality benchmark; required sub-check of #723 (closes one Open row).
- #725 — New-entity resolution coverage; required sub-check of #723 (closes the other Open row).
- #720 — Attribution/rejection-reason observation and refinement (known limitation).

## Context

The source-lifecycle sequence (#654, #655, #656, #699, #706) established staged activation, assertion retirement, read visibility, and reindex correctness. #718 aligned the LLM prompt to the active ontology; the controlled canary (#721) then measured extraction and graph outcome; #713 separated genuine contradictions from failed assertions so invalid promotions cannot spuriously move an ACTIVE relation to `CONFLICTED`.

Those results answer "is vLLM a viable provider?" They do not answer "should vLLM replace regex as the default?" The canary used a controlled, small-document corpus with existing canonical entities; large-PDF throughput and new-entity resolution remain unmeasured, and #720 has a 10% observed recurrence. Keeping regex as the default while permitting explicit opt-in is the reversible decision supported by the current evidence.
