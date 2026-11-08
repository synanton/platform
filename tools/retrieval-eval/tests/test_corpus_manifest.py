import hashlib
import json
from pathlib import Path

import pytest

from retrieval_eval.corpus_manifest import CorpusManifestError, chunk_fingerprint, verify


def _write_corpus(root: Path) -> Path:
    chunks = [
        {"tenant_id": "t", "content_ref_id": "ref-a", "chunk_ordinal": 0, "chunk_sha256": "a" * 64},
        {"tenant_id": "t", "content_ref_id": "ref-a", "chunk_ordinal": 1, "chunk_sha256": "b" * 64},
    ]
    (root / "chunks.jsonl").write_text("".join(json.dumps(c, sort_keys=True) + "\n" for c in chunks))
    (root / "queries-q25.jsonl").write_text('{"query_id":"q1"}\n')
    corpus = {
        "tenant": "t",
        "chunk_count": len(chunks),
        "chunk_fingerprint": chunk_fingerprint(chunks),
        "query_count": 1,
    }
    (root / "corpus.json").write_text(json.dumps(corpus))
    lines = []
    for name in ("chunks.jsonl", "corpus.json", "queries-q25.jsonl"):
        digest = hashlib.sha256((root / name).read_bytes()).hexdigest()
        lines.append(f"{digest}  {name}")
    manifest = root / "MANIFEST.sha256"
    manifest.write_text("\n".join(lines) + "\n")
    return manifest


def test_verify_ok(tmp_path):
    info = verify(_write_corpus(tmp_path))
    assert info["chunk_count"] == 2
    assert info["tenant"] == "t"


def test_tampered_chunk_fails(tmp_path):
    manifest = _write_corpus(tmp_path)
    with (tmp_path / "chunks.jsonl").open("a") as handle:
        handle.write('{"tenant_id":"t","content_ref_id":"ref-b","chunk_ordinal":0,"chunk_sha256":"c"}\n')
    with pytest.raises(CorpusManifestError, match="manifest mismatch"):
        verify(manifest)
