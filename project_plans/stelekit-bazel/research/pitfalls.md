# Bazel + Kotlin Multiplatform Migration: Pitfalls Research

**Researched**: 2026-05-17  
**Scope**: Known failure modes, gotchas, and risks for migrating SteleKit (KMP project) from Gradle to Bazel

---

## 1. Compose Compiler Plugin in Bazel

### How wiring works

The Compose compiler plugin must be declared via `kt_compiler_plugin` in `rules_kotlin`. The correct pattern:

```starlark
load("@rules_kotlin//kotlin:core.bzl", "kt_compiler_plugin")

kt_compiler_plugin(
    name = "jetpack_compose_compiler_plugin",
    id = "androidx.compose.compiler.plugins.kotlin",
    target_embedded_compiler = True,
    visibility = ["//visibility:public"],
    options = {
        "suppressKotlinVersionCompatibilityCheck": "2.x.x",
        "sourceInformation": "true",
    },
    deps = [
        artifact("org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable"),
    ],
)
```

Each `kt_jvm_library` or `kt_android_library` that uses Compose must reference the plugin via its `plugins` attribute.

### K2 mode breaking change (CRITICAL)

Starting with Compose Multiplatform 1.8.0, the framework **requires** K2. Since Kotlin 2.0, the Compose compiler has been bundled into the Kotlin repository itself (the `kotlin-compose-compiler-plugin-embeddable` artifact), rather than being a separate JetBrains artifact.

**Active open issue (rules_kotlin #1388, filed Oct 2025)**: Using Compose BOM 2025.10.00 with Kotlin 2.x in Bazel causes:
```
Argument type mismatch: actual type is 'Function0<Unit>', but
'@Composable() ComposableFunction0<Unit>' was expected.
```
This error means **the Compose plugin is not active during compilation**, even when `kt_compiler_plugin` is declared. The plugin registration mechanism changed between K1 and K2, and `rules_kotlin` has not yet fully adapted. `suppressKotlinVersionCompatibilityCheck` is a workaround, but its correct value for K2 is non-obvious.

### Plugin ordering risk

When both KSP (for Arrow Optics) and the Compose compiler plugin are active on the same target, ordering matters. The Compose plugin must run before the KSP processor sees the transformed composable types; Bazel's `plugins` list order is not guaranteed to be honored in the same way Gradle subplugins are. This requires careful testing.

### Version pinning requirement

The Compose compiler version must exactly match the Kotlin version (`kotlin-compose-compiler-plugin-embeddable:2.x.x` must equal the Kotlin toolchain version). In Gradle this is enforced by the `org.jetbrains.kotlin.plugin.compose` Gradle plugin; in Bazel you must pin it manually in `rules_jvm_external` and ensure it never drifts.

---

## 2. SQLDelight + Bazel

### Official support exists but is scoped

Square maintains `github.com/square/sqldelight_bazel_rules` — a dedicated `sqldelight_codegen` Starlark rule. This is a real, maintained artifact, not a DIY exercise.

```starlark
sqldelight_codegen(
    name = "stele_database_gen",
    package_name = "dev.stapler.stelekit",
    srcs = glob(["src/commonMain/sqldelight/**/*.sq"]),
    database_dialect = "sqlite:3.24",
    src_dir = "src/commonMain/sqldelight",
)
```

### Key limitations

- **KMP not fully covered**: `sqldelight_bazel_rules` is primarily tested for JVM and Android targets. Generating code for multiple KMP targets (iOS native, WASM) from a single Bazel rule is undocumented and likely requires custom per-platform `sqldelight_codegen` calls or a wrapper.
- **No Gradle CLI**: SQLDelight has no standalone CLI or non-Gradle entry point, so the Bazel rule invokes the Gradle plugin logic via a bootstrapped runner. This is a maintenance liability — SQLDelight Gradle plugin version changes may break the Bazel rule without notice.
- **Checked-in generated sources alternative**: The pragmatic fallback is to check in the generated Kotlin sources and treat them as regular source files in Bazel. This trades build hermeticity for simplicity but creates a dual-maintenance risk (schema and generated code can diverge).
- **Sub-package naming**: The rule generates `DatabaseImpl` in a sub-package derived from the Bazel package/target name, not from the SQLDelight `packageName` in the `.sq` file. This diverges from the Gradle behavior and may require source-level changes.

---

## 3. KSP (Kotlin Symbol Processing) in Bazel

### Arrow's actual KSP usage

Arrow core (`arrow-core`, `arrow-coroutines`, `arrow-fx`) does **not** require KSP or KAPT — it is a pure Kotlin library with no annotation processing. **The exception is `arrow-optics`**, which requires:

```kotlin
ksp("io.arrow-kt:arrow-optics-ksp-plugin:2.x.x")
```

SteleKit's CLAUDE.md shows Arrow is used for `Either`-based error handling only. Unless `@optics` annotations are used anywhere, **Arrow does not trigger a KSP requirement**. Confirm by grepping for `@optics` before treating KSP as mandatory.

### KSP in rules_kotlin

KSP is officially supported since `rules_kotlin` 1.8 via `kt_ksp_plugin`. The migration from KSP1 (compiler plugin) to KSP2 (standalone tool) is underway; KSP2 has been the default since early 2025. The `rules_kotlin` team is tracking this migration.

**Open limitation (rules_kotlin issue #1035)**: Passing options to KSP processors was not supported until recently. This is now fixed, but combined KSP + Compose plugin targets remain under-tested in Bazel.

### KAPT status

KAPT is in maintenance mode and deprecated. If any dependency still requires KAPT (not KSP), Bazel support is weaker. Verify all annotation-processing dependencies have KSP variants before committing to the migration.

---

## 4. IDE Integration Risks

### Two plugin ecosystems, both in flux

There are now **two separate Bazel plugins for IntelliJ/Android Studio**:

| Plugin | Status (May 2026) | Origin |
|--------|------------------|--------|
| Google's IJwB (`bazelbuild/intellij`) | Maintenance-only, will be deprecated in 2026 | Google (transferred ownership) |
| JetBrains new Bazel plugin | GA since 2025.2, requires IntelliJ 2025.2+ | JetBrains |

The JetBrains plugin reached GA only in mid-2025 and is focused on JVM, Kotlin, Scala, Python, Go. **KMP-specific features in the Bazel plugin are not documented** — the KMP plugin (beta, macOS-only until IntelliJ 2025.2.2) is separate and its interaction with Bazel projects is untested.

### Kotlin Multiplatform plugin vs Bazel

The KMP IDE plugin (which gives expect/actual navigation, KMP facet support, etc.) is designed for Gradle-based KMP projects. There is no documented path for KMP + Bazel projects to get full KMP IDE tooling. Developers would likely lose:
- expect/actual cross-navigation
- Platform-specific source set highlighting
- KMP run configurations

### Known issue: wrong Kotlin version in IDE

`bazelbuild/intellij` issue #1274: The Bazel IntelliJ plugin sets the wrong Kotlin version in IDE project model, causing spurious red errors in the editor that do not reflect actual build failures.

---

## 5. iOS + Bazel

### macOS build host is non-negotiable

Kotlin/Native **cannot produce final iOS binaries on Linux or Windows**. The Kotlin/Native documentation is explicit: "Building final binaries for Apple targets on Linux and Windows is not possible." Intermediate `.klib` artifacts can be cross-compiled, but the final `.framework` or `.xcframework` must be built on macOS.

**Implication**: If CI currently runs on Linux (e.g., GitHub Actions `ubuntu-latest`), a macOS runner must be added (or substituted) for iOS builds. This is a cost and infrastructure change.

### rules_apple complexity

Bazel's official `rules_apple` covers bundling `.ipa`/`.app` but:
- Still relies on `xcrun` to invoke Apple tools (not hermetic — xcrun searches the system PATH)
- Cross-compilation scenarios require careful platform constraint configuration
- Swift interop (needed for Kotlin/Native XCFramework consumption by Swift/Obj-C host apps) adds a `rules_swift` dependency on top of `rules_apple`

### Kotlin/Native toolchain download

Kotlin/Native downloads its own compiler distribution (`~/.konan/`) during builds. In a hermetic Bazel environment this download is **forbidden** during the build phase. The toolchain must be pre-fetched and declared as a Bazel repository dependency via a `http_archive` or similar. There is no out-of-the-box `rules_kotlin` support for this; it requires custom Starlark toolchain rules.

### Community alternative: rules_ios

`github.com/bazel-ios/rules_ios` is a community-maintained alternative to `rules_apple` with broader iOS feature coverage, but adds a third-party dependency with its own update cadence risk.

---

## 6. Hermetic Build Risks

### Gradle plugins that do network access

The following patterns in the current SteleKit Gradle build would break Bazel's sandboxing:

| Pattern | Gradle behavior | Bazel impact |
|---------|----------------|--------------|
| Kotlin/Native toolchain | Downloads from `download.jetbrains.com/kotlin/native/` automatically | Must be pre-declared as `http_archive` |
| SQLDelight code generation | Gradle plugin may resolve additional artifacts at configure time | Code gen rule must have all deps in `WORKSPACE` |
| Compose resources (fonts, images) | May fetch from Maven or CDN | All assets must be local or pre-fetched |
| Version catalogs (`libs.versions.toml`) | Gradle resolves BOMs at sync time | BOM contents must be pinned in `rules_jvm_external` lockfile |

### rules_jvm_external and BOM support

Gradle's BOM (Bill of Materials) support is native. In Bazel, `rules_jvm_external` supports BOMs but requires explicit `bom_imports` configuration. Version alignment that Gradle does automatically (e.g., Compose BOM aligning all `androidx.compose.*` versions) must be replicated manually in the `maven_install` rule.

### Minimal Version Selection vs Newest Version Selection

Gradle uses Newest Version Selection; Bazel's `rules_jvm_external` uses a Maven-compatible resolver. This means transitive version conflicts that Gradle silently resolved via "pick the newest" will **surface as hard errors** in Bazel until the lockfile is explicitly fixed. KMP projects with many transitive dependencies (Compose, Coroutines, SQLDelight, Arrow) are particularly exposed.

---

## 7. Migration Effort Realism

### Google's AOSP Bazel migration was cancelled (2024)

Google's project to migrate Android Open Source Project (AOSP) to Bazel was cancelled in April 2024, citing that "the project took longer than anticipated" and Android business priority reviews. This is the largest Android/Kotlin Bazel migration attempt ever made — its cancellation is a strong signal about the difficulty ceiling.

### Academic research: 11% abandonment rate

A study by the REBELs group (U. of Waterloo) of 35,000 GitHub projects found ~11% of projects that adopted Bazel abandoned it around the 2-year mark. Reasons cited: technical challenges, team onboarding difficulty, and observing other high-profile projects (e.g., Kubernetes) leaving Bazel.

### KMP-specific difficulty

A Google Summer of Code 2025 project was proposed specifically to improve Bazel's KMP support — treating it as a research/engineering problem rather than a solved one. The proposal targets dependency resolution issues and cross-platform build support that remain open as of 2025.

### Compose BOM version lock-in

Teams using Bazel with Kotlin/Compose have reported being **stuck on older Compose BOM versions** because updating to 2025.x requires Kotlin 2.x, which requires adapting `rules_kotlin` in ways that are non-trivial. This creates a maintenance lag: the Gradle ecosystem moves fast; Bazel wrappers follow slowly.

### Plugin ecosystem gap

Gradle has hundreds of ecosystem plugins (Detekt, Dokka, Spotless, versions plugin, etc.) that SteleKit likely uses (CLAUDE.md shows Detekt in CI). Each Gradle plugin must be replicated as a Bazel rule or dropped. There is no Detekt Bazel rule; it would need to run as a `genrule` or separate CI step, losing Bazel caching benefits.

---

## 8. Version Compatibility Matrix

### rules_kotlin version vs Kotlin version

`rules_kotlin` releases track Kotlin versions closely. The latest is `v2.3.20`, supporting Kotlin language levels 1.9, 2.0, 2.1, 2.2, 2.3. There is no published formal compatibility matrix, but the pattern is:

| rules_kotlin release | Kotlin version | K2 support | KSP version |
|---------------------|---------------|-----------|-------------|
| 1.8.x | Kotlin 1.9.x | KSP1 only | KSP1 |
| 2.0.x–2.1.x | Kotlin 2.0–2.1 | `x_use_k2` flag | KSP1 → KSP2 transition |
| 2.3.x (current) | Kotlin 2.3 | K2 default | KSP2 |

**Critical constraint**: `rules_kotlin` does **not** support Kotlin/Native or Kotlin/WASM targets. Only JVM, Android, and JS (partially maintained) are supported. For a full KMP build (JVM + Android + iOS/Native + WASM), multiple rule sets are required — and several of those rule sets do not exist yet in a production-ready state.

### Bazel version requirements

`rules_kotlin` 2.x requires Bazel 6.0+ (Bzlmod-compatible). Bazel 8.0 LTS was released December 2024 and is the current recommended version. The Bzlmod module system (replacing WORKSPACE) is now the default; older WORKSPACE-based setups are deprecated.

### Compose Multiplatform version requirements

| Compose Multiplatform | Required Kotlin | Compose compiler source |
|----------------------|----------------|------------------------|
| < 1.6.0 | Kotlin 1.9.x | Separate JetBrains artifact |
| 1.6.x–1.7.x | Kotlin 2.0–2.1 | Bundled in Kotlin repo |
| 1.8.0+ | Kotlin 2.0+ (K2 required) | `kotlin-compose-compiler-plugin-embeddable` |

SteleKit should be using Compose Multiplatform 1.8.x by now. This means:
- K2 is mandatory
- The Gradle plugin `org.jetbrains.kotlin.plugin.compose` approach doesn't translate to Bazel
- Manual `kt_compiler_plugin` wiring is required, with the active bug (issue #1388) unresolved

---

## Summary of Highest-Risk Items

1. **Kotlin/Native + iOS is unsupported by rules_kotlin** — there are no `kt_native_*` rules. Building the iOS target requires writing custom Starlark toolchain rules for the Kotlin/Native compiler distribution (which also cannot be network-fetched during builds). This is the single largest gap and could block full KMP migration.

2. **Compose compiler plugin K2 wiring is actively broken** — rules_kotlin issue #1388 (October 2025) shows that Compose BOM 2025 + Kotlin 2.x does not work out-of-the-box in Bazel. The `@Composable` type transformation fails silently, producing type mismatch errors at call sites. This must be resolved before any Compose UI code can build under Bazel.

3. **SQLDelight multiplatform code generation is undocumented** — `sqldelight_bazel_rules` exists for JVM/Android but has no documented KMP multi-target support. Generating `commonMain` Kotlin + native drivers for all platforms will require either custom Starlark work or a checked-in generated sources strategy, both of which have ongoing maintenance costs.
