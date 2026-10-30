#!/usr/bin/env python3
"""Asserts on coverage-baseline.yml. Run: python3 -m unittest scripts/ci/coverage_baseline_workflow_test.py -v"""
import os
import re
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
WORKFLOW = os.path.join(HERE, "..", "..", ".github", "workflows", "coverage-baseline.yml")


def workflow_text():
    with open(WORKFLOW, encoding="utf-8") as f:
        return f.read()


class CoverageBaselineWorkflowTest(unittest.TestCase):
    def test_coverageBaselineWorkflow_should_NotContainPullRequestDraftCondition_When_Parsed(self):
        text = workflow_text()
        self.assertNotIn("pull_request.draft", text)
        self.assertIsNone(re.search(r"^\s*if:.*draft", text, re.M), "no job-level if: may mention draft")
        self.assertIsNone(re.search(r"^\s*pull_request\b", text, re.M), "baseline must not trigger on pull_request")

    def test_coverageBaselineWorkflow_should_UploadJacocoXmlsAndRuntimeJson_When_Parsed(self):
        text = workflow_text()
        self.assertIn("workflow_dispatch:", text)
        self.assertIn("schedule:", text)
        for artifact in ("jacoco.xml", "jacoco-android.xml", "runtime.json"):
            self.assertIn(f"coverage-baseline-out/{artifact}", text)


if __name__ == "__main__":
    unittest.main()
