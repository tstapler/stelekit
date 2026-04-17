# ADR-001: Bottom Navigation Architecture — Slot Pattern vs expect/actual AppLayout

**Status**: Accepted
**Date**: 2026-04-12
**Feature**: Android UX Overhaul — F1 Android Bottom Navigation

---

## Context

SteleKit needs an Android-specific `NavigationBar` for the 4 primary destinations (Journals, All Pages, Flashcards, Notifications). Three implementation strategies were evaluated:

- **Option A** — `expect/actual AppLayout` composable: `commonMain` declares an `expect fun AppLayout(...)`, `androidMain` provides a `Scaffold`+`NavigationBar` actual, other platforms provide the existing Column layout.
- **Option B** — `bottomBar` slot on `MainLayout` with a thin `expect/actual PlatformBottomBar`: `MainLayout` gains one `bottomBar: @Composable () -> Unit = {}` parameter; the call site in `App.kt` fills it with `PlatformBottomBar(currentScreen, onNavigate)`; all other platforms get a no-op actual.
- **Option C** — Platform-specific `App.kt` entry points: `androidMain` wraps the entire `StelekitApp` in an `AndroidRootLayout` that owns the `Scaffold`.

The project already uses `expect/actual` for `platformNavigationInput` in `ModifierExtensions.kt`, confirming that pattern compiles and is understood by the codebase.

## Decision

**Option B — `bottomBar` slot on `MainLayout` with a `PlatformBottomBar` expect/actual.**

New files:
- `commonMain/ui/PlatformBottomBar.kt` — `expect fun PlatformBottomBar(currentScreen: Screen, onNavigate: (Screen) -> Unit)`
- `androidMain/ui/PlatformBottomBar.android.kt` — `actual` renders `NavigationBar` with 4 `NavigationBarItem`s and a private `BottomNavItem` enum
- `jvmMain/ui/PlatformBottomBar.jvm.kt` — `actual` is an empty composable (no-op)
- `iosMain` and `jsMain` get identical no-op actuals when those targets are active

Modified files:
- `MainLayout.kt` — adds `bottomBar: @Composable () -> Unit = {}` parameter; removes `Spacer(Modifier.navigationBarsPadding())` (replaced by `NavigationBar`'s own inset handling); removes `Modifier.statusBarsPadding()` (moved to F2 scope, handled by `TopBar`)
- `App.kt` — `MainLayout` call gains `bottomBar = { PlatformBottomBar(appState.currentScreen) { viewModel.navigateTo(it) } }`

## Rationale

| Criterion | Option A | Option B | Option C |
|---|---|---|---|
| Files added | 4+ actuals + new `expect` | 3 files (1 expect, 2 actuals) | 1 `androidMain` wrapper |
| State threading | `AppState` must pierce `expect` boundary | State stays in `commonMain` call site | `StelekitViewModel` must be re-hoisted |
| `GraphContent` visibility | Unchanged | Unchanged | Must be made public or duplicated |
| Inset control | Scaffold auto-handles | Explicit and auditable | Scaffold auto-handles |
| JVM screenshot tests | All baselines change (new layout structure) | Only bottomBar slot area changes | All baselines change |
| Consistency with existing code | New pattern | Mirrors `statusBar` slot exactly | New pattern |

Option B is minimum-invasive: `MainLayout` gains one parameter with a no-op default, keeping Desktop, iOS, and Web output identical. Navigation state (`AppState.currentScreen`, `viewModel.navigateTo`) stays entirely in `commonMain`.

Option A requires threading `AppState`/ViewModel references through an `expect` composable signature — exactly the coupling that `private fun GraphContent` was designed to avoid (ADR-001 in `App.kt` docstring). Option C requires either making `GraphContent` public or duplicating its initialization logic in `androidMain`.

## Consequences

**Positive:**
- Desktop, iOS, Web: zero change in rendered output — no-op `actual` is never reached on those platforms.
- `MainLayout` is fully testable on JVM in `jvmTest`; passing `bottomBar = {}` in tests preserves existing screenshot baseline shape.
- Tab selection is derived directly from `AppState.currentScreen` — the bottom bar is always in sync with navigation history, deep links, and programmatic navigation without extra `LaunchedEffect` sync.

**Negative / Watch-outs:**
- The `BottomNavItem` enum in `androidMain` references `Screen` from `commonMain` — this is a valid cross-module dependency (androidMain can import commonMain), but it means icon choices are tightly coupled to `Screen` values. Adding a new `Screen` subtype requires updating `BottomNavItem` or it silently shows no item for that screen.
- When `PlatformBottomBar` renders on Android, the `NavigationBar` composable internally applies `windowInsets = WindowInsets.navigationBars` by default. The `Spacer(Modifier.navigationBarsPadding())` at `MainLayout.kt:110` must be removed simultaneously, or navigation bar height is double-consumed (see ADR-002).

## Patterns Applied

- **Slot pattern** (Compose API design): `bottomBar: @Composable () -> Unit` slot follows the same convention as `Scaffold`'s `bottomBar` parameter and `MainLayout`'s existing `topBar`, `leftSidebar`, `statusBar` slots.
- **expect/actual** (KMP): Used only where the composable content genuinely differs by platform. The `NavigationBar` itself is `commonMain`-available; the `expect/actual` boundary exists only to supply the no-op on non-mobile platforms.
- **Unidirectional Data Flow**: `PlatformBottomBar` is purely presentational — it receives `currentScreen: Screen` and emits `onNavigate: (Screen) -> Unit`. No local state inside the bottom bar composable.
