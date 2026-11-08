# ADR-021: Pluggable Search Engine Boundary — Staged `SearchEngineAdapter` Extraction

**Status:** Accepted 2026-11-08
**Basis:** `LuceneIndexBuilder.build()` rebuilt the whole tenant with `OpenMode.CREATE`, materialised manifest/chunk lists, and `SearchService.reindex` closed the searcher for the duration — ~40 s of 503s at 62 docs and an OOM risk at corpus scale (#547). The engine was also hard-wired into the projection path, making a remote Solr/Elasticsearch adapter a rewrite rather than a swap.

## Decision

1. **Port for the projection write path.** All index mutations go through `org.synanton.synquest.search.SearchEngineAdapter` (domain-only surface: `rebuild`, `indexContentRefs`, `deleteChunkIds`, `isEmpty`, `indexPath`, `lastReport`). `EmbeddedLuceneAdapter` is the first implementation; `LuceneIndexBuilder` remains the engine-specific detail. `SearchProjectionConsumer` and the projection half of `SearchService` depend on the port, not the builder.
2. **Stream, don't materialise.** Full rebuilds page manifests and chunks through the Cassandra driver (`fetchSize(1000)`) and rely on Lucene's RAM-buffer segment flush with a single final commit. Heap is bounded by the writer buffer, and a failed rebuild is never committed (a partial index cannot be mistaken for complete on the next boot).
3. **Incremental re-projection + zero-downtime refresh.** `OpenMode.CREATE_OR_APPEND` plus `updateDocument(new Term("id", contentRef + ":" + ordinal))` replaces chunks by stable id. `SearcherManager.maybeRefresh()` swaps readers atomically; `reindex` keeps the current searcher open during a full rebuild, so searches continue on the previous snapshot (no 503 blackout from projection).
4. **Staged read path.** The read-side DSL (`SearchQuery`/`SearchFilter` → `SearchResult`) and relocating `HybridSearcher`, `RrfFusion`, `LuceneKnowledgeSearchBackend`, and `EmbeddingShape` into an `engine` package remain a tracked follow-up. Until that lands, `SearchService` still owns the hybrid legs and imports Lucene for reads; the write/lifecycle boundary is the first extracted port. This staging is deliberate: moving ~400 lines of fusion/rerank logic in the same change as the operational fix would put the green suite and the demo at risk.

Consequences:
- **Swap path:** a remote adapter implements the same port; the projection path does not change.
- **Memory:** a 10M-chunk rebuild no longer loads the corpus into heap.
- **Availability:** index refreshes are atomic; projection no longer forces a 503 window.
- **Test safety net:** `LuceneIndexIdempotencyTest` proves re-projected/duplicate events do not duplicate documents; it remains valid for the incremental refactor.
- **Rejected:** adopting Solr/ES now — operational cost breaks the one-command deploy and the HTTP hop is not justified at the current corpus size.
- **Known gap:** the read-path port is not yet extracted; `SearchService` still sees Lucene (documented above and in the tracking issue).

## Context

The projection is a derived, rebuildable view of canonical Cassandra knowledge (Design 1.31). At ~62 documents it was tolerable to wipe and rebuild; at 10k+ it would exhaust heap and black out search for the rebuild. The immediate need was operational (OOM + 503), not a search-DSL product. The adapter port is extracted now around the write/lifecycle seam, where the engine coupling was most acute, while the read-path move is scheduled as a separate, behaviour-preserving refactor.
