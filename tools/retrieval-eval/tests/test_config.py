import os
from pathlib import Path

import pytest

from retrieval_eval.config import check_dataset_root, load_config

REPO_CONFIG = Path(__file__).resolve().parents[1] / "config.yml"


def test_load_config_resolves_default_root():
    os.environ.pop("RETRIEVAL_BENCH_DATASETS_DIR", None)
    config = load_config(REPO_CONFIG)
    assert str(config.datasets_root) == "/home/aminin/workspace/synanton/testing"
    assert set(config.datasets) == {"pire", "rag_multi_corpus", "ohr_bench"}


def test_load_config_respects_env_override(tmp_path, monkeypatch):
    monkeypatch.setenv("RETRIEVAL_BENCH_DATASETS_DIR", str(tmp_path))
    config = load_config(REPO_CONFIG)
    assert config.datasets_root == tmp_path


def test_dataset_resolve_joins_root_and_relative_path(tmp_path, monkeypatch):
    monkeypatch.setenv("RETRIEVAL_BENCH_DATASETS_DIR", str(tmp_path))
    config = load_config(REPO_CONFIG)
    pire = config.dataset("pire")
    assert pire.resolve(config.datasets_root) == tmp_path / "PIRE"


def test_unknown_dataset_raises_with_known_list():
    config = load_config(REPO_CONFIG)
    with pytest.raises(KeyError, match="pire"):
        config.dataset("not-a-real-dataset")


def test_check_dataset_root_reports_missing_paths(tmp_path, monkeypatch):
    monkeypatch.setenv("RETRIEVAL_BENCH_DATASETS_DIR", str(tmp_path))
    config = load_config(REPO_CONFIG)
    problems = check_dataset_root(config)
    # tmp_path exists but none of the dataset subfolders do
    assert len(problems) == len(config.datasets)


def test_check_dataset_root_against_real_local_datasets():
    """Only meaningful on a machine with the datasets actually staged - skips otherwise."""
    config = load_config(REPO_CONFIG)
    if not config.datasets_root.is_dir():
        pytest.skip(f"datasets root not present on this machine: {config.datasets_root}")
    problems = check_dataset_root(config)
    assert problems == []


def test_missing_config_file_raises(tmp_path):
    with pytest.raises(FileNotFoundError):
        load_config(tmp_path / "does-not-exist.yml")
