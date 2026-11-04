#!/usr/bin/env python3
"""Build the demo-v2 graph fixture from hard entities and seed queries.

The graph is visualization-only. Nodes are chunks; edges connect chunks that
share a hard entity in the df 3-15 band. No soft entity becomes an edge.
"""
from __future__ import annotations
import json
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BENCH = ROOT / "demo-data/eval/retrieval-benchmark"


def main() -> None:
    hard = [json.loads(line) for line in (BENCH / "hard-entities-v2.jsonl").read_text().splitlines() if line.strip()]
    useful = [e for e in hard if 3 <= e["df"] <= 15]
    by_entity = {e["entity_id"]: e for e in useful}
    chunks: dict[str, set[str]] = defaultdict(set)
    for e in useful:
        for cid in e["chunk_ids"]:
            chunks[cid].add(e["entity_id"])

    dense = json.loads((BENCH / "dense-embeddings-v2.json").read_text())
    known_chunks = sorted({c["id"] for c in dense["chunks"]})

    seed_data = json.loads((BENCH / "graph-seed-v2.json").read_text())["seeds"]
    expected = {}
    for rows in seed_data.values():
        for row in rows:
            expected[row["gold"]] = row["neighbor_count"]

    graph: dict[str, dict] = {}
    for cid, entity_ids in sorted(chunks.items()):
        via = defaultdict(list)
        for eid in entity_ids:
            e = by_entity[eid]
            for neighbor in e["chunk_ids"]:
                if neighbor != cid:
                    via[neighbor].append({
                        "canonical_form": e["canonical_form"],
                        "type": e["type"],
                        "entity_kind": e["entity_kind"],
                        "df": e["df"],
                    })
        neighbors = [
            {"chunk_id": neighbor, "via": sorted(items, key=lambda x: (-x["df"], x["entity_kind"], x["canonical_form"]))}
            for neighbor, items in sorted(via.items())
        ]
        by_kind: dict[str, int] = defaultdict(int)
        for n in neighbors:
            for kind in {v["entity_kind"] for v in n["via"]}:
                by_kind[kind] += 1
        seed_entities = []
        for eid in sorted(entity_ids):
            e = by_entity[eid]
            seed_entities.append({
                "canonical_form": e["canonical_form"],
                "type": e["type"],
                "entity_kind": e["entity_kind"],
                "df": e["df"],
            })
        graph[cid] = {
            "neighbor_count": len(neighbors),
            "neighbors": neighbors,
            "neighbor_chunks": [n["chunk_id"] for n in neighbors],
            "entities": sorted(seed_entities, key=lambda x: (-x["df"], x["entity_kind"], x["canonical_form"])),
            "by_kind": dict(sorted(by_kind.items())),
        }

    for cid, count in expected.items():
        if cid not in graph:
            raise SystemExit(f"missing seed chunk in graph: {cid}")
        if graph[cid]["neighbor_count"] != count:
            raise SystemExit(f"neighbor count mismatch for {cid}: {graph[cid]['neighbor_count']} != {count}")

    out = {
        "corpus": "demo-v2",
        "corpus_digest": "5cf5b9553610482b2b5a1db2075def77b62c8cad6c8ddebcf080e6e26fd21e37",
        "filter": "hard && 3 <= df <= 15",
        "note": "Visualization-only chunk co-occurrence; not a retrieval leg.",
        "known_chunks": known_chunks,
        "chunks": graph,
    }
    path = BENCH / "graph-v2.json"
    path.write_text(json.dumps(out, indent=2) + "\n")
    print(f"wrote {path} chunks={len(graph)}")
    for cid, count in expected.items():
        print(f"{cid} neighbors={graph[cid]['neighbor_count']} (expected {count}) by_kind={graph[cid]['by_kind']}")


if __name__ == "__main__":
    main()
