"""Writes an ExtractionOutcome to the target output folder.

Two files per input document, named after it: `<stem>.txt` (the flattened text,
empty on failure) and `<stem>.json` (full outcome metadata - status, feature
states, element count, error detail) - so a failed extraction still leaves a
record of what happened, not just a missing file.
"""

from __future__ import annotations

import json
from dataclasses import asdict
from pathlib import Path

from .client import ExtractionOutcome


def write_outcome(outcome: ExtractionOutcome, source_file: Path, output_dir: Path) -> tuple[Path, Path]:
    output_dir.mkdir(parents=True, exist_ok=True)
    stem = source_file.stem

    text_path = output_dir / f"{stem}.txt"
    text_path.write_text(outcome.flattened_text)

    meta_path = output_dir / f"{stem}.json"
    meta_path.write_text(json.dumps(asdict(outcome), indent=2))

    return text_path, meta_path
