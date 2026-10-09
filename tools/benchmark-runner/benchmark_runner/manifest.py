"""Benchmark Manifest parsing and validation (BR-A1.1).

Reads a manifest, validates it against schemas/benchmark/benchmark-manifest.schema.json,
and fails fast with named errors carrying the field path. No execution happens here;
${VAR} interpolation and execution belong to later phases.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

import jsonschema

REPO_ROOT = Path(__file__).resolve().parents[3]
MANIFEST_SCHEMA_PATH = REPO_ROOT / "schemas" / "benchmark" / "benchmark-manifest.schema.json"


class ManifestError(Exception):
    """Base class; every failure names the manifest and the cause."""


class ManifestNotFound(ManifestError):
    def __init__(self, path: Path):
        super().__init__(f"manifest not found: {path}")
        self.path = path


class ManifestInvalidJson(ManifestError):
    def __init__(self, path: Path, reason: str):
        super().__init__(f"manifest is not valid JSON: {path}: {reason}")
        self.path = path


class ManifestSchemaError(ManifestError):
    """A schema violation. `field_path` is the dotted path to the offending field."""

    def __init__(self, path: Path, field_path: str, reason: str):
        super().__init__(f"manifest schema violation at '{field_path}': {reason} ({path})")
        self.path = path
        self.field_path = field_path


@dataclass(frozen=True)
class Manifest:
    """A validated manifest: raw document plus summary accessors."""

    path: Path
    document: dict = field(compare=False)

    @property
    def manifest_id(self) -> str:
        return self.document["manifest_id"]

    @property
    def corpus_ref(self) -> str:
        return self.document["corpus"]["ref"]

    @property
    def composition_ids(self) -> list:
        return [c["composition_id"] for c in self.document["compositions"]]


def _field_path(error: jsonschema.ValidationError) -> str:
    parts = [str(p) for p in error.absolute_path]
    return ".".join(parts) if parts else "(root)"


def load_schema(schema_path: Path = MANIFEST_SCHEMA_PATH) -> dict:
    if not schema_path.exists():
        raise ManifestNotFound(schema_path)
    try:
        return json.loads(schema_path.read_text())
    except json.JSONDecodeError as e:
        raise ManifestInvalidJson(schema_path, str(e)) from e


def load_manifest(path: Path, schema_path: Path = MANIFEST_SCHEMA_PATH) -> Manifest:
    """Read and validate a manifest. Raises a ManifestError subclass on any failure."""
    manifest_path = Path(path)
    if not manifest_path.exists():
        raise ManifestNotFound(manifest_path)
    try:
        document = json.loads(manifest_path.read_text())
    except json.JSONDecodeError as e:
        raise ManifestInvalidJson(manifest_path, str(e)) from e
    schema = load_schema(schema_path)
    validator = jsonschema.Draft202012Validator(schema)
    errors = sorted(validator.iter_errors(document), key=lambda e: list(e.absolute_path))
    if errors:
        first = errors[0]
        raise ManifestSchemaError(manifest_path, _field_path(first), first.message)
    return Manifest(path=manifest_path, document=document)
