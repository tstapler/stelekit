# W9 module-split analysis: splitting commonMain into leaf `kt_jvm_library` modules

Scope: read-only static analysis of `kmp/src/*Main` and `kmp/src/*Test` at commit `edae9954b2` plus the uncommitted working tree. No Bazel or Gradle was run. Labels: **VERIFIED** = produced by a script or command run during this analysis; **INFERRED** = derived from VERIFIED data with a stated assumption.

Method (all VERIFIED unless noted): a Python 3 script (kept in the session scratchpad, not in the repo) parses every `.kt` under `kmp/src` (4036 files), reads `package` and top-level declarations, resolves each `import dev.stapler.stelekit.*` (and fully qualified references in code, with comments and string literals stripped) to the declaring file, and builds file-level and package-level graphs. Generated SQLDelight classes (`db.SteleDatabase`, `TelemetryDatabase`, ...) live in `//kmp/src/generated/sqldelight` and are treated as an external leaf. Implicit same-package references are invisible to imports, so a separate token scan checked the 18 packages that my proposal splits across modules (section 2.4).

Baseline (VERIFIED by reading `kmp/BUILD.bazel`, `kmp/src/commonMain/kotlin/BUILD.bazel`, `kmp/src/jvmMain/kotlin/BUILD.bazel`, `kmp/src/jvmTest/kotlin/BUILD.bazel`, `kmp/src/businessTest/kotlin/BUILD.bazel`, `kotlin_common.bzl`): `jvm_main_lib` compiles `commonMain` + `jvmCommonMain` + `jvmMain` as one unit (`common_srcs` carries the expect side). Only `stats/` and the mermaid files are already split out. `jvm_tests`, `business_tests` and the commonTest libraries all depend on that one library, so any non-mermaid, non-stats edit reruns all of them.

## 1. Package-level dependency graph

Source-file counts (VERIFIED, `find`): commonMain 1538 (1537 under `commonMain/kotlin` plus the `bazel_stubs/SketchFontResources.kt` stub), jvmCommonMain 49, jvmMain 113, androidMain 151, iosMain 53, wasmJsMain 95.

### 1.1 Top-level package fan-in / fan-out (commonMain, packages = first segment after `dev.stapler.stelekit`)

Fan-out = number of other top-level packages a package imports; fan-in = number of packages importing it. Counts are packages, not files.

| package | files | fan-out | fan-in | package | files | fan-out | fan-in |
|---|---|---|---|---|---|---|---|
| ui | 545 | 43 | 3 | sections | 9 | 5 | 2 |
| sketch | 175 | 11 | 4 | util | 7 | 0 | 21 |
| git | 112 | 12 | 6 | vault | 7 | 5 | 3 |
| platform | 89 | 12 | 23 | editor | 6 | 2 | 1 |
| db | 72 | 20 | 15 | outliner | 6 | 2 | 6 |
| projectbridge | 52 | 17 | 3 | docs | 6 | 0 | 1 |
| repository | 46 | 18 | 20 | units | 6 | 0 | 7 |
| merge | 39 | 14 | 6 | parser | 4 | 3 | 4 |
| asset | 36 | 10 | 3 | cache | 4 | 0 | 4 |
| performance | 32 | 8 | 4 | diagnostics | 4 | 8 | 3 |
| llm | 28 | 9 | 7 | coroutines | 3 | 1 | 16 |
| jobs | 28 | 4 | 4 | search / service / query | 3 each | 3 / 3 / 4 | 1 / 3 / 2 |
| console | 23 | 5 | 3 | error | 2 | 2 | 22 |
| transfer | 21 | 7 | 1 | stats | 2 | 0 | 2 |
| model | 20 | 6 | 30 | clipboard | 2 | 2 | 1 |
| calibration | 20 | 3 | 4 | logging | 1 | 1 | 26 |
| migration | 17 | 6 | 2 | security | 1 | 0 | 7 |
| voice | 16 | 6 | 4 | loader / flashcard | 1 each | 1 / 1 | 0 / 1 |
| domain | 15 | 6 | 4 | resilience / benchmark | 1 each | 0 | 0 |
| sketchimport | 15 | 10 | 3 | fusion | 10 | 2 | 4 |
| export | 13 | 8 | 1 | parsing | 9 | 0 | 6 |
| capture | 13 | 10 | 1 | tags | 9 | 7 | 3 |

Hubs by fan-in: `logging` 26, `model` 30, `platform` 23, `error` 22, `util` 21, `repository` 20, `coroutines` 16, `db` 15. Fan-out outliers: `ui` 43, `db` 20, `repository` 18, `projectbridge` 17.

jvmMain/androidMain effect (VERIFIED): the JVM compilation unit (commonMain + jvmCommonMain + jvmMain) has 52 top-level packages / 342 package edges (commonMain alone: 48 / 297). Android (commonMain + jvmCommonMain + androidMain): 48 packages / 305 edges. Neither adds a new strongly connected component beyond the commonMain one (the SCC grows from 35 to 36 and 35 packages).

### 1.2 Second-level packages with the largest file counts (VERIFIED)

`ui.sketch` 196, `sketch.v2` 137, `ui.components` 125, `git` (root) 69, `db` 63, `ui` (root) 62, `ui.screens` 58, `ui.annotate` 57, `projectbridge` 52, `repository` 46, `performance` 32, `llm` 28, `jobs.schedule` 26, `asset.pipeline` 25.

## 2. Import cycles

### 2.1 Summary of cycles (VERIFIED)

| granularity | strongly connected components (non-trivial) |
|---|---|
| top-level package (commonMain) | 1 SCC: 35 of 48 packages, 1486 of 1538 files. Outside it: benchmark, cache, coroutines, docs, fusion, loader, logging, parsing, resilience, security, stats, units, util |
| second-level package | 2 SCCs: one of 3 packages / 9 files, one of 71 packages / 1482 files |
| **file** (commonMain, and identically for the JVM unit) | **7 SCCs**, sizes 3, 3, 3, 2, 2, 2, 2 (17 files in total, none larger than 3) |

The pivotal finding: the package graph is one tangle, but the **file graph is almost acyclic**. The earlier split project (`tier_manifest.bzl`, ADR-001 "Tier 3 = 18 packages in one SCC, un-splittable") reasoned at package granularity. At file granularity only seven tiny cycles exist, so a split is feasible if modules are defined by file lists (a Kotlin package may span modules on the JVM) rather than by whole packages. The Tier-3 SCC grew from 18 to 35 packages since that analysis (INFERRED: the repo grew; I did not re-derive the old number).

### 2.2 The 7 file-level SCCs (VERIFIED) and how to break each

| # | files in cycle | classification | fix |
|---|---|---|---|
| 1 | `error/DomainError.kt` <-> `git/LockRecovery.kt`, `merge/TargetWriter.kt` | type should move down | extract `LockVerdict`/`UnknownReason` (LockRecovery.kt:14-37) and `WriteRefusedReason` (TargetWriter.kt:11) plus `NotRoundTrippable` (merge/RoundTripGuard.kt:12) into the `core` module; `SyncOrigin` too (see 2.4) |
| 2 | `db/DatabaseWriteActor.kt` <-> `performance/HistogramWriter.kt` | genuinely coupled | keep both in one module (`persist`) |
| 3 | `asset/AssetSearch.kt` <-> `repository/AssetRepository.kt` | genuinely coupled | keep both in `persist`; move `AssetModels.kt`, `AssetSortOrder.kt` with them |
| 4 | `platform/ml/MonocularDepthEstimator.kt` -> `ui/annotate/AnnotationEditorViewModel.kt` -> `platform/sensor/SensorModule.kt` -> back | interface to extract | extract the `DepthModelUiState`-style DTO/port (MonocularDepthEstimator.kt:7 imports `ui.annotate.DepthModelUiState`) into `devices` |
| 5 | `ui/screens/JournalsViewModel.kt` <-> `ui/state/BlockStateManager.kt` | genuinely coupled | same module (`ui_main`) |
| 6 | `sketch/v2/commands/Command.kt` <-> `sketch/v2/constraints/ConstraintModel.kt` | genuinely coupled | same module (`sketch_core`) |
| 7 | `sketch/v2/binding/BindingEngine.kt`, `BindingFollow.kt` <-> `sketch/v2/commands/ElementCommands.kt` | genuinely coupled | same module (`sketch_core`) |

### 2.3 Why the package SCC exists: bidirectional package pairs (VERIFIED, file counts by direction)

Top-level pairs that import each other, largest first (A->B source files / B->A source files): ui-sketch 148/2, platform-ui 2/98, repository-ui 2/40, error-git 1/27, db-repository 9/14, error-merge 1/18, model-calibration 1/14, projectbridge-sketch 2/13, voice-llm 3/9, db-performance 5/4, platform-sketch 1/8, repository-merge 6/2, repository-asset 3/5, db-console 2/5, performance-repository 2/5, db-git 3/3, tags-llm 3/3, model-sketchimport 1/4, repository-domain 3/2, merge-projectbridge 2/3, and 11 more pairs of 1-3 files each (31 pairs in total). A greedy minimum-cut at top-level-package granularity needs to drop 79 package edges (122 file edges) to become acyclic (script `order.py`; greedy, not provably minimal). The proposal below needs far fewer because it cuts below package level.

### 2.4 Edges that remain once files are assigned to modules (VERIFIED, 16 file edges / 10 module pairs + 2 hidden)

After the module assignment in section 3 (47 files placed in a module other than their package's default, section 3.3), every remaining upward edge is one of these (src -> dst):

| # | edge(s) | classification | fix |
|---|---|---|---|
| R1 | `platform/FileSystem.kt:309` -> `performance/SpanEmitter.kt` (SpanEmitter itself references `HistogramWriter`, `AppSession`, `RingBufferSpanExporter`) | interface to extract | port in `core` (`SpanSink`); SpanEmitter stays in `persist` |
| R2 | `platform/SketchPlatformInput.kt:7` -> `ui/sketch/v2/RawPointer` (+ `SketchPointerAdapter`) | type to move down | move `RawPointer` to `platform` or `sketch_core` |
| R3 | `platform/ml/MonocularDepthEstimator.kt` -> `ui/annotate/AnnotationEditorViewModel.kt` | interface to extract | see SCC 4 |
| R4 | `jobs/InProcessJobs.kt:169` (`TrackedCopyRunHost`) -> `merge/{CopyRunHost,PageMergeService,MergePlan}` | type to move up | move `TrackedCopyRunHost` into `merge`; rest of `jobs/` becomes a pure module (also keeps the `jobs.schedule` direction rule, section 3.5) |
| R5 | `merge/ActiveTargetWriter.kt`, `MarkdownTargetWriter.kt` -> `SketchBundle*` types in `merge/SketchBundleCopy.kt` (hidden, same package) | interface to extract | define `SketchBundleWriter` port in `merge`; `SketchBundleCopy.kt`/`SketchBundleRemap.kt` (imported only by `projectbridge` and `ui/sketch`) move to `sketch_runtime` |
| R6 | `sketch/v2/runtime/convert/SheetConverter.kt` -> `ui/sketch/SketchViewModel.kt` (`toScenePrimitive`, `toSheetProps`, internal); `sketch/v2/runtime/export/PngExporter.kt` -> `ui/sketch/v2/ScenePainter.kt` | type to move / interface to extract | move `toScenePrimitive`/`toSheetProps` and the painter entry point into `sketch_core` |
| R7 | `ui/components/ImageBlock.kt:71` -> `ui.sketch.SketchPreviewBlock` (fq reference) | interface to extract | inject a renderer slot (CompositionLocal or lambda); `ui_main` must not import `ui_sketch` |
| R8 | `ui/components/ShareDialog.kt:24`, `ui/screens/PageView.kt:43` -> `ui/StelekitViewModel.kt` (2666 lines); `ui/screens/git/GitSetupScreenDialogs.kt` -> `ui/App.kt` (`appStorageSubtitleFor`) | genuinely coupled (hub) | extract a narrow view-model interface for the two consumers; move the helper out of `App.kt` |
| R9 | `ui/sketch/SketchRenderDispatcher.kt` -> `ui/StelekitAppDependencies.kt` | interface to extract | pass the dependency in |
| R10 | `error/DomainError.kt` -> `git/SyncOrigin.kt`: `SyncOrigin.kt` also holds an extension returning `SyncTrigger` (declared in `git/FirstSyncConfirmation.kt`) | type to split | move the `SyncOrigin` enum alone to `core`, keep the extension in `git` |

False positives from the hidden scan, listed so nobody chases them: `SyncOrigin.kt` -> `RateLimitRetry`/`FollowUp` (enum constant names that match type names) and `CalibratedLength.kt` -> `inches` (a parameter name).

Cross-module uses of `internal` symbols (VERIFIED, 26 imports in 14 files, 24 symbols) would stop compiling once a module boundary is crossed, e.g. `ui/GraphContentNewGraphFlow.kt` -> `ui/components/NewGraphDialog.kt#isValidGraphFolderName`, `repository/SqlDelight*Repository.kt` -> `merge/SelectionFilter.kt#toSqlArgs`, `ui/sketch/v2/NonDragTransformMode.kt` -> `sketch/v2/tools/SelectTool.kt#ownBox`. Fix: widen to `public` or add the module to `associates`. 319 commonMain files declare an `internal` symbol (VERIFIED), so any further split must re-run this check.

## 3. Proposed cut lines: 20 modules + the existing `stats`

### 3.1 DAG (ordered bottom-up; each module lists its direct dependencies after transitive reduction; VERIFIED from the file graph with the R-fixes assumed)

```
core            -> (none)                         stats  -> (none, exists)      fusion -> core
model           -> core
platform        -> model, stats
sched           -> platform
persist         -> platform
sketch_core     -> fusion, model
devices         -> platform
ai              -> persist
git             -> ai, sched
merge           -> git
asset           -> ai, devices
console         -> persist
export          -> persist
transfer        -> devices, persist
sketch_runtime  -> asset, merge, sketch_core
ui_found        -> console, export, merge
ui_main         -> sketch_runtime, transfer, ui_found
ui_sketch       -> ui_main
ui_app          -> ui_sketch
```

Longest chain: ui_app, ui_sketch, ui_main, sketch_runtime, merge, git, ai, persist, platform, model, core (11 levels). Module-level edges respect this order (no cycles) once R1-R10 are done.

### 3.2 Module table

Files = commonMain files assigned (VERIFIED). "Expect files" = commonMain files that declare `expect` (VERIFIED, 52 in total). "Needs actuals" = the module must co-compile its jvmCommonMain/jvmMain actuals via `common_srcs` (VERIFIED: 62 of 63 expect declarations have a wasmJsMain actual; the missing one is `MermaidDiagramSurface`, already its own module).

| # | module | contents (Kotlin packages) | files | expect files | role |
|---|---|---|---|---|---|
| 1 | `core` | util, logging, coroutines, cache, units, resilience, security, benchmark, docs, parsing, error, `SyncOrigin`, `LockVerdict`, `WriteRefusedReason` | 42 | 4 | hub (fan-in 19) |
| 2 | `fusion` | fusion | 10 | 0 | near-leaf (3 dependents) |
| 3 | `model` | model, parser, outliner, loader, editor, clipboard, flashcard + moved leaf types | 52 | 1 | hub (15) |
| 4 | `platform` | platform root, `platform.image`, `platform.security`, `service` | 50 | 13 | hub (15) |
| 5 | `sched` | all of `jobs/` (BackgroundJobs, InProcessJobs, schedule/**) | 28 | 1 | near-leaf (4) |
| 6 | `persist` | db, repository, migration, search, performance, diagnostics, sections, vault, query, asset models | 197 | 15 | hub (12), too big, see 3.6 |
| 7 | `sketch_core` | sketch.constraints, excalidraw, v2 scene/commands/constraints/binding/render/tools/codec/importer/... | 136 | 0 | near-leaf (3) |
| 8 | `devices` | platform.sensor/ml/google/measurement + calibration | 60 | 0 | near-leaf (6) |
| 9 | `ai` | llm, voice, tags, domain | 65 | 1 | mid (6) |
| 10 | `git` | git.**, `GraphManager`, `GraphDiagnostics`, `GraphRelocationCoordinator`, `RestoreRuntime` | 113 | 0 | mid (5) |
| 11 | `merge` | merge, capture | 49 | 0 | near-leaf (4) |
| 12 | `asset` | asset.pipeline + indexing | 33 | 0 | near-leaf (4) |
| 13 | `console` | console | 20 | 0 | near-leaf (3) |
| 14 | `export` | export | 13 | 0 | near-leaf (3) |
| 15 | `transfer` | transfer.qrcode | 21 | 1 | near-leaf (2) |
| 16 | `sketch_runtime` | sketch root + export + v2.runtime/bridge, projectbridge, sketchimport, merge/SketchBundle* | 104 | 0 | near-leaf (3) |
| 17 | `ui_found` | ui theme/i18n/placement/onboarding + 12 root files (AppState, NotificationManager, WindowSizeClass, ...) | 20 | 6 | near-leaf (3) |
| 18 | `ui_main` | ui components, screens, state, nav, library, assets, transfer, drive, misplaced | 218 | 7 | near-leaf (2) |
| 19 | `ui_sketch` | ui.sketch, ui.annotate, ui.sketchimport, bazel_stubs | 255 | 3 | near-leaf (1) |
| 20 | `ui_app` | ui root: App, GraphContent*, StelekitViewModel, StelekitAppDependencies | 50 | 1 | **leaf** (0 dependents) |

Sum: 42+10+52+50+28+197+136+60+65+113+49+33+20+13+21+104+20+218+255+50 = 1536, plus `stats` 2 = 1538 (VERIFIED against the script's file count).

Leaf status (VERIFIED from fan-in): only `ui_app` has no dependents. "Near-leaf" means 1-6 dependents. Hubs (everything depends on them): core, model, platform, persist.

Actual files for expect declarations (VERIFIED): jvmCommonMain+jvmMain actuals map to persist 16, platform 13, ui_found 6, ui_main 5, core 4, ui_sketch 3, ai 1, sched 1, transfer 1, ui_app 1; one actual (`db/libsql/LibsqlNativeLoader.jvm.kt`) has no matching expect in commonMain and goes with `persist`. Non-actual jvmMain files (desktop UI, Main) stay in `jvm_main_lib` as the application layer above `ui_app` (INFERRED: 113 jvmMain + 49 jvmCommonMain files, I did not place the non-actual ones individually).

### 3.3 Edge-breaking work per cut (the minimum set)

| work item | count | cost class |
|---|---|---|
| Whole-file module assignment overriding the package default (no Kotlin change; Kotlin package stays) | 47 files (35 type moves + 12 ui root files to `ui_found`) | mechanical, S |
| Code edits R1-R10 (section 2.4) | 10 items touching about 22 files (INFERRED) | M x 10, R8 is the largest (StelekitViewModel is 2666 lines) |
| Widen/associate `internal` across modules | 26 imports in 14 files | S |
| Per-module `common_srcs` + actuals wiring | 10 modules with expects | M |
| Android variants (`android_main` compiles commonMain too) | 20 modules x 2 platforms (the `stelekit_kt_common_library` macro already has a `platform` switch) | M |

The 35 type moves (VERIFIED list in the script's `ov4.py`) are small leaf types: `SelectionFilter`, `LinkProtectedSpans`, `AhoCorasickMatcher`, `HeightOffset`, `FocalSource`, `SceneLimits`, `ImportSource`, `ImportTypes`, `LibraryModels`, `LibraryFilterRules`, `DirectoryScanResult`, `CalibratedLength` to `model`; `UrlLinkRepair`, `LfsPointer`, `StrayPageMerge`, `SqlConsoleView`, `ConfirmPolicy`, `ConsoleMode`, `VaultCredentialStore`, `AssetModels`, `AssetSortOrder`, `AssetSearch` to `persist`; `PlatformImageFile` to `platform`; `SyncOrigin` to `core`; `GraphManager`, `GraphDiagnostics`, `GraphRegistryKnownScopes`, `GraphRelocationCoordinator`, `RestoreRuntime` to `git`; `StandaloneSketch`, `SketchDeleteService`, `SketchBundleCopy`, `SketchBundleRemap` to `sketch_runtime`; `ExistingImageAnnotationOpener`, `PermissionRationaleDialog` to `ui_main`. I checked each move target's own imports with the graph tool; none introduces a new upward edge (the only residue is R1-R10).

### 3.4 Why not simply one module per package

`ui`, `sketch`, `projectbridge` form one mutually-importing cluster (`ui.sketch` -> `sketch` 148 files, `projectbridge` <-> `sketch` both ways, `ui.sketch` -> `ui.sketchimport` -> `ui.annotate` -> `ui.sketch` is a 3-package cycle). Splitting these by package would produce build errors; the proposal merges `ui.sketch`, `ui.annotate`, `ui.sketchimport` into `ui_sketch` and puts the runtime layer between `sketch_core` and the UI.

### 3.5 Layering rules from CLAUDE.md under the proposal (VERIFIED)

- `jobs.schedule` must not import git/ui/db/merge: scripted check over `jobs/schedule/**` found 0 edges to those packages (it imports only core, platform, model). `sched` depends on `platform` only, so Bazel visibility would enforce this and could replace `JobsScheduleImportDirectionTest`. The same applies to `SketchSolverImportDirectionTest`, `SketchImportImportDirectionTest`, `SketchV2EngineImportDirectionTest` (sketch layers).
- DB write gating: files referencing `@DirectSqlWrite` outside the `db`/`repository` packages are `git` (2: `GraphManager`, `SqlDelightGitConfigRepository`) and `asset` (1: `AssetMoveService`), the remaining 17 are in `persist`. The opt-in marker is declared in `persist`, so cross-module opt-in still works; `DirectSqlWriteOptInAllowlistTest` stays valid as a cross-cutting audit.
- Cross-graph writes: `merge` (router and writers) sits above `persist` and `git`, matching "one router, one lock".

### 3.6 Optional Stage B (persist and hubs)

`persist` (197 files) is the one module with an internal cycle that cannot be cut cheaply: `db` <-> `repository` <-> `performance` (SCCs 2 and 3; db->repository 24 and repository->db 25 file edges, performance->db 9, db->performance 14). Within `persist`, `migration` (17 files) only has outgoing edges (11 file edges into repository/db) and nothing else in `persist` imports it, so it is a clean split candidate; `sections` (9) and `vault` (7) are imported by `db` (2 and 5 edges) and are weaker candidates (INFERRED, not placed in the main DAG). `platform` (50) and `core` (42) are also worth splitting by concern if the blast-radius numbers below are not enough.

## 4. Existing tests mapped to modules

Test universe (VERIFIED, `find`): `*Test.kt` files: commonTest 507, businessTest 563, jvmTest 385, androidUnitTest 314 (1455 JVM-run = 507+563+385; the plan's "~1455" matches). Bazel-registered today (INFERRED from the BUILD files): businessTest 563 (glob), jvmTest about 357 (385 minus 19 screenshot/mermaid-engine and 9 Gradle-only excludes), commonTest 298 of 507 (listed in `business_common_tests`); so about 1218 of the 1455 actually run under Bazel today.

Method: a test's "primary module" is the highest-ranked module in the module set of its explicit imports plus same-package references (token scan, names of 4+ characters) plus the same for fixture files it imports. This is an over-approximation of coupling (INFERRED); counts below are the "loose" variant. The "strict" variant (explicit imports only) leaves 365 tests without a resolved dependency, so it is only used as a lower bound in section 5.

### 4.1 JVM-run `*Test.kt` by primary module (loose; VERIFIED computation, INFERRED attribution)

| module | total | commonTest | businessTest | jvmTest | cross-cutting* |
|---|---|---|---|---|---|
| core | 21 | 13 | 6 | 2 | 1 |
| stats | 1 | 0 | 0 | 1 | 0 |
| fusion | 6 | 4 | 2 | 0 | 0 |
| model | 62 | 46 | 11 | 5 | 1 |
| platform | 33 | 16 | 6 | 11 | 3 |
| sched | 11 | 10 | 0 | 1 | 0 |
| persist | 200 | 34 | 72 | 94 | 6 |
| sketch_core | 82 | 76 | 6 | 0 | 6 |
| devices | 60 | 37 | 11 | 12 | 1 |
| ai | 60 | 10 | 39 | 11 | 3 |
| git | 216 | 63 | 101 | 52 | 14 |
| merge | 59 | 12 | 27 | 20 | 4 |
| asset | 28 | 0 | 28 | 0 | 5 |
| console | 17 | 0 | 17 | 0 | 0 |
| export | 13 | 8 | 2 | 3 | 0 |
| transfer | 22 | 14 | 3 | 5 | 0 |
| sketch_runtime | 78 | 24 | 53 | 1 | 1 |
| ui_found | 8 | 3 | 1 | 4 | 0 |
| ui_main | 166 | 30 | 42 | 94 | 3 |
| ui_sketch | 189 | 93 | 78 | 18 | 4 |
| ui_app | 74 | 7 | 22 | 45 | 12 |
| no production dependency | 49 | 7 | 36 | 6 | 31 |
| **sum** | 1455 | | | | 95 |

*cross-cutting = name matches audit/structural/completeness patterns or reads sources/resources (see 4.3); 13 tests also span sibling modules that are not in the primary's dependency closure (they need an extra `deps` entry: asset 8, devices 2, model 1, export 1, ui_found 1).

androidUnitTest (314 `*Test.kt`, VERIFIED count; attribution INFERRED, android-only `androidMain` code excluded): ui_sketch 107, ui_main 84, git 45, ui_app 24, platform 21, devices 20, persist 4, sched 3, plus 6 others. They run against `android_main`, so the split needs android variants of each module (section 3.3).

### 4.2 Registries that must go away or stay cross-cutting

`AllBusinessTests.kt`, `AllJvmTests.kt`, `AllMermaidJvmTests.kt`, `AllAndroidUnitTests.kt` (VERIFIED) are explicit class lists; with per-module test targets they are replaced by per-target `glob`s (W9 step 3). Their completeness guards (`AllBusinessTestsCompletenessTest`, `AllJvmTestsCompletenessTest`, `AllMermaidJvmTestsCompletenessTest`, `TestRegistrationAuditTest`) disappear or turn into a Bazel-level check that every `*Test.kt` is in some `srcs`.

### 4.3 Tests that span modules and must stay as separate cross-cutting targets

VERIFIED counts: 50 files read sources through `wiring/WiringInputs.kt` (49 tests plus `WiringInputs.kt`/`WiringRoots.kt` helpers); 30 files use `WiredAppFixture`/`DesktopWiredAppFixture`; 35 `*Test.kt` files walk the tree or read bundled resources. 95 JVM-run tests match one of the cross-cutting heuristics. Proposed grouping:

1. **Whole-repo source audits** (keep as one target, `//kmp/audit`, depending on source filegroups from every module instead of the compiled modules): `WiringCompletenessTest`, `WiringAllowlistRenewalTest`, `FirstOpenSmokeCatalogTest`, `NoGateFallbackAuditTest`, `TestPlacementAuditTest`, `TestHygieneAuditTest`, `TestRegistrationAuditTest`, `NewCodeAuditTest`, `PlatformLayerAuditTest`, `DomainLayerAuditTest`, `ScopeEscapeAuditTest`, `NonCancellableReturnAuditTest`, `EgressSurfaceAuditTest`, `ExitPathAuditTest`, `PrivateStringGuardTest`, `DirectSqlWriteOptInAllowlistTest`, `MigrationRunnerSchemaSyncTest`, `QueryPlanAuditTest`.
2. **Module-scoped audits** (move into the owning module's test target, fed by that module's source filegroup): the git/LFS audits (`GitGateAuditTest`, `LfsStagingCallSitesStructuralTest`, `LfsPostPushAuditTest`, `PatLeakageAuditTest`), asset cloud audits (`CloudStructuralAuditTest`, `CloudKeyLeakageAuditTest`), sketch grep tests (`SceneLimitsGrepTest`, `MotionPolicyGrepTest`, `SketchCanonicalNumberGuardTest`, `HardcodedStringAuditTest`), `AirGapGuardTest` (transfer), `CaptureWriterSourceAuditTest` (merge).
3. **Direction tests replaced by visibility**: `JobsScheduleImportDirectionTest`, `SketchSolverImportDirectionTest`, `SketchImportImportDirectionTest`, `SketchV2EngineImportDirectionTest`.
4. **Wiring / entry-point integration** (one `//kmp/integration` target on top of `ui_app` and the desktop library): `GitSyncStackEntryPointTest`, `TaskSchedulerDesktopEntryPointTest`, `SketchStorageDesktopEntryPointTest`, `TextMetricsDesktopEntryPointTest`, `WiringV2DesktopEntryPointTest`, `HtmlPasteEntryPointTest`, `PostSyncProjectJobEntryPointTest`, `AssetMoveReplayEntryPointTest` and the rest of the 30 `WiredAppFixture` users.

Existing fixture libraries also constrain the split: `common_test_fixtures` bundles real `*Test.kt` classes with fixtures (`kmp/src/commonTest/kotlin/BUILD.bazel` header comment), `git_testsupport`, `merge_fixtures`, `observable_effect_*`, `sketch_chrome_fixture`, `scheduler_contract_fixtures_*`. They must be re-homed next to the module they support, otherwise every leaf test target would still depend on all production code through them.

## 5. Blast radius, today vs after the split

Definition: "today" = every JVM-run test depends on `jvm_main_lib` (VERIFIED from the BUILD files), so an edit to any file outside `stats/` and mermaid reruns the whole universe, 1455 test files (about 1218 registered under Bazel, INFERRED). "After" = test files whose module closure contains the edited file's module, computed over the DAG in 3.1 with R1-R10 done. Two attribution variants: **strict** (explicit imports only; undercounts same-package use) and **loose** (adds same-package token matches; overcounts). Bazel works at module granularity, the file-level columns show the theoretical floor.

| edited file | module | today | after, module cone strict / loose | file-level cone strict / loose |
|---|---|---|---|---|
| `jobs/schedule/NetworkGate.kt` | sched | 1455 | 432 / 809 | 147 / 289 |
| `git/lfs/LfsGuardedStaging.kt` | git | 1455 | 419 / 798 | 94 / 241 |
| `llm/AnthropicMessagesClient.kt` | ai | 1455 | 453 / 879 | 92 / 212 |
| `sketch/v2/render/DrawListBuilder.kt` | sketch_core | 1455 | 321 / 590 | 40 / 94 |
| `ui/components/ShortcutTable.kt` | ui_main | 1455 | 198 / 429 | 17 / 65 |
| `transfer/qrcode/QrCodec.kt` | transfer | 1455 | 223 / 451 | 0 / 4 |
| `db/GraphWriter.kt` (hub contrast) | persist | 1455 | 730 / 1132 | 166 / 294 |
| `util/ContentHasher.kt` (hub contrast) | core | 1455 | 1090 / 1405 | 470 / 633 |
| `model/Models.kt` (hub contrast) | model | 1455 | 1031 / 1378 | 706 / 890 |

Module cone sizes, strict / loose (tests rerun when anything in the module changes): core 1090 / 1405, stats 810 / 1236, model 1031 / 1378, platform 810 / 1234, persist 730 / 1132, sched 432 / 809, ai 453 / 879, git 419 / 798, fusion 344 / 596, sketch_core 321 / 590, devices 317 / 617, merge 289 / 574, asset 252 / 535, sketch_runtime 243 / 507, ui_found 228 / 437, console 235 / 454, export 231 / 450, transfer 223 / 451, ui_main 198 / 429, ui_sketch 125 / 263, ui_app 31 / 74.

Averaged over all 1538 commonMain files (VERIFIED computation): median module cone 289 (strict) / 574 (loose), mean 378 / 655, against 1455 today, i.e. roughly 2.2x to 3.8x fewer reruns on average, 2.5x to 5x at the median. The file-level floor is a median of 2 (strict) / 9 (loose), so module granularity leaves most of the possible gain unrealized. The reason is the hubs: an edit in `core`, `model`, `platform` or `persist` still reruns 50-97% of tests, and those four modules hold 341 of 1538 files (22%).

Gate G7 (touch one leaf-module file, only dependents rerun) is achievable for `ui_*`, `sketch_*`, `transfer`, `console`, `export`, `merge`, `asset`; it is only weakly true for `persist` and not true for `core`/`model`. Mutation-proving should use a leaf such as `transfer/qrcode/QrCodec.kt` (223 strict / 451 loose tests in cone, not 1455).

## 6. KMP angle: which modules could become wasmJs klibs

VERIFIED: commonMain has 0 `.kt` files importing `java.`, `javax.` or `android.` (the only hit is `loader/LogseqDataLoader.kt.disabled`, not compiled). That is expected, since Gradle already compiles all of commonMain for wasmJs (INFERRED from CI, not run here). The real constraints per module are expect/actual co-compilation, Compose, and third-party libraries.

| module | expect files | Compose imports | notable libs (import counts) | wasm klib readiness |
|---|---|---|---|---|
| fusion, sketch_core, merge, asset, console, export, git (and existing stats) | 0 | none | coroutines, arrow, serialization, okio (merge), ktor (git 12), sqldelight (git 2, asset 2) | ready: pure commonMain, no actuals needed; need wasm klibs of arrow/kotlinx/ktor/okio/sqldelight in the dependency lock |
| core, model, sched, ai, transfer | 4, 1, 1, 1, 1 | none | coroutines, arrow, serialization, datetime, ktor (ai 60) | ready if the module also compiles its wasmJsMain actuals together (`common_srcs`) |
| platform | 13 | 2 files (`PlatformFileOpener.kt`, `PngEncoder.kt`) | okio, datetime | needs 13 wasm actuals plus the Compose plugin |
| persist | 15 | 3 files (`OtelComposeTracking.kt`, `PlatformJankStatsEffect.kt`, `SectionDefinition.kt`) | sqldelight 73, coroutines 321, arrow 189 | needs 15 wasm actuals, Compose plugin and the sqldelight wasm driver; extract the 3 Compose files to lift this |
| devices | 0 | 1 file (`MonocularDepthEstimator.kt`, the R3 file) | ktor 36 | ready after R3 removes the Compose/ui dependency |
| sketch_runtime | 0 | 1 file (`PngExporter.kt`, the R6 file) | arrow 119, serialization 89 | ready after R6 |
| ui_found, ui_main, ui_sketch, ui_app | 6, 7, 3, 1 | 53, 3444, 2795, 344 imports | Compose throughout | needs the Compose compiler plugin and `Res` handling on wasm (plan S1, S3, O4) |

Counting actuals (VERIFIED): wasmJsMain provides 62 of the 63 expect declarations (the missing one is the mermaid surface), jvmMain 50, androidMain 50, iosMain 45. So per-module wasm klibs are not blocked by a missing wasm actual; the blocker for the Compose-free set is only the dependency lock (W2), and 8 of the 20 modules (fusion, sketch_core, merge, asset, console, export, git, and devices after R3), plus the existing `stats`, need no actuals at all, which makes them the safest first wasm klib spike targets (S1).

## 7. Caveats and what was not checked

- Import-based graph. Same-package implicit references were only scanned for the 18 split packages (token match, 133 hidden edges, 6 flagged upward, 2 real); references through reflection, `typealias` chains or generated code are not visible. Re-run the check after each real move.
- Fully-qualified references in code are included; KDoc-only references are excluded (they initially created fake cycles: `DomainError.kt` -> `ui.annotate`/`transfer`).
- Test attribution (section 4) is approximate: loose over-counts, strict under-counts. Totals in section 5 bracket the truth.
- Cones assume R1-R10 are done and the DAG in 3.1 is the actual Bazel dependency graph; real `deps` may be stricter.
- Non-actual jvmMain/androidMain files were not placed in modules individually; androidUnitTest attribution ignores android-only code.
- No Bazel build was attempted, so rules_kotlin issues (friend `associates` across 20 modules, `common_srcs` per module, K2 `internal` handling, compile time of 20 compile actions) are untested. The existing `stats` and `mermaid_lib` targets are the only precedent.
- The old `tier_manifest.bzl` and `project_plans/commonmain-bazel-target-split/` should be reconciled with this file-level approach before more work starts there.

## 8. Stage B brainstorm: ports, DI and hub decomposition (Kibitzer-assisted, 2026-11-21)

Kibitzer evidence (`architecture_assessment`, scope `kmp/src/commonMain/**`): 1832 advisory findings; coupling: `ui` imports 65 packages, `ui/sketch/v2` 47, `ui/components` 40, `db` 28, `projectbridge` 20; blast radius: `model` imported by 56 packages, `error` 48, `logging` 47, `platform` 43, `util` 35, `repository` 35. Its refactor planners (`suggest_refactors`, `plan_split_refactor`, `list_refactor_candidates`) are Go-only: on this Kotlin repo `suggest_refactors` abstained on every package it examined (first 50 alphabetically seen) and `list_refactor_candidates` returned none, so the cuts above come from section 3, not Kibitzer.

Ordered steps, each gated by G7 (a real edit re-runs only its dependents' tests):
1. Cheap wins: apply R4 (`TrackedCopyRunHost` into `merge`) and R10 (`SyncOrigin` enum to `error`). DONE on branch `refactor/w9-leaf-prep`. The `db/misplaced` and `ui/misplaced` packages need no move: they only appear in Kibitzer's cycle path; `db/misplaced` imports only `db`, `model`, `platform`, `coroutines`, `util`, `error` and `ui/misplaced` imports `db`, `model`, `ui.components`, so both fit their module as-is.
2. The 47 file moves into leaf modules (no code change).
3. Ports at the persist hub: narrow interfaces in `core` (`SpanSink`, file-system port, sketch-input port), implementations stay in `persist`/platform modules (R1, R2, R3, R5, R6).
4. Decompose `StelekitViewModel` (2666 lines) into feature controllers taking only the ports they need; screens depend on controller interfaces (R8). Single composition root; `StelekitAppDependencies` becomes a thin assembler no feature module imports (R9); renderer slots via `CompositionLocal` instead of cross-feature imports (R7).
5. Split bag modules: `util`, `platform`, `model` into smaller modules.
6. Enforce: Bazel `visibility` for the CLAUDE.md layering rules, per-module import-direction tests, CI check that the module graph is acyclic.

Unmeasured: the test-cone gain for hub splits (loose attribution, 289-574 median of 1455) and how the patched rules_kotlin handles the 52 `expect`/`actual` files across 10 modules.

## 9. Stage A implementation result (2026-11-21)

Implemented on branch `worktree-agent-aba8812b33c52ef3b` (not pushed). Everything below was run in that worktree; counts come from `kmp/modules_manifest.bzl` and the Bazel logs.

**What exists.** `kmp/modules_manifest.bzl` assigns 1724 JVM source files (commonMain, jvmCommonMain, jvmMain) to exactly one module; `kmp/modules.bzl` turns it into 20 generated `kt_jvm_library` targets in `//kmp/modules` (core 55 files, fusion 10, model 53, platform 64, sched 29, persist 225, sketch_core 139, devices 63, ai 66, git 141, merge 52, asset 33, console 20, export 13, transfer 22, sketch_runtime 103, ui_found 27, ui_main 219, ui_sketch 270, ui_app 56). `stats` and `mermaid` keep their existing targets, `gradle_only` (4 files: JMH benchmarks, the real `SketchFontResources.kt`) is compiled by nothing, and the 43-file `app` layer (desktop `Main`, CLI, MCP server, Google/desktop adapters) is compiled by `//kmp/src/jvmMain/kotlin:jvm_main_lib`, which now re-exports every module. Each module compiles its own commonMain files plus the jvm actuals of its expect declarations (`common_srcs`), and has friend access (`associates`) to every module below it, so the pre-split `internal` semantics hold. Android is not split.

**Code edits beyond R1-R9 as written.** R10/R4 were already in. R1: `util/SpanSink.kt` port, `SpanEmitter` implements it, `FileSystem.setSpanEmitter` takes it. R2: `RawPhase`/`RawPointer` moved to `ui/sketch/v2/RawPointer.kt` (package unchanged) and assigned to `sketch_core` together with `platform/SketchPlatformInput.kt`. R3: `DepthModelUiState` moved to its own file in `devices`. R5: `SketchBundleCopy.kt` split; the types and `SketchBundleFiles` stay in `merge` (layout constants duplicated behind a test), `SketchCopy.kt`/`SketchBundleRemap.kt` (with `renamed` as an extension) go to `sketch_runtime`. R6: `toScenePrimitive`/`toSheetProps` moved to `sketch/StoredSheetMapping.kt` (public), `runtime/export/*` assigned to `ui_sketch`. R7: `LocalSketchEmbedRenderer` composition-local slot in `ImageBlock`, provided in `GraphContentActiveShell`. R9: `rememberSketchTextMetricsProvider` moved to its own file in `ui_sketch` (added to `WiringRoots`). Also needed (SCC 1 of section 2.2, not in the R list): `LockVerdict`/`UnknownReason` and `WriteRefusedReason`/`NotRoundTrippable`/`WriteCapabilityReason` moved to leaf files assigned to `core`. R8 was done as two narrow interfaces (`ShareDialogHost`, `PageViewHost`, implemented by `StelekitViewModel`) plus moving `appStorageSubtitleFor`; the view-model decomposition itself is still open.

**Measured cone (G7).** Touching a leaf file with a behavior-neutral top-level function and running `bazel build --explain` over `business_tests`, `jvm_tests`, `mermaid_jvm_tests`, `transfer_tests`, `desktop_app`: an edit in `transfer` recompiled transfer (22 files), ui_main, ui_sketch, ui_app, jvm_main_lib (43 files) and the umbrella test libraries plus `transfer_tests`; an edit in `export` recompiled export, ui_found, ui_main, ui_sketch, ui_app, jvm_main_lib and the umbrella test libraries, and `bazel test //kmp/modules:transfer_tests` then reported `(cached) PASSED`. Every module outside the edited module's reverse-dependency closure (for `transfer`: 15 of 20) stayed cached, where before the split one 1700-file `jvm_main_lib` and all test targets rebuilt for any edit. Limits: the umbrella-based test targets (`business_tests`, `jvm_tests`) still rebuild for any JVM edit, because they depend on `jvm_main_lib`; only `transfer_tests` (14 classes, 48 tests, previously Gradle-only) is re-homed. rules_kotlin produces `.abi.jar` files of the same size as the full jars, so a body-only edit does not stop recompilation of dependents.

**Verification.** `bazel test //kmp:business_tests` PASSED (6340 tests, includes `ModuleManifestGuardTest`); `bazel test //kmp/modules:transfer_tests` PASSED (48 tests); `bazel test //kmp/src/androidUnitTest/kotlin:android_unit_tests --config=android` PASSED (391 tests); `./gradlew :kmp:compileTestKotlinJvm` BUILD SUCCESSFUL; `bazel test //kmp:jvm_tests` runs 1948 tests with 60 failures both on `origin/main` (d70623fecf) and on this branch (same environment: bazel's sandbox mounts the home directory read-only, so tests that write under `user.home` fail; the failing-test names were diffed).
