"""Retrieval quality metrics for the benchmark.

Implements exactly the metrics named in docs/research/retrieval-evaluation-benchmark-plan.md
§5 (primary: Recall@10, NDCG@10; secondary: MRR@10, Recall@100, Precision@10),
which are themselves taken from Design 1.31 §85/87. Pure functions, no I/O -
these operate on already-retrieved ranked ID lists so they're testable without
a running stack.
"""

from __future__ import annotations

import math
from dataclasses import dataclass


def recall_at_k(retrieved: list[str], relevant: set[str], k: int) -> float:
    """Fraction of `relevant` found within the top k of `retrieved`.

    Returns 0.0 (not NaN) when `relevant` is empty, since an unanswerable
    query with an empty gold set trivially has nothing to recall - callers
    that need to distinguish "no gold labels" from "found nothing" should
    check `len(relevant) == 0` themselves before calling this.
    """
    if not relevant:
        return 0.0
    top_k = set(retrieved[:k])
    return len(top_k & relevant) / len(relevant)


def precision_at_k(retrieved: list[str], relevant: set[str], k: int) -> float:
    """Fraction of the top k of `retrieved` that are in `relevant`."""
    top_k = retrieved[:k]
    if not top_k:
        return 0.0
    hits = sum(1 for doc_id in top_k if doc_id in relevant)
    return hits / len(top_k)


def mrr(retrieved: list[str], relevant: set[str], k: int | None = None) -> float:
    """Reciprocal rank of the first relevant document in `retrieved`.

    0.0 if no relevant document appears in the top `k` (or anywhere, if
    `k` is None).
    """
    candidates = retrieved[:k] if k is not None else retrieved
    for rank, doc_id in enumerate(candidates, start=1):
        if doc_id in relevant:
            return 1.0 / rank
    return 0.0


def ndcg_at_k(retrieved: list[str], relevance_grades: dict[str, int], k: int) -> float:
    """Normalized Discounted Cumulative Gain @ k.

    `relevance_grades` maps doc_id -> graded relevance (e.g. 0/1/2 per
    Design 1.31 §91's 0-3 scale, or a binary 0/1 set expressed as a dict).
    A doc_id retrieved but absent from `relevance_grades` is treated as
    grade 0, not an error - most retrieved documents for a real query are
    not in the gold set at all.
    """
    if not relevance_grades or all(g <= 0 for g in relevance_grades.values()):
        return 0.0

    def _dcg(grades: list[int]) -> float:
        return sum(g / math.log2(i + 2) for i, g in enumerate(grades))

    top_k = retrieved[:k]
    gains = [relevance_grades.get(doc_id, 0) for doc_id in top_k]
    dcg = _dcg(gains)

    ideal_gains = sorted(relevance_grades.values(), reverse=True)[:k]
    idcg = _dcg(ideal_gains)

    if idcg == 0:
        return 0.0
    return dcg / idcg


@dataclass(frozen=True)
class QueryResult:
    """One query's retrieval result plus its gold labels, ready to score."""

    query_id: str
    retrieved: list[str]
    relevant: set[str]
    relevance_grades: dict[str, int] | None = None
    latency_ms: float | None = None

    def grades(self) -> dict[str, int]:
        if self.relevance_grades is not None:
            return self.relevance_grades
        # Binary fallback: every relevant doc is grade 1.
        return {doc_id: 1 for doc_id in self.relevant}


@dataclass(frozen=True)
class QueryMetrics:
    query_id: str
    recall_at_10: float
    recall_at_100: float
    precision_at_10: float
    ndcg_at_10: float
    mrr_at_10: float
    latency_ms: float | None


def evaluate_query(result: QueryResult) -> QueryMetrics:
    """Compute the full metric set (§5) for a single query result."""
    grades = result.grades()
    return QueryMetrics(
        query_id=result.query_id,
        recall_at_10=recall_at_k(result.retrieved, result.relevant, 10),
        recall_at_100=recall_at_k(result.retrieved, result.relevant, 100),
        precision_at_10=precision_at_k(result.retrieved, result.relevant, 10),
        ndcg_at_10=ndcg_at_k(result.retrieved, grades, 10),
        mrr_at_10=mrr(result.retrieved, result.relevant, 10),
        latency_ms=result.latency_ms,
    )


@dataclass(frozen=True)
class AggregateMetrics:
    query_count: int
    mean_recall_at_10: float
    mean_recall_at_100: float
    mean_precision_at_10: float
    mean_ndcg_at_10: float
    mean_mrr_at_10: float
    p50_latency_ms: float | None
    p95_latency_ms: float | None


def _percentile(values: list[float], pct: float) -> float:
    """Nearest-rank percentile - no interpolation, deterministic on small n."""
    if not values:
        raise ValueError("cannot compute a percentile of an empty list")
    ordered = sorted(values)
    rank = max(0, min(len(ordered) - 1, math.ceil(pct / 100 * len(ordered)) - 1))
    return ordered[rank]


def aggregate(per_query: list[QueryMetrics]) -> AggregateMetrics:
    """Aggregate per-query metrics into the summary this benchmark reports.

    p95 latency is the one primary metric (§5) that isn't a simple mean -
    computed only from queries that actually recorded a latency.
    """
    if not per_query:
        raise ValueError("cannot aggregate an empty result set")

    n = len(per_query)
    latencies = [m.latency_ms for m in per_query if m.latency_ms is not None]

    return AggregateMetrics(
        query_count=n,
        mean_recall_at_10=sum(m.recall_at_10 for m in per_query) / n,
        mean_recall_at_100=sum(m.recall_at_100 for m in per_query) / n,
        mean_precision_at_10=sum(m.precision_at_10 for m in per_query) / n,
        mean_ndcg_at_10=sum(m.ndcg_at_10 for m in per_query) / n,
        mean_mrr_at_10=sum(m.mrr_at_10 for m in per_query) / n,
        p50_latency_ms=_percentile(latencies, 50) if latencies else None,
        p95_latency_ms=_percentile(latencies, 95) if latencies else None,
    )
