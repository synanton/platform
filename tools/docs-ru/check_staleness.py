#!/usr/bin/env python3
"""Warn when an English source has changed since its Russian translation pin."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from datetime import date
from pathlib import Path, PurePosixPath


PIN_PATTERN = re.compile(r"^([0-9a-f]{40}) @ (\d{4}-\d{2}-\d{2})$")
FIELD_PATTERN = re.compile(r"^([A-Za-z][A-Za-z0-9_-]*):\s*(.*?)\s*$")


def read_frontmatter(path: Path) -> dict[str, str]:
    lines = path.read_text(encoding="utf-8").splitlines()
    if not lines or lines[0] != "---":
        return {}
    try:
        end = lines.index("---", 1)
    except ValueError as error:
        raise ValueError("front matter has no closing delimiter") from error

    fields: dict[str, str] = {}
    for line_number, line in enumerate(lines[1:end], start=2):
        if not line.strip():
            continue
        match = FIELD_PATTERN.match(line)
        if not match:
            raise ValueError(f"unsupported front matter syntax on line {line_number}")
        key, value = match.groups()
        if key in fields:
            raise ValueError(f"duplicate front matter field: {key}")
        fields[key] = value.strip("\"'")
    return fields


def _run_git(root: Path, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "-C", str(root), *args],
        check=False,
        capture_output=True,
        text=True,
    )


def _source_path(root: Path, target: Path, explicit: str | None) -> str:
    if explicit:
        source = PurePosixPath(explicit)
    else:
        relative = target.relative_to(root / "docs-ru")
        source = PurePosixPath("docs", *relative.parts)
    if source.is_absolute() or ".." in source.parts or not source.parts or source.parts[0] != "docs":
        raise ValueError("source must be a repository-relative path under docs/")
    return source.as_posix()


def inspect_translation(root: Path, target: Path) -> tuple[str | None, str | None]:
    relative_target = target.relative_to(root).as_posix()
    try:
        fields = read_frontmatter(target)
    except (OSError, UnicodeError, ValueError) as error:
        return None, f"{relative_target}: invalid front matter: {error}"

    pin_value = fields.get("translated-from")
    if not pin_value:
        return None, f"{relative_target}: missing translated-from pin"
    pin_match = PIN_PATTERN.fullmatch(pin_value)
    if not pin_match:
        return None, f"{relative_target}: translated-from must be '<40-char SHA> @ YYYY-MM-DD'"
    pin, pin_date = pin_match.groups()
    try:
        date.fromisoformat(pin_date)
        source = _source_path(root, target, fields.get("source"))
    except ValueError as error:
        return None, f"{relative_target}: {error}"

    if not (root / source).is_file():
        return None, f"{relative_target}: English source does not exist: {source}"
    if _run_git(root, "cat-file", "-e", f"{pin}^{{commit}}").returncode != 0:
        return None, f"{relative_target}: translated-from commit is not available: {pin}"
    if _run_git(root, "cat-file", "-e", f"{pin}:{source}").returncode != 0:
        return None, f"{relative_target}: source did not exist at translated-from commit: {source}"

    changed = _run_git(root, "diff", "--quiet", pin, "HEAD", "--", source)
    if changed.returncode == 1:
        return (
            f"::warning file={relative_target}::English source {source} changed since pin {pin} ({pin_date})",
            None,
        )
    if changed.returncode != 0:
        return None, f"{relative_target}: git diff failed while checking source {source}"
    return None, None


def check_repository(root: Path) -> tuple[list[str], list[str], int]:
    docs_ru = root / "docs-ru"
    if not docs_ru.is_dir():
        return [], ["docs-ru/ does not exist"], 0

    warnings: list[str] = []
    errors: list[str] = []
    checked = 0
    for target in sorted(docs_ru.rglob("*.md")):
        relative = target.relative_to(docs_ru)
        architecture_mirror = bool(relative.parts and relative.parts[0] == "architecture")
        try:
            fields = read_frontmatter(target)
        except (OSError, UnicodeError, ValueError) as error:
            if architecture_mirror:
                errors.append(f"{target.relative_to(root).as_posix()}: invalid front matter: {error}")
            continue
        if not architecture_mirror and "translated-from" not in fields:
            continue

        checked += 1
        warning, error = inspect_translation(root, target)
        if warning:
            warnings.append(warning)
        if error:
            errors.append(error)
    return warnings, errors, checked


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".", help="repository root (default: current directory)")
    args = parser.parse_args(argv)
    root = Path(args.root).resolve()

    warnings, errors, checked = check_repository(root)
    for warning in warnings:
        print(warning)
    for error in errors:
        print(f"::error::{error}")
    print(f"Checked {checked} translated document(s): {len(warnings)} stale warning(s), {len(errors)} error(s).")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
