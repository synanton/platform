"""Q3 → Result Manifest bridge (B5.2 RunLeg fallback path).

RunLeg-style legs (bench-emitter) produce Q3 JSON; this converts one Q3 file
plus a gold map into a schema-valid Result Manifest with metrics, reusing
metrics.collect_metrics. Recall needs gold chunk IDs — supplied separately
because gold lives with the query set, not the leg output.
"""

from __future__ import annotations

import dataclasses
import json
from pathlib import Path

from .executor import RawQueryResult
from . import metrics as metrics_mod
from .sinks import FileResultSink


def q3_to_results(q3: dict, gold_by_id: dict) -> tuple:
    """Split a Q3 document into runner queries + raw results."""
    queries = []
    results = []
    for entry in q3.get("queries", []):
        query_id = entry["query_id"]
        gold = list(gold_by_id.get(query_id, []))
        eligible = list(entry.get("eligible_set", []))
        queries.append({"query_id": query_id, "gold": gold, "eligible": eligible})
        results.append(
            RawQueryResult(
                query_id=query_id,
                hits=list(entry.get("top_k", [])),
                eligible_set=eligible,
                timing_ms=float(entry.get("timing_ms", 0.0)),
                timing_scope=str(entry.get("timing_scope", "runleg")),
                topology=str(entry.get("topology", "")),
                structural_empty=bool(entry.get("structural_empty", False)),
            )
        )
    return queries, results


def convert(
    q3_path: Path,
    manifest: dict,
    composition_id: str,
    run_id: str,
    gold_by_id: dict,
    topology: str,
    corpus_hash: str,
    out_dir: Path,
    environment: dict = None,
) -> Path:
    """Convert one Q3 file to a Result Manifest artifact. Returns the path."""
    q3 = json.loads(Path(q3_path).read_text())
    queries, results = q3_to_results(q3, gold_by_id)
    results = [dataclasses.replace(r, topology=topology) for r in results]
    manifest_metrics = manifest.get("metrics", {"recall_at_k": [10]})
    collected = metrics_mod.collect_metrics(
        {"metrics": manifest_metrics}, queries, results, {}
    )
    seed = manifest.get("reproducibility", {}).get("seed")
    document = metrics_mod.result_document(
        {**manifest, "manifest_id": manifest.get("manifest_id", run_id.rsplit("-", 1)[0])},
        {"composition_id": composition_id},
        run_id,
        queries,
        results,
        collected,
        {"verified": False, "corpus_hash": corpus_hash, "seed": seed},
        environment or {},
    )
    return FileResultSink(Path(out_dir)).write(document)
