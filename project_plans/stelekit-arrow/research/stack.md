# Stack Research: Arrow 2.x for Kotlin Multiplatform

## Latest Stable Version

**Arrow 2.2.1.1** (December 2025) — this is the version to target for SteleKit.

Arrow 2.0 was released December 2024, built with the K2 compiler, aligning with Kotlin 2.0.
Arrow 2.2.0 released November 2025, leveraging Kotlin 2.2 features.
Arrow 2.2.1.1 released December 2025, focused on Kotlin 2.3 and Jackson 3.0 compatibility.

---

## Maven Coordinates

All modules use group `io.arrow-kt` and version `2.2.1.1`.

| Module | Maven Coordinate | Purpose |
|---|---|---|
| Core | `io.arrow-kt:arrow-core:2.2.1.1` | Either, Option, NonEmptyList, Raise, Effect |
| Optics | `io.arrow-kt:arrow-optics:2.2.1.1` | Lenses, traversals, prisms, isos |
| Optics KSP | `io.arrow-kt:arrow-optics-ksp-plugin:2.2.1.1` | @optics annotation processor |
| Fx Coroutines | `io.arrow-kt:arrow-fx-coroutines:2.2.1.1` | Resource, STM, TVar, TMap, parallel |
| Resilience | `io.arrow-kt:arrow-resilience:2.2.1.1` | Schedule, retry, CircuitBreaker, Saga |
| BOM | `io.arrow-kt:arrow-stack:2.2.1.1` | Bill of materials for version alignment |

---

## KMP Platform Target Matrix

Arrow 2.x publishes artifacts for all Kotlin Multiplatform targets. Arrow 2.0 explicitly added WebAssembly support via the K2 compiler.

| Module | jvm | android | iosX64 | iosArm64 | iosSimArm64 | wasmJs |
|---|---|---|---|---|---|---|
| arrow-core | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| arrow-optics | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| arrow-optics-ksp-plugin | ✅ | ✅ | ✅ | ✅ | ✅ | ✅* |
| arrow-fx-coroutines | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| arrow-resilience | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

*KSP code generation runs at build time on the host JVM — the generated Kotlin code is then compiled to each target including wasmJs.

---

## Gradle Setup for KMP

### Step 1: Apply plugins in `build.gradle.kts`

```kotlin
plugins {
    kotlin("multiplatform")
    id("com.google.devtools.ksp") version "2.2.0-1.0.29"  // KSP version must match Kotlin version
    // Optional: Arrow Optics Gradle plugin (beta, simpler than KSP for pure-KMP)
    // id("io.arrow-kt.arrow-optics-gradle-plugin") version "2.2.1.1"
}
```

### Step 2: Add Arrow BOM + dependencies

```kotlin
kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                // Use BOM for version alignment
                implementation(platform("io.arrow-kt:arrow-stack:2.2.1.1"))
                implementation("io.arrow-kt:arrow-core")
                implementation("io.arrow-kt:arrow-fx-coroutines")
                implementation("io.arrow-kt:arrow-resilience")
                implementation("io.arrow-kt:arrow-optics")
            }
        }
    }
}
```

### Step 3: KSP configuration for Arrow Optics in KMP

The `ksp` configuration is deprecated in KMP — use target-specific configurations:

```kotlin
dependencies {
    // Run KSP on commonMain metadata (generates shared optics code)
    add("kspCommonMainMetadata", "io.arrow-kt:arrow-optics-ksp-plugin:2.2.1.1")
    // Also add per-target to prevent "unresolved reference" errors:
    add("kspJvm", "io.arrow-kt:arrow-optics-ksp-plugin:2.2.1.1")
    add("kspAndroid", "io.arrow-kt:arrow-optics-ksp-plugin:2.2.1.1")
    add("kspIosX64", "io.arrow-kt:arrow-optics-ksp-plugin:2.2.1.1")
    add("kspIosArm64", "io.arrow-kt:arrow-optics-ksp-plugin:2.2.1.1")
    add("kspIosSimulatorArm64", "io.arrow-kt:arrow-optics-ksp-plugin:2.2.1.1")
}

// Make commonMain compilation depend on KSP metadata generation
tasks.withType<org.jetbrains.kotlin.gradle.dsl.KotlinCompile<*>>().all {
    if (name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}

// Point to generated sources
kotlin.sourceSets.commonMain {
    kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
}
```

### Step 4: Arrow Optics Gradle Plugin (Beta — simpler alternative)

Arrow announced a new Gradle plugin in November 2025 that handles all KSP configuration automatically:

```kotlin
plugins {
    id("io.arrow-kt.arrow-optics-gradle-plugin") version "2.2.1.1"
}
// No additional KSP configuration needed — plugin handles it
```

This is beta but may be stable by the time SteleKit's migration begins. Prefer the KSP approach until the Gradle plugin is marked stable.

---

## Kotlin Version Compatibility

| Arrow Version | Kotlin Version | Notes |
|---|---|---|
| 2.0.x | 2.0.x | Built with K2 compiler; first Arrow K2 release |
| 2.1.x | 2.1.x | Builds on Kotlin 2.1 features |
| 2.2.0 | 2.2.x | Leverages new Kotlin 2.2 features |
| 2.2.1.1 | 2.2.x / 2.3.x | Explicit Kotlin 2.3 compatibility |

SteleKit currently uses Kotlin (see `build.gradle.kts` — uses `jvmToolchain(21)`, kotlinx-coroutines 1.10.2 which requires Kotlin 2.x). Arrow 2.2.1.1 is compatible.

**KSP version must match Kotlin version:** For Kotlin 2.x, use KSP `2.x.y-1.0.z`. Check https://github.com/google/ksp/releases for the exact mapping.

---

## Compose Multiplatform 1.7.x Compatibility

SteleKit uses Compose Multiplatform 1.7.3. Arrow does not depend on Compose — there are no known compatibility issues between Arrow 2.2.x and Compose Multiplatform 1.7.x. Arrow Optics generates pure Kotlin data class helpers that are Compose-agnostic.

---

## Known Issues

1. **KSP + KMP `ksp` config deprecated:** Must use target-specific `kspJvm`, `kspAndroid`, etc. — the generic `ksp(...)` shorthand produces deprecation warnings and may silently skip targets.

2. **"Unresolved reference" for generated optics:** Common on cold builds if `kspCommonMainKotlinMetadata` task hasn't run before compilation. Fix: add `dependsOn("kspCommonMainKotlinMetadata")` to all compilation tasks.

3. **wasmJs optics:** The KSP processor runs on JVM; generated code compiles fine to wasmJs. No runtime issues reported.

4. **Arrow 2.0 optics breaking change:** `Optional<S, A>` for nullable fields is now `Lens<S, A?>`. Migration guide at https://arrow-kt.io/learn/quickstart/migration/.

5. **STM on WASM/JS:** WASM/JS is single-threaded; STM compiles and runs but contention never actually occurs — all transactions complete without retry. This is correct behavior, not a bug.

---

## Sources

- [Arrow Setup Docs](https://arrow-kt.io/learn/quickstart/setup/)
- [Arrow 2.0 Release Blog](https://arrow-kt.io/community/blog/2024/12/05/arrow-2-0/)
- [Arrow 2.2.0 Release Blog](https://arrow-kt.io/community/blog/2025/11/01/arrow-2-2/)
- [Arrow 2.2.1.1 Release Blog](https://arrow-kt.io/community/blog/2025/12/17/arrow-2-2-1/)
- [Arrow Optics Gradle Plugin Beta](https://arrow-kt.io/community/blog/2025/11/01/arrow-optics-gradle/)
- [Arrow GitHub Releases](https://github.com/arrow-kt/arrow/releases)
- [Arrow KMP Template](https://github.com/arrow-kt/Arrow-KMP-Template)
- [KSP Multiplatform Docs](https://kotlinlang.org/docs/ksp-multiplatform.html)
- [Maven Central: arrow-fx-coroutines](https://mvnrepository.com/artifact/io.arrow-kt/arrow-fx-coroutines)
