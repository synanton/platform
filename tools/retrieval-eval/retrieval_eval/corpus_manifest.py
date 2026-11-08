"""Frozen retrieval-corpus verification.

The Run C corpus (`demo-fixed`, 82 chunks) and its 25-query gold are frozen in
`evaluation/retrieval-corpus/`. A reportable retrieval run must verify this
manifest first; regenerating the tenant from source files is not guaranteed to
reproduce the same chunk IDs/hashes.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

DEFAULT_MANIFEST = (
    Path(__file__).resolve().parents[3] / "evaluation" / "retrieval-corpus" / "MANIFEST.sha256"
)


class CorpusManifestError(RuntimeError):
    pass


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def chunk_fingerprint(chunks: list[dict]) -> str:
    canonical = "\n".join(
        f"{chunk['content_ref_id']}:{chunk['chunk_ordinal']}:{chunk['chunk_sha256']}"
        for chunk in chunks
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def verify(manifest: Path = DEFAULT_MANIFEST) -> dict:
    manifest = Path(manifest)
    if not manifest.exists():
        raise CorpusManifestError(f"corpus manifest not found: {manifest}")

    expected: dict[str, str] = {}
    for line in manifest.read_text(encoding="utf-8").splitlines():
        if line.strip():
            digest, relative = line.split(maxsplit=1)
            expected[relative.strip()] = digest
    if not expected:
        raise CorpusManifestError(f"empty corpus manifest: {manifest}")

    for relative, digest in expected.items():
        path = manifest.parent / relative
        if not path.exists():
            raise CorpusManifestError(f"missing frozen corpus file: {path}")
        actual = _sha256(path)
        if actual != digest:
            raise CorpusManifestError(
                f"corpus manifest mismatch for {relative}: {actual} != {digest}"
            )

    corpus = json.loads((manifest.parent / "corpus.json").read_text(encoding="utf-8"))
    chunks = [
        json.loads(line)
        for line in (manifest.parent / "chunks.jsonl").read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    if len(chunks) != corpus.get("chunk_count"):
        raise CorpusManifestError(
            f"chunk count drift: {len(chunks)} != {corpus.get('chunk_count')}"
        )
    fingerprint = chunk_fingerprint(chunks)
    if fingerprint != corpus.get("chunk_fingerprint"):
        raise CorpusManifestError(
            f"chunk fingerprint drift: {fingerprint} != {corpus.get('chunk_fingerprint')}"
        )
    return {
        "manifest": str(manifest),
        "files": len(expected),
        "tenant": corpus.get("tenant"),
        "chunk_count": len(chunks),
        "query_count": corpus.get("query_count"),
        "chunk_fingerprint": fingerprint,
    }
