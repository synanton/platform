"""BR-A1.3 acceptance: matching hash passes; mismatched/missing hash and seed fail."""

import hashlib
import json

import pytest

from benchmark_runner.repro import (
    CorpusHashMismatch,
    CorpusMissing,
    SeedMissing,
    expected_hash,
    hash_corpus,
    verify,
    verify_seed,
)


def write_corpus(path, payload=b"chunk-one\nchunk-two\n"):
    path.write_bytes(payload)
    return hashlib.sha256(payload).hexdigest()


def base_manifest(corpus_hash, seed=7):
    return {
        "manifest_id": "test",
        "corpus": {"ref": f"cas:{corpus_hash}", "alias": "t", "dataset_version": "t"},
        "reproducibility": {"seed": seed, "verify_corpus_hash": True},
    }


def test_matching_hash_passes(tmp_path):
    digest = write_corpus(tmp_path / "corpus.bin")

    report = verify(base_manifest(digest), tmp_path / "corpus.bin")

    assert report.verified is True
    assert report.corpus_hash == digest
    assert report.seed == 7


def test_mismatched_hash_fails_loud(tmp_path):
    write_corpus(tmp_path / "corpus.bin")
    wrong = "0" * 64

    with pytest.raises(CorpusHashMismatch) as exc_info:
        verify(base_manifest(wrong), tmp_path / "corpus.bin")
    assert wrong in str(exc_info.value)


def test_missing_corpus_fails_loud(tmp_path):
    with pytest.raises(CorpusMissing):
        verify(base_manifest("0" * 64), tmp_path / "absent.bin")


def test_missing_hash_ref_fails_loud(tmp_path):
    digest = write_corpus(tmp_path / "corpus.bin")
    manifest = base_manifest(digest)
    manifest["corpus"] = {"alias": "t", "dataset_version": "t"}

    with pytest.raises(CorpusHashMismatch):
        verify(manifest, tmp_path / "corpus.bin")


def test_missing_seed_fails_loud(tmp_path):
    digest = write_corpus(tmp_path / "corpus.bin")
    manifest = base_manifest(digest)
    del manifest["reproducibility"]["seed"]

    with pytest.raises(SeedMissing):
        verify(manifest, tmp_path / "corpus.bin")


def test_directory_hash_is_deterministic(tmp_path):
    (tmp_path / "a").write_bytes(b"1")
    (tmp_path / "sub").mkdir()
    (tmp_path / "sub" / "b").write_bytes(b"2")

    assert hash_corpus(tmp_path) == hash_corpus(tmp_path)
    assert len(hash_corpus(tmp_path)) == 64


def test_seed_must_be_int_not_bool():
    with pytest.raises(SeedMissing):
        verify_seed({"reproducibility": {"seed": True}})


def test_expected_hash_parses_cas_ref():
    assert expected_hash({"corpus": {"ref": "cas:" + "ab" * 32}}) == "ab" * 32
