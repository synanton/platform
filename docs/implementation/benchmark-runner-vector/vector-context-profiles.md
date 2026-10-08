# Vector Context Profiles

**Task:** VEC-B0.4 — Per-context profiles (old B0.7)
**Track:** Track B — SYN-VECTOR-001
**Status:** Draft for review
**Depends on:** VEC-B0.2 (deployment modes — landed), VEC-B0.3 (hardware profiles — landed)
**Evidence path:** `docs/implementation/benchmark-runner-vector/vector-context-profiles.md`
**Last updated:** 2026-10-08

---

## §1. Methodology and inputs

### §1a. Engine universe

11 engines, identical to VEC-B0.2 and VEC-B0.3.

PostgreSQL + pgvector · YDB · Cassandra + Lucene · Milvus · Qdrant · Tantivy ·
Lucene (standalone) · Weaviate · Vespa · OpenSearch · Quickwit

### §1b. Inputs

| Input | Source | Used for |
|---|---|---|
| Deployment matrix | VEC-B0.2 §2 | Viable/disqualified per context |
| Embedded-edge cases | VEC-B0.2 §4 | Embedded context scoping |
| Hardware profiles | VEC-B0.3 §3 | Trade-off reasoning (memory, overhead type) |
| Scalability | VEC-B0.3 §4 | Trade-off reasoning (horizontal model) |
| Operations | VEC-B0.3 §5 | Trade-off reasoning (dependencies, ops load) |
| License matrix | VEC-B0.1 | Disqualification axis (managed-service restrictions) |

No new vendor reads were performed for this artifact. All content is derived from
the landed B0.1–B0.3 set.

### §1c. Default discipline

Each context names exactly one recommended default. The default is not
conditional and not hedged. Trade-offs — which necessarily name alternatives —
are a separate required field, not a hedge on the default.

### §1d. Disqualification rules

| Context class | Rule |
|---|---|
| Cloud-managed | Managed offering on ≥2 of AWS / GCP / Azure (the three clouds B0.2 §3 covers) |
| Cloud-self-hosted, on-prem, docker | VEC-B0.2 §2 deployment-matrix value ✅ (not ⚠️, not ❌) |
| Embedded | Production in-process capability (VEC-B0.2 §2 ✅, not ⚠️ dev-only) |
| License axis (all contexts) | No engine in the 11-engine universe carries a managed-service restriction per VEC-B0.1 |

**License-axis finding.** The only license-gated engine in VEC-B0.1 is
Elasticsearch (SSPL/ELv2/AGPL), which sits in the adjacent tier and outside
this artifact's 11-engine universe. Within the universe, no engine is
license-disqualified from any context. The axis is a check that passes, not an
empty field. If the B0.1 matrix is extended with a universe engine carrying a
restriction, every context's disqualification set must be re-run.

---

## §2. Context profiles

### §2.1 Cloud-managed

Buying a managed service; infrastructure is the vendor's.

**Disqualified**

| Engine | Reason |
|---|---|
| YDB | Managed only in Yandex Cloud (0/3) |
| OpenSearch | Managed only on AWS (1/3) |
| Cassandra + Lucene | No managed combined engine (managed Cassandra exists; Lucene is not part of it) |
| Tantivy | No managed offering (library, N/A) |
| Lucene (standalone) | No managed Lucene product; available only via search services built on Lucene |
| Quickwit | No first-party managed service |

**Viable:** PostgreSQL + pgvector · Milvus · Qdrant · Weaviate · Vespa

**Recommended default:** **PostgreSQL + pgvector**

Available on all three clouds (Amazon RDS/Aurora, Cloud SQL/AlloyDB, Azure
Database for PostgreSQL), lowest adoption cost of any managed vector option, and
the only choice that reuses an existing relational stack. In a buy-the-service
context, integration and maturity outweigh purpose-built specialization.

**Trade-offs**

- **Qdrant Cloud** is the strongest purpose-built alternative: Apache-2.0, managed on all three clouds, simplest ops of the purpose-built set. Adds a new system rather than extending one you have.
- **Milvus / Zilliz** is the purpose-built option for large-scale deployments, but carries a heavier operational model (B0.3 §5).
- **Weaviate Cloud** offers BYOC into the customer's own cloud account — relevant if data-residency rules prevent full SaaS.
- **Vespa Cloud** has the strongest scalability story in the set (B0.3 §4) but covers AWS and GCP only; Azure users are blocked.
- pgvector's HNSW memory figure is unpublished (B0.3 §8a) — capacity planning carries more uncertainty than for engines with published formulas.

---

### §2.2 Cloud self-hosted

Running the engine on your own cloud infrastructure.

**Disqualified:** none.

**Viable:** all 11.

**Recommended default:** **Qdrant**

Single binary, no external dependencies (B0.3 §5), Apache-2.0. For a self-operated
deployment, the simplest system that meets the requirement wins, and Qdrant is
the simplest purpose-built engine in the universe.

**Trade-offs**

- **PostgreSQL + pgvector** if the platform already runs Postgres — consolidates the stack at the cost of a less specialized engine.
- **Milvus** requires etcd, MinIO, and Pulsar (or woodpecker) as additional systems to operate (B0.3 §5).
- **Vespa** and **OpenSearch** are self-contained but carry heavier operational footprints (B0.3 §5).
- **Quickwit** requires a PostgreSQL metastore and object storage (B0.3 §5).
- **Lucene (standalone)** and **Tantivy** are libraries, not services — viable only as components of a host application.

---

### §2.3 On-prem

Running the engine on your own infrastructure, potentially air-gapped.

**Disqualified:** none.

**Viable:** all 11.

**Recommended default:** **Qdrant**

Same reasoning as cloud self-hosted, plus air-gap suitability: no external
service dependencies, no object-storage requirement, single binary.

**Trade-offs**

- **PostgreSQL + pgvector** if Postgres is already present on-prem — same consolidation argument.
- **Milvus**'s external dependencies (etcd/MinIO/Pulsar) complicate air-gapped deployment.
- **Quickwit** requires object storage; MinIO can substitute on-prem, at the cost of another system.
- **Vespa** and **OpenSearch** are self-contained but heavier to operate air-gapped.
- **Lucene (standalone)** is a strong on-prem embedded option if the host application can supply it (see §2.5).

---

### §2.4 Docker

Containerized deployment, dev/test or production.

**Disqualified:** none.

**Viable:** all 11.

**Recommended default:** **Qdrant**

Single container, no sidecars, no dependencies. The Docker context rewards
minimal dependency graphs, and Qdrant is the only purpose-built engine in the
universe that ships as a single container.

**Trade-offs**

- **PostgreSQL + pgvector** via the `pgvector/pgvector` image — familiar and well-documented.
- **Milvus** standalone via docker-compose exists but includes etcd and MinIO containers.
- **Qdrant Local** (dev-local, in-memory) is available for ephemeral testing but is dev-only (B0.2 §4) — not a production default.
- For production containerized deployments, the on-prem reasoning applies unchanged.

---

### §2.5 Embedded

In-process within a host application.

**Disqualified**

| Engine | Reason |
|---|---|
| PostgreSQL + pgvector | No in-process embedding; server process |
| YDB | No in-process embedding; distributed process |
| Cassandra + Lucene | No in-process embedding; server process |
| Milvus | No in-process embedding; distributed process |
| Vespa | No in-process embedding; distributed process |
| OpenSearch | No in-process embedding; server process |
| Quickwit | No in-process embedding; distributed process |

**Viable (production):** Lucene (standalone)

**Viable (dev/test only):** Tantivy (sidecar, not in-process) · Qdrant (dev-local) · Weaviate (embedded dev mode)

**Recommended default:** **Lucene (standalone)**

The only production-embedded engine in the universe. JVM library, runs
in-process in the same JVM, Apache-2.0. In an embedded context there is no
alternative that meets the requirement.

**Trade-offs**

- **Tantivy** is the only non-JVM embedded-class option, but it is a Rust binary run as a sidecar — not in-process (B0.2 §4). Legitimate for non-JVM hosts that can accept a sidecar.
- **Qdrant Local** and **Weaviate Embedded** are dev/test modes only (B0.2 §4) — not production-embedded.
- **No dense-vector production-embedded engine exists for non-JVM hosts.** This is a structural gap in the universe, not a gap in the reads. Flagged in §4.
- Lucene's merge-time heap spikes (B0.3 §5) are the principal operational risk in embedded deployments.

---

## §3. Cross-context summary

| Context | Default | Viable | Disqualified |
|---|---|---|---|
| Cloud-managed | PostgreSQL + pgvector | 5 | 6 |
| Cloud-self-hosted | Qdrant | 11 | 0 |
| On-prem | Qdrant | 11 | 0 |
| Docker | Qdrant | 11 | 0 |
| Embedded | Lucene (standalone) | 1 (+3 dev-only) | 7 |

**Findings**

- **Qdrant is the cross-context default for self-operated server deployment** — three of five contexts.
- **The embedded context is the most constrained** — 1 production-viable engine out of 11.
- **Cloud-managed is the second-most constrained** — 5 of 11 viable, driven by managed-offering availability, not by any technical limitation.
- **The two managed/self-managed defaults differ on purpose**: cloud-managed optimizes for integration (pgvector); self-managed optimizes for operational simplicity (Qdrant). The split is intentional, not an inconsistency.
- **No license-based disqualification exists within the universe** (§1d). The disqualification axis is deployment-driven, not license-driven.

---

## §4. Gaps

| Gap | Basis | Phase-5 action |
|---|---|---|
| Embedded default is JVM-only | No dense-vector production-embedded engine exists for non-JVM hosts (§2.5) | Track universe expansion; re-run §2.5 if a Rust/Go production-embedded engine is added |
| pgvector HNSW memory unpublished | B0.3 §8a | Affects cloud-managed capacity planning; measurement candidate |
| §8b follow-up reads | B0.3 §8b | Could shift trade-off reasoning if unread pages reveal operational differences |
| Cloud-managed threshold (≥2 of 3 clouds) | §1d rule | If the platform standardizes on a single cloud, the disqualification set narrows; re-run §2.1 |

---

## §5. Acceptance check

- [x] Five contexts named (§2.1–§2.5)
- [x] Every context names a default — pgvector, Qdrant ×3, Lucene (§3)
- [x] Disqualified engines listed per context (§2.1, §2.5; §2.2–§2.4 empty by rule)
- [x] Viable engines listed per context
- [x] Trade-offs stated per context
- [x] Evidence path matches VEC-B0.4 requirement

Gate B0 status: B0.1–B0.4 landed; remaining B0 items tracked in
benchmark-tracker.md.
