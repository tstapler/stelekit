#!/usr/bin/env python3
"""Tests for coverage-map.py. Run: python3 -m unittest scripts/ci/coverage-map_test.py -v"""
import contextlib
import importlib.util
import io
import json
import os
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("coverage_map", os.path.join(HERE, "coverage-map.py"))
cm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cm)

PKG_FIELDS = {"pkg", "sourceSet", "tier", "lines", "branches", "mutation", "commits90d", "reachablePct",
              "score", "rank", "ownerPlan", "target"}
TOP_FIELDS = {"generatedAt", "commit", "runner", "tiersFile", "packages"}


def sourcefile(name, line_missed, line_covered, branch_missed=0, branch_covered=0):
    return (f'<sourcefile name="{name}">'
            f'<counter type="LINE" missed="{line_missed}" covered="{line_covered}"/>'
            f'<counter type="BRANCH" missed="{branch_missed}" covered="{branch_covered}"/></sourcefile>')


def jacoco(*packages):
    body = "".join(f'<package name="dev/stapler/stelekit/{name}">{files}</package>' for name, files in packages)
    return f'<?xml version="1.0"?><report name="kmp">{body}</report>'


class CoverageMapTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)

    def write(self, name, text):
        path = os.path.join(self.dir.name, name)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        return path

    def run_map(self, xml, tiers, churn=None, extra=()):
        argv = ["--jacoco", self.write("jacoco.xml", xml), "--tiers", self.write("tiers.yml", tiers),
                "--out", os.path.join(self.dir.name, "out"), "--churn-json", self.write("churn.json", json.dumps(churn or {})),
                "--commit", "abc123", "--command", "./gradlew :kmp:jacocoTestReport", *extra]
        err = io.StringIO()
        with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
            code = cm.main(argv)
        return code, err.getvalue()

    def result(self):
        with open(os.path.join(self.dir.name, "out", "coverage-map.json"), encoding="utf-8") as f:
            return json.load(f)

    def test_coverageMap_should_ScoreUncoveredTimesTierWeightTimesLog2_When_T1With1647UncoveredAnd12Commits(self):
        xml = jacoco(("db", sourcefile("A.kt", 1647, 5000)), ("ui", sourcefile("B.kt", 1647, 5000)))
        code, err = self.run_map(xml, "db: T1\nui: T3\n", churn={"db": 12, "ui": 12})
        self.assertEqual(code, 0, err)
        rows = {p["pkg"]: p for p in self.result()["packages"]}
        self.assertAlmostEqual(rows["db"]["score"], 1647 * 4 * 3.8073549, delta=1.0)
        self.assertTrue(25081 <= rows["db"]["score"] <= 25084, rows["db"]["score"])
        self.assertLess(rows["ui"]["score"], rows["db"]["score"], "a T3 package with the same gap scores lower")
        self.assertEqual(rows["db"]["rank"], 1)

    def test_coverageMap_should_RefuseToMerge_When_XmlComesFromBazelRunner(self):
        code, err = self.run_map(jacoco(("db", sourcefile("A.kt", 1, 1))), "db: T1\n", extra=("--runner", "bazel"))
        self.assertEqual(code, 1)
        self.assertIn("not comparable", err)
        self.assertFalse(os.path.exists(os.path.join(self.dir.name, "out", "coverage-map.json")))

    def test_coverageMap_should_FailListingPackage_When_PackageHasNoTier(self):
        xml = jacoco(("db", sourcefile("A.kt", 1, 1)), ("newpkg", sourcefile("B.kt", 1, 1)), ("other", sourcefile("C.kt", 1, 1)))
        code, err = self.run_map(xml, "db: T1\n")
        self.assertEqual(code, 1)
        self.assertIn("newpkg", err)
        self.assertIn("other", err)
        self.assertNotIn("db,", err)

    def test_coverageMap_should_ExcludeUnreachableFromDenominator_When_AllClassesInReachabilityBaseline(self):
        xml = jacoco(("search", sourcefile("DatalogEngine.kt", 400, 0) + sourcefile("DatalogQuery.kt", 100, 0)),
                     ("db", sourcefile("Live.kt", 10, 90) + sourcefile("DatalogEngine.kt", 5, 0)))
        baseline = self.write("baseline.txt", "DatalogEngine\tkmp/src/commonMain/kotlin/dev/stapler/stelekit/search/DatalogEngine.kt\t18\n"
                                              "DatalogQuery\tkmp/src/commonMain/kotlin/dev/stapler/stelekit/search/DatalogQuery.kt\t18\n")
        code, err = self.run_map(xml, "search: T2\ndb: T1\n", extra=("--reachability-baseline", baseline))
        self.assertEqual(code, 0, err)
        rows = {p["pkg"]: p for p in self.result()["packages"]}
        self.assertEqual(rows["search"]["reachablePct"], 0.0)
        self.assertEqual(rows["search"]["lines"], {"covered": 0, "total": 0})
        self.assertEqual(rows["search"]["score"], 0.0, "deleting dead code must not score as a regression")
        self.assertEqual(rows["db"]["lines"], {"covered": 90, "total": 100})
        self.assertEqual(rows["db"]["reachablePct"], 50.0)

    def test_coverageMap_should_MatchSchemaGolden_When_FixtureJacocoXmlMerged(self):
        xml = jacoco(("db", sourcefile("A.kt", 10, 90, 20, 30)))
        second = self.write("android.xml", jacoco(("db", sourcefile("A.kt", 50, 50))))
        code, err = self.run_map(xml, "db: T1\n", churn={"db": 2}, extra=("--jacoco", second + ":android"))
        self.assertEqual(code, 0, err)
        data = self.result()
        self.assertEqual(set(data), TOP_FIELDS)
        self.assertEqual(data["runner"], "./gradlew :kmp:jacocoTestReport")
        self.assertEqual(data["commit"], "abc123")
        self.assertEqual(len(data["tiersFile"]), 64)
        rows = {p["sourceSet"]: p for p in data["packages"]}
        self.assertEqual(set(rows), {"jvm", "android"})
        for row in rows.values():
            self.assertEqual(set(row), PKG_FIELDS)
        self.assertEqual(rows["jvm"]["lines"], {"covered": 90, "total": 100})
        self.assertEqual(rows["jvm"]["branches"], {"covered": 30, "total": 50})
        self.assertEqual(rows["jvm"]["mutation"], {"killed": 0, "total": 0, "measured": False})
        self.assertEqual(rows["jvm"]["target"], {"line": 90, "branch": 80})
        with open(os.path.join(self.dir.name, "out", "coverage-map.md"), encoding="utf-8") as f:
            md = f.read()
        self.assertIn("| 1 | db |", md)

    def test_coverageMap_should_RankByScoreDescending(self):
        xml = jacoco(("small", sourcefile("A.kt", 10, 90)), ("big", sourcefile("B.kt", 900, 100)), ("mid", sourcefile("C.kt", 100, 0)))
        code, _ = self.run_map(xml, "small: T2\nbig: T2\nmid: T2\n", churn={"small": 1, "big": 1, "mid": 1})
        self.assertEqual(code, 0)
        self.assertEqual([p["pkg"] for p in self.result()["packages"]], ["big", "mid", "small"])


if __name__ == "__main__":
    unittest.main()
