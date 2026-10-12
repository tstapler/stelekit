#!/usr/bin/env python3
"""Regenerates kmp/src/commonTest/.../git/merge/LogseqUnionGoldenCases.kt.

Runs the real python `merge=logseq-union` driver (`logseq-union-merge-driver.py BASE LOCAL REMOTE`,
which overwrites LOCAL) over a hand-written + seeded-random corpus and records its exact output
bytes as expected values for the Kotlin port (LogseqUnionMergeDriverGoldenTest). The python source
is not available in CI, so the generated file is committed.

Usage: scripts/gen-logseq-union-golden.py [DRIVER_PY] > kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/merge/LogseqUnionGoldenCases.kt
"""
import os
import random
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor

DRIVER = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser(
    "~/Documents/personal-wiki/logseq-union-merge-driver.py")

HAND = [
    # Simple addition
    ("simple-add-local", "- a\n- b\n", "- a\n- b\n- c\n", "- a\n- b\n"),
    ("simple-add-remote", "- a\n- b\n", "- a\n- b\n", "- a\n- b\n- c\n- d\n"),
    ("simple-add-crlf", "- a\r\n- b\r\n", "- a\r\n- b\r\n- c\r\n", "- a\r\n- b\r\n"),
    ("simple-add-from-empty", "", "- a\n", ""),
    ("both-empty", "", "", ""),
    ("all-identical", "- a\n- b\n", "- a\n- b\n", "- a\n- b\n"),
    # Same line count
    ("same-count-disjoint", "- a\n- b\n- c\n", "- A\n- b\n- c\n", "- a\n- b\n- C\n"),
    ("same-count-conflict", "- a\n- b\n", "- a\n- mine\n", "- a\n- theirs\n"),
    ("same-count-conflict-with-refs", "- a [[X]]\n- b\n", "- a [[X]]\n- mine #t\n", "- a [[X]]\n- theirs\n"),
    ("same-count-identical-edit", "- a\n- b\n", "- a\n- same\n", "- a\n- same\n"),
    ("same-count-crlf-conflict", "- a\r\n- b\r\n", "- a\r\n- m\r\n", "- a\r\n- t\r\n"),
    ("same-count-multiple-conflicts", "1\n2\n3\n", "a\n2\nc\n", "x\n2\nz\n"),
    # Data loss, remote side
    ("android-loss-remote-shrank", "- 1\n- 2\n- 3\n- 4\n- 5\n- 6\n- 7\n- 8\n- 9\n- 10\n",
     "- 1\n- 2\n- 3\n- 4\n- 5\n- 6\n- 7\n- 8\n- 9\n- 10\n- local new\n", "- 1\n- 2\n"),
    ("android-loss-remote-only-additions", "- 1\n- 2\n- 3\n- 4\n- 5\n- 6\n- 7\n- 8\n- 9\n- 10\n",
     "- 1\n- 2\n- 3\n- 4\n- 5\n- 6\n- 7\n- 8\n- 9\n- 10\n", "- 1\n- 2\n- remote new\n  - nested new\n"),
    ("android-loss-remote-empty", "- 1\n- 2\n- 3\n", "- 1\n- 2\n- 3\n", ""),
    # Data loss, local side
    ("local-loss-stale-local", "- 1\n- 2\n- 3\n- 4\n- 5\n- 6\n", "- 1\n- 2\n- 3\n- 7\n", "- 1\n- 2\n- 3\n- 4\n- 5\n- 6\n"),
    ("local-loss-no-local-new", "- 1\n- 2\n- 3\n- 4\n- 5\n- 6\n", "- 1\n- 2\n- 3\n", "- 1\n- 2\n- 3\n- 4\n- 5\n- 6\n- 8\n"),
    ("local-loss-below-threshold", "- 1\n- 2\n- 3\n- 4\n- 5\n- 6\n- 7\n- 8\n- 9\n- 10\n",
     "- 1\n- 2\n- 3\n- 4\n- 5\n- 6\n- 7\n- 8\n", "- 1\n- 2\n- 3\n- 4\n- 5\n- 6\n- 7\n- 8\n- 9\n- 10\n- 11\n"),
    ("local-loss-whitespace-only-diff", "- a\n- b\n- c\n- d\n", "  - a\n  - b\n", "- a\n- b\n- c\n- d\n"),
    # Page-reference strategy
    ("ref-insert-both-ends", "- a [[P]]\n- b\n", "- top\n- a [[P]]\n- b\n", "- a [[P]]\n- b\n- bottom\n- more\n"),
    ("ref-insert-same-spot", "- a [[P]]\n- b\n", "- a [[P]]\n- mine\n- b\n", "- a [[P]]\n- theirs\n- b\n"),
    ("ref-local-delete", "- a [[P]]\n- b\n- c\n- d\n", "- a [[P]]\n- c\n- d\n- e\n", "- a [[P]]\n- b\n- c\n- d\n- f\n"),
    ("ref-remote-delete", "- a [[P]]\n- b\n- c\n- d\n", "- a [[P]]\n- b\n- c\n- d\n- e\n", "- a [[P]]\n- c\n- d\n- f\n"),
    ("ref-both-delete-same", "- a [[P]]\n- b\n- c\n", "- a [[P]]\n- c\n- x\n", "- a [[P]]\n- c\n"),
    ("ref-local-edit-remote-delete", "- a [[P]]\n- b\n- c\n- d\n", "- a [[P]]\n- B!\n- c\n- d\n- x\n", "- a [[P]]\n- c\n- d\n"),
    ("ref-local-delete-remote-edit", "- a [[P]]\n- b\n- c\n- d\n", "- a [[P]]\n- c\n- d\n- x\n", "- a [[P]]\n- B!\n- c\n- d\n"),
    ("ref-both-edit-same-line", "- a [[P]]\n- b\n- c\n- x\n", "- a [[P]]\n- mine\n- c\n- x\n- y\n", "- a [[P]]\n- theirs\n- c\n- x\n"),
    ("ref-local-clears-block", "- a [[P]]\n- b\n- c\n- x\n", "- a [[P]]\n-\n- c\n- x\n- y\n", "- a [[P]]\n- b edited\n- c\n- x\n"),
    ("ref-local-blank-block", "- a [[P]]\n- b\n- c\n- x\n", "- a [[P]]\n\n- c\n- x\n- y\n", "- a [[P]]\n- b edited\n- c\n- x\n"),
    ("ref-remote-clears-block", "- a [[P]]\n- b\n- c\n- x\n", "- a [[P]]\n- b edited\n- c\n- x\n- y\n", "- a [[P]]\n-\n- c\n- x\n"),
    ("ref-replace-unequal-counts", "- a [[P]]\n- b\n- c\n- d\n- e\n", "- a [[P]]\n- B1\n- B2\n- B3\n- e\n- z\n", "- a [[P]]\n- b\n- C\n- D\n- e\n- w\n"),
    ("ref-both-replace-unequal", "- a [[P]]\n- b\n- c\n- d\n- e\n", "- a [[P]]\n- m1\n- m2\n- m3\n- e\n- z\n", "- a [[P]]\n- t1\n- d\n- e\n- w\n"),
    ("ref-duplicate-lines", "- a [[P]]\n- x\n- x\n- y\n", "- a [[P]]\n- x\n- x\n- x\n- y\n- z\n", "- a [[P]]\n- x\n- y\n- y\n- q\n"),
    ("ref-duplicate-blank-lines", "- a [[P]]\n\n- b\n\n- c\n", "- a [[P]]\n\n- b\n\n\n- c\n- n\n", "- a [[P]]\n\n- b\n- c\n- m\n"),
    ("ref-tag-hash", "- a #tag\n- b\n", "- a #tag\n- b\n- local\n", "- a #tag\n- remote\n- b\n- b2\n"),
    ("ref-block-ref", "- a ((uuid))\n- b\n", "- a ((uuid))\n- b\n- l\n", "- c\n- a ((uuid))\n- b\n- r\n"),
    ("ref-reorder", "- a [[P]]\n- b\n- c\n", "- c\n- b\n- a [[P]]\n- l\n", "- a [[P]]\n- b\n- c\n- r\n"),
    ("ref-nested-indent", "- a [[P]]\n\t- b\n\t\t- c\n", "- a [[P]]\n\t- b\n\t\t- c\n\t\t- l\n", "- a [[P]]\n\t- b\n\t- r\n\t\t- c\n"),
    ("ref-trailing-whitespace", "- a [[P]]\n- b \n- c\n", "- a [[P]]\n- b\n- c\n- l\n", "- a [[P]]\n- b \n- c\n- r\n"),
    ("ref-crlf", "- a [[P]]\r\n- b\r\n", "- a [[P]]\r\n- b\r\n- l\r\n", "- a [[P]]\r\n- r\r\n- b\r\n"),
    ("ref-mixed-line-endings", "- a [[P]]\r\n- b\n", "- a [[P]]\n- b\r\n- l\r", "- a [[P]]\r- b\n- r\n"),
    ("ref-unicode-separators", "- a [[P]] - b\u0085- c\x0b- d\x0c- e\n", "- a [[P]]\n- b\n- l\n", "- a [[P]]\n- b\n- r\n"),
    ("ref-empty-base", "", "- l [[P]]\n", "- r [[Q]]\n"),
    ("ref-empty-local", "- a [[P]]\n- b\n", "", "- a [[P]]\n- b\n- r\n"),
    ("ref-no-trailing-newline", "- a [[P]]\n- b", "- a [[P]]\n- b\n- l", "- a [[P]]\n- r\n- b"),
    ("ref-astral-and-nbsp", "- a [[P]]\n- \U0001F600\n", "- a [[P]]\n- \U0001F600\n- \n- l\n", "- a [[P]]\n- r\n- \U0001F600\n"),
    # Fallback strategy (no references, line counts differ)
    ("fallback-disjoint-adds", "a\nb\nc\n", "a\nb\nc\nl\n", "a\nR\nb\nc\n"),
    ("fallback-deletions-not-propagated", "a\nb\nc\nd\n", "a\nc\nd\nl\n", "a\nb\nd\nr\n"),
    ("fallback-duplicates-collapse", "a\nb\n", "a\nb\nb\nb\nl\n", "a\nb\nb\nr\nr\n"),
    ("fallback-blank-lines", "a\n\nb\n", "a\n\n\nb\nl\n", "a\nb\n\nr\n"),
    ("fallback-reorder", "a\nb\nc\n", "c\nb\na\nl\n", "a\nb\nc\nr\n"),
    ("fallback-crlf", "a\r\nb\r\n", "a\r\nb\r\nl\r\n", "a\r\nr\r\nb\r\n"),
    ("fallback-whitespace-lines", "a\n \nb\n", "a\n \n  \nb\nl\n", "a\n\nb\nr\n"),
    ("fallback-trailing-newline-differs", "a\nb", "a\nb\nl\n", "a\nr\nb"),
]

VOCAB = ["- alpha", "- beta [[Page]]", "- gamma #tag", "- delta", "", "\t- child", "- ((blockref))", "-",
         "- dup", "- dup", "- epsilon", "  - spaced", "- zeta [[Other]]", "- eta"]


def mutate(rng, lines, tag):
    lines = list(lines)
    for _ in range(rng.randint(0, 4)):
        op = rng.choice(["insert", "delete", "replace", "dup", "swap", "tail"])
        pos = rng.randint(0, len(lines))
        if op == "insert":
            lines.insert(pos, rng.choice(VOCAB) or f"- {tag} new")
        elif op == "delete" and lines:
            del lines[rng.randrange(len(lines))]
        elif op == "replace" and lines:
            lines[rng.randrange(len(lines))] = f"{rng.choice(VOCAB)} {tag}{rng.randint(0, 3)}"
        elif op == "dup" and lines:
            lines.insert(pos, lines[rng.randrange(len(lines))])
        elif op == "swap" and len(lines) > 1:
            i, j = rng.sample(range(len(lines)), 2)
            lines[i], lines[j] = lines[j], lines[i]
        elif op == "tail":
            lines.append(f"- {tag} tail {rng.randint(0, 9)}")
    return lines


def render(rng, lines):
    eol = rng.choice(["\n", "\n", "\n", "\r\n"])
    text = eol.join(lines)
    return text + eol if lines and rng.random() < 0.8 else text


def random_cases(count, seed):
    rng = random.Random(seed)
    cases = []
    for n in range(count):
        base = [rng.choice(VOCAB) for _ in range(rng.randint(0, 12))]
        if rng.random() < 0.15:
            base = [f"- line {i}" for i in range(rng.randint(8, 20))]  # unique lines: exercises data-loss ratios
        local, remote = mutate(rng, base, "L"), mutate(rng, base, "R")
        if rng.random() < 0.15:
            remote = remote[: max(1, len(remote) // 4)]
        cases.append((f"random-{n:03d}", render(rng, base), render(rng, local), render(rng, remote)))
    return cases


def long_cases(seed):
    """>=200-line inputs trigger difflib's autojunk popular-element purge."""
    rng = random.Random(seed)
    cases = []
    for n in range(6):
        base = [("-" if i % 3 == 0 else f"- line {i} [[P{i % 7}]]") for i in range(rng.randint(205, 260))]
        local, remote = mutate(rng, base, "L"), mutate(rng, base, "R")
        local += ["- L extra"] * rng.randint(0, 2)
        cases.append((f"long-autojunk-{n}", "\n".join(base) + "\n", "\n".join(local) + "\n", "\n".join(remote) + "\n"))
    return cases


def run_driver(case):
    name, base, local, remote = case
    with tempfile.TemporaryDirectory() as d:
        paths = {k: os.path.join(d, k) for k in ("base", "local", "remote")}
        for key, text in (("base", base), ("local", local), ("remote", remote)):
            with open(paths[key], "wb") as f:
                f.write(text.encode("utf-8"))
        subprocess.run(["python3", DRIVER, paths["base"], paths["local"], paths["remote"]],
                       check=True, capture_output=True, cwd=d)
        with open(paths["local"], "rb") as f:
            return name, base, local, remote, f.read().decode("utf-8")


def kt(s):
    out = ['"']
    for ch in s:
        o = ord(ch)
        if ch in '\\"$':
            out.append("\\" + ch)
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif ch == "\t":
            out.append("\\t")
        elif 0x20 <= o < 0x7F:
            out.append(ch)
        elif o > 0xFFFF:
            o -= 0x10000
            out.append("\\u%04X\\u%04X" % (0xD800 + (o >> 10), 0xDC00 + (o & 0x3FF)))
        else:
            out.append("\\u%04X" % o)
    out.append('"')
    return "".join(out)


def main():
    cases = HAND + random_cases(160, 20261011) + long_cases(77)
    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(run_driver, cases))
    print("// Copyright (c) 2026 Tyler Stapler")
    print("// SPDX-License-Identifier: Elastic-2.0")
    print()
    print("// GENERATED by scripts/gen-logseq-union-golden.py from the real python merge driver. Do not edit.")
    print("package dev.stapler.stelekit.git.merge")
    print()
    print("internal data class GoldenCase(val name: String, val base: String, val local: String, val remote: String, val expected: String)")
    print()
    print("internal val logseqUnionGoldenCases: List<GoldenCase> = listOf(")
    for name, base, local, remote, expected in results:
        print(f"    GoldenCase({kt(name)}, {kt(base)}, {kt(local)}, {kt(remote)}, {kt(expected)}),")
    print(")")


main()
