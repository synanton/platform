PR #47, **“SNTP-9 Retrieval Evaluation Benchmark”**, including the changed files and the benchmark artifacts. It is a meaningful step forward: the project is moving from “architecture + implementation” toward **measurable retrieval-system engineering**. The PR is currently open, has one commit (`ce0dcdf`), and changes 22 files, including benchmark tooling, evaluation data, PDFs, documentation, and extraction diagnostics. 

## Executive assessment

The project is currently at roughly this stage:

> **Synanton has crossed the prototype infrastructure stage and now has an experimentally measurable knowledge/retrieval pipeline, but it has not yet demonstrated the full AI-native enterprise knowledge thesis end-to-end.**

The important transition is that you now have:

**documents → extraction → structural chunks → indexing → retrieval → benchmark → measurable quality**

rather than only:

**documents → RAG/search demo**

That distinction is significant.

### What PR #47 establishes

| Area                                     | Current state                                             | Assessment |
| ---------------------------------------- | --------------------------------------------------------- | ---------- |
| Document ingestion                       | Working                                                   | 🟢          |
| PDF structured extraction                | Working and demonstrable                                  | 🟢          |
| Text/Markdown extraction                 | Working, but structurally weak                            | 🟡          |
| Extraction observability                 | Major improvement                                         | 🟢          |
| Semantic/structural chunking             | Demonstrated on PDFs                                      | 🟢          |
| BM25 retrieval                           | Measured                                                  | 🟢          |
| Retrieval benchmark harness              | Established                                               | 🟢          |
| Gold-query dataset                       | Established, but small                                    | 🟡          |
| Dense retrieval                          | Not operational in Phase 1                                | 🔴          |
| Hybrid retrieval                         | Partially demonstrated, but no dense component            | 🟡          |
| Reranking                                | Not yet demonstrated                                      | 🔴          |
| Graph/rule-based retrieval               | Not yet demonstrated                                      | 🔴          |
| Retrieval evaluation at meaningful scale | Not yet                                                   | 🟡          |
| Enterprise security propagation          | Existing architectural direction, not evaluated here      | 🟡          |
| Temporal/version-aware retrieval         | Architectural work exists, but not part of this benchmark | 🟡          |
| Provenance / lineage evaluation          | Not yet                                                   | 🟡          |
| Rebuildability evaluation                | Not yet                                                   | 🟡          |
| Production-grade GPU inference plane     | Planned, not deployed                                     | 🔴          |

------

# 1. The biggest progress: Synanton now has a measurement loop

This is probably the most important result of the PR.

The new `tools/retrieval-eval` gives you a reproducible benchmark harness with:

- dataset configuration
- gold queries
- ingestion/query wrappers
- metrics
- CLI
- recorded benchmark results.

The PR explicitly identifies B0 as the harness + gold-chunk annotation phase and B1 as the first retrieval baselines. 

This changes the development methodology.

Previously the question could be:

> “Does Synanton retrieval seem to work?”

Now it can become:

> “Did this change improve Recall@10, MRR, nDCG, latency, or another defined metric against a fixed knowledge version?”

That is exactly the direction an infrastructure project should take.

------

# 2. The first benchmark result is useful, but don't overinterpret it

The T01 BM25 result is:

- 10 queries
- Recall@10: **0.90**
- Recall@100: **0.90**
- Precision@10: **0.155**
- nDCG@10: **0.756**
- MRR@10: **0.714**
- p50 latency: **3.52 s**
- p95 latency: **8.55 s** 

The important thing is **not that these numbers are “good” or “bad.”**

The important thing is that you have a baseline.

That lets subsequent work answer:

```
BM25
   ↓
semantic chunking
   ↓
dense retrieval
   ↓
hybrid
   ↓
reranking
   ↓
graph/rank fusion
```

with empirical comparisons instead of architectural arguments.

However, **10 queries is far too small to characterize retrieval quality**. At this point these numbers should be treated as *baseline instrumentation*, not evidence of production retrieval quality.

I would therefore avoid putting:

> “Synanton achieves 90% retrieval recall”

in any external positioning material.

Instead:

> “Synanton now has a reproducible retrieval evaluation framework with an initial 10-query baseline.”

That is both accurate and considerably more defensible.

------

# 3. The most interesting result is actually the structural-extraction experiment

The PR adds three structurally rich PDFs:

- mental-health report
- outsourcing agreement
- SKS8300 web-interface manual

with respectively **11 / 47 / 4 real headings**, plus tables, lists and images. 

This is strategically important because it demonstrates something central to Synanton:

> **Knowledge quality starts before retrieval.**

If extraction destroys document structure, retrieval cannot recover it later.

The PR exposes this very clearly with Markdown:

> headings exist in the source, but Tika extraction produces only `PARAGRAPH` elements.

Consequently `SemanticChunkStage` cannot produce genuinely structure-aware chunks for those documents. 

This is an important architectural finding rather than merely an implementation bug.

The emerging pipeline is becoming:

```
Raw source
   │
   ▼
Content extraction
   │
   ├── text
   ├── headings
   ├── tables
   ├── lists
   ├── images
   └── layout
   │
   ▼
Structural representation
   │
   ▼
Semantic chunks
   │
   ▼
Knowledge / retrieval projections
```

That is much closer to Synanton's architectural thesis than generic “embed the document” RAG.

------

# 4. The extraction bug was actually a valuable discovery

The PR reports a real `NoSuchMethodError` in `extraction-gateway` caused by Spring Boot's dependency-management BOM forcing `commons-lang3` to 3.14.0 while Tika required a newer API. More importantly, the failure was effectively silent because `ExtractSyncService` lacked logging. 

The fix includes:

- dependency override
- logging
- regression test
- Docker build validation
- Docker smoke test
- Gradle cache handling.

This is good engineering progress.

The especially important improvement is the new **real extraction call in CI**, rather than merely:

```
compile
↓
unit tests
↓
green
```

You now have something closer to:

```
build image
↓
start service
↓
call gRPC
↓
perform extraction
↓
verify result
```

That closes a class of failure that ordinary unit/compile testing misses.

For an extraction-heavy platform, that is an important maturity improvement.

------

# 5. The dense/hybrid retrieval blocker is now explicit

This is another major positive outcome.

The benchmark revealed that the Phase 1 stack cannot actually exercise dense retrieval because `synquest` always calls the vLLM embedding service, while that service exists only behind the Phase 2 Docker profile requiring GPUs. Consequently:

```
T02 dense       BLOCKED
T03 hybrid      BLOCKED
```

and benchmark requests report:

```
query_usage.embed_skipped=true
```

The PR explicitly identifies this and connects it to the planned GPU plane. 

This is much better than having a nominal “hybrid search” architecture that has never actually executed the dense path.

The current situation is therefore:

```
Phase 1

BM25 ────────────────► works


Phase 2

BM25 ───────┐
            ├──► Hybrid ──► reranker
Embeddings ─┘
```

That dependency needs to be resolved before claims around hybrid retrieval become meaningful.

------

# 6. The GPU-plane decision is architecturally coherent

The PR records a decision to make the homelab Kubernetes cluster a dedicated Synanton GPU plane.

The described cluster has:

- 1 control-plane node
- 3 GPU workers
- GTX 1650 4 GB
- RTX 4060 Ti 16 GB
- RTX 5060 Ti 16 GB
- Calico
- NVIDIA GPU Operator/device plugin
- `RuntimeClass nvidia`
- Longhorn
- local SSD storage
- local registry.

The corresponding GPU-runtime deployment plan contains 13 ticket-sized items, while the platform has a separate integration-ticket document. 

This is a sensible infrastructure direction, but there is an important distinction:

> **The GPU plane is currently a planned capability, not yet project capability.**

The PR explicitly says the nodes are currently shut down and that nothing in the deployment plans is executable yet. 

So I would consider GPU deployment the next major infrastructure milestone.

------

# 7. The benchmark dataset is moving in the right direction

The queries are not just generic QA.

The current set contains categories such as:

- factual
- table lookup
- procedural
- multi-hop
- section-local
- keyword
- negative/unanswerable.

For example, one query asks which suppliers account for most inbound component volume, while another asks for an agreement's minimum order quantity. There is also a deliberately unanswerable question asking for a CEO's personal phone number. 

That's good because it starts testing different retrieval failure modes.

The negative query is particularly important for Synanton because enterprise knowledge infrastructure cannot be evaluated purely by:

> “Did we retrieve something relevant?”

It also needs:

> “Did we retrieve something when we should have retrieved nothing?”

That becomes even more important for your legal/compliance positioning.

------

# 8. But the benchmark needs to evolve substantially

This is the biggest area I would focus on after merging PR #47.

The current benchmark is essentially:

```
10 questions
↓
small document corpus
↓
retrieval metrics
```

You ultimately need something more like:

```
                    Retrieval Evaluation
                           │
        ┌──────────────────┼──────────────────┐
        │                  │                  │
    Retrieval           Security           Temporal
        │                  │                  │
    Recall              ACL tests          as-of date
    Precision           leakage            validity
    MRR                 privilege          supersession
    nDCG                 isolation         publication
        │                  │                  │
        └──────────────────┼──────────────────┘
                           │
                     Provenance
                           │
                     lineage accuracy
                     citation coverage
                     rebuild correctness
```

That is where Synanton starts becoming demonstrably different from ordinary RAG.

------

# 9. The next benchmark dimension should be enterprise knowledge semantics

Your previous architecture work around versioning, provenance, security propagation and materialized projections now becomes directly relevant.

I would make the next benchmark suite test at least these dimensions.

### A. Temporal retrieval

Questions like:

```
What was the applicable clause on 2024-01-01?
```

versus:

```
What is the current clause?
```

versus:

```
What version had been published but not yet observed?
```

This directly exercises the `published_at`, `observed_at`, `valid_from`, `valid_to` model you've been developing.

### B. Security propagation

Example:

```
User A can see source document X.
User B cannot.

Derived chunk X.17
    → must follow X's authorization boundary.
```

Then test:

```
search
retrieval
reranking
citations
derived entities
summaries
```

for leakage.

### C. Provenance

Every retrieved answer should be able to answer:

```
Where did this fact come from?
Which artifact?
Which source version?
Which extraction?
Which derived object?
Which projection?
```

### D. Rebuildability

Change:

```
source version
```

and verify:

```
derived knowledge
↓
projection
↓
search results
```

can be rebuilt deterministically or at least reproducibly.

That would be a much more powerful demonstration of the Synanton thesis than another generic retrieval benchmark.

------

# 10. There is an important architectural opportunity here

PR #47 is unintentionally demonstrating that **retrieval evaluation should become a first-class platform capability**, not remain a collection of research scripts.

I would eventually evolve:

```
tools/retrieval-eval/
```

into something conceptually like:

```
evaluation/
├── datasets/
├── scenarios/
├── metrics/
├── runners/
├── reports/
└── reproducibility/
```

with an explicit benchmark identity:

```
dataset_version: ...
knowledge_version: ...
extraction_version: ...
chunking_version: ...
index_version: ...
embedding_model: ...
retrieval_strategy: ...
reranker: ...
security_context: ...
temporal_context: ...
```

The PR already starts doing this with:

```
dataset_version
knowledge_version
search_config
embedding_model
retrieval_strategy
reranker
recorded_at
```

That is exactly the right direction. 

I would expand that metadata rather than replace it.

------

# 11. Current architecture maturity

Based on PR #47 together with the Synanton work we've reviewed previously, I'd describe the platform as follows:

```
                    SYNANTON MATURITY

                 ┌─────────────────────┐
                 │ Enterprise AI       │
                 │ applications        │
                 │                     │
                 │       NOT YET       │
                 └──────────┬──────────┘
                            │
                 ┌──────────▼──────────┐
                 │ Knowledge semantics │
                 │ temporal/security/  │
                 │ provenance/rebuild  │
                 │                     │
                 │ architecture exists │
                 │ implementation      │
                 │ partially proven    │
                 └──────────┬──────────┘
                            │
                 ┌──────────▼──────────┐
                 │ Retrieval platform  │
                 │                     │
                 │ BM25 ✓              │
                 │ structural chunks ✓ │
                 │ benchmark ✓         │
                 │ dense ~             │
                 │ hybrid ~            │
                 │ reranking —         │
                 └──────────┬──────────┘
                            │
                 ┌──────────▼──────────┐
                 │ Extraction          │
                 │                     │
                 │ PDF ✓               │
                 │ text ✓              │
                 │ Markdown ~          │
                 │ multimodal ~        │
                 └──────────┬──────────┘
                            │
                 ┌──────────▼──────────┐
                 │ Raw enterprise data │
                 │                     │
                 │        ✓            │
                 └─────────────────────┘
```

So the project is no longer merely an architectural proposal.

There is now a **working experimental knowledge infrastructure stack with empirical evaluation**.

------

# 12. What I would do next

I would prioritize the next work in this order.

### P0 — Make dense retrieval executable

Deploy GPU-runtime → embedding service → Synanton integration.

Then actually run:

```
T02 dense
T03 hybrid
```

rather than leaving them blocked.

This is the immediate missing component in the retrieval experiment. 

### P1 — Expand B2 retrieval experiments

Run:

```
T01 BM25 / flat
T02 dense / flat
T03 hybrid / flat

T04 BM25 / structural
T05 dense / structural
T06 hybrid / structural

T07 + reranker
T08 + hierarchical retrieval
T09 + graph/rank fusion
```

The critical question becomes:

> **Which knowledge representation and retrieval architecture produces measurable improvement?**

### P1 — Fix Markdown structural extraction

This is more important than it might initially appear.

A knowledge platform shouldn't have:

```
PDF → structure
Markdown → flat text
TXT → flat text
```

if structural knowledge is a core abstraction.

Ideally extraction should normalize all source types into a common structural representation:

```
Document
 ├── Section
 │    ├── Paragraph
 │    ├── Table
 │    ├── List
 │    └── Image
 └── Section
```

### P1 — Expand the benchmark corpus

Ten queries are sufficient for proving the harness works, not for evaluating retrieval architecture.

I'd target roughly:

```
100–300 queries
10–30 documents
multiple document types
multiple question categories
```

before making architectural conclusions from metrics.

### P2 — Add security evaluation

This should become one of the first **distinctive Synanton benchmarks**.

Not:

> “Does retrieval work?”

but:

> “Does retrieval remain correct when knowledge has enterprise authorization boundaries?”

### P2 — Add temporal evaluation

Then:

```
current
as-of-date
published
observed
valid
superseded
overlapping
```

become benchmark dimensions.

### P2 — Provenance/rebuild benchmark

Finally:

```
source
 → extraction
 → knowledge
 → projection
 → retrieval
```

should be mutated and rebuilt to demonstrate that the platform can maintain lineage and recover derived knowledge correctly.

------

# Overall project progress

My read from PR #47 is:

**The most important progress is not the new PDFs or even the BM25 score. It is that Synanton has started establishing an empirical engineering discipline around the knowledge pipeline.**

The project now has evidence for:

1. **real document extraction**
2. **real structural representation**
3. **real retrieval**
4. **real retrieval measurements**
5. **reproducible benchmark metadata**
6. **runtime failure detection**
7. **a concrete GPU execution path**
8. **a growing corpus designed around retrieval failure modes**

The major gap is that the benchmark currently measures mostly **ordinary retrieval quality**, while Synanton's architectural differentiation is supposed to be:

> **enterprise knowledge with provenance, security propagation, temporal semantics, derived projections, and rebuildability.**

The next stage should therefore shift from:

**“Can Synanton retrieve the right chunk?”**

to:

**“Can Synanton retrieve the right \*authorized, temporally correct, provenance-preserving derived knowledge\* and demonstrate that quantitatively?”**

That would turn the current retrieval benchmark from a useful RAG experiment into a benchmark that directly validates the **Synanton platform thesis**.