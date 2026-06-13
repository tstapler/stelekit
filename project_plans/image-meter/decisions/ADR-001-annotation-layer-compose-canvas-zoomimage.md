# ADR-001: Annotation Layer: Compose Canvas + ZoomImage vs Third-Party Annotation Library

**Date**: 2026-05-16
**Status**: Accepted
**Deciders**: Tyler Stapler

## Context

The image-meter feature requires an interactive annotation layer over potentially large construction photos (50 MP, 50–200 MB JPEG). The annotation layer must support drawing lines, polygons, text labels with leader lines, and tap/drag gesture input while the base image is simultaneously zoomable and pannable. The implementation must target Android, iOS, and Desktop from shared `commonMain` code (KMP). Annotations must be stored in normalized [0,1] image-space coordinates so they survive zoom/pan changes and remain resolution-independent.

The primary question is whether to use a dedicated third-party annotation library or build directly on Compose Multiplatform's built-in drawing primitives.

## Decision

Use **Compose Multiplatform `DrawScope` + `Modifier.pointerInput`** for all annotation rendering and gesture handling, layered on top of **ZoomImage** (`io.github.panpf.zoomimage`) as the base image host. No third-party annotation library will be added.

The architecture is a `Box` with five stacked layers:
1. `ZoomImage` — handles subsampling of large photos and pinch-zoom/pan via `GraphicsLayer` transform (hardware-accelerated, does not trigger annotation recomposition)
2. `Canvas` — committed annotations drawn via `DrawScope` (`drawLine`, `drawPath`, `drawText` via `TextMeasurer`)
3. `Canvas` + `Modifier.pointerInput` — in-progress annotation preview driven by `detectDragGestures` / `detectTapGestures`
4. `MeasurementLabelOverlay` — separate Compose pass for callout labels to avoid clipping
5. `AnnotationToolbar` — tool palette and calibration controls

All annotation coordinates are stored and manipulated in normalized [0,1] space; the `zoomState.transform` matrix is applied only at draw time.

For the image-export path (baking annotations into a pixel buffer for Drive upload), `GraphicsLayer.toImageBitmap()` (Compose 1.7+ / CMP 1.7+) captures the composable tree in `commonMain` without requiring direct Skiko API access.

## Alternatives Considered

**Third-party annotation libraries (e.g., `CanvaPaint`, `signature-pad-compose`, general-purpose draw libraries)**
- None of the available options as of early 2026 are KMP-compatible across Android + iOS + Desktop simultaneously.
- Most are Android-only or JVM-only.
- Licensing varies; several are GPL-adjacent or have commercial restrictions incompatible with this project's licensing stance.
- Adopting one would introduce a dependency that cannot be maintained or forked easily if it becomes unmaintained.

**Zoomable library (lighter alternative to ZoomImage)**
- `net.engawapg.lib:zoomable` is KMP and MIT-licensed, with a simpler API.
- Lacks tile-based subsampling for very large images — a 50 MP construction photo would OOM on mid-range devices without subsampling support.
- ZoomImage's subsampling is a hard requirement given the target image sizes; Zoomable is eliminated on this basis.

**Direct Skiko Canvas access (`org.jetbrains.skia.Canvas`)**
- Available implicitly via Compose Multiplatform on Desktop/iOS.
- Bypasses Compose layout and state system; requires manual invalidation.
- Not accessible from `commonMain` in a KMP-safe way — would require `expect`/`actual` splits.
- Reserved only for the export/baking path where it is strictly necessary.

## Consequences

**Positive**
- Full KMP compatibility: the entire annotation layer compiles from `commonMain` with no platform-specific code required for rendering.
- Full rendering control: no annotation primitive type is blocked by a third-party library's feature set; new annotation types (grid overlay, angle arcs) can be added by implementing a new `DrawScope` draw function.
- No licensing risk: all components are Apache 2.0 (Compose Multiplatform, ZoomImage) or MIT.
- Performance: `ZoomImage`'s `GraphicsLayer`-based zoom/pan runs at ~60 fps hardware-accelerated without triggering annotation recomposition; the annotation `Canvas` only redraws on `annotationState` changes.
- ZoomImage's tile-based subsampling prevents OOM on full-resolution construction photos.

**Negative / Risks**
- `DrawPath` with complex strokes is documented to be slower on iOS Compose than Android due to different rendering pipeline backends. Leader-line label placement will require platform-specific tuning for font metrics differences between Skiko (Desktop) and Android Canvas.
- `BlendMode` operations for highlight effects are not uniformly supported across all Skiko targets; annotation highlight styles must avoid blend modes not in the common subset.
- More initial implementation effort than dropping in a pre-built annotation SDK, since every annotation primitive (distance line with endpoint handles, polygon area fill, angle arc, label callout) must be implemented from scratch.
- iOS support in ZoomImage is listed as less battle-tested than Android; must be validated early in implementation.
- Text rendering via `TextMeasurer` in `commonMain` must be tested on all three platforms before release; font metric differences may cause label overlap or clipping.
