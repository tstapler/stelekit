# Architecture Research: excalidraw-sketch-engine

Date: 2026-11-14. Scope: scene model, command/undo, renderer, tools, solver boundary, persistence, sync, flag rollout. Confidence labels: VERIFIED = file opened this session; INFERRED = reasoned from verified facts.

## 1. What exists (VERIFIED)

Base path `kmp/src/commonMain/kotlin/dev/stapler/stelekit/`.

| Piece | Fact | Source |
|---|---|---|
| Scene | `sealed interface SketchPrimitive` (Line, Rect; sixteenths `Inches`), immutable `SketchScene`, `MAX_PRIMITIVES = 10_000`, `MAX_COORD_SIXTEENTHS = 1L shl 28`, linear `candidatesIn(Aabb)` (no spatial index) | `sketch/SketchScene.kt` (class body, lines ~66-100) |
| DB row type | `SketchPrimitive` data class with `kind: String`, `x1..y2: Long`, `name`, `layer`, `z` (distinct from the sealed domain type above) | `model/SketchModels.kt:46-58` |
| Undo | `SketchHistory`: immutable snapshot stack of `SketchDocument(scene, zones, sheet)`, `MAX_STEPS = 100`, equality via `sameContent` | `sketch/SketchHistory.kt:1-49` |
| Commands | Pure functions on `SketchDocument` returning `Either<CommandError, SketchDocument>` (not command objects); closed `CommandError` sealed set | `sketch/SketchCommands.kt:1-223` |
| Tool FSM | One `pointerInput`, `Idle -> Placing -> Dragging`, touchSlop tap-vs-drag, snapshot commit on pointer-up, non-drag `PlacementController<P>`/`NudgeStepper` in `ui/placement/` | ADR-008 `project_plans/home-project-sketching/decisions/ADR-008-sketch-editor-architecture.md:6-13` |
| Render | cached `Path` per primitive keyed on geometry version, cached text layout, grid as `drawPoints` | ADR-008:9; `ui/sketch/SketchRenderCache.kt`, `SketchCanvasDraw.kt` |
| Tables | `sketch_projects`, `sketch_sheets` (`layer_json`, `zones_json`), `sketch_primitives(kind,x1,y1,x2,y2,name,layer,z)`, `sketch_dimensions`; bounded named queries | `kmp/src/commonMain/sqldelight/.../SteleDatabase.sq:1413-1516`; `db/MigrationRunner.kt:853-921` (hash-guarded `sketch_tables` step) |
| Authority | The **sidecar bundle** `<graph>/assets/stelekit-sketch/<slug>/{project-model.json, sheets/<uuid>.json}` is authoritative and synced; DB rows are a cache; 3-way reconcile on bundle hashes; `schema:1` with `NewerSchema` corrupt state; golden tests | ADR-005 `.../ADR-005-fused-model-storage.md` "Decision" |
| Solver today | Linear rationals only (Sum/Equal/Offset), two-phase fusion; deliberately NOT a geometric solver | ADR-003 "Decision" |
| Flag | `homeSketch` Settings boolean, default off, enforced in `ScreenRouter`/`AppState` | ADR-008:12; referenced from `ui/GraphContentActiveShell.kt`, `ui/GraphDialogLayer.kt` |
| Size of touched code | `SketchViewModel.kt` 348, `SketchCanvas.kt` 212, `SketchCommands.kt` 223, `SqlDelightSketchRepositories.kt` 246, `ui/sketch/` total 3,379 lines | `wc -l` this session |

Note: capacity already (10,000) exceeds the requirement (5,000), so no capacity change is needed. `merge/` has no sketch references (grep empty), so sketches do not use `TargetWriterRouter` today; their only writer is the sidecar path (ADR-005 via `AtomicFileWriter`).

## 2. Recommendations

### 2.1 Scene/element model: new `v2` model alongside v1, with a one-way lossless adapter (recommended)

- Add `sketch/v2/` (or package `sketch.scene`): `SceneElement` sealed hierarchy (Rect, Diamond, Ellipse, Line, Arrow, Freedraw, Text, Image, Frame) sharing `ElementBase(id, layerId, groupIds, frameId, z-index as fractional/ordered key, locked, opacity, style, version, versionNonce)` and **Double** coordinates in a sheet-unit space (1 unit = 1/16 inch so the v1 number stays the same value).
- Do NOT retype `SketchPrimitive`. v1 `Line`/`Rect` stay as-is because the dimension-fusion/project-bridge pipeline and `SketchCsvWriter`/`SketchSvgWriter` depend on exact sixteenths (requirements Constraints). Resolves Open Question 4: **two coordinate regimes, one bridge**. Technical sheets keep integer sixteenths and exact dimensions; diagram elements are Double.
- Adapter: `V1ToV2.lift(SketchPrimitive) -> SceneElement` (exact: Long to Double is lossless below 2^53; `MAX_COORD_SIXTEENTHS = 2^28` is safe). `V2ToV1.project(SceneElement) -> SketchPrimitive?` is partial: defined only for axis-aligned Rect and Line whose endpoints are integers (after solver rounding to sixteenths); returns null otherwise. The dimension/fusion path reads only the projection, so v2-only elements never enter fusion.
- Why not unify: rational/integer exactness is the point of ADR-002/003; making all coordinates Double would force tolerance logic into Bareiss elimination and break golden fixtures. Why not keep only v1: cannot carry text, curves, bindings (requirements Alternatives).
- Constraint-bearing geometry (technical sketch mode, Open Question 2): constraints attach to **points/curves of v2 elements via parametric handles**, available on any element kind but UI-offered primarily in a "technical" sheet kind. Recommended v1 of solver scope: constraints apply to Line, Rect (as 4 lines + implicit coincident/H/V), Ellipse/circle-arc, Point; freehand/text/image are fixed bodies (can be dimensioned by bounding box only).

### 2.2 Command + undo engine: command objects with inverse patches, history of patches (replace snapshot-of-world)

- Keep ADR-008's immutability, but at 5,000 elements a snapshot per step stores a persistent list reference per step; if `SceneV2` uses a persistent map (kotlinx `PersistentMap`, or hand-rolled structural-sharing since "avoid dependencies") the cost is O(changed) per step and the existing 100-step bound stays. Recommendation: `SceneV2` = `Map<ElementId, SceneElement>` + `order: List<ElementId>` (z), structurally shared; history keeps snapshots (simple, proven by `SketchHistory`), with a **`Command` interface** `apply(doc): Either<CommandError, Applied(doc, changedIds)>` so the renderer cache, spatial index and solver re-run only for `changedIds`. This is an evolution of the existing function-per-command style, not a rewrite.
- Transactions: `History.transaction { ... }` coalesces multi-element drags, solver-moved geometry, and group/ungroup into one undo entry (resolves "Undo semantics across solver-moved geometry"): solver output is part of the same commit as the drag that triggered it; history never records intermediate solver iterations.
- Extend `CommandError` additively (it is a closed sealed interface; new cases only require updating the exhaustive `message()` when-expression in `SketchCommands.kt`).

### 2.3 Renderer split

Three layers, all commonMain:
1. **Scene-to-draw-list** (pure, tested in commonTest): element -> `DrawOp` list (path, text run, image ref, style) in scene space, with per-element cache keyed by `(id, version)`. This is where "identical rendering within tolerance" is asserted (golden draw-lists per fixture, not pixels, for JVM/Android/wasm).
2. **Compose painter** (`ui/sketch/v2/`): consumes the draw list in `DrawScope`; viewport culling via spatial index; static layer (committed elements) vs dynamic layer (in-progress element + selection handles) so a drag only invalidates the dynamic layer.
3. **Export writers** (SVG, PNG-via-painter, `.excalidraw`) consume the same draw list, so SVG/PNG parity is structural, not duplicated per format. Existing `SketchSvgWriter` stays for v1 sheets.
- Hand-drawn style (Open Question 3): put it behind a `StrokeStyleProvider` seam in layer 1 (clean = identity; rough = seeded jitter from `ElementBase.seed`, deterministic across platforms). Ship clean first; rough is additive and cannot affect model or persistence because the seed is stored per element (matches Excalidraw's `seed` field).
- Text: cross-platform measurement differs. Persist measured width/height in the element, treat them as hints, and re-measure on edit only; golden draw-list tests assert text runs with injected fixed-metric measurer.

### 2.4 Gesture/tool state machine

- Generalize ADR-008's `Idle -> Placing -> Dragging` to a **tool-per-state-machine** pattern: `interface Tool { fun onEvent(e: InputEvent, ctx: ToolContext): ToolResult }` where `ToolResult` carries `Command?`, `Preview?`, `NextTool?`. Pure, Compose-free, in `sketch/v2/tools/`; the one `pointerInput` adapter (existing `SketchGestures.kt`) only translates platform pointers to `InputEvent(type, point, pressure, modifiers, pointerKind)`.
- Event normalization lives in the adapter so stylus pressure/hover (device-only) is isolated; tools get `pressure: Float?`.
- Keep `PlacementController`/`NudgeStepper` as the non-drag path; every new tool must implement a keyboard/non-drag variant (accessibility requirement). Tool tests run in commonTest with synthetic event scripts (the "scripted scenario test" in success metric 2).

### 2.5 Constraint solver module boundary

- Own package `sketch/constraints/` with **no imports from `ui`, `db`, `repository`** (mirror the `JobsScheduleImportDirectionTest` pattern in CLAUDE.md: add `SketchSolverImportDirectionTest`). Contract: `solve(problem: ConstraintProblem) -> SolveResult` where problem = flat `DoubleArray` parameters + constraint list over parameter indices (planegcs-style), result = `Solved(params, dof) | Conflict(ids) | Redundant(ids) | NoConvergence`. The scene adapter maps element handles to parameter indices; the solver knows nothing about elements.
- Solve per committed command and (separately, bounded, off the hot path) during drag with warm start from the last solution; never inside `Canvas` draw. Budget: 100 constraints < 50 ms (requirement NFR); emit a sample to `benchmarks/budgets/`.
- Do not reuse the rational fusion solver (ADR-003): different problem class (nonlinear, Double). The only coupling point is the `V2ToV1.project` rounding step feeding dimensions (sec 2.1).
- Algorithm choice is a spike (Rabbit Hole), not decided here. INFERRED: start with Levenberg-Marquardt with analytic Jacobians + QR rank detection for DOF/redundancy; BFGS/DogLeg fallbacks only if the spike shows need.

### 2.6 Spatial index
Replace linear `candidatesIn` for v2 with a uniform grid or R-tree in commonMain built incrementally from `changedIds`; keep `SketchScene.candidatesIn` untouched for v1.

### 2.7 Persistence

- **Authority stays the sidecar** (ADR-005): add `sheets/<uuid>.json` field `scene` with `schema:2`, containing v2 elements (and constraints, layers, groups, frames) when the sheet is a v2 sheet; v1 sheets stay `schema:1`. Per-sheet schema, not per-project, so a bundle can hold mixed sheets and git merges stay per-sheet (preserves ADR-005 "different sheets merge cleanly").
- **`.excalidraw` interop** is import/export only (Open Question 1, recommended): native JSON is the source of truth because constraints, layers-with-opacity, and Double parametric handles have no Excalidraw field. Preserve unknown Excalidraw fields in an `extras` map on import for round trip.
- Images: stored as asset files referenced by `fileId` (hash), not inline base64 in the sheet JSON, to keep git diffs small; import caps size and never fetches URLs.
- **DB cache (SQLDelight)**: do not widen `sketch_primitives`. Add `sketch_elements(uuid PK, sheet_uuid, kind, z_key, bbox_minx, bbox_miny, bbox_maxx, bbox_maxy, payload_json)` with index `(sheet_uuid, z_key)` plus `sketch_constraints(uuid PK, sheet_uuid, kind, payload_json)`. Bounded reads: paged by `LIMIT/OFFSET` via a `selectSketchElementsBySheet` named query, bbox columns support viewport queries later without a full load. Each `CREATE TABLE IF NOT EXISTS` MUST also be added to `MigrationRunner.all` (CLAUDE.md mandatory rule; `MigrationRunnerSchemaSyncTest` enforces it); hash-guarded step like `sketch_tables` (`MigrationRunner.kt:853`). Writes go through `RestrictedDatabaseQueries` stubs annotated `@DirectSqlWrite`, executed by `DatabaseWriteActor`. After `.sq` edits regenerate `kmp/src/generated/sqldelight/` (Bazel uses committed output).
- `sketch_sheets` needs a `schema_version` column (`ALTER TABLE ... ADD COLUMN ... DEFAULT 1` via `MigrationRunner`; check how existing ALTER steps are expressed in `MigrationRunner.all` first, INFERRED pattern).

### 2.8 Sync / cross-graph write rules
- Sketch bundles are today written by `AtomicFileWriter` with CAS and three-way reconcile (ADR-005), outside `TargetWriterRouter`. v2 keeps that writer; it must not write another graph's bundle. Copying a sketch/sheet across graphs is new surface: route it through `TargetWriterRouter` as a **whole-file asset copy** (new file, never edit an existing one), preserving the "off-graph writes only splice / never re-render an existing file" rule (CLAUDE.md "Cross-graph writes"). If an off-graph target already has the slug, refuse rather than merge.
- Journal/page embeds reference a sheet by id (`ui/sketch/SketchEmbedWiring.kt`); embeds render via the v2 draw list when `schema:2`, via existing `SketchPreview` otherwise. Block text is unchanged, so no new splice concerns.
- Per-element `version`/`versionNonce` give a basis for element-level three-way merge later (out of scope now); today conflict granularity stays per-file with the ADR-005 `Corrupt`/`conflict-<hash8>` handling.

### 2.9 Feature flag rollout
- Flag `sketch.engine.v2`: per-graph `Settings` boolean, default off, same enforcement points as `homeSketch` (`ScreenRouter`/`AppState`). Flag controls **which editor opens and whether new sheets are created as v2**; it must NOT control whether v2 sheets are readable (readers always handle `schema:2`), otherwise turning the flag off would make data unreadable.
- Rollback semantics (decide in ADR, recommended): a v2 sheet opened with the flag off renders **read-only via the v2 renderer** (not "invisible"); v1-compatible projection (`V2ToV1.project`) is used only for the dimension pipeline. Do not back-convert a v2 sheet to v1 in place.
- Stages as requirements: (1) model + adapter + schema:2 read/write behind flag; (2) renderer + tools; (3) constraints; (4) interop. Each stage ships dark.

## 3. Event-Command-Policy (edit/solve/persist)

| Event | Command | Policy / outcome |
|---|---|---|
| Pointer up after drag | `MoveElements(ids, delta)` | Transaction: apply, re-solve if any moved element has constraints, commit ONE history step |
| Solve result `Conflict`/`Redundant` | none | Document unchanged; surface status (ids only in log); user must delete/relax a constraint |
| Solve `NoConvergence` during drag | none | Keep last good geometry, show warning; re-attempt on commit only |
| Element deleted that arrow is bound to | `DeleteElements` | Policy: unbind arrow endpoints in same transaction (`boundElements` cleanup) |
| Commit succeeds | `PersistSheet` (debounced 500 ms) | Serialize sheet to sidecar via `AtomicFileWriter`; update DB cache via `DatabaseWriteActor`; on `Corrupt` target, save beside and surface (ADR-005) |
| Sidecar changed on disk by sync | `ReconcileSheet` | Three-way hash reconcile (ADR-005); if `NewerSchema`, open read-only |
| `.excalidraw` import | `ImportScene(bytes)` | Size cap, unsupported elements dropped with a count report, never fetch URLs; one undoable step |
| Flag off, v2 sheet opened | none | Read-only render; edit disabled with reason |

## 4. Failure modes (Complexity 4)

### 4.1 Schema migration
1. **Missed `MigrationRunner.all` entry**: new tables exist only on fresh installs (`Schema.create` fails on existing DBs and the exception is swallowed, per CLAUDE.md). Mitigation: `MigrationRunnerSchemaSyncTest` is the gate; plus a test that opens a real v1-era DB fixture and asserts the new tables exist.
2. **Stale generated SQLDelight sources** cause Bazel-only compile failures. Mitigation: regenerate + CI job.
3. **Migration non-atomicity**: lifting v1 rows into `sketch_elements` partway then crashing. Mitigation: v1 rows are never deleted or rewritten; a sheet is converted lazily on first v2 open and only by writing a new `schema:2` sheet file plus bumping `schema_version` in ONE sidecar write after the new file is verified (write-new, verify hash, then switch pointer). Re-running is idempotent (same ids from `V1ToV2.lift`).
4. **Backup requirement**: requirements demand a sidecar backup before first v2 write; use `sheets/<uuid>.v1.bak.json` in the bundle (synced, small) rather than a local-only copy, so another device that pulls is not left with only v2.
5. **Old app version reads a `schema:2` sheet**: ADR-005 already defines `Corrupt(NewerSchema)` and says never overwrite; verify the shipped v1 reader really maps unknown `schema` to that state (INFERRED, UNVERIFIED: only the ADR was read, not `ProjectModelJson`/`SheetJson`). This is the highest-impact open check: a reader that ignores the field would silently re-save a sheet as v1 and lose v2 data. Add a golden test with a `schema:3` fixture against the current reader before shipping v2.
6. **Three-way reconcile across mixed versions** between two devices (one on v2, one on v1 build): the lagging device sees `NewerSchema` and must stay read-only; the reconcile base hash `last_synced_sidecar_hash` must be computed over the same canonical serialization on both, or false conflicts appear. Canonicalization (sorted keys, stable number formatting for Double) must be specified: Double formatting differs across JVM/wasm/iOS, so serialize Doubles with a fixed shortest-round-trip routine implemented in commonMain, not `toString()` (INFERRED risk).
7. **`sketch_primitives.z` is Long** while v2 z-order wants fractional keys: do not reuse the column.

### 4.2 v1/v2 coexistence
1. **Edit-in-one, fuse-in-other drift**: editing a sheet's rect in v2 changes geometry that fusion reads via `V2ToV1.project`; if the projection silently returns null (rotated, non-integer sixteenths) the dimension disappears from fusion. Mitigation: projection returns a typed result with `Dropped(reason)`; the UI shows "N elements not used for dimensions".
2. **Solver rounding vs exact dimensions**: solver Doubles rounded to sixteenths can break a `Sum` constraint by 1/16. Mitigation: round only at projection; run fusion's own verification and report mismatch rather than snap.
3. **Dual editors, shared state**: `SketchViewModel` (348 lines) and v2 view model must not both hold a sheet open; enforce a single `SheetLease` per sheet in the store (`SketchStore.kt`, 70 lines).
4. **Flag flip mid-session**: flag toggled with an open sheet; editor choice resolved at open time, not recomposed.
5. **Parallel code paths drift** (export, preview, embed): three renderers (v1 painter, v2 painter, SVG writer). Mitigation: embed/preview dispatch on `schema` in ONE function; golden tests for each path; delete the v1 editor only after parity tests pass (requirements Risk Control).
6. **Capacity/limits differ**: v1 enforces 10,000 primitives and layer limits (`TooManyLayers`); v2 layers/opacity may exceed them; the adapter must clamp or refuse with a typed error on `V2ToV1.project`, not throw.
7. **Wasm/iOS**: iOS is Gradle-only (CLAUDE.md), so iOS regressions will not show in Bazel CI; wasmJs test sources are not covered by detekt. Mitigation: keep all engine code in commonMain with commonTest coverage; run `:kmp:wasmJsBrowserTest` for renderer draw-list goldens.

## 5. Hotspot dispositions

- `ui/sketch/SketchViewModel.kt` (348 lines) and `SketchCanvas.kt`/`SketchCanvasDraw.kt`: **Isolate via seam.** Leave v1 editor untouched; add `SketchEditorRouter` choosing v1 or v2 editor at open time, and build the v2 view model separately, extracting only the shared `PlacementController`/`SketchStore`.
- `sketch/SketchCommands.kt` (223 lines, closed `CommandError`): **Extend as-is** for v1; v2 gets its own `Command` interface and error type, converted to the v1 message path only for shared status-line formatting.
- `sketch/SketchScene.kt` + `SketchHistory.kt`: **Extend as-is** (no change); v2 reuses the `History` shape by generalizing `SketchHistory` over a document type only if a second consumer shows exact duplication (rule of three), otherwise copy-with-tests in `v2/`.
- `repository/SqlDelightSketchRepositories.kt` (246 lines) and `SteleDatabase.sq` sketch block: **Extend as-is** with new, separate tables and new repository methods (additive, bounded); do not alter `sketch_primitives`.
- Sidecar serialization (`ProjectModelJson`/`SheetJson`): **Refactor-first (small)**: introduce an explicit schema-version dispatch (`SheetCodec` per `schema`) and add the `NewerSchema` golden test BEFORE any v2 write, so rollback safety is proven on the old reader path.

## 6. Open items to resolve in ADRs
1. Rollback behavior of flag-off with v2 sheets (recommended: read-only v2 render).
2. Persistent-map vs list for `SceneV2` (benchmark at 5,000 elements).
3. Solver algorithm (spike).
4. Whether `ProjectModelJson` already rejects higher `schema` (verify in code before ADR).
5. Double canonical serialization routine.
