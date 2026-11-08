# ADR-021: Pluggable Search Engine Boundary — Staged `SearchEngineAdapter` Extraction

**Status:** Accepted 2026-11-08
**Basis:** `LuceneIndexBuilder.build()` rebuilt the whole tenant with `OpenMode.CREATE`, materialised manifest/chunk lists, and `SearchService.reindex` closed the searcher for the duration — ~40 s of 503s at 62 docs and an OOM risk at corpus scale (#547). The engine was also hard-wired into the projection path, making a remote Solr/Elasticsearch adapter a rewrite rather than a swap.

## Decision

1. **Port for the projection write path.** All index mutations go through `org.synanton.synquest.search.SearchEngineAdapter` (domain-only surface: `rebuild`, `indexContentRefs`, `deleteChunkIds`, `isEmpty`, `indexPath`, `lastReport`). `EmbeddedLuceneAdapter` is the first implementation; `LuceneIndexBuilder` remains the engine-specific detail. `SearchProjectionConsumer` and the projection half of `SearchService` depend on the port, not the builder.
2. **Stream, don't materialise.** Full rebuilds page manifests and chunks through the Cassandra driver (`fetchSize(1000)`) and rely on Lucene's RAM-buffer segment flush with a single final commit. Heap is bounded by the writer buffer, and a failed rebuild is never committed (a partial index cannot be mistaken for complete on the next boot).
3. **Incremental re-projection + zero-downtime refresh.** `OpenMode.CREATE_OR_APPEND` plus `updateDocument(new Term("id", contentRef + ":" + ordinal))` replaces chunks by stable id. `SearcherManager.maybeRefresh()` swaps readers atomically; `reindex` keeps the current searcher open during a full rebuild, so searches continue on the previous snapshot (no 503 blackout from projection).
4. **Read path behind the same port.** The read contract is `SearchEngineAdapter.search(SearchQuery, SearchFilter) -> SearchResult` with engine-neutral `SearchDocument`/`SearchQuery`/`SearchFilter`/`SearchResult`. `LuceneSearchEngine` implements the hybrid legs/fusion/rerank; `SearchService` is a thin facade that maps API DTOs to the port. `HybridSearcher`, `RrfFusion`, `EmbeddingShape`, `LuceneIndexBuilder`, and `LuceneSearchEngine` live in `org.synanton.synquest.engine.lucene`; the `service` and `api` packages have **zero** `org.apache.lucene` imports. The operational fix (#551) and the read-path move (#550) landed as separate changes so the green suite and demo were never destabilised together.

Consequences:
- **Swap path:** a remote adapter implements the same port; the projection path does not change.
- **Memory:** a 10M-chunk rebuild no longer loads the corpus into heap.
- **Availability:** index refreshes are atomic; projection no longer forces a 503 window.
- **Test safety net:** `LuceneIndexIdempotencyTest` proves re-projected/duplicate events do not duplicate documents; it remains valid for the incremental refactor.
- **Rejected:** adopting Solr/ES now — operational cost breaks the one-command deploy and the HTTP hop is not justified at the current corpus size.
- **Decoupling:** the physical engine is isolated to `engine.lucene`; a remote adapter can replace it without touching `SearchService`, the API controllers, or the projection consumer.

## Context

The projection is a derived, rebuildable view of canonical Cassandra knowledge (Design 1.31). At ~62 documents it was tolerable to wipe and rebuild; at 10k+ it would exhaust heap and black out search for the rebuild. The immediate need was operational (OOM + 503), not a search-DSL product. The adapter port is extracted now around the write/lifecycle seam, where the engine coupling was most acute, while the read-path move is scheduled as a separate, behaviour-preserving refactor.
