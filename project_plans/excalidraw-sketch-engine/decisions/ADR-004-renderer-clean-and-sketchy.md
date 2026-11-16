# ADR-004: One draw list, a Clean/Sketchy style strategy, both shipped in v1

**Status**: Proposed (stays Proposed: the default style for new sheets is owner decision OD-5; CJK/fallback-font policy comes from Spike 0.1.3b)
**Date**: 2026-11-14

## Context
Owner decision (2026-11-14): both Clean and hand-drawn (Sketchy) styles ship in v1. Targets: Desktop, Android and Web first (Web stores sheets through OPFS atomic replace, owner decision D-104; Sketchy availability is per target); iOS compile-only and storage-unavailable. Elements carry Excalidraw's `roughness` and `seed`.

## Decision
- Pipeline: `SceneElement -> DrawOp list (scene space) -> {Compose painter, SVG writer, PNG writer}`. Parity of export with screen is structural. Draw lists are asserted in `commonTest` (golden), not pixels; pixel tolerance is a separate per-target check.
- `StrokeStyleProvider` interface with `CleanStrokeStyle` (identity geometry) and `SketchyStrokeStyle` (seeded double stroke, midpoint bow, hachure/cross-hatch/zigzag fill, per the MIT rough.js algorithm family, own implementation of a subset: line, rectangle, ellipse, polygon/path, curve). A per-element `roughness` of 0 renders Clean; a per-sheet default style chooses the starting value for new elements (Sketchy is Excalidraw's default feel; default for new sheets = Clean, owner can flip, OD-5).
- PRNG: an in-repo xorshift/mulberry-style generator seeded from `ElementBase.seed`; `kotlin.random.Random` is not relied on for cross-target identity. Goldens generated from the JS originals are fixtures (Spike 0.1.5).
- Freehand uses a clean-room Kotlin port of the MIT perfect-freehand algorithm with the notice kept in the file header and a `NOTICE` entry; golden outputs generated from the JS original.
- Fonts: bundled via `compose.components.resources`; no system fallback for diagram text; measured width/height/line breaks persisted per element and re-measured only on edit (`TextMetricsProvider` seam, fixed-advance fake for tests).
- Degradation near the 5,000-element cap: disable Sketchy, skip shadows, cull off-screen (non-blocking banner).
- No new runtime dependency; no WebView; no Skia in commonMain.

## Alternatives rejected
Clean only (contradicts owner decision); rough.js via JS bridge (no on all targets); render per-element composables (recomposition cost).

## Consequences
Sketchy on wasmJs/iOS needs rendering checks (Story 2.2.3). iOS is compile-only for v1.
