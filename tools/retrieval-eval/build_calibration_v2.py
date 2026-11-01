#!/usr/bin/env python3
"""Select the 40-row v2 calibration batch from judgments-pool-v2.

Rule: first pass one candidate per query, preferring an unseen document; then
15 extra candidates, preferring unseen documents, max 2 per query. Shuffle the
selected rows with seed 20261026 and assign opaque candidate ids.
"""
import argparse
import json
import random
from collections import defaultdict


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--pool", required=True)
    p.add_argument("--queries", required=True)
    p.add_argument("--sheet", required=True)
    p.add_argument("--key", required=True)
    p.add_argument("--manifest", required=True)
    args = p.parse_args()
    rows = [json.loads(line) for line in open(args.pool) if line.strip()]
    questions = {q["query_id"]: q["question"] for q in map(json.loads, open(args.queries))}
    by_query = defaultdict(list)
    for row in rows:
        by_query[row["query_id"]].append(row)
    qids = sorted(by_query)
    chosen = []
    used_docs = set()
    selected = defaultdict(int)

    def choose(qid):
        candidates = [r for r in by_query[qid] if r not in chosen]
        unseen = [r for r in candidates if r["chunk_id"].split("#")[0] not in used_docs]
        return (unseen or candidates)[0]

    for qid in qids:
        row = choose(qid)
        chosen.append(row)
        selected[qid] += 1
        used_docs.add(row["chunk_id"].split("#")[0])
    while len(chosen) < 40:
        options = [q for q in qids if selected[q] < 2]
        qid = sorted(options, key=lambda q: (selected[q], q))[0]
        row = choose(qid)
        chosen.append(row)
        selected[qid] += 1
        used_docs.add(row["chunk_id"].split("#")[0])
    if len(chosen) != 40:
        raise SystemExit(f"expected 40 rows, got {len(chosen)}")
    random.Random(20261026).shuffle(chosen)
    sheet = []
    key = []
    for index, row in enumerate(chosen):
        cid = f"v2p{index:05d}"
        sheet.append({
            "candidate_id": cid,
            "query_id": row["query_id"],
            "question": questions[row["query_id"]],
            "chunk_id": row["chunk_id"],
            "grade": None,
            "judged": False,
        })
        key.append({
            "candidate_id": cid,
            "query_id": row["query_id"],
            "chunk_id": row["chunk_id"],
            "source": row["source"],
            "rank": row["rank"],
            "gold_binary": row["gold_binary"],
        })
    open(args.sheet, "w").write("".join(json.dumps(r, sort_keys=True) + "\n" for r in sheet))
    open(args.key, "w").write("".join(json.dumps(r, sort_keys=True) + "\n" for r in key))
    docs = sorted({r["chunk_id"].split("#")[0] for r in chosen})
    manifest = {
        "batch_version": "v2",
        "selection_rule": "one per query preferring unseen document; 15 extra preferring unseen documents; max 2 per query",
        "seed": 20261026,
        "rng": "Python random.Random (Mersenne Twister)",
        "script": "tools/retrieval-eval/build_calibration_v2.py",
        "pool": args.pool,
        "rows": len(sheet),
        "queries": len({r["query_id"] for r in chosen}),
        "documents": len(docs),
        "per_query_counts": {q: selected[q] for q in sorted(selected)},
        "document_ids": docs,
    }
    open(args.manifest, "w").write(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    print(f"rows={len(sheet)} queries={manifest['queries']} documents={manifest['documents']}")


if __name__ == "__main__":
    main()
