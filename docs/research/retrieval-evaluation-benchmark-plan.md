---
title: "Retrieval Evaluation Benchmark — Research Plan (SNTP-9 / Issue #14)"
status: "in progress — Phase B0 done; Phase B1 T01/T04 done; T02/T03 planned against GPU-7 free models (§6 Phase B1-G)"
last_reviewed: "2026-09-25"
---

# Retrieval Evaluation Benchmark — Research Plan (SNTP-9 / Issue #14)

> **Document type:** Research plan
> **Goal:** Measure retrieval effectiveness (recall, ranking quality, latency) across chunking strategies, retrieval strategies, and embedding models, on Synanton's own ingestion/knowledge/search stack — not a standalone RAG harness.
> **Related:** [synanton-design-1.31.md](../architecture/synanton-design-1.31.md) §84-91 (the normative source for this benchmark), [synanton-platform-architecture-1.0.md](../architecture/synanton-platform-architecture-1.0.md) §9, [flat-vs-semantic-chunks-research-plan.md](./flat-vs-semantic-chunks-research-plan.md) (sibling plan — owns the flat-vs-semantic half of RQ1 in depth; this plan cross-references it rather than repeating it), [platform-api-plane/INDEX.md](../implementation/platform-api-plane/INDEX.md), [eventing-workflow-plane/INDEX.md](../implementation/eventing-workflow-plane/INDEX.md)

---

## 0. Correction Notice

Issue #14's original test plan (quoted in full in the issue) frames this as a **standalone benchmark harness**: external PDF datasets (PIRE, RAG-Multi-Corpus, OHR-Bench), a from-scratch ingestion/chunking pipeline, external vector/graph databases (Milvus, Qdrant, Neo4j, NetworkX), and a hardware assumption of "A100 GPU, 256GB RAM."

Design 1.31 §87 — the actual normative spec for this benchmark, since it names SNTP-9 explicitly — says otherwise:

> "Search implementation should integrate with the planned **SNTP-9 Retrieval Evaluation Benchmark**... and should consume extraction/knowledge artifacts through the canonical platform architecture **rather than creating an independent content pipeline**."

Three corrections this plan makes to the original issue text, each verified against the current codebase rather than assumed:

1. **No independent pipeline.** Ingestion goes through `synvault` → `synflux` (`ExtractionStage` via `content_extractor` for PDFs, `SemanticChunkStage`/`ChunkStage` for chunking) → `ingestion-cache`, exactly like every other demo in this repo. PDF parsing is `content_extractor`'s embedded `org.opendataloader:opendataloader-pdf-core` library (in-process, no HTTP sidecar, no external parser like Docling/Marker/PyPDF).
2. **No external vector/graph database.** `synquest` already implements real BM25 (`BM25Similarity`) and real dense/KNN vector search (Lucene's `KnnFloatVectorQuery`, HNSW-backed under the hood) plus real RRF fusion (`RrfFusion.combine(...)` in `SearchService`) — confirmed by reading the actual source, not assumed from README status tables. `relix` already implements graph retrieval with pluggable backends (`GraphConnector`: `InMemoryGraphConnector`/`Neo4jGraphConnector`/Nebula) — Neo4j is already an option, no need to introduce it as new infrastructure. **What's genuinely missing** (also verified by reading the source): reranking is only a policy stub (`RerankPolicySelector` in `planner` picks a *mode*, nothing calls an actual cross-encoder), and graph signals are fused with search results one layer up in `gateway`'s `PlanExecutor`/`RelixClient`, not inside `synquest`'s own RRF — both are real gaps this benchmark's implementation work must close, not assume already exist.
3. **Real hardware, not A100/256GB.** The only GPU hardware available to this project is the homelab k8s cluster documented in `content_extractor/doc/k8s-test-hardware.md`: three worker nodes with one consumer GPU each (GTX 1650 4GB, RTX 4060 Ti 16GB, RTX 5060 Ti 16GB), explicitly **not project-owned infrastructure** and a *fallback*, not the primary iteration environment — that doc's own guidance is `docker compose` first. This benchmark's environment section (§7 below) is built on that reality.

External PDF-layout datasets (PIRE, RAG-Multi-Corpus, OHR-Bench) are not discarded — they are **already downloaded locally** (§4a) and demoted only in *priority*: an optional, later-phase extension (§6, Phase B4) once the harness is proven on Synanton's own corpus, consistent with Design 1.31 §89's reproducibility requirement that every benchmark run pin a `dataset_version`.

---

## 1. Research Questions

Per Issue #14 and Design 1.31 §85/86, restated against real modules:

| ID | Question | Answered using |
|----|----------|-----------------|
| RQ1 | Fixed vs. semantic vs. **hierarchical** chunking — recall/precision impact? | `synflux` chunking stages. Fixed-vs-semantic is already the subject of [flat-vs-semantic-chunks-research-plan.md](./flat-vs-semantic-chunks-research-plan.md); **this plan owns only the hierarchical arm and the three-way comparison**, not a restatement of that plan's H1-H4. |
| RQ2 | Incremental value of hybrid (BM25 + vector via RRF) over single-strategy retrieval? | `synquest`'s existing `RrfFusion` — already implemented, this is a **measurement** task, not a build task. |
| RQ3 | Does graph-enhanced retrieval improve on hybrid? | `relix` + `gateway`'s existing graph/search fusion at the plan level — needs a benchmark-side harness to call it consistently, and (new work) a documented way to fuse graph rank signal *into* the RRF score rather than only side-by-side at the gateway layer. |
| RQ4 | How do different embedding models affect retrieval effectiveness? | `synanton-llm-client`'s existing provider-agnostic `LlmClientFactory`/`OpenAiCompatTranslator` — swapping `model-id` is already config, not code. |
| RQ5 | Latency/throughput trade-offs per strategy? | Design 1.31 §86's latency taxonomy (candidate generation, ranking, reranking, embedding) — measured directly from `synquest`'s existing `SearchTrace` (`embedMs, denseMs, lexicalMs, fusionMs, totalMs`, already computed in `SearchService`). |

---

## 2. Modules Touched

| Module | Change | Why |
|---|---|---|
| `java/synquest` | **New**: reranker call site wired into the search pipeline after RRF fusion, gated by a config flag. **New**: expose `SearchTrace` fields (already computed internally) on the response for benchmark harvesting. | Reranking is currently only a policy decision (`planner.RerankPolicySelector`); nothing invokes a model. Latency breakdown exists internally but isn't surfaced. |
| `java/gateway` | **New**: `RerankerPort` SPI (mirroring the README's long-stated intent for one, which doesn't exist in code yet) + one adapter (`VllmCrossEncoderRerankAdapter` or equivalent) calling a self-hosted cross-encoder via `synanton-llm-client`'s existing HTTP client plumbing. **New/Modified**: `PlanExecutor` records whether/how graph signal was fused with search results, for RQ3 measurement. | Closes the reranking gap identified above; makes graph-fusion behavior observable rather than implicit. |
| `java/synflux` | **New**: hierarchical chunking strategy (parent/child chunk relationships surfaced at retrieval time, per Design 1.31 §115/116) alongside the existing flat (`ChunkStage`) and semantic (`SemanticChunkStage`) strategies. `DocumentStructureBuilder` already builds a section tree — this exposes it as a retrievable parent-child relationship rather than only a flattening input to `SemanticChunker`. | RQ1's third arm doesn't exist yet; flat and semantic already do. |
| `java/synanton-llm-client` | **Modified** (maybe none): confirm `OpenAiCompatTranslator` covers every self-hosted embedding model in scope; add a translator only if a specific comparison target needs one not already covered. | Model comparison should be config-driven against the existing client, not a new integration per vendor. |
| `content_extractor` | **Unchanged.** Already parses PDFs via embedded `opendataloader-pdf-core`; this benchmark is a consumer, not a modifier. | Confirms scope boundary — no PDF-parser work belongs to this plan. |
| new: `tools/retrieval-eval/` | **New**: benchmark harness (ingest driver, query runner, metrics computation) plus `config.yml` (§4a) pointing at the already-downloaded local datasets. Follows the pattern `flat-vs-semantic-chunks-research-plan.md` already established for its own harness scripts. | Needs to exist somewhere; this plan's scripts are a superset of the sibling plan's, not a duplicate. |

---

## 3. Experimental Design

### 3.1 Chunking arms

| Arm | Code | Module | Status |
|-----|------|--------|--------|
| Fixed | `flat-text` | `synflux.ChunkStage` (legacy) | Exists |
| Semantic | `structure-aware` | `synflux.SemanticChunkStage` | Exists |
| Hierarchical | `hierarchical` | `synflux.SemanticChunkStage` + new parent-child exposure | **New work** (§2) |

### 3.2 Retrieval arms

| Arm | Module(s) | Status |
|-----|-----------|--------|
| BM25 | `synquest.HybridSearcher.lexical()` | Exists |
| Dense vector | `synquest.HybridSearcher.dense()` (Lucene KNN) | Exists |
| Hybrid (RRF) | `synquest.SearchService` + `RrfFusion.combine()` | Exists |
| Graph-enhanced | `relix` (`GraphConnector`) + `gateway.PlanExecutor`/`RelixClient` | Exists at the plan level; **new work** to fuse graph rank signal into the RRF score for this benchmark's T08-T11 rows (§3.4) |
| + Reranking | new `RerankerPort` + adapter | **New work** (§2) |

### 3.3 Embedding models in scope

Config-driven via `synanton-llm-client`, no new SDK integration required for the first three:

| Model | Source | Dim | Notes |
|---|---|---|---|
| `bge-base-en-v1.5` | self-hosted (already the `synflux` default `model-id`) | 768 | Baseline |
| `bge-large-en-v1.5` | self-hosted | 1024 | Same family, larger — isolates size effect |
| `e5-mistral-7b-instruct` | self-hosted, quantized | 4096 | Largest model the RTX 4060 Ti/5060 Ti (16GB) nodes can plausibly serve; confirm via a smoke test before committing it to the full matrix |
| `text-embedding-3-small` (OpenAI-compat) | external API | 1536 | Optional — `OpenAiCompatTranslator` already supports it; incurs real external cost, gate behind an explicit opt-in env var |

**GPU-7 free-model arms (added 2026-09-25, §6 Phase B1-G).** Until GPU-5 serves `bge-base` for real (blocked on `gpu-runtime` T-K8S-6a), dense retrieval runs through the GPU plane's external-provider mode (GPU-7) against **OpenRouter free embedding models only** — the only embedding models the capped test key may call (`allowed-model-pattern: ".*:free"`). None of them is `bge-base`, so these arms change the embedding variable and are reported as their own rows (`T02-G`/`T03-G`/`T04-G`), never as substitutes for the bge-base rows of §3.4:

| Logical model (GPU-7 catalog) | Provider model (never exposed downstream) | Native dim | Context | Notes |
|---|---|---|---|---|
| `synanton-free-embedding` | `nvidia/nemotron-3-embed-1b:free` | 2048 | 32k | Primary GPU-7 arm (already in `gateway-external.yaml`) |
| `synanton-free-embedding-nemotron-vl` | `nvidia/llama-nemotron-embed-vl-1b-v2:free` | 2048 (to confirm, G0) | 131k | Second free arm for RQ4 |
| `synanton-free-embedding-lfm` | `liquid/lfm-2.5-embedding-350m:free` | to confirm (G0) | **512 tokens** | Only valid where every chunk fits in 512 tokens; otherwise excluded, not silently truncated |

Two constraints follow from the current code, both verified: Lucene 9.11.1 (`gradle/libs.versions.toml`) caps `KnnFloatVectorField` at **1024 dims** by default, and `synquest`'s `synquest.embedding.dim` is hard-coded to `768` (`java/synquest/src/main/resources/application.yml`). A 2048-dim arm therefore needs either Matryoshka truncation to ≤1024 + L2 re-normalisation (valid only if the model is Matryoshka-trained — confirmed empirically in G0, not assumed) or a per-field codec override raising the dimension cap. The chosen reduction is recorded in the run record's `embedding_model` field (e.g. `synanton-free-embedding@1024`).

Cohere `embed-v3` and Voyage `voyage-2` are **out of scope for the baseline matrix** (no existing translator; would need new vendor-specific integration work with no current justification) — tracked as an Open Question (§10), not silently dropped.

### 3.4 Test matrix

Condensed from Issue #14's T01-T14, re-scoped to what's real or genuinely new:

| ID | Chunking | Retrieval | Embedding | Graph | Rerank | New work required |
|----|----------|-----------|-----------|-------|--------|--------------------|
| T01 | Fixed | BM25 | — | — | — | None |
| T02 | Fixed | Dense | bge-base | — | — | None |
| T03 | Fixed | Hybrid RRF | bge-base | — | — | None |
| T04 | Semantic | Hybrid RRF | bge-base | — | — | None |
| T05 | **Hierarchical** | Hybrid RRF | bge-base | — | — | Hierarchical chunking (§2) |
| T06 | Semantic | Hybrid RRF | bge-large | — | — | None (config swap) |
| T07 | Semantic | Hybrid RRF | e5-mistral-7b | — | — | Smoke-test GPU serving capacity first |
| T08 | Semantic | Hybrid RRF | bge-base | Entity | — | Graph rank-fusion (§2) |
| T09 | Semantic | Hybrid RRF | bge-base | Knowledge | — | Graph rank-fusion (§2) |
| T10 | Semantic | Hybrid RRF | bge-base | Knowledge | ✓ | Reranker (§2) + graph rank-fusion |
| T11 | Hierarchical | Hybrid RRF | bge-base | Knowledge | ✓ | Both new-work items together — run last |
| T02-G | Fixed | Dense | GPU-7 free (`synanton-free-embedding`) | — | — | §6 Phase B1-G (G1-G4) |
| T03-G | Fixed | Hybrid RRF | GPU-7 free | — | — | §6 Phase B1-G |
| T04-G | Semantic | Hybrid RRF | GPU-7 free | — | — | §6 Phase B1-G — first run where T04's "hybrid" actually includes dense |

`-G` rows run through the GPU plane's external mode (GPU-7) on OpenRouter free models. They answer RQ2 (does hybrid beat BM25?) and give RQ4 an early free-model subset. They don't replace the bge-base rows, and their latency isn't compared across planes (§8).

---

## 4. Corpus

**Primary (default):** `demo-data/documents/` — the same corpus `flat-vs-semantic-chunks-research-plan.md` uses, extended with the gold query set that plan already defines (`demo-data/eval/flat-vs-semantic/queries.jsonl`) plus new questions targeting hierarchical/multi-hop and graph-relevant relationships (entity co-occurrence across `acme-corp-profile.md`, `globex-manufacturing.md`, `vendor-agreements.md` — these already reference overlapping named entities per the demo corpus design).

**Update (2026-09-20): three real, spec-valid PDFs added.** The original 13-document corpus contains exactly one PDF (`quarterly-report.pdf`), which is spec-invalid (missing `xref`/`startxref` — confirmed via byte-level MinIO comparison during the `content_extractor` bug-fix pass; the fix belongs in this repo, not filed here). More importantly: **content_extractor's `TextModalityAdapter` (Tika-based, used for `.txt`/`.md`) does not parse markdown heading syntax into `HEADING` elements at all** — verified by direct gRPC probe against `structured-supply-chain.md`, the corpus's one file with real markdown headings: extraction succeeds (10 elements) but every element is `ELEMENT_PARAGRAPH`, none `ELEMENT_HEADING`. `SemanticChunkStage` therefore falls back to flat chunking (`chunk_type=FALLBACK`) for every text/markdown document in this corpus regardless of `extraction-gateway`'s health — meaning **no document in the original corpus can exercise real structure-aware chunking at all**, independent of any bug; this is documented, by-design behavior of the current text adapter (its own `feature_states` response reports `layout: FEATURE_NOT_APPLICABLE` for `text/markdown`).

PDF extraction (`content_extractor`'s OpenDataLoader-backed `PdfModalityAdapter`) does **not** have this limitation — it performs real heading/table/list detection. Three real, spec-valid PDFs were added to give the corpus at least some documents where semantic chunking can genuinely diverge from flat chunking:

| File | Source | Structure confirmed via direct probe |
|---|---|---|
| `mental-health-report-2010.pdf` | `testing/OHR-Bench/pdfs/academic/` | 11 headings (levels 1/2/3/5/6), 12 tables, 3 lists — 87 elements total |
| `outsourcing-agreement.pdf` | `testing/OHR-Bench/pdfs/law/` | 47 numbered headings (e.g. "1.6 Applicable Law", "3.4 Production Capacity") |
| `sks8300-web-interface-manual.pdf` | user-provided (`/mnt/WD4T/software/SKS8300/`) | 4 headings (numbered "I."/"II."/"III." sections), 21 images, 23 lists, 7 captions |

Confirmed via Cassandra inspection: `mental-health-report-2010.pdf` under a tenant with `extraction-gateway` healthy produces real `SECTION`/`TABLE`/`LIST` chunk types with real `section_path` values (e.g. `"APPENDIX A: DATA SOURCE DESCRIPTIONS"`); the same file under a tenant with `extraction-gateway` down produces uniform `chunk_type=FALLBACK`, empty `section_path` — this is the first genuine, verified Fixed-vs-Semantic divergence in this benchmark. None of the *original* 10 gold queries (§9 below) target these three new files yet — annotating queries against them is tracked as an open follow-up, not yet done.

**Optional extension (Phase B4):** the datasets below are already downloaded locally (not hypothetical — see §4a for exact paths) and can be adopted once the harness is validated on the primary corpus:

| Dataset | Fit | Caveat |
|---|---|---|
| [PIRE](https://huggingface.co/datasets/Wikit/PIRE) | Fastest to adopt — 100 PDFs, built for parsing/chunking comparison | Local copy has only `pdf_files.zip` + the dataset card; the HF data shards (`data/chunk.single-*`) referenced by the card are not yet fetched — extend `config.yml` (§4a) once those are pulled, or derive gold labels from the zip's own structure |
| [RAG-Multi-Corpus](https://github.com/udayallu/RAG-Multi-Corpus) | PDF + Markdown + HTML of the *same* content — isolates parsing from chunking effects; local copy is a full clone with real per-organization query CSVs already present | Larger integration lift (5 orgs, hundreds of queries with structured "Supporting Facts" as gold evidence) |
| [OHR-Bench](https://huggingface.co/datasets/opendatalab/OHR-Bench) | Largest, most diverse layouts; local copy has both parquet manifests and the full `pdfs.zip`/`retrieval.zip` | Scale (8,500+ pages, 1.5GB+ of PDFs) makes it a stretch goal, not a Phase B0-B3 target |

Also available locally, as reference (not a dataset): `bench-chunknorris-acl2025`, a cloned third-party PDF-parsing/chunking benchmark codebase — useful as prior art for the harness's chunker/evaluation code (its `experiment.config.yml` uses the same `FILES_DIR`-style local-path convention this plan adopts in §4a), not adopted wholesale since it's scoped to parser comparison on PIRE specifically, not this plan's full retrieval matrix.

Any external dataset used gets a pinned `dataset_version` per §5 below — no floating "latest" downloads in a benchmark run record.

---

## 4a. Local Dataset Configuration

All Issue #14 datasets specified in `tools/retrieval-eval/Datasets.md` are already downloaded to `testing` folder (a sibling of `platform` and `content_extractor`, matching this workspace's existing multi-repo layout convention). The harness must reference them from there by config, and must not attempt to (re-)download anything.


**Confirmed local layout:**

```text
testing/
    ├── PIRE/
    │   ├── README.md              # HF dataset card
    │   └── pdf_files.zip           # not yet extracted
    ├── RAG-Multi-Corpus/           # full git clone
    │   └── datasets/
    │       ├── Dataset categories - queries_01122025.csv   # master query file, all orgs
    │       ├── Aventro Motors/{pdf,md,docx,pptx,html}/ + Aventro Motors.csv
    │       ├── Velvera Technologies/...
    │       ├── ZX Bank/...
    │       ├── CloudWay-24/...
    │       └── Cendara University/...
    ├── OHR-Bench/
    │   ├── OHR-Bench.parquet
    │   ├── OHR-Bench_v2.parquet
    │   ├── pdfs.zip                # 1.5GB
    │   └── retrieval.zip           # 126MB
    └── bench-chunknorris-acl2025/   # reference codebase, not a dataset
```

**Config file — `platform/tools/retrieval-eval/config.yml`** (template, created alongside this plan; the harness code that reads it is Phase B0 future work):

```yaml
datasets:
  root: ${RETRIEVAL_BENCH_DATASETS_DIR:-/home/user/workspace/synanton/testing}

  pire:
    path: PIRE
    archive: pdf_files.zip           # harness extracts on first use; not committed anywhere

  rag_multi_corpus:
    path: RAG-Multi-Corpus/datasets
    queries: RAG-Multi-Corpus/datasets/Dataset categories - queries_01122025.csv
    organizations:
      - Aventro Motors
      - Velvera Technologies
      - ZX Bank
      - CloudWay-24
      - Cendara University

  ohr_bench:
    path: OHR-Bench
    manifest: OHR-Bench_v2.parquet
    pdfs_archive: pdfs.zip
    retrieval_archive: retrieval.zip
```

Conventions, matching the rest of this repo rather than inventing a one-off Python-tool style:

- `${VAR:-default}` interpolation mirrors `platform`'s own `.env.example`/`compose.yaml` pattern, so overriding the root (e.g. on a CI runner or a different workstation) is one env var, not an edited file.
- Paths in the config are relative to `datasets.root`; nothing under `testing/` is ever copied into the `platform` repo. Any extracted/cached files the harness produces (e.g. `PIRE/pdf_files/` after unzipping) stay under `testing/` or a harness-local `.cache/` directory excluded via `.gitignore` (§9 deliverable 2) — the multi-GB archives never touch git.
- `RETRIEVAL_BENCH_DATASETS_DIR` is the single override point if `testing/` ever moves or a second machine stages datasets elsewhere.

---

## 5. Metrics

Primary, per Design 1.31 §85/87 (non-negotiable — these are what the design doc itself asks this benchmark to produce):

- **Recall@10**
- **NDCG@10**
- **p95 latency**

Secondary, per Issue #14 §4.2 and Design 1.31 §86:

| Metric | Source |
|---|---|
| MRR@10, Recall@100, Precision@10 | Benchmark harness, computed from `synquest` search responses |
| Mean latency, throughput (QPS) | Load test against `synquest`/`gateway` |
| Indexing time | `synquest` boot-time index build, timed |
| Candidate-generation / ranking / reranking / embedding latency | `SearchService`'s existing `SearchTrace` fields (`embedMs, denseMs, lexicalMs, fusionMs`) once exposed on the response (§2) |

**Benchmark run record** — per Design 1.31 §88, every run must persist:

```yaml
benchmark:
  dataset_version: ...
  knowledge_version: ...
  search_config: ...
  embedding_model: ...
  retrieval_strategy: ...
  reranker: ...
```

For a run against a local dataset from §4a, `dataset_version` is the archive's content hash (e.g. sha256 of `pdf_files.zip`) or, for `RAG-Multi-Corpus` (a git clone), its commit hash — not a filesystem timestamp, so a re-run against a re-cloned/updated copy is distinguishable.

---

## 6. Methodology

### Phase B0 — Harness (1 week)

1. **Done.** `tools/retrieval-eval/` scaffold: config loader (`config.py`), metrics (`metrics.py`, unit-tested), gold-query loader (`gold.py`), benchmark-run record writer (`run_record.py`), ingest/query wrappers around the real `synflux`/`synquest` REST APIs (`ingest.py`/`query.py`, matching `run-extract-index-poc.sh` exactly), a compose-port resolver (`compose.py`), and a `retrieval-eval` CLI (`cli.py`) with `check-config`/`ingest`/`evaluate` subcommands. See `tools/retrieval-eval/README.md` for usage and an explicit list of what's real vs. stub.
2. **Done.** New `demo-data/eval/retrieval-benchmark/queries.jsonl` — 10 starter queries grounded in the real demo corpus (`acme-corp-profile.md`, `globex-manufacturing.md`, `vendor-agreements.txt`, `structured-supply-chain.md`), covering table-lookup, factual, multi-hop/graph-relevant, procedural, section-local (hierarchical), negative, and keyword categories. `flat-vs-semantic-chunks-research-plan.md`'s own `queries.jsonl` doesn't exist yet, so this file was created fresh rather than forked. **Gold chunk IDs annotated** (2026-09-20) against real ingested tenants using the new `retrieval-eval inspect` CLI command — two per-tenant files, `queries.rb-fixed.jsonl` and `queries.rb-semantic.jsonl` (chunk IDs differ per tenant since `content_ref_id` is a fresh UUID per ingestion run, even for the same source file).
3. **Done.** §5's benchmark-run YAML record is implemented in `run_record.py` and exercised by `cli.py evaluate`. `query.py`/`cli.py evaluate` also gained `--top-k-dense`/`--top-k-lexical` to isolate one side of the hybrid fusion (pass `0` to suppress dense; `1`, not `0`, to suppress lexical — `synquest`'s Lucene lexical path rejects `n=0`, confirmed empirically).

### Phase B1 — Retrieval-only benchmark, existing strategies (1 week)

**Status: T01 and T04 done (2026-09-20); T02/T03 blocked, not run — see finding below.**

Run T01-T04 (fixed/semantic chunking × BM25/dense/hybrid, no new code) — this validates the harness against **already-implemented** retrieval paths before any new-work items land, isolating harness bugs from feature gaps.

**Finding: dense retrieval is unreachable in the Phase 1 stack.** `synquest`'s `QueryEmbedder` always calls out to `EMBED_BASE_URL` (vLLM's embedding service) with no fallback; that service only exists behind `docker compose --profile phase2`, which needs 2×8GB GPUs per the platform README and isn't running in the Phase 1 stack this benchmark uses. Every query in this environment reports `query_usage.embed_skipped=true`; `top_k_dense` has nothing to suppress. **T02 (dense) and T03 (hybrid) would therefore just duplicate T01's BM25-only results today** — running them as if they were real would misreport "hybrid adds nothing" when hybrid was never actually exercised.

Decision (user-confirmed): run only the two genuinely distinct arms available in this environment —
- **T01** — `rb-fixed`, BM25-only (`--top-k-dense 0`), flat/fallback chunking.
- **T04** — `rb-semantic`, labeled "hybrid" but functionally BM25-only for the same reason as above; the arm's actual distinguishing variable is chunking strategy, not retrieval strategy, since none of the 10 gold queries target the 3 new structurally-rich PDFs yet (§4 update).

**T02/T03 are blocked, not skipped** — pending either the Phase 2 GPU profile being started, or another reachable embedding endpoint. The reachable endpoint now exists — the GPU plane's GPU-7 external mode — and the plan for using it is §6 Phase B1-G. `results/T03.yaml` (recorded 2026-09-18 against `demo-data-documents-v1`, every metric `0.0`) predates the gold-chunk annotation and was never a valid hybrid run; it is **superseded** and must not be cited. Results:

| Run | Tenant | Recall@10 | NDCG@10 | p95 latency | Record |
|---|---|---|---|---|---|
| T01 | `rb-fixed` | 0.900 | 0.756 | 8549ms | `demo-data/eval/retrieval-benchmark/results/T01.yaml` |
| T04 | `rb-semantic` | 0.900 | 0.736 | 8528ms | `demo-data/eval/retrieval-benchmark/results/T04.yaml` |

Recall is identical (expected — every gold query's answer lives in a document that chunks identically, as one flat chunk, under both tenants; see §4 update). The small NDCG difference (0.756 vs 0.736) is not a meaningful structural signal — it reflects tie-breaking over different per-tenant chunk UUIDs, not a real quality difference, since no query in this set actually touches a document with real structural divergence. **A meaningful T01-vs-T04 comparison requires new gold queries against the 3 newly-added PDFs** (tracked as an open item, not yet done) — the corpus and harness are now capable of it, but the query set hasn't caught up yet.

### Phase B1-G — Dense/hybrid via GPU-7 and free models (planned 2026-09-25)

**Why GPU-7.** `gpu-runtime`'s external-provider mode (GPU-7, Deployment Plan v3.1.0) is complete and passing acceptance; local GPU-5 is blocked on execution-JWT signing (T-K8S-6a). GPU-7 serves `Operation.EMBED` through the same `synanton.gpu.v1` contract GPU-5 will, so everything built here carries over to GPU-5 unchanged — only the catalog's logical model id changes (`synanton-bge-base-embedding`).

**Integration decision (user-confirmed, closes T-INT-1 for this benchmark): shared gRPC `LlmClient`.** `synquest.QueryEmbedder` and `synflux.EmbedStage` today call an OpenAI-compatible HTTP `/v1/embeddings` (`EMBED_BASE_URL`/`VLLM_EMBED_BASE_URL`); the GPU plane is gRPC-only over mTLS with no REST façade. Rejected alternatives: a benchmark-only HTTP→gRPC shim (a de-facto REST façade, adds latency skew) and pointing `EMBED_BASE_URL` straight at OpenRouter (bypasses the GPU plane's free-model guard, budget, kill switch and model-id rewrite, and puts the provider key in the platform).

**Invariants for every B1-G run:**
- **Fail closed, no silent CPU fallback.** The existing `gateway.gpu.GpuEmbeddingAdapter` degrades to the CPU `LlmClient` on failure; the benchmark path must not — a failed embed aborts the run (ingest) or is counted as a failed query, never an unembedded "dense" result. `query_usage.embed_skipped=true` on any query invalidates the run.
- **Free models only.** Only `*:free` provider models; spend on the key must be unchanged after each run (checked before/after, recorded).
- **Logical ids only on the platform side.** Platform config and run records name `synanton-free-embedding*`, never the provider model id.
- **One embedding model per index.** Changing model or dimension means re-ingest into a fresh tenant; embeddings are never mixed across models.

**Steps (one commit per step; code steps get their own tests):**

| Step | Work | Repo |
|---|---|---|
| G0 | **Probe, no code.** Read the key's rate limits (`GET /api/v1/key`; key never printed). One `EMBED` per free model via `tools/gpu7-check` to record native dim, whether the provider honours `dimensions`, and whether truncating to 1024/768 + re-normalising preserves neighbour order on a handful of gold chunks (Matryoshka check). Output: a short table appended to this section; decides the dimension per arm. | gpu-runtime tools |
| G1 | **Shared gRPC embed client.** Extract the EMBED path of `GpuEmbeddingAdapter` into a small shared module (e.g. `java/gpu-client`, depending on `gpu-contract`) implementing `org.synanton.llm.LlmClient`, with mTLS config (`GPU_TLS_*`, principal `synanton-platform`), per-call `tenant_id`, canonical error codes and a `fail-closed` mode. `gateway` keeps its current degrade behaviour by wrapping it. Opt-in `gpu-plane` Spring profile in `synquest` and `synflux` selects it instead of `HttpLlmClient`. Tests: in-process gRPC server, mTLS, fail-closed on `circuit_open`/`budget_exceeded`/`routing_disabled`. | platform |
| G2 | **Configurable dimension.** `EMBED_DIM` for `synquest.embedding.dim` and a matching optional truncation (`EMBED_TRUNCATE_DIM`, then L2 re-normalise) applied identically at ingest (`EmbedStage`) and query time (`QueryEmbedder`); startup fails if the configured dim exceeds the Lucene cap. `synflux` `embedding.model-id` becomes env-driven (`EMBED_MODEL_ID` already exists in `application-phase2.yml`; hoist it). | platform |
| G3 | **Catalog + tenants.** Add `synanton-free-embedding-nemotron-vl` and `synanton-free-embedding-lfm` to `gateway-external.yaml`; authorise benchmark tenants (`rb-fixed-g`, `rb-semantic-g`, one pair per embedding arm) for `synanton-platform`. `tools/gpu7-package-check.py` and `gpu7-check` stay green. | gpu-runtime |
| G4 | **Harness.** `retrieval-eval` gains: a query-embedding cache keyed by (logical model, dim, query sha256) so re-scoring a run costs no requests; a request budget + throttle (default ≤ 15 req/min, hard stop at a configured daily budget) with resumable ingest; run-record fields `gpu_plane: gpu-7`, `provider_mode: external-free`, `embedding_dim`, `embed_requests`, `spend_before`/`spend_after`; and a run-validity check (any `embed_skipped` ⇒ invalid). | platform |
| G5 | **Run T02-G/T03-G/T04-G** with `synanton-free-embedding`: re-ingest `rb-fixed-g`/`rb-semantic-g` (fresh tenants, new chunk UUIDs ⇒ gold chunk ids re-annotated with `retrieval-eval inspect`), then T02-G (`rb-fixed-g`, `--top-k-lexical 1`), T03-G (`rb-fixed-g`, full hybrid), T04-G (`rb-semantic-g`, full hybrid — the first *real* hybrid T04). Records `results/T02-G.yaml`, `T03-G.yaml`, `T04-G.yaml`. | platform |
| G6 | **RQ4 on free models (B3 early subset).** Repeat T03-G/T04-G for the other free arms that pass G0. `lfm` runs only if no chunk in the tenant exceeds 512 tokens (checked, recorded); otherwise reported as excluded. | platform |
| G7 | **Report + docs.** Results table in this section; §9 deliverable 8; `gpu-plane-integration-tickets.md`; README status. | platform |

**Request budget (estimate — replaced by G0/G5 actuals).** OpenRouter's documented free-model limits are ~20 requests/min and **50 requests/day** for accounts with under $10 of purchased credit (1,000/day above that); the capped key is assumed to be in the lower tier until G0 says otherwise. Ingest is ~⌈chunks/32⌉ requests per tenant (`synflux` `batch-size: 32`; embeddings are cached in `ingestion-cache` per tenant, chunk hash and model, so resumed ingests don't re-pay); queries are 10 per run, and the G4 query cache means one pass per (tenant, model, dim) serves every run on that tenant. Expect G5 to fit in one day and each G6 arm in about one more; the throttle stops a run rather than let it hit HTTP 429 mid-way.

**What B1-G does not do.**
- **Rerank (T10/T11):** OpenRouter has no free rerank model; GPU-7 returns `capability_not_supported` for `RERANK` on the real arm. Reranking stays on GPU-5 (`synanton-qwen3-reranker-0.6b`). The GPU-7 mock reranker is for wiring tests only and is never a benchmark row.
- **Latency comparability:** GPU-7 latency includes the WAN round trip and shared free-tier queueing. `embedMs` from `SearchTrace` is reported separately, and B1-G p95 numbers are never compared with GPU-5 or Phase 2 rows. Recall/NDCG comparisons are valid; latency comparisons across planes are not.
- **bge-base rows:** T02/T03 as defined in §3.4 (bge-base) still require GPU-5; B1-G adds `-G` rows alongside them and doesn't close them.

### Phase B2 — New retrieval capability (2-3 weeks)

1. Hierarchical chunking (T05).
2. Graph rank-fusion into `synquest`/`gateway` (T08, T09).
3. Reranker `RerankerPort` + adapter (T10, T11).
4. Re-run the full T01-T11 matrix once each lands.

### Phase B3 — Embedding model comparison (1 week, parallel with B2)

Run T02-T03 variants across `bge-base` → `bge-large` → `e5-mistral-7b` (with the GPU-capacity smoke test from §3.3 gating T07's inclusion) → optional `text-embedding-3-small`.

### Phase B4 — External dataset extension (optional, post-B0-B3)

Adopt PIRE first per §4/§4a (smallest, already local); re-run the core matrix subset (T01, T04, T09) on it to check whether findings generalize beyond the demo corpus. RAG-Multi-Corpus and OHR-Bench follow only if PIRE's results justify the larger integration lift.

### Phase B5 — Report (3-5 days)

Decision memo comparable to `flat-vs-semantic-chunks-research-plan.md`'s own §6/§9: recommend default retrieval configuration (hybrid vs. hybrid+graph vs. hybrid+graph+rerank) per query type, with the quality/latency/cost trade-off made explicit — this is the artifact Design 1.31's implementation should eventually be validated against once 1.31 itself is built (see [platform-api-plane/INDEX.md](../implementation/platform-api-plane/INDEX.md) and [eventing-workflow-plane/INDEX.md](../implementation/eventing-workflow-plane/INDEX.md) for the surrounding platform work this benchmark's results will eventually inform).

---

## 7. Environment

Corrected against `content_extractor/doc/k8s-test-hardware.md` (the only real hardware doc in either repo) rather than assumed:

| Component | Reality |
|---|---|
| Primary iteration environment | `docker compose` (this repo's existing `run-extract-index-poc.sh` stack) — matches that doc's own stated preference, faster to iterate on |
| Fallback hardware (if compose isn't sufficient) | Homelab k8s cluster, **not project-owned infrastructure**, lent for PoC only: `node0` (4 CPU/16Gi, no GPU, control-plane), `node1` (16 CPU/64Gi, GTX 1650 4GB), `node2` (20 CPU/64Gi, RTX 4060 Ti 16GB), `node3` (20 CPU/64Gi, RTX 5060 Ti 16GB) |
| GPU availability | Three consumer GPUs total, one per worker node, no MIG/time-slicing — **not** an A100, and not simultaneously available as one large pool |
| Storage | `local-ssd` (hostPath) preferred over `longhorn` for latency-sensitive local caches, per the same doc |
| Vector/graph store | None external — `synquest` (Lucene, embedded) and `relix` (in-memory/Neo4j-optional) run in-process or as existing compose services |
| Dataset storage | `testing` on the local workstation (§4a) — not synced into any compose volume unless a specific test needs it mounted |
| Python | 3.11+ for the harness scripts, matching Issue #14's original environment spec (the one part of that spec that doesn't conflict with anything real) |

Any B2/B3 phase needing more than one GPU concurrently (e.g. comparing two self-hosted embedding models side by side) schedules across `node2`/`node3`, not a single larger GPU that doesn't exist here.

---

## 8. Confounds and Mitigations

| Confound | Mitigation |
|---|---|
| Reranker and graph-fusion land mid-benchmark, changing the code under test | Phase B1 locks a baseline on already-implemented paths *before* B2's new work starts, so B1's numbers are a clean pre/post reference |
| `e5-mistral-7b` may not fit comfortably on a 16GB consumer GPU alongside other services | Smoke-test in isolation before committing T07 to the full matrix; drop it from the baseline matrix (keep in Open Questions) if it doesn't fit |
| External embedding API (`text-embedding-3-small`) introduces cost and a network dependency the rest of the benchmark doesn't have | Gated behind explicit opt-in; excluded from the primary Recall@10/NDCG@10/p95 comparison table, reported separately |
| Reusing `flat-vs-semantic`'s gold queries for RQ1's hierarchical arm without adding hierarchy-specific questions | Extend the query set explicitly (§6 Phase B0 item 2) rather than reuse as-is and claim hierarchical coverage that isn't there |
| GPU-7 free-tier latency (WAN + shared queueing) mistaken for model/strategy latency | `-G` rows report `embedMs` separately; their p95 is never compared with GPU-5/Phase 2 rows (§6 Phase B1-G) |
| Silent CPU fallback or skipped embeds making a "dense" run secretly BM25-only (the exact failure that invalidated the old `T03.yaml`) | The benchmark embed client fails closed; any `embed_skipped=true` invalidates the run (G1, G4) |
| Dimension reduction (2048 → ≤1024, Lucene cap) degrading a free model | Matryoshka check in G0 before any run; the dim is recorded in `embedding_model`; if a model fails the check, use a codec dimension override or drop the arm |
| Free-tier rate limits cutting off a run partway | Throttle, daily budget and resumable ingest in the harness (G4); a partial run is invalid, not reported |
| External dataset content changes between download and use (RAG-Multi-Corpus is a live git clone) | Pin `dataset_version` to a commit hash at the time of use (§5), not "whatever's on disk today" |

---

## 9. Deliverables

| # | Deliverable | Location | Status |
|---|---|---|---|
| 1 | Local dataset config template | `tools/retrieval-eval/config.yml` | Done |
| 2 | `.gitignore` entry for harness-extracted/cached dataset content | `tools/retrieval-eval/.gitignore` | Done |
| 3 | Benchmark harness (config, metrics, ingest/query wrappers, CLI incl. `inspect`, `--top-k-dense`/`--top-k-lexical`) | `tools/retrieval-eval/` | Done (B0/B1 scope; B2's new-capability wiring still pending) |
| 4 | Gold query set, annotated per tenant | `demo-data/eval/retrieval-benchmark/queries.{jsonl,rb-fixed.jsonl,rb-semantic.jsonl}` | Done (10 queries, real chunk IDs annotated against live `rb-fixed`/`rb-semantic` tenants) |
| 5 | `RerankerPort` SPI + one adapter | `java/gateway` | Not started (Phase B2) |
| 6 | Hierarchical chunking strategy | `java/synflux` | Not started (Phase B2) |
| 7 | Graph rank-fusion | `java/synquest` and/or `java/gateway` (decided during B2 implementation) | Not started (Phase B2) |
| 8 | Benchmark-run records (§5 YAML) per run | `demo-data/eval/retrieval-benchmark/results/` | Done for T01, T04 (2026-09-20); `T03.yaml` (2026-09-18) superseded/invalid; T02-G/T03-G/T04-G planned (§6 Phase B1-G); bge-base T02/T03 blocked on GPU-5 |
| 11 | Shared gRPC embed client (fail-closed) + `gpu-plane` profile in `synquest`/`synflux`; configurable embedding dim | `java/` (new shared module), `java/synquest`, `java/synflux` | Planned (B1-G G1/G2) |
| 12 | GPU-7 free embedding catalog arms + benchmark tenants | `gpu-runtime/deployments/external/config/gateway-external.yaml` | Planned (B1-G G3) |
| 13 | Harness: query-embedding cache, request throttle/budget, GPU-7 run-record fields, validity check | `tools/retrieval-eval/` | Planned (B1-G G4) |
| 9 | Decision memo | `docs/research/retrieval-evaluation-benchmark-results.md` (after B5) | Not started |
| 10 | 3 real structurally-rich PDFs added to corpus | `demo-data/documents/{mental-health-report-2010,outsourcing-agreement,sks8300-web-interface-manual}.pdf` | Done (2026-09-20); gold queries against them not yet written |

---

## 10. Open Questions

0. **Gold queries against the 3 new PDFs** — none of the 10 original queries target `mental-health-report-2010.pdf`, `outsourcing-agreement.pdf`, or `sks8300-web-interface-manual.pdf`, so T01-vs-T04's real structural divergence (confirmed via direct Cassandra inspection) isn't yet reflected in any Recall/NDCG number. Writing these is the most direct next step toward a *meaningful* T01-vs-T04 comparison.
0a. **`content_extractor`'s markdown heading gap** — `TextModalityAdapter` treats `.md` files as flat prose (Tika `AutoDetectParser`, no markdown-aware parsing). Fixing this (a real feature addition, not a bug) would let the *original* corpus's text/markdown files also exercise real semantic chunking, not just the 3 added PDFs. Out of scope for this plan; noted for whoever owns `content_extractor` roadmap next.
0b. **Starting the Phase 2 GPU profile, or standing up the real GPU Runtime instead** — needed before T02/T03 can run for real. The homelab k8s cluster (§7) is being recreated as a cluster dedicated to Synanton's GPU plane; see [`gpu-plane-integration-tickets.md`](./gpu-plane-integration-tickets.md) and `gpu-runtime/doc/k8s-reference-deployment-plan.md` for the full ticket backlog this depends on. Not this benchmark's own concern to execute, only to consume once it lands. **Update 2026-09-25:** GPU-5 is deployed-shape-complete but blocked on execution-JWT signing (`gpu-runtime` T-K8S-6a). GPU-7 (external mode) is complete, so dense/hybrid now proceeds there first (§6 Phase B1-G), and the integration path is decided: a shared gRPC `LlmClient`, not HTTP. The bge-base rows move to GPU-5 once T-K8S-6a lands, reusing the same client with the logical model changed to `synanton-bge-base-embedding`.
1. **Cohere/Voyage embedding comparison** — in scope only if a translator gets built; not committed in this plan. Revisit after B3's self-hosted results — if self-hosted models already show a clear winner, the commercial comparison may not be worth the integration cost.
2. **Where does graph rank-fusion belong** — inside `synquest`'s `RrfFusion` (treating graph as a third ranked list) or as a `gateway`-level re-ranking pass over already-fused hybrid results? Both are architecturally defensible; decide during B2 based on which keeps `synquest` and `relix` more independently testable.
3. **`e5-mistral-7b-instruct` on consumer GPU** — feasibility unconfirmed; the smoke test in §3.3/§8 answers this before B3 commits to it.
4. **External dataset adoption (Phase B4)** — worth doing at all before Design 1.31 itself has real implementation to validate against, or better deferred until then? Leaning toward deferring given the "not started" status of 1.31, but not decided here.
5. **PIRE's missing HF data shards** — the local copy has the dataset card and PDFs but not the `chunk.single`/`chunk.multi` parquet splits the card references. Fetch them, or derive gold spans directly from the card's documented schema? Decide when Phase B4 actually starts.

---

## 11. References

1. [synanton-design-1.31.md](../architecture/synanton-design-1.31.md) §84-91 — the normative source for this benchmark's scope and constraints
2. [synanton-platform-architecture-1.0.md](../architecture/synanton-platform-architecture-1.0.md) §9 (Search Boundary), §15 (1.31 status: Approved, not started)
3. [flat-vs-semantic-chunks-research-plan.md](./flat-vs-semantic-chunks-research-plan.md) — sibling plan, owns fixed-vs-semantic in depth
4. `content_extractor/doc/k8s-test-hardware.md` — real hardware reference
5. `testing/` — local dataset staging area (§4a)
6. Issue #14 / SNTP-9 original test plan — source of the query taxonomy, test-matrix structure, and dataset shortlist adapted here; superseded on pipeline/infrastructure/hardware assumptions per §0 above
