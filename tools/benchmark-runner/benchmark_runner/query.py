"""Result Manifest read API, CLI form (BR-A3.2).

Lists and shows validated Result Manifests under a FileResultSink directory:
the read endpoints GET /benchmarks/results, GET /benchmarks/results/{id},
GET /benchmarks/manifests/{id} against file-backed runs. HTTP transport is
deferred (UI integration is future per the card); the read semantics here are
what the future endpoints return.
"""

from __future__ import annotations

import json
from pathlib import Path


class ResultNotFound(Exception):
    def __init__(self, run_id: str):
        super().__init__(f"no result for run_id: {run_id}")


def list_results(base_path: Path) -> list:
    """Summaries of all Result Manifests, sorted by run_id. Skips event files."""
    summaries = []
    root = Path(base_path)
    if not root.exists():
        return summaries
    for path in sorted(root.glob("*.result.json")):
        try:
            document = json.loads(path.read_text())
        except (OSError, ValueError):
            continue
        queries = document.get("queries", [])
        summaries.append(
            {
                "run_id": document.get("run_id", path.stem),
                "manifest_id": document.get("manifest_id"),
                "composition_id": document.get("composition_id"),
                "query_count": len(queries),
            }
        )
    return summaries


def show_result(base_path: Path, run_id: str) -> dict:
    """Full Result Manifest for one run_id. Raises ResultNotFound."""
    path = Path(base_path) / f"{run_id}.result.json"
    if not path.exists():
        raise ResultNotFound(run_id)
    try:
        return json.loads(path.read_text())
    except ValueError as e:
        raise ResultNotFound(f"{run_id} (unreadable: {e})") from e
