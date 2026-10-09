"""Metric collection + topology propagation (BR-A2.2), per metric-taxonomy.md.

Every taxonomy field populated or explicitly null (taxonomy §9: zero is a
claim, null is an admission). Topology flows per query from execution —
no metric without its topology on runner-produced artifacts.
"""

from __future__ import annotations


def _percentile(sorted_values: list, pct: float):
    if not sorted_values:
        return None
    if len(sorted_values) == 1:
        return float(sorted_values[0])
    rank = pct / 100.0 * (len(sorted_values) - 1)
    low = int(rank)
    high = min(low + 1, len(sorted_values) - 1)
    frac = rank - low
    return float(sorted_values[low] * (1 - frac) + sorted_values[high] * frac)


def recall_at_k(gold: list, hits: list, k: int):
    """BR-A0.4 §1: |relevant ∩ top_k| / |relevant|; None when gold is empty."""
    if not gold:
        return None
    top = [h["chunk_id"] for h in hits[:k]]
    return len(set(gold) & set(top)) / len(gold)


def overlap(hits: list, eligible: list, k: int = None):
    """BR-A0.4 §3: |top_k ∩ eligible| / |eligible|; None when eligible is empty."""
    if not eligible:
        return None
    top = [h["chunk_id"] for h in hits[:k]] if k else [h["chunk_id"] for h in hits]
    return len(set(top) & set(eligible)) / len(eligible)


def eligible_identity(hits_eligible: list, expected_eligible: list) -> bool:
    """BR-A0.4 §2: returned eligible set equals expectation (order-insensitive)."""
    return set(hits_eligible) == set(expected_eligible)


def latency_percentiles(timings: list, percentiles=(50, 95, 99)):
    """BR-A0.4 §4 over non-structural timings; None when nothing measurable."""
    ordered = sorted(timings)
    return {f"p{p}": _percentile(ordered, p) for p in percentiles}


def collect_metrics(manifest: dict, queries: list, results: list, index_stats: dict) -> dict:
    """Build the metrics_summary block for one composition's Result Manifest."""
    requested_k = manifest.get("metrics", {}).get("recall_at_k", [10])
    gold_by_id = {q["query_id"]: list(q.get("gold", [])) for q in queries}
    eligible_by_id = {q["query_id"]: list(q.get("eligible", [])) for q in queries}

    recalls = {}
    for k in requested_k:
        values = [
            recall_at_k(gold_by_id.get(r.query_id, []), r.hits, k) for r in results
        ]
        values = [v for v in values if v is not None]
        recalls[str(k)] = sum(values) / len(values) if values else None

    overlaps = [
        overlap(r.hits, eligible_by_id.get(r.query_id, [])) for r in results
    ]
    overlaps = [v for v in overlaps if v is not None]

    identities = [
        eligible_identity(r.eligible_set, eligible_by_id.get(r.query_id, []))
        for r in results
    ]

    timings = [r.timing_ms for r in results if not r.structural_empty]
    latencies = latency_percentiles(timings)

    summary = {
        "recall_at_k": recalls,
        "overlap": (sum(overlaps) / len(overlaps)) if overlaps else None,
        "eligible_identity_rate": (sum(1 for i in identities if i) / len(identities))
        if identities
        else None,
        "latency_p50_ms": latencies.get("p50"),
        "latency_p95_ms": latencies.get("p95"),
        "latency_p99_ms": latencies.get("p99"),
        "index_build_time_s": index_stats.get("build_time_s"),
        "index_size_bytes": index_stats.get("size_bytes"),
    }
    return {k: v for k, v in summary.items() if v is not None or _keep_null(k)}

def _keep_null(key: str) -> bool:
    # Explicit nulls for unmeasured taxonomy fields (taxonomy §9).
    return key in (
        "overlap",
        "eligible_identity_rate",
        "latency_p50_ms",
        "latency_p95_ms",
        "latency_p99_ms",
        "index_build_time_s",
        "index_size_bytes",
    )


def result_document(
    manifest: dict,
    composition: dict,
    run_id: str,
    queries: list,
    results: list,
    metrics: dict,
    reproducibility: dict,
    environment: dict,
) -> dict:
    """Assemble a schema-valid Result Manifest with per-query topology."""
    return {
        "run_id": run_id,
        "corpus": manifest["corpus"].get("alias", manifest["corpus"]["ref"]),
        "manifest_id": manifest["manifest_id"],
        "composition_id": composition["composition_id"],
        "queries": [
            {
                "query_id": r.query_id,
                "top_k": r.hits,
                "eligible_set": r.eligible_set,
                "timing_ms": r.timing_ms,
                "timing_scope": r.timing_scope,
                "topology": r.topology,
            }
            for r in results
        ],
        "metrics_summary": metrics,
        "reproducibility": reproducibility,
        "environment": environment,
    }
