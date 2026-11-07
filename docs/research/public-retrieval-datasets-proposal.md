# Public Retrieval Datasets — Integration Proposal

**Status:** Proposed  
**Owner:** `synanton/platform`  
**Scope:** Extend the existing SNTP-9 retrieval evaluation benchmark with public, judged information-retrieval datasets.  
**Related:** [Retrieval Evaluation Benchmark — Research Plan](./retrieval-evaluation-benchmark-plan.md), [retrieval-eval harness](../../tools/retrieval-eval/README.md), [benchmark run records](../../docs/architecture/synanton-design-1.31.md)

## 1. Decision and architectural placement

Integrate Mr. TyDi Russian, BEIR SciFact, and BEIR NFCorpus into the **existing Platform retrieval-evaluation benchmark**.

Platform already owns the benchmark execution path: `tools/retrieval-eval/` drives the canonical Synflux/Synquest APIs, computes retrieval metrics, validates run validity, and writes benchmark run records. The public datasets are additional benchmark inputs, not a reason to create another runner, metrics package, result format, or production service.

This proposal therefore extends the current harness and its research plan. It does not introduce an Analytics-owned retrieval benchmark.

The existing benchmark against Synanton's own demo corpus remains the platform/integration baseline. Public datasets add independently judged, domain-specific relevance evidence:

- **Mr. TyDi Russian:** monolingual Russian retrieval and a larger corpus-scale experiment.
- **BEIR SciFact:** scientific claim-to-evidence retrieval.
- **BEIR NFCorpus:** biomedical/nutrition information retrieval.

These are retrieval tasks, not generative QA datasets. Initial evaluation measures retrieval of judged evidence; answer generation, answer correctness, and citation faithfulness remain out of scope.

## 2. Reuse existing Platform capabilities

| Concern | Existing owner | Proposed treatment |
|---|---|---|
| Execution and orchestration | `tools/retrieval-eval/` CLI | Extend; do not create a second runner |
| Synflux ingestion and Synquest search | Existing `ingest.py` / `query.py` API wrappers | Reuse the canonical REST path |
| Metrics | `retrieval_eval/metrics.py` | Reuse existing implementations; add only missing metrics with tests |
| Run records and hit artifacts | `run_record.py` | Reuse the current run-record contract; version it only if required fields cannot be represented compatibly |
| Dataset configuration | Existing `config.yml` and path validation | Add pinned dataset entries and local-path resolution |
| Corpus and qrels loading | Existing gold/query loading boundary | Add format-specific dataset adapters that normalize into the harness's existing internal query/gold representation |
| Reports and experiment status | Existing research plan and benchmark documentation | Extend in place; keep public-dataset results distinct from the internal demo corpus |

The first implementation should inspect and preserve the current CLI/data contracts. Do not assume that a new schema or a physical Iceberg table is needed.

## 3. Dataset contracts

| Dataset | Corpus / task | Initial use |
|---|---|---|
| Mr. TyDi Russian | Russian passages; commonly distributed test split has 995 queries and corpus is approximately 9.6M passages | Dev/sample smoke test first; full corpus and official test evaluation as a separately scheduled scale step |
| BEIR SciFact | 5,183 scientific abstracts; claim-to-evidence retrieval; commonly 300 test queries | Small end-to-end baseline |
| BEIR NFCorpus | Approximately 3,633 biomedical/nutrition documents; commonly 323 test queries | Small end-to-end baseline |

Counts are indicative, not hard-coded acceptance values. The adapter must record counts from the exact pinned artifacts and fail validation when references are inconsistent.

### 3.1 Preserve source identity and judgment semantics

- Preserve each upstream corpus/document ID exactly and map it deterministically to an internal benchmark document ID.
- Preserve the original judged unit: a Mr. TyDi passage or a BEIR document. Do not silently split or merge judged records.
- For BEIR, preserve title and body and document the title/body indexing policy.
- Preserve query IDs, split names, original qrel relevance grades, and source dataset identifiers.
- A missing qrel means **unjudged**, not automatically irrelevant. The evaluator's treatment of unjudged results must be explicit and version-pinned.
- Keep chunk-level experiments separate. If chunking is evaluated later, define and validate a deterministic parent-document aggregation rule before comparing with document-level qrels.
- Do not enrich benchmark documents with external web content or rewrite/translate the primary benchmark queries.

### 3.2 Splits

Use development data for configuration and model selection. Reserve official held-out test qrels for final reporting. The full Mr. TyDi test set must not be used for tuning. A small deterministic sample may validate the pipeline, but its scores must never be presented as official dataset results.

## 4. Retrieval experiment matrix

For each dataset, run the same corpus and query identities through:

1. **BM25** — lexical baseline using a recorded analyzer/tokenization configuration.
2. **Dense** — vector retrieval using a recorded embedding model/revision, dimension, normalization, and similarity metric.
3. **Hybrid RRF** — the existing lexical+dense fusion path, with candidate depths, RRF constant, deduplication, and final result depth recorded.
4. **Optional reranker** — only after the BM25/Dense/RRF baselines are stable and the reranking path is genuinely implemented and enabled.

Do not label a run Dense or Hybrid if embeddings were skipped or the dense leg was not exercised. Reuse the harness's existing fail-closed validity rules and record the actual retrieval legs used.

Do not change document granularity or preprocessing between BM25, Dense, and RRF arms. Report dataset-specific analyzer/preprocessing choices rather than silently applying one language configuration to all corpora.

## 5. Metrics and reporting

Reuse the current metric implementation in `retrieval_eval/metrics.py`. The initial report should include:

- nDCG@10;
- MRR@10;
- Recall@100;
- MAP@100 (add to the shared metrics module only if absent);
- per-query results and failed/empty-query counts;
- p50/p95/p99 latency, QPS at stated concurrency, and BM25/Dense/fusion timing where the current search API exposes it;
- corpus/index size, indexing duration, hardware, deployment topology, and resource utilization where available.

The existing research plan's normative metrics and run-record contract remain authoritative. If a metric is not yet available in the harness, mark it as a scoped implementation gap rather than claiming it is already supported.

Report results per dataset first. A macro-average may be included as a descriptive summary, but must not conceal domain- or language-specific regressions. Keep quality and performance results separate and describe the workload behind every latency/throughput figure.

## 6. Isolation, reproducibility, and licensing

Each dataset must be isolated from production/customer data and from other benchmark datasets using a dedicated benchmark tenant or an equivalent hard namespace. Verify isolation for source/document IDs, lexical and vector indexes, caches, query histories, qrels, and output artifacts.

Each dataset manifest should record:

- canonical upstream URL and exact release/revision;
- split names and observed corpus/query/qrels counts;
- license identifier and required attribution;
- checksums for downloaded artifacts;
- preprocessing and adapter versions;
- download/access timestamp;
- deterministic mapping/version information.

Loading must be idempotent for a given dataset revision and manifest. Re-running ingestion must not create duplicate documents or embeddings.

Mr. TyDi is commonly distributed under Apache-2.0; the BEIR SciFact and NFCorpus Hugging Face dataset cards declare CC-BY-SA-4.0. Verify the exact source artifacts and obligations before redistribution. Preserve attribution and upstream citations; do not commit raw corpora or large downloaded archives to this repository. Keep downloaded/extracted data outside Git, using the existing configurable dataset-root convention.

## 7. Delivery phases

### Phase 0 — Contract and inventory

- Confirm canonical upstream sources, revisions, split definitions, and licenses.
- Implement manifest/checksum generation and dataset inventory validation.
- Validate unique IDs, query-to-qrels references, qrels-to-corpus references, duplicate IDs, and split boundaries.
- Confirm exact artifact counts and document granularity.

**Exit:** all three datasets can be located and validated reproducibly without indexing.

### Phase 1 — Small end-to-end runs

- Add SciFact and NFCorpus adapters first.
- Run a deterministic Mr. TyDi dev/sample smoke test solely to validate loading and execution.
- Ingest each dataset into an isolated benchmark tenant.
- Run BM25, Dense, and RRF through the existing harness and emit the existing run-record format.
- Produce per-query diagnostics and the metrics available in the shared evaluator.

**Exit:** three dataset adapters work end to end; sample runs are clearly marked as smoke tests and not official scores.

### Phase 2 — Official quality baselines

- Run official held-out test queries/qrels for SciFact and NFCorpus.
- Schedule full Mr. TyDi indexing and evaluation after resource and runtime measurements from the smoke test.
- Freeze manifests, model/analyzer configuration, and evaluator version for each report.

**Exit:** reproducible per-dataset retrieval-quality baselines.

### Phase 3 — Performance and regression automation

- Add controlled concurrency, repetitions, and cold-/warm-cache measurements.
- Establish baselines and regression thresholds only after repeated measurements.
- Add CI or scheduled execution appropriate to runtime and resource cost; avoid making noisy performance measurements flaky build gates.

**Exit:** repeatable quality/performance regression reports with query-level diagnostics.

## 8. Analytics boundary

Analytics may later consume Platform benchmark run records and result artifacts for longitudinal analysis, dashboards, or Iceberg-backed history. That is a downstream analytical use, not ownership of dataset adapters or retrieval execution.

- Platform owns dataset loading contracts, isolated benchmark execution, scoring, run validity, and canonical run artifacts.
- Analytics may own downstream analytical models and historical aggregation once there is a concrete consumer requirement.
- Spark may be useful for large offline aggregation/backfills later.
- Flink is not required for the initial benchmark; introduce it only for a demonstrated streaming telemetry/evaluation use case.
- Iceberg is not a prerequisite for the first reproducible runs. Do not add a lakehouse dependency merely to store a small number of benchmark artifacts.

## 9. Acceptance criteria

- [ ] Dataset sources, revisions, licenses, attribution, checksums, counts, and splits are recorded.
- [ ] Dataset loaders preserve original query/document IDs and qrel grades.
- [ ] Qrels references and split boundaries are validated; unjudged documents are handled explicitly.
- [ ] Dataset ingestion is idempotent and isolated from production/customer data.
- [ ] BM25, Dense, and RRF use the same judged corpus/query identities.
- [ ] Runs reuse the existing Platform CLI, metrics, validity checks, and run-record contract.
- [ ] Invalid or incomplete Dense/Hybrid runs cannot be reported as valid.
- [ ] Per-dataset quality and performance reports include configuration and environment provenance.
- [ ] Official test qrels are reserved for final evaluation, not tuning.
- [ ] Smoke-test/sample results are never presented as official benchmark scores.
- [ ] Documentation distinguishes evidence retrieval from generated-answer evaluation.

## 10. References

- Mr. TyDi: https://github.com/castorini/mr.tydi
- Mr. TyDi dataset documentation: https://ir-datasets.com/mr-tydi
- Mr. TyDi Russian test split: https://huggingface.co/datasets/irds/mr-tydi_ru_test
- BEIR: https://github.com/beir-cellar/beir
- SciFact corpus: https://huggingface.co/datasets/BeIR/scifact
- SciFact qrels: https://huggingface.co/datasets/BeIR/scifact-qrels
- NFCorpus: https://huggingface.co/datasets/BeIR/nfcorpus
- NFCorpus qrels: https://huggingface.co/datasets/BeIR/nfcorpus-qrels

## Decision requested

Approve integrating these public datasets as an extension of the existing Platform retrieval-evaluation benchmark, with the phased scope and boundaries above. No separate Analytics runner or lakehouse implementation is required for the initial delivery.
