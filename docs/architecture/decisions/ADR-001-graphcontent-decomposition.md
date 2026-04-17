# ADR-001: Decompose `GraphContent` into Focused Composables

**Status**: Accepted  
**Date**: 2026-04-11  
**Deciders**: Tyler Stapler

---

## Context

`GraphContent` in `App.kt` was a ~380-line god composable with six distinct responsibilities: ViewModel factory/lifecycle, keyboard shortcuts, screen routing, dialog orchestration, status bar rendering, and layout wiring. This violated the Single Responsibility Principle and caused a production crash.

### The Crash

```
NoClassDefFoundError: dev/stapler/stelekit/ui/AppKt$GraphContent$2$1$7$4$1$7$1$1
  at dev.stapler.stelekit.ui.AppKt.GraphContent$lambda$9$0$6$3$0(App.kt:399)
  at androidx.compose.animation.CrossfadeKt$Crossfade$5$1.invoke(Crossfade.kt:132)
```

The JVM generates a named anonymous class for every lambda level within a single compilation unit. The class name `$GraphContent$2$1$7$4$1$7$1$1` encodes 8 levels of anonymous nesting. At this depth — reached by having inline composable content inside `Crossfade → when → Column → LaunchedEffect → snapshotFlow → collect` — the JVM class loader failed to resolve the class at runtime.

**Root cause**: `GraphContent` held inline UI content inside a `Crossfade`/`when` dispatch. Because `GraphContent` itself sat 6 levels deep in the composition tree before entering the router, any `when` branch adding 4+ inline levels triggered the failure threshold.

**Key insight**: The JVM nesting counter resets at each `@Composable` function boundary, because Kotlin compiles each top-level composable to its own class file. Named composables are the structural remedy.

### Architecture Debt

Beyond the crash, `GraphContent` violated several principles:

| Principle | Violation |
|---|---|
| SRP | Six independent reasons to change |
| OCP | Adding a new `Screen` required modifying the `when` dispatch inline |
| ISP | `PageView` and `JournalsView` received the full `LogseqViewModel` (50+ methods) but used ~5 |
| Clean Architecture | Interface adapter layer leaked application service concerns (ViewModel creation, lifecycle) |

---

## Decision

Decompose `GraphContent` into five focused composables plus one pure function. Each has a single reason to change.

### Target Structure

```
LogseqApp
└── key(activeGraphId)                         — forces ViewModel recreation on graph switch
    └── GraphContent                           — composition root (~80 lines): ViewModels + lifecycle
        └── LogseqTheme / CompositionLocalProvider
            ├── Onboarding (conditional)
            └── Box (focus-clear + platformNavigationInput)
                │   .onKeyEvent { onGraphKeyEvent(...) }  — pure function, not a composable
                ├── MainLayout
                │   ├── TopBar
                │   ├── LeftSidebar
                │   ├── ScreenRouter           — owns Crossfade + when dispatch
                │   │   ├── PageView
                │   │   ├── JournalsView
                │   │   ├── AllPagesScreen
                │   │   ├── FlashcardsScreen
                │   │   ├── NotificationHistory
                │   │   ├── LogDashboard
                │   │   └── PerformanceDashboard
                │   ├── RightSidebar
                │   └── StatusBarContent       — pure presentational, no ViewModel
                └── GraphDialogLayer           — all overlays in one composable
                    ├── CommandPalette
                    ├── SearchDialog
                    ├── SettingsDialog
                    ├── DiskConflictDialog
                    └── NotificationOverlay
```

### The Invariant

> Any `@Composable` that owns a `Crossfade`, `AnimatedContent`, or `when(screen)` dispatch **must not contain inline composable content** — only calls to named composables.

This is the single rule that prevents this class of crash from recurring. It follows directly from SRP: a composable that routes screens should not also define what those screens look like.

---

## Consequences

### Good
- `GraphContent` reduces from ~380 to ~90 lines.
- Each extracted composable is independently readable, reviewable, and modifiable.
- Adding a new `Screen` requires only adding a branch in `ScreenRouter` and a new composable — `GraphContent` is not touched (OCP).
- `StatusBarContent` receives only primitives — no ViewModel dependency.
- `onGraphKeyEvent` is a pure function — testable without a Compose runtime.
- Maximum lambda nesting depth in any single function drops from 11 to ≤5.
- The `NoClassDefFoundError` class of crash is structurally prevented.

### Neutral
- `ScreenRouter` still passes `LogseqViewModel` to some screens. Full ISP compliance (replacing the ViewModel with per-screen callback interfaces) is deferred to a future ADR as it requires changes to `PageView` and `JournalsView` signatures.

### Bad / Trade-offs
- More files/functions to navigate initially.
- `GraphContent` wires many dependencies that must now be threaded through to sub-composables. This is inherent cost — the function was doing too much.

---

## Alternatives Considered

### Alternative 1: Deeper `@Composable` function extraction only (no file split)
Keep everything in `App.kt` but extract named composables within the same file. **Chosen** — splitting into multiple files is premature given current scope. The composable boundaries are sufficient.

### Alternative 2: `ScreenRouter` as a sealed interface with `render()` methods
Make each `Screen` know how to render itself. Rejected — this inverts the dependency, coupling domain models (`Screen`) to UI framework types (`@Composable`).

### Alternative 3: No change (hotfix only)
Extract only `AllPagesScreen` and `FlashcardsScreen` (the minimal fix). Rejected — leaves `GraphContent` vulnerable to the same crash whenever a new screen with 2+ inline levels is added.

---

## Implementation

**Completed (hotfix)**:
- `AllPagesScreen` extracted from `Screen.AllPages` branch
- `FlashcardsScreen` extracted from `Screen.Flashcards` branch

**Completed (this ADR)**:
- `ScreenRouter` composable extracted — owns `Crossfade + when` dispatch
- `GraphDialogLayer` composable extracted — owns all overlay dialogs
- `StatusBarContent` composable extracted — pure presentational, primitive params
- `onGraphKeyEvent` pure function extracted — keyboard shortcut handling

**Future ADRs**:
- ADR-002: Replace `LogseqViewModel` parameter on screens with per-screen callback interfaces (ISP)
- ADR-003: Split `LogseqViewModel` into `NavigationController`, `GraphLoadingController`, `ConflictResolutionController`
- ADR-004: Decompose `AppState` (28 fields) into grouped sub-state slices
