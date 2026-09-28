# UX Research: Bazel Full CI Migration

**Date**: 2026-06-24
**Project**: bazel-full-ci-migration
**Audience**: Tyler Stapler (sole developer)

---

## 1. Local Developer Workflow: Ideal Command Sequences

### 1a. Run all checks locally (lint + screenshot verify + unit tests)

**Current (Gradle):**
```bash
xvfb-run --auto-servernum ./gradlew ciCheck
```
`ciCheck` is a single aggregate task wiring detekt + jvmTest + testDebugUnitTest + assembleDebug.

**After migration (target state):**
```bash
bazel test //... --config=ci
```
This is already documented in CLAUDE.md's build table as the Bazel equivalent of `./gradlew ciCheck`. The migration adds three missing target groups to that coverage: Detekt, Roborazzi verify, and smoke tests.

**DX gap today (pre-migration):** `bazel test //...` currently excludes Detekt (no Bazel target), screenshot verification (Roborazzi excluded from both jvmTest and androidUnitTest BUILD files via glob excludes), and emulator smoke tests (no `android_instrumentation_test` target). A developer who runs `bazel test //...` does not get the same signal as CI.

**Headless display requirement:** JVM tests already require xvfb-run on headless Linux due to Compose Desktop. After migration, screenshot tests will carry the same requirement. The CLAUDE.md xvfb-run guidance for `ciCheck` should be mirrored for `bazel test //...`.

### 1b. Update screenshot baselines after a UI change

**Current (Gradle):**
```bash
./gradlew :kmp:recordRoborazziDebug
```
Outputs PNG files to `kmp/src/androidUnitTest/snapshots/images/`. Developer commits the updated PNGs.

**After migration (target state):**
```bash
bazel run //kmp:record_screenshots   # proposed target name
```
This target should run the Roborazzi test suite in record mode and write baseline PNGs back to the source tree. Because Bazel tests run inside a sandbox with read-only source mounts, the `bazel run` pattern (not `bazel test`) is the correct mechanism: the `run` target can receive `--output_user_root` writable access or use `$(location)` to write back to the workspace.

**Key design constraint:** Bazel's hermetic sandbox makes in-place PNG writes impossible from a `bazel test` action. The record target *must* be a `bazel run` binary (or genrule with `tags = ["local"]`) to sidestep the sandbox. This is already the documented pattern for `//kmp:web_app` (which uses `tags = ["local"]` to allow Gradle network access).

**Failure mode if not addressed:** If record mode is accidentally left as a `bazel test` target, recorded PNGs will be written into the sandbox's ephemeral tmp dir and discarded. The developer will see a green test run but no updated baselines. This is a silent DX trap.

### 1c. See a Detekt violation with file/line number and rule name

**Current (Gradle):**
Detekt's Gradle plugin produces:
- Console output: `src/commonMain/kotlin/Foo.kt:42:10: error: FunctionNaming: ...`
- HTML report: `kmp/build/reports/detekt/detekt.html`
- SARIF report: `kmp/build/reports/detekt/detekt.sarif`

The SARIF report is structured for GitHub's code scanning feature (annotates PRs with inline violation markers). HTML is human-readable locally.

**After migration:**
If Detekt runs as a Bazel `genrule` or `java_test`, its stdout is captured in `bazel-testlogs/kmp/.../test.log`. With `--test_output=all`, violations appear inline. With `--test_output=errors` (the default), they only appear on failure.

The SARIF and HTML reports must be explicitly declared as `outs` of the genrule or uploaded as test artifacts. Without this, a CI failure shows only the raw Detekt console text in the GHA log — no inline PR annotations.

**Recommended `--test_output` for Detekt:** `--test_output=all` always, since Detekt is fast (~2 min) and the violation list is the primary output. Unlike unit tests where `errors` mode avoids printing thousands of passing test names, Detekt has no "passing test" noise.

**Detekt report format in Bazel context:** The plain-text console output from Detekt already includes `file:line:col: rule: message`. This is human-readable without any special tooling. The SARIF file needs to be declared as a `bazel test` output artifact and uploaded to GHA for inline PR annotations — this is the main DX gap vs. the current Gradle approach (which auto-uploads `kmp/build/reports/detekt/` as a CI artifact).

### 1d. See a screenshot diff failure with a visual comparison

**Current (Gradle):**
Roborazzi generates:
1. A diff PNG at `kmp/src/androidUnitTest/snapshots/compare/` showing side-by-side before/after
2. An HTML report (Roborazzi outputs a test failure message with the diff path)
3. CI uploads `kmp/src/androidUnitTest/snapshots/` as artifact `roborazzi-screenshots` (14-day retention)

The developer downloads the artifact from the GHA run, opens the `compare/` directory, and inspects the diff images directly.

**After migration:**
Bazel sandbox isolates test outputs. The diff PNGs will be written inside the sandbox's working dir. To surface them:
1. They must be declared as test outputs via `--test_output=streamed` or the test must write them to `$TEST_UNDECLARED_OUTPUTS_DIR` (Bazel's standard mechanism for test-generated artifacts).
2. `bazel-testlogs/<target>/test.outputs/` will contain any files written to `$TEST_UNDECLARED_OUTPUTS_DIR`.
3. CI must upload `bazel-testlogs/**/test.outputs/**` as a GHA artifact.

**Key design requirement (from requirements.md):** "Screenshot diff failures must produce a human-readable artifact (diff image or HTML report) uploadable as a GHA artifact." The `$TEST_UNDECLARED_OUTPUTS_DIR` mechanism satisfies this if the Roborazzi test is instrumented to write diffs there on failure.

---

## 2. CI Failure UX: What the Developer Sees in GHA

### Current CI failure UX (Gradle)

| Check | GHA output | Artifact |
|---|---|---|
| Detekt | Plain text in step log with file:line, plus SARIF if GitHub code scanning is wired | `detekt-report/` (HTML + SARIF, 7-day) |
| Roborazzi | JUnit XML failure with diff path in message; step log shows failing test name | `roborazzi-screenshots/` (PNG diffs, 14-day) |
| JVM tests | `mikepenz/action-junit-report` publishes inline check annotations per-test | `jvm-test-results/` (XML, 7-day) |
| Android unit tests | Same as JVM | `android-test-results/` (XML, 7-day) |
| Smoke tests | `mikepenz/action-junit-report`, connected test XML | `smoke-test-results/` (HTML report, 14-day) |

The `mikepenz/action-junit-report` action parses JUnit XML and annotates failed test files with inline GHA check annotations (showing file + line in the PR diff view). This is the primary failure signal for tests.

### Current Bazel CI failure UX (bazel-ci.yml)

**Critical gap:** `bazel-ci.yml` has **no `actions/upload-artifact` for test results** (except web dist). It uses `--test_output=all` on JVM and Android unit tests, so failures are visible in the raw step log, but there are no:
- JUnit XML uploads
- `mikepenz/action-junit-report` annotations
- `EnricoMi/publish-unit-test-result-action` annotations

This is a DX regression vs. the Gradle `ci.yml`. Bazel does produce JUnit XML at `bazel-testlogs/<target>/test.xml` (confirmed: this repo has `bazel-testlogs/kmp/src/jvmTest/kotlin/jvm_tests/test.xml` with full test suite data), but it is not currently uploaded.

**JVM test log format (observed):** `test.log` contains raw INFO/WARN log output from the app (GraphLoader logs, performance warnings) plus JUnit4 dot-notation for passing tests. A failure would append the stack trace inline. The log is verbose — dozens of lines of GraphLoader INFO per test — because `--test_output=all` streams everything. This makes failures hard to locate without scrolling.

**Recommended `--test_output` per target:**

| Target | Recommended | Reason |
|---|---|---|
| `//kmp:jvm_tests` | `--test_output=errors` | 602 tests; passing output is noisy with GraphLoader logs |
| `//kmp:business_tests` | `--test_output=errors` | Same rationale |
| `//kmp/src/androidUnitTest/...` | `--test_output=errors` | Robolectric output is verbose |
| Detekt (when added) | `--test_output=all` | Violation list IS the output; no passing-test noise |
| Roborazzi verify (when added) | `--test_output=errors` | Failures produce diff artifacts; passing is silent |
| Smoke/emulator tests | `--test_output=errors` | Logcat is extremely verbose |

**JUnit XML upload gap:** All three new targets (Detekt, Roborazzi, smoke tests) must upload `bazel-testlogs/**/test.xml` to surface inline GHA annotations. The existing Gradle CI jobs use `mikepenz/action-junit-report` for this. The Bazel CI jobs currently skip this entirely.

---

## 3. Discoverability: How Does the Developer Know What to Run?

### What's already documented (CLAUDE.md build table)

The CLAUDE.md Bazel build table correctly documents:
- `bazel run //kmp:desktop_app` — launch desktop
- `bazel test //kmp:jvm_tests` — JVM tests
- `bazel test //kmp:business_tests` — business tests only
- `bazel build //kmp:android_app --config=android` — APK
- `bazel test //...` — all tests
- `bazel test //... --config=ci` — CI equivalent of `./gradlew ciCheck`

**Gap:** The table says `bazel test //...` is equivalent to `./gradlew allTests`, and `bazel test //... --config=ci` is equivalent to `./gradlew ciCheck`. But today `bazel test //...` does NOT cover Detekt, Roborazzi, or smoke tests. Once migration completes, this equivalence becomes true — but there will be an interim period where the table is accurate about intent but not coverage.

**Recommendation:** Add a note to the table distinguishing current vs. post-migration coverage, or add a `# TODO: after Epic bazel-full-ci-migration` comment inline.

### Should new targets appear in `bazel test //...` automatically?

Yes, for Detekt and Roborazzi verify. These are blocking checks — they should be in the default `//...` expansion. A developer running `bazel test //...` should get the same signal as CI.

Smoke/emulator tests are a different case: they require a running emulator (external device), which is not available in a standard local `bazel test //...` invocation. These should use `tags = ["manual"]` or a flag like `--config=emulator` to exclude them from the default `//...` expansion — same way `--config=android` is required for Android APK targets.

### `bazel run` vs. `bazel test` mental model

- `bazel test`: hermetic, sandboxed, produces pass/fail signal. Use for verification.
- `bazel run`: launches a binary with workspace access. Use for record/update/generate workflows.

The record-screenshots target must be `bazel run` because it writes back to the source tree. This is already the pattern for `//kmp:desktop_app`. The CLAUDE.md should document `bazel run //kmp:record_screenshots` explicitly once the target exists.

---

## 4. Mental Model Shift: Bazel vs. Gradle for Detekt

### Cognitive differences

| Dimension | `./gradlew :kmp:detekt` | `bazel test //kmp:detekt` |
|---|---|---|
| Incremental cache | Gradle's own up-to-date check (input hash on source files) | Bazel action cache (hermetic hash of all inputs including config) |
| Config file changes | `config/detekt/detekt.yml` change invalidates task | Same: config is a declared `data` dep |
| buildSrc plugin | Auto-picked up by Gradle's buildSrc convention | Must be explicitly declared as a `data` dep or compiled JAR dep |
| Baseline file | `config/detekt/baseline.xml` read automatically | Must be declared as a `data` dep |
| Report location | `kmp/build/reports/detekt/detekt.html` | `bazel-testlogs/kmp/.../test.log` (raw); HTML/SARIF must be declared `outs` |
| SARIF for PR annotations | Automatic with `sarif.required.set(true)` | Requires explicit GHA upload step targeting `bazel-testlogs/**/test.outputs/` |

### What might be surprising

1. **buildSrc.jar dependency**: The current `detekt { }` config in `build.gradle.kts` uses `detektPlugins(files("${rootProject.projectDir}/buildSrc/build/libs/buildSrc.jar"))`. In Bazel, `buildSrc` doesn't exist — the custom ruleset JAR must be built as a `kt_jvm_library` target and declared as a dep. This is non-trivial if the buildSrc rules themselves use Gradle conventions.

2. **Source set enumeration**: Detekt's Gradle config explicitly lists four source sets (`commonMain`, `jvmMain`, `androidMain`, `iosMain`). A Bazel genrule or java_test must replicate this exactly. Missing a source set silently reduces lint coverage with no error.

3. **Classpath for type resolution**: Detekt's type-aware rules require the compiled classpath of the module under analysis. The Gradle plugin handles this automatically via task dependencies. In Bazel, if Detekt runs as a standalone genrule, it may lack the compiled KMP sources as classpath inputs — degrading to syntax-only analysis. This is the "Detekt classpath" rabbit hole noted in requirements.md.

4. **Speed perception**: `./gradlew :kmp:detekt` is fast because Gradle's incremental task skips re-running on unchanged sources. `bazel test //kmp:detekt` will also be fast after the first run (Bazel action cache), but the first run after a workspace clean will be slower due to JVM startup + analysis. Developers used to instant Gradle incremental results may find cold-cache Bazel slower.

---

## 5. Error Message Quality

### Current Bazel test output verbosity issue

The observed `test.log` for `//kmp:jvm_tests` shows that INFO/WARN log output from the app's runtime (GraphLoader, Performance warnings) is streamed alongside test results. With `--test_output=all`, a developer sees hundreds of lines of log output before the test result summary. A failure is buried in the middle.

**Recommended `.bazelrc` addition:**
```
# Default: show test output only on failure, not for every passing test
test --test_output=errors
```
This means `bazel test //...` will be quiet on success and print the full `test.log` only for failing targets. This matches standard developer expectations (Maven's Surefire, Gradle's test output mode).

The current `bazel-ci.yml` uses `--test_output=all` explicitly for all test targets, which will print thousands of log lines even on success. This should be changed to `--test_output=errors` in CI as well, with the `.bazelrc` default covering local runs.

### Detekt-specific output

When Detekt finds violations, the output format is:
```
src/commonMain/kotlin/dev/stapler/stelekit/Foo.kt:42:10: error: [FunctionNaming] Function name should start with a lowercase letter...
```
This includes file path (relative to workspace root), line, column, rule ID, and message. It is already human-readable and IDE-clickable (IntelliJ recognizes the `file:line:col` format). No special formatting is needed.

**SARIF for GHA annotations:** The SARIF file from Detekt (`detekt.sarif`) contains structured location data that the `github/codeql-action/upload-sarif` action can use to create inline PR annotations. This is a significant UX improvement over raw log scanning. The migration should preserve SARIF upload — either as a Bazel test output artifact or via a post-test step in the workflow.

### Roborazzi diff output

When a screenshot comparison fails, Roborazzi writes:
1. The actual screenshot PNG (what the test rendered)
2. The diff PNG (pixel-level difference highlighted)
3. A JUnit failure message with the file path of the diff

These go to `$TEST_UNDECLARED_OUTPUTS_DIR` if the test is instrumented for it, otherwise to Roborazzi's default output directory. The developer needs the diff PNG to understand what changed visually — the JUnit failure message alone ("expected image differs from actual") is insufficient.

**GHA artifact pattern:** The migration should ensure `bazel-testlogs/**/test.outputs/**` is uploaded as a CI artifact, with a descriptive name (`roborazzi-diffs`) and sufficient retention (14 days, matching the current Gradle job).

---

## 6. DX Improvements vs. Regressions

### Improvements the migration introduces

| Area | Improvement |
|---|---|
| Single command | `bazel test //...` covers lint + screenshots + unit tests without Gradle |
| Remote cache | Bazel's action cache (via bazel-cache-proxy) means clean-build detekt on CI hits cache; Gradle does not share cache across CI runs |
| Hermetic reproducibility | Detekt and screenshot tests run in the same hermetic sandbox as unit tests — no hidden dependency on local Gradle plugin versions |
| Discoverability | All checks are Bazel targets; `bazel query //...` lists them |

### Regressions or DX gaps the migration introduces

| Area | Gap | Fix |
|---|---|---|
| JUnit XML upload in bazel-ci.yml | Currently absent; test failures visible only in raw step log | Add `actions/upload-artifact` for `bazel-testlogs/**/test.xml` + `mikepenz/action-junit-report` step |
| SARIF/HTML Detekt report | Gradle auto-uploads HTML+SARIF; Bazel requires explicit `$TEST_UNDECLARED_OUTPUTS_DIR` or declared genrule outs | Declare SARIF as test output; add `github/codeql-action/upload-sarif` step |
| Screenshot record workflow | `./gradlew :kmp:recordRoborazziDebug` is simpler than `bazel run //kmp:record_screenshots` | Document in CLAUDE.md; add to build table |
| Roborazzi diff artifacts | Diff PNGs silently lost in sandbox unless `$TEST_UNDECLARED_OUTPUTS_DIR` is used | Instrument Roborazzi test to write diffs to this env var |
| Smoke test discovery | `bazel test //...` must not launch an emulator unexpectedly | Tag smoke tests `manual` or gate behind `--config=emulator` |
| `--test_output=all` in bazel-ci.yml | Makes CI logs noisy even on success | Change to `--test_output=errors` in both `.bazelrc` and `bazel-ci.yml` |

---

## 7. Recommendations

### P0 (blocking for migration success)

1. **Add JUnit XML upload + test report annotation to `bazel-ci.yml`** for all three new targets. Use the same `mikepenz/action-junit-report` pattern as `ci.yml`. Without this, CI failures show no inline annotations — a significant DX regression.

2. **Use `$TEST_UNDECLARED_OUTPUTS_DIR` for Roborazzi diff PNGs**. Instrument the screenshot test to copy diff images there on failure. Add `bazel-testlogs/**/test.outputs/**` to the CI artifact upload.

3. **Record mode must be `bazel run`, not `bazel test`**. Design the record target as a binary (`kt_jvm_binary` or genrule with `tags = ["local"]`) that writes PNGs back to the workspace. Document this explicitly in CLAUDE.md.

### P1 (high value, implement in same PR)

4. **Change `--test_output` default in `.bazelrc`** to `errors`. Update `bazel-ci.yml` to use `--test_output=errors` (not `all`) for JVM and Android tests. Detekt is the only target that benefits from `all`.

5. **Tag smoke/emulator tests `manual` or `requires-emulator`** to prevent `bazel test //...` from blocking on a missing emulator locally.

6. **Update CLAUDE.md build table** with the three new Bazel commands once targets exist:
   - `bazel test //kmp:detekt` (or whatever the lint target is named)
   - `bazel test //kmp:screenshot_verify`
   - `bazel run //kmp:record_screenshots`
   - Note clearly: `bazel test //... --config=ci` now covers all checks (post-migration)

### P2 (polish)

7. **Add `github/codeql-action/upload-sarif`** step in `bazel-ci.yml` for Detekt's SARIF output. This restores inline PR annotation for Detekt violations.

8. **Add `.bazelrc` comment** explaining why `--test_output=errors` is the right default, with a note that Detekt overrides to `all` when run standalone.

9. **Consider `bazelisk` alias documentation**: The CLAUDE.md references `bazel` directly. If `bazelisk` is the installed binary (which manages the `.bazelversion` pin), add a note that `bazel` resolves to `bazelisk` on this machine.
