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

## Spike 0.1.3b/c/e decisions: text metrics, CJK, offscreen render (2026-11-16)
Evidence: `research/determinism-spike.md` (VERIFIED by `DeterminismSpikeJvmTest`, `DeterminismSpikeWasmTest`, `AndroidPixelSpikeTest`; every number printed by those tests).
1. **Latin text with a bundled font is cross-target stable.** With identical font bytes (a 10 KB DejaVu Sans Latin subset standing in for the OD-4 font) widths differ by 0 px and line breaks at a 120 px wrap are identical on JVM, wasmJs and Android Robolectric, so the "persisted metrics authoritative" pivot is NOT triggered. Android lines are 1 px taller (24 vs 23 at 20 px). Platform default fonts differ by up to 5 px ("Auth Service": 117 JVM, 112 wasm, 114 Android), so no layout may use a platform font. Caveat: the subset had kerning and GSUB/GPOS removed, so shaping differences are unmeasured and Story 2.1.2 repeats the check with the real OD-4 font.
2. **CJK policy (b) is adopted**: CJK text uses the platform fallback font; its metrics are persisted per element, excluded from cross-target goldens, and wrap points for CJK are not asserted across targets. Reason: "データ" measures 60 px on JVM and Android but 27 px (default font) or 37 px (bundled Latin font) on wasmJs, which has no CJK glyphs. Policy (a), a bundled CJK subset, was not measured (no CJK font available here, OD-4 open) and stays an owner option that would replace this policy if the measurement later passes.
3. **wasmJs offscreen `ImageBitmap` and PNG encode work.** The rect-plus-text fixture renders non-blank on wasmJs with the same pixel hash as JVM, and `Image.makeFromBitmap(...).encodeToData(PNG)` returns 1,250 bytes on both. Story 5.2.2 uses the skiko path on wasm and does not need the Canvas-element seam (the PNG signature and a decode on wasm were not checked; byte equality with JVM is INFERRED from the equal size). iOS compile was not run on this host.
4. **Robolectric native graphics is usable for Android pixel parity of shapes and strokes** (0 of the shape-region pixels differ from JVM) and drifts on text (306 of 20,000 pixels, max channel delta 204, consistent with the 1 px line height). Task 2.1.3c keeps Robolectric with the tolerance: shapes exact, text at most 2% differing pixels or compared by draw list; on-device parity (Story 6.2.4c) is not covered by this run. Single run, so determinism across reruns is INFERRED.
