import json

from benchmark_runner.rerank import recall_report, rerank_documents, rerank_run


def _doc():
    return {
        "run_id": "r1",
        "environment": {"runner_version": "test"},
        "metrics_summary": {},
        "queries": [
            {"query_id": "q1",
             "top_k": [
                 {"chunk_id": "c1", "rank": 0, "score": 0.9, "snippet": "alpha one"},
                 {"chunk_id": "c2", "rank": 1, "score": 0.8, "snippet": "beta two"},
             ]},
            {"query_id": "q2",
             "top_k": [{"chunk_id": "c3", "rank": 0, "score": 0.5}]},
        ],
    }


def _queries():
    return [
        {"query_id": "q1", "text": "alpha", "gold": ["c2"]},
        {"query_id": "q2", "text": "beta", "gold": ["c3"]},
    ]


def test_recall_report_scores_gold_overlap():
    report = recall_report(_doc(), _queries())
    assert report["n"] == 2
    assert report["per_query"] == {"q1": 1.0, "q2": 1.0}
    assert report["mean"] == 1.0


def test_rerank_run_reorders_and_stamps(monkeypatch):
    calls = []

    def fake_embed(endpoint, model, query_text, documents, timeout_s=120):
        calls.append((model, query_text, list(documents)))
        # reverse the input order with descending scores
        return [(i, 1.0 - i * 0.1) for i in reversed(range(len(documents)))]

    monkeypatch.setattr("benchmark_runner.rerank.rerank_documents", fake_embed)
    out = rerank_run(_doc(), _queries(), "http://x", "m", depth=10, top_k=2)
    assert out["queries"][0]["top_k"][0]["chunk_id"] == "c2"
    assert out["queries"][0]["top_k"][0]["rank"] == 0
    assert out["environment"]["reranked"] is True
    assert out["environment"]["rerank_model"] == "m"
    assert out["metrics_summary"]["reranked_queries"] == 1
    # q2 has no snippet: passed through, counted skipped
    assert out["metrics_summary"]["rerank_skipped"] == 1
    assert calls[0][1] == "alpha"


def test_rerank_documents_parses_openai_shape(monkeypatch):
    import urllib.request

    class FakeResp:
        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def read(self):
            return json.dumps({"results": [
                {"index": 1, "relevance_score": 0.9},
                {"index": 0, "relevance_score": 0.1},
            ]}).encode()

    monkeypatch.setattr(urllib.request, "urlopen", lambda *a, **k: FakeResp())
    scored = rerank_documents("http://x", "m", "q", ["a", "b"])
    assert scored == [(1, 0.9), (0, 0.1)]
