#!/usr/bin/env python3
"""Assigns commonTest classes that no Bazel target runs yet to a module test target (W9 stage B).

A test belongs to the highest module (in kmp/modules_manifest.bzl) among the modules its package and imports reference,
so `bazel test //kmp/modules:<module>_tests` depends on that module and not on the jvm_main_lib umbrella. Writes
kmp/module_tests_manifest.bzl and one JUnit suite per module in kmp/modules/. Run from anywhere:

    scripts/kmp-module-tests-sync.py [--only m1,m2]   # modules that get a test target (default: every module)
"""
import collections, glob, json, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)
MANIFEST_OUT = "kmp/module_tests_manifest.bzl"
HEADER = "KMP_MODULE_TESTS = "
# Modules whose tests stay with the existing registries (the umbrella) or have their own Bazel package.
SKIP_MODULES = {"app", "mermaid", "stats", "gradle_only"}


def load_modules():
    t = open("kmp/modules_manifest.bzl", encoding="utf-8").read()
    return json.loads(t[t.index("KMP_MODULES = ") + len("KMP_MODULES = "):])


def closure(mods, k, seen=None):
    seen = seen if seen is not None else set()
    for d in mods.get(k, {}).get("deps", []):
        if d not in seen:
            seen.add(d)
            closure(mods, d, seen)
    return seen


def registered():
    reg = set()
    for b in glob.glob("kmp/src/**/BUILD.bazel", recursive=True) + glob.glob("kmp/modules/BUILD.bazel"):
        text = open(b, encoding="utf-8").read()
        reg |= {os.path.basename(x) for x in re.findall(r'"([\w/]+Test\.kt)"', text)}
    t = open(MANIFEST_OUT, encoding="utf-8").read() if os.path.exists(MANIFEST_OUT) else ""
    return reg, t


def test_classes(src):
    """Top-level non-abstract classes of a file that declare at least one @Test (a file may hold several, or none named like it)."""
    heads = list(re.finditer(r"^((?:(?:internal|public|open|private|abstract|final)\s+)*)class\s+(\w+)", src, re.M))
    found = []
    for i, h in enumerate(heads):
        body = src[h.end(): heads[i + 1].start() if i + 1 < len(heads) else len(src)]
        if "abstract" not in h.group(1) and "private" not in h.group(1) and "@Test" in body:
            found.append(h.group(2))
    return found


DECL = re.compile(r"^(?:internal |public |private |data |sealed |abstract |open |enum |annotation |fun )*(?:class|object|interface|typealias|fun|val|var)\s+(?:<[^>]*>\s*)?(?:[\w.<>?]+\.)?(\w+)", re.M)


def fixture_index():
    """pkg -> {declared top-level name -> commonTest file}, for helper files that are not themselves *Test.kt."""
    idx = collections.defaultdict(dict)
    for f in glob.glob("kmp/src/commonTest/kotlin/**/*.kt", recursive=True):
        src = open(f, encoding="utf-8").read()
        m = re.search(r"^package ([\w.]+)", src, re.M)
        if m:
            for n in DECL.findall(src):
                idx[m.group(1)].setdefault(n, f)
    return idx


def fixture_closure(f, idx, seen):
    """Adds to `seen` every commonTest helper file `f` references (same package by token, others by import), transitively."""
    src = open(f, encoding="utf-8").read()
    pkg = re.search(r"^package ([\w.]+)", src, re.M).group(1)
    tokens = set(re.findall(r"\b\w+\b", src))
    want = {fl for n, fl in idx.get(pkg, {}).items() if n in tokens}
    for imp in re.findall(r"^import ([\w.]+)", src, re.M):
        p, _, n = imp.rpartition(".")
        if n in idx.get(p, {}):
            want.add(idx[p][n])
        elif imp in idx:
            want |= set(idx[imp].values())
    for w in want:
        if w != f and w not in seen:
            seen.add(w)
            fixture_closure(w, idx, seen)


def main():
    only = None
    if "--only" in sys.argv:
        only = set(sys.argv[sys.argv.index("--only") + 1].split(","))
    mods = load_modules()
    cls2mod, pkg2mods = {}, collections.defaultdict(set)
    for k, v in mods.items():
        for s in v["srcs"]:
            if s.endswith(".kt") and "/kotlin/" in s:
                pkg = os.path.dirname(s.split("/kotlin/", 1)[1]).replace("/", ".")
                pkg2mods[pkg].add(k)
                cls2mod[pkg + "." + os.path.basename(s)[:-3]] = k
    reg, _ = registered()
    out = collections.defaultdict(list)
    idx = fixture_index()
    for f in sorted(glob.glob("kmp/src/commonTest/kotlin/**/*Test.kt", recursive=True)):
        if os.path.basename(f) in reg:
            continue
        src = open(f, encoding="utf-8").read()
        pkg = re.search(r"^package ([\w.]+)", src, re.M).group(1)
        ms = set(pkg2mods.get(pkg, ()))
        for imp in re.findall(r"^import ([\w.]+)", src, re.M):
            if imp in cls2mod:
                ms.add(cls2mod[imp])
            elif imp.startswith("dev.stapler") and imp.rsplit(".", 1)[0] in pkg2mods:
                ms |= pkg2mods[imp.rsplit(".", 1)[0]]
        for fq in set(re.findall(r"dev\.stapler\.stelekit(?:\.\w+)+", src)):
            parts = fq.split(".")
            for i in range(len(parts), 3, -1):
                cand = ".".join(parts[:i])
                if cand in cls2mod:
                    ms.add(cls2mod[cand])
                    break
                if cand in pkg2mods:
                    ms |= pkg2mods[cand]
                    break
        top = [x for x in ms if all(y == x or y in closure(mods, x) for y in ms)]
        if not top or top[0] in SKIP_MODULES or (only and top[0] not in only):
            continue
        for c in test_classes(src):
            out[top[0]].append((f, pkg + "." + c))
    for mod, items in out.items():
        name = "All" + "".join(p.title() for p in mod.split("_")) + "ModuleTests"
        lines = ["package dev.stapler.stelekit.modules", ""]
        lines += ["import %s" % c for _, c in items]
        lines += ["import org.junit.runner.RunWith", "import org.junit.runners.Suite", "",
                  "/** Generated by scripts/kmp-module-tests-sync.py: the commonTest classes of module `%s`. */" % mod,
                  "@RunWith(Suite::class)", "@Suite.SuiteClasses("]
        lines += ["    %s::class," % c.rsplit(".", 1)[1] for _, c in items]
        lines += [")", "class %s" % name, ""]
        open("kmp/modules/%s.kt" % name, "w", encoding="utf-8").write("\n".join(lines))
    data = {}
    strip = len("kmp/src/commonTest/kotlin/")
    for m, v in sorted(out.items()):
        files = {f for f, _ in v}
        v = [(f, c) for f, c in v]
        for f, _ in v:
            fixture_closure(f, idx, files)
        data[m] = {"suite": "All" + "".join(p.title() for p in m.split("_")) + "ModuleTests",
                   "srcs": sorted(x[strip:] for x in files)}
    open(MANIFEST_OUT, "w", encoding="utf-8").write(
        "# GENERATED by scripts/kmp-module-tests-sync.py; commonTest classes run by //kmp/modules:<module>_tests.\n"
        + HEADER + json.dumps(data, indent=1, sort_keys=True) + "\n")
    print({m: len(v["srcs"]) for m, v in data.items()})


main()
