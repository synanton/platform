#!/usr/bin/env python3
"""Compute intra/inter-rater agreement for the calibration batch.

Usage:
  calibration_kappa.py pass1.jsonl pass2.jsonl

Each pass is a copy of calibration-sheet-v1.jsonl with `grade` filled (0..3)
and grader_id/graded_at populated. Rows are joined by candidate_id.
"""
import json
import random
import sys
from pathlib import Path

LABELS = [0, 1, 2, 3]


def load(path: str) -> dict[str, int]:
    rows = [json.loads(line) for line in Path(path).read_text().splitlines() if line.strip()]
    out = {}
    for row in rows:
        grade = row.get("grade")
        if not isinstance(grade, int) or not 0 <= grade <= 3:
            raise SystemExit(f"{path}: {row.get('candidate_id')} has no valid grade 0..3")
        out[row["candidate_id"]] = grade
    return out


def _observed(pairs: list[tuple[int, int]]) -> list[list[int]]:
    observed = [[0] * 4 for _ in LABELS]
    for a, b in pairs:
        observed[a][b] += 1
    return observed


def _weighted_kappa(observed: list[list[int]]) -> float:
    n = sum(sum(row) for row in observed)
    if n == 0:
        return 1.0
    row = [sum(observed[i]) for i in range(4)]
    col = [sum(observed[i][j] for i in range(4)) for j in range(4)]
    num = den = 0.0
    for i in range(4):
        for j in range(4):
            weight = ((i - j) ** 2) / 9.0
            num += weight * observed[i][j]
            den += weight * row[i] * col[j] / n
    return 1 - (num / den) if den else 1.0


def _unweighted_kappa(observed: list[list[int]]) -> float:
    n = sum(sum(row) for row in observed)
    if n == 0:
        return 1.0
    row = [sum(observed[i]) for i in range(4)]
    col = [sum(observed[i][j] for i in range(4)) for j in range(4)]
    po = sum(observed[i][i] for i in range(4)) / n
    pe = sum(row[i] * col[i] for i in range(4)) / (n * n)
    return (po - pe) / (1 - pe) if pe != 1 else 1.0


def _bootstrap_ci(pairs: list[tuple[int, int]]) -> list[float]:
    rng = random.Random(20261026)
    values = []
    for _ in range(2000):
        sample = [pairs[rng.randrange(len(pairs))] for _ in pairs]
        values.append(_weighted_kappa(_observed(sample)))
    values.sort()
    return [round(values[49], 6), round(values[1949], 6)]


def kappas(a: dict[str, int], b: dict[str, int]) -> dict:
    ids = sorted(set(a) & set(b))
    if not ids:
        raise SystemExit("no overlapping candidate_id values")
    pairs = [(a[cid], b[cid]) for cid in ids]
    observed = _observed(pairs)
    n = len(ids)
    po = sum(observed[i][i] for i in range(4)) / n
    return {
        "n": n,
        "exact_agreement": round(po, 6),
        "kappa_unweighted": round(_unweighted_kappa(observed), 6),
        "kappa_quadratic_weighted": round(_weighted_kappa(observed), 6),
        "kappa_quadratic_weighted_ci95": _bootstrap_ci(pairs),
        "confusion_matrix": observed,
        "confusion_matrix_labels": LABELS,
        "disagreements": [
            {"candidate_id": cid, "pass1": a[cid], "pass2": b[cid]}
            for cid in ids if a[cid] != b[cid]
        ],
        "label": "INTRA-RATER" if "intra" in " ".join(sys.argv).lower() else "INTER-RATER",
    }


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: calibration_kappa.py pass1.jsonl pass2.jsonl")
    print(json.dumps(kappas(load(sys.argv[1]), load(sys.argv[2])), indent=2))
