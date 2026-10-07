# Vector Deployment Modes

**Task:** VEC-B0.2 — Deployment-mode + managed-availability matrix
**Track:** Track B — SYN-VECTOR-001
**Status:** Draft for review
**Depends on:** VEC-B0.1 (license matrix — landed)
**Evidence path:** `docs/implementation/benchmark-runner-vector/vector-deployment-modes.md`
**Last updated:** 2026-10-07

---

## §1. Runtime integration model

Engine universe: the 11 engines below are this draft's declared universe. The card
does not enumerate a closed list, so "every cell" is relative to this declaration.
Divergence from the VEC-B0.1 license matrix's engine set is recorded in §5a, not
resolved silently.

Every engine carries a runtime-integration value and a process model.
"External service" is a first-class value, not a fallback.

| Engine | Runtime integration | Process model |
|---|---|---|
| PostgreSQL + pgvector | In-process (SQL extension) | Same host as PG |
| YDB | In-process (native index) | Same host as YDB |
| Cassandra + Lucene | In-process (JVM library) | Same host as Cassandra |
| Milvus | External service | Separate process/cluster |
| Qdrant | External service | Separate process/cluster |
| Tantivy | External service | Sidecar or Quickwit |
| Lucene (standalone) | In-process (JVM library) | Same host |
| Weaviate | External service | Separate process/cluster |
| Vespa | External service | Separate process/cluster |
| OpenSearch | External service | Separate process/cluster |
| Quickwit | External service | Separate process/cluster |

Source: plan seed, 2026-10-01.

---

## §2. Deployment-mode matrix (engine × context)

Legend:
- ✅ — supported natively
- ⚠️ — supported with caveat (see Notes column)
- ❌ — not supported
- N/A — not applicable (library with no managed offering)

| Engine | Cloud-managed | Cloud self-hosted | On-prem | Docker | Embedded |
|---|---|---|---|---|---|
| PostgreSQL + pgvector | ✅ | ✅ | ✅ | ✅ | ❌ |
| YDB | ✅ (Yandex Cloud only) | ✅ | ✅ | ✅ | ❌ |
| Cassandra + Lucene | ⚠️ (Cassandra managed; Lucene not) | ✅ | ✅ | ✅ | ❌ |
| Milvus | ✅ | ✅ | ✅ | ✅ | ❌ |
| Qdrant | ✅ | ✅ | ✅ | ✅ | ⚠️ (dev-local only) |
| Tantivy | N/A | ✅ | ✅ | ✅ | ⚠️ (Rust binary, not JVM-embedded) |
| Lucene (standalone) | ⚠️ (via managed search services) | ✅ | ✅ | ✅ | ✅ (JVM library) |
| Weaviate | ✅ | ✅ | ✅ | ✅ | ⚠️ (embedded dev mode) |
| Vespa | ✅ | ✅ | ✅ | ✅ | ❌ |
| OpenSearch | ✅ | ✅ | ✅ | ✅ | ❌ |
| Quickwit | ⚠️ (via managed Rust services) | ✅ | ✅ | ✅ | ❌ |

### Notes per caveat

- **YDB cloud-managed** — managed only via Yandex Managed Service for YDB in Yandex Cloud. No AWS/GCP/Azure managed offering.
- **Cassandra + Lucene cloud-managed** — managed Cassandra exists (Amazon Keyspaces, DataStax Astra), but the Lucene side is in-process and not part of any managed Cassandra offering. Combined engine is therefore caveated.
- **Qdrant embedded** — Qdrant Local is a client-local in-memory mode for development/testing, not a production-embedded engine.
- **Tantivy embedded** — Tantivy is a Rust library. "Embedded" means a co-located Rust binary alongside the JVM service, not in-process JVM embedding. The distinction is stated, not implied.
- **Lucene standalone cloud-managed** — no managed "Lucene" product; availability is via managed search services built on Lucene (Amazon OpenSearch Service, Elastic Cloud).
- **Weaviate embedded** — Weaviate Embedded exists for Python development/testing; production runs as a service.
- **Quickwit cloud-managed** — no first-party managed Quickwit service; caveated as "via managed Rust services" per plan seed.

---

## §3. Managed-availability matrix (engine × cloud)

Every cell names the managed offering, or states why none exists.
"No managed offering" is a filled cell, not a blank.

| Engine | AWS | GCP | Azure |
|---|---|---|---|
| PostgreSQL + pgvector | Amazon RDS / Aurora | Cloud SQL / AlloyDB | Azure Database for PostgreSQL |
| YDB | ❌ (Yandex Cloud only) | ❌ | ❌ |
| Cassandra + Lucene | ⚠️ Keyspaces (Cassandra; no Lucene) | ⚠️ DataStax Astra (Cassandra; no Lucene) | ⚠️ DataStax Astra (Cassandra; no Lucene) |
| Milvus | Zilliz Cloud | Zilliz Cloud | Zilliz Cloud |
| Qdrant | Qdrant Managed Cloud | Qdrant Managed Cloud | Qdrant Managed Cloud |
| Tantivy | N/A | N/A | N/A |
| Lucene (standalone) | ⚠️ via Amazon OpenSearch Service | ⚠️ via Elastic Cloud | ⚠️ via Elastic Cloud |
| Weaviate | Weaviate Cloud (also BYOC) | Weaviate Cloud (also BYOC) | Weaviate Cloud (also BYOC) |
| Vespa | Vespa Cloud | Vespa Cloud | ❌ (Vespa Cloud is AWS/GCP only) |
| OpenSearch | Amazon OpenSearch Service | ❌ (self-host) | ❌ (self-host) |
| Quickwit | ⚠️ (no first-party managed service) | ⚠️ (no first-party managed service) | ⚠️ (no first-party managed service) |

### Cloud-specific notes

- **PostgreSQL + pgvector** — pgvector ships on every major managed Postgres: Amazon RDS/Aurora, Google Cloud SQL/AlloyDB, Azure Database for PostgreSQL.
- **Milvus / Zilliz Cloud** — available on AWS, GCP, and Azure; Azure coverage was added later and includes Central India as of 2025.
- **Qdrant Managed Cloud** — natively on AWS, GCP, and Azure; Hybrid Cloud for own infrastructure.
- **Weaviate Cloud** — SaaS on AWS, GCP, and Azure, with BYOC into the customer's own cloud account.
- **Vespa Cloud** — status page lists AWS and GCP as operational; no Azure zone listed.
- **OpenSearch** — Amazon OpenSearch Service is the managed offering; it is AWS-native and not available as a managed service on GCP or Azure. GCP/Azure deployments are self-hosted clusters.
- **YDB** — no cloud-managed offering outside Yandex Cloud.
- **Tantivy** — library only; no managed offering on any cloud. N/A is the correct filled value.
- **Cassandra + Lucene** — managed Cassandra on all three clouds (Keyspaces on AWS, DataStax Astra on GCP/Azure), but Lucene is not part of any of those offerings. The combined engine's cloud-managed column is therefore ⚠️, not ✅.

---

## §4. Embedded-edge cases (explicit, not implied)

| Engine | Embedded status | What "embedded" means here |
|---|---|---|
| Lucene (standalone) | ✅ | JVM library; runs in-process in the same JVM |
| Tantivy | ⚠️ | Rust binary co-located with the service; not JVM-embedded |
| Qdrant | ⚠️ | Client-local in-memory mode for dev/test; not production |
| Weaviate | ⚠️ | Python embedded mode for dev/test; not production |
| All others | ❌ | Server or distributed process; no in-process embedding |

The Tantivy/Qdrant/Weaviate caveats are the three cases where "embedded" is real but scoped. Stating the scope prevents the matrix from implying production-embedded support where only dev-local exists.

---

## §5. Universe divergence, gaps, and Phase-5 measurement candidates

### §5a. Divergence from VEC-B0.1 license matrix

The license matrix (VEC-B0.1) covers 21 engines across three tiers: primary (6),
PG extensions (3), adjacent (12). This draft covers 11. The gap is a scope
difference between license review (broad, informs selection) and deployment-mode
review (narrow, to engines under active benchmarking). Each divergence category
is recorded here rather than resolved silently.

| Divergence category | Matrix entries | Draft treatment | Decision |
|---|---|---|---|
| Combined rows | Apache Cassandra, PostgreSQL, Apache Lucene, pgvector | Combined as "Cassandra + Lucene" and "PostgreSQL + pgvector" | Retained — deployment profile identical for paired components |
| Quickwit | Listed standalone | Split from Tantivy seed row into standalone row | Accepted — distinct deployment profile |
| PG extensions | pgvectorscale, pgvecto.rs | Excluded | Excluded — deployment mode follows pgvector; no independent profile |
| Adjacent engines | Elasticsearch, Pinecone, Chroma, LanceDB, FAISS, hnswlib, sqlite-vec | Excluded | Excluded — not under active benchmarking; license status only (VEC-B0.1) |

### §5b. Phase-5 measurement candidates

Filled cells that are sourced rather than measured:

| Cell | Current basis | Phase-5 action |
|---|---|---|
| Vespa Azure | Vendor status page (AWS/GCP only) | Re-check quarterly; Azure zone may be added |
| Quickwit managed | Plan seed ("via managed Rust services") | Identify whether Datadog offers a managed Quickwit path |
| Cassandra + Lucene managed | Inference from managed-Cassandra offerings | Confirm no managed offering bundles Lucene |
| Tantivy managed | N/A by construction | Confirm no managed Tantivy product emerges |

---

## §6. Acceptance check

- [x] Every engine × context cell filled (§2)
- [x] Every engine × cloud cell filled (§3)
- [x] Runtime-integration model stated for every engine in the declared universe (§1)
- [x] Embedded caveats scoped explicitly (§4)
- [x] Universe divergence from VEC-B0.1 recorded (§5a)
- [x] Gaps named as Phase-5 candidates (§5b)
- [x] Evidence path matches VEC-B0.2 requirement

Gate B0 remains open pending VEC-B0.3 (hardware + scalability + ops complexity).
