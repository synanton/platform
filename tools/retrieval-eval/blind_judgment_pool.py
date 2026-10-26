#!/usr/bin/env python3
"""Create a blind grading sheet + separate key from judgments-pool-v1.

The blind sheet strips source/rank/gold_binary. Keep the key out of the grading
UI/session; join only after grades are recorded.
"""
import hashlib
import json
import random
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BENCH = ROOT / "demo-data/eval/retrieval-benchmark"
POOL = BENCH / "judgments-pool-v1.jsonl"
QUERIES = BENCH / "queries-v2.rb-fixed-g5.jsonl"
SHEET = BENCH / "grading-sheet-v1.jsonl"
KEY = BENCH / "judgments-key-v1.jsonl"


def main() -> None:
    rows = [json.loads(line) for line in POOL.read_text().splitlines()]
    questions = {q["query_id"]: q["question"]
                 for q in map(json.loads, QUERIES.read_text().splitlines())}
    random.Random(20261025).shuffle(rows)
    sheet = []
    key = []
    for index, row in enumerate(rows):
        candidate_id = f"p{index:06d}"
        sheet.append({
            "candidate_id": candidate_id,
            "query_id": row["query_id"],
            "question": questions[row["query_id"]],
            "chunk_id": row["chunk_id"],
            "grade": None,
            "judged": False,
        })
        key.append({
            "candidate_id": candidate_id,
            "query_id": row["query_id"],
            "chunk_id": row["chunk_id"],
            "source": row["source"],
            "rank": row["rank"],
            "gold_binary": row["gold_binary"],
        })
    _write(SHEET, sheet)
    _write(KEY, key)
    print(f"wrote {len(sheet)} blind rows to {SHEET} and key to {KEY}")


def _write(path: Path, rows: list[dict]) -> None:
    with path.open("w") as out:
        for row in rows:
            out.write(json.dumps(row, sort_keys=True) + "\n")


if __name__ == "__main__":
    main()
