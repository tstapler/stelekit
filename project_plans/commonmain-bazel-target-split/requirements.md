# Requirements: commonmain-bazel-target-split

**Date**: 2026-09-04
**Type**: build-system refactor / migration (Bazel target architecture)
**Complexity**: 4 — high-stakes / cross-cutting (touches ~800 files across 38 top-level `commonMain` packages, prior similar-sounding Bazel change already caused a CI outage — see Feasibility Risks)

## Problem Statement

`kmp/src/androidMain/kotlin/BUILD.bazel`'s `android_main` target and the equivalent `jvm_main_lib` target each bundle the **entire** `kmp/src/commonMain/kotlin` tree (~800 files, 38 top-level packages) into their own `common_srcs`/`srcs`, because `rules_kotlin` has no real multiplatform-metadata compilation support (tracked upstream as rules_kotlin#567 — the same gap that blocks native Kotlin/Wasm support and forces the wasmJs build through Gradle today).

Effect: every platform target independently recompiles the whole common tree as one monolithic Kotlin compilation unit. Profiling a cold local build of `//kmp:android_app --config=android` showed `KotlinCompile //kmp/src/androidMain/kotlin:android_main { kt: 711 }` taking 71s — roughly half of a 138s total critical path, and the single largest action in the build graph. Because it is one compilation unit, a change to *any* file in `commonMain` — even an unrelated leaf package like `search/` — invalidates and reruns the entire 711-file/71s compile with no partial cache credit and no intra-target parallelism. This is why most PRs (which typically touch `commonMain`) see little benefit from the CI caching already in place (disk-cache, repository-cache, a custom remote-cache proxy) — a cache hit requires an *exact*-match input to the whole monolith, and any real code change busts it entirely.

Affected: engineers running local Bazel builds/tests during development, and CI (Bazel Android/JVM/test jobs on GitHub Actions).

## Baseline

Today, engineers wait out the full ~71s monolithic `commonMain` compile on nearly every incremental Bazel build/test invocation touching `commonMain` (locally and in CI), regardless of how small or isolated the actual change is. There is no partial invalidation and no way to parallelize the compile across packages. The existing CI caching infrastructure (disk-cache, repository-cache, remote-cache proxy) does not mitigate this because the monolithic target's cache key changes on almost every commonMain-touching PR.

## Users / Consumers

- Engineers (including Tyler) doing local iterative development against `//kmp:android_app`, `//kmp:desktop_app`, and `//kmp:jvm_tests`/`//kmp:business_tests` via Bazel.
- CI: the Bazel Android/JVM/test jobs in GitHub Actions that gate every PR.
- Indirectly, anyone relying on PR turnaround time / CI cost for this repo.

## Success Metrics

For **phases 1-4 (this effort)**: a plan and validated design that, once implemented, is expected to and has a concrete measurement plan to confirm:
- A change confined to one non-hub package (e.g. `search/`) invalidates and recompiles only that package's target (plus transitive dependents), not the full ~800-file monolith.
  **Post-research correction (see ADR-001)**: `search` was reclassified during Phase 2/3 research as a member of the 18-package Tier-3 import cycle, so it no longer qualifies as a "non-hub package" and does not itself validate this metric under the accepted tiered architecture — the pilot's actual worked, validated example is `stats` (plan.md Story 2.2.1 / Epic 3.1), a genuine zero-risk, zero-cycle leaf package. This note corrects the illustrative example only; the underlying success criterion (isolated single-package invalidation) is unchanged.
- Re-profiled `//kmp:android_app --config=android` and `//kmp:jvm_tests` (same methodology: cold `--disk_cache`, `--profile=...`, jq top-N by duration) after a pilot split shows the per-package compile actions are individually smaller and, in aggregate for a single-package change, off the critical path compared to the current 71s monolith — this is a hypothesis to be *confirmed by the pilot*, not assumed.
- The extraction mechanism itself (Starlark macro + supporting checks — see Scope) is proven repeatable on the pilot packages before being applied to the higher-risk hub-touching packages.

Non-goal for phases 1-4: no code is migrated yet. Success here means requirements/research/plan/validation artifacts are complete, adversarially reviewed, and readiness-gated for a human to approve before Phase 5 implementation begins.

## Appetite

Large (3-6 weeks) for the full migration across all 18 hub-touching packages, executed as multiple incremental, independently-mergeable PRs — not a single big-bang change. The plan (Phase 3) must carve out a Small (1-2 day) first slice: migrating the 9 zero-risk, zero-expect/actual, zero-hub-import packages (`benchmark`, `docs`, `error`, `logging`, `parsing`, `resilience`, `rtc`, `service`, `stats`) plus the shared tooling that makes extraction repeatable, as a trial of the *mechanism* before touching any of the 18 hub-touching packages. Scope must fit the appetite of each slice — if a slice doesn't fit, cut scope for that slice rather than slipping the whole migration.

**Actual delivered scope (post-research correction, see ADR-001)**: research established that of the 18 hub-touching packages, only 7 (`command`, `clipboard`, `flashcard`, `loader`, `outliner`, `parser`, `vault`) get independent Bazel targets under this plan — these form part of Tier 2 (11 packages total, the other 4 — `model`, `cache`, `coroutines`, `util` — having originated from the "hub" pool). The remaining 11 hub-touching packages, together with 7 originally-hub packages, form one 18-package Tarjan strongly-connected component (Tier 3, 87.8% of `commonMain` by file count) that Bazel's structural cycle-rejection makes un-splittable without a prior dependency-inversion refactor; ADR-001 records the decision to ship Tier 3 as a single merged target for now. That refactor is **explicitly out of this appetite**, deferred to a separately-chartered future project (see ADR-001's Reconsideration triggers). This plan's actual Large (3-6 week) appetite covers the shared extraction tooling, the 9-package Tier 1 pilot, the 11-package Tier 2 rollout, and the Tier 3 merge-as-one-target ADR decision itself — not independently splitting all 18 hub-touching packages, which this plan does not attempt.

## Constraints

- **Bazel only.** iOS and wasmJs remain Gradle-only per repo `CLAUDE.md` (no Bazel KMP/Wasm support) — this effort touches only the Android and JVM/Desktop Bazel targets and their test source sets (`jvmTest`, `androidUnitTest`, `commonTest`, `businessTest`).
- **CI-verified, incremental rollout is mandatory, not optional.** A prior, much smaller Bazel change in this same effort (enabling persistent workers — see `f8e7aa82e1`/`6842053f8d` in `git log`) looked safe on a 61GB/24-core local dev machine but OOM-killed CI's 16GB runner. Local "it builds and passes" is not sufficient proof for a change at this scale. Every slice of this migration must be verified against actual CI (Bazel Android/JVM/test jobs), not just local builds.
- **Reversibility preferred.** Each package's extraction should be revertible independently; avoid designs that require all-or-nothing cutover.
- No fixed external deadline; the constraint is engineering risk tolerance (see Constraints above) and the "no fix without root cause / no completion claim without proof" verification discipline that governs this repo's work generally.

## Non-functional Requirements

- **Performance SLO**: no formal SLO; target is a measurable reduction in per-PR incremental Bazel build/test time for changes confined to a single non-hub package, confirmed by before/after profiling (see Success Metrics). Full-graph clean-build time is not expected to improve (same total work, better parallelized/cached).
- **Scalability**: N/A (build-tooling change, not a runtime system).
- **Security classification**: internal — no user data or secrets involved; this is developer tooling.
- **Data residency**: not applicable.

## Scope

### In Scope
- Research and design for splitting `commonMain`'s Bazel compilation into a `:platform-core` target (the ~109 files with real `expect`/`actual` declarations, across `model`, `platform`, `cache`, `coroutines`, `util`, `performance`, plus the expect/actual-bearing subsets of `db`, `llm`, `sections`, `transfer`, `ui`) plus per-package `kt_jvm_library`/`kt_android_library` targets for the remaining ~600+ files, depending on `:platform-core`'s compiled output rather than re-including its sources.
  **Post-research correction (see ADR-001)**: this bullet's "remaining ~600+ files" per-package split did not survive Phase 2/3 research. The actually-accepted design only independently splits ~74 files across 20 packages (Tier 1: 9 packages/24 files, Tier 2: 11 packages/50 files) into per-package targets. The remaining 535 files across 18 packages (Tier 3, 87.8% of `commonMain`, one Tarjan strongly-connected component) ship as **one merged Bazel target**, not per-package targets — Bazel's structural cycle-rejection makes them un-splittable without a prior dependency-inversion refactor, which ADR-001 defers to a separately-chartered follow-on project. This note corrects the delivered-scope description only; the `:platform-core` file inventory above is unaffected.
- **Reusable extraction tooling**, planned as a first-class deliverable (not incidental to the per-package migration), so that adding target N is mechanical rather than hand-written boilerplate:
  - A shared Starlark macro (e.g. `stelekit_kt_common_library`) wrapping `kt_jvm_library`/`kt_android_library` that standardizes `kotlinc_opts`, which compiler plugins apply (`compose_compiler_plugin` / `serialization_compiler_plugin` — some but not all of the 18 hub-touching packages use `@Composable`), `associates`/friend-visibility wiring to `:platform-core`, and target visibility.
  - A pre-migration check (script or ast-grep/gritql pattern — build on existing repo conventions in `stapler-scripts/` and `.claude/` where they fit; none currently exist for this purpose, per research) that detects cross-package Kotlin `internal`-visibility usage *before* a package is migrated, so each migration's blast radius (what must become `public` or go through `associates`) is known upfront rather than discovered via a failed compile.
  - A cycle-safety check confirming no import cycle is introduced when a new target boundary is drawn. `mcp__kibitzer__architecture_assessment` is confirmed available in this environment and advertises import-cycle detection plus a Mermaid dependency graph; research must verify it works correctly against this Kotlin codebase (no `.claude/inspect.json` is currently configured in this repo) before deciding whether to adopt it, extend it, or build a bespoke script.
- A concrete, phased/incremental rollout plan: pilot slice (9 zero-risk packages + tooling) verified end-to-end in CI before any of the 18 hub-touching packages are attempted.
- A before/after measurement plan (re-profiling methodology) to confirm the invalidation-scope hypothesis after the pilot.
- Test source set (`jvmTest`, `androidUnitTest`, `commonTest`, `businessTest`) re-wiring plan alongside the main-code split.

### Out of Scope
- Actually writing/merging the migration code (that's Phase 5, gated on human approval of this plan).
- iOS and wasmJs Bazel support (they stay Gradle-only; out of scope entirely).
- Waiting on or contributing to upstream `rules_kotlin#567` (multiplatform-metadata compilation) — the `:platform-core` bundling workaround stays as-is; this effort only shrinks what sits *outside* that workaround.
- Gradle build changes (Bazel is the canonical build system per `CLAUDE.md`; Gradle path is untouched).
- Migrating all 18 hub-touching packages in one shot — deferred to be sliced further during Phase 3 planning per the incremental-rollout constraint.

## Rabbit Holes

- **Package regrouping surprises**: the `internal`-visibility audit (risk #1) or the rigorous cycle check (risk #2) may show that the coarse regex-based package list from the initial analysis needs different groupings (some packages forced to merge rather than split). Phase 3 planning must treat the current 11/9/18 package split as provisional pending this data, not fixed.
- **Compose/serialization compiler plugin scoping**: determining exactly which of the 18 hub-touching packages need which compiler plugin, and whether rules_kotlin's plugin application model works cleanly per small target, could balloon if not scoped early.
- **`associates` at scale**: rules_kotlin's friend-module mechanism is already used once in this repo (`common_test_fixtures`); using it across potentially 18+ new targets simultaneously depending on `:platform-core` is unproven at this scale here and could surface unknown issues (worker overhead, opts propagation) — flagged explicitly by the user as a research risk, not to be hand-waved.
- **Tooling scope creep**: the Starlark macro / internal-usage checker / cycle checker could expand into a much larger "build a mini build-system linter" project. Phase 3 must scope the pilot version to exactly what the 9-package pilot + one hub-touching package needs, not a speculative general framework.

## Alternatives Considered

- **Do nothing / accept the 71s monolithic compile.** Rejected: it's the single largest action in the build graph and defeats the CI caching investment already made.
- **Wait for upstream `rules_kotlin#567`** (real multiplatform-metadata compilation). Rejected as the sole plan: no committed timeline upstream; this effort's `:platform-core` split is compatible with adopting that fix later (it would let `:platform-core` itself shrink further, not invalidate the per-package split).
- **Move commonMain fully to Gradle for Bazel-tracked targets.** Rejected: contradicts the repo's stated Bazel-is-canonical direction and would regress the caching/parallelism work already done in Bazel CI.

## Feasibility Risks

1. **Kotlin `internal` visibility is module-scoped.** Splitting one compilation unit into many Kotlin modules will break any cross-package `internal` usage at compile time. Actual usage must be quantified (not assumed) before finalizing target boundaries — may force different groupings than the coarse public-import analysis.
2. **Undetected import cycles.** The initial package-dependency analysis was done with a simple regex-based one-way import scanner. A true bidirectional or indirect cycle between packages would block a clean Bazel target split for those packages (Bazel forbids target cycles) and needs a rigorous, ideally compiler-aware, check — see `mcp__kibitzer__architecture_assessment`, to be evaluated in research.
3. **Package-count discrepancy in the input analysis.** The problem statement's own dependency-graph analysis lists 11 + 9 + 18 = **38** top-level packages (confirmed by `ls kmp/src/commonMain/kotlin/dev/stapler/stelekit/`), but risk #2 in the original ask refers to "all 29 top-level commonMain packages." Research must reconcile this discrepancy (likely a stale count) before finalizing the package inventory used for planning.
   **Post-research correction (see ADR-001)**: resolved during Phase 2 research. **38 is confirmed correct** — `research/features.md`'s independent Tarjan analysis ran over a verified 129-edge, 38-top-level-package import graph (609 files total), and ADR-001 records this as conclusive. The "29" figure in the original ask was stale and is superseded; it does not change any package's hub/non-hub or tier classification, since the tier assignments in ADR-001/plan.md were derived directly from the 38-package graph.
4. **rules_kotlin friend-visibility (`associates`) and Compose/serialization plugin behavior at multi-target scale** are unproven in this repo beyond the single existing `common_test_fixtures` use — could surface worker startup overhead, `kotlinc_opts` propagation bugs, or plugin scoping issues only visible once many small targets exist.
5. **Prior precedent for "looked safe locally, broke CI"**: the persistent-workers change (commits `f8e7aa82e1`, `6842053f8d`) OOM-killed the 16GB CI runner despite passing on a 61GB/24-core dev machine. This is a directly analogous class of risk (Bazel-graph-shape change validated only locally) and must inform the rollout/verification plan.
6. **Test target wiring.** `jvmTest`/`androidUnitTest`/`commonTest`/`businessTest` BUILD files currently use `associates` against the monolithic `jvm_main_lib`/`android_main` targets; splitting main code without a corresponding, carefully sequenced test-target update plan risks a broken or silently-reduced-coverage test build.

## Observability Requirements

Standard CI signal is sufficient — no new runtime metrics/alerts needed (this is build tooling, not a running service). What must be captured and compared explicitly, though:
- Before/after Bazel `--profile=...` traces for `//kmp:android_app --config=android` and `//kmp:jvm_tests`, analyzed the same way (jq top-N action durations by critical path).
- CI job wall-clock time and (given the OOM precedent) CI runner memory headroom for the Bazel Android/JVM/test jobs, watched explicitly during each incremental rollout slice — not just "did it turn green."

## Risk Control

- **Staged rollout, not feature-flagged** (this is a build graph, not runtime-toggleable app behavior): pilot slice (9 zero-risk packages + the extraction tooling) merged and CI-verified first; each subsequent hub-touching package (or small group) migrated as its own PR, verified in CI (including the OOM-prone Android dex/desugar path) before the next.
- **Rollback**: each package's Bazel target split should be revertible independently (revert that package's BUILD.bazel changes back to `common_srcs` inclusion in the monolith) without requiring a rollback of previously-migrated packages.
- **Explicit go/no-go after the pilot**: the pilot's before/after profiling result is a checkpoint — if it doesn't show the expected invalidation-scope improvement, re-evaluate the approach before migrating the 18 hub-touching packages.

## Open Questions

- Which specific files within `db`, `llm`, `sections`, `transfer`, `ui` carry the actual `expect`/`actual` declarations that must move into `:platform-core`, and can they be cleanly extracted from the rest of their package without further splitting those packages? (Phase 2 research.)
- Does `mcp__kibitzer__architecture_assessment` produce a usable/accurate result for this Kotlin codebase, or does research need a bespoke script (or an existing Kotlin-aware tool such as a compiler-plugin-based analyzer) for cycle detection and `internal`-usage auditing? (Phase 2 research.)
- What is the true count and grouping of top-level `commonMain` packages — 38 per current listing vs. 29 referenced in the original risk analysis — and does reconciling it change any package's hub/non-hub classification? (Phase 2 research.)
  **Post-research correction (see ADR-001)**: resolved. 38 is confirmed correct (per `research/features.md`'s verified 129-edge, 38-package import graph and ADR-001's conclusive re-derivation), superseding the stale "29" figure. No hub/non-hub classification changed as a result — this question is closed, not open.
- Exact pilot scope beyond the 9 zero-risk packages: should the pilot also include one hub-touching package (as the user's original ask suggested, e.g. `repository` + `git`) to validate the harder `associates`/`:platform-core`-dependency path before committing to all 18, or should that be a distinct second slice? (Phase 3 planning decision.)
