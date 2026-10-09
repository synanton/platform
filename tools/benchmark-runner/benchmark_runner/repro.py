"""Reproducibility verification (BR-A1.3): corpus hash check + seed check.

Fail loud on mismatch: a run against the wrong corpus must abort before
execution, never produce a silently misattributed artifact.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from pathlib import Path


class ReproducibilityError(Exception):
    """Base class; the run aborts before execution on any subclass."""


class CorpusMissing(ReproducibilityError):
    def __init__(self, path: Path):
        super().__init__(f"corpus not found: {path}")
        self.path = path


class CorpusHashMismatch(ReproducibilityError):
    def __init__(self, expected: str, actual: str):
        super().__init__(
            f"corpus hash mismatch: manifest expects {expected}, corpus is {actual}"
        )
        self.expected = expected
        self.actual = actual


class SeedMissing(ReproducibilityError):
    def __init__(self):
        super().__init__("manifest reproducibility.seed is missing: no seed, no reproducibility claim")


@dataclass(frozen=True)
class ReproducibilityReport:
    corpus_hash: str
    seed: int
    verified: bool


def hash_corpus(path: Path) -> str:
    """Content hash of a corpus file or directory.

    File: sha256 over bytes. Directory: sha256 over the concatenation of
    sorted (relative-path, bytes) pairs. Deterministic for identical trees.
    """
    target = Path(path)
    if not target.exists():
        raise CorpusMissing(target)
    digest = hashlib.sha256()
    if target.is_file():
        digest.update(target.read_bytes())
        return digest.hexdigest()
    for child in sorted(target.rglob("*")):
        if child.is_file():
            digest.update(str(child.relative_to(target)).encode())
            digest.update(child.read_bytes())
    return digest.hexdigest()


def expected_hash(manifest: dict) -> str:
    """Extract the expected hex digest from a manifest's cas: corpus ref."""
    ref = manifest.get("corpus", {}).get("ref", "")
    if not ref.startswith("cas:"):
        raise CorpusHashMismatch(ref or "(missing ref)", "(unreadable ref)")
    return ref[len("cas:") :]


def verify_seed(manifest: dict) -> int:
    """Seed must be present and an integer; otherwise the run claims nothing."""
    seed = manifest.get("reproducibility", {}).get("seed")
    if not isinstance(seed, bool) and isinstance(seed, int):
        return seed
    raise SeedMissing()


def verify(manifest: dict, corpus_path: Path) -> ReproducibilityReport:
    """Verify reproducibility preconditions. Raises on any failure."""
    seed = verify_seed(manifest)
    actual = hash_corpus(Path(corpus_path))
    expected = expected_hash(manifest)
    if actual != expected:
        raise CorpusHashMismatch(expected, actual)
    return ReproducibilityReport(corpus_hash=actual, seed=seed, verified=True)
