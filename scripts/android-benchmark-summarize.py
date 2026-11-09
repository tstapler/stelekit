#!/usr/bin/env python3
"""Turns the logcat captured by android-benchmark.yml into benchmark summary JSON files.

  android-benchmark-summarize.py --logcat <file> --history-dir <dir> [--resource-dir <dir>]
                                 [--sha S] [--branch B] [--now ISO8601]

ANDROID_BENCH lines (AndroidGraphBenchmark) keep the exact summary shape the workflow used to build
inline. ANDROID_RESOURCE lines (ResourceProbe via ProbeReporter) become one flat summary of
`Scenario.metric` numbers plus `device`, `pid`, `scenarioStartMs` and `exitReasons`, which
scripts/bench-budget-check.js and `benchdiff-export.js --kind android-resource` consume.
Prints the written paths, one per line, history file first.
"""

import argparse
import datetime
import json
import os
import re
import subprocess
import sys

BENCH_RE = re.compile(r"ANDROID_BENCH:\s*(\{.*\})")
RESOURCE_RE = re.compile(r"ANDROID_RESOURCE:\s*(\{.*\})")


def _json_or_none(text):
    try:
        return json.loads(text)
    except ValueError:
        return None


def parse_bench(lines):
    results = {}
    for line in lines:
        m = BENCH_RE.search(line)
        obj = _json_or_none(m.group(1)) if m else None
        if isinstance(obj, dict):
            results[obj.get("metric", "unknown")] = obj
    return results


def build_summary(results, sha, branch, now):
    load = results.get("loadPhase", {})
    jank = results.get("writeLatency", {})
    saf = results.get("safIo", {})
    return {
        "timestamp": now.strftime("%Y-%m-%d %H:%M:%S UTC"),
        "gitSha": sha,
        "branch": branch,
        "phase1Ms": load.get("phase1Ms"),
        "phase3Ms": load.get("phase3Ms"),
        "pageCount": load.get("pageCount"),
        "baselineP95Ms": jank.get("baselineP95Ms"),
        "phase3P95Ms": jank.get("phase3P95Ms"),
        "jankFactor": jank.get("jankFactor"),
        "writes": jank.get("writes"),
        "safDirectMs": saf.get("directMs"),
        "safProviderMs": saf.get("safMs"),
        "safPerFileMs": saf.get("safPerFileMs"),
        "safDirectPerFileMs": saf.get("directPerFileMs"),
        "safOverhead": saf.get("overhead"),
    }


def parse_resource(lines, sha, branch, now):
    """Flattens ProbeRecord lines; returns None when the run emitted none."""
    summary = None
    for line in lines:
        m = RESOURCE_RE.search(line)
        obj = _json_or_none(m.group(1)) if m else None
        if not isinstance(obj, dict) or "scenario" not in obj:
            continue
        if summary is None:
            summary = {
                "timestamp": now.strftime("%Y-%m-%d %H:%M:%S UTC"),
                "gitSha": sha,
                "branch": branch,
                "exitReasons": [],
            }
        for name, value in (obj.get("metrics") or {}).items():
            if isinstance(value, (int, float)) and not isinstance(value, bool):
                summary["%s.%s" % (obj["scenario"], name)] = value
        for key in ("device", "pid", "scenarioStartMs"):
            if key in obj and key not in summary:
                summary[key] = obj[key]
        summary["exitReasons"].extend(obj.get("exitReasons") or [])
    return summary


def _git(args, fallback):
    try:
        return subprocess.check_output(["git"] + args).decode().strip()
    except Exception:  # noqa: BLE001 - any failure means "unknown"
        return fallback


def _write(directory, slug, sha, payload):
    os.makedirs(directory, exist_ok=True)
    out = os.path.join(directory, "%s_%s.json" % (slug, sha))
    with open(out, "w") as f:
        json.dump(payload, f, indent=2)
    return out


def main(argv=None):
    p = argparse.ArgumentParser()
    p.add_argument("--logcat", required=True)
    p.add_argument("--history-dir", required=True)
    p.add_argument("--resource-dir")
    p.add_argument("--sha")
    p.add_argument("--branch")
    p.add_argument("--now", help="ISO8601 UTC timestamp, for tests")
    args = p.parse_args(argv)

    lines = open(args.logcat).read().splitlines() if os.path.exists(args.logcat) else []
    sha = args.sha or _git(["rev-parse", "--short", "HEAD"], "unknown")
    branch = args.branch or _git(["rev-parse", "--abbrev-ref", "HEAD"], "unknown")
    now = (datetime.datetime.fromisoformat(args.now) if args.now
           else datetime.datetime.now(datetime.timezone.utc))
    slug = now.strftime("%Y-%m-%d_%Hh%Mm%Ss")

    print(_write(args.history_dir, slug, sha, build_summary(parse_bench(lines), sha, branch, now)))
    if args.resource_dir:
        resource = parse_resource(lines, sha, branch, now)
        if resource is not None:
            print(_write(args.resource_dir, slug, sha, resource))
    return 0


if __name__ == "__main__":
    sys.exit(main())
