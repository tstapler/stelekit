# Validation Plan: stelekit-bazel

**Date**: 2026-05-17

---

## Overview

This document maps every requirement from `requirements.md` to concrete test cases. Tests are organized into four categories: Build Validation, Test Migration, Correctness, and CI Integration. Each test carries a unique ID, the requirement(s) it covers, the pass criterion, and whether it is automated or manual.

---

## Requirement → Test Mapping

| Requirement | Test ID | Test Name | Type | Scenario |
|---|---|---|---|---|
| G1: All KMP targets build | BV-01 | `bazel build //kmp:desktop_app` succeeds on clean checkout | Build | Happy path — JVM/Desktop |
| G1: All KMP targets build | BV-02 | `bazel build //kmp:android_app` succeeds on clean checkout | Build | Happy path — Android APK |
| G1: All KMP targets build | BV-03 | `bazel build //kmp:desktop_app` fails with informative error when Kotlin toolchain missing | Build | Error path — misconfigured toolchain |
| G1: All KMP targets build | BV-04 | `bazel build //kmp/src/generated/sqldelight:sqldelight_generated_jvm` succeeds | Build | SQLDelight generated sources visible to Bazel |
| G2: Full Gradle replacement | CV-01 | `find . -name "*.gradle.kts"` returns zero results after Epic 7 | Correctness | Happy path — Gradle files removed |
| G2: Full Gradle replacement | CV-02 | `find . -name "gradlew"` returns zero results after Epic 7 | Correctness | Happy path — wrapper removed |
| G2: Full Gradle replacement | CV-03 | `bazel build //...` passes with no Gradle fallback step in CI after Epic 7 | Correctness | Error path — no hidden Gradle dependency |
| G3: Incremental builds < 5s | BV-05 | Touch single `.kt` file; measure `bazel build //kmp:desktop_app` wall time | Build | Happy path — incremental rebuild |
| G3: Incremental builds < 5s | BV-06 | Touch multiple `.kt` files in unrelated package; verify only affected actions re-run | Build | Happy path — action graph correctness |
| G3: Incremental builds < 5s | BV-07 | Touch a `.kt` file in `androidMain`; verify `//kmp:desktop_app` action graph is unaffected | Build | Error path — no cross-target invalidation |
| G4: Remote caching via GHA | CI-01 | Second CI run on identical commit shows >50% cache hit rate in BuildBuddy dashboard | CI | Happy path — remote cache hit |
| G4: Remote caching via GHA | CI-02 | PR build with `--noremote_upload_local_results` does not poison main-branch cache | CI | Error path — cache isolation |
| G4: Remote caching via GHA | CI-03 | `maven_install.json` lock file present; `bazel build //...` succeeds without network after cold start | Build | Happy path — hermetic Maven resolution |
| G5: CI passes on Bazel | CI-04 | `bazel-ci.yml` `bazel-jvm` job passes on PR push | CI | Happy path — JVM CI job green |
| G5: CI passes on Bazel | CI-05 | `bazel-ci.yml` `bazel-android` job passes on PR push | CI | Happy path — Android CI job green |
| G5: CI passes on Bazel | CI-06 | `bazel-ci.yml` `bazel-android-tests` job passes on PR push | CI | Happy path — Android unit test CI job green |
| G5: CI passes on Bazel | CI-07 | Detekt CI job still runs and blocks merge on linting failures | CI | Error path — lint gate enforced |
| G5: CI passes on Bazel | CI-08 | Existing Gradle screenshot/Roborazzi CI jobs still pass (no regression) | CI | Error path — no Gradle regression |
| G6: Developer experience | BV-08 | `bazel run //kmp:desktop_app` launches the application window | Build | Happy path — run alias works |
| G6: Developer experience | TM-01 | `bazel test //kmp:jvm_tests` runs and all non-screenshot tests pass | Test Migration | Happy path — jvmTest suite |
| G6: Developer experience | TM-02 | `bazel test //kmp:business_tests` (or `//kmp/src/businessTest/kotlin:business_tests`) passes | Test Migration | Happy path — businessTest suite |
| G6: Developer experience | TM-03 | `bazel test //kmp/src/androidUnitTest/kotlin:android_unit_tests` passes | Test Migration | Happy path — Android unit tests |
| G6: Developer experience | TM-04 | `bazel mobile-install //kmp:android_app` installs APK on a connected device | Test Migration | Happy path — install alias |

---

## Test Cases — Detailed Specifications

### Category 1: Build Validation (BV)

#### BV-01: Clean JVM/Desktop build
- **ID**: BV-01
- **Description**: From a clean workspace with no prior Bazel output, `bazel build //kmp:desktop_app` (or equivalent alias) completes without error and produces a runnable JAR/binary.
- **Requirements covered**: G1, G6
- **Pass criteria**: Exit code 0; output artifact exists at `bazel-bin/kmp/src/jvmMain/kotlin/desktop_app`.
- **Automated/Manual**: Automated (CI job `bazel-jvm`)
- **Test command**: `bazel clean --expunge && bazel build //kmp:desktop_app`
- **Epic**: 2 (Story 2.3, Task 2.3.1)

#### BV-02: Clean Android build
- **ID**: BV-02
- **Description**: From a clean workspace, `bazel build //kmp:android_app` completes without error and produces a valid debug APK.
- **Requirements covered**: G1, G6
- **Pass criteria**: Exit code 0; `bazel-bin/androidApp/android_app.apk` exists and is a valid ZIP with `classes.dex`.
- **Automated/Manual**: Automated (CI job `bazel-android`)
- **Test command**: `bazel clean --expunge && bazel build //kmp:android_app`
- **Epic**: 3 (Story 3.3, Task 3.3.2)

#### BV-03: Toolchain misconfiguration error path
- **ID**: BV-03
- **Description**: If `MODULE.bazel` references a non-existent Kotlin toolchain version, `bazel build //kmp:desktop_app` fails with a clear, actionable error message (not a cryptic Starlark traceback).
- **Requirements covered**: G1
- **Pass criteria**: Exit code non-zero; error message references toolchain or `rules_kotlin` module, not an internal Bazel crash.
- **Automated/Manual**: Manual (one-time verification during scaffolding)
- **Epic**: 1 (Story 1.1, Task 1.1.3)

#### BV-04: SQLDelight generated sources compile
- **ID**: BV-04
- **Description**: `bazel build //kmp/src/generated/sqldelight:sqldelight_generated_jvm` and `:sqldelight_generated_android` succeed, confirming checked-in generated sources are recognized by Bazel.
- **Requirements covered**: G1
- **Pass criteria**: Exit code 0 for both targets; no "unresolved reference" errors in downstream builds.
- **Automated/Manual**: Automated (included in `bazel build //...`)
- **Test command**: `bazel build //kmp/src/generated/sqldelight:sqldelight_generated_jvm //kmp/src/generated/sqldelight:sqldelight_generated_android`
- **Epic**: 2 (Story 2.1, Task 2.1.2)

#### BV-05: Incremental rebuild < 5s after single file change
- **ID**: BV-05
- **Description**: After a successful cold build, touch a single `.kt` file in `commonMain` and re-run `bazel build //kmp:desktop_app`. The wall-clock time must be under 5 seconds.
- **Requirements covered**: G3
- **Pass criteria**: `time bazel build //kmp:desktop_app` reports real elapsed time < 5s on developer machine (Linux or macOS, 8+ cores). Must also be < 5s on the CI runner.
- **Automated/Manual**: Manual (benchmarked once per major Bazel rule version upgrade; optionally automated via a timing script in CI)
- **Measurement script**: `touch kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt && time bazel build //kmp:desktop_app`
- **Epic**: Spans Epics 1–2

#### BV-06: Action-graph correctness — only affected actions re-run
- **ID**: BV-06
- **Description**: After touching a file in `commonMain`, `bazel build //kmp:desktop_app --explain=/tmp/bazel_explain.txt` shows that only the affected `KotlinCompile` action and its dependents are re-executed, not the full graph.
- **Requirements covered**: G3
- **Pass criteria**: `grep "^  Executing" /tmp/bazel_explain.txt | wc -l` returns a number significantly less than the total action count from a clean build.
- **Automated/Manual**: Manual (reviewed once during Epic 2 validation)
- **Epic**: 1–2

#### BV-07: Cross-target isolation — androidMain change does not invalidate JVM build
- **ID**: BV-07
- **Description**: Touch a `.kt` file in `kmp/src/androidMain/`. `bazel build //kmp:desktop_app` reports all actions cached (no re-execution).
- **Requirements covered**: G3
- **Pass criteria**: `bazel build //kmp:desktop_app 2>&1 | grep "INFO: Build completed"` includes "0 actions executed" or all actions shown as "(cached)".
- **Automated/Manual**: Manual (one-time verification during Epic 3)
- **Epic**: 3

#### BV-08: `bazel run //kmp:desktop_app` launches application
- **ID**: BV-08
- **Description**: `bazel run //kmp:desktop_app` starts the SteleKit desktop application. The main window appears within 10 seconds.
- **Requirements covered**: G6
- **Pass criteria**: Application process starts; desktop window visible; no immediate crash or `ClassNotFoundException`. Verified manually.
- **Automated/Manual**: Manual
- **Epic**: 2 (Task 2.3.1)

---

### Category 2: Test Migration (TM)

#### TM-01: JVM test suite parity
- **ID**: TM-01
- **Description**: `bazel test //kmp:jvm_tests` runs the same set of tests as `./gradlew jvmTest` (minus screenshot/Roborazzi tests which are excluded). All must pass.
- **Requirements covered**: G5, G6
- **Pass criteria**: Exit code 0; test count in Bazel output matches Gradle jvmTest count minus Roborazzi-excluded count (verified by comparing `Tests run` totals). Zero failures.
- **Automated/Manual**: Automated (CI job `bazel-jvm`)
- **Test command**: `bazel test //kmp:jvm_tests --test_output=summary`
- **Epic**: 4 (Story 4.2, Task 4.2.1)

#### TM-02: Business test suite parity
- **ID**: TM-02
- **Description**: `bazel test //kmp/src/businessTest/kotlin:business_tests` (or top-level alias if added) runs and all business-logic tests pass.
- **Requirements covered**: G5, G6
- **Pass criteria**: Exit code 0; all tests pass; count matches Gradle `businessTest` output.
- **Automated/Manual**: Automated (included in `bazel test //...`)
- **Test command**: `bazel test //kmp/src/businessTest/kotlin:business_tests --test_output=summary`
- **Epic**: 4 (Story 4.1, Task 4.1.1)

#### TM-03: Android unit test suite parity
- **ID**: TM-03
- **Description**: `bazel test //kmp/src/androidUnitTest/kotlin:android_unit_tests` runs and all non-screenshot Android tests pass.
- **Requirements covered**: G5, G6
- **Pass criteria**: Exit code 0; test count matches `./gradlew testDebugUnitTest` count minus Roborazzi-excluded count.
- **Automated/Manual**: Automated (CI job `bazel-android-tests`)
- **Test command**: `bazel test //kmp/src/androidUnitTest/kotlin:android_unit_tests --test_output=summary`
- **Epic**: 4 (Story 4.3, Task 4.3.1)

#### TM-04: Arrow Either types compile and function correctly
- **ID**: TM-04
- **Description**: At least one existing test that exercises Arrow `Either` (happy path + error path) passes under Bazel, confirming Arrow 2.2.1.1 resolves correctly with the JVM classifier.
- **Requirements covered**: G1, G5
- **Pass criteria**: No `ClassNotFoundException` or `NoClassDefFoundError` for `arrow.core.Either`; the test passes.
- **Automated/Manual**: Automated (covered by TM-01/TM-02 if Arrow tests exist in those suites)
- **Note**: Identify a specific existing Arrow test (e.g., in `repository/` tests) during Epic 4 and verify it is included in the Bazel test run.
- **Epic**: 4

#### TM-05: Coroutines and `kotlinx-coroutines-test` available in test scope
- **ID**: TM-05
- **Description**: Tests using `runTest {}` from `kotlinx-coroutines-test` compile and run correctly under Bazel.
- **Requirements covered**: G5
- **Pass criteria**: No missing-symbol compile errors for `kotlinx.coroutines.test.*`; tests using `runTest {}` pass.
- **Automated/Manual**: Automated (covered by TM-01/TM-02)
- **Epic**: 4

#### TM-06: `bazel test //...` — full suite run
- **ID**: TM-06
- **Description**: `bazel test //...` runs all test targets (jvm_tests, business_tests, android_unit_tests) in a single invocation and all pass.
- **Requirements covered**: G5, G6
- **Pass criteria**: Exit code 0; all three test targets appear in summary output; zero failures.
- **Automated/Manual**: Automated (CI)
- **Test command**: `bazel test //... --test_output=summary`
- **Epic**: 4

---

### Category 3: Correctness (CV)

#### CV-01: SQLDelight generated code matches Gradle output
- **ID**: CV-01
- **Description**: Run `./gradlew :kmp:generateCommonMainSteleDatabase`, then `diff -r kmp/build/generated/sqldelight/commonMain/kotlin/ kmp/src/generated/sqldelight/`. The diff must be empty (or only contain documented intentional differences).
- **Requirements covered**: G1, G2
- **Pass criteria**: `diff` exits 0, or any differences are explained and accepted in a comment in the generated file header.
- **Automated/Manual**: Manual (run after any `.sq` schema change)
- **Regeneration command**: `./gradlew :kmp:generateCommonMainSteleDatabase && rsync -a kmp/build/generated/sqldelight/commonMain/kotlin/ kmp/src/generated/sqldelight/`
- **Epic**: 2 (Story 2.1)

#### CV-02: Compose UI smoke test — desktop app renders
- **ID**: CV-02
- **Description**: After `bazel run //kmp:desktop_app`, open a graph directory. Verify the page list renders and a page opens without Compose runtime errors.
- **Requirements covered**: G1, G6
- **Pass criteria**: No `ComposeRuntimeError` or `IllegalStateException` from Compose; UI is interactive.
- **Automated/Manual**: Manual
- **Epic**: 2 (Task 2.3.1)

#### CV-03: No Gradle fallback in CI after Gradle removal (Epic 7)
- **ID**: CV-03
- **Description**: After Epic 7, the CI workflow file contains no `gradle` or `gradlew` commands (verified by grep).
- **Requirements covered**: G2
- **Pass criteria**: `grep -r "gradlew\|gradle " .github/workflows/` returns zero results.
- **Automated/Manual**: Automated (CI lint step or pre-commit hook)
- **Epic**: 7

#### CV-04: `bazel mod graph` and `bazel fetch //...` succeed hermeticity check
- **ID**: CV-04
- **Description**: From a clean checkout with network access disabled (e.g., `--sandbox_default_allow_network=false` in `.bazelrc`), `bazel build //kmp:desktop_app` succeeds using only the locked Maven artifacts.
- **Requirements covered**: G1, G4
- **Pass criteria**: Build completes without any "network not allowed" or "could not download" errors. Lockfile `maven_install.json` satisfies all dependencies.
- **Automated/Manual**: Automated (`.bazelrc` sets `--sandbox_default_allow_network=false` globally)
- **Epic**: 1 (Story 1.1, Task 1.1.4)

#### CV-05: KMP classifier audit — no ambiguous artifact resolution
- **ID**: CV-05
- **Description**: `bazel build @maven//:pin` (i.e., `bazel run @maven//:pin`) completes without "multiple artifacts matching" warnings. The tracking table in `MODULE.bazel` comments documents classifier choices for all KMP libraries.
- **Requirements covered**: G1
- **Pass criteria**: Zero "ambiguous artifact" warnings from `rules_jvm_external`; lock file committed.
- **Automated/Manual**: Automated (Maven pin step in CI or local setup)
- **Epic**: 1 (Story 1.2, Task 1.2.1)

---

### Category 4: CI Integration (CI)

#### CI-01: Remote cache hit on second CI run
- **ID**: CI-01
- **Description**: After a main-branch push seeds the BuildBuddy remote cache, a subsequent CI run on the same commit (or a PR touching unrelated files) shows >50% action cache hit rate.
- **Requirements covered**: G4
- **Pass criteria**: BuildBuddy dashboard for the second run shows `Cache hit rate: >50%`. Measured via `--build_event_log_file=build_events.json` parsed in CI or viewed in BuildBuddy UI.
- **Automated/Manual**: Manual (inspected in BuildBuddy after first two CI runs)
- **Epic**: 5 (Story 5.1, Task 5.1.2)

#### CI-02: PR builds do not upload to cache
- **ID**: CI-02
- **Description**: PR CI jobs run with `--config=ci-pr` (`--noremote_upload_local_results`). Verify that PR artifacts do not appear as new entries in BuildBuddy cache viewer.
- **Requirements covered**: G4
- **Pass criteria**: After a PR build, no new cache entries are visible in BuildBuddy that did not already exist from main-branch runs.
- **Automated/Manual**: Manual (inspected in BuildBuddy)
- **Epic**: 5 (Story 5.1, Task 5.1.2)

#### CI-03: Hermetic build — no Maven network calls during build
- **ID**: CI-03
- **Description**: `maven_install.json` lock file is committed. In CI, `bazel build //...` does not make outbound Maven Central requests during compilation (only during `bazel fetch` pre-step).
- **Requirements covered**: G4
- **Pass criteria**: CI network log (or `--sandbox_default_allow_network=false` enforcement) shows no Maven Central connections during `bazel build`.
- **Automated/Manual**: Automated (`--sandbox_default_allow_network=false` in `.bazelrc` enforces this)
- **Epic**: 1 (Task 1.1.4) and 5 (Task 5.1.1)

#### CI-04: Bazel JVM CI job passes on PR
- **ID**: CI-04
- **Description**: The `bazel-jvm` GitHub Actions job (running `bazel test //kmp:jvm_tests --config=ci`) passes on a PR against `main`.
- **Requirements covered**: G5
- **Pass criteria**: GitHub Actions job shows green checkmark; `bazel-ci.yml` `bazel-jvm` job status = success.
- **Automated/Manual**: Automated
- **Epic**: 5 (Story 5.1, Task 5.1.1)

#### CI-05: Bazel Android build CI job passes on PR
- **ID**: CI-05
- **Description**: The `bazel-android` job (running `bazel build //kmp:android_app --config=ci`) passes on a PR.
- **Requirements covered**: G5
- **Pass criteria**: GitHub Actions `bazel-android` job status = success; APK artifact produced.
- **Automated/Manual**: Automated
- **Epic**: 5 (Task 5.1.1)

#### CI-06: Bazel Android unit test CI job passes on PR
- **ID**: CI-06
- **Description**: The `bazel-android-tests` job passes on a PR.
- **Requirements covered**: G5
- **Pass criteria**: GitHub Actions `bazel-android-tests` job status = success; all Android unit tests pass.
- **Automated/Manual**: Automated
- **Epic**: 5 (Task 5.1.1)

#### CI-07: Detekt still blocks merge on lint failures
- **ID**: CI-07
- **Description**: Introduce a deliberate Detekt violation in a PR. Verify the Detekt CI job fails and blocks merge.
- **Requirements covered**: G5
- **Pass criteria**: Detekt job exits non-zero; GitHub branch protection prevents merge.
- **Automated/Manual**: Manual (one-time verification during Epic 6)
- **Epic**: 6 (Story 6.1, Task 6.1.1)

#### CI-08: Gradle screenshot/Roborazzi jobs still pass
- **ID**: CI-08
- **Description**: During the transition period (Epics 1–6), the existing Gradle-based CI jobs for screenshot tests and Roborazzi still pass on every PR.
- **Requirements covered**: G5
- **Pass criteria**: All pre-existing Gradle CI jobs that were passing before the Bazel migration continue to pass. Zero new failures attributable to the Bazel migration.
- **Automated/Manual**: Automated (existing CI jobs)
- **Epic**: 5 (Task 5.1.1) — maintained alongside Bazel jobs

---

## Test Stack

- **Unit / Build**: Bazel (`bazel build`, `bazel test`) with `rules_kotlin` `kt_jvm_test` and `android_local_test`
- **JVM test framework**: `kotlin-test-junit` (JUnit 4 runner via `kt_jvm_test`)
- **Android test framework**: Robolectric 4.14.1 via `android_local_test`; `androidx.test.ext:junit`
- **Coroutine testing**: `kotlinx-coroutines-test` 1.10.2
- **Remote cache validation**: BuildBuddy free tier dashboard + `--build_event_log_file`
- **CI**: GitHub Actions via `bazel-contrib/setup-bazel` + `bazel-ci.yml`
- **Benchmark / timing**: `time` command + manual wall-clock measurement for G3 (BV-05)
- **Screenshot tests (Gradle-only, out of scope for Bazel)**: Roborazzi + `roborazzi-compose`

---

## Coverage Targets

- All 6 requirements (G1–G6) covered by at least 2 test cases each
- Unit / build test coverage: all public Bazel targets (`kt_jvm_library`, `kt_android_library`, `kt_jvm_test`, `android_local_test`) have at least one automated build or test validation
- All external integrations mocked or locked: Maven resolution via lockfile (CV-04), remote cache validated via BuildBuddy (CI-01)
- Test count goal: ≥20 automated test cases (mix of build targets and test suite runs); 8 manual verification steps

---

## Requirement Traceability Matrix

| Requirement | Description | Test IDs | Coverage |
|---|---|---|---|
| G1 | All KMP targets build (JVM + Android in scope) | BV-01, BV-02, BV-03, BV-04, TM-04, CV-01, CV-04, CV-05 | 8/8 test cases — full |
| G2 | Full Gradle replacement | CV-01, CV-02, CV-03 | 3/3 test cases — full (Epic 7 gate) |
| G3 | Incremental builds < 5s | BV-05, BV-06, BV-07 | 3/3 test cases — full |
| G4 | Remote caching via GHA | CI-01, CI-02, CI-03, BV-03 (hermetic), CV-04 | 5/5 test cases — full |
| G5 | CI passes using Bazel | CI-04, CI-05, CI-06, CI-07, CI-08, TM-01, TM-02, TM-03, TM-06 | 9/9 test cases — full |
| G6 | Developer experience preserved | BV-08, TM-01, TM-02, TM-03, TM-04 | 5/5 test cases — full |

**Overall requirement coverage: 6/6 (100%)**

---

## Test Execution Order (per Epic)

| Epic | Tests to run before marking Epic done |
|---|---|
| Epic 1: Bazel Scaffolding | CV-04, CV-05, BV-03 (toolchain error path) |
| Epic 2: JVM/Desktop | BV-01, BV-04, BV-05, BV-06, BV-08, CV-01, CV-02 |
| Epic 3: Android | BV-02, BV-07 |
| Epic 4: Test Targets | TM-01, TM-02, TM-03, TM-04, TM-05, TM-06 |
| Epic 5: CI Integration | CI-01, CI-02, CI-03, CI-04, CI-05, CI-06, CI-08 |
| Epic 6: Detekt | CI-07 |
| Epic 7: Gradle Removal | CV-01 (re-run), CV-03 |

---

## Open Risks and Mitigations

| Risk | Test that catches it | Mitigation |
|---|---|---|
| `commonMain` compiled twice (JVM + Android) doubles action count, slowing incremental builds | BV-05, BV-06 | Monitor action graph depth; ADR-005 documents the trade-off |
| Compose compiler plugin K2 compatibility breaks on Kotlin upgrade | BV-01, TM-01 | Pin `suppressKotlinVersionCompatibilityCheck` to `2.3.21`; ADR-004 documents the risk |
| SQLDelight schema drift (generated sources out of sync with `.sq` files) | CV-01 | Document regeneration in `README_REGEN.md`; add `./gradlew generateCommonMainSteleDatabase` to PR checklist |
| BuildBuddy free tier storage cap hit | CI-01, CI-02 | Monitor BuildBuddy dashboard; configure `--remote_cache_eviction_retries` |
| Robolectric `android-all` JAR not available in `maven_install.json` | TM-03 | Include `@robolectric//bazel:android-all` via the `robolectric` Bzlmod dep; test in Epic 4 |
