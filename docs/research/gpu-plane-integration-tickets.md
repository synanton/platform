---
title: "GPU Plane Integration — Ticket Backlog"
status: "backlog — T-INT-1 decided; T-INT-2 planned against GPU-7 (free models); bge-base rows blocked on gpu-runtime GPU-5 (T-K8S-6a)"
last_reviewed: "2026-09-25"
---

# GPU Plane Integration — Ticket Backlog

> **Document type:** Ticket backlog
> **Related:** [retrieval-evaluation-benchmark-plan.md](./retrieval-evaluation-benchmark-plan.md) (owns the narrative findings this backlog acts on), [`gpu-runtime/doc/k8s-reference-deployment-plan.md`](../../../gpu-runtime/doc/k8s-reference-deployment-plan.md) (the GPU-5 backlog that unblocks everything here)

## Why this exists

This session found that the retrieval-evaluation benchmark's T02 (dense-only) and T03 (hybrid) test-matrix rows are blocked, not run: `synquest`'s embedding client (`QueryEmbedder`) has no fallback and always calls out to `EMBED_BASE_URL`, which only resolves to a running service behind `docker compose --profile phase2` (2×8GB GPUs, not running in the Phase 1 stack this benchmark uses). Every query in that environment reports `query_usage.embed_skipped=true`.

The sibling `gpu-runtime` repo is the real, intended long-term home for this — but its Kubernetes deployment (GPU-5) is a confirmed blank slate. The homelab k8s cluster that could host it is being recreated as a cluster dedicated to Synanton's GPU plane (see the linked deployment plan). None of the tickets below are executable until that lands. They're recorded now so the dependency is explicit and the work doesn't have to be rediscovered later.

---

## Tickets

### T-INT-1 — Decide the integration path: real k8s endpoint vs. Docker Phase 2 profile

Once the new cluster and `gpu-runtime`'s GPU-5 deployment exist, decide how `platform` actually reaches it:

- **Option A:** `platform`'s `gateway`/`synflux` reach the cluster's `gpu-gateway` Service directly over the network. Requires the cluster's Service DNS (or a routable ingress) to be reachable from wherever `platform` itself runs — likely **not** the same machine this benchmark runs on, so this needs its own connectivity plan (VPN, routable network segment, or port-forward for dev use only).
- **Option B:** Keep the Docker Compose `--profile phase2` vLLM services for local dev entirely, and treat the k8s deployment as a separate, independently-validated "production-shaped" target that `platform` never directly calls from this benchmark's environment.

**Decide before touching `EMBED_BASE_URL`/`LLM_BASE_URL`** — don't assume the same env var contract applies to both cases; the k8s deployment fronts `gpu-gateway` (a different service, `synanton.gpu.v1`) rather than vLLM directly, so `platform`'s existing `LlmClient`/`QueryEmbedder` HTTP-to-vLLM assumption may not even apply to Option A without its own adapter work.

**Decided (2026-09-25): Option A, via a shared gRPC `LlmClient`.** The GPU plane is gRPC `synanton.gpu.v1` over mTLS only, with no REST façade (Deployment Plan v3.1.0), so the HTTP `EMBED_BASE_URL` contract doesn't apply. The EMBED path of `gateway.gpu.GpuEmbeddingAdapter` gets extracted into a shared module and selected in `synquest`/`synflux` by an opt-in `gpu-plane` profile, in fail-closed mode (no silent CPU fallback). The same client serves GPU-7 (external, available now) and GPU-5 (local, once T-K8S-6a lands); only the logical model id differs. Rejected: a benchmark-only HTTP→gRPC shim (a de-facto REST façade) and calling OpenRouter directly from the platform (bypasses the GPU plane's guards and puts the key in the platform). Full plan: [retrieval-evaluation-benchmark-plan.md §6 Phase B1-G](./retrieval-evaluation-benchmark-plan.md).

### T-INT-2 — Re-run T02 (dense-only) and T03 (hybrid)

Per `docs/research/retrieval-evaluation-benchmark-plan.md` §6 Phase B1, T01 (BM25, `rb-fixed`) and T04 (hybrid-labeled, `rb-semantic`) are done with real recorded results (`demo-data/eval/retrieval-benchmark/results/T01.yaml`, `T04.yaml`). T02/T03 are blocked, not run. Once a real embedding endpoint is reachable (via T-INT-1), run them for real using the same harness (`retrieval-eval evaluate ... --top-k-lexical 1` to isolate dense, or full hybrid for T03) and add their run records alongside the existing two.

**Update (2026-09-25):** split into two parts.
- **T-INT-2a (GPU-7, in progress: G0–G3 done 2026-09-25; G4 harness next):** T02-G/T03-G/T04-G on OpenRouter free embedding models through GPU-7 — steps G0–G7 in the benchmark plan's §6 Phase B1-G. These are separate rows, not the bge-base T02/T03.
- **T-INT-2b (GPU-5, blocked on T-K8S-6a):** the original bge-base T02/T03, using the same client with the logical model set to `synanton-bge-base-embedding`.

The old `results/T03.yaml` (2026-09-18, all metrics 0.0, dataset v1) is superseded and invalid.

### T-INT-3 — Write gold queries against the 3 newly-added structurally-rich PDFs

Independent of the GPU plane — can be done sooner, doesn't need to wait on T-INT-1/T-INT-2.

The corpus now includes `mental-health-report-2010.pdf`, `outsourcing-agreement.pdf`, and `sks8300-web-interface-manual.pdf` — all confirmed (via direct Cassandra inspection) to produce real `SECTION`/`TABLE`/`LIST` chunks under `rb-semantic` vs. uniform `FALLBACK` under `rb-fixed`. None of the 10 existing gold queries (`demo-data/eval/retrieval-benchmark/queries.jsonl`) target them, so T01-vs-T04's real structural divergence isn't reflected in any Recall/NDCG number yet. This is the research plan's own Open Question 0 — writing these queries is the single most direct way to make the T01-vs-T04 comparison actually meaningful.

### T-INT-4 — Add a few `OHR-Bench` PDFs as permanent `content_extractor` test fixtures

Independent of the GPU plane. This session validated `content_extractor`'s OpenDataLoader PDF adapter against several `OHR-Bench` domains (academic, law, manual, textbook, news, finance) plus one user-provided technical manual, all real spec-valid PDFs — every one produced genuine heading/table/list detection. That validation was one-off (a manual probe via `tools/extraction-probe`), not committed anywhere as a repeatable test. Consider selecting 2-3 of the already-downloaded `testing/OHR-Bench/pdfs/` files and committing them as permanent fixtures in `content_extractor/java/adapter-document-pdf/src/test/resources/fixtures/`, giving that adapter's test suite real-world coverage beyond its current hand-crafted files.

### T-INT-5 — Decide whether to fix `content_extractor`'s markdown-heading gap

Independent of the GPU plane. Confirmed this session via direct gRPC probe: `content_extractor`'s `TextModalityAdapter` (Tika `AutoDetectParser`-based, used for `.txt`/`.md`) never parses `#`/`##`/`###` syntax into `HEADING` elements — every element comes back `PARAGRAPH`. This is a real, documented feature gap (the adapter's own `feature_states` response is honest about it: `layout: FEATURE_NOT_APPLICABLE` for `text/markdown`), not a bug. It's the reason the *original* 13-document demo corpus can never exercise real semantic chunking regardless of `extraction-gateway`'s health — only the 3 newly-added PDFs can, since PDF extraction goes through a different adapter (OpenDataLoader) without this limitation.

This backlog does not decide whether to fix it — flagged here for whoever owns `content_extractor`'s roadmap next to accept or explicitly reject.

### T-INT-6 — B2 continuation (already planned, unaffected by any of the above)

Per `docs/research/retrieval-evaluation-benchmark-plan.md` §6 Phase B2: hierarchical chunking (T05), `RerankerPort` SPI + one adapter (T10/T11), graph rank-fusion into `synquest`/`gateway` (T08/T09). All independent of the GPU plane work above and can proceed in parallel with it.

---

## Dependency summary

```text
T-INT-1 (decided: shared gRPC LlmClient) ──┬──> T-INT-2a (T02-G/T03-G/T04-G on GPU-7 free models) — unblocked
                                           └──> T-INT-2b (bge-base T02/T03 on GPU-5) — blocked on gpu-runtime T-K8S-6a

T-INT-3 (gold queries for new PDFs)         — independent, do any time
T-INT-4 (OHR-Bench fixtures for content_extractor) — independent, do any time
T-INT-5 (markdown-heading gap decision)     — independent, needs a roadmap owner's call
T-INT-6 (B2 continuation)                   — independent, already planned
```
