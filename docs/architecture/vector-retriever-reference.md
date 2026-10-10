# VectorRetriever port reference (DOC-D2.2)

**Port owner:** `java/synquest-api` — `org.synanton.synquest.api`.
Implementations live outside `synquest-api` (module boundary guard enforced).
An implementer who reads this plus the three B1 adapters
(Milvus, Qdrant, Lucene standalone) can build a new adapter.

---

## 1. The port (two methods, one constant)

```java
public interface VectorRetriever {
    double MIN_SCORE_NO_THRESHOLD = Double.NEGATIVE_INFINITY;
    CompletionStage<VectorSearchResult> search(SecurityContext context, VectorSearchRequest request);
    SearchCapabilities capabilities();
}
```

- Transport-agnostic: in-process (Lucene) and external-service
  (Milvus/Qdrant over HTTP/gRPC) honour the same contract. Adapter-side
  clients stay out of `synquest-api`.
- Async by contract: return a `CompletionStage`, never block the caller.

## 2. Request (Reading 1 — caller embeds)

`VectorSearchRequest(queryEmbedding, embeddingModelRef, eligibility, topK)`:

- `queryEmbedding` — non-empty `float[]` (defensively copied in and out).
- `embeddingModelRef` — `Optional`; callers may pass pre-computed embeddings
  without fabricating provenance. The retriever **never embeds, never sees text**.
- `eligibility` — mandatory, pre-ranking (mirrors `SearchRequest`).
- `topK >= 1`. Retrieval is topK-bounded; **thresholding is caller-side**.
  Pass `MIN_SCORE_NO_THRESHOLD` (negative infinity) for "no threshold", and do
  score filtering in Java — never push it into the engine query, where the
  value may not round-trip.

## 3. Result (reuse by shape, not subtyping)

`VectorSearchResult(hits, totalEligible)` — same shape as `SearchResult`
**minus highlights** (lexical feature; never populate). `totalEligible` is the
count before topK truncation. The B1.4 facade translates back to
`SearchResult` with an empty highlights map.

## 4. Write path

`VectorProjection(chunkId, documentId, tenantId, embedding, embeddingModelRef,
orderingKey, generationId)` written via `SynquestIndexWriter.upsert(List<?
extends Projection>)` (sealed upsert). Asymmetry is deliberate: the
projection's model ref is **required** (stored vectors must carry provenance
for invalidation). `orderingKey`/`generationId` are per-tenant/doc/chunk
commit sequence, never wall-clock. No text, no metadata on this path.

## 5. Capabilities

`capabilities()` reuses `SearchCapabilities` with **lexical-only flags false
by contract**. Known gap (carried from B1.1, no owning task): vector-specific
flags (index type, distance metric) are not representable — revisit if a
future adapter needs them.

## 6. Checklist for a new adapter

1. Implement `search` async; enforce eligibility pre-ranking; filter scores in Java.
2. Implement `capabilities()` with lexical flags false.
3. Keep clients out of `synquest-api` (boundary guard will fail the build).
4. Add a conformance case to `VectorRetrieverContract` (see existing
   Milvus/Qdrant/pgvector contract tests, B3 record).
5. Register under the `vector.*` namespace (`validateComposition` rules in the
   composition guide); metadata-role adapters need document-storage capability.
