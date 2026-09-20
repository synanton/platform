"""Command-line entry point for the retrieval evaluation harness.

    retrieval-eval check-config
    retrieval-eval ingest    --tenant demo
    retrieval-eval evaluate  --tenant demo --queries <queries.jsonl> --run-id T01 \\
                              --dataset-version ... --knowledge-version ... \\
                              --search-config hybrid-rrf --embedding-model bge-base-en-v1.5 \\
                              --retrieval-strategy hybrid --reranker none

See docs/research/retrieval-evaluation-benchmark-plan.md §6 Phase B0-B1 for
where this fits, and §3.4 for the test-matrix row IDs `--run-id` is meant to
match.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from . import compose
from .config import DEFAULT_CONFIG_PATH, check_dataset_root, load_config
from .gold import load_gold_queries
from .ingest import IngestionFailed, IngestionTimedOut, ingest, reindex
from .metrics import QueryResult, aggregate, evaluate_query
from .query import search
from .run_record import RunIdentity, write_run_record

DEFAULT_RESULTS_DIR = Path(__file__).resolve().parents[3] / "demo-data" / "eval" / "retrieval-benchmark" / "results"


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
    print(f"Ingesting tenant={args.tenant} path={args.path} via {synflux_url} ...")
    try:
        result = ingest(synflux_url, args.tenant, args.path)
    except (IngestionFailed, IngestionTimedOut) as exc:
        print(f"Ingestion did not complete: {exc}", file=sys.stderr)
        return 1

    print(f"Ingested {result.processed_count} documents (errors={result.error_count})")
    print("Reindexing synquest ...")
    reindex(synquest_url, args.tenant)
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

    per_query = []
    for gold in queries:
        result = search(
            synquest_url, args.tenant, gold.question, top_k=max(10, 100),
            top_k_dense=args.top_k_dense, top_k_lexical=args.top_k_lexical,
        )
        retrieved_ids = [hit.chunk_id for hit in result.hits]
        qm = evaluate_query(
            QueryResult(
                query_id=gold.query_id,
                retrieved=retrieved_ids,
                relevant=gold.gold_chunk_ids,
                latency_ms=result.latency_ms,
            )
        )
        per_query.append(qm)
        print(
            f"  {gold.query_id} [{gold.category}]: "
            f"recall@10={qm.recall_at_10:.2f} ndcg@10={qm.ndcg_at_10:.2f} "
            f"latency={qm.latency_ms:.0f}ms"
        )

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
    output_path = write_run_record(identity, agg, args.output_dir, run_id=args.run_id)
    print(f"\nRun record written to {output_path}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="retrieval-eval")
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH)
    subparsers = parser.add_subparsers(dest="command", required=True)

    check_parser = subparsers.add_parser("check-config", help="Verify local dataset paths resolve")
    check_parser.set_defaults(func=_cmd_check_config)

    ingest_parser = subparsers.add_parser("ingest", help="Ingest a corpus into a tenant and reindex")
    ingest_parser.add_argument("--tenant", required=True)
    ingest_parser.add_argument("--path", default="/demo-data/documents")
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
    evaluate_parser.set_defaults(func=_cmd_evaluate)

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
