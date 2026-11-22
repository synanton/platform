# OGCE acceptance-gate register

**Status:** Initial register — all gates provisional  
**Decision:** [ADR-044: Ontology-Grounded Chunk Extraction](../architecture/decisions/adr-044-ontology-grounded-chunk-extraction.md)  
**Design source:** [v1.36 OGCE proposal](../architecture/proposals/v1.36/ontology-grounded-chunk-extraction.md)  
**Last reviewed:** 2026-11-22

## Governing rule

These thresholds are **engineering hypotheses, not SLAs or contractual guarantees**. They are not achieved results. Every gate remains **Provisional** until the benchmark profile is pinned (physical GPU/device class, model artifact and immutable revision, quantization, runtime/framework/CUDA versions, tokenizer, serving settings, and evaluation protocol) and the relevant measurement is completed.

Do not mark a gate **Met** or **Missed** without:
1. a committed, reproducible hardware/model/runtime profile;
2. the frozen ADR-022 gold revision and split/protocol recorded;
3. the measurement artifact, run metadata, and uncertainty/annotation-ceiling review;
4. a reviewable update to this register.

Do not tune against held-out test data. If the annotation ceiling makes a proposed quality target unattainable, record the ceiling and approve a revised threshold before examining hold-out results.

## Gate register

| Gate | Metric and numerator | Denominator | Provisional threshold | Measurement method | Status |
|---|---|---|---|---|---|
| G-01 Entity quality | Entity precision/recall combined as F1 | Frozen ADR-022 gold mentions under the ADR-022 matching and averaging protocol | F1 ≥ 0.70; baseline 0.618 | Re-score the same frozen gold revision; report precision, recall, F1, confidence interval, per-document results, and annotation ceiling | Provisional |
| G-02 Relation quality | Relation precision/recall combined as F1 | Frozen ADR-022 gold relations under the ADR-022 matching and averaging protocol | F1 ≥ 0.15 minimum; ≥ 0.20 stretch; baseline 0.078 | Same frozen gold and protocol; report relation-type breakdown, precision/recall, confidence interval, and per-document results | Provisional |
| G-03 JSONL validity | Non-empty emitted lines that parse as exactly one valid JSON object | All non-empty emitted lines, before ontology/schema-semantic rejection | ≥ 98% | Validate line-by-line; report JSON syntax/schema failures separately from response-level protocol violations | Provisional |
| G-04 Truncation recovery | Complete valid lines preceding the injected cut that are retained | All complete valid lines preceding injected truncation points | 100% | Inject truncation at line boundaries and within lines; include provider length-finish cases. Only the incomplete tail may be quarantined as `TRUNCATED` | Provisional |
| G-05 Unexplained quarantine | Quarantined emitted lines without an expected or recognized reason code | All non-empty emitted lines | ≤ 5% | Report by reason code; count duplicates and expected-invalid fixtures separately rather than as unexplained quarantine | Provisional |
| G-06 Ontology subset miss | Gold-required class/property IDs absent from the final selected subset after the retry | All gold-required class/property IDs for schema-valid gold candidates | ≤ 2% after one widened/full-schema retry; minimum 500 candidates per ontology release | Report pre-retry and post-retry miss rates separately; retain selector inputs, scores, dependency closure, fallback, and `OUT_OF_SUBSET` outcomes | Provisional |
| G-07 Cross-boundary relation recall | Recovered gold relations whose supporting spans cross an extraction-segment boundary | All gold boundary relations in the frozen annotated boundary subset | ≥ 80%, and no more than 2 percentage points below the full-context baseline | Measure on frozen gold boundary cases. Compare (A) overlap only, (B) overlap plus deterministic reconciliation, and (C) bounded adjacent-segment semantic reconciliation; report precision and relation F1 too | Provisional |
| G-08 Evidence anchoring | Promoted candidate evidence spans with a correct exact deterministic source-span anchor | All promoted candidate evidence spans | ≥ 99% | Compare persisted anchors with gold spans. Ambiguous or unanchored candidates are ineligible for promotion | Provisional |
| G-09 Coverage completeness | Successfully completed eligible extraction segments | All eligible extraction segments for the source generation | 100% for `KNOWLEDGE_READY`; otherwise status must be `PARTIAL` | Count processed, pending, failed, budget-skipped, and eligible segments. Empty-but-successful model output counts as processed; no silent omission is allowed | Provisional (status invariant) |
| G-10 Cost and latency | Per-document calls, prompt/completion tokens, retries, GPU-seconds, queue wait, wall time, throughput, and peak KV cache | Each measured document/run, with workload size and segment plan recorded | No absolute production threshold until the reference profile is pinned; compare at equal GPU-time/token budgets and report quality/coverage alongside cost | Run 512-token and proposed 256/384-token modes under the same pinned profile and equal-compute/token budgets; report p50/p95 wall time and throughput | Provisional |

**G-10 p95 dependency:** The single model load and single synthetic no-op extraction pass requested by [#814](https://github.com/synanton/platform/issues/814) can record observed latency only; it cannot establish a statistically defensible p95. The p95 component of G-10 is therefore **blocked pending separate explicit approval for a repeated-request synthetic workload beyond #814**. Do not infer approval for repeated requests from approval of #814, and do not mark the p95 component measured or met until an approved repeated-request protocol has run. The remaining G-10 telemetry may be recorded at the scope actually authorized, without representing it as p95 evidence.

## Measurement record

For every benchmark update, attach or link:

- GPU vendor/model, physical device identity or stable class, memory capacity, and relevant device/runtime capabilities;
- model name, immutable artifact revision/hash, quantization, tokenizer revision, and serving configuration;
- driver, CUDA, framework, inference runtime, and adapter versions;
- prompt/schema/ontology release digests and validator version;
- frozen-gold revision, split, matching protocol, sample counts, and any exclusion rules;
- raw per-document metrics and run metadata, including retries, tokens, GPU-seconds, queue wait, latency, throughput, KV-cache peak, coverage, and quarantine reason counts;
- confidence intervals and annotation-ceiling assessment for quality metrics;
- deviations from the pinned profile and an explanation of their impact.

A profile change creates a new benchmark profile/version; results from different profiles must not be silently combined.

## Status lifecycle

| Status | Meaning |
|---|---|
| **Provisional** | Threshold is a design hypothesis; no qualifying pinned-profile measurement has been reviewed. |
| **Measured** | A qualifying measurement exists and is linked, but the threshold has not been assessed or accepted. |
| **Met** | Reviewed measurement satisfies the threshold under the pinned profile and frozen protocol. |
| **Missed** | Reviewed measurement does not satisfy the threshold; record the gap, uncertainty, and remediation/decision. |
| **Revised** | Threshold was changed through an explicit review after evidence/annotation-ceiling analysis; preserve the previous value and rationale. |

No status transition is automatic. In particular, a successful smoke test or no-op harness run does not measure extraction quality, cost, or latency gates.
