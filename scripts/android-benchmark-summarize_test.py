"""Run: python3 -m unittest scripts/android-benchmark-summarize_test.py  (or: python3 scripts/android-benchmark-summarize_test.py)"""

import datetime
import importlib.util
import json
import os
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("summarize", os.path.join(HERE, "android-benchmark-summarize.py"))
summarize = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summarize)

NOW = datetime.datetime(2026, 11, 9, 12, 30, 5, tzinfo=datetime.timezone.utc)

LOGCAT = [
    '11-09 12:00:00.000 I/ANDROID_BENCH( 1): ANDROID_BENCH: {"metric":"loadPhase","phase1Ms":120,"phase3Ms":900,"pageCount":230}',
    '11-09 12:00:01.000 I/ANDROID_BENCH( 1): ANDROID_BENCH: {"metric":"writeLatency","baselineP95Ms":4.5,"phase3P95Ms":9.0,"jankFactor":2.0,"writes":28}',
    '11-09 12:00:02.000 I/ANDROID_BENCH( 1): ANDROID_BENCH: {"metric":"safIo","directMs":10,"safMs":50,"safPerFileMs":5,"directPerFileMs":1,"overhead":5.0}',
    '11-09 12:00:03.000 I/ANDROID_BENCH( 1): not json {',
    '11-09 12:00:04.000 I/ANDROID_RESOURCE( 1): ANDROID_RESOURCE: {"scenario":"RapidPlacement","pid":4242,"scenarioStartMs":1000,'
    '"device":{"memoryClass":192,"largeHeap":false},"metrics":{"cpuMsPerPlacement":142,"loaderConstructions":1},'
    '"exitReasons":[{"reason":"USER_REQUESTED","pid":4242,"timestamp":2000}]}',
    '11-09 12:00:05.000 I/ANDROID_RESOURCE( 1): ANDROID_RESOURCE: {"scenario":"LibraryScroll","metrics":{"maxDecodeBytes":1048576,"flag":true}}',
]


class SummarizeTest(unittest.TestCase):
    def test_bench_summary_keeps_the_previous_inline_shape(self):
        s = summarize.build_summary(summarize.parse_bench(LOGCAT), "abc1234", "main", NOW)
        self.assertEqual(
            list(s.keys()),
            ["timestamp", "gitSha", "branch", "phase1Ms", "phase3Ms", "pageCount", "baselineP95Ms",
             "phase3P95Ms", "jankFactor", "writes", "safDirectMs", "safProviderMs", "safPerFileMs",
             "safDirectPerFileMs", "safOverhead"])
        self.assertEqual(s["timestamp"], "2026-11-09 12:30:05 UTC")
        self.assertEqual((s["phase1Ms"], s["writes"], s["safProviderMs"], s["safOverhead"]), (120, 28, 50, 5.0))

    def test_missing_bench_lines_yield_nulls_not_errors(self):
        s = summarize.build_summary(summarize.parse_bench([]), "x", "y", NOW)
        self.assertIsNone(s["phase1Ms"])

    def test_resource_lines_flatten_to_scenario_metric_keys(self):
        r = summarize.parse_resource(LOGCAT, "abc1234", "main", NOW)
        self.assertEqual(r["RapidPlacement.cpuMsPerPlacement"], 142)
        self.assertEqual(r["RapidPlacement.loaderConstructions"], 1)
        self.assertEqual(r["LibraryScroll.maxDecodeBytes"], 1048576)
        self.assertNotIn("LibraryScroll.flag", r)  # booleans are not metrics
        self.assertEqual(r["pid"], 4242)
        self.assertEqual(r["device"]["memoryClass"], 192)
        self.assertEqual(len(r["exitReasons"]), 1)

    def test_no_resource_lines_yield_none(self):
        self.assertIsNone(summarize.parse_resource(LOGCAT[:3], "a", "b", NOW))

    def test_cli_writes_history_and_resource_files(self):
        with tempfile.TemporaryDirectory() as d:
            logcat = os.path.join(d, "logcat.txt")
            with open(logcat, "w") as f:
                f.write("\n".join(LOGCAT))
            rc = summarize.main(["--logcat", logcat, "--history-dir", os.path.join(d, "h"),
                                 "--resource-dir", os.path.join(d, "r"), "--sha", "abc1234",
                                 "--branch", "main", "--now", NOW.isoformat()])
            self.assertEqual(rc, 0)
            hist = os.path.join(d, "h", "2026-11-09_12h30m05s_abc1234.json")
            res = os.path.join(d, "r", "2026-11-09_12h30m05s_abc1234.json")
            self.assertEqual(json.load(open(hist))["phase3Ms"], 900)
            self.assertEqual(json.load(open(res))["RapidPlacement.cpuMsPerPlacement"], 142)

    def test_missing_logcat_still_writes_an_all_null_history_file(self):
        with tempfile.TemporaryDirectory() as d:
            summarize.main(["--logcat", os.path.join(d, "none.txt"), "--history-dir", d,
                            "--sha", "s", "--branch", "b", "--now", NOW.isoformat()])
            self.assertTrue(os.path.exists(os.path.join(d, "2026-11-09_12h30m05s_s.json")))


if __name__ == "__main__":
    unittest.main()
