"""Command-line entry point for the Benchmark Runner.

    benchmark-runner validate <manifest.json>
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from .manifest import ManifestError, load_manifest


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
    return parser


def main(argv: list = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
