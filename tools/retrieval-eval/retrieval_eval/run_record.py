"""Benchmark-run record per docs/architecture/synanton-design-1.31.md §88.

Every benchmark run must persist:

    benchmark:
      dataset_version: ...
      knowledge_version: ...
      search_config: ...
      embedding_model: ...
      retrieval_strategy: ...
      reranker: ...

so a result is reproducible from (dataset, knowledge version, projection
generation, search configuration, model versions) per §89. This module writes
that record plus the aggregate metrics (metrics.AggregateMetrics) next to it.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path

import yaml

from .metrics import AggregateMetrics


@dataclass(frozen=True)
class RunIdentity:
    """The §88 fields that make a run reproducible and comparable."""

    dataset_version: str
    knowledge_version: str
    search_config: str
    embedding_model: str
    retrieval_strategy: str
    reranker: str


def write_run_record(
    identity: RunIdentity,
    metrics: AggregateMetrics,
    output_dir: Path,
    run_id: str | None = None,
) -> Path:
    """Write one benchmark-run record and return the path written.

    `run_id` defaults to a UTC timestamp; pass an explicit one (e.g. a test
    matrix row ID like "T04") to keep result files matched to
    docs/research/retrieval-evaluation-benchmark-plan.md §3.4's table.
    """
    output_dir.mkdir(parents=True, exist_ok=True)
    run_id = run_id or datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")

    record = {
        "benchmark": asdict(identity),
        "recorded_at": datetime.now(timezone.utc).isoformat(),
        "metrics": asdict(metrics),
    }

    path = output_dir / f"{run_id}.yaml"
    path.write_text(yaml.safe_dump(record, sort_keys=False))
    return path
