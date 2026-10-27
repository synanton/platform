#!/usr/bin/env python3
"""Build judgments-pool-v2 from the current 317-chunk corpus.

Rule: BM25 top-20 + 10 deterministic random chunks per query.
Seed: 42, per query: random.Random(int(sha256(query_id)[:12], 16) ^ 42).
Gold: PDF queries are re-derived from gold_markers against current chunks;
RB queries carry v1 gold_chunk_ids (short documents, stable IDs).
"""
import argparse
import csv
import hashlib
import json
import random
import re
from collections import Counter, OrderedDict
from pathlib import Path


def norm(value: str) -> str:
    text = (value or "").replace("\\n", " ").replace("\\ ", " ").replace("\\", "")
    return re.sub(r"\s+", " ", text).strip().casefold()


def load_chunks(path: Path, tenant: str):
    csv.field_size_limit(10**9)
    with path.open() as handle:
        rows = [row for row in csv.DictReader(handle) if row["tenant_id"] == tenant]
    by_doc = OrderedDict()
    for row in rows:
        by_doc.setdefault(row["content_ref_id"], []).append(
            (int(row["chunk_ordinal"]), row["chunk_text"]))
    return by_doc


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--hits", required=True)
    parser.add_argument("--chunks", required=True)
    parser.add_argument("--queries", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--tenant", default="rb-fixed-g5")
    parser.add_argument("--corpus-digest", required=True)
    parser.add_argument("--gold-overrides")
    args = parser.parse_args()

    hits = json.loads(Path(args.hits).read_text())["queries"]
    queries = {q["query_id"]: q for q in map(json.loads, Path(args.queries).read_text().splitlines())}
    by_doc = load_chunks(Path(args.chunks), args.tenant)
    overrides = json.loads(Path(args.gold_overrides).read_text()) if args.gold_overrides else {}
    all_chunks = sorted(f"{doc}#{ordinal}" for doc, chunks in by_doc.items() for ordinal, _ in chunks)
    doc_text = {doc: {ordinal: text for ordinal, text in chunks} for doc, chunks in by_doc.items()}

    rows = []
    counts = {}
    diversity = {}
    for hit in hits:
        qid = hit["query_id"]
        query = queries[qid]
        top = hit["retrieved"][:20]
        rng = random.Random(int(hashlib.sha256(qid.encode()).hexdigest()[:12], 16) ^ 42)
        random_ids = rng.sample([chunk for chunk in all_chunks if chunk not in top], 10)
        markers = query.get("gold_markers") or []
        gold = set()
        gold_source = "carried_v1"
        if qid in overrides:
            gold = set(overrides[qid]["gold_chunk_ids"])
            gold_source = "manual_override_v2"
        elif markers:
            gold_source = "marker_recomputed_v2"
            for doc, chunks in by_doc.items():
                for ordinal, text in chunks:
                    if any(norm(marker) in norm(text) for marker in markers):
                        gold.add(f"{doc}#{ordinal}")
        else:
            gold = set(query.get("gold_chunk_ids") or [])
        for rank, chunk in enumerate(top, start=1):
            rows.append({"query_id": qid, "chunk_id": chunk, "source": "bm25_top20",
                         "rank": rank, "gold_binary": chunk in gold, "gold_source": gold_source,
                         "grade": None, "judged": False})
        for chunk in random_ids:
            rows.append({"query_id": qid, "chunk_id": chunk, "source": "random",
                         "rank": None, "gold_binary": chunk in gold, "gold_source": gold_source,
                         "grade": None, "judged": False})
        docs = {row["chunk_id"].split("#")[0] for row in rows if row["query_id"] == qid}
        diversity[qid] = len(docs)
        counts[qid] = len(top) + len(random_ids)

    out = Path(args.out)
    out.write_text("".join(json.dumps(row, sort_keys=True) + "\n" for row in rows))
    div_values = list(diversity.values())
    manifest = {
        "pool_version": "v2",
        "status": "rebuilt_on_current_hierarchical_chunking",
        "supersedes": "judgments-pool-v1.jsonl",
        "corpus_digest": args.corpus_digest,
        "corpus_chunks": len(all_chunks),
        "corpus_documents": len(by_doc),
        "bm25_run": args.hits,
        "pool_rule": "BM25 top-20 + 10 deterministic random chunks per query",
        "seed": 42,
        "seed_derivation": "int(sha256(query_id).hexdigest()[:12], 16) ^ 42",
        "rng": "Python random.Random (Mersenne Twister)",
        "script": "tools/retrieval-eval/build_judgment_pool_v2.py",
        "pool_rows": len(rows),
        "per_query_counts": counts,
        "document_diversity": {
            "metric": "distinct content_ref documents among the 30 candidates",
            "mean": round(sum(div_values) / len(div_values), 3),
            "min": min(div_values),
            "max": max(div_values),
            "per_query": diversity,
        },
        "gold": "PDF gold re-derived from markers on current chunks; manual overrides for pdf013/pdf015; RB gold carried from v1",
        "gold_overrides": args.gold_overrides,
    }
    Path(args.manifest).write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    print(f"wrote {len(rows)} rows; {len(counts)} queries; mean docs/query {manifest['document_diversity']['mean']}")


if __name__ == "__main__":
    main()
