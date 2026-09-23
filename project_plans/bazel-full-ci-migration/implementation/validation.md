# Validation Plan: bazel-full-ci-migration

**Date**: 2026-06-24

---

## Happy Path Scenario

Given a clean checkout of the `main` branch with Bazel 9.1.1, Java 21, and no Android device connected,
when a developer runs `bazel test //...` on a workstation,
then Detekt passes with zero violations above baseline, JVM tests pass with JUnit XML emitted to `bazel-testlogs/`, Roborazzi record targets are reachable via `bazel run`, and no `./gradlew` invocation is required for any of those operations.

---

## Requirement → Test Mapping

| Requirement | Test / Verification | Type | Scenario | When to run |
|---|---|---|---|---|
| **SM1**: `bazel test //...` covers lint, screenshot verification, and unit tests without Gradle | BV-SM1-A: `bazel test //kmp:detekt //kmp:jvm_tests //...` exits 0 on clean checkout | Build verification | Baseline repo, no violations introduced | After Epic 1.3 + 2.1 land |
| **SM1**: `bazel test //...` covers lint, screenshot verification, and unit tests without Gradle | BV-SM1-B: Clean checkout with no `./gradlew` or `gradle` in PATH — `bazel test //kmp:detekt //kmp:jvm_tests` still exits 0 | Negative / isolation test | Remove Gradle wrapper from PATH; verify Bazel targets are self-contained for Detekt and JVM unit tests | After Epic 1.3 lands |
| **SM1**: Roborazzi verify reachable via Bazel | BV-SM1-C: `bazel query //kmp:verify_android_screenshots` resolves; `bazel build //kmp:verify_android_screenshots` succeeds | Build verification | Target exists in kmp/BUILD.bazel | After Story 2.1.2 |
| **SM2**: Detekt violations caught by `bazel test` — zero regressions vs. current config | NEG-SM2-A: Introduce a synthetic Detekt violation in `kmp/src/commonMain/` (e.g., unused import or a known custom-rule trigger); run `bazel test //kmp:detekt`; assert non-zero exit code and violation in output | Negative / functional smoke | One synthetic violation; custom rule `SteleKitRuleSetProvider` must fire | After Story 1.3.1 |
| **SM2**: Custom rules load correctly | SMOKE-SM2-B: `bazel test //kmp:detekt` passes on clean checkout; inspect `bazel-testlogs/kmp/detekt/test.xml` — assert `testsuite errors="0"` and `tests > 0` | Functional smoke | Clean repo, no violations | After Story 1.3.1 |
| **SM2**: Custom rules discoverable via ServiceLoader | MANUAL-SM2-C: Extract `buildSrc` JAR from Bazel cache (`bazel build //buildSrc:detekt_rules`); run `jar tf <path>` and assert `META-INF/services/io.gitlab.arturbosch.detekt.api.RuleSetProvider` entry exists | Manual verification | Inspecting artifact structure | After Story 1.2.2 |
| **SM2**: detekt-api version skew does not cause runtime failure | MANUAL-SM2-D: Run `bazel test //kmp:detekt` with verbose output; confirm no `ClassNotFoundException` or `NoSuchMethodError` from `detekt-api` mismatches | Manual verification | detekt-api 1.23.7 vs rules_detekt's bundled 1.23.8 | Before Epic 1.1 merges (pre-flight gate) |
| **SM3**: Roborazzi baselines stored in repo and verified by Bazel | BV-SM3-A: `bazel run //kmp:record_jvm_screenshots` exits 0; PNG files appear under `kmp/src/jvmTest/snapshots/` | Build verification (run mode) | JVM screenshots recorded to workspace | After Story 2.1.1 |
| **SM3**: Roborazzi Android record reachable | BV-SM3-B: `bazel run //kmp:record_android_screenshots` exits 0 with a connected Android device / emulator | Build verification (run mode) | Android screenshots recorded | After Story 2.1.2 |
| **SM3**: Roborazzi Android verify catches a regression | NEG-SM3-C: Commit a baseline PNG; modify the corresponding Compose composable by one pixel; run `bazel test //kmp:verify_android_screenshots`; assert non-zero exit and diff artifact produced | Negative / functional smoke | Intentional 1-pixel delta in a Compose composable | After Story 2.1.3 (baselines committed) |
| **SM4**: Android emulator smoke tests stay in Gradle; documented non-migration | DOC-SM4-A: `kmp/BUILD.bazel` contains a comment block referencing ADR-010 explaining why `android_instrumentation_test` is not migrated | Documentation verification (grep) | Verify comment exists in BUILD file | After Phase 3 |
| **SM4**: `.bazelrc` stub exists for future emulator config | DOC-SM4-B: `.bazelrc` contains `# config emulator` stub; `bazel test --config=emulator //...` exits 0 (no-op, no targets match) | Build verification | No emulator targets exist yet; config is inert | After Phase 3 |
| **SM5**: Gradle Detekt job removed after 3 green Bazel runs | GRAD-SM5-A: `ci.yml` does not contain a `lint:` job block; `actionlint` passes on `ci.yml` after removal | CI YAML lint | Post-graduation state | After Epic 1.5 (graduation) |
| **SM5**: CI wall-clock not regressing > 20% | PERF-SM5-A: Compare GitHub Actions job-duration summary between pre-migration baseline and post-Epic-1 state; `bazel-detekt` job < 1.2× the Gradle `lint` job duration | Manual verification (GH Actions UI) | Two consecutive `push` runs on `main` | After Epic 1.4 is green for 3 runs |
| **SM5**: `bazel-ci.yml` jobs pass `actionlint` | CI-SM5-B: `actionlint -color .github/workflows/bazel-ci.yml` exits 0 after each phase's YAML changes | CI YAML lint | All phases; run locally before pushing | Continuously — after every YAML edit |
| **Epic 0**: JUnit XML upload surfaces failures as PR annotations | INTG-E0-A: On a branch with a known failing test in `//kmp:jvm_tests`, push to a PR; verify the PR checks panel shows an inline annotation (not just a red job) sourced from `mikepenz/action-junit-report` | Integration (GitHub PR) | Intentional test failure on a feature branch | After Story 0.1.1 |
| **Epic 0**: `checks: write` scoped to test jobs only | CI-E0-B: `bazel-ci.yml` `permissions:` at workflow level remains `contents: read`; `checks: write` appears only in `bazel-jvm` and `bazel-android-tests` job-level blocks | CI YAML lint / code review | Prevent over-provisioning | After Task 0.1.1a |
| **Epic 0**: `--test_output=errors` in CI config only | BV-E0-C: `.bazelrc` `test:ci` stanza includes `--test_output=errors`; global `test` stanza does NOT include it | File content verification (grep) | Ensure local dev output is not silenced | After Task 0.1.1c |
| **Story 1.1.1**: Maven artifacts resolvable | MANUAL-1-1: `mvn dependency:get -Dartifact=io.nlopez.compose.rules:detekt:0.4.27` exits 0; `mvn dependency:get -Dartifact=io.gitlab.arturbosch.detekt:detekt-api:1.23.7` exits 0 | Manual pre-flight | Maven Central availability | Before Story 1.1.1 merges (blocks Epic 1) |

---

## Test Stack

- **Build verification**: `bazel build <target>` and `bazel test <target>` assertions — local and CI
- **CI YAML lint**: `actionlint -color .github/workflows/bazel-ci.yml` and `actionlint -color .github/workflows/ci.yml` after every YAML edit
- **Functional smoke**: `bazel test //kmp:detekt` on a clean checkout (no local build cache) to confirm no cached green
- **Negative tests**: Introduce a synthetic Detekt violation (unused import or known custom-rule pattern); verify non-zero exit and violation text in `bazel-testlogs/kmp/detekt/test.log`
- **Manual verification steps** (labeled `MANUAL-*`): pre-flight artifact checks (Maven Central, JAR META-INF/services, version-skew runtime errors, GH Actions UI wall-clock timing)
- **Integration** (labeled `INTG-*`): push to a real PR branch to confirm JUnit XML → PR annotation pipeline

---

## Pre-flight Gates (block story merges)

These must pass before the associated story is merged:

| Gate | Blocks | Check |
|---|---|---|
| `io.nlopez.compose.rules:detekt:0.4.27` on Maven Central | Story 1.1.1 | `mvn dependency:get` exits 0 |
| `detekt-api:1.23.7` on Maven Central | Story 1.1.1 | `mvn dependency:get` exits 0 |
| `detekt-api` version skew — no runtime ClassNotFoundException | Story 1.2.2 | `bazel test //kmp:detekt` with custom rules loaded; grep test.log for `ClassNotFoundException` |
| Roborazzi JVM PNG determinism across 2 CI runs | Story 2.1.3 | Record on CI twice; diff PNGs; byte-identical or acceptable delta |

---

## Graduation Criteria

Graduation = removal of the corresponding Gradle CI job.

| Gradle job to remove | Graduation gate |
|---|---|
| `lint` (Detekt) in `ci.yml` | 3 consecutive green `bazel-detekt` runs on `main` push |
| `android` Roborazzi step in `ci.yml` | Baselines committed (Story 2.1.3) + 3 green `bazel-roborazzi` runs on `main` |

Graduation is verified by:
1. `actionlint` clean on `ci.yml` after job removal
2. No `lint:` or `recordRoborazziDebug` block in `ci.yml` (grep assertion)

---

## Coverage Targets

N/A — CI toolchain migration. Coverage is measured by SM pass/fail, not line coverage.

All 5 success metrics have at least one happy-path verification and one failure-path verification. SM4 has documentation-only coverage (intentional: documented non-migration per ADR-010).

---

## Test Case Summary

| Type | Count |
|---|---|
| Build verification (`bazel build` / `bazel test`) | 8 |
| CI YAML lint (`actionlint`) | 3 |
| Functional smoke (local `bazel test` on clean checkout) | 2 |
| Negative tests (introduce violation; assert non-zero exit) | 3 |
| Manual verification (pre-flight, GH Actions UI) | 5 |
| Integration (push to real PR) | 1 |
| Documentation verification (grep / code review) | 3 |
| **Total** | **25** |

**Requirements coverage**: 5 / 5 success metrics covered (SM4 via documented non-migration, not functional test — matches ADR-010 intent).
