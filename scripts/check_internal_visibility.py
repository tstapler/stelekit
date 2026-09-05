#!/usr/bin/env python3
"""
check_internal_visibility.py — cross-package Kotlin `internal`-visibility audit.

Implements the exact two-pass methodology validated in
project_plans/commonmain-bazel-target-split/research/features.md (Iteration
2/"Fixed regex" for declaration enumeration, Iteration 4/"Strict check" for
cross-reference) — not the two earlier, noisier iterations that methodology
explicitly rejected (naive symbol regex, bare word-boundary co-occurrence).

Usage:
    check_internal_visibility.py <package-name> [--root <commonMain kotlin root>]

Exit codes:
    0 — clean: no cross-package usage of this package's `internal` symbols.
    1 — violation(s) found (see stdout for the per-usage adjacency list).
    2 — malformed invocation (bad package name / package directory not found).

Output contract (see plan.md Story 1.4.1's acceptance criteria):
    Clean:     "No cross-package internal usage found for <pkg> (0 declarations
                consumed outside <pkg>)."
    Violation: one line per hit —
                "<declaring_pkg>/<file>:<line>: internal symbol '<Symbol>' used
                by <consuming_pkg>/<file>:<line>"
               followed by a summary line:
                "<N> cross-package internal usages found across <M> files —
                see associates requirements above."
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

DEFAULT_ROOT = Path("kmp/src/commonMain/kotlin/dev/stapler/stelekit")

# ── Pass 1: internal-declaration enumeration ──────────────────────────────────
# Separate patterns per declaration kind, each receiver-skipping and
# nested-generic tolerant (research/features.md Iteration 2's fix over the
# naive Iteration 1 regex, which mis-extracted receiver types as the symbol
# name, e.g. `internal fun Double.roundTo(...)` -> "Double").

_MODIFIER = (
    r"(?:data|sealed|abstract|open|final|inline|value|annotation|expect|actual|"
    r"suspend|tailrec|operator|infix|external|crossinline|noinline|const|"
    r"lateinit)\s+"
)

_CLASSLIKE_RE = re.compile(
    rf"\binternal\s+(?:{_MODIFIER})*(?:class|interface|object|enum\s+class|typealias)\s+"
    r"(?P<name>[A-Za-z_][A-Za-z0-9_]*)"
)

_FUN_RE = re.compile(rf"\binternal\s+(?:{_MODIFIER})*fun\b(?P<rest>.*)")
_VAL_VAR_RE = re.compile(rf"\binternal\s+(?:{_MODIFIER})*(?:val|var)\b(?P<rest>.*)")


def _strip_leading_generic_params(rest: str) -> str:
    """Strip a leading `<T, R : Foo<Bar>>` type-param list (balanced <>)."""
    rest = rest.lstrip()
    if not rest.startswith("<"):
        return rest
    depth = 0
    for i, ch in enumerate(rest):
        if ch == "<":
            depth += 1
        elif ch == ">":
            depth -= 1
            if depth == 0:
                return rest[i + 1 :].lstrip()
    return rest  # unbalanced — give up, treat whole thing as unparseable


def _extract_name_after_optional_receiver(rest: str, terminators: str) -> str | None:
    """Given `[Receiver<Generic>.]name<terminator>`, return `name`.

    Nested-generic tolerant: tracks bracket depth so a `.` inside a receiver's
    own generic args (e.g. `Flow<Either<DomainError, T>>.catchDbError`) is not
    mistaken for the receiver/name separator.
    """
    # Only `<`/`>` add nesting depth here (generic type args on the receiver,
    # e.g. `Flow<Either<DomainError, T>>.catchDbError`) — `(` is always a real
    # terminator (the parameter list), never treated as an opening bracket.
    depth = 0
    last_top_level_dot = -1
    end = len(rest)
    for i, ch in enumerate(rest):
        if ch == "<":
            depth += 1
        elif ch == ">":
            if depth > 0:
                depth -= 1
        elif depth == 0:
            if ch == ".":
                last_top_level_dot = i
            elif ch in terminators:
                end = i
                break
    name_start = last_top_level_dot + 1
    candidate = rest[name_start:end].strip()
    if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", candidate):
        return candidate
    return None


@dataclass(frozen=True)
class Declaration:
    symbol: str
    file: Path
    line: int


def enumerate_internal_declarations(package_dir: Path) -> list[Declaration]:
    declarations: list[Declaration] = []
    for kt_file in sorted(package_dir.rglob("*.kt")):
        for lineno, line in enumerate(kt_file.read_text(errors="replace").splitlines(), start=1):
            stripped = line.strip()
            if stripped.startswith("//") or stripped.startswith("*"):
                continue
            if "internal" not in line:
                continue

            m = _CLASSLIKE_RE.search(line)
            if m:
                declarations.append(Declaration(m.group("name"), kt_file, lineno))
                continue

            m = _FUN_RE.search(line)
            if m:
                rest = _strip_leading_generic_params(m.group("rest"))
                name = _extract_name_after_optional_receiver(rest, terminators=" \t(")
                if name:
                    declarations.append(Declaration(name, kt_file, lineno))
                continue

            m = _VAL_VAR_RE.search(line)
            if m:
                rest = _strip_leading_generic_params(m.group("rest"))
                name = _extract_name_after_optional_receiver(rest, terminators=" \t:=")
                if name:
                    declarations.append(Declaration(name, kt_file, lineno))
                continue
    return declarations


# ── Pass 2: strict FQN cross-reference ────────────────────────────────────────


@dataclass(frozen=True)
class Usage:
    symbol: str
    declaring_file: Path
    declaring_line: int
    consuming_file: Path
    consuming_line: int


def find_wildcard_imports(root: Path, package: str) -> list[tuple[Path, int]]:
    """`import dev.stapler.stelekit.<package>.*` lines outside `package` itself —
    these evade the strict per-symbol FQN check and must be flagged for manual
    review (research/features.md Iteration 4 found 4 such wildcard imports)."""
    pattern = re.compile(rf"^\s*import\s+dev\.stapler\.stelekit\.{re.escape(package)}\.\*\s*$")
    hits: list[tuple[Path, int]] = []
    for kt_file in sorted(root.rglob("*.kt")):
        try:
            rel = kt_file.relative_to(root / package)
            is_self = True
        except ValueError:
            is_self = False
        if is_self:
            continue
        for lineno, line in enumerate(kt_file.read_text(errors="replace").splitlines(), start=1):
            if pattern.match(line):
                hits.append((kt_file, lineno))
    return hits


def find_cross_package_usages(
    root: Path, package: str, declarations: list[Declaration]
) -> list[Usage]:
    usages: list[Usage] = []
    package_dir = root / package
    for decl in declarations:
        import_re = re.compile(
            rf"^\s*import\s+dev\.stapler\.stelekit\.{re.escape(package)}\.{re.escape(decl.symbol)}\s*$"
        )
        for kt_file in sorted(root.rglob("*.kt")):
            try:
                kt_file.relative_to(package_dir)
                continue  # same-package usage doesn't need associates
            except ValueError:
                pass
            for lineno, line in enumerate(kt_file.read_text(errors="replace").splitlines(), start=1):
                if import_re.match(line):
                    usages.append(
                        Usage(decl.symbol, decl.file, decl.line, kt_file, lineno)
                    )
    return usages


def _consuming_package(root: Path, file: Path) -> str:
    return file.relative_to(root).parts[0]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package", help="package name under the commonMain root")
    parser.add_argument("--root", default=str(DEFAULT_ROOT), help="commonMain kotlin root")
    args = parser.parse_args()

    root = Path(args.root)
    package_dir = root / args.package
    if not package_dir.is_dir():
        print(f"usage error: package directory not found: {package_dir}", file=sys.stderr)
        return 2

    declarations = enumerate_internal_declarations(package_dir)
    usages = find_cross_package_usages(root, args.package, declarations)
    wildcard_hits = find_wildcard_imports(root, args.package)

    if not usages:
        print(
            f"No cross-package internal usage found for {args.package} "
            f"(0 declarations consumed outside {args.package})."
        )
        if wildcard_hits:
            print(
                f"NOTE: {len(wildcard_hits)} wildcard import(s) of "
                f"dev.stapler.stelekit.{args.package}.* found outside {args.package} — "
                "these evade the strict per-symbol check; manual review required:"
            )
            for f, ln in wildcard_hits:
                print(f"  {f}:{ln}")
        return 0

    files_touched = {u.consuming_file for u in usages}
    for u in sorted(usages, key=lambda u: (str(u.declaring_file), u.declaring_line)):
        consuming_pkg = _consuming_package(root, u.consuming_file)
        print(
            f"{u.declaring_file.relative_to(root)}:{u.declaring_line}: "
            f"internal symbol '{u.symbol}' used by "
            f"{consuming_pkg}:{u.consuming_file.relative_to(root)}:{u.consuming_line}"
        )
    print(
        f"{len(usages)} cross-package internal usages found across "
        f"{len(files_touched)} files — see associates requirements above."
    )
    if wildcard_hits:
        print(
            f"NOTE: {len(wildcard_hits)} additional wildcard import(s) of "
            f"dev.stapler.stelekit.{args.package}.* found outside {args.package} — "
            "these evade the strict per-symbol check above; manual review required:"
        )
        for f, ln in wildcard_hits:
            print(f"  {f}:{ln}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
