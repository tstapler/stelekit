#!/usr/bin/env python3
"""Decide whether a PR touches anything the heavy build/test jobs care about.

Prints `code=true|false` (also appended to $GITHUB_OUTPUT when set). Anything that is not a
bot-managed release/bench file or a top-level doc counts as code, so an unknown path always runs CI.
docs/ and project_plans/ are deliberately code: businessTest bundles files from both as resources.

Usage: changed-paths.py --base BASE_SHA   (non-PR events: pass nothing; always code=true)
"""
import argparse
import os
import subprocess

NON_CODE_EXACT = {
    "version.txt",
    "CHANGELOG.md",
    ".release-please-manifest.json",
    "README.md",
    "CLAUDE.md",
    "LICENSE",
}
NON_CODE_PREFIXES = ("benchmarks/",)


def is_code(path):
    return path not in NON_CODE_EXACT and not path.startswith(NON_CODE_PREFIXES)


def any_code(paths):
    # An empty diff is unknowable here (shallow clone, force-push); fail open.
    return not paths or any(is_code(p) for p in paths)


def changed_files(base):
    out = subprocess.run(["git", "diff", "--name-only", f"{base}...HEAD"],
                         check=True, capture_output=True, text=True).stdout
    return [line for line in out.splitlines() if line]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="")
    args = ap.parse_args()
    code = True if not args.base else any_code(changed_files(args.base))
    line = f"code={'true' if code else 'false'}"
    print(line)
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as f:
            f.write(line + "\n")


if __name__ == "__main__":
    main()
