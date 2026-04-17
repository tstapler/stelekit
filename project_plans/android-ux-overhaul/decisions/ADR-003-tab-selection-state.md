# ADR-003: Tab Selection State — Derive from AppState.currentScreen, No Local State

**Status**: Accepted
**Date**: 2026-04-12
**Feature**: Android UX Overhaul — F1 Android Bottom Navigation

---

## Context

The `NavigationBar` composable requires a `selected: Boolean` per `NavigationBarItem`. There are two strategies for supplying this value:

**Option A — Local state**: `PlatformBottomBar` holds `var selectedTab by remember { mutableStateOf(BottomNavItem.JOURNALS) }`. Selection is updated in `onClick` handler. Navigation is triggered from the same `onClick`.

**Option B — Derive from `AppState.currentScreen`**: `PlatformBottomBar` receives `currentScreen: Screen` as a prop (derived from `appState.currentScreen` at the call site in `App.kt`). `selected = (currentScreen == item.screen)`. No internal state.

## Decision

**Option B — Derive from `AppState.currentScreen`.**

`PlatformBottomBar` is a stateless presentational composable. Its `selected` value for each item is computed as `currentScreen == item.screen`. The `currentScreen` prop is `appState.currentScreen` passed from `GraphContent` in `App.kt`.

For `Screen.PageView(page)` — which maps to no specific tab — the `AllPages` tab is highlighted (PageView is a page within the All Pages context). This mapping is encoded in `BottomNavItem.matchesScreen(screen: Screen): Boolean`:

```kotlin
fun matchesScreen(screen: Screen): Boolean = when (this) {
    JOURNALS -> screen is Screen.Journals
    ALL_PAGES -> screen is Screen.AllPages || screen is Screen.PageView
    FLASHCARDS -> screen is Screen.Flashcards
    NOTIFICATIONS -> screen is Screen.Notifications
}
```

Re-tap behavior (tap the already-selected tab): the `onNavigate` lambda at the call site checks `if (currentScreen == screen) { /* emit scroll-to-top signal */ } else { viewModel.navigateTo(screen) }`. A `SharedFlow<Unit>` per screen (deferred to a follow-up) handles scroll-to-top; for the initial ship, re-tapping is a no-op.

## Rationale

| Criterion | Option A (Local State) | Option B (Derived, chosen) |
|---|---|---|
| Source of truth | Split — local + AppState | Single — AppState |
| Back navigation | Requires `LaunchedEffect` sync | Works automatically |
| Programmatic navigation (e.g., tap a wiki link) | Local state diverges until user taps bottom bar | Bar updates automatically |
| Deep link / graph switch | Requires explicit reset | Works automatically |
| Testing `PlatformBottomBar` | Must manipulate internal state | Pass any `Screen` prop, assert `selected` |
| Complexity | Medium (LaunchedEffect sync or DisposableEffect) | Zero — no additional state |

`AppState.navigationHistory` + `historyIndex` already handle back/forward navigation correctly. Deriving the bottom bar from `currentScreen` means it is always consistent with that history — the user can navigate via wiki links, search, back gesture, or bottom bar tap and the selected tab always reflects reality.

## Consequences

**Positive:**
- Zero state duplication. `AppState.currentScreen` is the single source of truth for navigation.
- `PlatformBottomBar` is trivially testable: pass `currentScreen = Screen.AllPages`, assert `ALL_PAGES` item is selected, assert others are not.
- Back swipe on Android automatically updates `appState.currentScreen` via `BackHandler` → `viewModel.goBack()`, so the bottom bar reflects the correct destination without any bottom-bar-specific back-handling code.

**Negative / Watch-outs:**
- `Screen.Logs` and `Screen.Performance` are not represented in `BottomNavItem`. When these screens are active (reachable from Settings → Developer Tools after F1), no bottom bar tab will be highlighted. This is intentional — they are developer tools, not primary destinations — but may look odd if a user navigates to them. Mitigation: the bottom bar remains visible (no selected tab) so the user can still tap a primary destination to exit.
- The `PageView` → `ALL_PAGES` tab mapping in `matchesScreen` is a product decision encoded in `BottomNavItem`. If the product vision changes (e.g., pages navigated from Journals should highlight the Journals tab), this mapping must be updated. Document this logic with a comment in `BottomNavItem`.

## Patterns Applied

- **Unidirectional Data Flow (UDF)**: State flows down (`currentScreen` as prop), events flow up (`onNavigate` lambda). No two-way data binding or local state that can diverge.
- **Single Source of Truth (SSOT)**: `AppState.currentScreen` is the authoritative navigation state. `PlatformBottomBar` is a pure function of that state.
- **Predictable UI**: Given the same `currentScreen` value, `PlatformBottomBar` always renders identically. This makes the component deterministic and screenshot-testable.
