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


def search(synquest_base_url: str, tenant: str, query: str, top_k: int = 10) -> SearchResult:
    """POST /search and return hits mapped to chunk IDs, plus wall-clock latency.

    `latency_ms` is measured client-side around the HTTP call - it is a
    coarser number than synquest's own internal SearchTrace
    (embedMs/denseMs/lexicalMs/fusionMs), which is not yet exposed on the
    response (tracked as new work in
    docs/research/retrieval-evaluation-benchmark-plan.md §2). Use this for
    end-to-end p95 latency (§5) until that's wired through.
    """
    started = time.monotonic()
    response = requests.post(
        f"{synquest_base_url}/search",
        json={"tenant": tenant, "query": query, "top_k": top_k},
        timeout=30,
    )
    elapsed_ms = (time.monotonic() - started) * 1000
    response.raise_for_status()
    body = response.json()

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
    )
