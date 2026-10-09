"""B5.2 bridge tests: Q3 → Result Manifest on synthetic inputs."""

import json

from benchmark_runner.q3 import convert, q3_to_results


def make_q3():
    return {
        "run_id": "leg",
        "corpus": "mini",
        "queries": [
            {"query_id": "q1", "top_k": [{"chunk_id": "c1", "rank": 0, "score": 1.0}],
             "eligible_set": ["c1", "c2"], "timing_ms": 5.0, "timing_scope": "leg"},
            {"query_id": "q2", "top_k": [], "eligible_set": ["c3"],
             "timing_ms": 7.0, "timing_scope": "leg", "structural_empty": True},
        ],
    }


def test_split_joins_gold():
    queries, results = q3_to_results(make_q3(), {"q1": ["c1"], "q2": []})

    assert [q["query_id"] for q in queries] == ["q1", "q2"]
    assert queries[0]["gold"] == ["c1"]
    assert results[0].topology == ""
    assert results[1].structural_empty is True


def test_convert_produces_valid_artifact(tmp_path):
    q3_path = tmp_path / "leg.json"
    q3_path.write_text(json.dumps(make_q3()))
    manifest = {"manifest_id": "m1", "metrics": {"recall_at_k": [10]},
                "corpus": {"ref": "cas:" + "00" * 32, "alias": "t", "dataset_version": "t"},
                "reproducibility": {"seed": 3}}

    path = convert(q3_path, manifest, "ydb-ydb", "m1-ydb", {"q1": ["c1"]},
                   "ydb_kmeans", "deadbeef", tmp_path / "out",
                   environment={"runner_version": "test"})

    document = json.loads(path.read_text())
    assert document["run_id"] == "m1-ydb"
    assert document["composition_id"] == "ydb-ydb"
    assert document["metrics_summary"]["recall_at_k"]["10"] == 1.0
    assert all(q["topology"] == "ydb_kmeans" for q in document["queries"])
    assert document["reproducibility"] == {
        "verified": False, "corpus_hash": "deadbeef", "seed": 3}
