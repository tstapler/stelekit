# Research: Technology stack (excalidraw-sketch-engine)

Labels: VERIFIED = opened/ran in this repo or fetched source; INFERRED = reasoning or recall, not checked. Limited web search was done (two queries); anything not listed under Sources is INFERRED.

## 1. Repo baseline (VERIFIED)
- There is no `gradle/libs.versions.toml`; versions are inline. Plugin versions in `settings.gradle.kts:10-18`: Kotlin 2.4.10, Compose Multiplatform plugin 1.10.3, serialization plugin 2.4.10.
- `kmp/build.gradle.kts:92-130`: kotlinx-coroutines 1.10.2, kotlinx-datetime 0.7.1, kotlinx-serialization-json 1.10.0, arrow-fx-coroutines 2.2.1.1, okio 3.17.0, coil3 3.2.0, SQLDelight 2.3.2, lifecycle 2.8.4. Targets: JVM, Android, iosX64/Arm64/SimArm64, wasmJs (`build.gradle.kts:38-48`).
- Existing sketch code already uses Compose `rememberTextMeasurer` (`ui/sketch/SketchCanvas.kt:28-65`), a pure-Kotlin `SnapEngine`, `SketchHistory`/`SketchCommands` (command/undo), `ViewTransform`, and a hand-written SVG writer (`sketch/export/SketchSvgWriter.kt`). `SketchPrimitive` is integer sixteenths (`model/SketchModels.kt`). Build on these rather than add libraries.

## 2. Findings by area

### Rendering (Compose Canvas)
- Use `DrawScope` + `Path` (`moveTo/lineTo/cubicTo/quadraticTo`), `drawPath`, `Stroke(pathEffect = PathEffect.dashPathEffect)`, `withTransform`/`rotate` for rotated elements, `clipPath` for frames. All are in `androidx.compose.ui.graphics` common API (INFERRED from Compose API knowledge; the repo already draws with DrawScope).
- Perf at 500 elements: cache each element's `Path` keyed on (element id, version); redraw only visible elements (use the spatial index to cull); put static scene and active-gesture overlay in separate canvases/`drawWithCache` layers. Repo already has `SketchRenderCache.kt` as the precedent (VERIFIED file exists).
- Avoid `expect/actual` Skia (`org.jetbrains.skia`) in commonMain: it is not in the common source set (INFERRED). Pixel export needs a platform seam (see Export).

### Text measurement/layout
- `TextMeasurer.measure(text, style, constraints, softWrap, ...)` gives `TextLayoutResult` (line breaks, bounds, cursor rects) in commonMain; already used in repo (VERIFIED).
- Determinism risk: font fallback and metrics differ per Skia target. Mitigation: bundle one font family via `compose.components.resources` (already a dependency) and store element width/height and wrapped container width in the scene, re-measuring only on edit. Treat cross-platform pixel identity as tolerance-based, as success metric 4 already says.
- Editing: for multi-line in-place editing use `BasicTextField` overlaid at the element's transformed rect (INFERRED); IME works on all targets via the platform text input.

### Freehand smoothing
- No Kotlin port of perfect-freehand was found (web search; only Dart/Flutter, Odin, Python, Rust ports listed). Algorithm is ~300 lines (point streamlining, velocity-simulated pressure, outline polygon from left/right offsets, rounded caps). Recommendation: write a clean-room Kotlin port in commonMain, using the MIT original as the reference, and render the outline polygon as a quadratic-smoothed `Path` (INFERRED on effort; license of perfect-freehand is MIT, INFERRED, verify before copying code).
- Store raw points (x, y, pressure) compactly; Excalidraw stores `points` plus optional `pressures` (INFERRED from format recall; verify in the format spike). Apply Ramer-Douglas-Peucker simplification on pointer-up to cap size.

### Rough / hand-drawn style
- rough.js is JS only; no maintained Kotlin port found (the search did not cover it; UNVERIFIED). Option: implement a minimal rough-style renderer ourselves: for each line/curve, draw 2 passes of the segment with seeded random endpoint offsets and a slight midpoint bow, hachure fill via clipped parallel lines. About 200-400 lines. Seed per element (`seed` field, same as Excalidraw) gives stable output across platforms; use an own PRNG (e.g. xorshift or `kotlin.random.Random(seed)`, which is deterministic across targets, INFERRED) so renders match.
- Decision hook for Open Question 3: make the renderer a strategy (`Clean` vs `Sketchy`) behind one interface; ship `Clean` first, `Sketchy` as a later epic. No dependency needed.

### Spatial indexing
- Pure Kotlin options, no library: (a) uniform grid hash for roughly uniform diagrams, (b) packed R-tree (STR bulk load) or a simple dynamic R-tree for ≤5,000 elements. No known maintained KMP spatial-index library (UNVERIFIED search not run). Recommendation: a ~150-line AABB R-tree or grid in commonMain with incremental update on element bbox change; hit-test = index query, then exact test (distance to polyline/Bezier, point-in-polygon, rotated-rect inverse transform). At 5,000 elements even a flat bbox scan costs ~5k comparisons per move, so index mainly pays for snap queries and viewport culling; benchmark flat scan first (INFERRED).

### Constraint solving (pure Kotlin)
- Libraries: Multik's pure-Kotlin engine (`multik-kotlin`, `multik-default`) lists JVM, JS, WasmJS, iOS, native support; OpenBLAS engine is JVM/desktop-native only; docs differ slightly on WasmJS, so check the current release ([README](https://raw.githubusercontent.com/Kotlin/multik/develop/README.md), [platforms](https://kotlin.github.io/multik/multik-on-different-platforms.html)). KMath is KMP but its linear-algebra back-ends (e.g. EJML) are probably JVM-only (UNVERIFIED). EJML is Java: JVM/Android only, so rejected per requirement ("must run on all targets").
- Recommendation: no linear-algebra dependency. Sketch sizes (≤200 constraints, a few hundred variables) need only dense `DoubleArray` matrices with a hand-written solve (QR with Householder, or LDLT/Cholesky on the damped normal equations `J^T J + lambda I`). This is ~200 lines, fully commonMain, no allocation per iteration if buffers are reused. Verifies success metric 3 (<50 ms for 100 constraints: dense 200x200 Cholesky is ~3M flops per iteration; INFERRED estimate, benchmark in the solver spike).
- Algorithm: Levenberg-Marquardt (or DogLeg like planegcs) over point/parameter variables; analytic Jacobians per constraint type (more stable than numerical); decomposition into connected components to solve independent subsystems separately and cheaply; DOF and redundancy/conflict from rank of J via QR with column pivoting (rank-revealing); drag handled by treating the dragged point as a soft/fixed constraint and warm-starting from the previous solution. FreeCAD planegcs does exactly this (DogLeg/LM/BFGS, QR rank analysis) per the requirements doc; the design is INFERRED from recall of planegcs, confirm by reading its source (LGPL, so use as reference only, do not copy).
- Number type: solver on `Double`; map to the sixteenths integer model by rounding at the boundary only for technical-sketch (dimension) mode; keep diagram mode floating-point. Matches Open Question 4.

### Serialization
- kotlinx-serialization-json 1.10.0 is already present. Scene JSON: sealed `Element` hierarchy with `@SerialName` discriminators, `Json { ignoreUnknownKeys = true; encodeDefaults = false; classDiscriminator = "type" }`; `JsonElement` passthrough for unknown fields so `.excalidraw` extras round-trip (INFERRED design). Put `version` and `versionNonce`-style integers in each element, mirroring Excalidraw (INFERRED), so import/export is lossless for the supported subset. Size caps for untrusted import: check `String.length` before decode, cap element/point counts after decode.
- Excalidraw's element fields to model (from recall, verify in the format research): id, type, x, y, width, height, angle, strokeColor, backgroundColor, fillStyle, strokeWidth, strokeStyle, roughness, opacity, roundness, seed, groupIds, frameId, boundElements, link, locked; arrows add startBinding/endBinding, startArrowhead/endArrowhead, points; text adds text, fontSize, fontFamily, textAlign, verticalAlign, containerId, originalText, lineHeight.

### Export
- SVG: extend the existing hand-written `SketchSvgWriter` (VERIFIED exists). SVG is text; paths map directly to `d` attributes, so one element-to-path function can feed both the Compose renderer and SVG, which guarantees consistency. Text: emit `<text>` with the measured line breaks as `<tspan>`; embed the font only if the owner wants fidelity (otherwise font fallback in viewers).
- PNG: Compose `ImageBitmap` + `Canvas(ImageBitmap)` + `CanvasDrawScope` can draw the scene offscreen in commonMain (INFERRED; confirm on wasmJs). Encoding to PNG bytes needs a platform seam (JVM: `ImageIO`/Skia `Image.encodeToData`; Android: `Bitmap.compress`; iOS/wasm: Skia encode via skiko) so use an `expect/actual` `PngEncoder` (INFERRED). Okio 3.17.0 already available for byte output.
- `.excalidraw`: plain JSON (+ `files` map with base64 dataURL images); treat as untrusted, no fetch of external image URLs.

## 3. Recommended stack (no new runtime dependencies)
| Concern | Choice |
|---|---|
| Drawing | Compose `Canvas`/`DrawScope`, own element-to-`Path` functions, `drawWithCache` + per-element path cache |
| Text | `TextMeasurer` + bundled font via compose resources; store measured size |
| Freehand | Own Kotlin port of perfect-freehand algorithm |
| Rough style | Own seeded renderer behind a `Clean`/`Sketchy` strategy (deferred) |
| Spatial index | Own AABB R-tree or grid, flat-scan benchmark first |
| Solver | Own LM/DogLeg with dense `DoubleArray` linear algebra, analytic Jacobians, QR rank analysis |
| Serialization | kotlinx-serialization-json 1.10.0 (already present) |
| SVG export | Extend `SketchSvgWriter` |
| PNG export | Offscreen `ImageBitmap` draw + `expect/actual` PNG encoder |

If an ADR wants a linear-algebra dependency anyway, Multik (`multik-kotlin`) is the only candidate found that claims all four targets; validate wasmJs support before adopting.

## 4. Open items for later phases
- Verify perfect-freehand and rough.js licenses and exact algorithm parameters (not fetched).
- Spike: LM solver convergence under drag and redundancy detection; benchmark dense Cholesky for n=200.
- Spike: offscreen `ImageBitmap` rendering and PNG encode on wasmJs and iOS.
- Confirm current Excalidraw JSON schema (version field, `pressures`, frame/binding fields) against the excalidraw repo source.
- Run a text-metrics comparison JVM vs Android vs wasmJs with the bundled font.

## Sources
- Repo: `settings.gradle.kts`, `kmp/build.gradle.kts`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/{sketch,ui/sketch,model/SketchModels.kt}`.
- Multik README: https://raw.githubusercontent.com/Kotlin/multik/develop/README.md ; platforms: https://kotlin.github.io/multik/multik-on-different-platforms.html ; mobile: https://kotlin.github.io/multik/multik-on-mobile.html
- KMath: https://git.sciprog.center/kscience/kmath
- perfect-freehand ports (Dart, others; no Kotlin): https://github.com/adil192/perfect-freehand-dart , https://cdn.jsdelivr.net/gh/steveruizok/perfect-freehand@main/README.md
