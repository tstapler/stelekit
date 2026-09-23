# Feature Surface Research: Bazel Full CI Migration

**Date**: 2026-06-24
**Scope**: Detekt, Roborazzi (jvmTest + androidUnitTest), Android emulator smoke tests

---

## 1. Detekt Static Analysis

### Gradle invocation
```
./gradlew :kmp:detekt
```

### Source sets analyzed
Configured in `kmp/build.gradle.kts` (lines 985–997):
- `src/commonMain/kotlin`
- `src/jvmMain/kotlin`
- `src/androidMain/kotlin`
- `src/iosMain/kotlin`

**Excluded from analysis:** generated SQLDelight code (excluded via per-rule `excludes` in detekt.yml), `buildSrc/**`, test source sets (except where specific rules explicitly include them).

### Configuration
- Config file: `kmp/config/detekt/detekt.yml` (9.6KB)
- Baseline file: `kmp/config/detekt/baseline.xml` (53.8KB, 368 lines — ~350+ suppressed baseline issues)
- `buildUponDefaultConfig = true` — defaults apply for everything not overridden
- `jvmTarget = 21`
- Reports: HTML + SARIF enabled; TXT/XML/MD disabled

### Plugin dependencies (must be on classpath at analysis time)
1. **Custom SteleKit rules** — loaded from `buildSrc/build/libs/buildSrc.jar` (compiled from `buildSrc/src/main/kotlin/dev/stapler/detekt/`). 14 custom rules:
   - `InMemoryPagination` — forbids unbounded in-memory repository pagination
   - `RegexInLambda` — flags regex instantiation in lambdas (performance)
   - `MissingDirectRepositoryWrite` — enforces `@DirectSqlWrite` annotation gating
   - `RepositoryWriteCallSite` — enforces actor-gated write call sites
   - `ClassLevelDirectRepositoryWriteOptIn` — restricts class-level `@OptIn(DirectSqlWrite::class)`
   - `SaveBlockWithCopy` — enforces copy-on-write for block saves
   - `SwallowedCancellationException` — prevents swallowed `CancellationException`
   - `RememberKeyForConstructorArgs` — `remember {}` with constructor args must have a key
   - `NoRunBlockingInLifecycle` — bans `runBlocking` in composable lifecycle
   - `UnthemedTextInBackgroundContainer` — UI accessibility rule
   - `MustUseTypedLazyItems` — enforces typed lazy list items (excludes `TypedLazyItems.kt`, test files)
   - `JvmOnlyApiInCommonMain` — forbids JVM-only APIs leaking into commonMain (excludes platform-specific source sets)
   - `IndexWithoutAnalyze` — flags SQLite index creation without ANALYZE
   - `ActorWriteInLoop` — forbids actor writes inside loops
   - `MissingHelpPageAnnotation` — every `Screen` must have a help page annotation (excludes buildSrc and test files)

2. **Compose rules** — `io.nlopez.compose.rules:detekt:0.4.27`. ~25 Compose-specific rules enabled (modifier ordering, remember, composable naming, CompositionLocal allowlist, etc.). `ViewModelForwarding`, `ViewModelInjection`, `Material2`, `UnstableCollections`, `PreviewNaming` are disabled.

3. **Core Detekt rule sets** enabled with project-specific overrides:
   - `coroutines`: GlobalCoroutineUsage, RedundantSuspendModifier, SuspendFunSwallowedCancellation, etc. (InjectDispatcher disabled)
   - `performance`: ArrayPrimitive, CouldBeSequence (threshold=3), ForEachOnRange, SpreadOperator
   - `potential-bugs`: AvoidReferentialEquality (String), DoubleMutabilityForCollection, EqualsWithHashCodeExist, etc.
   - `naming`: FunctionNaming (allows PascalCase for @Composable), InvalidPackageDeclaration (root=`dev.stapler.stelekit`)
   - `style`: ForbiddenImport (`Dispatchers.IO`, `Dispatchers.Main`), ForbiddenMethodCall (`println`, `print`), MayBeConst, ModifierOrder, UnusedParameter
   - `complexity`: NestedBlockDepth (threshold=6); CyclomaticComplexMethod/LongMethod/LargeClass disabled
   - `empty-blocks`: EmptyCatchBlock (allows `_/ignored/expected/e`)
   - `exceptions`: PrintStackTrace, RethrowCaughtException, ReturnFromFinally, ThrowingNewInstanceOfSameException
   - `comments`: **entirely disabled**

### CI artifacts uploaded
- `kmp/build/reports/detekt/` — HTML report + SARIF file
- Retention: 7 days

### Key edge cases for Bazel target

1. **Custom rules require pre-compiled buildSrc JAR.** The buildSrc JAR must exist before detekt runs. In Gradle, `buildSrc` compiles automatically. In Bazel, the JAR must be an explicit input dep to the detekt genrule/rule.

2. **Type-aware vs. type-unaware analysis.** The current Gradle setup does NOT explicitly configure type resolution (`detekt { }` block has no `classpath` / `config.with.type.resolution`). Custom rules that use semantic analysis (e.g., `JvmOnlyApiInCommonMain`, `MissingDirectRepositoryWrite`) need the compilation classpath. Verify each rule's `requiresTypeResolution` before assuming it's safe to run type-unaware.

3. **Baseline file must be provided.** The baseline.xml suppresses ~350 pre-existing issues. Without it, Detekt would fail on every run. The baseline path is relative to the module root — the Bazel wrapper must map it correctly.

4. **Incremental analysis.** Gradle Detekt has incremental analysis support. Bazel achieves incremental correctness via input hashing — the equivalent is correct `srcs` and `deps` declarations so only affected files trigger re-analysis.

5. **Source set exclusions.** Test files (`**/*Test.kt`, `**/test/**`, `**/*Spec.kt`) and generated code are excluded via detekt.yml per-rule `excludes`. The Bazel target's `srcs` must mirror the four source directories listed above — it must NOT include `jvmTest`, `androidUnitTest`, `commonTest`, or `businessTest`.

6. **SARIF output.** CI uploads SARIF to GitHub Security tab. The Bazel target must produce SARIF alongside HTML or CI must extract it from the Bazel output directory.

---

## 2. Roborazzi Screenshot Tests

Roborazzi is used in two distinct source sets with different mechanisms:

### 2a. JVM screenshot tests (`jvmTest`)

**Gradle invocation:** `./gradlew :kmp:jvmTest -Proborazzi.test.record=true` (record mode); standard `jvmTest` run does verify mode implicitly when baselines exist.

**Files (7 test classes):**
- `ui/VoiceCaptureButtonScreenshotTest.kt` — VoiceCaptureButton states (idle/recording/etc., light/dark)
- `ui/BottomNavScreenshotTest.kt` — bottom navigation bar states
- `ui/components/TableBlockScreenshotTest.kt` — table block rendering
- `ui/screenshots/DesktopScreenshotTest.kt` — full desktop layout (journals, light/dark)
- `ui/screenshots/MobileScreenshotTest.kt` — mobile layout (journals, light/dark)
- `ui/screenshots/DemoGraphScreenshotTest.kt` — demo graph screen
- `ui/screenshots/JournalsViewScreenshotTest.kt` — journals view

**Framework:** `io.github.takahirom.roborazzi:roborazzi-compose-desktop:1.59.0`

**Mechanism:** Uses `captureRoboImage("build/outputs/roborazzi/<name>.png")` — output path is relative to module working directory. Tests use `createComposeRule()` from Compose UI testing.

**Record vs. verify mode:** Controlled by Gradle property `-Proborazzi.test.record=true`. No dedicated `recordRoborazziJvm` task exists — record mode is a property flag on `jvmTest`.

**Golden image location:** Images are written to `build/outputs/roborazzi/` relative to the `kmp/` module. These are **NOT committed** to the repository — the CI CI comment in `ci.yml` says "Goldens are intentionally not committed — rendering differs between machines."

**JVM execution requirements:**
- `java.awt.headless=false` — requires display (Xvfb in CI)
- `LIBGL_ALWAYS_SOFTWARE=1` and `GALLIUM_DRIVER` env vars for software rendering
- `maxParallelForks = availableProcessors/2` — screenshot tests must serialize (AWT contention)
- `jvmArgs("-Djdk.attach.allowAttachSelf=true", "--add-opens=java.base/java.lang=ALL-UNNAMED")` (BlockHound)
- `configureDisplayEnv()` — project helper that sets `DISPLAY` from environment

**Exclusion pattern in `jvmTestFast`:** `exclude("**/*Screenshot*", "**/*Roborazzi*", "**/screenshots/**")` — this is the canonical pattern for identifying screenshot test classes.

### 2b. Android Roborazzi tests (`androidUnitTest`)

**Gradle invocation (CI):**
```
./gradlew :kmp:testDebugUnitTest :kmp:recordRoborazziDebug :androidApp:assembleDebug
```
CI runs `recordRoborazziDebug` — always records, never verifies in CI (goldens not committed).

**Files (1 test class):**
- `androidUnitTest/kotlin/.../ui/UiStateScreenshotTest.kt` — 4 UI states (LoadingOverlay, PermissionRecoveryScreen x2, + one more state)

**Framework:** `io.github.takahirom.roborazzi:roborazzi:1.59.0` + `roborazzi-compose:1.59.0`

**Mechanism:** Uses `@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [29], qualifiers = "w411dp-h891dp-xhdpi-keyshidden-nonav")`. Calls `captureRoboImage()` (no explicit path — uses default Roborazzi convention: `kmp/src/androidUnitTest/snapshots/images/<ClassName>_<testMethodName>.png`).

**Record vs. verify mode:**
- `recordRoborazziDebug` task — generated by Roborazzi Gradle plugin, writes new baselines
- `verifyRoborazziDebug` task — compares against committed baselines
- CI currently uses **record only**; switching to verify requires committing baselines from a reference run

**Golden image location:** `kmp/src/androidUnitTest/snapshots/images/` — **not currently committed** (CI comment: "Goldens are intentionally not committed — rendering differs between machines")

**CI artifact upload:**
- Path: `kmp/src/androidUnitTest/snapshots/` — retained 14 days
- The artifact contains generated screenshots from the record run

### Edge cases for Bazel Roborazzi targets

1. **No native Bazel rules exist for Roborazzi.** The Roborazzi Gradle plugin generates `recordRoborazziDebug`/`verifyRoborazziDebug` tasks via convention. Bazel must use a `genrule` wrapping Gradle (or a custom Kotlin test rule) — the genrule approach is the realistic path.

2. **Golden baselines are not committed.** CI always runs in record mode. The Bazel version can match this behavior (always record, upload as artifact). If verify mode is desired in the future, baselines must first be committed.

3. **JVM screenshot tests require display.** `java.awt.headless=false` means these tests need Xvfb or a virtual framebuffer. Bazel's default linux-sandbox blocks display access. The Bazel target must either:
   - Use `--sandbox_add_mount_pair=/tmp/.X11-unix` or equivalent
   - Or wrap with Xvfb via a `sh_test` wrapper script
   - Or run with `--strategy=local` for screenshot tests

4. **Image path stability.** JVM tests use `captureRoboImage("build/outputs/roborazzi/<name>.png")` — path is relative to CWD when the test runs. In Bazel sandbox, CWD is the execroot, not the module dir. The Bazel wrapper must either set CWD or remap paths.

5. **Android Roborazzi tests use Robolectric.** `@Config(sdk = [29])` pins the Robolectric SDK. Bazel needs the Robolectric runtime JARs on the test classpath.

6. **Diff threshold.** Roborazzi default diff threshold is pixel-exact. No project-specific threshold is configured — standard default applies.

7. **Software rendering.** `LIBGL_ALWAYS_SOFTWARE=1` must be set in the sandbox environment for Compose Desktop rendering to work without a GPU.

---

## 3. Android Emulator Smoke Tests

### Gradle invocation
```
./gradlew :androidApp:connectedDebugAndroidTest
```
Run via `reactivecircus/android-emulator-runner@v2` GitHub Action.

### Emulator configuration (from `ci.yml` lines 193–201)
- API level: **29** (Android 10)
- Target: `default` (not google_apis)
- Arch: `x86_64`
- KVM hardware acceleration required (CI enables it via udev rule)
- Options: `-no-snapshot-save -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -memory 2048`
- Animations disabled via `disable-animations: true`
- Needs: `[android]` — waits for APK build to complete

### Instrumentation runner
`androidx.test.runner.AndroidJUnitRunner` (standard; no custom runner)

### SDK levels
- `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`
- Emulator runs API 29 (older than target — tests the minimum supported behavior, not the latest)

### Test classes (4 files in `kmp/src/androidInstrumentedTest/kotlin/`)

**`AppSmokeTest.kt`** — 5 tests covering:
- `coreInitializationDoesNotCrash` — instantiates PlatformSettings, DriverFactory, GraphManager without crashing
- `onboardingWelcomeStepShownFirst` — asserts "Welcome to SteleKit" UI shown
- `nextButtonAdvancesToGraphSelectionStep` — navigation via Compose UI test
- `backButtonReturnsToWelcomeStep` — back navigation
- `completingOnboardingCallsOnComplete` — full onboarding flow (Welcome → Graph Selection → Keymap → Get Started)

Uses `StrictModeRule` — `penaltyDeath` on disk reads/writes/network on main thread.

**`SqliteCapabilityTest.kt`** — 15 tests verifying bundled SQLite capabilities:
- SQLite version ≥ 3.49 (uses Requery bundled SQLite, NOT system SQLite)
- WAL mode active
- FTS5 module available and functional (insert/delete/update triggers)
- Porter tokenizer stemming
- WITHOUT ROWID tables
- WITH RECURSIVE CTEs
- JSON1 functions (`json_extract`, `json_each`)
- PRAGMA optimize
- Full DriverFactory end-to-end (createDriver, FTS5 search after setup, trigger safety)

**`StrictModeRule.kt`** — JUnit4 TestRule (not a test class itself)

**`AndroidGraphBenchmark.kt`** — benchmark class (not part of smoke tests; separate `androidInstrumentedTest` class)

### CI artifacts uploaded
- `androidApp/build/reports/androidTests/connected/` — HTML test report (14-day retention)
- `androidApp/build/outputs/androidTest-results/connected/**/*.xml` — JUnit XML (published via action-junit-report)

### Edge cases for Bazel emulator target

1. **`android_instrumentation_test` is experimental in rules_android 0.7.1.** This is the primary feasibility risk. The rule exists but is not production-ready — timeout handling, AVD lifecycle, and ADB multiplexing are all known gaps.

2. **KVM requirement.** The emulator requires KVM hardware virtualization (`/dev/kvm`). Bazel's linux-sandbox blocks `/dev/kvm` by default. The target must either:
   - Use `--sandbox_add_mount_pair=/dev/kvm`
   - Or run with `--strategy=local` (no sandbox for emulator tests)
   - Or wrap in a shell test that launches the emulator externally

3. **ADB device connectivity.** `connectedDebugAndroidTest` relies on ADB discovering a running emulator. In a Bazel sandbox, the ADB server is not available unless explicitly mounted. The genrule-wrapping-Gradle approach (same as Roborazzi) sidesteps this by running outside the sandbox.

4. **Emulator lifecycle management.** `reactivecircus/android-emulator-runner@v2` handles AVD creation, boot wait, and teardown. A Bazel rule must replicate or delegate this — likely via a shell wrapper script.

5. **Depends on APK artifact.** The CI `android-smoke` job `needs: [android]` — the debug APK from `:androidApp:assembleDebug` must exist before tests run. In Bazel, this is an explicit `data` or `deps` dependency.

6. **`SqliteCapabilityTest` uses bundled Requery SQLite.** The test intentionally bypasses system SQLite to verify the bundled binary. API 29 system SQLite lacks FTS5 — this is by design (test verifies the bundled version works, not the system one).

7. **`StrictMode.penaltyDeath` on main thread.** Any blocking I/O on the Compose main thread will crash the test process, not just fail it. This is intentional — it catches ANR-class bugs. The Bazel emulator runner must handle process crashes (exit code ≠ 0) as test failures.

8. **`AndroidGraphBenchmark.kt`** is co-located in `androidInstrumentedTest/` but is NOT part of the smoke test suite. The Bazel target should exclude it or it will attempt to run benchmarks during CI (slow, wrong environment).

---

## 4. CI Artifact Summary

| Job | Artifacts | Path | Retention |
|-----|-----------|------|-----------|
| Detekt | HTML report, SARIF | `kmp/build/reports/detekt/` | 7 days |
| Roborazzi (Android) | Screenshot PNGs | `kmp/src/androidUnitTest/snapshots/` | 14 days |
| Android APK | debug APK | `androidApp/build/outputs/apk/debug/*.apk` | 7 days |
| Android unit test results | JUnit XML | `**/build/test-results/testDebugUnitTest/**/*.xml` | 7 days |
| Emulator smoke tests | HTML report + JUnit XML | `androidApp/build/reports/androidTests/connected/` + `androidApp/build/outputs/androidTest-results/connected/**/*.xml` | 14 days |

---

## 5. Cross-cutting Constraints

### buildSrc JAR dependency
The Detekt custom rules live in `buildSrc/src/main/kotlin/dev/stapler/detekt/`. The compiled JAR (`buildSrc/build/libs/buildSrc.jar`) must be built before the Detekt analysis target runs. In Bazel, `buildSrc.jar` needs its own `kt_jvm_library` target (or a genrule wrapper) and be declared as a dep.

### Java 21 requirement
All three targets (Detekt, Roborazzi, emulator) require JDK 21 (`jvmTarget = 21` for Detekt; the Compose UI test framework needs Java 21+ runtime features). Bazel toolchain configuration must pin JDK 21.

### Xvfb for JVM screenshot tests
JVM Roborazzi tests require `java.awt.headless=false` with an active display. Bazel CI must provide a virtual framebuffer. The most robust approach is a `sh_test` wrapper that calls `xvfb-run --auto-servernum` before running the Bazel test target.

### No committed screenshot baselines
Both Roborazzi variants run in record mode in CI — no baseline comparison is currently done. The Bazel equivalent simply needs to generate and upload screenshots as artifacts. Verify mode requires a future decision to commit baselines from a reference machine.

### Gradle wrapping as fallback
For Roborazzi and emulator targets where native Bazel rules are infeasible or experimental, the recommended approach is a Bazel `genrule` or `sh_binary` that delegates to `./gradlew <task>`. This preserves Bazel's dependency graph (other targets can depend on this one) while reusing Gradle's existing configuration.
