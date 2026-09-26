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

## Baseline correction (004 / 006)

"Current behavior" in the parity matrix (004) and the measured baseline (006) is the
**ingestion-cache-backed search path (Lucene + ingestion-cache)** — explicitly not a
"current Cassandra search", which does not exist. Proposal §13/§16 amended with this
label; 006 measures the ingestion-cache path for search legs and the Cassandra
manifest/chunk path for persistence legs.
