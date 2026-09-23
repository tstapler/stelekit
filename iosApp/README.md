# iosApp — SteleKit iOS scaffold (Epic 5: iOS on-device provider)

This directory is the repo's **first-ever iOS Xcode project scaffolding**
(`project_plans/llm-service/implementation/plan.md`, Epic 5, Story 5.1). Before this, zero
`.xcodeproj`/`.xcworkspace`/`Podfile`/`iosApp/`/`.swift` files existed anywhere in the repo.

**Authored entirely in a Linux sandbox with no Xcode/macOS/iOS SDK access.** Nothing in this
directory has been opened in Xcode, built, or run. See "What could not be verified" below
before relying on any of it.

## What's here

- `iosApp.xcodeproj/` — one Xcode project, three targets:
  - **iosApp** (app) — Story 5.1's bare scaffold. Currently just a SwiftUI placeholder screen
    (`ContentView.swift`). Embeds `PingShim.framework` and `FoundationModelsShim.framework`.
  - **PingShim** (framework) — Story 5.2's cinterop smoke-test shim. Zero `FoundationModels`
    dependency; exposes a single `ping() -> Int32` that should return `42`. Exists to prove the
    Kotlin↔cinterop↔Swift bridge mechanism itself works in this codebase, decoupled from the
    separately-risky `FoundationModels` API surface.
  - **FoundationModelsShim** (framework) — Story 5.3's real shim, wrapping Apple's
    `FoundationModels` framework (iOS 26+/macOS 26+, Apple Intelligence's on-device model)
    behind an `@objc`-compatible completion-handler API.
- `PingShim/PingShim.swift`, `FoundationModelsShim/FoundationModelsShim.swift` — the shim
  sources. See file-level doc comments for exact manual-verification steps/gates.
- `kmp/src/nativeInterop/cinterop/PingShim.def`,
  `kmp/src/nativeInterop/cinterop/FoundationModelsShim.def` — the Kotlin/Native cinterop `.def`
  files (Stories 5.2/5.4), wired into `kmp/build.gradle.kts`'s iOS target `cinterops` blocks.

## Why manual verification, not CI (corrected framing)

Per `plan.md`'s Epic 5 "Corrected CI framing": `ci-ios.yml` DOES run on `macos-latest`, but
`compileKotlinIos*` is blocked today by two independent, already-documented upstream issues:

1. **Gradle issue #17559** — a `KotlinNativeBundleBuildService` classloader mismatch in
   multi-project builds with AGP.
2. **Kotlin 2.3.x's K2 compiler cannot read Compose Multiplatform 1.7.x klibs** (compiled with
   Kotlin 2.1.x).

`ci-ios.yml` currently works around both by validating `commonMain` via `compileKotlinJvm` as a
proxy — it does **not** build or run the actual iOS/cinterop compile path. Until those blockers
clear upstream, everything in this directory (and the cinterop wiring in
`kmp/build.gradle.kts`) can only be verified by a contributor on macOS with Xcode.

## Manual build/run workflow (what a contributor with Xcode must do)

There is no automated pipeline wiring these steps together yet — this is Task 5.1b's documented
manual workflow.

### 1. Build the shim frameworks (must happen BEFORE Kotlin/Native cinterop compilation)

The `.def` files bind against each shim's Clang module (`modules = PingShim` /
`modules = FoundationModelsShim` — see the `.def` files), which only exists once Xcode has built
each framework target and generated its module map + `-Swift.h` interface header. Build them
into the fixed path `kmp/build.gradle.kts`'s cinterop `compilerOpts` expect
(`iosApp/build/DerivedData/Build/Products/Debug-<sdk>`):

```bash
# Simulator (arm64 Apple Silicon Mac, or x86_64 — same SDK suffix "iphonesimulator"):
xcodebuild -project iosApp/iosApp.xcodeproj -scheme PingShim \
  -sdk iphonesimulator -configuration Debug \
  -derivedDataPath iosApp/build/DerivedData build

xcodebuild -project iosApp/iosApp.xcodeproj -scheme FoundationModelsShim \
  -sdk iphonesimulator -configuration Debug \
  -derivedDataPath iosApp/build/DerivedData build

# Physical device (arm64):
xcodebuild -project iosApp/iosApp.xcodeproj -scheme PingShim \
  -sdk iphoneos -configuration Debug \
  -derivedDataPath iosApp/build/DerivedData build

xcodebuild -project iosApp/iosApp.xcodeproj -scheme FoundationModelsShim \
  -sdk iphoneos -configuration Debug \
  -derivedDataPath iosApp/build/DerivedData build
```

(Each `xcodebuild -scheme <target>` invocation requires that target to have a shared scheme —
if Xcode didn't autocreate one when you first open the project, create it via Product → Scheme →
Manage Schemes → check "Shared" for `PingShim` and `FoundationModelsShim`.)

### 2. Build the Kotlin/Native framework + cinterop bindings

```bash
./gradlew :kmp:linkDebugFrameworkIosSimulatorArm64   # or IosArm64 for a physical device
```

This is the step currently blocked by Gradle #17559 / the K2-klib issue above — expect it to
fail until those upstream issues clear, independent of whether the cinterop wiring itself is
correct. If/when it does run, it produces `kmp/build/bin/iosSimulatorArm64/debugFramework/kmp.framework`
and (as a side effect of the `cinterops` block in `kmp/build.gradle.kts`) generates Kotlin
bindings for `PingShim`/`FoundationModelsShim`.

### 3. Open and run the Xcode scaffold

```bash
open iosApp/iosApp.xcodeproj
```

Select the `iosApp` scheme, pick a simulator or your signed device (Signing & Capabilities →
set your own Team, since `DEVELOPMENT_TEAM` is intentionally left blank in this scaffold), and
Run. This is Task 5.1c's gate: confirm the placeholder screen (`ContentView.swift`) appears.

`kmp.framework` (step 2) is **not yet wired into the `iosApp` target's Embed Frameworks phase**
— only `PingShim.framework`/`FoundationModelsShim.framework` are, since they're same-project
target dependencies Xcode resolves automatically. Embedding the externally-built
`kmp.framework` requires either manually adding it as a file reference in Xcode (Add Files →
select the built `.framework`) or a Run Script build phase that copies it in — left as a manual
step for whoever picks this up next, since automating it depends on the Gradle output path
actually being reachable (blocked per above).

## Manual verification gates (cannot run in CI — see `validation.md`)

| Gate | What to verify | Status |
|---|---|---|
| Task 5.1c | Bare Xcode project builds and shows the placeholder screen | **Not performed** — no macOS/Xcode in this environment |
| Task 5.2c | `PingShim().ping()` cinterop binding, called from Kotlin, returns exactly `42` — **hard gate, do not proceed to real FoundationModels work until this passes** | **Not performed** |
| Task 5.3c | `FoundationModelsShim.checkAvailability`/`format` exercised on macOS 26+/Apple-Intelligence-eligible hardware for at least one `.available` and one `.unavailable` case | **Not performed** |
| Task 5.4c | Real `.def`/cinterop binding compiles and generates recognizable Kotlin signatures for `FoundationModelsShim`'s two methods | **Not performed** — see `kmp/build.gradle.kts` compile attempt log noted in the epic's implementation summary |
| Task 5.5e | Full Kotlin→cinterop→Swift shim→FoundationModels path exercised end-to-end | **Not performed** |

Record actual results (Xcode version, macOS version, device/simulator, observed values) directly
in the doc comments of `PingShim.swift` / `FoundationModelsShim.swift` and in this table once
performed, per Story 5.2/5.3's explicit "document the verification steps taken" requirement.

## Known unverified assumptions a reviewer should double-check

- **Generated Kotlin binding method names.** The `.def` files use `modules = <ShimName>`
  (Clang module import), and each shim method has an explicit `@objc(...)` selector to pin the
  Objective-C name deterministically (e.g. `@objc(formatWithTranscript:systemPrompt:completion:)`).
  Kotlin/Native's Obj-C→Kotlin import is expected to produce
  `formatWithTranscript(transcript:systemPrompt:completion:)`-shaped Kotlin function signatures,
  but the exact generated name/parameter-label shape was not confirmed by running cinterop (see
  `IosOnDeviceLlmFormatterProvider.kt`/`IosOnDeviceLlmProvider.kt` doc comments for where to fix
  this if the real generated signature differs).
- **`NSInteger`→Kotlin mapping.** Swift `Int` bridges to Objective-C `NSInteger`, which
  Kotlin/Native cinterop typically imports as `kotlin.Long`, not `kotlin.Int` — the Kotlin-side
  code calls `.toInt()` on the codes it receives from the shim. Verify this is actually what
  cinterop generates once it runs.
- **`FoundationModels` API surface.** `SystemLanguageModel`, `LanguageModelSession`,
  `LanguageModelSession.GenerationError` case names, etc. are written to the best-effort
  documented shape as of authoring time (see `FoundationModelsShim.swift`'s file-level comment)
  and were never compiled against the real SDK.
- **Framework build output path.** The `Debug-iphonesimulator` / `Debug-iphoneos` DerivedData
  path convention documented above was not confirmed against a real `xcodebuild` invocation.
