# ADR-009: Roborazzi Screenshot Tests — Native android_local_test for Android Verify, sh_binary for Record

**Date**: 2026-06-24
**Status**: Accepted (supersedes original genrule-wrapper decision)
**Deciders**: Tyler Stapler
**Context**: bazel-full-ci-migration Epic 2

---

## Context

SteleKit has two categories of screenshot tests:
- **JVM/Desktop**: 8 test classes using `roborazzi-compose-desktop:1.59.0`, run via `jvmTest` with `-Proborazzi.test.record=true`.
- **Android**: 1 test class (`UiStateScreenshotTest`) using `roborazzi:1.59.0` + Robolectric, run via `:kmp:recordRoborazziDebug` / `:kmp:verifyRoborazziDebug`.

The migration goal is to expose these as native Bazel targets that remove Gradle from the CI verify path.

Three approaches were evaluated:

| Approach | Key Strength | Key Weakness |
|----------|-------------|-------------|
| A (original): `sh_binary` targets wrapping Gradle, `tags=["local"]` | Zero test rewrites; follows established pattern | Not hermetically cached; verify mode still Gradle-backed; SM#1 "without Gradle invocation" not satisfied for screenshot verify |
| B: Paparazzi (as Bazel-native screenshot lib) | BCR-adjacent ecosystem | Does not support `roborazzi-compose-desktop`; requires rewriting all JVM screenshot tests |
| C (chosen): Native `android_local_test` for verify, `sh_binary` for record | Verify mode is hermetically cached and fully Gradle-free; record mode retains simplicity of write-in-place | Record mode still Gradle-backed (in-place write); JVM verify requires hermetic font bundling |

## Decision

**Approach C**: Hybrid verify-native, record-Gradle strategy.

### Android screenshots

**Verify mode** — native `android_local_test` target (`//kmp:roborazzi_android_verify`):
- Baseline PNGs declared as `data = glob(["src/androidUnitTest/snapshots/images/**/*.png"])` — read from runfiles tree.
- `jvm_flags = ["-Droborazzi.test.verify=true", "-Droborazzi.output.dir=${TEST_UNDECLARED_OUTPUTS_DIR:-/tmp/roborazzi-diffs}"]`
- No Gradle invocation during test execution.
- Diff images uploaded as GHA test artifacts via `bazel-testlogs/kmp/roborazzi_android_verify/test.outputs/`.

**Record mode** — `sh_binary` target (`//kmp:record_android_screenshots`) wrapping `./gradlew :kmp:recordRoborazziDebug`:
- Record writes PNGs in-place adjacent to source files. Native record would require `--sandbox_writable_path` with an absolute path, which cannot be embedded in a Bazel target portably.
- `tags = ["local", "manual"]`.

### JVM screenshots

**Verify mode** — native `jvm_test` target (`//kmp:roborazzi_jvm_verify`) with hermetic fonts:
- Noto fonts bundled in `third_party/fonts/` as Bazel `data` deps.
- JVM flags: `-Dsun.font.fontpath.override=<runfiles path to fonts>`, `-Dsun.java2d.opengl=false`, `-Dsun.java2d.uiScale=1.0`, `-Djava.awt.headless=true`.
- Diff images to `$TEST_UNDECLARED_OUTPUTS_DIR`.

**Record mode** — `sh_binary` target (`//kmp:record_jvm_screenshots`) wrapping `./gradlew :kmp:jvmTest -Proborazzi.test.record=true`.

## Why Not Fully Native Record Mode

Record mode must write PNG files adjacent to source files in the workspace. Bazel's sandbox mounts source directories read-only. Overcoming this requires either:
1. `--sandbox_writable_path=<absolute-workspace-path>/kmp/src/androidUnitTest/snapshots` — cannot be embedded portably in a Bazel target; must be passed from the calling shell, which defeats the purpose of a canonical run target.
2. Nested `bazel` invocations — explicitly prohibited by Bazel.

Record is a developer-facing workflow (not CI-gated), so Gradle-backed sh_binary is acceptable.

## Consequences

**Positive**:
- `bazel test //kmp:roborazzi_android_verify` is fully hermetic and cacheable — no Gradle in the CI verify path.
- Screenshot regressions surface as Bazel test failures with JUnit XML annotations.
- Baseline PNGs are declared as explicit Bazel inputs — any uncommitted change is detectable.
- `bazel run //kmp:record_android_screenshots` remains the single canonical command for baseline updates.

**Negative / Risks**:
- Record mode still requires Gradle and Java on the running machine.
- JVM verify mode requires confirming font determinism across GHA runner images before enabling the CI gate (tracked as pre-flight Task 2.2.1pre).
- Roborazzi's baseline path resolution must be verified to respect `$TEST_SRCDIR` or `user.dir` in the `android_local_test` runfiles layout (tracked as pre-flight Task 2.1.1pre).

## Previous Decision

The original decision (genrule-backed sh_binary for everything) was superseded after the user requested deeper native Bazel integration. The genrule approach remains the escape hatch if native `android_local_test` proves infeasible.
