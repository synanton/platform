---
title: "Retrieval Evaluation Benchmark — Research Plan (SNTP-9 / Issue #14)"
status: "in progress — B0, B1 (GPU-5), T-INT-3 and B2.1 rerank done; B2.2 hierarchical (T05) next, then graph (T08/T09); B1-G blocked on OpenRouter reachability"
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

**GPU-7 free-model arms (added 2026-09-25, §6 Phase B1-G).** Until GPU-5 served `bge-base` for real (T-K8S-6a; done 2026-09-25, cluster phase 5 passed), dense retrieval runs through the GPU plane's external-provider mode (GPU-7) against **OpenRouter free embedding models only** — the only embedding models the capped test key may call (`allowed-model-pattern: ".*:free"`). None of them is `bge-base`, so these arms change the embedding variable and are reported as their own rows (`T02-G`/`T03-G`/`T04-G`), never as substitutes for the bge-base rows of §3.4:

| Logical model (GPU-7 catalog) | Provider model (never exposed downstream) | Native dim | Context | Notes |
|---|---|---|---|---|
| `synanton-free-embedding` | `nvidia/nemotron-3-embed-1b:free` | 2048 | 32k | Primary GPU-7 arm (already in `gateway-external.yaml`) |
| `synanton-free-embedding-nemotron-vl` | `nvidia/llama-nemotron-embed-vl-1b-v2:free` | 2048 (G0) | 131k | Second free arm for RQ4 (catalog: G3) |
| `synanton-free-embedding-lfm` | `liquid/lfm-2.5-embedding-350m:free` | 1024 (G0) | **512 tokens** | Catalog: G3. Only valid where every chunk fits in 512 tokens; otherwise excluded, not silently truncated |

Two constraints follow from the current code, both verified: Lucene 9.11.1 (`gradle/libs.versions.toml`) caps `KnnFloatVectorField` at **1024 dims** by default, and `synquest`'s `synquest.embedding.dim` is hard-coded to `768` (`java/synquest/src/main/resources/application.yml`). A 2048-dim arm therefore needs either a cut to ≤1024 dims plus L2 re-normalisation, or a per-field codec override raising the dimension cap. **G0 settled this:** every arm runs at 1024, cut client-side (§6 Phase B1-G, G0 findings). The chosen reduction is recorded in the run record's `embedding_model` field (e.g. `synanton-free-embedding@1024`).

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

**Update 2026-09-25: T02, T03 and a real T04 (T04-v2) have now run on GPU-5; see §6 Phase B1-K.** The rest of this paragraph is the original 2026-09-20 finding. **T02/T03 are blocked, not skipped** — pending either the Phase 2 GPU profile being started, or another reachable embedding endpoint. The reachable endpoint now exists — the GPU plane's GPU-7 external mode — and the plan for using it is §6 Phase B1-G. `results/T03.yaml` (recorded 2026-09-18 against `demo-data-documents-v1`, every metric `0.0`) predates the gold-chunk annotation and was never a valid hybrid run; it is **superseded** and must not be cited. Results:

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
- **One embedding model per index.** Each tenant's index holds vectors from exactly one logical model (`synquest` reads only rows for `EMBED_MODEL`). Changing the model means re-ingesting into a fresh tenant. Changing only the dimension needs a `/reindex`, not a re-ingest, because `synflux` stores native vectors and `synquest` truncates at index and query time (G2).

**Steps (one commit per step; code steps get their own tests):**

| Step | Work | Repo |
|---|---|---|
| G0 | **Done (2026-09-25)** — findings below. Read the key's rate limits (`GET /api/v1/key`; key never printed). One `EMBED` per free model via `tools/gpu7-check` to record native dim, whether the provider honours `dimensions`, and whether truncating to 1024/768 + re-normalising preserves neighbour order on a handful of gold chunks (Matryoshka check). Output: a short table appended to this section; decides the dimension per arm. | gpu-runtime tools |
| G1 | **Done (2026-09-25)** — shared fail-closed gRPC embed client (`java/gpu-client`) + opt-in `gpu-plane` profile in `synquest`/`synflux`; details below the table. | platform |
| G2 | **Done (2026-09-25)** — `EMBED_DIM` + `EMBED_TRUNCATE_DIM` in `synquest`, one truncation path for index build and query, startup validation, mismatch and coverage reporting; details below the table. | platform |
| G3 | **Done (2026-09-25)** — two more free embedding arms in the GPU-7 catalog, and a least-privilege `synanton-benchmark` principal for the six benchmark tenants; live-verified through the gateway. Details below the table. | gpu-runtime |
| G4 | **Done (2026-09-25)** — harness pacing, daily request budget, spend check via gpu-runtime, run validity, `gpu_plane` record fields, `rescore`; plus a synquest query-embedding cache and GPU-plane client pacing and 429 retry. Details below the table. | platform |
| G5 | **Blocked (2026-09-25): OpenRouter unreachable from this network** (Cloudflare 403 "Access denied by security policy", even unauthenticated). opencode.ai, now GPU-7's current test arm, has no `/embeddings` endpoint, so it can't replace OpenRouter for dense runs. Unblocks when OpenRouter is reachable again, or on GPU-5. **Run T02-G/T03-G/T04-G** with `synanton-free-embedding`: re-ingest `rb-fixed-g`/`rb-semantic-g` (fresh tenants, new chunk UUIDs ⇒ gold chunk ids re-annotated with `retrieval-eval inspect`), then T02-G (`rb-fixed-g`, `--top-k-lexical 1`), T03-G (`rb-fixed-g`, full hybrid), T04-G (`rb-semantic-g`, full hybrid — the first *real* hybrid T04). Records `results/T02-G.yaml`, `T03-G.yaml`, `T04-G.yaml`. | platform |
| G6 | **RQ4 on free models (B3 early subset).** Repeat T03-G/T04-G for the other free arms that pass G0. `lfm` runs only if no chunk in the tenant exceeds 512 tokens (checked, recorded); otherwise reported as excluded. | platform |
| G7 | **Report + docs.** Results table in this section; §9 deliverable 8; `gpu-plane-integration-tickets.md`; README status. | platform |

**G1 implementation notes (2026-09-25).** New `java/gpu-client` module:
- `GpuPlaneEmbedClient` implements `TenantAwareLlmClient`, a new sub-interface of `LlmClient` in `synanton-llm-client`. It fails closed: every failure throws `GpuPlaneException` with the canonical code.
- Only transient outcomes are retried: capacity denials, transport `UNAVAILABLE`, and a retryable `MODEL_NOT_READY`.
- `GpuPlaneChannels` builds the mTLS channel; `GpuErrorCodes` and `GpuEmbedCodec` provide the error codes and the payload codec (vectors ordered by `index`, count checked).
- The gateway module now delegates its channel, error-code and codec logic to the shared module and keeps its degrade-to-CPU `GpuEmbeddingAdapter`.
- The opt-in `gpu-plane` profile (`application-gpu-plane.yml`) is added in `synquest` and `synflux`. `synquest` passes the search tenant and sets `synquest.embedding.required=true`: a failed embed returns HTTP 503, never BM25-only. `synflux` passes the job tenant and runs `EmbedStage(failOnError=true)`: a failed batch fails the document, which is counted as a job error and not persisted.
- Environment variables: `GPU_PLANE_ENDPOINT`, `GPU_TLS_{ENABLED,CA_PATH,CERT_PATH,KEY_PATH,AUTHORITY}`, `EMBED_MODEL` (synquest) = `EMBED_MODEL_ID` (synflux), both logical IDs, default `synanton-free-embedding`.
- Tests: `GpuPlaneEmbedClientTest` (18, real Netty gRPC server including mTLS with a required client certificate), `GpuPlaneProfileWiringTest`, `QueryEmbedderTenantTest`, `EmbedStageGpuPlaneTest`.
- The embedding dimension was still 768 under this profile until G2.

**G2 implementation notes (2026-09-25).**
- **Settings** (`synquest`): `synquest.embedding.dim` = `EMBED_DIM` (default 768; 1024 under `gpu-plane`) and `synquest.embedding.truncate-dim` = `EMBED_TRUNCATE_DIM` (default 0, meaning off; 1024 under `gpu-plane`). In `synflux`, `embedding.model-id` is now `EMBED_MODEL_ID` in the base `application.yml` too.
- **One truncation path.** `EmbeddingShape.fit()` cuts the vector to the first `dim` components and L2-renormalises it. `LuceneIndexBuilder` (stored vectors) and `QueryEmbedder` (query vectors) both call it, so the two sides can't diverge.
- **Deviation from the original G2 text:** `synflux` does **not** truncate. The ingestion cache keeps native vectors (e.g. 2048-dim), and truncation happens only in `synquest`. This is lossless, keeps a single code path, and lets the same ingested tenant be indexed at 1024 or 768 with a `/reindex` instead of a re-ingest (the optional 768 variant from G0).
- **Startup validation:** the service refuses to start when `dim` is outside 1..1024 (`KnnVectorsFormat.DEFAULT_MAX_DIMENSIONS`, the Lucene 9.11 cap), or when `truncate-dim` is neither 0 nor equal to `dim`. Truncation never pads, so a shorter vector counts as a mismatch.
- **No silent mismatches.**
  - **Query side:** a vector that can't be made `dim`-long throws. With `synquest.embedding.required=true` the search returns HTTP 503. The same happens when dense search itself fails (e.g. an index built at another dimension), where previously it was a logged skip.
  - **Index side:** every build records `vector_docs`, `dim_mismatches` and `missing_vectors`, logs them, and exposes them on `GET /index/stats` together with `embedding_model`, `embedding_dim` and `embedding_truncated`. With `required=true` a build that isn't fully vectorised fails. Without it (the legacy default), the build still succeeds but reports the gap.
- **Bug found and fixed along the way:** a second constructor on the `SynquestProperties.Embedding` record makes Spring Boot drop every `synquest.embedding.*` property silently, falling back to the built-in defaults. The record keeps a single constructor, and `GpuPlaneProfileWiringTest` now asserts that binding works.
- **Tests:** `EmbeddingShapeTest` (5), `LuceneIndexBuilderDimensionTest` (3: a real Lucene index built with 1024-dim vectors from 2048-dim rows, mismatch counting, and failure under `required`), plus new cases in `QueryEmbedderTenantTest` and `GpuPlaneProfileWiringTest` (binding, and startup failure at `dim=2048`).
- **Switching an arm's dimension:** set `EMBED_DIM`/`EMBED_TRUNCATE_DIM` on `synquest`, restart, `POST /reindex?tenant=…`, then check `/index/stats` for `vector_docs == doc_count`.

**G3 implementation notes (2026-09-25; gpu-runtime `3bf36bb`).**
- **Catalog** (`deployments/external/config/gateway-external.yaml`, EMBED):
  - `synanton-free-embedding-nemotron-vl` → `nvidia/llama-nemotron-embed-vl-1b-v2:free`, `embedding-dim: 2048`.
  - `synanton-free-embedding-lfm` → `liquid/lfm-2.5-embedding-350m:free`, `embedding-dim: 1024`, `max-input-tokens: 512`.
  - Both are free and pass the gateway spend guard (`allowed-model-pattern: ".*:free"`). `max-input-tokens` is advertised in `GetModels`, not enforced: an over-long chunk to lfm fails upstream (`upstream_provider_error`), and fail-closed ingest then fails that document. That makes the G6 chunk-size check mandatory for lfm.
- **Principal `synanton-benchmark`.** It is limited to exactly the benchmark tenants, never `*` and without admin role:

**G4 implementation notes (2026-09-25).**
- **Query-embedding cache (synquest).** The plan put it in the harness, but the harness never embeds anything; synquest does. So it lives in synquest as `QueryEmbeddingCache`:
  - an LRU keyed by (tenant, logical model, query text), holding the vector after `EmbeddingShape.fit`;
  - `synquest.embedding.query-cache-size` = `EMBED_QUERY_CACHE_SIZE`: 0 (off) by default, 10,000 under `gpu-plane`;
  - failures are never cached, and the tenant is part of the key, so the GPU plane still authorizes every tenant;
  - `query_usage.embed_cached` says whether a request was made.
- **Pacing and 429 (gpu-client).** `gpu-plane.max-requests-per-minute` (`GPU_PLANE_MAX_RPM`, 15 in both `gpu-plane` profiles) paces every GPU-plane call, including ingest bursts inside synflux, which the harness can't pace. A provider 429 (`provider_rate_limited`, retryable) is now retried after `rate-limited-backoff-ms` (15 s × attempt). The gateway also counts 429s toward its circuit breaker, so pacing is what keeps the circuit closed.
- **Harness (`tools/retrieval-eval`):** `evaluate --gpu-plane gpu-7` adds:
  - pacing: 15 searches/min;
  - a daily request budget: 900 by default, with a local ledger in `.cache/`, measured for searches and estimated for ingest. The run stops before the limit; a stopped run is invalid;
  - spend snapshots before and after, from `gpu-runtime/tools/gpu7-check/gpu7_check.py --usage` (new flag; it prints only spend and quota). The platform never holds the key;
  - validity rules (`validity.py`): invalid on any `embed_skipped`, failed or 503 query, incomplete or mismatched index coverage (`/index/stats`, G2), a spend increase, or budget abort;
  - record sections `gpu_plane`, `latency_breakdown` (query-embed p50/p95, plan §8) and `validity`. Invalid runs are written to `results/invalid/` and exit with code 2.
  - Other additions: `ingest --retries N` (completed documents are skipped, so a re-run only re-pays failed ones), `rescore` (new gold, no searches, from `<run>.hits.json`) and `budget` (ledger + provider quota).
- **Validity applies to legacy runs too.** Without `--gpu-plane`, a "hybrid" run whose queries all report `embed_skipped` is invalid. That is exactly the superseded `T03.yaml`. Existing T01/T04 records are unchanged. T04 as recorded would now be flagged, as §6 Phase B1 already says: it was functionally BM25-only.
- **Tests:** harness 39 (`tests/test_gpu_plane_run.py` adds 22: ledger, throttle, spend parsing, every validity rule, end-to-end `evaluate`/`rescore` with synquest faked). gpu-client 21 (pacing, 429 retry). synquest `QueryEmbedderTenantTest` gains three cache cases. Platform `./gradlew check`: 401 tests, 0 failed.

  | Embedding arm (logical id) | Fixed-chunking tenant | Semantic-chunking tenant |
  |---|---|---|
  | `synanton-free-embedding` | `rb-fixed-g` | `rb-semantic-g` |
  | `synanton-free-embedding-nemotron-vl` | `rb-fixed-g-vl` | `rb-semantic-g-vl` |
  | `synanton-free-embedding-lfm` | `rb-fixed-g-lfm` | `rb-semantic-g-lfm` |

  Benchmark runs use its certificate, not the platform's `*`/admin one: `GPU_TLS_CERT_PATH`/`GPU_TLS_KEY_PATH` → `certs/synanton-benchmark.{crt,key}`, which `gen-certs.sh` now issues by default. Gateway audit and cost-ledger rows therefore separate benchmark traffic from platform traffic.
- **Budget, as defence in depth:** `tenant-daily-usd: 0.000001` for each benchmark tenant. Free calls (cost 0) never reach it, and a priced call would exhaust it at once (`budget_exceeded`).
- **Checks:**
  - `ExternalDeploymentConfigTest` (gpu-gateway) binds the shipped config with the real arm enabled, then runs `GatewayStartupValidator` and the `ProviderRouter` spend guard. It also asserts the arms, dims and principal scope.
  - `tools/gpu7-check` now EMBEDs every real arm through the gateway and checks vector length == catalog `embedding-dim` (live: 2048 / 2048 / 1024). It also checks the benchmark principal: allowed for `rb-fixed-g`, `tenant_not_allowed` for other tenants.
  - `tools/gpu7-package-check.py` rejects non-admin principals holding `*`.
- **Verified:** gpu-runtime `./gradlew build` (170 tests, 0 failed); `gpu7-check --compose` 20/20 with spend unchanged ($0.00052275); `gpu7-package-check.py --live` 16/16 (packaged smoke 27/27).

**G0 findings (2026-09-25).** Tool: `gpu-runtime/tools/gpu7-check/embed_probe.py`. Raw record: `demo-data/eval/retrieval-benchmark/results/G0-embed-probe.json`. 6 free requests, spend unchanged at $0.00052275.

*Key limits.* `is_free_tier: false`. The free-model quota is **1,000 requests/day**, not the assumed 50 (`free_model_daily_requests`). The $1 spend limit resets daily. The `used` counter didn't move during the probe, so it lags and can't be the harness's only budget signal; G4 counts its own requests.

*Models.* Tested with one batched request of 96 inputs (86 corpus passages + 10 gold queries). Passages are markdown heading sections or text paragraphs from the 12 `.md`/`.txt` demo documents, at most about 850 characters. A passage counts as relevant if it contains a hand-mapped gold-answer marker (`embed_probe.GOLD_MARKERS`). 9 queries are scored; rb009, the negative query, is excluded.

| Provider model (planned logical id) | Native dim | Provider `dimensions` field | Context | Batch latency (96 inputs) | Hit@1 / Hit@5 / MRR@10 at native |
|---|---|---|---|---|---|
| `nvidia/nemotron-3-embed-1b:free` (`synanton-free-embedding`) | 2048 | **Rejected** (HTTP 400, "dimensions must be one of 2048") | 32k | 3.1 s | 0.444 / 0.889 / 0.604 |
| `nvidia/llama-nemotron-embed-vl-1b-v2:free` (`…-nemotron-vl`) | 2048 | Honoured; result matches a client-side cut (cosine 0.9966 at 768) | 131k | 2.3 s | 0.444 / 0.889 / 0.630 |
| `liquid/lfm-2.5-embedding-350m:free` (`…-lfm`) | **1024** | Rejected (fixed at 1024) | 512 tok | 4.5 s | 0.667 / 0.889 / 0.741 |

*Matryoshka check.* The client-side cut is the first *d* components, then L2-renormalised. Overlap is the top-10 overlap with the native ranking, averaged over all 10 queries.

| Model | 1024: MRR / overlap / top-1 agree | 768 | 512 | 384 |
|---|---|---|---|---|
| nemotron-3 | 0.681 / 0.95 / 0.8 | 0.681 / 0.87 / 0.9 | 0.606 / 0.90 / 0.8 | 0.625 / 0.88 / 0.9 |
| nemotron-vl | 0.611 / 0.93 / 1.0 | 0.630 / 0.90 / 1.0 | 0.611 / 0.90 / 1.0 | 0.602 / 0.83 / 1.0 |
| lfm (native 1024) | — | 0.689 / 0.93 / 0.9 | 0.643 / 0.89 / 0.8 | 0.606 / 0.86 / 0.8 |

*Decisions.*
- **All three models pass G0 and go on to G5/G6.**
- **Every arm uses 1024 dims.** That is Lucene's default cap, so no codec override is needed. For both nemotron models, cutting 2048 → 1024 keeps quality within noise: one query is worth 0.111 Hit@1 on this 9-query sample. Top-10 overlap stays ≥ 0.93. lfm runs at its native 1024, where it scores best.
- **768 is an optional secondary variant** for direct comparability with bge-base (also 768), not the primary.
- **Truncation happens client-side**, in `synquest` at both index build and query time (G2, `EmbeddingShape`; `synflux` stores native vectors), not through the provider's `dimensions` field. nemotron-3 and lfm reject that field, and one code path for all arms keeps ingest and query vectors identical. G2's `EMBED_TRUNCATE_DIM` is therefore required, not optional.
- **lfm's 512-token window isn't hit by this corpus** (largest passage about 850 characters, roughly 200 tokens). G6 still checks real `synflux` chunk token counts, because the PDF sections may be longer.
- **Caveat.** This is a sanity check on a tiny sample (9 scored queries, 86 passages, answer-marker gold). It shows truncation isn't destructive; it doesn't rank the models. Model comparison is G6's job, on the real pipeline's chunks and annotated gold chunk ids.

**Request budget (G0 actuals).** The quota is 1,000 free requests/day. The binding limit is now the per-minute rate: OpenRouter documents about 20/min for free models, so G4's default pacing (15/min, both searches and GPU-plane client calls) stays. Ingest is ~⌈chunks/32⌉ requests per tenant (`synflux` `batch-size: 32`; embeddings are cached in `ingestion-cache` per tenant, chunk hash and model, so resumed ingests don't re-pay); queries are 10 per run, and the G4 query cache means one pass per (tenant, model, dim) serves every run on that tenant. The whole of G5 and G6 fits comfortably in one day's quota: the probe embedded 96 inputs in a single request, so each tenant ingest is a handful of requests. The throttle stops a run rather than let it hit HTTP 429 partway.

**What B1-G does not do.**
- **Rerank (T10/T11):** OpenRouter has no free rerank model; GPU-7 returns `capability_not_supported` for `RERANK` on the real arm. Reranking stays on GPU-5 (`synanton-qwen3-reranker-0.6b`). The GPU-7 mock reranker is for wiring tests only and is never a benchmark row.
- **Latency comparability:** GPU-7 latency includes the WAN round trip and shared free-tier queueing. `embedMs` from `SearchTrace` is reported separately, and B1-G p95 numbers are never compared with GPU-5 or Phase 2 rows. Recall/NDCG comparisons are valid; latency comparisons across planes are not.
- **bge-base rows:** T02/T03 as defined in §3.4 (bge-base) still require GPU-5; B1-G adds `-G` rows alongside them and doesn't close them.

### Phase B1-K — bge-base T02/T03/T04 on the home k8s GPU plane (GPU-5): done 2026-09-25

**Why now.** GPU-5 passed cluster phase 5 on 2026-09-25 (gpu-runtime T-K8S-6a). `synanton-bge-base-embedding` (TEI, node1) and `synanton-qwen3-reranker-0.6b` (vLLM, node2) answer end to end through Gateway → Envoy (JWT) → GPU. Measured single-request latency: EMBED 166 ms, RERANK 74 ms (the README GPU plane table has the full results).

That makes the **original** §3.4 rows possible: T02 (fixed, dense, bge-base) and T03 (fixed, hybrid, bge-base). It also allows a **real** T04: the recorded `T04.yaml` was functionally BM25-only (§6 Phase B1).

B1-G (GPU-7 free models) stays blocked: OpenRouter is unreachable, and opencode.ai has no embeddings. B1-K doesn't depend on it.

**What carries over unchanged from B1-G:**
- G1: the fail-closed gRPC embed client and the `gpu-plane` profile;
- G2: configurable dimension, run here at `EMBED_DIM=768` with no truncation;
- G4: harness validity rules, run records, `rescore`.

For `--gpu-plane gpu-5` the harness uses `provider_mode: local`: no spend check, no daily budget, no pacing.

**Run IDs and tenants:**

| Run ID | Tenant | Chunking | Retrieval | Embedding | Note |
|---|---|---|---|---|---|
| `T02` | `rb-fixed-g5` | fixed (extraction-gateway **down**) | dense (`--top-k-lexical 1`) | bge-base 768 | Original §3.4 row, first real run |
| `T03` | `rb-fixed-g5` | fixed | hybrid RRF | bge-base 768 | Replaces the superseded all-zero `T03.yaml` |
| `T04-v2` | `rb-semantic-g5` | semantic (extraction-gateway **up**) | hybrid RRF | bge-base 768 | First T04 with a real dense side. `T04.yaml` stays as the historical BM25-only record. |

**Steps (one commit each unless marked operator):**

| Step | Work | Repo / who |
|---|---|---|
| K1 | **Expose the Gateway to the workstation.** Add a NodePort Service `gpu-gateway-external` (gRPC 9090 → node1 `192.168.10.31:30990`, `externalTrafficPolicy: Local` so the client source IP survives). Add a NetworkPolicy ingress rule limiting 9090 to the workstation's IP block. Blueprint + Helm. mTLS still authenticates every call. Dev alternative: `kubectl port-forward --address <docker-bridge-ip>`. | gpu-runtime |
| K2 | **Benchmark principal on GPU-5.** Add `synanton-benchmark` to `gateway-local.yaml` + Helm, with tenants `rb-fixed-g5` and `rb-semantic-g5` only (no `*`, no admin). New `scripts/issue-client-cert.sh <cn>` that signs a client certificate with the **existing** GPU-5 CA (`git-ignored/gpu5-pki/ca.key`). `gen-certs.sh` would make a new CA and break the `gpu-gateway-tls` Secret. | gpu-runtime; **operator** runs the script (CA key) |
| K3 | **TEI input limit.** bge-base accepts 512 tokens. TEI runs without `--auto-truncate`, and the Gateway doesn't enforce `max-input-tokens`, so an over-long chunk would fail its whole document under fail-closed ingest. Decide: add `--auto-truncate` to TEI (blueprint + Helm; silent truncation, recorded in the run record) **or** keep it strict and treat failures as invalid runs. Recommended: keep it strict, and use the K5 dry run to measure how many chunks exceed 512 tokens. | gpu-runtime (if changed) + decision |
| K4 | **Platform overlay GPU-5 mode.** Split the GPU-7 network join out of `compose.gpu-plane.yaml` into `compose.gpu7-network.yaml`. `run-benchmark-gpu-plane.sh up --plane gpu5` then sets `GPU_PLANE_ENDPOINT=192.168.10.31:30990`, `GPU_TLS_AUTHORITY=gpu-gateway`, the PKI from `gpu-runtime/git-ignored/gpu5-pki` with client `synanton-benchmark`, `EMBED_MODEL(_ID)=synanton-bge-base-embedding`, `EMBED_DIM=768`, `EMBED_TRUNCATE_DIM=0` and `GPU_PLANE_MAX_RPM=0` (no free-tier limit). | platform |
| K5 | **Finish `remap-gold`.** Wire `retrieval_eval/remap.py` into the CLI (`retrieval-eval remap-gold --from-tenant rb-fixed --to-tenant rb-fixed-g5 --queries … --out …`) and add unit tests with a fake cqlsh. Changed chunks are reported for re-annotation with `inspect`, never guessed. | platform |
| K6 | **Dry run** on a throwaway tenant: ingest through the GPU plane; `/index/stats` must show `vector_docs == doc_count` and `embedding_dim` 768. Measure the chunk token distribution against TEI's 512 limit (K3). Run one `evaluate` to check validity end to end. | run |
| K7 | **Fixed tenant:** ingest `rb-fixed-g5` (extraction-gateway down, `--gpu-plane gpu-5 --retries 2`), remap gold from `rb-fixed`, then run **T02** and **T03** (`--gpu-plane gpu-5 --embedding-model synanton-bge-base-embedding --embedding-dim 768`). | run |
| K8 | **Semantic tenant:** start the extraction gateway (`docker-extraction-gateway`), ingest `rb-semantic-g5`, remap gold from `rb-semantic`, then run **T04-v2**. | run |
| K9 | **Report:** results table here (with T01 as the BM25 baseline), §9 deliverable 8, tickets T-INT-2b, README. The query-embedding latency (`latency_breakdown`) is LAN + GTX 1650 and not comparable with GPU-7 rows (§8). | platform docs |

**Results (2026-09-25).**
- Corpus: `demo-data-documents-v2-16docs`, 10 gold queries (rb009 is the negative query, so Recall@10 is capped at 0.9).
- Embeddings: `synanton-bge-base-embedding` (768) on GPU-5 via Gateway → Envoy (execution JWT) → TEI on node1.
- Every run is **valid**: every chunk vectorised, no embedding skipped, same model and dimension in the index and the run.
- Records: `demo-data/eval/retrieval-benchmark/results/{T02,T03,T04-v2}.yaml` (+ `.hits.json`).

| Run | Tenant (chunks) | Retrieval | Recall@10 | NDCG@10 | MRR@10 | P@10 | p50 / p95 latency | Query-embed p50 / p95 |
|---|---|---|---|---|---|---|---|---|
| T01 (baseline, 2026-09-20) | `rb-fixed` | BM25 | 0.900 | 0.756 | 0.714 | 0.155 | 3523 / 8549 ms (Phase 1 stack) | — (embedding unavailable) |
| **T02** | `rb-fixed-g5` (81) | dense only | 0.833 | 0.736 | 0.750 | 0.090 | 90 / 109 ms | 62 / 85 ms |
| **T03** | `rb-fixed-g5` (81) | hybrid RRF | **0.900** | **0.789** | 0.750 | 0.110 | 8 / 21 ms† | cached† |
| **T04-v2** | `rb-semantic-g5` (99) | hybrid RRF | **0.900** | **0.789** | 0.750 | 0.110 | 70 / 103 ms | 51 / 76 ms |

† T03 ran right after T02 on the same tenant with the same queries, so synquest's query-embedding cache served all 10 vectors (`embed_cached: 10`). Its latency therefore excludes embedding and isn't comparable. T04-v2 (a different tenant, uncached) shows the real hybrid latency.

**Findings:**
1. **Hybrid beats either side alone (RQ2).** T03 matches BM25's recall and improves NDCG@10 from 0.756 (T01) to 0.789. Dense alone (T02) is weaker: 0.833 / 0.736. It half-misses the exact-term query rb010 ("IATF 16949", recall 0.33), and hybrid recovers it (1.0).
2. **Chunking strategy is still unmeasured (RQ1).** T04-v2 equals T03 on every metric. All gold queries target the markdown and text documents, which chunk identically under both strategies (one flat chunk each; §4 update). The three structure-rich PDFs *are* chunked differently (99 vs 81 chunks) but have no gold queries. **T-INT-3 (gold queries on the PDFs) is now the blocking item for RQ1.**
3. **Latency (RQ5, same plane only).** Query embedding on the GTX 1650 across the LAN takes p50 51–62 ms and p95 76–85 ms; the first cold request took 461 ms in the dry run. End-to-end hybrid search takes p50 70 ms and p95 103 ms. T01's seconds-long latency came from the Phase 1 stack, where the embedding service was unreachable; it isn't a BM25 cost.
4. **Harness bug found and fixed in the K6 dry run.** `index_stats` didn't send `X-Tenant`, so synquest reported the `demo` tenant (commit `2e34afd`). The G4 coverage check would otherwise have compared the wrong index.

**Step status:**
- ✅ K1: NodePort `gpu-gateway-external` 192.168.10.31:30990, `ipBlock` allow-list (gpu-runtime `e46da65`).
- ✅ K2: `synanton-benchmark` principal on GPU-5 plus `issue-client-cert.sh` (`fab85a6`).
- ✅ K3: kept TEI **strict**. The dry run ingested all 16 documents with 0 errors, so no chunk exceeded 512 tokens.
- ✅ K4: overlay `--plane gpu5` (`c4fadfe`).
- ✅ K5: `remap-gold` (`9ed1047`). 11/11 gold IDs carried over for both tenants, with identical chunk hashes.
- ✅ K6: dry run (`rb-dryrun-g5`: 81/81 vectors, valid).
- ✅ K7, K8: runs above.
- ✅ K9: this report.

**Out of scope for B1-K, but unblocked by it:**
- **T10/T11 (reranker):** the GPU-5 reranker works, but the platform-side `RerankerPort` and adapter (B2) don't exist yet.
- **B3 larger embedding models:** each GPU already runs exactly one workload, so this would need a model swap on a node. It isn't part of this phase.

**Confounds specific to B1-K:**
- **LAN latency:** the network hop and the GTX 1650 dominate `embedMs`.
- **Shared GPU plane:** don't run a load test while benchmark runs are in progress.
- **Semantic chunking depends on the extraction gateway:** the extraction-gateway state must match the tenant (down for `rb-fixed-g5`, up for `rb-semantic-g5`), exactly as in B1.

### T-INT-3 — gold queries on the structure-rich PDFs + full arm matrix (done 2026-09-25)

**Query set v2** (`demo-data/eval/retrieval-benchmark/queries-v2.jsonl`; per tenant: `queries-v2.rb-{fixed,semantic}-g5.jsonl`) has 25 queries: the original 10 plus 15 new ones targeting the three PDFs:

| Source | Queries | Topics |
|---|---|---|
| `mental-health-report-2010.pdf` | 7 | data-source table rows, glossary definitions, medication table, advisory panel date |
| `sks8300-web-interface-manual.pdf` | 3 | default IP, HTTPS port and ciphers, firmware upgrade |
| `outsourcing-agreement.pdf` | 5 | parties and date, change-of-control threshold, forecast cadence, lab-testing costs, shipment certificates |

**Gold is objective:** `retrieval-eval annotate-markers` marks as relevant every chunk of `gold_source` whose normalised text contains a `gold_marker`. The same query therefore gets the right chunk IDs in each tenant, however that tenant chunked the file, and every PDF query resolves in both tenants.

The original 10 keep their hand annotation, carried over by `remap-gold`.

**Runs:** every run is valid, on GPU-5 with bge-base 768. Records: `results/{T01,T02,T03,T04b,T04d,T04}-q25.yaml`.

| Run | Chunking | Arm | All 25: Recall@10 / NDCG@10 | Original 10: R / NDCG / MRR | **PDF 15: R / NDCG / MRR** |
|---|---|---|---|---|---|
| T01-q25 | fixed | BM25 | 0.960 / 0.824 | 0.900 / 0.756 / 0.714 | 1.000 / 0.869 / 0.833 |
| T02-q25 | fixed | dense | 0.893 / 0.805 | 0.833 / 0.736 / 0.750 | 0.933 / 0.851 / 0.822 |
| T03-q25 | fixed | hybrid | **0.960 / 0.859** | 0.900 / 0.789 / 0.750 | **1.000 / 0.905** / 0.875 |
| T04b-q25 | semantic | BM25 | 0.920 / 0.801 | 0.900 / 0.756 / 0.714 | 0.933 / 0.831 / 0.807 |
| T04d-q25 | semantic | dense | 0.893 / 0.800 | 0.833 / 0.736 / 0.750 | 0.933 / 0.842 / 0.811 |
| T04-q25 | semantic | hybrid | 0.920 / 0.856 | 0.900 / 0.789 / 0.750 | 0.933 / 0.900 / **0.889** |

Latency: hybrid, uncached, p95 93–94 ms. The dense and BM25 rows reused cached query vectors, or needed none, so their latency isn't comparable.

**Findings:**
1. **RQ2 holds on the larger set.** Hybrid is the best arm under both chunking strategies: NDCG@10 0.859 fixed, 0.856 semantic. Dense alone is the weakest on recall.
2. **RQ1: fixed vs semantic is within noise on this corpus.** With 15 PDF queries, one query is worth 0.067 recall. The three PDF queries that differ between strategies each have an identifiable mechanism:
   - **pdf002** (data-source table row), *semantic better*: NDCG 0.63 → 1.00. The row sits in a clean table chunk.
   - **pdf005** (medication table), *semantic worse*: NDCG 0.63 → 0.50. Semantic chunking split the heading "Medications for Substance Use Disorders" into its own 115-character chunk (#25), away from its table (#26). This is **the case hierarchical chunking (T05, parent/child) targets.**
   - **pdf011** (parties and date), *missed by semantic*: every page of the agreement repeats a long "PARATEK PHARMACEUTICALS… REDACTED" boilerplate header, which crowds the cover chunk out of the top 10. This is a noise effect, and a **reranker (T10)** is the direct lever.
3. **Two fixes found while running:**
   - synquest returned 503 for BM25-only searches under `embedding.required` (Lucene k=0; `9ed868c`);
   - the harness `index_stats` used the wrong tenant (`2e34afd`, in B1-K).

### Phase B2 — New retrieval capability (2-3 weeks)

**B2 order and scope (user-confirmed 2026-09-25):**
1. **Rerank (T10):** in synquest, post-RRF.
2. **Hierarchical chunking (T05):** proper parent/child IDs.
3. **Graph (T08/T09):** full chunk-level graph.

Each is benchmarked as soon as it lands.

#### B2.1 Reranking (T10) — done 2026-09-25

**Correction to §0 / §2.** Reranking wasn't *only* a policy stub. The gateway's `PlanExecutor` already called `GpuRerankAdapter`, but:
- it's off by default and runs only for template T2;
- it reranks 200-character snippets;
- it sends Qwen3-Reranker raw text, which (next paragraph) inverts scores;
- it silently keeps RRF order on failure.

synquest, which the benchmark queries, had no reranking at all. The adapter's template gap is a gateway follow-up; the benchmark path below doesn't use it.

**Implementation (platform `ffaf195`):**
- synquest `/search` gets `rerank` and `rerank_candidates`. It fuses a wider RRF candidate list (default 50, max 100), reranks the **full stored chunk text** (up to 6000 chars) through the GPU plane (`GpuPlaneRerankClient`, RERANK op, `synanton-qwen3-reranker-0.6b` on GPU-5 node2), reorders, and cuts to `top_k`.
- Fail closed: an unconfigured or failing reranker returns 503, never RRF order labelled as reranked.
- The harness `evaluate --rerank` makes the run invalid if any response lacks `trace.rerank_ms`.

**Prompt format matters.** Qwen3-Reranker must receive its chat template (`RerankPromptFormat.QWEN3`). Measured through the GPU plane, with no template the scores **invert**: relevant 0.354 against distractors 0.681 and 0.769. With the template: 1.0 against 0.005 and 0.001.

**Results** (query set v2, bge-base 768, GPU-5; all valid; `results/{T03R,T04R}-q25.yaml`). Rerank@50 compared with hybrid RRF on the same tenant:

| Run | Chunking | All 25: R@10 / NDCG@10 / MRR@10 | Original 10: NDCG | PDF 15: R / NDCG / MRR | p50 / p95 latency |
|---|---|---|---|---|---|
| T03-q25 | fixed | 0.960 / 0.859 / 0.825 | 0.789 | 1.000 / 0.905 / 0.875 | 80 / 93 ms |
| **T03R-q25** | fixed | 0.960 / **0.893** / **0.880** | 0.786 | 1.000 / **0.965 / 0.967** | 1590 / 1657 ms |
| T04-q25 | semantic | 0.920 / 0.856 / 0.833 | 0.789 | 0.933 / 0.900 / 0.889 | 81 / 94 ms |
| **T04R-q25** | semantic | **0.960** / 0.871 / 0.853 | 0.780 | 1.000 / 0.931 / 0.922 | 1559 / 1609 ms |

**Findings:**
1. **Reranking is the largest quality gain so far.** NDCG@10 rises by 0.034 on fixed and 0.015 on semantic. On the PDFs, fixed goes from 0.905 to 0.965 NDCG and from 0.875 to 0.967 MRR. It recovers exactly the cases T-INT-3 predicted:
   - **pdf011**, the boilerplate-crowded cover chunk: 0.32 → 1.00 on fixed; missed → rank 1 on semantic, where it restores semantic recall to 0.960;
   - **pdf005**, heading split from its table: 0.63 → 1.00 on fixed.
2. **It isn't monotone.** rb008 and pdf012 drop from 1.00 to 0.63, and rb010, pdf010 and pdf014 dip slightly. The cross-encoder prefers a neighbouring chunk. Per-query-type defaults (B5) should account for this.
3. **Cost.** About 1.5 s p50 per query to rerank 50 full chunks on the RTX 4060 Ti, against about 80 ms for hybrid alone, so roughly 20×. Fewer candidates or shorter passages trade quality for latency; that sweep hasn't been run.

**Follow-up (gateway):** give `GpuRerankAdapter` the prompt-format option and full chunk text. Until then, enabling `gateway.rerank` with Qwen3-Reranker would *degrade* ranking.


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
| 5 | Reranking (T10): synquest post-RRF rerank + `GpuPlaneRerankClient` (GPU plane RERANK, Qwen3 template) | `java/synquest`, `java/gpu-client` | **Done (B2.1, 2026-09-25)**; the gateway adapter template gap is a follow-up |
| 6 | Hierarchical chunking strategy | `java/synflux` | Not started (Phase B2) |
| 7 | Graph rank-fusion | `java/synquest` and/or `java/gateway` (decided during B2 implementation) | Not started (Phase B2) |
| 8 | Benchmark-run records (§5 YAML) per run | `demo-data/eval/retrieval-benchmark/results/` | T01, T04 (2026-09-20, T04 functionally BM25-only). **T02, T03, T04-v2 on GPU-5 (2026-09-25, all valid; §6 Phase B1-K).** The old all-zero `T03.yaml` has been replaced. B1-G `-G` rows are blocked (OpenRouter). |
| 11 | Shared gRPC embed client (fail-closed) + `gpu-plane` profile in `synquest`/`synflux`; configurable embedding dim | `java/` (new shared module), `java/synquest`, `java/synflux` | Done (G1 + G2, 2026-09-25) |
| 12 | GPU-7 free embedding catalog arms + benchmark tenants | `gpu-runtime/deployments/external/config/gateway-external.yaml` | Done (G3, 2026-09-25; gpu-runtime `3bf36bb`) |
| 13 | Harness: query-embedding cache, request throttle/budget, GPU-7 run-record fields, validity check | `tools/retrieval-eval/` | Done (G4, 2026-09-25) |
| 9 | Decision memo | `docs/research/retrieval-evaluation-benchmark-results.md` (after B5) | Not started |
| 10 | 3 real structurally-rich PDFs added to corpus | `demo-data/documents/{mental-health-report-2010,outsourcing-agreement,sks8300-web-interface-manual}.pdf` | Done (2026-09-20); gold queries against them not yet written |

---

## 10. Open Questions

0. **Gold queries against the 3 new PDFs.** Done 2026-09-25 (T-INT-3 section in §6): 15 marker-annotated PDF queries in query set v2 plus the full arm matrix. Fixed vs semantic is within noise, and the three differing queries point at T05 (heading split from its table) and T10 (boilerplate crowding).
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
