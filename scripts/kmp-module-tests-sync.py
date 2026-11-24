#!/usr/bin/env python3
"""Assigns commonTest classes that no Bazel target runs yet to a module test target (W9 stage B).

A test belongs to the highest module (in kmp/modules_manifest.bzl) among the modules its package and imports reference,
so `bazel test //kmp/modules:<module>_tests` depends on that module and not on the jvm_main_lib umbrella. Writes
kmp/module_tests_manifest.bzl and one JUnit suite per module in kmp/modules/.

businessTest classes whose references (with their helper files') resolve to one chain of modules move the same way, to
//kmp/modules:<module>_business_tests: the script drops them from AllBusinessTests.kt, lists them in the generated
ModuleBusinessTests.kt, and re-registers any that stop qualifying. Tests that read sources/resources stay. Run from anywhere:

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
    """Package-relative paths (under commonTest/kotlin) that some BUILD file lists outside a comment."""
    reg = set()
    for b in glob.glob("kmp/src/**/BUILD.bazel", recursive=True) + glob.glob("kmp/modules/BUILD.bazel"):
        text = "\n".join(l for l in open(b, encoding="utf-8").read().splitlines() if not l.lstrip().startswith("#"))
        for x in re.findall(r'"([\w/:.]+Test\.kt)"', text):
            reg.add(x.split(":")[-1])
    return reg


def test_classes(src):
    """Top-level non-abstract classes of a file that declare at least one @Test (a file may hold several, or none named like it)."""
    heads = list(re.finditer(r"^((?:(?:internal|public|open|private|abstract|final)\s+)*)class\s+(\w+)", src, re.M))
    found = []
    for i, h in enumerate(heads):
        body = src[h.end(): heads[i + 1].start() if i + 1 < len(heads) else len(src)]
        if "abstract" not in h.group(1) and "private" not in h.group(1) and "@Test" in body:
            found.append(h.group(2))
    return found


ANY_DECL = re.compile(r"\b(?:class|object|interface|typealias|fun|val|var)\s+(?:<[^>]*>\s*)?(?:[\w.<>?]+\.)?(\w+)")
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
        if imp in idx:
            want |= set(idx[imp].values())
    # Imports and inline qualified names, including nested ones (`pkg.OuterTest.Harness` resolves to OuterTest's file).
    for fq in set(re.findall(r"^import ([\w.]+)", src, re.M)) | set(re.findall(r"\bdev\.stapler\.stelekit(?:\.\w+)+", src)):
        parts = fq.split(".")
        for i in range(len(parts) - 1, 0, -1):
            p, n = ".".join(parts[:i]), parts[i]
            if n in idx.get(p, {}):
                want.add(idx[p][n])
                break
    for w in want:
        if w != f and w not in seen:
            seen.add(w)
            fixture_closure(w, idx, seen)


def referenced_modules(src, cls2mod, pkg2mods):
    """Modules a source file references: same-package names by token, imports, and fully-qualified names."""
    pkg = re.search(r"^package ([\w.]+)", src, re.M).group(1)
    toks = set(re.findall(r"\b\w+\b", src))
    ms = {cls2mod[pkg + "." + n] for n in toks if pkg + "." + n in cls2mod}
    for imp in re.findall(r"^import ([\w.]+)", src, re.M):
        if imp in cls2mod:
            ms.add(cls2mod[imp])
        elif imp.startswith("dev.stapler") and imp.rsplit(".", 1)[0] in pkg2mods:
            ms |= pkg2mods[imp.rsplit(".", 1)[0]] - {"app"}  # package-level guess: never the umbrella
    for fq in set(re.findall(r"dev\.stapler\.stelekit(?:\.\w+)+", re.sub(r"^package .*$", "", src, flags=re.M))):
        parts = fq.split(".")
        for i in range(len(parts), 3, -1):
            cand = ".".join(parts[:i])
            if cand in cls2mod:
                ms.add(cls2mod[cand])
                break
            if cand in pkg2mods:
                ms |= pkg2mods[cand] - {"app"}
                break
    return ms


def suite_name(mod, kind=""):
    return "All" + "".join(p.title() for p in mod.split("_")) + "Module" + kind + "Tests"


def write_suite(mod, kind, classes, what):
    lines = ["package dev.stapler.stelekit.modules", ""]
    lines += ["import %s" % c for c in classes]
    lines += ["import org.junit.runner.RunWith", "import org.junit.runners.Suite", "",
              "/** Generated by scripts/kmp-module-tests-sync.py: the %s classes of module `%s`. */" % (what, mod),
              "@RunWith(Suite::class)", "@Suite.SuiteClasses("]
    lines += ["    %s::class," % c.rsplit(".", 1)[1] for c in classes]
    lines += [")", "class %s" % suite_name(mod, kind), ""]
    open("kmp/modules/%s.kt" % suite_name(mod, kind), "w", encoding="utf-8").write("\n".join(lines))


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
                path = os.path.join("kmp/src", s)
                if os.path.exists(path):  # every top-level declaration, not just the one named like the file
                    for n in ANY_DECL.findall(open(path, encoding="utf-8").read()):
                        cls2mod.setdefault(pkg + "." + n, k)
    reg = registered()
    out = collections.defaultdict(list)
    idx = fixture_index()
    for f in sorted(glob.glob("kmp/src/commonTest/kotlin/**/*Test.kt", recursive=True)):
        if f[len("kmp/src/commonTest/kotlin/"):] in reg:
            continue
        src = open(f, encoding="utf-8").read()
        pkg = re.search(r"^package ([\w.]+)", src, re.M).group(1)
        ms = referenced_modules(src, cls2mod, pkg2mods)
        if not ms:  # nothing resolved to a class: fall back to the package's lowest module (never the umbrella)
            ms = {m for m in pkg2mods.get(pkg, ()) if m != "app"}
            ms = {m for m in ms if not (closure(mods, m) & ms)}
            if len(ms) > 1:  # unordered siblings and no class reference to tell them apart: the module owning most of the package
                counts = collections.Counter(k for k, v in mods.items() for s in v["srcs"]
                                             if k in ms and s.endswith(".kt") and "/kotlin/" in s
                                             and os.path.dirname(s.split("/kotlin/", 1)[1]).replace("/", ".") == pkg)
                ms = {counts.most_common(1)[0][0]}
        top = [x for x in ms if all(y == x or y in closure(mods, x) for y in ms)]
        if not top:  # unordered siblings: take the module that owns most files of the test's package; the build proves it
            owner = collections.Counter(k for k in ms for s in mods[k]["srcs"] if s.endswith(".kt") and "/kotlin/" in s
                                        and os.path.dirname(s.split("/kotlin/", 1)[1]).replace("/", ".") == pkg)
            top = sorted(ms, key=lambda x: (-len(({x} | closure(mods, x)) & ms), -owner[x], x))[:1]
        if not top:
            print("WARNING: no single top module for %s: %s" % (f, sorted(ms)), file=sys.stderr)
        if not top or top[0] in SKIP_MODULES or (only and top[0] not in only):
            continue
        for c in test_classes(src):
            out[top[0]].append((f, pkg + "." + c))
    for mod, items in out.items():
        write_suite(mod, "", [c for _, c in items], "commonTest")
    data = {}
    strip = len("kmp/src/commonTest/kotlin/")
    for m, v in sorted(out.items()):
        files = {f for f, _ in v}
        v = [(f, c) for f, c in v]
        for f, _ in v:
            fixture_closure(f, idx, files)
        data[m] = {"suite": suite_name(m), "srcs": sorted(x[strip:] for x in files)}
    business = business_tests(mods, cls2mod, pkg2mods, only)
    live = {suite_name(m) for m in out} | {suite_name(m, "Business") for m in business}
    for old in glob.glob("kmp/modules/All*Tests.kt"):
        if "Generated by scripts/kmp-module-tests-sync.py" in open(old, encoding="utf-8").read() and \
                os.path.basename(old)[:-3] not in live:
            os.remove(old)
    open(MANIFEST_OUT, "w", encoding="utf-8").write(
        "# GENERATED by scripts/kmp-module-tests-sync.py; commonTest classes run by //kmp/modules:<module>_tests.\n"
        + HEADER + json.dumps(data, indent=1, sort_keys=True) + "\n\n"
        + "# businessTest classes run by //kmp/modules:<module>_business_tests (paths relative to kmp/src).\n"
        + BUSINESS_HEADER + json.dumps(business, indent=1, sort_keys=True) + "\n")
    print("commonTest:", {m: len(v["srcs"]) for m, v in data.items()})
    print("businessTest classes:", {m: len(v["classes"]) for m, v in business.items()},
          "total", sum(len(v["classes"]) for v in business.values()))


# ── businessTest (stage B, slice 1) ────────────────────────────────────────────────────────────────────────────────
BUSINESS_HEADER = "KMP_MODULE_BUSINESS_TESTS = "
BUSINESS_DIR = "kmp/src/businessTest/kotlin/"
REGISTRY = BUSINESS_DIR + "dev/stapler/stelekit/AllBusinessTests.kt"
INDEX = BUSINESS_DIR + "dev/stapler/stelekit/ModuleBusinessTests.kt"
# Tests that read repo sources or bundled resources, drive the whole wired app, or audit the tree stay in
# //kmp:business_tests (w9-module-split-analysis.md section 4.3): they need the umbrella and its resource jars.
CROSS_CUTTING_NAME = re.compile(r"(Audit|Structural|Completeness|ImportDirection|Grep|Guard|Wiring|EntryPoint)\w*Test\.kt$")
CROSS_CUTTING_TEXT = re.compile(
    r"\bWiringInputs\b|\bWiredAppFixture\b|\bDesktopWiredAppFixture\b|\bDocRepoLocator\b|\bWiringRoots\b|\bAllBusinessTests\b"
    r"|getResource|getSystemResource|\bclassLoader\b|\bClassLoader\b|\"user\.dir\"|\"kmp/|java\.class\.path")
COMMENT = re.compile(r"/\*.*?\*/|//[^\n]*", re.S)  # a name in a KDoc is not a use
# Moved out and then failed under the module target; kept here with the reason (fill only from a real failure).
KEEP = {
}


def business_fixture_index():
    """pkg -> {non-private top-level name -> file} over businessTest and commonTest, excluding the generated registries."""
    idx = collections.defaultdict(dict)
    files = sorted(glob.glob(BUSINESS_DIR + "**/*.kt", recursive=True)) + \
        sorted(glob.glob("kmp/src/commonTest/kotlin/**/*.kt", recursive=True))
    for f in files:
        if f in (REGISTRY, INDEX):
            continue
        src = open(f, encoding="utf-8").read()
        m = re.search(r"^package ([\w.]+)", src, re.M)
        if m:
            for line in src.splitlines():
                if line.startswith("private "):
                    continue
                d = DECL.match(line)
                if d:
                    idx[m.group(1)].setdefault(d.group(1), f)
    return idx


def read_previous_business():
    try:
        t = open(MANIFEST_OUT, encoding="utf-8").read()
    except FileNotFoundError:
        return {}
    if BUSINESS_HEADER not in t:
        return {}
    return json.loads(t[t.index(BUSINESS_HEADER) + len(BUSINESS_HEADER):])


def business_tests(mods, cls2mod, pkg2mods, only):
    """Moves businessTest classes whose referenced modules (with their fixtures') form one chain to per-module targets."""
    idx = business_fixture_index()
    reach = {m: closure(mods, m) for m in mods}
    files = [f for f in sorted(glob.glob(BUSINESS_DIR + "**/*.kt", recursive=True)) if f not in (REGISTRY, INDEX)]
    text = {f: open(f, encoding="utf-8").read() for f in files}
    quoted = set()  # simple names other files refer to by string (ConversionReadyConsistencyTest and the like)
    for f, src in text.items():
        quoted |= {(f, n) for n in re.findall(r'"(\w+Test)"', src)}
    clos = {}
    for f in files:
        seen = set()
        fixture_closure(f, idx, seen)
        clos[f] = seen
    cand = {}
    for f in files:
        classes = test_classes(text[f])
        if not f.endswith("Test.kt") or not classes:
            continue
        name = os.path.basename(f)[:-3]
        if CROSS_CUTTING_NAME.search(f) or name in KEEP or any(n == name and g != f for g, n in quoted):
            continue
        group = {f} | clos[f]
        if any(CROSS_CUTTING_TEXT.search(COMMENT.sub("", open(g, encoding="utf-8").read())) for g in group):
            continue
        ms = set()
        for g in group:
            ms |= referenced_modules(open(g, encoding="utf-8").read(), cls2mod, pkg2mods)
        top = [x for x in ms if all(y == x or y in reach[x] for y in ms)]
        if len(top) != 1 or top[0] in SKIP_MODULES or (only and top[0] not in only):
            continue
        pkg = re.search(r"^package ([\w.]+)", text[f], re.M).group(1)
        cand[f] = (top[0], [pkg + "." + c for c in classes])
    # A file a remaining business_tests source compiles against must stay in business_tests too.
    changed = True
    while changed:
        changed = False
        for f in files:
            if f in cand:
                continue
            for g in clos[f] & set(cand):
                del cand[g]
                changed = True
    out = collections.defaultdict(lambda: {"classes": [], "tests": [], "srcs": set()})
    for f, (m, classes) in sorted(cand.items()):
        out[m]["classes"] += classes
        out[m]["tests"].append(f)
        out[m]["srcs"] |= {f} | clos[f]
    strip = len("kmp/src/")
    business = {}
    for m, v in sorted(out.items()):
        write_suite(m, "Business", v["classes"], "businessTest")
        business[m] = {"suite": suite_name(m, "Business"), "classes": v["classes"],
                       "tests": sorted(x[strip:] for x in v["tests"]), "srcs": sorted(x[strip:] for x in v["srcs"])}
    existing = {re.search(r"^package ([\w.]+)", s, re.M).group(1) + "." + c for s in text.values() for c in test_classes(s)}
    sync_registry(business, read_previous_business(), existing)
    write_index(business)
    return business


def sync_registry(business, previous, existing):
    """Drops moved classes from AllBusinessTests and re-registers classes that moved back (and still exist) since the last run."""
    moved = {c for v in business.values() for c in v["classes"]}
    before = {c for v in previous.values() for c in v.get("classes", [])}
    lines = open(REGISTRY, encoding="utf-8").read().split("\n")
    imported = {l[len("import "):].strip() for l in lines if l.startswith("import ")}
    keep = []
    for l in lines:
        s = l.strip()
        if l.startswith("import ") and s[len("import "):] in moved:
            continue
        if s.endswith("::class,") or s.endswith("::class"):
            ref = s.rstrip(",")[:-len("::class")]
            fq = ref if "." in ref else next((c for c in moved if c.rsplit(".", 1)[1] == ref and
                                             (c in imported or c.rsplit(".", 1)[0] == "dev.stapler.stelekit")), None)
            if fq in moved:
                continue
        keep.append(l)
    registered = {r for r in (s.strip().rstrip(",")[:-len("::class")] for s in keep if s.strip().rstrip(",").endswith("::class"))}
    back = sorted(c for c in (before - moved) & existing if c not in registered and c.rsplit(".", 1)[1] not in registered)
    if back:
        close = max(i for i, l in enumerate(keep) if l.strip() == ")")
        keep[close:close] = ["    %s::class," % c for c in back]
    open(REGISTRY, "w", encoding="utf-8").write("\n".join(keep))


def write_index(business):
    entries = sorted((c, m) for m, v in business.items() for c in v["classes"])
    lines = ["package dev.stapler.stelekit", "",
             "/**",
             " * Generated by scripts/kmp-module-tests-sync.py; do not edit. businessTest classes that run in",
             " * `//kmp/modules:<module>_business_tests` instead of [AllBusinessTests] (class name to module), read by",
             " * AllBusinessTestsCompletenessTest and TestRegistrationAuditTest.",
             " */",
             "internal val MODULE_BUSINESS_TESTS: Map<String, String> = mapOf("]
    lines += ['    "%s" to "%s",' % e for e in entries]
    lines += [")", ""]
    open(INDEX, "w", encoding="utf-8").write("\n".join(lines))


main()
