# Port type reference (Phase 1/2 daily surface)

Modules: `java/synvault-api` (`org.synanton.synvault.api`),
`java/synquest-api` (`org.synanton.synquest.api`),
shared types in `java/storage-contract` (`org.synanton.storage.contract`).
Provisional surfaces (`PublicationIntent`, error taxonomy) — see
`011-provisional-followup.md`; do not treat as frozen until 010 flips.

## SynvaultStore (Knowledge 1.25 persistence)

```java
CompletionStage<Document> putDocument(SecurityContext ctx, Document doc, DocumentWriteOptions o);
CompletionStage<Optional<Document>> getDocument(SecurityContext ctx, DocumentId id);
CompletionStage<Void> deleteDocument(SecurityContext ctx, DocumentId id);
CompletionStage<ChunkPage> getChunks(SecurityContext ctx, DocumentId doc, ChunkQuery q, PageRequest page);
CompletionStage<Void> putDocumentRevision(SecurityContext ctx, DocumentRevision rev, RevisionWriteOptions o);
CompletionStage<List<ProvenanceRecord>> getProvenance(SecurityContext ctx, DocumentId id);
StoreCapabilities capabilities();
```

**DocumentRevision atomicity unit:** `Document(document, chunks, provenance [non-empty], metadata, publication)` commit atomically when the backend supports transactions; otherwise the adapter reports `supportsTransactions=false` and rejects with `UNSUPPORTED` (never silent weakening). `putDocument` is metadata-only — never chunks/provenance/publication.

## SynquestEngine (Search 1.31 retrieval, query-facing only)

```java
CompletionStage<SearchResult> search(SecurityContext ctx, SearchRequest req);
SearchCapabilities capabilities();
```

```java
// Projection mutation (consumes Eventing 1.27) + lifecycle — separate ports
CompletionStage<Void> upsert(List<ChunkProjection> projections);            // SynquestIndexWriter
CompletionStage<Void> delete(GenerationId gen, Collection<ChunkId> ids);    // generation-scoped
CompletionStage<Void> ensureSchema(SchemaOptions o);
CompletionStage<Void> rebuild(RebuildOptions o);                            // new generation
CompletionStage<IndexStatus> status();                                      // SynquestIndexAdmin
```

**Eligibility vs temporal:** `EligibilityConstraints` is security-only;
`TemporalExtension` is separate; **both are pre-ranking** (candidate generation,
never post-filter). Adapters with `temporal=false` reject non-empty temporal
instead of ignoring it.

**ChunkProjection{orderingKey, generationId}:** monotonic key per
(tenant, doc, chunk) from the Synvault commit sequence (never wall-clock);
writer applies only newer keys, discards older as no-ops (invariant 36);
deletes are generation-scoped (invariant 35).

**ActiveProviders:** typed `name@version` snapshot per port (`storage-contract`),
shared by selection (039) and observability (038) — see provider-selection guide.
