#!/usr/bin/env python3
"""
SteleKit performance export analyzer.

Usage:
    python3 scripts/analyze-perf.py <export.json[.gz]> [--session-only] [--top N]

Flags:
    --session-only   Only analyze spans from the current session (filter out ring-buffer
                     history from prior sessions).
    --top N          Show top N slow spans per operation (default: 10).
"""
import gzip
import json
import sys
import argparse
from collections import defaultdict


def percentile(sorted_vals, p):
    if not sorted_vals:
        return 0
    idx = int(len(sorted_vals) * p / 100)
    return sorted_vals[min(idx, len(sorted_vals) - 1)]


def short_path(path):
    if not path or path == "?":
        return path
    return path.split("/")[-1]


def analyze(path, session_only=False, top_n=10):
    if path.endswith(".gz"):
        with gzip.open(path, "rt", encoding="utf-8") as f:
            data = json.load(f)
    else:
        with open(path) as f:
            data = json.load(f)

    all_spans = data["spans"]
    session = data["session"]
    session_start = session["sessionStartMs"]
    session_end = session_start + session["sessionDurationMs"]

    spans = [s for s in all_spans if s["startEpochMs"] >= session_start] if session_only else all_spans
    label = "current session" if session_only else "ring buffer (all sessions)"

    print("=" * 80)
    print(f"SteleKit Perf Report — {label}")
    print("=" * 80)
    print(f"  File:            {path}")
    print(f"  App version:     {data.get('appVersion', '?')}")
    print(f"  Commit:          {data.get('commitHash', 'N/A')}")
    print(f"  Platform:        {data.get('platform', '?')}")
    print(f"  Session duration:{session['sessionDurationMs']/1000:.1f}s")
    print(f"  Total spans:     {len(all_spans)} ({len(spans)} in {label})")
    print(f"  Error spans:     {session['errorSpans']}")
    slo = session.get("sloViolations", [])
    if slo:
        print(f"  SLO violations:  {', '.join(slo)}")
    print()

    # --- Operation stats ---
    by_name = defaultdict(list)
    for s in spans:
        by_name[s["name"]].append(s["durationMs"])

    print(f"{'Operation':<35} {'count':>6} {'p50ms':>8} {'p95ms':>8} {'maxms':>8} {'total_s':>8} {'%wall':>6}")
    print("-" * 80)
    total_wall_ms = sum(sum(v) for v in by_name.values())
    for name, durations in sorted(by_name.items(), key=lambda x: sum(x[1]), reverse=True):
        s = sorted(durations)
        p50 = percentile(s, 50)
        p95 = percentile(s, 95)
        mx = s[-1]
        total = sum(s)
        pct = total / total_wall_ms * 100 if total_wall_ms else 0
        print(f"{name:<35} {len(s):>6} {p50:>8} {p95:>8} {mx:>8} {total/1000:>8.1f} {pct:>5.0f}%")

    # --- Untraced gap analysis ---
    all_span_list = data["spans"]  # always use full list for trace grouping
    by_trace = defaultdict(list)
    for s in all_span_list:
        by_trace[s["traceId"]].append(s)

    roots = [s for s in spans if s["name"] == "parseAndSavePage"]
    if roots:
        print()
        print(f"=== parseAndSavePage untraced gaps (top {top_n}) ===")
        gaps = []
        for root in roots:
            tid = root["traceId"]
            children = [c for c in by_trace[tid] if c["spanId"] != root["spanId"]]
            child_total = sum(c["durationMs"] for c in children)
            gap = root["durationMs"] - child_total
            fp = short_path(root.get("attributes", {}).get("file.path", "?"))
            rel = (root["startEpochMs"] - session_start) / 1000
            gaps.append((gap, root["durationMs"], child_total, len(children), fp, rel))
        gaps.sort(reverse=True)
        print(f"  {'gap_ms':>10} {'total_ms':>10} {'child_ms':>10} {'gap%':>6} {'nchild':>6}  file")
        for g, total, ch, nc, fp, rel in gaps[:top_n]:
            pct = g / total * 100 if total else 0
            sign = "+" if rel >= 0 else ""
            print(f"  {g:>10} {total:>10} {ch:>10} {pct:>5.0f}% {nc:>6}  {fp}  (t{sign}{rel:.1f}s)")

    # --- db.saveBlocks bimodal ---
    sb = [s for s in spans if s["name"] == "db.saveBlocks"]
    if sb:
        print()
        print(f"=== db.saveBlocks (top {top_n} slowest) ===")
        print(f"  {'ms':>8} {'blocks':>7} {'ms/blk':>9}  flag")
        for s in sorted(sb, key=lambda s: s["durationMs"], reverse=True)[:top_n]:
            bc = int(s.get("attributes", {}).get("block.count", "0") or "0")
            ms_per = s["durationMs"] / bc if bc > 0 else 0
            flag = " SLOW" if ms_per > 50 else ""
            print(f"  {s['durationMs']:>8} {bc:>7} {ms_per:>9.1f}{flag}")

    # --- db.getBlocks slow ---
    gb = [s for s in spans if s["name"] == "db.getBlocks" and s["durationMs"] > 100]
    if gb:
        print()
        print(f"=== db.getBlocks slow (>100ms) ===")
        for s in sorted(gb, key=lambda s: s["durationMs"], reverse=True)[:top_n]:
            attrs = s.get("attributes", {})
            bc = attrs.get("block.count", "?")
            pg = attrs.get("page.name", "?")
            is_j = attrs.get("page.is_journal", "?")
            print(f"  {s['durationMs']:>6}ms  blocks={bc:<5}  journal={is_j}  page={pg}")

    # --- db.lookupPage slow ---
    lp = [s for s in spans if s["name"] == "db.lookupPage" and s["durationMs"] > 100]
    if lp:
        print()
        print(f"=== db.lookupPage slow (>100ms) ===")
        for s in sorted(lp, key=lambda s: s["durationMs"], reverse=True)[:top_n]:
            attrs = s.get("attributes", {})
            pg = attrs.get("page.name", "?")
            print(f"  {s['durationMs']:>6}ms  page={pg}")

    # --- Repeated file loads ---
    file_counts = defaultdict(list)
    for s in spans:
        if s["name"] == "parseAndSavePage":
            fp = short_path(s.get("attributes", {}).get("file.path", "?"))
            file_counts[fp].append(s["durationMs"])
    repeated = {f: v for f, v in file_counts.items() if len(v) > 1}
    if repeated:
        print()
        print("=== Files parsed more than once ===")
        for f, durations in sorted(repeated.items(), key=lambda x: len(x[1]), reverse=True):
            print(f"  {len(durations)}x  total={sum(durations)}ms  {f}")

    # --- db.queue_wait if present ---
    qw = [s for s in spans if s["name"] == "db.queue_wait"]
    if qw:
        print()
        print(f"=== db.queue_wait spans ({len(qw)} total) ===")
        errors = [s for s in qw if s.get("statusCode") == "ERROR"]
        print(f"  Total wait: {sum(s['durationMs'] for s in qw)/1000:.1f}s")
        print(f"  p95:        {percentile(sorted(s['durationMs'] for s in qw), 95)}ms")
        print(f"  max:        {max(s['durationMs'] for s in qw)}ms")
        if errors:
            print(f"  >500ms:     {len(errors)}")

    # --- file.read spans if present ---
    fr = [s for s in spans if s["name"] == "file.read"]
    if fr:
        print()
        print(f"=== file.read spans ({len(fr)} total) ===")
        durations = sorted(s["durationMs"] for s in fr)
        print(f"  p50={percentile(durations, 50)}ms  p95={percentile(durations, 95)}ms  max={durations[-1]}ms")
        slow = [s for s in fr if s["durationMs"] > 500]
        for s in sorted(slow, key=lambda s: s["durationMs"], reverse=True)[:top_n]:
            attrs = s.get("attributes", {})
            fp = short_path(attrs.get("file.path", "?"))
            kb = int(attrs.get("content.bytes", 0) or 0) // 1024
            print(f"  {s['durationMs']:>6}ms  {kb}KB  {fp}")

    # --- SQL query stats ---
    query_stats = data.get("queryStats", [])
    if query_stats:
        print()
        print(f"=== SQL query stats (top {top_n} by total_ms) ===")
        print(f"  {'table:op':<30} {'calls':>7} {'total_s':>8} {'mean_ms':>8} {'max_ms':>8} {'p95_ms':>8} {'errors':>7}")
        print("  " + "-" * 76)
        for qs in sorted(query_stats, key=lambda x: x.get("totalMs", 0), reverse=True)[:top_n]:
            key = f"{qs.get('tableName', '?')}:{qs.get('operation', '?')}"
            calls = qs.get("calls", 0)
            total_s = qs.get("totalMs", 0) / 1000
            mean = qs.get("totalMs", 0) // calls if calls else 0
            max_ms = qs.get("maxMs", 0)
            errors = qs.get("errors", 0)
            # estimate p95 from buckets
            buckets = [
                (1, qs.get("b1", 0)), (5, qs.get("b5", 0)), (16, qs.get("b16", 0)),
                (50, qs.get("b50", 0)), (100, qs.get("b100", 0)), (500, qs.get("b500", 0)),
                (99999, qs.get("bInf", 0)),
            ]
            target = max(1, int(calls * 0.95))
            cum, p95 = 0, 99999
            for bound, cnt in buckets:
                cum += cnt
                if cum >= target:
                    p95 = bound
                    break
            err_flag = " ERR" if errors > 0 else ""
            print(f"  {key:<30} {calls:>7} {total_s:>8.1f} {mean:>8} {max_ms:>8} {p95:>8}{err_flag}")

    # --- Query plans ---
    query_plan = data.get("queryPlan", {})
    if query_plan:
        print()
        print("=== Query plans ===")
        for key, rows in sorted(query_plan.items()):
            if not rows:
                continue
            print(f"  [{key}]")
            for row in rows:
                detail = row.get("detail", "")
                flag = " ⚠ SCAN" if "SCAN" in detail and "INDEX" not in detail else ""
                print(f"    {detail}{flag}")

    print()


def main():
    p = argparse.ArgumentParser(description="Analyze SteleKit perf export JSON or JSON.GZ")
    p.add_argument("file", help="Path to stelekit-perf-*.json or stelekit-perf-*.json.gz")
    p.add_argument("--session-only", action="store_true",
                   help="Only include spans from the current session (exclude ring-buffer history)")
    p.add_argument("--top", type=int, default=10, metavar="N",
                   help="Show top N entries per section (default: 10)")
    args = p.parse_args()
    analyze(args.file, session_only=args.session_only, top_n=args.top)


if __name__ == "__main__":
    main()
