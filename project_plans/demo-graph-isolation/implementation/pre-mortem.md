# Pre-Mortem: Demo Graph Isolation

Feature: Demo Graph Isolation for SteleKit
Date: 2026-07-05
Status: Phase 4 validation — imagining the feature shipped and failed

---

## Failure Mode 1

**Name**: WASM `localStorage` old-format demo entry bypasses `isDemo` stripping on upgrade

**Priority**: P1 — data corruption / crash on upgrade for existing WASM users who previously loaded the demo

**Scenario**:
1. A user ran the current WASM build and clicked "Load Demo Graph".
2. The old code registered a `GraphInfo` with `path = "/demo"` and no `isDemo` field.
3. The registry JSON in `localStorage` contains `{"path": "/demo", "id": "...", ...}` — no `"isDemo"` key.
4. The new release ships with the `isDemo` field added to `GraphInfo` with `default = false`.
5. On the next load, `json.decodeFromString<GraphRegistry>(registryJson)` deserializes the old entry with `isDemo = false` (the default) because `ignoreUnknownKeys = true` — the field is absent, not present-and-false.
6. `loadRegistry()` strips entries where `isDemo == true`. The old entry has `isDemo = false` → it survives.
7. `activeGraphId` still points to the old demo graph ID.
8. `GraphManager.init` calls `switchGraph(oldDemoId)`. Task 2.1.3a checks `graphInfo.isDemo` → `false` → selects `SQLDELIGHT` backend.
9. `DriverFactory` tries to open (or create) a SQLite database at a path derived from hashing `"/demo"`. The path does not exist and is not writable in a browser context.
10. DB init fails; `_activeRepositorySet` stays `null`; `migrationReady` becomes `true` (via `finally`); the loading overlay spins forever.

**Root cause**: `loadRegistry()` stripping logic only removes entries where `isDemo == true`. The sentinel value for a "was-demo" entry from the pre-`isDemo` era is the absence of the field, which deserializes as `false`. The plan never adds a path-based fallback filter (`path == "/demo"`).

**Detection**: Crash reports from existing WASM users showing infinite loading after upgrade. Sentry or OTel would show `switchGraph` → `RepositoryFactoryImpl` initialization failure with a path like `/demo`. Zero reproduction on new installs.

**Mitigation**: In `loadRegistry()`, after deserializing, add a secondary filter alongside the `isDemo` check:
```kotlin
val stripped = registry.graphs.filter { !it.isDemo && it.path != "/demo" }
```
Also add a `DemoGraphRegistryMigrationTest` that deserializes a legacy registry JSON (no `isDemo` field, `path = "/demo"`) and asserts the resulting graph list is empty.

---

## Failure Mode 2

**Name**: `afterEvaluate` dependency only wires `compileKotlinJvm`; Android and iOS compilations silently miss `generateDemoFileSystem`

**Priority**: P1 — Android and iOS CI builds fail with "Unresolved reference: DemoFileSystem" after Phase 3

**Scenario**:
1. Phase 3 moves `DemoFileSystem.kt` from `wasmJsMain` to `commonMain` so it is available to all platforms.
2. The plan wires `compileKotlinJvm.dependsOn(generateDemoFileSystem)` via `afterEvaluate` in `build.gradle.kts`.
3. `compileKotlinJvm` is the Kotlin/JVM compilation task. It now guarantees the generated file exists before JVM compilation.
4. Android compilation is handled by a different task — `compileDebugKotlin` (or `compileKotlinAndroid` in Kotlin Gradle plugin nomenclature). iOS uses a `compileKotlinIos*` task family.
5. Neither Android nor iOS tasks inherit a dependency on `generateDemoFileSystem`.
6. A developer runs `bazel build //kmp:android_app --config=android` or CI triggers an Android build. Gradle resolves the compilation graph. `compileDebugKotlin` does not depend on `generateDemoFileSystem`. The generated `DemoFileSystem.kt` may be stale or absent (e.g., on a clean checkout or after `./gradlew clean`).
7. Android compilation fails: `error: unresolved reference: DemoFileSystem` inside whatever `commonMain` code instantiates it.
8. The Android and iOS CI jobs fail. The JVM job passes (because `compileKotlinJvm` has the dependency). The mismatch between passing JVM CI and failing Android CI adds confusion.

**Root cause**: `afterEvaluate { tasks.named("compileKotlinJvm").configure { dependsOn(...) } }` wires exactly one task. Moving source to `commonMain` means the generated file participates in every platform's compilation. All platform compilation tasks must declare the dependency.

**Detection**: The Android CI job in `ci.yml` fails on the first run after the Phase 3 merge. The failure message includes "Unresolved reference: DemoFileSystem". JVM CI is green, creating a false sense that the build is healthy.

**Mitigation**: Wire `generateDemoFileSystem` as a dependency of the `compileCommonMainKotlinMetadata` task (which covers all platform-shared compilation) rather than the JVM-only task:
```kotlin
afterEvaluate {
    tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }
         .configureEach { dependsOn(generateDemoFileSystem) }
}
```
Or, prefer a non-`afterEvaluate` approach by directly hooking into the `KotlinCompilation` API:
```kotlin
kotlin.sourceSets.commonMain.resources.srcDir(generateDemoFileSystem)
```
Add an explicit Android CI smoke test that compiles `commonMain` on a clean workspace.

---

## Failure Mode 3

**Name**: `graphWriter` and `graphLoader` constructed with platform `fileSystem`, not `effectiveFileSystem`; demo edits write to the real filesystem

**Priority**: P1 — demo block edits attempt real filesystem writes; on some platforms this creates garbage files or silently corrupts state

**Scenario**:
1. Phase 4 adds `effectiveFileSystem = remember(activeGraphInfo?.isDemo) { if (isDemo) DemoFileSystem() else fileSystem }` inside `GraphContent`.
2. The developer correctly places this after the `activeGraphInfo` capture (line 473 of `App.kt`).
3. However, `graphLoader` is constructed at line 551 of the existing code:
   ```kotlin
   val graphLoader = remember(fileSystem, repos, sidecarManager) {
       repos.createGraphLoader(fileSystem, sidecarManager = sidecarManager)
   }
   ```
   and `graphWriter` at line 565:
   ```kotlin
   val graphWriter = remember(fileSystem, repos, graphLoader, sidecarManager) {
       GraphWriter(fileSystem, repos.writeActor, ...)
   }
   ```
   Both use `fileSystem` — the real platform `FileSystem` received as a parameter — not `effectiveFileSystem`.
4. The `remember` key for both blocks is `fileSystem` (the real one). For demo, `fileSystem` does not change between recompositions, so the blocks never re-execute with `effectiveFileSystem`.
5. A user in the demo session edits a block. `GraphWriter.saveBlock()` calls `fileSystem.writeFile("/demo/pages/SomePage.md", content)` against the real platform filesystem.
6. On Linux/macOS, the write succeeds if `/demo/pages/` happens to exist (unlikely) or silently fails, leaving the IN_MEMORY repository in a state that doesn't match the demo filesystem. On Android, the write fails at the SAF layer. On Windows, the write targets `C:\demo\pages\...`.
7. Additionally, `LocalFileSystem provides fileSystem` at line 433-434 of `GraphContent` is set before `effectiveFileSystem` is computed and is never updated. Any child composable calling `LocalFileSystem.current` receives the real filesystem.

**Root cause**: The plan describes adding `effectiveFileSystem` as a local variable but does not enumerate the three places in `GraphContent` that must be changed: (a) `LocalFileSystem provides fileSystem` → `LocalFileSystem provides effectiveFileSystem`, (b) `remember(fileSystem, ...) { repos.createGraphLoader(fileSystem, ...) }`, (c) `remember(fileSystem, ...) { GraphWriter(fileSystem, ...) }`. Missing any one breaks demo isolation.

**Detection**: In a demo session, editing any block and then inspecting the process's file writes (e.g., via `strace` or macOS `fs_usage`) shows write attempts to real paths beginning with `/demo/`. Alternatively, the `DemoGraphPersistenceTest` required by Phase 7 would catch this if it asserts that `GraphWriter` receives a `DemoFileSystem` instance, but the plan's test cases focus on persistence behavior, not the filesystem type passed to the writer.

**Mitigation**: Replace all three occurrences in `GraphContent`. Change the `CompositionLocalProvider` to use `effectiveFileSystem`. Change both `remember` blocks to take `effectiveFileSystem` as their key and pass it as the argument. Add a test that constructs `GraphContent` in demo mode and asserts via a spy/fake filesystem that no write call is made to the real platform filesystem.

---

## Failure Mode 4

**Name**: `DemoFileSystemSyncTest` path hardcoded to `wasmJsMain`; CI fails immediately after Phase 3 moves the file to `commonMain`

**Priority**: P2 — CI test failure blocks PR merge; regression detection for demo content is silently disabled

**Scenario**:
1. Phase 3 changes the generator output destination from `wasmJsMain` to `commonMain`. The generated file moves from `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/DemoFileSystem.kt` to `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/DemoFileSystem.kt`.
2. Phase 3 says "Update `DemoFileSystemSyncTest.kt` path reference." The developer updates the generator task path but overlooks the test.
3. `DemoFileSystemSyncTest.generatedFileSource` walks up the directory tree looking for:
   ```kotlin
   dir.resolve("src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/DemoFileSystem.kt")
   ```
   (line 35 of the test). This path no longer exists.
4. The lazy val reaches the root of the walk and calls `fail(...)` with "Generated DemoFileSystem.kt not found".
5. Every test in `DemoFileSystemSyncTest` fails with the `fail` exception before reaching any assertion.
6. The `jvmTest` CI job fails. Because this is a test failure (not a compile error), the error message is misleading: it says the generator hasn't been run, not that the path is wrong.

**Root cause**: The test file contains a hardcoded relative path fragment `"src/wasmJsMain/..."` that is not covered by Phase 3's explicit step to "Update `DemoFileSystemSyncTest.kt` path reference." The step is listed as a task but has no associated test or lint rule to verify the update was actually made.

**Detection**: `bazel test //kmp:jvm_tests` or `./gradlew jvmTest` fails immediately after the Phase 3 commit lands. The failure message `"Generated DemoFileSystem.kt not found — run :kmp:generateDemoFileSystem first"` sends the investigator toward the generator task rather than the test's hardcoded path string.

**Mitigation**: Change `DemoFileSystemSyncTest` to search both candidate locations during Phase 3, rather than hard-coding one:
```kotlin
val candidates = listOf(
    "src/commonMain/kotlin/dev/stapler/stelekit/platform/DemoFileSystem.kt",
    "src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/DemoFileSystem.kt",
)
```
Alternatively, make the test derive the path from a system property set by the Gradle task rather than walking the directory tree.

---

## Failure Mode 5

**Name**: `setOnboardingCompleted(true)` fires before `switchGraph(DEMO_GRAPH_ID)` within the same coroutine; an Android Activity recreation between these two calls leaves the session stuck on a loading overlay

**Priority**: P2 — demo user sees infinite loading spinner after rotating the device or switching apps during the onboarding → demo transition; requires a force-quit to recover

**Scenario**:
1. The plan wires demo selection as:
   ```kotlin
   scope.launch {
       graphManager.addDemoGraph()
       viewModel.setOnboardingCompleted(true)   // ← persisted immediately to platformSettings
       graphManager.switchGraph(DEMO_GRAPH_ID) // ← in-memory state change + IO coroutine
   }
   ```
2. `addDemoGraph()` completes (fast, in-memory).
3. `viewModel.setOnboardingCompleted(true)` is called. This writes `onboardingCompleted=true` to `platformSettings`. Compose schedules a recomposition.
4. Before `graphManager.switchGraph(DEMO_GRAPH_ID)` runs, the user rotates the device (or Android kills the foreground Activity to reclaim memory).
5. The Android Activity is recreated. All `remember { }` state — including `GraphManager` — is reconstructed from scratch (no `rememberSaveable` or retained ViewModel is used for `GraphManager`).
6. `GraphManager.init` runs `loadRegistry()`. The persisted registry has no demo entry (`addDemoGraph()` intentionally does not call `saveRegistry()`). `graphs` is empty or contains only the default path. `activeGraphId` is null.
7. Task 2.1.2a strips demo entries and resets `onboardingCompleted` if `graphs.isEmpty()`. If the user previously had a real graph registered, `graphs` is NOT empty → `onboardingCompleted` is NOT reset. `platformSettings.onboardingCompleted = true` persists.
8. `GraphContent` is shown (condition: repos non-null once `LaunchedEffect(currentGraphPath)` loads the real graph). `Onboarding` is NOT shown because `appState.onboardingCompleted == true`.
9. If the user had no real graph (fresh install), `graphs` IS empty → Task 2.1.2a resets `onboardingCompleted` → Onboarding shown → SAFE. But for a returning user who had previously added a real graph before trying the demo, the app shows the main UI for a real graph that was never explicitly selected. This is confusing, but not an infinite spinner — the real graph does load.
10. The edge case that produces an infinite spinner: if `switchGraph(DEMO_GRAPH_ID)` was half-complete in the graphScope IO coroutine when the Activity was recreated. On Activity recreation, the graphScope is cancelled. `_pendingMigration` (the `CompletableDeferred`) from the half-initialized demo switch is also lost. The new `GraphManager.init` creates a fresh `_pendingMigration = CompletableDeferred().also { it.complete(Unit) }`. But `LaunchedEffect(activeGraphId)` re-runs with the new `activeGraphId`. If `activeGraphId` is null, `migrationReady` starts as `false` and the `LaunchedEffect(null)` awaits `_pendingMigration` — which is already completed → `migrationReady = true`. `repos` may be null (no active graph). `if (repos == null || !migrationReady)` → `repos == null` → shows `LoadingOverlay`. Then `LaunchedEffect(currentGraphPath)` fires and loads the real graph if `currentGraphPath` is non-empty. The spinner resolves.

The specific stuck case: `currentGraphPath.isEmpty()` AND `onboardingCompleted = true` AND `repos = null`. The app shows `LoadingOverlay` with no pending work to resolve it. This requires a fresh Android install (no `graphPath`, no previously registered real graph) combined with the activity-recreation scenario during the demo transition. Uncommon but reproducible on a low-memory device during demo onboarding.

**Root cause**: `setOnboardingCompleted(true)` is a persistent, immediately-visible side effect placed before `switchGraph()` in the sequence. Task 2.1.2a resets `onboardingCompleted` only when `graphs.isEmpty()` after stripping, which is correct for the completion path but cannot recover the mid-sequence crash case where `onboardingCompleted=true` but no graph was loaded into the session.

**Detection**: Android crash reports showing `LoadingOverlay` as the final UI state with no subsequent navigation. Reproducible by enabling "Don't keep activities" in Android Developer Options, tapping "Load Demo Graph", and rotating the device immediately.

**Mitigation**: Reorder the sequence so `switchGraph(DEMO_GRAPH_ID)` runs BEFORE `setOnboardingCompleted(true)`. `switchGraph()` is synchronous (the IO coroutine is fire-and-forget), so it completes instantly and the active graph ID is set before the persisted flag changes:
```kotlin
scope.launch {
    graphManager.addDemoGraph()
    graphManager.switchGraph(DEMO_GRAPH_ID)   // ← set active graph first
    viewModel.setOnboardingCompleted(true)     // ← then hide onboarding
}
```
Also add a guard in the `LaunchedEffect(currentGraphPath)` path: if `onboardingCompleted == true` and `currentGraphPath.isEmpty()` and `repos == null`, show the `Onboarding` screen rather than `LoadingOverlay`.
