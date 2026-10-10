# Stack Research: bazel-full-ci-migration

**Date**: 2026-06-24
**Scope**: Detekt, Roborazzi screenshot tests, Android emulator smoke tests under Bazel

---

## Repo Baseline (current versions)

| Component | Version | Where declared |
|---|---|---|
| rules_kotlin | 2.3.20 | MODULE.bazel |
| rules_android | 0.7.1 | MODULE.bazel |
| rules_jvm_external | 6.9 | MODULE.bazel |
| rules_robolectric | 4.16.1 | MODULE.bazel |
| Detekt | 1.23.7 | settings.gradle.kts (plugin) |
| Compose rules for Detekt | io.nlopez.compose.rules:detekt:0.4.27 | kmp/build.gradle.kts |
| Roborazzi | 1.59.0 | kmp/build.gradle.kts |
| Robolectric | 4.16 | kmp/build.gradle.kts / MODULE.bazel |
| Kotlin stdlib | 2.3.21 | MODULE.bazel maven.install |

---

## Target 1: Detekt (`bazel test //kmp:detekt`)

### Available rules

**`rules_detekt` (BCR: `buildfoundation/bazel_rules_detekt`)**

- BCR latest: `0.8.1.13` — ships Detekt **1.23.8** internally
- Bzlmod declaration: `bazel_dep(name = "rules_detekt", version = "0.8.1.13")`
- Source: https://github.com/buildfoundation/bazel_rules_detekt
- BCR page: https://registry.bazel.build/modules/rules_detekt

The rule set exposes two rules:
- `detekt` — build-time rule; violations fail the build action (like a compiler error)
- `detekt_test` — test-time rule; build always succeeds, violations fail the test

For CI parity with the Gradle `./gradlew :kmp:detekt` job, `detekt_test` is the right
choice: violations emit structured test results (XML) consumed by the JUnit report
action without blocking compilation.

### Key attributes

| Attribute | Purpose |
|---|---|
| `srcs` | Kotlin source files to analyse |
| `cfgs` | Detekt YAML config files (`.yml` only) |
| `baseline` | Path to baseline XML |
| `plugins` | Any `JavaInfo`-providing label (custom rule JARs) |
| `build_upon_default_config` | Boolean — extends built-in defaults rather than replacing |

### SteleKit-specific complexity: two plugin JARs

The Gradle configuration loads two detekt plugins:
1. `buildSrc.jar` — a custom rule set compiled from `buildSrc/src/main/kotlin/dev/stapler/detekt/` (~12 rules covering `@DirectSqlWrite`, coroutine patterns, Compose patterns, etc.)
2. `io.nlopez.compose.rules:detekt:0.4.27` — external Compose-specific rules

For the Bazel target:
- Plugin 1 requires building `buildSrc` as a `kt_jvm_library` target and passing its output JAR label to `plugins`
- Plugin 2 must be declared in `maven.install` (it is not currently in MODULE.bazel) and passed as a `@maven//:io_nlopez_compose_rules_detekt` label

### Source sets

The Gradle `detekt {}` block analyses:
- `src/commonMain/kotlin`
- `src/jvmMain/kotlin`
- `src/androidMain/kotlin`
- `src/iosMain/kotlin`

The Bazel target's `srcs` must aggregate these four source trees via `glob()` or a
filegroup. Generated SQLDelight sources in `src/generated/` are excluded via `detekt.yml`
and must remain excluded.

### Config files

- `kmp/config/detekt/detekt.yml` (9.6 KB) — main config
- `kmp/config/detekt/baseline.xml` (53.8 KB) — suppressions baseline

Both must be passed via `cfgs` and `baseline` respectively.

### Detekt 1.x vs 2.x status

Detekt 2.0 (targeting Kotlin 2.2+) is in alpha as of 2026. `rules_detekt 0.8.1.13`
ships Detekt 1.23.8. The repo is currently on Detekt 1.23.7 via the Gradle plugin.
These are compatible — the Bazel version is one patch ahead. Detekt 2.x migration is
**out of scope** for this migration.

### Integration approach (recommended)

```starlark
# kmp/BUILD.bazel (addition)
load("@rules_detekt//detekt:defs.bzl", "detekt_test")

detekt_test(
    name = "detekt",
    srcs = glob([
        "src/commonMain/kotlin/**/*.kt",
        "src/jvmMain/kotlin/**/*.kt",
        "src/androidMain/kotlin/**/*.kt",
        "src/iosMain/kotlin/**/*.kt",
    ]),
    cfgs = ["config/detekt/detekt.yml"],
    baseline = "config/detekt/baseline.xml",
    build_upon_default_config = True,
    plugins = [
        "//buildSrc:buildSrc_detekt_plugins",  # kt_jvm_library target to be created
        "@maven//:io_nlopez_compose_rules_detekt",
    ],
)
```

MODULE.bazel additions required:
```starlark
bazel_dep(name = "rules_detekt", version = "0.8.1.13")
# Add to maven.install artifacts:
# "io.nlopez.compose.rules:detekt:0.4.27"
```

---

## Target 2: Roborazzi Screenshot Tests

### Current Gradle setup

- Plugin: `id("io.github.takahirom.roborazzi") version "1.59.0"`
- Tasks: `recordRoborazziDebug` (write goldens), `verifyRoborazziDebug` (compare)
- Test runner: `RobolectricTestRunner` + `@GraphicsMode(GraphicsMode.Mode.NATIVE)` via Robolectric 4.16
- Test location: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/UiStateScreenshotTest.kt`
- Snapshot output: `kmp/src/androidUnitTest/snapshots/images/`

### Bazel support status

**Roborazzi has no native Bazel support and no `rules_roborazzi` in BCR.**

The upstream FAQ explicitly acknowledges Bazel support as "not supported" as of 2026.
There is no equivalent to `rules_paparazzi` for Roborazzi. Paparazzi itself (Square's
screenshot library) has no Bazel rules either.

### Feasible approach: `android_local_test` + `genrule` wrapper

Since Roborazzi runs on top of Robolectric (JVM, no device needed), the tests can
be wired through Bazel's `android_local_test` rule from `rules_android`. This is the
same mechanism that `//kmp/src/androidUnitTest/kotlin:android_unit_tests` already uses
in this repo.

Two modes require two separate Bazel targets:

**Verify mode** (`bazel test //kmp:screenshot_tests`):
```starlark
android_local_test(
    name = "screenshot_tests",
    srcs = glob(["src/androidUnitTest/kotlin/**/*Screenshot*.kt",
                 "src/androidUnitTest/kotlin/**/*Roborazzi*.kt"]),
    test_class = "...",
    deps = [
        "@maven//:io_github_takahirom_roborazzi_roborazzi",
        "@maven//:io_github_takahirom_roborazzi_roborazzi_compose",
        "@maven//:org_robolectric_robolectric",
        # ... compose, ui-test deps
    ],
    # Pass goldens directory into sandbox
    data = glob(["src/androidUnitTest/snapshots/images/**"]),
    jvm_flags = [
        "-Droborazzi.test.verify=true",
        "-Dfile.encoding=UTF-8",
    ],
)
```

**Record mode** (`bazel run //kmp:record_screenshots`):
Record mode must write files to the source tree (not the sandbox output directory),
which is fundamentally incompatible with Bazel's hermetic sandbox. Two options:

1. **`genrule` wrapper**: `bazel run` target that executes Gradle's `recordRoborazziDebug`
   internally — same pattern as the existing `//kmp:web_app` genrule that delegates to
   Gradle. This is the lowest-friction path and preserves the existing golden workflow.

2. **`--sandbox_writable_path`**: Pass `--sandbox_writable_path=kmp/src/androidUnitTest/snapshots`
   so the test sandbox can write updated goldens. This keeps the target as `android_local_test`
   but breaks hermeticity and requires a separate `.bazelrc` config flag.

**Recommended**: use the genrule wrapper for record mode; the `android_local_test` for
verify mode. This mirrors how `//kmp:web_app` works today.

### Robolectric Native Graphics (RNG) requirement

`UiStateScreenshotTest` uses `@RunWith(RobolectricTestRunner)` with implicit Native Graphics
mode. Under `android_local_test`, the Robolectric Android-all JAR is provided via
`rules_robolectric` (already declared at 4.16.1 in MODULE.bazel). The `@GraphicsMode`
annotation and RNG binaries must be accessible at test runtime.

Known friction point: `android_local_test` currently places the Android-all JAR on the
classpath via `@rules_robolectric//bazel:android-all`, but RNG requires a native `.so`
(libandroid_runtime.so) that Robolectric downloads at test startup. In Bazel's offline
sandbox, this download will fail. The workaround is to pre-fetch the RNG binary and
declare it as a `data` dependency, or use `--sandbox_add_mount_pair` to expose the
Robolectric cache directory. This is the hardest unsolved problem in the migration.

### .bazelrc flags for screenshot tests

```
# Screenshot tests need AWT/Swing for Robolectric Native Graphics
test:screenshot --jvm_flags=-Dawt.toolkit=sun.awt.HToolkit
test:screenshot --sandbox_add_mount_pair=/tmp/.X11-unix
# Allow Robolectric to download RNG binaries on first run (breaks hermeticity)
test:screenshot --sandbox_default_allow_network=true  # temporary until RNG is hermetically bundled
```

---

## Target 3: Android Emulator Smoke Tests

### Current Gradle setup

- GHA action: `reactivecircus/android-emulator-runner@v2`, API 29, x86_64, swiftshader
- Test command: `./gradlew :androidApp:connectedDebugAndroidTest`
- Tests: `kmp/src/androidInstrumentedTest/kotlin/dev/stapler/stelekit/AppSmokeTest.kt`
  Uses `createComposeRule()`, `@RunWith(AndroidJUnit4::class)`, `InstrumentationRegistry`

### `android_instrumentation_test` in rules_android 0.7.1

The `android_instrumentation_test` rule from rules_android:
- Requires an `android_device` target (specifies the emulator AVD spec)
- Requires `target_device` attribute pointing to the `android_device`
- Bazel manages emulator lifecycle: boots, runs tests, shuts down
- Hardware acceleration via KVM is required on Linux
- Requires a display (Xvfb) for headless CI

**Key limitation**: `android_instrumentation_test` with Bazel's built-in
`android_device` targets uses QEMU-based emulators managed by Bazel itself via the
`android_test_runner`. This is independent of `reactivecircus/android-emulator-runner`
(which manages a system-level emulator outside Bazel). The two approaches are
**mutually exclusive** — you use one or the other.

### Bazel-managed vs. GHA-runner-managed: tradeoff matrix

| Concern | Bazel `android_instrumentation_test` | GHA `emulator-runner` + Bazel shell |
|---|---|---|
| Hermeticity | High (Bazel-sandboxed emulator per test) | Low (system emulator, shared state) |
| Cache benefits | Test results cacheable | Not cacheable |
| GHA startup overhead | High (~5 min AVD download + boot per Bazel run) | Medium (~3–4 min system boot once per job) |
| rules_android 0.7.1 stability | Moderate (android_device targets, QEMU2) | N/A |
| `--config=android` requirement | Yes | Yes (for APK build) |
| ADB outside sandbox | Requires `--strategy=TestRunner=local` | Natural (system ADB) |
| Sandbox flag for ADB socket | `--sandbox_add_mount_pair=/tmp/.X11-unix` (display) + ADB socket | Not needed |

### Recommended approach: hybrid (short-term)

Given the complexity of getting Bazel's `android_instrumentation_test` working reliably
with rules_android 0.7.1 on GHA runners, the lowest-risk path for Phase 1 is:

1. Keep `reactivecircus/android-emulator-runner` as the GHA-side emulator launcher
2. Wrap the Bazel-built test APK dispatch via a `bazel run` target that calls `adb shell am instrument` against the already-running emulator
3. Use `--strategy=TestRunner=local` and `--test_arg=--device_broker_type=LOCAL_ADB_SERVER` so Bazel connects to the GHA-started emulator rather than booting its own

This allows the CI workflow to:
- Build the test APK hermetically via Bazel
- Delegate emulator lifecycle to the GHA action (no Bazel-owned QEMU)
- Gain Bazel caching for the APK build step

Full `android_instrumentation_test` with a Bazel-owned `android_device` can be
pursued in a later phase once rules_android 0.7.1's emulator stability improves.

### Required .bazelrc flags for emulator tests

```
# Use local ADB broker (emulator started externally by GHA action)
test:android-smoke --test_strategy=exclusive
test:android-smoke --test_arg=--device_broker_type=LOCAL_ADB_SERVER
# Do not sandbox — emulator lives outside Bazel's process tree
test:android-smoke --strategy=TestRunner=local
# Grant access to ADB socket (typically /tmp/ADB_SOCKET or /dev/socket/adb_tmp)
test:android-smoke --sandbox_add_mount_pair=/run/user/1000
```

### GHA minutes impact: emulator startup as Bazel side-effect vs. job step

Current GHA flow: `android-smoke` job `needs: [android]`, adds ~8–12 min total
(emulator boot ~3–4 min + test run ~2–3 min + upload).

Under the hybrid approach, emulator startup remains a job-level step (same as today),
so GHA minutes impact is **unchanged** compared to the current Gradle approach. The
benefit is APK build caching — if the APK is already cached from the `bazel-android`
job, the smoke test job only pays emulator startup + test run time.

Full `android_instrumentation_test` with Bazel-owned emulator would likely **increase**
GHA minutes because Bazel boots a fresh emulator per test invocation (not cached between
CI runs unless the emulator snapshot is in the remote cache).

---

## Open Questions: Findings

1. **Is there a `rules_detekt` or `rules_kotlin` detekt integration?**
   Yes: `buildfoundation/rules_detekt` at BCR `0.8.1.13`. rules_kotlin has no built-in
   detekt integration. Use `rules_detekt`.

2. **Can Roborazzi's annotation processor be wired via `kt_android_library` without forking?**
   Roborazzi does not use kapt annotation processors — it uses a Gradle plugin for task
   registration (record/verify modes). The `captureRoboImage()` API is pure Kotlin.
   The library itself can be passed as a `deps` entry to `android_local_test`. The
   unsolved problem is Robolectric Native Graphics binary fetching in an offline sandbox.

3. **What does `android_instrumentation_test` need beyond ADB?**
   KVM (hardware acceleration), an `android_device` target, Xvfb (headless), and the
   32-bit libs on Ubuntu (`libc6:i386`, `libstdc++6:i386`, `lib32z1`). The emulator is
   managed by Bazel; the GHA runner must not have a conflicting system emulator running.

4. **Can `--strategy=TestRunner=local` run emulator tests outside sandbox while preserving hermetic builds?**
   Yes — `--strategy=TestRunner=local` disables sandboxing only for the test runner
   action, not for compilation. Build hermeticity is preserved; only the test execution
   step runs unsandboxed (necessary for ADB socket access).

5. **GHA minutes impact of emulator startup as Bazel side-effect vs. job step?**
   Bazel-owned emulator: likely increases minutes (fresh boot per test run, no cross-run
   caching). GHA action-owned emulator (hybrid): unchanged from today, with APK build
   caching as the net gain.

---

## Summary of Integration Complexity

| Target | Bazel rule | BCR available | Custom work required | Risk |
|---|---|---|---|---|
| `//kmp:detekt` | `rules_detekt 0.8.1.13` | Yes | Build `buildSrc` as `kt_jvm_library`; add nlopez plugin to maven.install | Low–Medium |
| `//kmp:screenshot_tests` (verify) | `android_local_test` | via rules_android | RNG binary hermeticity; golden data deps | Medium–High |
| `//kmp:record_screenshots` (record) | `genrule` wrapping Gradle | N/A | Same pattern as `//kmp:web_app` | Low |
| `//kmp:smoke_tests` (hybrid) | shell + `android_local_test` | via rules_android | GHA workflow restructure; `--strategy=TestRunner=local` | Medium |
| `//kmp:smoke_tests` (full Bazel) | `android_instrumentation_test` | via rules_android | `android_device` target; QEMU on GHA; 32-bit libs | High |
