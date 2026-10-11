# Benchmark Tracker — Four Targets

Target-named scheme (replaces owner/mixed naming). Alias map at the bottom
covers the transition; history is not rewritten.

| Target | Adapter | Emitter ticket | Branch | Q3 output | Status |
|---|---|---|---|---|---|
| Baseline | Lucene (in-JVM) | BENCH-BASE (was 028b) | merged (#65, #68) | runs/baseline-v1.json | ✅ Closed 2026-09-28 (96/120, 24 structural; closeout on main) |
| Cassandra | 024A (ingestion-cache/Lucene) | CASS-EMIT-024a (was 028c) | DESIGN-emit-cassandra | runs/cassandra-v1.json | ✅ B.2 green 2026-09-28 (sha 85921bb2, 96/120 non-empty, 24 structural) |
| YDB | 024B | YDB-EMIT-024b (was 028d) | DESIGN-emit-ydb (was DESIGN-YDB-028d) | runs/ydb-v1.json | Paused — 041 root cause deferred to Phase 4 (see 041-experiment-record.md) |
| PostgreSQL | PostgresSynquestEngine | PG-EMIT-pg (was 028e) | DESIGN-emit-pg (was DESIGN-PG-028e) | runs/pg-v1.json | Scaffold only; real run post-R4 |

Pre-R3 gates (all must close before R3; parallel with 041):

| Gate | Owner | Status |
|---|---|---|
| 006 re-freeze (thresholds from baseline-v1.json) | andreminin | Unblocked now — runs parallel with 041, not serial after C.2. Percentile rule: legs flagged `structural_empty` are EXCLUDED from threshold math (no signal, not zero signal) |
| 024B/PG fusion-semantics check (pre-ranking invariant) | YDB workstream (024B) · PG workstream (PG) | **024B CLOSED**: iterative over-fetch on all 3 legs (page-until-full, 20k cap, fail-loudly `SCAN_CAP_HIT` tripwire); regression + cap tests green. PG still deferred to leg activation |

Cross-cutting:

| Component | Ticket | Status |
|---|---|---|
| Emitter module (shared) | BENCH-EMIT-* (was A.1–A.6) | Phase A complete, green |
| Comparator | BENCH-CMP (was PG-POC-017) | Green (12/12 + 4/4 contract) |
| Corpus generator | 028a.1–028a.11 | Complete on main |
| Phase 4 backlog | — | NONE experiment, index isolation, BulkUpsert-RPC, clock skew, resource broker, 028-4.X KNN-vs-text split (owner TBD — pre-R3 backlog, non-blocking, tracked here so it isn't lost) |
| Harness amendment 2026-09-30 (PN-7) | — | RunLeg-based runs NEVER executed the truncate step (`engine.truncate()` fetched-and-dropped the lambda). Observed clean state came from fresh prefixes/temp dirs, not truncate — earlier "truncate-on-start verified" claims read as "redundant mechanism, unverified". Fixed with `.run()` (all legs); structural prevention (void return) is a post-R3 refactor. YDB close never ran either (transport leak, harmless one-shot). |

## Forward plan (Benchmark Runner + Vector Engine Selection)

- Execution plan: [benchmark-runner-vector/INDEX.md](./benchmark-runner-vector/INDEX.md) — 48 canonical tasks
  (BR 12 + VEC 24 + DOC 12). Track files: [BR](./benchmark-runner-vector/track-a-benchmark-runner.md) ·
  [VEC](./benchmark-runner-vector/track-b-vector-engine.md) · [DOC](./benchmark-runner-vector/track-doc-documentation.md).
- This tracker stays the execution record for the four-target benchmark; the new plan's B5/VEC-B5.x
  runs report back here. No duplication: task definitions live in the plan, run results live here.

### Week-1 gate: closed 2026-10-01

- Track A / Track B / Docs owner: Andrei Minin (all three). Recorded in
  [benchmark-runner-vector/INDEX.md](./benchmark-runner-vector/INDEX.md) §1.
- Legal + architecture reviewers TBD (needed at B0/B6 review points, not blocking).
- Week-2 gate (Manifest + Result schema + metric taxonomy freeze) schedulable.

### VEC-B0.1 — License matrix: landed (legal review open, non-blocking)

- Scope: primary engines + pg-extensions + adjacent incl. Tantivy/Quickwit rows.
- Matrix: [vector-license-matrix.md](./benchmark-runner-vector/vector-license-matrix.md) landed — 034b08e (PR #93, 2026-10-01).
- LICENSE reads complete: pgvectorscale PostgreSQL/High (TSL-lineage hypothesis refuted),
  pgvecto.rs Apache-2.0/High, Quickwit Apache-2.0/High; Verified-restricted retired uninstantiated.
- Final matrix state incl. Weaviate mixed-license row (BSD-3-Clause OSS core + proprietary enterprise wl/; PR #104). All 12 universe-touching entries read; no managed-service restriction found.
- Elasticsearch row is the only license-gated decision cell (SSPL/ELv2/AGPL) — legal sign-off required.
- Legal review checklist open; dependency-level scan deferred to legal review.

### VEC-B0.2 — Deployment-mode + managed-availability matrix: landed

- Scope: runtime-integration model + engine × context deployment matrix + engine × cloud managed-availability matrix + embedded-edge cases.
- Matrix: [vector-deployment-modes.md](./benchmark-runner-vector/vector-deployment-modes.md) landed — 02fd620 (PR #96, 2026-10-07).
- Declared 11-engine universe; divergence from VEC-B0.1's 21-engine license set recorded in §5a (combined rows retained, Quickwit split, PG-extension + adjacent exclusions).
- Phase-5 measurement candidates recorded in §5b (Vespa Azure, Quickwit managed, Cassandra+Lucene managed, Tantivy managed).

### VEC-B0.3 — Hardware + scalability + operational complexity: landed

- Scope: CPU/memory/storage profiles per engine + scalability + operations.
- Profiles: [vector-hardware-profiles.md](./benchmark-runner-vector/vector-hardware-profiles.md) landed — 425c374 (PR #98, 2026-10-07).
- 11-engine universe (same as B0.2); no measured cells — all sourced or n/a-with-reason; assumed cells routed to Phase-5 in §8.
- Cross-engine findings: overhead type non-uniform (§6a), tier comparability non-universal (§6b), source vintage spans 4 years (§6c).
- Open: storage footprint gap (10/11 engines blank) flagged as batch Phase-5 candidate.

### VEC-B0.4 — Per-context profiles: landed

- Scope: five contexts (cloud-managed, cloud-self-hosted, on-prem, docker, embedded) × disqualified / viable / default / trade-offs.
- Profiles: [vector-context-profiles.md](./benchmark-runner-vector/vector-context-profiles.md) landed — e3028d2 (PR #101, 2026-10-08).
- Defaults: PostgreSQL + pgvector (cloud-managed); Qdrant (cloud-self-hosted, on-prem, docker); Lucene standalone (embedded).
- License top-up reads complete (all 12 universe-touching entries: no managed-service restriction found; Weaviate wl/ enterprise split noted, no use restriction on OSS core).

### VEC-B0.5 — Cross-context recommendations: landed

- Scope: context → recommended / alternative / notes summary table.
- Evidence: §3 of [vector-context-profiles.md](./benchmark-runner-vector/vector-context-profiles.md) — section landed with e3028d2 (PR #101, 2026-10-08).
- Satisfied as written; acceptance ("every context has a recommendation") met — five contexts, one default each.
- Known deviation: card describes `context → recommended / alternative / notes`; §3 carries `Context | Default | Viable | Disqualified`. Accepted at review; no edit.

### Gate B0: closed 2026-10-08

- Owner: Andrei Minin (Track B). B0.1–B0.5 landed and mirrored above — primary artifact PRs #93/#96/#98/#101 (+fixes #99/#102, matrix touch-up #104); tracker PRs #92/#94/#97/#100/#103/#105/#106.
- Recorded deviation: B0.5 satisfied via §3 (shape differs from card; accepted at review, no edit).
- Carried open: legal sign-off + dependency scan (B0.1); storage-footprint batch measurement. If legal returns a restriction on a universe engine, the gate reopens for B0.4 review.

### VEC-B1.1 — VectorRetriever port + DTOs: landed

- Scope: VectorRetriever port, VectorSearchRequest (pre-embedded vectors only), VectorSearchResult (no highlights), VectorProjection, Projection sealed interface (incl. ChunkProjection gains `implements Projection`).
- Evidence: PR #110 (5be66de) — interface + DTO files + JSON round-trip tests green, boundary guard green.
- Decisions: Reading 1 (caller embeds, retriever never sees text); reuse-by-shape (records final, no subtyping); capabilities reuse with lexical-only flags false by contract.
- Carried gap: capabilities() reuses SearchCapabilities — vector-specific flags (index type, distance metric per B0.3 §2a) not representable; no B1 task owns this. Revisit if B3 per-adapter work needs it.
- B1.4 note: facade synthesizes an empty highlights map when translating back to SearchResult.

### VEC-B1.2 — Writer port sealed upsert: landed

- Scope: upsert(List<ChunkProjection>) → upsert(List<? extends Projection>) + narrowing contract; ordering-key/generation + rebuild-semantics javadoc; VectorProjection forward-ref fix.
- Evidence: PR #111 (d5b5a78) — api + inmemory suites green; Postgres contract suite 10/10 on live container; YDB/Cassandra test-compile green (CI covers).
- Deviations: no VectorIndexWriter port (tenant/operator two-port decomposition preserved); no new rebuild (already on SynquestIndexAdmin); adapters fail UNSUPPORTED on unimplemented kinds, never silently drop.
- Follow-up (non-blocking, hygiene): failure-path metric inconsistency — Postgres records no metric on pre-pass UNSUPPORTED failure; Cassandra/YDB record false; InMemory uses track(). Reconcile on a B1.2-adjacent branch.

### VEC-B1.3 — Wrap adapters as VectorRetriever: landed

- Scope: Cassandra + Lucene, YDB, PostgreSQL + pgvector each expose their vector path through VectorRetriever; no behavior change.
- Evidence: PR #115 (2213f97) — three wrappers + three live equivalence suites green; 33 existing contract tests green across the three adapters.
- Deviations: card says "test suite unchanged"; Option 2 adds three equivalence suites at Operator direction (live-container validation before B1.4 facade wiring). Accepted at review.
- Design note: wrappers delegate through engine.search(mode=VECTOR), not adapter internals — zero behavior change is structural. Three wrappers are byte-identical; a single DelegateVectorRetriever in synquest-api would pass the boundary guard. Revisit before B3 authors Milvus/Qdrant retrievers against the per-adapter pattern.
- Carried gap: capabilities() hardcodes vector-only shape — underlying engine capability detail (filters, index type) not surfaced; overlaps B1.1's recorded VectorCapabilities gap.

### VEC-B1.4 — Routing facade: landed

- Scope: RoutingSynquestEngine in synquest-api routes mode=VECTOR + embedding-present + narrow fields through VectorRetriever; all other requests pass through to the engine. Wide vector+embedding requests fail loud naming the fields.
- Evidence: PR #118 (8490f81) — facade + unit tests green, api suite + boundary guard green.
- Reading-1 claim: zero change for direct-engine callers; facade callers get the narrow retriever contract or an explicit IllegalArgumentException — no silent narrowing. Empty highlights on routed results.
- Deviation from card: card implied pass-through; fail-loud on wide vector requests adopted at operator direction (silent narrowing rejected).
- Confirmed at review: RelevanceFilters is single-field (mustMatchMetadata), so the emptiness check is complete — no silent filter-dropping.
- Precedent: first behavioral class in synquest-api; module scope "port-owned types" extended to include the routing facade.
- Carried: B2.1 owns retriever selection — facade holds one retriever, constructor-injected.
- Widening escape hatch: if VectorSearchRequest grows filters/temporal/minScore, the corresponding clause comes off both predicates (isNarrowVectorRequest, isLossyVectorRequest).

### VEC-B2.1 — Provider namespaces: landed

- Scope: `metadata.*` + `vector.*` namespaces in ProviderRegistry (role-based resolution, not positional).
- Evidence: PR #131 (c9935f5) — registry + composition test (118 insertions).

### VEC-B2.2 — Composition validation: landed

- Scope: `validateComposition` with load-time defaults (unset `vector.provider` → `metadata.provider`); unknown names and capability mismatches fail loud naming provider + capability + reason.
- Evidence: PR #132 (058d426) — validation + test (131 insertions).
- Carried: no silent fallback, ever (now a dev-guide rule via DOC-D2.3).

### VEC-B2.3 — Composition config schema + examples: landed

- Scope: `synquest-composition.schema.json` (`0.1.0-draft`) + all 6 composition examples; fusion (`rrf`, `rrf_k`) and execution (`parallel`/`sequential`, `per_query_override`) included.
- Evidence: PR #133 (28b8001) — schema + 7 files (151 insertions).

### VEC-B3.1 + B3.2 — Milvus + Qdrant adapters: landed (one PR)

- Scope: Both external-service adapters behind VectorRetriever, async, eligibility pre-ranking, Java-side score filtering.
- Evidence: PR #134 (9dabcac) — 9 files (883 insertions), incl. per-adapter tests.

### VEC-B3.3 — Lucene standalone + Cassandra parity: landed

- Scope: In-process Lucene adapter; parity test vs Cassandra+Lucene on identical queries (R3 attribution basis).
- Evidence: PR #135 (507e548) — adapter + parity test (393 insertions).

### VEC-B3.4 — pgvector reference: landed

- Scope: Explicit pgvector reference — contract test + vector write path on PostgresSynquestEngine.
- Evidence: PR #136 (18d6166) — 2 files (62 insertions).

### VEC-B4 narrow — Sequential filtered-KNN: landed

- Scope: Sequential metadata-first mode + `executionMode` per-query override; result shapes identical to parallel by construction (shared fusion path).
- Evidence: PR #137 (18f77bf) — 11 files (269 insertions), incl. SearchServiceSequentialTest.
- Carried: per-backend sequential is Phase-5; crossover selectivity unmeasured without dense legs (B5.6 record).

### VEC-B5.2 — Composition legs executed (partial): comp 1, 2+3, 4+5, 6

- Comp 1 (Cassandra+Lucene, BM25-only): live synquest run, 10 queries, p50/p95 4.5/36ms, recall null (gold unannotated). Local artifact.
- Comps 2/3 (Milvus/Qdrant vector legs): distinct-vector self-match runs, recall@10 1.0 (correctness, not quality), p95 50.5/27.1ms.
- Comps 4/5 (PG lexical legs, shared metadata evidence): recall@10 1.0, p95 21.6ms.
- Comp 6 (YDB lexical legs): recall@10 1.0, p50/p95 19.8/40.8ms.
- Dense-leg quality + hybrid runs blocked on real embeddings (mock-constant vectors prove plumbing only). Artifacts local per `runs/` convention (untracked).
- Machinery: Synquest REST executor + endpoint probe + Q3 bridge in `tools/benchmark-runner` (59+ tests green).

### VEC-B5.3 — Vector-engine effect isolated

- Lucene vs Milvus vs Qdrant on identical mini workload: recall 1.0 ×3, p50s 2–4ms, p95s 27–51ms. No selection signal at this scale; all three viable. Evidence: `b5-effect-isolation.md`.
- B5.4 (metadata-store) + B5.5 (integration): deferred — need hybrid runs, no table faked.

### VEC-B5.6 — Crossover measured (BM25-only scope)

- 10/10 identical hit sets parallel vs sequential, live. No crossover identifiable without dense legs; procedure in place via per-leg trace timings.
- Superseded for the Lucene leg by F-2 below (dense-leg crossover now measured).

### F-1 — Real embeddings live: landed 2026-10-10

- TEI + `synanton-bge-base-embedding` (BAAI/bge-base, 768d) on node1 via GPU-5 blueprint; bench reaches it through an SSH tunnel (`:30800`) + `host-gateway` (NodePort can't cross this host's firewall). Tunnel is manual — host reboot fails dense runs closed (503 by design).
- Wiring: synquest `VLLM_EMBED_BASE_URL` + synflux ingest env in compose; runner `--embedding-model/--embedding-dim` stamps result manifests. Runner suite 65 green.
- Index proof: 81 docs / 81 vector_docs, 0 mismatches, 0 missing. First dense leg: 3/3 × 5 hits, correct tops.
- Bugs fixed en route: `synflux.embedding.*` misnested under `synanton:` (batch-size silently 0 → infinite empty-batch loop, 419k TEI 400s) + fail-loud guard; blank-chunk filter in EmbedStage.
- Evidence: PR #156 (310e6f3).

### F-2 — Dense-leg crossover (Lucene leg): measured 2026-10-10

- 3 queries × parallel vs sequential, topKDense=10, real 768d vectors. 2/3 identical; q2 same set + same top-3 with ranks 4/5 swapped. First real crossover signal, and it's a tail swap — consistent with "sequential constrains the dense universe; order may differ at the tail".
- Artifacts local-only (`vec-f1-dense-first-768-cassandra-lucene-m16`, parallel + sequential runs, model + dim stamped).
- Remaining F-2: hybrid runs for comps 2–6 need embedded corpora in Milvus/Qdrant/PG/YDB (backfill work, not yet scheduled).

### F-2 backfill — Milvus + Qdrant hybrid legs with real vectors: landed 2026-10-10

- Method: `MilvusF2HybridLegRun` / `QdrantF2HybridLegRun` (JUnit, established B5 leg pattern) — 81 demo chunks backfilled from ingestion cache into fresh 768d HNSW collections (M16, COSINE), F-3 queries embedded live via TEI. Artifacts local-only per `runs/` convention.
- Recall@10 (first-pass gold, n=3): **Milvus 0.889 = Qdrant 0.889 > Lucene-hybrid 0.778.** Both HNSW engines return byte-identical q3 rankings and surface `logistics-network.md` at rank 3 — the doc Lucene-hybrid missed entirely.
- Latencies: Qdrant 3–38 ms, Milvus 6–74 ms (small-scale; noise band, not a ranking).
- Honest scope: n=3 queries, first-pass gold; cross-engine agreement is a signal, not a verdict. PG/YDB-native legs still open.

### F-9 — bge-small 384d legs (PG 1.0, YDB 1.0, full model×engine matrix): landed 2026-10-11

- Path chosen over engine surgery: TEI-small (bge-small-en-v1.5, 384d) serves
  from manual docker on node1 (K8s small workload gets no GPU injection —
  post-reboot plugin fault, operator track); `:30801` tunnel in stacks script.
- Second ingest pass: `IngestionJobRunner` skip logic now verifies per-model
  coverage (was state-only: a second model no-op'd). 81 chunks × 2 models coexist.
- Recall@10 (first-pass gold, n=3) — model × engine matrix:

  | Engine | bge-base 768d | bge-small 384d |
  |---|---|---|
  | Lucene-hybrid | 0.778 | 0.889 |
  | Milvus HNSW | 0.889 | 0.889 |
  | Qdrant HNSW | 0.889 | 0.889 |
  | PG (384-locked) | — | 1.0 |
  | YDB-hybrid (384-locked) | — | 1.0 |

- Reading (scoped, n=3): model effect real on Lucene (+0.111 small over base,
  logistics-network.md surfaces #1); engine effect real at fixed model
  (PG/YDB-small 1.0 > Lucene-small 0.889); Milvus=Qdrant byte-identical on
  both models. PG/YDB lack 768d cells (structural lock-in) — no cross-model
  engine claim beyond the 384d column.
- Live compose state: synquest + synflux point at small/30801 (384d index,
  81/81 vectors, 0 mismatches). Base restore = revert env + reindex once
  K8s TEI-base schedules again.
- Evidence: PgF2HybridLegRun, YdbF2HybridLegRun (bench-emitter),
  QueryExecutor model-ref overload (provenance, no synthetic mislabel),
  Milvus/Qdrant leg parametrization (dim/model/run-id via env).

### B5.5 — Integration effect isolated: zero (2026-10-11)

- Lucene-in-service (vector-only mode, `top_k_lexical: 0`) vs Lucene-standalone
  adapter on identical 384d vectors: byte-identical orderings all 3 queries,
  recall 1.0/1.0/0.667 both. The service adds no distortion; earlier
  hybrid-vs-standalone ranking differences were fusion (RRF re-ranks), not
  integration overhead.
- En route: real service bug — `top_k_lexical=0` 500'd (Lucene rejects
  numHits<=0). Fixed in `HybridSearcher.lexical` with the same empty-TopDocs
  guard the dense side already had. Evidence: `LuceneF2HybridLegRun`.

### B5.4 — Metadata-store effect: null by construction (recorded 2026-10-11)

- Vector-only legs cannot measure it: adapters never touch the metadata store
  (retriever sees eligibility + vectors only, by Reading-1 design). Same bytes
  from Cassandra or PG score identically — running the comparison would
  manufacture a table around a zero.
- The effect is observable only in hybrid service legs (lexical side reads the
  store). That needs a PG-backed synquest service — unbuilt, out of scope;
  recorded as a constraint, not a gap.

### P0 — K8s GPU injection: root-caused, bench on manual docker (2026-10-11)

- Verdict: admitted K8s containers receive ZERO NVIDIA devices cluster-wide
  (proven on node1 + node2 + node3 with router-direct probes: `libcuda.so.1`
  missing). Manual `docker --gpus all` with identical image/model/GPU serves
  fine — driver, silicon, image, weights all exonerated.
- Mechanism: plugin runs `DEVICE_LIST_STRATEGY=envvar`, but containerd has
  only `runc` + `enable_cdi` (no nvidia runtime, no hooks) — nobody consumes
  the env injection path. The custom v0.20.0 plugin build crashes
  instantly on `cdi-annotations` (all 3 nodes), so the correct pairing is
  unrepresentable; strategy stays `envvar`, GPU workloads stay at 0.
- Collateral fixed en route: node2 driver↔libs mismatch (module 595.84 vs
  libs 595.99.02 — its own nvidia-smi failed); node2 driver upgraded to
  595.99.02, cluster converged. Node-version matrix in analysis (ask on demand).
- Bench state: `tei-base-bench` (:8002→:30800, 768d) + `tei-small-bench`
  (:8001→:30801, 384d) manual docker on node1; stacks script resolves both
  with K8s-NodePort fallback. Return to K8s serving requires a plugin build
  that supports CDI annotations (or an nvidia runtime in containerd) —
  operator project, not bench work.

### P0 resolved — v0.21.0 plugin restores K8s injection (2026-10-11)

- Rebuilt k8s-device-plugin from current upstream main (8 commits ahead of
  the Sep-4 tree, incl. a 4GiB-memory reporting fix relevant to the 4GB
  1650) with the same debian12-base patch; pushed `:v0.21.0` (v0.20.0 kept
  as rollback); DaemonSet rolled.
- Base TEI schedules green on the first clean admission and serves 768d
  through the K8s-native NodePort path (verified 200 + dim). Manual
  `tei-base-bench` retired. Small stays manual (`tei-small-bench`, one GPU
  per node by design — K8s small pends while base holds it).
- Live state: 768d = K8s TEI (designed path), 384d = manual docker
  (documented fallback); stacks script auto-resolves both; `:30800`/`:30801`
  both 200. Cause of the v0.20.0 failure not isolated to a commit —
  recorded as version-level, not attributed.

### P0 time-slicing — base + small concurrent on node1 (2026-10-11)

- Plugin ConfigMap `sharing.timeSlicing: [{nvidia.com/gpu, replicas: 2}]`
  (no rename — existing specs unchanged); allocatable 2 on all workers.
- Both TEI deployments at 1 concurrently, both serve (768d + 384d verified
  at pod IPs). VRAM shared, no eviction at bench scale.
- Manual dockers retired; `:30800`/`:30801` both resolve to K8s NodePorts.
  Caveat: plan D2's one-workload rule stands for PROD (reranker/synthesis);
  time-slicing is approved for bench coexistence only, until load-tested.
- Revert (one command): delete the ConfigMap env/volume from the DS
  (`CONFIG_FILE`), allocatable returns to 1.

### F-2 backfill — PG/YDB legs: blocked on 384d lock-in (recorded 2026-10-10)

- Both POC engines hard-code 384d: PG `vector(384)` column rejects wider vectors (`PostgresSynquestEngine` paddedVector/write path); YDB's statements are 384-literal-shaped (768d noted in-code as binding vs the 64MB gRPC cap).
- Options rejected: (a) running PG/YDB at 384d with different vectors — breaks cross-engine comparability; (b) truncating 768→384 via Matryoshka cut — compares cut vectors on one engine against full vectors on others.
- Filed as F-9 (dim-parameterization of the POC engines) in the selection framework. F-2 closes with Lucene + Milvus + Qdrant hybrid legs.

### F-3 — First-pass gold + first recall: landed 2026-10-10

- Gold: `demo-data/eval/retrieval-benchmark/queries-f1-dense.json` — 3 queries, content_ref-level gold, every judgment carries `annotator: agent-first-pass, confidence: low` + rationale. PDFs unjudged (unread this pass). Human re-annotation replaces, never edits, this file.
- Result: **recall@10 = 0.78** (q1 1/1 Acme, q2 1/1 Globex, q3 1/3 — logistics-network.md + quarterly-report-q1.md missed from top-5). Artifact local-only, model + dim stamped.
- Methodology wrinkle recorded: recall key is k=10 but lists are top-5, so this reads as recall@5. Fix the k/returned-hits alignment before comparing across legs.

### F-3 confirmed — human gold in chat (2026-10-11)

- All three first-pass judgments confirmed unchanged (Acme-only / Globex-only /
  3-facet q3). File provenance upgraded to `human-confirmed, medium`
  (single annotator, no adjudication). PDFs still unjudged — next expansion.
- Final scoreboard, human gold, n=3:

  | Engine | bge-base 768d | bge-small 384d |
  |---|---|---|
  | Lucene-hybrid (service) | 0.778 | 0.889 |
  | Lucene-vector (service) | — | 0.889 |
  | Lucene-standalone | — | 0.889 |
  | Milvus HNSW | 0.889 | 0.889 |
  | Qdrant HNSW | 0.889 | 0.889 |
  | PG (384-locked) | — | 1.0 |
  | YDB-hybrid (384-locked) | — | 1.0 |

  (768d Lucene cell rescored from run log after /tmp wipe; queries+gold identical.)
- Reading holds: model effect (Lucene +0.111), engine effect at fixed model
  (PG/YDB 1.0 > rest 0.889), Milvus=Qdrant identical everywhere, B5.5
  integration zero. All cells provisional on n=3 + unjudged PDFs.

### F-3 v2 — PDF judgments + outsourcing surprise (2026-10-11)

- All 4 PDFs judged from ingested chunks: outsourcing-agreement (pharma
  contract) ADDED to q2 gold per user (supplier relationship counts);
  mental-health + sks8300-manual excluded (domain); quarterly-report.pdf
  excluded (single content-free chunk — gold marks substance).
- v2 scoreboard (human gold, n=3): PG 1.0, YDB 1.0; Milvus/Qdrant 0.889 both
  models; Lucene-vec/standalone-384 0.889; Lucene-hyb-384 0.722;
  Lucene-hyb-768 0.611 (rerun live for a complete matrix).
- Standout: hybrid fusion HURT q2 on Lucene (outsourcing-agreement ranks in
  pure-vector but not in fused top-5, both models). RRF re-ranks a good
  dense hit out — first evidence against blind fusion, and a direct input
  to crossover tuning.

### F-3 n=12 — full matrix, human gold (2026-10-11)

- 9 expansion queries (q4–q12) human-confirmed in chat; legs read queries
  from the single gold file (`BENCH_QUERIES_FILE`; run with `--no-build-cache`,
  env vars are invisible to Gradle up-to-date checks — a full rerun silently
  no-ops without it).
- Recall@10, human gold, n=12:

  | Engine | bge-base 768d | bge-small 384d |
  |---|---|---|
  | Lucene-hybrid (service) | 0.861 | 0.847 |
  | Lucene-vector (service) | — | 0.889 |
  | Lucene-standalone | 0.778 | 0.806 |
  | Milvus HNSW | 0.847 | 0.847 |
  | Qdrant HNSW | 0.847 | 0.847 |
  | PG (384-locked) | — | 1.0 |
  | YDB-hybrid (384-locked) | — | 1.0 |

- Regularities: Milvus=Qdrant byte-identical per-query on BOTH models
  (incl. shared misses q6 0.5 / q7 0.0); PG=YDB perfect 12/12; q7 (scorecard)
  missed by every pure-vector leg but caught by hybrid + PG/YDB (lexical
  table match rescues it — the mirror image of the q2 fusion miss).
- Ranking by mean: PG=YDB (1.0) > Lucene-vec-384 (0.889) >
  Lucene-hyb-768 (0.861) > Milvus=Qdrant both models + Lucene-hyb-384
  (0.847) > Lucene-st-384 (0.806) > Lucene-st-768 (0.778).
  All cells n=12, single annotator, PDFs judged.

### F-3 n=20 — round 2 (q13–q20), small-384 column (2026-10-11)

- 8 new queries human-confirmed (pharma parties/terms, switch login, mental
  health, Krakow, EV capex, USMCA, milk-run/VMI bridge). Legs read the
  single gold file (`BENCH_QUERIES_FILE`).
- Recall@10, human gold, n=20, bge-small 384d: PG 0.975, YDB 0.975,
  Milvus 0.883, Qdrant 0.883, Lucene-hyb 0.883, Lucene-st 0.858.
  (Milvus=Qdrant byte-identical again, all 20.)
- Systematic miss: q20 (milk-run/VMI bridge) 0.5 on ALL six legs — the two
  gold docs never co-occur in any top-5. Multi-doc bridge queries are the
  hardest class in this corpus; next tuning input after q2-fusion.
- YDB CA now repo-local (`docker/certs/ydb/`, gitignored) + `YDB_CA_PATH`
  export in stacks script — /tmp wipes broke legs on reboot, per decision.

### F-tune — RRF diagnosis: fetch depth + fusion math both exonerated (2026-10-11)

- q2 outsourcing-agreement, Lucene-small: `rrf_k` ∈ {10, 30, 60, 120} gives
  byte-identical rankings (k is inert — expected: single-leg hits keep a
  constant ratio under RRF rescaling).
- BM25-only probe (top 100): outsourcing chunks at lexical ranks 12–18 —
  present but weak (pharma vocab shares no query terms).
- `top_k_lexical` 10→100 changes nothing: with dense rank 3 (1/63) + lexical
  rank ~12–18 (1/72–1/78), the fused sum still trails dual-leg competitors.
  The miss is structural to rank-fusion, not a depth artifact.
- Candidate fixes (recorded, not implemented): score-based fusion, leg-priority
  weighting, single-leg union boost. Any of these is a SearchService change
  needing its own review — filed here as the first gold-backed tuning input.

### F-tune option A — implemented, default off (2026-10-11)

- `RrfFusion.combine` overload + `synquest.search.default-single-leg-boost`
  (default 1.0 = classic RRF; <1.0 rejected). Boost applies to DENSE-only
  hits only — boosting lexical-only hits promoted BM25 tail noise over
  dual-leg substance (measured). Unit tests: default-equivalence,
  parity-flip, rejection. Option B kept as code TODO + review-brief item.
- Trade curve on n=12 human gold (Lucene-hyb-384): 1.0 → 0.8472 (q2 0.5,
  q7 1.0); 1.5 → 0.7639 (q2 0.5, q7 0.0); 2.0 → 0.8056 (q2 1.0, q7 0.0).
  No value dominates: the boost moves the miss between queries. Shipped
  default-off; fitting the value needs a bigger gold set. Classic RRF
  remains the production behavior.

### Ops closeout (2026-10-11)

- node0 `kubectl logs` failures (`nvidia-smi not found` on log reads) tracked
  node1 kubelet restarts during the GPU fault window; function fully restored
  with no change (verified across namespaces). No action.
- Orphan watchdog cron installed on node0 (verified silent-healthy).

### VEC-B6 — Synthesis published (draft for review)

- Framework: `docs/architecture/vector-engine-selection.md` — matrix with measured/unmeasured cells, per-context recommendations with evidence notes, sources, follow-ons F-1–F-8 (owners TBD).
- B0.4 profiles stay frozen as task evidence; functional overlay lives in the framework.
- NOT yet reviewed: architecture + legal sign-off required before production use.

### F-4/F-5 — Reviews signed 2026-10-11: closed

- Andrei Minin signed the review brief with architecture + legal authority.
- ADR-013 Proposed → Accepted (deciders + date filled); framework F-4/F-5
  rows closed with signatures. Framework may now steer production decisions
  within its stated scope and revisit triggers.
- Next: F-8 Week-2 freeze scheduling (packet ready).

### DOC-D3.3 — Pattern library additions: landed

- Appended PN-9…PN-13 to `docs/implementation/pg-poc/pattern-notes.md`
  (the library seed; house rules append-only/chronological kept): roles-not-
  positions composition, unmeasured-cell flags, score-space portability,
  identical-input attribution, runbook-by-execution. Each names its Track B origin.

### DOC-D4.1 — Cross-reference pass 2026-10-10: landed

- Mechanical citation check over all 10 DOC-evidence docs: every referenced
  path resolves (6 same-directory sibling links + schema basenames verified
  against `schemas/benchmark/`). One genuine fix: framework §4 cited
  `b5-effect-isolation.md` bare — corrected to the full
  `../implementation/benchmark-runner-vector/` path.
- Standing cadence: repeat this pass at every phase gate; gate checklists
  carry DOC items (see Week-2 entry below).

### Week-2 gate: FROZEN 2026-10-11

- Scope: Manifest + Result schema + metric-taxonomy freeze (BR-A0.2/A0.3/A0.4).
- Owner: Andrei Minin (approved F-8). Executed per
  `docs/implementation/week2-freeze-packet.md` §2–§4, no exceptions.
- Frozen: manifest schema 0.2.0, result schema 0.2.0, taxonomy 1.0.
  Fixtures bumped + re-validated; freeze-check leg reproduced 0.8472.
- Post-freeze rule: schema changes need version bump + migration note, never
  silent edit. DOC-D0 final; B5 pre-check formal complete.
- DOC items for future gates (standing): D0 refs current vs frozen versions;
  D4.1 cross-reference pass repeated; runbook validation log green
  against the frozen CLI.

## Alias map (transition only — drop after all branches renamed)

- 028b → BENCH-BASE · 028c → CASS-EMIT-024a · 028d → YDB-EMIT-024b ·
  028e → PG-EMIT-pg · A.* → BENCH-EMIT-* · PG-POC-017 → BENCH-CMP
- runs/024a-v1.json → runs/cassandra-v1.json · runs/024b-v1.json → runs/ydb-v1.json
- legs [baseline, 024A, 024B, PG] → [baseline, cassandra, ydb, postgres]

## Rename order

1. B.2 completes as-is; artifact renamed to runs/cassandra-v1.json at CLI-wrap.
2. Branch + config + tracker rename pass before 024b starts.
3. Baseline/PG rename on next touch.

- Status: rename pass pending (024b paused, not started); alias map active until drop condition met.

## Parked (single source — lands only here, not in branch-local files)

- `--engine baseline` wiring + README boot-jar line: parked until 028b line
  and emitter line both merge to main; then one tiny PR off main.
  (Two branch-local copies would drift — this section is the only record.)
