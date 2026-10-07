# Vector License Matrix (VEC-B0.1) — DRAFT, unlanded scratch

> **Status: DRAFT — verify reads pending; header to be removed before merge.**
> Staged 2026-10-01 to de-risk the only unlanded artifact in the
> Benchmark Runner + Vector Engine chain. Verify flags must clear
> (LICENSE-file reads) before VEC-B0.1 exits in-progress.
> Evidence target: `vector-license-matrix.md` per
> [track-b-vector-engine.md](./track-b-vector-engine.md) VEC-B0.1.
> Partial-restriction handling: TBD — if a LICENSE read shows mixed terms
> (e.g., TSL on some modules only), the rule for partial instantiation gets
> written here before merge, not improvised at push time.

## Purpose

Non-functional license comparison for all vector engines under consideration. Feeds the
per-context profiles (VEC-B0.4) and the deployment-mode filter (VEC-B0.2). This is a decision
input, not legal advice — every cell requires legal review before it's used to disqualify an
engine in a production context.

## Column schema

| Column | Definition |
|---|---|
| Engine | Canonical name |
| Type | Integrated / external service / embedded library |
| License | SPDX identifier |
| Copyleft scope | Permissive / weak copyleft / strong copyleft / non-OSI. Permissive = OSI-approved with no copyleft obligation on redistribution (MIT, BSD-family, Apache-2.0, PostgreSQL License — the last is MIT/BSD-close, grouped here by obligation, not by text) |
| Commercial restriction | None / managed-service ban / network-triggered copyleft / proprietary |
| Managed offering | Vendor and cloud (if any) |
| Confidence | High (public, unambiguous) / Medium (public, interpretive) / Legal (requires sign-off) |
| Source | URL or document reference |

## Primary engines — full evaluation

| Engine | Type | License | Copyleft scope | Commercial restriction | Managed offering | Confidence | Source |
|---|---|---|---|---|---|---|---|
| Apache Cassandra | Integrated DB | Apache-2.0 | Permissive | None | DataStax Astra, AWS Keyspaces | High | apache.org/licenses |
| PostgreSQL | Integrated DB | PostgreSQL | Permissive | None | RDS, Cloud SQL, Azure DB, Supabase | High | postgresql.org/about/licence |
| YDB | Integrated DB | Apache-2.0 | Permissive | None | YDB Managed (Yandex Cloud) | High | github.com/ydb-platform/ydb |
| Apache Lucene | Embedded library | Apache-2.0 | Permissive | None | N/A (library) | High | lucene.apache.org |
| Milvus | External service | Apache-2.0 | Permissive | None | Zilliz Cloud | High | github.com/milvus-io/milvus |
| Qdrant | External service | Apache-2.0 | Permissive | None | Qdrant Cloud | High | github.com/qdrant/qdrant |

Note: Milvus is distributed under Apache-2.0, but its managed offering (Zilliz Cloud) is
proprietary. The license column refers to the OSS software; the managed column is a commercial
offering, not a code-license concern.

## PostgreSQL extensions — full evaluation

| Extension | License | Copyleft scope | Commercial restriction | Confidence | Source |
|---|---|---|---|---|---|
| pgvector | PostgreSQL | Permissive | None | High | github.com/pgvector/pgvector |
| pgvectorscale | PostgreSQL — read 2026-10-01, LICENSE @ main (© Tiger Data). TSL-lineage hypothesis REFUTED for the current tree. | Permissive | None | High (resolved 2026-10-01) | github.com/timescale/pgvectorscale |
| pgvecto.rs | Apache-2.0 — read 2026-10-01, LICENSE @ main matches. | Permissive | None | High (resolved 2026-10-01) | github.com/tensorchord/pgvecto.rs |

## Adjacent engines — summary cards

| Engine | Type | License | Copyleft scope | Commercial restriction | Confidence | Source |
|---|---|---|---|---|---|---|
| Weaviate | External service | BSD-3-Clause | Permissive | None | High | github.com/weaviate/weaviate |
| Vespa | External service | Apache-2.0 | Permissive | None | High | github.com/vespa-engine/vespa |
| OpenSearch | External service | Apache-2.0 | Permissive | None | High | github.com/opensearch-project |
| Elasticsearch | External service | SSPL OR Elastic-2.0 OR AGPL-3.0 | Non-OSI (SSPL/ELv2); strong copyleft (AGPL) | Managed-service ban (SSPL/ELv2); network-triggered copyleft (AGPL) | Legal | elastic.co/pricing/faq/licensing |
| Pinecone | Managed service | Proprietary | N/A | Proprietary | High | pinecone.io/legal |
| Chroma | Service / library | Apache-2.0 | Permissive | None | High | github.com/chroma-core/chroma |
| LanceDB | Embedded library | Apache-2.0 | Permissive | None | High | github.com/lancedb/lancedb |
| FAISS | Embedded library | MIT | Permissive | None | High | github.com/facebookresearch/faiss |
| hnswlib | Embedded library | Apache-2.0 | Permissive | None | High | github.com/nmslib/hnswlib |
| sqlite-vec | SQLite extension | MIT OR Apache-2.0 | Permissive | None | High | github.com/asg017/sqlite-vec |
| Tantivy | Embedded library (Rust) | MIT | Permissive | None | High | github.com/quickwit-oss/tantivy |
| Quickwit | Apache-2.0 — read 2026-10-01, LICENSE @ main matches (© 2021-Present Datadog, Inc.: Apache held post-acquisition). | Permissive | None | High (resolved 2026-10-01) | github.com/quickwit-oss/quickwit |

## Tantivy / Quickwit integration model (per Rust-as-service decision, c0e6ef5)

| | Tantivy | Quickwit |
|---|---|---|
| Runtime integration | External service (sidecar) | External service (distributed) |
| JNI | Excluded | N/A |
| Runtime integration column value | "External service — Rust binary, HTTP/gRPC" | "External service — REST API, ES-compatible" |

## The Elasticsearch cell — the one that needs legal

The only adjacent engine where the license cell is genuinely a decision-gate, not a footnote:

- SSPL: not OSI-approved; requires releasing source of the entire stack offering Elasticsearch
  as a service.
- ELv2: not open source by OSI definition; prohibits providing as a managed service,
  circumventing license keys, removing notices.
- AGPL-3.0 (added Aug 2024): strong copyleft; network use triggers source-distribution obligation.

Elastic's strategy is effectively "pick your copyleft." For a self-hosted enterprise that
doesn't offer the software as a service, any of the three is typically workable. For a
managed-service provider or a SaaS, SSPL and ELv2 are effectively excluded.

Verify before use: any context profile that includes Elasticsearch must name which license
interpretation was assumed, and it must be signed off by legal.

## Legal review checklist

| Item | Owner | Status |
|---|---|---|
| Confirm SPDX identifiers match the LICENSE files | Legal | ⬜ |
| Confirm no CLA / patent clause changes between "latest" and pinned versions | Legal | ⬜ |
| Confirm SSPL/ELv2/AGPL interpretations for the intended deployment contexts | Legal | ⬜ |
| Confirm managed-vendor licensing for Milvus/Qdrant/Weaviate managed offerings | Legal | ⬜ |
| Confirm no dependency-level copyleft that changes the analysis (e.g., a GPL-licensed plugin) | Legal | ⬜ |

## Confidence distribution

| Confidence | Meaning |
|---|---|
| High | Public, unambiguous; no legal interpretation needed (bulk of rows) |
| Medium | Cleared 2026-10-01 — all three verify-pending rows resolved High on LICENSE reads |
| Legal | Elasticsearch; managed-offering terms; dependency copyleft scan |
| ~~Verified-restricted~~ | Retired 2026-10-01 — sole instantiator candidate (pgvectorscale→TSL) refuted by the LICENSE read; a category justified by one failed hypothesis is not needed. Re-create if a future read lands restricted. |

Read scope — type, not stability. The three 2026-10-01 Highs certify license *type* at
HEAD-of-main. Stability is a separate axis: Tiger Data is a rebrand, Datadog an acquisition,
and the canonical relicense pattern is acquisition-then-relicense. Stability is tracked via
version-pinning (Phase 0.5 refinement below), not by these Highs — a future reader must not
read them as settled answers on both axes.

## References

- SPDX License List — canonical SPDX identifiers.
- Individual project LICENSE files (linked per row).
- Vendor licensing pages (Elastic, DataStax, Zilliz, Qdrant) for managed offerings.

## Deliberately not in this draft

- Version pinning. Licenses change between versions (e.g., Elasticsearch 7.10.2 → 7.11 SSPL
  switch). The matrix should eventually be version-scoped. Phase 0.5 refinement, not blocking.
- Dependency-level license scan. Top-level license doesn't catch copyleft in transitive graphs
  (licensee/scancode run per release artifact). Deferred to legal review.
