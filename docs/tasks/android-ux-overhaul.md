# Android UX Overhaul — Implementation Plan

**Feature**: Android UX Overhaul
**Status**: Planning
**Target**: Android (primary); Desktop/iOS/Web must not regress
**Developer**: Solo (Tyler Stapler)

---

## Table of Contents

1. [Epic Overview](#epic-overview)
2. [Architecture Decisions](#architecture-decisions)
3. [Dependency Visualization](#dependency-visualization)
4. [Story Breakdown](#story-breakdown)
   - [Story 1+2: Bottom Navigation + Edge-to-Edge (must ship together)](#story-1--2-f1--f2-bottom-navigation--edge-to-edge)
   - [Story 3: Predictive Back + Screen Transitions](#story-3-f3-predictive-back--screen-transitions)
   - [Story 4: Touch Target Audit](#story-4-f4-touch-target-audit)
   - [Story 5: Accessibility Pass](#story-5-f5-accessibility-pass)
   - [Story 6: Material You Dynamic Color](#story-6-f6-material-you-dynamic-color)
5. [Known Issues](#known-issues)
6. [Integration Checkpoints](#integration-checkpoints)
7. [Context Preparation Guide](#context-preparation-guide)

---

## Epic Overview

### User Value

SteleKit's Android UI was designed desktop-first. Android phone users experience a cascade of UX violations: primary navigation hidden behind a hamburger (2+ taps to reach any destination), content clipped at the system status and navigation bars, touch targets too small to hit reliably, no predictive back gesture, and no TalkBack accessibility. Together these make the app feel unfinished on Android and prevent EU distribution under the European Accessibility Act (EAA, in force June 2025).

This overhaul brings SteleKit into conformance with Material Design 3, Android 14+ platform expectations, and WCAG 2.1 AA — without touching Desktop, iOS, or Web layouts.

### Success Metrics

| Metric | Target |
|---|---|
| Primary destination reachability | 1 tap from any screen |
| Edge-to-edge | Content bleeds under status bar and nav bar on Android 14+ |
| Touch targets | All interactive elements >= 48x48dp on Android |
| Predictive back | Back swipe shows destination preview on Android 14+ |
| Screen transitions | Directional slide+fade on forward; suppressed on back swipe |
| TalkBack test | 5 primary flows navigable without sight: graph open, page nav, block edit, search, settings |
| WCAG 2.1 AA | Contrast and semantic compliance on all screens |
| Material You | Android 12+ users can opt in to dynamic color via Settings |

### Scope

**In scope**: F1–F6 as defined below. Android phone form factor is the primary concern.
**Out of scope**: iOS tab bar navigation, Desktop layout changes, Web UX, Flashcards feature implementation, plugin/multi-graph switching UX, block-level animations.

### Constraints

- KMP: `commonMain` code must compile for Android, Desktop, iOS, Web. `WindowCompat`, `dynamicColorScheme`, `BackHandler` are `androidMain`-only; wrap in `expect/actual` where they affect `commonMain` interfaces.
- No breaking ViewModel changes: `AppState`, `Screen`, `StelekitViewModel` are minimally modified. Navigation restructure is at the layout/composable layer.
- No new third-party dependencies. All required APIs are available in CMP 1.7.3 + activity-compose 1.9.2.
- Screenshot tests: `jvmTest` uses Roborazzi. Run `./gradlew recordRoborazziJvm` and commit baselines before F1/F2 work begins. Update again after F1/F2 complete.

---

## Architecture Decisions

| ADR | File | Decision |
|---|---|---|
| ADR-001 | `project_plans/android-ux-overhaul/decisions/ADR-001-bottom-nav-architecture.md` | Use `bottomBar` slot on `MainLayout` + `PlatformBottomBar` expect/actual; avoids expect/actual on AppLayout or restructuring GraphContent |
| ADR-002 | `project_plans/android-ux-overhaul/decisions/ADR-002-inset-ownership.md` | Each component owns the inset it visually fills (TopBar claims statusBars, NavigationBar claims navigationBars); MainLayout strips manual padding modifiers rather than migrating to Scaffold |
| ADR-003 | `project_plans/android-ux-overhaul/decisions/ADR-003-tab-selection-state.md` | Tab selection derived from `AppState.currentScreen`; no local state in PlatformBottomBar |
| ADR-004 | `project_plans/android-ux-overhaul/decisions/ADR-004-dynamic-color-opt-in.md` | `DYNAMIC` added as explicit opt-in enum value; existing Stone/Parchment/Dark themes untouched |

---

## Dependency Visualization

```
Pre-work (no code change)
  └── TASK-0: Record Roborazzi baselines
        │
        ▼
Story 1+2 (F1+F2) ─── MUST SHIP TOGETHER ───────────────────────────────────┐
  ├── TASK-1.1: AndroidManifest.xml fixes (adjustNothing + enableOnBackInvokedCallback)
  ├── TASK-1.2: PlatformBottomBar expect/actual + BottomNavItem enum          │
  ├── TASK-1.3: MainLayout restructure (bottomBar slot, strip manual insets)  │
  ├── TASK-1.4: TopBar status bar inset + App.kt NotificationOverlay fix      │
  └── TASK-1.5: JournalsView/PageView imePadding + SuggestionNavigatorPanel audit
        │                                                                      │
        ▼ (F1+F2 complete)                                                    │
Story 3 (F3) ────────────────────────────────────────────────────────────────┘
  ├── TASK-3.1: PlatformBackHandler expect/actual
  └── TASK-3.2: AnimatedContent directional transitions in ScreenRouter

Stories 4, 5, 6 are independent — can run in parallel with or after F3:

Story 4 (F4) ─ independent
  └── TASK-4.1: TopBar + Sidebar touch target audit

Story 5 (F5) ─ depends on F2 (for NotificationOverlay), otherwise independent
  ├── TASK-5.1: GraphSwitcher semantics + MobileBlockToolbar icon migration
  └── TASK-5.2: Indent/Outdent promotion to primary toolbar row

Story 6 (F6) ─ independent
  ├── TASK-6.1: StelekitThemeMode.DYNAMIC + expect/actual getDynamicColorScheme
  └── TASK-6.2: Settings UI DYNAMIC option + extended colors derivation

Recommended sequence:
TASK-0 → TASK-1.1 → TASK-1.2 → TASK-1.3 → TASK-1.4 → TASK-1.5
       → TASK-3.1 → TASK-3.2
       → TASK-4.1 (parallel)
       → TASK-5.1 → TASK-5.2 (parallel after F2)
       → TASK-6.1 → TASK-6.2 (parallel)
```

---

## Story Breakdown

---

### Story 1 + 2: F1 + F2 — Bottom Navigation + Edge-to-Edge

**These two stories must ship as a single release.** Both restructure `MainLayout`; shipping F1 without F2 leaves stale `statusBarsPadding` modifiers that double-pad. Shipping F2 without F1 removes inset protection before the `NavigationBar` provides its replacement.

**User value**: Primary destinations reachable in 1 tap from anywhere. Content fills the full phone screen with no boxed chrome at top or bottom.

**Acceptance criteria**:
- `NavigationBar` with 4 items (Journals, Pages, Flashcards, Notifications) is visible at the bottom of the screen on Android phone (width < 600dp)
- NavigationBar is absent on Desktop and tablets (width >= 600dp)
- Content draws under the system status bar (no hard background boundary at the top)
- Content draws under the system navigation bar when on gesture navigation mode
- No double-height gap at top or bottom of the layout
- Soft keyboard appearing in block editing does not overlap text; focused block scrolls into view above keyboard
- `NotificationOverlay` respects navigation bar insets on both gesture-nav and 3-button-nav devices
- `./gradlew jvmTest` passes; `./gradlew testDebugUnitTest` passes

---

#### TASK-0: Record Roborazzi Baselines (Pre-work)

**Objective**: Establish screenshot regression baselines before any layout changes are made.

**Files**: `kmp/build.gradle.kts` (to verify Roborazzi output path)

**Size**: Small (1h)

**Implementation steps**:
1. Verify Roborazzi output directory: check `roborazzi.properties` or `kmp/build.gradle.kts` for `roborazzi.outputDir` setting.
2. Run `./gradlew recordRoborazziJvm` from the repo root.
3. Confirm generated `.png` files appear in the expected source directory (not `build/`). The default for Roborazzi 1.59.0 in KMP jvmTest is `src/jvmTest/roborazzi/`.
4. Stage and commit the baseline images with message: `test(screenshots): record Roborazzi baselines before android-ux-overhaul`.

**Validation**:
- `./gradlew verifyRoborazziJvm` passes with committed baselines on a clean checkout.
- Making a deliberate 4dp change to any tested composable causes `verifyRoborazziJvm` to fail.

**INVEST check**:
- Independent: can be done before any code change
- Valuable: establishes regression protection that does not currently exist
- Estimable: 1 hour
- Small: one command + commit
- Testable: verifyRoborazziJvm must pass

---

#### TASK-1.1: AndroidManifest.xml Fixes

**Objective**: Fix the two manifest-level blockers before any edge-to-edge code lands.

**Files** (primary): `androidApp/src/main/AndroidManifest.xml`

**Size**: Micro (30 min)

**Prerequisites**: TASK-0 complete (baselines committed)

**Implementation steps**:
1. Change `android:windowSoftInputMode="adjustResize"` to `android:windowSoftInputMode="adjustNothing"` on the `<activity>` element (line 25).
2. Add `android:enableOnBackInvokedCallback="true"` to the `<application>` element (not the `<activity>` element) to enable predictive back globally.

**Result**:
```xml
<application
    ...
    android:enableOnBackInvokedCallback="true">

    <activity
        android:name="dev.stapler.stelekit.MainActivity"
        android:windowSoftInputMode="adjustNothing"
        ...>
```

**Validation**:
- Build succeeds: `./gradlew assembleDebug`
- Open a page on a physical device or emulator (API 30+), tap a block to focus. Keyboard appears. Confirm content is NOT pushed up (it will be until TASK-1.5 adds `imePadding`). The purpose of this task is to stop `adjustResize` from fighting edge-to-edge — keyboard overlap is expected and will be fixed in TASK-1.5.

**INVEST check**:
- Independent: manifest-only change, zero code dependencies
- Small: 2 line changes in 1 file
- Testable: build passes; keyboard no longer resizes the window

---

#### TASK-1.2: PlatformBottomBar expect/actual + BottomNavItem

**Objective**: Create the `PlatformBottomBar` expect/actual and the `BottomNavItem` mapping enum.

**Files**:
- NEW: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/PlatformBottomBar.kt`
- NEW: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/PlatformBottomBar.android.kt`
- NEW: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/ui/PlatformBottomBar.jvm.kt`

**Size**: Small (2h)

**Prerequisites**: Understanding of existing `ModifierExtensions.kt` / `.android.kt` / `.jvm.kt` expect/actual pattern; `Screen` sealed class in `AppState.kt`

**Implementation steps**:

1. Create `commonMain/ui/PlatformBottomBar.kt`:
```kotlin
package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable

@Composable
expect fun PlatformBottomBar(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit
)
```

2. Create `androidMain/ui/PlatformBottomBar.android.kt` with a private `BottomNavItem` enum mapping `Screen` → icon → label:
```kotlin
package dev.stapler.stelekit.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

private enum class BottomNavItem(
    val icon: ImageVector,
    val label: String
) {
    JOURNALS(Icons.Default.AutoStories, "Journals"),
    ALL_PAGES(Icons.AutoMirrored.Filled.List, "Pages"),
    FLASHCARDS(Icons.Default.Style, "Flashcards"),
    NOTIFICATIONS(Icons.Default.Notifications, "Notifications");

    fun matchesScreen(screen: Screen): Boolean = when (this) {
        JOURNALS    -> screen is Screen.Journals
        ALL_PAGES   -> screen is Screen.AllPages || screen is Screen.PageView
        FLASHCARDS  -> screen is Screen.Flashcards
        NOTIFICATIONS -> screen is Screen.Notifications
    }

    fun toScreen(): Screen = when (this) {
        JOURNALS    -> Screen.Journals
        ALL_PAGES   -> Screen.AllPages
        FLASHCARDS  -> Screen.Flashcards
        NOTIFICATIONS -> Screen.Notifications
    }
}

@Composable
actual fun PlatformBottomBar(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit
) {
    NavigationBar {
        BottomNavItem.entries.forEach { item ->
            NavigationBarItem(
                selected = item.matchesScreen(currentScreen),
                onClick = { onNavigate(item.toScreen()) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.label) }
            )
        }
    }
}
```

3. Create `jvmMain/ui/PlatformBottomBar.jvm.kt`:
```kotlin
package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBottomBar(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit
) { /* Desktop uses sidebar navigation */ }
```

4. If `jsMain` or `iosMain` are active targets, create identical no-op actuals.

**Validation**:
- `./gradlew jvmTest` compiles (the new expect/actual resolves).
- `./gradlew assembleDebug` compiles.
- In a preview or screenshot test, `PlatformBottomBar(currentScreen = Screen.Journals, onNavigate = {})` on JVM renders nothing (empty composable).

**INVEST check**:
- Independent: no changes to existing files; compiles in isolation
- Small: 3 new files, ~60 lines total
- Testable: jvmTest compilation confirms expect/actual resolution

---

#### TASK-1.3: MainLayout Restructure

**Objective**: Add `bottomBar` slot to `MainLayout`; remove the two manual inset modifiers that conflict with edge-to-edge.

**Files**:
- MODIFY: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/MainLayout.kt`
- MODIFY: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

**Size**: Medium (2h)

**Prerequisites**: TASK-1.2 complete; TASK-1.1 complete

**Implementation steps**:

1. In `MainLayout.kt`, add `bottomBar: @Composable () -> Unit = {}` as the last parameter.

2. Remove `Modifier.statusBarsPadding()` from the `Column` modifier at line 38. The full modifier chain becomes:
```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        // statusBarsPadding removed — TopBar will own this inset (TASK-1.4)
)
```

3. Replace the `if (isMobile) { Spacer(modifier = Modifier.navigationBarsPadding()) }` block at line 109–111 with `bottomBar()`:
```kotlin
// Replace:
statusBar()
if (isMobile) {
    Spacer(modifier = Modifier.navigationBarsPadding())
}

// With:
statusBar()
bottomBar()
// NavigationBar (rendered by PlatformBottomBar) internally handles WindowInsets.navigationBars.
// On Desktop/iOS, bottomBar() is a no-op.
```

4. In `App.kt`, add `bottomBar` to the `MainLayout(...)` call inside `GraphContent`. Place it after `statusBar`:
```kotlin
MainLayout(
    ...
    statusBar = {
        if (!isMobile) {
            StatusBarContent(...)
        }
    },
    bottomBar = {
        PlatformBottomBar(
            currentScreen = appState.currentScreen,
            onNavigate = { screen ->
                viewModel.navigateTo(screen)
                if (isMobile && appState.sidebarExpanded) viewModel.toggleSidebar()
            }
        )
    }
)
```

5. In `App.kt`, also remove Logs and Performance from the Sidebar `NavigationItem` list (they are demoted to Settings → Developer Tools). In `Sidebar.kt` (inside `LeftSidebar`), remove:
```kotlin
// Remove these two NavigationItem calls:
NavigationItem("Logs", Icons.Default.Info, currentScreen is Screen.Logs) { onNavigate(Screen.Logs) }
NavigationItem("Performance", Icons.Default.Settings, currentScreen is Screen.Performance) { onNavigate(Screen.Performance) }
```
Add a Settings entry to the sidebar that navigates to the settings dialog (or leave a "Developer Tools" label with the two items indented under it — defer UX to implementation time).

**Validation**:
- `./gradlew jvmTest` passes without new failures.
- On Desktop (JVM run): sidebar still present, no bottom bar, layout unchanged visually from pre-F1.
- On Android: `NavigationBar` appears at bottom with 4 items. Tapping each navigates to the correct screen and highlights the correct tab.
- `appState.currentScreen = Screen.PageView(anyPage)` highlights the "Pages" tab.
- Roborazzi Desktop tests will fail (expected). Run `./gradlew recordRoborazziJvm` after visual verification.

**INVEST check**:
- Valuable: delivers the visible bottom navigation
- Estimable: 2h (well-understood refactor)
- Small: 2 files, ~20 lines net change

---

#### TASK-1.4: TopBar Status Bar Inset + NotificationOverlay Fix

**Objective**: Move status bar inset ownership to `TopBar` (mobile path); fix `NotificationOverlay` magic padding.

**Files**:
- MODIFY: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TopBar.kt`
- MODIFY: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

**Size**: Small (1h)

**Prerequisites**: TASK-1.3 complete

**Implementation steps**:

1. In `TopBar.kt`, add `windowInsetsPadding(WindowInsets.statusBars)` to the mobile path `Row` modifier:
```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .height(if (isMobile) 56.dp else 40.dp)
        .background(MaterialTheme.colorScheme.background)
        .then(
            if (isMobile) Modifier.windowInsetsPadding(WindowInsets.statusBars)
            else Modifier
        )
        .padding(horizontal = 8.dp)
        .testTag("top-bar"),
    ...
)
```

Add the necessary import: `import androidx.compose.foundation.layout.WindowInsets` and `import androidx.compose.foundation.layout.windowInsetsPadding`.

Note: The `TopBar` height on mobile is `56.dp`. With `windowInsetsPadding(WindowInsets.statusBars)`, the status bar inset is applied as padding inside the Row, meaning the Row's visible content area is `56.dp` below the status bar. The status bar area itself is covered by the `TopBar` background color, which is correct edge-to-edge behavior.

2. In `App.kt` inside `GraphDialogLayer`, replace the magic `32.dp` on `NotificationOverlay`:
```kotlin
// Before:
NotificationOverlay(
    notificationManager = notificationManager,
    modifier = Modifier.padding(bottom = 32.dp)
)

// After:
NotificationOverlay(
    notificationManager = notificationManager,
    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
)
```

**Validation**:
- On Android with gesture navigation (no visible nav bar): `NotificationOverlay` appears above the gesture area.
- On Android with 3-button navigation: `NotificationOverlay` appears above the nav bar.
- On Android with status bar: `TopBar` content (menu, back buttons) is visible below the status bar, not behind it.
- Status bar area is filled by `TopBar` background color (not transparent gap).
- On Desktop: no visible change (the `windowInsetsPadding` branch is guarded by `isMobile`, and `WindowInsets.statusBars` is zero on Desktop/JVM anyway).

**INVEST check**:
- Independent: isolated to TopBar and a single App.kt line
- Small: 2 files, <15 lines
- Testable: visual check on device for both nav modes

---

#### TASK-1.5: IME Padding for Block Editing + SuggestionNavigatorPanel Audit

**Objective**: Add `imePadding()` to block editing scroll containers so the keyboard does not obscure content; audit `SuggestionNavigatorPanel` for double-consume risk.

**Files**:
- MODIFY: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/JournalsView.kt` (locate scroll container)
- MODIFY: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/PageView.kt` (locate scroll container)
- AUDIT: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SuggestionNavigatorPanel.kt` (line 71)

**Size**: Medium (2h)

**Prerequisites**: TASK-1.1 complete (adjustNothing in manifest), TASK-1.3 complete (MainLayout restructured)

**Implementation steps**:

1. In `JournalsView.kt`, locate the `LazyColumn` that renders journal blocks. Add `imePadding()` to its modifier:
```kotlin
LazyColumn(
    modifier = Modifier
        .fillMaxSize()
        .imePadding()  // pushes content above soft keyboard
) { ... }
```
Do NOT add `imePadding()` to the outer `Column` or `Box` wrapper — only to the scroll container.

2. In `PageView.kt`, locate the scrollable container for block content. Apply the same `imePadding()` pattern.

3. In `SuggestionNavigatorPanel.kt` line 71, evaluate whether `.navigationBarsPadding()` is inside or outside the `MainLayout` content area:
   - If the panel is rendered as an overlay composable floating above the `MainLayout` content (e.g., a `Box` sibling to `MainLayout` at the `BoxWithConstraints` level in `App.kt`), the `.navigationBarsPadding()` is correct and should be kept.
   - If the panel is rendered inside `MainLayout`'s `content` slot, the `.navigationBarsPadding()` is now double-consuming (since `NavigationBar` via `bottomBar` already consumed nav bar insets). Remove it.
   Trace the call site in `App.kt` to determine which case applies and document the decision with an inline comment.

4. Run a Grep audit for any remaining manual inset modifiers inside `commonMain` composables that are rendered inside the `MainLayout` content area:
   Patterns to search: `statusBarsPadding`, `navigationBarsPadding`, `systemBarsPadding`, `safeContentPadding`, `safeDrawingPadding`.

**Validation**:
- On Android (API 30+) with block editor open: tap a block, keyboard appears, the focused block scrolls into view above the keyboard. Typing continues to scroll the focused block into view.
- Dismiss keyboard: content returns to full height without a gap.
- On Desktop (JVM): `imePadding()` is effectively a no-op (no soft keyboard). JVM tests pass.
- `SuggestionNavigatorPanel` correctly positions itself relative to the navigation bar with no visible gap or overlap.

**INVEST check**:
- Valuable: keyboard usability is currently broken after adjustNothing change
- Small: isolated to scroll container modifiers + one audit
- Testable: manual keyboard test on device

---

### Story 3: F3 — Predictive Back + Screen Transitions

**Depends on**: F1+F2 complete

**User value**: Android 14+ users get predictive back gesture with destination preview. All users get directional slide+fade transitions between screens.

**Acceptance criteria**:
- On Android 14+, initiating a back swipe from a page shows the system predictive back preview without a conflicting `AnimatedContent` exit animation
- Forward navigation (tapping a link) triggers a slide-in-from-right transition
- Back navigation (back swipe or back button) does not trigger `AnimatedContent` exit animation (let system handle it)
- `BackHandler` intercepts back in editing mode (active block focused), selection mode, and open dialogs
- Desktop, iOS: no behavior change (no-op `PlatformBackHandler`)

---

#### TASK-3.1: PlatformBackHandler expect/actual

**Objective**: Create the `PlatformBackHandler` expect/actual following the `ModifierExtensions` pattern.

**Files**:
- NEW: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/PlatformBackHandler.kt`
- NEW: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/PlatformBackHandler.android.kt`
- NEW: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/ui/PlatformBackHandler.jvm.kt`

**Size**: Small (1h)

**Prerequisites**: TASK-1.2 pattern understood; TASK-1.1 complete (`enableOnBackInvokedCallback` in manifest)

**Implementation steps**:

1. `commonMain/ui/PlatformBackHandler.kt`:
```kotlin
package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable

/**
 * Platform-aware back handler. Android: wraps BackHandler from activity-compose.
 * Desktop/iOS/Web: no-op (no system back concept on those platforms).
 *
 * Use in any screen that needs to intercept the system back action:
 * - Editing mode (block focused)
 * - Selection mode (selectedBlockUuids non-empty)
 * - Modal dialogs (handled by Dialog composable itself, but add here if custom)
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
```

2. `androidMain/ui/PlatformBackHandler.android.kt`:
```kotlin
package dev.stapler.stelekit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}
```

3. `jvmMain/ui/PlatformBackHandler.jvm.kt`:
```kotlin
package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Desktop has no system back gesture — intentional no-op
}
```

4. Add `PlatformBackHandler` usage in screens that need back interception. Place inside `PageView` and `JournalsView` for editing mode:
```kotlin
// In PageView / JournalsView, near the top of the composable:
PlatformBackHandler(
    enabled = editingBlockId != null,
    onBack = {
        // Clear focus / exit editing mode
        focusManager.clearFocus()
    }
)
```

Place for selection mode in `MobileBlockToolbar`'s host screen:
```kotlin
PlatformBackHandler(
    enabled = isInSelectionMode,
    onBack = { onClearSelection() }
)
```

**Validation**:
- `./gradlew jvmTest` passes.
- On Android 14+: with a block focused, pressing back clears focus rather than navigating back.
- In selection mode on Android: pressing back clears selection rather than navigating back.
- On Desktop: back behavior unchanged (back still goes to prior screen via existing `platformNavigationInput` mechanism).

**INVEST check**:
- Independent: new files only; does not modify existing composables except to call the new function
- Small: 3 new files + targeted call site additions
- Testable: behavioral verification on Android 14+ device

---

#### TASK-3.2: AnimatedContent Directional Transitions in ScreenRouter

**Objective**: Replace `Crossfade` in `ScreenRouter` with directional `AnimatedContent` that slides forward and suppresses exit animation on back gesture.

**Files**:
- MODIFY: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` (ScreenRouter composable)
- MODIFY: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt` (add navDirection derived property)

**Size**: Medium (3h)

**Prerequisites**: TASK-3.1 complete; understanding of `AppState.historyIndex`

**Implementation steps**:

1. Add a `NavDirection` sealed class or enum to `AppState.kt` (or a companion file):
```kotlin
enum class NavDirection { FORWARD, BACK, REPLACE }
```

2. Add a `navDirection: NavDirection` field to `AppState` (or derive it as a property from `historyIndex` delta). The simplest approach is to track direction in `StelekitViewModel.navigateTo()` / `goBack()` / `goForward()`:
   - `navigateTo()` → `FORWARD`
   - `goBack()` → `BACK`
   - `goForward()` → `FORWARD`

   Add `navDirection: NavDirection = NavDirection.REPLACE` to `AppState`.

3. In `ScreenRouter`, replace `Crossfade` with `AnimatedContent`:
```kotlin
@Composable
private fun ScreenRouter(
    screen: Screen,
    navDirection: NavDirection,
    ...
) {
    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            when (navDirection) {
                NavDirection.FORWARD -> {
                    slideInHorizontally { fullWidth -> fullWidth } + fadeIn() togetherWith
                    slideOutHorizontally { fullWidth -> -fullWidth } + fadeOut()
                }
                NavDirection.BACK -> {
                    // Suppress exit animation — let system predictive back handle it
                    // Enter: slide in from left
                    slideInHorizontally { fullWidth -> -fullWidth } + fadeIn() togetherWith
                    ExitTransition.None
                }
                NavDirection.REPLACE -> {
                    fadeIn() togetherWith fadeOut()
                }
            }
        },
        label = "ScreenRouter"
    ) { currentScreen ->
        when (currentScreen) {
            // ... same branches as before
        }
    }
}
```

4. Update the `ScreenRouter` call site in `GraphContent` to pass `navDirection = appState.navDirection`.

5. Update `StelekitViewModel.navigateTo()`, `goBack()`, `goForward()` to set `navDirection` on the new `AppState` field.

**Validation**:
- Tapping a page link: screen slides in from the right.
- Pressing back (button or gesture): screen slides in from the left, no double animation artifact.
- On Android 14+ with predictive back: initiate back swipe slowly. System preview shows the destination behind the current screen. Release: navigation completes. No `AnimatedContent` exit slide plays (suppressed via `ExitTransition.None`).
- On Desktop: transitions work as expected; no system back gesture interaction.

**INVEST check**:
- Valuable: directional transitions are a Material 3 requirement
- Estimable: 3h (moderate complexity from AnimatedContent API)
- Testable: visual test on Android 14 + desktop; `./gradlew jvmTest` passes

---

### Story 4: F4 — Touch Target Audit

**Independent** — can ship in any order relative to F3, F5, F6.

**User value**: Touch targets across the mobile UI meet the Android-required 48x48dp minimum, eliminating mis-taps on the TopBar and Sidebar.

**Acceptance criteria**:
- All 5 `IconButton` calls in `TopBar.kt` mobile path: `Modifier.size(48.dp)` (currently 40dp)
- `LeftSidebar` close button and `GraphItem` delete button: `Modifier.size(48.dp)` (currently 36dp)
- Roborazzi baselines updated after changes

---

#### TASK-4.1: TopBar + Sidebar Touch Target Audit

**Objective**: Increase all undersized mobile `IconButton` targets to 48dp.

**Files**:
- MODIFY: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TopBar.kt`
- MODIFY: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/Sidebar.kt`

**Size**: Small (1h)

**Prerequisites**: None

**Implementation steps**:

1. In `TopBar.kt`, mobile path (lines 57–118), change all `Modifier.size(40.dp)` to `Modifier.size(48.dp)`:
   - Menu hamburger button (line 57)
   - Back button (line 67)
   - Forward button (line 75)
   - New page button (line 100)
   - Settings button (line 105)
   - More/overflow button (line 112)

   The `TopBar` mobile height is `56.dp` — 48dp buttons fit within this height.

2. In `Sidebar.kt`:
   - `LeftSidebar` close button at line 80: change `Modifier.size(36.dp)` to `Modifier.size(48.dp)`
   - `GraphItem` delete button at line 346: change `Modifier.size(36.dp)` to `Modifier.size(48.dp)`
   - `SidebarItem` favorite button at line ~468: change `Modifier.size(36.dp)` to `Modifier.size(48.dp)`

   Note: `SidebarItem` and `NavigationItem` already use `heightIn(min = 48.dp)` — leave as-is.

3. After changes, run `./gradlew recordRoborazziJvm` to update screenshot baselines that include the TopBar or Sidebar.

**Validation**:
- Visually inspect all changed buttons on Android device — they should have more comfortable tap area without overlapping adjacent buttons.
- `./gradlew verifyRoborazziJvm` passes with new baselines committed.
- No JVM test failures.

**INVEST check**:
- Independent: isolated size constant changes
- Small: 2 files, ~8 line changes
- Testable: visual inspection; Roborazzi baseline update verifies intentional pixel changes

---

### Story 5: F5 — Accessibility Pass

**Partially depends on F2** (for `NotificationOverlay` inset-aware positioning, which is done in TASK-1.4). The toolbar work is independent.

**User value**: TalkBack users can navigate all 5 primary flows. EU EAA compliance for WCAG 2.1 AA semantic requirements.

**Acceptance criteria**:
- `GraphSwitcher` Surface announces role as button and state as "expanded/collapsed" to TalkBack
- `MobileBlockToolbar` formatting buttons use proper icons with `contentDescription` (no bare `Text("B")`)
- Indent and Outdent are in the primary formatting row alongside Bold/Italic/Code/Highlight/Link
- Strikethrough moves to overflow
- 5 TalkBack navigation flows tested manually and pass: graph open, page navigation, block editing, search, settings
- Android Accessibility Scanner reports no critical findings

---

#### TASK-5.1: GraphSwitcher Semantics + MobileBlockToolbar Icon Migration

**Objective**: Add semantic role to `GraphSwitcher`; replace text-label format buttons with proper icons and `contentDescription`.

**Files**:
- MODIFY: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/Sidebar.kt`
- MODIFY: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MobileBlockToolbar.kt`

**Size**: Medium (2h)

**Prerequisites**: None

**Implementation steps**:

1. In `Sidebar.kt`, `GraphSwitcher` composable (line ~193), add semantics to the `Surface`:
```kotlin
Surface(
    color = MaterialTheme.colorScheme.primaryContainer,
    shape = MaterialTheme.shapes.medium,
    modifier = Modifier
        .fillMaxWidth()
        .clickable { expanded = !expanded }
        .semantics {
            role = Role.Button
            stateDescription = if (expanded) "Expanded" else "Collapsed"
            contentDescription = "Graph switcher: $currentGraphName"
        }
)
```
Add imports: `import androidx.compose.ui.semantics.Role`, `import androidx.compose.ui.semantics.role`, `import androidx.compose.ui.semantics.semantics`, `import androidx.compose.ui.semantics.stateDescription`, `import androidx.compose.ui.semantics.contentDescription`.

2. In `MobileBlockToolbar.kt`, replace all `TextButton` + `Text(...)` format buttons in Row 1 with `IconButton` + `Icon` + `contentDescription`:

Replace Bold:
```kotlin
// Before:
TextButton(onClick = { onFormat(FormatAction.BOLD) }) {
    Text("B", fontWeight = FontWeight.Bold, fontSize = 16.sp)
}
// After:
IconButton(onClick = { onFormat(FormatAction.BOLD) }) {
    Icon(Icons.Default.FormatBold, contentDescription = "Bold")
}
```

Apply same pattern for:
- Italic → `Icons.Default.FormatItalic`, contentDescription = "Italic"
- Code → `Icons.Default.Code`, contentDescription = "Inline code"
- Highlight → `Icons.Default.Highlight`, contentDescription = "Highlight"
- Link → `Icons.Default.InsertLink`, contentDescription = "Insert link"

Move Strikethrough to an overflow button (see TASK-5.2). Remove the `TextButton` for Strikethrough from Row 1.

Add required icon imports at the top of the file.

**Validation**:
- With TalkBack enabled on Android: focus on `GraphSwitcher` — it announces "Graph switcher: [name], button, Collapsed/Expanded".
- With TalkBack enabled: focus on Bold button in toolbar — it announces "Bold, button".
- `./gradlew jvmTest` passes (no compilation errors from icon name changes).
- Icons are visually correct on device.

**INVEST check**:
- Independent: accessibility attributes don't affect layout or function
- Small: 2 files, targeted semantic additions
- Testable: TalkBack behavioral test; visual confirmation of icons

---

#### TASK-5.2: Indent/Outdent Promotion to Primary Toolbar Row

**Objective**: Move Indent and Outdent from Row 2 (structure actions) into Row 1 (format actions) so they are visible whenever a block is being edited; move Strikethrough to overflow.

**Files**:
- MODIFY: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MobileBlockToolbar.kt`

**Size**: Small (1h)

**Prerequisites**: TASK-5.1 complete

**Context**: After TASK-5.1, Row 1 has: Bold, Italic, Code, Highlight, Link (5 items). The design calls for 8 items: Bold, Italic, Code, Highlight, Link, Indent, Outdent, More. Strikethrough moves to More overflow.

**Implementation steps**:

1. In `MobileBlockToolbar.kt`, Row 1 (inside `if (editingBlockId != null)`), add Indent and Outdent after Link:
```kotlin
// After Link IconButton:
IconButton(onClick = { onIndent(editingBlockId) }) {
    Icon(Icons.Default.FormatIndentIncrease, contentDescription = "Indent block")
}
IconButton(onClick = { onOutdent(editingBlockId) }) {
    Icon(Icons.Default.FormatIndentDecrease, contentDescription = "Outdent block")
}
// More overflow button (for Strikethrough and future additions):
var showOverflow by remember { mutableStateOf(false) }
Box {
    IconButton(onClick = { showOverflow = true }) {
        Icon(Icons.Default.MoreHoriz, contentDescription = "More formatting options")
    }
    DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
        DropdownMenuItem(
            text = {
                Row {
                    Icon(Icons.Default.FormatStrikethrough, contentDescription = null,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Strikethrough")
                }
            },
            onClick = {
                onFormat(FormatAction.STRIKETHROUGH)
                showOverflow = false
            }
        )
    }
}
```

2. Remove Indent and Outdent from Row 2 (they are now in Row 1). Row 2 retains: Undo, Redo (always visible) + MoveUp, MoveDown, AddBlock (editing only).

3. Remove the Strikethrough `TextButton` from Row 1 (it was removed in TASK-5.1; this task adds its overflow home).

**Validation**:
- Primary row when editing shows: Bold, Italic, Code, Highlight, Link, Indent, Outdent, More (8 items, no horizontal scrolling required on a 360dp-wide phone)
- Tapping Indent indents the current block.
- Tapping Outdent outdents the current block.
- Tapping More opens dropdown with Strikethrough.
- With TalkBack: all 8 primary row items announce correctly.

**INVEST check**:
- Small: 1 file, restructured toolbar rows
- Testable: visual check + functional test on device

---

### Story 6: F6 — Material You Dynamic Color

**Independent** — lowest priority; can ship last or be deferred.

**User value**: Android 12+ users can opt into wallpaper-derived dynamic color via Settings.

**Acceptance criteria**:
- `StelekitThemeMode.DYNAMIC` enum value exists
- On Android 12+: selecting Dynamic in Settings changes app colors to match the wallpaper
- On Android 11 and below: Dynamic option is hidden in Settings
- On Desktop/iOS/Web: Dynamic option is hidden; if somehow persisted, falls back to SYSTEM behavior
- Extended colors (`bullet`, `indentGuide`, `sidebarBackground`, `blockRefBackground`) are derived from M3 roles in DYNAMIC mode
- No crash on Android 11 or below

---

#### TASK-6.1: StelekitThemeMode.DYNAMIC + expect/actual getDynamicColorScheme

**Objective**: Add `DYNAMIC` to the theme mode enum; create the `expect/actual` pair that supplies the wallpaper-derived color scheme.

**Files**:
- MODIFY: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/theme/Theme.kt`
- NEW: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/theme/DynamicColorScheme.kt`
- NEW: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/theme/DynamicColorScheme.android.kt`
- NEW: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/ui/theme/DynamicColorScheme.jvm.kt`

**Size**: Medium (3h)

**Prerequisites**: Understanding of `StelekitTheme` composable and `StelekitExtendedColors`

**Implementation steps**:

1. In `Theme.kt`, add `DYNAMIC` to `StelekitThemeMode`:
```kotlin
enum class StelekitThemeMode {
    LIGHT, DARK, SYSTEM, STONE, DYNAMIC
}
```

2. Create `commonMain/ui/theme/DynamicColorScheme.kt`:
```kotlin
package dev.stapler.stelekit.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/**
 * Returns a wallpaper-derived dynamic ColorScheme on Android 12+.
 * Returns null on Android 11 and below, and on all non-Android platforms.
 * Callers fall back to the static scheme when null is returned.
 */
@Composable
expect fun getDynamicColorScheme(isDark: Boolean): ColorScheme?

/**
 * True on Android 12+ (API 31+). False on all other platforms and API levels.
 * Used to conditionally show the Dynamic theme option in Settings.
 */
expect fun isDynamicColorAvailable(): Boolean
```

3. Create `androidMain/ui/theme/DynamicColorScheme.android.kt`:
```kotlin
package dev.stapler.stelekit.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun getDynamicColorScheme(isDark: Boolean): ColorScheme? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (isDark) dynamicDarkColorScheme(context)
        else dynamicLightColorScheme(context)
    } else null
}

actual fun isDynamicColorAvailable(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
```

4. Create `jvmMain/ui/theme/DynamicColorScheme.jvm.kt`:
```kotlin
package dev.stapler.stelekit.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

@Composable
actual fun getDynamicColorScheme(isDark: Boolean): ColorScheme? = null

actual fun isDynamicColorAvailable(): Boolean = false
```

5. In `StelekitTheme` in `Theme.kt`, add `DYNAMIC` handling. Add a `dynamicColorScheme` call and derive `extendedColors` from the scheme:
```kotlin
@Composable
fun StelekitTheme(
    themeMode: StelekitThemeMode = StelekitThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        StelekitThemeMode.LIGHT -> false
        StelekitThemeMode.DARK, StelekitThemeMode.STONE -> true
        StelekitThemeMode.DYNAMIC, StelekitThemeMode.SYSTEM -> isDarkThemeSystem
    }

    val dynamicScheme = if (themeMode == StelekitThemeMode.DYNAMIC) {
        getDynamicColorScheme(darkTheme)
    } else null

    val colorScheme = dynamicScheme ?: when (themeMode) {
        StelekitThemeMode.LIGHT -> LightColorScheme
        StelekitThemeMode.DARK -> DarkColorScheme
        StelekitThemeMode.STONE -> StoneColorScheme
        StelekitThemeMode.SYSTEM -> if (isDarkThemeSystem) DarkColorScheme else LightColorScheme
        StelekitThemeMode.DYNAMIC -> if (isDarkThemeSystem) DarkColorScheme else LightColorScheme
    }

    val extendedColors = if (dynamicScheme != null) {
        // Derive from M3 roles for contrast guarantee
        StelekitExtendedColors(
            bullet = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            indentGuide = colorScheme.outlineVariant.copy(alpha = 0.4f),
            sidebarBackground = colorScheme.surfaceVariant,
            blockRefBackground = colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    } else when (themeMode) {
        StelekitThemeMode.LIGHT -> LightExtendedColors
        StelekitThemeMode.DARK -> DarkExtendedColors
        StelekitThemeMode.STONE -> StoneExtendedColors
        StelekitThemeMode.SYSTEM -> if (isDarkThemeSystem) DarkExtendedColors else LightExtendedColors
        StelekitThemeMode.DYNAMIC -> if (isDarkThemeSystem) DarkExtendedColors else LightExtendedColors
    }
    ...
}
```

**Validation**:
- `./gradlew jvmTest` and `./gradlew testDebugUnitTest` pass.
- `./gradlew assembleDebug` compiles.
- On Android 11 emulator: `isDynamicColorAvailable()` returns false.
- On Android 12+ device: `isDynamicColorAvailable()` returns true; selecting DYNAMIC theme in Settings changes colors.

**INVEST check**:
- Independent: new files + Theme.kt additions; does not change existing theme behavior
- Estimable: 3h
- Testable: compile + device test on two API levels

---

#### TASK-6.2: Settings UI DYNAMIC Option

**Objective**: Show the Dynamic theme option in Settings only on Android 12+; add the Settings entry point for the new mode.

**Files**:
- MODIFY: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SettingsDialog.kt` (or wherever `StelekitThemeMode.entries.forEach` renders theme options)
- MODIFY: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TopBar.kt` (mobile overflow menu)

**Size**: Small (1h)

**Prerequisites**: TASK-6.1 complete

**Implementation steps**:

1. Locate where `StelekitThemeMode.entries.forEach` renders theme radio buttons or dropdown items in the settings UI (in `TopBar.kt` mobile overflow menu and/or `SettingsDialog.kt`).

2. Add a conditional filter to exclude `DYNAMIC` on platforms where it is not available:
```kotlin
StelekitThemeMode.entries
    .filter { mode -> mode != StelekitThemeMode.DYNAMIC || isDynamicColorAvailable() }
    .forEach { mode ->
        val label = when (mode) {
            StelekitThemeMode.LIGHT -> "Light"
            StelekitThemeMode.DARK -> "Dark"
            StelekitThemeMode.SYSTEM -> "System"
            StelekitThemeMode.STONE -> "Stone"
            StelekitThemeMode.DYNAMIC -> "Dynamic (Material You)"
        }
        // ... render as before
    }
```

3. The `isDynamicColorAvailable()` function from TASK-6.1 is available in `commonMain` via the `expect` declaration, so this filter is valid in `commonMain` code.

**Validation**:
- On Android 11 or Desktop: Dynamic option is absent from the theme list.
- On Android 12+: Dynamic option appears with label "Dynamic (Material You)".
- Selecting Dynamic on Android 12+ applies the wallpaper-derived scheme.
- Switching away from Dynamic (e.g., to Stone) restores the static Stone palette.

**INVEST check**:
- Small: 1-2 files, filter condition + label addition
- Testable: Settings UI on two Android API levels

---

## Known Issues

### Bug 001: adjustResize + Edge-to-Edge Conflict [SEVERITY: Critical — BLOCKER]

**Description**: `AndroidManifest.xml` line 25 has `android:windowSoftInputMode="adjustResize"`. This is architecturally incompatible with `enableEdgeToEdge()` on Android 11+. With `adjustResize` active, the system resizes the Activity window when the keyboard appears, which fights the edge-to-edge window management. Result: keyboard may appear but content does not scroll; focused `TextField` may be hidden behind the keyboard.

**Mitigation**: TASK-1.1 changes `adjustResize` to `adjustNothing`. TASK-1.5 adds `imePadding()` to scroll containers.

**Files affected**:
- `androidApp/src/main/AndroidManifest.xml` — source of conflict
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/JournalsView.kt` — scroll container needs `imePadding()`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/PageView.kt` — scroll container needs `imePadding()`

**Prevention strategy**: Do not change `adjustNothing` back to `adjustResize`. If keyboard avoidance is reported as broken on a specific API level, add `imePadding()` to that screen's scroll container — never revert to `adjustResize`.

**Related tasks**: TASK-1.1, TASK-1.5

---

### Bug 002: Double Inset Consumption on MainLayout [SEVERITY: Critical — BLOCKER]

**Description**: `MainLayout.kt` line 38 applies `Modifier.statusBarsPadding()` and line 110 applies `Spacer(Modifier.navigationBarsPadding())`. Once `NavigationBar` is added (which internally applies `WindowInsets.navigationBars`), the navigation bar inset is consumed twice — resulting in a double-height gap at the bottom. Similarly, if any future composable inside the layout tree reapplies `statusBarsPadding`, the status bar gap doubles.

**Mitigation**: TASK-1.3 removes both manual modifiers from `MainLayout`. TASK-1.4 moves `statusBarsPadding` ownership to `TopBar`. TASK-1.5 audits `SuggestionNavigatorPanel` for remaining double-consume risk.

**Files affected**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/MainLayout.kt` — lines 38, 110 must be removed
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SuggestionNavigatorPanel.kt` — line 71 audit required
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TopBar.kt` — gains `windowInsetsPadding(WindowInsets.statusBars)`

**Prevention strategy**: After F2 ships, run a periodic Grep for `BarsPadding\|safeContentPadding\|safeDrawingPadding` in `commonMain`. Any result inside a composable that is rendered within `MainLayout`'s content area is a double-consume candidate and requires investigation.

**Related tasks**: TASK-1.3, TASK-1.4, TASK-1.5

---

### Bug 003: Roborazzi Baselines Not Committed [SEVERITY: Medium — Process Risk]

**Description**: Current screenshot tests write to `build/outputs/roborazzi/*.png` (gitignored). No committed baseline directory exists. Roborazzi is operating in record-only mode — tests write images but do not assert anything. There is currently zero screenshot regression protection.

**Mitigation**: TASK-0 establishes baselines before any F1/F2 code changes. After F1+F2, run `./gradlew recordRoborazziJvm` again and commit updated images with a descriptive message.

**Files affected**:
- `kmp/build.gradle.kts` — verify Roborazzi output directory configuration
- All `*ScreenshotTest.kt` files under `jvmTest`

**Prevention strategy**: After the overhaul, add `./gradlew verifyRoborazziJvm` to the local `make test` or `make verify` target so baselines are checked on every test run.

**Related tasks**: TASK-0

---

### Bug 004: AnimatedContent Back-Gesture Double Animation Flicker [SEVERITY: Medium]

**Description**: On Android 14+ with predictive back enabled (`enableOnBackInvokedCallback="true"`), when the user initiates a back swipe, the system simultaneously starts the predictive back preview (shrinks the current screen to show the destination behind it) AND triggers the `AnimatedContent` exit animation when the gesture commits. The user sees two animations play on top of each other — the system shrink + the app's slide-out — which looks broken.

**Mitigation**: TASK-3.2 uses `ExitTransition.None` when `navDirection == NavDirection.BACK`, letting the system predictive back animation handle the visual. The `AnimatedContent` enter animation (slide-in from left) still plays for the incoming screen.

**Files affected**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` — `ScreenRouter` `AnimatedContent` transitionSpec
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt` — `NavDirection` enum + `navDirection` field

**Prevention strategy**: Any future `AnimatedContent` that responds to back navigation should suppress its `exitTransition` when the navigation direction is BACK. Document this pattern in a comment in `ScreenRouter`.

**Related tasks**: TASK-3.2

---

### Bug 005: StelekitExtendedColors Static Palette Won't Adapt to Dynamic Color [SEVERITY: Medium]

**Description**: In `DYNAMIC` mode, `MaterialTheme.colorScheme` changes to wallpaper-derived values. But `StelekitExtendedColors` — which defines `bullet`, `indentGuide`, `sidebarBackground`, `blockRefBackground` — is derived from static stone/parchment hues. On a cool-blue wallpaper, bullets remain warm stone, creating visible palette clash. On a warm-beige wallpaper, `PaleStone` bullets may be near-invisible against the dynamic background.

**Mitigation**: TASK-6.1 derives `StelekitExtendedColors` from M3 color roles in `DYNAMIC` mode: `bullet` from `onSurfaceVariant.copy(alpha = 0.6f)`, `indentGuide` from `outlineVariant.copy(alpha = 0.4f)`, `sidebarBackground` from `surfaceVariant`, `blockRefBackground` from `primaryContainer.copy(alpha = 0.3f)`. M3 roles have guaranteed contrast relationships within the M3 system.

**Files affected**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/theme/Theme.kt` — `StelekitTheme` extended colors branch for DYNAMIC

**Prevention strategy**: Never add a new `StelekitExtendedColors` field using a hardcoded hex color without providing a dynamic derivation branch.

**Related tasks**: TASK-6.1

---

### Bug 006: PaleStone Bullets Below 3:1 Contrast Threshold [SEVERITY: Low — Pre-existing]

**Description**: `PaleStone` (`#B8AFA0`) rendered on `ParchmentBackground` (`#F5F0E8`) has a contrast ratio of approximately 2.3:1. Bullets are interactive (they are the block expand/collapse target) and should meet the WCAG 2.1 AA 3:1 threshold for large interactive UI elements. This is a pre-existing issue not introduced by the overhaul but made more visible by the accessibility focus of F5.

**Mitigation**: Not addressed in this overhaul — track as a separate accessibility bug. Potential fix: darken `PaleStone` to approximately `#8A806E` which would achieve ~3.2:1 on `ParchmentBackground`.

**Files affected**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/theme/Theme.kt` — `PaleStone` color constant (in the colors file, likely `Colors.kt`)

**Prevention strategy**: When modifying the Parchment theme palette, run WCAG contrast checks on all `bullet` and `indentGuide` color combinations against `ParchmentBackground`.

**Related tasks**: None in this overhaul — file as separate issue.

---

## Integration Checkpoints

### Checkpoint 1: After TASK-0 (Pre-work)
- `./gradlew verifyRoborazziJvm` passes with committed baselines.
- No code changes to any source file.

### Checkpoint 2: After Story 1+2 (F1+F2 complete)
Verify on a physical Android phone (ideally Android 14, gesture navigation):
- `NavigationBar` visible at the bottom with 4 items.
- Journals tab selected on app launch.
- Tapping each tab navigates correctly.
- Navigating via wiki link to a page highlights the Pages tab.
- Content draws behind the status bar (status bar area shows TopBar background color, not a white gap).
- Content draws behind the navigation bar (NavigationBar component fills that area).
- No double-height gap at top or bottom.
- Tapping a block to edit: keyboard appears, content scrolls above keyboard.
- On Desktop JVM: layout unchanged from pre-F1 (no bottom bar, sidebar present).
- `./gradlew jvmTest` passes.
- Record and commit updated Roborazzi baselines.

### Checkpoint 3: After Story 3 (F3 complete)
- On Android 14+ with gesture navigation: slow back swipe shows system predictive preview (destination visible behind shrinking current screen). Release: navigates back without double animation.
- Forward navigation (tap page link): slide-in from right.
- Back navigation (button): no exit animation on departing screen.
- In editing mode: pressing back clears focus, does not navigate.
- In selection mode: pressing back clears selection, does not navigate.

### Checkpoint 4: After Stories 4+5 (F4+F5 complete)
- All mobile `IconButton` elements are visibly larger (48dp).
- With TalkBack enabled: all 5 primary flows navigable without sight.
- `GraphSwitcher` announces role and state.
- All toolbar format buttons announce their action.
- Primary toolbar row shows Bold, Italic, Code, Highlight, Link, Indent, Outdent, More.

### Checkpoint 5: After Story 6 (F6 complete — Final)
- On Android 12+ device with colorful wallpaper: Settings shows "Dynamic (Material You)"; selecting it changes app colors.
- Bullets, sidebar background, block references remain visible in dynamic mode.
- On Android 11: Dynamic option absent from Settings.
- On Desktop: Dynamic option absent from Settings.
- `./gradlew jvmTest` and `./gradlew testDebugUnitTest` pass.

---

## Context Preparation Guide

### TASK-0 context
- Read: `kmp/build.gradle.kts` (Roborazzi plugin configuration section)
- Command: `./gradlew recordRoborazziJvm`

### TASK-1.1 context
- Read: `androidApp/src/main/AndroidManifest.xml` (full file — 35 lines)
- Understand: `adjustResize` vs `adjustNothing` distinction (pitfalls research)

### TASK-1.2 context
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/ModifierExtensions.kt` (expect declaration pattern)
- Read: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/ModifierExtensions.android.kt` (actual pattern)
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt` (Screen sealed class, all subtypes)
- Read: ADR-001, ADR-003

### TASK-1.3 context
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/MainLayout.kt` (full file — 113 lines)
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` (lines 260–358: `MainLayout` call in `GraphContent`)
- Read: ADR-001, ADR-002

### TASK-1.4 context
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TopBar.kt` (lines 46–118: mobile Row)
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` (lines 456–503: `GraphDialogLayer` with `NotificationOverlay`)
- Read: ADR-002

### TASK-1.5 context
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/JournalsView.kt` (locate scroll container)
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/PageView.kt` (locate scroll container)
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SuggestionNavigatorPanel.kt` (line 71 context)
- Grep: `navigationBarsPadding\|statusBarsPadding` in `commonMain` for any remaining instances

### TASK-3.1 context
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/PlatformBackHandler.kt` (once created in this task)
- Read: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/ui/ModifierExtensions.android.kt` (pattern reference)
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/PageView.kt` (editing mode state)
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt` (`editingBlockId` field)

### TASK-3.2 context
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` (lines 402–448: `ScreenRouter` composable)
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt` (`historyIndex`, `navigationHistory`)
- Read: ADR-003 (for NavDirection state ownership rationale)

### TASK-4.1 context
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TopBar.kt` (lines 55–118: mobile `IconButton` calls)
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/Sidebar.kt` (lines 73–90: close button; lines 340–360: `GraphItem` delete button; lines 465–475: `SidebarItem` favorite button)

### TASK-5.1 context
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/Sidebar.kt` (lines 183–218: `GraphSwitcher` Surface)
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MobileBlockToolbar.kt` (lines 74–113: format button row)
- Reference: Material Icons list for `FormatBold`, `FormatItalic`, `Code`, `Highlight`, `InsertLink`

### TASK-5.2 context
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MobileBlockToolbar.kt` (full file — 163 lines; after TASK-5.1 changes)
- Read: features research P5 for 8-item primary row rationale

### TASK-6.1 context
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/theme/Theme.kt` (full file — 158 lines)
- Read: ADR-004
- Reference: `androidx.compose.material3.dynamicDarkColorScheme`, `dynamicLightColorScheme` API

### TASK-6.2 context
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SettingsDialog.kt` (full file)
- Read: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TopBar.kt` (lines 150–172: theme mode dropdown in mobile overflow)
- Read: ADR-004
