#!/usr/bin/env python3
"""Coverage map generator (plan Story 11.1): merges Gradle JaCoCo XML reports by package.

Usage:
  coverage-map.py --jacoco jacoco.xml[:sourceSet] [--jacoco jacoco-android.xml:android ...]
                  --tiers coverage-tiers.yml --out docs/coverage
                  [--reachability-baseline scripts/ci/reachability-baseline.txt]
                  [--mutations mutations.xml] [--churn-json FILE] [--runner gradle]
                  [--repo-root DIR] [--commit SHA] [--command "…"]

Writes <out>/coverage-map.json and <out>/coverage-map.md (sorted by rank, 1 = biggest gap).
Package = the first path segment under dev/stapler/stelekit. See docs/coverage/SCHEMA.md.

Refuses an XML from the Bazel runner: its numbers are not comparable with Gradle's (pitfalls E6).
Fails listing every package that has no tier in the tiers file.
"""
import argparse
import hashlib
import json
import math
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

ROOT_PKG = "dev/stapler/stelekit"
TIER_WEIGHT = {"T1": 4, "T2": 3, "T3": 1, "T4": 0}
# Target line/branch percentage per tier; T4 is excluded from the gap ranking.
TIER_TARGET = {"T1": (90, 80), "T2": (80, 70), "T3": (60, 40), "T4": (0, 0)}
CHURN_WINDOW_DAYS = 90


class MapError(Exception):
    pass


def package_of(jacoco_package_name):
    """dev/stapler/stelekit/ui/components -> 'ui'; the root package itself -> '(root)'."""
    if not jacoco_package_name.startswith(ROOT_PKG):
        return None
    rest = jacoco_package_name[len(ROOT_PKG):].strip("/")
    return rest.split("/")[0] if rest else "(root)"


def counter(el, kind):
    for c in el.findall("counter"):
        if c.get("type") == kind:
            return int(c.get("covered")), int(c.get("missed"))
    return 0, 0


def check_runner(runner, path):
    if runner != "gradle" or "bazel-" in path.replace(os.sep, "/").split("/")[-1] or "/bazel-out/" in path:
        raise MapError(f"refusing to merge {path}: runner '{runner}' is not Gradle; Bazel and Gradle "
                       f"coverage numbers are not comparable (pitfalls E6)")


def unreachable_basenames(baseline_path):
    """File basenames (no extension) that are named for a type in the reachability baseline."""
    if not baseline_path:
        return set()
    names = set()
    with open(baseline_path, encoding="utf-8") as f:
        for ln in f:
            parts = ln.rstrip("\n").split("\t")
            if len(parts) >= 2 and not ln.startswith("#"):
                names.add(os.path.splitext(os.path.basename(parts[1]))[0])
    return names


def read_jacoco(path, source_set, unreachable, acc):
    root = ET.parse(path).getroot()
    for pkg in root.iter("package"):
        key = package_of(pkg.get("name", ""))
        if key is None:
            continue
        entry = acc.setdefault((key, source_set), {"lines": [0, 0], "branches": [0, 0], "unreachable": [0, 0], "files": 0, "unreachableFiles": 0})
        for sf in pkg.findall("sourcefile"):
            lc, lm = counter(sf, "LINE")
            bc, bm = counter(sf, "BRANCH")
            entry["files"] += 1
            if os.path.splitext(sf.get("name", ""))[0] in unreachable:
                entry["unreachableFiles"] += 1
                entry["unreachable"][0] += lc
                entry["unreachable"][1] += lm
                continue
            entry["lines"][0] += lc
            entry["lines"][1] += lc + lm
            entry["branches"][0] += bc
            entry["branches"][1] += bc + bm


def read_tiers(path):
    """Minimal reader for `package: T1|T2|T3|T4` lines (a flat YAML mapping with optional `# comments`)."""
    tiers = {}
    with open(path, encoding="utf-8") as f:
        for ln in f:
            ln = ln.split("#", 1)[0].strip()
            if not ln:
                continue
            m = re.fullmatch(r"([\w()./-]+)\s*:\s*(T[1-4])", ln)
            if not m:
                raise MapError(f"{path}: cannot parse tier line {ln!r}")
            tiers[m.group(1)] = m.group(2)
    return tiers


def read_mutations(path):
    """PIT mutations.xml -> {package: (killed, total)}; empty without a file."""
    if not path:
        return {}
    result = {}
    for m in ET.parse(path).getroot().iter("mutation"):
        cls = (m.findtext("mutatedClass") or "").replace(".", "/")
        key = package_of(cls.rsplit("/", 1)[0]) if "/" in cls else None
        if key is None:
            continue
        killed, total = result.get(key, (0, 0))
        result[key] = (killed + (m.get("status") == "KILLED"), total + 1)
    return result


def churn_from_git(repo_root):
    """Commits in the last 90 days touching each package (any source set)."""
    out = subprocess.run(
        ["git", "log", f"--since={CHURN_WINDOW_DAYS}.days.ago", "--name-only", "--pretty=format:@@%H", "--", "kmp/src"],
        cwd=repo_root, capture_output=True, text=True, check=True).stdout
    counts, current = {}, set()
    pat = re.compile(r"/dev/stapler/stelekit/([^/]+)/")
    def flush():
        for p in current:
            counts[p] = counts.get(p, 0) + 1

    for line in out.splitlines():
        if line.startswith("@@"):
            flush()
            current = set()
        else:
            m = pat.search("/" + line)
            if m:
                current.add(m.group(1))
    flush()
    return counts


def build(args):
    for path, _set in args.jacoco:
        check_runner(args.runner, path)
    unreachable = unreachable_basenames(args.reachability_baseline)
    acc = {}
    for path, source_set in args.jacoco:
        read_jacoco(path, source_set, unreachable, acc)
    tiers = read_tiers(args.tiers)
    missing = sorted({k for k, _ in acc} - set(tiers))
    if missing:
        raise MapError("packages without a tier in " + args.tiers + ": " + ", ".join(missing))
    churn = json.load(open(args.churn_json)) if args.churn_json else churn_from_git(args.repo_root)
    mutations = read_mutations(args.mutations)
    owners = json.load(open(args.owner_plan_json)) if args.owner_plan_json else {}
    packages = []
    for (pkg, source_set), e in sorted(acc.items()):
        tier = tiers[pkg]
        lc, lt = e["lines"]
        bc, bt = e["branches"]
        killed, mtotal = mutations.get(pkg, (0, 0))
        commits = churn.get(pkg, 0)
        reachable_pct = round(100.0 * (e["files"] - e["unreachableFiles"]) / e["files"], 1) if e["files"] else 100.0
        score = (lt - lc) * TIER_WEIGHT[tier] * math.log2(2 + commits)
        tl, tb = TIER_TARGET[tier]
        packages.append({
            "pkg": pkg, "sourceSet": source_set, "tier": tier,
            "lines": {"covered": lc, "total": lt}, "branches": {"covered": bc, "total": bt},
            "mutation": {"killed": killed, "total": mtotal, "measured": mtotal > 0},
            "commits90d": commits, "reachablePct": reachable_pct, "score": round(score, 1),
            "rank": 0, "ownerPlan": owners.get(pkg, ""), "target": {"line": tl, "branch": tb},
        })
    for rank, p in enumerate(sorted(packages, key=lambda p: (-p["score"], p["pkg"], p["sourceSet"])), 1):
        p["rank"] = rank
    return {
        "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "commit": args.commit or git_head(args.repo_root),
        "runner": args.command or "gradle (command not recorded)",
        "tiersFile": hashlib.sha256(open(args.tiers, "rb").read()).hexdigest(),
        "packages": sorted(packages, key=lambda p: p["rank"]),
    }


def git_head(repo_root):
    try:
        return subprocess.run(["git", "rev-parse", "HEAD"], cwd=repo_root, capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


def pct(c, t):
    return f"{100.0 * c / t:.1f}%" if t else "n/a"


def render_md(data):
    lines = ["# Coverage map", "",
             f"Generated {data['generatedAt']} at `{data['commit']}`. Runner: `{data['runner']}`. Tiers file sha256: `{data['tiersFile'][:12]}`.",
             "", "| Rank | Package | Source set | Tier | Lines | Branches | Mutation | Commits 90d | Reachable | Score |",
             "|---|---|---|---|---|---|---|---|---|---|"]
    for p in data["packages"]:
        m = p["mutation"]
        mut = f"{m['killed']}/{m['total']}" if m["measured"] else "not measured"
        lines.append(f"| {p['rank']} | {p['pkg']} | {p['sourceSet']} | {p['tier']} | "
                     f"{p['lines']['covered']}/{p['lines']['total']} ({pct(p['lines']['covered'], p['lines']['total'])}) | "
                     f"{pct(p['branches']['covered'], p['branches']['total'])} | {mut} | {p['commits90d']} | "
                     f"{p['reachablePct']}% | {p['score']:.0f} |")
    return "\n".join(lines) + "\n"


def parse_args(argv):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--jacoco", action="append", required=True, help="PATH[:sourceSet] (default source set: jvm)")
    ap.add_argument("--tiers", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--reachability-baseline")
    ap.add_argument("--mutations")
    ap.add_argument("--churn-json")
    ap.add_argument("--owner-plan-json")
    ap.add_argument("--runner", default="gradle")
    ap.add_argument("--repo-root", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
    ap.add_argument("--commit")
    ap.add_argument("--command")
    args = ap.parse_args(argv)
    args.jacoco = [split_jacoco_arg(j) for j in args.jacoco]
    return args


def split_jacoco_arg(value):
    path, sep, source_set = value.rpartition(":")
    return (path, source_set) if sep and source_set.isalnum() and not os.path.exists(value) else (value, "jvm")


def main(argv=None):
    args = parse_args(argv)
    try:
        data = build(args)
    except MapError as e:
        print(f"coverage-map: {e}", file=sys.stderr)
        return 1
    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "coverage-map.json"), "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
        f.write("\n")
    with open(os.path.join(args.out, "coverage-map.md"), "w", encoding="utf-8") as f:
        f.write(render_md(data))
    print(f"wrote {len(data['packages'])} package rows to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
