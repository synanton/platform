---
title: "Flat vs Semantic Chunks — Search Quality Research Plan"
status: "draft"
last_reviewed: "2026-08-28"
---

# Flat vs Semantic Chunks — Search Quality Research Plan

> **Document type:** Research plan
> **Goal:** Measure whether structure-aware (semantic) chunking improves RAG retrieval and answer quality versus flat fixed-window chunking, and quantify the cost trade-off.
> **Related:** [synanton-design-1.22.md](../architecture/synanton-design-1.22.md) (Part X chunking), [semantic-chunking implementation plan](../implementation/semantic-chunking/INDEX.md), [standalone extract-index PoC](../implementation/demo/standalone-syntology-demo.md)

---

## 1. Research Question

Does **semantic chunking** (heading/table/list boundaries, `section_path`, atomic tables) produce better **retrieval + RAG answer quality** than **flat-text chunking** (fixed token windows over `flattenedText`), and at what **ingest + query cost**?

### Hypotheses

| ID | Hypothesis | Falsifiable if |
|----|------------|----------------|
| H1 | Semantic chunks improve **context precision** for section-scoped questions (e.g. “What is the Q1 revenue in the supply chain report?”) | Flat-text NDCG@k ≥ semantic within confidence interval |
| H2 | Semantic chunks improve **citation accuracy** (correct `section_path` / page in cited chunk) | Citation match rate flat ≥ semantic |
| H3 | Semantic chunks improve **faithfulness** of synthesized answers (fewer conflation errors across sections) | RAGAS faithfulness flat ≥ semantic |
| H4 | Semantic chunking **increases ingest cost** (more chunks, longer embed text with section prefixes) but may **reduce synthesis cost** (fewer irrelevant tokens in context) | Total $/query semantic > flat with no quality gain |

---

## 2. Experimental Arms

Two ingestion modes on the **same source documents** and **same extraction output** (structured `elements` from v1.21):

| Arm | Code | Pipeline | Chunk boundary | Indexed text |
|-----|------|----------|----------------|--------------|
| **A — Flat** | `flat-text` | `ExtractionStage` → **flat fallback** (`SemanticChunkStage` token split *or* legacy `ChunkStage`) | Fixed window (~512 tokens, 10% overlap) | Raw `flattenedText` windows |
| **B — Semantic** | `structure-aware` | `ExtractionStage` → `SemanticChunkStage` → `SemanticChunker` | Headings, lists, tables atomic; paragraph split fallback | Content + optional `section_path` prefix |

**Controlled constants (both arms):**

- Same tenant, embedding model, BM25 + HNSW settings, reranker (on/off documented per run)
- Same enrichment pass (disabled initially; optional Phase 2 with enrichment enabled)
- Same query set and gateway synthesis model + `context_budget`
- Same corpus version (content hash pinned)

**Configuration sketch:**

```yaml
# Arm A
synflux:
  ingest:
    chunk:
      strategy: flat-text          # planned property; until wired, use SemanticChunkStage flat fallback or ChunkStage
      target-tokens: 512
      overlap-tokens: 51

# Arm B
synflux:
  ingest:
    chunk:
      strategy: structure-aware
      target-tokens: 512
      include-section-path: true
      keep-table-atomic: true
```

Use **separate tenant IDs** (`demo-flat`, `demo-semantic`) or re-ingest with manifest wipe between arms to avoid index contamination.

---

## 3. Corpus

### 3.1 Document set

| Tier | Source | Purpose |
|------|--------|---------|
| **Core** | `demo-data/documents/` (markdown + PDF where extraction succeeds) | Regression baseline; mixed length |
| **Structured** | `structured-supply-chain.md`, `quarterly-report-q1.md`, `logistics-network.md` | Headings, tables, lists — semantic advantage expected |
| **Adversarial** | 2–3 synthetic docs: nested H2/H3, multi-page tables, duplicate headings | Boundary stress tests |

Target: **15–25 documents**, **200–600 chunks per arm** (record actuals).

### 3.2 Query set (gold labels)

Build **40–60 questions** with human-authored gold answers and **relevant chunk IDs** (or `content_ref_id#ordinal`):

| Category | Count | Example |
|----------|-------|---------|
| Section-local factual | 15 | “What regions does the supply chain overview cover?” |
| Table lookup | 10 | “What was gross income in 2024?” (Jordan fixture / report tables) |
| Cross-section synthesis | 10 | “Compare risk factors and mitigation in the compliance report” |
| Negative / unanswerable | 5 | “What is the CEO’s personal phone number?” (not in corpus) |
| Keyword-heavy (BM25-friendly) | 5 | Exact product code or proper noun |

Store as `demo-data/eval/flat-vs-semantic/queries.jsonl`:

```json
{
  "query_id": "q001",
  "question": "…",
  "gold_answer": "…",
  "gold_chunk_ids": ["<ref>#0", "<ref>#3"],
  "gold_section_paths": ["Supply Chain", "Europe"],
  "category": "section-local"
}
```

---

## 4. Metrics

### 4.1 Retrieval quality (no LLM)

| Metric | Definition | Tool |
|--------|------------|------|
| **Recall@k** | \|gold ∩ retrieved@k\| / \|gold\| | Custom harness |
| **NDCG@k** | Graded relevance by chunk rank | `pytrec_eval` or Java equivalent |
| **MRR** | Mean reciprocal rank of first gold chunk | Custom |
| **Citation field accuracy** | Retrieved hit has correct `section_path` / `page_start` when gold specifies section | Platform hit metadata |

Run at **k ∈ {5, 10, 20}** with and without reranker.

### 4.2 RAG quality (retrieval + synthesis)

End-to-end via gateway `POST /query` (or synapt public API):

| Metric | Definition | Tool |
|--------|------------|------|
| **Faithfulness** | Answer claims supported by retrieved context | [RAGAS](https://docs.ragas.io/) |
| **Answer relevance** | Answer addresses the question | RAGAS |
| **Context precision** | Fraction of retrieved chunks needed for gold answer | RAGAS |
| **Context recall** | Gold facts present in retrieved context | RAGAS |
| **Citation accuracy** | Cited chunks match gold `chunk_id` or section | Manual + automated string match on `section_path` |

Optional: **LLM-as-judge** (GPT-4 class) on 20% sample for inter-rater calibration.

### 4.3 Cost and efficiency

Pull from platform-native telemetry first; reconcile with cloud billing where applicable.

**Ingest (per document, per arm):**

| Metric | Source |
|--------|--------|
| Chunk count | Cassandra / manifest stats |
| Total embed characters / tokens | `ingest_usage` → `StageUsage` for `embed` stage |
| Enrichment LLM tokens | `ingest_usage.modelInputTokens` / `modelOutputTokens` (if enrichment on) |
| Wall time / CPU | `ingest_usage.wallMs`, `cpuNs` per stage |
| Index size (bytes, doc count) | Lucene directory size per tenant |

**Query (per question, per arm):**

| Metric | Source |
|--------|--------|
| Query embedding tokens | synquest / gateway trace |
| Retrieved chunks × avg tokens | Hit `token_count` sum |
| Synthesis input / output tokens | Gateway `execution_trace` or LLM client logs |
| End-to-end latency p50/p95 | Load test or sequential run timestamps |
| Cache hit rate | `X-Synanton-Cache-Status` header |

**Normalized cost:**

```
cost_per_answer = embed_ingest_amortized + query_embed + retrieval_compute + synthesis_llm
```

Express in **USD** using model price sheet (embed + chat) and **$/1k queries** for comparison.

---

## 5. Methodology

### Phase R0 — Harness (1 week)

1. Pin corpus checksum; publish query gold file.
2. Script dual ingest: `./scripts/run-chunking-eval-ingest.sh --arm flat|semantic`.
3. Script eval runner: `./scripts/run-chunking-eval.sh` → retrieval JSON + gateway answers JSONL.
4. Python eval notebook or CLI using RAGAS + retrieval metrics.

### Phase R1 — Retrieval-only benchmark (1 week)

1. Ingest both arms.
2. Run query set through synquest search API (no synthesis).
3. Compute Recall@k, NDCG@k, MRR; stratify by query category.
4. Record chunk-count and index-size delta.

### Phase R2 — Full RAG benchmark (1–2 weeks)

1. Same queries through gateway synthesis (fixed model + temperature 0).
2. Run RAGAS batch; export per-query scores.
3. Aggregate by category; bootstrap 95% CI for mean delta (semantic − flat).

### Phase R3 — Cost analysis (parallel with R2)

1. Aggregate `ingest_usage` from manifests.
2. Sum query-side tokens from traces.
3. Build cost table: quality delta vs $/1k queries.

### Phase R4 — Report (3 days)

Decision memo: recommend default `synflux.chunking.strategy` per document type (structured PDF/MD → semantic; plain text logs → flat optional).

---

## 6. Acceptance / Decision Criteria

| Outcome | Criteria |
|---------|----------|
| **Adopt semantic as default for structured docs** | Semantic wins NDCG@10 ≥ **+5% relative** on structured subset *and* faithfulness ≥ **+0.05** absolute, with ≤ **25%** ingest cost increase |
| **Keep flat default globally** | No statistically significant quality gain *or* ingest cost > **2×** with < **3%** NDCG gain |
| **Hybrid policy** | Semantic wins on tables/sections only; ship `strategy: auto` keyed on extraction element mix |

Statistical test: paired bootstrap on per-query NDCG differences (n ≥ 40 queries); α = 0.05.

---

## 7. Confounds and Mitigations

| Confound | Mitigation |
|----------|------------|
| Different chunk counts → different BM25 IDF | Same corpus docs; report chunks/doc; optional fixed-chunk-count subsample sensitivity |
| Section path prefix inflates embed length | Arm B with `include-section-path: false` ablation run |
| Extraction failure → flat fallback only | Tag documents with `extraction_mode`; exclude from primary analysis |
| Reranker masks chunking differences | Report with reranker **off** (primary) and **on** (secondary) |
| Synthesis cache | Disable cache or use unique query suffix per run |
| LLM judge variance | Fixed judge model; 3-run median for RAGAS |

---

## 8. Deliverables

| # | Deliverable | Location |
|---|-------------|----------|
| 1 | Gold query set | `demo-data/eval/flat-vs-semantic/queries.jsonl` |
| 2 | Ingest scripts | `scripts/run-chunking-eval-ingest.sh` |
| 3 | Eval runner | `scripts/run-chunking-eval.sh` |
| 4 | RAGAS eval module | `tools/chunking-eval/` (Python) |
| 5 | Results CSV / charts | `demo-data/eval/flat-vs-semantic/results/` |
| 6 | Decision memo | `docs/demo/flat-vs-semantic-chunks-results.md` (after run) |

---

## 9. Minimal Reproduction (smoke test)

Before full benchmark, validate the harness on **one structured doc**:

```bash
# 1. Start stack
./scripts/run-extract-index-poc.sh

# 2. Ingest structured-supply-chain.md both ways
./scripts/run-chunking-eval-ingest.sh --arm flat --path demo-data/documents/structured-supply-chain.md
./scripts/run-chunking-eval-ingest.sh --arm semantic --path demo-data/documents/structured-supply-chain.md

# 3. Five manual queries — expect semantic arm to rank "Europe" section chunk above unrelated windows
curl -s 'http://localhost:8083/search?tenant=demo-semantic&q=Europe+warehouse+capacity' | jq '.hits[:3] | .[].section_path'
```

---

## 10. Timeline

| Week | Activity |
|------|----------|
| 1 | R0 harness + gold queries v1 |
| 2 | R1 retrieval benchmark |
| 3–4 | R2 RAG + R3 cost |
| 5 | R4 report + default strategy recommendation |

---

## 11. References

1. [synanton-design-1.22.md](../architecture/synanton-design-1.22.md) — Part X semantic chunking
2. [semantic-chunking/INDEX.md](../implementation/semantic-chunking/INDEX.md) — implementation phases SC-1–SC-4
3. RAGAS metrics: faithfulness, answer_relevancy, context_precision, context_recall
4. Platform telemetry: `ResourceUsage` / `ingest_usage` on manifests, gateway `execution_trace`

---

## 12. Open Questions

1. Should enrichment (Pass-1/Pass-2 LLM) be **on** or **off** for the primary comparison? **Proposal:** off first; Phase R2b with enrichment if ingest budget allows.
2. Include **HNSW** in primary metrics or BM25-only? **Proposal:** both — BM25 primary (cheaper), HNSW hybrid secondary.
3. PDF vs markdown weighting? **Proposal:** ≥30% PDF pages in corpus to reflect production mix.
