from __future__ import annotations

import json
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
TOOL = REPOSITORY_ROOT / "tools/docs-ru/translation_tool.py"
FIXTURES = Path(__file__).resolve().parent / "fixtures"
SOURCE = REPOSITORY_ROOT / "docs/api/error-model.md"
DESIGN_132 = REPOSITORY_ROOT / "docs/architecture/synanton-design-1.32.md"
EXPECTED_CATEGORIES = (
    "VALIDATION_ERROR",
    "AUTHENTICATION_REQUIRED",
    "AUTHORIZATION_DENIED",
    "RESOURCE_NOT_FOUND",
    "RESOURCE_CONFLICT",
    "PRECONDITION_FAILED",
    "RATE_LIMITED",
    "DEPENDENCY_UNAVAILABLE",
    "OPERATION_FAILED",
    "INTERNAL_ERROR",
)
WAVE2_RETRY_CATEGORIES = (
    "TRANSIENT",
    "PERMANENT",
    "SECURITY",
    "INVALID",
    "DEPENDENCY",
    "TIMEOUT",
    "RESOURCE",
)
WAVE2_SECURITY_IDENTIFIERS = (
    "acl_grants",
    "resource_acl",
    "class_grants",
    "classification[]",
    "store_original",
    "fail_closed",
    "AclInjector",
    "ClassificationDetector",
    "ReindexAfterPolicyChangeWorkflow",
    "content_original",
    "content_masked",
    "embedding_original",
    "embedding_masked",
    "acl_mask",
    "representation=original",
    "representation=masked",
    "HIGH_SECURITY",
    "STANDARD",
    "RESTRICTED",
    "PERSONAL",
    "FINANCIAL",
    "PUBLIC",
    "USER",
    "GROUP",
    "ROLE",
    "QUARANTINED",
    "PENDING_REVIEW",
    "SINGLE",
    "DUAL",
    "MASKED‑ONLY",
    "Single",
    "Dual",
    "Masked-only",
    "Masked‑only",
    "MASK",
    "DROP",
    "QUARANTINE",
    "REDACTED:CLASS",
    "SSN",
    "PII",
    "security_officer",
)
WAVE2_SECURITY_CONCEPTS = (
    "authentication",
    "authorization",
    "classification",
    "masking",
    "fail-closed",
    "fail‑closed",
    "representation",
    "least privilege",
    "resource ACL",
    "redaction",
    "quarantine",
    "tenant scope",
    "unlabelled content",
    "security context",
    "classification-aware search",
)


class TranslationToolTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / "docs/api").mkdir(parents=True)
        (self.root / "docs/architecture").mkdir(parents=True)
        (self.root / "docs-ru/api").mkdir(parents=True)
        (self.root / "tools/docs-ru/tests/fixtures").mkdir(parents=True)
        shutil.copyfile(SOURCE, self.root / "docs/api/error-model.md")
        shutil.copyfile(DESIGN_132, self.root / "docs/architecture/synanton-design-1.32.md")
        shutil.copyfile(FIXTURES / "error-model.ru.md", self.root / "docs-ru/api/error-model.md")
        shutil.copyfile(FIXTURES / "glossary.md", self.root / "docs-ru/GLOSSARY.md")
        shutil.copyfile(FIXTURES / "glossary.md", self.root / "tools/docs-ru/tests/fixtures/glossary.md")

    def tearDown(self) -> None:
        self.temp.cleanup()

    def run_tool(
        self,
        command: str,
        target: str = "docs-ru/api/error-model.md",
        source: str = "docs/api/error-model.md",
        sections: str | None = None,
    ) -> subprocess.CompletedProcess[str]:
        args = [
            sys.executable,
            str(TOOL),
            command,
            "--root",
            str(self.root),
            "--source",
            source,
            "--target",
            target,
            "--glossary",
            "docs-ru/GLOSSARY.md",
        ]
        if sections is not None:
            args.extend(["--sections", sections])
        return subprocess.run(
            args,
            check=False,
            capture_output=True,
            text=True,
        )

    def test_error_model_translation_preserves_all_ten_categories(self) -> None:
        result = self.run_tool("validate")
        repeated_result = self.run_tool("validate")
        report = json.loads(result.stdout)

        self.assertEqual(0, result.returncode, result.stdout)
        self.assertEqual(result.stdout, repeated_result.stdout)
        self.assertTrue(report["valid"])
        target = (self.root / "docs-ru/api/error-model.md").read_text(encoding="utf-8")
        for category in EXPECTED_CATEGORIES:
            self.assertIn(f"`{category}`", target)

    def test_repository_glossary_is_accepted_when_present(self) -> None:
        project_glossary = REPOSITORY_ROOT / "docs-ru/GLOSSARY.md"
        if not project_glossary.is_file():
            self.skipTest("canonical glossary is added in the separate glossary PR")

        result = subprocess.run(
            [
                sys.executable,
                str(TOOL),
                "prepare",
                "--root",
                str(REPOSITORY_ROOT),
                "--source",
                "docs/api/error-model.md",
                "--target",
                "docs-ru/api/error-model.md",
                "--glossary",
                "docs-ru/GLOSSARY.md",
            ],
            check=False,
            capture_output=True,
            text=True,
        )

        self.assertEqual(0, result.returncode, result.stdout)
        packet = json.loads(result.stdout)
        terms = {entry["english"]: entry["do_not_translate"] for entry in packet["glossary"]}
        self.assertIn("VALIDATION_ERROR", terms)
        self.assertIn("INTERNAL_ERROR", terms)
        for category in WAVE2_RETRY_CATEGORIES:
            self.assertTrue(terms.get(category), f"{category} must be marked do-not-translate for Wave 2")
        for identifier in WAVE2_SECURITY_IDENTIFIERS:
            self.assertTrue(terms.get(identifier), f"{identifier} must be marked do-not-translate for Wave 2")
        entries = {entry["english"]: entry for entry in packet["glossary"]}
        for concept in WAVE2_SECURITY_CONCEPTS:
            self.assertIn(concept, entries)
            self.assertFalse(entries[concept]["do_not_translate"])
            self.assertTrue(entries[concept]["russian"])

    def test_prepare_packet_is_deterministic_and_reads_glossary(self) -> None:
        first = self.run_tool("prepare")
        second = self.run_tool("prepare")

        self.assertEqual(0, first.returncode, first.stdout)
        self.assertEqual(first.stdout, second.stdout)
        packet = json.loads(first.stdout)
        terms = {entry["english"] for entry in packet["glossary"]}
        self.assertIn("VALIDATION_ERROR", terms)
        self.assertEqual("docs-ru/api/error-model.md", packet["target_path"])
        self.assertEqual("0.1.0", packet["tool_version"])

        glossary = self.root / "docs-ru/GLOSSARY.md"
        glossary.write_text(
            glossary.read_text(encoding="utf-8").replace(
                "| `Operation` | `Operation` | yes |", "| `Operation` | Операция | yes |"
            ),
            encoding="utf-8",
        )
        changed = self.run_tool("prepare")
        self.assertNotEqual(first.stdout, changed.stdout)

    def test_identifier_paraphrase_is_rejected_using_glossary_flag(self) -> None:
        target = self.root / "docs-ru/api/error-model.md"
        target.write_text(target.read_text(encoding="utf-8").replace("VALIDATION_ERROR", "INPUT_ERROR"), encoding="utf-8")

        result = self.run_tool("validate")
        report = json.loads(result.stdout)

        self.assertEqual(1, result.returncode)
        self.assertFalse(report["valid"])
        identifiers = next(check for check in report["checks"] if check["name"] == "untranslated_identifiers")
        self.assertIn("VALIDATION_ERROR", {mismatch["term"] for mismatch in identifiers["mismatches"]})

    def test_glossary_can_mark_a_term_translatable(self) -> None:
        glossary = self.root / "docs-ru/GLOSSARY.md"
        glossary.write_text(
            glossary.read_text(encoding="utf-8").replace(
                "| `VALIDATION_ERROR` | `VALIDATION_ERROR` | yes |",
                "| `VALIDATION_ERROR` | Ошибка валидации | no |",
            ),
            encoding="utf-8",
        )
        target = self.root / "docs-ru/api/error-model.md"
        target.write_text(target.read_text(encoding="utf-8").replace("VALIDATION_ERROR", "Ошибка валидации"), encoding="utf-8")

        result = self.run_tool("validate")
        report = json.loads(result.stdout)

        self.assertEqual(0, result.returncode, result.stdout)
        self.assertTrue(report["valid"])

    def test_fenced_code_is_preserved_verbatim(self) -> None:
        source = self.root / "docs/api/error-model.md"
        target = self.root / "docs-ru/api/error-model.md"
        source.write_text("# 55. Shape\n\n```json\n{\"code\": \"VALIDATION_ERROR\"}\n```\n", encoding="utf-8")
        target.write_text("# 55. Структура\n\n```json\n{\"code\": \"INPUT_ERROR\"}\n```\n", encoding="utf-8")

        result = self.run_tool("validate")
        report = json.loads(result.stdout)
        code_check = next(check for check in report["checks"] if check["name"] == "fenced_code_verbatim")

        self.assertFalse(code_check["passed"])

    def test_section_numbers_references_and_field_names_are_preserved(self) -> None:
        source = self.root / "docs/api/error-model.md"
        target = self.root / "docs-ru/api/error-model.md"
        source.write_text(
            "# 55. Operation\n\nSee §55 and §§56–57.\n\n**Status:** Approved\n**Basis:** Test\n\n## Decision\n\n## Context\n",
            encoding="utf-8",
        )
        target.write_text(
            "# 54. Операция\n\nСм. §54 и §§56–57.\n\n**Статус:** Одобрено\n**Основание:** Тест\n\n## Решение\n\n## Контекст\n",
            encoding="utf-8",
        )

        report = json.loads(self.run_tool("validate").stdout)
        checks = {check["name"]: check["passed"] for check in report["checks"]}

        self.assertFalse(checks["numbered_section_headings"])
        self.assertFalse(checks["section_references_and_link_anchors"])
        self.assertFalse(checks["field:**Status:**"])
        self.assertFalse(checks["field:**Basis:**"])
        self.assertFalse(checks["field:## Decision"])
        self.assertFalse(checks["field:## Context"])

    def test_target_must_be_under_docs_ru(self) -> None:
        result = self.run_tool("prepare", target="translations/error-model.ru.md")
        report = json.loads(result.stdout)

        self.assertEqual(2, result.returncode)
        self.assertIn("inside docs-ru/", report["error"])

    def test_glossary_must_be_the_docs_ru_canonical_glossary(self) -> None:
        result = subprocess.run(
            [
                sys.executable,
                str(TOOL),
                "prepare",
                "--root",
                str(self.root),
                "--source",
                "docs/api/error-model.md",
                "--target",
                "docs-ru/api/error-model.md",
                "--glossary",
                "tools/docs-ru/tests/fixtures/glossary.md",
            ],
            check=False,
            capture_output=True,
            text=True,
        )
        report = json.loads(result.stdout)

        self.assertEqual(2, result.returncode)
        self.assertIn("docs-ru/GLOSSARY.md", report["error"])

    def test_internal_link_anchor_must_be_preserved(self) -> None:
        source = self.root / "docs/api/error-model.md"
        target = self.root / "docs-ru/api/error-model.md"
        source.write_text("# 55. Operation\n\n[Resource](#operation-resource)\n", encoding="utf-8")
        target.write_text("# 55. Операция\n\n[Ресурс](#operatsiya-resource)\n", encoding="utf-8")

        report = json.loads(self.run_tool("validate").stdout)
        anchor_check = next(check for check in report["checks"] if check["name"] == "section_references_and_link_anchors")

        self.assertFalse(anchor_check["passed"])

    def test_numbered_section_range_prepares_and_validates_exact_pilot_sections(self) -> None:
        source = "docs/architecture/synanton-design-1.32.md"
        target = "docs-ru/pilots/synanton-design-1.32-sections-55-62.md"
        (self.root / "docs-ru/pilots").mkdir()

        prepared = self.run_tool("prepare", target=target, source=source, sections="55-62")
        self.assertEqual(0, prepared.returncode, prepared.stdout)
        packet = json.loads(prepared.stdout)
        self.assertEqual("55-62", packet["section_range"])
        self.assertTrue(packet["source"].startswith("# 55. Operation Resource"))
        self.assertIn("# 62. Error Categories", packet["source"])
        self.assertNotIn("# 54. ", packet["source"])
        self.assertNotIn("# 63. ", packet["source"])
        self.assertEqual([str(number) for number in range(55, 63)], re.findall(r"(?m)^# (\d+)\.", packet["source"]))

        target_path = self.root / target
        target_path.write_text(packet["source"], encoding="utf-8")
        validated = self.run_tool("validate", target=target, source=source, sections="55-62")
        self.assertEqual(0, validated.returncode, validated.stdout)
        self.assertTrue(json.loads(validated.stdout)["valid"])

    def test_public_operation_model_fixture_keeps_full_and_partial_distinct(self) -> None:
        operation_model = (REPOSITORY_ROOT / "docs/api/operation-model.md").read_text(encoding="utf-8")
        start = operation_model.index("2. **`PARTIAL` (extraction) has no public state.")
        end = operation_model.index("\n\n3. **`CANCEL_REQUESTED`", start)
        source_excerpt = operation_model[start:end]
        source_path = self.root / "docs/api/error-model.md"
        source_path.write_text(source_excerpt, encoding="utf-8")

        target_path = self.root / "docs-ru/pilots/operation-outcome-test.md"
        target_path.parent.mkdir(parents=True)
        target_path.write_text(
            (FIXTURES / "operation-outcome-public-model.ru.md").read_text(encoding="utf-8"),
            encoding="utf-8",
        )
        result = self.run_tool("validate", target="docs-ru/pilots/operation-outcome-test.md")
        report = json.loads(result.stdout)
        translated = target_path.read_text(encoding="utf-8")
        partial_line = next(line for line in translated.splitlines() if "outcome: PARTIAL" in line)
        full_line = next(line for line in translated.splitlines() if "outcome: FULL" in line)

        self.assertEqual(0, result.returncode, result.stdout)
        self.assertTrue(report["valid"])
        self.assertIn("частич", partial_line.casefold())
        self.assertIn("полным успехом", full_line.casefold())
        self.assertIn("`PARTIAL` (извлечение) не является публичным состоянием", translated)

    def test_section_range_rejects_invalid_input(self) -> None:
        result = self.run_tool("prepare", source="docs/architecture/synanton-design-1.32.md", sections="62-55")

        self.assertEqual(2, result.returncode)
        self.assertIn("positive and ascending", json.loads(result.stdout)["error"])


if __name__ == "__main__":
    unittest.main()
