# BUG-001: KotlinNativeBundleBuildService Classloader Mismatch Breaks iOS CI [SEVERITY: High]

**Status**: 🐛 Open
**Discovered**: 2026-04-18
**Upstream Tracker**: [Gradle issue #17559](https://github.com/gradle/gradle/issues/17559)
**Impact**: iOS CI cannot run `KotlinNativeCompile` or iOS-associated `KotlinCompileCommon` tasks.
All iOS compilation checks are blocked; iOS CI job is currently `continue-on-error: true`.

## Problem Description

In a multi-project build where `:kmp` uses `kotlin-multiplatform` and `:androidApp` uses
Android Gradle Plugin (AGP), `KotlinNativeBundleBuildService` is loaded by two different
classloaders. The Kotlin Gradle Plugin (KGP) wires the service onto tasks via:

```kotlin
task.kotlinNativeBundleBuildService.value(serviceProvider).disallowChanges()
```

Gradle 8.8+ validates that the `Property<T>` and `Provider<T>` share the same classloader
instance for the type `T`. When they don't (due to the mixed plugin sets), Gradle throws:

```
Cannot set the value of task ':kmp:compileKotlinIosSimulatorArm64' property
'kotlinNativeBundleBuildService' of type KotlinNativeBundleBuildService using a provider
of type KotlinNativeBundleBuildService.
```

This error occurs during project **configuration** (task creation), so it blocks all
iOS-associated tasks — both `KotlinNativeCompile` and iOS-target `KotlinCompileCommon`.

## Reproduction Steps

1. Have a KMP project with `kotlin-multiplatform` in one subproject and AGP in another
2. Use Gradle 8.8+ and any Kotlin version in 2.1.x–2.3.x
3. Run any iOS compilation task on macOS (e.g., `./gradlew :kmp:compileKotlinIosSimulatorArm64`)
4. Expected: Kotlin iOS sources compile
5. Actual: Build fails at configuration with classloader mismatch error

## Root Cause

`UsesKotlinNativeBundleBuildService.kotlinNativeBundleBuildService` is declared as
`Property<KotlinNativeBundleBuildService>` and annotated `@get:Internal`. The correct Gradle
idiom for build service properties is `@get:ServiceReference` (available since Gradle 7.4),
which allows Gradle to handle the injection without classloader validation. Alternatively,
the type could be widened to `Property<Any>` as GraalVM did in their native-build-tools
([PR #80](https://github.com/graalvm/native-build-tools/pull/80)) for the same class of bug.

**No Kotlin version contains a fix.** Research confirmed 2.1.x, 2.2.x, and 2.3.x all ship
the broken pattern. The fix must come from JetBrains upstream.

## Files Likely Affected (upstream, not in this repo)

- `kotlin/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/targets/native/toolchain/KotlinNativeBundleBuildService.kt` — declares `UsesKotlinNativeBundleBuildService` interface and wires the service

## Fix Approach

**Upstream fix (requires JetBrains action):**

Option A — use `@ServiceReference` annotation (Gradle-idiomatic):
```kotlin
interface UsesKotlinNativeBundleBuildService : Task {
    @get:ServiceReference  // was @get:Internal
    val kotlinNativeBundleBuildService: Property<KotlinNativeBundleBuildService>
}
```

Option B — widen property type to avoid classloader check (GraalVM pattern):
```kotlin
@get:Internal
val kotlinNativeBundleBuildService: Property<Any>
```

**Workaround in this repo (already applied):**
- `ci-ios.yml` uses `continue-on-error: true` on the iOS job
- iOS CI runs `compileCommonMainKotlinMetadata` (pure common metadata, no iOS-target
  association) instead of `compileKotlinIosSimulatorArm64`
- See `.github/workflows/ci-ios.yml` for current state

**To file upstream:** Create a YouTrack issue titled:
> "KGP: UsesKotlinNativeBundleBuildService should use @ServiceReference or Property<Any>
> to avoid Gradle #17559 classloader mismatch in multi-project builds with mixed plugin sets"

## Verification

When the upstream fix is released:
1. Remove `continue-on-error: true` from `ci-ios.yml`
2. Restore `./gradlew :kmp:compileKotlinIosSimulatorArm64` in the iOS CI step
3. Confirm the iOS CI job passes on macOS without the build service property error

## Related

- `.github/workflows/ci-ios.yml` — current workaround
- `gradle.properties` — `kotlin.native.toolchain.enabled=false` (no effect on root cause,
  kept as documentation)
- [Gradle issue #17559](https://github.com/gradle/gradle/issues/17559)
- [GraalVM native-build-tools PR #80](https://github.com/graalvm/native-build-tools/pull/80)
- [Gradle issue #30927](https://github.com/gradle/gradle/issues/30927) — Gradle's own validator
  should suggest `@ServiceReference` for un-annotated build service properties
