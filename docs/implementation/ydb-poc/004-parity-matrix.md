# YDB-POC-004 — Frozen Feature-Parity Matrix

**Status:** Frozen (Phase 0D; current + requirement columns — YDB column fills during 024B)
**Date:** 2026-09-26
**Baseline label:** "current behavior" = ingestion-cache-backed Lucene path (proposal §13 note)

Evidence pointers are file-level; all under `java/synquest/src/main`.

| Feature | Current behavior (frozen) | Requirement | YDB behavior | Status |
|---|---|---|---|---|
| BM25/scoring formula | Lucene `BM25Similarity` defaults (k1=1.2, b=0.75), `StandardAnalyzer` on field `text`; query escaped via `MultiFieldQueryParser.escape` (`HybridSearcher.lexical`) | Must | TBD (024B) | Open |
| Highlight offsets (eligibility-safe) | **Not implemented** — no highlighter/snippet path anywhere in the service | Must meet-or-justify: implement or record approved alternative before Phase 6 | TBD (024B) | Open |
| Sparse+dense fusion | RRF `1/(rrfK+rank+1)` per list, defaults topK 20 / dense 100 / lexical 100 / rrfK 60 (`RrfFusion`, `SearchService`, `application.yml`) | Must | TBD (024B) | Open |
| Per-tenant index isolation | Physical: one Lucene `FSDirectory` per tenant (`SearchService.initTenant`); candidate sets disjoint by construction | Must | TBD (024B) | Open |
| Pre-ranking eligibility | Tenant isolation only. `CuckooAclFilter` exists but is **not wired** into `SearchService`; no classification-level candidate filtering | Must | TBD (024B) | Open |
| Metadata operators | **None** — no metadata term/filter query on the search path (section-expansion helper only) | Must meet-or-justify | TBD (024B) | Open |
| Score normalization | **None** — raw RRF scores returned | Must | TBD (024B) | Open |
| Result ordering/tie-breaking | RRF score desc; ties fall through to stream (insertion) order — **unspecified** | Must (specify) | TBD (024B) | Open |
| Delete semantics | **Full tenant reindex** (`LuceneIndexBuilder.build` + commit); no per-doc delete path | Must | TBD (024B) | Open |
| Update semantics | **Full tenant reindex**; no per-doc update path | Must | TBD (024B) | Open |
| Explainability | Partial: per-hit dense/lexical scores + ranks exposed (`Hit`); no full explanation trace | May | TBD (024B) | Open |
| Graph retrieval | N/A | Out of scope (Relix) | — | — |
| Temporal retrieval | N/A | Out of scope (extension point exists) | — | — |

## Freeze notes

- Rows marked "meet-or-justify" (highlights, metadata operators) are the two places
  current behavior is *absent*: YDB either implements them or the requirement is
  formally downgraded with Architecture sign-off before Phase 6. Silence is not an option.
- "Unspecified" tie-breaking must be specified (deterministic rule) in 024A/024B —
  both adapters, same rule — before recall/latency numbers are comparable.
- YDB column + Status fill as 024A/024B land. No `Must` row may stay TBD at Phase 6.
