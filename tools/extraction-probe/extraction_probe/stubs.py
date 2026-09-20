"""Generates the synanton.extraction.v1 gRPC Python stubs from the real .proto files.

Stubs are never committed to this repo - they're generated on first use into a
gitignored cache directory, so they can never silently drift from the actual
contract in java/extraction-contract/src/main/proto/. Regenerated automatically
whenever the source .proto files change (mtime-compared against the cache).
"""

from __future__ import annotations

import importlib
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from types import ModuleType

_PROTO_FILES = ("extraction_service.proto", "extraction_payload.proto")
_CACHE_SUBDIR = ".generated-stubs"


def _cache_dir(proto_source_dir: Path) -> Path:
    tool_root = Path(__file__).resolve().parent.parent
    return tool_root / _CACHE_SUBDIR


def _needs_regeneration(proto_source_dir: Path, cache_dir: Path) -> bool:
    generated = cache_dir / "synanton" / "extraction" / "v1" / "extraction_service_pb2.py"
    if not generated.is_file():
        return True
    generated_mtime = generated.stat().st_mtime
    for name in _PROTO_FILES:
        src = proto_source_dir / "synanton" / "extraction" / "v1" / name
        if not src.is_file():
            raise FileNotFoundError(
                f"Expected proto file not found: {src}. "
                f"Is proto.source_dir in config.yml pointing at a real platform checkout?"
            )
        if src.stat().st_mtime > generated_mtime:
            return True
    return False


@dataclass(frozen=True)
class ExtractionStubs:
    service_pb2: ModuleType
    payload_pb2: ModuleType
    service_grpc: ModuleType


def ensure_stubs(proto_source_dir: Path) -> ExtractionStubs:
    """Generate (if needed) the gRPC stubs and return the three modules a caller needs."""
    cache_dir = _cache_dir(proto_source_dir)

    if _needs_regeneration(proto_source_dir, cache_dir):
        cache_dir.mkdir(parents=True, exist_ok=True)
        v1_dir = cache_dir / "synanton" / "extraction" / "v1"
        v1_dir.mkdir(parents=True, exist_ok=True)
        for name in _PROTO_FILES:
            src = proto_source_dir / "synanton" / "extraction" / "v1" / name
            (v1_dir / name).write_bytes(src.read_bytes())

        proto_args = [str(v1_dir / name) for name in _PROTO_FILES]
        result = subprocess.run(
            [
                sys.executable, "-m", "grpc_tools.protoc",
                f"-I{cache_dir}",
                f"--python_out={cache_dir}",
                f"--grpc_python_out={cache_dir}",
                *proto_args,
            ],
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            raise RuntimeError(
                f"Failed to generate gRPC stubs from {proto_source_dir}:\n{result.stderr}"
            )

    cache_str = str(cache_dir)
    if cache_str not in sys.path:
        sys.path.insert(0, cache_str)

    # Reload rather than cached-import in case a previous process generated stale stubs.
    for mod_name in (
        "synanton.extraction.v1.extraction_payload_pb2",
        "synanton.extraction.v1.extraction_service_pb2",
        "synanton.extraction.v1.extraction_service_pb2_grpc",
    ):
        sys.modules.pop(mod_name, None)

    payload_pb2 = importlib.import_module("synanton.extraction.v1.extraction_payload_pb2")
    service_pb2 = importlib.import_module("synanton.extraction.v1.extraction_service_pb2")
    service_grpc = importlib.import_module("synanton.extraction.v1.extraction_service_pb2_grpc")
    return ExtractionStubs(service_pb2=service_pb2, payload_pb2=payload_pb2, service_grpc=service_grpc)
