# SteleKit Bazel Migration — Architecture Research

## Executive Summary

Three critical findings:
1. **No production-ready KMP Bazel ruleset exists.** `rules_kotlin` covers JVM + Android only; full KMP (iOS, WASM/JS, commonMain) has no official Bazel support. The practical migration path is JVM-first: migrate the JVM and Android targets to Bazel, treat iOS/WASM as a later phase or keep them in Gradle during transition.
2. **Use `MODULE.bazel` (Bzlmod), not `WORKSPACE`.** `WORKSPACE` is disabled by default in Bazel 8 (released Dec 2024) and will be removed in Bazel 9. All new projects must use `MODULE.bazel`. The Bazel Central Registry now hosts 650+ modules including `rules_kotlin`, `rules_jvm_external`, and `rules_android`.
3. **`commonMain` must be compiled twice — once as `kt_jvm_library`, once as `kt_android_library`.** There is no KMP-aware rule that handles expect/actual dispatch. The shared source set is expressed as a single Bazel target per platform-flavor, with each platform target adding it to `deps`. This is the standard pattern for sharing Kotlin code across JVM and Android in Bazel.

---

## 1. BUILD File Structure for a KMP Project

### The Core Problem: No KMP Rule

`rules_kotlin` (bazelbuild/rules_kotlin) supports three flavors:
- `kt_jvm_*` — JVM compilation
- `kt_android_*` — Android compilation via AGP integration
- `kt_js_*` — Kotlin/JS (limited, experimental)

There is **no `kt_kmp_*` or `kt_multiplatform_*` rule**. The GitHub issue tracking KMP support ([#567 Kotlin Bazel Cross-Platform Roadmap](https://github.com/bazelbuild/rules_kotlin/issues/567)) has been open since 2021 and remains "investigating." The August 2025 KMP roadmap from JetBrains mentions a "Kotlin Build Tools API" to ease integration with Bazel eventually — but this is future work, not available today.

**Practical implication**: KMP's expect/actual mechanism is a compiler feature tied to the Kotlin multiplatform compiler plugin. Bazel cannot invoke it without a rule that understands source sets and target architectures. For iOS (Kotlin/Native) and WASM/JS, no stable Bazel rules exist.

### Recommended BUILD Layout

Given the constraint above, the migration should proceed in layers:

```
//
├── MODULE.bazel
├── .bazelrc
├── kmp/
│   ├── BUILD.bazel              # Top-level aliases and visibility
│   ├── src/
│   │   ├── commonMain/
│   │   │   └── BUILD.bazel      # kt_jvm_library (jvm variant) + kt_android_library (android variant)
│   │   ├── jvmCommonMain/
│   │   │   └── BUILD.bazel      # kt_jvm_library + kt_android_library (shared JVM/Android extras)
│   │   ├── jvmMain/
│   │   │   └── BUILD.bazel      # kt_jvm_library :jvm_main, kt_jvm_binary :desktop_app
│   │   ├── androidMain/
│   │   │   └── BUILD.bazel      # kt_android_library :android_main
│   │   └── sqldelight/
│   │       └── BUILD.bazel      # sqldelight_codegen rule or pregenerated sources
│   └── src/jvmTest/
│       └── BUILD.bazel          # kt_jvm_test targets
└── androidApp/
    └── BUILD.bazel              # android_binary :android_app
```

**One BUILD file per source set** is the recommended approach because:
- Each source set has a distinct dependency graph
- Bazel's incremental model rewards smaller, well-scoped targets
- Test targets stay co-located with the sources they test

### commonMain Handling

`commonMain` sources contain platform-agnostic Kotlin that must be compiled for each target platform. Since rules_kotlin has no multiplatform rule, the pattern is:

```python
# kmp/src/commonMain/BUILD.bazel

load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_library")
load("@rules_kotlin//kotlin:android.bzl", "kt_android_library")

# JVM flavor — used by jvmMain and jvmTest
kt_jvm_library(
    name = "common_jvm",
    srcs = glob(["kotlin/**/*.kt"]),
    deps = [
        # Arrow, kotlinx-coroutines, SQLDelight runtime, Compose runtime jars
        "@maven//:io_arrow_kt_arrow_core_jvm",
        "@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_core_jvm",
        # ...
    ],
    visibility = ["//kmp:__subpackages__"],
)

# Android flavor — used by androidMain and android tests
kt_android_library(
    name = "common_android",
    srcs = glob(["kotlin/**/*.kt"]),
    custom_package = "dev.stapler.stelekit",
    manifest = "//kmp:AndroidManifest.xml",
    deps = [
        "@maven//:io_arrow_kt_arrow_core_jvm",
        "@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_core_jvm",
        # ...
    ],
    visibility = ["//kmp:__subpackages__"],
)
```

`jvmMain` depends on `:common_jvm`; `androidMain` depends on `:common_android`. The source files are shared (same `glob`), compiled twice with different Bazel rules. This duplicates compile work but is the only supported approach without a KMP rule.

**Alternative**: Check in SQLDelight-generated sources and treat `commonMain` as a single `kt_jvm_library` for both targets by using `kt_android_library` rules that inherit from it. Some teams flatten commonMain + jvmMain into a single target per platform to reduce complexity.

---

## 2. WORKSPACE vs MODULE.bazel (Bzlmod)

**Use `MODULE.bazel` for all new projects without exception.**

| Aspect | WORKSPACE | MODULE.bazel (Bzlmod) |
|--------|-----------|----------------------|
| Status in Bazel 8 | Disabled by default | Default and recommended |
| Status in Bazel 9 | Cannot be enabled | Only supported mode |
| Transitive deps | Manual, conflict-prone | Automatically resolved |
| Bazel Central Registry | Not used | 650+ modules available |
| rules_kotlin | Available | Available (v2.x on BCR) |
| rules_jvm_external | Available | Available (v6.6+ supports `maven.from_toml`) |

### MODULE.bazel Skeleton for SteleKit

```python
module(
    name = "stelekit",
    version = "0.0.0",
)

bazel_dep(name = "rules_kotlin", version = "2.1.3")
bazel_dep(name = "rules_android", version = "0.6.0")
bazel_dep(name = "rules_jvm_external", version = "6.6")
bazel_dep(name = "bazel_skylib", version = "1.7.1")

# Maven dependency resolution
maven = use_extension("@rules_jvm_external//:extensions.bzl", "maven")

# Option A: import from the existing libs.versions.toml (see section 6)
maven.from_toml(
    libs_versions_toml = "//gradle:libs.versions.toml",
)

# Option B: declare artifacts inline
maven.install(
    artifacts = [
        "io.arrow-kt:arrow-core:2.2.1.1",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2",
        # ...
    ],
    repositories = [
        "https://repo1.maven.org/maven2",
        "https://maven.google.com",
        "https://jitpack.io",
    ],
)

use_repo(maven, "maven")

# Android SDK
android_sdk_repository_extension = use_extension(
    "@rules_android//rules/android_sdk_repository:rule.bzl",
    "android_sdk_repository_extension",
)
use_repo(android_sdk_repository_extension, "androidsdk")

# Kotlin toolchain (registered in root BUILD.bazel)
register_toolchains("//:kotlin_toolchain")
```

---

## 3. rules_kotlin Rule Mapping to KMP Source Sets

| Gradle source set | Bazel rule | Notes |
|-------------------|-----------|-------|
| `commonMain` | `kt_jvm_library` + `kt_android_library` | Compiled twice, one per platform; no KMP rule |
| `jvmCommonMain` | `kt_jvm_library` | Depends on `:common_jvm`; JVM+Android shared extras |
| `jvmMain` | `kt_jvm_library` | Desktop app logic; depends on `:jvm_common` |
| `androidMain` | `kt_android_library` | Depends on `:common_android` + `:jvm_common_android` |
| `iosMain` | **Not supported** | Requires Kotlin/Native toolchain; no stable rules |
| `wasmJsMain` | **Not supported** | Requires WASM target toolchain; no stable rules |
| `jvmTest` | `kt_jvm_test` | Standard JUnit4 runner |
| `androidUnitTest` | `android_local_test` | Requires Robolectric setup |
| `businessTest` | `kt_jvm_test` | Pure JVM tests, depends on `:common_jvm` |

### Compose Multiplatform Complication

The Compose compiler plugin is distributed as a Kotlin compiler plugin and requires specific wiring in `kotlinc_opts`. As of 2025, there are no dedicated Bazel rules for Compose Multiplatform. The Compose desktop JAR can be added as a Maven artifact and passed to `kt_jvm_library`, but the compiler plugin must be explicitly threaded through `kt_kotlinc_options`:

```python
kt_kotlinc_options(
    name = "kotlinc_opts_compose",
    x_optin = ["androidx.compose.runtime.ExperimentalComposeApi"],
    plugin_options = [
        # compose compiler plugin options
    ],
)
```

This is an open engineering problem — the Compose compiler plugin has historically required Gradle's compose plugin to configure correctly. Teams building Compose apps with Bazel typically wrap the compiler plugin jar manually.

---

## 4. Toolchain Declaration Patterns

### Root BUILD.bazel — Kotlin Toolchain

```python
# //:BUILD.bazel
load("@rules_kotlin//kotlin:core.bzl", "define_kt_toolchain")

define_kt_toolchain(
    name = "kotlin_toolchain",
    api_version = "2.1",         # matches Kotlin 2.1.x in settings.gradle.kts
    jvm_target = "21",           # matches jvmToolchain(21)
    language_version = "2.1",
)
```

### JDK Toolchain

Bazel 7+ uses the `rules_java` JDK toolchain. In `.bazelrc`:

```
build --java_runtime_version=21
build --tool_java_runtime_version=21
```

Or declare explicitly in MODULE.bazel:
```python
bazel_dep(name = "rules_java", version = "8.3.2")
```

### Android SDK Toolchain

```python
# In MODULE.bazel
android_sdk_repository_extension = use_extension(
    "@rules_android//rules/android_sdk_repository:rule.bzl",
    "android_sdk_repository_extension",
)
android_sdk_repository_extension.configure(
    api_level = 36,              # compileSdk = 36
    build_tools_version = "35.0.0",
)
use_repo(android_sdk_repository_extension, "androidsdk")
```

`.bazelrc`:
```
build --android_sdk=@androidsdk//:sdk
build --android_platforms=//platforms:android_arm64
```

---

## 5. commonMain: Separate Library vs Inline

**Recommendation: separate `kt_jvm_library` and `kt_android_library` targets, one each.**

Do not inline commonMain sources directly into each platform target via `glob`. Separate targets give:
- Better incremental caching (change to commonMain invalidates both flavors simultaneously, allowing parallel recompile)
- Cleaner visibility scoping — only `jvmMain` and `jvmTest` see `:common_jvm`
- Easier migration path when/if a KMP rule becomes available (replace two targets with one)

The penalty is that the ~80-100 commonMain Kotlin files are compiled twice per build. On modern hardware with Bazel's action graph, this is typically <10 seconds incremental and cached on subsequent builds.

---

## 6. Dependency Management: rules_jvm_external and the Version Catalog

### Key Finding: `maven.from_toml` — Direct TOML Import

`rules_jvm_external` v6.6+ supports importing Maven artifacts directly from a Gradle `libs.versions.toml` version catalog:

```python
# MODULE.bazel
maven = use_extension("@rules_jvm_external//:extensions.bzl", "maven")
maven.from_toml(
    libs_versions_toml = "//gradle:libs.versions.toml",
)
```

This means SteleKit's existing `gradle/libs.versions.toml` can be the single source of truth for Maven versions shared between Gradle (during transition) and Bazel. The TOML file would need to be in a Bazel-visible location under `//gradle/`.

### Coordinate Mapping

Gradle artifact coordinates (`group:artifact:version`) map to Bazel label targets with colons replaced by underscores:

| Gradle | Bazel label |
|--------|-------------|
| `io.arrow-kt:arrow-core:2.2.1.1` | `@maven//:io_arrow_kt_arrow_core` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2` | `@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_core` |
| `app.cash.sqldelight:runtime:2.3.2` | `@maven//:app_cash_sqldelight_runtime` |
| `androidx.compose.ui:ui:1.10.6` | `@maven//:androidx_compose_ui_ui` |

### KMP Artifact Disambiguation

KMP libraries publish multiple variants (JVM, Android, iOS, JS). `rules_jvm_external` resolves Maven artifacts using POM metadata, which for KMP libraries means it typically picks the JVM variant. For Android-specific variants, the `target_substitutions` attribute or explicit artifact coordinates (e.g., `io.arrow-kt:arrow-core-jvm:2.2.1.1`) may be needed.

The Gradle metadata resolution issue ([rules_jvm_external#864](https://github.com/bazel-contrib/rules_jvm_external/issues/864)) tracks KMP artifact disambiguation in Bazel. As of 2025, the recommended workaround is to use explicit `-jvm` classifier coordinates for JVM targets and `-android` for Android targets.

### Lock File

Pin all resolved Maven artifact checksums using `maven_install.json` for reproducible builds:

```python
maven.install(
    lock_file = "//:maven_install.json",
    # ...
)
```

Generate/update: `bazel run @maven//:pin`

---

## 7. Test Target Structure

### JVM Unit Tests (jvmTest / businessTest)

```python
# kmp/src/jvmTest/BUILD.bazel
load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_test")

kt_jvm_test(
    name = "jvm_tests",
    srcs = glob(["kotlin/**/*.kt"]),
    test_class = "dev.stapler.stelekit.AllTestsSuite",   # or use junit runner
    deps = [
        "//kmp/src/jvmMain:jvm_main",
        "//kmp/src/businessTest:business_tests",
        "@maven//:junit_junit",
        "@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_test",
        "@maven//:io_ktor_ktor_client_mock",
    ],
    jvm_flags = [
        "-Djdk.attach.allowAttachSelf=true",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "-Djava.awt.headless=false",
    ],
)
```

### businessTest (Pure Logic, No UI)

```python
# kmp/src/businessTest/BUILD.bazel
kt_jvm_library(
    name = "business_tests",
    srcs = glob(["kotlin/**/*.kt"]),
    deps = [
        "//kmp/src/commonMain:common_jvm",
        "@maven//:org_jetbrains_kotlin_kotlin_test_junit",
        "@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_test",
    ],
    visibility = ["//kmp:__subpackages__"],
    testonly = True,
)
```

### Android Unit Tests (androidUnitTest with Robolectric)

Robolectric has dedicated Bazel support via [robolectric-bazel](https://github.com/robolectric/robolectric-bazel). Use `android_local_test` (from `rules_android`), not `kt_android_local_test` (which does not exist):

```python
# kmp/src/androidUnitTest/BUILD.bazel
load("@rules_android//rules:rules.bzl", "android_local_test")
load("@rules_kotlin//kotlin:android.bzl", "kt_android_library")

# Compile test sources as an Android library first
kt_android_library(
    name = "android_unit_test_lib",
    srcs = glob(["kotlin/**/*.kt"]),
    custom_package = "dev.stapler.stelekit",
    deps = [
        "//kmp/src/androidMain:android_main",
        "@maven//:junit_junit",
        "@maven//:org_robolectric_robolectric",
        "@maven//:androidx_test_core",
        "@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_test",
    ],
    testonly = True,
)

android_local_test(
    name = "android_unit_tests",
    srcs = glob(["kotlin/**/*.kt"]),
    manifest = "//kmp:TestAndroidManifest.xml",
    deps = [
        ":android_unit_test_lib",
        "@robolectric//bazel:android-all",
        "@maven//:org_robolectric_robolectric",
    ],
)
```

MODULE.bazel must include:
```python
bazel_dep(name = "robolectric", version = "4.14.1")
```

### Screenshot Tests (Roborazzi)

Roborazzi does not have official Bazel support. The Roborazzi GitHub repository acknowledges this. Options:
1. **Keep screenshot tests in Gradle**: Run `./gradlew testDebugUnitTest` for screenshot verification; run all other tests via Bazel.
2. **Manual integration**: Add Roborazzi JARs via `maven_install` and configure `android_local_test` with the correct JVM flags and test runner. This requires manually wiring Compose UI test infrastructure and is undocumented.
3. **Exclude from Bazel CI initially**: Mark screenshot test targets as `tags = ["manual"]` so `bazel test //...` skips them.

**Recommendation**: Keep screenshot tests in Gradle during the initial migration phase (Phase 1 of migration). This is the lowest-risk approach and unblocks the core migration.

---

## 8. SQLDelight Code Generation

### Option A: Checked-in Generated Sources (Recommended for Initial Migration)

Run `./gradlew generateCommonMainSteleDatabase` once to produce the generated Kotlin under `kmp/build/generated/`, copy these files to a committed source directory (e.g., `kmp/src/commonMain/generated/sqldelight/`), and include them in the `commonMain` BUILD target's `srcs` glob.

Pros: Zero Bazel rule complexity; works immediately.
Cons: Generated files in version control; must regenerate manually on schema change.

### Option B: Custom `sqldelight_codegen` Rule

[square/sqldelight_bazel_rules](https://github.com/square/sqldelight_bazel_rules) provides a `sqldelight_codegen` rule, but it targets SQLDelight 1.x. For SQLDelight 2.x, there is no maintained Bazel rule. A custom rule invoking the SQLDelight CLI (`sqldelight-gradle-plugin` exposes a CLI entrypoint) would need to be written.

For a migration project, Option A is strongly recommended. Option B can be adopted once the initial migration is stable.

---

## 9. Key Risk Summary

| Risk | Severity | Mitigation |
|------|----------|-----------|
| No KMP rule for iOS/WASM | High | Phase 1 targets JVM + Android only; iOS/WASM remain in Gradle |
| Compose compiler plugin wiring | High | Pre-generate `.class` files or use Compose desktop JAR directly; investigate `kt_compiler_plugin` rule |
| SQLDelight 2.x codegen | Medium | Check in generated sources for Phase 1 |
| KMP Maven variant disambiguation | Medium | Use explicit `-jvm`/`-android` artifact classifiers in `maven_install` |
| Roborazzi screenshot tests | Low | Keep in Gradle; exclude from Bazel CI |
| `rules_android` maturity | Medium | `rules_android` 0.x is still pre-1.0; Google uses it internally but API may change |

---

## References

- [rules_kotlin GitHub](https://github.com/bazelbuild/rules_kotlin)
- [rules_kotlin rule reference](https://bazelbuild.github.io/rules_kotlin/kotlin.html)
- [Bzlmod Migration Guide](https://bazel.build/external/migration)
- [Bazel 8 Release Notes](https://blog.bazel.build/2024/12/09/bazel-8-release.html)
- [rules_jvm_external Bzlmod docs](https://github.com/bazel-contrib/rules_jvm_external/blob/master/docs/bzlmod.md)
- [rules_jvm_external MODULE.bazel example](https://github.com/bazel-contrib/rules_jvm_external/blob/master/examples/bzlmod/MODULE.bazel)
- [robolectric-bazel](https://github.com/robolectric/robolectric-bazel)
- [square/sqldelight_bazel_rules](https://github.com/square/sqldelight_bazel_rules)
- [KMP Bazel Cross-Platform Roadmap Issue #567](https://github.com/bazelbuild/rules_kotlin/issues/567)
- [Compose Multiplatform + Bazel discussion](https://slack-chats.kotlinlang.org/t/3145636/does-compose-multiplatform-support-bazel)
- [KMP + Bazel discussion (kotlinlang Slack)](https://slack-chats.kotlinlang.org/t/16406255/we-need-a-build-system-for-kotlin-we-have-reached-our-limit-)
- [KMP August 2025 Roadmap](https://blog.jetbrains.com/kotlin/2025/08/kmp-roadmap-aug-2025/)
