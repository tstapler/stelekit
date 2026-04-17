# ADR-002: Graph View Rendering Engine — Compose Canvas with Pure-KMP Physics

**Date**: 2026-04-16
**Status**: Proposed
**Feature**: Graph View (Knowledge Graph Visualization)

---

## Context

SteleKit needs a force-directed graph visualization to display page relationships
(the "Graph View" feature). Three rendering strategies were evaluated:

| Option | Description | KMP-Native | Effort | Fidelity |
|--------|-------------|------------|--------|----------|
| **A — Compose Canvas + pure-KMP physics** | Custom `Canvas` composable; Verlet-integration physics loop driven by `withFrameMillis` | Yes | High | Full |
| **B — WebView + D3.js** | Render a bundled HTML/JS page in a `WebView`; message-pass node clicks back to Kotlin | No (hybrid) | Medium | Good |
| **C — Platform-specific (Skia/Metal/etc.)** | Native graph library per target | No | Very High | Best |

## Decision

**Option A — Compose Canvas with a pure-KMP physics simulation.**

## Rationale

* **KMP-first**: The entire codebase is pure KMP/Compose Multiplatform. Introducing
  a WebView hybrid (Option B) would require per-platform `WebView` expect/actual
  wiring, message serialization, and an entirely separate JS dependency tree.
* **No external runtime**: Option B bundles a JS runtime; Option C requires per-target
  native libraries. Both add non-trivial binary size and maintenance surface.
* **Compose Canvas is sufficient**: Force-directed graphs at the scale of a personal
  knowledge base (typically < 5 000 nodes) run comfortably at 60 fps with a naive
  O(n²) Barnes–Hut approximation on modern hardware. The existing `withFrameMillis`
  coroutine API makes the animation loop idiomatic Compose.
* **Full interaction parity**: Pointer input, pinch-zoom, and pan are all natively
  supported by Compose `Modifier.pointerInput`, avoiding the latency of JS bridge
  calls for hit testing.

## Consequences

* **Positive**: Single source file for all platforms; no hybrid runtime; clean state
  management via `StateFlow`-backed physics engine.
* **Negative**: More implementation work than embedding a mature library. Performance
  tuning (Barnes–Hut, spatial hashing) will be needed for graphs > 2 000 nodes.
* **Neutral**: The physics loop is deliberately isolated behind a `GraphPhysicsEngine`
  interface so it can be swapped out without touching UI code.

## Patterns Applied

* **Strategy** — `GraphPhysicsEngine` interface decouples simulation algorithm from
  rendering composable.
* **Observer** — `StateFlow<GraphSimulationState>` pushes frame updates to Canvas.
* **Value Object** — `GraphNode`, `GraphEdge` are immutable snapshots passed to Canvas.
