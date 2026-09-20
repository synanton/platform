"""Command-line entry point for extraction-probe.

    extraction-probe extract --input path/to/document.pdf --output-dir path/to/target

Uploads the file to the configured object store, calls the real extraction-gateway
ExtractSync RPC, and writes the flattened text (plus a JSON metadata record) into
--output-dir.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from .client import extract_sync, guess_media_type
from .config import DEFAULT_CONFIG_PATH, load_config
from .objectstore import upload_file
from .output import write_outcome


def _cmd_extract(args: argparse.Namespace) -> int:
    config = load_config(args.config)
    input_path = args.input
    if not input_path.is_file():
        print(f"Input file not found: {input_path}", file=sys.stderr)
        return 1

    media_type = args.media_type or guess_media_type(input_path)
    print(f"Uploading {input_path} (media_type={media_type}) to "
          f"{config.objectstore.endpoint}/{config.objectstore.bucket} ...")
    uploaded = upload_file(config.objectstore, input_path)
    print(f"  uploaded as {uploaded.bucket}/{uploaded.key} "
          f"({uploaded.size_bytes} bytes, sha256={uploaded.sha256[:12]}...)")

    print(f"Calling ExtractSync on {config.extraction_gateway.target} ...")
    outcome = extract_sync(
        config.extraction_gateway,
        config.proto_source_dir,
        uploaded,
        media_type,
        tenant_id=args.tenant,
    )

    print(f"  status={outcome.status}")
    if outcome.error_code:
        print(f"  error_code={outcome.error_code}")
        print(f"  error_diagnostic={outcome.error_diagnostic}")
    else:
        print(f"  flattened_text_len={len(outcome.flattened_text)} "
              f"element_count={outcome.element_count}")
        print(f"  feature_states={outcome.feature_states}")

    text_path, meta_path = write_outcome(outcome, input_path, args.output_dir)
    print(f"\nWrote {text_path}")
    print(f"Wrote {meta_path}")

    return 0 if outcome.status in ("STATUS_COMPLETED", "STATUS_PARTIAL") else 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="extraction-probe")
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH)
    subparsers = parser.add_subparsers(dest="command", required=True)

    extract_parser = subparsers.add_parser(
        "extract", help="Upload a document and save the extraction-gateway's textual response"
    )
    extract_parser.add_argument("--input", type=Path, required=True, help="Path to the document to upload (e.g. a PDF)")
    extract_parser.add_argument("--output-dir", type=Path, required=True, help="Folder to write <name>.txt / <name>.json into")
    extract_parser.add_argument("--media-type", default=None, help="Override the auto-detected media type")
    extract_parser.add_argument("--tenant", default="extraction-probe")
    extract_parser.set_defaults(func=_cmd_extract)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
