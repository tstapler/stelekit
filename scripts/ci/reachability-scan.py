#!/usr/bin/env python3
"""Name-based reachability scan: lists production types referenced from no other production file.

Token-indexes production sources only (kmp/src/*Main, androidApp/src/main; tests, benchmarks'
test code and generated code are excluded), so a type used only by its own file and/or tests is a
candidate. A candidate stays live if a live type in the same file mentions it.

Usage:
  reachability-scan.py [--root DIR] [--confirm] [--explain]
  reachability-scan.py [--root DIR] --write-baseline FILE
  reachability-scan.py [--root DIR] --check-baseline FILE     (exit 1 on a type not in the baseline)
      [--allowlist FILE]       also fail if an `awaiting-unit` wiring-allowlist class the scan
                               reports is missing from the baseline (the two files are twins)
      [--allowlist-prev FILE]  also fail if an allowlist entry's `renewals=` dropped versus FILE

Output line: TypeName<TAB>file<TAB>register-row-number (row from the plan's Disposition Register,
mirrored in REGISTER below; "-" for a type the register does not account for).
The scan is name-based: it cannot see dead code behind a live name (see ADR-002 Limits).
"""
import argparse
import os
import re
import subprocess
import sys

PRODUCTION_DIR_RE = re.compile(r"^(kmp/src/[A-Za-z0-9]+Main/|androidApp/src/main/)")
EXCLUDED_PATH_PARTS = ("/generated/", "/build/", "/.claude/", "/node_modules/")
SOURCE_SUFFIXES = (".kt", ".java")
RESOURCE_SUFFIXES = (".xml", ".pro", ".txt", ".json", ".kts", ".gradle")  # name references outside code

TOKEN_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
SERIALIZABLE_RE = re.compile(r"@(?:kotlinx\.serialization\.)?Serializable\b")
UISTATE_RE = re.compile(r".*UiState$")

# Known false positives that a name-based token scan cannot see through (plan Disposition Register
# rows 34 and 35): both are consumed through a file that the scan counts as a self-reference.
KNOWN_FALSE_POSITIVES = {
    "InitializingDebugScreen": "used inside SettingsDialogHost.kt (register row 34)",
    "OtelSpanElement": "used by the in-file currentSpan extension consumed by OtelSqlDelightExtensions.kt (register row 35)",
    # Register row 36: kotlinx-benchmark entry points, launched by `./gradlew :kmp:jvmBenchmark`
    # (verified 2026-10-27: kmp/build/reports/benchmarks/main/*/jvm.json held all three classes).
    "AhoCorasickBenchmark": "JMH entry point run by ./gradlew :kmp:jvmBenchmark (register row 36)",
    "MarkdownEngineBenchmark": "JMH entry point run by ./gradlew :kmp:jvmBenchmark (register row 36)",
    "SearchBenchmark": "JMH entry point run by ./gradlew :kmp:jvmBenchmark (register row 36)",
}

# Disposition Register (plan.md "Disposition Register"): row number -> type names it covers.
REGISTER = {
    4: ["AssetIndexService"],
    5: ["AssetMoveService"],
    6: ["AssetPipelineService"],
    7: ["PluginRegistry"],
    8: ["OnDeviceLabelingPlugin"],
    9: ["PdfTextPlugin"],
    10: ["OcrPlugin"],
    11: ["CloudClaudePlugin", "CloudEnrichmentConfig"],
    12: ["CloudVisionPlugin"],
    13: ["MlKitImageLabeler", "AndroidPdfTextExtractor", "OnnxImageLabeler", "JvmPdfTextExtractor"],
    14: ["CoreMlImageLabeler", "IosPdfTextExtractor"],
    15: ["NoOpPdfTextExtractor", "NoOpImageLabeler"],
    16: ["RTCManager", "RTCOp"],
    17: ["VectorSearch", "InferenceWorker", "PlaceholderInferenceWorker", "RemoteInferenceWorker", "InferenceBridge"],
    18: ["DatalogEngine", "DatalogQuery", "DatalogPattern", "VisualQueryBuilder", "DatalogSearchSupport"],
    19: ["JsPluginBridge"],
    20: ["PluginHost", "Plugin"],
    21: ["MigrationCli"],
    22: ["PluginMigration", "EavTuple"],
    23: ["Command", "UndoManager", "BlockCommands"],
    24: ["CommandManagerCoordinator"],
    25: ["HtmlClipboardReader"],
    26: ["WebcamCameraProvider", "WebCameraProvider"],
    27: ["GooglePhotosPickerLauncher", "PhotosPickerApiClient"],
    28: ["DriveExportService", "DriveFileBrowserViewModel", "DriveFileBrowserScreen"],
    29: ["KtorWaybackMachineService"],
    30: ["KeyboardEmulationDeviceFactory"],
    31: ["AndroidUsbSerialFactory"],
    32: ["NoOpErrorTracker"],
    33: ["ICommandRegistry"],
    34: ["InitializingDebugScreen"],
    35: ["OtelSpanElement"],
    37: ["BackgroundSyncScheduler"],
}
REGISTER_ROW_COUNT = 37
ROW_OF = {name: row for row, names in REGISTER.items() for name in names}
# Rows 23 and 36 are registered by package, not by type name.
ROW_BY_PATH = (("/stelekit/command/", 23), ("/stelekit/benchmarks/", 36))


def register_row(name, rel):
    if name in ROW_OF:
        return ROW_OF[name]
    return next((row for part, row in ROW_BY_PATH if part in "/" + rel), "-")


def is_production(rel):
    rel = "/" + rel if not rel.startswith("/") else rel
    rel = rel[1:]
    if any(part in "/" + rel for part in EXCLUDED_PATH_PARTS):
        return False
    return bool(PRODUCTION_DIR_RE.match(rel))


def production_files(root):
    sources, resources = [], []
    for base in ("kmp/src", "androidApp/src"):
        for dirpath, dirnames, filenames in os.walk(os.path.join(root, base)):
            dirnames[:] = [d for d in dirnames if d not in ("build", "generated", "node_modules")]
            for fn in filenames:
                full = os.path.join(dirpath, fn)
                rel = os.path.relpath(full, root).replace(os.sep, "/")
                if not is_production(rel):
                    continue
                if fn.endswith(SOURCE_SUFFIXES):
                    sources.append(rel)
                elif fn.endswith(RESOURCE_SUFFIXES):
                    resources.append(rel)
    return sorted(sources), sorted(resources)


def manifest_component_names(root, resources):
    names = set()
    for rel in resources:
        if os.path.basename(rel) == "AndroidManifest.xml":
            for m in re.finditer(r'android:name="([^"]+)"', read_text(root, rel)):
                names.add(m.group(1).rsplit(".", 1)[-1])
    return names


SEGMENT_RE = re.compile(
    r"^((?:(?:public|internal|private|protected|expect|actual|abstract|open|final|sealed|data|enum|"
    r"annotation|inline|value|fun|const|external|suspend|operator|infix|tailrec|override|lateinit)\s+)*)"
    r"(class|interface|object|typealias|fun|val|var)\b\s*(?:<[^>]*>\s*)?(?:[\w.<>?, ]*\.)?(\w+)?"
)


class Segment:
    """A column-0 declaration. Type segments are candidates; fun/val/var segments are live roots."""

    def __init__(self, name, is_type, private, serializable, tokens):
        self.name, self.is_type, self.private = name, is_type, private
        self.serializable, self.tokens = serializable, tokens


def parse_segments(lines):
    starts = []
    for i, line in enumerate(lines):
        m = SEGMENT_RE.match(line)
        if m:
            starts.append((i, m))
    segments = []
    for idx, (i, m) in enumerate(starts):
        end = starts[idx + 1][0] if idx + 1 < len(starts) else len(lines)
        is_type = m.group(2) in ("class", "interface", "object", "typealias")
        if is_type and m.group(2) == "object" and not m.group(3):
            continue
        window = lines[max(0, i - 4): i + 1]
        serializable = any(SERIALIZABLE_RE.search(w) for w in window)
        tokens = set(TOKEN_RE.findall("\n".join(lines[i:end])))
        segments.append(Segment(m.group(3) if is_type else None, is_type,
                                "private" in m.group(1).split(), serializable, tokens))
    return segments


def read_text(root, rel):
    with open(os.path.join(root, rel), encoding="utf-8", errors="replace") as f:
        return f.read()


def resource_token_set(root, resources):
    tokens = set()
    for rel in resources:
        tokens |= set(TOKEN_RE.findall(read_text(root, rel)))
    return tokens


def live_names(segments_by_file, file_tokens, resource_tokens):
    declaring = {}
    for rel, segs in segments_by_file.items():
        for seg in segs:
            if seg.is_type and seg.name:
                declaring.setdefault(seg.name, set()).add(rel)
    live = {n for n, files in declaring.items()
            if n in resource_tokens or any(n in toks for rel, toks in file_tokens.items() if rel not in files)}
    changed = True
    while changed:
        changed = False
        for segs in segments_by_file.values():
            same_file = {s.name for s in segs if s.is_type and s.name}
            for seg in segs:
                if not (seg.name in live or not seg.is_type):
                    continue
                new = (seg.tokens & same_file) - live - {seg.name}
                if new:
                    live |= new
                    changed = True
    return live


def filter_reason(seg, manifest_names):
    if seg.serializable:
        return "@Serializable DTO"
    if UISTATE_RE.match(seg.name):
        return "*UiState type"
    if seg.name in manifest_names:
        return "Android manifest component"
    return KNOWN_FALSE_POSITIVES.get(seg.name)


def scan(root):
    sources, resources = production_files(root)
    manifest_names = manifest_component_names(root, resources)
    segments_by_file, file_tokens = {}, {}
    for rel in sources:
        text = read_text(root, rel)
        segments_by_file[rel] = parse_segments(text.splitlines())
        file_tokens[rel] = set(TOKEN_RE.findall(text))
    live = live_names(segments_by_file, file_tokens, resource_token_set(root, resources))
    skip_reasons, candidates, seen = {}, [], set()
    for rel in sorted(segments_by_file):
        for seg in segments_by_file[rel]:
            if not seg.is_type or seg.private or not seg.name or seg.name in live or seg.name in seen:
                continue
            reason = filter_reason(seg, manifest_names)
            if reason:
                skip_reasons[seg.name] = reason
                continue
            seen.add(seg.name)
            candidates.append((seg.name, rel))
    return sorted(candidates), skip_reasons


def sg_confirm(root, name, declaring_file):
    """Independent AST-level call-site count outside the declaring file (ast-grep)."""
    try:
        out = subprocess.run(
            ["sg", "run", "--pattern", name, "--lang", "kotlin", "--json=compact",
             "kmp/src", "androidApp/src"],
            cwd=root, capture_output=True, text=True, timeout=120)
    except (OSError, subprocess.TimeoutExpired) as e:
        return f"sg unavailable ({e})"
    import json
    try:
        hits = json.loads(out.stdout or "[]")
    except json.JSONDecodeError:
        return "sg output unparseable"
    prod_other = [h for h in hits if is_production(h["file"].replace(os.sep, "/")) and h["file"] != declaring_file]
    return f"sg production matches outside declaring file: {len(prod_other)}"


def format_line(name, rel):
    return f"{name}\t{rel}\t{register_row(name, rel)}"


def read_baseline(path):
    entries = {}
    with open(path, encoding="utf-8") as f:
        for ln in f:
            ln = ln.rstrip("\n")
            if ln and not ln.startswith("#"):
                entries[ln.split("\t")[0]] = ln
    return entries


def read_allowlist(path):
    """wiring-allowlist.txt -> {class: (reason, date, renewals)}; fields are separated by 2+ spaces."""
    entries = {}
    with open(path, encoding="utf-8") as f:
        for ln in f:
            ln = ln.strip()
            if not ln or ln.startswith("#"):
                continue
            parts = re.split(r"\s{2,}", ln)
            renewals = 0
            if parts and parts[-1].startswith("renewals="):
                renewals = int(parts.pop().split("=", 1)[1])
            entries[parts[0]] = (parts[1] if len(parts) > 1 else "", parts[2] if len(parts) > 2 else "", renewals)
    return entries


def allowlist_problems(found, baseline, allowlist, previous=None):
    problems = []
    for name, (reason, _date, _renewals) in sorted(allowlist.items()):
        if reason.startswith("awaiting-unit") and name in found and name not in baseline:
            problems.append(f"allowlist twin: {name} is awaiting-unit and reported unreferenced by the scan "
                            f"but missing from the baseline; shrink or grow both files together")
    for name, (_r, _d, renewals) in sorted((previous or {}).items()):
        if name in allowlist and allowlist[name][2] < renewals:
            problems.append(f"allowlist renewals lowered for {name}: {renewals} -> {allowlist[name][2]}")
    return problems


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
    ap.add_argument("--confirm", action="store_true", help="print an ast-grep call-site count per candidate")
    ap.add_argument("--explain", action="store_true", help="also print filtered names with the filter reason")
    ap.add_argument("--write-baseline")
    ap.add_argument("--check-baseline")
    ap.add_argument("--allowlist")
    ap.add_argument("--allowlist-prev")
    args = ap.parse_args(argv)
    root = os.path.abspath(args.root)
    candidates, skipped = scan(root)
    lines = [format_line(n, r) for n, r in candidates]

    if args.write_baseline:
        with open(args.write_baseline, "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + ("\n" if lines else ""))
        print(f"wrote {len(lines)} lines to {args.write_baseline}")
        return 0

    if args.check_baseline:
        baseline = read_baseline(args.check_baseline)
        found = {n for n, _ in candidates}
        new = sorted(found - set(baseline))
        shrunk = sorted(set(baseline) - found)
        for n in shrunk:
            print(f"baseline can shrink: {n}")
        if new:
            for n, r in candidates:
                if n in new:
                    print(f"NEW unreferenced type (not in baseline): {n}\t{r}")
            return 1
        problems = []
        if args.allowlist:
            previous = read_allowlist(args.allowlist_prev) if args.allowlist_prev else None
            problems = allowlist_problems(found, set(baseline), read_allowlist(args.allowlist), previous)
        for problem in problems:
            print(problem)
        if problems:
            return 1
        print(f"OK: no new unreferenced types ({len(found)} found, {len(baseline)} in baseline)")
        return 0

    for (name, rel), line in zip(candidates, lines):
        print(line + (f"\t# {sg_confirm(root, name, rel)}" if args.confirm else ""))
    if args.explain:
        for name, reason in sorted(skipped.items()):
            print(f"# filtered {name}: {reason}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
