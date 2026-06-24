# Requirements: bazel-full-ci-migration

**Date**: 2026-06-24
**Type**: migration
**Complexity**: 4 — high-stakes / cross-cutting

## Problem Statement

SteleKit's CI is split across two build systems: `bazel-ci.yml` owns JVM tests, Android
unit tests, Android APK build, and WASM bundle build; `ci.yml` still runs Detekt (lint),
Roborazzi screenshot tests + APK assembly, and Android emulator smoke tests under Gradle.
This split forces every contributor to reason about two toolchains, prevents Bazel's
remote-cache and hermetic-sandbox benefits from applying to lint and screenshot coverage,
and means the Gradle daemon and associated classpath resolution runs in CI even for
targets Bazel already owns.

## Baseline

Today's CI runs two parallel workflow files:
- `ci.yml`: JVM tests (Gradle), Android tests + Roborazzi (Gradle), Detekt (Gradle),
  Android emulator smoke tests (Gradle), WASM compile-check (Gradle)
- `bazel-ci.yml`: JVM tests (Bazel), Android APK (Bazel), Android unit tests (Bazel),
  WASM build via Gradle genrule (Bazel shell)

Three Gradle jobs remain with no Bazel equivalent:
1. `Lint (Detekt)` — `./gradlew :kmp:detekt`
2. `Android` (Roborazzi screenshot record/verify + APK) — `./gradlew :kmp:recordRoborazziDebug :androidApp:assembleDebug`
3. `Android Smoke Tests (Emulator)` — `./gradlew :androidApp:connectedDebugAndroidTest`

## Users / Consumers

- **Tyler Stapler** (sole developer) — runs `bazel test //...` locally and interprets CI
- **GitHub Actions CI** — must produce the same blocking checks as today, gating PRs
- **Release pipeline** — `bazel-ci.yml` feeds into release artifact generation

## Success Metrics

1. `bazel test //...` locally covers lint, screenshot verification, and unit tests without
   any Gradle invocation (measured: `./gradlew` absent from the critical CI path).
2. Detekt violations caught by `bazel test` — zero regressions vs. current Detekt config.
3. Roborazzi screenshot baselines stored in the repo and verified by Bazel; recorded
   screenshots update via `bazel run` target (not a Gradle task).
4. Android emulator smoke tests (`AppSmokeTest`, `SqliteCapabilityTest`) run under
   `bazel test` with a GHA-managed emulator — same pass/fail signal as today.
5. `ci.yml` Gradle jobs for Detekt, Roborazzi, and smoke tests are removed (replaced by
   `bazel-ci.yml` equivalents); total CI wall-clock time does not regress by > 20%.

## Appetite

**Large — 3–6 weeks**
*(Scope must fit the appetite. If it doesn't fit, cut scope — do not move the deadline.)*

Suggested epic order by risk and dependency:
1. Detekt (lowest risk, highest daily value, unblocks knowing the Bazel detekt pattern)
2. Roborazzi (medium risk, complex Gradle plugin, core screenshot test coverage)
3. Android emulator smoke tests (highest risk, requires `android_instrumentation_test` or
   alternative, most infrastructure-dependent)

## Constraints

- **Bazel version**: 9.1.1 (pinned in `.bazelversion`)
- **rules_kotlin**: 2.3.20 — must remain compatible; no major version upgrade without a
  separate PR
- **rules_android**: 0.7.1 — `android_instrumentation_test` is experimental; must not
  break the existing `android_unit_tests` or APK build targets
- **iOS stays Gradle**: `ci-ios.yml` is macOS + Xcode only; no Bazel Kotlin/Native
  support exists — out of scope permanently for this project
- **WASM native rules**: blocked on `rules_kotlin#567`; the existing Gradle-genrule
  wrapper for `//kmp:web_app` stays until upstream support lands — out of scope
- **No screenshot baseline churn**: Roborazzi migration must not force re-recording all
  baselines; existing PNG files in `kmp/src/test/snapshots/` must remain valid

## Non-functional Requirements

- **Performance SLO**: Bazel CI wall-clock must not regress > 20% vs. current Gradle
  times for each replaced job (Detekt: ~2 min, Roborazzi: ~8 min, smoke: ~12 min)
- **Scalability**: N/A — single-developer project; CI parallelism is horizontal (jobs)
- **Security classification**: internal (OSS, public repo)
- **Data residency**: no special requirements

## Scope

### In Scope

- Bazel target for **Detekt** — `bazel test //kmp:detekt` or equivalent aspect
- Bazel target for **Roborazzi screenshot tests** — verify mode (`bazel test`) and
  record mode (`bazel run`)
- Bazel target for **Android emulator smoke tests** — `android_instrumentation_test`
  or equivalent, wired to GHA's `reactivecircus/android-emulator-runner`
- Update `bazel-ci.yml` to replace corresponding Gradle jobs
- Remove or mark-defunct the Gradle jobs in `ci.yml` that are replaced
- Update `CLAUDE.md` build table to reflect new canonical Bazel commands

### Out of Scope

- iOS (`ci-ios.yml`) — macOS + Xcode, no Bazel support
- WASM native rules — blocked upstream (`rules_kotlin#567`)
- Detekt configuration changes (rule tuning) — separate concern
- Migration of the Gradle build itself (kmp/build.gradle.kts) — Bazel is additive
- Benchmarks (`android-benchmark.yml`, `benchmark.yml`) — separate Epic 7 item
- `fdroid.yml`, `pages.yml`, `release.yml` — independent, not test/lint pipeline

## Rabbit Holes

- **Roborazzi Bazel plugin**: No published `rules_roborazzi` exists. Integration options
  are (a) genrule wrapping Gradle, (b) `kt_android_library` test with a custom Roborazzi
  runner, or (c) rewriting screenshots in Paparazzi (different library). Deep dive
  needed in Phase 2 to pick the right approach without painting into a corner.
- **Detekt classpath**: Detekt needs the compiled classpath of the module under analysis.
  Using it as a Bazel `java_test` vs. a `genrule` vs. a `kt_compiler_plugin` approach
  each have different classpath implications. Must pick one without reopening the whole
  Kotlin compilation graph.
- **`android_instrumentation_test` emulator management**: rules_android 0.7.1's
  instrumentation test support may require a running emulator AVD before the test
  action fires. GHA's `reactivecircus/android-emulator-runner` starts the emulator as
  a job step — wiring this into Bazel's hermetic sandbox model is non-trivial.
- **Screenshot baseline storage**: Roborazzi currently stores baselines under
  `kmp/src/test/snapshots/images/`. Bazel tests run in a sandbox; the baseline PNGs
  must be declared as `data` deps and diffed rather than written in-place.

## Alternatives Considered

- **Keep Gradle for lint/screenshots/emulator forever**: Avoids migration cost but
  perpetuates the dual-toolchain split and prevents Bazel remote-cache from covering
  these jobs.
- **Paparazzi instead of Roborazzi**: Paparazzi has better Bazel integration via
  `rules_jvm_external`; switching would require re-recording all baselines and
  might differ in rendering fidelity (Roborazzi uses real Compose rendering).
- **Remote build execution (RBE)**: A longer-term accelerant but orthogonal to the
  toolchain migration; not in scope here.

## Feasibility Risks

1. **Roborazzi has no native Bazel rules**: The genrule-wrapping-Gradle approach is
   the most likely path but reintroduces Gradle for that target — defeating the purpose.
   The native `kt_android_library` approach requires wiring Roborazzi's annotation
   processor and test runner against Bazel's Kotlin compilation, which may have
   classpath incompatibilities.
2. **`android_instrumentation_test` is experimental in rules_android 0.7.1**: May
   require patching (as we did for zip/unzip) or a version bump with unknown
   compatibility impact on existing targets.
3. **Hermetic sandbox vs. emulator**: Bazel's linux-sandbox blocks network and device
   access by default. Running emulator tests under Bazel may require
   `--sandbox_writable_path` and `--strategy=local` workarounds that weaken hermeticity.
4. **CI minutes**: Emulator startup on GHA adds ~3–5 min overhead; if Bazel's action
   overhead compounds this, the 20% regression constraint may require parallelization.

## Observability Requirements

- Each new `bazel test` target must emit JUnit XML under `bazel-testlogs/` so the
  existing `EnricoMi/publish-unit-test-result-action` publisher in CI can consume them.
- Screenshot diff failures must produce a human-readable artifact (diff image or HTML
  report) uploadable as a GHA artifact — same UX as today's Roborazzi HTML report.

## Risk Control

- **Feature flag**: N/A — this is toolchain infrastructure, not product behavior.
- **Rollback**: Each epic is a separate PR. If Detekt Bazel integration breaks, revert
  that PR; `ci.yml` Detekt job remains until the Bazel equivalent is verified green on
  main for ≥ 3 consecutive runs.
- **Staged rollout**: Bazel jobs run alongside Gradle jobs in CI for each epic until
  confidence is established. Gradle jobs are removed only after the Bazel equivalent
  has been green on main for ≥ 3 runs.
- **Escape hatch**: Roborazzi and emulator tests can fall back to Gradle genrule wrapping
  if native integration proves infeasible within the appetite.

## Open Questions

1. Is there a `rules_detekt` or `rules_kotlin` detekt integration already published?
2. Can Roborazzi's `RoborazziRule` annotation processor be wired via `kt_android_library`
   without forking the Roborazzi repo?
3. What does `android_instrumentation_test` need from the emulator beyond ADB connectivity?
4. Can Bazel's `--strategy=TestRunner=local` run emulator tests outside the sandbox
   while preserving hermetic builds for everything else?
5. What is the GHA-minutes impact of starting the emulator as a `bazel test` side-effect
   vs. as a job step (current approach)?
