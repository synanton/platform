#!/usr/bin/env python3
"""Build a deterministic first-pass judgment pool for queries-v2.

Pool = BM25 top-20 from T01-q25.hits.json + 10 deterministic random chunks from
the same 81-chunk corpus. This is a candidate pool, not graded judgments.
Dense top-k is absent until #440 lands, so the pool is biased against dense.
"""
import hashlib
import json
import random
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BENCH = ROOT / "demo-data/eval/retrieval-benchmark"
HITS = BENCH / "results/T01-q25.hits.json"
QUERIES = BENCH / "queries-v2.rb-fixed-g5.jsonl"
OUT = BENCH / "judgments-pool-v1.jsonl"


def main() -> None:
    hits = json.loads(HITS.read_text())["queries"]
    queries = {q["query_id"]: q for q in map(json.loads, QUERIES.read_text().splitlines())}
    corpus = sorted({chunk for q in hits for chunk in q["retrieved"]})
    rows = []
    for q in hits:
        qid = q["query_id"]
        gold = set(queries[qid].get("gold_chunk_ids") or [])
        top = q["retrieved"][:20]
        rng = random.Random(int(hashlib.sha256(qid.encode()).hexdigest()[:12], 16) ^ 42)
        random_ids = rng.sample([c for c in corpus if c not in top], 10)
        for rank, chunk in enumerate(top, start=1):
            rows.append(_row(qid, chunk, gold, "bm25_top20", rank))
        for chunk in random_ids:
            rows.append(_row(qid, chunk, gold, "random", None))
    with OUT.open("w") as out:
        for row in rows:
            out.write(json.dumps(row, sort_keys=True) + "\n")
    print(f"wrote {len(rows)} candidates for {len(hits)} queries to {OUT}")


def _row(qid: str, chunk: str, gold: set[str], source: str, rank: int | None) -> dict:
    return {
        "query_id": qid,
        "chunk_id": chunk,
        "gold_binary": chunk in gold,
        "source": source,
        "rank": rank,
        "grade": None,
        "judged": False,
    }


if __name__ == "__main__":
    main()
