#!/usr/bin/env python3
"""Validate DEV-25 provenance declarations and derived metadata.

Read-only. No model inference, no label changes. The authority model is:

  manifest.tsv          -> document inventory / record existence
  annotation-status.tsv -> per-document validation
  gold-release.md       -> release decision (prose must match the table/status)
  gold/PENDING.tsv      -> derived from annotation-status.tsv

Run from the repository or CI:
  python3 evaluation/dev25/check_provenance.py
"""
from __future__ import annotations

import csv
import hashlib
import re
import sys
from pathlib import Path

DEV = Path(__file__).resolve().parent
VALIDATED = "VALIDATED_GOLD"


def read_tsv(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle, delimiter="\t"))


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def status_counts(status_rows: list[dict[str, str]]) -> tuple[int, int]:
    validated = sum(row.get("annotation_status") == VALIDATED for row in status_rows)
    return validated, len(status_rows)


def check_status_line(text: str, label: str, validated: int, total: int,
                      errors: list[str]) -> None:
    match = re.search(r"validated\s+(\d+)\s*/\s*(\d+)", text)
    if not match:
        errors.append(f"{label}: missing 'validated X/Y' status count")
    else:
        stated_validated, stated_total = int(match.group(1)), int(match.group(2))
        if (stated_validated, stated_total) != (validated, total):
            errors.append(
                f"{label}: validated count {stated_validated}/{stated_total} "
                f"!= annotation-status {validated}/{total}")
    pending = re.search(r"pending\s+(\d+)", text)
    if not pending:
        errors.append(f"{label}: missing 'pending N' status count")
    elif int(pending.group(1)) != total - validated:
        errors.append(
            f"{label}: pending count {pending.group(1)} "
            f"!= annotation-status {total - validated}")


def check_release_table(text: str, validated: int, total: int,
                        errors: list[str]) -> None:
    required = [
        f"| {validated}/{total} validated gold | Yes |",
        f"| All {total} have annotations | Yes |",
        "| Evidence spans validated | Yes |",
        "| Pending list | Empty",
    ]
    for line in required:
        if line not in text:
            errors.append(f"gold-release.md: missing acceptance row: {line}")


def check_checksums(dev: Path, errors: list[str]) -> None:
    checksums = dev / "checksums.sha256"
    if not checksums.exists():
        errors.append("checksums.sha256: missing")
        return
    for raw in checksums.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line:
            continue
        parts = line.split(maxsplit=1)
        if len(parts) != 2:
            errors.append(f"checksums.sha256: malformed line: {raw}")
            continue
        expected, rel = parts[0], parts[1].strip()
        path = dev / rel
        if not path.exists():
            errors.append(f"checksums.sha256: listed file missing: {rel}")
        elif sha256(path) != expected:
            errors.append(f"checksums.sha256: checksum mismatch: {rel}")


def run(dev: Path = DEV) -> list[str]:
    errors: list[str] = []
    manifest = read_tsv(dev / "manifest.tsv")
    status = read_tsv(dev / "annotation-status.tsv")
    pending = read_tsv(dev / "gold" / "PENDING.tsv")
    touched = read_tsv(dev / "development-touched.tsv")

    if len(manifest) != 25:
        errors.append(f"manifest.tsv: expected 25 rows, got {len(manifest)}")
    if len(status) != 25:
        errors.append(f"annotation-status.tsv: expected 25 rows, got {len(status)}")

    manifest_ids = [row.get("doc_id", "") for row in manifest]
    status_ids = [row.get("doc_id", "") for row in status]
    if len(set(manifest_ids)) != len(manifest_ids):
        errors.append("manifest.tsv: duplicate doc_id")
    if len(set(status_ids)) != len(status_ids):
        errors.append("annotation-status.tsv: duplicate doc_id")
    if set(manifest_ids) != set(status_ids):
        missing_status = sorted(set(manifest_ids) - set(status_ids))
        missing_manifest = sorted(set(status_ids) - set(manifest_ids))
        errors.append(
            "manifest/status identity mismatch: "
            f"missing_status={missing_status} missing_manifest={missing_manifest}")

    status_by_id = {row.get("doc_id", ""): row for row in status}
    for row in manifest:
        doc_id = row.get("doc_id", "")
        other = status_by_id.get(doc_id)
        if other is None:
            continue
        if row.get("annotation_status") != other.get("annotation_status"):
            errors.append(
                f"{doc_id}: status mismatch manifest={row.get('annotation_status')} "
                f"status={other.get('annotation_status')}")

    validated, total = status_counts(status)
    validated_ids = {row.get("doc_id", "") for row in status
                     if row.get("annotation_status") == VALIDATED}
    nonvalidated_ids = {row.get("doc_id", "") for row in status} - validated_ids

    readme = (dev / "README.md").read_text(encoding="utf-8")
    release = (dev / "gold-release.md").read_text(encoding="utf-8")
    check_status_line(readme, "README.md", validated, total, errors)
    check_status_line(release, "gold-release.md", validated, total, errors)
    check_release_table(release, validated, total, errors)

    for label, text in (("README.md", readme), ("gold-release.md", release)):
        for stale in ("Only the five documents", "Exactly 5", "20 remain UNLABELED",
                      "NOT RELEASED", "5 preserved"):
            if stale in text:
                errors.append(f"{label}: stale declaration remains: {stale}")

    if validated == total:
        if f"All {total} documents are VALIDATED_GOLD" not in readme:
            errors.append("README.md: missing 'All N documents are VALIDATED_GOLD'")
        if f"All {total} are VALIDATED_GOLD" not in release:
            errors.append("gold-release.md: missing 'All N are VALIDATED_GOLD'")

    pending_ids = {row.get("doc_id", "") for row in pending}
    if pending_ids != nonvalidated_ids:
        errors.append(
            "PENDING.tsv is not exactly the non-validated set: "
            f"pending={sorted(pending_ids)} expected={sorted(nonvalidated_ids)}")

    touched_ids = [row.get("doc_id", "") for row in touched]
    if len(set(touched_ids)) != len(touched_ids):
        errors.append("development-touched.tsv: duplicate doc_id")
    for row in touched:
        doc_id = row.get("doc_id", "")
        if doc_id not in status_by_id:
            errors.append(f"development-touched.tsv: unknown doc_id {doc_id}")
            continue
        if status_by_id[doc_id].get("annotation_status") != VALIDATED:
            errors.append(f"development-touched.tsv: {doc_id} is not VALIDATED_GOLD")
        if row.get("labels_consumed") != "none":
            errors.append(f"development-touched.tsv: {doc_id} labels_consumed must be 'none'")

    check_checksums(dev, errors)
    return errors


def main() -> int:
    errors = run()
    try:
        validated, total = status_counts(read_tsv(DEV / "annotation-status.tsv"))
    except Exception:  # noqa: BLE001 - report before failing
        validated = total = -1
    print(f"provenance: validated={validated}/{total} errors={len(errors)}")
    for error in errors:
        print("  ERR", error)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
