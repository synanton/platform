#!/usr/bin/env python3
"""Fail when changed files contain bidi or zero-width control characters."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


CONTROL_RANGES = (
    (0x202A, 0x202E),  # bidi embeddings/overrides/pop
    (0x2066, 0x2069),  # bidi isolates
    (0x200B, 0x200D),  # zero-width space/non-joiner/joiner
    (0xFEFF, 0xFEFF),  # zero-width no-break space/BOM in content
)


def is_forbidden(codepoint: int) -> bool:
    return any(start <= codepoint <= end for start, end in CONTROL_RANGES)


def changed_paths(base: str, head: str) -> list[Path]:
    output = subprocess.check_output(
        ["git", "diff", "--name-only", "-z", f"{base}...{head}"]
    )
    return [Path(name.decode()) for name in output.split(b"\0") if name]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True, help="base commit SHA")
    parser.add_argument("--head", required=True, help="head commit SHA")
    args = parser.parse_args()

    findings: list[str] = []
    for path in changed_paths(args.base, args.head):
        if not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for line_number, line in enumerate(text.splitlines(), start=1):
            for column, character in enumerate(line, start=1):
                codepoint = ord(character)
                if is_forbidden(codepoint):
                    findings.append(
                        f"{path}:{line_number}:{column}: forbidden U+{codepoint:04X}"
                    )

    if findings:
        print("Bidirectional/zero-width control characters found:")
        print("\n".join(findings))
        return 1
    print(f"Unicode control scan clean ({len(changed_paths(args.base, args.head))} changed paths).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
