#!/usr/bin/env python3
"""Print BM25, Dense, and RRF attribution for a demo query.

Uses cached dense-v2 query/chunk vectors so TEI can be down; BM25 is live against
the local demo-v2 synquest. Unknown queries fall back to TEI only for the query
embedding. Gold flags come from demo-v2-gold-ranks.json.
"""
from __future__ import annotations
import base64, hashlib, hmac, json, math, os, re, sys, time, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BENCH = ROOT / "demo-data/eval/retrieval-benchmark"
SYNQUEST = os.environ.get("SYNQUEST_URL", "http://localhost:18083")
TEI = os.environ.get("TEI_URL", "http://localhost:30801")
TENANT = os.environ.get("DEMO_TENANT", "demo-v2")
SECRET = os.environ.get("SYNANTON_JWT_SECRET", "local-real-demo-secret-at-least-32-bytes")
K = 20


def token() -> str:
    def b64(value: bytes) -> str:
        return base64.urlsafe_b64encode(value).rstrip(b"=").decode()
    header = b64(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
    claims = b64(json.dumps({"sub": "demo-runner", "tenant_id": TENANT,
                             "exp": int(time.time()) + 3600}).encode())
    sig = b64(hmac.new(SECRET.encode(), f"{header}.{claims}".encode(), hashlib.sha256).digest())
    return f"{header}.{claims}.{sig}"


def post_json(url: str, body: dict) -> dict:
    req = urllib.request.Request(url, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json",
                                          "Authorization": f"Bearer {token()}"},
                                 method="POST")
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.loads(response.read())


def query_type(text: str) -> str:
    exact = re.search(r"\b(?:ERR_|SEC-|IATF\s+\d+|ISO\s+\d+|VLAN\d+|v\d+\.\d+|\d{4})\b", text, re.I)
    quoted = re.search(r'"[^"]+"|`[^`]+`', text)
    conceptual = re.search(r"\b(how does|why|define|explain|difference|compare|distinguish|meaning)\b", text, re.I)
    if exact or quoted:
        return "exact"
    if conceptual or len(text.split()) > 10:
        return "conceptual"
    return "mixed"


def entities(text: str) -> int:
    patterns = [
        r"\bERR_[A-Z0-9_-]+", r"\bSEC-[A-Z0-9-]+", r"\bIATF\s+\d+",
        r"\bISO\s+\d+(?::\d+)?", r"\bVLAN\d+", r"\b(?:\d{1,3}\.){3}\d{1,3}\b",
        r"\b(?:19|20)\d{2}\b", r"\bv\d+(?:\.\d+){1,3}\b",
        r"\b[A-Z][a-zA-Z]+(?:Engine|Manager|Store|Policy|Class|Object|Folder|Document)\b",
        r"\b[A-Z][a-z]+\s+v\.\s+[A-Z][a-z]+\b", r"(?:Section|§)\s*\d+(?:\.\d+)*",
    ]
    found = set()
    for pattern in patterns:
        for match in re.finditer(pattern, text):
            found.add(match.group(0).casefold())
    return len(found)


def dense_top(query: str, cache: dict) -> tuple[list[str], str]:
    questions = {q["query_id"]: q["question"] for q in
                 map(json.loads, (BENCH / "queries-v2.rb-fixed-g5.jsonl").read_text().splitlines())}
    by_question = {questions[q["query_id"]]: q["embedding"] for q in cache["queries"]}
    vector = by_question.get(query)
    source = "cached-query"
    if vector is None:
        body = post_json(TEI + "/v1/embeddings", {"input": query, "model": "synanton-bge-small-embedding"})
        vector = body["data"][0]["embedding"]
        source = "live-TEI-query"
    scored = []
    for chunk in cache["chunks"]:
        dot = sum(a * b for a, b in zip(vector, chunk["embedding"]))
        scored.append((dot, chunk["id"]))
    scored.sort(reverse=True)
    return [chunk for _, chunk in scored[:20]], source


def main() -> None:
    query = " ".join(sys.argv[1:]).strip()
    if not query:
        raise SystemExit("usage: run-query.sh <query>")
    gold = json.loads((BENCH / "demo-v2-gold-ranks.json").read_text())["gold"]
    gold_ids = set()
    for ids in gold.values():
        gold_ids.update(ids)
    cache = json.loads((BENCH / "dense-embeddings-v2.json").read_text())
    bm25 = post_json(SYNQUEST + "/v1/search", {"query": query})
    bm25_ids = [r["object_id"].replace(":", "#", 1) for r in bm25.get("results", [])]
    dense_ids, dense_source = dense_top(query, cache)
    contributions = {}
    for rank, chunk in enumerate(bm25_ids, 1):
        contributions.setdefault(chunk, {})["bm25"] = {"rank": rank, "score": 1 / (K + rank)}
    for rank, chunk in enumerate(dense_ids, 1):
        contributions.setdefault(chunk, {})["dense"] = {"rank": rank, "score": 1 / (K + rank)}
    fused = sorted(((sum(part["score"] for part in parts.values()), chunk, parts)
                    for chunk, parts in contributions.items()),
                   key=lambda item: (-item[0], item[1]))

    print(f"Query type: {query_type(query)}")
    print(f"BM25 source: live synquest ({len(bm25_ids)} results)")
    print(f"Dense source: {dense_source} ({len(dense_ids)} results)")
    print("\nBM25 top-10:")
    for rank, chunk in enumerate(bm25_ids[:10], 1):
        print(f"  {rank:2d}. {chunk}  {1/(K+rank):.6f}")
    print("\nDense top-10:")
    for rank, chunk in enumerate(dense_ids[:10], 1):
        print(f"  {rank:2d}. {chunk}  {1/(K+rank):.6f}")
    print(f"\nRRF fused (k={K}):")
    for rank, (score, chunk, parts) in enumerate(fused[:10], 1):
        b = f"BM25 #{parts['bm25']['rank']}" if "bm25" in parts else "BM25 —"
        d = f"Dense #{parts['dense']['rank']}" if "dense" in parts else "Dense —"
        marker = "  ← GOLD" if chunk in gold_ids else ""
        print(f"  {rank:2d}. {chunk}  {score:.6f}  [{b}, {d}]{marker}")
    print(f"\nLegs present: {sorted({leg for parts in contributions.values() for leg in parts})}")
    print("Gold flag: " + ("yes" if any(chunk in gold_ids for _, chunk, _ in fused[:10]) else "no"))
    selection = json.loads((BENCH / "demo-query-selection-v2.json").read_text())
    graph = selection.get("graph_availability", {})
    matched = next((qid for qid, item in graph.items()
                    if isinstance(item, dict) and item.get("question") == query), None)
    if matched:
        item = graph[matched]
        print(f"Graph availability ({matched}): useful entities={len(item.get('useful_entities', []))}, neighbors={item.get('neighbors', 0)}")
    else:
        print("Graph availability: n/a (ontology/graph API not built)")
    print("Note: graph is visualization-only; this runner does not rank by graph.")


if __name__ == "__main__":
    main()
