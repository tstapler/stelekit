# ADR-004: Graph View Screen — Dedicated Screen vs. Overlay

**Date**: 2026-04-16
**Status**: Proposed
**Feature**: Graph View (Knowledge Graph Visualization)

---

## Context

The graph view can be surfaced in two UX patterns:

| Pattern | Description |
|---------|-------------|
| **A — Dedicated `Screen.GraphView`** | Added to the `sealed class Screen` hierarchy; navigated to from sidebar / command palette |
| **B — Overlay / modal** | Full-screen overlay triggered by a toolbar button; no navigation history entry |

## Decision

**Option A — Dedicated `Screen.GraphView`** extending the existing `sealed class Screen`.

## Rationale

* **Navigation history**: `StelekitViewModel.navigateTo()` supports back/forward
  navigation. Making the graph view a proper `Screen` lets users navigate back to
  it with Cmd+[ without losing filter state.
* **Sidebar integration**: The left sidebar already maps `Screen` subclasses to
  navigation items; adding a `Screen.GraphView` entry is a three-line change.
* **Local graph panel**: A compact `LocalGraphPanel` composable (showing only the
  current page's immediate neighbourhood) will be wired into the right sidebar,
  separate from the full-screen `Screen.GraphView`. This panel is NOT a `Screen`.
* **URL/deep-link readiness**: Future deep-link support (planned for the web target)
  requires named routes; sealed `Screen` subclasses map cleanly to route strings.

## Consequences

* **Positive**: Consistent navigation model; Cmd+[ works; sidebar icon present.
* **Negative**: Slight increase in `Screen` enum size and `navigateTo(destination: String)` when/else.
* **Neutral**: `AppState.currentScreen` carries the `Screen.GraphView` instance, which
  holds any filter state (`showJournals`, `showOrphans`, `hopCount`).

## Patterns Applied

* **State Machine** — `Screen` sealed class is the navigation state machine; `Screen.GraphView` is a new state.
* **Memento** — Filter state is embedded in `Screen.GraphView` data object, restored on back-navigation.
