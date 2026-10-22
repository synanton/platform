#!/usr/bin/env python3
"""Build deterministic translation packets and validate Russian mirrors."""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Sequence


GLOSSARY_HEADER = ("english term", "russian rendering", "do-not-translate flag")
TOOL_VERSION = "0.1.0"
TRUE_FLAGS = {"yes", "true", "do-not-translate"}
FALSE_FLAGS = {"no", "false", "translate"}
PROTECTED_FIELDS = ("**Status:**", "**Basis:**")
PROTECTED_SECTION_HEADINGS = ("## Decision", "## Context")
SECTION_HEADING = re.compile(
    r"^ {0,3}#{1,6}\s+(?:§{1,2}\s*)?(\d+(?:\.\d+)*)(?=$|[.)\s])"
)
SECTION_REFERENCE = re.compile(r"§{1,2}\s*\d+(?:\.\d+)*(?:\s*[-–—]\s*\d+(?:\.\d+)*)?")
LINK_ANCHOR = re.compile(r"\]\([^)]*#([^\s)]+)\)")
FENCE_OPEN = re.compile(r"^ {0,3}(`{3,}|~{3,})(.*)$")
TOP_LEVEL_SECTION = re.compile(r"^ {0,3}#\s+(\d+)\.\s+")
SECTION_RANGE = re.compile(r"^(\d+)-(\d+)$")


@dataclass(frozen=True)
class GlossaryEntry:
    english: str
    russian: str
    do_not_translate: bool


def parse_glossary(text: str) -> list[GlossaryEntry]:
    """Read the canonical three-column Markdown glossary."""
    entries: list[GlossaryEntry] = []
    header_seen = False

    for line_number, line in enumerate(text.splitlines(), start=1):
        if not line.lstrip().startswith("|"):
            continue
        cells = [cell.strip().strip("`").strip() for cell in line.strip().strip("|").split("|")]
        if len(cells) != 3:
            raise ValueError(f"glossary line {line_number} must have exactly three columns")

        normalized = tuple(cell.casefold() for cell in cells)
        if not header_seen:
            if normalized != GLOSSARY_HEADER:
                raise ValueError("glossary must start with the English term / Russian rendering / do-not-translate flag header")
            header_seen = True
            continue

        if all(re.fullmatch(r":?-{3,}:?", cell.replace(" ", "")) for cell in cells):
            continue

        flag = cells[2].casefold().replace(" ", "-")
        if flag not in TRUE_FLAGS | FALSE_FLAGS:
            raise ValueError(f"glossary line {line_number} flag must be yes or no")
        if not cells[0] or not cells[1]:
            raise ValueError(f"glossary line {line_number} has an empty term or rendering")
        entries.append(GlossaryEntry(cells[0], cells[1], flag in TRUE_FLAGS))

    if not header_seen:
        raise ValueError("glossary three-column header was not found")
    if not entries:
        raise ValueError("glossary has no entries")
    terms = [entry.english for entry in entries]
    if len(terms) != len(set(terms)):
        raise ValueError("glossary contains duplicate English terms")
    return entries


def resolve_repo_path(root: Path, value: str, *, must_exist: bool = True) -> Path:
    path = Path(value)
    if not path.is_absolute():
        path = root / path
    resolved = path.resolve()
    try:
        resolved.relative_to(root.resolve())
    except ValueError as error:
        raise ValueError(f"path must be inside repository root: {value}") from error
    if must_exist and not resolved.is_file():
        raise ValueError(f"file does not exist: {value}")
    return resolved


def ensure_docs_ru_target(root: Path, target: Path) -> None:
    docs_ru = (root / "docs-ru").resolve()
    try:
        target.resolve().relative_to(docs_ru)
    except ValueError as error:
        raise ValueError("target path must be inside docs-ru/") from error


def ensure_canonical_glossary(root: Path, glossary: Path) -> None:
    expected = (root / "docs-ru/GLOSSARY.md").resolve()
    if glossary.resolve() != expected:
        raise ValueError("glossary path must be docs-ru/GLOSSARY.md")


def parse_section_range(value: str | None) -> tuple[int, int] | None:
    if value is None:
        return None
    match = SECTION_RANGE.fullmatch(value)
    if not match:
        raise ValueError("section range must use START-END notation, for example 55-62")
    start, end = int(match.group(1)), int(match.group(2))
    if start < 1 or end < start:
        raise ValueError("section range must be positive and ascending")
    return start, end


def extract_section_range(text: str, section_range: tuple[int, int]) -> str:
    """Extract complete top-level numbered sections, excluding adjacent sections."""
    start, end = section_range
    lines = text.splitlines(keepends=True)
    start_index: int | None = None
    end_index: int | None = None
    end_found = False
    fence_char = ""
    fence_size = 0

    for index, line in enumerate(lines):
        without_newline = line.rstrip("\r\n")
        if fence_char:
            closing = re.match(r"^ {0,3}(`+|~+)[ \t]*$", without_newline)
            if closing and closing.group(1)[0] == fence_char and len(closing.group(1)) >= fence_size:
                fence_char = ""
                fence_size = 0
            continue

        opening = FENCE_OPEN.match(without_newline)
        if opening:
            marker = opening.group(1)
            fence_char = marker[0]
            fence_size = len(marker)
            continue

        heading = TOP_LEVEL_SECTION.match(without_newline)
        if not heading:
            continue
        number = int(heading.group(1))
        if start_index is None:
            if number == start:
                start_index = index
                end_found = number == end
            continue
        if number == end:
            end_found = True
        elif number > end:
            end_index = index
            break

    if start_index is None:
        raise ValueError(f"section {start} was not found as a top-level numbered heading")
    if not end_found:
        raise ValueError(f"section {end} was not found after section {start}")
    if end_index is None:
        end_index = len(lines)
    return "".join(lines[start_index:end_index])


def _relative(root: Path, path: Path) -> str:
    return path.relative_to(root.resolve()).as_posix()


def prepare_packet(
    root: Path,
    source_value: str,
    target_value: str,
    glossary_value: str,
    section_range: tuple[int, int] | None = None,
) -> dict:
    source = resolve_repo_path(root, source_value)
    target = resolve_repo_path(root, target_value, must_exist=False)
    glossary_path = resolve_repo_path(root, glossary_value)
    ensure_docs_ru_target(root, target)
    ensure_canonical_glossary(root, glossary_path)

    source_text = source.read_text(encoding="utf-8")
    if section_range is not None:
        source_text = extract_section_range(source_text, section_range)
    entries = parse_glossary(glossary_path.read_text(encoding="utf-8"))
    packet = {
        "constraints": [
            "Translate prose only; preserve identifiers marked do-not-translate in the glossary.",
            "Preserve every fenced code block verbatim.",
            "Preserve numbered section headings, explicit section references, and link anchors.",
            "Keep **Status:**, **Basis:**, ## Decision, and ## Context field names unchanged.",
        ],
        "glossary": [asdict(entry) for entry in entries],
        "source": source_text,
        "source_path": _relative(root, source),
        "target_path": _relative(root, target),
        "tool_version": TOOL_VERSION,
    }
    if section_range is not None:
        packet["section_range"] = f"{section_range[0]}-{section_range[1]}"
    return packet


def _fenced_blocks(text: str) -> tuple[list[str], bool]:
    lines = text.splitlines(keepends=True)
    blocks: list[str] = []
    current: list[str] = []
    fence_char = ""
    fence_size = 0

    for line in lines:
        without_newline = line.rstrip("\r\n")
        if not fence_char:
            opening = FENCE_OPEN.match(without_newline)
            if opening:
                marker = opening.group(1)
                fence_char = marker[0]
                fence_size = len(marker)
                current = [line]
            continue

        current.append(line)
        closing = re.match(r"^ {0,3}(`+|~+)[ \t]*$", without_newline)
        if closing and closing.group(1)[0] == fence_char and len(closing.group(1)) >= fence_size:
            blocks.append("".join(current))
            current = []
            fence_char = ""
            fence_size = 0

    return blocks, not fence_char


def _section_headings(text: str) -> list[str]:
    return [match.group(1) for line in text.splitlines() if (match := SECTION_HEADING.match(line))]


def _count_token(text: str, token: str) -> int:
    pattern = re.compile(rf"(?<![\w]){re.escape(token)}(?![\w])", re.UNICODE)
    return len(pattern.findall(text))


def validate_translation(source_text: str, target_text: str, entries: Sequence[GlossaryEntry]) -> dict:
    source_fences, source_fences_well_formed = _fenced_blocks(source_text)
    target_fences, target_fences_well_formed = _fenced_blocks(target_text)
    identifier_mismatches = []
    for entry in entries:
        if not entry.do_not_translate:
            continue
        source_count = _count_token(source_text, entry.english)
        target_count = _count_token(target_text, entry.english)
        if source_count != target_count:
            identifier_mismatches.append(
                {"term": entry.english, "source_count": source_count, "target_count": target_count}
            )

    checks = [
        {
            "name": "fenced_code_verbatim",
            "passed": source_fences_well_formed and target_fences_well_formed and source_fences == target_fences,
        },
        {"name": "numbered_section_headings", "passed": _section_headings(source_text) == _section_headings(target_text)},
        {
            "name": "section_references_and_link_anchors",
            "passed": SECTION_REFERENCE.findall(source_text) == SECTION_REFERENCE.findall(target_text)
            and LINK_ANCHOR.findall(source_text) == LINK_ANCHOR.findall(target_text),
        },
        {"name": "untranslated_identifiers", "passed": not identifier_mismatches, "mismatches": identifier_mismatches},
    ]
    for field in PROTECTED_FIELDS:
        checks.append(
            {"name": f"field:{field}", "passed": source_text.count(field) == target_text.count(field)}
        )
    for heading in PROTECTED_SECTION_HEADINGS:
        heading_pattern = re.compile(rf"^{re.escape(heading)}\s*$", re.MULTILINE)
        checks.append(
            {"name": f"field:{heading}", "passed": len(heading_pattern.findall(source_text)) == len(heading_pattern.findall(target_text))}
        )

    return {"checks": checks, "valid": all(check["passed"] for check in checks)}


def _emit(result: dict) -> None:
    json.dump(result, sys.stdout, ensure_ascii=False, sort_keys=True, indent=2)
    sys.stdout.write("\n")


def _add_common_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--root", default=".", help="repository root (default: current directory)")
    parser.add_argument("--source", required=True, help="English source path relative to repository root")
    parser.add_argument("--target", required=True, help="Russian target path; must be under docs-ru/")
    parser.add_argument("--glossary", required=True, help="three-column glossary path relative to repository root")
    parser.add_argument("--sections", help="optional inclusive range of top-level numbered sections, e.g. 55-62")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    prepare_parser = subparsers.add_parser("prepare", help="emit a deterministic translation packet as JSON")
    _add_common_arguments(prepare_parser)
    validate_parser = subparsers.add_parser("validate", help="check a Russian mirror against its source and glossary")
    _add_common_arguments(validate_parser)

    args = parser.parse_args(argv)
    root = Path(args.root).resolve()
    try:
        section_range = parse_section_range(args.sections)
        if args.command == "prepare":
            _emit(prepare_packet(root, args.source, args.target, args.glossary, section_range))
            return 0

        source_path = resolve_repo_path(root, args.source)
        source = source_path.read_text(encoding="utf-8")
        if section_range is not None:
            source = extract_section_range(source, section_range)
        target_path = resolve_repo_path(root, args.target)
        ensure_docs_ru_target(root, target_path)
        target = target_path.read_text(encoding="utf-8")
        glossary_path = resolve_repo_path(root, args.glossary)
        ensure_canonical_glossary(root, glossary_path)
        entries = parse_glossary(glossary_path.read_text(encoding="utf-8"))
        result = validate_translation(source, target, entries)
        result.update(
            {
                "source_path": _relative(root, source_path),
                "target_path": _relative(root, target_path),
                "tool_version": TOOL_VERSION,
                "valid": result["valid"],
            }
        )
        if section_range is not None:
            result["section_range"] = f"{section_range[0]}-{section_range[1]}"
        _emit(result)
        return 0 if result["valid"] else 1
    except (OSError, UnicodeError, ValueError) as error:
        _emit({"error": str(error), "valid": False})
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
