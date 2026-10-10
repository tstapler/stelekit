# SteleKit Bazel Migration — Implementation Plan

_Authored: 2026-05-17_

---

## Scope

### In Scope

- **JVM/Desktop target**: `commonMain` (JVM flavor) + `jvmCommonMain` + `jvmMain` compiled via `kt_jvm_library` / `kt_jvm_binary`
- **Android target**: `commonMain` (Android flavor) + `jvmCommonMain` + `androidMain` compiled via `kt_android_library` / `android_binary`
- **JVM tests**: `businessTest` + `jvmTest` via `kt_jvm_test`
- **Android unit tests**: `androidUnitTest` via `android_local_test` + Robolectric
- **SQLDelight codegen**: checked-in generated sources strategy (re-generated from Gradle when `.sq` files change)
- **Compose compiler plugin**: wired via `kt_compiler_plugin` for JVM and Android targets
- **Detekt**: run as a `genrule` or standalone CI step (no Bazel caching benefit; outside the action graph)
- **Remote caching**: BuildBuddy free tier on GitHub Actions CI
- **MODULE.bazel (Bzlmod)**: mandatory; no WORKSPACE

### Out of Scope (future epics — see section below)

- iOS / Kotlin Native targets (`iosMain`, `iosX64`, `iosArm64`, `iosSimulatorArm64`)
- WASM/JS target (`wasmJsMain`) and all npm/webpack concerns
- Roborazzi screenshot tests (kept in Gradle)
- `kotlinx-benchmark` integration (kept in Gradle)
- Full Gradle removal (Gradle stays as a code-generator for SQLDelight and for iOS/WASM/screenshot until those phases are complete)

---

## Dependency Notes (from build.gradle.kts analysis)

Key versions to pin in Bazel:

| Artifact | Version |
|---|---|
| Kotlin | 2.3.21 |
| Compose Multiplatform | 1.10.3 |
| SQLDelight | 2.3.2 |
| Arrow | 2.2.1.1 |
| Coroutines | 1.10.2 |
| AGP (Android Gradle Plugin) | 8.13.2 |
| Android compileSdk | 36, minSdk 26 |
| JVM target | 21 |

The project has **no `gradle/libs.versions.toml`** — all dependency versions are declared inline in `kmp/build.gradle.kts` and `settings.gradle.kts`. The `maven.from_toml()` shortcut is therefore not applicable; artifacts must be declared inline in `MODULE.bazel`.

---

## Epic 1: Bazel Scaffolding

_Goal: A working, empty Bazel workspace that resolves Maven dependencies and has toolchains registered. No source compilation yet._

### Story 1.1: Bootstrap MODULE.bazel and .bazelrc

#### Task 1.1.1: Create `MODULE.bazel` at repo root

**File**: `MODULE.bazel`

Content must declare:
- `module(name = "stelekit", version = "0.0.0")`
- `bazel_dep(name = "rules_kotlin", version = "2.3.20")`
- `bazel_dep(name = "rules_android", version = "0.6.6")`
- `bazel_dep(name = "rules_jvm_external", version = "6.6")`
- `bazel_dep(name = "bazel_skylib", version = "1.7.1")`
- `bazel_dep(name = "rules_java", version = "8.3.2")`
- `bazel_dep(name = "robolectric", version = "4.14.1")`
- `maven` extension with all artifacts from `commonMain`, `jvmMain`, `androidMain`, `jvmTest`, `androidUnitTest` declared explicitly with `-jvm` / `-android` classifiers where KMP variants exist (e.g., `app.cash.sqldelight:runtime-jvm:2.3.2`, `app.cash.sqldelight:android-driver:2.3.2`)
- `android_sdk_repository_extension` with `api_level = 36, build_tools_version = "35.0.0"`
- `use_repo(maven, "maven")`

**Acceptance criterion**: `bazel mod graph` runs without error; `bazel fetch //...` resolves all Maven artifacts with no network failures.

**Complexity**: M

#### Task 1.1.2: Create `.bazelrc`

**File**: `.bazelrc`

Required flags:
```
build --java_runtime_version=21
build --tool_java_runtime_version=21
build --android_sdk=@androidsdk//:sdk
build --incompatible_enable_android_toolchain_resolution

# CI config
build:ci --remote_cache=grpcs://remote.buildbuddy.io
build:ci --remote_header=x-buildbuddy-api-key=$BUILDBUDDY_API_KEY
build:ci --noremote_upload_local_results  # only seed cache from main branch pushes
build:ci --build_event_log_file=build_events.json

# Correctness
build --sandbox_default_allow_network=false
build --incompatible_strict_action_env
```

**Acceptance criterion**: `bazel info` runs cleanly on a developer machine; `.bazelrc` is recognized by `bazel build //...`.

**Complexity**: S

#### Task 1.1.3: Create root `BUILD.bazel` with Kotlin toolchain

**File**: `BUILD.bazel`

Define the Kotlin toolchain:
```python
load("@rules_kotlin//kotlin:core.bzl", "define_kt_toolchain")

define_kt_toolchain(
    name = "kotlin_toolchain",
    api_version = "2.1",
    jvm_target = "21",
    language_version = "2.1",
)
```

Also declare the Compose compiler plugin here (shared by all targets):
```python
load("@rules_kotlin//kotlin:core.bzl", "kt_compiler_plugin")

kt_compiler_plugin(
    name = "compose_compiler_plugin",
    id = "androidx.compose.compiler.plugins.kotlin",
    target_embedded_compiler = True,
    visibility = ["//visibility:public"],
    options = {
        "suppressKotlinVersionCompatibilityCheck": "2.3.21",
        "sourceInformation": "true",
    },
    deps = ["@maven//:org_jetbrains_kotlin_kotlin_compose_compiler_plugin_embeddable"],
)
```

**Acceptance criterion**: `bazel build //:kotlin_toolchain` succeeds; toolchain is discoverable by `bazel query`.

**Complexity**: S

#### Task 1.1.4: Pin Maven lockfile

**File**: `maven_install.json` (auto-generated)

Run `bazel run @maven//:pin` after artifact list is complete to generate the lockfile. Commit it.

**Acceptance criterion**: `maven_install.json` exists and is referenced by `MODULE.bazel` (`lock_file = "//:maven_install.json"`). A fresh `bazel build //...` after deleting `~/.cache/bazel` does not perform any Maven resolution (all artifacts served from lockfile + local cache).

**Complexity**: S

### Story 1.2: Resolve KMP Maven variant disambiguation

#### Task 1.2.1: Audit all KMP dependencies for JVM/Android classifier requirements

All KMP libraries in `commonMain` that publish platform variants need explicit classifiers. Known cases:
- `app.cash.sqldelight:runtime` → `runtime-jvm` (JVM), `android-driver` (Android)
- `io.arrow-kt:arrow-core` → check if `-jvm` suffix is needed
- `org.jetbrains.kotlinx:kotlinx-coroutines-core` → usually JVM artifact available without classifier
- `io.ktor:ktor-client-core` → check KMP variant; use `ktor-client-core-jvm` if needed
- `io.coil-kt.coil3:coil-compose` → KMP library; check variant requirements
- `io.github.petertrr:kotlin-multiplatform-diff` → likely needs `-jvm` classifier
- `com.fleeksoft.ksoup:ksoup` → check classifier

Create a tracking table in a comment at the top of the `maven.install` block in `MODULE.bazel` documenting which classifier was chosen and why for each KMP artifact.

**Acceptance criterion**: `bazel build @maven//:pin` completes; no "artifact not found" errors for any dependency. `rules_jvm_external` lock file resolves all artifacts to concrete JARs.

**Complexity**: M

---

## Epic 2: JVM/Desktop Target

_Goal: `bazel build //kmp:desktop_app` produces a runnable JVM binary; `bazel run //kmp:desktop_app` launches the desktop UI._

### Story 2.1: Generate and check in SQLDelight sources

#### Task 2.1.1: Run Gradle code generator and commit generated sources

**Files**: Create directory `kmp/src/generated/sqldelight/` and populate from Gradle output.

Steps:
1. Run `./gradlew :kmp:generateCommonMainSteleDatabase` (or the equivalent task that outputs to `kmp/build/generated/sqldelight/`)
2. Copy generated Kotlin files from `kmp/build/generated/sqldelight/commonMain/kotlin/` to `kmp/src/generated/sqldelight/`
3. Add a `.gitkeep` and update `.gitignore` to ensure the directory is tracked
4. Document the regeneration command in a `kmp/src/generated/sqldelight/README_REGEN.md` (one-liner: when to re-run, what command)

**Acceptance criterion**: `kmp/src/generated/sqldelight/dev/stapler/stelekit/db/SteleDatabase.kt` (and peers) exist in the repo as committed source files. `git diff --stat HEAD` shows them tracked.

**Complexity**: S

#### Task 2.1.2: Create `kmp/src/generated/sqldelight/BUILD.bazel`

**File**: `kmp/src/generated/sqldelight/BUILD.bazel`

```python
load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_library")

kt_jvm_library(
    name = "sqldelight_generated_jvm",
    srcs = glob(["**/*.kt"]),
    deps = [
        "@maven//:app_cash_sqldelight_runtime_jvm",
        "@maven//:app_cash_sqldelight_coroutines_extensions_jvm",
        "@maven//:app_cash_sqldelight_async_extensions_jvm",
        "@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_core_jvm",
    ],
    visibility = ["//kmp:__subpackages__"],
)

load("@rules_kotlin//kotlin:android.bzl", "kt_android_library")

kt_android_library(
    name = "sqldelight_generated_android",
    srcs = glob(["**/*.kt"]),
    custom_package = "dev.stapler.stelekit.db",
    deps = [
        "@maven//:app_cash_sqldelight_runtime_jvm",
        "@maven//:app_cash_sqldelight_coroutines_extensions_jvm",
        "@maven//:app_cash_sqldelight_async_extensions_jvm",
        "@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_core_jvm",
    ],
    visibility = ["//kmp:__subpackages__"],
)
```

**Acceptance criterion**: `bazel build //kmp/src/generated/sqldelight:sqldelight_generated_jvm` succeeds.

**Complexity**: S

### Story 2.2: commonMain JVM library target

#### Task 2.2.1: Create `kmp/src/commonMain/kotlin/BUILD.bazel` (JVM flavor)

**File**: `kmp/src/commonMain/kotlin/BUILD.bazel`

Define `kt_jvm_library(name = "common_jvm", ...)` with:
- `srcs = glob(["**/*.kt"])`
- `plugins = ["//:compose_compiler_plugin"]`
- `deps` covering all `commonMain` Maven artifacts (Arrow, coroutines, datetime, serialization, markdown, SQLDelight runtime, Compose runtime JARs, lifecycle, Coil, Ktor core, Ksoup, multiplatform-diff)
- `deps` also including `//kmp/src/generated/sqldelight:sqldelight_generated_jvm`
- `visibility = ["//kmp:__subpackages__"]`

Note: `compose.runtime`, `compose.foundation`, `compose.material3`, `compose.materialIconsExtended`, `compose.components.resources` must map to concrete Maven coordinates for Compose Multiplatform 1.10.3 JVM artifacts. These must be declared in `maven.install`.

**Acceptance criterion**: `bazel build //kmp/src/commonMain/kotlin:common_jvm` succeeds (zero compilation errors).

**Complexity**: L

#### Task 2.2.2: Create `kmp/src/jvmCommonMain/kotlin/BUILD.bazel`

**File**: `kmp/src/jvmCommonMain/kotlin/BUILD.bazel`

Define `kt_jvm_library(name = "jvm_common_main", ...)` with:
- `deps = ["//kmp/src/commonMain/kotlin:common_jvm", "@maven//:io_opentelemetry_opentelemetry_api"]`

**Acceptance criterion**: `bazel build //kmp/src/jvmCommonMain/kotlin:jvm_common_main` succeeds.

**Complexity**: S

### Story 2.3: jvmMain binary target

#### Task 2.3.1: Create `kmp/src/jvmMain/kotlin/BUILD.bazel`

**File**: `kmp/src/jvmMain/kotlin/BUILD.bazel`

Define:
```python
kt_jvm_library(
    name = "jvm_main_lib",
    srcs = glob(["**/*.kt"]),
    plugins = ["//:compose_compiler_plugin"],
    deps = [
        "//kmp/src/jvmCommonMain/kotlin:jvm_common_main",
        "@maven//:app_cash_sqldelight_sqlite_driver",
        "@maven//:org_jetbrains_compose_desktop_currentOs",  # Compose Desktop
        "@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_swing",
        "@maven//:org_eclipse_jgit_org_eclipse_jgit",        # JGit 7.x
        "@maven//:org_eclipse_jgit_org_eclipse_jgit_ssh_apache",
        "@maven//:io_opentelemetry_opentelemetry_sdk",
        "@maven//:io_opentelemetry_opentelemetry_exporter_logging",
        "@maven//:org_bouncycastle_bcprov_jdk18on",
        "@maven//:io_ktor_ktor_client_okhttp_jvm",
    ],
    visibility = ["//kmp:__subpackages__"],
)

kt_jvm_binary(
    name = "desktop_app",
    main_class = "dev.stapler.stelekit.desktop.MainKt",
    runtime_deps = [":jvm_main_lib"],
    jvm_flags = [
        "-Djdk.attach.allowAttachSelf=true",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
    ],
    visibility = ["//visibility:public"],
)
```

Also add a `skiko` version-pinning mechanism. In `MODULE.bazel`, add `override_target` or `exclusion` rules for Skiko AWT runtime variants to force `0.9.37.4` (matching Gradle's `resolutionStrategy.force`). Document the exact Bazel equivalent approach (likely `artifact()` with explicit version in `maven.install` with `override_targets`).

**Acceptance criterion**: `bazel run //kmp/src/jvmMain/kotlin:desktop_app` launches the SteleKit desktop application. App window appears.

**Complexity**: L

#### Task 2.3.2: Add top-level aliases to `kmp/BUILD.bazel`

**File**: `kmp/BUILD.bazel`

```python
alias(name = "desktop_app", actual = "//kmp/src/jvmMain/kotlin:desktop_app", visibility = ["//visibility:public"])
```

Developer command becomes: `bazel run //kmp:desktop_app`

**Acceptance criterion**: `bazel run //kmp:desktop_app` works as an alias.

**Complexity**: S

---

## Epic 3: Android Target

_Goal: `bazel build //kmp:android_app` produces a valid debug APK; `bazel mobile-install //kmp:android_app` installs on a connected device._

### Story 3.1: commonMain Android library target

#### Task 3.1.1: Create `kt_android_library` target in `kmp/src/commonMain/kotlin/BUILD.bazel`

**File**: `kmp/src/commonMain/kotlin/BUILD.bazel` (add to existing file from Task 2.2.1)

Define `kt_android_library(name = "common_android", ...)` with:
- Same `srcs` glob as `common_jvm`
- `plugins = ["//:compose_compiler_plugin"]`
- `custom_package = "dev.stapler.stelekit"`
- `manifest = "//kmp:AndroidManifest.xml"` (or `//androidApp:AndroidManifest.xml`)
- `deps` using Android-specific Maven coordinates (Jetpack Compose, AndroidX, etc.)
- `deps` including `//kmp/src/generated/sqldelight:sqldelight_generated_android`

**Acceptance criterion**: `bazel build //kmp/src/commonMain/kotlin:common_android` succeeds.

**Complexity**: L

### Story 3.2: jvmCommonMain Android flavor

#### Task 3.2.1: Add `kt_android_library` to `kmp/src/jvmCommonMain/kotlin/BUILD.bazel`

**File**: `kmp/src/jvmCommonMain/kotlin/BUILD.bazel` (add to existing file from Task 2.2.2)

```python
kt_android_library(
    name = "jvm_common_main_android",
    srcs = glob(["**/*.kt"]),
    deps = [
        "//kmp/src/commonMain/kotlin:common_android",
        "@maven//:io_opentelemetry_opentelemetry_api",
    ],
    custom_package = "dev.stapler.stelekit",
    visibility = ["//kmp:__subpackages__"],
)
```

**Acceptance criterion**: `bazel build //kmp/src/jvmCommonMain/kotlin:jvm_common_main_android` succeeds.

**Complexity**: S

### Story 3.3: androidMain library and app binary

#### Task 3.3.1: Create `kmp/src/androidMain/kotlin/BUILD.bazel`

**File**: `kmp/src/androidMain/kotlin/BUILD.bazel`

Define `kt_android_library(name = "android_main", ...)` with:
- `srcs = glob(["**/*.kt"])`
- `plugins = ["//:compose_compiler_plugin"]`
- `deps` including `jvm_common_main_android`, all `androidMain` Maven artifacts (AndroidX Activity, AppCompat, Core KTX, coroutines-android, SQLDelight android-driver, sqlite-android, Ktor OkHttp, DocumentFile, Jetpack Compose UI/Material3, OpenTelemetry SDK, JankStats, security-crypto, mlkit-genai, Glance, WorkManager, JGit 5.13.x, jsch)
- `custom_package = "dev.stapler.stelekit"`

**Acceptance criterion**: `bazel build //kmp/src/androidMain/kotlin:android_main` succeeds.

**Complexity**: L

#### Task 3.3.2: Create `androidApp/BUILD.bazel` with `android_binary`

**File**: `androidApp/BUILD.bazel`

```python
load("@rules_android//rules:rules.bzl", "android_binary")

android_binary(
    name = "android_app",
    manifest = "src/main/AndroidManifest.xml",
    custom_package = "dev.stapler.stelekit.android",
    deps = ["//kmp/src/androidMain/kotlin:android_main"],
    multidex = "native",
    # Core library desugaring
    desugar_java8_libs = True,
    visibility = ["//visibility:public"],
)
```

Wire `coreLibraryDesugaring` via `desugar_java8_libs` or equivalent `rules_android` mechanism. Declare `com.android.tools:desugar_jdk_libs:2.1.4` in `maven.install`.

Note: `packaging { resources { excludes += "plugin.properties" } }` must be replicated as a resource strip rule or `nocompress_extensions` attribute.

**Acceptance criterion**: `bazel build //androidApp:android_app` produces an APK. `bazel mobile-install //androidApp:android_app` installs on a connected Android 8+ device.

**Complexity**: L

#### Task 3.3.3: Add top-level alias for Android app

**File**: `kmp/BUILD.bazel`

```python
alias(name = "android_app", actual = "//androidApp:android_app", visibility = ["//visibility:public"])
```

**Acceptance criterion**: `bazel build //kmp:android_app` works.

**Complexity**: S

---

## Epic 4: Test Targets

_Goal: `bazel test //...` runs all JVM and Android unit tests. All existing `./gradlew jvmTest` tests pass._

### Story 4.1: businessTest library

#### Task 4.1.1: Create `kmp/src/businessTest/kotlin/BUILD.bazel`

**File**: `kmp/src/businessTest/kotlin/BUILD.bazel`

```python
kt_jvm_library(
    name = "business_tests",
    srcs = glob(["**/*.kt"]),
    testonly = True,
    deps = [
        "//kmp/src/commonMain/kotlin:common_jvm",
        "@maven//:org_jetbrains_kotlin_kotlin_test_junit",
        "@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_test_jvm",
        "@maven//:org_jetbrains_kotlinx_kotlinx_serialization_json_jvm",
    ],
    visibility = ["//kmp:__subpackages__"],
)
```

**Acceptance criterion**: `bazel build //kmp/src/businessTest/kotlin:business_tests` succeeds.

**Complexity**: S

### Story 4.2: jvmTest target

#### Task 4.2.1: Create `kmp/src/jvmTest/kotlin/BUILD.bazel`

**File**: `kmp/src/jvmTest/kotlin/BUILD.bazel`

```python
kt_jvm_test(
    name = "jvm_tests",
    srcs = glob(["**/*.kt"], exclude = ["**/*Screenshot*", "**/*Roborazzi*"]),
    test_class = "dev.stapler.stelekit.JvmTestSuite",  # adjust to actual suite class or use junit runner
    deps = [
        "//kmp/src/jvmMain/kotlin:jvm_main_lib",
        "//kmp/src/businessTest/kotlin:business_tests",
        "@maven//:org_jetbrains_kotlin_kotlin_test_junit",
        "@maven//:org_jetbrains_compose_desktop_ui_test_junit4",
        "@maven//:io_ktor_ktor_client_mock_jvm",
        "@maven//:io_projectreactor_tools_blockhound",
    ],
    jvm_flags = [
        "-Djdk.attach.allowAttachSelf=true",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "-Djava.awt.headless=false",
    ],
    env = {
        "LIBGL_ALWAYS_SOFTWARE": "1",
    },
    visibility = ["//visibility:public"],
    tags = ["requires-display"],  # some tests need display server
)
```

Roborazzi screenshot tests (`**/*Screenshot*`, `**/*Roborazzi*`) are excluded via glob `exclude` — they remain Gradle-only.

**Acceptance criterion**: `bazel test //kmp/src/jvmTest/kotlin:jvm_tests` runs; all non-screenshot tests pass. Test count matches `./gradlew jvmTest` minus screenshot tests.

**Complexity**: M

#### Task 4.2.2: Add top-level test alias

**File**: `kmp/BUILD.bazel`

```python
alias(name = "jvm_tests", actual = "//kmp/src/jvmTest/kotlin:jvm_tests", visibility = ["//visibility:public"])
```

Developer command: `bazel test //kmp:jvm_tests`

**Acceptance criterion**: `bazel test //kmp:jvm_tests` passes.

**Complexity**: S

### Story 4.3: Android unit tests (Robolectric)

#### Task 4.3.1: Create `kmp/src/androidUnitTest/kotlin/BUILD.bazel`

**File**: `kmp/src/androidUnitTest/kotlin/BUILD.bazel`

```python
load("@rules_android//rules:rules.bzl", "android_local_test")
load("@rules_kotlin//kotlin:android.bzl", "kt_android_library")

kt_android_library(
    name = "android_unit_test_lib",
    srcs = glob(["**/*.kt"]),
    custom_package = "dev.stapler.stelekit",
    testonly = True,
    deps = [
        "//kmp/src/androidMain/kotlin:android_main",
        "@maven//:junit_junit",
        "@maven//:org_robolectric_robolectric",
        "@maven//:androidx_test_core",
        "@maven//:androidx_test_ext_junit",
        "@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_test_jvm",
        "@maven//:androidx_arch_core_core_testing",
        "@maven//:androidx_glance_glance_appwidget_testing",
    ],
)

android_local_test(
    name = "android_unit_tests",
    manifest = "//androidApp:src/main/AndroidManifest.xml",
    deps = [
        ":android_unit_test_lib",
        "@robolectric//bazel:android-all",
        "@maven//:org_robolectric_robolectric",
    ],
    visibility = ["//visibility:public"],
)
```

Note: Roborazzi Android screenshot tests (`roborazzi`, `roborazzi-compose`) are declared as deps but the screenshot test classes themselves are excluded via a glob or `tags = ["manual"]`.

**Acceptance criterion**: `bazel test //kmp/src/androidUnitTest/kotlin:android_unit_tests` passes. Test count matches `./gradlew testDebugUnitTest` minus screenshot tests.

**Complexity**: M

---

## Epic 5: CI Integration and Remote Caching

_Goal: GitHub Actions CI runs `bazel test //...` instead of `./gradlew ciCheck`. Remote cache seeded from main-branch builds. CI green._

### Story 5.1: GitHub Actions workflow

#### Task 5.1.1: Create `.github/workflows/bazel-ci.yml`

**File**: `.github/workflows/bazel-ci.yml`

Jobs:
- `bazel-jvm`: `bazel test //kmp:jvm_tests --config=ci` on `ubuntu-latest` with xvfb-run for display-requiring tests
- `bazel-android`: `bazel build //kmp:android_app --config=ci` on `ubuntu-latest`
- `bazel-android-tests`: `bazel test //kmp/src/androidUnitTest/kotlin:android_unit_tests --config=ci` on `ubuntu-latest`

Each job:
1. Installs Bazel via `bazel-contrib/setup-bazel` action
2. Sets `BUILDBUDDY_API_KEY` from GitHub Secrets
3. Caches `~/.cache/bazel` using `actions/cache` keyed on `hashFiles('MODULE.bazel', 'maven_install.json')`

Maintain existing Gradle jobs (`gradle-jvm`, `gradle-android`, `detekt`) in parallel during transition. They run the screenshot tests and Roborazzi checks that remain Gradle-only.

**Acceptance criterion**: Bazel CI jobs pass on a PR. Build event stream visible in BuildBuddy dashboard.

**Complexity**: M

#### Task 5.1.2: Register BuildBuddy and configure remote cache seeding

**File**: `.bazelrc` (update from Task 1.1.2)

Add:
```
# Only upload cache results from main branch (not PRs, to avoid cache poisoning)
build:ci-main --remote_upload_local_results
build:ci-pr  --noremote_upload_local_results
```

In `bazel-ci.yml`:
- Main branch pushes use `--config=ci --config=ci-main`
- PR builds use `--config=ci --config=ci-pr`

**Acceptance criterion**: After a main-branch push, a subsequent PR build shows >50% remote cache hit rate in BuildBuddy dashboard for the incremental build scenario.

**Complexity**: S

#### Task 5.1.3: Add `bazel-diff` for affected-target detection (optional / stretch)

**File**: `.github/workflows/bazel-ci.yml` (update)

Add a pre-step that runs `bazel-diff` to compute affected targets between PR HEAD and merge base. Pass the affected set to `bazel test` instead of `//...`.

**Acceptance criterion**: PRs that touch only `androidMain` sources skip `//kmp:jvm_tests` entirely. CI wall-clock time drops measurably.

**Complexity**: M

### Story 5.2: Developer documentation

#### Task 5.2.1: Update `CLAUDE.md` with Bazel command equivalents

**File**: `CLAUDE.md`

Add a "Bazel Build Commands" section alongside the existing Gradle section:

| Gradle | Bazel |
|--------|-------|
| `./gradlew run` | `bazel run //kmp:desktop_app` |
| `./gradlew jvmTest` | `bazel test //kmp:jvm_tests` |
| `./gradlew allTests` | `bazel test //...` |
| `./gradlew ciCheck` | `bazel test //... --config=ci` |
| `./gradlew installAndroid` | `bazel mobile-install //kmp:android_app` |
| `./gradlew packageDistributionForCurrentOS` | (Gradle only — see Epic 6) |

**Acceptance criterion**: Docs reflect accurate commands. A new developer can run the app using only Bazel after following the README.

**Complexity**: S

---

## Epic 6: Detekt and Linting Integration

_Goal: Detekt runs in CI without Gradle. `bazel test //...:detekt` enforces style._

### Story 6.1: Detekt as a Bazel genrule

#### Task 6.1.1: Add Detekt as a `sh_binary` / `genrule` Bazel target

**File**: `kmp/BUILD.bazel` (add to existing)

Detekt has no official Bazel rule. Two options:

**Option A (simpler)**: Keep Detekt as a standalone CI step that invokes the Detekt CLI JAR directly via `java -jar` — not a Bazel target. Add it as a separate GitHub Actions job step that runs after `bazel build`.

**Option B (Bazel-native)**: Declare a `genrule` that downloads the Detekt CLI JAR (via `rules_jvm_external`) and runs it against source directories. Mark it `tags = ["manual"]` so it doesn't run in `//...`.

Recommendation: Option A for initial migration (simpler, no caching loss for a fast linter step). Option B if Detekt becomes a bottleneck.

**Acceptance criterion**: Detekt runs in CI and blocks merges on linting failures. The custom buildSrc ruleset (`buildSrc.jar`) is still invoked.

**Complexity**: M

---

## Epic 7: Gradle Removal

_Goal: No `gradlew`, `build.gradle.kts`, or `settings.gradle.kts` remain in the repository. All functionality is handled by Bazel or has been intentionally deferred._

**Prerequisite**: Epics 1–6 are complete and CI is fully green on Bazel. iOS and WASM targets have been either migrated or formally deferred to a separate track.

### Story 7.1: Audit remaining Gradle-only functionality

#### Task 7.1.1: Inventory all Gradle tasks not yet in Bazel

**Files**: `kmp/build.gradle.kts`, `settings.gradle.kts`, `androidApp/build.gradle.kts`, `macrobenchmark/build.gradle.kts`

Produce a checklist of:
- `packageDistributionForCurrentOS` (Compose Desktop packaging) → needs `rules_pkg` or stays external
- `jvmTestProfile` (JFR profiling task) → express as a `sh_binary` wrapper around `bazel run`
- `graphStats` (library stats test) → migrate to Bazel test target with `tags = ["manual"]`
- `jvmBenchmark` (kotlinx-benchmark) → no Bazel equivalent; document as manually run via Gradle
- Screenshot test tasks → remain Gradle for now; document in CLAUDE.md

**Acceptance criterion**: Checklist exists with disposition (Bazel, manual script, or deferred) for each Gradle task.

**Complexity**: S

#### Task 7.1.2: Migrate `packageDistributionForCurrentOS` to `rules_pkg`

**Files**: `kmp/BUILD.bazel` (add `pkg_zip` or `pkg_deb` targets)

Use `rules_pkg` to package the JVM binary into platform-specific distributions. This partially replaces the Compose Desktop packaging but does not produce `.dmg`/`.msi` installers — those require platform-specific tooling.

Alternatively: create a `sh_binary` wrapper that invokes the Bazel-built `desktop_app` JAR with `jpackage` for native installers.

**Acceptance criterion**: `bazel build //kmp:desktop_dist` produces a `.deb` on Linux and a `.tar.gz` on macOS with the app binary and JVM bundled.

**Complexity**: L

#### Task 7.1.3: Remove Gradle files

**Files to delete**:
- `gradlew`, `gradlew.bat`, `gradle/` directory (wrapper)
- `settings.gradle.kts`
- `kmp/build.gradle.kts`
- `androidApp/build.gradle.kts`
- `macrobenchmark/build.gradle.kts`
- `tools/flamegraph/build.gradle.kts`
- `buildSrc/` (custom Detekt rules — migrate to a `kt_jvm_library` Bazel target or standalone JAR)
- `gradle.properties`

**Acceptance criterion**: `find . -name "*.gradle.kts"` returns zero results. `find . -name "gradlew"` returns zero results. `bazel build //...` still passes.

**Complexity**: M

---

## Out of Scope (Future Epics)

### Future Epic A: iOS / Kotlin Native Migration

Blocked on `rules_kotlin` adding Kotlin/Native support (tracked in rules_kotlin issue #567). No ETA as of May 2026. Requires:
- Custom Kotlin/Native toolchain declaration (pre-fetched `~/.konan/` compiler distribution as `http_archive`)
- `rules_apple` / `rules_ios` for `.framework` / `.xcframework` bundling
- macOS-only CI runner for final binary production
- SQLDelight native driver (`app.cash.sqldelight:native-driver`) Bazel compilation

### Future Epic B: WASM/JS Migration

Blocked on `rules_kotlin` Kotlin/WASM support (none exists as of May 2026). Requires:
- WASM toolchain integration
- `npm` dependency resolution (currently via yarn/webpack in Gradle)
- sqlite-wasm OPFS runtime packaging

### Future Epic C: Roborazzi Screenshot Tests in Bazel

Blocked on Roborazzi Bazel support. Requires manual wiring of Compose UI test infrastructure for `android_local_test`. No community examples exist.

### Future Epic D: kotlinx-benchmark in Bazel

JMH / kotlinx-benchmark has no Bazel rule. Would require a custom `genrule` that invokes the benchmark runner JAR. Low priority — benchmarks run infrequently.

---

## ADR Candidates

The following decisions should be documented as Architecture Decision Records before or during implementation:

| # | Decision | Why It Needs an ADR |
|---|----------|---------------------|
| ADR-001 | SQLDelight: checked-in generated sources vs custom `sqldelight_codegen` rule | Checked-in sources are pragmatic but create a schema/code drift risk; the trade-off should be explicit and the regeneration process formally documented |
| ADR-002 | KMP scope: JVM + Android only in Bazel (iOS and WASM remain Gradle) | Deviates from the original G1 requirement ("all KMP targets"); stakeholder alignment required |
| ADR-003 | Remote caching: BuildBuddy free tier vs bazel-github-actions-cache | BuildBuddy requires an external account and API key; `bazel-github-actions-cache` uses GHA-native storage but hits a 10 GB cap. Cost/risk trade-off. |
| ADR-004 | Compose compiler plugin K2 wiring: `kt_compiler_plugin` + `suppressKotlinVersionCompatibilityCheck` | Active open bug (rules_kotlin #1388) means this workaround may break on Compose BOM upgrades. Document the known risk and the version pins required. |
| ADR-005 | `commonMain` compiled twice (JVM + Android flavors) vs single-flavor approach | Duplicating compile work has a real cost. The decision to use two separate targets vs. flattening should be explicit. |
| ADR-006 | Gradle removal timing: parallel vs immediate | Keeping Gradle for iOS/WASM/screenshots means two build systems co-exist indefinitely. Define the condition under which Gradle is removed. |

---

## Summary

| Metric | Count |
|---|---|
| Epics | 7 (5 in-scope + 2 support) |
| Stories | 14 |
| Tasks | 27 |
| ADRs flagged | 6 |

### Epic Summary

| Epic | Goal | Complexity | Blocking? |
|---|---|---|---|
| 1: Bazel Scaffolding | MODULE.bazel, toolchains, Maven resolution | M | All others |
| 2: JVM/Desktop Target | `bazel run //kmp:desktop_app` working | L | Epic 4 (jvmTest) |
| 3: Android Target | `bazel build //kmp:android_app` working | L | Epic 4 (android tests) |
| 4: Test Targets | All non-screenshot tests pass under Bazel | M | Epic 5 (CI) |
| 5: CI Integration | GitHub Actions green on Bazel | M | Epic 7 |
| 6: Detekt / Linting | Linting in CI without Gradle | S | Epic 7 |
| 7: Gradle Removal | No `gradlew` in repo | L | Epics 1–6 complete |

### Recommended sequencing

1. Epic 1 (scaffolding) — unblocks everything
2. Epic 2 story 2.1 (SQLDelight gen) — low risk, needed by both JVM and Android
3. Epic 2 stories 2.2–2.3 and Epic 3 in parallel — JVM and Android can be developed concurrently
4. Epic 4 (tests) — depends on Epics 2 and 3
5. Epic 6 (Detekt) — can proceed in parallel with Epics 2–4
6. Epic 5 (CI) — after Epics 2–4 pass locally
7. Epic 7 (Gradle removal) — last step, after CI is confirmed green
