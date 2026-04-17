# ADR-002: Inset Ownership — Scaffold Owns Insets, MainLayout Strips Manual Modifiers

**Status**: Accepted
**Date**: 2026-04-12
**Feature**: Android UX Overhaul — F2 Edge-to-Edge Layout

---

## Context

When `enableEdgeToEdge()` is active (already present in `MainActivity.onCreate()` at line 36), the app window extends behind the system status bar and navigation bar. To prevent content from being obscured, insets must be applied exactly once somewhere in the layout tree.

The current `MainLayout.kt` applies insets manually in two places:
- Line 38: `.then(if (isMobile) Modifier.statusBarsPadding() else Modifier)` — adds top padding equal to the status bar height on mobile
- Line 110: `Spacer(modifier = Modifier.navigationBarsPadding())` — adds bottom space equal to the navigation bar height on mobile

`App.kt` also has a hardcoded override:
- Line 502: `NotificationOverlay(notificationManager, modifier = Modifier.padding(bottom = 32.dp))` — magic constant instead of inset-aware padding

Additionally, `SuggestionNavigatorPanel.kt` line 71 applies `.navigationBarsPadding()` directly on a panel modifier — this will double-consume once the scaffold model is in place if this panel is inside the Scaffold content area.

The question is: which layer should own inset consumption?

## Decision

**`MainLayout` (via `Column`) is restructured so that:**
1. `Modifier.statusBarsPadding()` is removed from line 38; instead `TopBar.kt`'s mobile path applies `Modifier.windowInsetsPadding(WindowInsets.statusBars)` to its `Row`, making the status bar inset consumption localized to the component that visually fills that space.
2. `Spacer(Modifier.navigationBarsPadding())` is removed from line 110; the `NavigationBar` composable (placed via the `bottomBar` slot from ADR-001) internally applies `windowInsets = WindowInsets.navigationBars` by default, consuming the nav bar inset in the component that visually fills it.
3. `NotificationOverlay` magic padding `32.dp` is replaced with `Modifier.windowInsetsPadding(WindowInsets.navigationBars)` so it correctly positions itself above the navigation bar on all nav-mode configurations.
4. `SuggestionNavigatorPanel.kt` line 71 `.navigationBarsPadding()` is audited: if the panel is rendered inside the Scaffold content area, the modifier is removed; if it floats as an overlay outside the Scaffold content, it is retained.
5. IME (keyboard) insets are handled at the scrollable content level: `windowSoftInputMode` is changed from `adjustResize` to `adjustNothing` in `AndroidManifest.xml`, and `Modifier.imePadding()` is applied to the `LazyColumn` or scroll container inside block editing screens (not at the `MainLayout` level, which would affect Desktop).

The `MainLayout` root `Column` is **not** wrapped in a `Scaffold` for this overhaul. A full `Scaffold` migration would require `content` lambda to accept `PaddingValues`, which is a breaking change rippling through every screen composable called from `ScreenRouter`. The manual inset approach achieves the same result with a smaller diff surface.

## Rationale

The two alternatives were:

**Alternative A — Full Scaffold migration**: Replace `MainLayout`'s `Column` with `Scaffold(contentWindowInsets = WindowInsets.safeDrawing)`. `Scaffold` auto-distributes insets to content via `innerPadding`. Clean but requires threading `PaddingValues` through `MainLayout`'s `content` lambda → every screen composable → `PageView`, `JournalsView`, etc. That is a >10 file change with significant risk of missed padding propagations.

**Alternative B (chosen) — Component-local inset ownership**: Each visual component claims the inset that corresponds to the space it fills. `TopBar` claims `WindowInsets.statusBars`. `NavigationBar` claims `WindowInsets.navigationBars` (already its default). Scroll containers claim `WindowInsets.ime`. This is the same conceptual model as Scaffold but without the `PaddingValues` threading requirement.

Alternative B has one risk that Alternative A does not: a developer could add a new composable that re-applies `navigationBarsPadding()` inside the layout, creating a double-consume. This is mitigated by the audit step (see Consequences) and the Grep-based detection heuristic documented in the pitfalls research.

## Consequences

**Positive:**
- `content` lambda signature on `MainLayout` does not change — all screen composables are untouched.
- Status bar inset consumption is visible at the `TopBar` level, which is the composable that physically occupies the status bar area.
- Navigation bar inset consumption is visible at the `NavigationBar` level (inside `bottomBar` slot), which is the composable that physically occupies the nav bar area.
- On Desktop/iOS/Web, both modifications are no-ops: `TopBar` on Desktop does not apply `statusBarsPadding` (the branch is guarded by `isMobile`), and `NavigationBar` is never rendered (no-op `PlatformBottomBar` actual).

**Negative / Watch-outs:**
- A developer audit of all `*BarsPadding()` and `windowInsetsPadding(WindowInsets.*)` calls in `commonMain` is required before shipping F2. Run:
  ```
  grep -r "BarsPadding\|safeContentPadding\|safeDrawingPadding\|systemBarsPadding" kmp/src/commonMain
  ```
  Any remaining call that sits inside the `MainLayout` content area is a double-consume candidate.
- `imePadding()` on `LazyColumn` inside `JournalsView` / `PageView` is a `commonMain` change that is safe on Desktop (it is a no-op when no IME is active) but must be tested manually on both Android and Desktop to confirm no visible bottom gap appears in Desktop mode.
- The `NotificationOverlay` change (magic `32.dp` → `WindowInsets.navigationBars`) changes the overlay position on gesture-nav devices (where nav bar height is 0dp) versus 3-button-nav devices. Test on both configurations.

## Patterns Applied

- **Inset ownership by visual occupancy**: Each composable consumes the inset corresponding to the space it visually fills. This matches the guidance in Android developer docs on "who should apply inset padding."
- **Defensive programming**: The Grep audit step and the `SuggestionNavigatorPanel` review are explicit safeguards against double-consumption, which is the most common class of edge-to-edge bug.
- **Open-Closed for screen composables**: By not changing the `content` lambda signature, all existing screen composables remain closed to modification for this feature — they do not need to know whether they are inside a `Scaffold` or a `Column`.
