# Implementation Plan: bazel-full-ci-migration

**Feature**: Migrate Detekt (native rules_detekt), Roborazzi screenshot tests (native android_local_test), and Android emulator smoke tests (sh_test + ADB) from Gradle CI to Bazel
**Date**: 2026-06-24
**Status**: Ready for implementation
**ADRs**: ADR-008-bazel-detekt-via-rules-detekt.md, ADR-009-roborazzi-native-android-local-test.md, ADR-010-emulator-smoke-sh-test-adb.md

---

## Domain Glossary

| Term | Definition | Notes |
|------|-----------|-------|
| `detekt_test` | A Bazel Starlark rule from `rules_detekt` that runs Detekt analysis as a test action | Emits JUnit XML under `bazel-testlogs/` |
| `rules_detekt` | BCR module `buildfoundation/bazel_rules_detekt` v0.8.1.13 providing `detekt` and `detekt_test` Bazel rules | Ships Detekt 1.23.8 internally |
| `kt_jvm_library` | Bazel rule from `rules_kotlin` that compiles a Kotlin library to a JAR | Used to build `buildSrc` custom Detekt rules |
| `SteleKitRuleSetProvider` | Service-provider class in `buildSrc` that registers all 14 custom Detekt rules | Must be in `META-INF/services` for Detekt to discover the plugin |
| `detekt-api` | Detekt's extension API JAR; compile-only dependency for custom rule authors | `compileOnly` in Gradle; on compile classpath in Bazel `kt_jvm_library` |
| `baseline.xml` | Detekt suppression file listing pre-existing violations to ignore | ~350 entries; must be passed as explicit `data` dep in the Bazel rule |
| `genrule` | Bazel built-in rule that shells out to an arbitrary command | Must carry `tags = ["local"]` to disable sandbox |
| `record_jvm_screenshots` | Bazel `sh_binary` run-target wrapping `./gradlew :kmp:jvmTest -Proborazzi.test.record=true` | JVM record stays Gradle-backed; writes PNGs in place |
| `record_android_screenshots` | Bazel `sh_binary` run-target wrapping `./gradlew :kmp:recordRoborazziDebug` | Android record stays Gradle-backed |
| `roborazzi_android_verify` | Native `android_local_test` target for Android screenshot verify mode; baseline PNGs declared as `data` deps; passes `-Droborazzi.test.verify=true` via jvm_flags | No Gradle; diff images go to `$TEST_UNDECLARED_OUTPUTS_DIR` |
| `verify_android_screenshots` | Bazel `android_local_test` target for Android Roborazzi verify mode — native Bazel, no Gradle; baselines declared as `data` deps | Replaces the former Gradle-backed sh_test |
| `smoke_tests` | `sh_test` target in `kmp/BUILD.bazel` wrapping `adb shell am instrument` for `AppSmokeTest` and `SqliteCapabilityTest`; `tags=["local","manual"]` — disables sandbox and excludes from `bazel test //...`; expects emulator already running and `$ADB_APK_PATH` pointing to `kmp-debug-androidTest.apk` | No Gradle in test execution |
| `JUnit XML` | Standard XML test result format; Bazel writes to `bazel-testlogs/<target>/test.xml` | Consumed by `mikepenz/action-junit-report` for PR annotations |
| `bazel-cache-proxy` | Rust binary bridging Bazel remote cache protocol to GitHub Actions cache API | Runs as sidecar in every `bazel-ci.yml` job |
| `--config=ci` | `.bazelrc` named config enabling color=no, curses=no, retry on network errors | Used in CI invocations |
| `--config=emulator` | `.bazelrc` named config enabling `--strategy=TestRunner=local` for the emulator sh_test; used in CI for `bazel test //kmp:smoke_tests --config=emulator` | Set in `test:emulator` stanza |
| `Roborazzi` | Screenshot testing library for Compose (JVM and Android) that records and compares PNG snapshots. Android verify mode uses native `android_local_test`; JVM verify mode uses native `jvm_test` with hermetic fonts. | Record mode stays Gradle-backed |
| `Xvfb` | Virtual framebuffer X server; required for JVM Compose Desktop screenshot tests in headless CI | `xvfb-run --auto-servernum` wraps the Bazel test invocation |

---

## Creative Pass — Alternatives Evaluated

**Approach A: Native Bazel test rules (android_local_test for Android Roborazzi + sh_test for emulator)**
- Strength: fully hermetic (for reads); cacheable; no Gradle in verify/test execution path.
- Status: **CHOSEN** for Android Roborazzi verify (`android_local_test`) and emulator smoke tests (`sh_test + ADB`). Record mode still uses Gradle-backed sh_binary (write-in-place cannot be sandboxed without nested Bazel calls).

**Approach B: Single Gradle genrule wrapper for everything (Detekt + Roborazzi as genrules)**
- Strength: uniform pattern; no new MODULE.bazel complexity.
- Weakness: Detekt loses hermetic caching (genrule is `tags=["local"]`); `rules_detekt` already handles this natively and is in BCR.

**Approach C (previously chosen, now SUPERSEDED): Hybrid — `detekt_test` (native) + Gradle-backed Roborazzi genrule + emulator stays Gradle**
- Was chosen for its pragmatic escape hatches. Superseded per direction to go deeper on native integration.

**Approach D (chosen): Deep native hybrid — `detekt_test` + `android_local_test` + `jvm_test` + `sh_test + ADB`**
- Detekt: native `detekt_test` from `rules_detekt` (hermetic, cached).
- Roborazzi Android verify: native `android_local_test` with baseline PNGs as `data` deps, diff output to `$TEST_UNDECLARED_OUTPUTS_DIR`.
- Roborazzi JVM verify: native `jvm_test` with hermetic Noto fonts as `data` deps, JVM flags pin DPI/GPU.
- Roborazzi record mode (Android + JVM): Gradle-backed `sh_binary` (write-in-place is unavoidable without nested Bazel).
- Emulator smoke tests: `sh_test` with `tags=["local"]` calling `adb shell am instrument` directly; GHA starts emulator; Bazel orchestrates execution.

---

## Pattern Decisions

| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|-----------|---------------|--------|---------------------|--------|
| Detekt Bazel integration | `detekt_test` from BCR `rules_detekt 0.8.1.13` | BCR | genrule wrapping `./gradlew :kmp:detekt` | Native rule gives hermetic caching; genrule would be `tags=["local"]` losing all cache benefit |
| buildSrc rules JAR | `kt_jvm_library` in new `buildSrc/BUILD.bazel` | rules_kotlin | Pre-built JAR checked in | Bazel builds it reproducibly from source; no stale JAR risk |
| Roborazzi Android verify | Native `android_local_test` with `data = glob(baseline PNGs)` and `jvm_flags = ['-Droborazzi.test.verify=true']`; diff images written to `$TEST_UNDECLARED_OUTPUTS_DIR` | `android_local_test` test action semantics | Gradle-backed sh_test | Native gives Bazel caching + JUnit XML; sh_test would still invoke Gradle defeating SM#1 |
| Roborazzi JVM verify | Native `jvm_test` with hermetic Noto fonts as `data` deps; JVM flags pin DPI and disable GPU rendering for determinism | Hermetic font bundling | System font stack | Bundled fonts eliminate the cross-machine rendering divergence that previously made JVM verify infeasible |
| Roborazzi record mode (Android + JVM) | `sh_binary` wrapping Gradle — record writes in-place; nested Bazel invocations with `--sandbox_writable_path` are prohibited | Simplest correct approach | `--sandbox_writable_path` directly | Sandbox writable path requires absolute path + nested bazel call which is disallowed |
| Android emulator smoke tests | `sh_test` in `kmp/BUILD.bazel` with `tags=["local","manual"]` calling `adb shell am instrument` directly; GHA emulator-runner starts device before `bazel test //kmp:smoke_tests --config=emulator`; APK from `:kmp:assembleDebugAndroidTest`; `--config=emulator` sets `--strategy=TestRunner=local` | Achieves `bazel test` semantics without android_instrumentation_test | `android_instrumentation_test` | Not exported by `rules_android 0.7.1`; no bzlmod path for `android_test_support` |
| JUnit XML upload | `mikepenz/action-junit-report@v4` + `actions/upload-artifact@v4` | Existing pattern in `ci.yml` | Alternative JUnit publishers | Matches the pattern already working in `ci.yml`; zero new tool learning |

---

## Observability Plan

- **Logs**: Each Bazel test target emits to `bazel-testlogs/<target>/test.log`; CI uploads `test.xml` for PR annotations.
- **Metrics**: Wall-clock time per CI job reported by GitHub Actions summary; regression threshold 20%.
- **Alerts**: `mikepenz/action-junit-report` creates failing PR check on any test failure; Detekt violations surface as test failures in the `bazel-detekt` CI job.
- **Artifacts**: Detekt SARIF/HTML upload from `kmp/build/reports/detekt/` (kept via `continue-on-error` Gradle fallback until Bazel Detekt is stable); Roborazzi PNGs from `kmp/src/androidUnitTest/snapshots/`.

## Risk Control

- **Feature flag**: None required — all new Bazel jobs use `continue-on-error: true` initially; existing Gradle jobs remain until 3 consecutive green Bazel runs.
- **Rollback procedure**: Remove new `bazel-detekt` / `bazel-roborazzi-android` / `bazel-roborazzi-jvm` / `bazel-emulator-smoke` jobs from `bazel-ci.yml`; re-enable `lint` / `android` Gradle jobs if they were disabled. Git revert of MODULE.bazel + buildSrc/BUILD.bazel.
- **Staged rollout**:
  1. Epic 0 (JUnit XML) — standalone improvement, no rollback needed
  2. Epic 1 (Detekt) — Bazel job added with `continue-on-error: true`; Gradle `lint` job stays; remove after 3 green runs
  2. Epic 2 (Roborazzi) — native `android_local_test` verify target added with `continue-on-error: true`; JVM verify added after font determinism confirmed; Gradle Roborazzi step stays until both Bazel jobs are stable
  3. Epic 3 (Emulator) — `bazel-emulator-smoke` added with `continue-on-error: true`; Gradle `android-smoke` job stays; remove after 3 green runs
- **Escape hatch**: If native `android_local_test` Roborazzi integration or `sh_test` emulator approach proves infeasible within the appetite, Gradle genrule wrappers and documented non-migration remain available escape hatches.

## Unresolved Questions

- [ ] Will `detekt-api:1.23.7` on `rules_detekt 0.8.1.13`'s internal Detekt 1.23.8 classpath cause version skew for the custom rules JAR? — blocks Story 1.2.2 — owner: Tyler (test locally before wiring into CI)
- [ ] Does `io.nlopez.compose.rules:detekt:0.4.27` have a JVM JAR on Maven Central (no `-jvm` classifier needed)? — blocks Story 1.1.1 — owner: Tyler (verify `mvn dependency:get` before merging MODULE.bazel changes)
- [ ] Does Roborazzi read Android baselines from `$TEST_SRCDIR`/`$RUNFILES_DIR` or from `System.getProperty("user.dir")`? — blocks Story 2.1.1a — owner: Tyler (Task 2.1.1pre: create a temporary android_local_test, run with verify=true, observe baseline resolution path)
- [ ] Which fonts does the JVM Roborazzi rendering pipeline actually use? — blocks Story 2.2.1 — owner: Tyler (Task 2.2.1pre: run record mode locally, inspect PNGs to determine minimum font set needed for hermetic bundling)
- [ ] Is the instrumented test APK buildable via `rules_android` `android_binary` + `instruments` attribute, or does it require `./gradlew :kmp:assembleDebugAndroidTest`? — blocks Story 3.1.4 (affects whether Gradle is needed at APK build time) — owner: Tyler (Task 3.1.1a). APK output path: `kmp/build/outputs/apk/androidTest/debug/kmp-debug-androidTest.apk`.

---

## Dependency Visualization

```
Epic 0 (JUnit XML upload)
  └── no dependencies — implement first

Epic 1 (Detekt)
  Story 1.1 (MODULE.bazel deps)
    └── Story 1.2 (buildSrc/BUILD.bazel)
          └── Story 1.3 (kmp/BUILD.bazel detekt_test target)
                └── Story 1.4 (bazel-ci.yml bazel-detekt job)
                      └── Story 1.5 (remove Gradle lint job after 3 green runs)

Epic 2 (Roborazzi)
  Epic 2.0 (MODULE.bazel Roborazzi Maven artifacts)
    └── Epic 2.1 (android_local_test verify target + Android record sh_binary)
          └── Story 2.1.3 (commit Android baselines)
                └── Epic 2.4 (graduate Roborazzi, remove Gradle step)
  Epic 2.2 (JVM native verify with hermetic fonts) — parallel with 2.1
    └── merged into Epic 2.4 graduation

Epic 3 (Emulator)
  Story 3.1.1 (investigate instrumented APK build path)
    └── Story 3.1.2 (smoke_tests sh_test)
          └── Story 3.1.3 (.bazelrc emulator config)
                └── Story 3.1.4 (bazel-ci.yml emulator job)
                      └── Epic 3.2 (graduate emulator, remove Gradle android-smoke)
```

---

## Phase 0: Infrastructure Prerequisite
### Epic 0.1: JUnit XML upload and test output cleanup in bazel-ci.yml
**Goal**: Fix the existing gap where Bazel test failures are invisible as PR annotations; improve CI output legibility.

#### Story 0.1.1: Add JUnit XML upload and test report annotation to all existing bazel-ci.yml jobs
**As a** developer, **I want** Bazel test failures to appear as inline PR check annotations, **so that** I don't have to grep raw step logs to find failures.

**Acceptance Criteria**:
- After the `Run JVM tests` step in `bazel-jvm` job: `mikepenz/action-junit-report@v4` runs with `report_paths: 'bazel-testlogs/**/test.xml'` and `check_name: Bazel JVM Test Results`.
- After the `Run Android unit tests` step in `bazel-android-tests` job: same pattern, `check_name: Bazel Android Test Results`.
- `permissions: checks: write` added to `bazel-ci.yml` workflow level (currently only has `contents: read`).
- `--test_output=all` changed to `--test_output=errors` in both test invocations and in `.bazelrc` default.
  - *Given* a single test failure in `//kmp:jvm_tests`, *When* the `bazel-jvm` CI job completes, *Then* the PR shows an inline annotation on the failed test method without requiring the raw log to be expanded.

**Files**:
- `/home/tstapler/Programming/stelekit/.github/workflows/bazel-ci.yml`
- `/home/tstapler/Programming/stelekit/.bazelrc`

##### Task 0.1.1a: Add `permissions: checks: write` at job level in bazel-ci.yml (~2 min)
- Do NOT add `checks: write` at the workflow level (would over-provision to APK/web jobs that produce no JUnit XML).
- Instead, add job-level `permissions` blocks to the `bazel-jvm` and `bazel-android-tests` jobs only:
  ```yaml
  permissions:
    contents: read
    checks: write
  ```
- Files: `.github/workflows/bazel-ci.yml`

##### Task 0.1.1b: Change `--test_output=all` to `--test_output=errors` in bazel-ci.yml (~2 min)
- In the `Run JVM tests` step: change `--test_output=all` to `--test_output=errors`
- In the `Run Android unit tests` step: change `--test_output=all` to `--test_output=errors`
- Files: `.github/workflows/bazel-ci.yml`

##### Task 0.1.1c: Add `--test_output=errors` to the `:ci` config in .bazelrc (~2 min)
- Do NOT add to the global `test` stanza (that silences local dev output for all runs).
- Instead, add under the `# ── CI config` or `build:ci` section:
  ```
  test:ci --test_output=errors
  ```
- This ensures the change only applies when `--config=ci` is passed (i.e., in CI invocations), not in local developer runs where `--test_output=errors` hides useful output.
- Files: `.bazelrc`

##### Task 0.1.1d: Add JUnit XML upload after JVM test step (~3 min)
- After the `Run JVM tests` step in the `bazel-jvm` job, add:
  ```yaml
  - name: Publish Bazel JVM test report
    uses: mikepenz/action-junit-report@v4
    if: always()
    with:
      report_paths: 'bazel-testlogs/**/test.xml'
      fail_on_failure: true
      check_name: Bazel JVM Test Results

  - name: Upload Bazel JVM test XML
    uses: actions/upload-artifact@v4
    if: always()
    with:
      name: bazel-jvm-test-results
      path: bazel-testlogs/**/test.xml
      retention-days: 7
  ```
- Files: `.github/workflows/bazel-ci.yml`

##### Task 0.1.1e: Add JUnit XML upload after Android unit test step (~3 min)
- After the `Run Android unit tests` step in the `bazel-android-tests` job, add equivalent steps with `check_name: Bazel Android Test Results` and artifact name `bazel-android-test-results`.
- Files: `.github/workflows/bazel-ci.yml`

---

## Phase 1: Detekt in Bazel
### Epic 1.1: MODULE.bazel — add rules_detekt and Compose rules Maven artifact
**Goal**: Declare all new Bazel module and Maven dependencies needed for Detekt.

#### Story 1.1.1: Add `rules_detekt` bzlmod dep and `io.nlopez.compose.rules:detekt` Maven artifact
**As a** developer, **I want** `rules_detekt` available as a Bazel module, **so that** I can use `detekt_test` in BUILD files.

**Acceptance Criteria**:
- `bazel_dep(name = "rules_detekt", version = "0.8.1.13")` appears in `MODULE.bazel`.
- `"io.nlopez.compose.rules:detekt:0.4.27"` added to `maven.install` artifacts list.
- `"io.gitlab.arturbosch.detekt:detekt-api:1.23.7"` added to `maven.install` artifacts list (compile dep for buildSrc rules JAR).
- `bazel run @maven//:pin` succeeds and `rules_jvm_external++maven+maven_install.json` is updated.
  - *Given* `MODULE.bazel` contains `bazel_dep(name = "rules_detekt", version = "0.8.1.13")`, *When* `bazel mod deps` is run, *Then* `rules_detekt` appears in the resolved module graph with version `0.8.1.13`.

**Files**:
- `/home/tstapler/Programming/stelekit/MODULE.bazel`
- `/home/tstapler/Programming/stelekit/rules_jvm_external++maven+maven_install.json`

##### Task 1.1.1pre: Pre-flight — validate detekt-api JAR compatibility (~5 min)
- Before modifying MODULE.bazel, validate that `detekt-api:1.23.7` is binary-compatible with `rules_detekt 0.8.1.13`'s internal Detekt 1.23.8 runtime.
- Download both JARs temporarily and compare the `RuleSetProvider` interface signature:
  `curl -s "https://repo1.maven.org/maven2/io/gitlab/arturbosch/detekt/detekt-api/1.23.7/detekt-api-1.23.7.jar" -o /tmp/detekt-api.jar && jar -tf /tmp/detekt-api.jar | grep RuleSetProvider`
- If the interface changed between 1.23.7 and 1.23.8, use `detekt-api:1.23.8` instead (same minor version as rules_detekt's bundled Detekt).
- Resolve Unresolved Question #1 before proceeding to Task 1.1.1a.
- Also verify `io.nlopez.compose.rules:detekt:0.4.27` is on Maven Central:
  `curl -I "https://repo1.maven.org/maven2/io/nlopez/compose/rules/detekt/0.4.27/detekt-0.4.27.jar"` (should return 200)
- Files: none (pre-flight only)

##### Task 1.1.1a: Add `bazel_dep` for rules_detekt to MODULE.bazel (~2 min)
- After the `bazel_dep(name = "rules_robolectric", ...)` line, add:
  ```
  bazel_dep(name = "rules_detekt", version = "0.8.1.13")
  ```
- Files: `MODULE.bazel`

##### Task 1.1.1b: Add Compose detekt rules and detekt-api to Maven artifacts (~3 min)
- In the `maven.install` `artifacts` list, add under the Detekt-related comment (or after `kotlin-reflect`):
  ```
  # ── Detekt — custom rule API + Compose rules plugin ──────────────────────
  "io.gitlab.arturbosch.detekt:detekt-api:1.23.7",
  "io.nlopez.compose.rules:detekt:0.4.27",
  ```
- Files: `MODULE.bazel`

##### Task 1.1.1c: Re-pin Maven lockfile (~5 min)
- Run: `bazel run @maven//:pin`
- Commit the updated `rules_jvm_external++maven+maven_install.json`.
- Files: `rules_jvm_external++maven+maven_install.json`

---

### Epic 1.2: buildSrc/BUILD.bazel — build custom Detekt rules as kt_jvm_library
**Goal**: Compile the 14 custom SteleKit Detekt rules and `SteleKitRuleSetProvider` into a JAR Bazel can pass to `detekt_test` as a plugin.

#### Story 1.2.1: Create `buildSrc/BUILD.bazel` with `kt_jvm_library` for all 14 rule sources
**As a** developer, **I want** `//buildSrc:detekt_rules` to produce a JAR containing all custom Detekt rule classes, **so that** `//kmp:detekt` can load them as a plugin.

**Acceptance Criteria**:
- `buildSrc/BUILD.bazel` exists with a `kt_jvm_library` named `detekt_rules`.
- All 16 Kotlin files in `buildSrc/src/main/kotlin/dev/stapler/detekt/` (15 rule files + `SteleKitRuleSetProvider.kt`) are listed in `srcs`.
- `@maven//:io_gitlab_arturbosch_detekt_detekt_api` is in `deps`.
- `bazel build //buildSrc:detekt_rules` succeeds.
  - *Given* `buildSrc/BUILD.bazel` is created with all 16 `srcs`, *When* `bazel build //buildSrc:detekt_rules` runs, *Then* `bazel-bin/buildSrc/detekt_rules.jar` is produced with no compilation errors.

**Files**:
- `/home/tstapler/Programming/stelekit/buildSrc/BUILD.bazel` (new file)

##### Task 1.2.1a: Create buildSrc/BUILD.bazel (~5 min)
- Create `buildSrc/BUILD.bazel` with:
  ```starlark
  load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_library")

  kt_jvm_library(
      name = "detekt_rules",
      srcs = glob(["src/main/kotlin/dev/stapler/detekt/**/*.kt"]),
      deps = [
          "@maven//:io_gitlab_arturbosch_detekt_detekt_api",
      ],
      # detekt-api must NOT be on the runtime classpath of the detekt_test plugin loader.
      # Declare it with neverlink = True to match Gradle's compileOnly semantics — this
      # prevents kotlin-compiler-embeddable and other detekt-api transitive deps from
      # being pulled onto the detekt_test classpath and causing ClassCastException for
      # RuleSetProvider instances at runtime.
      # NOTE: rules_kotlin kt_jvm_library does not have a neverlink attribute; use
      # runtime_deps exclusion or declare detekt-api as a separate neverlink java_library:
      #   java_library(name = "detekt_api_compile_only",
      #     exports = ["@maven//:io_gitlab_arturbosch_detekt_detekt_api"],
      #     neverlink = True)
      # Then reference "//buildSrc:detekt_api_compile_only" in the kt_jvm_library deps.
      visibility = ["//kmp:__pkg__"],
  )
  ```
- **Implementation note**: The complete `buildSrc/BUILD.bazel` to create (no ambiguity — use this exactly):
  ```starlark
  load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_library")

  # detekt-api is compileOnly in Gradle — must be neverlink in Bazel to avoid
  # pulling kotlin-compiler-embeddable onto the detekt_test plugin classpath.
  # neverlink = True means the JAR is on the compile classpath but NOT the runtime classpath.
  java_library(
      name = "detekt_api_neverlink",
      exports = ["@maven//:io_gitlab_arturbosch_detekt_detekt_api"],
      neverlink = True,
  )

  kt_jvm_library(
      name = "detekt_rules",
      srcs = glob(["src/main/kotlin/dev/stapler/detekt/**/*.kt"]),
      deps = [
          # Use the neverlink wrapper — NOT @maven//:io_gitlab_arturbosch_detekt_detekt_api directly.
          ":detekt_api_neverlink",
      ],
      # resources includes the META-INF/services file so Detekt can discover SteleKitRuleSetProvider.
      # Story 1.2.2 verifies this file exists; if missing, create it before running build.
      resources = glob(["src/main/resources/**"]),
      visibility = ["//kmp:__pkg__"],
  )
  ```
- Files: `buildSrc/BUILD.bazel`

#### Story 1.2.2: Verify SteleKitRuleSetProvider is discoverable by Detekt
**As a** developer, **I want** Detekt to discover the custom rules via the standard service-provider mechanism, **so that** none of the 14 rules are silently skipped.

**Acceptance Criteria**:
- `bazel build //buildSrc:detekt_rules` JAR contains `META-INF/services/io.gitlab.arturbosch.detekt.api.RuleSetProvider` listing `dev.stapler.detekt.SteleKitRuleSetProvider`. (Verify with `jar -tf bazel-bin/buildSrc/detekt_rules.jar | grep services`.)
  - *Given* `SteleKitRuleSetProvider.kt` has a `@AutoService` annotation or the service file is in `resources/`, *When* the JAR is inspected, *Then* the services file is present.

**Note**: If the service file is not auto-generated (no `@AutoService`), create `buildSrc/src/main/resources/META-INF/services/io.gitlab.arturbosch.detekt.api.RuleSetProvider` with the provider class name, and add `resources = ["src/main/resources/META-INF/services/io.gitlab.arturbosch.detekt.api.RuleSetProvider"]` to the `kt_jvm_library`.

**Files**:
- `/home/tstapler/Programming/stelekit/buildSrc/src/main/kotlin/dev/stapler/detekt/SteleKitRuleSetProvider.kt` (read-only verification)
- `/home/tstapler/Programming/stelekit/buildSrc/BUILD.bazel`
- Possibly: `buildSrc/src/main/resources/META-INF/services/io.gitlab.arturbosch.detekt.api.RuleSetProvider` (create only if missing)

##### Task 1.2.2a: Inspect SteleKitRuleSetProvider for service registration mechanism (~2 min)
- Read `buildSrc/src/main/kotlin/dev/stapler/detekt/SteleKitRuleSetProvider.kt`.
- Check if a services file exists: `ls buildSrc/src/main/resources/META-INF/services/` (use Bash).
- If missing, create the services resource file and add it to `kt_jvm_library.resources`.
- Files: `buildSrc/BUILD.bazel`, optionally `buildSrc/src/main/resources/META-INF/services/io.gitlab.arturbosch.detekt.api.RuleSetProvider`

---

### Epic 1.3: kmp/BUILD.bazel — add `detekt_test` target
**Goal**: Replace the comment block at lines 91–104 of `kmp/BUILD.bazel` with a working `detekt_test` target.

#### Story 1.3.1: Add `detekt_test` target covering all production source sets
**As a** developer, **I want** `bazel test //kmp:detekt` to run Detekt against all production Kotlin sources with the project config and baselines, **so that** lint violations block CI.

**Acceptance Criteria**:
- `kmp/BUILD.bazel` loads `detekt_test` from `rules_detekt` and defines target named `detekt`.
- `srcs` glob covers `src/commonMain/kotlin/**/*.kt`, `src/jvmMain/kotlin/**/*.kt`, `src/androidMain/kotlin/**/*.kt`, `src/iosMain/kotlin/**/*.kt`.
- `srcs` does NOT include `src/generated/`, `src/jvmTest/`, `src/androidUnitTest/`, `src/commonTest/`, `src/businessTest/`.
- `cfgs = ["config/detekt/detekt.yml"]` and `baseline = "config/detekt/baseline.xml"` are set.
- `plugins = ["//buildSrc:detekt_rules", "@maven//:io_nlopez_compose_rules_detekt"]`.
- `build_upon_default_config = True`.
- `bazel test //kmp:detekt` succeeds locally with zero new violations (baseline suppresses pre-existing ones).
  - *Given* the `detekt.yml` config and `baseline.xml` are passed, *When* `bazel test //kmp:detekt` runs, *Then* the test exits 0 on a clean checkout (same outcome as `./gradlew :kmp:detekt` today).

**Files**:
- `/home/tstapler/Programming/stelekit/kmp/BUILD.bazel`

##### Task 1.3.1a: Replace Detekt comment block with `detekt_test` target in kmp/BUILD.bazel (~5 min)
- Remove lines 91–104 (the comment starting `# ── Detekt linting`).
- Add:
  ```starlark
  load("@rules_detekt//detekt:defs.bzl", "detekt_test")

  # ── Detekt linting ─────────────────────────────────────────────────────────────
  # Run: bazel test //kmp:detekt
  # Record mode: n/a — Detekt has no record mode; update baseline.xml manually.
  detekt_test(
      name = "detekt",
      srcs = glob(
          [
              # Source sets matching kmp/build.gradle.kts detekt { } source config (lines 985–997).
              # jvmCommonMain intentionally excluded — not in Gradle Detekt config; adding it
              # would be scope drift that could surface new violations and break baseline.xml.
              # wasmJsMain excluded — not in Gradle Detekt config (iosMain is the boundary for KMP analysis).
              "src/commonMain/kotlin/**/*.kt",
              "src/jvmMain/kotlin/**/*.kt",
              "src/androidMain/kotlin/**/*.kt",
              "src/iosMain/kotlin/**/*.kt",
          ],
          exclude = [
              "src/generated/**",
          ],
      ),
      cfgs = ["config/detekt/detekt.yml"],
      baseline = "config/detekt/baseline.xml",
      plugins = [
          "//buildSrc:detekt_rules",
          "@maven//:io_nlopez_compose_rules_detekt",
      ],
      build_upon_default_config = True,
      visibility = ["//visibility:public"],
  )
  ```
- Files: `kmp/BUILD.bazel`

---

### Epic 1.4: bazel-ci.yml — add `bazel-detekt` CI job
**Goal**: Run `//kmp:detekt` in CI as a new job alongside the existing Gradle `lint` job (staged rollout).

#### Story 1.4.1: Add `bazel-detekt` job to bazel-ci.yml with `continue-on-error: true`
**As a** CI system, **I want** Detekt to run under Bazel in CI, **so that** I can validate the Bazel Detekt target before removing the Gradle equivalent.

**Acceptance Criteria**:
- New job `bazel-detekt` in `bazel-ci.yml` runs `bazel test //kmp:detekt --config=remote-cache --test_output=errors`.
- Job has `continue-on-error: true` initially.
- Job reuses the `bazel-cache-proxy` sidecar pattern from other jobs.
- After the test step, `mikepenz/action-junit-report@v4` runs with `check_name: Bazel Detekt Results`.
- JUnit XML uploaded as artifact `bazel-detekt-results`.
  - *Given* a Detekt violation is introduced in `src/commonMain/kotlin/`, *When* the `bazel-detekt` job runs, *Then* the job fails and the violation appears as an annotation on the PR (once `continue-on-error` is removed).

**Files**:
- `/home/tstapler/Programming/stelekit/.github/workflows/bazel-ci.yml`

##### Task 1.4.1a: Add `bazel-detekt` job to bazel-ci.yml (~5 min)
- Add new job after the `bazel-web` job:
  ```yaml
  # ── Detekt static analysis (Bazel) ────────────────────────────────────────────
  bazel-detekt:
    name: Bazel Detekt
    runs-on: ubuntu-latest
    timeout-minutes: 15
    if: github.event.pull_request.draft == false
    continue-on-error: true  # Remove after 3 consecutive green runs; then remove Gradle lint job
    steps:
      - uses: actions/checkout@v4

      - name: Cache bazel-cache-proxy binary
        id: bcp-cache
        uses: actions/cache@v4
        with:
          path: ~/.cargo/bin/bazel-cache-proxy
          key: bcp-${{ runner.os }}-7806c5f

      - name: Install bazel-cache-proxy
        if: steps.bcp-cache.outputs.cache-hit != 'true'
        run: |
          sudo apt-get install -y --no-install-recommends protobuf-compiler
          cargo install --git https://github.com/tstapler/bazel-cache-proxy --rev 7806c5f bazel-cache-proxy --locked

      - name: Start bazel-cache-proxy (GHA backend)
        run: |
          printf '[backend]\ntype = "gha"\n' > /tmp/bcp.toml
          ~/.cargo/bin/bazel-cache-proxy serve --config /tmp/bcp.toml &
          sleep 2
          curl -sf http://127.0.0.1:9090/healthz

      - uses: actions/setup-java@v4
        with:
          java-version: "21"
          distribution: "temurin"

      - name: Set up Bazel
        uses: bazel-contrib/setup-bazel@0.19.0
        with:
          bazelisk-version: "1.x"
          disk-cache: detekt
          repository-cache: true
          external-cache: true
          cache-save: ${{ github.event_name != 'pull_request' }}

      - name: Run Detekt
        run: bazel test //kmp:detekt --config=remote-cache --config=ci

      - name: Publish Bazel Detekt report
        uses: mikepenz/action-junit-report@v4
        if: always()
        with:
          # Scoped to only the detekt target's output path, not all bazel-testlogs
          report_paths: 'bazel-testlogs/kmp/detekt/test.xml'
          fail_on_failure: true
          check_name: Bazel Detekt Results

      - name: Upload Bazel Detekt test XML
        uses: actions/upload-artifact@v4
        if: always()
        with:
          name: bazel-detekt-results
          path: bazel-testlogs/kmp/detekt/test.xml
          retention-days: 7
    permissions:
      contents: read
      checks: write
  ```
- Files: `.github/workflows/bazel-ci.yml`

##### Task 1.4.1b: Add custom-rules smoke-check to verify rules actually fire (~4 min)
- In a local branch, temporarily introduce a synthetic violation (e.g., add `println("test")` to any commonMain file — violates the `ForbiddenMethodCall` rule).
- Run `bazel test //kmp:detekt` and confirm non-zero exit with the violation reported.
- Revert the synthetic violation before merging.
- This is a manual verification task, not an automated story — but it MUST be done before removing `continue-on-error`. Detekt silently skips rules that fail to load (version skew, missing service registration), and a passing detekt target on a codebase with no custom rule coverage is indistinguishable from a properly loaded one without this check.
- Document result: "custom rules verified firing on [date]" in the PR description.
- Files: any commonMain .kt file (temporary, reverted)

---

### Epic 1.5: Graduate Bazel Detekt — remove Gradle lint job
**Goal**: After 3 consecutive green runs of `bazel-detekt`, remove `continue-on-error` and delete the Gradle `lint` job from `ci.yml`.

#### Story 1.5.1: Remove `continue-on-error` from `bazel-detekt` and delete Gradle `lint` job
**As a** developer, **I want** a single canonical Detekt gate in CI, **so that** I don't maintain two lint systems.

**Acceptance Criteria**:
- `continue-on-error: true` line removed from `bazel-detekt` job.
- `lint` job entirely removed from `ci.yml`.
- `ci.yml` `jvm-test` job (which has no dependency on `lint`) is unaffected.
  - *Given* a Detekt violation is introduced, *When* CI runs, *Then* only `bazel-detekt` fails (not a Gradle job).

**Files**:
- `/home/tstapler/Programming/stelekit/.github/workflows/bazel-ci.yml`
- `/home/tstapler/Programming/stelekit/.github/workflows/ci.yml`

##### Task 1.5.1a: Remove `continue-on-error` from bazel-detekt job (~2 min)
- Delete the `continue-on-error: true` line from the `bazel-detekt` job.
- Files: `.github/workflows/bazel-ci.yml`

##### Task 1.5.1b: Remove Gradle `lint` job from ci.yml (~2 min)
- Delete the entire `lint:` job block (lines 139–165 in current `ci.yml`).
- Files: `.github/workflows/ci.yml`

---

## Phase 2: Roborazzi Screenshot Tests in Bazel (Native)
### Epic 2.0: MODULE.bazel — add Roborazzi Maven artifacts
**Goal**: Declare Roborazzi dependencies needed for native `android_local_test` and `jvm_test` targets.

#### Story 2.0.1: Add Roborazzi artifacts to maven.install
**As a** developer, **I want** Roborazzi JARs available as Maven artifacts in Bazel, **so that** `android_local_test` and `jvm_test` targets can depend on them directly.

**Acceptance Criteria**:
- `io.github.takahirom.roborazzi:roborazzi:1.59.0`, `io.github.takahirom.roborazzi:roborazzi-compose:1.59.0`, `io.github.takahirom.roborazzi:roborazzi-junit-rule:1.59.0`, and `io.github.takahirom.roborazzi:roborazzi-compose-desktop:1.59.0` appear in `maven.install` artifacts.
- `bazel run @maven//:pin` succeeds with updated lockfile.

**Files**: `MODULE.bazel`, `rules_jvm_external++maven+maven_install.json`

##### Task 2.0.1a: Add Roborazzi artifacts to MODULE.bazel maven.install (~3 min)
- In the `maven.install` `artifacts` list, add:
  ```
  # ── Roborazzi screenshot tests ────────────────────────────────────────────────
  "io.github.takahirom.roborazzi:roborazzi:1.59.0",
  "io.github.takahirom.roborazzi:roborazzi-compose:1.59.0",
  "io.github.takahirom.roborazzi:roborazzi-compose-desktop:1.59.0",
  "io.github.takahirom.roborazzi:roborazzi-junit-rule:1.59.0",
  ```
- Files: `MODULE.bazel`

##### Task 2.0.1b: Re-pin Maven lockfile (~5 min)
- Run: `bazel run @maven//:pin`
- Commit updated lockfile.
- Files: `rules_jvm_external++maven+maven_install.json`

---

### Epic 2.1: Android Roborazzi — native android_local_test for verify mode
**Goal**: Replace the Gradle-backed `sh_test` verify target with a native `android_local_test` that Bazel can cache, sandbox (for reads), and annotate with JUnit XML.

#### Story 2.1.1: Create roborazzi_android_verify android_local_test target
**As a** developer, **I want** `bazel test //kmp:roborazzi_android_verify` to run Android screenshot verification natively (no Gradle), **so that** screenshot regressions block CI hermetically.

**Acceptance Criteria**:
- `//kmp:roborazzi_android_verify` is an `android_local_test` target (placed in `kmp/src/androidUnitTest/kotlin/BUILD.bazel` to inherit the `associates` link to `android_main` needed for `internal` symbols — see Task 2.1.1a note).
- `srcs` includes `src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/UiStateScreenshotTest.kt` (note the `/ui/` subdirectory).
- `deps` includes `//kmp/src/androidUnitTest/kotlin:android_unit_test_lib` (correct label — the `//kmp:android_unit_test_lib` alias does NOT exist) plus Roborazzi Maven deps.
- `data` includes `@rules_robolectric//bazel:android-all` (required for Robolectric offline mode) plus baseline PNGs.
- `manifest = "//androidApp:BazelAndroidManifest.xml"` — not `src/androidMain/AndroidManifest.xml` (the library manifest lacks `package=` attribute).
- `jvm_flags = ["-Droborazzi.test.verify=true", "-Droborazzi.output.dir=/tmp/roborazzi-diffs"]` — hardcoded path because Bazel does not shell-expand `${VAR:-fallback}` in jvm_flags.
- Diff images (when baselines mismatch) go to `/tmp/roborazzi-diffs` (local) or are captured via `bazel-testlogs/…/test.outputs/` in CI.
- `bazel test //kmp:roborazzi_android_verify` exits 0 with no source changes.
- `tags = ["manual"]` until baselines are committed (Story 2.1.3).
  - *Given* a committed baseline PNG is changed, *When* `bazel test //kmp:roborazzi_android_verify` runs, *Then* the test fails and a diff PNG appears in `bazel-testlogs/kmp/roborazzi_android_verify/test.outputs/`.

**Files**: `kmp/BUILD.bazel`

##### Task 2.1.1pre: Verify Roborazzi can read baselines from runfiles path (~10 min)
- Before creating the target, confirm that Roborazzi's baseline resolution respects the `TEST_SRCDIR` / `RUNFILES_DIR` environment variable or `System.getProperty("user.dir")`.
- In a local test branch: add `UiStateScreenshotTest.kt` to a temporary `android_local_test`, run it with `-Droborazzi.test.verify=true` and observe whether Roborazzi looks for PNGs relative to CWD, `$RUNFILES_DIR`, or an absolute path.
- If Roborazzi uses `System.getProperty("user.dir")`: the test must set `-Duser.dir=$RUNFILES_DIR/kmp` via `jvm_flags` to point to the runfiles tree.
- If Roborazzi uses `$TEST_SRCDIR`: baselines are discoverable as data deps without extra flags.
- Document result in PR description before merging this story.
- Files: none (pre-flight investigation only)

##### Task 2.1.1a: Add roborazzi_android_verify target to kmp/src/androidUnitTest/kotlin/BUILD.bazel (~8 min)
- **Placement**: Add this target in `kmp/src/androidUnitTest/kotlin/BUILD.bazel` (NOT `kmp/BUILD.bazel`). Reason: `android_unit_test_lib` in that file uses `associates = ["//kmp/src/androidMain/kotlin:android_main"]` which grants access to `internal` Kotlin symbols from the main source. A target in a different BUILD package would not inherit this — it would fail to access `internal` composables used by `UiStateScreenshotTest.kt`. Placing both targets in the same BUILD file avoids this package boundary.
- In `kmp/src/androidUnitTest/kotlin/BUILD.bazel`, add after `android_unit_tests`:
  ```starlark
  # ── Roborazzi screenshot verify ───────────────────────────────────────────────
  #
  # Android verify mode: native android_local_test — no Gradle.
  # Baseline PNGs declared as data deps; diff images go to $TEST_UNDECLARED_OUTPUTS_DIR.
  # Run: bazel test //kmp:roborazzi_android_verify
  #
  # Android/JVM record mode: Gradle-backed sh_binary (record writes in-place;
  # --sandbox_writable_path requires an absolute path and nested Bazel calls are
  # prohibited, so Gradle remains the record runner).
  # Run: bazel run //kmp:record_android_screenshots
  #      bazel run //kmp:record_jvm_screenshots

  android_local_test(
      name = "roborazzi_android_verify",
      # CORRECTED: file is under /ui/ subdirectory
      srcs = ["src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/UiStateScreenshotTest.kt"],
      deps = [
          # CORRECTED: //kmp:android_unit_test_lib does NOT exist; correct label is:
          "//kmp/src/androidUnitTest/kotlin:android_unit_test_lib",
          "@maven//:io_github_takahirom_roborazzi_roborazzi",
          "@maven//:io_github_takahirom_roborazzi_roborazzi_compose",
          "@maven//:io_github_takahirom_roborazzi_roborazzi_junit_rule",
      ],
      data = [
          # REQUIRED: Robolectric needs android-all JARs for offline mode (-Drobolectric.offline=true)
          "@rules_robolectric//bazel:android-all",
      ] + glob(["src/androidUnitTest/snapshots/images/**/*.png"]),
      jvm_flags = [
          "-Droborazzi.test.verify=true",
          # CORRECTED: Bazel does NOT shell-expand ${VAR:-fallback} in jvm_flags — the literal
          # string would be passed as the property value. Hardcode the fallback path instead.
          # In CI, bazel-testlogs/…/test.outputs/ captures any files written here.
          "-Droborazzi.output.dir=/tmp/roborazzi-diffs",
      ],
      # CORRECTED: library AndroidManifest.xml lacks package= attribute required by android_local_test.
      # Use the same BazelAndroidManifest.xml already proven to work with android_unit_tests.
      manifest = "//androidApp:BazelAndroidManifest.xml",
      tags = ["manual"],  # Remove after Story 2.1.3 commits baselines
      visibility = ["//visibility:public"],
  )
  ```
- **Note**: The Maven artifact coordinate for Roborazzi must be verified via `bazel query @maven//:all | grep roborazzi` after pinning — the label format depends on the group/artifact coordinates in the lockfile.
- Files: `kmp/BUILD.bazel`

#### Story 2.1.2: Keep Android record mode as Gradle-backed sh_binary
**As a** developer, **I want** `bazel run //kmp:record_android_screenshots` to regenerate Android screenshot baselines via Gradle, **so that** baseline updates go through a single canonical command.

**Note**: Record mode cannot be made hermetic without either (a) nested Bazel invocations (`--sandbox_writable_path` with absolute path) or (b) writing to `$TEST_UNDECLARED_OUTPUTS_DIR` and copying outputs back manually — both more complex than the Gradle sh_binary for a developer workflow. Record is intentionally kept Gradle-backed; verify is native Bazel.

**Acceptance Criteria**:
- `//kmp:record_android_screenshots` is a `sh_binary` with `tags = ["local", "manual"]` wrapping `./gradlew :kmp:recordRoborazziDebug --no-daemon`.
- `bazel run //kmp:record_android_screenshots` writes PNGs to `kmp/src/androidUnitTest/snapshots/images/`.

**Files**: `kmp/BUILD.bazel`, `kmp/record_android_screenshots.sh`

##### Task 2.1.2a: Create record_android_screenshots.sh wrapper script (~3 min)
- Create `kmp/record_android_screenshots.sh`:
  ```bash
  #!/usr/bin/env bash
  set -euo pipefail
  exec "${BUILD_WORKSPACE_DIRECTORY}/gradlew" :kmp:recordRoborazziDebug \
      --no-daemon --build-cache --project-dir "${BUILD_WORKSPACE_DIRECTORY}"
  ```
- Make executable.
- Files: `kmp/record_android_screenshots.sh`

##### Task 2.1.2b: Add sh_binary record target to kmp/BUILD.bazel (~3 min)
- Add after roborazzi_android_verify:
  ```starlark
  sh_binary(
      name = "record_android_screenshots",
      srcs = ["record_android_screenshots.sh"],
      tags = ["local", "manual"],
      visibility = ["//visibility:public"],
  )
  ```
- Files: `kmp/BUILD.bazel`

#### Story 2.1.3: Commit Android Roborazzi baselines (prerequisite for verify gate)
**As a** developer, **I want** PNG baselines committed to the repo, **so that** `roborazzi_android_verify` can detect regressions.

**Acceptance Criteria**:
- Run `bazel run //kmp:record_android_screenshots` on a reference CI machine.
- PNG files in `kmp/src/androidUnitTest/snapshots/images/` committed.
- `bazel test //kmp:roborazzi_android_verify` passes on subsequent runs against the same commit.
- `manual` tag removed from `roborazzi_android_verify`.

**Files**: `kmp/src/androidUnitTest/snapshots/images/` (PNG files), `kmp/BUILD.bazel`

---

### Epic 2.2: JVM Roborazzi — native jvm_test with hermetic fonts for verify mode
**Goal**: Achieve deterministic JVM screenshot comparison across machines by bundling a hermetic font set, then running verify mode as a native `jvm_test`.

**Background**: JVM Roborazzi uses Skia + the system font stack. Rendering differs between machines only because the font metric tables differ. Bundling Noto fonts + pinning font path + disabling GPU rendering produces deterministic output.

#### Story 2.2.1: Bundle hermetic Noto fonts as Bazel data dep
**As a** developer, **I want** a fixed set of fonts checked into the repo as a Bazel `data` dep, **so that** JVM screenshot tests use the same font metrics on every machine.

**Acceptance Criteria**:
- `third_party/fonts/` directory containing Noto Sans, Noto Sans Mono, and Noto Serif (ttf) is committed.
- A `filegroup` target `//third_party/fonts:noto_fonts` exposes these files.
- JVM screenshot test uses a JVM flag to override font discovery (specific flag TBD by Task 2.2.1pre — see warning below).
  - *Given* the fonts are declared as `data` deps, *When* the `jvm_test` screenshot target runs, *Then* rendering is deterministic across GHA runner images.

**⚠️ Warning**: `sun.font.fontpath.override` is NOT a valid JVM 21 property (it does not exist in OpenJDK/Temurin 21). The actual mechanism to override font lookup in Compose Desktop on JDK 21 requires investigation — it may require a custom `FontFamily` in test composables, a Skia rendering flag, or a different JVM property. Task 2.2.1pre is a **hard blocker** for Story 2.2.2 — do not write the `jvm_test` target until the correct font-override mechanism is confirmed.

**Files**: `third_party/fonts/*.ttf`, `third_party/fonts/BUILD.bazel`

##### Task 2.2.1pre: Determine minimum font set needed by JVM Roborazzi tests (~15 min)
- Run `bazel run //kmp:record_jvm_screenshots` locally and inspect which fonts are actually used in the test renders (check `kmp/build/outputs/roborazzi/*.png` filenames; look at font references in test composables).
- Identify the minimum set: likely system default → Noto Sans as the hermetic substitute.
- Files: none (pre-flight investigation)

##### Task 2.2.1a: Create third_party/fonts/ with Noto fonts (~10 min)
- Download Noto Sans Regular + Bold and Noto Mono Regular TTFs from Google Fonts (or `pacman -S noto-fonts` on Manjaro and copy from `/usr/share/fonts/noto/`). Target: `third_party/fonts/*.ttf`.
- Create `third_party/fonts/BUILD.bazel`:
  ```starlark
  filegroup(
      name = "noto_fonts",
      srcs = glob(["*.ttf"]),
      visibility = ["//kmp:__pkg__"],
  )
  ```
- Files: `third_party/fonts/BUILD.bazel`, font TTF files

#### Story 2.2.2: Create jvm verify target for JVM Roborazzi screenshots
**As a** developer, **I want** `bazel test //kmp:roborazzi_jvm_verify` to compare JVM screenshot renders against committed baselines hermetically, **so that** JVM screenshot regressions block CI.

**Acceptance Criteria**:
- `//kmp:roborazzi_jvm_verify` is a `jvm_test` (or `kt_jvm_test`) target including the 8 JVM screenshot test classes.
- `data` includes `//third_party/fonts:noto_fonts` and baseline PNGs from `kmp/build/outputs/roborazzi/`.
- `jvm_flags`:
  - `-Droborazzi.test.verify=true`
  - `<font override flag TBD by Task 2.2.1pre — sun.font.fontpath.override does NOT exist in JDK 21>`
  - `-Dsun.java2d.opengl=false` (disable GPU)
  - `-Dsun.java2d.uiScale=1.0` (pin DPI)
  - `-Djava.awt.headless=true`
- Diff images go to `$TEST_UNDECLARED_OUTPUTS_DIR`.
- `tags = ["manual"]` until baselines are confirmed deterministic across two CI runs.
  - *Given* two runs of the record target produce identical PNGs, *When* `roborazzi_jvm_verify` runs, *Then* exit 0.

**Note**: JVM baselines must be committed — run `bazel run //kmp:record_jvm_screenshots` on CI, commit PNGs, then enable this target by removing `manual` tag.

**Files**: `kmp/BUILD.bazel`

#### Story 2.2.3: JVM record mode stays Gradle-backed
**As a** developer, **I want** `bazel run //kmp:record_jvm_screenshots` to regenerate JVM screenshot baselines, **so that** I have a single canonical record command.

**Acceptance Criteria**:
- `//kmp:record_jvm_screenshots` is a `sh_binary` with `tags = ["local", "manual"]` wrapping `./gradlew :kmp:jvmTest -Proborazzi.test.record=true --no-daemon`.
- `bazel run //kmp:record_jvm_screenshots` writes PNGs under `kmp/build/outputs/roborazzi/`.

**Files**: `kmp/BUILD.bazel`, `kmp/record_jvm_screenshots.sh`

##### Task 2.2.3a: Create record_jvm_screenshots.sh wrapper script (~3 min)
- Create `kmp/record_jvm_screenshots.sh`:
  ```bash
  #!/usr/bin/env bash
  set -euo pipefail
  exec "${BUILD_WORKSPACE_DIRECTORY}/gradlew" :kmp:jvmTest \
      -Proborazzi.test.record=true \
      --no-daemon --build-cache \
      --project-dir "${BUILD_WORKSPACE_DIRECTORY}"
  ```
- Make executable.
- Files: `kmp/record_jvm_screenshots.sh`

##### Task 2.2.3b: Add sh_binary JVM record target to kmp/BUILD.bazel (~3 min)
- Add:
  ```starlark
  sh_binary(
      name = "record_jvm_screenshots",
      srcs = ["record_jvm_screenshots.sh"],
      tags = ["local", "manual"],
      visibility = ["//visibility:public"],
  )
  ```
- Files: `kmp/BUILD.bazel`

---

### Epic 2.3: bazel-ci.yml — add Roborazzi CI jobs
**Goal**: Run Roborazzi verify in CI (gated) for both Android and JVM.

#### Story 2.3.1: Add bazel-roborazzi-android CI job (verify gate on PRs)
**As a** CI system, **I want** Android screenshot regressions to block PRs, **so that** screenshot drift is caught before merging.

**Acceptance Criteria**:
- New job `bazel-roborazzi-android` in `bazel-ci.yml` runs `bazel test //kmp:roborazzi_android_verify --config=remote-cache --config=ci`.
- Job has `continue-on-error: true` until baselines are committed (Story 2.1.3).
- On failure, diff images from test outputs uploaded as artifact `android-screenshot-diffs`.
- Job uses `android-actions/setup-android@v3` (for Robolectric native binaries).
- `mikepenz/action-junit-report@v4` step publishes JUnit XML.

**Files**: `.github/workflows/bazel-ci.yml`

##### Task 2.3.1a: Add bazel-roborazzi-android job to bazel-ci.yml (~8 min)
- Add new job:
  ```yaml
  # ── Roborazzi Android screenshots (native android_local_test) ─────────────────
  bazel-roborazzi-android:
    name: Bazel Roborazzi Android (verify)
    runs-on: ubuntu-latest
    timeout-minutes: 20
    if: github.event.pull_request.draft == false
    continue-on-error: true  # Remove after Story 2.1.3 commits baselines
    permissions:
      contents: read
      checks: write
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          java-version: "21"
          distribution: "temurin"

      - uses: android-actions/setup-android@v3

      - name: Cache bazel-cache-proxy binary
        id: bcp-cache
        uses: actions/cache@v4
        with:
          path: ~/.cargo/bin/bazel-cache-proxy
          key: bcp-${{ runner.os }}-7806c5f

      - name: Install bazel-cache-proxy
        if: steps.bcp-cache.outputs.cache-hit != 'true'
        run: |
          sudo apt-get install -y --no-install-recommends protobuf-compiler
          cargo install --git https://github.com/tstapler/bazel-cache-proxy --rev 7806c5f bazel-cache-proxy --locked

      - name: Start bazel-cache-proxy (GHA backend)
        run: |
          printf '[backend]\ntype = "gha"\n' > /tmp/bcp.toml
          ~/.cargo/bin/bazel-cache-proxy serve --config /tmp/bcp.toml &
          sleep 2
          curl -sf http://127.0.0.1:9090/healthz

      - name: Set up Bazel
        uses: bazel-contrib/setup-bazel@0.19.0
        with:
          bazelisk-version: "1.x"
          disk-cache: roborazzi-android
          repository-cache: true
          external-cache: true
          cache-save: ${{ github.event_name != 'pull_request' }}

      - name: Run Android screenshot verify
        run: bazel test //kmp:roborazzi_android_verify --config=remote-cache --config=ci

      - name: Publish Roborazzi Android report
        uses: mikepenz/action-junit-report@v4
        if: always()
        with:
          report_paths: 'bazel-testlogs/kmp/roborazzi_android_verify/test.xml'
          fail_on_failure: true
          check_name: Bazel Roborazzi Android Results

      - name: Upload screenshot diffs on failure
        uses: actions/upload-artifact@v4
        if: failure()
        with:
          name: android-screenshot-diffs
          path: bazel-testlogs/kmp/roborazzi_android_verify/test.outputs/
          retention-days: 14
  ```
- Files: `.github/workflows/bazel-ci.yml`

#### Story 2.3.2: Add bazel-roborazzi-jvm CI job (verify gate, after baselines stable)
**As a** CI system, **I want** JVM screenshot regressions to block PRs once hermetic fonts are confirmed, **so that** JVM screenshot drift is caught.

**Acceptance Criteria**:
- New job `bazel-roborazzi-jvm` runs `xvfb-run --auto-servernum bazel test //kmp:roborazzi_jvm_verify --config=remote-cache --config=ci`.
- Job has `continue-on-error: true` until JVM baselines are confirmed deterministic.
- Uses Xvfb (pre-installed on `ubuntu-latest`).

**Files**: `.github/workflows/bazel-ci.yml`

##### Task 2.3.2a: Add bazel-roborazzi-jvm job to bazel-ci.yml (~8 min)
- Add job similar to `bazel-roborazzi-android` but using `xvfb-run --auto-servernum bazel test //kmp:roborazzi_jvm_verify` and `check_name: Bazel Roborazzi JVM Results`.
- `continue-on-error: true` initially.
- Files: `.github/workflows/bazel-ci.yml`

---

### Epic 2.4: Graduate Roborazzi — remove Gradle screenshot jobs
**Goal**: After baselines are committed and ≥ 3 consecutive green Bazel runs, remove the Gradle `android` job's Roborazzi step.

#### Story 2.4.1: Remove Roborazzi steps from Gradle android CI job
**As a** CI system, **I want** a single canonical screenshot CI path, **so that** I'm not running both Gradle and Bazel screenshot tests in parallel indefinitely.

**Acceptance Criteria**:
- `recordRoborazziDebug` step removed from `android` job in `ci.yml`.
- `bazel-roborazzi-android` and `bazel-roborazzi-jvm` jobs have `continue-on-error` removed.
- `roborazzi_android_verify` and `roborazzi_jvm_verify` targets have `manual` tags removed.

**Files**: `.github/workflows/ci.yml`, `.github/workflows/bazel-ci.yml`, `kmp/BUILD.bazel`

---

## Phase 3: Android Emulator Smoke Tests via sh_test + ADB
### Epic 3.1: Create sh_test wrapping direct ADB instrumented test execution
**Goal**: Replace the Gradle `android-smoke` CI job with `bazel test //kmp:smoke_tests` that calls `adb shell am instrument` directly — no Gradle involvement in test execution.

**Background**: `android_instrumentation_test` is not exported by `rules_android 0.7.1` and `android_test_support` has no bzlmod path. The alternative is a `sh_test` with `tags = ["local"]` (disables sandbox) that:
1. Reads the APK path from `$ADB_APK_PATH` (set by GHA before calling `bazel test`).
2. Installs the APK via `adb install`.
3. Runs `adb shell am instrument -w` for `AppSmokeTest` and `SqliteCapabilityTest`.
4. Parses the output and exits non-zero on `FAILURES`.

This approach gives `bazel test` semantics (pass/fail) without Gradle.

#### Story 3.1.1: Understand instrumented test APK build path
**As a** developer, **I want** the instrumented test APK available at a known path, **so that** the smoke test sh_test can install it.

**Acceptance Criteria**:
- The instrumented test APK path is identified.
- If the instrumented APK requires Gradle: `./gradlew :kmp:assembleDebugAndroidTest` is added as a GHA step before `bazel test`, passing the APK path via `ADB_APK_PATH` env var.
  - *Given* the APK is built and path is in `$ADB_APK_PATH`, *When* the sh_test runs `adb install "$ADB_APK_PATH"`, *Then* the app is installed on the connected emulator.

**Files**: Investigation only — `androidApp/build.gradle.kts`, `androidApp/src/androidTest/`

##### Task 3.1.1a: Investigate instrumented test APK build path (~5 min)
- Check if `rules_android` can build an instrumented test APK via `android_binary` with `instruments` attribute.
- Check `androidApp/build.gradle.kts` for `assembleDebugAndroidTest` output path.
- Check what APK paths `reactivecircus/android-emulator-runner` currently uses.
- Decision: if instrumented APK requires Gradle, keep `./gradlew :kmp:assembleDebugAndroidTest` as a CI step before `bazel test`; the sh_test is still 100% Gradle-free during its execution.
- Files: none (investigation)

#### Story 3.1.2: Create kmp/smoke_tests sh_test calling adb directly
**As a** developer, **I want** `bazel test //kmp:smoke_tests` to run `AppSmokeTest` and `SqliteCapabilityTest` via ADB, **so that** emulator smoke tests are addressable as a Bazel target.

**Note on target location**: `AppSmokeTest` and `SqliteCapabilityTest` live in `kmp/src/androidInstrumentedTest/` — NOT `androidApp/src/androidTest/`. The target belongs in `kmp/BUILD.bazel` (or `kmp/src/androidInstrumentedTest/BUILD.bazel`), not `androidApp/BUILD.bazel`. The instrumented test APK is built by `:kmp:assembleDebugAndroidTest`, not `:androidApp:assembleDebugAndroidTest`.

**Acceptance Criteria**:
- `kmp/BUILD.bazel` contains `sh_test(name = "smoke_tests", ...)` with `tags = ["local", "manual"]`.
- `tags = ["manual"]` is required alongside `"local"` — without it, `bazel test //...` would attempt to run `smoke_tests` on any machine, failing immediately with "ADB_APK_PATH must be set".
- The test script reads `$ADB_APK_PATH` (fails with clear error if not set).
- Calls `adb wait-for-device`, installs APK, runs `adb shell am instrument -w ... AndroidJUnitRunner`.
- Parses output — exits 1 if `FAILURES`, `INSTRUMENTATION_ABORTED`, or `INSTRUMENTATION_FAILED` appear.
- JUnit XML is produced: the script writes XML with `<failure>` child elements (not `status=` attributes) to `$XML_OUTPUT_FILE`.
- `bazel test //kmp:smoke_tests` exits 0 on success.
  - *Given* an emulator is running and `$ADB_APK_PATH` points to the kmp instrumented test APK, *When* `bazel test //kmp:smoke_tests --config=emulator` runs, *Then* exit 0 if both smoke tests pass.

**Files**: `kmp/BUILD.bazel`, `kmp/smoke_tests.sh`

##### Task 3.1.2a: Create kmp/smoke_tests.sh ADB runner script (~10 min)
- Create `kmp/smoke_tests.sh`:
  ```bash
  #!/usr/bin/env bash
  set -euo pipefail

  if [[ -z "${ADB_APK_PATH:-}" ]]; then
    echo "ERROR: ADB_APK_PATH must be set to the kmp instrumented test APK path." >&2
    echo "       Expected: kmp/build/outputs/apk/androidTest/debug/kmp-debug-androidTest.apk" >&2
    exit 1
  fi

  PACKAGE="dev.stapler.stelekit"
  RUNNER="${PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"
  TESTS="dev.stapler.stelekit.AppSmokeTest,dev.stapler.stelekit.SqliteCapabilityTest"
  TIMESTAMP=$(date -u +%Y-%m-%dT%H:%M:%S)

  # adb wait-for-device is a no-op inside android-emulator-runner@v2's script: block
  # (emulator is already booted), but is a safety net for direct local use.
  echo "Waiting for device..."
  adb wait-for-device

  echo "Installing APK: $ADB_APK_PATH"
  adb install -r "$ADB_APK_PATH"

  echo "Running smoke tests..."
  result=$(adb shell am instrument -w -e class "$TESTS" "$RUNNER" 2>&1) || true
  echo "$result"

  # Use uppercase ADB am-instrument failure tokens only — avoid 'Error' (capital E)
  # which matches benign log lines. Canonical failure tokens: FAILURES, INSTRUMENTATION_ABORTED,
  # INSTRUMENTATION_FAILED.
  if echo "$result" | grep -q "FAILURES\|INSTRUMENTATION_ABORTED\|INSTRUMENTATION_FAILED"; then
    failed=true
    exit_code=1
  else
    failed=false
    exit_code=0
  fi

  # JUnit XML: use <failure> child elements (not status= attribute) for mikepenz/action-junit-report
  # to correctly annotate failures. status= is a pass-through field that tools ignore for pass/fail.
  if [[ -n "${XML_OUTPUT_FILE:-}" ]]; then
    if [[ "$failed" == "true" ]]; then
      failure_el_app='<failure message="smoke test failed">See adb instrument output</failure>'
      failure_el_sql='<failure message="smoke test failed">See adb instrument output</failure>'
    else
      failure_el_app=""
      failure_el_sql=""
    fi
    cat > "$XML_OUTPUT_FILE" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="AndroidSmokeTests" timestamp="${TIMESTAMP}" tests="2">
  <testcase classname="AppSmokeTest" name="smoke">${failure_el_app}</testcase>
  <testcase classname="SqliteCapabilityTest" name="smoke">${failure_el_sql}</testcase>
</testsuite>
EOF
  fi

  exit $exit_code
  ```
- Make executable.
- Files: `kmp/smoke_tests.sh`

##### Task 3.1.2b: Add smoke_tests sh_test to kmp/BUILD.bazel (~3 min)
- In `kmp/BUILD.bazel`, add:
  ```starlark
  # ── Android Emulator Smoke Tests ───────────────────────────────────────────────
  # AppSmokeTest and SqliteCapabilityTest live in kmp/src/androidInstrumentedTest/ —
  # target belongs in kmp/BUILD.bazel, not androidApp/BUILD.bazel.
  # Run via: bazel test //kmp:smoke_tests --config=emulator
  # Requires: emulator running + ADB_APK_PATH=kmp/build/outputs/apk/androidTest/debug/kmp-debug-androidTest.apk
  # No Gradle involvement in test execution — ADB only.
  # tags=["manual"]: prevents bazel test //... from running without an emulator.
  # See ADR-010.
  sh_test(
      name = "smoke_tests",
      srcs = ["smoke_tests.sh"],
      tags = ["local", "manual"],  # local: no sandbox; manual: exclude from //... wildcard
      visibility = ["//visibility:public"],
  )
  ```
- Files: `kmp/BUILD.bazel`, `kmp/smoke_tests.sh`

#### Story 3.1.3: Add --config=emulator to .bazelrc
**As a** developer, **I want** `--config=emulator` to set the right options for running smoke tests, **so that** `bazel test //kmp:smoke_tests --config=emulator` works without extra flags.

**Acceptance Criteria**:
- `.bazelrc` contains `test:emulator --strategy=TestRunner=local`.
- `.bazelrc` contains a comment documenting the emulator config.

**Files**: `.bazelrc`

##### Task 3.1.3a: Add --config=emulator to .bazelrc (~2 min)
- Add at the end of `.bazelrc`:
  ```
  # ── Android emulator smoke tests ─────────────────────────────────────────────
  # Usage: bazel test //kmp:smoke_tests --config=emulator
  # Requires: emulator started by reactivecircus/android-emulator-runner@v2 GHA action.
  # ADB_APK_PATH must be set to the instrumented test APK path before calling bazel test.
  test:emulator --strategy=TestRunner=local
  ```
- Files: `.bazelrc`

#### Story 3.1.4: Add bazel-emulator-smoke CI job to bazel-ci.yml
**As a** CI system, **I want** `bazel test //kmp:smoke_tests` to run in CI with a GHA-managed emulator, **so that** smoke test coverage is maintained without Gradle in the test execution path.

**Acceptance Criteria**:
- New job `bazel-emulator-smoke` in `bazel-ci.yml`:
  1. Builds the instrumented test APK (Gradle step — APK build only, test execution is Bazel)
  2. Starts emulator via `reactivecircus/android-emulator-runner@v2`
  3. Sets `ADB_APK_PATH` to the test APK path
  4. Runs `bazel test //kmp:smoke_tests --config=emulator`
- Job has `continue-on-error: true` initially.
- JUnit XML artifact uploaded.
  - *Given* an emulator is running and the APK is installed, *When* `bazel test //kmp:smoke_tests --config=emulator` runs, *Then* same pass/fail signal as today's Gradle smoke test.

**Files**: `.github/workflows/bazel-ci.yml`

##### Task 3.1.4a: Add bazel-emulator-smoke job to bazel-ci.yml (~10 min)
- Add new job:
  ```yaml
  # ── Android emulator smoke tests (sh_test + ADB) ─────────────────────────────
  # Emulator started by GHA; bazel test invokes adb directly (no Gradle in test execution).
  bazel-emulator-smoke:
    name: Bazel Emulator Smoke Tests
    runs-on: ubuntu-latest
    timeout-minutes: 30
    if: github.event.pull_request.draft == false
    continue-on-error: true  # Remove after 3 consecutive green runs; then remove Gradle android-smoke job
    permissions:
      contents: read
      checks: write
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          java-version: "21"
          distribution: "temurin"

      - uses: android-actions/setup-android@v3

      - uses: gradle/actions/setup-gradle@v4
        with:
          cache-encryption-key: ${{ secrets.GRADLE_ENCRYPTION_KEY }}

      - name: Build instrumented test APK
        # Gradle builds the test APK only; test execution is via adb (no Gradle in test).
        # CORRECTED: AppSmokeTest/SqliteCapabilityTest are in kmp/src/androidInstrumentedTest/,
        # so the test APK is produced by :kmp:assembleDebugAndroidTest, not :androidApp:assembleDebugAndroidTest.
        run: ./gradlew :kmp:assembleDebugAndroidTest --no-daemon

      - name: Cache bazel-cache-proxy binary
        id: bcp-cache
        uses: actions/cache@v4
        with:
          path: ~/.cargo/bin/bazel-cache-proxy
          key: bcp-${{ runner.os }}-7806c5f

      - name: Install bazel-cache-proxy
        if: steps.bcp-cache.outputs.cache-hit != 'true'
        run: |
          sudo apt-get install -y --no-install-recommends protobuf-compiler
          cargo install --git https://github.com/tstapler/bazel-cache-proxy --rev 7806c5f bazel-cache-proxy --locked

      - name: Start bazel-cache-proxy (GHA backend)
        run: |
          printf '[backend]\ntype = "gha"\n' > /tmp/bcp.toml
          ~/.cargo/bin/bazel-cache-proxy serve --config /tmp/bcp.toml &
          sleep 2
          curl -sf http://127.0.0.1:9090/healthz

      - name: Set up Bazel
        uses: bazel-contrib/setup-bazel@0.19.0
        with:
          bazelisk-version: "1.x"
          disk-cache: emulator-smoke
          repository-cache: true
          external-cache: true
          cache-save: ${{ github.event_name != 'pull_request' }}

      - name: Enable KVM access
        run: |
          echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
          sudo udevadm control --reload-rules
          sudo udevadm trigger --name-match=kvm

      - name: Run smoke tests on emulator
        uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 29
          arch: x86_64
          force-avd-creation: false
          emulator-options: -no-snapshot-save -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -camera-back none
          disable-animations: true
          script: |
            # CORRECTED: kmp test APK path (not androidApp); target is //kmp:smoke_tests (not //androidApp:smoke_tests)
            ADB_APK_PATH=kmp/build/outputs/apk/androidTest/debug/kmp-debug-androidTest.apk \
            bazel test //kmp:smoke_tests --config=emulator

      - name: Publish Bazel smoke test report
        uses: mikepenz/action-junit-report@v4
        if: always()
        with:
          report_paths: 'bazel-testlogs/kmp/smoke_tests/test.xml'
          fail_on_failure: true
          check_name: Bazel Emulator Smoke Test Results

      - name: Upload smoke test results
        uses: actions/upload-artifact@v4
        if: always()
        with:
          name: bazel-smoke-test-results
          path: bazel-testlogs/kmp/smoke_tests/test.xml
          retention-days: 7
  ```
- Files: `.github/workflows/bazel-ci.yml`

---

### Epic 3.2: Graduate — remove Gradle android-smoke job
**Goal**: After `bazel-emulator-smoke` is green for ≥ 3 consecutive runs, remove the Gradle `android-smoke` job from `ci.yml`.

#### Story 3.2.1: Remove Gradle android-smoke job from ci.yml
**As a** CI system, **I want** a single canonical emulator smoke test path, **so that** I'm not running both Gradle and Bazel emulator tests in parallel indefinitely.

**Acceptance Criteria**:
- `android-smoke` job entirely removed from `ci.yml`.
- `bazel-emulator-smoke` job has `continue-on-error` removed.
  - *Given* the Bazel emulator job has been green for ≥ 3 runs, *When* a PR is opened, *Then* only `bazel-emulator-smoke` gates the emulator smoke test signal.

**Files**: `.github/workflows/ci.yml`, `.github/workflows/bazel-ci.yml`

---

## Phase 4: CLAUDE.md Build Table Update
### Epic 4.1: Update CLAUDE.md canonical command table
**Goal**: Reflect the new `bazel test //kmp:detekt` and `bazel run //kmp:record_*_screenshots` commands in the CLAUDE.md build table.

#### Story 4.1.1: Update CLAUDE.md Gradle → Bazel equivalence table and add new Bazel-only commands
**As a** developer, **I want** CLAUDE.md to list the new canonical Bazel commands for lint and screenshot recording, **so that** I know the right commands to use.

**Acceptance Criteria**:
- The Gradle → Bazel table in CLAUDE.md adds a row: `./gradlew :kmp:detekt` → `bazel test //kmp:detekt`.
- New section under Bazel commands documents `bazel run //kmp:record_jvm_screenshots` and `bazel run //kmp:record_android_screenshots`.
- The table notes that emulator smoke tests remain Gradle-only.
  - *Given* CLAUDE.md is updated, *When* a developer reads the Bazel command table, *Then* they see `bazel test //kmp:detekt` as the canonical lint command.

**Files**:
- `/home/tstapler/Programming/stelekit/CLAUDE.md`

##### Task 4.1.1a: Update Bazel build table in CLAUDE.md (~3 min)
- In the Gradle → Bazel table, add row: `./gradlew :kmp:detekt` | `bazel test //kmp:detekt`
- Add new subsection under Bazel Build Commands:
  ```
  # Record screenshot baselines
  bazel run //kmp:record_jvm_screenshots     # JVM/Desktop screenshots (requires display)
  bazel run //kmp:record_android_screenshots # Android screenshots (Robolectric-based)
  ```
- Note: `bazel test //kmp:smoke_tests --config=emulator` is the new Bazel canonical; `./gradlew :androidApp:connectedDebugAndroidTest` is removed after Epic 3.2 graduates.
- Files: `CLAUDE.md`

---

## Execution Order Summary

| Priority | Epic | Prerequisite | Risk |
|----------|------|-------------|------|
| 1 | Epic 0.1 — JUnit XML upload | None | Low |
| 2 | Epic 1.1 — MODULE.bazel Detekt deps | Epic 0.1 done | Low |
| 3 | Epic 1.2 — buildSrc/BUILD.bazel (neverlink + service file) | Epic 1.1 | Medium |
| 4 | Epic 1.3 — kmp/BUILD.bazel detekt_test | Epic 1.2 | Medium |
| 5 | Epic 1.4 — bazel-ci.yml bazel-detekt + custom rules smoke-check | Epic 1.3 | Low |
| 6 | Epic 2.0 — MODULE.bazel Roborazzi Maven artifacts | Any | Low |
| 7 | Epic 2.1 — Android native android_local_test verify (pre-flight first) | Epic 2.0 | Medium (runfiles path investigation) |
| 8 | Epic 2.2 — JVM verify with hermetic fonts (pre-flight font set first) | Any | High (font determinism) |
| 9 | Epic 3.1 — Emulator sh_test + ADB (investigate test APK build first) | Any | Medium |
| 10 | Epic 2.3 — bazel-ci.yml Roborazzi jobs | Epics 2.1, 2.2 | Low |
| 11 | Epic 3.1.4 — bazel-ci.yml emulator job | Epic 3.1 | Low |
| 12 | Epic 1.5 — Graduate Detekt (3 green runs) | 3 green runs | Low |
| 13 | Epic 2.1.3 — Commit Android baselines | Runfiles path confirmed | Medium |
| 14 | Epic 2.4 — Graduate Roborazzi (3 green runs) | Epics 2.1.3 + 3 green runs | Low |
| 15 | Epic 3.2 — Graduate Emulator (3 green runs) | 3 green runs | Low |
| 16 | Epic 4.1 — CLAUDE.md update | Epics 1.5, 2.4, 3.2 | Low |
