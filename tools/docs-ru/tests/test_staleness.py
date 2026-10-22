from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
CHECKER = REPOSITORY_ROOT / "tools/docs-ru/check_staleness.py"


class StalenessCheckTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source = self.root / "docs/architecture/design.md"
        self.target = self.root / "docs-ru/architecture/design.md"
        self.source.parent.mkdir(parents=True)
        self.target.parent.mkdir(parents=True)
        self._git("init", "--quiet", "--initial-branch=main")
        self._git("config", "user.name", "Docs Test")
        self._git("config", "user.email", "docs-test@example.invalid")
        self.source.write_text("# Design\n\nEnglish source v1.\n", encoding="utf-8")
        self._git("add", "docs/architecture/design.md")
        self._git("commit", "--quiet", "-m", "source v1")
        self.pin = self._git("rev-parse", "HEAD").stdout.strip()
        self.target.write_text(
            f"---\ntranslated-from: {self.pin} @ 2026-10-22\n---\n\n# Дизайн\n",
            encoding="utf-8",
        )
        self._git("add", "docs-ru/architecture/design.md")
        self._git("commit", "--quiet", "-m", "Russian mirror")

    def tearDown(self) -> None:
        self.temp.cleanup()

    def _git(self, *args: str) -> subprocess.CompletedProcess[str]:
        result = subprocess.run(
            ["git", "-C", str(self.root), *args],
            check=False,
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            self.fail(result.stderr)
        return result

    def _check(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(CHECKER), "--root", str(self.root)],
            check=False,
            capture_output=True,
            text=True,
        )

    def test_unchanged_source_is_clean(self) -> None:
        result = self._check()

        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn("0 stale warning(s), 0 error(s)", result.stdout)

    def test_changed_source_emits_warning_without_failing(self) -> None:
        self.source.write_text("# Design\n\nEnglish source v2.\n", encoding="utf-8")
        self._git("add", "docs/architecture/design.md")
        self._git("commit", "--quiet", "-m", "source v2")

        result = self._check()

        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn("::warning file=docs-ru/architecture/design.md::", result.stdout)
        self.assertIn("English source docs/architecture/design.md changed since pin", result.stdout)

    def test_architecture_mirror_requires_a_pin(self) -> None:
        self.target.write_text("# Дизайн\n", encoding="utf-8")

        result = self._check()

        self.assertEqual(1, result.returncode)
        self.assertIn("missing translated-from pin", result.stdout)

    def test_source_path_must_stay_under_docs(self) -> None:
        self.target.write_text(
            f"---\ntranslated-from: {self.pin} @ 2026-10-22\nsource: ../outside.md\n---\n",
            encoding="utf-8",
        )

        result = self._check()

        self.assertEqual(1, result.returncode)
        self.assertIn("under docs/", result.stdout)


if __name__ == "__main__":
    unittest.main()
