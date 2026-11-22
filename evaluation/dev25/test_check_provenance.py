#!/usr/bin/env python3
"""Synthetic drift tests for check_provenance.py."""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import check_provenance as cp

TOTAL = 25
CHECKSUMMED = [
    "manifest.tsv",
    "annotation-status.tsv",
    "README.md",
    "gold-release.md",
    "gold/PENDING.tsv",
    "development-touched.tsv",
]


def write_fixture(dev: Path) -> None:
    (dev / "gold").mkdir(parents=True, exist_ok=True)
    manifest = ["doc_id\tdocument_id\tcategory\tannotation_status"]
    status = ["doc_id\tdocument_id\tcategory\tannotation_status\treview_status"]
    for index in range(1, TOTAL + 1):
        doc_id = f"g7-dev-{index:03d}"
        manifest.append(f"{doc_id}\tdoc-{index}.txt\tcategory\tVALIDATED_GOLD")
        status.append(f"{doc_id}\tdoc-{index}.txt\tcategory\tVALIDATED_GOLD\tcomplete")
    (dev / "manifest.tsv").write_text("\n".join(manifest) + "\n", encoding="utf-8")
    (dev / "annotation-status.tsv").write_text("\n".join(status) + "\n", encoding="utf-8")
    (dev / "README.md").write_text(
        "# fixture\n\n"
        f"**Status:** validated {TOTAL}/{TOTAL}; pending 0 (derived).\n\n"
        f"All {TOTAL} documents are VALIDATED_GOLD.\n",
        encoding="utf-8")
    (dev / "gold-release.md").write_text(
        "# fixture release\n\n"
        f"**Status:** RELEASED; validated {TOTAL}/{TOTAL}; pending 0.\n\n"
        f"All {TOTAL} are VALIDATED_GOLD.\n\n"
        "| Criterion | State |\n"
        "|---|---|\n"
        f"| {TOTAL}/{TOTAL} validated gold | Yes |\n"
        f"| All {TOTAL} have annotations | Yes |\n"
        "| Evidence spans validated | Yes |\n"
        "| Pending list | Empty (derived) |\n",
        encoding="utf-8")
    (dev / "gold" / "PENDING.tsv").write_text(
        "doc_id\tdocument_id\tcategory\tstatus\n", encoding="utf-8")
    (dev / "development-touched.tsv").write_text(
        "doc_id\tdocument_id\tused_in\tdate\tscope\tlabels_consumed\tnote\n"
        "g7-dev-001\tdoc-1.txt\t#820\t2026-11-22\tcontract tests\tnone\tprovenance only\n",
        encoding="utf-8")


def refresh_checksums(dev: Path) -> None:
    lines = []
    for rel in CHECKSUMMED:
        lines.append(f"{cp.sha256(dev / rel)}  {rel}")
    (dev / "checksums.sha256").write_text("\n".join(lines) + "\n", encoding="utf-8")


class CheckProvenanceTest(unittest.TestCase):

    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.dev = Path(self.tmp.name) / "dev25"
        write_fixture(self.dev)
        refresh_checksums(self.dev)

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def test_valid_fixture_has_no_errors(self) -> None:
        self.assertEqual([], cp.run(self.dev))

    def test_manifest_row_without_status_is_detected(self) -> None:
        with (self.dev / "manifest.tsv").open("a", encoding="utf-8") as handle:
            handle.write("g7-dev-026\tdoc-26.txt\tcategory\tVALIDATED_GOLD\n")
        refresh_checksums(self.dev)
        errors = cp.run(self.dev)
        self.assertTrue(any("identity mismatch" in error or "expected 25" in error
                            for error in errors), errors)

    def test_status_flip_is_detected(self) -> None:
        path = self.dev / "annotation-status.tsv"
        text = path.read_text(encoding="utf-8")
        path.write_text(text.replace(
            "g7-dev-001\tdoc-1.txt\tcategory\tVALIDATED_GOLD",
            "g7-dev-001\tdoc-1.txt\tcategory\tUNLABELED"), encoding="utf-8")
        refresh_checksums(self.dev)
        errors = cp.run(self.dev)
        self.assertTrue(any("status mismatch" in error for error in errors), errors)

    def test_readme_count_edit_is_detected(self) -> None:
        path = self.dev / "README.md"
        path.write_text(path.read_text(encoding="utf-8").replace(
            f"validated {TOTAL}/{TOTAL}", f"validated {TOTAL - 1}/{TOTAL}"),
            encoding="utf-8")
        refresh_checksums(self.dev)
        errors = cp.run(self.dev)
        self.assertTrue(any("README.md: validated count" in error for error in errors), errors)

    def test_checksum_drift_is_detected(self) -> None:
        with (self.dev / "manifest.tsv").open("a", encoding="utf-8") as handle:
            handle.write("\n")
        errors = cp.run(self.dev)
        self.assertTrue(any("checksum mismatch" in error for error in errors), errors)

    def test_pending_entry_for_validated_doc_is_detected(self) -> None:
        path = self.dev / "gold" / "PENDING.tsv"
        path.write_text(
            "doc_id\tdocument_id\tcategory\tstatus\n"
            "g7-dev-001\tdoc-1.txt\tcategory\tUNLABELED\n", encoding="utf-8")
        refresh_checksums(self.dev)
        errors = cp.run(self.dev)
        self.assertTrue(any("PENDING.tsv is not exactly" in error for error in errors), errors)


if __name__ == "__main__":
    unittest.main()
