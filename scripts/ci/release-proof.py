#!/usr/bin/env python3
"""Decide whether release.yml's safety gate may skip its JVM/Android test run.

Walks first-parent history from the release commit past bot-only commits (version
bumps, benchmark summaries, formula bumps) to the nearest code commit, then asks
GitHub for a green ci.yml or bazel-ci.yml run on exactly that commit. A workflow
run is green only if every job in it passed, so "all required checks" is implied.

Usage: release-proof.py --repo OWNER/REPO --sha SHA
Prints key=value lines (proof=true|false, proof_commit, proof_run, reason).
Exit 0 always on a clean decision; absence of proof is not an error.
"""
import argparse
import json
import subprocess
import sys

BOT_EXACT = {
    "version.txt",
    "CHANGELOG.md",
    ".release-please-manifest.json",
    "Formula/stelekit.rb",
    "Casks/stelekit.rb",
}
BOT_PREFIXES = ("benchmarks/",)
PROOF_WORKFLOWS = ("ci.yml", "bazel-ci.yml")
MAX_WALK = 25
GH_FILE_LIMIT = 300  # commits API truncates the file list here; treat as non-bot


def is_bot_path(path):
    return path in BOT_EXACT or path.startswith(BOT_PREFIXES)


def is_bot_only(files):
    return 0 < len(files) < GH_FILE_LIMIT and all(is_bot_path(p) for p in files)


def gh_json(args):
    out = subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout
    return json.loads(out) if out.strip() else None


def fetch_commit(repo, sha):
    c = gh_json(["api", f"repos/{repo}/commits/{sha}"])
    parents = [p["sha"] for p in c.get("parents", [])]
    return {"files": [f["filename"] for f in c.get("files", [])], "parent": parents[0] if parents else None}


def green_run(repo, sha, workflow):
    runs = gh_json(["run", "list", "--repo", repo, "--workflow", workflow, "--commit", sha,
                    "--status", "success", "--limit", "1", "--json", "databaseId,conclusion"]) or []
    return runs[0]["databaseId"] if runs else None


def find_proof(repo, sha, commit_fn=fetch_commit, run_fn=green_run):
    """Return (proof_commit, workflow, run_id, reason); proof_commit is None when absent."""
    cur = sha
    for _ in range(MAX_WALK):
        c = commit_fn(repo, cur)
        if is_bot_only(c["files"]):
            if not c["parent"]:
                return None, None, None, "history ended while walking past bot commits"
            cur = c["parent"]
            continue
        for wf in PROOF_WORKFLOWS:
            run_id = run_fn(repo, cur, wf)
            if run_id:
                return cur, wf, run_id, f"green {wf} run {run_id} on code commit {cur}"
        return None, None, None, f"no green {' or '.join(PROOF_WORKFLOWS)} run on code commit {cur}"
    return None, None, None, f"more than {MAX_WALK} bot-only commits before a code commit"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--sha", required=True)
    a = ap.parse_args()
    try:
        commit, wf, run_id, reason = find_proof(a.repo, a.sha)
    except (subprocess.CalledProcessError, KeyError, ValueError) as e:
        # Any lookup failure means no proof, so the gate runs the full suite.
        commit, wf, run_id, reason = None, None, None, f"lookup failed: {e}"
    print(f"proof={'true' if commit else 'false'}")
    print(f"proof_commit={commit or ''}")
    print(f"proof_run={run_id or ''}")
    print(f"reason={reason}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
