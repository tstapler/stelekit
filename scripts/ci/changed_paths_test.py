import importlib.util
import os
import unittest

_spec = importlib.util.spec_from_file_location(
    "changed_paths", os.path.join(os.path.dirname(__file__), "changed-paths.py"))
cp = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cp)


class AnyCodeTest(unittest.TestCase):
    def test_release_pr_is_not_code(self):
        self.assertFalse(cp.any_code(["version.txt", "CHANGELOG.md", ".release-please-manifest.json"]))

    def test_bench_bot_commit_is_not_code(self):
        self.assertFalse(cp.any_code(["benchmarks/history/x.json", "benchmarks/summary.md"]))

    def test_one_source_file_makes_it_code(self):
        self.assertTrue(cp.any_code(["CHANGELOG.md", "kmp/src/commonMain/kotlin/A.kt"]))

    def test_docs_and_plans_are_code_because_tests_bundle_them(self):
        self.assertTrue(cp.any_code(["docs/journeys/x.md"]))
        self.assertTrue(cp.any_code(["project_plans/p/implementation/plan.md"]))

    def test_workflow_and_build_files_are_code(self):
        self.assertTrue(cp.any_code([".github/workflows/ci.yml"]))
        self.assertTrue(cp.any_code(["MODULE.bazel"]))

    def test_empty_diff_fails_open(self):
        self.assertTrue(cp.any_code([]))

    def test_prefix_does_not_match_lookalike(self):
        self.assertTrue(cp.is_code("benchmarks-tool/run.py"))


if __name__ == "__main__":
    unittest.main()
