"""Run orchestration (BR-A2.1): validate → verify → execute N compositions → sink.

Per composition: cost controls (timeout, event-count guard) wrap execution;
metrics collected per taxonomy (BR-A2.2); one Result Manifest + one event per
composition through the configured sink.
"""

from __future__ import annotations

from pathlib import Path

from . import costs, metrics, repro
from .executor import CompositionExecutor, ExecutionContext
from .manifest import Manifest
from .sinks import ResultSink, run_completed_event


def run_manifest(
    manifest: Manifest,
    corpus_path: Path,
    queries: list,
    executor: CompositionExecutor,
    sink: ResultSink,
    environment: dict = None,
    timeout_ms: int = None,
    max_events: int = None,
) -> list:
    """Execute all compositions; returns artifact paths. Raises loudly on any failure."""
    report = repro.verify(manifest.document, Path(corpus_path))
    controls = costs.controls_from_manifest(manifest.document)
    timeout = timeout_ms if timeout_ms is not None else controls["timeout_ms"]
    limit = max_events if max_events is not None else controls["max_event_count"]
    counter = costs.EventCounter(limit) if limit else None

    artifacts = []
    for composition in manifest.document["compositions"]:
        def step(comp=composition):
            return _execute_one(
                manifest, comp, corpus_path, queries, executor, sink,
                report, environment or {}, counter,
            )

        if timeout:
            artifacts.append(costs.run_with_timeout(step, timeout))
        else:
            artifacts.append(step())
    return artifacts


def _execute_one(
    manifest, composition, corpus_path, queries, executor, sink,
    report, environment, counter,
):
    ctx = ExecutionContext(
        corpus_path=str(corpus_path),
        seed=report.seed,
    )
    results = executor.execute(composition, queries, ctx)
    if counter is not None:
        counter.observe(len(results))
    collected = metrics.collect_metrics(
        manifest.document, queries, results, executor.index_stats()
    )
    run_id = f"{manifest.manifest_id}-{composition['composition_id']}"
    document = metrics.result_document(
        manifest.document,
        composition,
        run_id,
        queries,
        results,
        collected,
        {"verified": True, "corpus_hash": report.corpus_hash, "seed": report.seed},
        environment,
    )
    path = sink.write(document)
    sink.emit(
        run_completed_event(
            run_id, manifest.manifest_id, 1, 0,
        )
    )
    return path
