"""Command-line entry point for the Benchmark Runner.

    benchmark-runner validate <manifest.json>
    benchmark-runner verify <manifest.json> --corpus <path>
    benchmark-runner run <manifest.json> --corpus <path> [--dry-run]
"""

from __future__ import annotations

import argparse
import json as json_module
import sys
from pathlib import Path

from . import costs, repro
from .manifest import ManifestError, load_manifest

EXIT_MANIFEST_ERROR = 2
EXIT_REPRO_FAILURE = 3
EXIT_COST_FIRED = 4


def cmd_validate(args: argparse.Namespace) -> int:
    try:
        manifest = load_manifest(Path(args.manifest))
    except ManifestError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    compositions = ", ".join(manifest.composition_ids)
    print(f"manifest {manifest.manifest_id}: valid")
    print(f"  corpus: {manifest.corpus_ref}")
    print(f"  compositions ({len(manifest.composition_ids)}): {compositions}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="benchmark-runner")
    sub = parser.add_subparsers(dest="command", required=True)
    validate = sub.add_parser("validate", help="validate a Benchmark Manifest against the schema")
    validate.add_argument("manifest", help="path to the manifest JSON file")
    validate.set_defaults(func=cmd_validate)
    verify = sub.add_parser("verify", help="verify reproducibility preconditions (corpus hash + seed)")
    verify.add_argument("manifest", help="path to the manifest JSON file")
    verify.add_argument("--corpus", required=True, help="path to the corpus file or directory")
    verify.set_defaults(func=cmd_verify)
    run = sub.add_parser("run", help="execute a manifest against a corpus")
    run.add_argument("manifest", help="path to the manifest JSON file")
    run.add_argument("--corpus", help="path to the corpus file or directory")
    run.add_argument("--queries", help="path to a JSON array of {query_id, gold[], eligible[]}")
    run.add_argument("--out", help="result sink base directory (FileResultSink)")
    run.add_argument("--executor", default="simulated", choices=["simulated"],
                     help="composition executor (Synquest-backed lands with B5)")
    run.add_argument("--dry-run", action="store_true", help="validate only; produce no artifacts")
    run.set_defaults(func=cmd_run)
    return parser


def cmd_verify(args: argparse.Namespace) -> int:
    try:
        manifest = load_manifest(Path(args.manifest))
    except ManifestError as e:
        print(f"error: {e}", file=sys.stderr)
        return EXIT_MANIFEST_ERROR
    try:
        report = repro.verify(manifest.document, Path(args.corpus))
    except repro.ReproducibilityError as e:
        print(f"error: {e}", file=sys.stderr)
        return EXIT_REPRO_FAILURE
    print(f"reproducibility verified: corpus {report.corpus_hash[:12]}… seed {report.seed}")
    return 0


def cmd_run(args: argparse.Namespace) -> int:
    try:
        manifest = load_manifest(Path(args.manifest))
    except ManifestError as e:
        print(f"error: {e}", file=sys.stderr)
        return EXIT_MANIFEST_ERROR
    controls = costs.controls_from_manifest(manifest.document)
    if args.dry_run or controls["dry_run"]:
        print(f"dry run: manifest {manifest.manifest_id} valid; "
              f"{len(manifest.composition_ids)} compositions would execute; no artifacts produced")
        return 0
    if not args.corpus or not args.queries or not args.out:
        print("error: --corpus, --queries and --out are required (or use --dry-run)",
              file=sys.stderr)
        return EXIT_REPRO_FAILURE
    import json as json_module

    from .executor import SimulatedExecutor
    from .runner import run_manifest
    from .sinks import FileResultSink

    try:
        queries = json_module.loads(Path(args.queries).read_text())
    except (OSError, ValueError) as e:
        print(f"error: cannot load queries: {e}", file=sys.stderr)
        return EXIT_MANIFEST_ERROR
    executor = SimulatedExecutor(seed=manifest.document["reproducibility"]["seed"])
    sink = FileResultSink(Path(args.out))
    try:
        artifacts = run_manifest(
            manifest, Path(args.corpus), queries, executor, sink,
            environment={"runner_version": "0.2.0"},
        )
    except ManifestError as e:
        print(f"error: {e}", file=sys.stderr)
        return EXIT_MANIFEST_ERROR
    except Exception as e:
        print(f"error: {e}", file=sys.stderr)
        return EXIT_COST_FIRED
    for path in artifacts:
        print(f"wrote {path}")
    return 0


def main(argv: list = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
