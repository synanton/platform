"""BR-A2.2 acceptance: metric computation against known fixtures."""

import pytest

from benchmark_runner.executor import RawQueryResult
from benchmark_runner.metrics import (
    eligible_identity,
    latency_percentiles,
    overlap,
    recall_at_k,
)


def hit(cid, rank=0, score=1.0):
    return {"chunk_id": cid, "rank": rank, "score": score}


def test_recall_at_k_counts_gold_hits():
    assert recall_at_k(["a", "b", "c", "d"], [hit("a"), hit("x"), hit("b")], 10) == 0.5


def test_recall_at_k_respects_cutoff():
    assert recall_at_k(["a", "b"], [hit("x"), hit("a")], 1) == 0.0
    assert recall_at_k(["a", "b"], [hit("x"), hit("a")], 2) == 0.5


def test_recall_empty_gold_is_none_not_zero():
    assert recall_at_k([], [hit("a")], 10) is None


def test_overlap_fraction_of_eligible():
    assert overlap([hit("a"), hit("x")], ["a", "b", "c", "d"]) == 0.25


def test_overlap_empty_eligible_is_none_not_zero():
    assert overlap([hit("a")], []) is None


def test_eligible_identity_is_set_equality():
    assert eligible_identity(["a", "b"], ["b", "a"]) is True
    assert eligible_identity(["a"], ["a", "b"]) is False


def test_latency_percentiles():
    got = latency_percentiles([10.0, 20.0, 30.0, 40.0])
    assert got["p50"] == 25.0
    assert got["p95"] == 38.5
    assert got["p99"] == pytest.approx(39.7)


def test_latency_empty_is_none():
    assert latency_percentiles([]) == {"p50": None, "p95": None, "p99": None}


def test_structural_legs_excluded_by_caller():
    # Taxonomy rule lives in collect_metrics; unit form: caller filters first.
    results = [
        RawQueryResult("q1", [], ["a"], 100.0, "t", "topo", False),
        RawQueryResult("q2", [], [], 9999.0, "t", "topo", True),
    ]
    timings = [r.timing_ms for r in results if not r.structural_empty]

    assert timings == [100.0]
    assert latency_percentiles(timings)["p50"] == 100.0
