# Implementation Plan: commonmain-bazel-target-split

**Feature**: Split `commonMain`'s monolithic Bazel `common_srcs`/`srcs` inclusion into a
`:platform_core` target plus per-package targets for the 20 packages outside the verified
18-package import cycle, with the cycle's 18 packages (87.8% of files) shipped as one merged
target for now.
**Date**: 2026-09-04
**Status**: Ready for implementation
**ADRs**: ADR-001 (tiered target architecture + Tier-3 merge decision), ADR-002 (tooling choice)

---

## Domain Glossary

| Term | Definition | Notes |
|------|-----------|-------|
| `commonMain` monolith | Today's single compilation unit: all 609 `.kt` files under `kmp/src/commonMain/kotlin/dev/stapler/stelekit/`, textually included via `common_srcs`/`srcs` in both `android_main` and `jvm_main_lib` | The thing being split |
| `:platform_core` | New `filegroup` of the ~109 files carrying real `expect`/`actual` declarations (`model`, `platform`, `cache`, `coroutines`, `util`, `performance` in full, plus the expect/actual-bearing subset of `db`, `llm`, `sections`, `transfer`, `ui`) | Stays textually included via `common_srcs` in both platform targets — not itself a separately-cached compile unit. `kmp/src/commonMain/kotlin/BUILD.bazel:13` cites "ADR-005" for this double-compilation trade-off, but no such ADR exists in `docs/adr/` (ADR-005 there is `ADR-005-shadowfilecache-per-instance-fresh-flag.md`, an unrelated topic) — this is a pre-existing dangling reference in the repo's own docstring, not one of this project's ADRs; the constraint itself (verified independently via `research/architecture.md`'s direct reading of the BUILD.bazel comment) still holds regardless of the broken citation |
| Tier 1 | 9 zero-risk packages: `benchmark`, `docs`, `error`, `logging`, `parsing`, `resilience`, `rtc`, `service`, `stats` (24 files) | Zero cycle involvement, zero hub imports — pilot slice |
| Tier 2 | 11 clean non-cyclic packages: `model`, `cache`, `coroutines`, `util`, `command`, `clipboard`, `flashcard`, `loader`, `outliner`, `parser`, `vault` (50 files) | Clean pairwise splits, modulo 2 cross-tier `internal`-usage fixes |
| Tier 3 | 18 packages forming one Tarjan SCC: `asset`, `calibration`, `db`, `domain`, `editor`, `export`, `git`, `llm`, `migration`, `performance`, `platform`, `repository`, `search`, `sections`, `tags`, `transfer`, `ui`, `voice` (535 files, 87.8% of `commonMain`) | See ADR-001 for the 17-vs-18 count correction. Ships as one merged target — not split further in this plan |
| SCC (strongly connected component) | Graph-theory term: a maximal set of nodes where every node can reach every other node via directed edges | Tier 3 is exactly one SCC per Tarjan's algorithm run in `research/features.md` |
| `stelekit_kt_common_library` | The proposed shared Starlark macro wrapping `kt_jvm_library`/`kt_android_library` | Lives in new file `//:kotlin_common.bzl` — **authoritative filename**; `research/architecture.md`'s illustrative snippets say `kt_common.bzl`, a naming inconsistency in that research doc only, not adopted here |
| `tier_manifest.bzl` | New file defining `TIER_1_PACKAGES`/`TIER_2_PACKAGES`/`TIER_3_PACKAGES` as Starlark lists — single source of truth for tier membership | See Story 1.1.2; supersedes prose-only tier tables in ADR-001/this plan as the checkable reference |
| `:platform_core_srcs` | The concrete `filegroup` this plan actually creates for the ~109 expect/actual-bearing files, wired into `common_srcs` of both platform targets | See Epic 1.6 — closes the adversarial review's BLOCKER that no task ever created this artifact |
| `check_internal_visibility.py` | The bespoke two-pass cross-package `internal`-usage audit script | Lives at `scripts/check_internal_visibility.py`; outputs a per-package-pair adjacency list, not a boolean |
| `.claude/inspect.json` | Kibitzer's config file, enabling the `import-cycles` and `coupling` `architecture_checker`s | Committed for reproducibility of the *planning-time* cycle check — not wired into CI (see ADR-002) |
| `associates` | rules_kotlin attribute granting friend-visibility (`-Xfriend-paths`) — access to a target's `internal` members | Grants access only to *directly* listed targets, not transitively (pitfalls.md) |
| `common_srcs` | rules_kotlin attribute (added by this repo's `third_party/patches/rules_kotlin_kmp.patch`) marking a subset of `srcs` as the expect/metadata side of a KMP compilation, emitting `-Xcommon-sources` | Only `:platform_core` and the Tier-3 merged target use this — Tier 1/2 packages never need it |
| `kt_srcs` filegroup | The existing `glob(["**/*.kt"])` filegroup in `kmp/src/commonMain/kotlin/BUILD.bazel` that both platform targets pull the whole monolith from today | Must gain an `exclude` entry per package as it's extracted (Option 1, see architecture.md) |
| `WorkerKey` | Bazel's persistent-worker identity: one resident worker pool per (mnemonic, flag-set, tool environment) combination | Each distinct Compose/serialization/plain plugin combination among the new targets is its own `WorkerKey` |
| Go/no-go checkpoint | The explicit decision point after Tier 1 lands in CI: re-profile, compare against the 71s baseline, decide whether to proceed to Tier 2 | Per requirements.md's Risk Control section |
| Tier-3 merged cluster target | The single Bazel target (e.g. `//kmp/src/commonMain/kotlin:commonmain_cycle_cluster`) absorbing all 18 Tier-3 packages | Decision recorded in ADR-001; **not implemented** in this plan's Phase 5 scope — see Epic 5.1 |
| Blast radius | The set of files/packages whose visibility must change (become `public`, or gain an `associates` entry) when a given package is extracted | Output of `check_internal_visibility.py` |

---

## Pattern Decisions

| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|-----------|---------------|--------|---------------------|--------|
| Overall target architecture | Platform-core + tiered per-package targets (Tier 1, Tier 2 independent; Tier 3 merged) | ADR-001, `research/architecture.md`, `research/features.md` | (A) Single flat target per tier | Defeats the per-package invalidation-scope success metric — any file change in a 9-package bundle still invalidates the whole bundle |
| Overall target architecture | (as above) | ADR-001 | (B) Recursive sub-package splitting (to `ui.gallery`/`editor.blocks` granularity) | Multiplies target count past 100+, reproducing rules_kotlin #1653's O(N²) analysis-phase risk at greater scale than even the original 18-package ask; exceeds Large (3-6wk) appetite |
| Tier 3 handling | (a) Ship as one merged Bazel target now | ADR-001 | (b) Dependency-inversion refactor first (break `platform→ui`, `db→repository`, etc.) | Real architecture/semantic risk to production code, not a build-tooling change; deserves its own separately-chartered project/appetite, not a rider on this migration |
| BUILD-file boilerplate | Hand-written Starlark macro (`stelekit_kt_common_library`) | ADR-002, `research/build-vs-buy.md` | Gazelle-style codegen (`bazel-gazelle`, `cirruslabs/bazel-project-generator`) | No existing Kotlin/Bazel Gazelle extension fits this repo's `common_srcs`+Compose+serialization+`associates` shape; solving a scale problem this migration (~29 targets) doesn't have yet |
| `internal`-usage audit | Bespoke `ast-grep`/Python two-pass script | ADR-002, `research/build-vs-buy.md` | Konsist | JUnit/Kotest-runtime tool requiring a new Gradle test target — contradicts Bazel-canonical direction; wrong cost/frequency profile for a one-off audit |
| `internal`-usage audit | (as above) | ADR-002 | detekt custom rule | Needs binding-context/type resolution this repo's existing custom-rule mechanism (`buildSrc.jar`) doesn't provide; over-built for a throwaway-after-first-use audit |
| Import-cycle detection | kibitzer (planning-time, interactive) + Bazel's native cycle rejection / `bazel query somepath` (CI-time) | ADR-002, `research/build-vs-buy.md` | Bespoke cycle-detection script | Kibitzer already works, already verified against this exact codebase; duplicating it is pure scope creep |
| Import-cycle detection (CI path) | Rely on Bazel's structural cycle rejection + optional `bazel query somepath` smoke test | ADR-002 | Wiring kibitzer/MCP into CI | GH Actions runners have no MCP server or kibitzer binary; no budget/requirement to add one |

---

## Migration Plan

**This is a migration of build-graph structure, not application data — but the same rigor
applies.**

### Sequencing across tiers

1. **Tooling + prerequisites** (Phase 1) — must land first; every later task depends on the
   macro, the audit script's findings, and the `.bazelrc` worker cap existing.
2. **Tier 1 pilot** (Phase 2) — 9 packages, extracted one PR per package (or small grouped PRs),
   each independently CI-verified.
3. **Go/no-go checkpoint** (Phase 3) — re-profile; a human decision gate before Tier 2 begins.
4. **Tier 2** (Phase 4) — 11 packages, same per-package PR pattern, plus the 2 cross-tier
   `associates` fixes.
5. **Tier 3 decision, not implementation** (Phase 5) — ADR only; the merged target itself is
   future work, explicitly out of scope for this plan's implementation phase.

### Reversibility per package

Each package's extraction is a self-contained diff: a new `BUILD.bazel` under
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/<pkg>/`, one new `exclude` line in the root
`kt_srcs` filegroup's glob, and (if the package is a friend-visibility consumer) one or more
`associates` entries added to test targets. **Rollback = revert that one commit/PR.** No other
package's migration state is touched. This satisfies requirements.md's Risk Control constraint
directly.

### Can a half-migrated state ever produce a broken build?

Yes, in exactly one specific way, identified in `research/architecture.md`: if the new
per-package `BUILD.bazel` (declaring `:pkg`) and the `kt_srcs` glob's `exclude` entry for that
package land in **separate** commits, the package's files are claimed by two compilation units
simultaneously (the still-including monolith glob, and the new target) — this produces a
**fail-fast** duplicate-top-level-declaration compile error in whichever target ends up
depending on both, not silent corruption. **Mandatory rule for every task in Phases 2 and 4**:
the `BUILD.bazel` creation and the `kt_srcs` exclude edit are the same commit/PR, never
sequenced. This is enforced structurally in every per-package task below (both edits are listed
in the same task's Files list).

### Rollback procedure per tier

- **Tier 1 / Tier 2 (per package)**: `git revert` the single package's extraction commit. The
  monolith's glob re-matches those files immediately (no `exclude` line survives the revert), and
  the new `BUILD.bazel` is gone — back to today's state for that package with no cross-package
  impact.
- **Tier 3**: not implemented in this plan — no rollback procedure needed yet. ADR-001 records
  the merge-vs-refactor decision only.

---

## Observability Plan

This is build tooling, not a running service — "observability" means CI signal, not
runtime metrics/alerts:

- **Logs**: Bazel `--profile=<file>` traces, captured before and after each tier, analyzed via
  the same jq top-N methodology requirements.md's Success Metrics section already specifies.
- **Metrics**: CI job wall-clock time (per job, not just pipeline total) and CI runner memory
  headroom (GitHub Actions' own resource-usage reporting, or a `free -h`/`ps` sample step added
  to the workflow during the pilot) — watched explicitly per rollout slice, not just red/green,
  per the persistent-worker-OOM precedent in `research/pitfalls.md`.
- **Alerts**: none new (no running service) — the "alert" equivalent is the go/no-go checkpoint
  itself (Phase 3) and a human reading the before/after profile diff.

---

## Risk Control

- **Staged rollout, not feature-flagged** (a build graph has no runtime toggle): Phase 1 tooling
  → Phase 2 Tier 1 (CI-verified per package) → Phase 3 go/no-go → Phase 4 Tier 2 (only if Phase 3
  passes) → Phase 5 Tier 3 ADR (decision only).
- **Rollback**: per-package, per Migration Plan above — concrete for every task in Phases 2/4.
- **Explicit go/no-go after Tier 1** (Phase 3, Epic 3.1): if the re-profiled `//kmp:android_app
  --config=android` / `//kmp:jvm_tests` don't show per-package invalidation-scope improvement,
  Phase 4 (Tier 2) does not start — re-evaluate the approach (e.g. was `:platform_core` drawn too
  wide, is a Tier 1 package's `deps` edge accidentally pulling in the full monolith) before
  spending Tier 2's larger PR count.
- **CI-runner-memory watch, every slice**: per the worker-cap epic (Phase 1, Epic 1.3) and the
  `pitfalls.md` precedent — a slice is not "done" on green alone; the CI job's peak memory must
  be checked against the 16GB runner budget explicitly.

---

## Unresolved Questions

- [ ] **kibitzer's two runs disagree** (`build-vs-buy.md`: 0 cycle findings unscoped;
  `features.md`: 2 cycle findings unscoped, same commit, same scope) — blocks confidence in
  Tier 3's exact membership list, though not this plan's Phase 1-4 work (Tier 1/2 are untouched
  by the cycle either way). Owner: whoever starts Phase 5 implementation — re-run
  `mcp__kibitzer__architecture_assessment` fresh, immediately before writing the first Tier 3
  merged-target `BUILD.bazel`, and re-run the manual Tarjan script from `research/features.md`
  against the then-current `main` (packages may have shifted since 2026-09-04). Blocks Epic 5.1's
  Tier-3 ADR only if the recount changes the 18-package list.
- [ ] **Exact intra-Tier-1/Tier-2 dependency edges are not fully enumerated in research** (e.g.
  does `parsing` import `error`? does `outliner` import `parser`?) — `research/features.md`'s
  edge list only prints edges *from* packages with outgoing edges to hub/hub-touching packages;
  Tier 1/2 packages' edges to each other were not separately tabulated. Owner: the engineer
  extracting each package — the first task in every per-package story below is "derive this
  package's exact `deps` via `grep`," not an assumption from this plan. Blocks Story 2.2.x/4.1.x
  task (a) for every package, resolved trivially per-package as each is reached.
- [ ] **`associates` with two simultaneous friend targets is unverified in this repo's pinned
  rules_kotlin 2.3.20** — blocks Story 2.1.1 (hands-on verification task) specifically; this is
  the first task of Phase 2, before any real package depends on the answer.
- [ ] **`rules_kotlin`'s O(N²) `kt_android_library` analysis-phase fix (PR #1652) is not
  confirmed present in any specific post-2.3.20 release** (pitfalls.md: "INFERRED from release
  dates; not independently verified by diffing the tag") — blocks Epic 1.2's exact target
  version pin; Task 1.2.1a includes verifying the fix is actually present in the chosen version
  before pinning, not just picking the latest tag by date.
- [ ] **Whether any zero-risk/Tier-2 package's files are referenced by Android resources/manifest
  fragments** — `pitfalls.md` flags this as a class of Bazel Android-splitting pitfall but rates
  it "likely low" here since these are Kotlin compilation units, not `android_library` targets
  with their own `res/`. Owner: Task 1.1.1a (a one-line confirmation grep) closes this before
  Tier 1 begins.

---

## Dependency Visualization

```
Phase 1: Tooling & Prerequisites
  Epic 1.1 (Starlark macro + tier_manifest.bzl)  ───┐
  Epic 1.2 (rules_kotlin upgrade) ─────────────────┤
  Epic 1.3 (.bazelrc worker cap)───────────────────┤  all can run in parallel;
  Epic 1.4 (internal-visibility audit script) ─────┤  all MUST complete before Phase 2 starts
  Epic 1.5 (kibitzer config commit) ───────────────┤
  Epic 1.6 (define + wire :platform_core) ─────────┤  (blocks Phase 4's platform-core-relationship
                                                    │   checks specifically — see Story 1.6.3)
  Epic 1.8 (CI duplicate-source-claim check) ──────┘
                    │
                    ▼
  Epic 1.7 (pre-Tier-1 gates: androidApp audit +
            rules_kotlin ABI-stability spike) ──── HARD GATE: Epic 2.2 does not start
                    │                               until Story 1.7.2 records PASS
                    ▼
Phase 2: Tier 1 Pilot (9 packages)
  Epic 2.1 (associates dual-target verification) ─── MUST complete before Epic 2.2
                    │
                    ▼
  Epic 2.2 (per-package extraction ×9)
                    │
                    ▼
  Epic 2.3 (test source-set wiring pattern)
                    │
                    ▼
Phase 3: Go/No-Go Re-profiling
  Epic 3.1 ─── GATE: Phase 4 does not start unless this passes
                    │
                    ▼
Phase 4: Tier 2 Rollout (11 packages)
  Epic 4.1 (per-package extraction ×11)
                    │  GATE: Epic 4.2's `model`/`util` fixes need Epic 4.1's
                    │  extracted targets (Stories 4.1.2-4.1.4) to already exist
                    ▼
  Epic 4.2 (cross-tier associates fixes: cache→performance, util→model/ui)
                    │
                    ▼
  Epic 4.3 (test source-set wiring pattern, Tier 2)
                    │
                    ▼
Phase 5: Tier 3 Decision (ADR only — no code)
  Epic 5.1 (finalize ADR-001's tier-3 decision, mark implementation out of scope)
```

---

## Common Per-Package Extraction Recipe

**Written once, in full, here — Epic 2.2 (Phase 2, Tier 1) and Epic 4.1 (Phase 4, Tier 2) both
point back to this section rather than each re-stating it, so this is the one place to read the
full recipe rather than flipping between sections on each of the ~20 per-package iterations.**

For every package being extracted into its own Bazel target:

1. **Derive the package's exact `deps`** via
   `grep -rhoE 'import dev\.stapler\.stelekit\.[a-zA-Z0-9_]+' kmp/src/commonMain/kotlin/dev/
   stapler/stelekit/<pkg> --include='*.kt' | sort -u` — do not assume from this plan's tier
   classification alone (intra-tier edges weren't fully enumerated in research). Optionally run
   this as part of `scripts/check_extraction_readiness.sh <pkg>` (Story 1.4.2), which wraps this
   step and steps 2-3 in one command.
2. **Bidirectional `internal`-visibility check** — run `scripts/check_internal_visibility.py`
   (Epic 1.4) with `<pkg>` as **both** the declaring package and the consuming package: does
   anything outside `<pkg>` use an `internal` symbol `<pkg>` declares, and does `<pkg>` use an
   `internal` symbol some other, still-monolithic package declares? Record any hit as an
   `associates` requirement before writing `BUILD.bazel`.
3. **Compose/serialization grep** — `grep -rl '@Composable\|@Serializable'
   kmp/src/commonMain/kotlin/dev/stapler/stelekit/<pkg>/` and set the macro's
   `extra_plugins`/`uses_compose`/`uses_serialization` params accordingly. Do not assume
   plugin-free.
4. **Write `BUILD.bazel` via the macro** — `kmp/src/commonMain/kotlin/dev/stapler/stelekit/<pkg>/
   BUILD.bazel` using `stelekit_kt_common_library`, with `associates`/plugin params set per steps
   2-3, **and a one-line comment above the target citing `tier_manifest.bzl` and/or ADR-001 for
   this package's tier rationale** — matching this repo's existing convention
   (`kmp/src/commonMain/kotlin/BUILD.bazel:13`'s "See ADR-005..." docstring, per this plan's
   Domain Glossary) — e.g. `# Tier 1: zero cycle involvement, zero hub imports (ADR-001,
   tier_manifest.bzl:TIER_1_PACKAGES).`
5. **Glob-exclude edit, same commit** — add `"dev/stapler/stelekit/<pkg>/**"` to `kt_srcs`'s glob
   `exclude` list in `kmp/src/commonMain/kotlin/BUILD.bazel` — **same commit as step 4**, never
   sequenced separately (Migration Plan's mandatory rule; see "Can a half-migrated state ever
   produce a broken build?" above).
6. **Isolated build** — `bazel build //kmp/src/commonMain/kotlin/dev/stapler/stelekit/<pkg>:<pkg>`
   for a fast, localized failure signal.
7. **Full-graph build/test** — `bazel build //kmp:desktop_app //kmp:android_app --config=android`
   and `bazel test //kmp:jvm_tests //kmp:business_tests`, to confirm no duplicate-declaration or
   missing-dependency error.
8. **Confirm the tier_manifest.bzl/BUILD.bazel rationale comments agree** — the package still
   appears in the tier list its Step 4 comment claims (catches the Rabbit Holes-flagged case where
   a mid-extraction visibility/cycle finding forced a package into a different tier than
   originally planned, without the BUILD.bazel comment being updated to match).

---

## Phase 1: Tooling & Prerequisites

### Before starting: check for in-flight concurrent edits to test BUILD files

**This is a mandatory pre-flight check, not a suggestion** — whoever begins implementation of this
plan (any Phase, but especially Phases 2/4's test source-set wiring, Epics 2.3/4.3) must first run
`gh pr view 306 --repo tstapler/stelekit` (or search `gh pr list --repo tstapler/stelekit --search
"is:open commonTest BUILD.bazel"` if #306 has since merged/closed and been superseded by a
follow-up PR) and re-read the **current, live** state of
`kmp/src/commonTest/kotlin/BUILD.bazel` directly from the repo — not from this plan's or
`research/`'s snapshot of it. As of this plan's 2026-09-04 writing, PR #306 ("test: relocate
pure-logic migration/search tests to commonTest") is **OPEN** and actively editing exactly that
file, which Epic 1.x/2.x's research snapshots describe. If #306 (or its successor) is still open
when implementation starts, treat its diff as the authoritative in-flight state to rebase onto —
do not trust a research doc's file listing/line numbers for `commonTest/kotlin/BUILD.bazel`
without confirming they still match `main` plus that PR's pending changes.

### Epic 1.1: Shared Starlark macro

**Goal**: A single macro any per-package `BUILD.bazel` can call instead of hand-rolling
`kt_jvm_library`/`kt_android_library` boilerplate.

#### Story 1.1.1: Create `stelekit_kt_common_library` macro
**As a** engineer extracting a package into its own Bazel target, **I want** a macro that
defaults `kotlinc_opts`, plugin selection, and `associates`/visibility wiring, **so that** each
new package's `BUILD.bazel` is ~10 lines instead of duplicating the ~50-line pattern seen in
`android_main`/`jvm_main_lib`.

**Acceptance Criteria**:
- The macro accepts `name`, `srcs`, `platform` (`"jvm"` or `"android"`), `deps`, `associates`
  (default `[]`), **`extra_plugins`** (default `[]`, a `label_list` of compiler-plugin targets
  spliced directly into the underlying rule's `plugins`), `visibility` (default
  `["//kmp:__subpackages__"]`). **Design correction (architecture review concern #1 — open/closed
  violation)**: rather than hardcoding two boolean flags (`uses_compose`/`uses_serialization`)
  that would require *editing the macro itself* for a third future plugin, the macro's real
  extensibility point is `extra_plugins` — a caller needing Compose or serialization support can
  pass `extra_plugins = ["//:compose_compiler_plugin"]` directly. `uses_compose = True` /
  `uses_serialization = True` remain as optional sugar parameters (default `False`) that each
  append their corresponding well-known plugin label into the effective `extra_plugins` list
  before splicing, purely for ergonomics on the two plugins this migration needs today. A future
  third plugin becomes a new call-site argument, never a macro edit.
  - *Given* the macro file `kotlin_common.bzl` exists at repo root, *When* `kmp/src/commonMain/
    kotlin/dev/stapler/stelekit/stats/BUILD.bazel` calls
    `stelekit_kt_common_library(name = "stats", srcs = glob(["*.kt"]), platform = "jvm", deps =
    ["@maven//:org_jetbrains_kotlin_kotlin_stdlib"])` with no `extra_plugins`/`uses_compose`/
    `uses_serialization` args, *Then* the expanded `kt_jvm_library` target has `plugins = []`
    (neither compiler plugin spliced in) and `kotlinc_opts = "//:kmp_jvm_kotlinc_opts"` (the
    default for `platform = "jvm"`).
- The macro rejects (via a `fail()`) any call passing a target in both `deps` and `associates`
  simultaneously, mirroring the `common_test_fixtures` precedent's documented rules_kotlin
  constraint.
  - *Given* a hypothetical future `BUILD.bazel` calls
    `stelekit_kt_common_library(name = "x", deps = [":platform_core"], associates =
    [":platform_core"], ...)`, *When* Bazel loads that package, *Then* the macro's `fail()` call
    raises a clear Starlark error naming the offending target, before Bazel attempts the
    (rules_kotlin-rejected) underlying rule invocation.
**Files**: `kotlin_common.bzl` (new)

##### Task 1.1.1a: Write the macro skeleton and platform-selection logic (~4 min)
- Create `kotlin_common.bzl` with a `load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_library")` and
  `load("@rules_android//android:rules.bzl", "kt_android_library")` (verify exact load paths
  against `kmp/src/androidMain/kotlin/BUILD.bazel`'s existing `load(...)` line first).
- Implement the `platform == "jvm"` vs `"android"` branch selecting `kt_jvm_library` vs
  `kt_android_library` and the matching default `kotlinc_opts` label.
- Files: `kotlin_common.bzl`

##### Task 1.1.1b: Implement plugin-splicing and the deps/associates guard (~3 min)
- Add the `extra_plugins` `label_list` param (default `[]`), spliced directly into the underlying
  rule's `plugins` list; add `uses_compose`/`uses_serialization` boolean sugar params that each
  conditionally append their well-known plugin label (`//:compose_compiler_plugin` /
  `//:serialization_compiler_plugin`) into the effective `extra_plugins` list before splicing —
  this keeps the macro open to a future third plugin via a new `extra_plugins` entry, with no
  macro edit required (architecture review concern #1).
- Add the `fail()` guard checking for overlap between `deps` and `associates`.
- Files: `kotlin_common.bzl`

##### Task 1.1.1c: Smoke-test the macro against the `stats` package (~3 min)
- Write `kmp/src/commonMain/kotlin/dev/stapler/stelekit/stats/BUILD.bazel` using the new macro
  (this doubles as Story 2.2.1's first task — do not duplicate, just confirm the macro compiles
  a real target before Phase 2 begins).
- Run `bazel build //kmp/src/commonMain/kotlin/dev/stapler/stelekit/stats:stats` and confirm it
  succeeds.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/stats/BUILD.bazel` (created here,
  reused/finalized in Epic 2.2)

#### Story 1.1.2: Commit `tier_manifest.bzl` as the single source of truth for tier membership
**As a** engineer (or future audit/CI script), **I want** `TIER_1_PACKAGES`/`TIER_2_PACKAGES`/
`TIER_3_PACKAGES` defined once in a checked-in Starlark file, **so that** tier membership has one
answer, not two documents (ADR-001's prose and this plan's tables) that can silently drift if the
Rabbit Holes-flagged post-audit tier revision happens (architecture review concern #2).

**Acceptance Criteria**:
- *Given* ADR-001's tier lists, *When* `tier_manifest.bzl` is created at repo root, *Then* it
  defines exactly `TIER_1_PACKAGES = ["benchmark", "docs", "error", "logging", "parsing",
  "resilience", "rtc", "service", "stats"]`, `TIER_2_PACKAGES = ["model", "cache", "coroutines",
  "util", "command", "clipboard", "flashcard", "loader", "outliner", "parser", "vault"]`, and
  `TIER_3_PACKAGES = ["asset", "calibration", "db", "domain", "editor", "export", "git", "llm",
  "migration", "performance", "platform", "repository", "search", "sections", "tags", "transfer",
  "ui", "voice"]`, matching ADR-001 verbatim.
- Every per-package `BUILD.bazel` written in Epic 2.2/4.1 is expected to be consistent with
  whichever list its package appears in — a manual cross-check for now (Epic 1.8's structural
  check covers duplicate `srcs` ownership, not tier-list consistency; extending it to tier
  consistency is future scope, not this story's).
- *Given* `tier_manifest.bzl` is read by a future engineer, *When* they open the file, *Then* each
  of the three tier-list assignments (`TIER_1_PACKAGES`, `TIER_2_PACKAGES`, `TIER_3_PACKAGES`) is
  preceded by a one-sentence comment explaining *why* that tier is what it is, e.g.:
  ```
  # Tier 3: forms one Tarjan SCC (research/features.md, ADR-001) — cannot be split further
  # without breaking real import cycles.
  TIER_3_PACKAGES = [...]
  ```
  Bare package-name lists with no rationale comment fail this criterion — the comment must give a
  structural reason to find the "why" (pointing at ADR-001/research/features.md) without
  re-reading either document in full.
**Files**: `tier_manifest.bzl` (new)

##### Task 1.1.2a: Write `tier_manifest.bzl` with the three lists, each preceded by a one-sentence tier-rationale comment (~2 min)
- Files: `tier_manifest.bzl`

##### Task 1.1.2b: Cross-check against ADR-001's lists for exact match (~2 min)
- Files: none (verification)

---

### Epic 1.2: rules_kotlin + Kotlin upgrade (verified independently)

**Goal**: Land rules_kotlin 2.4.10 (containing PR #1652's fix for the O(N²)
`_get_android_resource_class_jars` analysis-phase cost, issue #1653) and Kotlin 2.4.10 in
lockstep on both the Bazel and Gradle sides, *before* the pilot multiplies `kt_android_library`
target count, per `research/stack.md`'s recommendation plus the confirmed release data below.

**Confirmed version data (2026-09-04, via `gh api`, not web search)**:
- rules_kotlin is pinned at **2.3.20** (`MODULE.bazel:16`), patched locally via
  `third_party/patches/rules_kotlin_kmp.patch` (adds the KMP `common_srcs` support this entire
  target-split plan depends on — confirmed this is a local, not upstream, patch). Latest release
  is **2.4.10**, confirmed via `gh api repos/bazel-contrib/rules_kotlin/releases`.
- The 2.3.21→2.4.10 changelog includes, beyond #1652 (Android resource class-jar perf):
  **PR #1712** ("Use associates' COMPILE jars as friends when abi jars retain internal
  [visibility]") — directly relevant to this plan's biggest risk area (internal-visibility
  breakage across target splits). **Read this PR's actual diff/description as part of Task
  1.2.1a** — it may simplify the `associates` strategy Epic 2.1/4.2 currently hand-designs.
  Also relevant, lower priority: PR #1715 (pluggable JvmTaskExecutor), general worker/KSP2
  caching improvements.
- Kotlin (Gradle side) is pinned at **2.3.21** (`settings.gradle.kts` — multiplatform/jvm/
  android/compose/serialization plugins) and **2.3.21** for
  `kotlin-compose-compiler-plugin-embeddable`/`kotlin-serialization-compiler-plugin-embeddable`
  in `MODULE.bazel`. rules_kotlin 2.4.10 bundles Kotlin 2.4.10 for its embedded compiler (PR
  #1661, "Upgrade Kotlin tooling to 2.4.10") — **the Gradle-side Kotlin plugin version must move
  to 2.4.10 in the same PR**, not separately or later. Do not let Bazel and Gradle compile with
  different Kotlin compiler versions: this repo has already hit a real symptom of exactly that
  skew (an R8/D8 warning about incompatible `.kotlin_module` metadata versions between a newer
  Kotlin producer and an older consumer). 2.4.20 is still RC/beta — do not use it.
- Bazel itself: no action needed, already on 9.2.0 (latest in the 9.x line; "8.8.0" seen
  elsewhere is the older parallel release line still getting backports, not a target to move to).

#### Story 1.2.1: Upgrade to rules_kotlin 2.4.10 + Kotlin 2.4.10 in lockstep, verified via its own CI run
**As a** maintainer, **I want** rules_kotlin and Kotlin upgraded together and CI-green *before*
any per-package `kt_android_library` targets exist, **so that** the pilot doesn't reproduce the
O(N²) analysis-phase blowup, doesn't hit Bazel/Gradle Kotlin-version skew, and can benefit from
PR #1712's friend-jar handling if it simplifies the associates strategy.

**Acceptance Criteria**:
- `MODULE.bazel:16`'s `bazel_dep(name = "rules_kotlin", version = "2.3.20")` is bumped to
  `"2.4.10"`, and `MODULE.bazel`'s `kotlin-compose-compiler-plugin-embeddable`/
  `kotlin-serialization-compiler-plugin-embeddable` pins, and `settings.gradle.kts`'s Kotlin
  multiplatform/jvm/android/compose/serialization plugin versions, all move to `2.4.10` in the
  same commit — not staggered across separate PRs.
  - *Given* `MODULE.bazel:16` currently pins `2.3.20` and `settings.gradle.kts` pins Kotlin
    `2.3.21`, *When* this story's PR is merged, *Then* `grep -rn '2\.3\.2[01]' MODULE.bazel
    settings.gradle.kts` returns no matches and the equivalent `2.4.10` greps do.
- The existing `third_party/patches/rules_kotlin_kmp.patch` (the `common_srcs` patch — the
  mechanism this entire target-split plan depends on) still applies cleanly against 2.4.10; this
  is a **hard prerequisite**, not optional.
  - *Given* the version bump in `MODULE.bazel`, *When* `bazel build //kmp:desktop_app` runs,
    *Then* it succeeds with no patch-application error (Bazel fails at module-resolution time
    with a clear patch-rejection message if the patch no longer applies).
- PR #1712's friend-jar handling is read and evaluated (not just noted) before Epic 2.1/4.2's
  `associates` strategy is finalized.
  - *Given* PR #1712's merged diff (`gh pr view 1712 --repo bazel-contrib/rules_kotlin
    --json body,files` or a direct diff read), *When* its behavior change is understood, *Then*
    this story records explicitly whether it changes/simplifies what Story 2.1.1's dual-target
    `associates` smoke test needs to verify, and updates that story if so.
- CI (not just local) passes on the upgraded version before any Tier 1 extraction PR is opened,
  with no R8/D8 `.kotlin_module` metadata-version warning in the build log (the exact symptom
  this repo has already hit from Bazel/Gradle Kotlin-version skew).
  - *Given* this story's own PR (touching only `MODULE.bazel` and `settings.gradle.kts`, plus the
    patch file if needed), *When* it runs through the existing GitHub Actions Bazel Android/JVM/
    test jobs, *Then* all jobs report green, independently confirming the upgrade itself (isolated
    from any target-split change) is safe.
**Files**: `MODULE.bazel`, `settings.gradle.kts`, `third_party/patches/rules_kotlin_kmp.patch`
(if it needs re-diffing against 2.4.10)

##### Task 1.2.1a: Confirm PR #1652's presence in 2.4.10 and read PR #1712's diff (~5 min)
- `gh api repos/bazel-contrib/rules_kotlin/releases` (already confirmed 2.4.10 is latest) and
  `gh pr view 1652 --repo bazel-contrib/rules_kotlin --json mergeCommit,closedAt` to confirm
  1652's fix is present at 2.4.10 (do not assume by release date alone).
- `gh pr view 1712 --repo bazel-contrib/rules_kotlin --json body,files` (or fetch the diff
  directly) — read it, and record in this story whether it simplifies the `associates` strategy.
- Files: none (research/verification only, informs Task 1.2.1b)

##### Task 1.2.1b: Bump rules_kotlin + Kotlin version pins in lockstep, re-verify the patch applies (~5 min)
- Edit `MODULE.bazel:16` (`rules_kotlin` → `2.4.10`), `MODULE.bazel`'s compose/serialization
  compiler-plugin pins → `2.4.10`, and `settings.gradle.kts`'s Kotlin plugin versions → `2.4.10`
  — all in the same commit.
- Run `bazel build //kmp:desktop_app` locally; if the patch fails to apply, re-diff
  `third_party/patches/rules_kotlin_kmp.patch` against the new `compile.bzl`/`jvm.bzl` source.
- **Timebox/escalation rule**: if the patch does not cleanly re-apply within **30 minutes** of
  investigation, stop — do not attempt a full patch rewrite inline as part of this task. A
  restructured upstream `compile.bzl`/`jvm.bzl` (the realistic worst case, hours not minutes)
  becomes its own follow-up task — and, if the patch's approach no longer fits the new
  rules_kotlin internals, its own ADR update (to ADR-002 or a new ADR) — not a same-task fix.
  Escalate to Tyler with the specific conflict before continuing past the 30-minute mark.
- Also run `./gradlew :kmp:compileKotlinJvm` (or equivalent) locally to confirm the Gradle side
  compiles clean at 2.4.10 before pushing — this is the check that would have caught the
  R8/D8 `.kotlin_module` skew symptom earlier if the versions had drifted.
- Files: `MODULE.bazel`, `settings.gradle.kts`, `third_party/patches/rules_kotlin_kmp.patch`
  (conditional)

##### Task 1.2.1c: Push as its own PR, verify full CI green via real CI (not local) (~2 min, mostly wait time)
- Open a PR touching only the upgrade; do not bundle with any Tier 1 package extraction.
- Confirm all Bazel Android/JVM/test CI jobs pass via `gh run watch` on the actual GitHub Actions
  run — per this repo's own prior incident (persistent-worker flags looked safe on a local
  61GB/24-core box but OOM-killed the 16GB CI runner), a clean local build is not sufficient
  evidence; wait for and read the real CI result before marking this task done.
- Files: none (CI verification)

---

### Epic 1.3: `.bazelrc` `KotlinCompile` worker cap

**Goal**: Add the worker-instance cap this repo currently has zero of for `KotlinCompile`,
before ~27+ distinct per-package targets (across several `WorkerKey`s) exist to multiply the
exact OOM mechanism that hit `Desugar`/`DexBuilder`/`PackageAndroidResources` in commits
`f8e7aa82e1`/`6842053f8d`.

#### Story 1.3.1: Add and CI-verify a `KotlinCompile` worker cap
**As a** CI maintainer, **I want** `--worker_max_instances=KotlinCompile=<N>` (and the multiplex
variant) set in `.bazelrc` before the pilot lands, **so that** the 16GB CI runner doesn't OOM the
same way it did for the Android worker mnemonics.

**Correction (verified directly against `.bazelrc`, not just research summaries)**: the prior
persistent-worker precedent for `Desugar`/`DexBuilder`/`PackageAndroidResources` was **fully
reverted**, not left at a stable working cap. `.bazelrc:38-51`'s own comment block ("REVERTED")
states all three mnemonics were tried capped at 2 instances apiece (6 workers × 3GB = 18GB) and
**still OOM-killed CI three times** (runs `33929109869`, `33931054162`, `33931907031`) before the
flags were removed entirely — there is currently no active `--worker_max_instances` line anywhere
in `.bazelrc` for any mnemonic. So `KotlinCompile=2` below is a **cautious starting guess by
analogy**, not a repeat of a value proven safe elsewhere — if 2 instances of a 3GB-heap worker
still OOM'd a 16GB runner for those mnemonics, `KotlinCompile`'s own per-instance heap footprint
must be checked (it is likely smaller per-instance than Desugar/DexBuilder, since `kotlinc`
workers don't hold `-Xmx3G` by default — confirm actual heap flags before treating `=2` as safe)
and the CI verification step below is not optional belt-and-suspenders, it is the only thing that
tells us whether `=2` for `KotlinCompile` actually holds up, given the exact precedent it's
modeled on already failed once at the same number.

**Acceptance Criteria**:
- `.bazelrc` gains `build --worker_max_instances=KotlinCompile=2` and
  `build --worker_max_multiplex_instances=KotlinCompile=2` as a cautious starting value (NOT
  presented as a repeat of a proven-safe precedent — see correction above; `research/pitfalls.md`
  recommended this as a starting point, not a validated final answer).
  - *Given* `.bazelrc` today has no line mentioning `KotlinCompile` (confirmed via
    `grep -n "KotlinCompile" .bazelrc` returning nothing), *When* this story's PR lands, *Then*
    `grep -n "worker_max_instances=KotlinCompile" .bazelrc` returns exactly the two new lines.
- The cap is verified against **real CI runner memory** (16GB), not just a local machine.
  - *Given* the cap is added, *When* `bazel test //kmp:jvm_tests` and
    `bazel build //kmp:android_app --config=android` run in the actual GitHub Actions CI job
    (not locally), *Then* a memory-headroom sample (e.g. a `free -h` step added temporarily to
    the workflow, or the runner's own resource-usage report) shows peak memory stays below the
    16GB ceiling with margin, and no `Worker process did not return a WorkResponse` error appears
    in the job log (the exact failure signature from the prior incident).
**Files**: `.bazelrc`, `.github/workflows/<the Bazel Android/JVM/test workflow file>` (temporary
memory-sampling step, if not already present)

##### Task 1.3.1a: Add the two `.bazelrc` lines (~2 min)
- Add `build --worker_max_instances=KotlinCompile=2` and
  `build --worker_max_multiplex_instances=KotlinCompile=2` directly below the "Persistent workers
  for Android dex/desugar/resource actions — REVERTED" comment block (`.bazelrc:38-51` — NOT
  lines 67-72, which are unrelated "Output legibility"/"CI profile" config). Extend that block's
  comment to note `KotlinCompile` is a new, separate worker mnemonic being capped preemptively for
  the same class of risk, with its own CI verification (Task 1.3.1c) — do not imply it inherits
  the (failed) Android-mnemonic precedent's safety.
- Files: `.bazelrc`

##### Task 1.3.1b: Add a continuous memory-sampling step to the CI workflow (~3 min)
- Locate the Bazel Android/JVM/test job(s) in `.github/workflows/` and add a sampling loop (e.g.
  `while true; do free -h; sleep 5; done &` backgrounded around the build step, not just a single
  `free -h` before and after) — a one-shot before/after snapshot can miss the transient mid-build
  memory spike that is the failure mode the prior incident actually exhibited. Remove once the cap
  is confirmed sufficient across the pilot (or keep permanently if cheap).
- Files: the relevant `.github/workflows/*.yml` file (exact name to be confirmed at task time —
  `ci.yml` per this repo's `CLAUDE.md` references, but verify against the actual Bazel job name)

##### Task 1.3.1c: Open the PR, verify CI green with memory headroom evidence (~2 min, wait time)
- Confirm the sampled memory stays under budget and no worker-crash errors appear.
- Files: none (CI verification)

---

### Epic 1.4: Cross-package `internal`-visibility audit script

**Goal**: A repeatable script producing a per-package-pair adjacency list, so each package
extraction's blast radius is known before writing its `BUILD.bazel`, not discovered via a failed
compile.

#### Story 1.4.1: Build `check_internal_visibility.py`
**As a** engineer extracting a package, **I want** a script that lists exactly which
cross-package `internal` symbols my package's own files depend on (and which other packages
depend on my package's `internal` symbols), **so that** I know upfront whether I need
`associates` and against which target(s).

**Acceptance Criteria**:
- The script implements the exact methodology `research/features.md` validated (not the two
  earlier, noisier iterations it explicitly rejected): Pass 1 enumerates `internal` declarations
  per package with receiver-type-aware regex or `sg` structural patterns (not the naive
  `internal (class|fun|val|var) NAME` regex that mis-extracted receiver types). Pass 2 checks for
  the exact `import dev.stapler.stelekit.<pkg>.<Symbol>` line (not bare identifier
  co-occurrence).
  - *Given* the script is run against `kmp/src/commonMain/kotlin/dev/stapler/stelekit/cache/`
    and `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/`, *When* it cross-references
    `cache`'s `internal` declarations (`PlatformLock`, `withLock` per `research/features.md`)
    against `performance`'s import lines, *Then* it reports exactly the 3-file `cache→performance`
    adjacency already confirmed by hand in `research/features.md` — reproducing that known-good
    result is the script's acceptance test.
  - *Given* the script is run against `repository` and `search` (both same-package internal false
    positives in the earlier iterations — e.g. `Flow` collisions), *When* it checks for
    cross-package usage of `repository`'s internal `Flow`-returning extension functions, *Then*
    it does **not** flag `kotlinx.coroutines.flow.Flow` references as a `repository`-internal
    usage (the false positive the strict FQN-import check in `research/features.md` eliminated).
- Output format is a per-pair adjacency list (declaring package, symbol, consuming package, file
  list), matching `research/features.md`'s table shape, not a per-package boolean.
- **Concrete output/exit-code contract** (so an engineer running this ~20 times by hand has an
  unambiguous contract, not just a description of the data shape):
  - Clean run: exit code `0`, stdout prints exactly one summary line, e.g.
    `No cross-package internal usage found for cache (0 declarations consumed outside cache).`
  - Violation run: exit code `1`, stdout prints one line per violation in the form
    `<declaring_pkg>:<file>:<line>: internal symbol '<Symbol>' used by <consuming_pkg>:<file>:<line>`,
    e.g.
    `cache/PlatformLock.kt:12: internal symbol 'PlatformLock' used by
    performance/OtelRepositoryWrappers.kt:47`, followed by a final summary line,
    e.g. `3 cross-package internal usages found across 2 files — see associates requirements
    above.`
  - A malformed invocation (bad package path, package directory not found) exits `2` with a
    one-line usage error to stderr — distinct from the `1` (real violations found) exit code, so
    calling scripts/CI can tell "audit ran and found nothing" from "audit ran and found problems"
    from "audit did not run."
**Files**: `scripts/check_internal_visibility.py`

##### Task 1.4.1a: Implement Pass 1 (internal declaration enumeration) (~5 min)
- Port `research/features.md`'s "fixed regex" (Iteration 2) logic — separate patterns for
  `class/interface/object/typealias`, `fun` with receiver-skipping, `val/var` with
  receiver-skipping, nested-generic-tolerant `<...>` matching — into a reusable Python function.
- Files: `scripts/check_internal_visibility.py`

##### Task 1.4.1b: Implement Pass 2 (strict FQN cross-reference) (~4 min)
- Grep for exact `import dev.stapler.stelekit.<pkg>.<Symbol>` lines across all other packages'
  files for each Pass-1 symbol; also check for wildcard imports
  (`import dev.stapler.stelekit.<pkg>.*`) per `research/features.md`'s methodology, flagging
  them for manual review rather than silently passing them through.
- Files: `scripts/check_internal_visibility.py`

##### Task 1.4.1c: Validate against the 6 known-good pairs from `research/features.md` (~4 min)
- Run the script and confirm it reproduces exactly: `cache→performance` (2 symbols, 3 files),
  `db→migration` (`replaceWikilink`, 1 file), `repository→git` (`asDbFlowOrNull`, 1 file),
  `util→model`/`util→ui` (`roundTo`, 1 file each), `voice→llm` (`LlmProviderSupport`, 2 files) —
  no more, no fewer, no stdlib-collision false positives.
- Files: `scripts/check_internal_visibility.py` (fix any discrepancy found)

#### Story 1.4.2: Build a per-package extraction-readiness aggregator script

**As a** engineer about to run the Common Per-Package Extraction Recipe's first three manual
pre-checks (derive `deps`, bidirectional `internal`-visibility check, Compose/serialization grep)
for roughly 20 packages in a row, **I want** one command that runs all three and prints a
go/no-go summary, **so that** the correct path is faster than skipping a step, not just eventually
caught by a compile failure — the pre-mortem already flagged fatigue/shortcut-taking risk across
~20 near-identical manual iterations, and today's mitigation (compile-time backstops) only catches
a skipped step *after* the fact.

**Acceptance Criteria**:
- A script `scripts/check_extraction_readiness.sh <package-name>` runs, in order: (1) the `deps`
  derivation grep (`grep -rhoE 'import dev\.stapler\.stelekit\.[a-zA-Z0-9_]+' .../<pkg> | sort -u`),
  (2) `scripts/check_internal_visibility.py <pkg>` in both directions (declaring and consuming),
  (3) the `@Composable`/`@Serializable` grep — and prints a single `GO` or `NO-GO` summary line at
  the end, followed by the three sub-results, e.g.:
  ```
  [1/3] deps: 4 packages imported (stdlib, error, model, cache)
  [2/3] internal-visibility: 0 cross-package usages found
  [3/3] plugins: no @Composable/@Serializable found
  ---
  GO: <pkg> is ready for extraction (see steps 4-8 of the Common Per-Package Extraction Recipe)
  ```
  or, on a non-empty internal-visibility/plugin result, `NO-GO` plus which check(s) require
  manual follow-up before writing `BUILD.bazel`.
- It does not need to be sophisticated (no config file, no caching) — a thin wrapper shelling out
  to the three existing checks is sufficient; the value is the one-command aggregation, not new
  detection logic.
- It is invoked once per package during Epic 2.2 (Tier 1) and Epic 4.1 (Tier 2) as an optional
  accelerant for the Common Per-Package Extraction Recipe's steps 1-3 — the manual grep/script
  invocations remain the documented fallback if the aggregator itself needs debugging.
**Files**: `scripts/check_extraction_readiness.sh` (new)

##### Task 1.4.2a: Write the aggregator script wrapping the three existing checks (~4 min)
- Files: `scripts/check_extraction_readiness.sh`

##### Task 1.4.2b: Validate against one already-known package (`cache`, which has a known
`internal`-visibility hit) to confirm the GO/NO-GO logic fires correctly on both outcomes (~3 min)
- Files: `scripts/check_extraction_readiness.sh` (fix any discrepancy found)

---

### Epic 1.5: Commit `.claude/inspect.json` (kibitzer config)

**Goal**: The kibitzer config used to find the cycle in `research/features.md` currently exists
nowhere in the repo (confirmed: `ls .claude/inspect.json` → "No such file or directory" as of
this planning session) — commit it so the cycle check is reproducible, not a one-off research
artifact.

#### Story 1.5.1: Commit a working `.claude/inspect.json`
**As a** future engineer re-verifying the Tier 3 cycle before Phase 5 implementation (per the
Unresolved Questions above), **I want** the exact config that worked, **so that** I don't
re-derive the schema from scratch the way `research/features.md` had to (it needed to fix an
invalid stub another agent had left).

**Acceptance Criteria**:
- `.claude/inspect.json` contains at minimum the `import-cycles` check
  (`{"name": "kotlin-import-cycles", "architecture_checker": "import-cycles", "severity":
  "advisory", "triggers": ["batch"]}`) and the `coupling` check used to corroborate hub
  classification.
  - *Given* `.claude/inspect.json` is committed, *When* `mcp__kibitzer__architecture_assessment`
    is run with `path=<repo root>`, `scope="kmp/src/commonMain/kotlin/**"`, *Then* it returns
    real findings (not the literal string "no `.claude/inspect.json` found above this path" that
    both `research/stack.md` and `research/build-vs-buy.md` document as the zero-config
    behavior).
- This is explicitly **not** wired into any CI workflow file (per ADR-002 — kibitzer is a
  planning-time tool, GH Actions has no MCP server).
  - *Given* `.claude/inspect.json` is committed, *When* `grep -rl "inspect.json\|kibitzer"
    .github/workflows/` is run, *Then* it returns no matches — confirming no accidental CI
    coupling was introduced.
**Files**: `.claude/inspect.json`

##### Task 1.5.1a: Write and commit the config (~2 min)
- Use the exact schema validated in `research/features.md` (`architecture_checker` field name,
  not `language` — the mistake an earlier agent's stub made).
- Files: `.claude/inspect.json`

##### Task 1.5.1b: Confirm zero CI coupling (~1 min)
- `grep -rl "inspect.json\|kibitzer" .github/workflows/` — must return nothing.
- Files: none (verification only)

---

### Epic 1.6: Define and wire `:platform_core`

**Goal**: Close the adversarial review's BLOCKER — no task in the prior draft of this plan ever
created `:platform_core`, yet Story 4.1.1 (`cache`), Story 4.1.2 (`model`), Story 4.1.3
(`coroutines`), and Story 4.2.1 (`util`) each need to resolve their relationship to it. Since
Tier 3 absorbs `db`/`llm`/`sections`/`transfer`/`ui` wholesale into one merged target (ADR-001)
regardless of exactly which of their files carry `expect`/`actual` declarations, the actual
`:platform_core`-building scope this plan needs is **narrower** than requirements.md's Scope
section implied: this epic (a) confirms the full, precise file inventory — including the
`db`/`llm`/`sections`/`transfer`/`ui` subset, for documentation completeness and to inform Tier
3's own eventual `BUILD.bazel` — and (b) creates and wires the filegroup covering
`model`/`platform`/`cache`/`coroutines`/`util`/`performance` plus that subset. The bulk of
`db`/`llm`/`sections`/`transfer`/`ui`'s *non*-expect/actual files stay inside Tier 3's merged
target regardless of this epic's output, since Tier 3 is not split further in this plan (Phase 5
is ADR-only).

#### Story 1.6.1: Run the deferred `expect`/`actual` file inventory
**As a** engineer defining `:platform_core`'s exact file list, **I want** the concrete, per-file
`expect`/`actual` inventory that `research/architecture.md` explicitly deferred ("this document
defers the literal enumeration to a dedicated... follow-up research task") and requirements.md
left as an Open Question, **so that** `:platform_core`'s filegroup glob is a verified file list,
not architecture.md's illustrative `<expect_actual_files>.kt` placeholder.

**Acceptance Criteria**:
- *Given* `grep -rlE '\b(expect|actual)\b' kmp/src/commonMain/kotlin/dev/stapler/stelekit/
  {model,platform,cache,coroutines,util,performance,db,llm,sections,transfer,ui} --include='*.kt'`
  is run, *When* the output is captured, *Then* it produces a concrete file list — one line per
  file — for each of the 11 named packages, superseding architecture.md's placeholder entries for
  `db`/`llm`/`sections`/`transfer`/`ui`.
- *Given* `model`/`platform`/`cache`/`coroutines`/`util`/`performance` are documented as
  "expect/actual in full" per requirements.md's Scope section, *When* the same grep is run against
  just those 6 packages, *Then* the result either confirms every file in each package carries
  `expect`/`actual` (matching the "in full" claim) or identifies the exception, which must be
  recorded here before Story 1.6.2 proceeds — do not assume "in full" holds without running the
  grep.
- The full inventory (all 11 packages) is recorded in this story as a table (file path →
  package), not left implicit.
**Files**: `project_plans/commonmain-bazel-target-split/implementation/plan.md` (this file —
record the inventory table in this story)

##### Task 1.6.1a: Run the grep inventory across all 11 candidate packages (~4 min)
- `grep -rlE '\b(expect|actual)\b' kmp/src/commonMain/kotlin/dev/stapler/stelekit/
  {model,platform,cache,coroutines,util,performance,db,llm,sections,transfer,ui} --include='*.kt'`
- Files: none (grep only, informs Task 1.6.1b)

##### Task 1.6.1b: Record the resulting file table in this story (~3 min)
- Files: `project_plans/commonmain-bazel-target-split/implementation/plan.md`

#### Story 1.6.2: Create `:platform_core`'s filegroup and wire it into both platform targets
**As a** engineer, **I want** a concrete `filegroup` (per `research/architecture.md`'s design —
`:platform_core` stays `common_srcs`-included, it is not an independently compiled target, since
expect/actual resolution requires the actuals to be present in the same compilation unit) wired
into `android_main`'s and `jvm_main_lib`'s `common_srcs`, **so that** Stories 4.1.1/4.1.2/4.1.3/
4.2.1's "resolve platform-core relationship" checks have a real artifact to resolve against
instead of an undefined name.

**Acceptance Criteria**:
- *Given* Story 1.6.1's verified file inventory, *When* `kmp/src/commonMain/kotlin/BUILD.bazel`
  is edited, *Then* it gains a new `filegroup(name = "platform_core_srcs", srcs = glob([...the
  verified paths...]), visibility = ["//kmp:__subpackages__"])`, replacing architecture.md's
  illustrative placeholder globs with the real, verified file list from Story 1.6.1.
- *Given* the new filegroup, *When* `kmp/src/androidMain/kotlin/BUILD.bazel`'s `android_main` and
  `kmp/src/jvmMain/kotlin/BUILD.bazel`'s `jvm_main_lib` are edited, *Then* each target's
  `common_srcs` list gains `//kmp/src/commonMain/kotlin:platform_core_srcs` — additive to, not a
  replacement of, the existing `kt_srcs`/`kt_expect_srcs` entries, since Tier 3's not-yet-extracted
  files still need `kt_srcs`'s full monolith glob until Tier 3's own future implementation phase.
  - *Given* this wiring, *When* `bazel build //kmp:desktop_app //kmp:android_app
    --config=android` runs, *Then* it succeeds with no "expect/actual declared in the same module"
    conflict and no duplicate-source error.
- **Explicit scope note** (per this epic's Goal): this story does *not* remove `db`/`llm`/
  `sections`/`transfer`/`ui`'s non-expect/actual files from `kt_srcs` — the bulk of those packages
  stays inside Tier 3's future merged target, wired via `kt_srcs` as today, until Tier 3 has its
  own implementation phase (Phase 5 is ADR-only per Epic 5.1). Only the `expect`/`actual` subset
  of those 5 packages moves into `:platform_core_srcs`, and since `:platform_core_srcs` itself
  stays `common_srcs`-included (not independently compiled), this move has **no invalidation-scope
  effect on its own** — it exists to give Tier 2's `model`/`coroutines`/`cache`/`util` a concrete
  artifact to fold into if their Task (a) decision (Story 1.6.3) is "fold," and to keep the
  filegroup's own definition (not just its existence) reviewable and correct.
**Files**: `kmp/src/commonMain/kotlin/BUILD.bazel`, `kmp/src/androidMain/kotlin/BUILD.bazel`,
`kmp/src/jvmMain/kotlin/BUILD.bazel`

##### Task 1.6.2a: Write the `platform_core_srcs` filegroup (~4 min)
- Files: `kmp/src/commonMain/kotlin/BUILD.bazel`

##### Task 1.6.2b: Wire `common_srcs` in both platform targets, same commit (~3 min)
- Files: `kmp/src/androidMain/kotlin/BUILD.bazel`, `kmp/src/jvmMain/kotlin/BUILD.bazel`

##### Task 1.6.2c: Build/test verification (~3 min)
- `bazel build //kmp:desktop_app //kmp:android_app --config=android`, `bazel test
  //kmp:jvm_tests //kmp:business_tests`.
- Files: none (verification)

#### Story 1.6.3: Apply the "resolve platform-core relationship" check uniformly to all four Tier 2 candidates
**As a** engineer extracting `cache`, `model`, `coroutines`, or `util`, **I want** the same
fold-vs-independent decision task Story 4.1.2 (`model`) already has, **so that** `cache` (Story
4.1.1) and `util` (Story 4.2.1) — both listed in requirements.md's Scope section as
`:platform-core` file sources, same as `model`/`coroutines` — don't silently skip a check the
other two get (adversarial review BLOCKER, part c).

**Acceptance Criteria**:
- *Given* Story 4.1.1 (`cache`) previously had no platform-core-relationship task, *When* this
  story's edit lands, *Then* Story 4.1.1 gains a task structurally identical to Task 4.1.2a,
  deciding whether `cache` stays folded into `:platform_core_srcs` or becomes a true independent
  Tier 2 target, with the one-sentence justification recorded inline (see Phase 4's Story 4.1.1,
  updated Task 4.1.1a below).
- *Given* Story 4.2.1 (`util`) previously had no such task, *When* this story's edit lands,
  *Then* Story 4.2.1 gains the equivalent task for `util` (see Phase 4's Story 4.2.1, updated
  Task 4.2.1a below).
- This story's own completion is the edit to Stories 4.1.1 and 4.2.1 later in this document.
**Files**: `project_plans/commonmain-bazel-target-split/implementation/plan.md` (edits to Stories
4.1.1 and 4.2.1 in Phase 4)

##### Task 1.6.3a: Add the platform-core-relationship task to Story 4.1.1 (`cache`) (~2 min)
- Files: `project_plans/commonmain-bazel-target-split/implementation/plan.md`

##### Task 1.6.3b: Add the platform-core-relationship task to Story 4.2.1 (`util`) (~2 min)
- Files: `project_plans/commonmain-bazel-target-split/implementation/plan.md`

---

### Epic 1.7: Pre-Tier-1 verification gates

**Goal**: Two verification steps that must run before Epic 2.2 (Tier 1 per-package extraction)
begins — not because Tier 1 packages themselves are architecturally risky, but because both
check load-bearing assumptions Epic 2.2's first extraction (`stats`) already relies on: that
`androidApp`'s own BUILD.bazel has no glob/resource assumption tied to commonMain's current
monolithic shape, and that rules_kotlin's compiled output is ABI-stable enough for a per-package
split to actually produce the invalidation-scope win the whole plan is built on.

#### Story 1.7.1: Inspect `androidApp`'s BUILD.bazel for monolith-shape assumptions
**As a** engineer about to start extracting packages out of the monolith, **I want**
confirmation that `//androidApp:android_app` (aliased by `kmp/BUILD.bazel`'s `android_app`, per
`research/architecture.md`'s note that it was "out of tree, not read here") has no glob or
resource rule assuming commonMain's current, unsplit directory shape, **so that** Tier 1/2
extraction doesn't silently break an untested consumer (adversarial review concern #4).

**Acceptance Criteria**:
- *Given* `//androidApp:android_app`'s actual `BUILD.bazel` (its exact path is resolved as step 1
  of this task, via `bazel query //androidApp:android_app --output=location`), *When* it is read in full, *Then*
  this story records explicitly: (a) whether it globs any path under
  `kmp/src/commonMain/kotlin/` directly (as opposed to depending on `:android_main`'s compiled
  output only), and (b) whether any Android resource/manifest merger rule references a
  commonMain-package-shaped path.
- *Given* the answer is "no monolith-shape assumption found" (the likely outcome, since
  `androidApp` should depend on `android_main`'s compiled output, not commonMain's raw sources),
  *When* this story completes, *Then* that negative result is recorded plainly, closing
  `research/architecture.md`'s explicit "not read here" gap — a confirmed absence is a valid,
  documented outcome, not a skipped check.
- *Given* the answer instead finds a monolith-shape assumption, *When* this story completes,
  *Then* the specific assumption is listed as a new Feasibility Risk / blocker for Epic 2.2,
  before any Tier 1 package is extracted.
**Files**: `project_plans/commonmain-bazel-target-split/implementation/plan.md` (record the
finding in this story)

##### Task 1.7.1a: Locate and read `androidApp`'s BUILD.bazel in full (~3 min)
- `bazel query //androidApp:android_app --output=location`, then read the file it names.
- Files: none (read-only)

##### Task 1.7.1b: Record the finding (~2 min)
- Files: `project_plans/commonmain-bazel-target-split/implementation/plan.md`

#### Story 1.7.2: Verify rules_kotlin's ABI-stable interface-jar behavior (hard prerequisite gate before Epic 2.2)
**As a** engineer about to spend the Tier 1 pilot's PR budget on a tiered-target architecture,
**I want** direct proof that touching a file still inside the monolith does *not* force a
re-execution of an already-extracted package's `KotlinCompile` action (only a re-analysis), **so
that** the entire tiered-target premise — that most PRs (which mostly touch Tier 3, per ADR-001's
87.8% figure) still benefit from Tier 1/2 extraction via unaffected dependency compiles — is
verified before, not after, the pilot's per-package PRs are opened (architecture review concern
#5, the most substantively important finding).

**Acceptance Criteria**:
- *Given* the Task 1.1.1c smoke target (`:stats`, built against the still-monolithic
  `jvm_main_lib`/`android_main` for platform-core symbols per `research/architecture.md`'s worked
  example), *When* an unrelated file still inside the monolith (e.g. a file in `ui/` or any other
  Tier 3 package) is touched and `bazel build //kmp/src/commonMain/kotlin/dev/stapler/stelekit/
  stats:stats --profile=/tmp/abi_check.json` is re-run, *Then* the resulting profile shows the
  `:stats` `KotlinCompile` action was **re-analyzed but not re-executed** (Bazel's action-cache
  hit path, not a fresh compile) — confirmed via `bazel analyze-profile /tmp/abi_check.json` or
  the `--profile` JSON's own action-state field.
  - *Given* the result instead shows `:stats`'s `KotlinCompile` **was** re-executed, *When* this
    story completes, *Then* this is recorded as a **blocking finding**: rules_kotlin's
    `kt_jvm_library`/`kt_android_library` do *not* produce ABI-stable interface jars for
    dependency-change detection here, undermining the aggregate invalidation-scope win most PRs
    (which touch Tier 3) would see from Tier 1/2 extraction — Epic 2.2 does not proceed until the
    **Decision gate on FAIL** below is executed (a real branching decision, not a silent
    "root-caused or accepted" soft landing).
- **Decision gate on FAIL (pre-mortem failure #1, P1 — hard stop, not silent continuation)**: a
  FAIL result means Tier 1/2 packages will keep getting *recompiled* (not just re-analyzed)
  whenever any Tier 3 file changes — i.e. the mechanism "works" only in the narrow
  isolated-package sense this spike tests, and could deliver ~0% real-world benefit once Tier 3
  (87.8% of files, dominating actual PR traffic) is factored in. On FAIL, choose explicitly
  between exactly two branches and record which one, before Epic 2.2 opens a single extraction PR:
  - **(a) Re-scope downward, continue.** Restate Tier 1/2's success claim as narrower and
    quantified: only a PR confined to Tier 1/2 packages with zero Tier 3 files touched sees a
    compile-time win; any Tier-3-touching PR sees none. Continuing is "worth doing but modest," not
    the aggregate win originally hypothesized. Choosing this branch makes Task 3.1.1e (Story
    3.1.1) **mandatory** (it is optional only when this gate never fires) and requires the numeric
    go/no-go threshold added to Story 3.1.1 to actually be computed, not skipped, before Phase 4 is
    allowed to start.
  - **(b) Hard stop, redesign.** Treat FAIL as evidence the whole tiered-target premise's implicit
    ABI-stable-interface-jar assumption doesn't hold under this repo's pinned rules_kotlin, and
    investigate a *different* Bazel mechanism — e.g. an explicit `deps` edge on a genuinely
    separately-compiled interface/API target, rather than relying on an ABI-stable jar implicitly
    produced by `kt_jvm_library`/`kt_android_library` — before committing further PR budget to
    Tier 2. Phase 4 does not start under this branch until that investigation concludes; Tier 1
    (Epic 2.2, small PR cost) may still proceed at the engineer's discretion since it delivers
    isolated-package value regardless of which branch is chosen.
  - Silently choosing (a) without recording the threshold and computing it in Story 3.1.1 is not
    an acceptable resolution — see Task 1.7.2c.
  - **Cross-reference to Story 3.1.1**: this is the same underlying risk caught at two points.
    Story 1.7.2 (this story) catches it at the mechanism/spike level, before Tier 1 starts. Story
    3.1.1's go/no-go checkpoint catches it again at the aggregate/real-PR-traffic level, after
    Tier 1 ships — Story 3.1.1's own Framing caveat already notes its PASS/FAIL criteria measure
    mechanism-correctness only, not the fraction of real PRs that would actually benefit; that gap
    is exactly what this gate's branch (a) closes by making Task 3.1.1e's real-PR-pattern sampling
    mandatory and threshold-gated instead of optional. A FAIL here and a "mechanism passes but
    real-PR-benefit fraction is stark" result at 3.1.1 are treated as the same failure mode, not
    two independent risks.
- This is framed and time-boxed explicitly as a **~10-minute check**, run once, before Epic 2.2's
  first real per-package extraction (Story 2.2.1) begins — not a standing CI check.
**Files**: none (measurement/verification task — no code changes; blocks Epic 2.2 until run)

##### Task 1.7.2a: Touch an unrelated monolith file and re-profile `:stats` (~4 min)
- Files: none

##### Task 1.7.2b: Confirm re-analysis-not-re-execution via the profile output (~3 min)
- Files: none

##### Task 1.7.2c: Record PASS/FAIL and, if FAIL, execute the Decision-gate-on-FAIL branch (a or b) before Epic 2.2 proceeds (~3 min)
- If FAIL: record which branch was chosen (a: re-scope-and-continue, or b: hard-stop-and-redesign)
  with its one-sentence justification. If (a), immediately also edit Story 3.1.1 to mark Task
  3.1.1e mandatory and confirm its numeric threshold is present (Story 3.1.1 already carries this
  threshold as of this plan revision — this task's job on a real FAIL is to flip Task 3.1.1e from
  optional to mandatory for this run, not to author the threshold from scratch).
- If PASS: record PASS plainly; no decision-gate branch applies.
- Files: `project_plans/commonmain-bazel-target-split/implementation/plan.md`

---

### Epic 1.8: CI-time duplicate-source-claim structural check

**Goal**: Turn `research/architecture.md`'s own suggestion ("a natural candidate for a CI-time
structural check... asserting no `.kt` file is claimed by more than one `kt_srcs`-derived
compilation unit") into an actual task, rather than relying solely on the Migration Plan's
"same commit" convention and a delayed, confusing fail-fast error at whatever PR first wires a
consumer to both a stale glob and a new target (architecture review concern #3).

#### Story 1.8.1: Add a CI script asserting exclusive `.kt` file ownership
**As a** CI maintainer, **I want** a cheap script that fails a PR if any `.kt` file under
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/` is matched both by the monolith's `kt_srcs`
glob and by some per-package target's own `srcs`, **so that** the "same commit" rule (Migration
Plan) has an automated backstop instead of only a task-list convention and a delayed, confusing
duplicate-declaration compile error.

**Acceptance Criteria**:
- *Given* a script `scripts/check_no_duplicate_kt_ownership.py` that (a) evaluates `kt_srcs`'s
  glob (with its current `exclude` list) against the filesystem, (b) evaluates every per-package
  `BUILD.bazel`'s own `srcs` glob the same way, and (c) computes the intersection, *When* it is
  run against a deliberately-broken state (a package with both a live `BUILD.bazel` and no
  corresponding `kt_srcs` exclude entry — constructible as a test fixture), *Then* it exits
  non-zero and names the offending file(s) and both claiming targets.
  - *Given* the same script run against the actual repo state after any Tier 1/2 extraction
    (where the "same commit" rule was followed correctly), *When* it runs, *Then* it exits zero.
- *Given* a new GitHub Actions workflow step (or an addition to an existing Bazel CI job) that
  runs this script whenever a PR touches `kmp/src/commonMain/kotlin/BUILD.bazel` or any package's
  own `BUILD.bazel` under that tree, *When* such a PR is opened, *Then* the check runs and blocks
  merge on failure.
**Files**: `scripts/check_no_duplicate_kt_ownership.py` (new), the relevant
`.github/workflows/*.yml` file (new step, path conditioned on the files above)

##### Task 1.8.1a: Write the glob-intersection script (~5 min)
- Reuse Bazel's own glob semantics (either shell out to `bazel query` for each target's resolved
  `srcs`, or reimplement the simple `**/*.kt` + `exclude` glob logic in Python — decide based on
  which is more robust to Bazel version drift at task time).
- Files: `scripts/check_no_duplicate_kt_ownership.py`

##### Task 1.8.1b: Validate against a deliberately-broken fixture (~3 min)
- Files: none (test run only), or a temporary fixture directory deleted after validation

##### Task 1.8.1c: Wire the CI workflow step, path-conditioned (~3 min)
- Files: the relevant `.github/workflows/*.yml` file

---

## Phase 2: Tier 1 Pilot (9 packages)

### Epic 2.1: `associates`-with-two-targets hands-on verification

**Goal**: Resolve the unverified risk flagged in requirements.md's Rabbit Holes and
`research/pitfalls.md` (#5 in its design-against list) — does rules_kotlin 2.3.20's `associates`
actually work with 2+ simultaneous friend targets? — before any real test file needs it.

#### Story 2.1.1: Verify `associates` with two simultaneous friend targets
**As a** engineer wiring test targets for Tier 1 packages, **I want** hands-on proof that
`associates = [":pkgA", ":pkgB"]` compiles and grants `internal` access to both, **so that**
later per-package test-wiring tasks aren't built on an unverified assumption.

**Acceptance Criteria**:
- A throwaway/smoke `kt_jvm_test` target with `associates` listing two friend targets — each
  declaring one `internal` symbol referenced by the test, and each **already existing at this
  point in the plan** — compiles successfully.
  - **Sequencing correction (fixes a forward-reference bug in an earlier draft of this plan)**:
    the second friend target must not be `:error` (`//kmp/src/commonMain/kotlin/dev/stapler/
    stelekit/error:error`) — `error`'s own `BUILD.bazel` is not created until Story 2.2.2, which
    lives in Epic 2.2, and the Dependency Visualization diagram gates Epic 2.2 to run **after**
    this epic (Epic 2.1 "MUST complete before Epic 2.2"). Using `:error` here would make this
    story's own acceptance test depend on a target Epic 2.2 hasn't created yet. Instead, the
    second friend target is a throwaway fixture library created and deleted entirely within this
    story (Task 2.1.1a) — not a real Tier 1/2/3 package — so both friend targets genuinely exist
    before this smoke test is written, independent of any later epic's output.
  - *Given* `:stats` (Tier 1, already created via Task 1.1.1c's smoke target in Phase 1 — i.e.
    genuinely existing before Epic 2.1 runs — extended here with one throwaway `internal`
    declaration since it doesn't already have one, per `research/architecture.md`) and a new
    throwaway fixture target `//kmp/src/jvmTest/kotlin/dev/stapler/stelekit/
    _associates_smoke_fixture:_associates_smoke_fixture` (created solely for this test, declaring
    one `internal` symbol of its own) each expose one `internal` symbol, *When* a smoke test
    target declares `associates = ["//kmp/src/commonMain/kotlin/dev/stapler/stelekit/
    stats:stats", "//kmp/src/jvmTest/kotlin/dev/stapler/stelekit/
    _associates_smoke_fixture:_associates_smoke_fixture"]` and references both `internal` symbols,
    *Then* `bazel build //<smoke test target>` succeeds with no visibility error.
- The result (works / doesn't work / works with caveats) is recorded directly in this plan's
  Unresolved Questions list as resolved, with the exact rules_kotlin behavior observed.
**Files**: a temporary smoke-test `BUILD.bazel` + `.kt` file, placed under
`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/` following the `common_test_fixtures`
pattern (the exact filename is chosen at task time), plus the throwaway fixture library's own
`BUILD.bazel` + `.kt` under
`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/_associates_smoke_fixture/` — both deleted after
verification unless kept as a permanent regression test.

##### Task 2.1.1a: Add one throwaway `internal` symbol to `stats`, and create a throwaway fixture library with its own `internal` symbol (~3 min)
- Add a trivial `internal` symbol to `stats` only if it doesn't already declare one (confirmed
  empty for `stats` in `research/architecture.md`).
- Create a new, temporary `kt_jvm_library` fixture target declaring one `internal` symbol — this
  is *not* a real Tier 1/2/3 package, purely a throwaway second friend target for this
  verification, so the test does not depend on `:error` or any other package not yet extracted at
  this point in the plan (see the Sequencing correction above).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/stats/*.kt` (one file, minimal addition),
  `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/_associates_smoke_fixture/BUILD.bazel` (new),
  `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/_associates_smoke_fixture/*.kt` (new)

##### Task 2.1.1b: Write and run the smoke test (~4 min)
- Create the smoke test target with `associates` on both `:stats` and the throwaway fixture
  library, referencing both `internal` symbols.
- Run `bazel build`/`bazel test` and record pass/fail plus any error text verbatim.
- Files: smoke test `BUILD.bazel` + `.kt` (new, temporary)

##### Task 2.1.1c: Record the result, clean up or keep as regression test (~3 min)
- Update this plan's Unresolved Questions entry with the verified answer.
- Either delete the throwaway `internal` symbol/fixture library/smoke test (if purely
  instrumental) or keep them as a permanent regression test guarding this exact rules_kotlin
  behavior (prefer keeping it — it's cheap insurance against a future rules_kotlin upgrade
  regressing this).
- Files: as above (`stats`, the fixture library, and the smoke test)

---

### Epic 2.2: Per-package extraction (9 Tier 1 packages)

**Goal**: Each of the 9 zero-risk packages becomes its own `kt_jvm_library`/`kt_android_library`
target, no longer textually included in the monolith.

**Common recipe per package** (identical mechanically for all 9 — worked in full for the first
two, `stats` and `error`, as the simplest and highest-fan-in representatives; the remaining 7
follow the same 3-task shape exactly, each task bundling the 8 recipe steps in the
[Common Per-Package Extraction Recipe](#common-per-package-extraction-recipe) section above —
also used verbatim by Epic 4.1 for Tier 2, so it is written once, not duplicated here).

#### Story 2.2.1: Extract `stats`
**As a** engineer, **I want** `stats` (2 files, zero `expect`/`actual`, zero `internal`, per
`research/architecture.md`'s worked example) as its own target, **so that** a change to
`StatsCollector`-equivalent code no longer invalidates the monolith.

**Acceptance Criteria**:
- *Given* `stats/BUILD.bazel` declares `stelekit_kt_common_library(name = "stats", srcs =
  glob(["*.kt"]), deps = ["@maven//:org_jetbrains_kotlin_kotlin_stdlib"])` (per the derived-deps
  step — `stats` has zero outgoing edges to any hub per the "leaf packages" list in
  `research/features.md`) and `kt_srcs`'s glob excludes `dev/stapler/stelekit/stats/**`, *When*
  a change confined to `stats/LibraryStatsProvider.kt` is made and `bazel build
  //kmp:android_app --config=android --profile=/tmp/after.json` runs, *Then* the profile shows a
  `KotlinCompile //kmp/src/commonMain/kotlin/dev/stapler/stelekit/stats:stats` action (small,
  isolated) and **no** re-run of the full monolithic `android_main` `KotlinCompile` action (only
  its dependents, if any, re-link).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/stats/BUILD.bazel`,
`kmp/src/commonMain/kotlin/BUILD.bazel`

##### Task 2.2.1a: Derive `stats`'s deps, run the bidirectional `internal`-visibility check and the Compose/serialization grep, and write `BUILD.bazel` (~3 min)
- Includes recipe steps 1-4 above (deps derivation, `check_internal_visibility.py` both
  directions, `@Composable`/`@Serializable` grep, `BUILD.bazel` write).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/stats/BUILD.bazel`

##### Task 2.2.1b: Add the `kt_srcs` exclude line in the same commit (~1 min)
- Files: `kmp/src/commonMain/kotlin/BUILD.bazel`

##### Task 2.2.1c: Build/test verification (~3 min)
- Isolated build first: `bazel build //kmp/src/commonMain/kotlin/dev/stapler/stelekit/
  stats:stats`; then `bazel build //kmp:desktop_app //kmp:android_app --config=android`,
  `bazel test //kmp:jvm_tests //kmp:business_tests`.
- Files: none (verification)

#### Story 2.2.2: Extract `error`
**As a** engineer, **I want** `error` (2 files, imported by 136 packages per `research/
features.md`'s coupling data — the highest-fan-in Tier 1 package) as its own target, **so that**
its wide consumption is served from a small, independently-cached compile unit.

**Acceptance Criteria**:
- *Given* `error/BUILD.bazel` declares its own target with `deps` derived per the recipe (likely
  just stdlib — `error` is a leaf package per the edge list), *When* `stats` (already extracted
  in Story 2.2.1) is checked, *Then* `stats`'s `BUILD.bazel` does **not** need an `error` dep
  update unless the derived-deps grep in Task 2.2.1a actually found `stats→error` (open — verify
  at task time, don't assume).
  - *Given* `error`'s high fan-in (136 consuming packages, almost all still in the monolith
    during Tier 1), *When* `error` is extracted, *Then* the monolith (`android_main`/
    `jvm_main_lib`) gains a `deps` edge on `:error` (not `associates`, since `error` has zero
    confirmed cross-package `internal` usage per `research/features.md`'s 6-pair table) so the
    136 still-monolithic consumers keep resolving `DomainError` etc.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/BUILD.bazel`,
`kmp/src/commonMain/kotlin/BUILD.bazel`, `kmp/src/androidMain/kotlin/BUILD.bazel`,
`kmp/src/jvmMain/kotlin/BUILD.bazel`

##### Task 2.2.2a: Derive `error`'s deps, run the bidirectional `internal`-visibility check and the Compose/serialization grep, and write `BUILD.bazel` (~3 min)
- Includes recipe steps 1-4 above.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/BUILD.bazel`

##### Task 2.2.2b: Add the `kt_srcs` exclude line + add `:error` to the monolith's `deps` (~3 min)
- The monolith still contains all 136 consumers of `error`, so `android_main`/`jvm_main_lib` need
  a new `deps` entry on `:error` in the same commit.
- Files: `kmp/src/commonMain/kotlin/BUILD.bazel`, `kmp/src/androidMain/kotlin/BUILD.bazel`,
  `kmp/src/jvmMain/kotlin/BUILD.bazel`

##### Task 2.2.2c: Build/test verification (~3 min)
- Isolated build first: `bazel build //kmp/src/commonMain/kotlin/dev/stapler/stelekit/
  error:error`; then the full-graph build/test.
- Files: none (verification)

#### Stories 2.2.3–2.2.9: Extract remaining Tier 1 packages

Identical 3-task recipe as Stories 2.2.1/2.2.2, each task now bundling the full 8-step
[Common Per-Package Extraction Recipe](#common-per-package-extraction-recipe) (derive deps →
bidirectional `internal`-visibility check → Compose/serialization grep → write `BUILD.bazel`
(with tier-rationale comment) + exclude line in one commit → isolated build → full-graph
build/test → tier_manifest.bzl/BUILD.bazel rationale-comment cross-check). Each package's own
consuming-package fan-in (does it need a `deps` edge added to the
still-monolithic platform targets, like `error` did, or is it a true leaf with zero external
consumers, like `stats`?) is determined by that package's own Task (a) grep — not assumed here.

| Story | Package | Files | Known fan-in (research/features.md coupling data) | Notes |
|---|---|---|---|---|
| 2.2.3 | `logging` | 1 | 67 | Same pattern as `error` — likely needs a monolith `deps` edge |
| 2.2.4 | `parsing` | 8 | 15 | Same pattern |
| 2.2.5 | `benchmark` | 1 | not profiled | Verify fan-in via grep at task time |
| 2.2.6 | `docs` | 6 | not profiled | Verify fan-in via grep at task time |
| 2.2.7 | `resilience` | 1 | not profiled | Verify fan-in via grep at task time |
| 2.2.8 | `rtc` | 1 | not profiled | Verify fan-in via grep at task time |
| 2.2.9 | `service` | 2 | not profiled | Verify fan-in via grep at task time |

**Files per story**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/<pkg>/BUILD.bazel`,
`kmp/src/commonMain/kotlin/BUILD.bazel`, plus `kmp/src/{androidMain,jvmMain}/kotlin/BUILD.bazel`
if the package has any external consumer still in the monolith.

---

### Epic 2.3: Test source-set wiring for Tier 1

**Goal**: Confirm the additive `associates` pattern `research/architecture.md` proposed for the
9-package pilot actually works end-to-end (not just in the isolated Epic 2.1 smoke test) for real
`jvmTest`/`businessTest`/`androidUnitTest` files.

#### Story 2.3.1: Audit and update test targets for Tier 1 extractions
**As a** engineer, **I want** every existing test file that exercises a Tier 1 package's code
(and needs `internal` access) to keep working after that package is extracted, **so that** test
coverage isn't silently reduced.

**Acceptance Criteria**:
- *Given* `research/architecture.md`'s confirmation that none of `common_test_fixtures`'s 4
  fixture files live in a Tier 1 package, *When* `jvm_tests`/`business_tests`/
  `android_unit_test_lib`'s `BUILD.bazel` files are inspected after all 9 Tier 1 extractions
  land, *Then* each still lists its original monolith `associates` entry (`jvm_main_lib` or
  `android_main`) **plus** an additive entry for each Tier 1 package a test file in that source
  set actually references an `internal` symbol from (determined via the audit script from Epic
  1.4, not assumed).
  - *Given* no Tier 1 package test file is found to need `internal` access (the likely outcome,
    since all 6 confirmed cross-package `internal` pairs in `research/features.md` involve
    non-Tier-1 declaring packages), *When* this story completes, *Then* it is acceptable (and
    should be stated plainly in the story's completion note) that zero test `BUILD.bazel` changes
    were needed — "no change required" is a valid, verified outcome, not a shortcut.
**Files**: `kmp/src/{commonTest,jvmTest,androidUnitTest,businessTest}/kotlin/BUILD.bazel`
(conditional — may end up unchanged)

##### Task 2.3.1a: Run `check_internal_visibility.py` scoped to the 9 Tier 1 packages as declaring packages (~3 min)
- Confirm zero cross-package `internal` usages originate from any Tier 1 package (expected,
  per Epic 1.4's validation against the known 6 pairs, none of which declare from a Tier 1
  package).
- Files: none (script run only)

##### Task 2.3.1b: Update test `BUILD.bazel` files only if the audit found a real dependency (~3 min, conditional)
- If Task 2.3.1a found nothing, this task is a no-op — record that explicitly rather than
  silently skipping the story.
- Files: `kmp/src/{jvmTest,businessTest,androidUnitTest}/kotlin/BUILD.bazel` (conditional)

---

## Phase 3: Go/No-Go Re-profiling

### Epic 3.1: Before/after re-profiling checkpoint

**Goal**: Confirm the invalidation-scope hypothesis with real data before committing to Tier 2's
larger PR count.

#### Story 3.1.1: Re-profile and compare against the 71s baseline
**As a** decision-maker, **I want** the exact profiling methodology from requirements.md re-run
after Tier 1 lands, **so that** Tier 2 only proceeds if the pilot actually delivered the expected
invalidation-scope win.

**Acceptance Criteria**:
- *Given* a cold `--disk_cache` build of `//kmp:android_app --config=android` with
  `--profile=/tmp/after.json`, *When* `bazel analyze-profile` / the same jq top-N-by-duration
  query requirements.md's baseline used is run against it, *Then* the resulting top-N list shows
  the 9 Tier 1 `KotlinCompile` actions as individually small (each proportional to its own file
  count, e.g. `stats`'s 2-file compile far under 1s) rather than one 711-file/71s action.
- *Given* a change confined to `kmp/src/commonMain/kotlin/dev/stapler/stelekit/stats/
  LibraryStatsProvider.kt` only, *When* an incremental `bazel build //kmp:android_app
  --config=android` runs, *Then* the action graph shows only `:stats`'s `KotlinCompile` (and any
  direct dependent) re-running — not the Tier-3 monolith equivalent, not any other Tier 1
  package.
- **Go/no-go decision recorded explicitly**: if either criterion above fails, Phase 4 does not
  start; the specific failure (e.g. "monolith `deps` edge on `:error` caused full monolith
  re-analysis on every Tier 1 change" — a plausible failure mode if `:error`'s `deps` edge was
  drawn too broadly) is root-caused before any Tier 2 PR opens.
- **Framing caveat (adversarial + architecture review)**: the PASS/FAIL criteria above measure
  *mechanism-correctness* only — that an isolated single-package change avoids recompiling the
  monolith. They do **not** measure what fraction of this repo's actual historical PRs would hit
  that path versus touching Tier 3 (87.8% of files, including the highest-traffic packages `ui`,
  `db`, `repository`, `editor`). A mechanism PASS here is not itself evidence of realized
  developer-time savings across real PR patterns — state this plainly in the recorded decision
  (Task 3.1.1d); do not let it imply a broader claim the checkpoint doesn't support. Task 3.1.1e
  below can supply real PR-pattern data if this framing gap is a concern before Phase 4.
- **Numeric go/no-go criterion (pre-mortem failure #1, P1 — cross-referenced with Story 1.7.2's
  decision gate)**: pre-committed *before* Task 3.1.1e is run, so the threshold can't be picked
  after seeing the result: **≥50% of the last 50 merged PRs touching
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/` must touch only Tier 1/2 packages (per
  `tier_manifest.bzl`) and zero Tier 3 files.** This criterion is:
  - **Mandatory to compute** (Task 3.1.1e is no longer optional) whenever Story 1.7.2's Task
    1.7.2c recorded a FAIL and chose branch (a) (re-scope-and-continue) — in that case a mechanism
    PASS at this checkpoint is not sufficient on its own; Phase 4 does not start unless the ≥50%
    threshold is also met, and if it is not met, the specific gap (e.g. "only 12% of sampled PRs
    would have benefited") is recorded as the reason Phase 4 is paused, per Story 1.7.2's branch
    (a) requirement.
  - **Optional but still recommended** whenever Story 1.7.2 recorded PASS — in that case Task
    3.1.1e remains a nice-to-have sanity check, not a gate, since the mechanism itself was already
    confirmed to generalize (a PASS at 1.7.2 means Tier 3 changes don't force re-execution of
    already-extracted targets, so the aggregate benefit question is less at risk than it is under
    branch (a)).
  - This is the same underlying risk Story 1.7.2's decision gate names — see that story's
    Cross-reference note: Story 1.7.2 catches it at the isolated mechanism/spike level before
    Tier 1 starts; this criterion catches it again at the aggregate/real-PR-traffic level after
    Tier 1 ships, closing the gap the Framing caveat above flags.
**Files**: none (measurement/decision task — no code changes)

##### Task 3.1.1a: Run the cold-cache profile for `//kmp:android_app --config=android` (~4 min)
- Same methodology as requirements.md's baseline (cold `--disk_cache`, `--profile=...`).
- Files: none

##### Task 3.1.1b: Run the cold-cache profile for `//kmp:jvm_tests` (~4 min)
- Files: none

##### Task 3.1.1c: Run the single-package-change incremental-build check (~3 min)
- Touch one file in `stats/`, rebuild, inspect the action graph.
- Files: none

##### Task 3.1.1d: Record the go/no-go decision in this plan (~2 min)
- Update this section with PASS/FAIL and, if FAIL, the root-cause hypothesis before Phase 4
  planning resumes.
- Files: `project_plans/commonmain-bazel-target-split/implementation/plan.md` (this file —
  append a dated result note to this story)

##### Task 3.1.1e: Sample real PR-touch patterns — mandatory if Story 1.7.2 FAILed down branch (a), otherwise optional (~5 min)
- Run `git log --stat -n 50 --oneline -- kmp/src/commonMain/kotlin/dev/stapler/stelekit/` (or an
  equivalent recent-merge-commit sample) and classify each touched top-level package as Tier
  1/2/3 (per `tier_manifest.bzl`, Story 1.1.2), computing what fraction of the 50 sampled PRs
  touched only Tier 1/2 packages and zero Tier 3 files. This gives the go/no-go decision real data
  instead of only mechanism-proof (adversarial review concern #5).
- **Check the numeric threshold above (≥50%)** if this task is mandatory (Story 1.7.2 recorded
  FAIL + branch (a)): record the computed fraction and whether it meets or misses the threshold;
  a miss pauses Phase 4 per Story 1.7.2's branch (a) requirement, root-caused/recorded before any
  further Tier 2 planning resumes.
- If this task is running only because it's recommended (Story 1.7.2 recorded PASS), it remains
  informational: record the fraction found: it does not gate Phase 4 on its own in that case,
  since the mechanism itself was already confirmed to generalize.
- Files: `project_plans/commonmain-bazel-target-split/implementation/plan.md` (record the sample
  result, and which case — mandatory/gating vs. optional/informational — applied)

---

## Phase 4: Tier 2 Rollout (11 packages)

*Gated on Phase 3's go/no-go passing.*

### Epic 4.1: Per-package extraction (11 Tier 2 packages)

**Goal**: Same recipe as Epic 2.2 — the
[Common Per-Package Extraction Recipe](#common-per-package-extraction-recipe) section above,
written once and shared by both epics — applied to the 11 clean non-cyclic Tier 2 packages.

**Two representative worked stories** (the package with a confirmed `internal`-usage dependency,
and the highest-fan-in Tier 2 package), followed by a compact table for the remaining 9.

#### Story 4.1.1: Extract `cache` (has a confirmed cross-package `internal` dependency)
**As a** engineer, **I want** `cache` extracted with its `internal` symbols
(`PlatformLock`, `withLock`) still visible to `performance` (a Tier 3 package, not yet
extracted), **so that** `performance`'s 3 files consuming those symbols keep compiling.

**Acceptance Criteria**:
- *Given* `cache` is one of the four Tier 2 packages requirements.md's Scope section lists as a
  `:platform-core` file source (`model`, `cache`, `coroutines`, `util`), *When* `cache` is
  extracted, *Then* its platform-core relationship is resolved via the same explicit
  fold-vs-independent decision task Story 4.1.2 (`model`) uses — see Task 4.1.1a below — **not**
  silently assumed independent the way an earlier draft of this plan did (adversarial review
  BLOCKER, part c / Story 1.6.3).
- *Given* `research/features.md`'s confirmed finding that `cache`'s `PlatformLock` and
  `withLock` are used by 3 files in `performance` (a Tier 3, still-monolithic package during
  Phase 4), *When* `cache/BUILD.bazel` is created (assuming Task 4.1.1a's decision is
  "independent" — see below if "fold") and `kt_srcs`'s glob excludes
  `dev/stapler/stelekit/cache/**`, *Then* the still-monolithic `android_main`/`jvm_main_lib`
  target (which still contains `performance`'s files) gains an **`associates`** entry
  (`associates = ["//kmp/src/commonMain/kotlin/dev/stapler/stelekit/cache:cache"]`, not `deps`)
  so `performance`'s 3 files retain `internal` access to `cache`'s symbols — confirmed by
  `bazel build //kmp:android_app --config=android` succeeding with no visibility error on
  `performance/OtelRepositoryWrappers.kt`/`performance/SpanRepository.kt` (the 3 files
  `research/features.md` identified).
- This is the first real (non-smoke-test) use of `associates` from a still-monolithic platform
  target onto a newly-extracted package — flagged explicitly as validating Epic 2.1's smoke-test
  result under real conditions, not just synthetic ones.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/cache/BUILD.bazel`,
`kmp/src/commonMain/kotlin/BUILD.bazel`, `kmp/src/androidMain/kotlin/BUILD.bazel`,
`kmp/src/jvmMain/kotlin/BUILD.bazel`

##### Task 4.1.1a: Resolve `cache`'s platform-core relationship (~4 min)
- Structurally identical to Task 4.1.2a (`model`): re-check `cache`'s Story 1.6.1 expect/actual
  inventory result and decide fold-vs-independent; write the one-sentence justification into this
  story before Task 4.1.1b proceeds (Story 1.6.3).
- Files: this plan document (append decision), then either
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/cache/BUILD.bazel` or
  `:platform_core_srcs`'s filegroup definition

##### Task 4.1.1b: Derive `cache`'s deps, write `BUILD.bazel` (~3 min)
- Only if Task 4.1.1a's decision is "independent." If "fold," this task is replaced by updating
  `:platform_core_srcs`'s filegroup instead (per Task 1.6.2a).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/cache/BUILD.bazel`

##### Task 4.1.1c: Exclude `cache/**` from `kt_srcs`, add `associates` (not `deps`) on the monolith targets (~3 min)
- Note: rules_kotlin rejects a target in both `associates` and `deps` — confirm the monolith
  doesn't already `deps` on `:cache` before adding the `associates` entry (it won't, since this
  is `:cache`'s first appearance as a target, but state the check explicitly per the
  `common_test_fixtures` precedent's documented rule).
- Files: `kmp/src/commonMain/kotlin/BUILD.bazel`, `kmp/src/androidMain/kotlin/BUILD.bazel`,
  `kmp/src/jvmMain/kotlin/BUILD.bazel`

##### Task 4.1.1d: Build/test verification, specifically exercising `performance`'s 3 consuming files (~4 min)
- `bazel build //kmp:android_app --config=android`; confirm no `internal`-visibility compile
  error on the 3 identified `performance` files.
- Files: none

#### Story 4.1.2: Extract `model` (highest-fan-in Tier 2 package)
**As a** engineer, **I want** `model` (13 files, imported by 377 packages per `research/
stack.md`'s coupling data — the single highest-fan-in package in the entire codebase) as its own
target, **so that** its extremely wide consumption is served efficiently.

**Acceptance Criteria**:
- *Given* `model` is also one of the ~109 files feeding `:platform_core` (per requirements.md's
  Scope section — `model` carries real `expect`/`actual` declarations), *When* `model` is
  extracted, *Then* it is **not** a plain standalone `kt_jvm_library` the way `stats`/`cache`
  are — it must be resolved against `:platform_core`'s `common_srcs` design (Epic 4.1's Task
  4.1.2a explicitly re-reads `research/architecture.md`'s `:platform_core` section before writing
  `model/BUILD.bazel`, since `model` may need to stay inside `:platform_core`'s `common_srcs`
  rather than become an independent `deps`-only target).
  - *Given* the ambiguity above is real (this plan does not resolve whether `model` becomes a
    true independent Tier 2 target or stays folded into `:platform_core`), *When* Task 4.1.2a
    is executed, *Then* its output is a explicit written decision (folded vs. independent) with
    one sentence of justification, added to this story before Task 4.1.2b proceeds — this is
    flagged here rather than silently assumed one way, since requirements.md itself left the
    exact `:platform_core` file inventory as an open question.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/BUILD.bazel` (if independent) or
no new file (if folded into `:platform_core`'s existing filegroup)

##### Task 4.1.2a: Resolve model's platform-core relationship (~4 min)
- Re-check whether `model`'s files carry real `expect`/`actual` (per requirements.md) and decide
  fold-vs-independent; write the one-sentence justification into this story.
- Files: this plan document (append decision), then either
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/BUILD.bazel` or
  `:platform_core`'s filegroup definition

##### Task 4.1.2b: Execute per the decision (write BUILD.bazel + exclude, or update platform-core filegroup) (~3 min)
- Files: as decided in 4.1.2a

##### Task 4.1.2c: Build/test verification (~3 min)
- Files: none

#### Stories 4.1.3–4.1.11: Extract remaining Tier 2 packages

Identical 3-task recipe as Story 4.1.1/4.1.2 minus the special-case considerations (no known
`internal`-usage dependency, no known `:platform_core` ambiguity — verify both are actually
absent via grep at task time before assuming the plain recipe applies).

| Story | Package | Files | Notes |
|---|---|---|---|
| 4.1.3 | `coroutines` | 3 | Also a `:platform_core` candidate per requirements.md's file list — same Task-(a) check as `model` |
| 4.1.4 | `util` | 7 | Has confirmed `internal`-usage dependents in Tier 3 (`model`, `ui` — see Epic 4.2, not this story) |
| 4.1.5 | `command` | 3 | Verify fan-in via grep at task time |
| 4.1.6 | `clipboard` | 2 | Verify fan-in via grep at task time |
| 4.1.7 | `flashcard` | 1 | Verify fan-in via grep at task time |
| 4.1.8 | `loader` | 1 | Verify fan-in via grep at task time |
| 4.1.9 | `outliner` | 6 | Verify fan-in via grep at task time |
| 4.1.10 | `parser` | 4 | Verify fan-in via grep at task time |
| 4.1.11 | `vault` | 6 | Verify fan-in via grep at task time |

**Files per story**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/<pkg>/BUILD.bazel`,
`kmp/src/commonMain/kotlin/BUILD.bazel`, plus `kmp/src/{androidMain,jvmMain}/kotlin/BUILD.bazel`
if the package has any external consumer still in the monolith or Tier 3.

---

### Epic 4.2: Cross-tier `internal`-usage fixes

**Goal**: Handle the 2 confirmed cross-package `internal` dependencies whose *consuming* package
lives in Tier 3 (still-monolithic) even after the *declaring* package (Tier 2) is extracted —
`cache→performance` is already handled inline in Story 4.1.1; this epic covers the remaining one.

#### Story 4.2.1: Wire `util`'s `internal` symbol (`roundTo`) for `model` and `ui` consumers
**As a** engineer, **I want** `util`'s `internal fun Double.roundTo(...)` still visible to its
one consuming file in `model` (Tier 2, extracted in Story 4.1.2/4.1.3) and its one consuming file
in `ui` (Tier 3, still monolithic), **so that** neither breaks when `util` is extracted.

**Acceptance Criteria**:
- *Given* `util` is one of the four Tier 2 packages requirements.md's Scope section lists as a
  `:platform-core` file source (`model`, `cache`, `coroutines`, `util`), *When* `util` is
  extracted, *Then* its platform-core relationship is resolved via the same explicit
  fold-vs-independent decision task Story 4.1.2 (`model`) uses — see Task 4.2.1a below — **not**
  silently assumed independent the way an earlier draft of this plan did (adversarial review
  BLOCKER, part c / Story 1.6.3).
- *Given* `research/features.md`'s confirmed finding (`util.roundTo` used by 1 file in `model`,
  1 file in `ui`), *When* `util/BUILD.bazel` is created (assuming Task 4.2.1a's decision is
  "independent"), *Then* `model`'s own target (already extracted per Story 4.1.2/4.1.3,
  decision-dependent) gains `associates = [..., ":util"]` (or, if `roundTo` is made `public`
  instead of `internal` — a simpler alternative worth considering explicitly here since it's a
  single trivial extension function, not a real encapsulation boundary — no `associates` entry is
  needed at all; this story's Task 4.2.1b decides which).
  - *Given* `ui` (Tier 3) is still fully inside the monolith during Phase 4, *When* `util` is
    extracted, *Then* the monolith (`android_main`/`jvm_main_lib`) gains an `associates` entry on
    `:util` (mirroring the `cache`→monolith pattern from Story 4.1.1) **unless** the
    make-it-public alternative was chosen, in which case a plain `deps` edge suffices.
- A `bazel query "somepath(//kmp/src/commonMain/kotlin/dev/stapler/stelekit/util:util,
  //kmp/src/androidMain/kotlin:android_main)"` run (per ADR-002's CI-regression-check
  recommendation) returns empty both directions, confirming no cycle was introduced by this
  cross-tier wiring.
  - *Given* the `associates`/`deps` wiring above, *When* the `bazel query somepath` command runs
    in both directions (`util`→monolith and monolith→`util`), *Then* only the monolith→`util`
    direction returns a path (the expected dependency direction); the reverse returns empty.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/util/BUILD.bazel`,
`kmp/src/commonMain/kotlin/BUILD.bazel`, `kmp/src/androidMain/kotlin/BUILD.bazel`,
`kmp/src/jvmMain/kotlin/BUILD.bazel`, `model`'s `BUILD.bazel` (if not folded into
`:platform_core`)

##### Task 4.2.1a: Resolve `util`'s platform-core relationship (~4 min)
- Structurally identical to Task 4.1.2a (`model`): re-check `util`'s Story 1.6.1 expect/actual
  inventory result and decide fold-vs-independent; write the one-sentence justification into this
  story before Task 4.2.1b proceeds (Story 1.6.3).
- Files: this plan document (append decision), then either
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/util/BUILD.bazel` or `:platform_core_srcs`'s
  filegroup definition

##### Task 4.2.1b: Decide make-`roundTo`-public vs. `associates` (~2 min)
- A single trivial extension function is a low-stakes visibility change — evaluate whether
  making it `public` is simpler than wiring `associates` in two places; record the decision.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/util/*.kt` (if made public)

##### Task 4.2.1c: Wire the chosen mechanism for both `model` and the monolith (`ui`'s consumer) (~3 min)
- Files: as listed above, per the Task 4.2.1b decision

##### Task 4.2.1d: Run the `bazel query somepath` regression check both directions (~2 min)
- Files: none (verification)

---

### Epic 4.3: Test source-set wiring for Tier 2

**Goal**: Mirror Epic 2.3's pattern for the 11 Tier 2 packages — confirm every existing
`jvmTest`/`androidUnitTest`/`commonTest`/`businessTest` file that exercises a Tier 2 package's
code (and needs `internal` access) keeps working after that package is extracted, per
requirements.md's Scope section, which requires test source-set re-wiring "alongside the
main-code split" for every tier, not just Tier 1.

#### Story 4.3.1: Audit and update test targets for Tier 2 extractions
**As a** engineer, **I want** every existing test file that exercises a Tier 2 package's code
(and needs `internal` access) to keep working after that package is extracted, **so that** test
coverage isn't silently reduced — the same guarantee Story 2.3.1 already gives Tier 1.

**Acceptance Criteria**:
- *Given* `check_internal_visibility.py` (Epic 1.4) is run scoped to the 11 Tier 2 packages as
  declaring packages, *When* `jvm_tests`/`business_tests`/`android_unit_test_lib`'s
  `BUILD.bazel` files are inspected after all 11 Tier 2 extractions land (Epic 4.1) and the 2
  cross-tier `associates` fixes (Epic 4.2) are in place, *Then* each test target still lists its
  original monolith `associates` entry (`jvm_main_lib` or `android_main`, for whichever Tier 3
  packages it still exercises) **plus** an additive entry for each Tier 2 package a test file in
  that source set actually references an `internal` symbol from — determined by the audit script,
  not assumed by analogy with Tier 1's result.
  - *Given* the audit finds a cross-package `internal` usage from a Tier 2 package into a test
    file (e.g. a `model`- or `cache`-internal symbol referenced directly from a `jvmTest`/
    `businessTest` file, distinct from the Epic 4.2 cross-tier fixes which cover *production*
    code in Tier 3 consuming Tier 2 `internal` symbols), *When* this story completes, *Then* the
    specific test target gains the corresponding `associates` entry on the newly-extracted Tier 2
    package's target, and the addition is recorded here with the exact symbol/file that required
    it.
  - *Given* no Tier 2 package's test-consuming files are found to need `internal` access (a
    plausible outcome, structurally identical to Tier 1's — Epic 1.4's known 6 cross-package
    `internal` pairs are all production-code pairs, none test-file-originated), *When* this story
    completes, *Then* it is acceptable (and must be stated plainly in the story's completion note,
    mirroring Story 2.3.1's rule) that zero test `BUILD.bazel` changes were needed — "no
    test-wiring change required" is a valid, verified outcome for Tier 2 too, not a shortcut or an
    unaudited assumption.
**Files**: `kmp/src/{commonTest,jvmTest,androidUnitTest,businessTest}/kotlin/BUILD.bazel`
(conditional — may end up unchanged, exactly as Story 2.3.1 allows for Tier 1)

##### Task 4.3.1a: Run `check_internal_visibility.py` scoped to the 11 Tier 2 packages as declaring packages (~3 min)
- Confirm which (if any) cross-package `internal` usages originate from a Tier 2 package into a
  test-source-set file, as distinct from the Epic 4.2 production-code cross-tier fixes already
  handled (`cache`→`performance`, `util`→`model`/`util`→`ui`) — this task scopes specifically to
  test files, per requirements.md's Scope section requiring test-set re-wiring for every tier.
- Files: none (script run only)

##### Task 4.3.1b: Update test `BUILD.bazel` files only if the audit found a real test-side dependency (~3 min, conditional)
- If Task 4.3.1a found nothing, this task is a no-op — record that explicitly rather than
  silently skipping the story, matching Task 2.3.1b's precedent.
- Files: `kmp/src/{jvmTest,businessTest,androidUnitTest}/kotlin/BUILD.bazel` (conditional)

---

## Phase 5: Tier 3 Decision (ADR only — no implementation)

### Epic 5.1: Finalize and scope-bound the Tier 3 decision

**Goal**: Ensure ADR-001's (a)-merge decision is the recorded, actionable outcome for this
project, and that any further Tier 3 work is explicitly deferred, not silently assumed as a
continuation of this plan's Phase 5 implementation.

#### Story 5.1.1: Confirm ADR-001 as final, mark Tier 3 implementation out of scope
**As a** future implementer picking up this project's Phase 5 (per SDD's own phase numbering —
distinct from this plan's internal "Phase 5" section name; note the collision explicitly), **I
want** an unambiguous record that Tier 3's merged-target implementation is not part of this
plan's task list, **so that** I don't accidentally scope-creep into the 87.8%-of-codebase cluster
without its own dedicated planning.

**Acceptance Criteria**:
- *Given* ADR-001 records decision (a) (merge Tier 3 into one target, defer (b) dependency-
  inversion refactor to a separate project), *When* SDD Phase 5 (`sdd:5-implement`) is run
  against this plan, *Then* its task list contains only Phase 1 (tooling), Phase 2 (Tier 1), and
  Phase 4 (Tier 2) tasks from this document — no task instructs writing
  `commonmain_cycle_cluster`'s `BUILD.bazel`.
- *Given* the Unresolved Question above (kibitzer's two contradictory runs), *When* Phase 5
  implementation begins, *Then* a fresh kibitzer run and a fresh manual Tarjan pass are performed
  once against the then-current `main` before Tier 3's ADR is treated as final for any future
  follow-on project — this story's completion criterion is that this re-verification task is
  itself scheduled (e.g. as the first task of whatever follow-on project scopes option (b)), not
  that it's completed now.
**Files**: `project_plans/commonmain-bazel-target-split/decisions/ADR-001-tiered-target-
architecture.md` (already written — this story confirms/finalizes it, no further edit expected
unless Phase 4's actual results surface new information)

##### Task 5.1.1a: Re-read ADR-001 adversarially once before finalizing (~3 min)
- Confirm the 18-vs-17 count correction, the (a)-vs-(b) rationale, and the reconsideration
  triggers all still hold given whatever was learned during Phases 2-4's real extraction work.
- Also confirm ADR-001's Consequences section still carries its "Tier-3 merged target must
  self-document its own tier rationale" note — whoever eventually writes
  `commonmain_cycle_cluster`'s `BUILD.bazel` (a later, separately-scoped implementation phase)
  needs that requirement to survive this ADR's finalization, not get trimmed as stale.
- Files: `project_plans/commonmain-bazel-target-split/decisions/ADR-001-tiered-target-architecture.md`

##### Task 5.1.1b: Record explicit out-of-scope statement in this plan's Phase 5 section (~2 min)
- Files: `project_plans/commonmain-bazel-target-split/implementation/plan.md` (this file)

##### Task 5.1.1c: Open a tracking issue for the Tier 3 dependency-inversion follow-on project (~2 min)
- Open (or confirm already open) a GitHub issue referencing ADR-001's "(b) dependency-inversion
  refactor" alternative and its Reconsideration triggers section, even with placeholder scope, so
  the follow-on project survives past this project's closure instead of living only in the ADR's
  prose (architecture review concern #6).
- Acceptance: a tracking issue exists, its body links
  `project_plans/commonmain-bazel-target-split/decisions/ADR-001-tiered-target-architecture.md`,
  and its title makes clear it's the Tier 3 dependency-inversion follow-on (not this project's own
  remaining work).
- Files: none (GitHub issue, not repo content) — add the issue URL back into ADR-001's
  Consequences section once opened.
