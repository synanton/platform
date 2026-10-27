#!/usr/bin/env python3
"""Print BM25, Dense, and RRF attribution for a demo query.

Uses cached dense-v2 query/chunk vectors so TEI can be down; BM25 is live against
the local demo-v2 synquest. Unknown queries fall back to TEI only for the query
embedding. Gold flags come from demo-v2-gold-ranks.json.
"""
from __future__ import annotations
import json, os, re, sys, time, urllib.parse, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BENCH = ROOT / "demo-data/eval/retrieval-benchmark"
SYNQUEST = os.environ.get("SYNANTON_SYNQUEST_URL", os.environ.get("SYNQUEST_URL", "http://localhost:18083"))
SECURITY = os.environ.get("SYNANTON_SECURITY_URL", os.environ.get("SECURITY_URL", "http://localhost:18081"))
TEI = os.environ.get("TEI_URL", "http://localhost:30801")
API = os.environ.get("SYNANTON_API_URL", "")
TENANT = os.environ.get("DEMO_TENANT", "demo-v2")
K = 20
TOKEN = ""
AUTH_EXPIRES = 0


def post_json(url: str, body: dict) -> dict:
    headers = {"Content-Type": "application/json"}
    if TOKEN:
        headers["Authorization"] = f"Bearer {TOKEN}"
    req = urllib.request.Request(url, data=json.dumps(body).encode(),
                                 headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.loads(response.read())


def mint_token() -> dict:
    return post_json(SECURITY + "/v1/auth/tenant", {
        "principal_id": "demo-runner",
        "tenant_id": TENANT,
        "capabilities": [],
    })


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
    global TOKEN, AUTH_EXPIRES
    if len(sys.argv) >= 3 and sys.argv[1] == "--neighbors":
        chunk_id = sys.argv[2]
        print(f"Targets: security={SECURITY} graph={API or SYNQUEST}")
        issued = mint_token()
        request = urllib.request.Request(
            (API or SYNQUEST) + "/v1/demo/graph/neighbors/" + urllib.parse.quote(chunk_id, safe=""),
            headers={"Authorization": "Bearer " + issued["token"]})
        with urllib.request.urlopen(request, timeout=30) as response:
            print(response.read().decode())
        return
    query = " ".join(sys.argv[1:]).strip()
    if not query:
        raise SystemExit("usage: run-query.sh <query> | --neighbors <chunk_id>")
    print(f"Targets: security={SECURITY} search={API or SYNQUEST}")
    issued = mint_token()
    TOKEN = issued["token"]
    AUTH_EXPIRES = int(issued["expires_at"])
    print(f"Auth: tenant={issued['tenant_id']} expires_at={AUTH_EXPIRES} "
          f"ttl_seconds={max(0, AUTH_EXPIRES - int(time.time()))}")
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
    fused = sorted(
        ((sum(part["score"] for part in parts.values()), chunk, parts)
         for chunk, parts in contributions.items()),
        key=lambda item: (
            -item[0],
            item[2].get("bm25", {}).get("rank", 10**9),
            item[1],
        ),
    )

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
