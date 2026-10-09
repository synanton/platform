"""BR-A2.1 acceptance: N-composition manifest produces N valid artifacts."""

import json
from pathlib import Path

import jsonschema

from benchmark_runner.executor import SimulatedExecutor
from benchmark_runner.runner import run_manifest
from benchmark_runner.manifest import load_manifest
from benchmark_runner.sinks import FileResultSink

REPO = Path(__file__).resolve().parents[3]
RESULT_SCHEMA = json.loads(
    (REPO / "schemas" / "benchmark" / "result-manifest.schema.json").read_text()
)
VALIDATOR = jsonschema.Draft202012Validator(RESULT_SCHEMA)

QUERIES = [
    {"query_id": "q1", "gold": ["c1", "c2", "c3"], "eligible": ["c1", "c2", "c3", "c4"]},
    {"query_id": "q2", "gold": ["c5"], "eligible": ["c5", "c6"]},
    {"query_id": "q3", "gold": [], "eligible": []},
]


def three_comp_manifest(tmp_path):
    document = json.loads(
        (REPO / "schemas" / "benchmark" / "fixtures" / "six-composition.json").read_text()
    )
    document["manifest_id"] = "test-3comp"
    document["corpus"] = {
        "ref": "cas:" + "ab" * 32,
        "alias": "t",
        "dataset_version": "t",
    }
    document["compositions"] = document["compositions"][:3]
    document["reproducibility"] = {"seed": 11, "verify_corpus_hash": False}
    document["cost_controls"]["dry_run"] = False
    target = tmp_path / "manifest.json"
    target.write_text(json.dumps(document))
    return target


def test_three_compositions_produce_three_valid_artifacts(tmp_path):
    corpus = tmp_path / "corpus.bin"
    corpus.write_bytes(b"test corpus bytes")
    manifest = load_manifest(three_comp_manifest(tmp_path))
    # Point the manifest at the real fixture corpus hash.
    import hashlib

    manifest.document["corpus"]["ref"] = "cas:" + hashlib.sha256(b"test corpus bytes").hexdigest()
    sink = FileResultSink(tmp_path / "out")

    artifacts = run_manifest(
        manifest,
        corpus,
        QUERIES,
        SimulatedExecutor(seed=11),
        sink,
        environment={"runner_version": "test"},
    )

    assert len(artifacts) == 3
    validator = VALIDATOR
    for path in artifacts:
        document = json.loads(path.read_text())
        errors = list(validator.iter_errors(document))
        assert errors == []
        # Topology present on every query (BR-A2.2 propagation).
        assert all("topology" in q for q in document["queries"])
        assert document["metrics_summary"]["recall_at_k"]["10"] is not None
    run_ids = [json.loads(p.read_text())["run_id"] for p in artifacts]
    assert len(set(run_ids)) == 3


def test_hash_mismatch_aborts_before_artifacts(tmp_path):
    corpus = tmp_path / "corpus.bin"
    corpus.write_bytes(b"other bytes")
    manifest = load_manifest(three_comp_manifest(tmp_path))
    sink = FileResultSink(tmp_path / "out")

    try:
        run_manifest(manifest, corpus, QUERIES, SimulatedExecutor(seed=11), sink)
    except Exception:
        pass
    else:
        raise AssertionError("expected abort on hash mismatch")

    assert not (tmp_path / "out").exists()
