# ADR-002: Extraction Tooling — Starlark Macro + Bespoke Script + Kibitzer/`bazel query`

**Status**: Accepted
**Date**: 2026-09-04
**Deciders**: Tyler Stapler (via SDD Phase 3 planning)

## Context

Three distinct tooling needs were identified in `requirements.md`'s Scope section and evaluated
in `research/build-vs-buy.md`:

1. A mechanism to standardize per-package `BUILD.bazel` boilerplate (`kotlinc_opts`, Compose/
   serialization plugin selection, `associates` wiring, visibility).
2. A pre-migration check for cross-package Kotlin `internal`-visibility usage.
3. A cycle-safety check confirming no import cycle is introduced when a new target boundary is
   drawn.

## Decision

### 1. Starlark macro — build in-repo, do not adopt a generator/Gazelle-style tool

**Chosen**: a hand-written Starlark macro, `stelekit_kt_common_library`, in a new file
`//:kotlin_common.bzl` (repo root — this repo has **zero** existing `.bzl` files;
`find . -name '*.bzl' -not -path './vendor/*' -not -path './.claude/worktrees/*'` returns
nothing, confirmed in `research/stack.md`). Root placement matches where the macro's own
dependencies live: `//:kmp_jvm_kotlinc_opts`, `//:kmp_android_kotlinc_opts`,
`//:compose_compiler_plugin`, `//:serialization_compiler_plugin` are all defined in the repo-root
`BUILD.bazel` today (verified by reading it directly).

**Alternative rejected**: a Gazelle-style codegen tool (`bazel-contrib/bazel-gazelle`,
`cirruslabs/bazel-project-generator`). `pitfalls.md` cites Uber running Gazelle across a
~1M-file/>10k-commits-week monorepo with a CI "no drift" gate — real precedent for *why*
hand-maintained BUILD files don't scale, but not evidence this repo is at that scale yet
(~29 new targets in this plan's scope, not thousands). No existing Kotlin/Bazel Gazelle
extension was found with a working precedent matching this repo's `common_srcs`/Compose/
serialization/`associates`-to-a-shared-core shape (`build-vs-buy.md`). Building a generator here
would be solving a problem this migration doesn't yet have, at the cost of a new build
dependency and its own maintenance burden — exactly the "tooling scope creep" rabbit hole
requirements.md flags. Note this leaves `deps` list authoring itself unautomated (a real gap,
correctly flagged in `pitfalls.md`) — out of scope for this plan; each package's `deps` list is
derived once via the same `grep`/`ast-grep` edge-extraction method `research/features.md` already
used, and hand-written into that package's `BUILD.bazel`.

### 2. Cross-package `internal`-visibility audit — bespoke `ast-grep`/Python script, not Konsist/detekt

**Chosen**: a bespoke two-pass script, `scripts/check_internal_visibility.py` (repo-root
`scripts/`, matching this repo's existing convention of flat Python/Bash scripts there —
e.g. `scripts/analyze-perf.py`, `scripts/benchmark-local.sh`). Pass 1: `sg`/regex over
`kmp/src/commonMain/kotlin/**/*.kt` to enumerate `internal` declarations per declaring package.
Pass 2: strict `import dev.stapler.stelekit.<pkg>.<Symbol>` FQN-line matching (not bare
identifier co-occurrence) against every other package's files, per `research/features.md`'s own
hard-won methodology (iterated 3 versions to kill a ~70% false-positive rate from naive
identifier search — stdlib collisions like `kotlinx.coroutines.flow.Flow`, receiver-type
mis-extraction, even one code-comment false positive). Output shape: a **per-package-pair
adjacency list** (declaring package, symbol, consuming package, file count), not a per-package
boolean — `pitfalls.md` confirms `associates`/`-Xfriend-paths` grants access only to *directly*
listed targets, not transitively, so the audit's consumers (the humans writing `BUILD.bazel`
`associates` lists) need the exact pairs, not an aggregate "package X has some cross-package
internal usage" flag.

**Alternatives rejected**:
- **Konsist** — purpose-built for exactly this rule shape, but runs as a JUnit/Kotest test
  requiring a Gradle test-runtime entry point. This repo's stated direction is Bazel-canonical
  with Gradle kept only for iOS/screenshot-tests/benchmarks (`CLAUDE.md`); standing up a new
  Gradle test target purely to run a one-time, pre-migration audit is a cost/frequency mismatch.
  Reconsider only if the org later wants a *standing* "no internal leakage" CI gate — a different,
  separate decision.
- **detekt custom rule** — this repo already runs detekt (live infra, not a new dependency), but
  a rule that resolves "does this reference point at an `internal` symbol declared in a
  *different* package" needs semantic type resolution (binding-context rules with a resolved
  classpath), not PSI/AST-only matching — detekt's existing custom-rule mechanism here
  (`buildSrc.jar`) doesn't reach that. Over-built for a throwaway-after-first-use audit.
- **`kotlinc -Xexplicit-api=strict` / compile-and-read-errors** — real and cheap, but
  structurally circular as a *pre-migration* check: it requires the split to already exist to
  produce the diagnostic. Retained as the **post-hoc backstop**, not the pre-migration gate (see
  Migration Plan in plan.md: every package extraction is verified by an actual `bazel build`,
  which will fail fast and unambiguously on any internal-visibility miss the script didn't catch).

### 3. Import-cycle detection — kibitzer for planning (already done), `bazel query`/Bazel's own graph rejection for CI

**Chosen, with an explicit CI-invocation answer** (the open question requirements.md/this task
explicitly flagged): `mcp__kibitzer__architecture_assessment`'s `import-cycles` checker is an
**interactively-invoked planning tool**, run by a human or an agent session during research/
planning (already done twice: once in `research/features.md`'s verification pass, once in
`research/build-vs-buy.md`'s tool-evaluation pass). **It is not wired into CI** and should not
be: kibitzer is a personal tool (`~/code/github.com/tstapler/kibitzer`) invoked here via an MCP
server available to this interactive session — GitHub Actions CI runners have no MCP server and
no kibitzer binary installed, and there is no requirement or budget in this plan to add one.

CI-time enforcement instead relies on two backstops, neither requiring new tooling:
- **Bazel's own target-graph analysis phase natively rejects cycles.** If a Tier-2 package's
  `deps` accidentally introduced a cycle (e.g. a future edit makes `:cache` depend on
  `:performance` while `:performance`/Tier 3 still depends on `:cache`), `bazel build`/`bazel
  test` fails outright at the analysis phase with a clear cycle error — this is a hard Bazel
  invariant, not something that needs re-implementing.
- **Optional, cheap regression check**: `bazel query "somepath(//kmp/src/commonMain/kotlin/dev/
  stapler/stelekit/<pkgA>, //kmp/src/commonMain/kotlin/dev/stapler/stelekit/<pkgB>)"` for the
  specific pairs the cycle analysis flagged as risky (e.g. `cache`↔`performance` post-extraction)
  gives a clearer, targeted error message than waiting for a generic Bazel cycle failure — a
  `bazel query`-based smoke test is included as an explicit task in the Tier 2 epic (plan.md
  Epic 4.2) for exactly the two cross-tier `internal`-usage pairs, precisely because `build-vs-
  buy.md` already recommends this as the "cheap, independent post-hoc regression check."

**Alternative rejected**: writing a bespoke cycle-detection script. Kibitzer already works,
already ran successfully against this exact codebase, and duplicating it would be pure
tooling-scope creep with no capability gain.

**Reconciling the discrepancy between research passes**: `research/build-vs-buy.md`'s kibitzer
run (unscoped, full `commonMain`, 615 files) reported **0** `[import-cycle]` findings out of 803
total findings — directly contradicting `research/features.md`'s later run (same unscoped
`commonMain` scope, 615 files, 744 findings) which found **2** `import-cycle` findings including
the 18-package (not 17, see ADR-001) cycle. This ADR does not resolve which run is "correct" —
both cannot be right for the same input at the same commit. However, this does not block this
ADR's tooling decision (kibitzer is chosen for *planning*, not as the sole source of truth for
whether a cycle exists — the manual Tarjan-over-verified-edge-list analysis in
`research/features.md` independently reaches the same 18-package conclusion via a method that
does not depend on kibitzer at all) and does not block ADR-001's architecture decision (which
rests on the Tarjan result, cross-checked, not solely on kibitzer). It **does** mean kibitzer's
own reliability/determinism across invocations is not yet fully understood — flagged as an
Unresolved Question in plan.md, with a recommendation to re-run it once, fresh, immediately
before Phase 5 implementation begins as a cheap confirmation, rather than trusting either
historical run's kibitzer output as-is.

## Consequences

- Three small, single-purpose tools/configs enter the repo: `kotlin_common.bzl` (macro),
  `scripts/check_internal_visibility.py` (audit script), `.claude/inspect.json` (kibitzer
  config, committed for reproducibility of the planning-time cycle check, not for CI use).
- No new runtime/test dependency (no Konsist, no Gradle test target, no detekt binding-context
  rule) is added to the build.
- The `deps` list per package remains hand-authored (not generated) — an explicit, accepted gap,
  not silently assumed away.
