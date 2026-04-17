# Stack Research: KMP/CMP Android Platform APIs

**Dimension**: Stack
**Date**: 2026-04-12
**Project**: android-ux-overhaul
**CMP version in use**: 1.7.3 (confirmed from `kmp/build.gradle.kts`)

---

## Executive Summary

SteleKit uses Compose Multiplatform (CMP) 1.7.3 with `androidx.activity:activity-compose:1.9.2` and `androidx.compose.material3:material3:1.4.0` in `androidTarget`. The three Android-specific APIs required for this overhaul — `enableEdgeToEdge()`, `BackHandler`/`PredictiveBackHandler`, and `NavigationBar` inset wiring — fall into two distinct categories:

1. **Must stay in `androidMain`**: `enableEdgeToEdge()` (Activity method) and `PredictiveBackHandler` (from `androidx.activity.compose`, Android-only). These cannot be placed in `commonMain` and must be called from `MainActivity` or an `androidMain`-only composable.

2. **Already available in `commonMain`**: `NavigationBar`, `WindowInsets.*`, `Scaffold`, `statusBarsPadding()`, `navigationBarsPadding()`, `imePadding()`. These ship in `org.jetbrains.compose.material3` and `org.jetbrains.compose.foundation`, which are true KMP artifacts. `BackHandler` from `androidx.activity.compose` is Android-only, but CMP itself does not expose a `BackHandler` in `commonMain` — the correct pattern is `expect/actual`.

The project already has the right structure for `expect/actual` extension functions (`ModifierExtensions.kt` / `ModifierExtensions.android.kt`). The same pattern should be applied for `BackHandler`.

**Critically**: `enableEdgeToEdge()` is already called in `MainActivity.onCreate()` (line 36). The remaining gap is that `MainLayout.kt` uses `statusBarsPadding()` as a manual escape hatch (line 38) instead of delegating inset management to a `Scaffold`. This must change when F1 (bottom nav) and F2 (edge-to-edge) land together.

---

## Findings

### Finding 1: `enableEdgeToEdge()` is `androidMain`-only and is already called

**Status**: Already present. No new work needed.

`enableEdgeToEdge()` is a method on `ComponentActivity` (from `androidx.activity:activity:1.9.2`). It:
- Sets the window to draw behind system bars by making them transparent (or translucent for 3-button nav mode).
- Automatically handles `WindowCompat.setDecorFitsSystemWindows(window, false)` internally — there is no need to call both. Calling `setDecorFitsSystemWindows` manually is the pre-`enableEdgeToEdge()` pattern and is now redundant.
- Must be called **before** `setContent {}` in `onCreate`.
- Is an `Activity` method — it has no equivalent in `commonMain` and cannot be abstracted away. It belongs in `MainActivity.kt` in the `androidApp` module.

**Current state** (`androidApp/src/main/kotlin/dev/stapler/stelekit/MainActivity.kt`, line 36):
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()   // already present
    setContent { StelekitApp(...) }
}
```

**Sources**: Android developer docs (developer.android.com/develop/ui/views/layout/edge-to-edge), confirmed by code inspection.

---

### Finding 2: `windowSoftInputMode="adjustResize"` conflicts with edge-to-edge

**Status**: Active conflict. Must be resolved before F2 ships.

`AndroidManifest.xml` (line 25) declares `android:windowSoftInputMode="adjustResize"`. This is incompatible with edge-to-edge:

- `adjustResize` causes the system to physically resize the Activity window when the soft keyboard appears. With `enableEdgeToEdge()`, the window draws edge-to-edge, but `adjustResize` fights this by shrinking the window, creating layout jank and gaps at the bottom.
- The correct modern approach is to set `windowSoftInputMode="adjustNothing"` (or remove it, letting the default apply) and instead use `Modifier.imePadding()` on the composable that contains text input fields. This gives smooth, animation-synchronized keyboard avoidance.
- `Modifier.imePadding()` is available in `commonMain` via `org.jetbrains.compose.foundation:foundation`.

**Required change** in `androidApp/src/main/AndroidManifest.xml`:
```xml
<!-- Before -->
android:windowSoftInputMode="adjustResize"

<!-- After -->
android:windowSoftInputMode="adjustNothing"
```

And in the block editor composable, apply `Modifier.imePadding()` to the scrollable content container (not to the top-level layout).

**Sources**: Android developer docs on edge-to-edge and IME handling.

---

### Finding 3: `NavigationBar` and `WindowInsets` are available in `commonMain`

**Status**: Ready to use. No `expect/actual` needed for these APIs.

`NavigationBar`, `NavigationBarItem`, `Scaffold`, and all `WindowInsets.*` types (`WindowInsets.navigationBars`, `WindowInsets.statusBars`, `WindowInsets.safeContent`, `WindowInsets.ime`) are defined in `commonMain` of the CMP fork of `compose-multiplatform-core`. They are in:
- `org.jetbrains.compose.material3:material3:1.7.3` — `NavigationBar`, `NavigationBarItem`, `Scaffold`
- `org.jetbrains.compose.foundation:foundation:1.7.3` — all `WindowInsets.*`, `windowInsetsPadding()`, `statusBarsPadding()`, `navigationBarsPadding()`, `imePadding()`

This means a `NavigationBar` composable can live in `commonMain` and will compile for Android, Desktop, iOS, and Web. The navigation bar itself adapts per-platform: on Desktop/Web it renders as a regular Material component; on Android it correctly consumes `WindowInsets.navigationBars` when placed as Scaffold's `bottomBar`.

**The recommended pattern for F1**: Place the Android `NavigationBar` inside an `androidMain` composable that wraps `commonMain` content via Scaffold — OR place it directly in `commonMain` behind the `isMobile` flag (already used in `App.kt`). Both are valid. The simpler approach is `commonMain` behind `isMobile`:

```kotlin
// commonMain — inside GraphContent or a new AndroidLayout composable
Scaffold(
    bottomBar = {
        if (isMobile) {
            NavigationBar {
                NavigationBarItem(
                    selected = currentScreen is Screen.Journals,
                    onClick = { onNavigate(Screen.Journals) },
                    icon = { Icon(Icons.Default.Book, contentDescription = "Journals") },
                    label = { Text("Journals") }
                )
                // ... other destinations
            }
        }
    },
    contentWindowInsets = WindowInsets.safeContent
) { paddingValues ->
    Box(Modifier.padding(paddingValues)) {
        content()
    }
}
```

On Desktop, `isMobile` is `false`, so the `NavigationBar` branch is never reached. This avoids any `expect/actual` complexity entirely.

**Sources**: CMP core repository (`compose-multiplatform-core`, `jb-main` branch) — `NavigationBar.kt` confirmed in `commonMain`; Android developer docs on Scaffold + NavigationBar.

---

### Finding 4: `BackHandler` from `androidx.activity.compose` is Android-only

**Status**: Requires `expect/actual` to use back-interception in `commonMain`.

`BackHandler` is defined in `androidx.activity.compose` (package `androidx.activity.compose`), which is an Android-only artifact. It is **not** part of CMP's `commonMain`. There is no CMP-provided equivalent in `commonMain` as of CMP 1.7.3.

The correct pattern for KMP projects (confirmed by Voyager navigator's source structure) is `expect/actual`:

**`commonMain`** — `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/BackHandlerExt.kt`:
```kotlin
import androidx.compose.runtime.Composable

@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
```

**`androidMain`** — `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/BackHandlerExt.android.kt`:
```kotlin
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}
```

**`jvmMain`** — `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/ui/BackHandlerExt.jvm.kt`:
```kotlin
import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Desktop: no system back concept; no-op
}
```

**`jsMain`** — similar no-op actual.

This mirrors the existing `platformNavigationInput` expect/actual pattern already in use in this project (`ModifierExtensions.kt`).

**Sources**: `androidx.activity.compose` package reference (confirmed Android-only); Voyager navigator source structure using `expect fun BackHandler(...)` in `commonMain` with platform actuals.

---

### Finding 5: `PredictiveBackHandler` for back-swipe progress is Android-only

**Status**: `androidMain`-only. Cannot be shared. Must be wired at Activity or `androidMain` composable level.

`PredictiveBackHandler` (from `androidx.activity.compose`) provides a `Flow<BackEventCompat>` that streams gesture progress during a predictive back swipe, enabling animated transitions. It is Android-only and requires:

1. `android:enableOnBackInvokedCallback="true"` in `AndroidManifest.xml` — this is the opt-in for Android 13/14. On Android 15+, the system back gesture is always predictive.
2. `androidx.activity:activity-compose:1.9.2` or higher (already present).
3. `Material3 1.3.0+` for built-in predictive back animations in Material components (present: `material3:1.4.0`).

For the F3 scope (replacing `Crossfade` with `AnimatedContent`), the `PredictiveBackHandler` is not strictly required. The `AnimatedContent` directional transition works without it — it just needs a navigation direction key. `PredictiveBackHandler` is only needed if the team wants the "peek" animation that plays during the back swipe gesture (before the user releases). That is a stretch goal, not required for F3 as specified.

The simpler F3 implementation uses `AnimatedContent` in `ScreenRouter` keyed on `(screen, direction)` — no predictive back wiring needed.

**`AndroidManifest.xml` change required** (in `<application>` element):
```xml
<application
    ...
    android:enableOnBackInvokedCallback="true">
```

**Sources**: Android predictive back gesture documentation; `androidx.activity.compose` package summary.

---

### Finding 6: Current `MainLayout.kt` uses manual inset padding — must be replaced with `Scaffold`

**Status**: The root cause of the edge-to-edge problem. The `Scaffold` replacement is the central change for F1+F2.

`MainLayout.kt` (line 38) uses:
```kotlin
.then(if (isMobile) Modifier.statusBarsPadding() else Modifier)
```

And at line 110:
```kotlin
Spacer(modifier = Modifier.navigationBarsPadding())
```

And `App.kt` (line 486) uses a hardcoded notification overlay:
```kotlin
NotificationOverlay(notificationManager, modifier = Modifier.padding(bottom = 32.dp))
```

These are the three inset violations. The fix is to replace `MainLayout.kt`'s `Column` root with a `Scaffold` that:
- Owns `topBar` (existing `TopBar` composable).
- Owns `bottomBar` (new `NavigationBar` for `isMobile`, nothing for desktop).
- Passes `contentWindowInsets = WindowInsets.safeContent` so Scaffold distributes correct padding to content via `paddingValues`.
- The `NotificationOverlay` should use `WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()` instead of `32.dp`.

`Scaffold`'s `bottomBar` slot automatically accounts for navigation bar insets — when a `NavigationBar` is placed there, Scaffold adjusts `paddingValues` so content is not obscured by either the navigation bar UI or the system navigation bar behind it.

**Key rule**: Do NOT apply `Modifier.padding(paddingValues)` AND also `navigationBarsPadding()` to the same content. This double-consumes insets and creates a gap. Choose one: `Scaffold` (preferred) or manual inset modifiers (avoid).

**Sources**: Android developer docs on Scaffold + WindowInsets; developer.android.com/develop/ui/compose/layouts/insets.

---

### Finding 7: CMP 1.7.3 is the correct version — all required APIs are present

**Status**: No dependency upgrades required for F1–F3.

Confirmed version matrix in `kmp/build.gradle.kts`:
- CMP: `1.7.3` (runtime, foundation, material3, resources)
- `androidx.activity:activity-compose`: `1.9.2`
- `androidx.compose.material3:material3`: `1.4.0` (androidTarget)
- `org.jetbrains.compose.material3:material3`: `1.7.3` (commonMain)

All needed APIs are available at these versions:
- `NavigationBar` — available in CMP material3 commonMain since early CMP versions (was always a Material component).
- `WindowInsets.*` — available in CMP foundation commonMain since CMP 1.6.x.
- `BackHandler` (androidMain) — available in `activity-compose:1.9.2`.
- `PredictiveBackHandler` — available in `activity-compose:1.9.2`.
- `enableEdgeToEdge()` — available in `activity:1.9.2`.
- `Material3 1.4.0` — supports predictive back animations in Material components natively.

No version upgrades are needed to implement F1, F2, or F3.

---

### Finding 8: `SuggestionNavigatorPanel` also uses `navigationBarsPadding()` — collateral fix needed

**Status**: Secondary inset violation to fix during F2.

`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SuggestionNavigatorPanel.kt` (line 71) applies `.navigationBarsPadding()` directly on a panel modifier. Once `Scaffold` manages insets at the top level, this will double-consume navigation bar insets and create a gap. This composable should instead consume `WindowInsets.navigationBars` only if it is rendered outside the Scaffold content area, or receive its padding from `paddingValues` if inside the Scaffold content.

---

## Recommendations

These are concrete, copy-paste-ready patterns for the implementation team.

### R1: `MainActivity.kt` — no changes required for edge-to-edge

`enableEdgeToEdge()` is already present. The only `MainActivity` change needed is for predictive back:

```kotlin
// No changes to enableEdgeToEdge() call — it is correct as-is.
// enableEdgeToEdge() subsumes WindowCompat.setDecorFitsSystemWindows(window, false).
// DO NOT add setDecorFitsSystemWindows separately.
```

### R2: `AndroidManifest.xml` — two changes

```xml
<application
    ...
    android:enableOnBackInvokedCallback="true">   <!-- ADD: predictive back opt-in -->

    <activity
        android:name="dev.stapler.stelekit.MainActivity"
        android:windowSoftInputMode="adjustNothing"   <!-- CHANGE from adjustResize -->
        ...>
```

### R3: Replace `MainLayout.kt` root `Column` with `Scaffold`

The new `MainLayout` signature and structure:

```kotlin
@Composable
fun MainLayout(
    isMobile: Boolean = false,
    sidebarExpanded: Boolean = false,
    onSidebarDismiss: () -> Unit = {},
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit,
    topBar: @Composable () -> Unit,
    leftSidebar: @Composable () -> Unit,
    rightSidebar: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,  // now receives paddingValues
    statusBar: @Composable () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { topBar() },
        bottomBar = {
            if (isMobile) {
                MobileNavigationBar(
                    currentScreen = currentScreen,
                    onNavigate = onNavigate
                )
            }
        },
        contentWindowInsets = WindowInsets.safeContent
    ) { paddingValues ->
        // Pass paddingValues down — DO NOT apply statusBarsPadding/navigationBarsPadding here
        content(paddingValues)
    }
}
```

Remove `Modifier.statusBarsPadding()` from line 38 and the `Spacer(Modifier.navigationBarsPadding())` at line 110 — `Scaffold` handles both via `paddingValues`.

### R4: `MobileNavigationBar` composable — place in `commonMain`

```kotlin
// commonMain: kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/MobileNavigationBar.kt
@Composable
fun MobileNavigationBar(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit
) {
    NavigationBar {
        NavigationBarItem(
            selected = currentScreen is Screen.Journals,
            onClick = { onNavigate(Screen.Journals) },
            icon = { Icon(Icons.Default.AutoStories, contentDescription = null) },
            label = { Text("Journals") }
        )
        NavigationBarItem(
            selected = currentScreen is Screen.AllPages,
            onClick = { onNavigate(Screen.AllPages) },
            icon = { Icon(Icons.Default.Pages, contentDescription = null) },
            label = { Text("Pages") }
        )
        NavigationBarItem(
            selected = currentScreen is Screen.Flashcards,
            onClick = { onNavigate(Screen.Flashcards) },
            icon = { Icon(Icons.Default.Style, contentDescription = null) },
            label = { Text("Flashcards") }
        )
        NavigationBarItem(
            selected = currentScreen is Screen.Notifications,
            onClick = { onNavigate(Screen.Notifications) },
            icon = { Icon(Icons.Default.Notifications, contentDescription = null) },
            label = { Text("Activity") }
        )
    }
}
```

`NavigationBar` is from `org.jetbrains.compose.material3` — imports work in `commonMain` without any `androidMain` wrapper. No `expect/actual` needed.

### R5: `PlatformBackHandler` — add `expect/actual` following existing project pattern

Create three files following the project's existing `ModifierExtensions` pattern:

**`commonMain`** — `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/PlatformBackHandler.kt`:
```kotlin
package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable

@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
```

**`androidMain`** — `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/PlatformBackHandler.android.kt`:
```kotlin
package dev.stapler.stelekit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}
```

**`jvmMain`** — `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/ui/PlatformBackHandler.jvm.kt`:
```kotlin
package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Desktop has no system back gesture — no-op
}
```

Repeat for `jsMain` if JS target is enabled. Use this composable in `commonMain` screens that intercept back (editing mode, open dialogs, selection mode — as required by F3).

### R6: Fix `NotificationOverlay` inset — replace magic `32.dp` with WindowInsets

In `App.kt` (line 486):
```kotlin
// Before:
NotificationOverlay(notificationManager, modifier = Modifier.padding(bottom = 32.dp))

// After:
NotificationOverlay(
    notificationManager,
    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
)
```

`WindowInsets.navigationBars` is zero when there is no system navigation bar (e.g., full-screen gesture navigation), and equals the navigation bar height when a bar is present — correct in all cases.

### R7: Fix `SuggestionNavigatorPanel.kt` double-inset risk

`SuggestionNavigatorPanel.kt` line 71 uses `.navigationBarsPadding()`. Audit this composable's position in the layout hierarchy after `Scaffold` is introduced. If it is rendered inside `Scaffold`'s content area (which has already consumed nav bar insets via `paddingValues`), remove the `.navigationBarsPadding()` call. If it floats outside the Scaffold content (e.g., as an overlay `Box` sibling), keep it.

### R8: `windowSoftInputMode` migration guide for `imePadding()`

After switching to `adjustNothing`, apply `Modifier.imePadding()` to the innermost scrollable container in block editing screens, not to the top-level layout:

```kotlin
// In block editor scrollable area:
LazyColumn(
    modifier = Modifier
        .fillMaxSize()
        .imePadding()  // pushes content above keyboard
) { ... }
```

Do not apply `imePadding()` to `Scaffold`'s outer modifier — let Scaffold handle status/nav bar insets and let `imePadding()` live only where text input occurs.

---

## API Reference Summary

| API | Package | Available in `commonMain`? | Minimum Version |
|---|---|---|---|
| `enableEdgeToEdge()` | `androidx.activity` | No — call from `MainActivity` | activity 1.6.0+ |
| `WindowCompat.setDecorFitsSystemWindows` | `androidx.core` | No — subsumed by `enableEdgeToEdge()` | (deprecated pattern) |
| `NavigationBar` | `androidx.compose.material3` | Yes (CMP commonMain) | CMP 1.6.x+ |
| `Scaffold` | `androidx.compose.material3` | Yes (CMP commonMain) | CMP 1.0.x+ |
| `WindowInsets.*` | `androidx.compose.foundation.layout` | Yes (CMP commonMain) | CMP 1.6.x+ |
| `statusBarsPadding()` | `androidx.compose.foundation.layout` | Yes (CMP commonMain) | CMP 1.6.x+ |
| `navigationBarsPadding()` | `androidx.compose.foundation.layout` | Yes (CMP commonMain) | CMP 1.6.x+ |
| `imePadding()` | `androidx.compose.foundation.layout` | Yes (CMP commonMain) | CMP 1.6.x+ |
| `BackHandler` | `androidx.activity.compose` | No — Android only | activity-compose 1.6.0+ |
| `PredictiveBackHandler` | `androidx.activity.compose` | No — Android only | activity-compose 1.8.0+ |
| `android:enableOnBackInvokedCallback` | `AndroidManifest.xml` | N/A — manifest attribute | Android 13 (API 33) |
| `dynamicLightColorScheme` | `androidx.compose.material3` | No — use `Build.VERSION` guard in `androidMain` | Material3 1.0.0+ |
