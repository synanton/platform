"""B1-G G4: pacing, daily budget, spend check, validity, gpu_plane record fields, rescore.

The CLI is exercised end-to-end with synquest and the spend command faked, so no stack or
provider key is needed.
"""

import json
from pathlib import Path

import pytest
import yaml

from retrieval_eval import cli
from retrieval_eval.budget import BudgetExhausted, RequestBudget, RequestLedger, Throttle
from retrieval_eval.query import IndexStats, SearchHit, SearchResult, SearchUnavailable
from retrieval_eval.spend import SpendSnapshot, parse_snapshot
from retrieval_eval.validity import check_run, dense_expected

# ─── budget / throttle / spend ───────────────────────────────────────────────


def test_ledger_counts_per_day_and_plane(tmp_path):
    ledger = RequestLedger(tmp_path / "l.json")
    ledger.add("gpu-7", 3)
    ledger.add("gpu-7", 2)
    ledger.add("gpu-5", 1)
    assert ledger.used_today("gpu-7") == 5
    assert ledger.used_today("gpu-5") == 1


def test_budget_stops_before_exceeding(tmp_path):
    budget = RequestBudget(RequestLedger(tmp_path / "l.json"), "gpu-7", daily_limit=2)
    budget.ensure(1)
    budget.record(2)
    with pytest.raises(BudgetExhausted):
        budget.ensure(1)
    assert RequestBudget(RequestLedger(tmp_path / "l.json"), "gpu-7", 0).remaining() is None


def test_throttle_spaces_calls_evenly():
    now = [0.0]
    slept = []
    t = Throttle(30, clock=lambda: now[0], sleep=lambda d: (slept.append(d), now.__setitem__(0, now[0] + d)))
    for _ in range(3):
        t.wait()
    assert slept == [2.0, 2.0]  # 30/min → one slot every 2 s; the first call is free
    assert Throttle(0).wait() == 0.0


def test_spend_snapshot_parses_gpu7_check_usage_line():
    out = 'noise\n{"usage": 0.00052275, "free_model_daily_requests": {"used": 62, "limit": 1000}}\n'
    snap = parse_snapshot(out)
    assert snap.usage_usd == 0.00052275
    assert snap.free_requests_used == 62


# ─── validity ────────────────────────────────────────────────────────────────

FULL = IndexStats(doc_count=10, embedding_model="synanton-free-embedding", embedding_dim=1024,
                  vector_docs=10, dim_mismatches=0, missing_vectors=0)


def _check(**kw):
    base = dict(dense=True, embed_skipped_queries=[], failed_queries=[], stats=FULL,
                embedding_model="synanton-free-embedding", embedding_dim=1024,
                spend_before=0.5, spend_after=0.5, free_only=True, aborted=None)
    base.update(kw)
    return check_run(**base)


def test_clean_run_is_valid():
    assert _check().valid


@pytest.mark.parametrize("override, fragment", [
    ({"embed_skipped_queries": ["rb001"]}, "embedding skipped"),
    ({"failed_queries": ["rb002"]}, "queries failed"),
    ({"stats": IndexStats(10, "synanton-free-embedding", 1024, 7, 3, 0)}, "not fully vectorised"),
    ({"stats": IndexStats(10, None, None, None, None, None)}, "no coverage report"),
    ({"stats": IndexStats(10, "other-model", 1024, 10, 0, 0)}, "index built with model"),
    ({"embedding_dim": 768}, "index dim"),
    ({"spend_after": 0.51}, "spend increased"),
    ({"spend_before": None}, "without a spend check"),
    ({"aborted": "budget"}, "aborted"),
])
def test_each_gap_invalidates_the_run(override, fragment):
    v = _check(**override)
    assert not v.valid
    assert any(fragment in r for r in v.reasons), v.reasons


def test_bm25_only_runs_do_not_need_vectors():
    assert not dense_expected("none", None)
    assert not dense_expected("bge-base-en-v1.5", 0)
    assert dense_expected("synanton-free-embedding", None)
    v = _check(dense=False, stats=IndexStats(10, None, None, None, None, None), embed_skipped_queries=["x"],
               free_only=False)
    assert v.valid


# ─── CLI evaluate / rescore, synquest and spend faked ────────────────────────


@pytest.fixture
def queries(tmp_path):
    p = tmp_path / "q.jsonl"
    p.write_text("\n".join(json.dumps(r) for r in [
        {"query_id": "q1", "question": "alpha?", "gold_chunk_ids": ["r#0"]},
        {"query_id": "q2", "question": "beta?", "gold_chunk_ids": ["r#1"]},
        {"query_id": "q3", "question": "alpha?", "gold_chunk_ids": ["r#0"]},
    ]) + "\n")
    return p


def _hit(cid):
    return SearchHit(cid, 1.0, 0.5, 0.5, "file:///a", "", "")


def _fake(monkeypatch, *, skipped=(), fail=(), stats=FULL, spend=(0.5, 0.5)):
    seen = []

    def search(url, tenant, q, **kw):
        seen.append(q)
        if q in fail:
            raise SearchUnavailable("503")
        return SearchResult(hits=[_hit("r#0"), _hit("r#1")], latency_ms=100.0, query_usage_present=True,
                            embed_skipped=q in skipped, embed_cached=seen.count(q) > 1, embed_ms=40.0)

    spends = iter(spend)
    monkeypatch.setattr(cli.compose, "synquest_base_url", lambda: "http://synquest")
    monkeypatch.setattr(cli, "search", search)
    monkeypatch.setattr(cli, "index_stats", lambda url, tenant: stats)
    monkeypatch.setattr(cli, "snapshot", lambda cmd: SpendSnapshot(next(spends), 62))
    return seen


def _evaluate(tmp_path, queries, *extra):
    return cli.main(["evaluate", "--tenant", "rb-fixed-g", "--queries", str(queries), "--run-id", "T03-G",
                     "--dataset-version", "d", "--knowledge-version", "k", "--search-config", "hybrid-rrf",
                     "--embedding-model", "synanton-free-embedding", "--retrieval-strategy", "hybrid",
                     "--output-dir", str(tmp_path / "results"), "--ledger", str(tmp_path / "ledger.json"),
                     "--gpu-plane", "gpu-7", "--max-rpm", "0", "--spend-cmd", "fake", *extra])


def test_valid_gpu7_run_records_plane_fields_and_counts_requests(tmp_path, queries, monkeypatch):
    _fake(monkeypatch)
    assert _evaluate(tmp_path, queries) == 0

    rec = yaml.safe_load((tmp_path / "results" / "T03-G.yaml").read_text())
    assert rec["validity"] == {"valid": True, "reasons": []}
    g = rec["gpu_plane"]
    assert g["plane"] == "gpu-7" and g["provider_mode"] == "external-free"
    assert g["embedding_dim"] == 1024
    assert g["embed_requests"] == 2 and g["embed_cached"] == 1  # q3 repeats q1's text
    assert g["spend_before"]["usage_usd"] == g["spend_after"]["usage_usd"]
    assert rec["latency_breakdown"]["queries"] == 2
    assert RequestLedger(tmp_path / "ledger.json").used_today("gpu-7") == 2
    assert (tmp_path / "results" / "T03-G.hits.json").is_file()


def test_skipped_embedding_makes_the_run_invalid_and_moves_it_aside(tmp_path, queries, monkeypatch):
    _fake(monkeypatch, skipped={"beta?"})
    assert _evaluate(tmp_path, queries) == 2
    assert not (tmp_path / "results" / "T03-G.yaml").exists()
    rec = yaml.safe_load((tmp_path / "results" / "invalid" / "T03-G.yaml").read_text())
    assert rec["validity"]["valid"] is False
    assert any("embedding skipped" in r for r in rec["validity"]["reasons"])


def test_fail_closed_503_is_a_failed_query_not_a_lexical_result(tmp_path, queries, monkeypatch):
    _fake(monkeypatch, fail={"beta?"})
    assert _evaluate(tmp_path, queries) == 2
    rec = yaml.safe_load((tmp_path / "results" / "invalid" / "T03-G.yaml").read_text())
    assert rec["metrics"]["query_count"] == 2
    assert any("queries failed: q2" in r for r in rec["validity"]["reasons"])


def test_spend_increase_invalidates(tmp_path, queries, monkeypatch):
    _fake(monkeypatch, spend=(0.5, 0.6))
    assert _evaluate(tmp_path, queries) == 2


def test_budget_stops_the_run_before_the_limit(tmp_path, queries, monkeypatch):
    seen = _fake(monkeypatch)
    RequestLedger(tmp_path / "ledger.json").add("gpu-7", 9)
    assert _evaluate(tmp_path, queries, "--daily-request-budget", "10") == 2
    assert len(seen) == 1  # stopped before the 2nd search
    rec = yaml.safe_load((tmp_path / "results" / "invalid" / "T03-G.yaml").read_text())
    assert any("aborted" in r for r in rec["validity"]["reasons"])


def test_rescore_recomputes_from_saved_hits_without_searching(tmp_path, queries, monkeypatch):
    _fake(monkeypatch)
    assert _evaluate(tmp_path, queries) == 0
    monkeypatch.setattr(cli, "search", lambda *a, **k: (_ for _ in ()).throw(AssertionError("searched")))

    regold = tmp_path / "q2.jsonl"
    regold.write_text("\n".join(json.dumps(r) for r in [
        {"query_id": "q1", "question": "alpha?", "gold_chunk_ids": ["r#1"]},
        {"query_id": "q2", "question": "beta?", "gold_chunk_ids": ["r#1"]},
        {"query_id": "q3", "question": "alpha?", "gold_chunk_ids": ["r#1"]},
    ]) + "\n")
    assert cli.main(["rescore", "--hits", str(tmp_path / "results" / "T03-G.hits.json"), "--queries", str(regold),
                     "--run-id", "T03-G-rescored", "--output-dir", str(tmp_path / "results")]) == 0
    rec = yaml.safe_load((tmp_path / "results" / "T03-G-rescored.yaml").read_text())
    assert rec["metrics"]["mean_mrr_at_10"] == pytest.approx(0.5)  # r#1 is at rank 2 for every query
    assert rec["gpu_plane"]["plane"] == "gpu-7" and rec["rescored_from"] == "T03-G.hits.json"


def test_legacy_runs_keep_their_record_shape(tmp_path, queries, monkeypatch):
    _fake(monkeypatch)
    rc = cli.main(["evaluate", "--tenant", "rb-fixed", "--queries", str(queries), "--run-id", "T01",
                   "--dataset-version", "d", "--knowledge-version", "k", "--search-config", "bm25-only",
                   "--embedding-model", "none", "--retrieval-strategy", "bm25", "--top-k-dense", "0",
                   "--output-dir", str(tmp_path / "results"), "--ledger", str(tmp_path / "ledger.json")])
    assert rc == 0
    rec = yaml.safe_load((tmp_path / "results" / "T01.yaml").read_text())
    assert "gpu_plane" not in rec
    assert rec["validity"]["valid"] is True
    assert RequestLedger(tmp_path / "ledger.json").used_today("none") == 0


def test_index_stats_sends_the_tenant_header(monkeypatch):
    """synquest resolves /index/stats' tenant from X-Tenant (default 'demo'), not from ?tenant=."""
    from retrieval_eval import query
    seen = {}

    class R:
        def raise_for_status(self): pass
        def json(self): return {"tenant": "rb-fixed-g5", "doc_count": 3, "vector_docs": 3, "dim_mismatches": 0,
                                "missing_vectors": 0, "embedding_model": "m", "embedding_dim": 768}

    def get(url, params=None, headers=None, timeout=None):
        seen.update(params=params, headers=headers)
        return R()

    monkeypatch.setattr(query.requests, "get", get)
    st = query.index_stats("http://synquest", "rb-fixed-g5")
    assert seen["headers"] == {"X-Tenant": "rb-fixed-g5"}
    assert st.fully_vectorised is True and st.embedding_dim == 768
