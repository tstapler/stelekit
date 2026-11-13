#!/usr/bin/env python3
"""Run: python3 scripts/ci/release_proof_test.py"""
import importlib.util
import os
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("release_proof", os.path.join(HERE, "release-proof.py"))
rp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rp)

BOT = ["version.txt", "CHANGELOG.md", ".release-please-manifest.json"]
CODE = ["kmp/src/commonMain/Foo.kt"]


def history(commits, green):
    """commits: sha -> (files, parent); green: set of (sha, workflow)."""
    return (lambda repo, sha: {"files": commits[sha][0], "parent": commits[sha][1]},
            lambda repo, sha, wf: 42 if (sha, wf) in green else None)


class ReleaseProofTest(unittest.TestCase):
    chain = {"rel": (BOT, "bench"), "bench": (["benchmarks/history/x.json"], "code"),
             "code": (CODE, "older"), "older": (CODE, None)}

    def test_walksPastBotCommits_and_acceptsBazelCi(self):
        cf, rf = history(self.chain, {("code", "bazel-ci.yml")})
        self.assertEqual(rp.find_proof("r", "rel", cf, rf)[:2], ("code", "bazel-ci.yml"))

    def test_acceptsCiYml(self):
        cf, rf = history(self.chain, {("code", "ci.yml")})
        self.assertEqual(rp.find_proof("r", "rel", cf, rf)[:2], ("code", "ci.yml"))

    def test_noProof_when_codeCommitNotGreen(self):
        cf, rf = history(self.chain, {("older", "ci.yml")})  # must not skip past a code commit
        self.assertIsNone(rp.find_proof("r", "rel", cf, rf)[0])

    def test_botCommitGreenRunIsIgnored(self):
        cf, rf = history(self.chain, {("bench", "ci.yml"), ("rel", "ci.yml")})
        self.assertIsNone(rp.find_proof("r", "rel", cf, rf)[0])

    def test_mixedCommitIsCode(self):
        chain = {"rel": (BOT + CODE, "p"), "p": (CODE, None)}
        cf, rf = history(chain, {("p", "ci.yml")})
        self.assertIsNone(rp.find_proof("r", "rel", cf, rf)[0])

    def test_emptyOrHugeFileListIsNotBotOnly(self):
        self.assertFalse(rp.is_bot_only([]))
        self.assertFalse(rp.is_bot_only(["version.txt"] * rp.GH_FILE_LIMIT))

    def test_walkLimit(self):
        chain = {str(i): (BOT, str(i + 1)) for i in range(100)}
        cf, rf = history(chain, set())
        self.assertIsNone(rp.find_proof("r", "0", cf, rf)[0])


if __name__ == "__main__":
    unittest.main()
