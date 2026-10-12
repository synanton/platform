"""Rerank evaluation (P1, ticket T13/T14): reorder a run's top-N per query
through a cross-encoder (/v1/rerank, OpenAI-compatible) and score recall
before/after. Runner-side measurement of reranker value; service-side
wiring (SearchReranker bean) is a separate platform task.
"""

from __future__ import annotations

import json
import urllib.request
import urllib.error
from pathlib import Path


class RerankFailed(Exception):
    def __init__(self, reason: str):
        super().__init__(f"rerank failed: {reason}")


def rerank_documents(endpoint: str, model: str, query_text: str,
                     documents: list, timeout_s: int = 120,
                     instruction: str = None) -> list:
    """Returns [(index, score)] sorted by score desc. Raises RerankFailed.

    Applies the QWEN3 chat template around query and passages (required for
    Qwen3-Reranker: without it scores invert — relevant 0.35 vs distractors
    0.68+, measured GPU-5 2026-09-25; see RerankPromptFormat in gpu-client).
    vLLM serves the yes/no classifier but leaves templating to the client.
    """
    qwen_prefix = ("<|im_start|>system\nJudge whether the Document meets the "
                   "requirements based on the Query and the Instruct provided. "
                   "Note that the answer can only be \"yes\" or \"no\".<|im_end|>\n"
                   "<|im_start|>user\n")
    qwen_suffix = "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
    instruct = instruction or "Given a web search query, retrieve relevant passages that answer the query"
    templated_query = (qwen_prefix + "<Instruct>: " + instruct
                       + "\n<Query>: " + query_text + "\n")
    templated_docs = ["<Document>: " + d + qwen_suffix for d in documents]
    payload = {"model": model, "query": templated_query, "documents": templated_docs}
    url = endpoint.rstrip("/") + "/v1/rerank"
    data = json.dumps(payload).encode()
    request = urllib.request.Request(
        url, data=data, headers={"Content-Type": "application/json"}, method="POST"
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout_s) as response:
            body = json.loads(response.read().decode())
    except urllib.error.HTTPError as e:
        raise RerankFailed(f"HTTP {e.code}: {e.read().decode(errors='replace')[:200]}") from e
    except (urllib.error.URLError, OSError, TimeoutError) as e:
        raise RerankFailed(str(e)) from e
    scored = []
    for item in body.get("results", []):
        idx = item.get("index")
        score = item.get("relevance_score", item.get("score"))
        if idx is None or score is None:
            continue
        scored.append((idx, float(score)))
    scored.sort(key=lambda pair: pair[1], reverse=True)
    return scored


def rerank_run(document: dict, queries: list, endpoint: str, model: str,
               depth: int, top_k: int) -> dict:
    """Re-rank each query's top-`depth` hits (by snippet) to `top_k`.

    Queries without snippets on every hit are passed through unchanged and
    counted in `rerank_skipped`. Returns a new Result Manifest document with
    `environment.reranked` provenance.
    """
    out_queries = []
    reranked = 0
    skipped = 0
    for entry in document.get("queries", []):
        qid = entry.get("query_id")
        spec = next((q for q in queries if q.get("query_id") == qid), {})
        hits = list(entry.get("top_k", []))[:depth]
        if not hits or any("snippet" not in h for h in hits):
            out_queries.append(entry)
            skipped += 1
            continue
        scored = rerank_documents(
            endpoint, model, spec.get("text", qid), [h["snippet"] for h in hits])
        order = [idx for idx, _ in scored if 0 <= idx < len(hits)]
        order += [i for i in range(len(hits)) if i not in order]
        new_hits = []
        for rank, i in enumerate(order[:top_k]):
            hit = dict(hits[i])
            hit["rank"] = rank
            hit["score"] = next(s for j, s in scored if j == i)
            new_hits.append(hit)
        out = dict(entry)
        out["top_k"] = new_hits
        out_queries.append(out)
        reranked += 1
    out = dict(document)
    out["queries"] = out_queries
    env = dict(document.get("environment", {}))
    env["reranked"] = True
    env["rerank_model"] = model
    env["rerank_depth"] = depth
    out["environment"] = env
    out["metrics_summary"] = dict(document.get("metrics_summary", {}))
    out["metrics_summary"]["reranked_queries"] = reranked
    out["metrics_summary"]["rerank_skipped"] = skipped
    return out


def recall_report(document: dict, queries: list, k: int = 10) -> dict:
    """Per-query recall@k over a (possibly reranked) Result Manifest."""
    gold = {q.get("query_id"): list(q.get("gold", [])) for q in queries}
    per_query = {}
    for entry in document.get("queries", []):
        g = gold.get(entry.get("query_id"), [])
        if not g:
            continue
        tops = [h.get("chunk_id") for h in entry.get("top_k", [])[:k]]
        per_query[entry.get("query_id")] = len(set(g) & set(tops)) / len(g)
    mean = sum(per_query.values()) / len(per_query) if per_query else None
    return {"mean": mean, "n": len(per_query), "per_query": per_query}
