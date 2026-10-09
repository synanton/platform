"""Synquest-backed execution (BR-A2.1/B5.2): drive a live synquest service over REST.

Mirrors the retrieval-eval harness query path (POST /search with top_k knobs).
BM25-only when topKDense is 0 (no embedding service needed); hybrid otherwise.
"""

from __future__ import annotations

import json
import urllib.request
import urllib.error

from .executor import CompositionExecutor, RawQueryResult


class EndpointUnreachable(Exception):
    def __init__(self, endpoint: str, reason: str):
        super().__init__(f"synquest endpoint unreachable: {endpoint}: {reason}")
        self.endpoint = endpoint


class SearchFailed(Exception):
    def __init__(self, endpoint: str, status: int, body: str):
        super().__init__(f"synquest search failed: {endpoint}: HTTP {status}: {body[:200]}")
        self.endpoint = endpoint
        self.status = status


def post_search(endpoint: str, payload: dict, timeout_s: int = 60) -> dict:
    """POST /search on a synquest service. Raises EndpointUnreachable/SearchFailed."""
    url = endpoint.rstrip("/") + "/search"
    data = json.dumps(payload).encode()
    request = urllib.request.Request(
        url, data=data, headers={"Content-Type": "application/json"}, method="POST"
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout_s) as response:
            return json.loads(response.read().decode())
    except urllib.error.HTTPError as e:
        raise SearchFailed(endpoint, e.code, e.read().decode(errors="replace")) from e
    except (urllib.error.URLError, OSError, TimeoutError) as e:
        raise EndpointUnreachable(endpoint, str(e)) from e


def probe(endpoint: str, timeout_s: int = 10) -> dict:
    """Reachability + version probe. Never raises; returns a status dict."""
    url = endpoint.rstrip("/") + "/actuator/health"
    try:
        with urllib.request.urlopen(url, timeout=timeout_s) as response:
            body = response.read().decode(errors="replace")
            return {"endpoint": endpoint, "reachable": True, "status": response.status, "body": body[:200]}
    except Exception as e:  # noqa: BLE001 - probe must never raise
        return {"endpoint": endpoint, "reachable": False, "reason": str(e)[:200]}


class SynquestExecutor(CompositionExecutor):
    """CompositionExecutor over a live synquest service (B5.2 execution leg).

    Maps manifest compositions to service query params; converts service hits
    to RawQueryResult with the composition's topology annotation.
    """

    def __init__(self, endpoint: str, topology: str = None, timeout_s: int = 60):
        self.endpoint = endpoint
        self.topology_override = topology
        self.timeout_s = timeout_s

    def _topology(self, composition: dict) -> str:
        if self.topology_override:
            return self.topology_override
        if composition.get("topology"):
            return composition["topology"]
        return f"{composition.get('vector_provider', 'unknown')}"

    def execute(self, composition: dict, queries: list, ctx) -> list:
        results = []
        for query in queries:
            payload = {
                "tenant": query.get("tenant", "demo"),
                "query": query.get("text", query["query_id"]),
                "top_k": query.get("top_k", 10),
                "top_k_dense": query.get("top_k_dense", 100),
                "top_k_lexical": query.get("top_k_lexical", 100),
                "rrf_k": query.get("rrf_k", 60),
            }
            response = post_search(self.endpoint, payload, self.timeout_s)
            hits = [
                {"chunk_id": h.get("content_ref_id", h.get("chunk_id", "")),
                 "rank": i,
                 "score": float(h.get("score", 0.0))}
                for i, h in enumerate(response.get("hits", []))
            ]
            trace = response.get("trace", {})
            timing = float(trace.get("totalMs", trace.get("total_ms", 0.0)))
            results.append(
                RawQueryResult(
                    query_id=query["query_id"],
                    hits=hits,
                    eligible_set=list(query.get("eligible", [])),
                    timing_ms=timing,
                    timing_scope="synquest",
                    topology=self._topology(composition),
                    # No signal only when the leg returned nothing AND nothing
                    # was expected: hits prove the leg ran against live state.
                    structural_empty=not hits
                    and not query.get("gold")
                    and not query.get("eligible"),
                )
            )
        return results
