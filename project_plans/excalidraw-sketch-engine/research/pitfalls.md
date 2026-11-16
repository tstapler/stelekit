# Pitfalls and risks: excalidraw-sketch-engine

Date: 2026-11-14. Labels: VERIFIED = a source I opened or a search result I can link; INFERRED = my engineering judgement, not confirmed against a source or run. Web search returned snippets, not full post-mortems; I did not open the FreeCAD/Excalidraw issue bodies in full, so tracker claims are SNIPPET-level.

Orientation (VERIFIED, directory listing): the existing engine lives in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/sketch/` (SketchScene, SketchCommands, SketchHistory, SnapEngine, ZoneConstraints, ViewTransform) and `ui/sketch/` (SketchCanvas, SketchGestures, SketchDrag, SketchRenderCache, SketchNonDragMode, SketchSemantics, LayerPanel). `SketchCommands`/`SketchHistory` and `SketchRenderCache` are the natural extension points for the undo and recomposition items below.

## 1. Solver (2D constraints)

| # | Pitfall | Evidence | Design against | Test that catches it |
|---|---------|----------|----------------|----------------------|
| S1 | Redundant constraints make Jacobian rank-deficient; solver slows or hangs (one report: ~12 vs ~45,000 iterations once redundant equalities were removed) | SNIPPET: [FreeCAD #3773/#1647 etc. via tracker](https://tracker.freecad.org/view.php?id=3773), [redundancy detection commit](https://gitea.suzanne.soy/suzanne.soy/FreeCAD/commit/ce5d9a332a75c3e02c79709f38c62311d60a0b2e) (unofficial mirror) | Rank analysis (QR with pivoting) before solving; classify constraints as redundant/conflicting/ok; hard iteration and wall-time cap on every solve | Property test: generate a satisfiable sketch, append a duplicate or implied constraint; assert solve terminates under the cap and reports "redundant" with the constraint id |
| S2 | One solver's failure is hidden by never reaching the fallback (DogLeg runs forever so LM never tried) | SNIPPET: FreeCAD tracker (same search) | Per-algorithm iteration budget, then fallback; surface which algorithm succeeded | Test with a contrived sketch where first algorithm is forced to exceed budget; assert fallback runs |
| S3 | False positive/negative redundancy and "fully constrained" claims differ per algorithm | SNIPPET: FreeCAD tracker ids [3286](https://tracker.freecad.org/view.php?id=3286), [3976](https://tracker.freecad.org/view.php?id=3976) | DOF count from Jacobian rank of the final system, not from algorithm status; compare against a brute-force reference on small sketches | Differential test: DOF from rank vs. analytic DOF for generated primitive-only sketches (n points = 2n, each constraint removes known DOF) |
| S4 | Solver drags geometry far or flips solution branch during interactive drag (arc flips, tangent branch swap) | INFERRED (known behaviour of NR-style solvers) | Solve from previous solution (warm start), cap per-step displacement, keep orientation hints for tangent/arc | Scripted drag across 200 small steps; assert no step moves any point more than k times the pointer delta and no tangent branch flip |
| S5 | Singular Jacobian at degenerate geometry (zero-length line, coincident points, radius 0) | INFERRED | LM damping + regularisation; reject/clamp degenerate creation; never divide by raw length | Fuzz: random degenerate inputs, assert no NaN/Infinity in output and no thrown exception |
| S6 | Float drift: JVM, Android (ART), wasm, iOS native can differ in last ulps (FMA contraction, `Math.*` vs libm implementations) so "deterministic" solves differ per platform | INFERRED. JVM `strictfp` is default since Java 17 but Kotlin/Native and Kotlin/Wasm transcendental functions use platform libm; I did not run this | Avoid sin/cos/atan2 inside the Newton loop where possible; use tolerance-based assertions not bitwise; round solved coordinates to a fixed grid on commit; snapshot tests compare within eps | Run the same solver fixture on JVM and wasmJs (Karma) and iOS simulator; assert max abs diff < 1e-6 and that rounded-on-commit output is identical |
| S7 | 100-constraint solve budget (<50 ms) blown by dense matrices on wasm | INFERRED | Sparse or block-decomposed solve: partition the constraint graph into connected components and solve each independently (also bounds failure blast radius) | Benchmark in `benchmarks/budgets/`: 100-constraint scene, relative-to-empty-canvas budget; plus a component-partition equivalence test |

Rule: a solve never throws to the UI. Wrap in `Either<SolveError, Solution>`; non-convergence returns the last good geometry plus diagnostics (repo rule: Arrow `Either`, no thrown domain errors).

## 2. Undo with solver-moved geometry

- Pitfall: a command records only the user-edited element; replaying (redo) after other edits re-solves to a different result, or undo restores the user element but leaves solver-moved neighbours. (INFERRED.)
- Design: record each command as a before/after patch of ALL elements whose geometry changed (including solver output), not as "intent + re-solve". Redo applies the stored patch verbatim, never re-solves. Solving happens only when creating the command. Constraint add/remove is its own command with its resulting geometry patch.
- Interactive drag = one command at pointer-up (coalesce), with the solver running per-move only on a transient scene (tldraw uses marks and batched transactions for the same reason: [tldraw editor docs](https://tldraw.dev/sdk-features/editor); and its release notes list several undo bugs fixed over time, e.g. [v5.4.0](https://tldraw.dev/releases/v5.4.0), SNIPPET).
- Test: property test: random command sequences, then undo all, assert scene equals the initial scene exactly (structural equality, including solver-moved elements); then redo all, assert equals the final scene. Also: undo bounded (history cap) must evict whole commands only.

## 3. Arrow binding

- Pitfalls: binding references stale after element delete/duplicate/paste (dangling ids); copy-paste of arrow without target must unbind; duplicates must remap bound ids; circular or self bindings; binding focus/gap recomputed after rotate/resize; bound text label ordering; rebinding only when an endpoint is dragged (Excalidraw changed this in [PR #7946](https://redirect.github.com/excalidraw/excalidraw/pull/7946), SNIPPET). Excalidraw's own binding/labelling internals are described in [instagit write-up](https://instagit.com/excalidraw/excalidraw/excalidraw-arrow-binding-labeling/) (third party, unverified).
- Design: bindings are stored on the arrow as `{elementId, focus, gap}` (matches `.excalidraw`); a single `reconcileBindings(scene)` invariant pass runs after every command and import; deleting a target nulls arrow bindings in the same command patch (so undo restores both).
- Tests: (a) invariant property test after random commands: every binding id resolves to a live element; (b) move bound shape, assert arrow endpoint stays on shape boundary within eps; (c) copy/paste/duplicate subset matrix.

## 4. Text metrics across Skia targets

- Pitfall: text layout (width, wrap, baseline) differs across JVM, Android, iOS and wasm (different font fallback and shaping; web fonts load asynchronously). Excalidraw hit this: it moved from stored baseline to hardcoded per-font metrics ([PR #7693](https://git.biohazardvfx.com/Nicholai/excalidraw/commit/7e471b55ebd45663c7708f3c2e29eebe43fe6d97), SNIPPET) and needed per-browser fudge (+0.05 px Firefox/Safari, same snippet). Bundled-font-only advice for Compose is INFERRED; I did not find a Compose source on cross-target text metric differences (search returned nothing).
- Design: bundle the fonts (no system fallback for diagram text); store the element's width/height/lineHeight and wrapped line breaks in the scene (wrap computed at edit time, persisted) so rendering never depends on remeasure; use a `TextMetricsProvider` seam (Excalidraw has the same idea) with a deterministic fixed-advance fallback for tests; treat font load completion as a render invalidation on wasmJs.
- Tests: golden: measure a fixture string set on JVM, Robolectric and wasm; assert width within tolerance of stored values and identical line breaks. Pixel-diff render fixtures with tolerance (matches requirement metric 4). Test an unloaded-font render does not persist wrong bounds (do not write measured sizes while font status is not loaded).

## 5. Input: pointer, stylus, touch

- VERIFIED (search): on wasmJs, stylus data comes from DOM PointerEvent (`pointerType=pen`, pressure 0..1 hardware-normalised, tilt) per [compose-stylus](https://klibs.io/project/MohamedRejeb/compose-stylus) and [MDN pointer events](https://developer.mozilla.org/en-US/docs/Web/API/Pointer_events). A third-party lib exists; core Compose pointer API exposing pressure on every target is NOT confirmed. A Slack thread reports a multi-touch bug in compose-web ([thread](https://slack-chats.kotlinlang.org/t/30710739/hi-i-found-a-bug-in-how-multi-touch-events-are-handled-in-co), anecdotal).
- INFERRED pitfalls: palm rejection (touch landing during pen stroke), pen hover absent on some targets, pressure always 1.0 or 0.0 for mouse/touch, two-finger pinch cancelling a one-finger stroke, browser default touch gestures (scroll, back-swipe, text-selection) stealing events on wasm (needs `touch-action: none` on the canvas host), right-click/context menu, high-DPI coordinate scaling, wheel delta units differing (pixels vs lines), iOS not first-class (Gradle-only).
- Design: normalise to one `ScenePointerEvent(kind, pos, pressure?, pointerId, timestamp)` in commonMain; gesture state machine as a pure function (events in, actions out) so it is tested once in commonTest with scripted streams; pressure is optional and freehand must work without it; keep `SketchNonDragMode` (non-drag placement, accessibility) parity for every new tool.
- Tests: scripted event streams in commonTest (cancel mid-stroke, second pointer arrives, pointer-up outside canvas, rapid down/up); Robolectric Compose test for touch; device-only items (stylus pressure, hover, palm rejection) go on the owner-verification checklist, per CLAUDE.md "device behaviour cannot be tested in CI".

## 6. Recomposition and render cost at 500+ elements

- Pitfall: one `mutableStateOf(scene)` makes every drag invalidate the whole canvas and every composable reading it; per-element composables with hundreds of nodes. (INFERRED; Compose general knowledge.)
- Design: draw everything in a single `Canvas`/`drawBehind` (no composable per element); keep scene in an immutable persistent structure with element-level identity so unchanged elements reuse cached `Path`/`TextLayoutResult` (extend existing `SketchRenderCache`); two layers: static scene cache and a transient layer for the dragged/selected elements (tldraw does hit-test and geometry caching and R-tree indexing for the same reasons: [v4.4.0 notes](https://tldraw.dev/releases/v4.4.0) and [performance docs](https://tldraw.dev/sdk-features/performance), SNIPPET); skip hover hit-testing while panning (same source); viewport culling via spatial index.
- Tests: Compose recomposition-count test (a drag of one element must not recompose the panel/other elements); benchmark in `benchmarks/budgets/` (500 elements, p95 frame budget, relative to empty canvas); allocation check on pointer-move path (tldraw cut per-pointer-event allocations in hit testing: [v5.4.0](https://tldraw.dev/releases/v5.4.0), SNIPPET).

## 7. Hit-testing

- Pitfalls: linear scan over all elements per pointer-move; hit on rotated shapes, curves, hollow (unfilled) shapes (click inside should miss), text bounds, arrows near thin strokes (hit tolerance must be in screen pixels, not scene units, so it scales with zoom), groups and locked layers, frames clipping children, z-order tie-break.
- Design: spatial index (R-tree or uniform grid) keyed by AABB of rotated element plus padding; narrow-phase geometry per kind; tolerance = pixels / zoom; hit returns topmost unlocked visible element in z-order.
- Tests: property test: index query equals brute-force scan on random scenes (catches index staleness after move/rotate/delete); example tests for hollow shape interior, rotated rect corner, locked/hidden layer, tolerance at zoom 0.1 and 10; scaling test asserting query time sub-linear (count of narrow-phase candidates, not wall time, to avoid flakiness).

## 8. Coordinate model and determinism

- Existing model is integer sixteenths and is load-bearing (requirements Constraints). Pitfalls: converting Excalidraw doubles to sixteenths loses precision and breaks import/export round-trip; mixing models makes constraints solved in doubles disagree with rational dimensions; rounding on each commit accumulates drift.
- Design: floating-point scene for new elements, an explicit lossless adapter for legacy primitives (sixteenths / 16 is exactly representable in a double, so v1 to v2 is exact; the reverse projection must round, and is only valid for line/rect); solved geometry is rounded once at commit, not per iteration. Decide this in an ADR (Open Question 4).
- Tests: property: legacy primitive -> v2 -> legacy projection is identity for all int sixteenth values; JVM vs wasm JSON number round-trip (see 9).

## 9. Serialization determinism (JVM vs wasm)

- INFERRED pitfalls: `Double.toString` shapes differ (`1.0E-5` vs `1e-5`, trailing `.0`), JS numbers are IEEE doubles but Kotlin `Long` ids above 2^53 lose precision if they pass through JS `Number`; kotlinx.serialization handles this but custom parsing may not; NaN/Infinity are not valid JSON.
- Design: use kotlinx.serialization for all JSON; element ids as strings; canonical serializer (sorted keys, fixed number formatting) for git-friendly diffs and stable hashes; reject NaN/Infinity on both import and solver output.
- Test: golden JSON fixtures serialised on JVM and wasmJs compared byte-for-byte; round-trip property test (`decode(encode(x)) == x`) in commonTest.

## 10. Untrusted `.excalidraw` import

- Format facts (INFERRED from my knowledge of the format; verify in the format-research doc): JSON with `type`, `version`, `elements[]`, `appState`, `files{}` where images are base64 data URLs keyed by fileId; elements carry `version`, `versionNonce`, `isDeleted`, `boundElements`, `frameId`, `groupIds`; `link` fields; `embeddable` and `iframe` types; unknown fields are common across versions.
- Threats and design:
  - Size/DoS: cap file bytes before parsing (e.g. read bounded), cap element count (scene limit 5,000), cap points per freedraw/line, cap text length, cap nesting/groupIds depth; streaming or bounded read, never `readText()` on an unbounded file (aligns with CLAUDE.md "no O(graph) / unbounded" spirit).
  - Decompression/image bombs: base64 `files` entries: cap decoded bytes per image and total; decode dimensions from the header before allocating a bitmap; reject absurd pixel counts; never auto-decode on the UI thread.
  - NaN/huge coordinates: reject or clamp non-finite and |coord| beyond a bound (overflow in AABB/R-tree, solver).
  - Dangling/cyclic references: `frameId`, `boundElements`, `groupIds`, `containerId` pointing nowhere or cyclic (frame inside itself); run the same `reconcile` invariant pass as in section 3 and drop dangling refs with a logged reason code.
  - No outbound fetch: `link` and embeddable URLs stored as inert text, never fetched or auto-opened; image `dataURL` only `data:image/(png|jpeg|gif|webp)` , reject SVG data URLs (script/external-ref risk) or rasterise in a sandbox-free pure parser; no HTML rendering.
  - Duplicate ids and id collisions with existing elements on paste/merge: remap ids on import.
- Tests: fuzz/property test with generated malformed JSON (truncated, deep nesting, huge arrays, NaN strings, duplicate ids, cyclic frames): assert bounded time/memory (budget in test), returns `Either.Left(reason)` or a sanitised scene, never throws; fixture tests for oversized image and oversized file; assert zero network calls (no HTTP client reachable from importer: also a dependency/import-direction test in the style of `JobsScheduleImportDirectionTest`).

## 11. Migration data loss

- Pitfalls: CLAUDE.md rule: any new `CREATE TABLE IF NOT EXISTS` in `SteleDatabase.sq` must also be in `MigrationRunner.all`, else it silently never appears on existing databases (VERIFIED: repo CLAUDE.md; enforced by `MigrationRunnerSchemaSyncTest`). Also: SQLDelight generated sources must be regenerated and committed (Bazel uses committed output). Sidecar bundle format versioning; v1 reader opening a v2-written file; lossy projection of v2-only elements into v1 (rollback story in requirements Risk Control).
- Design: forward-only migration, run lazily per sketch on first open with a backup of the sidecar before first v2 write (as per requirements); keep unknown fields (`extras` map) on elements so a future or foreign version survives a round trip; schema version number in the bundle; never delete v1 data in the migration itself.
- Tests: golden migration test over fixtures captured from the current app schema (success metric 6); `MigrationRunnerSchemaSyncTest` stays green; idempotence (migrate twice = once); crash-mid-migration test (kill between backup and write, reopen, data intact); v1 reader on v2 file is read-only or refuses cleanly.

## 12. Concurrency / lifecycle rules from repo CLAUDE.md that constrain the design (VERIFIED in CLAUDE.md)

- Scope ownership: scene/editor controller objects stored in `remember {}` must own their `CoroutineScope(SupervisorJob() + Dispatchers.Default)` and must not take `rememberCoroutineScope()`. Applies to the tool/gesture controller, solver runner, autosave debounce. Test: grep audit as the CLAUDE.md suggests plus a lifecycle test that disposes the composition and asserts no further launches.
- Uncaught Throwable guard: any long-lived scope (solver worker, autosave, import) attaches a `CoroutineExceptionHandler`; OOM on a large import or 5,000-element scene on Android would otherwise kill the process. Solver and importer catch `Throwable` where they allocate heavily (not just `Exception`) and return `Either.Left`. Test: inject an `OutOfMemoryError` from a fake importer/solver, assert the UI reports an error state and the process-level default handler is not invoked (pattern of `StelekitViewModelCrashReproductionTest`).
- Bounded reads: sketch persistence reads must be per-sketch/paged; no repository method that loads all sketches or all elements of a graph; elements-per-sketch bounded by the 5,000 cap. Test pattern: `QueryPlanAuditTest`-style plan audit for new queries and a `LargeGraph`-style test.
- Writes: sketch writes go through `DatabaseWriteActor` and `RestrictedDatabaseQueries` `@DirectSqlWrite` stubs; sketches embedded in pages or off-graph targets obey the cross-graph router rules (splice-only for non-active graphs). Do not write sketch sidecar files of the active graph behind `GraphWriter`.
- Debounced autosave must not race with undo: single-writer serialisation of persisted scene (version counter, last-writer-wins check) to avoid saving a stale scene after undo. Test: rapid edit/undo/edit with a fake slow writer; final persisted == final in-memory.
- Observability: log solver failures with constraint ids only, no user text (requirements); test asserts log lines contain no element text.
- Android debugging: persisted logs only (no logcat); use `Logger`.
- Test registration: register new classes in `AllBusinessTests`/`AllJvmTests`/`AllAndroidUnitTests` and `commonTest` BUILD.bazel; a new seam implementation (e.g. `TextMetricsProvider`) must be constructed in a composition root or `WiringCompletenessTest` fails; compose-test files in jvmTest need a `// jvmTest-reason:` comment.

## 13. Feature-flag / rollback hazards

- Both editors writing the same row: v1 editor opening a v2-written sketch. Design: version gate on open, v1 read-only on v2 data (or projection), decided in ADR. Test: matrix of (writer version x reader version).

## Gaps (could not source)

- No Compose-specific source on text-measurement divergence across JVM/Android/iOS/wasm; treated as INFERRED.
- iOS stylus pointer type/pressure through Compose core: not found.
- FreeCAD tracker items are search snippets; mirrors of FreeCAD code are unofficial. No planegcs-specific numerical post-mortem opened.
- No float-determinism experiment run; recommended as an early spike (section S6 test).

## Sources

- FreeCAD tracker: https://tracker.freecad.org/view.php?id=3773, /1647, /3286, /3976, /1557, /3791, /2172
- FreeCAD redundancy detection commit (mirror): https://gitea.suzanne.soy/suzanne.soy/FreeCAD/commit/ce5d9a332a75c3e02c79709f38c62311d60a0b2e
- Excalidraw text metrics PR #7693 (mirror): https://git.biohazardvfx.com/Nicholai/excalidraw/commit/7e471b55ebd45663c7708f3c2e29eebe43fe6d97
- Excalidraw PR #7946: https://redirect.github.com/excalidraw/excalidraw/pull/7946
- Excalidraw binding write-up: https://instagit.com/excalidraw/excalidraw/excalidraw-arrow-binding-labeling/
- tldraw: https://tldraw.dev/sdk-features/performance, https://tldraw.dev/sdk-features/editor, https://tldraw.dev/releases/v4.4.0, https://tldraw.dev/releases/v5.4.0, https://tldraw.dev/blog/introducing-signia
- Input: https://klibs.io/project/MohamedRejeb/compose-stylus, https://developer.mozilla.org/en-US/docs/Web/API/Pointer_events, https://slack-chats.kotlinlang.org/t/30710739/hi-i-found-a-bug-in-how-multi-touch-events-are-handled-in-co
