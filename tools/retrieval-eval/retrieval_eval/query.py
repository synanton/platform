"""Query runner - wraps synquest's existing /search endpoint.

Hit-to-chunk-id mapping matches the real `Hit` DTO
(java/synquest/src/main/java/org/synanton/synquest/api/dto/Hit.java) and the
"<content_ref_id>#<chunk_ordinal>" gold-ID convention already used by
docs/research/flat-vs-semantic-chunks-research-plan.md's own queries.jsonl
format, so gold labels are directly comparable between the two plans.
"""

from __future__ import annotations

import time
from dataclasses import dataclass

import requests


def chunk_id(content_ref_id: str, chunk_ordinal: int) -> str:
    return f"{content_ref_id}#{chunk_ordinal}"


@dataclass(frozen=True)
class SearchHit:
    chunk_id: str
    score: float
    score_dense: float
    score_lexical: float
    source_uri: str
    section_path: str
    heading: str


@dataclass(frozen=True)
class SearchResult:
    hits: list[SearchHit]
    latency_ms: float
    query_usage_present: bool
    # From synquest's query_usage / trace. embed_skipped=True means dense retrieval never
    # ran for this query; embed_cached=True means no embedding request was made (query cache).
    embed_skipped: bool = False
    embed_cached: bool = False
    embed_ms: float | None = None
    # B2/T10: set when synquest reranked this search (trace.rerank_ms present)
    rerank_ms: float | None = None


class SearchUnavailable(RuntimeError):
    """synquest returned 503. Under synquest.embedding.required this means the query
    embedding or dense search failed (fail closed), not "BM25-only results"."""


@dataclass(frozen=True)
class IndexStats:
    doc_count: int
    embedding_model: str | None
    embedding_dim: int | None
    vector_docs: int | None
    dim_mismatches: int | None
    missing_vectors: int | None
    section_docs: int | None = None      # chunks with a section hierarchy (B2/T05)

    @property
    def fully_vectorised(self) -> bool | None:
        """None when synquest didn't build the index in this process (no coverage report)."""
        if self.vector_docs is None:
            return None
        return self.vector_docs == self.doc_count and not self.dim_mismatches and not self.missing_vectors


def index_stats(synquest_base_url: str, tenant: str) -> IndexStats:
    """GET /index/stats: doc count plus the embedding coverage report of the last build (G2)."""
    # synquest's MockTenantFilter takes the tenant from X-Tenant (default "demo") and it wins
    # over ?tenant=, so without the header this silently reported the demo tenant's index
    response = requests.get(f"{synquest_base_url}/index/stats", params={"tenant": tenant},
                            headers={"X-Tenant": tenant}, timeout=30)
    response.raise_for_status()
    body = response.json()
    return IndexStats(
        doc_count=body.get("doc_count", 0),
        embedding_model=body.get("embedding_model"),
        embedding_dim=body.get("embedding_dim"),
        vector_docs=body.get("vector_docs"),
        dim_mismatches=body.get("dim_mismatches"),
        missing_vectors=body.get("missing_vectors"),
        section_docs=body.get("section_docs"),
    )


def search(
    synquest_base_url: str,
    tenant: str,
    query: str,
    top_k: int = 10,
    top_k_dense: int | None = None,
    top_k_lexical: int | None = None,
    rerank: bool = False,
    rerank_candidates: int | None = None,
    expand: str | None = None,
    expand_max_chunks: int | None = None,
) -> SearchResult:
    """POST /search and return hits mapped to chunk IDs, plus wall-clock latency.

    `top_k_dense`/`top_k_lexical` isolate one side of the hybrid RRF fusion for
    the T01 (BM25-only)/T02 (dense-only) matrix rows (plan §3.4) - pass `0` for
    the side to suppress. Omit both (`None`) for full hybrid (T03/T04).

    `latency_ms` is measured client-side around the HTTP call (end-to-end p95,
    §5). `embed_ms` is synquest's own `trace.query_embed_ms`, reported
    separately because on GPU-7 it includes the WAN round trip (§8).
    """
    request_body = {"tenant": tenant, "query": query, "top_k": top_k}
    if top_k_dense is not None:
        request_body["top_k_dense"] = top_k_dense
    if top_k_lexical is not None:
        request_body["top_k_lexical"] = top_k_lexical
    if expand:
        request_body["expand"] = expand
        if expand_max_chunks is not None:
            request_body["expand_max_chunks"] = expand_max_chunks
    if rerank:
        request_body["rerank"] = True
        if rerank_candidates is not None:
            request_body["rerank_candidates"] = rerank_candidates

    started = time.monotonic()
    response = requests.post(
        f"{synquest_base_url}/search",
        json=request_body,
        timeout=30,
    )
    elapsed_ms = (time.monotonic() - started) * 1000
    if response.status_code == 503:
        raise SearchUnavailable(f"synquest 503 for tenant={tenant}: {response.text[:300]}")
    response.raise_for_status()
    body = response.json()
    usage = body.get("query_usage") or {}
    trace = body.get("trace") or {}

    hits = [
        SearchHit(
            chunk_id=chunk_id(h["content_ref_id"], h["chunk_ordinal"]),
            score=h.get("score", 0.0),
            score_dense=h.get("score_dense", 0.0),
            score_lexical=h.get("score_lexical", 0.0),
            source_uri=h.get("source_uri", ""),
            section_path=h.get("section_path", ""),
            heading=h.get("heading", ""),
        )
        for h in body.get("hits", [])
    ]

    return SearchResult(
        hits=hits,
        latency_ms=elapsed_ms,
        query_usage_present=body.get("query_usage") is not None,
        embed_skipped=bool(usage.get("embed_skipped", False)),
        embed_cached=bool(usage.get("embed_cached", False)),
        embed_ms=trace.get("query_embed_ms", usage.get("query_embed_ms")),
        rerank_ms=trace.get("rerank_ms"),
    )
