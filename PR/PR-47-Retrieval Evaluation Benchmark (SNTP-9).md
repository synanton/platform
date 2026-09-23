Based on the diff and description of **PR #47** (`SNTP-9 Retrieval Evaluation Benchmark`), the Synanton project has made substantial progress in retrieval evaluation, bug fixing, and infrastructure planning. The PR adds over 1,200 lines of changes, primarily introducing a new benchmarking harness, enriching the demo corpus, and documenting critical findings.

## 🧪 Retrieval Evaluation Benchmark (SNTP-9)

The core of the PR is the introduction of a new tool, **`tools/retrieval-eval`**, a benchmark harness for retrieval evaluation. It includes configuration, metrics, and ingest/query wrappers. The PR reports that **Phase B0** (harness + gold-chunk annotation) and **Phase B1's T01 (BM25, flat chunking) and T04 (hybrid-labeled, real structural chunking) baseline runs** are complete. The results are recorded in new JSONL files (`queries.rb-fixed.jsonl` and `queries.rb-semantic.jsonl`) and a YAML results file (`T01.yaml`), providing a reproducible baseline for retrieval quality.

A companion CLI, **`tools/extraction-probe`**, was also added to independently upload documents to the `extraction-gateway` and inspect its textual/structured output, which proved essential for diagnosing extraction issues.

## 🐞 Critical Fix: Silent Extraction Bug

The PR documents a real, silent extraction bug in `content_extractor`. The `extraction-gateway` was failing every text/markdown extraction with a `NoSuchMethodError` because Spring Boot 3.3.5's dependency management was forcing `commons-lang3` to version `3.14.0`, below what Tika required. The failure was completely silent because the service had no logging on the failure path. The fix includes a BOM override for `commons-lang3`, a new logger on every failure path, a regression test, and a new CI job (`docker-smoke-test`) to prevent similar runtime-only classpath bugs from passing CI silently.

## 📄 Known Limitation: Markdown Heading Parsing

The PR transparently documents a **known limitation** rather than a bug: the `content_extractor`'s text/markdown adapter does not parse markdown headings. A direct gRPC probe against `structured-supply-chain.md` confirmed that extraction succeeds but every element is returned as `PARAGRAPH`, not `HEADING`. Consequently, `SemanticChunkStage` can only produce real structure-aware chunks for PDF documents, not for `.md`/`.txt` files. PDF extraction (OpenDataLoader-backed) does not have this limitation.

## 📚 Corpus Enrichment

To demonstrate the structural differences, the PR adds three new PDF documents to the demo corpus: `mental-health-report-2010.pdf`, `outsourcing-agreement.pdf`, and `sks8300-web-interface-manual.pdf`. These contain 11, 47, and 4 real headings respectively, plus tables, lists, and images, sourced from the public `OHR-Bench` dataset and a user-provided manual.

## 🚧 Blocked: Dense/Hybrid Retrieval

A significant finding is that **dense/hybrid retrieval is unreachable in the Phase 1 stack**. `synquest`'s embedding client always calls the vLLM embedding service, which only runs behind `docker compose --profile phase2` (requiring 2×8GB GPUs). Since that profile is not active in the Phase 1 demo, every search reports `query_usage.embed_skipped=true`. As a result, benchmark tests **T02 (dense-only) and T03 (hybrid) are blocked, not run**, pending the availability of the GPU profile.

## 🖥️ GPU Infrastructure Plan

To unblock dense retrieval, the team has decided to create a **4-node Kubernetes homelab cluster dedicated to Synanton's GPU plane**. The hardware includes:

- `node0`: control-plane, no GPU
- `node1`: GTX 1650 4GB
- `node2`: RTX 4060 Ti 16GB
- `node3`: RTX 5060 Ti 16GB

The cluster will reproduce the proven bootstrap shape (Calico CNI, NVIDIA GPU Operator, Longhorn, local registry). The `gpu-runtime` repo (GPU-1 through GPU-3 complete, GPU-4 contract-unified, GPU-5 blank slate) will be deployed there to provide an isolated embedding/inference endpoint. A detailed ticket backlog for cluster bootstrap and workload deployment is documented in `gpu-runtime/doc/k8s-reference-deployment-plan.md` and `docs/research/gpu-plane-integration-tickets.md`.

## 📝 Immediate Next Steps

While the cluster is being recreated, three independent tasks can proceed immediately:

1. Writing gold queries against the three newly added PDFs.
2. Adding `OHR-Bench` PDFs as permanent `content_extractor` test fixtures.
3. Continuing the retrieval benchmark with **B2** (hierarchical chunking, reranker, graph rank-fusion).

## 📊 Summary of Changes

The PR includes:

- **New tools**: `retrieval-eval`, `extraction-probe`
- **New data**: 3 PDFs, 2 JSONL query sets, 1 YAML benchmark result
- **New documentation**: `cluster-as-built.md`, `retrieval-benchmark-b0-b1-demo.md`, and updates to `README.md` and `INDEX.md`
- **Bug fix**: `content_extractor` dependency and logging fix

Overall, PR #47 represents a thorough, well-documented step forward in retrieval evaluation, with clear separation between fixed bugs, known limitations, and blocked work. The addition of a reproducible benchmark and a concrete GPU infrastructure plan sets a solid foundation for the next phases.