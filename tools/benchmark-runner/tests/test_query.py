"""BR-A3.2 acceptance: list/show over stored Result Manifests."""

import json

import pytest

from benchmark_runner.query import ResultNotFound, list_results, show_result


def write_result(directory, run_id, composition="c1", queries=2):
    document = {
        "run_id": run_id,
        "corpus": "demo",
        "manifest_id": "m1",
        "composition_id": composition,
        "queries": [
            {
                "query_id": f"q{i}",
                "top_k": [],
                "eligible_set": [],
                "timing_ms": 1.0,
                "timing_scope": "t",
            }
            for i in range(queries)
        ],
    }
    (directory / f"{run_id}.result.json").write_text(json.dumps(document))
    return document


def test_list_returns_summaries_sorted(tmp_path):
    write_result(tmp_path, "run-b", composition="c2", queries=1)
    write_result(tmp_path, "run-a", composition="c1", queries=3)

    summaries = list_results(tmp_path)

    assert [s["run_id"] for s in summaries] == ["run-a", "run-b"]
    assert summaries[0]["query_count"] == 3
    assert summaries[1]["composition_id"] == "c2"


def test_list_empty_dir_returns_empty(tmp_path):
    assert list_results(tmp_path / "absent") == []


def test_list_skips_event_files(tmp_path):
    write_result(tmp_path, "run-a")
    (tmp_path / "run-a.event.json").write_text("{}")

    assert [s["run_id"] for s in list_results(tmp_path)] == ["run-a"]


def test_show_returns_full_document(tmp_path):
    document = write_result(tmp_path, "run-a")

    assert show_result(tmp_path, "run-a") == document


def test_show_missing_raises_named_error(tmp_path):
    with pytest.raises(ResultNotFound):
        show_result(tmp_path, "nope")
