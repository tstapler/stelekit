#!/usr/bin/env python3
"""Private-string guard: keeps addresses, household names and wiki page titles out of the public repo.

Two layers:
  * structural patterns (kmp/src/businessTest/resources/private-string-patterns.txt) - run everywhere, CI included;
  * a local denylist of case-insensitive phrases ($STELEKIT_PRIVATE_DENYLIST, default
    ~/.cache/stelekit-private/denylist.txt) - never in the repo, so CI cannot run it. With
    --require-denylist a missing or empty denylist is exit 2 (fail closed).

Findings print file, line and rule name only, never the matched text (CI logs of a public repo are public).
Exit: 0 clean, 1 findings, 2 configuration error (including a missing denylist under --require-denylist).
"""
import argparse
import os
import re
import subprocess
import sys

COVERED = [
    re.compile(r"^project_plans/home-project-sketching/"),
    re.compile(r"^kmp/src/.*/home-sketch/"),
    re.compile(r"^docs/"),
]
SELF_EXEMPT = {
    "kmp/src/businessTest/resources/private-string-patterns.txt",
    "kmp/src/businessTest/resources/private-string-allow.txt",
}
DEFAULT_PATTERNS = "kmp/src/businessTest/resources/private-string-patterns.txt"
DEFAULT_ALLOW = "kmp/src/businessTest/resources/private-string-allow.txt"
CI_NOTE = "titles unchecked in CI; the local denylist is the control"


def is_covered(path):
    return path not in SELF_EXEMPT and any(c.search(path) for c in COVERED)


def load_lines(path):
    with open(path, encoding="utf-8") as f:
        return [ln.rstrip("\n") for ln in f]


def load_patterns(path):
    out = []
    for ln in load_lines(path):
        if not ln.strip() or ln.lstrip().startswith("#"):
            continue
        name, sep, rx = ln.partition(" = ")
        if not sep:
            raise ValueError(f"bad pattern line (want 'name = regex'): {ln[:40]}")
        out.append((name.strip(), re.compile(rx.strip())))
    return out


def load_allow(path):
    """Exact tokens, plus `re:<regex>` lines that allow a match the regex fully matches."""
    if not os.path.isfile(path):
        return set(), []
    lines = [ln.strip() for ln in load_lines(path) if ln.strip() and not ln.lstrip().startswith("#")]
    exact = {ln for ln in lines if not ln.startswith("re:")}
    regexes = [re.compile(ln[3:]) for ln in lines if ln.startswith("re:")]
    return exact, regexes


def is_allowed(text, allow):
    exact, regexes = allow
    return text in exact or any(r.fullmatch(text) for r in regexes)


def load_denylist(path):
    if not path or not os.path.isfile(path):
        return None
    phrases = [ln.strip().lower() for ln in load_lines(path) if ln.strip() and not ln.lstrip().startswith("#")]
    return phrases or None


def git(root, *args):
    return subprocess.run(["git", "-C", root, *args], check=True, capture_output=True, text=True).stdout


def added_lines(root, diff_args):
    """Yield (path, lineno, text) for every added line of a `git diff -U0` over covered paths."""
    cur, ln = None, 0
    for row in git(root, "diff", "-U0", "--no-color", *diff_args).splitlines():
        if row.startswith("+++ "):
            cur = row[6:] if row.startswith("+++ b/") else None
        elif row.startswith("@@"):
            m = re.search(r"\+(\d+)", row)
            ln = int(m.group(1)) if m else 0
        elif row.startswith("+") and not row.startswith("+++"):
            if cur and is_covered(cur):
                yield cur, ln, row[1:]
            ln += 1


def lines_to_scan(root, files, diff_base, staged=False):
    """Yield (path, lineno, text): added lines when diff_base/staged is given, else whole files."""
    if staged:
        yield from added_lines(root, ["--cached"])
        return
    if diff_base:
        yield from added_lines(root, [f"{diff_base}...HEAD"])
        return
    paths = files if files else git(root, "ls-files").splitlines()
    for p in paths:
        if not is_covered(p):
            continue
        full = os.path.join(root, p)
        if not os.path.isfile(full):
            continue
        try:
            with open(full, encoding="utf-8") as f:
                for i, text in enumerate(f, 1):
                    yield p, i, text.rstrip("\n")
        except UnicodeDecodeError:
            continue


def scan(rows, patterns, allow, denylist):
    findings = []
    for path, lineno, text in rows:
        for name, rx in patterns:
            for m in rx.finditer(text):
                if not is_allowed(m.group(0), allow):
                    findings.append((path, lineno, name))
        if denylist:
            low = text.lower()
            for i, phrase in enumerate(denylist, 1):
                if phrase in low:
                    findings.append((path, lineno, f"denylist entry #{i}"))
    return findings


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("files", nargs="*", help="repo-relative files to check (default: every tracked covered file)")
    ap.add_argument("--root", default=".")
    ap.add_argument("--patterns")
    ap.add_argument("--allow")
    ap.add_argument("--denylist", default=os.environ.get(
        "STELEKIT_PRIVATE_DENYLIST", os.path.expanduser("~/.cache/stelekit-private/denylist.txt")))
    ap.add_argument("--require-denylist", action="store_true", help="exit 2 when the local denylist is missing or empty")
    ap.add_argument("--diff-base", help="scan only lines added since this ref (merge-base diff)")
    ap.add_argument("--staged", action="store_true", help="scan only lines added in the index (pre-commit hook)")
    args = ap.parse_args(argv)

    root = args.root
    try:
        patterns = load_patterns(args.patterns or os.path.join(root, DEFAULT_PATTERNS))
        allow = load_allow(args.allow or os.path.join(root, DEFAULT_ALLOW))
    except (OSError, ValueError, re.error) as e:
        print(f"private-string-guard: cannot load patterns: {e}", file=sys.stderr)
        return 2
    denylist = load_denylist(args.denylist)
    names = git(root, "diff", "--cached", "--name-only").splitlines() if args.staged else args.files
    touches_covered = not names or any(is_covered(p) for p in names)
    if denylist is None:
        if args.require_denylist and touches_covered:
            print("private-string-guard: local denylist missing or empty; refusing (fail closed). "
                  "Generate it with scripts/home-sketch/build-denylist.py", file=sys.stderr)
            return 2
        print(f"private-string-guard: {CI_NOTE}", file=sys.stderr)

    findings = scan(lines_to_scan(root, args.files, args.diff_base, args.staged), patterns, allow, denylist)
    for path, lineno, rule in findings:
        print(f"{path}:{lineno}: private-string [{rule}]")
    if findings:
        print(f"private-string-guard: {len(findings)} finding(s)", file=sys.stderr)
        return 1
    print("private-string-guard: clean" + ("" if denylist else f" ({CI_NOTE})"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
