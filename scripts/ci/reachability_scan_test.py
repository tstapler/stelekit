#!/usr/bin/env python3
"""Tests for reachability-scan.py. Run: python3 -m unittest scripts/ci/reachability_scan_test.py -v"""
import importlib.util
import io
import os
import tempfile
import unittest
from contextlib import redirect_stdout

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
BASELINE = os.path.join(HERE, "reachability-baseline.txt")

spec = importlib.util.spec_from_file_location("reachability_scan", os.path.join(HERE, "reachability-scan.py"))
scan_mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scan_mod)

# Types the scan finds at baseline time that the plan's Disposition Register does not name
# (plan assumption "the register accounts for every line" does not hold). May only shrink.
UNREGISTERED_AT_BASELINE = {
    "AppStateOptics", "AssetPendingMove", "BenchmarkRegistry", "BlockOptics", "ChunkChecksum", "CursorState",
    "DesktopFileSystem", "ExplanationContent", "ExplanationDoc", "GitObjectContentVerifier", "GraphPreset",
    "IOSKableBleScanner", "IosMediaAttachmentService", "LfsPointerSniffer", "LidarHardwareCheck", "LruWeigher",
    "MeasureTemplateResolver", "NamespaceUtils", "PageOptics", "PropertiesParser", "PropertiesResult",
    "PropertyOptics", "TutorialContent", "TutorialDoc", "UrlFetcherIos",
}


def write(root, rel, text):
    path = os.path.join(root, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def run_cli(*argv):
    buf = io.StringIO()
    with redirect_stdout(buf):
        code = scan_mod.main(list(argv))
    return code, buf.getvalue()


class ReachabilityScanTest(unittest.TestCase):
    def test_reachabilityScan_should_ListKnownDormantAndExcludeFalsePositives_When_RunAtHead(self):
        candidates, skipped = scan_mod.scan(REPO)
        names = {n for n, _ in candidates}
        for known in ("AssetIndexService", "RTCManager", "DatalogEngine", "CloudClaudePlugin", "DesktopSyncScheduler"):
            self.assertIn(known, names)
        for false_positive in ("InitializingDebugScreen", "OtelSpanElement"):
            self.assertNotIn(false_positive, names)
        sources, resources = scan_mod.production_files(REPO)
        manifest = scan_mod.manifest_component_names(REPO, resources)
        self.assertFalse(names & manifest, "manifest components must be filtered")
        self.assertFalse([n for n in names if n.endswith("UiState")], "*UiState types must be filtered")
        self.assertFalse([r for _, r in candidates if "/generated/" in r], "generated sources must not be scanned")
        self.assertTrue({"BenchmarkResult", "ScenarioDescriptor"} <= set(skipped), "@Serializable DTOs filtered")
        self.assertFalse({"BenchmarkResult", "ScenarioDescriptor"} & names)

    def test_reachabilityScan_should_FailListingType_When_NewOrphanClassAdded(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "kmp/src/commonMain/kotlin/a/Live.kt", "class Live\nfun use() = Live()\n")
            write(root, "kmp/src/commonMain/kotlin/a/Caller.kt", "fun call() { val x: Live? = null }\n")
            baseline = os.path.join(root, "baseline.txt")
            write(root, "baseline.txt", "")
            code, _ = run_cli("--root", root, "--check-baseline", baseline)
            self.assertEqual(code, 0)
            write(root, "kmp/src/commonMain/kotlin/a/Orphan.kt", "class OrphanThing\n")
            code, out = run_cli("--root", root, "--check-baseline", baseline)
            self.assertEqual(code, 1)
            self.assertIn("OrphanThing", out)

    def test_reachabilityScan_should_PassAndPrintBaselineCanShrink_When_RTCManagerDeleted(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "kmp/src/commonMain/kotlin/a/Other.kt", "fun other() = 1\n")
            baseline = os.path.join(root, "baseline.txt")
            write(root, "baseline.txt", "RTCManager\tkmp/src/commonMain/kotlin/rtc/RTCManager.kt\t16\n")
            code, out = run_cli("--root", root, "--check-baseline", baseline)
            self.assertEqual(code, 0)
            self.assertIn("baseline can shrink: RTCManager", out)

    def test_reachabilityScan_should_NotListTypeUsedByLiveSiblingOrTopLevelFunction(self):
        with tempfile.TemporaryDirectory() as root:
            write(root, "kmp/src/commonMain/kotlin/a/Pair.kt",
                  "class Used\nclass Helper\nclass Holder { val h: Helper = Helper() }\nfun make() = Used()\n")
            write(root, "kmp/src/commonMain/kotlin/a/Other.kt", "fun f(h: Holder) {}\n")
            candidates, _ = scan_mod.scan(root)
            self.assertEqual([], [n for n, _ in candidates])

    def test_reachabilityBaseline_should_AccountForEveryLineInDispositionRegister_When_ScriptCheckRuns(self):
        self.assertTrue(os.path.isfile(BASELINE), "baseline must be committed")
        unregistered = set()
        with open(BASELINE, encoding="utf-8") as f:
            lines = [ln.rstrip("\n") for ln in f if ln.strip() and not ln.startswith("#")]
        self.assertTrue(lines)
        for ln in lines:
            fields = ln.split("\t")
            self.assertEqual(3, len(fields), f"TypeName<TAB>file<TAB>row expected: {ln!r}")
            name, rel, row = fields
            self.assertTrue(os.path.isfile(os.path.join(REPO, rel)), f"{rel} does not exist")
            if row == "-":
                unregistered.add(name)
                continue
            self.assertTrue(1 <= int(row) <= scan_mod.REGISTER_ROW_COUNT, f"row {row} not in register")
            self.assertEqual(int(row), scan_mod.register_row(name, rel))
        self.assertTrue(unregistered <= UNREGISTERED_AT_BASELINE,
                        f"new type missing from the register: {sorted(unregistered - UNREGISTERED_AT_BASELINE)}")

    def test_reachabilityBaseline_should_MatchScanAtHead(self):
        code, out = run_cli("--root", REPO, "--check-baseline", BASELINE)
        self.assertEqual(code, 0, out)


if __name__ == "__main__":
    unittest.main()
