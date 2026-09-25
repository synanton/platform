"""Command-line entry point for the retrieval evaluation harness.

    retrieval-eval check-config
    retrieval-eval ingest    --tenant demo [--gpu-plane gpu-7 --retries 2]
    retrieval-eval evaluate  --tenant demo --queries <queries.jsonl> --run-id T01 \\
                              --dataset-version ... --knowledge-version ... \\
                              --search-config hybrid-rrf --embedding-model bge-base-en-v1.5 \\
                              --retrieval-strategy hybrid --reranker none \\
                              [--gpu-plane gpu-7]   # pacing, daily budget, spend check, validity
    retrieval-eval rescore   --hits results/T03-G.hits.json --queries <queries.jsonl> --run-id T03-G
    retrieval-eval budget    --gpu-plane gpu-7

See docs/research/retrieval-evaluation-benchmark-plan.md §6 Phase B0-B1 for
where this fits, and §3.4 for the test-matrix row IDs `--run-id` is meant to
match.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from . import compose
from .config import DEFAULT_CONFIG_PATH, check_dataset_root, load_config
from .gold import load_gold_queries
import math

import requests

from .budget import BudgetExhausted, RequestBudget, RequestLedger, Throttle
from .ingest import IngestionFailed, IngestionTimedOut, ingest, reindex
from .metrics import QueryResult, _percentile, aggregate, evaluate_query
from .query import SearchUnavailable, index_stats, search
from .run_record import RunIdentity, read_hits, read_record, write_hits, write_run_record
from .spend import SpendProbeFailed, snapshot
from .validity import check_run, dense_expected

DEFAULT_RESULTS_DIR = Path(__file__).resolve().parents[3] / "demo-data" / "eval" / "retrieval-benchmark" / "results"
# gpu-runtime's key-holding tool; the platform never sees the provider key (§6 Phase B1-G, G4)
DEFAULT_SPEND_CMD = str(
    Path(__file__).resolve().parents[4] / "gpu-runtime" / "tools" / "gpu7-check" / ".venv" / "bin" / "python"
) + " " + str(Path(__file__).resolve().parents[4] / "gpu-runtime" / "tools" / "gpu7-check" / "gpu7_check.py") + " --usage"

PROVIDER_MODES = {"none": "none", "gpu-7": "external-free", "gpu-5": "local"}
PLANE_DEFAULTS = {  # (max searches/min, daily request budget) — G0: 1,000 free requests/day, ~20/min
    "none": (0, 0),
    "gpu-7": (15, 900),
    "gpu-5": (0, 0),
}


def _cmd_check_config(args: argparse.Namespace) -> int:
    config = load_config(args.config)
    print(f"datasets root: {config.datasets_root}")
    for name, spec in config.datasets.items():
        print(f"  {name}: {spec.resolve(config.datasets_root)}")

    problems = check_dataset_root(config)
    if problems:
        print("\nProblems found:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1

    print("\nAll configured dataset paths exist.")
    return 0


def _cmd_ingest(args: argparse.Namespace) -> int:
    synflux_url = compose.synflux_base_url()
    synquest_url = compose.synquest_base_url()
    attempts = 1 + max(0, args.retries)
    result = None
    for attempt in range(1, attempts + 1):
        print(f"Ingesting tenant={args.tenant} path={args.path} via {synflux_url} (attempt {attempt}/{attempts}) ...")
        try:
            result = ingest(synflux_url, args.tenant, args.path)
        except (IngestionFailed, IngestionTimedOut) as exc:
            print(f"Ingestion did not complete: {exc}", file=sys.stderr)
            result = None
            if attempt == attempts:
                return 1
            continue
        print(f"Ingested {result.processed_count} documents (errors={result.error_count})")
        if result.error_count == 0:
            break
        # synflux skips documents already persisted and reuses cached chunk embeddings, so
        # a re-run only re-pays the documents that failed (e.g. a fail-closed GPU-plane embed).
        if attempt < attempts:
            print("  documents failed — re-running; completed documents are skipped")

    print("Reindexing synquest ...")
    try:
        reindex(synquest_url, args.tenant)
    except requests.HTTPError as exc:
        print(f"Reindex failed ({exc}). Under synquest.embedding.required this means the index is not "
              "fully vectorised — see synquest logs; re-run ingest with --retries.", file=sys.stderr)
        return 1
    try:
        stats = index_stats(synquest_url, args.tenant)
        print(f"Index: {stats.doc_count} chunks, vectors={stats.vector_docs} "
              f"(model={stats.embedding_model}, dim={stats.embedding_dim}, "
              f"mismatches={stats.dim_mismatches}, missing={stats.missing_vectors})")
        if args.gpu_plane != "none" and stats.doc_count:
            # estimate (upper bound) of embedding requests: per-document batches of --batch-size
            estimate = (result.processed_count if result else 0) + math.ceil(stats.doc_count / args.batch_size)
            RequestLedger(args.ledger).add(args.gpu_plane, estimate)
            print(f"Ledger: +{estimate} estimated {args.gpu_plane} embedding requests for this ingest")
    except requests.RequestException as exc:
        print(f"(index stats unavailable: {exc})", file=sys.stderr)
    if result is not None and result.error_count:
        print(f"{result.error_count} documents still failing after {attempts} attempts", file=sys.stderr)
        return 1
    print("Done.")
    return 0


def _cmd_inspect(args: argparse.Namespace) -> int:
    synquest_url = compose.synquest_base_url()
    result = search(synquest_url, args.tenant, args.query, top_k=args.top_k)
    for hit in result.hits:
        print(f"{hit.chunk_id}")
        print(f"  score={hit.score:.4f} score_dense={hit.score_dense:.4f} score_lexical={hit.score_lexical:.4f}")
        print(f"  source_uri={hit.source_uri}")
        print(f"  section_path={hit.section_path!r} heading={hit.heading!r}")
    return 0


def _cmd_evaluate(args: argparse.Namespace) -> int:
    synquest_url = compose.synquest_base_url()
    queries = load_gold_queries(args.queries)
    if not queries:
        print(f"No gold queries found in {args.queries}", file=sys.stderr)
        return 1

    plane = args.gpu_plane
    provider_mode = args.provider_mode or PROVIDER_MODES[plane]
    free_only = provider_mode == "external-free"
    rpm_default, budget_default = PLANE_DEFAULTS[plane]
    max_rpm = rpm_default if args.max_rpm is None else args.max_rpm
    daily_budget = budget_default if args.daily_request_budget is None else args.daily_request_budget
    dense = dense_expected(args.embedding_model, args.top_k_dense)
    spend_cmd = args.spend_cmd if args.spend_cmd is not None else (DEFAULT_SPEND_CMD if free_only else "")

    try:
        stats = index_stats(synquest_url, args.tenant)
    except requests.RequestException as exc:
        print(f"(index stats unavailable: {exc})", file=sys.stderr)
        stats = None
    embedding_dim = args.embedding_dim or (stats.embedding_dim if stats else None)

    spend_before = spend_after = None
    if spend_cmd:
        try:
            spend_before = snapshot(spend_cmd)
        except (SpendProbeFailed, OSError) as exc:
            print(f"Spend check failed before the run ({exc}); refusing to start a free-models run.",
                  file=sys.stderr)
            return 1

    ledger = RequestLedger(args.ledger)
    budget = RequestBudget(ledger, plane, daily_budget)
    throttle = Throttle(max_rpm)
    counts_requests = plane != "none" and dense

    per_query, raw, failed, skipped = [], [], [], []
    embed_requests = embed_cached = 0
    embed_ms_values: list[float] = []
    aborted = None
    for gold in queries:
        if counts_requests:
            try:
                budget.ensure(1)
            except BudgetExhausted as exc:
                aborted = str(exc)
                print(f"  STOP: {exc}", file=sys.stderr)
                break
            throttle.wait()
        try:
            result = search(
                synquest_url, args.tenant, gold.question, top_k=max(10, 100),
                top_k_dense=args.top_k_dense, top_k_lexical=args.top_k_lexical,
            )
        except (SearchUnavailable, requests.RequestException) as exc:
            failed.append(gold.query_id)
            raw.append({"query_id": gold.query_id, "error": str(exc)[:300]})
            print(f"  {gold.query_id}: FAILED {exc}", file=sys.stderr)
            if counts_requests:
                budget.record(1)  # the embed may have been attempted; count it conservatively
                embed_requests += 1
            continue

        if counts_requests and not result.embed_cached and not result.embed_skipped:
            budget.record(1)
            embed_requests += 1
        embed_cached += int(result.embed_cached)
        if result.embed_skipped:
            skipped.append(gold.query_id)
        if result.embed_ms is not None and not result.embed_cached:
            embed_ms_values.append(float(result.embed_ms))

        retrieved_ids = [hit.chunk_id for hit in result.hits]
        qm = evaluate_query(QueryResult(query_id=gold.query_id, retrieved=retrieved_ids,
                                        relevant=gold.gold_chunk_ids, latency_ms=result.latency_ms))
        per_query.append(qm)
        raw.append({"query_id": gold.query_id, "retrieved": retrieved_ids, "latency_ms": result.latency_ms,
                    "embed_ms": result.embed_ms, "embed_cached": result.embed_cached,
                    "embed_skipped": result.embed_skipped})
        print(
            f"  {gold.query_id} [{gold.category}]: "
            f"recall@10={qm.recall_at_10:.2f} ndcg@10={qm.ndcg_at_10:.2f} "
            f"latency={qm.latency_ms:.0f}ms"
            + (" [embed skipped]" if result.embed_skipped else "")
            + (" [cached]" if result.embed_cached else "")
        )

    if spend_cmd:
        try:
            spend_after = snapshot(spend_cmd)
        except (SpendProbeFailed, OSError) as exc:
            print(f"Spend check failed after the run: {exc}", file=sys.stderr)

    validity = check_run(
        dense=dense, embed_skipped_queries=skipped, failed_queries=failed, stats=stats,
        embedding_model=args.embedding_model, embedding_dim=embedding_dim,
        spend_before=spend_before.usage_usd if spend_before else None,
        spend_after=spend_after.usage_usd if spend_after else None,
        free_only=free_only, aborted=aborted,
    )

    if not per_query:
        print("No query completed; nothing to score. " + "; ".join(validity.reasons), file=sys.stderr)
        return 2

    agg = aggregate(per_query)
    print(
        f"\n{agg.query_count} queries: "
        f"mean recall@10={agg.mean_recall_at_10:.3f} "
        f"mean ndcg@10={agg.mean_ndcg_at_10:.3f} "
        f"p95 latency={agg.p95_latency_ms:.0f}ms"
    )

    identity = RunIdentity(
        dataset_version=args.dataset_version,
        knowledge_version=args.knowledge_version,
        search_config=args.search_config,
        embedding_model=args.embedding_model,
        retrieval_strategy=args.retrieval_strategy,
        reranker=args.reranker,
    )
    gpu_section = None
    if plane != "none":
        gpu_section = {
            "plane": plane,
            "provider_mode": provider_mode,
            "embedding_dim": embedding_dim,
            "embed_requests": embed_requests,
            "embed_cached": embed_cached,
            "embed_skipped": len(skipped),
            "max_searches_per_minute": max_rpm,
            "daily_request_budget": daily_budget,
            "ledger_used_today": ledger.used_today(plane),
            "spend_before": spend_before.as_dict() if spend_before else None,
            "spend_after": spend_after.as_dict() if spend_after else None,
            "index": None if stats is None else {
                "doc_count": stats.doc_count, "vector_docs": stats.vector_docs,
                "dim_mismatches": stats.dim_mismatches, "missing_vectors": stats.missing_vectors,
                "embedding_model": stats.embedding_model, "embedding_dim": stats.embedding_dim,
            },
        }
    latency = None
    if embed_ms_values:
        latency = {
            "note": "query embedding only, uncached queries; on GPU-7 includes the WAN round trip — "
                    "not comparable across planes (plan §8)",
            "queries": len(embed_ms_values),
            "p50_embed_ms": _percentile(embed_ms_values, 50),
            "p95_embed_ms": _percentile(embed_ms_values, 95),
        }

    output_dir = args.output_dir if validity.valid else args.output_dir / "invalid"
    output_path = write_run_record(identity, agg, output_dir, run_id=args.run_id, gpu_plane=gpu_section,
                                   validity=validity.as_dict(), latency_breakdown=latency)
    write_hits(output_dir, args.run_id, raw)
    print(f"\nRun record written to {output_path}")
    if not validity.valid:
        print("RUN INVALID — not a reportable result:", file=sys.stderr)
        for reason in validity.reasons:
            print(f"  - {reason}", file=sys.stderr)
        return 2
    return 0


def _cmd_rescore(args: argparse.Namespace) -> int:
    """Recompute metrics from a run's saved hits against (re-annotated) gold — no searches."""
    hits_path = Path(args.hits)
    source = read_record(hits_path.with_name(hits_path.name.replace(".hits.json", ".yaml")))
    raw = read_hits(hits_path)
    gold = {q.query_id: q for q in load_gold_queries(args.queries)}
    per_query = []
    for row in raw:
        if "retrieved" not in row or row["query_id"] not in gold:
            continue
        per_query.append(evaluate_query(QueryResult(
            query_id=row["query_id"], retrieved=row["retrieved"],
            relevant=gold[row["query_id"]].gold_chunk_ids, latency_ms=row.get("latency_ms"))))
    if not per_query:
        print("No scorable queries in the hits file", file=sys.stderr)
        return 1
    agg = aggregate(per_query)
    identity = RunIdentity(**source["benchmark"])
    validity = source.get("validity")
    output_dir = args.output_dir if (validity is None or validity.get("valid")) else args.output_dir / "invalid"
    path = write_run_record(identity, agg, output_dir, run_id=args.run_id, gpu_plane=source.get("gpu_plane"),
                            validity=validity, latency_breakdown=source.get("latency_breakdown"),
                            extra={"rescored_from": str(hits_path.name), "rescored_queries": str(args.queries)})
    print(f"{agg.query_count} queries rescored: recall@10={agg.mean_recall_at_10:.3f} "
          f"ndcg@10={agg.mean_ndcg_at_10:.3f} → {path}")
    return 0


def _cmd_remap_gold(args: argparse.Namespace) -> int:
    """Carry gold chunk IDs to a re-ingested tenant by (source_uri, chunk_ordinal) + identical chunk_sha256."""
    from .remap import docker_cqlsh, remap_queries
    rows = [json.loads(line) for line in Path(args.queries).read_text().splitlines() if line.strip()]
    out_rows, report = remap_queries(rows, docker_cqlsh(args.cassandra_container), args.from_tenant, args.to_tenant)
    Path(args.out).write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in out_rows))
    print(f"{report.mapped} gold chunk IDs remapped {args.from_tenant} → {args.to_tenant}; wrote {args.out}")
    for u in report.unmapped:
        print(f"  UNMAPPED {u}", file=sys.stderr)
    if report.unmapped:
        print(f"{len(report.unmapped)} IDs need manual re-annotation (retrieval-eval inspect)", file=sys.stderr)
        return 1 if args.strict else 0
    return 0


def _cmd_annotate_markers(args: argparse.Namespace) -> int:
    """Gold chunk IDs for rows with gold_markers: chunks of gold_source whose text contains a marker."""
    from .annotate import annotate, docker_copy_export
    rows = [json.loads(line) for line in Path(args.queries).read_text().splitlines() if line.strip()]
    out_rows, report = annotate(rows, docker_copy_export(args.cassandra_container)(args.tenant), args.tenant)
    Path(args.out).write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in out_rows))
    print(f"tenant={args.tenant}: {report.annotated} rows annotated by marker, {report.kept} kept as-is; wrote {args.out}")
    if report.unmatched:
        print(f"  no chunk matched the markers of: {', '.join(report.unmatched)}", file=sys.stderr)
        return 1
    return 0


def _cmd_budget(args: argparse.Namespace) -> int:
    ledger = RequestLedger(args.ledger)
    print(f"ledger {ledger.path}: {args.gpu_plane} requests today = {ledger.used_today(args.gpu_plane)}")
    spend_cmd = args.spend_cmd if args.spend_cmd is not None else DEFAULT_SPEND_CMD
    if spend_cmd:
        try:
            snap = snapshot(spend_cmd)
            print(f"provider: spend ${snap.usage_usd}, free-model requests used today {snap.free_requests_used}")
        except (SpendProbeFailed, OSError) as exc:
            print(f"(spend check unavailable: {exc})", file=sys.stderr)
    return 0


def _add_plane_args(p: argparse.ArgumentParser) -> None:
    p.add_argument("--gpu-plane", choices=sorted(PROVIDER_MODES), default="none",
                   help="where embeddings come from: none (legacy HTTP/Phase 1), gpu-7 (external, free models), gpu-5 (local)")
    p.add_argument("--ledger", type=Path, default=RequestLedger().path,
                   help="local daily request ledger (git-ignored .cache/)")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="retrieval-eval")
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH)
    subparsers = parser.add_subparsers(dest="command", required=True)

    check_parser = subparsers.add_parser("check-config", help="Verify local dataset paths resolve")
    check_parser.set_defaults(func=_cmd_check_config)

    ingest_parser = subparsers.add_parser("ingest", help="Ingest a corpus into a tenant and reindex")
    ingest_parser.add_argument("--tenant", required=True)
    ingest_parser.add_argument("--path", default="/demo-data/documents")
    ingest_parser.add_argument("--retries", type=int, default=0,
                               help="re-run ingest while documents fail (completed ones are skipped)")
    ingest_parser.add_argument("--batch-size", type=int, default=32, help="synflux embedding batch size (ledger estimate)")
    _add_plane_args(ingest_parser)
    ingest_parser.set_defaults(func=_cmd_ingest)

    evaluate_parser = subparsers.add_parser("evaluate", help="Run a gold query set and score the results")
    evaluate_parser.add_argument("--tenant", required=True)
    evaluate_parser.add_argument("--queries", type=Path, required=True)
    evaluate_parser.add_argument("--run-id", required=True, help='e.g. "T01" - see plan §3.4')
    evaluate_parser.add_argument("--dataset-version", required=True)
    evaluate_parser.add_argument("--knowledge-version", required=True)
    evaluate_parser.add_argument("--search-config", required=True)
    evaluate_parser.add_argument("--embedding-model", required=True)
    evaluate_parser.add_argument("--retrieval-strategy", required=True)
    evaluate_parser.add_argument("--reranker", default="none")
    evaluate_parser.add_argument("--output-dir", type=Path, default=DEFAULT_RESULTS_DIR)
    evaluate_parser.add_argument("--top-k-dense", type=int, default=None, help="Pass 0 to suppress dense (T01 BM25-only)")
    evaluate_parser.add_argument("--top-k-lexical", type=int, default=None, help="Pass 1 to suppress lexical (T02 dense-only; synquest rejects 0)")
    _add_plane_args(evaluate_parser)
    evaluate_parser.add_argument("--provider-mode", default=None, help="default: gpu-7→external-free, gpu-5→local")
    evaluate_parser.add_argument("--embedding-dim", type=int, default=None, help="default: from /index/stats")
    evaluate_parser.add_argument("--max-rpm", type=int, default=None, help="searches per minute (default 15 on gpu-7; 0 = unthrottled)")
    evaluate_parser.add_argument("--daily-request-budget", type=int, default=None,
                                 help="stop before exceeding this many embedding requests today (default 900 on gpu-7; 0 = none)")
    evaluate_parser.add_argument("--spend-cmd", default=None,
                                 help="command printing spend JSON (default on gpu-7: gpu-runtime gpu7_check.py --usage; '' = skip)")
    evaluate_parser.set_defaults(func=_cmd_evaluate)

    rescore_parser = subparsers.add_parser("rescore", help="Recompute a run's metrics from its saved hits (no searches)")
    rescore_parser.add_argument("--hits", type=Path, required=True, help="<run>.hits.json written by evaluate")
    rescore_parser.add_argument("--queries", type=Path, required=True)
    rescore_parser.add_argument("--run-id", required=True)
    rescore_parser.add_argument("--output-dir", type=Path, default=DEFAULT_RESULTS_DIR)
    rescore_parser.set_defaults(func=_cmd_rescore)

    remap_parser = subparsers.add_parser(
        "remap-gold", help="Carry gold chunk IDs to a re-ingested tenant (same source files, identical chunks)")
    remap_parser.add_argument("--from-tenant", required=True)
    remap_parser.add_argument("--to-tenant", required=True)
    remap_parser.add_argument("--queries", type=Path, required=True, help="annotated gold file of --from-tenant")
    remap_parser.add_argument("--out", type=Path, required=True)
    remap_parser.add_argument("--cassandra-container", default="docker-cassandra-1")
    remap_parser.add_argument("--strict", action="store_true", help="exit 1 if any ID can't be carried over")
    remap_parser.set_defaults(func=_cmd_remap_gold)

    annotate_parser = subparsers.add_parser(
        "annotate-markers", help="Annotate gold chunk IDs from gold_markers/gold_source for one tenant")
    annotate_parser.add_argument("--tenant", required=True)
    annotate_parser.add_argument("--queries", type=Path, required=True)
    annotate_parser.add_argument("--out", type=Path, required=True)
    annotate_parser.add_argument("--cassandra-container", default="docker-cassandra-1")
    annotate_parser.set_defaults(func=_cmd_annotate_markers)

    budget_parser = subparsers.add_parser("budget", help="Show today's request ledger and provider spend/quota")
    _add_plane_args(budget_parser)
    budget_parser.add_argument("--spend-cmd", default=None)
    budget_parser.set_defaults(func=_cmd_budget)

    inspect_parser = subparsers.add_parser("inspect", help="Run a raw query and print every hit field (for gold-chunk-ID annotation)")
    inspect_parser.add_argument("--tenant", required=True)
    inspect_parser.add_argument("--query", required=True)
    inspect_parser.add_argument("--top-k", type=int, default=10)
    inspect_parser.set_defaults(func=_cmd_inspect)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
