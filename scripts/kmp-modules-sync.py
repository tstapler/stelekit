#!/usr/bin/env python3
"""Keeps kmp/modules_manifest.bzl in step with the source tree.

A new JVM source file (commonMain, jvmCommonMain, jvmMain) joins the module that already owns most files of its
directory; a file that no longer exists is dropped. A file whose directory has no owner is reported and left for you
to place by hand (edit the manifest). Run from anywhere; ModuleManifestGuardTest is the check that this was done.

    scripts/kmp-modules-sync.py          # rewrite the manifest
    scripts/kmp-modules-sync.py --check  # exit 1 if the manifest is out of date
"""
import collections
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MANIFEST = os.path.join(ROOT, "kmp", "modules_manifest.bzl")
SOURCE_SETS = ("commonMain", "jvmCommonMain", "jvmMain")
HEADER = "KMP_MODULES = "
# Own Bazel package (stats) or Gradle-only files (benchmarks, the real SketchFontResources): listed, never auto-assigned.
STATS_DIR = "commonMain/kotlin/dev/stapler/stelekit/stats/"


def load():
    text = open(MANIFEST, encoding="utf-8").read()
    head, _, body = text.partition(HEADER)
    return head, json.loads(body)


def sources():
    found = set()
    for source_set in SOURCE_SETS:
        base = os.path.join(ROOT, "kmp", "src", source_set, "kotlin")
        for dirpath, dirnames, filenames in os.walk(base):
            dirnames[:] = [d for d in dirnames if d != "build"]
            for name in filenames:
                if name.endswith(".kt"):
                    found.add(os.path.relpath(os.path.join(dirpath, name), os.path.join(ROOT, "kmp", "src")).replace(os.sep, "/"))
    return found


def sync(modules):
    present = sources()
    owner = {s: m for m, spec in modules.items() for s in spec["srcs"] if not s.startswith("//")}
    stale = sorted(s for s in owner if s not in present and not s.startswith(STATS_DIR))
    for s in stale:
        modules[owner[s]]["srcs"].remove(s)
    by_dir = collections.defaultdict(collections.Counter)
    for s, m in owner.items():
        if s not in stale:
            by_dir[os.path.dirname(s)][m] += 1
    added, orphans = [], []
    for s in sorted(present - set(owner)):
        counts = by_dir.get(os.path.dirname(s))
        if not counts:
            orphans.append(s)
            continue
        module = counts.most_common(1)[0][0]
        modules[module]["srcs"].append(s)
        added.append((s, module))
    for spec in modules.values():
        spec["srcs"].sort()
    return stale, added, orphans


def main():
    head, modules = load()
    stale, added, orphans = sync(modules)
    for s in stale:
        print("removed (gone):", s)
    for s, m in added:
        print(f"added to {m}:", s)
    for s in orphans:
        print("NOT PLACED (no module owns this directory; add it to kmp/modules_manifest.bzl by hand):", s)
    changed = bool(stale or added)
    if "--check" in sys.argv:
        sys.exit(1 if changed or orphans else 0)
    if changed:
        open(MANIFEST, "w", encoding="utf-8").write(head + HEADER + json.dumps(modules, indent=1) + "\n")
    sys.exit(1 if orphans else 0)


if __name__ == "__main__":
    main()
