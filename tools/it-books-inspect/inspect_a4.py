#!/usr/bin/env python3
"""Read-only A4.1 inspection report for the regex baseline.

This tool reads the A4 candidate export and A2 annotations. It never reruns
extraction, reconstructs historical prompts, or writes graph state.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Iterable


def load_json(path: Path) -> dict:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def utf16_slice(text: str, start: int, end: int) -> str:
    raw = text.encode("utf-16-le")
    return raw[start * 2:end * 2].decode("utf-16-le", errors="replace")


def context(text: str, start: int, end: int, radius: int = 70) -> str:
    left = max(0, start - radius)
    right = min(len(text.encode("utf-16-le")) // 2, end + radius)
    return utf16_slice(text, left, right).replace("\n", " ")


def source_texts(a2_dir: Path) -> dict[str, str]:
    manifest = load_json(a2_dir / "manifest.json")
    root = a2_dir.resolve().parents[2]
    return {
        source["sourceId"]: (root / source["excerptPath"]).read_text(encoding="utf-8")
        for source in manifest["sources"]
    }


def a2_mentions(annotations: dict) -> dict[str, dict]:
    return {mention["id"]: mention for mention in annotations["mentions"]}


def matched_a2_ids(candidates: dict) -> set[str]:
    return {
        mention["a2MentionId"]
        for mention in candidates["mentions"]
        if mention.get("a2MentionId")
    }


def md_escape(value: str) -> str:
    return value.replace("|", "\\|").replace("\n", " ")


def mention_table(rows: Iterable[dict], texts: dict[str, str], include_expected: bool = False) -> str:
    lines = ["| Source | Span | Surface / type | Source context | A2 match |", "|---|---|---|---|---|"]
    for row in rows:
        expected = ""
        if include_expected:
            expected = f"{row['a2MentionId']} / {row.get('a2Type')}"
        lines.append(
            "| {source} | {start}:{end} | `{surface}` / `{type}` | ...{ctx}... | {expected} |".format(
                source=row["sourceId"], start=row["documentStartOffset"], end=row["documentEndOffset"],
                surface=md_escape(row["surface"]), type=row["type"],
                ctx=md_escape(context(texts[row["sourceId"]], row["documentStartOffset"],
                                       row["documentEndOffset"])),
                expected=md_escape(expected),
            )
        )
    return "\n".join(lines)


def relation_table(rows: Iterable[dict]) -> str:
    lines = ["| Source | Predicate | Subject → object | A5 | A2 judgment | Evidence |",
             "|---|---|---|---|---|---|"]
    for row in rows:
        lines.append(
            "| {source} | `{predicate}` | `{subject}` ({stype}) → `{object}` ({otype}) | {a5} | {judgment} | {evidence} |".format(
                source=row["sourceId"], predicate=row["predicate"],
                subject=md_escape(row.get("subjectSurface") or "?"),
                stype=row.get("subjectType") or "?",
                object=md_escape(row.get("objectSurface") or "?"),
                otype=row.get("objectType") or "?",
                a5=row.get("a5Outcome", "?"),
                judgment=row.get("a2Judgment", "UNMATCHED"),
                evidence=row.get("evidenceStatus", "?"),
            )
        )
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--a4-dir", required=True, type=Path)
    parser.add_argument("--a2-dir", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--limit", type=int, default=8)
    args = parser.parse_args()

    candidates = load_json(args.a4_dir / "candidates.json")
    annotations = load_json(args.a2_dir / "annotations.json")
    texts = source_texts(args.a2_dir)
    expected_by_id = a2_mentions(annotations)
    matched = matched_a2_ids(candidates)

    mentions = candidates["mentions"]
    relations = candidates["relations"]
    false_positives = [m for m in mentions if not m.get("a2MentionId")]
    false_negatives = [
        {
            "sourceId": mention["sourceId"],
            "documentStartOffset": mention["documentStartOffset"],
            "documentEndOffset": mention["documentEndOffset"],
            "surface": mention["surface"],
            "type": mention["type"],
            "a2MentionId": mention["id"],
            "a2Type": mention["type"],
        }
        for mention in annotations["mentions"] if mention["id"] not in matched
    ]
    exact_matches = [m for m in mentions if m.get("a2MentionId")]
    type_mismatches = [m for m in exact_matches if not m.get("typeMatchesA2")]
    suspicious = sorted(
        false_positives,
        key=lambda m: (m.get("type") != "Concept", len(m.get("surface", ""))),
        reverse=True,
    )[:args.limit]

    out = [
        "# A4.1 manual inspection — regex baseline",
        "",
        "**Source:** A4 candidate export + A2 annotations.",
        "**Mode:** read-only; no extraction, prompt reconstruction, or graph writes.",
        "**Prompt/response availability:** not available for the regex path; no historical prompt was captured.",
        "",
        "This is a diagnostic sample, not a gold-label change or a replacement for A4 metrics.",
        "",
        "## Failure taxonomy",
        "",
        "| Bucket | Count in this export | Interpretation |",
        "|---|---:|---|",
        f"| Predicted mentions not matched to A2 | {len(false_positives)} | Candidate over-extraction, wrong span, or wrong type |",
        f"| A2 mentions not matched | {len(false_negatives)} | Missed span or non-identical boundary |",
        f"| Exact-span A2 matches | {len(exact_matches)} | Mention boundary agrees; type may still differ |",
        f"| Exact-span type mismatches | {len(type_mismatches)} | Detection worked but type assignment did not |",
        f"| Regex relation candidates | {len(relations)} | All are outside the A1 predicate vocabulary |",
        "",
        "## False-positive mention sample",
        "",
        mention_table(suspicious, texts),
        "",
        "## False-negative mention sample",
        "",
        mention_table(false_negatives[:args.limit], texts, include_expected=True),
        "",
        "## Exact-span matches",
        "",
        mention_table(exact_matches[:args.limit], texts, include_expected=True),
        "",
        "## Type mismatches on exact spans",
        "",
        mention_table(type_mismatches[:args.limit], texts, include_expected=True),
        "",
        "## Relation candidates",
        "",
        relation_table(relations),
        "",
        "All relation candidates use `is_a`, which A5 rejects as an unknown predicate. They cannot be accepted graph edges regardless of span quality.",
        "",
        "## Manual review template",
        "",
        "| Candidate | Human assessment | Notes |",
        "|---|---|---|",
        "| Each sampled mention/relation | correct / incomplete / unsupported / incorrect / unresolved | Fill in after source review; do not edit A2 or A4 |",
        "",
        "## What remains unavailable",
        "",
        "- System/user prompt and hydrated contract for a historical run.",
        "- Raw model response and Gateway request/execution IDs.",
        "- A causal attribution from prompt construction to a specific prediction.",
        "",
        "Those fields are explicitly not reconstructed. For LLM runs they require the protected interaction-trace workflow, not this regex diagnostic.",
    ]
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text("\n".join(out) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
