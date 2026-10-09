"""BR-A1.1 acceptance: valid / malformed / missing-field / wrong-type manifests."""

import copy
import json
from pathlib import Path

import pytest

from benchmark_runner.manifest import (
    ManifestInvalidJson,
    ManifestNotFound,
    ManifestSchemaError,
    load_manifest,
)

REPO_ROOT_FIXTURE = Path(__file__).resolve().parents[3]
VALID_FIXTURE = (
    REPO_ROOT_FIXTURE / "schemas" / "benchmark" / "fixtures" / "six-composition.json"
)


def read_valid():
    return json.loads(VALID_FIXTURE.read_text())


def test_valid_fixture_loads(tmp_path):
    manifest = load_manifest(VALID_FIXTURE)

    assert manifest.manifest_id == "vec-6comp-r1"
    assert manifest.corpus_ref.startswith("cas:")
    assert len(manifest.composition_ids) == 6


def test_missing_file_raises_named_error(tmp_path):
    with pytest.raises(ManifestNotFound):
        load_manifest(tmp_path / "nope.json")


def test_invalid_json_raises_named_error(tmp_path):
    bad = tmp_path / "bad.json"
    bad.write_text("{not json")

    with pytest.raises(ManifestInvalidJson):
        load_manifest(bad)


def test_missing_field_names_field_path(tmp_path):
    document = read_valid()
    del document["compositions"]
    target = tmp_path / "manifest.json"
    target.write_text(json.dumps(document))

    with pytest.raises(ManifestSchemaError) as exc_info:
        load_manifest(target)
    assert "compositions" in exc_info.value.field_path or "compositions" in str(
        exc_info.value
    )


def test_wrong_type_names_field_path(tmp_path):
    document = read_valid()
    document["metrics"]["recall_at_k"] = "10"
    target = tmp_path / "manifest.json"
    target.write_text(json.dumps(document))

    with pytest.raises(ManifestSchemaError) as exc_info:
        load_manifest(target)
    assert "recall_at_k" in exc_info.value.field_path


def test_bad_enum_names_field_path(tmp_path):
    document = read_valid()
    broken = copy.deepcopy(document["compositions"][0])
    broken["vector_provider"] = "pinecone"
    document["compositions"][0] = broken
    target = tmp_path / "manifest.json"
    target.write_text(json.dumps(document))

    with pytest.raises(ManifestSchemaError) as exc_info:
        load_manifest(target)
    assert "vector_provider" in exc_info.value.field_path


def test_bad_corpus_ref_rejected(tmp_path):
    document = read_valid()
    document["corpus"]["ref"] = "sha256:xyz"
    target = tmp_path / "manifest.json"
    target.write_text(json.dumps(document))

    with pytest.raises(ManifestSchemaError) as exc_info:
        load_manifest(target)
    assert "ref" in exc_info.value.field_path
