# Design Proposal: Evaluate PostgreSQL as a Third Synvault/Synquest Persistence Backend

**Status:** Draft for Architecture Review (Revision 4 — 2026-09-27; closes coordination items: joint Phase 6, Gate B as comparator-validity gate, convergence-config owner)
**Governing document:** Synanton Platform Architecture 1.0 (Approved — capstone)
**Scope:** Persistence and retrieval port adapter for Knowledge 1.25 and Search 1.31, third candidate
**Candidate:** PostgreSQL 16+ with `pgvector`; extensions pinned in Phase 0
**Related platform designs:** 1.22, 1.23, 1.25, 1.26, 1.27, 1.28, 1.29, 1.31, 1.32, 1.34
**Sibling proposals:** YDB PoC (PR #51, approved for merge); Cassandra (current default)
**Non-goals:** Replacing MinIO object storage, replacing the graph engine, replacing Cassandra as default without PoC results, redefining ownership or semantics established by the designs above.

------

## 0. Architectural Dependency

This proposal is subordinate to Platform Architecture 1.0. It introduces **no new ports, no new planes, no new semantics**. It proposes one additional adapter behind the already-approved `SynvaultStore`, `SynquestEngine`, `SynquestIndexWriter`, and `SynquestIndexAdmin` ports.

Where this proposal concerns security, eventing, canonical knowledge, source versioning, temporal semantics, or search behavior, the corresponding normative platform design governs. Conflicts resolve in favor of the approved designs.

### 0.1 Sequencing constraint (Architecture 1.0 §14–15)

The same §14–15 gate that applies to the YDB PoC applies here: `1.27` (Eventing) and `1.32` (Operation/error contracts) are unfrozen, so the Postgres adapter is **throwaway-scoped** unless the gate flips. This proposal does not pre-commit domain APIs.

### 0.2 Relationship to the YDB PoC

The YDB PoC is a parallel evaluation. This proposal does not replace, supersede, or depend on the YDB PoC's outcome. Both run behind the same ports. At Phase 6, the decision space becomes:

| Outcome | Shape                                                  |
| ------- | ------------------------------------------------------ |
| 1       | YDB both ports                                         |
| 2       | YDB Synvault only                                      |
| 3       | YDB persistence; dedicated search                      |
| 4       | Cassandra remains                                      |
| 5       | Dedicated search backend                               |
| **6**   | **PostgreSQL both ports**                              |
| **7**   | **PostgreSQL Synvault only**                           |
| **8**   | **PostgreSQL + dedicated search**                      |
| **9**   | **Hybrid: one backend per port across all candidates** |

The port architecture was built to allow any of these.

Phase 6 is a joint decision point. It runs only when both PoCs have produced their evidence packages. The YDB PoC's Phase 6 does not close until the PostgreSQL PoC has reached its Phase 6 evidence state (or has formally collapsed per Gate A/B). Neither PoC closes the decision unilaterally.

------

## 1. Summary

PostgreSQL is proposed as a **third candidate** behind the storage/search ports. The motivation is not that Postgres is superior to YDB or Cassandra, but that:

- It has the **most mature operational story** of the three.
- It natively satisfies the **atomicity contract** (single-node ACID, no distributed complexity).
- It offers **row-level security (RLS)** — a first-class mechanism for the mandatory pre-ranking eligibility that YDB required a probe ladder to establish.
- It provides **`pgvector`** for ANN and **`tsvector`/GIN or extension-based BM25** for lexical retrieval in one system.
- The team's operational familiarity is likely higher than either alternative.

Postgres's weaknesses — horizontal scaling, ANN quality at very large scale, hybrid search as a bolted-on capability — are real and must be measured, not assumed away.

This proposal defines what to evaluate and how, not what to conclude.

------

## 2. Relationship to Platform Architecture 1.0

### 2.1 Plane mapping

| Proposal component                      | Architecture plane           | Authority                                                    |
| --------------------------------------- | ---------------------------- | ------------------------------------------------------------ |
| `PostgresSynvaultStore`                 | Knowledge 1.25 (persistence) | Same as `YdbSynvaultStore`. Does not own source-version authority. |
| `PostgresSynquestEngine`                | Search 1.31 (retrieval)      | Same as `YdbSynquestEngine`. Derived projection only.        |
| `PostgresSynquestIndexWriter` / `Admin` | Search 1.31                  | Same as YDB.                                                 |
| `PostgresSynvaultStore` publication log / relay  | Adapter-internal             | Eventing 1.27 remains authoritative. Log is the adapter-internal artifact; relay is the Eventing 1.27 consumer. |

No new planes. No redefined ownership. No competing semantics.

### 2.2 Ownership statement

`PostgresSynvaultStore` is a **persistence port adapter**, not a competing authority. Source-version authority remains with Ingestion 1.28; canonical knowledge remains owned by Knowledge 1.25; derived search projections remain owned by Search 1.31.

### 2.3 Eventing is authoritative

The Postgres adapter does not define event delivery, retry, ordering, replay, or consumer idempotency. Eventing 1.27 governs. The publication-log mechanism remains adapter-internal (same shape as the YDB adapter's).

### 2.4 Security is authoritative

Security context derives from Identity 1.29 + Security 1.23. Postgres's RLS is an **implementation mechanism**, not a source of authorization truth. Pre-ranking eligibility is enforced in the query, composed before any ranking operation.

### 2.5 Temporal semantics are authoritative

Design 1.34 governs. Postgres can express `valid_from`/`valid_to`/`published_at`/`observed_at` as native columns and range predicates. No new temporal model.

------

## 3. Motivation

### 3.1 Why a third candidate

Cassandra is `UNSUPPORTED` for the revision-atomicity contract (008). YDB demonstrates the contract but requires a probe ladder for pre-ranking eligibility and hybrid search. Postgres:

- satisfies the revision contract **without distributed transaction complexity**,
- satisfies pre-ranking eligibility via **RLS + query composition** (a mature, well-understood mechanism),
- offers **one system** for persistence and search,
- has the **lowest operational novelty** of the three.

It is not a foregone conclusion that Postgres wins. Its weaknesses at scale are real. But it belongs in the comparison.

### 3.2 What is already decided

The port architecture is approved. `SynvaultStore`, `SynquestEngine`, `SynquestIndexWriter`, `SynquestIndexAdmin` are the interfaces. The Postgres adapter is new code behind approved ports; nothing about the proposal reopens port design.

------

## 4. Goals

### 4.1 Architecture

- Add `PostgresSynvaultStore`, `PostgresSynquestEngine`, `PostgresSynquestIndexWriter`, `PostgresSynquestIndexAdmin` adapters behind existing ports.
- Reuse the storage-testkit contract suite.
- Reuse the conformance registry and provider selection.
- Do not modify the ports or the shared types.

### 4.2 Evaluation

- Transactional revision commit (single-node ACID, no distributed concerns).
- Pre-ranking eligibility via RLS + query composition.
- Lexical retrieval (BM25 or tsvector).
- Vector ANN (`pgvector` HNSW or IVFFlat).
- Hybrid retrieval (RRF; extension or custom).
- Metadata filtering at all four selectivity levels.
- Side-channel eligibility.
- Cursor pagination stability.
- Freshness (commit → search-visible).
- Scale, failure, recovery.
- Cost at target workload.

### 4.3 Scope boundary

Same as the YDB PoC: **lexical/vector/hybrid subset of Search 1.31**. Graph retrieval and temporal retrieval are out of scope, with extension points preserved.

------

## 5. Non-Goals

- Replacing Cassandra as default without PoC results.
- Reworking the ports or shared types.
- Redefining Eventing, Security, or Temporal semantics.
- Graph or temporal retrieval evaluation.
- ClickHouse replacement.
- Object storage replacement (MinIO / Content Cache 1.26).
- Full production migration before PoC results.

------

## 6. Candidate Comparison

| Criterion               | Cassandra              | YDB                                          | PostgreSQL                          |
| ----------------------- | ---------------------- | -------------------------------------------- | ----------------------------------- |
| Revision atomicity      | `UNSUPPORTED` (008)    | Distributed serializable                     | **Native ACID**                     |
| Pre-ranking eligibility | Custom                 | Requires plan-level composition (probed)     | **RLS + query predicate**           |
| Lexical search          | Ingestion-cache/Lucene | Native full-text, conjunctive behavior noted | `tsvector`/GIN or extension BM25    |
| Vector search           | Ingestion-cache/HNSW   | Native ANN                                   | `pgvector` HNSW/IVFFlat             |
| Hybrid                  | Custom                 | Native (RC disabled-by-default)              | Custom or extension                 |
| Horizontal scaling      | Native, mature         | Native, automatic                            | **Requires sharding (Citus, etc.)** |
| Operational maturity    | High                   | Newer to platform                            | **Highest**                         |
| Metadata storage        | Wide-column            | Relational                                   | **Relational + JSONB**              |
| Transaction cost model  | N/A for revisions      | Distributed                                  | Single-node                         |
| Extension ecosystem     | Limited                | Limited                                      | **Very large**                      |
| Vendor lock-in          | Low                    | Moderate                                     | **Lowest (open, portable)**         |

The comparison is not a ranking. Each row's "best" depends on what the PoC measures.

------

## 7. Proposed Architecture

### 7.1 Same ports, new adapter

```text
        SynvaultStore / SynquestEngine (unchanged)
                        |
        +---------------+---------------+
        |               |               |
    Cassandra          YDB          PostgreSQL
     adapter         adapter         adapter
```



No changes to the port interfaces, shared types, conformance registry, provider selection, or observability contract. The Postgres adapter plugs into the existing plumbing.

### 7.2 Module structure

```text
synanton-synvault-postgres
synanton-synquest-postgres
```



Same conventions: `*-api` untouched, no CQL/YQL/SQL leakage outside adapters, contract tests run against every implementation.

### 7.3 Observability

Reuses the §8.3 contract. Same metrics, same trace shape, same freshness/lag semantics. The Postgres adapter provides `ActiveProviders` entries identically to YDB.

### 7.4 Security and multi-tenancy

- **RLS policies** enforce tenant isolation at the row level, composed into every query.
- The `EligibilityConstraints` port contract is enforced by translating the validated `SecurityContext` into RLS predicates **before** any ranking.
- Service-context broadening prevention uses the same `EligibilityScope` gate established for YDB (P0-3 fix in the YDB PoC).
- Cross-tenant leakage tests cover reads, writes, search, side channels, rebuild, and replay under load.

RLS is a **mechanism**, not the source of truth. The validated context remains authoritative.

------

## 8. Postgres Implementation Sketch

### 8.1 Schema (PoC starting point)

```sql
CREATE TABLE documents (
    tenant_id    uuid NOT NULL,
    doc_id       uuid NOT NULL,
    source_uri   text,
    title        text,
    metadata     jsonb,
    storage_revision bigint NOT NULL,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, doc_id)
);

CREATE TABLE chunks (
    tenant_id    uuid NOT NULL,
    chunk_id     uuid NOT NULL,
    doc_id       uuid NOT NULL,
    ordinal      int NOT NULL,
    text         text NOT NULL,
    token_count  int NOT NULL,
    metadata     jsonb,
    embedding    vector(384),          -- dimension pinned in Phase 0
    tsv          tsvector,             -- or BM25 extension column
    PRIMARY KEY (tenant_id, chunk_id)
);

CREATE TABLE provenance (
    tenant_id      uuid NOT NULL,
    chunk_id       uuid NOT NULL,
    extractor      text,
    source_version_id uuid,
    embedding_model_ref jsonb,
    page           int,
    start_offset   int,
    end_offset     int,
    PRIMARY KEY (tenant_id, chunk_id)
);

CREATE TABLE publication_log (
    tenant_id     uuid NOT NULL,
    revision_id   uuid NOT NULL,
    payload       jsonb NOT NULL,
    created_at    timestamptz NOT NULL,
    published_at  timestamptz,
    PRIMARY KEY (tenant_id, revision_id)
);
```



RLS policy example:

```sql
ALTER TABLE chunks ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON chunks
    USING (tenant_id = current_setting('app.tenant_id')::uuid);
```



The `app.tenant_id` setting is populated from the validated `SecurityContext` at session start; the query cannot override it.

### 8.2 Index strategy

- `pgvector` HNSW index on `chunks.embedding` (or IVFFlat — both evaluated in Phase 2).
- GIN index on `chunks.tsv` (or BM25 extension index).
- B-tree on `(tenant_id, doc_id)` for chunk retrieval.
- GIN on `chunks.metadata` for metadata filters.

### 8.3 Capabilities to validate

- Revision atomicity — single-node ACID, no distributed concerns.
- Pre-ranking eligibility via RLS — **the RLS interaction with ANN is the key unknown (does the planner apply RLS before or after the vector index scan?). See the Gate 0 analogue below; it gates the engine.**
- Lexical recall vs Lucene (measure, don't assume).
- `pgvector` recall at 384-d and (if possible) 768-d.
- Hybrid retrieval using RRF (custom or extension).
- Cursor pagination stability under concurrent writes.
- Freshness (commit → visible) using the relay seam.
- Dimension-fixed-at-schema constraint.

### 8.4 Gate 0 analogue — RLS × ANN composition (load-bearing, test-first)

This is the single most consequential unknown in the Postgres candidate and it is tested **first — before the engine exists** — same rule as YDB's Gate 0 (`phase2-preflight.md`): pre-ranking eligibility is a **binary gate**, not a benchmark row. No benchmark, scale, or freshness run in Phase 2 may start until it passes.

Test: a vector query guarded by a **highly selective tenant predicate**, with `EXPLAIN` captured to verify whether RLS composes into candidate generation at plan level. Three structurally different outcomes, decided up front:

1. **RLS composes pre-ranking at plan level** → gate passes, proceed to benchmarks. Postgres matches YDB on this critical capability.
2. **RLS filters post-ANN** → adapter-side explicit predicate composition is acceptable **iff** the ranked set derives only from eligible candidates (prove it; leakage = fail). Proof is two-part, and both parts are required — this is the YDB PoC's PlanAssertions requirement translated to Postgres:
    - (a) **EXPLAIN analysis** — the eligibility predicate must appear in the same plan node as the ANN index scan, not as a post-filter over ANN results (direct analogue of YDB's `KqpTable ".../v_vec/..."` assertion proving index use, not scan fallback). Plan-shape inspection alone is insufficient if the planner output is ambiguous.
    - (b) **Adversarial leakage test** — construct queries where post-filtering would return ineligible top-K (e.g. top-K nearest neighbours are all cross-tenant) and verify the adapter returns eligible candidates instead. This proves the property behaviorally, not by inspection. (A differential check — `row_security = force` RLS-native vs explicit-predicate path set-equal on the same corpus/query — may be added as supporting evidence.)
    The benchmark's eligibility-filtered legs then measure explicit composition, not RLS-native composition — every such metric carries a mechanism suffix in its name, not in a footnote: `*_rls_native` vs `*_predicate_composed` (topology in the metric name, per the YDB PoC rule).
3. **Eligible-ID materialization + intersect** → acceptable only with the changed performance profile recorded; thresholds re-examined, not inherited — per the YDB Option B rule: when the operation is structurally different (materialize-and-intersect is not ANN-in-a-filtered-range), the gate is absolute-vs-requirement, not adapter-vs-baseline (same shape as vector-recall on synthetic vectors).

If none holds, `PostgresSynquestEngine` fails a Must requirement and the Synquest leg collapses regardless of latency. That is the correct outcome — **do not benchmark past it**. Collapse scope mirrors YDB: the PG engine leg and the 028-as-Postgres comparison collapse; 024A (Cassandra-over-ingestion-cache) and 024B (YDB) are unaffected and still produce their comparisons; Phase 6 falls back to Outcomes 1–5 / partial-Postgres (Synvault-only, or Synquest-with-external-vector).

------

## 9. Consistency Strategy

Same as the YDB proposal:

```text
commit → durable publication record → Eventing 1.27 → projection consumer → search-visible
```



Postgres's single-node ACID makes the commit side simpler than YDB's distributed transactions. The relay semantics, ordering keys, generation IDs, and tolerance requirements are unchanged — the projection is still derived, the index is still rebuildable, out-of-order events still must not regress search state.

The publication-log port remains adapter-internal, same shape as YDB.

------

## 10. PoC Plan

### Phase 0 — Interface extraction and setup

- `synvault-postgres` and `synquest-postgres` modules scaffolded.
- Postgres version, `pgvector` version, and BM25 extension choice pinned.
- Feature stability inventory for extensions (pgvector is GA; BM25 extensions vary).
- Feature-parity matrix frozen against the same baseline used for YDB.

**Reuse:** The 004 parity matrix, 005 corpus, 006 thresholds, 028 pre-flight, and 028 convergence config from the YDB PoC apply to Postgres as well — extended to a fourth leg, not replaced. No new corpus, no new thresholds.

### Phase 1 — PostgresSynvaultStore

- Schema deployed; RLS policies applied.
- Revision atomicity demonstrated (positive, negative, concurrent — same tests as YDB).
- Mandatory provenance rejection, `putDocument` metadata-only, cursor pagination.
- Contract suite green.

### Phase 2 — PostgresSynquestEngine

**Pre-Phase-2 gates (both must pass before any PG Phase 2 engine code or benchmark):**

- **Gate A (§8.4 RLS × ANN):** highly selective tenant predicate + vector query + `EXPLAIN`. Outcome 1 → proceed; Outcome 2 → explicit predicate composition with two-part leakage-proof (EXPLAIN + adversarial test); Outcome 3 → materialize-and-intersect with absolute-vs-requirement thresholds. None → PG Synquest collapses, do not benchmark past it.
- **Gate B (comparator validity: 024A convergence + PG tie-break determinism):** 028's three-legged convergence gate (baseline vs 024A, per §11) must pass before PG Phase 2 begins, **and** PG-side tie-break determinism (PG-POC-013: `pgvector` tie order + `tsvector` ranking determinism confirming `(score desc, chunkId asc)` post-retrieval) must be verified. Both are comparator validity — if tie-break is nondeterministic, the four-leg convergence gate produces noisy results, the same failure mode as an 024A convergence break. PG-vs-baseline inherits the comparator's validity: if either leg of Gate B fails, the PG leg is **blocked, not merely annotated** — benchmarks do not run until the framing is revised. This prevents building the engine and running benchmarks only to discover the comparator was broken.

Then:
- Lexical, vector, hybrid legs.
- Pre-ranking eligibility, side-channel eligibility, temporal rejection.
- Contract suite green.
- Tierce: three-tier comparison becomes four-tier (baseline, 024A Cassandra-over-ingestion-cache, 024B YDB, Postgres). The four-legged framing requires the comparator discipline in §11 — same corpus, thresholds, tie-breaking, topology annotations, selectivity levels, and per-adapter convergence scoping — before any leg runs.

### Phase 3 — Projection consistency

Same tests as YDB: freshness, replay, out-of-order, generation promotion, tenant-scoped pending.

```text
source update
    ↓
Synvault commit
    ↓
durable publication record
    ↓
Eventing 1.27
    ↓
projection consumer
    ↓
search-visible update
```

### Phase 4 — Scale, failure, and cost

Same dimensions as YDB, with one addition: **horizontal scaling test**. Where does Postgres stop scaling, and does that boundary matter for Synanton's projected workload?

### Phase 5 — PoC-scope migration tooling

Same as YDB, using the completed adapters. The migrator built for the YDB PoC should be adapter-agnostic; if it isn't, that's a finding.

### Phase 6 — Decision

Decision space extends to Outcomes 6–9 (Postgres variants). The comparison is now four-legged.

------

## 11. Benchmark Methodology

YDB's three-legged benchmark was baseline vs 024A vs 024B. Adding Postgres makes it four-legged: **baseline vs 024A (Cassandra-over-ingestion-cache) vs 024B (YDB) vs Postgres**. Before that runs, the comparator problem must be closed — **verify the comparator before comparing against it**:

- **Same corpus, same thresholds, same tie-breaking discipline.** Reuse is literal: the frozen 004 parity matrix, 005 corpus, 006 thresholds, and 028 pre-flight apply unchanged. No new corpus, no Postgres-specific thresholds; gate against the same absolutes. Tie-breaking follows the frozen deterministic rule — `(score desc, chunkId asc)` applied post-retrieval on every adapter (PG-side determinism verified under Gate B via PG-POC-013 before Phase 2); residual top-K boundary variance on exact ties is noted with tolerance, never exact-sequence comparison.
- **Same topology annotations.** Every Postgres metric carries its index scan type (HNSW vs IVFFlat, native vs fallback), whether RLS filtered pre- or post-ANN (Gate 0 outcome), payload fetch pattern (e.g. ANN + N+1 text fetch, batched where applicable), `pgvector`/BM25 extension versions, hybrid flag (custom RRF vs extension RRF), and embedding dims on both sides with bias direction. No metric without its topology.
- **Same eligibility-filtered selectivity levels.** RLS-composed eligibility runs at all four selectivity levels (0.1% / 1% / 10% / 100%), plus ANN recall under RLS at high selectivity, hybrid latency custom-RRF vs extension-RRF, and the required side-channel / rebuild-under-load / leakage-under-load legs.
- **Same convergence gate scoping — now across three comparators, not one.** The 028 convergence gate (`028-convergence-config.yaml`) extends from `legs: [baseline, 024A, 024B]` to `legs: [baseline, 024A, 024B, PG]` with tolerances fixed **before** running, never after: lexical/vector/hybrid top-K overlap ≥0.90 (fully-matching queries only on the conjunctive-FT legs), metadata and eligibility **eligible-set identity** (exact set match; leakage = hard fail, not tolerance), scores never compared across legs (spaces differ), `minScore=0` neutral on all legs. Critically, the gate scopes to **each adapter vs baseline** rather than one adapter vs baseline: 024A-vs-baseline, 024B-vs-baseline, and PG-vs-baseline each pass or fail independently. **Pre-Phase-2 rule:** the 024A-vs-baseline leg of this gate must pass before PG Phase 2 begins (Gate B above), alongside PG tie-break determinism. If 024A convergence fails, the PG leg is blocked and 028 needs a revised framing — Phase 6 must never have to distinguish "adapter overhead" from "mirror drift" after the fact.

The frozen thresholds from 006 apply. **Do not invent Postgres-specific thresholds**; gate against the same absolutes.

------

## 12. Acceptance Criteria

### Architecture

- No changes to ports, shared types, or provider selection.
- No SQL outside the Postgres adapter modules.
- Contract tests pass against Postgres alongside Cassandra, YDB, in-memory.
- Capability claims gated by conformance evidence (§9.3).

### Synvault

- Revision atomicity demonstrated (positive, negative, concurrent).
- RLS tenant isolation demonstrated.
- Cross-tenant leakage tests pass under load.
- Provenance mandatory; `putDocument` metadata-only.
- Cursor pagination stable.

### Synquest

- Lexical retrieval meets Recall@10 target.
- Vector retrieval meets Recall@10 target (or is unclaimed on synthetic vectors, same as YDB).
- Hybrid retrieval meets Recall@10 target.
- Eligibility-filtered retrieval meets latency target.
- RLS composes pre-ranking, verified against the plan (EXPLAIN) per the §8.4 Gate 0 analogue; post-ANN outcome recorded per-leg, leakage = hard fail.
- Side channels obey eligibility.
- Temporal extension point rejects cleanly.
- Tie-breaking follows the frozen deterministic rule (`score desc, chunkId asc` applied post-retrieval on every adapter; PG-side determinism verified in PG-POC-013 — `pgvector` tie order and `tsvector` ranking determinism are unverified until then); convergence gates each adapter vs baseline independently (§11); `minScore=0` neutral on all legs; dims and bias annotated on every vector number.

### Performance

Same thresholds as YDB. Postgres gates against absolutes, not against YDB.

### Cost

Same model as §16.1 of the YDB proposal, adding:

- Cost of managed Postgres at target scale.
- Cost of horizontal scaling (if reached).
- Operational overhead relative to Cassandra and YDB.

------

## 13. Risks and Mitigations

| Risk                                                 | Mitigation                                                   |
| ---------------------------------------------------- | ------------------------------------------------------------ |
| RLS does not compose pre-ranking at plan level       | Test first per §8.4 (three outcomes decided up front); Outcome 2 requires leakage-proof explicit composition, Outcome 3 re-examines thresholds; none → PG Synquest collapses, do not benchmark past it |
| Four-legged comparator drift (PG vs 024A vs 024B vs baseline) | §11 discipline: same corpus/thresholds/tie-breaking, per-leg topology annotations, per-adapter-vs-baseline convergence gate fixed before running |
| `pgvector` recall insufficient at high selectivity   | Measure at all four selectivity levels                       |
| Hybrid requires custom code                          | Budget for a custom RRF implementation; compare against extension if available |
| Horizontal scaling boundary hit sooner than expected | Measure; document the boundary; compare to Cassandra/YDB at same scale |
| BM25 extension maturity varies                       | Feature stability inventory; pin the extension; treat as production risk if preview |
| Postgres becomes "obvious default"                   | Enforce the same throwaway scope; Phase 6 decides            |
| Effort duplication with YDB PoC                      | Reuse corpus, thresholds, tests, migrator; only adapter-specific work is new |
| SQL leakage outside adapter                          | Same ArchUnit-style boundary rule as CQL/YQL                 |

------

## 14. Alternatives Considered

- **Postgres + pgvector only** — this proposal; the baseline Postgres story.
- **Postgres + external vector DB** (Qdrant, Milvus, Weaviate) — a different candidate; separate proposal if Postgres wins Synvault but loses Synquest.
- **Postgres + OpenSearch** — same shape, same reason.
- **CockroachDB, YugabyteDB** — distributed Postgres; not evaluated; different operational profile.
- **Retain Cassandra + YDB evaluation** — the current state; Postgres is additive.

**Note on Outcome 9 (hybrid: one backend per port).** Outcome 9 is not just a Phase 6 row selection — a mixed deployment (e.g. Postgres for Synvault, YDB for Synquest) is a separate candidate requiring its own evaluation: two operational stories to maintain, two failure modes, cross-backend relay/tenant-scoping questions (does one relay connect both? does the tenant-scoping agreement hold across both?), and a cost model that is not the sum of parts. If Phase 6 leans toward Outcome 9, it spins off a dedicated hybrid-evaluation track rather than closing by selection alone.

------

## 15. Decisions Requested

**Decision 1 — Approve the third-candidate track.** Approve PostgreSQL as a candidate behind the existing ports, on the same throwaway-scoped terms as the YDB PoC.

**Decision 2 — Approve reuse of YDB PoC artifacts.** The 004 parity matrix, 005 corpus, 006 thresholds, 028 pre-flight, 028 convergence config, and the schema/migration tooling from the YDB PoC are reused (convergence extended to a fourth leg, not replaced). YDB-grown infrastructure (037/038/039) is inherited via PG-POC-000. Only adapter-specific work is new.

**Decision 3 — Approve the parallel execution.** Postgres PoC runs alongside the YDB PoC's baseline-gated remainder. Neither PoC's engine outcome gates the other. Both feed Phase 6. Exception: PG Phase 2 is blocked until the two pre-Phase-2 gates pass — Gate A (§8.4 RLS × ANN) and Gate B (§11 comparator validity: 024A convergence + PG tie-break determinism). Phase 6 itself is joint: it runs only when both PoCs have produced their evidence packages, and neither PoC closes the decision unilaterally (see §0.2).

**Decision 4 — Extend Phase 6 outcomes.** Outcomes 1–5 (YDB/Cassandra variants) extended to 6–9 (Postgres variants). The decision space is now four-legged.

**Decision 5 — No production migration yet.** Production migration is blocked until all four candidates are evaluated against the frozen thresholds, with the same Decision 4 criteria applied to each.

------

## 16. Explicitly Out of Scope

- Replacing Cassandra as default before Phase 6.
- Reworking ports, shared types, or conformance machinery.
- Redefining ownership, eventing, security, or temporal semantics.
- Graph or temporal retrieval evaluation.
- ClickHouse or MinIO replacement.
- Production migration before all candidates are measured.

------

## 17. Recommendation

Approve the PostgreSQL PoC as a **third candidate** behind the approved ports, on the same throwaway-scoped terms as the YDB PoC. Reuse the YDB PoC's corpus, thresholds, contract suite, convergence config, and migration tooling. Add only the adapter-specific code and the three Postgres-specific gates:

1. **RLS composition with ANN at plan level** — the analogue of YDB's Gate 0 (§8.4). Test first, before implementing the engine, with three outcomes decided up front. If RLS filters post-ANN, the adapter composes the predicate explicitly and every eligibility-filtered number records which mechanism it measures.
2. **Horizontal scaling boundary** — measure where Postgres stops scaling and whether that boundary is inside or outside Synanton's workload.
3. **Four-legged comparator discipline (§11)** — same corpus, thresholds, and tie-breaking; same topology annotations; same selectivity levels; convergence scoped to each adapter vs baseline. State it before any code; the 028 gate extends to four legs with tolerances pinned before running.

If both gates pass, Postgres is a full candidate. If either fails, it's a partial candidate (e.g., Synvault-only, or Synquest-with-external-vector).

The decision is not which backend wins. It's which backend **survives the measurement** — and the port architecture ensures any of the three can be chosen without domain redesign.

------

## 18. Ticket Sketch (for Phase 0 planning)

Extends the YDB ticket plan with a parallel `PG-POC-` series. Corpus, thresholds, contract tests, and migrator are reused; YDB-grown infrastructure is inherited, not rebuilt. Full tracker with phases, gates, and traceability: `docs/implementation/pg-poc-implementation-plan.md`.

| Ticket     | Scope                                                                 |
| ---------- | --------------------------------------------------------------------- |
| PG-POC-000 | Inherit YDB infrastructure as prerequisites: 037 shared port-types home (`storage-contract`), 038 observability contract, 039 provider selection + startup validation (extended for PG). 040 call-site rewire is independent — PG does not affect it. |
| PG-POC-001 | Pin Postgres + pgvector + BM25 extension versions                     |
| PG-POC-002 | Feature stability inventory (extensions)                              |
| PG-POC-003 | Schema deployment + RLS policies (DDL in one place per backend)       |
| PG-POC-004 | `PostgresSynvaultStore` (atomic revisions, RLS)                       |
| PG-POC-005 | Gate A — RLS + ANN plan composition (§8.4; EXPLAIN + adversarial proof) |
| PG-POC-006 | Gate B — comparator-validity gate before PG Phase 2 (§11): 024A convergence verification **plus** PG tie-break determinism (PG-POC-013 input); owns extending `028-convergence-config.yaml` from 3 legs to 4 with tolerances pinned before running |
| PG-POC-007 | `PostgresSynquestEngine` (lexical/vector/hybrid)                      |
| PG-POC-008 | Eligibility / side channels / temporal                                |
| PG-POC-009 | Projection consistency                                                |
| PG-POC-010 | Scale, failure, cost (incl. horizontal boundary)                      |
| PG-POC-011 | Migration tooling (reuse YDB migrator)                                |
| PG-POC-012 | Lifecycle / quota discipline from day one: per-backend quota, synchronous teardown, schema-inventory test (YDB closeout discipline; prevents pool/connection exhaustion rediscovery) |
| PG-POC-013 | Tie-break verification: `pgvector` tie order + `tsvector` ranking determinism; confirm `(score desc, chunkId asc)` post-retrieval holds on PG (same class as YDB VIEW-sort finding) |
| PG-POC-014 | Four-legged benchmark run (baseline vs 024A vs 024B vs PG)            |
| PG-POC-015 | Phase 6 PG evidence contribution                                      |
| PG-POC-016 | Joint Phase 6 with YDB (per §0.2; Outcome 9 spins off hybrid track)   |

Total: 17 tickets (000–016). The 40-ticket YDB structure provides the template; most of it is reuse or inheritance.

------

## 19. One Note on Discipline

This proposal inherits every process rule and evidence-scope discipline established during the YDB PoC's full review cycle — the early-cycle five plus the four the closeout sweep added after catching real bugs (SchemaInstaller split, path accumulation):

- Green tests state their scope.
- Conformance claims gated by evidence.
- No metric without its topology.
- No capability claim without its qualifier.
- Same throwaway scope until 010 resolves.
- Teardown WARN rule (closeout finding: swallowed teardown exceptions) — best-effort catches on teardown must log, not swallow silently.
- DDL-in-one-place rule (closeout finding: SchemaInstaller split) — schema DDL lives in exactly one place per backend.
- Lifecycle rule (closeout finding: path-count accumulation) — test resources reclaimed synchronously; quota-shaped limits get this treatment from day one (PG-POC-012).
- Visible-skip rule (closeout finding: green-silent off-container skips) — a guard that skips off-container must skip visibly, not green-silently.

The Postgres PoC should be a **proof of the port architecture's substitutability**, not just a proof of Postgres. If it succeeds, the architecture is validated independently of any backend. If it fails, the architecture still holds — the failure is Postgres's, not the ports'.

Either outcome strengthens the platform.