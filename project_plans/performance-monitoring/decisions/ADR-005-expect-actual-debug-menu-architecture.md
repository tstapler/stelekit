# ADR-005: expect/actual Debug Menu Architecture

**Date**: 2026-04-13
**Status**: Accepted
**Deciders**: Tyler Stapler

---

## Context

The debug menu overlay needs platform-specific UI presentation:
- **Android**: Material3 `ModalBottomSheet` (follows Material Design guidelines for transient overlays on mobile; dismissible with back gesture and swipe).
- **Desktop (JVM)**: Compose Desktop `Dialog` window (modeless or modal Swing-backed window; fits desktop conventions).

Two structural approaches were evaluated:

| Approach | Shared logic | Platform UI | Compile-time safety | Precedent in codebase |
|----------|-------------|-------------|--------------------|-----------------------|
| Single `@Composable` with `if (isAndroid)` runtime branching | Yes | Partial | No (compile-time platform detection requires reflection hacks in KMP) | No |
| `expect`/`actual` `@Composable` function | Yes (state model) | Fully platform-native | Yes (linker error if actual missing) | Yes — `PlatformBackHandler`, `PlatformBottomBar`, `ModifierExtensions` all use this pattern |

The codebase already uses expect/actual for: `PlatformBackHandler.android.kt`, `PlatformBottomBar.android.kt`, `ModifierExtensions.android.kt`, `DynamicColorSupport.android.kt`. The pattern is established and team-familiar.

---

## Decision

Use **`expect`/`actual` `@Composable` functions** for the debug menu overlay and all related UI platform abstractions:

- `expect @Composable fun DebugMenuOverlay(state: DebugMenuState, onStateChange: (DebugMenuState) -> Unit, onExportBugReport: () -> Unit)` in `commonMain`
- `actual @Composable fun DebugMenuOverlay(...)` in `androidMain` — `ModalBottomSheet`
- `actual @Composable fun DebugMenuOverlay(...)` in `jvmMain` — `Dialog`

The shared state model (`DebugMenuState`) and persistence (`DebugFlagRepository`) live in `commonMain` and are consumed identically by both actuals.

---

## Rationale

1. **Existing pattern consistency**: The codebase already has 4 expect/actual composables in the UI layer. Adding a fifth follows established conventions. New developers can reference `PlatformBottomBar.android.kt` as a direct template.

2. **Compile-time completeness guarantee**: If a new target (e.g., iOS) is added, the compiler will refuse to build until `DebugMenuOverlay.ios.kt` is provided. A runtime `if (platform == ANDROID)` check would silently show nothing on iOS.

3. **Platform-native UX**: A `ModalBottomSheet` on Android uses system gestures (swipe to dismiss) and respects window insets automatically. A Compose Desktop `Dialog` can be resized and repositioned — appropriate for a developer tool. A single shared implementation cannot achieve both without substantial platform-branching internally.

4. **Testability**: Each actual can be screenshot-tested independently (Roborazzi for JVM; Compose UI test for Android). The shared `DebugMenuState` business logic can be unit-tested in `commonTest` without any UI.

---

## Debug Gate Strategy

The debug menu must not be accessible in production release builds.

**Android**: The long-press trigger composable is wrapped in:
```kotlin
if (BuildConfig.DEBUG) {
    // long-press gesture registers here
}
```
`BuildConfig.DEBUG` is a compile-time constant set by the Android Gradle Plugin. In release builds, the entire gesture handler is dead code and may be eliminated by R8.

**Desktop (JVM)**: The trigger checks a JVM system property:
```kotlin
if (System.getProperty("dev.stapler.debug") == "true") {
    // long-press gesture registers here  
}
```
This allows internal testers to enable the debug menu without a code change (`java -Ddev.stapler.debug=true -jar stelekit.jar`), while production users see nothing.

**Shared state persistence** (`debug_flags` SQLDelight table): Even if rows exist from a previous debug session, the overlay is unreachable in release builds. The `DebugFlagRepository` reads are harmless (just reading DB rows that are never acted upon).

---

## Consequences

**Positive**:
- Consistent with existing codebase patterns — minimal onboarding friction.
- Platform-native UX for each target.
- Compile-time enforcement of completeness.

**Negative**:
- If a third target (e.g., iOS, Web) is added before Phase 3 is complete, a stub `DebugMenuOverlay.ios.kt` must be created immediately to unblock compilation. This stub can be a no-op composable.
- `DebugMenuState` changes (new toggle fields) require updating both actual implementations. This is mitigated by the shared `onStateChange: (DebugMenuState) -> Unit` callback — adding a new field to the data class forces a recompile of both actuals, making the change visible at compile time.

---

## Related Expect/Actual Files in This Feature

| commonMain (expect) | androidMain (actual) | jvmMain (actual) |
|--------------------|--------------------|-----------------|
| `DebugMenuOverlay.kt` | `DebugMenuOverlay.android.kt` | `DebugMenuOverlay.jvm.kt` |
| `OtelProvider.kt` | `OtelProvider.android.kt` | `OtelProvider.jvm.kt` |
| `DeviceInfo.kt` | `DeviceInfo.android.kt` | `DeviceInfo.jvm.kt` |
