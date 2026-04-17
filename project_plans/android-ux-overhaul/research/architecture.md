# Architecture Research: Android Bottom Nav Split in SteleKit KMP

**Research dimension**: Architecture
**Authored**: 2026-04-12
**Status**: Complete

---

## Executive Summary

SteleKit already uses `expect/actual` for a platform-specific `Modifier` extension
(`platformNavigationInput`), proving the pattern compiles and works in this exact
build setup. That existing precedent, combined with the structure of `MainLayout.kt`
and `App.kt`, makes the architectural choice for Android bottom nav straightforward.

**Recommendation: Option B — inject a `bottomBar` slot into `MainLayout`.**

The existing `MainLayout` already takes `topBar`, `statusBar`, `leftSidebar`, and
`rightSidebar` as `@Composable () -> Unit` lambda parameters. Adding a `bottomBar`
slot follows the identical pattern already established in the file: `commonMain`
provides the slot, `androidMain` fills it, all other platforms pass an empty lambda.
This avoids creating a new `expect/actual` file, avoids a new platform-specific `App.kt`
fork, and keeps the full navigation wiring in `commonMain` where `AppState` and
`StelekitViewModel` already live.

Option A (`expect/actual AppLayout`) adds a new file boundary for little gain.
Option C (platform-specific `App.kt`) duplicates the entire `GraphContent` wiring or
requires an awkward outer wrapper that itself needs the `AppState` and ViewModel
references — state that lives deep inside `GraphContent`.

---

## Codebase Baseline

### Key files

| File | Role |
|---|---|
| `commonMain/ui/App.kt` | `StelekitApp` → `GraphContent` → `MainLayout` + `ScreenRouter` |
| `commonMain/ui/MainLayout.kt` | Layout skeleton with composable-slot parameters |
| `commonMain/ui/AppState.kt` | `Screen` sealed class + `AppState` data class |
| `commonMain/ui/ModifierExtensions.kt` | `expect fun Modifier.platformNavigationInput(...)` |
| `androidMain/ui/ModifierExtensions.android.kt` | `actual` (no-op for Android) |
| `jvmMain/ui/ModifierExtensions.jvm.kt` | `actual` (mouse button routing) |

### Existing `MainLayout` slot signature

```kotlin
@Composable
fun MainLayout(
    isMobile: Boolean = false,
    sidebarExpanded: Boolean = false,
    onSidebarDismiss: () -> Unit = {},
    topBar: @Composable () -> Unit,
    leftSidebar: @Composable () -> Unit,
    rightSidebar: @Composable () -> Unit,
    content: @Composable () -> Unit,
    statusBar: @Composable () -> Unit   // <-- existing slot precedent
)
```

The `statusBar` slot is already conditionally filled in `App.kt`:

```kotlin
statusBar = {
    if (!isMobile) {
        StatusBarContent(...)
    }
}
```

This exact pattern is the template for adding `bottomBar`.

### Existing `expect/actual` precedent

`ModifierExtensions.kt` / `.android.kt` / `.jvm.kt` shows the project already builds
`expect/actual` across `commonMain`, `androidMain`, and `jvmMain` without issues.
The pattern is usable; the question is whether it is *necessary* for bottom nav.

---

## Option Evaluation

### Option A — `expect/actual AppLayout` composable

`commonMain` declares:

```kotlin
// commonMain/ui/AppLayout.kt
@Composable
expect fun AppLayout(
    isMobile: Boolean,
    content: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit
)
```

`androidMain` implements with `Scaffold` + `NavigationBar`;
`jvmMain` and `iosMain` implement with the plain existing layout.

| Pros | Cons |
|---|---|
| Hard platform split — Android gets its own `Scaffold` with full M3 inset handling | New file for every target (`androidMain`, `jvmMain`, `iosMain`, `jsMain`) |
| `Scaffold`'s `contentWindowInsets` handles inset threading automatically | `expect` composable forces `AppState` / ViewModel refs to be passed down through the `expect` signature, or require `CompositionLocal` indirection |
| Cleanest conceptual model | All navigation wiring already lives in `GraphContent` → `MainLayout` — moving it into an `expect` composable means either duplicating or threading those dependencies |
| | Breaks the existing single-file call site in `App.kt`; `GraphContent` must call `AppLayout` instead of `MainLayout`, making it harder to follow |
| | `jvmMain` actual is identical to `MainLayout`, creating dead weight |

**Verdict**: Principled but over-engineered for what is a one-platform addition.
The `expect` mechanism is the right tool when the *interface* diverges across all
platforms; here only Android differs.

---

### Option B — `bottomBar` slot on `MainLayout` (Recommended)

Add one parameter to `MainLayout`:

```kotlin
@Composable
fun MainLayout(
    ...
    statusBar: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit = {}   // <-- new
)
```

In `App.kt` (`GraphContent`), pass a platform-specific composable via a
`platformBottomBar()` `expect fun`:

```kotlin
// commonMain/ui/PlatformBottomBar.kt
@Composable
expect fun PlatformBottomBar(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit
)
```

```kotlin
// androidMain/ui/PlatformBottomBar.android.kt
@Composable
actual fun PlatformBottomBar(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit
) {
    NavigationBar {
        BottomNavItem.entries.forEach { item ->
            NavigationBarItem(
                selected = currentScreen == item.screen,
                onClick = { onNavigate(item.screen) },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) }
            )
        }
    }
}
```

```kotlin
// jvmMain/ui/PlatformBottomBar.jvm.kt  (and iosMain, jsMain)
@Composable
actual fun PlatformBottomBar(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit
) { /* no-op */ }
```

Call site in `App.kt` — inside `GraphContent`, the `MainLayout` call gains:

```kotlin
MainLayout(
    ...
    bottomBar = {
        PlatformBottomBar(
            currentScreen = appState.currentScreen,
            onNavigate = { viewModel.navigateTo(it) }
        )
    }
)
```

Inside `MainLayout`, the slot is rendered at the bottom of the `Column`, replacing
the existing `Spacer(Modifier.navigationBarsPadding())`:

```kotlin
// Replace:
if (isMobile) {
    Spacer(modifier = Modifier.navigationBarsPadding())
}

// With:
bottomBar()
// (NavigationBar internally applies windowInsets = WindowInsets.navigationBars,
//  so no extra Spacer needed when bottomBar is non-empty)
```

| Pros | Cons |
|---|---|
| Zero new file boundaries beyond one `expect` file | One more `expect/actual` file — but the codebase already has this pattern |
| Follows the exact existing `statusBar` slot pattern; reviewers immediately understand it | `MainLayout` grows one parameter — negligible |
| All navigation state (`AppState.currentScreen`, `viewModel.navigateTo`) stays in `commonMain` | |
| No `Scaffold` required — avoids inset double-consumption risk (see Insets section) | |
| Desktop, iOS, Web: no change to rendered output — no-op `actual` | |
| `isMobile` guard can remain in `MainLayout` if needed, but `PlatformBottomBar` is already the correct guard | |
| Easy to test: `MainLayout` is fully testable in `jvmTest` since `bottomBar` is just a slot | |

**Verdict**: Minimum-invasive, consistent with existing codebase conventions, keeps
all state in `commonMain`. This is the recommended approach.

---

### Option C — Platform-specific `App.kt` entry points

`androidMain` provides its own `App.kt` (or `AndroidLayout.kt`) that wraps the
existing `commonMain` output inside an Android-specific `Scaffold`:

```kotlin
// androidMain/ui/AndroidRootLayout.kt
@Composable
fun AndroidRootLayout(appState: AppState, viewModel: StelekitViewModel) {
    Scaffold(
        bottomBar = { AndroidBottomNav(appState.currentScreen) { viewModel.navigateTo(it) } },
        contentWindowInsets = WindowInsets.safeContent
    ) { innerPadding ->
        // Pass innerPadding to commonMain content
        StelekitAppContent(modifier = Modifier.padding(innerPadding))
    }
}
```

| Pros | Cons |
|---|---|
| `Scaffold` handles insets correctly out of the box | `StelekitApp` / `GraphContent` don't expose a modifier-accepting surface — refactoring them to accept `innerPadding` would require threading `Modifier` through 3 composable levels |
| Cleanest M3 pattern for Android | `AppState` and `StelekitViewModel` must be hoisted above `StelekitApp`, or `AndroidRootLayout` must re-create them — duplicating ViewModel initialization logic |
| | `StelekitApp` is the entry point for ALL platforms; splitting it requires either an `expect/actual` on the root composable (biggest possible surface area) or adding an Android-specific Activity wrapper composable that reaches inside `GraphContent` |
| | `GraphContent` is `private` — correctly so per ADR-001; Option C forces it public or requires significant restructuring |
| | The requirements doc explicitly constrains: "No breaking ViewModel changes" and "navigation restructure happens at the layout/composable layer" |

**Verdict**: Creates more coupling problems than it solves. The clean M3 `Scaffold`
story is appealing but is not worth the restructuring cost. Inset handling can be
solved correctly within Option B (see below).

---

## Screen Routing: Keeping `ScreenRouter` in `commonMain`

`ScreenRouter` is `private` in `App.kt` and already in `commonMain`. Under Option B,
it stays entirely untouched. The event flow is:

```
User taps NavigationBar item (androidMain)
  → calls onNavigate(screen)            [lambda from commonMain call site]
  → calls viewModel.navigateTo(screen)  [StelekitViewModel, commonMain]
  → updates AppState.currentScreen      [StateFlow, commonMain]
  → ScreenRouter re-composes            [commonMain, reads appState.currentScreen]
  → PlatformBottomBar re-composes       [androidMain, reads currentScreen prop]
```

The `PlatformBottomBar` receives `currentScreen: Screen` as a prop (derived from
`appState.currentScreen` at the call site in `App.kt`). This is a one-way data flow:
state down, events up. The `Screen` sealed class is `commonMain` and is referenced
freely from `androidMain` — no cross-module visibility issue.

The `BottomNavItem` enum (or sealed class) mapping icons/labels to `Screen` values
lives in `androidMain` since it references Material icons:

```kotlin
// androidMain/ui/BottomNavItem.kt
enum class BottomNavItem(
    val screen: Screen,
    val icon: ImageVector,
    val label: String
) {
    JOURNALS(Screen.Journals, Icons.Default.Book, "Journals"),
    ALL_PAGES(Screen.AllPages, Icons.Default.Pages, "Pages"),
    FLASHCARDS(Screen.Flashcards, Icons.Default.Style, "Flashcards"),
    NOTIFICATIONS(Screen.Notifications, Icons.Default.Notifications, "Notifications")
}
```

This keeps icon dependencies (which are Android/Compose-specific imports) out of
`commonMain` while keeping the routing logic (`Screen` values) shared.

---

## State Management: Derived vs. Local `BottomNavigation` State

**Decision: derive from `AppState.currentScreen` — no local state.**

### Tradeoffs

| | Derived from `AppState.currentScreen` | Local `var selectedTab` state |
|---|---|---|
| Source of truth | Single (`AppState`) | Split — local state can diverge from actual screen |
| Back/forward nav | Works automatically — history changes `currentScreen`, bar updates | Must sync with history manually |
| Deep links / programmatic navigation | Bar updates for free | Must be explicitly reset |
| Testing | Trivial — pass any `Screen` to `PlatformBottomBar`, observe selection | Harder — internal state not accessible |
| Scroll-to-top on re-tap | Requires checking if already selected in `onNavigate` | Same |
| Complexity | Zero — no additional state | Medium — requires `LaunchedEffect` sync or `DisposableEffect` |

The existing `AppState.navigationHistory` + `historyIndex` design already handles
back/forward correctly. Deriving the bottom bar selection from `currentScreen` means
the bar always reflects truth, including when the user taps a link inside a page that
navigates to `AllPages`, or when the desktop keyboard shortcut history is exercised
(irrelevant on Android but harmless).

Re-tap same tab (scroll-to-top idiom): if `currentScreen == item.screen`, emit a
scroll-to-top event rather than calling `navigateTo`. This is handled in `onNavigate`
at the call site or inside `PlatformBottomBar` with an additional `onScrollToTop` lambda.

---

## Inset Threading: Who Owns `WindowInsets` Consumption

This is the highest-risk detail for edge-to-edge. The requirements confirm F1 and F2
must ship together, which means `MainLayout` will be restructured at the same time the
bottom nav is added.

### Current state (broken for edge-to-edge)

```kotlin
// MainLayout.kt line 38 — blocks edge-to-edge
.then(if (isMobile) Modifier.statusBarsPadding() else Modifier)

// MainLayout.kt line 110 — partially correct but orphaned
if (isMobile) {
    Spacer(modifier = Modifier.navigationBarsPadding())
}
```

### Correct inset model under Option B (no `Scaffold`)

Under Option B, `MainLayout` owns a `Column` that is the full-screen container.
The correct strategy is **consume insets exactly once at the correct layer**:

```
WindowInsets.statusBars   → consumed by topBar content (TopBar.kt gets windowInsetsPadding(WindowInsets.statusBars))
WindowInsets.navigationBars → consumed by NavigationBar itself (Material3 NavigationBar defaults to this)
WindowInsets.ime          → consumed by the editing content area
```

`NavigationBar` in Material3 automatically applies `windowInsets = WindowInsets.navigationBars`
by default. So when `bottomBar` slot contains a `NavigationBar`:

1. Remove `Spacer(Modifier.navigationBarsPadding())` from `MainLayout` — the
   `NavigationBar` already consumes those insets.
2. Remove `.statusBarsPadding()` from `MainLayout` — move status bar padding into
   `TopBar.kt` using `Modifier.windowInsetsPadding(WindowInsets.statusBars)` or
   wrap `TopBar` content in `TopAppBar` which handles this automatically.
3. Remove hardcoded `padding(bottom = 32.dp)` from `NotificationOverlay` in
   `GraphDialogLayer` — replace with `WindowInsets.navigationBars` adjusted for
   the bottom nav bar height.

When `bottomBar` is empty (Desktop, iOS, Web), the `NavigationBar` is absent and
no nav-bar inset is consumed by it — the non-mobile path in `MainLayout` doesn't use
`navigationBarsPadding()` today and the desktop `Spacer` is already guarded by
`if (isMobile)`, so removing it is safe.

### Double-consumption risk

Double-consumption happens when both a parent and a child call
`Modifier.windowInsetsPadding(WindowInsets.navigationBars)`. Under Option B:

- **Parent** (`MainLayout` `Column`): must NOT apply `navigationBarsPadding()` itself.
- **Child** (`NavigationBar` via `bottomBar` slot): applies it automatically.

The current `Spacer(Modifier.navigationBarsPadding())` at line 110 of `MainLayout`
must be removed as part of the F1+F2 combined change. If it is left in AND a
`NavigationBar` is also rendered, both consume the inset and the nav bar area is
double-padded — a 2× gutter at the bottom.

IME (soft keyboard) insets are a separate concern. The `windowSoftInputMode` conflict
noted in the requirements must be resolved in `AndroidManifest.xml` (`adjustResize` is
incompatible with edge-to-edge; use `adjustPan` or no value, relying on Compose's
`WindowInsets.ime` instead). The content area `Box` in `MainLayout` should apply
`Modifier.imePadding()` or the editing screen should — not `MainLayout` globally,
since not all screens have keyboards.

### Option A `Scaffold` comparison

`Scaffold` with `contentWindowInsets = WindowInsets.safeContent` would handle all of
this automatically. The reason Option B is still preferred is that `Scaffold`'s
`innerPadding` must be threaded down to the content composable, which requires
`MainLayout`'s `content` lambda to accept a `PaddingValues` — a breaking signature
change that ripples into every screen composable called from `ScreenRouter`. The
manual inset approach in Option B is slightly more work but does not change any
screen composable signatures.

---

## Recommendation: Option B

### Rationale summary

1. **Minimum invasive**: `MainLayout` gains one parameter with a no-op default;
   all other signatures unchanged.
2. **Consistent with existing code**: mirrors the `statusBar` slot and the
   `platformNavigationInput` `expect/actual` pattern already in the repo.
3. **State stays in `commonMain`**: `AppState.currentScreen` drives the bar;
   `viewModel.navigateTo` handles selection — zero new state.
4. **Inset control is explicit**: No hidden `Scaffold` magic; inset consumption
   is auditable in two places (`TopBar.kt` for status bar, `NavigationBar` for nav bar).
5. **No restructuring of `GraphContent`**: which is `private` per ADR-001 and should
   stay that way.
6. **`jvmTest` / Roborazzi compatibility**: `MainLayout` is testable on JVM; the
   `bottomBar` slot receives `{}` in tests, so existing screenshot baselines require
   only a mechanical update (layout is identical on JVM).

---

## Proposed File Structure

```
kmp/src/
  commonMain/kotlin/dev/stapler/stelekit/ui/
    MainLayout.kt                        # +1 param: bottomBar: @Composable () -> Unit = {}
    App.kt                               # MainLayout call gains bottomBar = { PlatformBottomBar(...) }
    PlatformBottomBar.kt                 # NEW: expect fun PlatformBottomBar(currentScreen, onNavigate)

  androidMain/kotlin/dev/stapler/stelekit/ui/
    PlatformBottomBar.android.kt         # NEW: actual — NavigationBar with 4 items
    BottomNavItem.kt                     # NEW: enum Screen→icon→label mapping

  jvmMain/kotlin/dev/stapler/stelekit/ui/
    PlatformBottomBar.jvm.kt             # NEW: actual — empty composable

  # iosMain and jsMain get identical empty actuals when those targets are enabled
```

Files that must be modified (not created):

| File | Change |
|---|---|
| `MainLayout.kt` | Add `bottomBar` slot; remove `Spacer(navigationBarsPadding)` on mobile; remove `statusBarsPadding()` (F2 scope) |
| `App.kt` | Pass `bottomBar = { PlatformBottomBar(appState.currentScreen) { viewModel.navigateTo(it) } }` to `MainLayout`; fix `NotificationOverlay` padding (F2 scope) |
| `TopBar.kt` | Add `windowInsetsPadding(WindowInsets.statusBars)` on mobile path (F2 scope) |
| `AndroidManifest.xml` | `windowSoftInputMode` fix; `enableOnBackInvokedCallback` (F3 scope) |

---

## API Sketch (Recommended Approach)

### `commonMain/ui/PlatformBottomBar.kt`

```kotlin
package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable

/**
 * Platform-specific primary navigation bar.
 * Android: MD3 NavigationBar with 4 primary destinations.
 * Desktop / iOS / Web: empty composable — navigation is via sidebar.
 */
@Composable
expect fun PlatformBottomBar(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit
)
```

### `androidMain/ui/PlatformBottomBar.android.kt`

```kotlin
package dev.stapler.stelekit.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

private enum class BottomNavItem(
    val screen: Screen,
    val icon: ImageVector,
    val label: String
) {
    JOURNALS(Screen.Journals, Icons.Default.Book, "Journals"),
    ALL_PAGES(Screen.AllPages, Icons.Default.Article, "Pages"),
    FLASHCARDS(Screen.Flashcards, Icons.Default.Style, "Flashcards"),
    NOTIFICATIONS(Screen.Notifications, Icons.Default.Notifications, "Notifications")
}

@Composable
actual fun PlatformBottomBar(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit
) {
    NavigationBar {
        BottomNavItem.entries.forEach { item ->
            NavigationBarItem(
                selected = currentScreen == item.screen,
                onClick = { onNavigate(item.screen) },
                icon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label
                    )
                },
                label = { Text(item.label) }
            )
        }
    }
}
```

### `jvmMain/ui/PlatformBottomBar.jvm.kt`

```kotlin
package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBottomBar(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit
) { /* Desktop uses sidebar navigation — no bottom bar */ }
```

### `commonMain/ui/MainLayout.kt` — delta

```kotlin
@Composable
fun MainLayout(
    isMobile: Boolean = false,
    sidebarExpanded: Boolean = false,
    onSidebarDismiss: () -> Unit = {},
    topBar: @Composable () -> Unit,
    leftSidebar: @Composable () -> Unit,
    rightSidebar: @Composable () -> Unit,
    content: @Composable () -> Unit,
    statusBar: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit = {}    // NEW — no-op default keeps Desktop unchanged
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // REMOVE: .then(if (isMobile) Modifier.statusBarsPadding() else Modifier)
            // Status bar inset now handled in TopBar.kt (F2 scope)
    ) {
        topBar()

        if (isMobile) {
            // ... existing mobile Box with scrim + overlay sidebar ...
        } else {
            // ... existing desktop Row ...
        }

        statusBar()
        bottomBar()   // NEW — NavigationBar handles its own nav-bar insets internally
        // REMOVE: if (isMobile) { Spacer(Modifier.navigationBarsPadding()) }
    }
}
```

### `commonMain/ui/App.kt` — delta (GraphContent, MainLayout call)

```kotlin
MainLayout(
    isMobile = isMobile,
    sidebarExpanded = appState.sidebarExpanded,
    onSidebarDismiss = { viewModel.toggleSidebar() },
    topBar = { TopBar(...) },
    leftSidebar = { LeftSidebar(...) },
    rightSidebar = { RightSidebar(...) },
    content = { ScreenRouter(...) },
    statusBar = { if (!isMobile) StatusBarContent(...) },
    bottomBar = {                                          // NEW
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

---

## Open Questions for Implementation

1. **Re-tap scroll-to-top**: When the user taps the already-selected tab, the
   conventional Android behavior is scroll to top. This requires an event channel
   (e.g., `SharedFlow<Unit>` per screen) hoisted into each ViewModel or passed via
   `CompositionLocal`. Defer to F1 implementation; start with simple `navigateTo`
   re-call (which is a no-op in current history logic — needs a check).

2. **`PageView` screen and bottom nav selection**: When navigating to a
   `Screen.PageView(page)`, none of the 4 bottom nav items should appear selected
   (or "Pages" could be highlighted as the parent tab). Define the mapping in
   `BottomNavItem` — e.g., add a `matchesScreen(Screen): Boolean` method.

3. **Tablet breakpoint**: The `isMobile = maxWidth < 600.dp` breakpoint is the
   existing signal. On tablets (width >= 600dp), the sidebar returns and the bottom
   nav should be absent — or replaced with `NavigationRail`. The `PlatformBottomBar`
   `expect` signature could accept `isMobile: Boolean` to conditionally render nothing
   on tablets, keeping all breakpoint logic in `commonMain`.

4. **Roborazzi baselines**: `MainLayout` tests will need baseline images regenerated.
   The JVM actual of `PlatformBottomBar` is empty, so JVM screenshot tests will show
   the same layout as before (minus the `navigationBarsPadding` spacer, which was
   invisible anyway). Baseline regeneration should be low-effort.
