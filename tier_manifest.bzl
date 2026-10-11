"""
tier_manifest.bzl — single source of truth for commonMain package tier membership.

Supersedes prose-only tier tables in ADR-001 / plan.md as the checkable
reference. See project_plans/commonmain-bazel-target-split/decisions/
ADR-001-tiered-target-architecture.md for the full rationale and
project_plans/commonmain-bazel-target-split/research/features.md for the
underlying Tarjan strongly-connected-component analysis.
"""

# Tier 1: zero cycle involvement, zero hub imports (ADR-001, research/features.md) —
# each package is a clean leaf with no path into the Tier-3 import cycle, so each
# gets its own independent Bazel target with no associates/cycle risk.
TIER_1_PACKAGES = [
    "benchmark",
    "docs",
    "error",
    "logging",
    "parsing",
    "resilience",
    "rtc",
    "service",
    "stats",
]

# Tier 2: clean non-cyclic packages (ADR-001) — pairwise splits are safe modulo 2
# confirmed cross-tier `internal`-visibility fixes (cache->performance,
# util->model/util->ui, both consumers living in Tier 3; see plan.md Epic 4.2).
TIER_2_PACKAGES = [
    "model",
    "cache",
    "coroutines",
    "util",
    "command",
    "clipboard",
    "flashcard",
    "loader",
    "outliner",
    "parser",
    "vault",
]

# Tier 3: forms one Tarjan SCC (research/features.md, ADR-001) — cannot be split
# further without breaking real import cycles; Bazel's structural cycle-rejection
# makes these 18 packages un-splittable without a prior dependency-inversion
# refactor, which ADR-001 defers to a separately-chartered follow-on project.
# Ships as one merged Bazel target (out of this plan's implementation scope —
# see plan.md Epic 5.1, ADR-only).
TIER_3_PACKAGES = [
    "asset",
    "calibration",
    "db",
    "domain",
    "editor",
    "export",
    "git",
    "llm",
    "migration",
    "performance",
    "platform",
    "repository",
    "search",
    "sections",
    "tags",
    "transfer",
    "ui",
    "voice",
]
