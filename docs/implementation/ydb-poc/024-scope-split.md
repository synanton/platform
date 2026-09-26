# YDB-POC-024 scope — dual adapter (split 024A / 024B)

**Status:** Recorded (re-scopes 024; no new ticket numbers)
**Date:** 2026-09-26

## Why the split

011 established that no pre-existing Cassandra search implementation exists to move —
current search runs on the ingestion-cache-backed Lucene path. 024 therefore delivers
**two** adapters, not one.

## Split

| Ticket | Deliverable | Depends on |
|---|---|---|
| **024A** | `CassandraSynquestEngine` (new code): lexical/vector/hybrid retrieval over the current ingestion-cache-backed path, behind the `SynquestEngine` port with pre-ranking eligibility | 001, 002, 010, 011, 037, 039 |
| **024B** | `YdbSynquestEngine`: same port, YDB-backed (the original 024 scope) | 001, 002, 010, 011, 037, 039 |

025–028 (eligibility proof, side-channels, temporal rejection, benchmarks) run against
**both** adapters. The §14 benchmark matrix compares three legs: ingestion-cache
baseline (006) vs 024A vs 024B.

## 024B schema decision (recorded 2026-09-26): separate index structure

When 024B introduces the native vector type, it uses a **separate index table**
for the vector search path — 021's `chunks` schema (Base64 embedding + dim) is
final and is not migrated. Rationale: keeps the demonstrated 021 contract stable
while 024B experiments; migration risk stays out of the critical path.
Exception: if YDB 26.3 requires the vector column to live in the source table
for index eligibility, 024B may migrate instead — but the migration procedure
then becomes explicit 024B scope, reviewed before execution. Default holds
unless proven otherwise during the Gate 0 probe.

Key derivation (clean, recorded): vectors rows address chunks as
`tenant|chunk_id`, derived losslessly from 021's `(tenant_id, chunk_id)` —
no join, no divergence. 021's "final" status stands; the vectors table holds a
superset (text + embedding) keyed differently for HybridRank's single-PK rule.

## Embedding pipeline status (recorded 2026-09-26): not available here

No live embedding endpoint in this environment (no vLLM/Ollama; GPU-plane
embedder is opt-in and fails closed without the plane). 024B therefore builds
structure, timing, and eligibility mechanics on synthetic 384-d vectors per the
corpus spec — the recall-quality gate (≥0.95) waits on the real pipeline and is
not claimed by 024B's contract runs.

## Baseline correction (004 / 006)

"Current behavior" in the parity matrix (004) and the measured baseline (006) is the
**ingestion-cache-backed search path (Lucene + ingestion-cache)** — explicitly not a
"current Cassandra search", which does not exist. Proposal §13/§16 amended with this
label; 006 measures the ingestion-cache path for search legs and the Cassandra
manifest/chunk path for persistence legs.
