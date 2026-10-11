# Architecture Research: commonmain-bazel-target-split

**Date**: 2026-09-04

## Current BUILD.bazel architecture (as-is)

### `kmp/src/commonMain/kotlin/BUILD.bazel`

Two targets, both `filegroup`s (not compiled here — commonMain is never compiled standalone):

```python
filegroup(
    name = "kt_srcs",
    srcs = glob(["**/*.kt"]),
    visibility = ["//kmp:__subpackages__"],
)

filegroup(
    name = "demo_filesystem_source",
    srcs = ["dev/stapler/stelekit/platform/DemoFileSystem.kt"],
    visibility = ["//kmp:__subpackages__"],
)
```

The docstring at the top of the file (lines 1–14) states the reason no compilation happens here:

> "JVM: sources are exported via the kt_srcs filegroup and compiled together with jvmCommonMain and jvmMain sources in //kmp/src/jvmMain/kotlin:jvm_main_lib. Compiling commonMain alone for JVM is not possible because expect declarations require their actual implementations to be present in the same compilation unit. ... See ADR-005 for the double-compilation trade-off."

Key fact: `kt_srcs` uses an **unrestricted recursive glob** — `glob(["**/*.kt"])` — with no per-package enumeration and no `exclude`. This is the single glob that both `android_main` and `jvm_main_lib` pull the entire commonMain tree from.

### `kmp/src/androidMain/kotlin/BUILD.bazel`

One `kt_android_library` target, `android_main` (lines 21–176):

```python
kt_android_library(
    name = "android_main",
    srcs = [
        "//kmp/src/commonMain/kotlin:kt_srcs",
        "//kmp/src/jvmCommonMain/kotlin:kt_srcs",
    ] + glob(["**/*.kt"]),
    common_srcs = [
        "//kmp/src/commonMain/kotlin:kt_srcs",
        "//kmp/src/jvmCommonMain/kotlin:kt_expect_srcs",
    ],
    kotlinc_opts = "//:kmp_android_kotlinc_opts",
    plugins = [
        "//:compose_compiler_plugin",
        "//:serialization_compiler_plugin",
    ],
    custom_package = "dev.stapler.stelekit",
    deps = [ ... ~50 maven/generated deps ... ],
    visibility = ["//kmp:__subpackages__", "//androidApp:__subpackages__"],
)
```

`common_srcs` is the rules_kotlin mechanism that marks a subset of `srcs` as the "expect" (common/metadata) side of an expect/actual pair for **this specific compilation unit** — here, the whole of `commonMain`'s `kt_srcs` plus `jvmCommonMain`'s expect-only subset (`kt_expect_srcs`). Everything in `srcs` but not in `common_srcs` (i.e., `androidMain`'s own `glob(["**/*.kt"])` and the actual-bearing part of `jvmCommonMain`) is compiled as the "actual"/platform side in the same invocation. `custom_package` sets `R` class package for Android resource references.

### `kmp/src/jvmMain/kotlin/BUILD.bazel`

Two targets: `jvm_main_lib` (`kt_jvm_library`, lines 20–122) and `desktop_app` (`kt_jvm_binary`, lines 125–136).

```python
kt_jvm_library(
    name = "jvm_main_lib",
    srcs = [
        "//kmp/src/commonMain/kotlin:kt_srcs",
        "//kmp/src/jvmCommonMain/kotlin:kt_srcs",
    ] + glob(
        ["**/*.kt"],
        exclude = ["**/benchmarks/**"],  # JMH benchmarks — Gradle-only (jmh plugin)
    ),
    common_srcs = [
        "//kmp/src/commonMain/kotlin:kt_srcs",
        "//kmp/src/jvmCommonMain/kotlin:kt_expect_srcs",
    ],
    kotlinc_opts = "//:kmp_jvm_kotlinc_opts",
    plugins = [
        "//:compose_compiler_plugin",
        "//:serialization_compiler_plugin",
    ],
    deps = [ ... ~35 maven/generated deps ... ],
    visibility = ["//kmp:__subpackages__"],
)

kt_jvm_binary(
    name = "desktop_app",
    main_class = "dev.stapler.stelekit.desktop.MainKt",
    runtime_deps = [":jvm_main_lib"],
    jvm_flags = [...],
    visibility = ["//visibility:public"],
)
```

This is structurally identical to `android_main` (same `srcs`/`common_srcs` pattern reusing `commonMain:kt_srcs` and `jvmCommonMain:kt_expect_srcs`), confirming the requirements.md claim that both platform targets **independently recompile the entire common tree** as separate compilation units — there is no sharing of the commonMain compile output between `android_main` and `jvm_main_lib` today; only the *source filegroup* is shared, not any compiled artifact.

### `kmp/src/jvmCommonMain/kotlin/BUILD.bazel`

Two `filegroup`s: `kt_srcs` (all of jvmCommonMain, `glob(["**/*.kt"])`) and `kt_expect_srcs` (one explicit file: `dev/stapler/stelekit/db/libsql/LibsqlNativeLoader.kt`). The docstring explains why the split exists: putting both the expect and its actual in `common_srcs` triggers "expect and actual declared in the same module" — this is the same class of same-module conflict that `:platform-core` extraction must avoid.

### Test source sets

- **`kmp/src/commonTest/kotlin/BUILD.bazel`** — `common_test_fixtures` (`kt_jvm_library`, lines 13–33). Docstring (lines 1–9): "FakeFileSystem lives in commonTest/ so it compiles on all KMP platforms, but it is also referenced by jvmTest sources... This kt_jvm_library exposes just the fixture files needed by jvmTest without pulling in the full commonTest suite." Uses **explicit file enumeration** in `srcs` (4 files), not a glob. Uses `associates = ["//kmp/src/jvmMain/kotlin:jvm_main_lib"]` with the comment: "Friend (not just deps) access to jvm_main_lib is required: SectionManifestParserTest references the internal `TOML_PARSING_SUPPORTED` expect/actual declaration, which is only visible across a kt_jvm_library boundary via `associates`... rules_kotlin rejects a target listed in both associates and deps, so it must NOT also appear in deps here." This is the `associates` precedent flagged as a research risk in requirements.md.

- **`kmp/src/jvmTest/kotlin/BUILD.bazel`** — `jvm_tests` (`kt_jvm_test`, lines 15–63). `srcs = glob(["**/*.kt"], exclude=[screenshot/Roborazzi patterns])`. `associates = ["//kmp/src/jvmMain/kotlin:jvm_main_lib"]` (whole monolith, friend access to everything). `deps` includes `//kmp/src/commonTest/kotlin:common_test_fixtures`.

- **`kmp/src/androidUnitTest/kotlin/BUILD.bazel`** — `android_unit_test_lib` (`kt_android_library`, lines 17–42) with `associates = ["//kmp/src/androidMain/kotlin:android_main"]`, then `android_unit_tests` (`android_local_test`) depends on `:android_unit_test_lib`.

- **`kmp/src/businessTest/kotlin/BUILD.bazel`** — `business_tests` (`kt_jvm_test`, lines 20–35), `srcs = glob(["**/*.kt"])`, `associates = ["//kmp/src/jvmMain/kotlin:jvm_main_lib"]` — same monolith-wide friend access as `jvm_tests`.

All four test targets use `associates` (not just `deps`) against the *entire* monolithic main target (`jvm_main_lib` or `android_main`), which is what makes `internal` symbols anywhere in commonMain visible to any test file today — a property that a per-package split necessarily narrows.

### `kmp/BUILD.bazel` (top-level aliases)

`desktop_app` aliases `//kmp/src/jvmMain/kotlin:desktop_app`; `android_app` aliases `//androidApp:android_app` (out of tree, not read here — androidApp likely depends on `//kmp/src/androidMain/kotlin:android_main` transitively); `jvm_tests` / `business_tests` are `test_suite` wrappers around the single `kt_jvm_test` targets above. No target here references commonMain packages directly — everything routes through the platform-specific monolith targets.

## Proposed `:platform-core` target design

Two per-platform `kt_jvm_library` / `kt_android_library` targets (not one shared target) are needed, **not** because the source list differs conceptually, but because `common_srcs`/expect-actual resolution is inherently tied to *which actuals are compiled alongside it* — a `kt_jvm_library` and a `kt_android_library` are different rule kinds with different toolchains, and rules_kotlin has no cross-rule-kind output sharing for KMP metadata (this is exactly the rules_kotlin#567 gap the repo already works around). So:

- `//kmp/src/commonMain/kotlin:platform_core` — a **new `filegroup`** (or, if platform-core sources need actual compiled once, this stays a filegroup like `kt_srcs` does today) restricted to the ~109 expect/actual-bearing files (`model`, `platform`, `cache`, `coroutines`, `util`, `performance`, plus the expect/actual subset of `db`, `llm`, `sections`, `transfer`, `ui`).
- `android_main` keeps its `common_srcs`/`srcs` wiring exactly as today, but sources it from `:platform_core` (new, narrower filegroup) for the expect side, plus a **new set of per-package `deps`** for the compiled non-expect/actual packages, instead of the monolithic `commonMain:kt_srcs`.
- `jvm_main_lib` mirrors the same change.

This is compatible with, not a replacement for, the existing `common_srcs` mechanism — `:platform-core`'s files still must be compiled *together with* the platform actuals in one `kt_android_library`/`kt_jvm_library` invocation (ADR-005's constraint doesn't change), so `:platform-core` cannot become a standalone `kt_jvm_library` that `android_main`/`jvm_main_lib` merely `deps` on — the expect/actual resolution requirement still forces textual inclusion via `common_srcs`, just of a much smaller file set (109 files instead of ~800). What *does* change: everything **outside** platform-core (the ~600+ remaining files) moves from `common_srcs`-included source text into real `deps` edges onto separately-compiled `kt_jvm_library`/`kt_android_library` targets — this is where the actual compilation-unit split (and thus the caching/invalidation win) comes from.

Concretely:
```python
# kmp/src/commonMain/kotlin/BUILD.bazel (illustrative)
filegroup(
    name = "platform_core_srcs",
    srcs = glob([
        "dev/stapler/stelekit/model/**/*.kt",
        "dev/stapler/stelekit/platform/**/*.kt",
        "dev/stapler/stelekit/cache/**/*.kt",
        "dev/stapler/stelekit/coroutines/**/*.kt",
        "dev/stapler/stelekit/util/**/*.kt",
        "dev/stapler/stelekit/performance/**/*.kt",
        # explicit expect/actual-bearing files only, NOT full-package globs, for
        # db/llm/sections/transfer/ui — these packages keep most files outside
        # platform-core and split further into their own per-package targets.
        "dev/stapler/stelekit/db/<expect_actual_files>.kt",
        "dev/stapler/stelekit/llm/<expect_actual_files>.kt",
        "dev/stapler/stelekit/sections/<expect_actual_files>.kt",
        "dev/stapler/stelekit/transfer/<expect_actual_files>.kt",
        "dev/stapler/stelekit/ui/<expect_actual_files>.kt",
    ]),
    visibility = ["//kmp:__subpackages__"],
)
```
The exact file list for the `db`/`llm`/`sections`/`transfer`/`ui` subsets is an **open question** per requirements.md ("Which specific files ... carry the actual expect/actual declarations ... Phase 2 research") — this document defers the literal enumeration to a dedicated `grep -rl 'expect \|actual '` inventory pass in a follow-up research task, since requirements.md explicitly flags it as still open at Phase 2.

Because platform-core is still folded into `android_main`/`jvm_main_lib` via `common_srcs`, it is **not itself a separately-cached compilation unit** — it does not reduce the current 71s number directly. Its purpose is purely to shrink what other per-package targets need to textually include vs. depend on: any package with a real `deps`-only relationship to platform-core (i.e., it only calls platform-core's public/exported API, no shared `internal`) can become an independent `kt_jvm_library`/`kt_android_library` that depends on `android_main`/`jvm_main_lib`'s compiled output instead of re-including platform-core's sources.

## Proposed per-package target design (worked examples)

### Zero-risk package: `stats`

Verified: `stats/` has 2 files (`GraphStatsReport.kt`, `LibraryStatsProvider.kt`), zero `expect`/`actual` declarations, zero `internal` declarations (`grep -rlE '\b(expect|actual)\b'` and `internal ` both empty). This package is a clean leaf.

```python
# kmp/src/commonMain/kotlin/dev/stapler/stelekit/stats/BUILD.bazel (illustrative)
load("//:kt_common.bzl", "stelekit_kt_common_library")  # proposed shared macro

stelekit_kt_common_library(
    name = "stats",
    srcs = glob(["*.kt"]),
    platform = "jvm",  # or "android" — see below on per-platform duplication
    deps = [
        "//kmp/src/jvmMain/kotlin:jvm_main_lib",  # for platform-core symbols + whatever else stats needs
        "@maven//:org_jetbrains_kotlin_kotlin_stdlib",
    ],
    visibility = ["//kmp:__subpackages__"],
)
```

Since `stats` has no cross-package `internal` usage, plain `deps` (not `associates`) suffices — no friend-module wiring needed. This is the "mechanical, boilerplate" case the Starlark macro should optimize for.

### Hub-touching package: `search`

Verified (`grep -rhoE '^import dev\.stapler\.stelekit\.[a-zA-Z.]+' search/*.kt`): `search/`'s 3 files (`FtsQueryBuilder.kt`, `DatalogQuery.kt`, `VectorSearch.kt`) import `dev.stapler.stelekit.error.DomainError`, `dev.stapler.stelekit.model.{Block,BlockUuid}`, and `dev.stapler.stelekit.repository.BlockReadRepository`. Zero `expect`/`actual` in `search/` itself. This is a harder case because:
- `model` is a platform-core package (already folded into `android_main`/`jvm_main_lib` via `common_srcs`) — `search` can reach it transitively once it depends on the compiled `android_main`/`jvm_main_lib` output.
- `error` is one of the 9 zero-risk pilot packages — if `error` is migrated to its own target first, `search` needs a real `deps` edge on `:error`, not just on the monolith.
- `repository` is one of the 18 hub-touching packages, **not yet migrated in this pilot** — `search` still needs it via the monolith (or, if `repository` is migrated in the same slice, via a `deps` edge on `:repository`).

```python
# kmp/src/commonMain/kotlin/dev/stapler/stelekit/search/BUILD.bazel (illustrative)
load("//:kt_common.bzl", "stelekit_kt_common_library")

stelekit_kt_common_library(
    name = "search",
    srcs = glob(["*.kt"]),
    deps = [
        "//kmp/src/commonMain/kotlin/dev/stapler/stelekit/error:error",       # if error migrated
        "//kmp/src/jvmMain/kotlin:jvm_main_lib",                              # repository + model (not yet split out)
        "@maven//:org_jetbrains_kotlin_kotlin_stdlib",
    ],
    # associates only needed IF search uses an `internal` symbol from repository/model —
    # confirmed absent by the internal-usage audit tool (Scope item, not yet run) before
    # finalizing this target; a public-API-only dependency needs plain deps.
    visibility = ["//kmp:__subpackages__"],
)
```

Whether `search`→`repository` needs `associates` (friend access) or plain `deps` hinges entirely on the not-yet-run internal-visibility audit (Feasibility Risk #1) — this document surfaces the dependency shape, not a final verdict, since `BlockReadRepository`'s visibility wasn't inspected as part of this task (out of scope: reading unrelated source trees in bulk). The concrete unresolved point for Phase 3 planning: **`search` cannot be fully extracted from the monolith until `repository` either also has its own target or `search`'s dependency on it is proven `deps`-only** — this is exactly the "some packages forced to merge rather than split" risk called out in Rabbit Holes.

## Test source set migration implications

Today all four test targets (`common_test_fixtures`, `jvm_tests`, `android_unit_test_lib`, `business_tests`) use `associates` against exactly one monolithic target (`jvm_main_lib` or `android_main`) for whole-repo friend access. Splitting main code into many targets forces a choice per test file: which package-target(s) does *this specific test* need friend access to?

**Can this be done incrementally, package-by-package?** Yes, in principle, for the 9 zero-risk pilot packages, because:
- rules_kotlin's `associates` attribute accepts a **list** of targets, not just one (`kt_jvm_test`/`kt_jvm_library` both declare it as `label_list` per rules_kotlin's kt.bzl — consistent with the existing single-entry lists seen in all four current BUILD files, which don't preclude adding entries).
- None of the 9 pilot packages (`benchmark`, `docs`, `error`, `logging`, `parsing`, `resilience`, `rtc`, `service`, `stats`) are import targets of the existing `associates` friend-usage in `common_test_fixtures` (`coroutines/SessionLifecycleContractTest.kt`, `db/sidecar/FakeFileSystem.kt`, `sections/*`) — none of those 4 fixture files live in a pilot package, so `common_test_fixtures` needs no changes for the pilot slice.
- **Concretely, for the pilot's 9 packages**: any `jvmTest`/`businessTest`/`androidUnitTest` file that tests only pilot-package code and uses no `internal` symbol from a still-monolithic package can add `associates = ["//kmp/src/jvmMain/kotlin:jvm_main_lib", "//kmp/src/commonMain/kotlin/dev/stapler/stelekit/stats:stats"]` (multi-entry) — i.e., **augment**, not replace, the existing monolith-wide associate, since the monolith still exists and still contains the other ~29 non-pilot packages. This is additive and reversible per the Constraints' revertibility requirement.
- The only forcing function toward an all-or-nothing cutover would be if rules_kotlin rejected a target appearing in `associates` when it (transitively) overlaps with another `associates` entry's compiled sources — this is untested here (Rabbit Hole: "`associates` at scale... unproven at this scale") and should be a smoke test in Phase 3/5, not assumed safe.

**Per existing test target, what changes for the 9-package pilot:**
- `commonTest/BUILD.bazel` (`common_test_fixtures`): **no change** — its 4 fixture files are outside the 9 pilot packages.
- `jvmTest/BUILD.bazel` (`jvm_tests`): any jvmTest file testing a pilot package (e.g. a hypothetical `LibraryStatsProviderTest.kt` if one exists under `jvmTest`) would need `:stats` (etc.) added to `associates` alongside the existing `jvm_main_lib` entry — but since `jvm_main_lib` still `common_srcs`-includes the *rest* of commonMain (non-pilot packages) during the pilot, `jvm_main_lib`'s associate stays too. No test file needs to *lose* an associate during the pilot; this is purely additive.
- `businessTest/BUILD.bazel` (`business_tests`): same additive pattern as `jvmTest`.
- `androidUnitTest/BUILD.bazel` (`android_unit_test_lib`): same additive pattern, associate list gains pilot-package targets alongside `android_main`.

This confirms the pilot **can** proceed package-by-package without an all-or-nothing test cutover, provided the multi-entry `associates` mechanism itself works at 2+ entries — which is the one piece of "unproven at this scale" risk flagged in Rabbit Holes and should be validated with the *first* pilot package before assuming it holds for all 9.

## Glob/exclude mechanics for incremental extraction

Verified: `kmp/src/commonMain/kotlin/BUILD.bazel`'s `kt_srcs` filegroup uses `glob(["**/*.kt"])` — an **unrestricted recursive wildcard**, not an explicit per-package/per-file enumeration and not a glob with any `exclude` list (contrast with `jvm_main_lib`'s own `glob(["**/*.kt"], exclude = ["**/benchmarks/**"])`, which does exclude a subdirectory today, proving the exclude mechanism is already in active use elsewhere in this same file family — just not yet on the commonMain-side glob).

Consequence: **moving a package's files out of the monolith's glob is not automatic — it requires an explicit `exclude` entry (or, more robustly, rewriting the glob from a repo-root wildcard to an explicit list of the *remaining* top-level package directories) at the same time the new per-package target is created.** Bazel's `glob()` re-evaluates at each build against whatever files currently match the pattern in that package's directory tree; since the pilot plan does **not** propose physically moving files to a new directory (the packages stay at `kmp/src/commonMain/kotlin/dev/stapler/stelekit/<pkg>/`, only a new sibling `BUILD.bazel` is added under each package directory), `glob(["**/*.kt"])` at the `kmp/src/commonMain/kotlin/` root will **continue to match the pilot packages' files** unless the glob is edited to exclude them.

Two viable approaches, to be decided in Phase 3 planning:
1. **Explicit exclude, incremental**: add `exclude = ["dev/stapler/stelekit/stats/**", "dev/stapler/stelekit/logging/**", ...]` to `kt_srcs`'s glob for each migrated package, one line per package as it's extracted. Minimal diff per PR, directly matches the "each package's extraction should be revertible independently" constraint — reverting is deleting one exclude line.
2. **Invert to an explicit include list of remaining packages**: rewrite `kt_srcs` from a wildcard to `glob(["dev/stapler/stelekit/{model,platform,cache,...}/**/*.kt"])` enumerating only non-yet-migrated packages. Higher diff churn per PR (the include list changes every migration) but makes "what's still in the monolith" self-documenting from the BUILD file instead of requiring cross-referencing an exclude list against the full package inventory.

Given the revertibility constraint and the "small, independently mergeable PRs" appetite, **option 1 (explicit excludes) is the better fit** — it's strictly additive-per-PR and trivially revertible, whereas option 2's include-list requires editing the same line on every single package migration (higher merge-conflict risk across concurrently-in-flight migration PRs, which the appetite explicitly wants to support as "multiple incremental, independently-mergeable PRs").

Whichever approach is chosen, the **same glob/exclude edit must be made in three places in lockstep** for every migrated package, because `kt_srcs` is consumed by both `android_main` and `jvm_main_lib`, and because `android_main`/`jvm_main_lib` each *also* have their own `+ glob(["**/*.kt"])` for their own platform-specific directories (unaffected by a commonMain-package exclude, but a reminder that the exclude only needs to land in the shared `commonMain/kotlin/BUILD.bazel` `kt_srcs` filegroup, not separately in `androidMain`'s or `jvmMain`'s own BUILD files, since both consume `kt_srcs` by reference rather than re-globbing commonMain themselves).

**Two-different-compiled-versions risk**: because `kt_srcs` is a single filegroup referenced identically by both `android_main` and `jvm_main_lib`, and because Bazel's action graph is content-addressed (a target's inputs, not its label, determine its cache key), there is no scenario under this design where `android_main` and `jvm_main_lib` see *different* contents for the same still-monolithic package — both consume the same `kt_srcs` filegroup output at the same commit, so they're always consistent with each other. The actual risk is narrower and one-directional: if a package is extracted into `:stats` (new target) but the `exclude` edit to `kt_srcs`'s glob is forgotten or lands in a separate, out-of-sync commit, `stats/*.kt` would be compiled **twice** — once inside the monolith (via the stale glob) and once in the new `:stats` target — producing duplicate-class errors at link/compile time in whichever target ends up depending on both (e.g. a test target with `associates` on both `jvm_main_lib` and `:stats`). This is a fail-fast condition (Bazel/kotlinc will error on duplicate top-level declarations across two same-package inputs in one compilation unit), not a silent-corruption risk — but it does mean **the glob-exclude edit and the new per-package target's creation must land in the same commit/PR**, not sequenced across two PRs, to avoid a broken intermediate state. This should be an explicit checklist item in the Phase 3 rollout plan and a natural candidate for a CI-time structural check (e.g. asserting no `.kt` file is claimed by more than one `kt_srcs`-derived compilation unit) alongside the internal-usage and cycle checks already scoped.

## Sources

- `kmp/src/commonMain/kotlin/BUILD.bazel:1-35`
- `kmp/src/androidMain/kotlin/BUILD.bazel:1-177`
- `kmp/src/jvmMain/kotlin/BUILD.bazel:1-137`
- `kmp/src/jvmCommonMain/kotlin/BUILD.bazel:1-37`
- `kmp/src/commonTest/kotlin/BUILD.bazel:1-34`
- `kmp/src/jvmTest/kotlin/BUILD.bazel:1-64`
- `kmp/src/androidUnitTest/kotlin/BUILD.bazel:1-57`
- `kmp/src/businessTest/kotlin/BUILD.bazel:1-36`
- `kmp/BUILD.bazel:1-104`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/stats/` (directory listing + `grep -rlE '\b(expect|actual)\b'` / `grep -rn 'internal '` — both empty)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/search/*.kt` (`grep -rhoE '^import dev\.stapler\.stelekit\.[a-zA-Z.]+'` — imports from `error`, `model`, `repository`; `grep -rlE '\b(expect|actual)\b'` — empty)
- `project_plans/commonmain-bazel-target-split/requirements.md` (full read, all sections)
