#!/usr/bin/env python3
"""Compute intra/inter-rater agreement for the calibration batch.

Usage:
  calibration_kappa.py pass1.jsonl pass2.jsonl

Each pass is a copy of calibration-sheet-v1.jsonl with `grade` filled (0..3)
and grader_id/graded_at populated. Rows are joined by candidate_id.
"""
import json
import sys
from pathlib import Path


def load(path: str) -> dict[str, int]:
    rows = [json.loads(line) for line in Path(path).read_text().splitlines() if line.strip()]
    out = {}
    for row in rows:
        grade = row.get("grade")
        if not isinstance(grade, int) or not 0 <= grade <= 3:
            raise SystemExit(f"{path}: {row.get('candidate_id')} has no valid grade 0..3")
        out[row["candidate_id"]] = grade
    return out


def kappas(a: dict[str, int], b: dict[str, int]) -> dict:
    ids = sorted(set(a) & set(b))
    if not ids:
        raise SystemExit("no overlapping candidate_id values")
    n = len(ids)
    labels = [0, 1, 2, 3]
    observed = [[0] * 4 for _ in labels]
    for cid in ids:
        observed[a[cid]][b[cid]] += 1
    row = [sum(observed[i]) for i in range(4)]
    col = [sum(observed[i][j] for i in range(4)) for j in range(4)]
    po = sum(observed[i][i] for i in range(4)) / n
    pe = sum(row[i] * col[i] for i in range(4)) / (n * n)
    unweighted = (po - pe) / (1 - pe) if pe != 1 else 1.0
    num = den = 0.0
    for i in range(4):
        for j in range(4):
            w = ((i - j) ** 2) / 9.0
            num += w * observed[i][j]
            den += w * row[i] * col[j] / n
    weighted = 1 - (num / den) if den else 1.0
    return {
        "n": n,
        "exact_agreement": round(po, 6),
        "kappa_unweighted": round(unweighted, 6),
        "kappa_quadratic_weighted": round(weighted, 6),
        "label": "INTRA-RATER" if "intra" in " ".join(sys.argv).lower() else "INTER-RATER",
    }


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: calibration_kappa.py pass1.jsonl pass2.jsonl")
    print(json.dumps(kappas(load(sys.argv[1]), load(sys.argv[2])), indent=2))
