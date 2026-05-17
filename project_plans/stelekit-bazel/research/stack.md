# Stack Research: Bazel Ruleset Ecosystem for Kotlin Multiplatform

_Researched: 2026-05-17_

---

## 1. `bazelbuild/rules_kotlin` — Current State and KMP Support

### Status

- **Latest release**: `2.3.20` (available in the Bazel Central Registry as of May 2026).
- `bazel_dep(name = "rules_kotlin", version = "2.3.20")` in MODULE.bazel is the current entry point.
- Actively maintained under `bazel-contrib/rules_kotlin` (moved from `bazelbuild/`).

### What Is Supported

- `kt_jvm_library`, `kt_jvm_binary`, `kt_jvm_test` — stable and production-quality.
- `kt_android_library` — works; used by companies like Turo and Grab in production monorepositories.
- `kt_js_library` — exists but is less mature.
- Compiler plugins via `kt_compiler_plugin` and `kt_ksp_plugin` (KSP support since 1.8).
- KAPT annotation processing via `java_plugin` / `exported_plugins`.

### What Is NOT Supported

- **Kotlin Multiplatform (KMP) in the full sense is not supported.** There are no `kt_multiplatform_library` rules, no `expect`/`actual` mechanism, and no way to express source set hierarchies (`commonMain`, `jvmMain`, `androidMain`, `iosMain`, `jsMain`) in Bazel BUILD files.
- **Issue #567** ("Kotlin Bazel Cross-Platform Roadmap") has been open for years. The roadmap document acknowledges the gap but there is no concrete implementation of `expect`/`actual` handling in rules_kotlin.
- **Kotlin/Native (iOS) is completely absent.** rules_kotlin has no `kt_native_*` rules. Compiling to a Kotlin/Native framework for iOS or a `.klib` for WASM is outside its scope.
- A 2022 Kotlin Slack answer confirmed: "Bazel migration is possible for Kotlin JVM or JS projects, but not for KMP."

### Kotlin 2.x / K2 Compatibility

- Kotlin 2.3.20 (the latest as of May 2026) is the language release; `rules_kotlin 2.3.20` tracks a similar version number but the two are not guaranteed to be in sync.
- **Compose with Kotlin 2.x is a known friction point.** Issue #1388 in rules_kotlin (filed October 2025) reports that using Compose BOM `2025.10.00` with Kotlin 2.x fails because the Compose compiler moved from a standalone artifact to `org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable` (bundled with Kotlin since 2.0). The Compose compiler plugin integration in rules_kotlin requires a `suppressKotlinVersionCompatibilityCheck` workaround and is not yet seamlessly supported.
- The working pattern for Compose on JVM with rules_kotlin is:

```starlark
kt_compiler_plugin(
    name = "compose_compiler_plugin",
    id = "androidx.compose.compiler.plugins.kotlin",
    target_embedded_compiler = True,
    options = {
        "suppressKotlinVersionCompatibilityCheck": "2.0.21",
        "sourceInformation": "true",
    },
    deps = [
        artifact("org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable"),
    ],
)
```

This works for **Compose on JVM/Android** only. Compose Multiplatform (Desktop, iOS, WASM) has no Bazel support path.

### Kotlin Build Tools API (KEEP-421)

JetBrains introduced the Build Tools API in Kotlin 1.9.20 as the official integration point for non-Gradle build tools, including incremental compilation support. Meta used this to enable Kotlin incremental compilation in Buck2 (announced August 2025). rules_kotlin has not yet adopted this API — it still drives `kotlinc` directly. Adoption of KEEP-421 would unlock true incremental builds in Bazel, but there is no public timeline.

---

## 2. `rules_kmp` — Community KMP + Bazel Projects

There is **no mature, production-ready `rules_kmp` project** as of May 2026. Searches across GitHub, the Bazel Central Registry, and the Kotlin Slack archive found:

- No GitHub repository named `rules_kmp` with meaningful adoption.
- A GSoC 2025 Kotlin project description referencing Bazel KMP support improvements (dependency resolution, Gradle metadata, `expect`/`actual` linkage), but no shipped artifact from that work is publicly available.
- Individual developer experiments exist (not indexed in BCR, no versioned releases).

**Conclusion**: `rules_kmp` does not exist as a usable library. KMP in Bazel requires custom bespoke rules or a different strategy.

---

## 3. Alternative Approaches

### 3a. Bazel for JVM-only, Gradle for Native Targets (Hybrid)

The pragmatic approach used by the few teams building KMP + Bazel:
- Use Bazel to compile `commonMain` + `jvmMain` + `androidMain` source sets (these are all JVM-compatible).
- Leave iOS (`iosMain`, Kotlin/Native) and WASM (`jsMain`) compiled by Gradle, or produce `.klib` artifacts via Gradle and treat them as pre-built inputs to Bazel.
- This gives incremental Bazel benefits for the 80% of the code (JVM + Android) while keeping Gradle as a peripheral tool.

### 3b. Gradle as a Bazel Pre-processor / Code Generator

Run Gradle once to generate:
- SQLDelight Kotlin output (checked in or as a Gradle task artifact).
- Any KSP-generated code.
- `expect`/`actual` resolved source sets for each platform.

Then feed the generated Kotlin files into Bazel's `kt_jvm_library` / `kt_android_library`. This approach makes Gradle a one-time generator, not the build system. The downside is that changes to `.sq` files or annotated code require re-running Gradle.

### 3c. Gazelle for BUILD File Generation

`srmocher/gazelle-kotlin` is a Gazelle extension that generates `kt_jvm_library` targets from Kotlin source files. It is described as "early work-in-progress" and relies on `rules_jvm_external` for dependency inference. It generates `kt_jvm_library` only — no KMP awareness.

Gazelle reduces the BUILD file maintenance burden for JVM Kotlin, but it has no understanding of KMP source set structure (`commonMain` vs `jvmMain` etc.).

### 3d. Airin (Morfly/airin) — Gradle-to-Bazel Migration Tool

- A Gradle plugin that reads your Gradle project model and emits `.bzl` / `BUILD.bazel` files.
- Last updated February 2026, actively maintained by a Turo engineer.
- Targets Android + JVM Kotlin projects. No KMP multiplatform awareness.
- Useful for bootstrapping the initial BUILD file set for JVM/Android modules.

### 3e. Grazel (Grab) — Incremental Gradle-to-Bazel Migration

- Another Gradle plugin approach (`./gradlew migrateToBazel`), used in production at Grab.
- Handles Android, `kt_jvm_library`, Dagger, Hilt. No KMP multiplatform support.
- As of June 2025, still receiving issues and updates.

### 3f. Buck2 as an Alternative to Bazel

Meta's Buck2 is the strongest alternative for Kotlin + Android:
- KotlinConf 2025 (May, Copenhagen) featured "Build your Kotlin and Android apps with Buck2" by Meta's Sergei Rybalkin.
- Meta enabled Kotlin incremental compilation in Buck2 via the Build Tools API (KEEP-421), announced August 2025.
- Buck2 has mature Android rules (in Meta's monorepository) and remote execution.
- **But**: Buck2 also has no KMP (Kotlin/Native, WASM) rules. The same gap exists. Buck2 is written in Rust with all rules in Starlark, giving it better long-term extensibility but a steeper learning curve.

---

## 4. SQLDelight + Bazel Integration

### square/sqldelight_bazel_rules

- **Official repository**: `github.com/square/sqldelight_bazel_rules`
- Provides a `sqldelight_codegen` Starlark rule that runs the SQLDelight compiler as a Bazel action, emitting generated Kotlin sources.
- Key attributes: `name`, `package_name`, `srcs` (`.sq` files), `database_dialect`, `src_dir`, `module_name`.
- The README states: "SQLDelight doesn't presently have a CLI or non-Gradle entry point, so the Bazel tooling provides this front-end until it can be upgraded."

### SQLDelight 2.x Compatibility

- **Critical issue**: The `square/sqldelight_bazel_rules` repository was built against SQLDelight 1.x (`com.squareup.sqldelight`). SQLDelight 2.0 changed the Maven group ID to `app.cash.sqldelight` — a breaking change.
- The last substantive commit in `sqldelight_bazel_rules` predates the 2.0 release, and there is no evidence of a 2.x-compatible release.
- **For SteleKit (SQLDelight 2.3.2)**: The existing Bazel rules are incompatible. Options:
  1. Fork `sqldelight_bazel_rules` and update it to invoke the `app.cash.sqldelight` 2.x CLI/compiler.
  2. Check in generated Kotlin sources from SQLDelight (`./gradlew generateCommonMainSteleDatabaseInterface`) and treat them as static source files in Bazel.
  3. Write a custom Bazel `genrule` / Starlark rule that shells out to the SQLDelight Gradle task and captures outputs.

Option 2 (checked-in generated sources) is the lowest-risk path for initial migration: run Gradle once per schema change, commit the generated files, build everything else in Bazel.

---

## 5. Compose Multiplatform + Bazel

### Summary

Compose Multiplatform (JetBrains) has **no official Bazel support**. The Kotlin Slack archive (2022, still accurate as of 2026) confirms: "Does compose multiplatform support Bazel? No."

### JVM / Android Compose in Bazel (Partial Support)

For **Compose on JVM** (Desktop) and **Jetpack Compose on Android**, the `kt_compiler_plugin` mechanism works with the workaround described in Section 1. This covers the `jvmMain` / `androidMain` Compose UI code in SteleKit.

### Compose for iOS and WASM

- These targets require Kotlin/Native and Kotlin/WASM toolchains respectively.
- Neither is available in rules_kotlin or any Bazel ruleset.
- Compose for iOS is stable as of 2025; Compose for Web (WASM) is in Beta. Both are Gradle-only from a build tooling perspective.

### Compose Compiler Plugin Versioning

- Since Kotlin 2.0, the Compose compiler is bundled: `org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable` (matches the Kotlin version).
- In rules_kotlin, you must explicitly declare this as a `kt_compiler_plugin`. The version must exactly match the Kotlin version used in the `kotlin_repositories()` call. Mismatches cause cryptic type errors ("actual type is `Function0<Unit>`, expected `@Composable ComposableFunction0<Unit>`").

---

## 6. Toolchain Requirements

### JDK Version Pinning

- Bazel 7.4+ / 8.x supports `local_java_repository` / `remote_java_repository` for pinning.
- `rules_kotlin 2.3.20` requires a minimum of Bazel 7.4; Bazel 8.x is recommended.
- JDK 17 or 21 should be pinned via MODULE.bazel `java_toolchains` extension for reproducible builds.

### Kotlin Version Compatibility

- rules_kotlin 2.3.20 supports recent Kotlin releases. It declares the Kotlin toolchain in `kotlin_repositories(kotlin_release_version = "2.x.y")`.
- The Kotlin version in rules_kotlin must match the version of `kotlin-compose-compiler-plugin-embeddable` used in the Compose plugin declaration.
- Kotlin 2.3.x (December 2025 release) brought stricter KMP metadata dependency matching that can cause metadata compilation failures. This is a KMP-specific concern less relevant if we compile only JVM/Android targets in Bazel.

### Android SDK (rules_android)

- `rules_android 0.6.6` supports Bazel 7.4+ / 8.x. Available in the Bazel Central Registry.
- Android SDK is configured via `MODULE.bazel` with `android_sdk_repository` or `android_sdk_repository_name`.
- Toolchain registration covers `@rules_android//toolchains/android:android_default_toolchain`.
- The Android NDK (for any JNI) requires Android NDK 25b+ with `rules_android_ndk`.
- `rules_jvm_external` handles Maven dependency resolution. **Critical gap**: `rules_jvm_external` uses POM files by default, but KMP libraries publish Gradle Module Metadata (`.module` files) which encode platform-variant selection. AndroidX is reportedly considering dropping `.pom` support entirely (Issue #1376). PR #1357 in `rules_jvm_external` adds a Gradle metadata resolver, but it was still failing tests and was very slow as of late 2025. This means KMP Maven artifacts (e.g., `app.cash.sqldelight:runtime`) may resolve to the wrong variant (or fail to resolve) in Bazel without manual `artifact()` overrides specifying the correct classifier.

### iOS Toolchain

There are no Bazel rules for Kotlin/Native iOS compilation. iOS targets must remain in Gradle (or Xcode directly via the Kotlin/Native Xcode integration). rules_apple exists for Swift/ObjC iOS apps, but Kotlin/Native is not supported in any Bazel ruleset.

### WASM/JS Toolchain

`rules_kotlin` has basic `kt_js_library` but Kotlin/WASM (`wasm-js` target) has no Bazel support. WASM builds remain Gradle-only.

---

## 7. Dependency Resolution: KMP Artifacts in rules_jvm_external

KMP libraries publish platform-specific variants selected via Gradle Module Metadata. `rules_jvm_external` is POM-centric. This creates two concrete problems for SteleKit:

1. **SQLDelight runtime** (`app.cash.sqldelight:runtime`) has a `jvm` artifact and an `android` artifact; without Gradle metadata resolution, Bazel may pick the wrong one or fail.
2. **Coroutines, Arrow, etc.** are KMP libraries. Their JVM artifacts are usually available with explicit classifiers (`:jvm` suffix or no classifier for the JVM variant), so manual `artifact()` declarations often work.

Workaround: specify JVM-specific artifact coordinates explicitly in `maven_install`:
```starlark
artifact("app.cash.sqldelight:runtime-jvm:2.3.2")
artifact("app.cash.sqldelight:android-driver:2.3.2")
```

---

## 8. Key Open-Source Projects Reference Table

| Project | URL | Maturity | KMP Support | Notes |
|---|---|---|---|---|
| rules_kotlin | github.com/bazel-contrib/rules_kotlin | Stable (2.3.20) | JVM + Android + JS only | No `expect`/`actual`, no Native/WASM |
| rules_android | github.com/bazelbuild/rules_android | Active (0.6.6) | Android only | SDK + NDK toolchains |
| rules_jvm_external | github.com/bazel-contrib/rules_jvm_external | Active | Partial (POM-only, KMP variant gap) | PR #1357 adds Gradle resolver (incomplete) |
| sqldelight_bazel_rules | github.com/square/sqldelight_bazel_rules | Stale (1.x only) | N/A | Does not support SQLDelight 2.x |
| gazelle-kotlin | github.com/srmocher/gazelle-kotlin | Early WIP | None | JVM BUILD file gen only |
| Airin | github.com/Morfly/airin | Active (Feb 2026) | None | Gradle→Bazel migration, Android/JVM |
| Grazel | github.com/grab/grazel | Active | None | Gradle→Bazel migration, Android |
| Buck2 | buck2.build | Active (Meta) | None | Best alternative to Bazel for Kotlin + Android |

---

## Summary

1. **rules_kotlin supports JVM and Android Kotlin well, but KMP is a hard wall.** The `expect`/`actual` mechanism, Kotlin/Native (iOS), and Kotlin/WASM are completely absent from rules_kotlin and from any other publicly available Bazel ruleset. There is no `rules_kmp` project worth adopting. A Bazel migration for SteleKit must either scope down to JVM + Android only, or adopt a hybrid where iOS and WASM targets remain Gradle-compiled.

2. **SQLDelight 2.x has no working Bazel rules.** `square/sqldelight_bazel_rules` is stale and incompatible with `app.cash.sqldelight` 2.x. The lowest-risk path is to check in generated Kotlin sources from SQLDelight as a build artifact and exclude them from Bazel's incremental graph. A custom genrule invoking the SQLDelight CLI is the next step up.

3. **Compose on JVM/Android in Bazel works with friction.** Using `kt_compiler_plugin` with `kotlin-compose-compiler-plugin-embeddable` is the documented path, but active issues (e.g., #1388) show that Kotlin 2.x + Compose BOM 2025.x requires careful version pinning and `suppressKotlinVersionCompatibilityCheck` workarounds. Compose Multiplatform (iOS, Web) has zero Bazel support.
