# ADR-013: Vector Engine Selection per Deployment Context

**Status:** Proposed (requires architecture + legal review; see framework §4 F-4/F-5)
**Date:** 2026-10-10
**Deciders:** TBD — architecture review pending
**Framework:** [vector-engine-selection.md](../vector-engine-selection.md) (evidence, matrices, follow-ons F-1–F-8)

## Context

The platform supports five deployment contexts (cloud-managed, cloud-self-hosted,
on-prem, docker, embedded), each with different license, operations, and footprint
constraints. VEC-B0 established non-functional fit per engine; VEC-B5 measured
functional cells for six of eleven engines (index-correctness recall 1.0,
small-scale latencies) with the rest explicitly `unmeasured`. This record turns
the framework's per-context recommendations into decisions with owners, dates,
rationale, and revisit triggers — per DOC-D3.2 acceptance.

Non-negotiables applying to every row: Elasticsearch excluded from
managed-service contexts (license gate, legal sign-off required); cross-runtime
integrations are services, not JNI; benchmark claims are BM25-only scope until
dense legs run on real embeddings.

## Decision

| Context | Recommended | Alternative | Rationale | Revisit trigger |
|---|---|---|---|---|
| Cloud-managed | PostgreSQL + pgvector | Qdrant Cloud | Permissive license, all-three-cloud managed, in-process SQL; PG lexical measured (recall 1.0) | HNSW figure unpublished — revisit if capacity planning needs hard numbers (F-7) |
| Cloud-self-hosted | Qdrant | PG + pgvector | Correctness + latency measured; simplest ops in class; no object-store requirement | Any `unmeasured` cell getting measured; legal restriction on a universe engine |
| On-prem | Qdrant | PG + pgvector | Same evidence + air-gap suitability | Same as above |
| Docker | Qdrant | pgvector image | Single container, no sidecars | Same as above |
| Embedded | Lucene standalone | Tantivy sidecar (non-JVM) | Correctness measured; only production-embeddable option | Merge-time heap-spike mitigation; Tantivy sidecar evaluation |

Each recommendation is justified by non-functional fit first, with measured
cells as supporting — not deciding — evidence. Cells marked `unmeasured` in
the framework are not claimed here.

## Consequences

- What becomes easier: per-context defaults are citable in deployment and
  composition PRs; `validateComposition` already enforces the namespace rules.
- What becomes harder: changing a default requires reopening this ADR with new
  measurements, not a code-review side effect.
- Explicitly not decided: dense-leg quality ranking, scale behaviour, hybrid
  crossover, metadata-store and integration effects — all deferred to F-1–F-3
  with real embeddings and hybrid runs.
