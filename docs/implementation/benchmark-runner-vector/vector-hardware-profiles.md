# Vector Hardware Profiles

**Task:** VEC-B0.3 — Hardware + scalability + operational complexity
**Track:** Track B — SYN-VECTOR-001
**Status:** Draft for review
**Depends on:** VEC-B0.1 (license matrix — landed), VEC-B0.2 (deployment modes — landed)
**Evidence path:** `docs/implementation/benchmark-runner-vector/vector-hardware-profiles.md`
**Reads:** Agent 2, 2026-10-07
**Assembly:** Agent 1, 2026-10-07

---

## §1. Methodology and declared universe

### §1a. Engine universe

The 11 engines below are this draft's declared universe, identical to VEC-B0.2's
universe. Divergence from VEC-B0.1's license matrix engine set is recorded in §7,
not resolved silently.

PostgreSQL + pgvector · YDB · Cassandra + Lucene · Milvus · Qdrant · Tantivy ·
Lucene (standalone) · Weaviate · Vespa · OpenSearch · Quickwit

### §1b. Data provenance

No cell in this artifact is `measured` in the card's sense — no benchmark was run,
no deployment was instrumented. Every filled cell is `sourced` (published vendor
figure or formula) or `assumed` (no published figure, or page-exists-unread).
`n/a` cells state a structural reason the metric does not apply. This is a
finding about the state of published data, not a read failure: the matrix records
what vendors publish, and what they don't.

Source vintages vary and are stated per cell where relevant. The oldest anchor
(Vespa, 2022-06-08) is also the widest-band and most consequential; it is flagged
in §8 as a re-verification candidate.

### §1c. Marking discipline

Every hardware cell carries one of four states (defined in §2d): `measured`,
`sourced`, `assumed`, `n/a`. `assumed` cells route to §9 Phase-5 candidates by
category — "no figure published" is a measurement gap; "page unread" is a cheap
follow-up read; the two are distinguished in the cell text.

---

## §2. Shared reference tables

### §2a. Index families

Engine memory shape is determined by index family before any dimension figure
is interpretable. Three families appear, plus a lexical-only class.

| Family | Engines | Memory shape |
|---|---|---|
| HNSW | pgvector (option), Milvus, Qdrant, Weaviate, Vespa, OpenSearch, Lucene (standalone) | Resident graph; overhead scales with m and d |
| IVF / kmeans-tree | YDB, pgvector (IVFFlat option) | Posting lists + cluster cache; index resident in distributed storage |
| SAI / plugin | Cassandra + Lucene | Split across JVM heap (memtable indexes) and chunk cache |
| Lexical-only | Tantivy, Quickwit | No dense-vector memory model published |

### §2b. Quantization states (byte-per-dimension multipliers)

Sources: Vespa docs (int8/bfloat16/float/double); pgvector README (fp32/halfvec/bit/sparsevec);
Qdrant turbo4 (from Qdrant optimize docs).

| State | Bytes/dim | Source |
|---|---|---|
| fp32 (float) | 4 | universal default |
| fp16 / bfloat16 | 2 | Vespa, pgvector |
| int8 / uint8 | 1 | Vespa, YDB |
| turbo4 | 0.5 | Qdrant |
| bit / binary | 0.125 | pgvector |
| sparsevec | 8 × nnz + 16 | pgvector |

Quantization reduction is not linear against memory cells: Weaviate's own
cross-check (6 GB → 2 GB at 1024d, ~3×) includes index overhead, not just
vector-byte reduction.

### §2c. HNSW parameter defaults

HNSW is not universal (§2a). Non-HNSW engines have sibling parameter tables.

| Engine | m / maxConn | ef_c | ef_s / other |
|---|---|---|---|
| pgvector | 16 | 64 | ef_search 40 |
| OpenSearch | 16 | (default) | — |
| Lucene (standalone) | 16 | 100 | — |
| Milvus | 16 | — | M=4 datapoint at 1.36× raw |
| Vespa | 16 (max-links-per-node) | — | neighbors-to-explore 200 |
| Weaviate | 64 (maxConn) | — | 640 B/node fixed overhead |
| Qdrant | — | — | assumed (params not sourced) |
| YDB | — | — | IVF params: levels 1–16, clusters 2–2048, overlap_clusters 1 |
| Cassandra + Lucene | — | — | n/a (no joint HNSW config published) |
| Tantivy | — | — | n/a (no HNSW in indexed versions) |
| Quickwit | — | — | n/a (lexical) |

`m` drives memory materially: Milvus at M=4 vs M=16 differs by 32% on the same
engine (1.36× vs 1.8×). Cells default to the vendor's stated default unless the
cell text says otherwise.

### §2d. Cell-state legend

| State | Meaning | Routing |
|---|---|---|
| measured | Benchmark or instrumented deployment | — (none in this artifact) |
| sourced | Published vendor figure or formula | — |
| assumed (no figure) | No published figure found | Phase-5 measurement |
| assumed (unread) | Page exists in vendor docs, content not read | Phase-5 follow-up read |
| n/a (host-provided) | Embedded library; property supplied by host | — |
| n/a (IVF) | Metric defined for HNSW; engine uses other family | — |
| n/a (absent) | Engine has no such model by construction | — |

The card names three states (`measured` / `sourced` / `assumed`). `n/a` is a
fourth, required because the card's own acceptance test ("every engine has an
entry") is met by engines for which the metric does not apply. Making `n/a`
explicit rather than blank-with-note keeps the "every cell filled" check honest:
a filled `n/a` is different from an unfilled cell.

---

## §3. Hardware profiles

### PostgreSQL + pgvector

| Field | Value | Tier | Quant | Marking | Source |
|---|---|---|---|---|---|
| CPU profile | — | — | — | assumed (no figure) | — |
| Mem 384d | 1544 B/vec · 1.544 GB/1M | storage | fp32 | sourced | pgvector README (4d+8) |
| Mem 768d | 3080 B/vec · 3.080 GB/1M | storage | fp32 | sourced | same |
| Mem 1536d | 6152 B/vec · 6.152 GB/1M | storage | fp32 | sourced | same |
| Index-tier (HNSW graph) | — | index | fp32 | assumed (no figure) | README: "uses more memory" vs IVFFlat |
| Storage footprint | — | — | — | assumed (no figure) | — |

HNSW params: m=16, ef_c=64, ef_s=40. Quant states: fp32 (4d+8), halfvec fp16 (2d+8), bit (d/8+8), sparsevec (8·nnz+16).

**Tier note:** `storage` is Postgres row format, not resident memory. Not
comparable to `index`-tier cells of other engines until the HNSW graph figure lands.

### YDB

| Field | Value | Tier | Quant | Marking | Source |
|---|---|---|---|---|---|
| CPU profile | — | — | — | assumed (no figure) | — |
| Mem 384d | n/a | — | — | n/a (IVF) | index resident in distributed storage; bounded by level-cache config |
| Mem 768d | n/a | — | — | n/a (IVF) | same |
| Mem 1536d | n/a | — | — | n/a (IVF) | same |
| Storage footprint | — | — | — | assumed (no figure) | — |

Index params (IVF): levels 1–16 (rec 1–3), clusters 2–2048 (rec 64–512), overlap_clusters 1 (rec 3), vector_type float/uint8/int8, dim ≤ 16384. Posting table stores keys not vectors by default; covering index stores vectors; level table cacheable via `resource_manager.kqp_level_cache_max_size_bytes`. Clusters read over network per descent.

### Cassandra + Lucene

| Field | Value | Tier | Quant | Marking | Source |
|---|---|---|---|---|---|
| CPU profile | — | — | — | assumed (no figure) | — |
| Mem 384d | — | — | — | assumed (no joint figure) | — |
| Mem 768d | — | — | — | assumed (no joint figure) | — |
| Mem 1536d | — | — | — | assumed (no joint figure) | — |
| Storage footprint | +20–35% disk over unindexed (SAI variant) | disk | — | sourced | cassandra.apache.org SAI FAQ |

Sourced notes: SAI memory splits between JVM heap (memtable indexes) and chunk cache (on-disk). Per-index byte metrics exist (`indexFileCacheSize`). `cassandra-lucene-index` plugin dials: ram_buffer_mb 64 / max_merge_mb 5 / max_cached_mb 30 per local index × partitions.

### Milvus

| Field | Value | Tier | Quant | Marking | Source |
|---|---|---|---|---|---|
| CPU profile | — | — | — | assumed (no figure) | (managed calc 1:4 CPU:mem is deployment shape, not profile) |
| Mem 384d | 2765 B/vec · 2.765 GB/1M | index | fp32 | sourced | milvus.io/blog 2026-03-19 |
| Mem 768d | 5530 B/vec · 5.530 GB/1M | index | fp32 | sourced | same |
| Mem 1536d | 11059 B/vec · 11.059 GB/1M | index | fp32 | sourced | same |
| Storage footprint | — | — | — | assumed (no figure) | — |

Multiplier: 1.8× raw. Same-page range stated as 1.5–2.0×; sizing-tool blog states 2–3×. Second datapoint: 8M × 768d HNSW M=4 → 1.36× raw (Alibaba sizing calc, experimental, managed context).

### Qdrant

| Field | Value | Tier | Quant | Marking | Source |
|---|---|---|---|---|---|
| CPU profile | segments parallelize per request; default_segment_number = core count for latency | — | — | sourced-qualitative | qdrant.tech ops-optimization/optimize |
| Mem 384d | — | — | — | assumed (no figure) | — |
| Mem 768d | — | — | — | assumed (no figure) | — |
| Mem 1536d | — | — | — | assumed (no figure) | — |
| Storage footprint | — | — | — | assumed (no figure) | — |

Sourced qualitative (tiers): default cached tier = fp32 vectors in RAM; turbo4 datatype = 4-bit/dim on disk; turboquant bits1; cold tier = vectors + HNSW on disk; `inline_storage` ≈ 3–6× float32 vectors when cold + quantized.

### Tantivy

| Field | Value | Tier | Quant | Marking | Source |
|---|---|---|---|---|---|
| CPU profile | — | — | — | assumed (no figure) | — |
| Mem 384d | n/a | — | — | n/a (absent) | no dense-vector model published |
| Mem 768d | n/a | — | — | n/a (absent) | same |
| Mem 1536d | n/a | — | — | n/a (absent) | same |
| Storage footprint | — | — | — | assumed (no figure) | — |

IndexWriter memory arena: min 15 MB/thread (baseline 12 MB), configurable budget, segments flush on budget; ≤8 worker threads. HNSW params: n/a (no dense-vector path).

### Lucene (standalone)

| Field | Value | Tier | Quant | Marking | Source |
|---|---|---|---|---|---|
| CPU profile | — | — | — | assumed (no figure) | — |
| Mem 384d | — | — | — | assumed (no figure) | — |
| Mem 768d | — | — | — | assumed (no figure) | — |
| Mem 1536d | — | — | — | assumed (no figure) | — |
| Storage footprint | — | — | — | assumed (no figure) | — |

Sourced notes (build-time, not resident): 1M × 256d M16/efC100 build-time RAM 876 MB (vectors 497 + graph 379, `IndexingChain::ramBytesUsed`); merge-time heap >2 GB at 9M vecs M16 (eager neighbor arrays). HNSW params: M=16, efC=100 (benchmark standard; matches OpenSearch Lucene-engine defaults).

### Weaviate

| Field | Value | Tier | Quant | Marking | Source |
|---|---|---|---|---|---|
| CPU profile | queries CPU-bound; imports CPU-bound via indexing | — | — | sourced-qualitative | docs.weaviate.io concepts/resources |
| Mem 384d | 2176 B/vec · 2.176 GB/1M (formula); 3.07 GB upper (2× raw) | index | fp32 | sourced-formula | same |
| Mem 768d | 3712 B/vec · 3.712 GB/1M; 6.14 GB upper | index | fp32 | sourced-formula | same |
| Mem 1536d | 6784 B/vec · 6.784 GB/1M; 12.29 GB upper | index | fp32 | sourced-formula | same |
| Storage footprint | — | — | — | assumed (no figure) | — |

Overhead model: fixed 640 B/node (maxConn 64 × 10 B), independent of d. Cross-check (same vendor): 6 GB/1M @1024d unquantized; 2 GB quantized. Formula at 1024d: 1e6 × (4096+640) = 4.7 GB; + overhead ≈ 6 GB ✓. HNSW params: maxConn=64.

### Vespa

| Field | Value | Tier | Quant | Marking | Source |
|---|---|---|---|---|---|
| CPU profile | — | — | — | assumed (no figure) | — |
| Mem 384d | ~1843–2150 B/vec · ~1.84–2.15 GB/1M | index | fp32 | assumed (extrap. from 768d; const-overhead alt ~2150) | blog.vespa.ai 2022-06-08 |
| Mem 768d | ~3686–4301 B/vec · ~3.69–4.30 GB/1M | index | fp32 | sourced | same |
| Mem 1536d | ~7373–8602 B/vec · ~7.37–8.60 GB/1M | index | fp32 | assumed (extrap.; const-overhead alt ~6758) | same |
| Storage footprint | — | — | — | assumed (no figure) | — |

Overhead model: proportional, ~20–40% of raw. Anchor: "1B × 768d fp32 requires close to 3 TiB; HNSW graph adds 20–40%; ~4 TiB total." Interpolation law stated (linear-overhead); constant-overhead alternative bracketed in the cell. HNSW params: max-links-per-node=16, neighbors-to-explore-at-insert=200. Tensor cell types: int8=1B, bfloat16=2B, float=4B, double=8B.

### OpenSearch

| Field | Value | Tier | Quant | Marking | Source |
|---|---|---|---|---|---|
| CPU profile | — | — | — | assumed (no figure) | — |
| Mem 384d | 1830 B/vec · 1.830 GB/1M | index | fp32 | sourced-formula | docs.opensearch.org knn-methods-engines |
| Mem 768d | 3520 B/vec · 3.520 GB/1M | index | fp32 | sourced-formula | same |
| Mem 1536d | 6899 B/vec · 6.899 GB/1M | index | fp32 | sourced-formula | same |
| Storage footprint | — | — | — | assumed (no figure) | — |

Formula: 1.1 × (4 × dimension + 8 × m) B/vector, m default 16. Replication note: "using a replica doubles the total number of vectors" — cost implication, not topology; recorded here, not in the replication cell.

### Quickwit

| Field | Value | Tier | Quant | Marking | Source |
|---|---|---|---|---|---|
| CPU profile | index ~7.5 MB/s/core | — | — | sourced | quickwit.io/docs/deployment/cluster-sizing |
| Mem 384d | n/a | — | — | n/a (absent) | lexical-only; no per-vector figure |
| Mem 768d | n/a | — | — | n/a (absent) | same |
| Mem 1536d | n/a | — | — | n/a (absent) | same |
| Storage footprint | — | — | — | assumed (no figure) | — |

Sourced node shapes (not per-vector cells): indexers 4 GB RAM/core, ≥8 GB instances, ≥120 GB volume (100 GB split cache + 4 GiB ingest queue); searchers 8 GB RAM/core on S3 (4 GB/core on fast store), ≥4 GB instances, ≤500 MB/request/node aggregation cap, stateless; metastore PG 1c/2 GB (2c/4 GB at hundreds of indexes); PoC single node 2c/8 GB; indexer default heap 2 GiB (doubling ≈ total).

---

## §4. Scalability

Legend: (S) sourced, (Sq) sourced-qualitative, (A) assumed, (N/A) not applicable with reason.

| Engine | Vertical max | Horizontal model | Replication | Consistency | Upgrade path |
|---|---|---|---|---|---|
| PostgreSQL + pgvector | scale-up single instance; 32 TB non-partitioned limit (S) | replicas (hot standby) + sharding via Citus/PgDog (Sq) | WAL-based; PITR (S) | ACID (S) | ALTER EXTENSION vector UPDATE (S) |
| YDB | — (A) | sharded/distributed; level-table auto-partitioning by load (Sq) | index-table replicas supported (Sq) | SYNC index-write option (default, only option) (S) | — (A) |
| Cassandra + Lucene | — (A) | sharded (token ring); plugin indexes below distribution layer (Sq) | RF-based; index follows data (Sq) | — (A) | — (A) |
| Milvus | — (A) | sharded (segments 512MB/1GB/2GB; query/index/data/proxy nodes) (Sq) | — (A) | — (A) | — (A) |
| Qdrant | — (A) | sharded (segment model) (Sq) | — (A) | — (A) | — (A) |
| Tantivy | — (A) | none native (embedded library) (A) | n/a (host-provided) | n/a (host-provided) | — (A) |
| Lucene (standalone) | 2.1B docs per-index hard limit (S) | none native (embedded library) (Sq) | n/a (host-provided) | n/a (host-provided) | — (A) |
| Weaviate | — (A) | sharded, logarithmic to billions (Sq) | — (A) | — (A) | — (A) |
| Vespa | — (A: methodology page, no figure) | sharded (flat random, volume) + replicated (grouped, throughput) (S) | grouped content distribution (Sq) | — (A: unread) | — (A) |
| OpenSearch | — (A) | sharded (segments/shards) (S) | replica shards; doubles vector count (S) | — (A) | — (A: unread) |
| Quickwit | — (A) | sharded + independently scalable roles (5 components) (S) | — (A) | — (A) | — (A) |

---

## §5. Operations

Legend: (S) sourced, (Sq) sourced-qualitative, (A) assumed, (N/A) not applicable with reason.

| Engine | Node count (HA) | Dependencies | Backup | Observability | Failure modes |
|---|---|---|---|---|---|
| PostgreSQL + pgvector | — (A) | Postgres 13+ (S) | PITR (S) | pg_stat_statements / PgHero; EXPLAIN ANALYZE BUFFERS (S) | — (A) |
| YDB | — (A) | — (A) | — (A) | — (A) | — (A) |
| Cassandra + Lucene | — (A) | — (A) | — (A; snapshots platform feature, not read) | SAI index-group + table-state metrics (Sq) | — (A) |
| Milvus | — (A) | — (A; etcd/MinIO/Pulsar general knowledge, not read) | — (A) | — (A) | — (A) |
| Qdrant | — (A) | — (A) | — (A: unread) | — (A: unread) | — (A) |
| Tantivy | n/a (host-provided) | Rust host (sidecar model per plan) (S-plan) | snapshot sidecar's index dirs (A: plan carryover) | — (A) | — (A) |
| Lucene (standalone) | n/a (host-provided) | JVM host (Sq) | n/a (host-provided) | `ramBytesUsed()` API (build-time estimate) (S) | merge-time heap spikes (Sq) |
| Weaviate | — (A) | — (A) | — (A) | — (A) | — (A) |
| Vespa | — (A) | — (A) | — (A) | — (A: unread — metrics-for-capacity-planning) | — (A: unread — scaling-for-failures section) |
| OpenSearch | — (A) | — (A) | — (A: unread — snapshot/restore) | — (A: unread — monitoring) | — (A) |
| Quickwit | metastore 1-or-several pods for HA (PG-backed); searchers scale on concurrency (S) | PostgreSQL metastore; S3/GCS/Azure object storage (S) | — (A: splits on object storage, reason not procedure) | Prometheus + Grafana in reference deployment (Sq) | — (A) |

---

## §6. Cross-engine findings

Three findings that only emerge from the set, not from any single engine read.

### §6a. Overhead type is not uniform

HNSW index overhead against raw vector bytes takes three different forms:

| Type | Engines | Shape |
|---|---|---|
| Fixed-per-node | Weaviate (640 B/node), OpenSearch (~10% of raw + 128 B for m=16) | Ratio shrinks as d grows |
| Proportional to raw | Vespa (~20–40%), Milvus (1.8×) | Ratio constant across d |
| Not applicable | YDB (IVF), Tantivy/Quickwit (lexical) | — |

Interpolation across dimensions is valid for proportional engines, invalid for
fixed engines. Vespa's 384/1536 cells are marked `assumed` for this reason;
Weaviate's are computed directly (640 B/node × constant). pgvector's HNSW figure
would fall into a fourth bucket — unknown, because it isn't published.

### §6b. Tier comparability is not universal

`index`-tier memory cells across engines are not directly comparable to each
other, nor to pgvector's `storage`-tier cells. OpenSearch's formula includes
graph overhead; Weaviate's includes maxConn overhead; Vespa's includes the
20–40% graph band; Milvus's includes 1.8× total; pgvector's includes neither
graph nor shared_buffers residency. Cells carry the tier label because comparing
across tiers produces false precision.

### §6c. Source vintages vary by four years

Vespa's anchor is 2022-06-08; Milvus's is 2026-03-19. Both are current as of
read, but a 2022 anchor on a moving engine should not be treated as equivalent
to a 2026 one without re-verification. Flagged in §9.

---

## §7. Universe divergence from B0.1 / B0.2

Same 11-engine universe as VEC-B0.2. Divergence from VEC-B0.1's 21-engine set is
inherited from the B0.2 §5a record — no new divergences introduced by this draft.

| Category | Matrix entries | This draft | Basis |
|---|---|---|---|
| Combined rows | Cassandra / Lucene, PostgreSQL / pgvector | Combined | Deployment and hardware profile identical for paired components |
| Quickwit split | listed standalone | Standalone (split from Tantivy) | Distinct engine |
| PG extensions | pgvectorscale, pgvecto.rs | Excluded | Hardware profile follows pgvector; no independent figures published |
| Adjacent engines | Elasticsearch, Pinecone, Chroma, LanceDB, FAISS, hnswlib, sqlite-vec | Excluded | Not under active benchmarking |

---

## §8. Phase-5 measurement candidates

Cells marked `assumed` route here, split by category.

### §8a. Measurement gaps (no figure published — requires benchmarking or vendor disclosure)

| Engine | Cell | Note |
|---|---|---|
| pgvector | Index-tier HNSW memory | README says only "more memory" vs IVFFlat |
| pgvector | CPU profile | — |
| Cassandra + Lucene | Mem 384/768/1536d | No joint figure for either pairing |
| Qdrant | Mem 384/768/1536d | Sourced qualitative tiers only |
| Qdrant | CPU profile | Qualitative; no numbers |
| Lucene (standalone) | Mem 384/768/1536d (resident) | Build-time figures exist; steady-state does not |
| Tantivy | All memory cells | Lexical-only; n/a on dense-vector, but other metrics possible |
| Quickwit | All per-vector memory cells | Lexical-only |
| Milvus, Weaviate, Vespa, OpenSearch | CPU profiles | None published |
| All engines | Storage footprint (except Cassandra+Lucene SAI) | Broad gap; consider a batch Phase-5 measurement |

### §8b. Follow-up reads (page exists, content unread)

| Engine | Cell | Page |
|---|---|---|
| OpenSearch | Upgrade path | rolling-upgrade page |
| OpenSearch | Backup | snapshot/restore pages |
| OpenSearch | Observability | monitoring pages |
| Qdrant | Replication, Consistency | consistency-guarantees page |
| Qdrant | Upgrade path | upgrades page |
| Qdrant | Backup | snapshots page |
| Qdrant | Observability | monitoring/telemetry + memory-usage pages |
| Vespa | Consistency | Consistency Model page |
| Vespa | Observability | metrics-for-capacity-planning |
| Vespa | Failure modes | scaling-for-failures section |
| Qdrant | HNSW params | not sourced in this batch |

### §8c. Re-verification candidates (sourced but dated or contested)

| Cell | Reason |
|---|---|
| Vespa 768d anchor (2022-06-08) | Four years of releases; anchors all three Vespa dims |
| Milvus multiplier 1.8× | Same-page range 1.5–2.0×; sizing-tool states 2–3× |
| OpenSearch mem cells | Formula-derived; not verified against running deployment |

---

## §9. Acceptance check

- [x] Every engine has an entry (§3) — 11 engines
- [x] Every hardware cell marked (§1c, §2d) — measured / sourced / assumed / n/a
- [x] Assumed cells routed to Phase-5 candidates (§8)
- [x] Scalability section present (§4)
- [x] Operations section present (§5)
- [x] Evidence path matches VEC-B0.3 requirement

No cell is `measured`. All filled cells are `sourced` or explicitly `n/a` with a
stated structural reason; `assumed` cells are routed by category. The card's
marking discipline is met; the artifact records what is published, and names
what is not.
