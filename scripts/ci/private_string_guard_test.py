"""Mutation tests for private-string-guard.py: a planted address must fail, clean trees pass, and the
local run fails closed without a denylist. Runs against a temp git-free tree via explicit file lists.
Planted strings are invented (documentation-style), never real."""
import importlib.util
import os
import pathlib
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent.parent
spec = importlib.util.spec_from_file_location("guard", HERE / "private-string-guard.py")
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)

PATTERNS = str(REPO / guard.DEFAULT_PATTERNS)
ALLOW = str(REPO / guard.DEFAULT_ALLOW)
PLAN = "project_plans/home-project-sketching/note.md"


class GuardTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = self.tmp.name
        self.denylist = os.path.join(self.root, "denylist.txt")

    def write(self, rel, text):
        p = pathlib.Path(self.root, rel)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")

    def run_guard(self, *extra, files=(PLAN,)):
        return guard.main(["--root", self.root, "--patterns", PATTERNS, "--allow", ALLOW,
                           "--denylist", self.denylist, *extra, *files])

    def test_clean_file_passes(self):
        self.write(PLAN, "Closet width is `FC.opening`; see the [[page]] placeholder.\n")
        self.assertEqual(self.run_guard(), 0)

    def test_planted_numbered_street_address_fails(self):
        self.write(PLAN, "Lives at 1234 N 56th somewhere\n")
        self.assertEqual(self.run_guard(), 1)

    def test_planted_named_street_address_fails(self):
        self.write(PLAN, "Ship to 4321 Example Oak St today\n")
        self.assertEqual(self.run_guard(), 1)

    def test_planted_wiki_link_and_home_path_and_email_fail(self):
        for text in ("see [[Some Real Page]]\n", "/home/someone/notes\n", "ask a.b@example.org\n",
                     "Closet Plans Final.md\n"):
            with self.subTest(text=text):
                self.write(PLAN, text)
                self.assertEqual(self.run_guard(), 1)

    def test_uncovered_path_is_ignored(self):
        self.write("kmp/src/commonMain/Other.kt", "1234 N 56th\n")
        self.assertEqual(self.run_guard(files=("kmp/src/commonMain/Other.kt",)), 0)

    def test_denylist_phrase_fails_case_insensitively_without_echoing_it(self):
        self.write(self.denylist, "# header\nzorblax gardens\n")
        self.write(PLAN, "notes on Zorblax Gardens here\n")
        self.assertEqual(self.run_guard("--require-denylist"), 1)

    def test_missing_denylist_fails_closed_when_required(self):
        self.write(PLAN, "clean\n")
        self.assertEqual(self.run_guard("--require-denylist"), 2)

    def test_missing_denylist_ok_when_hook_stages_only_uncovered_files(self):
        self.write("README.md", "clean\n")
        self.assertEqual(self.run_guard("--require-denylist", files=("README.md",)), 0)

    def test_missing_denylist_ok_without_require_flag_as_in_ci(self):
        self.write(PLAN, "clean\n")
        self.assertEqual(self.run_guard(), 0)


if __name__ == "__main__":
    unittest.main()
