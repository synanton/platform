from retrieval_eval.metrics import (
    AggregateMetrics,
    QueryMetrics,
    QueryResult,
    aggregate,
    evaluate_query,
    mrr,
    ndcg_at_k,
    precision_at_k,
    recall_at_k,
)


def test_recall_at_k_basic():
    retrieved = ["a", "b", "c", "d"]
    relevant = {"b", "d", "z"}
    assert recall_at_k(retrieved, relevant, k=4) == 2 / 3
    assert recall_at_k(retrieved, relevant, k=1) == 0.0


def test_recall_at_k_empty_gold_is_zero_not_nan():
    assert recall_at_k(["a"], set(), k=10) == 0.0


def test_precision_at_k_basic():
    retrieved = ["a", "b", "c"]
    relevant = {"a", "c"}
    assert precision_at_k(retrieved, relevant, k=3) == 2 / 3
    assert precision_at_k([], relevant, k=10) == 0.0


def test_mrr_first_hit_position():
    assert mrr(["a", "b", "c"], {"b"}) == 1 / 2
    assert mrr(["a", "b", "c"], {"a"}) == 1.0
    assert mrr(["a", "b", "c"], {"z"}) == 0.0


def test_mrr_respects_k_cutoff():
    assert mrr(["a", "b", "c"], {"c"}, k=2) == 0.0


def test_ndcg_perfect_ranking_is_one():
    retrieved = ["a", "b", "c"]
    grades = {"a": 2, "b": 1, "c": 0}
    assert ndcg_at_k(retrieved, grades, k=3) == 1.0


def test_ndcg_reversed_ranking_is_less_than_one():
    retrieved = ["c", "b", "a"]  # worst-first
    grades = {"a": 2, "b": 1, "c": 0}
    score = ndcg_at_k(retrieved, grades, k=3)
    assert 0.0 < score < 1.0


def test_ndcg_no_relevance_is_zero():
    assert ndcg_at_k(["a", "b"], {}, k=10) == 0.0
    assert ndcg_at_k(["a", "b"], {"a": 0, "b": 0}, k=10) == 0.0


def test_evaluate_query_binary_fallback_grades():
    result = QueryResult(query_id="q1", retrieved=["x", "y", "z"], relevant={"y"})
    metrics = evaluate_query(result)
    assert isinstance(metrics, QueryMetrics)
    assert metrics.query_id == "q1"
    assert metrics.mrr_at_10 == 1 / 2
    assert metrics.recall_at_10 == 1.0


def test_aggregate_computes_mean_and_p95():
    per_query = [
        evaluate_query(QueryResult(query_id="q1", retrieved=["a"], relevant={"a"}, latency_ms=100)),
        evaluate_query(QueryResult(query_id="q2", retrieved=["b"], relevant={"z"}, latency_ms=200)),
    ]
    agg = aggregate(per_query)
    assert isinstance(agg, AggregateMetrics)
    assert agg.query_count == 2
    assert agg.mean_recall_at_10 == 0.5
    assert agg.p95_latency_ms in (100, 200)  # nearest-rank percentile of a 2-element list
