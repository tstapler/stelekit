# Implementation Plan: annotation-memory-and-nav-ia

**Feature**: Diagnose and harden the Android annotation-editor kill ("excessive CPU usage"), add one CPU+memory+jank benchmark harness with CI budgets, fix the annotation feature's CRITICAL/MAJOR defects, and make navigation self-explanatory by merging Assets and Gallery into one Library destination with grouped, described nav sections.
**Date**: 2026-11-08
**Status**: Repaired five times (architecture/adversarial review; pre-mortem and consistency review; triad iteration 1 plus the owner's 2026-11-08 decisions; triad iteration 2 with agent-chosen defaults; triad iteration 3, final, which fixed the search-semantics contradiction and added LD-11..LD-20 and ED-18..ED-27 in `design/ux.md`; see Repair log). All owner decisions OD-1..OD-6 and ADR-006 are DECIDED (section Owner Decisions); only OD-7 (veto-able scope additions) is open and it blocks nothing. Ready for implementation: Epics A and B, Epic C and Epic D are all unconditional. The critical path starts with PR-0 and the W9 release cut.
**Size**: 4 epics, 34 stories, 136 tasks (counts recounted by script from the headings; was 4 / 34 / 134 before Repair 5: +2 tasks, D.7a-0 and C.9m; was 4 / 34 / 121 before Repair 4: +13 tasks; was 4 / 33 / 105 before Repair 3: +Story D.11 and +16 tasks; was 4 / 32 / 93 before Repair 2; was 4 / 31 / 77 before Repair 1; all itemised in the Repair log)
**ADRs**: [ADR-001](../decisions/ADR-001-unified-resource-probe-and-budgets-file.md) harness + budgets file, [ADR-002](../decisions/ADR-002-cpu-measurement-method.md) CPU method, [ADR-003](../decisions/ADR-003-loupe-decode-strategy.md) loupe strategy, [ADR-004](../decisions/ADR-004-calibration-pixel-basis.md) calibration basis, [ADR-005](../decisions/ADR-005-undo-and-persistence-semantics.md) undo + persistence, [ADR-006](../decisions/ADR-006-nav-ia-decision-gate.md) nav IA (DECIDED: merge into Library), [ADR-007](../decisions/ADR-007-nav-section-model-and-phone-more-sheet.md) NavSection model, [ADR-008](../decisions/ADR-008-bounded-gallery-and-annotated-join.md) bounded Library reads and the query-level annotated join, [ADR-009](../decisions/ADR-009-benchmark-device-configuration.md) device configuration

Evidence labels follow the research: VERIFIED = opened or executed (cited file), INFERRED = reasoned. Research files: `research/{cpu,pitfalls,features,architecture,stack,build-vs-buy,ux}.md`. Path abbreviations: `CM` = `kmp/src/commonMain/kotlin/dev/stapler/stelekit`, `AM` = `kmp/src/androidMain/kotlin/dev/stapler/stelekit`, `AIT` = `kmp/src/androidInstrumentedTest/kotlin/dev/stapler/stelekit`, `AUT` = `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit`, `BT` = `kmp/src/businessTest/kotlin/dev/stapler/stelekit`, `CT` = `kmp/src/commonTest/kotlin/dev/stapler/stelekit`, `JT` = `kmp/src/jvmTest/kotlin/dev/stapler/stelekit`, `ANN` = `CM/ui/annotate`, `LIB` = `CM/ui/library` (Epic D, new).

---

## What we know and do not know (read first)

| Claim | Status | Source |
|---|---|---|
| The reported kill reason was "excessive CPU usage" | Owner statement, reason string not seen | requirements.md Amendment |
| Stock Android only applies the excessive-CPU kill to HOME/cached processes (>= 25% of a core averaged over ~5 min); foreground apps are exempt | VERIFIED (AOSP android14 source) | cpu.md section 1 |
| The kill was NOT reproduced: ~100 ms CPU/press with real injected touches, 36% of a core while the finger is down, 2-4% idle afterwards, no exit reasons, flat memory on 12 MP and 48 MP images | VERIFIED (Pixel_Fold AVD, Android 14) | cpu.md section 3, pitfalls.md Part B |
| Main thread is 70-75% of process CPU; the loupe is ~30% of the cost (142 vs 99 ms/press) | VERIFIED | cpu.md section 3 |
| Hoisting the per-press `ImageLoader` changed nothing measurable | VERIFIED | cpu.md section 2 row 4 |
| `performTouchInput` inflates CPU 5-7x | VERIFIED | cpu.md section 3 |
| Root cause of the owner's kill | **NOT FOUND** | cpu.md summary |

Consequence for the plan: Epic A is structured as *capture evidence, widen the repro to the only configuration that can trigger the stock rule (background after a burst), then apply cheap fixes whose value is proven by the same probe*. No fix is claimed to cure the owner's crash until the owner's `importance=` line and a post-fix soak support it. Success Metric 1's literal "reproduces the pre-fix failure" cannot be met if the failure never reproduces; the substitute is the "gate fails on a mutated pre-fix build" proof (task B.5c), and this limitation is stated in Unresolved Questions.

**Claim vocabulary (fixed in advance)**: without the owner's Logs `importance=` line (W1), the plan may claim only *mitigated and monitored*, never *fixed*. The background repro (A.3) measures idle CPU after a burst and, via the positive control (task A.3d), is shown able to reach the process state and exit-reason path the stock rule uses; if the control shows the instrumented process never reaches cached/HOME importance, A.3 is renamed "idle CPU after burst" and makes no claim about the OS rule. Success Metric 1's "reproduces the pre-fix failure" is unmet; `requirements.md` **Amendment 2 (ACCEPTED by the owner 2026-11-08, OD-5)** replaces it with the criterion this plan implements: gates proven red by the pre-fix proof procedure in Story B.5 (probe present, fix reverted or mutated, red result recorded), a positive-control repro (A.3d), diagnostics released first (Story A.0, PR-0), and "mitigated and monitored" wording until the owner's `importance=` line and a post-fix device soak say otherwise. A manual device soak on the owner's phone (W4) precedes any "fixed" wording even if A.3d outcome (b) removes the CI background repro.

---

## Domain Glossary

| Term | Definition | Notes |
|---|---|---|
| `ResourceProbe` | Instrumented-test helper that snapshots CPU, memory, frames and Coil stats around a scenario | Replaces the "MemoryCpuProbe" working name because it also records frames and exit reasons; lives in `AIT/performance/probe/` |
| `ProbeSnapshot` | Immutable record: `cpuMs`, `wallMs`, `threadTicks`, `javaUsedBytes`, `nativeAllocatedBytes`, `pssKb`, `graphicsPssKb`, `vmHwmKb` | Value class fields use `Bytes`/`Kb` suffixes, never bare `Long` in signatures |
| `ProbeScenarioId` | Sealed identifier for a scenario (`OpenLargePhoto`, `RapidPlacement`, `LoupeCycles`, `SelectMoveDrag`, `CaptureAnnotateExport`, `LibraryScroll`, `ScreenSwitchChurn`, `SustainedDrag`, `BackgroundIdle`, `CpuVsAnnotationCount`, `RealPersistenceDepth`, `LargeSource`) | Matches Success Metric 2 letters (a) open editor, (b) `RapidPlacement`, (c) `LoupeCycles`, (d) `SelectMoveDrag`, (e) `CaptureAnnotateExport`, (f) `LibraryScroll`, (g) `ScreenSwitchChurn`; plan-added letters (h) `SustainedDrag`, (j) `CpuVsAnnotationCount`; `BackgroundIdle`, `RealPersistenceDepth` and `LargeSource` have no letter |
| `ProbeRecord` | One emitted JSON line: scenario, metric, value, unit, sha, device profile | Consumed by `scripts/bench-budget-check.js` and `benchdiff-export.js` |
| `DeviceProfile` | `memoryClass`, `largeMemoryClass`, `isLowRamDevice`, `heapGrowthLimit`, `memTotalKb`, API level, GPU mode | Attached to every `ProbeRecord` (ADR-009) |
| `BudgetEntry` | `{key, budget, mode, calibratedFromRuns, mutation}` in `benchmarks/budgets/annotation-resource-budgets.json`; the file also carries top-level `globalMode` (`report-only` / `enforce`) | `mode` is `BudgetMode`; `mutation` names the `MutationFlag` that proves a `gate` entry can fail |
| `BudgetMode` | Enum `Gate` / `ReportOnly` (JSON `gate` / `report-only`; prose says "gate" and "report-only") | Promotion or rollback is a one-field edit; `globalMode: report-only` demotes every entry with one edit (ADR-001) |
| `DirectTouchInjector` | Test utility posting `MotionEvent`s straight to the root view | Required for CPU numbers (ADR-002) |
| `ThreadCpuShare` | Per-thread-group CPU ticks parsed from `/proc/self/task/*/stat` | Parser `ProcStatParser` is pure, in commonMain |
| `CoilDecodeStats` | Test `EventListener` counters: decode count, max bytes, cache hits, `MemoryCache.size` | Needs the `eventListenerFactory` seam (task B.2a) |
| `ExitRecord` | Common-code mirror of `ApplicationExitInfo`: reason, importance, status, pssKb, description, timestamp | Formatter is pure and unit-tested |
| `ProcessImportance` | Named importance (`Foreground`, `Visible`, `Service`, `Cached`, ...) mapped from the int | Decides which OS kill rule can apply |
| `DiagnosticsSample` | Persisted line: cpu ms delta, top-3 thread groups, foreground flag, heap, Coil memory-cache size, undo depth, screen, image dims | Written by `EditorDiagnosticsSampler` only while the editor is open |
| `Loupe` | The 120 dp magnifier circle shown while a finger is down | Not "magnifier"; `Modifier.magnifier` is a rejected option |
| `LoupeWindow` | Source-image region displayed by the loupe | Only used if ADR-003 selects region decode |
| `NativeImageSize` | Width/height of the file in oriented space, read from file bounds, never from the Coil painter | Newtype (ADR-004) |
| `Calibration` | Existing model: method + pixelsPerMeter basis | Basis becomes `NativeImageSize` |
| `InProgressPoints` | Points placed but not yet forming a committed annotation | Never restored by undo (ADR-005) |
| `UndoHistory<T>` | Bounded (<= 50) undo/redo stack, extracted from the ViewModel | New class (ADR-005) |
| `UndoEntry` | Sealed: `Committed(before)` / `Calibration(before, after)` | Exhaustive `when` |
| `AnnotationPersistenceQueue` | Single-consumer queue ordering every editor write; the writes themselves go through `DatabaseWriteActor` | Owns its scope (`SupervisorJob + Dispatchers.Default + handler`) and a `close()` called by the VM |
| `NavSection` | Group of destinations with a header (`Daily`, `Connections`, `Graph`, `Developer`) | Pure commonMain model (ADR-007). Repair 4: the Library row is the first row of `Graph`; there is no `Media` or `Library` section (ux.md ED-16) |
| `NavDestinationSpec` | `{screen, labelKey, descriptionKey, iconKey (NavIconKey enum), visibility}` for one nav row | Labels are I18n keys; tests use the constants |
| `AnnotatedBadge` | "Annotated" marker (icon plus word) on a Library card | Comes from the query-level join in the same paged read (ADR-008) |
| `MemoryPressureLevel` | commonMain enum carried by the `MemoryPressureListeners` fan-out (`Moderate`, `RunningLow`, `RunningCritical`, `Complete`) | Task A.2e |
| `EditorDiagnosticsSource` / `EditorDiagnosticsSink` | commonMain ports between the ViewModel's read-only view and the androidMain sampler | Task A.2f |
| `CrosshairPlacementState` | Pure commonMain state for crosshair + nudge placement and non-drag vertex move; keeps crosshair logic out of the ViewModel | OD-1 included (C.8c, C.8f) |
| `MutationFlag` | Test-only instrumentation argument that injects a named failure (`loaderPerPress`, `perDrawMeasure`, `busyWaitMove`, `backgroundBusyLoop`, `leakStrongRef`, `exitOnBackground`, `loupeFullDecode`, `cacheNoLimit`, `undoUnbounded`, `oomInjection`) to prove a gate can fail | Task B.5d; lives in `androidInstrumentedTest`/debug only |
| `Library` | The merged Assets + Gallery destination (`Screen.Library`), working title, I18n-keyed (ADR-006 DECIDED 2026-11-08) | Plan prose says Gallery/Assets only for today's code and for the legacy aliases; the Library row is the first row of the `Graph` nav section |
| `LibraryQuery` | Immutable filter + sort + paging request: annotated, media type, orphaned, tag, search, sort, limit, after (last-row key tuple; keyset paging, never OFFSET) | One paged repository call per page (ADR-008) |
| `LegacyScreenAlias` | Mapping from `Screen.Gallery` / `Screen.AssetBrowser(q)` and their tracing keys to `Screen.Library` | Redirect at `navigateTo`; never on the back stack (D.11a) |
| `PlacementGesture` | Pure classification of a press into QuickTap, DragAim, Cancel, MultiPointerCancel, PointerClick | Thresholds: 8 dp slop and 120 ms only, no upper time limit (ED-3, ED-3b; C.9f, C.9j) |
| `LibraryFilterRules` | Pure commonMain rules for Library filters: groups (Type OR, Show and Tags separate), `disabledReason(chip, state)`, `normalize(state)`, plus `LibraryMediaClass.of` (LD-11) and `LibrarySearch` (tokenise, escape, patterns; LD-3) | ux.md LD-1, LD-3, LD-7, LD-8, LD-11; task D.6g; shared by the UI, the ViewModel and the SQL parity test |
| `NudgeStep` | Sealed Fine (1 screen px), Medium (8 dp), Coarse (5% of canvas width), Exact (1 image px) with a conversion to image pixels | ux.md ED-6; task C.8i |
| `HandleHitResolver` | Pure canvas hit resolution: handle beats line, nearest vertex within 24 dp wins, deterministic ties | ux.md ED-1; task C.8e |
| PR-0 | The diagnostics-first release: A.2a, A.2b, A.2e, A.2f, the minimum A.2d sampler, A.0a-c | Merged and released before any A.4/A.5/C behaviour change (Story A.0) |

Glossary count: 36 terms (Repair 3: `MeasuredPhotos` / `Files` replaced by `Library`; `LibraryQuery`, `LegacyScreenAlias`, `PlacementGesture` added; Repair 4: `LibraryFilterRules`, `NudgeStep`, `HandleHitResolver` added; counted by script).

---

## Pattern Decisions

CREATIVE pass (Step 0.5). Three overall approaches:

1. **Fix-first**: apply every suspected CPU/memory fix, then benchmark. Strength: fastest visible change. Weakness: the kill never reproduced, so fixes cannot be shown to matter (hoisting the loader already measured as no effect).
2. **Measure-first, fix-with-proof (chosen)**: build the probe and the widened repro first, apply each fix behind a before/after probe delta, keep or relabel as hardening by a pre-declared rule. Strength: every claim is conditional on measurement, gates are born with a failing proof. Weakness: more up-front harness work before the first fix.
3. **Out-of-process Macrobenchmark**: official tooling, sees process death from outside. Weakness: separate non-debuggable APK, emulator discouraged, UiAutomator drags over a Compose canvas (build-vs-buy.md section 1). Rejected as the gate; remains a device-only optional trace.

| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|---|---|---|---|---|
| Overall sequencing | Measure-first, fix-with-proof | Evidence discipline (CLAUDE.md) | Fix-first | Kill not reproduced; fixes unproven |
| Benchmark harness | Hand-rolled `ResourceProbe` (Template Method over scenarios) | GoF | Macrobenchmark `MemoryUsageMetric` | Emulator-hostile, new APK type (ADR-001) |
| Budget evaluation | Single external evaluator script reading one JSON file | Separation of measurement from policy | Per-test hard-coded asserts | Promotion/rollback must be one edit |
| `ProcStatParser`, `ExitRecord` formatter, `BudgetEntry` | Pure functions / value objects in commonMain | CLAUDE.md "pure logic in commonMain" | Android-only helpers | One commonTest run covers every target; property-testable |
| Coil instrumentation | Seam (optional `eventListenerFactory` param, default null) | Fowler "Separated Interface"/seam | Reflection into the loader | No production behaviour change |
| Editor persistence | Queue (single consumer) | Concurrency pattern | Scattered `persistLock` | Lock gaps are the bug (ADR-005) |
| Undo | `UndoHistory<UndoEntry>` extracted class | Memento/Command-lite | Whole-state snapshots in the VM | Ghost-point bug class (ADR-005) |
| `UndoEntry` | Sealed interface | type-driven-design | Boolean flags | Exhaustive handling |
| `NativeImageSize`, `Bytes`, `Kb` | Newtypes / value classes | type-driven-design | Raw `Int`/`Long` | Decode size vs native size confusion is the C1 defect |
| Nav model | Pure data model rendered by Sidebar, phone sheet, palette | PoEAA "Registry"-style single source | Three hand-written lists | Drift guarded by a commonTest invariant (ADR-007) |
| Library reads | Pagination with the `annotated` join in SQL, bounded tag projection | CLAUDE.md graph-scale rule | Standing unbounded flow; per-item or chunked lookups (N+1) | GC thrash/OOM class (ADR-008) |
| Loupe | Strategy chosen by spike | GoF Strategy (only if >1 impl survives) | Decide up front | No measurement supports either (ADR-003) |
| Domain errors | Arrow `Either<DomainError, T>` | CLAUDE.md | Thrown exceptions | Repo rule |

---

## Tech Debt Disposition

| Area | Existing Issue | Disposition | Justification |
|---|---|---|---|
| `ANN/AnnotationEditorViewModel.kt` (1,049 lines, `@Suppress("LargeClass")`) | God Object; save paths outside `persistLock`; no `CoroutineExceptionHandler` (architecture.md section 8, features.md C4/M1) | **Isolate via seam**: new `UndoHistory`, `AnnotationPersistenceQueue`, `EditorDiagnosticsSampler` classes; VM only delegates (tasks A.2b, C.3b, C.5a) | Adding more methods to the VM would repeat the violation; a full split is out of scope |
| `ANN/AnnotationEditorScreen.kt` (1,684 lines) | `loupeOffset` read at screen scope; `LoupeOverlay` inline with its image request (cpu.md section 2) | **Isolate via seam**: move `LoupeOverlay` to `ANN/LoupeOverlay.kt` with an injectable loader/request seam (task A.4a) | Needed for benchmark injection and the ADR-003 spike |
| `CM/ui/components/SteleKitAssetFetcher.kt` | Loader built per call site; no listener seam; `CapturePreviewDialog` bypasses shared cache (architecture.md A12) | **Extend as-is** (add `eventListenerFactory`, test cache reset, route `CapturePreviewDialog`) | Small, stable file; additions are seams, not new violations |
| `CM/ui/gallery/GalleryViewModel.kt` and `CM/ui/assets/AssetBrowserViewModel.kt` | Gallery: standing unbounded `getAllImageAnnotations()`, in-memory `sortImages` and `availableTags` aggregation over the full list (`GalleryViewModel.kt:77,89-91,115-120`); Assets: separate ViewModel and screen for the same files | **Replace via merge** (ADR-006 DECIDED): one `LibraryViewModel` with SQL paging, sort, filters and the `annotated` join (tasks D.7a-D.7c, D.6); old screens deleted in D.11b only after the parity table is green | Violates the CLAUDE.md graph-scale rule; the merge removes two ViewModels instead of fixing both |
| `CM/ui/components/Sidebar.kt` (1,283 lines; flat list `:315-355`, Gallery and Assets rows `:340-341`) | Flat list, no sections | **Extend as-is** with a rendering of `NavSection` (small composable); no refactor of unrelated code | Stable file; new code is a leaf composable |
| `AM/ui/PlatformBottomBar.android.kt`, `CM/ui/ScreenRouter.kt` | Small, stable | **Extend as-is** | No violation added |
| `.github/workflows/android-benchmark.yml` | Inline Python summarizer | **Isolate via seam**: extract to `scripts/android-benchmark-summarize.py` before adding fields (task B.4b) | Summarizer would otherwise keep growing inline |
| `AnnotationExporter`, `AndroidAnnotatedImageRenderer` | Label text never drawn; EXIF ignored; fixed pixel sizes (features.md M5) | **Refactor-first** within story C.7 | Correctness defects, not style |
| `CM/repository/SqlDelightMeasurementAnnotationRepository.kt` (writes at `:39-117`: `saveMeasurementAnnotation`, `saveMeasurements`, `deleteMeasurementAnnotation`, `deleteMeasurementsForImage`) | Mutating calls under `@DirectRepositoryWrite` + `withContext(PlatformDispatcher.DB)`, bypassing `DatabaseWriteActor` (`DirectRepositoryWrite.kt:5-10` defines this as must-go-through-the-actor; `AnnotationEditorViewModel.kt` opts in at lines 507, 597, 642, 703, 716, 736, 847; `research/architecture.md` sections 2 and 4 claimed otherwise and are wrong) | **Refactor-first** (task C.5c, sequenced before the queue C.5a/C.5b; C.5d routes the VM-level paths) | CLAUDE.md "Route writes through `DatabaseWriteActor`"; the actor already has a generic `execute(priority, invalidate, op)` at `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/DatabaseWriteActor.kt:763` and serialises on one channel, so no schema change is needed and the queue's `persistLock`-style serialisation reduces to ordering only |
| `CM/ui/MemoryPressureListeners.kt` (`register(listener: () -> Unit)`, `notifyListeners()`, lines 17-37) | No trim level in the fan-out; callers `StelekitViewModel.onMemoryPressure`, the asset-pipeline listener and `MemoryPressureListenersTest` | **Extend as-is** (task A.2e adds a typed `MemoryPressureLevel` commonMain enum and `register((MemoryPressureLevel) -> Unit)`, keeping the no-arg overload) | Small stable file; existing callers keep compiling |
| `ANN/AnnotationEditorViewModel.kt` growth from C.8c, C.9c, C.1b, B.2c | More members added to a 1,049-line `@Suppress("LargeClass")` VM | **Isolate via seam**: crosshair logic is `CrosshairPlacementState` (pure, commonMain) held by one VM field; `NativeImageSize` parsing lives in `ImageBoundsReader`; Epic C PRs track `wc -l AnnotationEditorViewModel.kt` and must show no net growth | Architecture review concern; keeps the VM a delegator |
| Instrumented tests not in CI (`AssetPipelineHeapGateTest`, `AssetBrowserE2ETest`, ...) | Unprotected (architecture.md headline 5) | **Isolate via seam**: only the new annotation scenarios are wired in B.6; legacy classes are listed in Unresolved Questions | Wiring all would blow the job's runtime without improving this project's goals |

---

## Migration Plan

- **Default**: Epics A, B and D (except D.7a/D.7c: new queries and indexes) are code-only; no table change.
- **Epic C.1 is a real migration (resolved, was a contingency)**. The calibration basis is already stored: `image_annotations.pixels_per_meter` + `calibration_method` (`SteleDatabase.sq:1034-1036,1090,1106`; table created in `MigrationRunner.kt:392`). The old rows are decode-size-relative and the new `NativeImageSize` basis changes the meaning of every new row, so old and new rows must be distinguishable. Chosen persistence (confirmed or overturned by spike C.1a *before any code*, recorded as an ADR-004 amendment): a new column `calibration_basis_version INTEGER NOT NULL DEFAULT 0` on `image_annotations` (0 = legacy decode-relative, 1 = native pixels); rows are written as 1 only after the fix. Required steps in task C.1d: (1) add the column to `SteleDatabase.sq`; (2) add an `ALTER TABLE ... ADD COLUMN` entry (guarded against re-run, following the existing entries) to `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/MigrationRunner.kt` and make sure `MigrationRunnerSchemaSyncTest` (`kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/MigrationRunnerSchemaSyncTest.kt`) passes; (3) add write stubs to `RestrictedDatabaseQueries` annotated `@DirectSqlWrite` if the insert/update queries change; (4) `./gradlew :kmp:generateCommonMainSteleDatabase` then `rsync -a kmp/build/generated/sqldelight/code/SteleDatabase/commonMain/ kmp/src/generated/sqldelight/` and commit the generated sources; (5) a unit test reads a pre-fix row (version 0) and a post-fix row (version 1) and asserts no stored value changes for version 0; (6) **displayed values are asserted, not only stored ones** (task C.1e): the editor, Gallery cards and export must show for a version-0 row the same distance text as before the update, because they all recompute from normalized points and `pixelsPerMeter`; (7) a downgrade test opens a database containing the added column with the pre-fix schema (older APK) and reads/writes succeed. The migration is **ALLOWED** (OD-6 DECIDED 2026-11-08, requirements Amendment 2); legacy measurements are never silently rescaled. Generated-sources regeneration is serialised across PRs in the order C.1d/C.1f, D.7c, D.7a (one owner per regeneration, regenerate on rebase, never hand-merge) and the `SQLDelight generated sources` CI job (`ci.yml:81-107`) is **not** a required check today (verified 2026-11-08: no rulesets, `main` not protected); task B.6e records that and puts the owner-independent fallback in force (local `git diff --exit-code kmp/src/generated/` plus `gh pr checks` green before merge). Reversibility: additive column; reverting the PR leaves an unused column. If C.1a finds a different shape (for example a settings key), the amendment names it and C.1d is re-scoped; either way the marker is persisted and tested. C1 stays labelled *unconfirmed* (features.md marks it INFERRED) until C.1a runs the two-window-size log from features.md C1.
- **Epic D (ADR-008)**: the new paged Library queries (with the `annotated` join) are read-only and need the SQLDelight regeneration commit, not a table migration (D.7a). **Indexes are migrations too**: `idx_image_annotations_imported_at` already exists (`MigrationRunner.kt:553-556`); any further index needed for the join key, the per-sort-order and tag-filtered paged queries is created in `SteleDatabase.sq` *and* in `MigrationRunner` (task D.7c), with `MigrationRunnerSchemaSyncTest` extended to cover indexes, and `QueryPlanAuditTest` extended in the same PR.
- **Rollback**: revert the specific PR; the only on-disk change (C.1d column, D.7c indexes) is additive.

## Observability Plan

- **Logs** (all through `Logger`, never `android.util.Log`, so they reach `AndroidFileLogSink`): editor open/close with image `NativeImageSize` and tier; `DiagnosticsSample` every 10 s while the editor is open and on each placement burst (>= 5 placements in 2 s); trim-memory events with level; loupe degradation level changes; `ExitRecord` lines (10 entries) via `PreviousSessionLog.replay`.
- **Metrics (probe scenarios, CI)**: see Epic B tables. Each operation >100 ms in the editor (decode, export bake) logs duration.
- **Alerts**: no pager. The alert is a failing `scripts/bench-budget-check.js` step (gate mode) or a regression row in the sticky benchdiff PR comment (report-only).
- **Privacy**: records contain dimensions, byte counts and thread names only; no image paths or contents are logged or uploaded (requirements Non-functional).

## Risk Control

- **Feature flag**: not gated for fixes (bug-fix semantics). The loupe strategy (A.5b) ships with a one-line `LoupeStrategy` constant so a revert is a single value change. Epic D ships behind no flag but as its own PR(s), reversible by revert.
- **Gate rollout (Repair 2)**: every new gate starts report-only. The budgets file carries `globalMode: report-only` (ADR-001), the budget step runs `continue-on-error` and is not a required check for the first 10 runs, and the job path-filters to `AIT/performance/**`, `kmp/src/commonMain/**/annotate/**`, `SteleKitAssetFetcher.kt` and `benchmarks/budgets/**`. Promotion (`globalMode: enforce` plus per-entry `mode`) happens in B.7b after >= 10 valid runs; demotion is the same one-field edit. Owner escape hatch: PR label `skip-annotation-gate`.
- **Rollback per epic**:
  - A: revert the individual fix PRs (each fix is its own PR with its before/after numbers); diagnostics (A.0, A.2) are additive and can stay.
  - B: set affected budgets to `report-only`; remove the added `gradlew` line from `android-benchmark.yml`; harness code is test-only.
  - C: each story is a separate PR; C.3/C.5 (behaviour change in undo) revert as a unit with ADR-005.
  - D: revert D.4 (phone sheet) independently of D.3 (sidebar); the drawer code stays until D.4 merges. The old Gallery and Assets screens and ViewModels are deleted only in D.11b, after D.6 parity is green, so D.6-D.10 can be reverted without losing either screen; D.11b is the last Epic D merge and reverts alone.
- **Staged rollout**: full rollout on merge; the owner dogfoods the release APK (wall-clock blocker W3).

## Unresolved Questions

Owner decisions OD-1..OD-6 and ADR-006 are DECIDED (2026-11-08, section Owner Decisions below); only OD-7 and the open questions in this list remain.

- [ ] What does the owner's in-app Logs line say (`Previous process exit: ... importance=<n> <description>`), and was the app on screen at death? Decides foreground-burst (non-stock/OEM) vs background busy loop vs mis-paraphrased reason. Blocks the *claim* that any Epic A fix cures the crash; does not block A.2-A.4 — owner: Tyler
- [ ] Is there an OEM battery/CPU watchdog on the owner's phone (Samsung/Xiaomi/Oppo-style; cpu.md section 1 INFERRED)? Needs the phone model and Android version; no CI harness can reproduce it — blocks nothing, limits what "fixed" can mean — owner: Tyler
- [ ] Native/graphics memory on a real low-RAM phone: the AVD ignored `-memory 2048` (pitfalls.md Part B) and swiftshader hides hardware-bitmap pressure. Needs a physical-device run (or Firebase Test Lab, build-vs-buy.md) — blocks promotion of any memory metric to `Gate` beyond the deterministic set — owner: Tyler (device) / agent (script)
- [ ] Is LeakCanary `3.0-alpha-9` (2026-06-25, build-vs-buy.md) acceptable as an optional report-only dependency? Plan default: WeakReference leak gate with no new dependency (task B.3d); LeakCanary only if approved — blocks nothing — owner: Tyler
- [ ] Is PR #560 (loupe `layout` fix, `LoupeAlignmentE2ETest`) merged? **Checked 2026-11-08: still OPEN, unmerged.** It touches the same loupe code that A.4a (extraction), C.2b, C.9a, C.9f and A.5 rewrite, and the cpu.md baselines were measured without it. **Owner-independent fallback (task A.3f)**: rebase if merged; otherwise stack the loupe work on #560's branch; if #560 is abandoned, land its fix first as a prerequisite PR. Baselines are re-measured on the chosen base. Only B.6c (wiring `LoupeAlignmentE2ETest` into CI) waits for the real merge — owner: Tyler (merge), agent (fallback)
- [x] ~~Final Library names / IA option (ADR-006)~~ DECIDED 2026-11-08: merge into one destination, working title "Library" (rename is one I18n edit, task D.1a); tags are annotation tags only (Epic D decision 4). No longer blocks Epic D.
- [ ] Where is `Calibration.pixelsPerMeter` persisted and what decode size did existing sessions assume (git history of PR #556/#558)? Blocks story C.1 beyond spike C.1a — owner: agent (spike C.1a)
- [ ] Do Coil 3.2.0 API shapes used in stack.md section 2 (`EventListener`, `Image.size`, `MemoryCache.size`, default `allowHardware`) match? Blocks B.2a (first task is a `javap` spike) — owner: agent
- [ ] Does `androidInstrumentedTest` see classes from `commonMain` helpers (`ProcStatParser`)? Expected yes (it is the app's debug test APK); verify in B.1a — owner: agent
- [ ] Which output channel survives for a local no-logcat run (`connectedDebugAndroidTest` uninstalls the app, pitfalls.md Part B)? Candidates: `additionalTestOutputDir`, instrumentation status, assertion message — blocks B.4a only — owner: agent
- [ ] Does the extra scenario time keep `android-benchmark.yml` under an acceptable CI duration, or must the job split? Decided from the first CI run in B.6a — owner: agent
- [x] OD-1 .. OD-6 are DECIDED; see the Owner Decisions section. (Entries removed from this open list; history in the Repair log.)
- [ ] **OD-7 Scope additions beyond requirements (recorded so the owner can veto; not blocking)**: D.5 "Annotate a photo" palette command, D.9 first-run tip, MAJOR/minor fixes in C.6-C.9 (metric 5 requires only BLOCKER/CRITICAL), handedness in C.9a (ux.md optional; setting location open) and the Epic C UX-criterion tasks added in Repair 2. Consistent with the owner's pursue-full-scope stance; recorded so the owner can veto — owner: Tyler
- [ ] **Agent-chosen defaults awaiting owner review (Repair 4, not blocking)**: ux.md LD-1..LD-20 and ED-1..ED-27 ("Defaults - owner may override"), notably ED-8 (orphan Annotate disabled with a reason instead of the 2026-11-08 snackbar), ED-3 (quick tap moves the crosshair instead of placing), ED-16 (Library in the Graph section) and ED-17 (cold-user sessions, W11) — owner: Tyler
- [ ] Legacy instrumented tests (`AssetPipelineHeapGateTest`, `AssetBrowserE2ETest`, `AssetBrowserIndexedE2ETest`) are not in CI; wiring them is outside this project's goals but is recorded here as follow-up debt — owner: Tyler

## Owner Decisions

All DECIDED on **2026-11-08** by the owner (Tyler). Wording that said "pending owner confirmation" has been removed from this plan, `requirements.md` and the ADRs.

| ID | Decision | Status | Where it lands |
|---|---|---|---|
| OD-5 | **ACCEPTED**: the honest replacement for Success Metric 1. Probe-proven gates red on mutated/unfixed behaviour (Story B.5 procedure), a positive-control repro (A.3d), diagnostics shipped first (Story A.0), and the wording **"mitigated and monitored"** until the owner's Logs `importance=` line says otherwise. | DECIDED | requirements.md Amendment 2 item 1; Claim vocabulary; Stories A.0, A.3d, B.5 |
| OD-6 | **ALLOWED**: calibration-basis migration with a persisted marker column (`image_annotations.calibration_basis_version`), a `MigrationRunner.all` entry, `MigrationRunnerSchemaSyncTest`, regenerated SQLDelight sources per CLAUDE.md; legacy measurements are never silently rescaled. | DECIDED | requirements.md Amendment 2 item 2; ADR-004; Tasks C.1d, C.1e, C.1f, C.1g |
| OD-1 | **INCLUDED**: non-drag placement and move mode (Crosshair + nudge, "Move vertex" TalkBack actions). Drag-to-aim stays the default. | DECIDED | Tasks C.8c, C.8f, C.8g, C.9f; UX-33, UX-34, UX-36, UX-39 unconditional |
| OD-2 | Included with OD-1 (step selector 1 px / 10 px / 1% of width, drag-the-crosshair), because the dependent stories were made unconditional. | DECIDED (consequence of OD-1) | Task C.8c; UX-33, UX-34 |
| OD-3 | Included with OD-1 (off-canvas release cancels placement, UX-38). | DECIDED (consequence of OD-1) | Tasks C.9a, C.9f; UX-38 |
| OD-4 | Included with OD-1 ("Move vertex N" TalkBack actions; Drive export offline message, message only, no retry queue). | DECIDED (consequence of OD-1) | Tasks C.8f, C.7a, C.7c; UX-29, UX-36, UX-39 |
| ADR-006 | **MERGE** Assets and Gallery into ONE destination, working title "Library", with filters (Annotated, media type, Orphaned, tag, search) and sort. Replaces the earlier rename-by-job recommendation (option C); options A and C are recorded as **rejected** in the ADR. | DECIDED | Epic D (D.1-D.11); ADR-006, ADR-007, ADR-008 amendments; ux.md S1-S7 |

Open, not blocking: OD-7 (scope additions: D.5 "Annotate a photo" command, D.9 first-run tip and upgrade notice, MAJOR/minor fixes in C.6-C.9, handedness, the Epic C UX-criterion tasks).

## Dependency Visualization

```
WALL-CLOCK (owner Tyler unless noted):  W1 paste Logs line   W3 dogfood   W4 phone-device run   W9 PR-0 release cut   W10 required-check ruleset (optional)
        |
   [PR-0: A.0a-c + A.2a/b/e/f + min A.2d] --merged--> [A.0d / W9: release-please PR merged, release workflow green, APK attached]
        |                                                     |
        |                                          NO A.4 / A.5 / Epic C behaviour PR merges before W9 completes; W3 dogfood starts on this release
        |                                          (non-behaviour work continues meanwhile: probe-only PR, spikes, Epic D, pure additions)
   [A.1 evidence]
        |
        v
   A.2 diagnostics ------------------------------.
   (A.2a..d)                                      \
                                                   v
   [PROBE-ONLY PR, no behaviour change]: B.1a-d (probe + counters incl. loader-construction) + B.2a-d (seams) + B.4a (output channel)
        |   <-- merges BEFORE any A.4 fix; every gate is proven red against unfixed behaviour (Story B.5 procedure)
        v
   A.3f (#560: merged -> rebase | OPEN -> STACK on its branch | abandoned -> land it first) --> A.3 widened repro (A.3d positive control, A.3e noise floor n>=10)
        |                                                           |
        |                                                           +---> A.4 cheap fixes (A.4a..e, each with before/after, after W9)
        |                          |                  |                   +--> A.5a loupe spike --> A.5b implement --> A.5c degradation
        v                          v                                      |
   B.4 emission+benchdiff     B.3 scenarios a-g + CPU                      |
        |                          |                                       |
        +--------> B.5 budgets file + evaluator + mutation proofs <--------+
                          |
                    B.6 CI wiring (benchmark job, smoke job, #560 test, B.6d privacy gate, B.6e required-check verification)
                          |
                    B.7 calibration soak (>= 10 CI runs, W5) --> gate promotions

   Epic C (all unconditional after the 2026-11-08 decisions):
   C.1a spike (ADR-004 amendment) -> C.1d (schema) -> C.1f (stubs, regen, downgrade) -> C.1b/c/e -> C.1g (goldens; needs D.6b for the card surface)
   C.2a (pure) ; C.2b needs A.4a + #560 base     C.3 -> C.5c+C.5e (one PR, actor refactor + call-site sweep) -> C.5a -> C.5b -> C.5d (ADR-005)     C.6     C.7
   C.8a -> C.8c (crosshair, step selector) -> C.8e (select handles) -> C.8f (non-drag move, Move vertex) ; C.8g (keys) ; C.8b
   C.9d (tokens) -> C.9a, C.9f (gesture; needs A.4a) , C.9g (loading), C.9h/C.9i (a11y tests) ; C.9b, C.9c, C.9e

   Epic D (independent of A-C, separate PRs): D.1 (DONE) -> D.2 (model) -> D.11a (Screen.Library + aliases) ; D.7a-0 (spike, ADR-008) -> D.7a (queries + regen) -> D.7c (indexes) -> D.7b (LibraryViewModel)
        -> D.6a..d (Library screen, needs D.7b) -> D.3 (sidebar) -> D.4c -> D.4a -> D.4b (sheet) -> D.5 (palette) -> D.8a..d (touch points, persisted ids) -> D.9a/c (tip, upgrade notice) -> D.9b (ux:review) -> D.10 -> D.11b (delete old screens, last)
   Repair 4 additions: D.6g (LibraryFilterRules, pure) precedes D.6a and D.7a; D.6a -> D.6e (compact sheet, config change) and D.6f (overflow, delete, Add menu states); D.9c -> D.9d (New badge, What's new) -> D.9e (cold-user sessions, owner step W11, after D.9d and C.9l); A.0b -> A.0e (redaction); A.2e -> A.2g; B.3a -> B.3e; C.8e (hit resolver) and C.8h (delete + snackbar queue, needs C.9i's timeout helper); C.8c -> C.8i (needs C.9d tokens); C.9f -> C.9j; C.9k and C.9l after C.8c/C.8i.
   Repair 5 additions: D.7a-0 precedes D.7a (and D.6g's reference filter uses its resolved names); D.6g (membership, search tokeniser) precedes D.7a; D.6f (Add anchoring) after D.6c; D.9c (reducer) -> D.9d; D.4c carries the nav-shell swap test; C.9m (layout fallback, Back precedence) after C.8c/C.8i/C.9j; C.9j carries the cancel-zone/system-gesture rule; C.9f the quick-tap switch; C.8f the N-vertex actions; C.9e the process-death note. Longest wall-clock chain: Epic A release -> W3 soak (>= 10 sessions over >= 14 days, one 14-day extension).
   B.8 test-class registration follows every story that adds a test class; D.4c precedes D.4b; D.7c precedes D.7b.
   Generated-sources regeneration is serialised: C.1d/C.1f, then D.7c, then D.7a (one owner per regeneration, regenerate on rebase).
   C stories write tests that B.3 scenarios reuse; C.2 and C.3 land before B.7 so budgets calibrate on fixed behaviour.

D.7a is the only Epic D story touching the annotation data path; D.11b (deletions) is the last Epic D merge.
```

Agent waves (critical path; Repair 4 placement: A.0e and A.2g ride with PR-0's wave only if they stay pure additions, otherwise W-A; B.3e with B.3a; D.6g in W-A; D.6e/f, D.9d in W-C; C.8e/h/i, C.9j/k/l with their parent tasks in W-E; D.9e in W-F): W-0: {PR-0 = A.0a-c, A.2a, A.2b, A.2e, A.2f, minimum A.2d} -> **W9 release cut (A.0d; wall-clock, gates every behaviour PR below)**. In parallel with W9, non-behaviour work may run: W-A: {B.1a-d, B.2a-d, B.4a (probe-only PR), A.2c, C.2a, C.1a, D.1a-b, D.2, D.7a/c, B.6d} -> W-B: {A.3f (#560 rebase or stack), B.4b-c, B.5a-b, B.5d, C.9d, C.5c+C.5e, C.3, D.7b, D.11a} -> W-C (needs W9 for the behaviour PRs): {A.3a-e, B.3, A.4a-e (after the probe-only PR is merged), C.5a/b/d, C.1b-g, D.6a-d, D.3} -> W-D: {A.5a, B.5c proofs, D.4, D.5} -> W-E: {A.5b-c, B.6, B.6e, C.2b, C.6-C.9, D.8, D.9a/c} -> W-F: {B.7 soak triggers, D.9b, D.10, D.11b, final verify}. A behaviour-changing C story (C.3b, C.5b) does not start before B.5c so the "before" numbers come from unmodified code. Longest wall-clock chains: (1) PR-0 merged -> W9 -> first behaviour PR; (2) B.6 merged -> >= 10 CI runs (W5) -> gate promotion (B.7); (3) **the longest chain by wall-clock**: the Epic A release -> W3 soak window (>= 10 sessions over >= 14 days, one 14-day extension) -> the owner's Success Metric 6 decision.

---

# Phase 1: Diagnose, measure, harden (Epics A and B, interleaved by wave)

## Epic A: Crash diagnosis and hardening

**Goal**: Capture the evidence needed to identify the kill, make production self-diagnosing without logcat, widen the repro to the only configuration that can trigger the stock rule, and land cheap CPU/memory fixes only with measured justification.

### Story A.1: Owner-evidence capture and triage table
**As the** owner, **I want** the exact exit record of the next kill pasted once, **so that** the plan knows whether the kill was foreground, cached, or not the stock rule.
**Acceptance Criteria**:
- A written triage table maps the owner's `importance=` value to a work direction before any "fixed" claim is made.
  - *Given* an `ExitRecord` with reason `EXCESSIVE_RESOURCE_USAGE` and `ProcessImportance.Cached`, *When* the owner pastes it, *Then* the plan records "background busy loop" and A.3's background phase becomes the primary repro (priority order in task A.1a).
  - *Given* `EXCESSIVE_RESOURCE_USAGE` with `ProcessImportance.Foreground`, *When* pasted, *Then* the plan records "non-stock watchdog" and adds an OEM investigation note (no CI work can reproduce it).
  - *Given* a reason other than `EXCESSIVE_RESOURCE_USAGE` (e.g. `REASON_LOW_MEMORY`, `REASON_ANR`), *When* pasted, *Then* the memory/ANR branch in Epic A.4/A.5 is prioritised and the CPU-kill framing is dropped.
**Files**: `project_plans/annotation-memory-and-nav-ia/implementation/plan.md` (this table), no source changes.

##### Task A.1a: Triage table and ask (XS)
- Add the three-row triage above to a short `project_plans/annotation-memory-and-nav-ia/implementation/owner-evidence.md` (only if the owner replies; otherwise the table stays here). Ask the owner for: the Logs line, phone model/Android version, whether the app was on screen, whether the phone was charging.
- Files: `project_plans/annotation-memory-and-nav-ia/implementation/plan.md`
- Wall-clock blocker W1 (owner: Tyler). Not on the critical path for A.2-A.4.

### Story A.0: Diagnostics-first release (PR-0), ships ahead of the wave
**As the** owner, **I want** the evidence-capturing code released before any behaviour change, **so that** if the kill recurs I can paste an `importance=` line and `DiagnosticsSample` rows instead of waiting for the full wave (pre-mortem failure 1).
**Acceptance Criteria**:
- PR-0 contains exactly: A.2a (exit formatter), A.2b (editor exception handler), A.2e (typed trim level, logging only), A.2f (diagnostics port), the **minimum form** of A.2d (10 s tick `DiagnosticsSample` with cpu delta, top-3 thread groups, foreground flag, heap, cache size, undo depth; the burst trigger and `setProcessStateSummary` may follow), and A.0a-c. It is merged and **released** (release-please PR merged, release workflow green, APK attached; named wall-clock blocker W9, task A.0d) **before any A.4, A.5 or Epic C behaviour change merges**; W3 dogfood starts on this release.
- Forced-kill replay: a debug build leaves a readable record.
  - *Given* a debug build with the editor open and the test-only `exitOnBackground` mutation (or a forced `Process.killProcess`), *When* the app is relaunched in a second instrumentation run, *Then* the Logs screen shows the exit reason name, `importance=<name>`, status, PSS, the description verbatim and the last 3 `DiagnosticsSample` lines, and the post-kill banner (A.0b) appears once.
- PR-0 does not depend on Epic B: it carries no budget, CI gate or probe dependency.
**Files**: see A.2a, A.2b, A.2d, A.2e, A.2f; plus `CM/ui/` banner and Logs composables (A.0b, A.0c). A.0d is the release step (no files).
**Tests first**: `AUT/ui/logs/LogsScreenCopyTest`, `AUT/ui/logs/LogRedactionTest`, `AUT/ui/annotate/PostKillBannerTest`, `AUT/ui/annotate/EditorErrorBannerTest` (androidUnitTest), `AIT/performance/annotation/ForcedKillReplayTest` (two-run, device-only evidence, not a CI gate).

##### Task A.0a: PR-0 boundary, release ordering and forced-kill replay AC (S)
- Define PR-0 as above, add the merge-order rule to the PR template ("no A.4/A.5/C behaviour PR merges before PR-0 is released"), write `ForcedKillReplayTest` (run 1 kills via the mutation, run 2 asserts the replay). Record the release version in the Repair log when cut.
- Files: `AIT/performance/annotation/ForcedKillReplayTest.kt`, `AIT/performance/probe/Mutations.kt` (flag `exitOnBackground`, defined in B.5d; PR-0 may carry a stub of the flag in `androidInstrumentedTest` only)
##### Task A.0b: Post-kill launch banner and Logs Copy action (S) (UX-22, UX-23)
- On launch, if `PreviousSessionLog` has an unseen exit record from an editor session, show one dismissible banner "The app closed unexpectedly while annotating. [View log] [Dismiss]"; remember the record id so it never repeats; a read failure logs only (no banner, no blocking). Add a Logs [Copy] action for the exit line that shows "Copied. Review before posting publicly."; the copied text passes through `LogRedactor.redactForShare` (task A.0e). Persisting the seen-record id failure must not block launch.
- Files: `CM/ui/` banner composable and Logs screen (`CM/ui/screens/` Logs screen file), `androidApp/src/main/kotlin/.../SteleKitApplication.kt` (record id hand-off), `AUT/ui/annotate/PostKillBannerTest.kt`, `AUT/ui/logs/LogsScreenCopyTest.kt`
##### Task A.0c: Editor error banner with Retry and Close editor (S) (UX-21, pre-mortem failure 11)
- When the A.2b handler catches a `Throwable`, show "Something went wrong... [Retry] [Close editor]"; committed measurements remain. After a handled `Error` (for example `OutOfMemoryError`) the editor stops accepting input until Retry or Close, instead of continuing in a possibly broken state.
- Files: `ANN/AnnotationEditorViewModel.kt` (state only), `ANN/AnnotationEditorScreen.kt`, `AUT/ui/annotate/EditorErrorBannerTest.kt`
##### Task A.0d: Cut and verify the PR-0 release (XS) - wall-clock blocker W9, on the critical path
- Merging PR-0 to `main` only makes `release-please` open or update the standing `chore(main): release X.Y.Z` PR; nothing ships until that PR is merged and the release workflow builds the APK (CLAUDE.md "Release Process" steps 1-3). This task names that wait: (1) after PR-0 merges, find the standing release PR (`gh pr list --search "head:release-please"`) and confirm it contains the PR-0 commits; (2) the owner (Tyler) merges it, or authorises `gh workflow run release.yml -f version=vX.Y.Z`; (3) confirm the release run is green and the Android APK is attached to the GitHub Release (`gh release view vX.Y.Z --json assets`); (4) record the version in the Repair log and hand the APK to the owner for W3 dogfood. No A.4, A.5 or Epic C behaviour PR merges before step 3 completes (A.0a rule). Work that does not change behaviour (probe-only PR, spikes, Epic D, pure additions) continues while W9 runs.
- Files: none (release process); PR note

##### Task A.0e: Redaction rule for Logs [Copy] and `DiagnosticsSample` lines (S) (A.0b follow-up, privacy)
- **What a user could paste publicly from Logs [Copy]**: the exit record (reason, importance, status, PSS and the OS-supplied description verbatim, which can name the package and process), up to 3 `DiagnosticsSample` lines (dimensions, thread-group names, heap and cache sizes, screen name), and, if the whole log were copied, `last-crash.txt` stack traces and log lines that can contain absolute file paths, `content://` URIs, graph folder names and page names inside exception messages. [Copy] therefore copies **only the visible exit block and at most 3 samples** (never the whole log) through `LogRedactor.redactForShare`, which (1) replaces absolute paths (`/...`, `C:\...`), `file://` and `content://` URIs with `<path>`, (2) replaces the active graph's folder name and every page or asset name known to the app with `<graph>` / `<name>`, (3) keeps class names, reason names and numbers, and (4) guarantees that nothing is copied that the Logs screen does not already display. Logs display itself is unchanged. The redactor is pure commonMain and idempotent; the Copy confirmation reads "Copied. Review before posting publicly."
- Files: `CM/platform/diagnostics/LogRedactor.kt` (new), `CM/ui/` Logs screen file (Copy action wiring), `CT/platform/diagnostics/LogRedactorTest.kt` (property: no `/`-rooted path, `content://`, graph name or page name survives; idempotent), `AUT/ui/logs/LogRedactionTest.kt`

### Story A.2: Production diagnostics without logcat
**As the** owner, **I want** the next kill to leave enough in the in-app Logs screen, **so that** I can diagnose it from my phone. Tasks A.2a, A.2b, A.2e, A.2f and the minimum form of A.2d ship in PR-0 (Story A.0); A.2c (trim behaviour) and the rest of A.2d follow.
**Acceptance Criteria**:
- The replayed exit line carries reason name, importance name, status, PSS, description and up to 10 entries (cpu.md section 4 gaps).
  - *Given* an `ExitRecord(reason=9, importance=400, status=0, pssKb=210_000, description="excessive cpu 61000 during 300000 dur=300000 limit=25")`, *When* `formatExitRecord` runs, *Then* the output contains `EXCESSIVE_RESOURCE_USAGE`, `importance=CACHED`, `pss=205MB` and the description verbatim.
- An `Error` (e.g. `OutOfMemoryError`) thrown inside any editor `scope.launch` is routed to `fatalError`/log and does not reach the default uncaught handler (CLAUDE.md rule; features.md C4).
  - *Given* an `AnnotationEditorViewModel` whose repository throws `OutOfMemoryError` on save, *When* the user commits a DISTANCE measurement, *Then* the process-level default handler records zero invocations and `state.error` is non-null.
- Trim-memory events are handled in the editor: on `TRIM_MEMORY_RUNNING_CRITICAL` the loupe step-down level increases and the shared Coil cache is trimmed.
  - *Given* a fake `MemoryPressureListeners` fan-out delivering a typed `MemoryPressureLevel.RunningCritical` (task A.2e) and an editor at loupe level 0, *When* the level is delivered, *Then* the loupe level becomes >= 1 and one log line `editor.trim level=...` is emitted.
- A persisted CPU/memory sample is written while the editor is open and at placement bursts.
  - *Given* `DiagnosticsSample` writer with a fake clock, *When* 6 placements occur within 2 s, *Then* exactly one burst sample is appended containing `cpuMsDelta`, top-3 `ThreadCpuShare`, `foreground=true`, `undoDepth=3`.
**Files**: `CM/platform/diagnostics/ExitRecord.kt` (new), `CM/platform/diagnostics/ProcStatParser.kt` (new), `CM/platform/diagnostics/EditorDiagnosticsPort.kt` (new, commonMain `EditorDiagnosticsSource`/`EditorDiagnosticsSink` interfaces), `AM/platform/diagnostics/EditorDiagnosticsSampler.kt` (new), `androidApp/src/main/kotlin/.../SteleKitApplication.kt` (`previousExitNotes`, lines ~166-186 per cpu.md), `ANN/AnnotationEditorViewModel.kt`, `ANN/DepthEstimationCoordinator.kt`, `CM/ui/MemoryPressureListeners.kt`.
**Tests first**: `CT/platform/diagnostics/ExitRecordFormatterTest` (commonTest, example + `kotest-property` over importance ints), `CT/platform/diagnostics/ProcStatParserTest` (commonTest, property: tick sum equals sum of lines, malformed lines ignored), `BT/ui/annotate/AnnotationEditorCrashGuardTest` (businessTest, mirrors `StelekitViewModelCrashReproductionTest`), `BT/ui/annotate/EditorMemoryPressureTest` (businessTest, fake fan-out), `AUT/platform/diagnostics/EditorDiagnosticsSamplerTest` (androidUnitTest, Robolectric-free fake clock).

##### Task A.2a: `ExitRecord` + formatter + `previousExitNotes` extension (S)
- Tests first, then `ExitRecord`, `ProcessImportance` mapping, `formatExitRecord`; change `previousExitNotes` to build `ExitRecord`s and request 10 entries.
- Files: `CM/platform/diagnostics/ExitRecord.kt`, `CT/platform/diagnostics/ExitRecordFormatterTest.kt`, `androidApp/src/main/kotlin/.../SteleKitApplication.kt`
##### Task A.2b: `CoroutineExceptionHandler` on editor scopes (S)
- Add a handler to `AnnotationEditorViewModel.scope` and `DepthEstimationCoordinator` scope that logs and sets `fatalError`/`error` state; rethrow nothing; the UI side (banner, input stops after a handled `Error`) is task A.0c. Extract no new logic into the VM beyond the handler (Isolate via seam).
- Files: `ANN/AnnotationEditorViewModel.kt`, `ANN/DepthEstimationCoordinator.kt`, `BT/ui/annotate/AnnotationEditorCrashGuardTest.kt`
##### Task A.2c: Editor `onTrimMemory` handling (S)
- Depends on A.2e (typed level). Register the editor with `MemoryPressureListeners`; define `LoupeDegradationLevel` (0 full, 1 smaller/quantized, 2 no loupe) as a sealed type consumed in A.5c; clear/trim the shared Coil memory cache; log via `Logger`.
- Files: `CM/ui/MemoryPressureListeners.kt`, `ANN/LoupeDegradation.kt` (new), `BT/ui/annotate/EditorMemoryPressureTest.kt`
##### Task A.2d: `ProcStatParser` + `EditorDiagnosticsSampler` + process-state summary (M)
- Depends on A.2f (port). Pure parser in commonMain; Android sampler runs only while the editor is composed (10 s tick plus burst trigger), writes `DiagnosticsSample` through `Logger`; sets `ActivityManager.setProcessStateSummary` with `annotate:tool=<tool>,loupe=<level>` (API 30). Sampler cost is measured by the probe (report-only metric `sampler.cpuPctOfCore`, target < 1%).
- PR-0 minimum form: the 10 s tick only (cpu delta, top-3 thread groups, foreground flag, heap, Coil memory-cache size, undo depth, screen, image dims); the burst trigger and `setProcessStateSummary` may land later. The Coil cache size closes the requirements Observability gap (screen, image dimensions, cache size, undo depth).
- Files: `CM/platform/diagnostics/ProcStatParser.kt`, `AM/platform/diagnostics/EditorDiagnosticsSampler.kt`, `CT/platform/diagnostics/ProcStatParserTest.kt`, `AUT/platform/diagnostics/EditorDiagnosticsSamplerTest.kt`
- Verify step: `bazel build //kmp:android_app --config=android` (the sampler must compile under the Bazel `android_main` target; check `kt_android_library` deps).
##### Task A.2e: Typed `MemoryPressureLevel` in the fan-out (S)
- Add a commonMain `enum class MemoryPressureLevel` (`Moderate`, `RunningLow`, `RunningCritical`, `Complete`) and `register((MemoryPressureLevel) -> Unit)` + `notifyListeners(level)` to `CM/ui/MemoryPressureListeners.kt` (current API: `register(() -> Unit)`, `notifyListeners()`, lines 17-37), keeping the no-arg overloads (the level defaults to `Moderate`). Disposition: Extend as-is. **Split in Repair 4** (the task previously listed the fan-out API, the ViewModel, the Android host hook, the asset-pipeline listener, the test and its registration, six files): this task is the API and its test; the host mapping and caller sweep are A.2g.
- Files: `CM/ui/MemoryPressureListeners.kt`, `BT/ui/MemoryPressureListenersTest.kt` (extend), `AllBusinessTests.kt` (registration per B.8)
##### Task A.2g: Trim-level host mapping and existing-caller sweep (S, after A.2e)
- Map the Android `onTrimMemory` int to `MemoryPressureLevel` in the host and keep green every existing caller: `StelekitViewModel.onMemoryPressure` and the asset-pipeline listener (find all with `rg 'MemoryPressureListeners\.(register|notifyListeners)' kmp androidApp`). No behaviour change for the no-arg callers.
- Files: `androidApp/src/main/kotlin/.../MainActivity.kt` (the `onTrimMemory` host hook), `CM/ui/StelekitViewModel.kt` (`onMemoryPressure`), the asset-pipeline listener file the `rg` finds
##### Task A.2f: Diagnostics port from the ViewModel to the sampler (XS)
- The sampler needs `undoDepth`, tool, screen and image dimensions from the commonMain VM, and androidMain must not reach into it. Define `EditorDiagnosticsSource` (read-only snapshot: tool, screen, `NativeImageSize`, `undoDepth`, loupe level) and `EditorDiagnosticsSink` as commonMain interfaces; the VM exposes a read-only `EditorDiagnosticsSource` view and `ScreenRouter`/`AnnotationEditorScreen` receive the Android sampler as a parameter (same injection style as the haptic callback). No new logic in the VM beyond exposing the view.
- Files: `CM/platform/diagnostics/EditorDiagnosticsPort.kt`, `ANN/AnnotationEditorViewModel.kt` (one read-only property), `CM/ui/ScreenRouter.kt`

### Story A.3: Widened repro promoted to tracked tests
**As a** maintainer, **I want** the throwaway repro (cpu.md appendix, pitfalls.md appendix) in the tree and extended to the untested configurations, **so that** the only setting that can trigger the stock kill is exercised.
**Acceptance Criteria**:
- A background phase follows each burst and measures idle CPU for 60 s (ADR-002).
  - *Given* a `RapidPlacement` burst of 100 DIRECT-injected presses on a 4000x3000 JPEG, *When* the activity is sent to the background and CPU is sampled for 60 s, *Then* the median of three consecutive 20 s windows is < 10% of one core (denominator: one core's wall time per window, from `/proc/self/stat` utime+stime) and `getHistoricalProcessExitReasons` has no new entry. The metric starts `ReportOnly` (see B.5b) and the record also stores the process importance read during the window.
  - *Given* the positive control (task A.3d: a deliberate test-only background busy loop), *When* the same background phase runs, *Then* the probe reports CPU above the gate and records the observed importance (`ActivityManager.getRunningAppProcesses()` importance and `/proc/<pid>/oom_score_adj`); the harness is accepted as able to observe the stock-rule state only if importance reaches cached/HOME, otherwise A.3's background phase is labelled "idle CPU after burst" and the plan makes no OS-rule claim.
- Real SQLDelight persistence, depth estimator enabled and haptics enabled are exercised (cpu.md "Not tested").
  - *Given* a `RealPersistenceDepth` run with the SQLDelight driver and an enabled `DepthEstimationCoordinator`, *When* 100 presses complete, *Then* the probe records `cpuMsPerPlacement` and `threadShare[DefaultDispatcher]` and the run produces no exit reason.
- Sources beyond a plain 12 MP JPEG are covered: 48 MP, HEIC/WebP, EXIF orientation 6, a `content://`-style SAF URI.
  - *Given* an 8000x6000 JPEG with EXIF orientation 6, *When* the editor opens and 20 presses complete, *Then* `maxDecodeBytes` <= `MAX_DECODE_PIXELS * 4` and no exception is recorded.
- Drag gestures are included (DISTANCE drag-to-aim, SELECT vertex drags, 20 s sustained drag), injected via `DirectTouchInjector`.
  - *Given* a 20 s sustained circular drag, *When* sampled, *Then* sustained CPU % (process CPU over the 20 s drag window as a percent of one core's wall time) and p95 frame time are emitted. The 60% threshold is **report-only** until calibrated in B.7 (baseline 36% is emulator-specific, from the inflated n=1 harness); this resolves the earlier contradiction with B.5b.
- The harness is calibrated before any threshold derived from it is trusted. The cpu.md figures come from `performTouchInput` (inflates CPU 5-7x) with n=1, so the +-10% noise band, the 142/99/86 ms per press figures and the "hoisting verified" claim are **provisional** until task A.3e measures the noise floor with direct `dispatchTouchEvent` injection and n>=10 on the CI emulator.
**Files**: `AIT/performance/annotation/AnnotationPlacementResourceTest.kt`, `AIT/performance/annotation/AnnotationLifecycleResourceTest.kt`, `AIT/performance/annotation/BackgroundPositiveControlTest.kt`, `AIT/performance/probe/DirectTouchInjector.kt`, `AIT/performance/probe/TestImages.kt` (new), `kmp/build.gradle.kts` (androidInstrumentedTest dependency `androidx.test.uiautomator:uiautomator:2.3.0`).
**Tests first**: the tests *are* the deliverable; their expected-value assertions are written against the budgets file in B.5, with `ReportOnly` until calibrated.

##### Task A.3a: `DirectTouchInjector` + `TestImages` + background helper (S)
- Port the injector from the cpu.md appendix (8 ms cadence, 50 ms hold, `SOURCE_TOUCHSCREEN`); add `Backgrounder.sendToBackground()` using UiAutomator `pressHome()`; generate JPEG/PNG/HEIC(if encoder available)/EXIF-rotated fixtures into `cacheDir`.
- Files: `AIT/performance/probe/DirectTouchInjector.kt`, `AIT/performance/probe/TestImages.kt`, `AIT/performance/probe/Backgrounder.kt`, `kmp/build.gradle.kts`
##### Task A.3b: Placement scenarios (b), (c), (d), (h), (j) (M)
- `RapidPlacement` (100 presses, drag-to-aim), `LoupeCycles` (press/release without placing), `SelectMoveDrag` (10 pre-placed, vertex drags that start on a vertex — the cpu.md sustained-SELECT run was invalid), `SustainedDrag` (20 s), `CpuVsAnnotationCount` (blocks of 25 presses, slope).
- Files: `AIT/performance/annotation/AnnotationPlacementResourceTest.kt`
##### Task A.3c: Lifecycle scenarios (a), (e), BackgroundIdle, RealPersistenceDepth, LargeSource (M)
- Open on large photo; capture -> annotate -> export using a stub camera file; background idle 60 s; SQLDelight driver + depth estimator + non-no-op haptic; 48 MP / EXIF / SAF-style sources.
- Files: `AIT/performance/annotation/AnnotationLifecycleResourceTest.kt`
##### Task A.3d: Positive control for the background phase (S)
- A test-only (androidInstrumentedTest source set, never shipped) deliberately busy background loop started from the editor scope. Run the A.3 background phase with the control on: the probe must report CPU above the gate, must record the process importance during the window, and the exit-reason check must run. State the outcome in the PR: (a) importance reaches cached/HOME and the control trips the stock rule or at least the probe, so the harness can see the state; or (b) it does not, so A.3 is renamed "idle CPU after burst" and all "stock rule" wording is removed from A.3/ADR-002. Without the owner's `importance=` line this task caps the claim at "mitigated and monitored". Fallback if outcome (b) (no CI repro possible): one manual device soak on the owner's phone (W4) is required before any "fixed" wording.
- Files: `AIT/performance/annotation/BackgroundPositiveControlTest.kt`, `project_plans/annotation-memory-and-nav-ia/decisions/ADR-002-cpu-measurement-method.md` (amendment recording the outcome)
##### Task A.3e: Noise-floor measurement before any A.4 threshold (S)
- First deliverable of A.3 after the injector: n>=10 repeats of `RapidPlacement` with direct `dispatchTouchEvent` injection on the pinned CI emulator; record median, spread and the resulting band. The Keep rule's band and B.5/B.7 thresholds use this number; until it exists the +-10% band and every cpu.md-derived figure is labelled provisional.
- Files: `AIT/performance/annotation/AnnotationPlacementResourceTest.kt` (scenario output), `project_plans/annotation-memory-and-nav-ia/decisions/ADR-002-cpu-measurement-method.md` (measured band)
##### Task A.3f: Prerequisite: PR #560 merged, or stacked on, then re-baseline (S)
- State checked 2026-11-08: PR #560 ("fix(annotate): loupe centre now shows the point under the finger") is **OPEN, unmerged** (`gh pr view 560 --json state` returned `OPEN`). #560 changes the same loupe code A.4a extracts (`AnnotationEditorScreen.kt:1610` `LoupeOverlay`, `~1607-1684`). Do this before A.4a, C.2b, C.9a, C.9f, A.5a, with one owning agent on those files.
- **Owner-independent fallback (decided; no wait on the owner)**: (1) if #560 is merged, rebase onto `main`; (2) if it is still open, **stack** the loupe work on #560's head branch (`git-stacked-prs` skill: base the A.4a/C.2b/C.9a/C.9f PRs on `refs/pull/560/head`'s branch, never copy its changes) so the work proceeds and GitHub retargets each PR to `main` when #560 merges; (3) if #560 is closed unmerged or abandoned, **land its fix first as a prerequisite PR** of this project (cherry-pick onto a fresh branch, keep its `LoupeAlignmentE2ETest`, run its tests), then continue from `main`. Baselines (`RapidPlacement`, `SustainedDrag`) are re-taken on whichever base is chosen and the base is recorded in every baseline record. Only B.6c (wiring `LoupeAlignmentE2ETest` into CI) waits for the real merge (W6).
- Files: no new files; PR note

### Story A.4: Cheap CPU/memory fixes, each proven by a before/after probe delta
**As the** owner, **I want** the suspected CPU hot spots reduced (only the loupe on/off delta is measured; per-component costs are INFERRED in cpu.md), **so that** the editor burns less CPU per gesture regardless of what killed the process.
**Keep rule (fixed in advance, applies to every task below)**: a fix is described in its PR as a *CPU fix* only if the probe median over n>=10 runs improves beyond the noise band measured in task A.3e (the cpu.md +-10% is provisional: n=1, inflated harness) on its target metric; otherwise it is labelled *hardening* and is kept only when it removes a structural risk (e.g. unbounded loader construction). No fix PR says it "fixes the crash".
**Acceptance Criteria**:
- `loupeOffset` is no longer read at screen scope.
  - *Given* the editor with DISTANCE tool and a finger moving 20 times, *When* recomposition counts are read through the `@VisibleForTesting` composition counter, *Then* the screen-body counter increases by <= 1 across the 20 moves (baseline: once per move) and the loupe counter increases per move.
- `MeasurementLabelOverlay` and `CommittedAnnotationsCanvas` no longer allocate or measure per draw.
  - *Given* 50 committed annotations, *When* a frame is drawn twice with unchanged state, *Then* `textMeasurer.measure` is invoked 0 times on the second draw and the `CpuVsAnnotationCount` slope (ms per press at n=50 over n=10) is reported; the target is a slope <= 1.2x (baseline +43% from n=0 to n=51, cpu.md section 2 row 3).
- One `ImageLoader` is built per editor session.
  - *Given* 100 loupe presses, *When* `buildSteleKitImageLoader` invocations are counted by the test seam, *Then* the count is 1 (baseline: 100).
- `CapturePreviewDialog` uses the shared cache builder.
  - *Given* the capture preview opens, *When* its loader is inspected, *Then* its memory cache is the instance returned by `sharedImageMemoryCache(context)`.
**Files**: `ANN/AnnotationEditorScreen.kt`, `ANN/LoupeOverlay.kt` (new, extracted), `ANN/MeasurementLabelOverlay.kt`, `CM/ui/components/SteleKitAssetFetcher.kt`, `CM/ui/capture/CapturePreviewDialog.kt`.
**Tests first**: `AUT/ui/annotate/EditorRecompositionTest` (androidUnitTest, Robolectric — Compose-behavior test per CLAUDE.md placement guidance), `AUT/ui/annotate/MeasurementLabelCacheTest` (androidUnitTest, decided now; uses a `TextMeasurer` counting seam in `MeasurementLabelOverlay.kt`, listed in task A.4b Files), `BT/ui/components/ImageLoaderConstructionCountTest` (businessTest, pure seam), probe scenarios from A.3 for before/after numbers.

##### Task A.4a: Extract `LoupeOverlay` + defer `loupeOffset` read (M)
- Move `LoupeOverlay` (Screen:~1607-1684) to `ANN/LoupeOverlay.kt` with an injectable loader/request provider (seam). Pass `loupeOffset` as `State<Offset?>`/lambda read inside layout/draw (`Modifier.offset { }`, `graphicsLayer`). Add the `@VisibleForTesting` composition counter. Record before/after via `RapidPlacement` and `SustainedDrag`.
- Files: `ANN/LoupeOverlay.kt`, `ANN/AnnotationEditorScreen.kt`, `AUT/ui/annotate/EditorRecompositionTest.kt`
##### Task A.4b: Cache measured labels, remove per-draw allocation (S)
- `remember` text layouts keyed by `(text, style)`; hoist `TextStyle`; replace `points.map` in `CommittedAnnotationsCanvas` with index loops; prune `pathMap` (features.md m1). Spatial pruning of O(n^2) label collision is done only if the probe shows the slope persists. Add the `internal` measure-count seam (`TextMeasurer` wrapper) and the `@VisibleForTesting` composition counter (an `internal` plain counter in commonMain, no Android annotation) that `MeasurementLabelCacheTest`/`EditorRecompositionTest` read.
- Files: `ANN/MeasurementLabelOverlay.kt`, `ANN/AnnotationEditorScreen.kt`, `AUT/ui/annotate/MeasurementLabelCacheTest.kt`
##### Task A.4c: Hoist `ImageLoader` + construction-count seam + route `CapturePreviewDialog` (S)
- Screen-scope `rememberSteleKitImageLoader()` passed down; `CapturePreviewDialog.kt:40` uses the shared builder. The `internal` construction counter in `buildSteleKitImageLoader` is **not** introduced here: it lands in the probe-only PR (task B.2d) so reverting this fix cannot remove it. Labelled *hardening* unless numbers move (measured: no effect, cpu.md section 2 row 4).
- Files: `CM/ui/components/SteleKitAssetFetcher.kt`, `CM/ui/capture/CapturePreviewDialog.kt`, `BT/ui/components/ImageLoaderConstructionCountTest.kt` (businessTest, not jvmTest: Coil 3 and the loader builder are commonMain, so this needs no display)
##### Task A.4d: Persistence cost check with real SQLDelight (S)
- Using `RealPersistenceDepth`, measure `saveMeasurementAnnotation` per commit (cpu.md suspect 7, previously unmeasured). If DefaultDispatcher CPU share > 15% or a commit exceeds 50 ms, hand findings to task C.5a (queue); otherwise record "no hotspot".
- Files: `AIT/performance/annotation/AnnotationLifecycleResourceTest.kt` (assertion on scenario output only)
##### Task A.4e: Depth-estimation and haptics cost check (S)
- With `DepthEstimationCoordinator` running, measure sustained CPU and thread share; if a depth pass runs after backgrounding, add cancellation on `onStop` (the one path that could burn CPU while cached). No change if the probe shows idle.
- Files: `ANN/DepthEstimationCoordinator.kt`, `AIT/performance/annotation/AnnotationLifecycleResourceTest.kt`

### Story A.5: Loupe strategy via measured spike (ADR-003)
**As the** owner, **I want** a loupe that costs less CPU and bounded memory per press, **so that** the largest measured hot spot after recomposition shrinks.
**Acceptance Criteria**:
- A spike table compares options 1-3 (and 4 if needed) on one probe run set.
  - *Given* the same 4000x3000 image and 100 presses, *When* each strategy runs 5 times, *Then* the ADR-003 table lists median CPU ms/press, main-thread ms/press, p95 frame ms, max decode bytes and graphics PSS per strategy.
- The chosen strategy bounds a single loupe allocation to <= 2 MB (or documents why not).
  - *Given* the chosen strategy and a 48 MP source, *When* 100 presses complete, *Then* `CoilDecodeStats.maxBytes` for loupe requests <= 2_097_152 (`ReportOnly` until the A.5a spike table has chosen the strategy, then `Gate`; the number is not fixed before the spike) and decode count per press <= 1 for strategy 1 or 0 after the first for strategy 2/3.
- The loupe survives narrow canvases (shared with C.2) and degrades in steps on memory pressure.
  - *Given* a trim level `RUNNING_CRITICAL`, *When* a press occurs, *Then* the loupe uses level >= 1 (smaller window) and an inline chip "Low memory: magnifier simplified" appears once (`stateDescription` set).
**Files**: `ANN/LoupeOverlay.kt`, `CM/platform/image/ImageSampling.kt`, `CM/platform/image/LoupeSource.kt` (commonMain `fun interface LoupeSource`, only if option 3 wins; Android implementation in `AM/platform/image/AndroidLoupeSource.kt`, JVM implementation optional; iOS and wasmJs get no implementation and the default cached-bitmap strategy is used, so no `expect` is declared and no iOS/wasmJs actual files are needed), `ANN/LoupeDegradation.kt`.
**Tests first**: `CT/platform/image/ImageSamplingTest` extensions (quantization property: for any canvas size, requests collapse to <= 4 distinct sizes across a 10% resize sweep), `AIT/performance/annotation/LoupeStrategySpikeTest` (spike harness, not a gate), `AUT/ui/annotate/LoupeDegradationTest`.

##### Task A.5a: Three-way loupe spike (M)
- Implement strategies behind a test-only `LoupeStrategy` parameter; run via the probe; fill the ADR-003 table; apply the pre-declared selection rule.
- Files: `ANN/LoupeOverlay.kt`, `AIT/performance/annotation/LoupeStrategySpikeTest.kt`, `project_plans/annotation-memory-and-nav-ia/decisions/ADR-003-loupe-decode-strategy.md`
##### Task A.5b: Implement the chosen strategy (M)
- Production implementation of the winner; remove the others; if region decode wins, add the commonMain `LoupeSource` interface injected from the platform shell (default = cached-bitmap strategy, which is the only strategy on iOS/wasmJs; if the team instead keeps `expect/actual`, all four actuals android/jvm/iOS/wasmJs must be listed and the iOS/wasmJs ones are explicit "unsupported -> cached bitmap" actuals; `BitmapRegionDecoder` and ImageIO differ in EXIF handling so a parity test is required). Loupe request sizes quantized to 256 px steps. Reclassified: under-classed as M if the Android implementation plus parity test is needed (named in the overrun sources).
- **Split trigger**: if A.5a picks region decode (strategy 3), or A.5b would touch more than 5 files, split into A.5b-1 (the strategy behind the commonMain `LoupeSource` interface with the default cached-bitmap implementation, `ImageSampling` quantisation, remove the losing strategies) and A.5b-2 (`AndroidLoupeSource` plus the EXIF-orientation parity test against the ImageIO/JVM reference); A.5b-1 merges and ships first with the default strategy, so A.5b-2 can be reverted alone.
- Files: `ANN/LoupeOverlay.kt`, `CM/platform/image/ImageSampling.kt`, `CM/platform/image/LoupeSource.kt` (conditional), `AM/platform/image/AndroidLoupeSource.kt` (conditional)
##### Task A.5c: Low-memory degradation levels + chip (S)
- Wire `LoupeDegradationLevel` from A.2c: level 1 smaller window/zoom, level 2 no loupe with persistent chip; copy per ux.md section 6; log level changes through `Logger`.
- Files: `ANN/LoupeDegradation.kt`, `ANN/AnnotationEditorScreen.kt`, `AUT/ui/annotate/LoupeDegradationTest.kt`

## Epic B: Unified CPU + memory + jank benchmark harness

**Goal**: One probe, one budgets file, one evaluator, CI-hosted, with every gate proven able to fail (ADR-001, ADR-002, ADR-009).

### Story B.1: `ResourceProbe` core
**As a** maintainer, **I want** one helper that records CPU, per-thread share, frames, heap, native, PSS, VmHWM and exit reasons around any scenario, **so that** CPU and memory come from the same run.
**Acceptance Criteria**:
- `snapshot()` returns every metric listed in ADR-001 and `settle()` runs 3 GC cycles with a 100 ms sleep.
  - *Given* a freshly started instrumented process, *When* `ResourceProbe.snapshot()` is called twice 3 s apart while idle, *Then* `cpuMs` delta < 300 (idle < 10% of a core) and all fields are non-negative.
- Frame metrics use `Window.addOnFrameMetricsAvailableListener` and report p50/p95/p99 and janky-frame percent.
  - *Given* 120 frames of an idle-with-animation screen, *When* sampled, *Then* the record contains `frames=120`, `p95Ms`, and `jankyPct` between 0 and 100.
- Every record carries `DeviceProfile` (ADR-009).
  - *Given* an emulator with `memoryClass=192`, *When* a `ProbeRecord` is emitted, *Then* `device.memoryClass=192` and `device.largeHeap=false` appear in the JSON.
**Files**: `AIT/performance/probe/ResourceProbe.kt`, `AIT/performance/probe/ProbeSnapshot.kt`, `AIT/performance/probe/FrameSampler.kt`, `AIT/performance/probe/PeakSampler.kt`, `AIT/performance/probe/DeviceProfile.kt`.
**Tests first**: `AIT/performance/probe/ResourceProbeSelfTest` (instrumented; includes a mutation case that burns CPU for 2 s and asserts the probe sees > 150 ms CPU per second, proving the probe can see load).

##### Task B.1a: Snapshot types, `settle`, exit-reason check, device profile (S)
- Verify `androidInstrumentedTest` can use the commonMain `ProcStatParser` (Unresolved Question). Use `ProcStatParser` for thread groups.
- Files: `AIT/performance/probe/ProbeSnapshot.kt`, `AIT/performance/probe/ResourceProbe.kt`, `AIT/performance/probe/DeviceProfile.kt`
##### Task B.1b: `FrameSampler` + `PeakSampler` (S)
- Port `PeakHeapSampler` (20 ms) and add native heap; add FrameMetrics sampler (not Choreographer; Choreographer is the throwaway's method).
- Files: `AIT/performance/probe/FrameSampler.kt`, `AIT/performance/probe/PeakSampler.kt`
##### Task B.1c: `ResourceProbeSelfTest` incl. mutation burn (XS)
- Files: `AIT/performance/probe/ResourceProbeSelfTest.kt`
##### Task B.1d: Probe-only PR, merges before any behaviour change (S)
- **First commit of the project (ahead of A.4)**: the complete instrumentation with zero behaviour change in production code: `ResourceProbe` (B.1a-c), `DirectTouchInjector`, the Coil listener seam, `resetSharedImageMemoryCacheForTest`, `undoDepth`, **the `buildSteleKitImageLoader` construction counter (B.2d)**, and the output channel (B.4a). Every new counter is `internal`, default-off or read-only. Acceptance: on the unmodified `408907d9cd` behaviour plus this PR, the scenarios run and produce baseline records (`loaderConstructions = 100` per 100 presses is the expected pre-fix value). This PR is what makes "run it against the unfixed behaviour" possible, because `408907d9cd` itself has no harness.
- **Production-diff audit step (pre-mortem failure 5)**: the PR may change only the files listed in B.1a-c, B.2b, B.2d, B.2e and B.4a; the diff against `408907d9cd` is read line by line for "no behaviour change" and the audit result is pasted in the PR. Counters use `AtomicInteger`; the factory parameter defaults to null; no mutation symbol is added to `main` (see B.5d).
- Files: no new files beyond B.1a-c, B.2b, B.2d, B.2e, B.4a (this task is the sequencing and PR boundary)

### Story B.2: Test seams in production code
**As a** maintainer, **I want** minimal seams for Coil stats, cache reset and undo depth, **so that** deterministic metrics are observable.
**Acceptance Criteria**:
- Coil API shapes confirmed against 3.2.0 before use.
  - *Given* the 3.2.0 `coil-core` jar, *When* `javap` lists `EventListener` and `MemoryCache`, *Then* the spike note records the exact members used (`onSuccess`, `Image.size`, `MemoryCache.size/maxSize`) or the replacement.
- `buildSteleKitImageLoader(eventListenerFactory = null)` is behaviourally identical to today when the factory is null.
  - *Given* a loader built with the default parameter, *When* an image loads, *Then* no listener is attached and cache sizing (`maxSizePercent 0.10`) is unchanged (`SteleKitImageLoaderMemoryTest` still passes).
- `resetSharedImageMemoryCacheForTest()` empties and recreates the cache.
  - *Given* a cache holding 3 entries, *When* the reset runs, *Then* `size == 0`.
- `AnnotationEditorViewModel.undoDepth` is readable by tests.
  - *Given* 4 commits, *When* `undoDepth` is read, *Then* it equals 4 (capped at 50).
**Files**: `CM/ui/components/SteleKitAssetFetcher.kt`, `ANN/AnnotationEditorViewModel.kt` (one `internal val`), `AIT/performance/probe/CoilDecodeStats.kt`.
**Tests first**: `JT/ui/components/SteleKitImageLoaderMemoryTest` (existing, extended), `BT/ui/components/CoilSeamTest` (businessTest: Coil 3 and the builder are commonMain, no display needed), `BT/ui/components/ImageLoaderConstructionCountTest`, `BT/ui/annotate/UndoDepthTest` (businessTest).

##### Task B.2a: Coil 3.2.0 API spike (XS)
- `javap` per the `java-api-discovery` skill; record results in the stack note of ADR-001.
- Files: `project_plans/annotation-memory-and-nav-ia/decisions/ADR-001-unified-resource-probe-and-budgets-file.md`
##### Task B.2b: Listener factory seam, cache reset, `CoilDecodeStats` (S)
- `CoilSeamTest` asserts the listener fired at least once (a `<=` decode gate must not pass vacuously on zero decodes) and fails with a message naming the Coil version; the Coil version is recorded in `ProbeRecord.device`. A Coil bump (Renovate) reruns the probe (ADR-001).
- Files: `CM/ui/components/SteleKitAssetFetcher.kt`, `AIT/performance/probe/CoilDecodeStats.kt`, `BT/ui/components/CoilSeamTest.kt`
##### Task B.2c: `undoDepth` seam (XS)
- Files: `ANN/AnnotationEditorViewModel.kt`, `BT/ui/annotate/UndoDepthTest.kt`
##### Task B.2d: Loader-construction counter in the probe-only PR (XS)
- Moved from A.4c. `internal` counter incremented in `buildSteleKitImageLoader`, read by the probe as `loaderConstructions`. It exists before any fix, so reverting A.4c cannot remove it.
- Files: `CM/ui/components/SteleKitAssetFetcher.kt`, `BT/ui/components/ImageLoaderConstructionCountTest.kt`
##### Task B.2e: Allocation-path audit (S)
- Covers the requirements In Scope audit items that had no task: `PlatformImageDownscaler` (peak per capture), Coil disk-cache sizing in `buildSteleKitImageLoader`, and depth-estimation input bitmaps (`DepthEstimationCoordinator`). Output is a table appended to `research/architecture.md` (path, owner class, peak bytes or bound, bounded yes/no); any unbounded path becomes a finding handed to A.4 or C.7b. No production change in this task.
- Files: `project_plans/annotation-memory-and-nav-ia/research/architecture.md`

### Story B.3: Scenarios (a)-(g) plus CPU scenarios
**As a** maintainer, **I want** each Success Metric 2 scenario recorded with CPU and memory together, **so that** a regression shows up in whichever resource it hits.
**Acceptance Criteria**: (A.3 owns scenarios b, c, d, h, j, a, e, BackgroundIdle, RealPersistenceDepth, LargeSource; B.3 adds f and g.)
- Scenario (f): scrolling Library (the merged Assets and Gallery destination, ADR-006) with a large image set records memory and CPU, unfiltered and with the Annotated filter.
  - *Given* 5,000 `ImageAnnotation` rows and 5,000 asset rows with 20 real 4000x3000 JPEGs repeated, *When* Library is scrolled top to bottom twice (once unfiltered, once with the Annotated filter), *Then* records include `javaRetainedMb`, `nativeDeltaMb`, `cpuMsPerScreenful`, `maxDecodeBytes` <= 4_194_304 (cell-sized decodes), and `cacheSize <= cacheMaxSize`.
- Scenario (g): switching Library (alternating the Annotated filter) -> editor -> Library 30 times does not leak (Success Metric 2g, originally Assets/Gallery/editor).
  - *Given* 30 cycles, *When* the editor ViewModel is held only by a `WeakReference` and a bounded loop (GC + 100 ms sleep, up to 5 s; three `System.gc()` calls are not enough on ART) runs, *Then* the reference is cleared (`ReportOnly` until proven by a mutation that holds a strong reference, then `Gate`) and retained Java heap slope per cycle is reported.
- Library-scroll scenarios do not depend on the nav work (they find the destination through `NavDestinationSpec` constants after D.2, or direct `navigate(Screen.Library(...))` calls after D.11a; before D.11a they scroll today's `Screen.Gallery` and `Screen.AssetBrowser` and the record names carry `legacy=true`).
**Files**: `AIT/performance/annotation/LibraryScrollSwitchResourceTest.kt`, `CT/fixtures/FixtureGraphSeeds.kt` (shared seeding logic lives in **commonTest**, consumed by androidInstrumentedTest and by the row-count test; `AIT` classes are never imported from jvmTest, they are not visible there), `AIT/performance/probe/FixtureGraph.kt` (thin Android wrapper over `FixtureGraphSeeds`).
**Tests first**: scenario test classes are the tests; `BT/db/FixtureGraphCountsTest` (businessTest) covers `FixtureGraphSeeds` row counts.

##### Task B.3a: Shared `FixtureGraphSeeds` and its row-count test (S)
- Pure seeding logic in commonTest plus the businessTest that pins the row counts (tests first). **Split in Repair 4** (the Android wrapper moved to B.3e).
- Files: `CT/fixtures/FixtureGraphSeeds.kt`, `BT/db/FixtureGraphCountsTest.kt`, `kmp/src/commonTest/kotlin/BUILD.bazel` (add `FixtureGraphSeeds.kt` to `business_common_tests` srcs, see Story B.8), `AllBusinessTests.kt` (registration)
##### Task B.3e: Android `FixtureGraph` wrapper over the shared seeds (XS, after B.3a)
- Thin androidInstrumentedTest wrapper that seeds a database from `FixtureGraphSeeds` and a self-check that it produces the counts B.3a pins. `AIT` classes are never imported from jvmTest.
- Files: `AIT/performance/probe/FixtureGraph.kt`, `AIT/performance/probe/FixtureGraphSmokeTest.kt`
##### Task B.3b: Scenario (f) library scroll (M)
- Files: `AIT/performance/annotation/LibraryScrollSwitchResourceTest.kt`
##### Task B.3c: Scenario (g) switch churn with WeakReference leak gate (S)
- No new dependency; LeakCanary stays optional (Unresolved Question).
- Files: `AIT/performance/annotation/LibraryScrollSwitchResourceTest.kt`
##### Task B.3d: Bitmap/owned-allocation checks for export (e) (S)
- Assert exported bitmap `allocationByteCount` and peak during bake for a 4096x3072 source; report `bakeAndEncode` peak (architecture.md A7 ~110-130 MB estimate is INFERRED; this measures it).
- Files: `AIT/performance/annotation/AnnotationLifecycleResourceTest.kt`

### Story B.4: Emission and benchdiff reporting
**As the** owner, **I want** the results in the PR comment, **so that** regressions are visible without running anything.
**Acceptance Criteria**:
- Records are emitted without needing the owner to run logcat.
  - *Given* a local `connectedDebugAndroidTest` run, *When* it finishes, *Then* the chosen channel (spike B.4a) yields a `ProbeRecord` file on the host and CI additionally logs `ANDROID_RESOURCE` JSON lines.
- `scripts/benchdiff-export.js --kind android-resource` maps fields to groups.
  - *Given* a summary JSON with `RapidPlacement.cpuMsPerPlacement=142`, *When* the exporter runs, *Then* the canonical benchdiff JSON contains group `android/AnnotateResources`, name `RapidPlacement.cpuMsPerPlacement`, unit `ms`, better `lower`.
- The summarizer is a standalone script.
  - *Given* `scripts/android-benchmark-summarize.py` and a fixture of logcat lines, *When* run, *Then* it writes `benchmarks/android-history/<ts>_<sha>.json` identical in shape to the inline step's previous output for the existing `AndroidGraphBenchmark` fields.
**Files**: `scripts/benchdiff-export.js`, `scripts/benchdiff-export.test.js`, `scripts/android-benchmark-summarize.py` (new), `scripts/android-benchmark-summarize_test.py` (new), `.github/workflows/android-benchmark.yml`, `AIT/performance/probe/ProbeReporter.kt`.
**Tests first**: `scripts/benchdiff-export.test.js` cases, `scripts/android-benchmark-summarize_test.py`.

##### Task B.4a: Output-channel spike + `ProbeReporter` (S)
- Files: `AIT/performance/probe/ProbeReporter.kt`
##### Task B.4b: Extract summarizer to `scripts/` with tests (S)
- Files: `scripts/android-benchmark-summarize.py`, `scripts/android-benchmark-summarize_test.py`, `.github/workflows/android-benchmark.yml`
##### Task B.4c: `benchdiff-export.js` android-resource kind + tests (S)
- Files: `scripts/benchdiff-export.js`, `scripts/benchdiff-export.test.js`

### Story B.5: Budgets file, evaluator, mutation and pre-fix proofs
**As the** owner, **I want** gates that provably fail on regressions, **so that** green means something.
**Acceptance Criteria**:
- A single file defines every budget and mode.
  - *Given* `benchmarks/budgets/annotation-resource-budgets.json`, *When* validated by `scripts/bench-budget-check.js --validate`, *Then* every `ProbeScenarioId.metric` emitted by the probe has an entry (missing entry = error) and every `mode` is `gate` or `report-only`.
- The evaluator fails only on breached `gate` entries.
  - *Given* a records file with `RapidPlacement.maxDecodeBytes=12_582_912` and a `gate` budget of 2_097_152, *When* the checker runs, *Then* exit code is 1 and the message names the metric, value and budget; *Given* the same breach on a `report-only` entry, *Then* exit code is 0 and a warning row is printed.
- Mode flip is one edit.
  - *Given* the budget `RapidPlacement.cpuMsPerPlacement` with `mode=report-only`, *When* only that field is changed to `gate` and the next run exceeds the budget, *Then* the checker exits 1 with no other file changed.
- Each `gate` has a pre-fix failing proof and a mutation proof, recorded in the PR, following the **pre-fix proof procedure** below.
  - *Given* the probe-only PR merged (B.1d) and the A.4c fix commit reverted *alone* (`git revert <A.4c commit>`; the counter survives because it lives in B.2d), *When* the harness runs, *Then* `loaderConstructions` = 100 and the gate fails (red result pasted in the A.4c PR).
  - *Given* a test-only mutation flag, *When* the flag adds a 10 ms busy-wait in the pointer-move handler, *Then* `cpuMsPerPlacement` exceeds the calibrated budget once promoted.
- A per-gate **proof matrix** exists in the PR and in ADR-001 (below); a gate with no achievable red proof stays `ReportOnly` and is listed under "cannot be proven".

**Pre-fix proof procedure (exact)**:
1. `408907d9cd` has no harness, so "run on 408907d9cd" is replaced by: apply only the probe-only PR (B.1d) on top of the unfixed behaviour (the behaviour-changing commits are not yet merged) and run the scenario. That is the pre-fix red run.
2. For a gate whose fix is already merged, create a throwaway branch from `main`, revert **only** that fix commit (`git revert --no-commit <sha>`), keep the probe/counters, run, record red; do not merge.
3. For a gate whose condition cannot occur in the pre-fix code (kill never reproduced), use a **test-only mutation flag** (`-Pannotate.mutation=<name>`, read via `BuildConfig`/instrumentation arguments in androidInstrumentedTest only) that injects the failure: `loaderPerPress`, `perDrawMeasure`, `busyWaitMove`, `backgroundBusyLoop` (a leaked `while(true)` coroutine in the editor scope that survives `onStop`), `leakStrongRef`, `exitOnBackground` (`Process.killProcess` after `onStop`, for the exit-reason gate), `loupeFullDecode`, `cacheNoLimit`, `undoUnbounded`, `oomInjection` (the last four back the matrix rows below). Each `gate` entry in the budgets file names its flag in the `mutation` field (`BudgetEntry`, ADR-001).
4. Paste the red output (record JSON line + checker exit code 1) in the PR for the gate.

| Gate | Pre-fix proof | Mutation proof | Cannot be proven (reason) |
|---|---|---|---|
| `loaderConstructions == 1` | revert A.4c alone, expect 100 | `loaderPerPress` | |
| decode bytes / count per press | probe-only PR on unfixed loupe, expect full-size decode | `loupeFullDecode` | |
| `cache.size <= maxSize` | n/a | `cacheNoLimit` (test-only cache) | pre-fix code respects the limit |
| `undoDepth <= 50` | n/a | `undoUnbounded` | pre-fix code is bounded |
| idle CPU after burst | n/a (pre-fix idles at 2-4%) | `backgroundBusyLoop` (mandatory) + positive control A.3d | kill never reproduced |
| process survived / no bad exit reason | n/a | `exitOnBackground` and OOM injection | kill never reproduced |
| `AnnotationRapidPlacementSmokeTest` | C.2 narrow-canvas crash reverted (the one reproducible crash by reading) | mutation throwing in the pointer handler | the CPU kill is not reproducible |
| WeakReference leak (g) | n/a | `leakStrongRef` | |
**Files**: `benchmarks/budgets/annotation-resource-budgets.json`, `scripts/bench-budget-check.js`, `scripts/bench-budget-check.test.js`, `scripts/fixtures/budgets/*.json`.
**Tests first**: `scripts/bench-budget-check.test.js` (node), written before the script.

##### Task B.5a: Evaluator script + node tests + fixtures (S)
- Files: `scripts/bench-budget-check.js`, `scripts/bench-budget-check.test.js`, `scripts/fixtures/budgets/`
##### Task B.5b: Initial budgets file (deterministic gates, others report-only) (S)
- Entries per ADR-001 and cpu.md section 4 thresholds. **Gate from day one (deterministic, with a mutation proof)**: decode count/press, `cache.size <= maxSize`, `undoDepth <= 50`, `loaderConstructions == 1`, process survived, no bad exit reasons. **ReportOnly until calibrated through B.7**: idle CPU % after burst (initial value 10% kept as the proposed budget; cold CI emulator spikes from JIT/GC/activity-stop make it unsafe as a day-one gate; promote when the median of 3 windows is stable over >=10 runs), sustained CPU % (60%, report-only, resolves the A.3 contradiction), max single decode bytes thresholds that depend on the not-yet-chosen strategy (loupe 2 MB) or on a not-yet-measured cell size (4 MB library decodes), WeakReference leak (until its mutation proof). ADR-001's day-one list is amended to match (ADR amendment in this task; done in Repair 2).
- **Day-one flake protection (pre-mortem failure 2)**: (a) the bad-exit-reason set is an explicit allowlist (`CRASH`, `CRASH_NATIVE`, `ANR`, `EXCESSIVE_RESOURCE_USAGE`, `LOW_MEMORY`, `SIGNALED`) filtered to entries with this test process's `pid` and `timestamp >=` the scenario start, so harness-caused `REASON_OTHER`, `USER_REQUESTED` and reinstall/teardown entries (for example from the earlier `AndroidGraphBenchmark` step) are ignored; unit-tested in `scripts/bench-budget-check.test.js` with a fixture containing such entries; (b) the file starts with `globalMode: report-only`, so every gate is evaluated report-only until the B.7b calibration soak; (c) a single-edit demotion: `globalMode: report-only` (whole file) or one entry's `mode` (one gate); (d) escape hatch label `skip-annotation-gate` (B.6a).
- Files add: `project_plans/annotation-memory-and-nav-ia/decisions/ADR-001-unified-resource-probe-and-budgets-file.md`
- Files: `benchmarks/budgets/annotation-resource-budgets.json`
##### Task B.5c: Pre-fix and mutation proofs (M)
- Execute the pre-fix proof procedure above for every row of the matrix (B.5d supplies the mutation flags); paste each red result into the corresponding fix PR or the harness PR. Throwaway branches are not merged.
- Files: PR descriptions only (no repo files)
##### Task B.5d: Test-only mutation flags (S)
- Implement the named mutation flags (instrumentation-argument gated, androidInstrumentedTest/debug only, absent from release builds) so every matrix row has a repeatable red run; add a node test that the budgets file lists a mutation name for each `gate` entry.
- Mutation seams live in `androidInstrumentedTest` or a `debug` source set only (pre-mortem failure 5). Where a flag needs a production hook (e.g. `loaderPerPress`), the test supplies the behaviour through the already-shipped injection seams (the A.4a loader/request provider, the B.2b `eventListenerFactory`), so no mutation symbol exists in `main`. Replace the "assert never set in release" runtime check with a build check: an R8/lint (or dex-grep) step that fails if `Mutations` or any flag name is present in the release APK.
- `BudgetEntry.mutation` is validated by `bench-budget-check.js --validate` (ADR-001).
- Files: `AIT/performance/probe/Mutations.kt`, `scripts/bench-budget-check.js`, `scripts/bench-budget-check.test.js`

### Story B.6: CI integration
**As a** maintainer, **I want** the new scenarios in CI on pinned hardware, **so that** regressions fail before they reach a phone.
**Acceptance Criteria**:
- `android-benchmark.yml` runs the new classes in one batched Gradle invocation after `AndroidGraphBenchmark`.
  - *Given* a PR touching `AIT/performance/**`, *When* CI runs, *Then* one extra `./gradlew :kmp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<comma list>` step executes and the sticky comment tagged `android-resource-benchmark` appears.
- AVD RAM is pinned and verified (before B.6a claims a low-RAM profile: a CI dry run must show `ram-size` reaches the guest, since the local AVD ignored `-memory 2048`, pitfalls.md Part B; if the pin fails, Success Metric 2's numeric memory budgets are `ReportOnly`, not gating, and `android-smoke`'s `-memory 2048` is not described as a low-memory profile).
  - *Given* `ram-size` set to 2048 and the emulator reporting `MemTotal` within 10% of it, *When* the job starts, *Then* the run is valid; *Given* it reports 3,050,208 kB (as in pitfalls.md), *Then* memory metrics are tagged `invalid-device` and cannot fail the job.
- The budget check step fails the job on gate breaches.
  - *Given* a gate breach in the records, *When* `node scripts/bench-budget-check.js` runs, *Then* the job fails after the comment is posted (comment `if: always()`).
- A crash-class pass/fail test runs in `android-smoke` (API 29, `-memory 2048`).
  - *Given* `AnnotationRapidPlacementSmokeTest` placing 100 points with direct injection, *When* it runs under `:androidApp:connectedDebugAndroidTest`, *Then* it passes with no uncaught exception and `junit-report` fails the job otherwise.
- `LoupeAlignmentE2ETest` runs in CI once PR #560 is merged.
  - *Given* #560 merged, *When* CI runs, *Then* the class is in the `class=` list (task B.6c); until then the task is blocked and recorded.
- **Privacy: only synthetic data leaves the runner (requirements NFR "Security classification"; task B.6d).** Every uploaded failure artifact, every `benchmarks/**` history JSON (`benchmarks/android-history/`, `benchmarks/history/`) and every PR comment is generated from synthetic fixtures; none contains a file path, a user file name, graph content, EXIF GPS data, a username or a hostname.
  - *Given* an artifact directory, a history JSON and a comment body built from a run that also contains a planted real-looking path (`/home/alice/Pictures/IMG_0001.jpg`, `content://media/external/images/1`, `/storage/emulated/0/DCIM/x.jpg`), *When* `node scripts/check-artifact-privacy.js` runs, *Then* it exits 1 naming the file and the offending pattern; *Given* a clean run, *Then* it exits 0.
- **The `SQLDelight generated sources` job is verified, not assumed, to block merges (task B.6e).** Checked 2026-11-08: `gh api repos/tstapler/stelekit/rulesets` returned `[]` and `gh api repos/tstapler/stelekit/branches/main/protection/required_status_checks` returned HTTP 404 "Branch not protected", so that job (`ci.yml:81-107`) is **not** a required check today.
  - *Given* a PR that changes a `.sq` file without regenerating `kmp/src/generated/`, *When* the PR's checks are read, *Then* either the job is a required check (a ruleset or branch protection names it, read back with `gh api`) or the owner-independent fallback holds: the story's verify step runs `git diff --exit-code kmp/src/generated/` locally and `gh pr checks <pr>` must show the job green before any merge (a `github:pr-ship` precondition).
**Files**: `.github/workflows/android-benchmark.yml`, `.github/workflows/ci.yml`, `androidApp/src/androidTest/kotlin/dev/stapler/stelekit/AnnotationRapidPlacementSmokeTest.kt`, `scripts/check-artifact-privacy.js`.
**Tests first**: `actionlint -color` clean (repo rule) and `uvx 'zizmor==1.25.2' .` clean before merge.

##### Task B.6a: Benchmark-job wiring, RAM pin, budget step, artifact upload (M)
- Set an explicit job `timeout-minutes` and a runtime budget (+10 minutes over the current job) before wiring. PRs run the deterministic subset only (loader count, decode count, cache, undo depth, smoke); the n>=10 noise-floor and calibration runs go on `workflow_dispatch`/nightly. The budget step is `continue-on-error` and not a required check for the first 10 runs; the job path-filters to `AIT/performance/**`, `kmp/src/commonMain/**/annotate/**`, `SteleKitAssetFetcher.kt` and `benchmarks/budgets/**`; escape hatch label `skip-annotation-gate`. Whether the `SQLDelight generated sources` job blocks merges is verified in task B.6e (it does not today). The first CI run still decides whether the job must split.
- Files: `.github/workflows/android-benchmark.yml`
##### Task B.6b: `android-smoke` crash-class test (S)
- Files: `androidApp/src/androidTest/kotlin/dev/stapler/stelekit/AnnotationRapidPlacementSmokeTest.kt`, `.github/workflows/ci.yml` (only if the module filter needs a change)
##### Task B.6c: Wire `LoupeAlignmentE2ETest` (XS, blocked on #560)
- Files: `.github/workflows/android-benchmark.yml`
##### Task B.6d: Privacy gate for CI outputs: synthetic fixtures only (S)
- (1) Fixtures: `TestImages` and `FixtureGraphSeeds` generate every image and row in-test with names `synthetic-<n>`; no fixture reads from the runner's or a developer's media. (2) `scripts/check-artifact-privacy.js` scans the failure-artifact directory (only `ProbeRecord` JSON and JUnit XML are uploaded, never screenshots of real content or app databases), any `benchmarks/**` JSON added or changed, and the sticky-comment body, rejecting absolute paths (`/home/`, `/Users/`, `/storage/`, `/data/`, `C:\\`), `content://` and `file://` URIs, names not matching `synthetic-`, EXIF GPS tags, e-mail-like strings and the runner hostname; fields outside the `ProbeRecord` allowlist fail it. (3) The workflow runs it before `upload-artifact` and before the comment step, and a failure blocks both. (4) A node test plants the paths above and asserts exit 1; `ProbeRecordPrivacyTest` (BT) and `benchdiff-export.test.js` allowlist test already exist.
- Files: `scripts/check-artifact-privacy.js`, `scripts/check-artifact-privacy.test.js`, `.github/workflows/android-benchmark.yml`
##### Task B.6e: Verify the generated-sources check blocks merges; fallback if not (XS)
- Run `gh api repos/tstapler/stelekit/rulesets` and `.../branches/main/protection/required_status_checks` and paste the output in the PR. If `SQLDelight generated sources` is not required (the 2026-11-08 reading), record that in the Repair log and (a) ask the owner to add it to a ruleset (wall-clock blocker W10, non-blocking), and (b) put the owner-independent fallback in force: every story that changes a `.sq` file runs `git diff --exit-code kmp/src/generated/` after regeneration and `gh pr checks` must show the job green before merge. Serialisation of regeneration across PRs (Migration Plan) does not depend on this.
- Files: none (verification output in the PR); `.github/workflows/ci.yml` only if the owner prefers a path-triggered duplicate job

### Story B.7: Calibration and gate promotion
**As the** owner, **I want** noisy metrics promoted to gates only after real CI data, **so that** gates do not flake.
**Acceptance Criteria**:
- A calibration script derives budgets from observed runs.
  - *Given* 10 history files with `RapidPlacement.cpuMsPerPlacement` values (median 141, max 190), *When* `scripts/bench-budget-calibrate.js` runs, *Then* it proposes `budget = max(observed) * 1.5` = 285 (or 2x median if larger) and prints the proposal without editing the file.
- A promotion happens only with >= 10 valid runs and zero `invalid-device` runs.
  - *Given* 9 valid runs, *When* the script is asked to promote, *Then* it refuses and prints `need 10, have 9`.
**Files**: `scripts/bench-budget-calibrate.js`, `scripts/bench-budget-calibrate.test.js`, `benchmarks/budgets/annotation-resource-budgets.json`.
**Tests first**: `scripts/bench-budget-calibrate.test.js`.

##### Task B.7a: Calibration script + tests (S)
- Files: `scripts/bench-budget-calibrate.js`, `scripts/bench-budget-calibrate.test.js`
##### Task B.7b: Promotion PR after soak (XS)
- After >= 10 CI runs on the merged harness (nightly/dispatch runs count; PR runs on the path-filtered subset also count), promote metrics whose spread is acceptable and flip `globalMode` to `enforce`; record `calibratedFromRuns`. Wall-clock blocker W5 (CI runs accrue on merges).
- Files: `benchmarks/budgets/annotation-resource-budgets.json`

### Story B.8: New-test-class registration and Bazel verification
**As a** maintainer, **I want** every new test class to be registered where Bazel runs it, **so that** `bazel test //kmp:business_tests` really runs it (the MEMORY.md "businessTest suite gap" must not recur).
**Verified facts**: `kmp/src/commonTest/kotlin/BUILD.bazel:104-133` (`business_common_tests`) is an explicit `srcs` list, not a glob; `kmp/src/businessTest/kotlin/dev/stapler/stelekit/AllBusinessTests.kt` is a JUnit `@Suite.SuiteClasses(...)` list and only classes named there run (`businessTest/kotlin/BUILD.bazel:137` `test_class = ...AllBusinessTests`); `AllBusinessTestsCompletenessTest` fails when a classpath `*Test` is unregistered. businessTest sources are globbed into the target (`businessTest/kotlin/BUILD.bazel:136`) **but still need the `AllBusinessTests` entry**; androidUnitTest sources are globbed and need no list.
**Acceptance Criteria**:
- Every new commonTest class has (1) a `srcs` entry in `business_common_tests` and (2) an `AllBusinessTests` registration; every new businessTest class has the `AllBusinessTests` registration.
  - *Given* the checklist below, *When* `bazel test //kmp:business_tests --test_output=all` runs, *Then* the output lists each class name in the checklist (grep each name; the run fails the task if any is missing) and `AllBusinessTestsCompletenessTest` passes.
- A plan-level **new test class checklist** is kept current in the PR template of each story.
**New test class checklist** (class -> source set -> Bazel registration):

| Class | Source set | `commonTest/kotlin/BUILD.bazel` `business_common_tests` srcs | `AllBusinessTests` |
|---|---|---|---|
| `ExitRecordFormatterTest`, `ProcStatParserTest` (A.2) | commonTest | add | add |
| `ImageSamplingTest` (extended, A.5) | commonTest | already listed (`BUILD.bazel:132`) | already listed (`AllBusinessTests.kt:413`) |
| `CalibrationDecodeInvarianceTest`, `NativeImageSizeTest` (C.1) | commonTest | add | add |
| `LoupeLayoutPropertyTest` (C.2) | commonTest | add | add |
| `PlacementGestureClassifierTest` (C.9f) | commonTest | add | add |
| `LibraryFilterRulesTest`, `LibraryFilterRulesPropertyTest` (D.6g), `HandleHitResolverTest` (C.8e), `PointerTypeGestureTest` (C.9j), `LogRedactorTest` (A.0e) (Repair 4) | commonTest | add | add |
| `NavLabelUniquenessTest` (D.2a), `LibraryFilterSqlParityTest` (D.7a), `NudgeStepTest` (C.8i), `EditorSnackbarQueueTest` (C.8h), `EditorLoadGenerationTest` (C.9g) (Repair 4) | businessTest | n/a | add |
| `LibraryMediaClassTest`, `LibrarySearchTest`, `LibraryStatusLineTest` (D.6g, D.6a), `UpgradeNoticeStateMachineTest` (D.9c), `PlacementCancelZoneTest` (C.9j), `EditorBackPrecedenceTest` (C.9m) (Repair 5) | commonTest | add | add |
| `AreaCloseTolerancePropertyTest` (C.10) | commonTest | add | add |
| `NavSectionBuilderTest`, `NavCommandsTest` (D.2, D.5) | commonTest | add | add |
| `FixtureGraphSeeds.kt` (B.3, fixture, not a test) | commonTest | add (sources only) | n/a |
| `AnnotationEditorCrashGuardTest`, `EditorMemoryPressureTest`, `UndoDepthTest`, `CoilSeamTest`, `ImageLoaderConstructionCountTest`, `FixtureGraphCountsTest`, `MemoryPressureListenersTest` (ext.), `MeasurementAnnotationActorRoutingTest`, `RealActorOrderingTest`, `CalibrationBasisMigrationTest`, `CalibrationBasisDowngradeTest`, `LegacyCalibrationWritePathPropertyTest`, `PersistedScreenIdTest` (A.2, A.4, B.2, B.3, C.1, C.5, D.8) | businessTest | n/a (globbed) | add |
| `UndoHistoryTest`, `AnnotationPersistenceQueueTest`, `EditorInitialLoadRaceTest`, `LegacyCalibrationFlagTest`, `CrosshairPlacementTest`, `CrosshairMoveVertexTest`, `CrosshairKeyHandlerTest`, `EditorStateAtomicityTest`, `AssetDetailAnnotateOrphanTest`, `LibraryAnnotatedJoinTest`, `LargeLibraryBatchingTest`, `LibraryViewModelStateTest`, `LibraryRedirectTest`, `NavModelExhaustivenessTest` | businessTest | n/a | add |
| androidUnitTest classes (A.4, A.5, C.x, D.x) | androidUnitTest | n/a | n/a; run via `bazel test //kmp/src/androidUnitTest/kotlin:android_unit_tests --config=android` and confirm each class name appears |

##### Task B.8a: Register Epic A/B/C commonTest and businessTest classes (S)
- Files: `kmp/src/commonTest/kotlin/BUILD.bazel`, `kmp/src/businessTest/kotlin/dev/stapler/stelekit/AllBusinessTests.kt`
##### Task B.8b: Register Epic D classes and verify the full run lists them (S)
- Files: `kmp/src/commonTest/kotlin/BUILD.bazel`, `kmp/src/businessTest/kotlin/dev/stapler/stelekit/AllBusinessTests.kt`
##### Task B.8c: Bazel verification step and script (XS)
- Accepts explicit class names, or with no arguments derives them from `git diff --name-only origin/main...HEAD -- '*Test.kt'` (new/changed test classes), so a forgotten registration cannot be hidden by omitting the argument. A small `scripts/check-bazel-test-listing.sh [class...]` that runs `bazel test //kmp:business_tests --test_output=all` (wrapped in `timeout 30m`) and greps the log for each name; invoked as the last verify step of every story that adds a class.
- Files: `scripts/check-bazel-test-listing.sh`

Each story adding a test class includes its B.8 rows in its own PR (tasks B.8a/b are the audit and backfill; do not defer registration to the end).

---

# Phase 2: Annotation feature quality (Epic C)

## Epic C: Annotation feature review fixes

**Goal**: Every CRITICAL/MAJOR finding in `research/features.md` is fixed with a test; minor findings are fixed in grouped stories. Traceability: C1 -> C.1; C2 -> C.2; C3 -> C.3; C4 -> A.2b; M1/M7 -> C.5; M2 -> C.6; M3/M4 -> C.8; M5 -> C.7; M6, m1-m7 -> C.9 (m1/m2 overlap A.4b), m8 (process death resets tool/selection) -> C.9e, M8 (`loupeOffset` at screen scope) -> A.4a. UX criteria added in Repair 3: UX-33/34 -> C.8c, C.8g; UX-36/39 non-drag move -> C.8f; UX-38 -> C.9a, C.9f; UX-29 offline copy -> C.7a, C.7c (all unconditional); UX-48 -> C.9g; UX-49, UX-50 -> C.9f; UX-51, UX-52 -> C.9h, C.9i. UX criteria added in Repair 2: UX-21 -> A.0c, UX-22/23 -> A.0b, UX-26 -> C.6b, UX-29 -> C.7c, UX-36/37 -> C.8e, UX-41 -> C.9d, UX-15 -> D.6b (Repair 3: the Library card; was D.6d). Test-coverage gaps 1-10 are mapped in Story C.10.

### Story C.1: Calibration is independent of decode size (CRITICAL C1, ADR-004)
**As a** user, **I want** a measurement to be the same on every device and window size, **so that** I can trust the number.
**Acceptance Criteria**:
- Distances use `NativeImageSize`, not the painter's intrinsic size.
  - *Given* an `ImageAnnotation` for a 4000x3000 file with `Calibration(MANUAL_REFERENCE, pixelsPerMeter=100.0 at native width)` and two NormalizedPoints (0.1, 0.5) and (0.6, 0.5), *When* `computeMeasurement` runs with the editor decoded at 1080 px and again at 2208 px wide, *Then* both return 20.0 m (0.5 x 4000 / 100) with a difference of 0.0.
- EXIF-rotated files use oriented size.
  - *Given* a 4000x3000 JPEG with EXIF orientation 6, *When* `readNativeImageSize` runs, *Then* it returns 3000x4000.
- Legacy calibrations are flagged, not silently rescaled; the flag is a **persisted marker** (migration, see Migration Plan) decided by spike C.1a before any code.
  - *Given* a calibration row with `calibration_basis_version = 0` (saved before the fix), *When* the editor opens, *Then* the calibration badge shows "Recalibrate for accuracy" (UX-28) and no stored value changes; *Given* a row written after the fix, *Then* `calibration_basis_version = 1` and no badge.
  - *Given* a database created before this migration, *When* `MigrationRunner.applyAll()` runs, *Then* the column exists with default 0 on every existing row (`MigrationRunnerSchemaSyncTest` passes).
- **Never rescale legacy rows silently (explicit acceptance criterion, pre-mortem failure 3).** The editor, any Library card that shows a distance (today's Gallery card shows date and count; C.1g confirms), exporter labels and C.7 baked labels all recompute from normalized points and `pixelsPerMeter`, so asserting only the stored value is not enough.
  - *Given* a version-0 annotation created by the pre-fix schema (fixture database) whose pre-update displayed distance text is M, *When* the app is updated, *Then* the displayed distance is M, unchanged, in the editor on open, on any Library card that shows a distance, and in the exported/baked label (golden over the fixture, task C.1g), and only a user-initiated recalibration writes `calibration_basis_version = 1`.
  - *Given* every write path (commit, undo, redo, bulk/tag rewrite, calibration re-save, queue replay), *When* each saves an annotation, *Then* the property `calibration_basis_version = 1` holds only if `NativeImageSize` was used for a user-initiated calibration, and a path that did not set the column never stamps 1 (default 0 is not promoted).
  - *Given* a database with the added column opened by the pre-fix schema (older APK), *When* it reads and writes annotations, *Then* nothing fails (downgrade test).
**Files**: `CM/platform/image/NativeImageSize.kt` (new; pure value type with `NativeImageSize.oriented(w, h, exifOrientation)`; placed in `platform/image/`, not `model/`, which is the page/block domain package), `CM/platform/image/ImageBoundsReader.kt` (new `fun interface ImageBoundsReader` returning raw width, height, EXIF orientation; no `expect/actual`), `AM/platform/image/AndroidImageBoundsReader.kt`, `kmp/src/jvmMain/.../JvmImageBoundsReader.kt` (iOS and wasmJs shells inject a reader that returns the painter-independent decode via their existing codecs, or the editor is not reachable there: state which in C.1b; existing `ExifOrientationReader`/`OrientationTransform` in `platform/image/` are reused), `ANN/AnnotationEditorViewModel.kt` (`setImageSize`, `computeMeasurement`, `confirmGridRefCalibration`; holds `NativeImageSize` only, not raw `Int`s), `ANN/AnnotationEditorScreen.kt` (stop passing `intrinsicSize`), `CM/db/MigrationRunner.kt`, `kmp/src/commonMain/sqldelight/.../SteleDatabase.sq`, `kmp/src/generated/sqldelight/`.
**Tests first**: `CT/ui/annotate/CalibrationDecodeInvarianceTest` and `CT/platform/image/NativeImageSizeTest` (commonTest, kotest-property over decode widths 256-4096 and EXIF orientations 1-8; both registered per B.8), `AUT/platform/image/ImageBoundsTest` (androidUnitTest, EXIF fixtures), `BT/ui/annotate/LegacyCalibrationFlagTest` (businessTest; reads a version-0 and a version-1 row), `BT/db/CalibrationBasisMigrationTest` (migration, reversibility), `BT/db/CalibrationBasisDowngradeTest` (older schema reads and writes the added column), `BT/ui/annotate/LegacyCalibrationWritePathPropertyTest` (every write path), `AUT/ui/annotate/LegacyDisplayGoldenTest` (editor displayed text; Library card if it shows a distance) and an extension of `JT/ui/annotate/AnnotationExporterTest` (exported label), all over the pre-fix fixture database.

##### Task C.1a: Spike: where is calibration persisted, what basis did old sessions use? (S)
- Read-only, runs first (wave W-A), no code before it finishes. Read git history for #556/#558 and the `Calibration`/`ImageAnnotation` serialization (`image_annotations.pixels_per_meter`, `calibration_method`). Run the two-window-size log from features.md C1 to confirm or refute the defect (C1 stays "unconfirmed" until then). Produce an **ADR-004 amendment** that fixes: where the legacy marker lives (default proposal: `image_annotations.calibration_basis_version INTEGER NOT NULL DEFAULT 0`; alternative: a settings key), the exact values, the read path for version 0, and the re-estimate for C.1b-d. Set ADR-004 `Status` to Accepted only with that amendment.
- Files: `project_plans/annotation-memory-and-nav-ia/decisions/ADR-004-calibration-pixel-basis.md`
##### Task C.1b: `NativeImageSize`, `ImageBoundsReader` seam, VM wiring (M)
- Pure orientation-swap rule in commonMain (property-tested); platform part is a `fun interface ImageBoundsReader`; no `expect/actual`, so no iOS/wasmJs actual files are needed. Net VM line count must not grow (track `wc -l`).
- Files: `CM/platform/image/NativeImageSize.kt`, `CM/platform/image/ImageBoundsReader.kt`, `AM/platform/image/AndroidImageBoundsReader.kt`, `kmp/src/jvmMain/.../JvmImageBoundsReader.kt`, `ANN/AnnotationEditorViewModel.kt`
##### Task C.1c: Legacy flag UI (S)
- "Recalibrate for accuracy" badge driven by the persisted marker (UX-28); no stored value changes until the user acts.
- Files: `ANN/AnnotationEditorScreen.kt`
##### Task C.1d: Marker migration: schema, `MigrationRunner` entry, schema-sync test (S)
- **Schema half of the former C.1d (split in Repair 3: the old task touched six or more files; each half now fits one worker run).** Migration Plan steps (1), (2) and (5): add the column to `SteleDatabase.sq`, the guarded `ALTER TABLE ... ADD COLUMN` entry in `MigrationRunner.kt` (the `image_annotations` table is created at `MigrationRunner.kt:392`), `MigrationRunnerSchemaSyncTest` passes, and the pre-fix / post-fix row test. Sequenced after C.1a and before any code writes version 1. OD-6 is DECIDED (ALLOWED, 2026-11-08).
- Files: `kmp/src/commonMain/sqldelight/.../SteleDatabase.sq`, `CM/db/MigrationRunner.kt`, `BT/db/MigrationRunnerSchemaSyncTest.kt` (extend if needed), `BT/db/CalibrationBasisMigrationTest.kt`
##### Task C.1f: Write stubs, regenerated SQLDelight sources, downgrade test (S)
- Migration Plan steps (3), (4) and (7): `RestrictedDatabaseQueries` `@DirectSqlWrite` stubs for any changed insert/update (existing measurement stubs sit at `RestrictedDatabaseQueries.kt:610-634`; the image-annotation insert/update stubs are in the same file), run `./gradlew :kmp:generateCommonMainSteleDatabase` and `rsync -a kmp/build/generated/sqldelight/code/SteleDatabase/commonMain/ kmp/src/generated/sqldelight/`, commit the generated sources (mechanical output of one command: counted as one unit, reviewed by `git diff --stat`, not line by line), and the downgrade test (database with the added column opened by the pre-fix schema reads and writes). Follows C.1d in the same PR; regeneration order across PRs is serialised (Migration Plan). **Split trigger (generated sources)**: if the regeneration diff touches more than 6 files under `kmp/src/generated/`, includes generated code for queries unrelated to the calibration column, or conflicts on rebase with the D.7c/D.7a regeneration, split into C.1f-gen (only the `.sq`, `RestrictedDatabaseQueries.kt` stubs and `kmp/src/generated/**`, a mechanical PR with the local `git diff --exit-code kmp/src/generated/` result) and C.1f-test (the downgrade test); never hand-merge generated files.
- Files: `CM/db/RestrictedDatabaseQueries.kt`, `CM/repository/ImageAnnotationRepository.kt` (write path), `kmp/src/generated/sqldelight/`, `BT/db/CalibrationBasisDowngradeTest.kt`
##### Task C.1e: Legacy-basis read path and write-path property (S)
- Version-0 rows keep the pre-fix computation (the painter-size basis the old app used) so the displayed distance equals what the pre-update app showed on that device and window; the C1 invariance fix applies to version-1 rows only. Add `LegacyCalibrationWritePathPropertyTest` over commit, undo, redo, bulk/tag, calibration re-save and queue replay. The badge (C.1c) is the only visible change for version-0 rows. (The displayed-value goldens moved to C.1g in Repair 3 so this task fits three files.)
- Files: `ANN/AnnotationEditorViewModel.kt` (delegation only; basis choice lives in `NativeImageSize`/calibration helper, net VM line count must not grow), `CM/platform/image/CalibrationBasis.kt` (new helper), `BT/ui/annotate/LegacyCalibrationWritePathPropertyTest.kt`
##### Task C.1g: Displayed-value goldens across editor, Library card and export (S)
- Golden fixtures come from a database created by the pre-fix schema. Assert the same distance text M before and after the update in the editor on open, on any Library card that shows a distance (confirm whether today's Gallery card shows one; if not, record "count only" and drop that surface), and in the exported/baked label. Depends on C.1e and D.6b for the card surface.
- Files: `ANN/AnnotationExporter.kt` (label source only), `LIB/LibraryItemCard.kt` (label source, only if the card shows a distance), `AUT/ui/annotate/LegacyDisplayGoldenTest.kt`, `JT/ui/annotate/AnnotationExporterTest.kt`

### Story C.2: Loupe cannot crash on narrow or wide images (CRITICAL C2)
**As a** user with a panorama or a long receipt, **I want** placement to work, **so that** the app does not close.
**Acceptance Criteria**:
- Clamping never throws.
  - *Given* a canvas 360 dp x 90 dp (4:1 panorama) and loupe 120 dp with 16 dp margins, *When* the user presses, *Then* `clampLoupeOffset` returns an in-range value and no `IllegalArgumentException` is raised.
  - *Given* any `min > max` bounds pair (property test), *When* `coerceInSafe` runs, *Then* it returns a finite value and never throws.
- The loupe stays visible and clamped in the degenerate case.
  - *Given* a 4000x600 image on a phone canvas, *When* the loupe is shown, *Then* its bounds lie inside the canvas rect.
**Files**: `CM/ui/annotate/LoupeGeometry.kt` (existing, holds `loupeInnerOffset`; add the pure `clampLoupeOffset` here rather than creating `LoupeLayout.kt`), `ANN/LoupeOverlay.kt`. **Ordering**: C.2a is pure and independent (W-A); C.2b edits `ANN/LoupeOverlay.kt`, which A.4a creates, so C.2b depends on A.4a and on PR #560 merged or rebased (task A.3f); do not start it in W-A.
**Tests first**: `CT/ui/annotate/LoupeLayoutPropertyTest` (commonTest, `Arb`), `AUT/ui/annotate/LoupeNarrowCanvasTest` (androidUnitTest, Robolectric).

##### Task C.2a: Pure `clampLoupeOffset` + tests (XS)
- Files: `CM/ui/annotate/LoupeGeometry.kt`, `CT/ui/annotate/LoupeLayoutPropertyTest.kt`, B.8 registration
##### Task C.2b: Use it in `LoupeOverlay`; Robolectric panorama test (S)
- Prerequisites: A.4a merged and A.3f done. The C2 narrow-canvas crash is the one crash reproducible by reading; `AnnotationRapidPlacementSmokeTest` (B.6b) includes a 4:1 canvas case so it has a persistent on-device test, not Robolectric-only.
- Files: `ANN/LoupeOverlay.kt`, `AUT/ui/annotate/LoupeNarrowCanvasTest.kt`

### Story C.3: Undo never resurrects in-progress points (CRITICAL C3, ADR-005)
**As a** user, **I want** Undo to remove exactly the last annotation, **so that** it does not create phantom points.
**Acceptance Criteria**:
- Undo restores the committed set only.
  - *Given* a DISTANCE tool, 2 points placed (committed), then 1 point placed (in progress), *When* Undo is pressed, *Then* `committedAnnotations.size` = 0, `inProgressPoints` is empty, and `currentTool` remains DISTANCE.
  - *Given* a committed DISTANCE then undo then one tap, *When* the tap is processed, *Then* `inProgressPoints.size` = 1 (not a 3-point commit).
- Undo does not change calibration unless the entry is a calibration entry.
  - *Given* `UndoEntry.Calibration(before=100.0 px/m, after=250.0 px/m)` on top and a later commit, *When* Undo is pressed twice, *Then* the first removes the commit and the second restores 100.0 px/m while the commit made earlier than the calibration change remains.
**Files**: `ANN/UndoHistory.kt` (new), `ANN/AnnotationEditorViewModel.kt` (delegation only).
**Tests first**: `BT/ui/annotate/UndoHistoryTest` (businessTest; property: for any random op sequence, after undo-all `committedAnnotations` is empty and `inProgressPoints` empty), update `AnnotationEditorViewModelTest`/`...BranchTest`.

##### Task C.3a: Tests first incl. property test and updated existing tests (S)
- Files: `BT/ui/annotate/UndoHistoryTest.kt`, `BT/ui/annotate/AnnotationEditorViewModelTest.kt`
##### Task C.3b: `UndoHistory<UndoEntry>` class and VM delegation (M)
- Files: `ANN/UndoHistory.kt`, `ANN/AnnotationEditorViewModel.kt`

### Story C.5: Serialized persistence, no lost or resurrected rows (MAJOR M1/M7, ADR-005)
**As a** user, **I want** every placement saved exactly once in order, **so that** undo and fast taps never lose or resurrect data.
**Acceptance Criteria**:
- Rapid commit then undo never resurrects the undone row.
  - *Given* a fake repository with 200 ms save latency, *When* the user commits annotation X then immediately undoes, *Then* after the queue drains the repository contains no row for X.
- Initial load racing the first mutation never deletes persisted rows.
  - *Given* 3 persisted annotations and a load that completes after the user's first placement, *When* the queue drains, *Then* the repository holds 4 rows.
- A failed initial load followed by a write does not wipe the DB.
  - *Given* `getAnnotations` returning `Either.Left`, *When* the user places a point and commit saves, *Then* no bulk `delete-all` is issued and the user sees a retry banner.
- Undo of a calibration change keeps annotations added after it.
  - *Given* calibration changed, then 1 annotation added, *When* the calibration undo is invoked from the snackbar, *Then* the annotation remains in state and DB (ADR-005).
- One priority for all measurement writes (pre-mortem failure 4). `DatabaseWriteActor` has HIGH and LOW channels with no cross-channel ordering (`DatabaseWriteActor.kt:104,218-219`), so mixed priorities could invert commit and undo set-save and resurrect an undone row.
  - *Given* the **real** `DatabaseWriteActor` (not a recording fake) with interleaved LOW graph-load writes, *When* the user commits X, undoes, and commits Y with bulk rewrites in between, *Then* the repository ends with exactly the rows the editor shows, in submission order, and no undone row is resurrected (`RealActorOrderingTest`; `EditorInitialLoadRaceTest` and the 200 ms-latency test also run against the real actor).
  - *Given* the wrapper `MeasurementWrites.execute`, *When* any editor write is submitted, *Then* it is `Priority.HIGH` (a test fails if any other priority is passed) and `invalidate` is scoped to the annotation tables, with the extra recomposition measured by `CpuVsAnnotationCount`.
  - *Given* pending awaits in the queue and `GraphManager` shutting the actor down, *When* the VM `onCleared`/`close()` runs, *Then* pending awaits are cancelled and nothing hangs (`GraphManagerDatabaseLifecycleTest` pattern).
- All measurement-annotation writes go through `DatabaseWriteActor` (CLAUDE.md; architecture review blocker).
  - *Given* the finished C.5, *When* `rg "DirectRepositoryWrite" kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/` runs, *Then* it returns zero matches (the 7 opt-ins at `AnnotationEditorViewModel.kt:507,597,642,703,716,736,847` are gone) and no `@DirectRepositoryWrite` remains on `SqlDelightMeasurementAnnotationRepository`'s write methods (`:39-117`).
  - *Given* single, batch (`saveMeasurements`), undo/redo set-save and calibration re-save paths, *When* each runs with a recording `DatabaseWriteActor`, *Then* each produced exactly one `actor.execute { }` (or typed method) call, in submission order.
**Files**: `ANN/AnnotationPersistenceQueue.kt` (new; creates its own `CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)` and exposes `close()` called from the VM's `onCleared`/dispose; `UndoHistory` is a pure data structure and owns no scope), `ANN/AnnotationEditorViewModel.kt`, `CM/repository/SqlDelightMeasurementAnnotationRepository.kt`, `CM/repository/RepositoryFactory.kt` and the DI site that builds the repository (inject `DatabaseWriteActor`).
**Tests first**: `BT/ui/annotate/AnnotationPersistenceQueueTest` (businessTest, `TestDispatcher` interleavings + a randomized-ordering property), `BT/ui/annotate/EditorInitialLoadRaceTest`, `BT/repository/RealActorOrderingTest` (real `DatabaseWriteActor` plus LOW traffic).

##### Task C.5a: Queue + tests first (M)
- Depends on C.5c. With the actor serialising on one channel, the queue reduces to ordering and the initial-load merge: no `persistLock` (`AnnotationEditorViewModel.kt:211`, used at `:706`, `:719`) remains; delete it. The queue calls the repository and never opts into `DirectRepositoryWrite`.
- Files: `ANN/AnnotationPersistenceQueue.kt`, `BT/ui/annotate/AnnotationPersistenceQueueTest.kt`
##### Task C.5b: Route commit/delete/calibration/tag/bulk paths; initial-load merge; failed-load retry banner (M)
- Files: `ANN/AnnotationEditorViewModel.kt`, `ANN/AnnotationEditorScreen.kt`, `BT/ui/annotate/EditorInitialLoadRaceTest.kt`
##### Task C.5c: Refactor-first: route measurement writes through `DatabaseWriteActor` (M, sequenced before C.5a)
- **Split in Repair 3** (the old task listed seven files plus every test call site): C.5c is the production refactor (four files); C.5e is the mechanical call-site sweep and documentation corrections. **C.5c and C.5e land in one PR as two commits** (the constructor change does not compile until the call sites are updated), run by one worker after the other.
- A single wrapper `MeasurementWrites.execute` (one place, `Priority.HIGH`, `invalidate` scoped to the annotation tables) is the only way the repository submits to the actor; a test asserts the priority. The recording-actor test checks submission order only; ordering and races are proven by `RealActorOrderingTest`. The op passed to the actor must not call `actor.execute` again (no re-entrancy).
- Inject `DatabaseWriteActor` into `SqlDelightMeasurementAnnotationRepository` and switch it from `SteleDatabaseQueries` to `RestrictedDatabaseQueries` (stubs already exist at `RestrictedDatabaseQueries.kt:610-634`); wrap `saveMeasurementAnnotation` (`:40`), `saveMeasurements` (`:63`, includes its `queries.transaction`), `deleteMeasurementAnnotation` (`:91`), `deleteMeasurementsForImage` (`:104`) in `actor.execute { ... }` (`DatabaseWriteActor.kt:763`, returns `Either<DomainError, Unit>`) or add typed actor methods, and keep the existing `withContext(PlatformDispatcher.DB)`/`DomainError.DatabaseError.WriteFailed` mapping inside the op. Replace the class-level `@OptIn(DirectRepositoryWrite::class)` with the actor-gated form per CLAUDE.md `@DirectSqlWrite` rules (none for the existing four queries).
- Files: `CM/repository/SqlDelightMeasurementAnnotationRepository.kt`, `CM/repository/MeasurementWrites.kt` (new wrapper), `CM/repository/RepositoryFactory.kt` (`:440` constructor site), `BT/repository/MeasurementAnnotationActorRoutingTest.kt` (recording actor)
##### Task C.5e: Constructor call-site sweep and documentation corrections for C.5c (S)
- Mechanical: update every remaining constructor call site found by `rg 'SqlDelightMeasurementAnnotationRepository\('` (the test builders; count recorded in the PR, expected a handful of one-line edits, listed in the PR, not enumerated here because the number is found by the search), add the `UpgradeResilienceTest` entry for the closed-DB guard, correct `research/architecture.md` sections 2 and 4 (they claimed the writes already went through the actor; they did not) and amend ADR-005 (the "keep `@DirectRepositoryWrite`" consequence is removed). Justification for exceeding the file cap: call-site edits are one-line changes of one pattern, reviewed by `git diff --stat` and a clean compile.
- Files: the test builders the search finds (mechanical), `UpgradeResilienceTest`, `research/architecture.md`, `decisions/ADR-005-undo-and-persistence-semantics.md`
##### Task C.5d: Route VM-level measurement paths through the actor-backed repository; real-actor ordering tests (M)
- Run `EditorInitialLoadRaceTest`, the 200 ms-latency test and the new `RealActorOrderingTest` against the real actor with interleaved LOW graph-load writes; add the queue `close()` versus actor-shutdown test.
- Commit, delete, calibration re-save, bulk/tag rewrite and undo/redo set-save in `AnnotationEditorViewModel.kt` call the repository only via `AnnotationPersistenceQueue`; remove the 7 `@OptIn(DirectRepositoryWrite::class)` annotations (lines 507, 597, 642, 703, 716, 736, 847).
- Files: `ANN/AnnotationEditorViewModel.kt`, `BT/ui/annotate/EditorInitialLoadRaceTest.kt`, `BT/repository/RealActorOrderingTest.kt`

### Story C.6: Honest unsaved-changes behaviour (MAJOR M2)
**As a** user, **I want** no dialog that implies work will be lost when it will not, **so that** I do not mistrust the app.
**Acceptance Criteria**:
- Back with only committed annotations leaves immediately.
  - *Given* 3 committed annotations and no in-progress points, *When* Back is pressed, *Then* the editor closes with no dialog.
- Back with in-progress points confirms discarding only those.
  - *Given* 1 in-progress point, *When* Back is pressed, *Then* a dialog reads "Discard 1 unfinished point?" with Discard and Keep editing; Discard closes without changing committed annotations.
**Files**: `ANN/AnnotationEditorScreen.kt` (lines ~727-739 per features.md M2).
**Tests first**: `AUT/ui/annotate/UnsavedChangesDialogTest` (androidUnitTest).

##### Task C.6a: Tests + dialog replacement (S)
- Files: `ANN/AnnotationEditorScreen.kt`, `AUT/ui/annotate/UnsavedChangesDialogTest.kt`
##### Task C.6b: Leave while saving: "Saving..." wait and retry banner (S) (UX-26)
- Depends on C.5a. If the persistence queue is non-empty when the user chooses Discard or Back, wait up to 2 s showing "Saving...", then close; if the save fails stay open with the retry banner and offer [Retry] / [Close anyway] (confirm "Close without saving the last change?"). The S10 sentence "Placed measurements are already saved" is shown only once ux.md open question 2 is confirmed by C.5.
- Files: `ANN/AnnotationEditorScreen.kt`, `ANN/AnnotationEditorViewModel.kt` (state only), `AUT/ui/annotate/LeaveWhileSavingTest.kt`

### Story C.7: Export is correct (MAJOR M5)
**As a** user exporting to Drive, **I want** labels, correct scale and orientation, **so that** the exported image matches the screen.
**Acceptance Criteria**:
- Labels contain text.
  - *Given* a DISTANCE annotation labelled "3.20 m", *When* `bakeAnnotations` runs on a 1000x750 source, *Then* pixels inside the label pill bounds differ from the pill background colour (text drawn) — asserted by a golden diff of the label region.
- Stroke and dot sizes scale with resolution.
  - *Given* two bakes of the same annotation at 1000 px and 4000 px wide, *When* line widths are measured, *Then* the 4000 px stroke width is 4x +-5% of the 1000 px width.
- EXIF orientation is honoured.
  - *Given* a 4000x3000 JPEG with orientation 6 and a point at (0.25, 0.25), *When* baked, *Then* the dot is at (0.25 x 3000, 0.25 x 4000) in the output.
- Decode is bounded by pixels, not edge, and failures are `Throwable`-safe.
  - *Given* a 4096x4096 source, *When* decoded, *Then* decoded pixels <= `MAX_DECODE_PIXELS` and an injected `OutOfMemoryError` in `ImageEncoder` returns `Either.Left`, not a crash.
**Files**: `ANN/AnnotationExporter.kt`, `AM/platform/google/AndroidAnnotatedImageRenderer.kt`, `AM/platform/image/ImageEncoder.android.kt`.
**Tests first**: `JT/ui/annotate/AnnotationExporterTest` (existing, extended: label text, scale), `AUT/platform/google/AndroidAnnotatedImageRendererTest` (androidUnitTest, Robolectric, EXIF + OOM injection).

##### Task C.7a: Label text + resolution-scaled styling (S)
- In scope (OD-4 included, 2026-11-08; UX-29): the Drive-export offline message "Offline. Saved on this device. Try again when connected." with Retry (message only, no retry queue).
- Files: `ANN/AnnotationExporter.kt`, `JT/ui/annotate/AnnotationExporterTest.kt`
##### Task C.7b: EXIF orientation + pixel-bounded decode + `Throwable` guard in renderer/encoder (M)
- Files: `AM/platform/google/AndroidAnnotatedImageRenderer.kt`, `AM/platform/image/ImageEncoder.android.kt`, `AUT/platform/google/AndroidAnnotatedImageRendererTest.kt`
##### Task C.7c: Export failure and progress states; themed Drive icon (S) (UX-29)
- Show progress and success, "Sign in to Google Drive" for not-signed-in, "Export smaller" for encode/OOM, and a specific message plus next action for a missing file. Replace the hard-coded `0xFF1565C0` Drive icon colour with a themed token meeting 4.5:1 (text) and 3:1 (graphics) in light and dark. The offline message "Offline. Saved on this device. Try again when connected." with [Retry] is unconditional (OD-4 included; message only, no queue).
- Files: `ANN/AnnotationEditorScreen.kt` (export menu), `AM/platform/google/` export flow, `AUT/ui/annotate/AnnotationExportStatesTest.kt`

### Story C.8: Accessibility, haptics, non-drag placement (MAJOR M3/M4)
**As a** TalkBack or one-handed user, **I want** to read, select and place annotations without dragging, **so that** the editor is usable.
**Acceptance Criteria**:
- The semantic overlay is wired.
  - *Given* 3 committed annotations, *When* the semantics tree is inspected, *Then* three nodes with `contentDescription` like "Distance, 3.20 m, from point 1 to point 2" exist and are focusable with `Role.Button`.
- Commit announces and vibrates.
  - *Given* a haptic callback passed from `ScreenRouter`, *When* a measurement commits, *Then* the callback is invoked once and a live-region text "Measurement 3.20 m added" is set.
- **A non-drag placement and move mode exists (OD-1 INCLUDED 2026-11-08; UX-33, UX-34; WCAG 2.2 SC 2.5.7).** Drag-to-aim (press, drag, lift to place) stays the **default**; Crosshair mode is the alternative, reachable from the Mode toggle and offered by the L2 loupe chip (A.5c, UX-19) as "Place at crosshair". Drag-the-crosshair and a step selector are included (OD-2 included with OD-1). **Steps are screen-relative (ux.md ED-6, Repair 4)**: Fine (1 screen pixel, default), Medium (8 dp), Coarse (5% of canvas width) and Exact (1 image pixel), because 1 image pixel on a 48 MP photo is below one screen pixel and the former 1 px / 10 px / 1% steps produced no visible movement.
  - *Given* "Place at crosshair" mode with the crosshair centred at (0.5, 0.5) and the Fine step, *When* the user taps Nudge Right 10 times then Place, *Then* a point is added at x = 0.5 + 10 * s / imageWidthPx where s is the image pixels per screen pixel at the current scale; *Given* the Exact step the offset is 10 / imageWidthPx.
  - *Given* a nudge button held for 1.5 s, *Then* it auto-repeats after 400 ms at 12 steps per second with the stride doubling each second up to 8x, stops on release, and announces the position once as percentages ("Crosshair at 42 percent across, 61 percent down", rate-limited to one per 600 ms).
- **Non-drag move**: *Given* a selected distance annotation, *When* the user runs the TalkBack custom action "Move vertex 1" (or taps Move vertex 1 in the select toolbar), *Then* Crosshair mode opens with the crosshair on that vertex, the nudge pad moves it, [Apply] commits one undo entry and [Cancel move] restores the original position (UX-36, UX-39).
- **Desktop keyboard**: arrow keys nudge by the selected step, Shift+Arrow = 10 x the selected step, Enter places, Esc leaves Crosshair mode (UX-34).
- **Accessibility auto-offer (ED-6)**: *Given* touch exploration (TalkBack) or a switch-access service is enabled at editor open and the setting is Auto, *Then* the editor starts in Crosshair mode with the Crosshair hint; the user's later choice is remembered (`AccessibilityAutoCrosshairTest`).
**Files**: `ANN/AnnotationSemanticNode.kt`, `ANN/AnnotationEditorScreen.kt`, `CM/ui/ScreenRouter.kt` (lines ~413-422), `ANN/CrosshairPlacementState.kt` (new, pure commonMain), `ANN/PlaceAtCrosshairControls.kt` (new), `ANN/CrosshairKeyHandler.kt` (new).
**Tests first**: `AUT/ui/annotate/AnnotationSemanticsTest`, `AUT/ui/annotate/PlaceAtCrosshairControlsTest`, `AUT/ui/annotate/MoveVertexActionsTest` (androidUnitTest), `BT/ui/annotate/CrosshairPlacementTest`, `BT/ui/annotate/CrosshairMoveVertexTest`, `BT/ui/annotate/CrosshairKeyHandlerTest` (businessTest).

##### Task C.8a: Wire `AnnotationSemanticNode` overlay + commit announcement (S)
- Select and Delete custom actions (UX-39); the "Move vertex 1/2" actions are wired to the C.8f move mode (OD-4 included).
- Files: `ANN/AnnotationSemanticNode.kt`, `ANN/AnnotationEditorScreen.kt`, `AUT/ui/annotate/AnnotationSemanticsTest.kt`
##### Task C.8b: Haptic wiring (XS)
- Files: `CM/ui/ScreenRouter.kt`, `AUT/ui/annotate/HapticWiringTest.kt`
##### Task C.8c: Crosshair + nudge placement mode, step selector, drag-the-crosshair (M)
- Unconditional (OD-1 and OD-2 included). Crosshair/nudge logic lives in `CrosshairPlacementState` (pure, commonMain, one field in the VM, no crosshair logic in `AnnotationEditorViewModel`); `CrosshairPlacementTest` tests the state class directly. Includes dragging the crosshair itself, tap-on-canvas moving the crosshair (no point placed; see C.9f), and the Crosshair-mode canvas gestures of ED-19 (hold or drag on empty canvas or on the crosshair is a relative drag, lift never places, no cancel zone; `CrosshairPlacementTest` cases), clamping with a one-time "Left edge" announcement, and the "Choose a measuring tool first" disabled reason. The step selector values (`NudgeStep`), auto-repeat, D-pad layout, position announcements, hints and the accessibility auto-offer are C.8i.
- Files: `ANN/CrosshairPlacementState.kt`, `ANN/PlaceAtCrosshairControls.kt`, `ANN/AnnotationEditorViewModel.kt` (one field), `BT/ui/annotate/CrosshairPlacementTest.kt`, `AUT/ui/annotate/PlaceAtCrosshairControlsTest.kt`
##### Task C.8f: Non-drag vertex move (crosshair on a selected vertex) and "Move vertex N" TalkBack actions (M)
- Reuses `CrosshairPlacementState` with a `MoveTarget(annotationId, vertexIndex)`; [Apply] commits one `UndoHistory` entry, [Cancel move] restores. Registers custom semantics actions on each annotation node per ED-23: "Select next vertex", "Select previous vertex", "Move selected vertex (vertex i of N)", and direct "Move vertex 1" ... "Move vertex N" only when N <= 6 (`MoveVertexActionsTest` for N = 2, 4, 6, 12). Depends on C.8a, C.8c, C.8e.
- Files: `ANN/CrosshairPlacementState.kt`, `ANN/AnnotationSemanticNode.kt`, `ANN/AnnotationEditorScreen.kt`, `BT/ui/annotate/CrosshairMoveVertexTest.kt`, `AUT/ui/annotate/MoveVertexActionsTest.kt`
##### Task C.8g: Desktop keyboard nudge, place and leave (S) (UX-34)
- Arrow keys nudge by the selected step, Shift+Arrow by 10 x the selected step, Enter places, Esc leaves Crosshair mode; pure key-to-intent mapping in commonMain, wired in the desktop shell, with a focus-visible ring on the focused control (ED-12).
- Files: `ANN/CrosshairKeyHandler.kt`, `ANN/AnnotationEditorScreen.kt`, `BT/ui/annotate/CrosshairKeyHandlerTest.kt`
##### Task C.8e: Select handles and the canvas hit-resolution rule (M) (UX-36)
- Selected annotation vertices get >= 48 dp nominal touch areas and a selected style that is not colour-only (ring plus handle shape). Hit resolution is the pure `HandleHitResolver` (ux.md ED-1): a vertex handle beats any line body; among vertices within the 24 dp radius the nearest wins; a tie within 1 dp goes to the selected annotation's vertex, then to the last placed; beyond 24 dp from every vertex a touch selects the nearest line and does not drag. **Audited surface (B-UX-2)**: `EditorTouchTargetAuditTest` (C.9h) audits controls only; canvas hit areas are tested by `HandleHitResolverTest` (property: any two vertices and any touch point resolve to at most one handle, nearest wins, deterministic ties) and `CanvasHandleEquivalentPathTest` (for any annotation whose vertices are closer than 48 dp, the Move vertex 1/2 actions and 48 dp toolbar buttons exist); the exemption rests on WCAG 2.2 SC 2.5.8's equivalent-control exception. **Split in Repair 4** (the task previously also carried delete-with-Undo, six files): Delete and the snackbar are C.8h.
- Files: `ANN/HandleHitResolver.kt` (new, pure), `ANN/InProgressAnnotationCanvas.kt` (select handles), `CT/ui/annotate/HandleHitResolverTest.kt`, `AUT/ui/annotate/SelectHandlesTest.kt`, `AUT/ui/annotate/CanvasHandleEquivalentPathTest.kt`
##### Task C.8h: Delete with Undo and the editor snackbar queue (S) (UX-37, UX-71)
- Delete and Undo are visible whenever something is selected; Delete shows an Undo snackbar and Undo restores the annotation; deletes inside one window coalesce into "Deleted 2 measurements [Undo]". `EditorSnackbarQueue` (pure, ED-14): one snackbar at a time; informational ones replace each other; an action-bearing one is never replaced and later ones queue (max 3, oldest informational dropped first); dismissal by swipe, Esc or the TalkBack Dismiss custom action; positioned above the toolbar and D-pad. Uses the C.9i timeout helper.
- Files: `ANN/EditorSnackbarQueue.kt` (new), `ANN/AnnotationEditorViewModel.kt` (delete-undo entry via `UndoHistory`), `ANN/AnnotationEditorScreen.kt`, `AUT/ui/annotate/DeleteUndoSnackbarTest.kt`, `BT/ui/annotate/EditorSnackbarQueueTest.kt`
##### Task C.8d: Record owner decisions OD-1..OD-4 as DECIDED and propagate (XS, done in Repair 3)
- OD-1 (non-drag placement/move mode) was INCLUDED by the owner on 2026-11-08; because the instruction made the dependent stories unconditional (UX-33/34/36/38/39/29), OD-2 (step selector, drag-the-crosshair), OD-3 (off-canvas release cancels placement) and OD-4 (Move vertex TalkBack actions, Drive offline message) are recorded as included by the same decision. Drag-to-aim stays the default placement gesture. Recorded in `design/ux.md` (Open design questions) and the plan's Owner Decisions; C.8c, C.8f, C.8g, C.9a and the C.7a/C.7c offline copy are unconditional.
- Files: `project_plans/annotation-memory-and-nav-ia/design/ux.md`, `project_plans/annotation-memory-and-nav-ia/implementation/plan.md`

##### Task C.8i: Crosshair ergonomics: `NudgeStep`, auto-repeat, D-pad, announcements, hints, accessibility auto-offer (S) (UX-33, UX-63, UX-64, UX-65)
- Pure `NudgeStep` (Fine / Medium / Coarse / Exact with conversion to image pixels, never less than 1 image pixel); press-and-hold auto-repeat (400 ms delay, 12 per second, stride doubling each second up to 8x); plus-shaped D-pad (cells >= 56 dp, centre = Place point) placed on the magnifier-side setting's side, left/right not mirrored in RTL; rate-limited position announcements and edge announcements; the Crosshair first-use hint (`annotationHintVersion`, shown once per version including to existing users, polite announcement, dismissal persisted, re-readable from editor overflow > Help: placing points); start in Crosshair mode when TalkBack or switch access is enabled (setting Auto/Always/Never, ED-6).
- Files: `ANN/NudgeStep.kt` (new, pure), `ANN/PlaceAtCrosshairControls.kt`, `ANN/CrosshairPlacementState.kt`, `BT/ui/annotate/NudgeStepTest.kt`, `AUT/ui/annotate/AccessibilityAutoCrosshairTest.kt`

### Story C.9: Loupe edges, double decode, and minor findings (MAJOR M6, minors m3-m7)
**As a** user, **I want** small paper cuts gone, **so that** the editor feels finished.
**Acceptance Criteria**:
- Loupe near an edge shows a filled background, flips side near left/right edges, and has a placeholder on first press.
  - *Given* a press at x = 2 dp from the left edge, *When* the loupe renders, *Then* its circle has no transparent region and the crosshair remains under the touch point.
- Main image decodes once.
  - *Given* a cold open, *When* `CoilDecodeStats` is read after the aspect ratio settles, *Then* main-image decode count = 1 (baseline 2, features.md m4).
- Orphan-asset "Annotate" is disabled with a stated reason (ED-8, Repair 4; supersedes the enabled-plus-snackbar wording of 2026-11-08, owner may restore it).
  - *Given* an asset with no `pageUuids` (no annotation is created for an orphan; `ImageAnnotation` is anchored to a block/page), *When* the Library card overflow, the detail screen or the Annotate flow renders its Annotate control, *Then* the control is disabled, a visible reason line "Add this file to a page to annotate it" is next to it and an [Open pages] button is present; the editor never opens and no annotation row is created (a disabled control with a visible reason, not a tap that fails and relocates).
- Short-line placement gives feedback.
  - *Given* two points 0.2% of width apart, *When* committed, *Then* a transient "Points too close" hint appears and no annotation is created.
- State writes use atomic `update`.
  - *Given* concurrent `addPoint` and initial-load writes under `TestDispatcher`, *When* both run, *Then* neither's change is lost (property: final state contains both).
- **Tap versus drag semantics (decision 2026-11-08, revised by ED-3 in Repair 4; task C.9f).** Placement tool, Drag mode (the default; Crosshair mode is in C.8c), **touch input**, two thresholds only (8 dp slop, 120 ms) so every press has exactly one outcome and the former 120-300 ms band is gone:
  - *Given* a press released before 120 ms with movement <= 8 dp (a quick tap), *Then* the Crosshair moves to the contact and the editor enters Crosshair mode with the one-time hint; **no point is placed from the raw fingertip and the loupe is not shown**. *Given* setting Quick tap = Place point, *Then* one point is placed at the contact plus the Fingertip offset (0 to 48 dp, default 0).
  - *Given* a press held >= 120 ms (any duration, no upper limit) or moved beyond 8 dp, *Then* the loupe shows (at 120 ms or when the slop is exceeded, whichever is first) and **lift places** the point at the aim position.
  - *Given* the finger leaves the canvas by more than the 12 dp edge tolerance and lifts, *Then* the press **cancels** with no point (OD-3 included); *Given* it lifts inside the tolerance, *Then* the point is clamped to the image edge and placed (UX-31). While outside the tolerance the loupe dims to 40%, shows "Release to cancel" with an X icon, fires one haptic on entry and re-entry and announces politely once (task C.9j, ED-4). The first-use hint reads "Press and hold, drag to aim, lift to place. Drag onto the toolbar or off the image to cancel. A quick tap switches to Crosshair mode."
  - *Given* a second pointer lands during a press, or a system gesture cancels it, *Then* the press is discarded with no point.
  - **Mouse and stylus (ED-3b, task C.9j)**: *Given* a mouse or stylus click, *Then* one point is placed at the pointer position at once; a drag beyond 8 dp shows the loupe and release places; Esc cancels a drag; hover shows the aim dot and never the loupe.
  - *Given* Select tool, *When* the user taps, *Then* it selects the nearest annotation within 24 dp, never places a point; *Given* Crosshair mode, *When* the user taps the canvas, *Then* the crosshair moves there and **no point is placed**.
- **Accidental placement can be cleared beyond Undo, discoverably (task C.9f).**
  - *Given* >= 1 unfinished point, *When* the toolbar renders, *Then* a visible, labelled [Clear points] button (>= 48 dp, text plus icon, not colour-only) discards all unfinished points without touching committed annotations; *Given* none, *Then* it is absent. Undo still removes the last unfinished point, then the last committed annotation. Vocabulary (ED-2): no control is a bare "Cancel" or "Done"; the names are Clear points, Cancel move, Cancel export, Finish, Deselect, Close. The first-use hint names both ("Undo removes the last point. Clear points removes unfinished points.").
- **The editor open / main-image loading state is specified (task C.9g).**
  - *Given* a cold open, *When* the main image is still loading, *Then* the canvas shows a progress indicator with "Opening photo..." (polite live region once), the toolbar is visible, **placement and loupe are disabled** until `NativeImageSize` is known (a tap shows the hint "Photo is still opening"), and Back/Close works at all times.
  - *Given* the image is cached in the shared Coil memory cache, *Then* it appears as a placeholder immediately and the spinner is skipped.
  - *Given* the load takes longer than 3 s, *Then* the text becomes "Still opening... large photos can take a moment" (polite, once) with a visible [Back to Library]; *Given* it fails to decode or exceeds the 15 s ceiling (fake clock), *Then* the full-canvas error "Couldn't open this image." with [Retry] and [Back to Library] replaces the spinner (S8 error row); the error is logged through `Logger` with dimensions only (ED-13; replaces the 8 s timeout).
  - *Given* Retry is pressed while an earlier decode is still running, *Then* the earlier job is cancelled, the attempt carries a new generation number, and a late success or failure from an older generation is discarded: it never replaces a newer state and never shows an error after a success (`EditorLoadGenerationTest`).
- **Accessibility test plan (tasks C.9h, C.9i; UX-40, UX-41, UX-07, UX-39).**
  - *Focus order*: *Given* the editor in each of its states (idle, in-progress points, selection, Crosshair mode, error banner, degradation chip), *When* the semantics traversal order is read, *Then* it is: top-bar title, banners and chip, canvas annotation nodes in creation order, toolbar, mode toggle, nudge pad (`EditorFocusOrderTest`).
  - *Targets (B-UX-2, ED-1)*: the **audited surface is controls**: toolbar buttons, mode toggle, step selector, D-pad, [Place point], [Clear points], [Finish], [Deselect], [Apply] / [Cancel move], chips and their dismiss controls, banner, dialog and snackbar actions, overflow items; each has a >= 48 dp touch area and 8 dp between neighbours (`EditorTouchTargetAuditTest`). **Canvas hit areas (vertex handles, line bodies) are not audited as controls**; they follow the nearest-wins and handle-priority rule of `HandleHitResolver` (C.8e) with the non-drag Move vertex path as the equivalent alternative (WCAG 2.2 SC 2.5.8 equivalent-control exception, cited from the spec text, not re-fetched).
  - *Keyboard (ED-12)*: a focus-visible ring on keyboard focus for chips, buttons and the D-pad, labelled chip groups, Tab order equal to visual order, Esc leaves modes (`EditorKeyboardFocusTest`).
  - *Contrast and motion*: tokens meet 4.5:1 / 3:1 in light and dark (`ThemeContrastTest`, C.9d); with the reduced-motion setting on, loupe appearance, chips, skeleton and sheets do not animate (`ReducedMotionTest`).
  - *Live regions*: commit, degradation, save and point-count messages are `LiveRegionMode.Polite`; only the fatal-error banner is `Assertive` (`LiveRegionPolitenessTest`).
  - *Accessibility timeouts*: *Given* `AccessibilityManager.getRecommendedTimeoutMillis` exceeds the nominal duration (this includes the Android "Time to take action" setting with no screen reader), *When* a timed message appears (the Delete-Undo snackbar, the L1 low-memory chip, the "Points too close" hint), *Then* it persists at least the recommended time (or until dismissed when that is unavailable); *Given* a touch-exploration or switch service is enabled, *Then* a message that carries an action never auto-dismisses (`TimedMessageAccessibilityTest`, fake `AccessibilityManager`). Snackbar stacking and dismissal follow ED-14 (`EditorSnackbarQueueTest`, C.8h); the save-failed banner is persistent, carries an action and is announced assertively once. Manual TalkBack pass on the owner's phone is listed under device evidence in validation.md.
- Handedness (decided): the magnifier-side setting (Right default, Left mirrors the offset) lives in **Settings > Annotation > "Magnifier side"** in the existing settings store, with a shortcut item in the editor overflow menu that opens the same setting; it is not a system setting. (C.9a) The Settings > Annotation screen also holds Quick tap, Fingertip offset and Start in Crosshair mode, and the editor overflow also holds Help: placing points and Annotation settings (task C.9l, ux.md S19).
- **Configuration change (ED-7, task C.9k)**: *Given* unfinished points, a crosshair position, Crosshair mode, a selected annotation, an open unsaved-changes dialog and the details sheet, *When* the device rotates, folds or unfolds, *Then* all of them are retained (the crosshair stays on the same image point, stored as normalized coordinates), the loupe is re-clamped, and a press that was in flight is discarded without a point (`EditorConfigChangeTest`; Pixel_Fold pass recorded as device evidence).
- **Hints for existing users (ED-5)**: *Given* an existing install on its first editor open after the update, *Then* the placement hint (and, on first Crosshair use, the Crosshair hint) is shown once per `annotationHintVersion`, announced politely, with dismissal persisted and the hint re-readable from editor overflow > Help: placing points.
**Files**: `ANN/LoupeOverlay.kt`, `ANN/AnnotationEditorScreen.kt`, `ANN/AnnotationEditorViewModel.kt`, `ANN/PlacementGesture.kt` (new, pure), `CM/ui/assets/AssetDetailViewModel.kt`, `CM/ui/ScreenRouter.kt`.
**Tests first**: `AUT/ui/annotate/LoupeEdgeTest`, `CT/ui/annotate/PlacementGestureClassifierTest` (property: every press maps to exactly one outcome), `CT/ui/annotate/PointerTypeGestureTest`, `AUT/ui/annotate/CancelZoneFeedbackTest`, `AUT/ui/annotate/InProgressCancelTest` (class name kept; the control is [Clear points]), `AUT/ui/annotate/EditorLoadingStateTest`, `BT/ui/annotate/EditorLoadGenerationTest`, `AUT/ui/annotate/EditorConfigChangeTest`, `AUT/ui/annotate/EditorKeyboardFocusTest`, `AUT/ui/annotate/AnnotationSettingsTest`, `AUT/ui/annotate/EditorOverflowMenuTest`, `AUT/ui/annotate/EditorFocusOrderTest`, `EditorTouchTargetAuditTest`, `ReducedMotionTest`, `LiveRegionPolitenessTest`, `TimedMessageAccessibilityTest`, `BT/ui/annotate/EditorStateAtomicityTest`, `BT/ui/assets/AssetDetailAnnotateOrphanTest`, `AIT/performance/annotation/AnnotationLifecycleResourceTest` (decode count).

##### Task C.9a: Loupe edge/placeholder/handedness (S)
- Prerequisites: A.4a merged, A.3f done (shares the loupe code with PR #560, or stacked on it). Includes off-canvas release cancels placement with the 12 dp edge tolerance (OD-3 included, UX-38; the in-gesture feedback is C.9j), the handedness setting (its settings screen is C.9l) and the placement first-use hint copy shown once per hint version including to existing users (ED-5).
- Files: `ANN/LoupeOverlay.kt`, `CM/ui/settings/` annotation setting entry (existing settings store), `AUT/ui/annotate/LoupeEdgeTest.kt`
##### Task C.9b: Single main-image decode (aspect before request) (S)
- Files: `ANN/AnnotationEditorScreen.kt`
##### Task C.9c: Orphan-asset Annotate disabled with a reason (ED-8), short-line hint, atomic `update` (S)
- Files: `CM/ui/assets/AssetDetailViewModel.kt`, `CM/ui/ScreenRouter.kt`, `ANN/AnnotationEditorViewModel.kt`, `BT/ui/annotate/EditorStateAtomicityTest.kt`
##### Task C.9d: Contrast tokens and reduced motion for all new UI (S) (UX-41)
- **Scope cut in Repair 3 (the old task named about ten composables)**: C.9d now only defines the themed tokens (chips, banners, sheet, badges, handles) meeting 4.5:1 (text) and 3:1 (graphics) in light and dark, a `rememberReducedMotion()` helper over an injectable `ReducedMotionProvider` (Android: `Settings.Global.ANIMATOR_DURATION_SCALE == 0`, to be verified on the Pixel_Fold; other platforms: Settings > Appearance > Reduce motion, default off; ED-15), and `ThemeContrastTest` (including the "Release to cancel" label on the 40% scrim, dimmed Library chips and their reason text, the "Can't preview" glyph and the New badge: ED-27). **Applying** the tokens and the helper is the ownership rule of each surface task: A.0b/A.0c, A.5c, C.6b, C.7c, C.8e, C.9f, C.9g, D.3, D.4, D.6, D.9 must use the tokens, honour reduced motion, and include a contrast assertion in their own test. C.9d lands before those tasks.
- Files: `CM/ui/theme/` tokens, `CM/ui/theme/ReducedMotion.kt` (new), `AUT/ui/ThemeContrastTest.kt`
##### Task C.9f: Tap versus drag semantics, in-progress Cancel button, first-use hint (S) (UX-35, UX-38)
- Pure `PlacementGesture` classifier in commonMain (QuickTap, DragAim, Cancel, MultiPointerCancel) with the two thresholds above (8 dp, 120 ms); the canvas delegates to it. A quick tap that enters Crosshair mode switches the mode toggle visibly and announces the switch (ED-18, `QuickTapModeSwitchTest`); with Quick tap = Place point there is no mode change. [Clear points] toolbar button for unfinished points, and the toolbar vocabulary Finish / Deselect / Close (no bare Cancel or Done). Depends on A.4a (loupe extraction) and A.3f. Mouse/stylus and the cancel-zone feedback are C.9j.
- Files: `ANN/PlacementGesture.kt`, `ANN/InProgressAnnotationCanvas.kt`, `ANN/AnnotationEditorScreen.kt`, `CT/ui/annotate/PlacementGestureClassifierTest.kt`, `AUT/ui/annotate/InProgressCancelTest.kt`
##### Task C.9g: Editor open and main-image loading state (S) (UX-48)
- Loading, cached-placeholder, disabled-input, "Still opening..." at 3 s, 15 s ceiling and error states per the AC; generation-numbered decodes so Retry cancels the previous job and stale results are discarded (ED-13); the load state lives in `EditorState` (state only in the VM). Combines with C.9b (single decode).
- Files: `ANN/AnnotationEditorScreen.kt`, `ANN/AnnotationEditorViewModel.kt` (state only), `AUT/ui/annotate/EditorLoadingStateTest.kt`, `BT/ui/annotate/EditorLoadGenerationTest.kt`
##### Task C.9h: Editor accessibility tests: focus order, 48 dp targets, live-region politeness (S) (UX-40)
- Tests first plus the fixes they find: `EditorFocusOrderTest`, `EditorTouchTargetAuditTest`, `LiveRegionPolitenessTest`; sets `traversalIndex` / `isTraversalGroup` and `liveRegion` where missing; the traversal states include `banner` (before the canvas) and `snackbar` (traversed last, polite live region; ED-25).
- Files: `ANN/AnnotationEditorScreen.kt`, `AUT/ui/annotate/EditorFocusOrderTest.kt`, `AUT/ui/annotate/EditorTouchTargetAuditTest.kt`, `AUT/ui/annotate/LiveRegionPolitenessTest.kt`
##### Task C.9i: Accessibility-aware timeouts and reduced-motion test (S) (UX-41)
- A `rememberA11yTimeout()` helper (commonMain interface, Android implementation over `AccessibilityManager.getRecommendedTimeoutMillis` with the content flags, applied whenever it exceeds the nominal time, with or without a screen reader) used by the Delete-Undo snackbar, the L1 chip and the "Points too close" hint; `TimedMessageAccessibilityTest` and `ReducedMotionTest`. Messages that carry an action never auto-dismiss under an accessibility service.
- Files: `CM/ui/theme/A11yTimeout.kt`, `AM/ui/A11yTimeout.android.kt`, `AUT/ui/annotate/TimedMessageAccessibilityTest.kt`, `AUT/ui/annotate/ReducedMotionTest.kt`
##### Task C.9e: Restore tool and selection after process death (S) (minor m8)
- `remember(imageAnnotationUuid)` ViewModel state resets on process death; persist the current tool and selection (not in-progress points, which ADR-005 says are never restored) via saved state; when unfinished points existed at death, show the one-time note of ED-26 ("Unfinished points weren't kept after the app restarted. Placed measurements are saved."; `EditorProcessDeathRestoreTest`). Committed annotations already survive.
- Files: `ANN/AnnotationEditorViewModel.kt` (saved-state hook), `CM/ui/ScreenRouter.kt`, `BT/ui/annotate/EditorStateAtomicityTest.kt`, `AUT/ui/annotate/EditorProcessDeathRestoreTest.kt`

##### Task C.9j: Mouse/stylus semantics and in-gesture cancel feedback (S) (UX-57, UX-66)
- Pointer-type-aware classification (`PointerClick` for mouse and stylus: place at the pointer at once, drag beyond 8 dp shows the loupe, release places, Esc cancels, hover shows the aim dot only; ED-3b) and the cancel-zone feedback (cancel boundary = canvas + 12 dp tolerance, measured anywhere in the window so the toolbar region also cancels, and a press starting inside the system-gesture inset places nothing, with `systemGestureExclusion` on the side strips during a press: ED-20, `PlacementCancelZoneTest`, `SystemGestureInsetPressTest`; loupe dims to 40%, "Release to cancel" with an X icon, one haptic on entry and re-entry, one polite announcement per entry, instant dim under reduced motion; ED-4).
- Files: `ANN/PlacementGesture.kt`, `ANN/LoupeOverlay.kt`, `ANN/CancelZoneFeedback.kt` (new), `CT/ui/annotate/PointerTypeGestureTest.kt`, `AUT/ui/annotate/CancelZoneFeedbackTest.kt`
##### Task C.9k: Editor retains state across rotation, fold and unfold (S) (UX-61)
- Keep unfinished points, crosshair (normalized image coordinates), mode, tool, step, selection and open dialogs/sheets across configuration change via the ViewModel plus `rememberSaveable` for UI-only state; re-clamp the loupe; discard an in-flight press; a pending Delete-Undo snackbar keeps its remaining time. Pixel_Fold fold/unfold pass recorded in validation.md device evidence.
- Files: `ANN/AnnotationEditorViewModel.kt` (state only), `ANN/AnnotationEditorScreen.kt`, `AUT/ui/annotate/EditorConfigChangeTest.kt`
##### Task C.9l: Settings > Annotation screen and the editor overflow menu (S) (UX-55, UX-69)
- Settings > Annotation: Magnifier side, Quick tap (Move crosshair / Place point), Fingertip offset (0-48 dp, step 4, disabled with a reason unless Quick tap = Place point), Start in Crosshair mode (Auto / Always / Never); persisted in the existing settings store; a persistence failure keeps the in-session value and never blocks. Editor overflow ("More options", 48 dp): Help: placing points (re-reads the placement and Crosshair hints), Magnifier side, Annotation settings, Recalibrate (legacy badge only). Items >= 48 dp, Esc/Back closes and returns focus.
- Files: `CM/ui/settings/AnnotationSettings.kt` (new), `ANN/EditorOverflowMenu.kt` (new), `AUT/ui/annotate/AnnotationSettingsTest.kt`, `AUT/ui/annotate/EditorOverflowMenuTest.kt`
##### Task C.9m: Crosshair layout fallback and editor Back precedence (S) (UX-76, UX-79)
- Layout rules of ED-21 as a pure `CrosshairLayoutPolicy` (canvas >= 200 dp and 40% of the window height; bottom cluster, then a side rail on a landscape phone, then the [Nudge pad] sheet at font scale >= 1.5 or when the canvas rule cannot hold) and the Back/Esc precedence of ED-24 as a pure reducer (overflow menu, dialog or sheet, active press, non-drag move, selection, unfinished points via the S10 dialog, queued save, leave). Tests first: `CrosshairLayoutTest` at 640x360 landscape, 411x891 at font scale 2.0 and 673 dp; `CT/ui/annotate/EditorBackPrecedenceTest` (property: Back never leaves the editor while a layer is open).
- Files: `ANN/CrosshairLayoutPolicy.kt` (new, pure), `ANN/EditorBackHandler.kt` (new, pure reducer), `ANN/AnnotationEditorScreen.kt`, `AUT/ui/annotate/CrosshairLayoutTest.kt`, `CT/ui/annotate/EditorBackPrecedenceTest.kt`

### Story C.10: Remaining test-coverage gaps (features.md section 5)
**As a** maintainer, **I want** every listed gap to have a named test, **so that** the review's findings stay fixed.
**Acceptance Criteria**: one test per gap, each failing before its fix lands (red-first noted in the PR).
  - *Given* gap 1 (undo/inProgress semantics), *When* `UndoHistoryTest` runs on the pre-C.3 code, *Then* it fails; gap 2 -> `AnnotationPersistenceQueueTest`; gap 3 -> `LoupeNarrowCanvasTest`; gap 4 -> B.3 scenarios; gap 5 -> `CalibrationDecodeInvarianceTest`; gap 6 -> exporter tests (C.7); gap 7 -> `AssetDetailAnnotateOrphanTest` and a camera-capture -> editor `androidApp` test; gap 8 -> `InProgressAnnotationCanvasGestureTest` extension for the pointer-cancel `finally` path plus an AREA close-tolerance property test; gap 9 -> `AnnotationSemanticsTest`; gap 10 -> B.6.
**Files**: `AUT/ui/annotate/InProgressAnnotationCanvasGestureTest.kt` (extend), `CT/ui/annotate/AreaCloseTolerancePropertyTest.kt`, `androidApp/src/androidTest/kotlin/dev/stapler/stelekit/CaptureToEditorTest.kt`.

##### Task C.10a: Pointer-cancel path test + AREA close-tolerance property test (S)
- Files: `AUT/ui/annotate/InProgressAnnotationCanvasGestureTest.kt`, `CT/ui/annotate/AreaCloseTolerancePropertyTest.kt`
##### Task C.10b: Camera-capture -> editor smoke test (S)
- Files: `androidApp/src/androidTest/kotlin/dev/stapler/stelekit/CaptureToEditorTest.kt`

---

# Phase 3: Navigation information architecture (Epic D, independent)

## Epic D: One Library destination (ADR-006 DECIDED 2026-11-08: merge Assets and Gallery)

**Goal**: Assets and Gallery become **one destination, working title "Library"**, with filters (Annotated, media type, Orphaned, tag, search) and sort, so a reader of the nav alone knows where photos, annotated photos and file attachments live. Destinations sit in labelled sections with descriptions on desktop and phone, and existing users are told once. Shippable separately from A-C. Path abbreviation for this epic: `LIB` = `CM/ui/library`.

**Design decisions made in Repair 3 (recorded in the ADR-006 Decision, ADR-007 and ADR-008 amendments; the owner chose "merge", the rest follows from it)**:

1. **Route compatibility (decided)**: one nav destination `Screen.Library(initialSearch: String? = null, initialFilter: LibraryFilter? = null)`. `Screen.Gallery` (`CM/ui/AppState.kt:79`) and `Screen.AssetBrowser` (`:83`) stay in the sealed hierarchy for one release as **redirect aliases**, classified `NON_NAV_REDIRECT` in `NavDestinationSpec.forScreen`. **`NON_NAV_REDIRECT` is new work, not an existing classification**: `rg NON_NAV_REDIRECT kmp/src` returns no match today (checked 2026-11-08) and `AppState.kt` has no such notion; D.2a defines the type and D.11a wires the redirect. `StelekitViewModel.navigateTo` rewrites `Gallery` to `Library(initialFilter = Annotated)` and `AssetBrowser(q)` to `Library(initialSearch = q)` before touching the back stack, so no back-stack entry holds an alias and every persisted, restored or deep-linked old value still resolves. `Screen.AssetDetail(uuid)` is unchanged. Removing the alias subtypes is later cleanup, recorded as debt, not part of this project.
2. **Identifiers**: the new tracing key is `Library`; the old keys `Gallery` and `AssetBrowser` are mapped by `LegacyScreenAlias` and asserted by D.8c. `DemoGraphCoverageTest`'s help-exempt list (`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/docs/DemoGraphCoverageTest.kt:90`) gains `Library` and keeps the two aliases.
3. **Data and the `annotated` join (ADR-008)**: one `LibraryViewModel` replaces `GalleryViewModel` and `AssetBrowserViewModel`. Reads are one paged repository query over the asset index with the annotation state joined at query level (an `EXISTS` / aggregate sub-select against an index on the annotation image key), never a preload, never an in-memory join, never a per-item lookup. If spike D.7a-0 finds `image_annotations` rows with no asset row (captured photos not indexed), a `UNION ALL` branch includes them so no Gallery content disappears.
4. **Tags**: the tag filter is the annotation tag set (`getDistinctAnnotationTags()`, bounded projection). Assets carry no tags in v1; an asset with no annotation never matches a tag filter. Further unification is not planned (answers research/ux.md open question 2 as "not unified").
5. **Sort**: Recently added (default), Recently annotated, Most measurements, Name A-Z. Gallery's `GallerySortOrder` values map onto these; every sort has an index (D.7c). **Defaults (ux.md LD-1..LD-20, ADR-008 Defaults 1-9; final in Repair 5)**: unannotated items sort last for the two annotation sorts; paging is keyset on a per-sort non-null key tuple that ends in the item id (LD-2), not OFFSET; Type chips are multi-select (OR, membership table LD-11) with Annotated/Orphaned/tags as separate AND conditions; non-image Type chips and Annotated/tag chips dim each other with a reason, while Annotated with Orphaned is allowed (LD-1, LD-16); search tokenises on whitespace and separators and requires every token to be a word-prefix match in the file name, an annotation tag or an annotation label, at least 2 characters (LD-3, LD-7: one definition, one SQL shape); filters are session state and the sort order is a persisted preference.
6. **"Import from Gallery" stub (decided: delete)**: `CM/ui/gallery/GalleryScreen.kt:124` passes `onImportPhoto = null` and `GalleryEmptyState.kt:34-91` renders the button only when non-null, so it has never been visible. It is removed. Library's **Add** menu offers Capture photo, Attach file and, on Android only, "Choose photo to annotate" (reusing the D.5 picker callback); desktop and wasm omit that item with a documented reason. Every added file is anchored to the page last viewed (or today's journal), so an Add never creates an orphan (LD-13); the result under active filters is LD-12.
7. **Card tap rule (decided)**: tapping an **annotated image** card opens the annotation editor (Gallery parity: the common job is "see my measurements"); tapping any other card opens the asset detail. The Annotated badge is what tells the user which behaviour to expect; an overflow action "Details" is always available. Orphan items (no page) show Annotate **disabled with the reason line and [Open pages]** (ED-8; there is no snackbar); an annotated orphan keeps [Open annotation] (LD-16).
8. **Nav section (decided in Repair 3, revised in Repair 4 as an agent default, ux.md ED-16)**: the destination is the **first row of the existing Graph section** (Library, Graph Stats, Git Setup). Repair 3's one-item "Media" section is dropped: a section whose only row repeats its header is redundant chrome. ADR-007 sections are Daily, Connections, Graph, Developer. Row: "Library - Every photo and file on your pages" (7 words). I18n keys, so the working title and the header name are each one edit; fallback header "Files and graph" if cold-user sessions (D.9e) miss it. The sidebar row for `Screen.LibraryStats` (`Sidebar.kt:346`, still labelled "Library Stats") becomes "Graph Stats" (D.3b, `NavLabelUniquenessTest`).
9. **Short bottom-bar label (decided)**: `NavDestinationSpec` carries an optional `shortLabelKey`. The bottom bar shows **"Pages"**, the sidebar and sheet row show **"All pages"**; both resolve to the same destination. The bar needs four short labels that fit at 200% font scale; the row has room for the descriptive label.
10. **Developer section layout (decided)**: the Developer section is appended **last** (below Graph and Favorites) and appears only while on Logs or Performance, so showing or hiding it never shifts any row above it; the sheet and sidebar do not animate the insertion when reduced motion is on. Asserted by a bounds-stability test (D.3a).

### Story D.1: Decision recorded (ADR-006 DECIDED, owner, 2026-11-08)
**As the** owner, **I want** my merge decision recorded with exact strings, **so that** the implementation matches how I think about my photos and files.
**Acceptance Criteria**:
- ADR-006 `Status` is Accepted/DECIDED (2026-11-08) and lists the working title and the exact I18n strings. Options A and C are marked **rejected** (history kept).
  - *Given* the ADR file, *When* read, *Then* Decision names "merge into one Library", lists filters (Annotated, media type, Orphaned, tag, search) and sort, and every earlier option carries the word "rejected".
- The title "Library" stays a working title: a rename is one I18n edit and does not reopen the decision.
**Files**: `decisions/ADR-006-nav-ia-decision-gate.md`.

##### Task D.1a: Confirm the working title and the I18n strings (XS)
- Non-blocking: proceeds with "Library" unless the owner supplies another title. Strings: label, short label, description ("Every photo and file on your pages"), filter chip labels, empty-state copy, upgrade-notice copy.
- Files: `decisions/ADR-006-nav-ia-decision-gate.md`
##### Task D.1b: Record the decision in ADR-006 (XS, done in Repair 3)
- The ADR carries the Decision, the alternatives marked rejected and the consequences. Re-open only if the owner changes the decision.
- Files: `decisions/ADR-006-nav-ia-decision-gate.md`

### Story D.2: Shared `NavSection` model (ADR-007)
**As a** developer, **I want** one nav data model, **so that** sidebar, phone sheet and palette cannot drift.
**Acceptance Criteria**:
- Every `Screen` has exactly one `NavDestinationSpec`, an explicit `NON_NAV` entry, or a `NON_NAV_REDIRECT` entry (the Gallery and AssetBrowser aliases; **`NON_NAV_REDIRECT` is new work introduced by D.2a**, it does not exist in `AppState.kt` or elsewhere today).
  - *Given* the sealed `Screen` hierarchy (`CM/ui/AppState.kt:37-104`), *When* `NavDestinationSpec.forScreen(screen)` is a commonMain `when` over `Screen` with **no `else` branch**, *Then* adding a subtype without a spec, `NON_NAV` or `NON_NAV_REDIRECT` fails compilation (compiler-exhaustive replaces `KClass.sealedSubclasses` reflection, which is JVM-only and cannot live in commonTest); `BT/ui/nav/NavModelExhaustivenessTest` (businessTest, JVM) additionally uses reflection to assert every sealed subtype is classified once.
- Labels come from I18n keys, never string literals in composables.
  - *Given* `NavDestinationSpec(screen = Screen.Library(), labelKey = I18n.NAV_LIBRARY, descriptionKey = I18n.NAV_LIBRARY_DESC)`, *When* rendered, *Then* the text equals `I18n.get(labelKey)`.
- Short labels: *Given* the All pages spec with `shortLabelKey = I18n.NAV_PAGES_SHORT`, *When* the bottom bar renders, *Then* it shows "Pages", and the sidebar and sheet show "All pages" for the same `Screen`.
- Labels are unique and the old name is gone (Repair 4).
  - *Given* every `NavDestinationSpec` label and short label, *When* compared, *Then* no two specs share a label, the label "Library" belongs only to `Screen.Library`, and the spec for `Screen.LibraryStats` is labelled `I18n.NAV_GRAPH_STATS` = "Graph Stats" (`Sidebar.kt:346` says "Library Stats" today and collides with the new destination name; `NavLabelUniquenessTest`).
- Developer-section items appear only when active and last.
  - *Given* current screen is not Logs or Performance, *When* the section list is built, *Then* the Developer section is absent; *Given* current screen is Logs, *Then* it is the final section and contains Logs.
**Files**: `CM/ui/nav/NavSection.kt` (new), `CM/ui/i18n/I18n.kt`.
**Tests first**: `BT/ui/nav/NavModelExhaustivenessTest` (businessTest), `CT/ui/nav/NavSectionBuilderTest` (commonTest, property: no destination appears twice), `BT/ui/nav/NavLabelUniquenessTest` (businessTest). All registered per B.8. `NavDestinationSpec` holds an `NavIconKey` enum (not an `ImageVector`); renderers map the key to the icon so the model stays platform-free.

##### Task D.2a: Model + builder + tests first (S)
- Files: `CM/ui/nav/NavSection.kt`, `BT/ui/nav/NavModelExhaustivenessTest.kt`, `CT/ui/nav/NavSectionBuilderTest.kt`, `BT/ui/nav/NavLabelUniquenessTest.kt`, B.8 registration
##### Task D.2b: I18n keys for labels, short labels and descriptions (XS)
- Includes `NAV_LIBRARY`, `NAV_LIBRARY_DESC`, `NAV_PAGES_SHORT`, `NAV_ALL_PAGES`, section header keys (Daily, Connections, Graph, Developer), `NAV_GRAPH_STATS` and the Library filter/sort/empty/notice strings.
- Files: `CM/ui/i18n/I18n.kt`

### Story D.3: Sidebar sections with descriptions
**As a** desktop user, **I want** headed groups with a line of help under each row, **so that** I know what each destination is for.
**Acceptance Criteria**:
- Section headers have heading semantics and rows are >= 48 dp with a supporting line (2 lines allowed at 200% font scale).
  - *Given* the Graph section, *When* the semantics tree is inspected, *Then* a node with `heading()` and text "Graph" precedes the Library row (first in the section) whose description is visible text, and the row height >= 48 dp.
- The old sidebar label is renamed (Repair 4).
  - *Given* the rendered sidebar, *When* its rows are listed, *Then* the `Screen.LibraryStats` row reads "Graph Stats", exactly one row reads "Library", and no row reads "Library Stats" (`SidebarSectionsTest.graphStatsRename`, plus the pure `NavLabelUniquenessTest` of D.2).
- Selected state is semantic, not colour-only.
  - *Given* current screen `Library`, *When* inspected, *Then* the Library row has `selected = true` and an indicator shape.
- Decorative icons are not announced twice.
  - *Given* a row with an icon and text, *When* inspected, *Then* the icon has no `contentDescription`.
- Showing the Developer section does not move any row above it.
  - *Given* the section list built for Journals and again for Logs, *When* the bounds of every row present in both are compared, *Then* they are identical (the Developer section is appended last).
**Files**: `CM/ui/components/Sidebar.kt` (the flat list is at `:315-355`, the Gallery and Assets rows at `:340-341`), `CM/ui/components/NavSectionList.kt` (new leaf composable), `CM/ui/GraphContentLeftSidebar.kt`.
**Tests first**: `AUT/ui/components/SidebarSectionsTest` (androidUnitTest, Robolectric), goldens updated via Roborazzi (Gradle-only) in the same PR.

##### Task D.3a: `NavSectionList` composable + tests first (M)
- Files: `CM/ui/components/NavSectionList.kt`, `AUT/ui/components/SidebarSectionsTest.kt`
##### Task D.3b: Replace the flat list in `Sidebar.kt`; rename "Library Stats" to "Graph Stats"; update screenshot goldens (S)
- Files: `CM/ui/components/Sidebar.kt`, `CM/ui/GraphContentLeftSidebar.kt`

### Story D.4: Phone "More" opens a grouped sheet (ADR-007)
**As a** phone user, **I want** "More" to show the same grouped, described list, **so that** Library is findable.
**Acceptance Criteria**:
- Tapping More opens a modal bottom sheet rendering `NavSection`s; the drawer is no longer used for More.
  - *Given* the Android shell at `w411dp-h891dp`, *When* More is tapped, *Then* a sheet with headers Daily, Connections and Graph (Library first under Graph) is shown and `toggleSidebar` is not invoked.
- Bottom bar labels fit and are not announced twice.
  - *Given* the four bottom-bar items at 411 dp and 200% font scale, *When* inspected, *Then* no label is truncated below one full word (the bar uses `shortLabelKey`, "Pages"), and each icon has `contentDescription = null` when text is present.
- First-open smoke still passes.
  - *Given* `first-open-screens.txt` updated (**in this story's PR, not deferred to D.8**: `kmp/src/businessTest/resources/first-open-screens.txt` is edited by D.4b so the smoke gate stays green), *When* `FirstOpenSmokeTest` runs, *Then* it reaches Library via the sheet.
**Files**: `AM/ui/PlatformBottomBar.android.kt` (the "Pages" label is at `:29`), `CM/ui/GraphContentStatusAndBottomBar.kt`, `AM/ui/MoreSheet.android.kt` (new), `androidApp/src/test/kotlin/dev/stapler/stelekit/FirstOpenSmokeTest.kt`.
**Tests first**: `AUT/ui/MoreSheetTest` (androidUnitTest), existing `FirstOpenSmokeTest` updated first.

##### Task D.4c: Sheet state in commonMain (XS, before D.4b)
- `AppState.moreSheetOpen` + `StelekitViewModel.setMoreSheetOpen(Boolean)` (commonMain; `GraphContentStatusAndBottomBar.kt` triggers it, `MoreSheet.android.kt` renders it); desktop and iOS shells keep the sidebar/drawer unchanged (non-regression test). Add a `WiringCompletenessTest` expectation for the new trigger. The Developer section's expanded/collapsed flag lives in `AppState` and is shared by sidebar and sheet; crossing 600 dp (fold or unfold) retains the current destination and that flag, dismisses an open More sheet on unfold and moves focus to the current destination in the new shell (ED-22, ux.md S20, UX-78; `NavShellConfigChangeTest`).
- Files: `CM/ui/AppState.kt`, `CM/ui/StelekitViewModel.kt`, `BT/wiring/WiringCompletenessTest.kt`, `AUT/ui/nav/NavShellConfigChangeTest.kt`
##### Task D.4a: `MoreSheet` + tests first (M)
- Files: `AM/ui/MoreSheet.android.kt`, `AUT/ui/MoreSheetTest.kt`
- Verify step: `bazel build //kmp:android_app --config=android` (new `MoreSheet.android.kt` needs Bazel `kt_android_library` deps).
##### Task D.4b: Rewire `PlatformBottomBar`/shell; bottom-bar a11y duplicate fix (S)
- Files: `AM/ui/PlatformBottomBar.android.kt`, `CM/ui/GraphContentStatusAndBottomBar.kt`, `kmp/src/businessTest/resources/first-open-screens.txt`

### Story D.5: Command palette navigation commands
**As a** keyboard user, **I want** "Go to Library", "Show annotated photos" and "Annotate a photo" in the palette, **so that** I can reach them without the sidebar.
**Acceptance Criteria**:
- Commands exist and navigate.
  - *Given* the palette open and the query "library", *When* the first result is activated, *Then* `currentScreen` is `Screen.Library` with no filter.
  - *Given* "Show annotated photos", *When* activated, *Then* `currentScreen` is `Screen.Library` with `initialFilter = Annotated` and every other filter and the search cleared (ux.md LD-8).
  - *Given* "Annotate a photo" (the platform picker callback is implemented on Android only; desktop and wasm hide the command with a documented reason), *When* activated, *Then* the image picker flow starts and, on a result, `navigateToAnnotationEditor` is called.
- Commands derive from `NavSection`.
  - *Given* a new `NavDestinationSpec` added in tests, *When* the command list is built, *Then* a "Go to ..." command for it exists.
**Files**: `CM/editor/commands/EssentialCommands.kt` (navigation group ~`:589`), `BT/wiring/WiringCompletenessTest.kt` (expectations), `CT/editor/commands/NavCommandsTest.kt` (registered per B.8), the Android picker callback in `ScreenRouter`.
**Tests first**: `CT/editor/commands/NavCommandsTest`.

##### Task D.5a: Commands + wiring + tests (S)
- Files: `CM/editor/commands/EssentialCommands.kt`, `CT/editor/commands/NavCommandsTest.kt`

### Story D.6: The Library screen (merges Gallery and Assets behaviours)
**As a** user, **I want** one place for my photos, annotated photos and attachments, **so that** I never have to guess which of two screens holds a file.
**Acceptance Criteria**:
- Filters and sort work together (AND semantics) and are visible.
  - *Given* a Library of 100 items of which 7 are annotated images, *When* the Annotated chip is selected, *Then* exactly 7 cards are listed in pages of <= 100, a polite live region says "7 annotated items", and a visible [Clear filters] action restores the full list.
  - *Given* filters Images + Annotated + tag "site" + search "kitchen", *When* applied, *Then* only items matching all four are listed, and removing any one chip widens the list.
  - *Given* Images + PDFs selected in the Type group, *When* applied, *Then* items of either type are listed (OR inside the group, LD-1); *Given* Images + PDFs + Annotated, *Then* the PDF chip is dimmed (Annotated needs photos) and the list is annotated images only. Type membership is the `AssetMediaType` table of LD-11 (a file stored as `FILE` or with an unrecognised media type is in Other).
  - *Given* the Orphaned chip, *When* selected, *Then* only items with no referencing page are listed (the Assets behaviour); an annotated orphan is listed with the caption "Not on any page" and [Open annotation] enabled.
  - **Contradictions (B-UX-1, LD-1)**: *Given* Annotated or a tag chip is on, *When* the filter bar is inspected, *Then* every non-image Type chip is dimmed, focusable, carries a `stateDescription` with the reason ("Unavailable: only photos can be annotated"), and tapping one shows that reason in the reasons live region without changing the filters; the same holds for Annotated and tag chips while a non-image Type is selected. *Given* Annotated and Orphaned are both on, *Then* nothing is dimmed and the list is annotated photos that are on no page (LD-16). *Then* no sequence of taps on enabled chips yields a state for which `LibraryFilterRules.normalize` changes anything.
  - **Status line (LD-5, LD-17)**: *Given* five chip taps within 400 ms, *When* the fake clock advances, *Then* exactly one polite announcement with the settled count is made; page loads announce nothing. *Given* each combination in the LD-17 table (one type, two types, Annotated, Orphaned, one, two and three tags, long search text, one item, zero items, the 10,000 cap), *Then* the line equals the template output ("3 annotated images tagged site or kitchen, matching 'sink'", "1 item", "10,000+ items of type Images, PDFs"). The count line, the reasons region and the snackbar region are three separate live-region nodes (`LibraryStatusLineTest`, `LiveRegionPolitenessTest`).
  - **Search (LD-3)**: *Given* the search field, *When* 1 character is typed, *Then* no search runs, the list is unchanged and the reasons region says "Type at least 2 characters to search"; *When* "kit sink" is typed, *Then* only items where both words start a word in the file name, an annotation tag or an annotation label are listed.
  - **Tag overflow (LD-14)**: *Given* 40 distinct tags, *When* the Tags group renders, *Then* selected tags plus the 8 most used are chips, a [+32 more] chip opens a searchable multi-select sheet, and the group never scrolls horizontally.
  - **Add under filters (LD-12, LD-13)**: *Given* Annotated is on and a photo is captured, *Then* the snackbar reads "Added {name} to {page}. Hidden by current filters." [Clear filters] and the filters are unchanged; *Given* no filters, *Then* the new card is scrolled into view and outlined; *Given* the Name A-Z sort, *Then* the snackbar offers [Show]. *Given* no page was ever viewed, *Then* the file is added to today's journal page. No Add creates an orphan (`LibraryAddResultTest`, `LibraryAddAnchorTest`).
  - **Undecodable files (LD-18)**: *Given* a corrupt image, *When* its thumbnail fails to decode with a decoder exception, *Then* the card shows "Can't preview" with no tint, no retry occurs until "Try again"; *Given* an out-of-memory failure, *Then* the skeleton tile is shown and re-requested on scroll idle (`LibraryThumbnailPlaceholderTest`).
  - **Scroll anchor after process death (LD-19)**: *Given* the user scrolled to item X (page 3) and the process is killed, *When* the screen is restored, *Then* pages load from the top until X is loaded (at most 10) and the grid scrolls to X; *Given* X no longer exists, *Then* the grid stays at the top.
  - **Persistence (LD-4)**: *Given* filters, search and sort set, *When* the screen rotates, folds, is restored after process death, or the user returns from the editor, *Then* filters, search text and the scroll anchor (item id) are unchanged; *Given* a fresh launch, *Then* filters are cleared and the sort is the persisted `libraryDefaultSort`.
  - **Compact width (LD-6)**: *Given* width 411 dp or font scale 2.0, *When* the screen renders, *Then* the bar is one [Filters (n)] button + Search + Sort and the first card's top edge is visible without scrolling; the sheet holds the groups and [Clear filters] (task D.6e).
- Sort is a menu with the four orders in decision 5 (default Recently added; unannotated items last for the two annotation sorts, LD-2); the chosen order and filters survive Back from the editor/detail (same scroll position, and keyboard/TalkBack focus returns to the originating card, LD-9).
- Cards: thumbnail (or a type icon for non-images), "Annotated" badge as icon plus word, measurement count, unique `contentDescription` ("Annotated photo, {date}, {n} measurements, tag {tag}" / "PDF file, {name}, {size}, on page {page}").
  - *Given* two annotated cards, *When* inspected, *Then* their descriptions differ and include date and measurement count.
- Behaviours migrated are tracked in the parity table below; every row has a test, and D.11b deletes the old screens only when all rows are green.
- Tap rule (decision 7): *Given* an annotated image card, *When* tapped, *Then* the editor opens; *Given* any other card, *Then* the asset detail opens, with an Annotate action only for images. The rule is one of the tasks of the cold-user sessions (D.9e).
- Placeholders are distinct (ED-9): *Given* a thumbnail that is still loading under memory pressure and a card whose file is missing, *When* inspected, *Then* the first is a text-free skeleton tile with the card's normal description and no error tint, the second has the broken-image icon, the visible text "File missing" and an error-tinted border (`LibraryThumbnailPlaceholderTest`).
- Keyboard (LD-9): *Given* a desktop keyboard, *When* arrow keys, Home/End and PageUp/PageDown are pressed, *Then* focus moves between cards; chip groups expose labelled-group semantics with roving focus; a focus-visible ring is present on keyboard focus only (`LibraryKeyboardNavigationTest`).
- Add menu (decision 6): Capture photo, Attach file, Choose photo to annotate (Android only), with the permission-denied, cancelled, unreadable-file and in-progress states of ux.md S19 (task D.6f).
- States (UX-12, UX-13, UX-14, UX-15, UX-42..UX-47): loading skeleton, empty (unfiltered / filtered / Annotated-filtered), error with Retry and Open Logs, page-2 failure with inline Retry, missing-file tile (Keep / Remove with confirmation), permission-denied variant. Every state has an action or Back.

**Parity table** (every old behaviour, where it lives now, test):

| Old behaviour | Source | Library implementation | Test |
|---|---|---|---|
| Sort order menu | `GalleryViewModel.kt:115-120` | sort menu, SQL `ORDER BY` | `LibraryScreenFilterTest` |
| Tag chips | `GalleryViewModel.kt:89-91` | tag chip row from `getDistinctAnnotationTags()` | `LibraryScreenFilterTest` |
| Annotation thumbnail + measurement count | `GalleryScreen.kt` | card with badge and count | `LibraryCardSemanticsTest` |
| Tap opens editor | `GalleryScreen.kt` | tap rule (decision 7) | `LibraryScreenFilterTest` |
| Missing-file handling | none today | missing-file tile | `LibraryMissingFileTest` |
| Empty state + Capture Photo | `GalleryEmptyState.kt` | Library empty state + Add menu | `LibraryEmptyStateTest` |
| "Import from Gallery" stub | `GalleryScreen.kt:124` | deleted (decision 6) | `LibraryEmptyStateTest` (button absent) |
| Search field | `AssetBrowserSearchBar.kt` | search field in Library | `LibraryScreenFilterTest` |
| Type filter chips | `AssetBrowserUiState.kt` | media-type chips | `LibraryScreenFilterTest` |
| Orphaned filter | `AssetBrowserViewModel.kt` | Orphaned chip | `LibraryScreenFilterTest` |
| Status line | `AssetBrowserStatus.kt` | status/count line (polite live region) | `LibraryScreenFilterTest` |
| Per-asset actions menu | `AssetActionMenu.kt` | card overflow menu | `LibraryCardSemanticsTest` |
| Asset detail + Annotate | `AssetDetailScreen.kt`, `AssetDetailViewModel.kt` | unchanged screen, linked from Library | `AssetDetailAnnotateOrphanTest` |
| Global-search "Show all" | `GraphDialogLayer.kt:130` -> `Screen.AssetBrowser(q)` | alias redirect to `Library(initialSearch = q)` | `LibraryRedirectTest` |

**Files**: `LIB/LibraryScreen.kt`, `LIB/LibraryFilterBar.kt`, `LIB/LibraryItemCard.kt`, `LIB/LibraryStates.kt`, `LIB/LibraryAddMenu.kt`, `LIB/LibraryFilterSheet.kt`, `LIB/LibraryDeleteDialog.kt`, `LIB/LibraryFilterRules.kt` (all new), `CM/ui/assets/AssetDetailScreen.kt`.
**Tests first**: `CT/ui/library/LibraryFilterRulesTest`, `LibraryFilterRulesPropertyTest` (commonTest); `AUT/ui/library/LibraryScreenFilterTest`, `LibraryCardSemanticsTest`, `LibraryEmptyStateTest`, `LibraryErrorStateTest`, `LibraryMissingFileTest`, `LibraryCompactFilterTest`, `LibraryConfigChangeTest`, `LibraryKeyboardNavigationTest`, `LibraryOverflowAndDeleteTest`, `LibraryThumbnailPlaceholderTest`, `LibraryAddResultTest`, `LibraryAddAnchorTest`, `ScreenHeadingsTest` (all androidUnitTest, Robolectric); `CT/ui/library/LibraryMediaClassTest`, `LibrarySearchTest`, `LibraryStatusLineTest` (commonTest, Repair 5).

##### Task D.6a: Library screen shell, filter bar and sort menu (M)
- Compose shell over `LibraryUiState`: grouped filter chips (Type, Show, Tags per LD-1; dimmed-with-reason chips from `LibraryFilterRules`), search field, sort menu, debounced status/count line (LD-5), [Clear filters], labelled chip groups with roving keyboard focus, grid arrow-key navigation and focus restore (LD-9). Chips are toggle buttons (`toggleable`, selected semantics, check icon, >= 48 dp). The status line uses the LD-17 templates with three separate live regions (count, reasons, snackbar); the Tags group follows the LD-14 overflow rule; dimmed chips and the "Release to cancel"-style tokens are covered by `ThemeContrastTest` (ED-27). Depends on D.6g.
- **Split trigger**: if D.6a touches more than 5 files or the LD-17 formatter plus the LD-14 tag sheet push it beyond M, split into D.6a-1 (bar, chips, sort menu, keyboard) and D.6a-2 (status line, reasons region, tag overflow sheet). If spike D.7a-0 finds annotation rows with no asset row (the `UNION ALL` branch), D.6b renders those items from the annotation's own `file_path` (no file size, no "on page" text) and that rendering is a separate D.6b-union task only if it needs more than 2 files.
- Files: `LIB/LibraryScreen.kt`, `LIB/LibraryFilterBar.kt`, `LIB/LibraryUiState.kt`, `AUT/ui/library/LibraryScreenFilterTest.kt`
##### Task D.6b: Library card: thumbnail, Annotated badge, count, unique descriptions, overflow menu (S)
- Replaces `AssetItemCard`/Gallery card. Non-image types get a type icon. Missing-file tile (UX-15: "File missing", Keep / Remove with confirmation "Delete measurements for this photo? This can't be undone.").
- Files: `LIB/LibraryItemCard.kt`, `AUT/ui/library/LibraryCardSemanticsTest.kt`, `AUT/ui/library/LibraryMissingFileTest.kt`
##### Task D.6c: Empty, error, loading, permission states; Add menu; delete the "Import from Gallery" stub (S)
- Copy per `design/ux.md` S5/S6 (merged); the empty-state copy names the page new files go to ("Capture a photo or attach a file; it is added to {page name}, the page you were last on", LD-13). Skeleton while loading, page-2 failure inline Retry, Retry re-runs the load, [Open Logs]. Add menu per decision 6. Remove the dead `onImportPhoto` parameter from the empty state.
- Files: `LIB/LibraryStates.kt`, `LIB/LibraryAddMenu.kt`, `AUT/ui/library/LibraryEmptyStateTest.kt`, `AUT/ui/library/LibraryErrorStateTest.kt`
##### Task D.6d: Detail links and orphan Annotate (S)
- `AssetDetailScreen` shows [Annotate] (images, not annotated), [Open annotation] (annotated), and for an orphan the **disabled Annotate with the visible reason line and an [Open pages] button** (ED-8; replaces the C.9c snackbar). Back returns to Library with filters and scroll preserved.
- Files: `CM/ui/assets/AssetDetailScreen.kt`, `CM/ui/assets/AssetDetailViewModel.kt`, `BT/ui/assets/AssetDetailAnnotateOrphanTest.kt`

##### Task D.6e: Compact-width filter sheet and Library configuration changes (S)
- Below 600 dp width or font scale >= 1.5 the filter bar collapses to [Filters (n)] + Search + Sort with the chips in a modal bottom sheet (live "Show {n} items" button, [Clear filters], one-line summary of active chips); the layout switches live when the window crosses 600 dp (Pixel Fold cover about 411 dp, inner about 673 dp); the column count is floor(width / 160 dp), minimum 2, with the scroll anchored by first visible item id (after process death by the bounded forward load of LD-19, at most 10 pages, else top); filters, sort, search text, an open sheet and focus survive rotation, fold, unfold and process-death restore (ux.md LD-4, LD-6, ED-7, S20). Uses the C.9d tokens and honours reduced motion.
- Files: `LIB/LibraryFilterSheet.kt` (new), `LIB/LibraryScreen.kt`, `LIB/LibraryFilterBar.kt`, `AUT/ui/library/LibraryCompactFilterTest.kt`, `AUT/ui/library/LibraryConfigChangeTest.kt`
##### Task D.6f: Per-asset overflow, delete, Add menu states and thumbnail placeholders (S)
- Add anchoring and results (LD-12, LD-13): every Capture, Choose-photo and Attach is appended to the page last viewed (`lastViewedPageUuid`) or today's journal; the result is scrolled-to-and-outlined, "Hidden by current filters" with [Clear filters], or [Show] under a non-default sort. Undecodable thumbnails (LD-18) and the "Can't preview" card. Card overflow (Details, Annotate with the orphan reason, Open annotation, Open page, the existing per-asset actions); Delete file with a confirmation that names referencing pages and measurements, progress, failure with Retry and no Undo; missing non-annotated asset sheet ([Remove from Library] / [Keep]); Add menu states (camera permission denied, picker cancelled, unreadable file, in progress); loading-skeleton versus "File missing" tiles (ux.md S19, LD-10, ED-9, ED-11, UX-67, UX-70).
- Files: `LIB/LibraryItemCard.kt`, `LIB/LibraryAddMenu.kt`, `LIB/LibraryDeleteDialog.kt` (new), `AUT/ui/library/LibraryOverflowAndDeleteTest.kt`, `AUT/ui/library/LibraryThumbnailPlaceholderTest.kt`
##### Task D.6g: `LibraryFilterRules` pure model and tests first (S, before D.6a and D.7a)
- Pure commonMain: filter groups, `disabledReason(chip, state)`, `normalize(state)` (entry-point rules LD-8), `LibraryMediaClass.of(mediaType)` (LD-11), `LibrarySearch` (tokenise on whitespace and the separator set, escape `%` and `\`, build the `:p1..:p5` patterns, the 2-character rule; LD-3), the reference in-memory filter used by the SQL parity test (same hay normalisation, ASCII-only case folding, keyset order of LD-2), `LibraryQuery` construction. Tests first: example cases for every contradiction in LD-1 (and Annotated + Orphaned allowed, LD-16), a `kotest-property` test that no enabled-chip tap sequence leaves a state that `normalize` would change, and that `normalize` is idempotent.
- Files: `LIB/LibraryFilterRules.kt`, `CT/ui/library/LibraryFilterRulesTest.kt`, `CT/ui/library/LibraryFilterRulesPropertyTest.kt`, B.8 registration (`business_common_tests` srcs and `AllBusinessTests.kt`)

### Story D.7: Library reads are bounded (ADR-008; refactor-first)
**As a** user with thousands of files and annotations, **I want** Library not to load everything, **so that** the app does not stall.
**Acceptance Criteria**:
- One paged query per page, annotated state joined in SQL.
  - *Given* 8,030 annotation rows and 8,000 asset rows, *When* Library loads and scrolls to the end, *Then* every repository call carried `limit <= 100`, exactly one query ran per page (no per-item lookup, no N+1), no `getAllImageAnnotations` and no unbounded asset read occurred.
  - *Given* a page of 100 items of which 7 are annotated, *When* it loads, *Then* exactly those 7 carry `annotated = true` from the same query that returned the page; *Given* an image annotated from two pages, *Then* it is one item whose measurement count is the sum over both annotation rows (LD-15).
- The filter, sort and search semantics are the ADR-008 "Defaults" and ux.md LD-1..LD-7 (B-UX-1).
  - *Given* random item sets and normalized `LibraryQuery` states (property test, `LibraryFilterSqlParityTest`), *When* the SQL page query and the in-memory reference filter of `LibraryFilterRules` run, *Then* they return the same ids in the same order (Type OR within the group, Annotated/Orphaned/tag/search AND across groups, unannotated items last for the annotation sorts, item id as the final tiebreak).
  - *Given* the search definition of LD-3/LD-7, *When* the SQL page query and the reference filter run on the same fixture, *Then* they agree on: multi-word queries ("kit sink": both tokens, possibly in different fields), `%`, `_` and `\` typed by the user (literal; `_` splits the token), a 1-character query (no search, list unchanged), 0 characters (unfiltered), ASCII case ("KIT" matches "kitchen"), non-ASCII case (`é` does not match `É`; both sides use the same ASCII-only fold), separators ("IMG_0012" = tokens img and 0012; "site-visit" matches tag "site visit"), a match through a tag only, through a label only, and not through a page name or file content (`LibraryFilterSqlParityTest`).
  - *Given* 8,030 rows paged by the keyset of LD-2 in every sort order, *When* all pages are read, *Then* each row appears exactly once, in order, and the same holds with ties on every leading key component.
  - *Given* `countLibrary(query)` for a result above 10,000 rows, *Then* it reports 10,000 and the UI shows "10,000+".
- No standing unbounded flow remains.
  - *Given* a repository spy, *When* the screen is observed for 5 s of writes, *Then* each emission has <= 100 rows.
- Query plans are audited.
  - *Given* `QueryPlanAuditTest` includes the paged query for **each** sort order and each filter (Annotated, media type, Orphaned, tag, search prefix) and their common combinations, *When* it runs, *Then* none does a full table scan of the annotation or asset tables beyond the filtered range.
- Sorting, tag aggregation and the count are done in SQL, not in memory.
  - *Given* 8,030 rows and a sort change, *When* page 1 is requested, *Then* the query carries the `ORDER BY` for that order and results across pages are globally ordered; page N+1 is requested with the key tuple of the last row of page N (keyset), never with an OFFSET.
  - *Given* the tag chip row, *When* it loads, *Then* it comes from a bounded `getDistinctAnnotationTags(limit = 200)` projection (`DISTINCT` ordered by usage, with a limit; LD-14), not from `images.flatMap { it.tags }` (`GalleryViewModel.kt:89-91`).
  - *Given* the status line, *When* it needs a total, *Then* it uses a debounced `countLibrary(query)` and never materialises rows.
- `LibraryState` has an explicit paging state (loaded count, hasMore, load-more error); every Flow read ends in `catchDbError` (`UpgradeResilienceTest` entry).
**Files**: `CM/repository/ImageAnnotationRepository.kt`, `CM/repository/AssetRepository.kt` (or a new `LibraryRepository.kt` composing both reads; decided in D.7a-0), `kmp/src/commonMain/sqldelight/.../SteleDatabase.sq`, `kmp/src/generated/sqldelight/`, `LIB/LibraryViewModel.kt`.
**Tests first**: `BT/ui/library/LibraryAnnotatedJoinTest`, `BT/ui/library/LargeLibraryBatchingTest`, `BT/ui/library/LibraryFilterSqlParityTest` (businessTest), `JT/db/QueryPlanAuditTest` (extended), `UpgradeResilienceTest` (extended).

##### Task D.7a-0: Schema, join-key and search-shape spike (S, before D.7a; recorded in ADR-008)
- Answer, with the command output pasted in ADR-008: (a) the join key between `asset_index` and `image_annotations` (`file_path`, `relative_path`, `block_uuid`, `page_uuid`); (b) whether any `image_annotations` row has no asset row (decides the `UNION ALL` branch and its split trigger); (c) whether `asset_index` has a file-name column or the `relative_path` basename expression of LD-7 is needed; (d) the column for "last annotated at" (`imported_at_ms` of the annotation row if there is no update time); (e) that the search predicate of LD-7 plans as a bounded LIMITed scan on the 8,030-row fixture and its measured time, deciding whether an FTS table (a `MigrationRunner` entry and a write path) is ever needed (default: no, ADR-008 Defaults 5); (f) that `ESCAPE '\'` and the `REPLACE` chain behave identically on the Android SQLite driver and the JVM driver (a two-driver check using `LibraryFilterSqlParityTest` cases). No production code; an ADR-008 amendment and the resolved column names are the output.
- Files: `project_plans/annotation-memory-and-nav-ia/decisions/ADR-008-bounded-gallery-and-annotated-join.md`
##### Task D.7a: Paged `getLibraryPage` with the annotated join, tag projection and count + tests first (M)
- D.7a-0 is its own task (above) and must be merged into ADR-008 before this task starts; this task implements the queries it resolves. Add read-only queries; a Flow-backed read gets `catchDbError`. Run the CLAUDE.md regeneration commands and commit `kmp/src/generated/sqldelight/`. The query semantics are fixed by ADR-008 "Defaults" 1-9 and ux.md LD-1..LD-7, LD-11, LD-15; the keyset predicate, the search predicate and `countLibrary` (`SELECT COUNT(*) FROM (SELECT 1 ... LIMIT 10001)`) are written exactly as in LD-7 with the column names D.7a-0 recorded.
- **Split trigger (generated sources)**: if the regeneration diff touches more than 6 files under `kmp/src/generated/`, touches generated code for queries other than the Library ones, or conflicts on rebase with the C.1d/C.1f or D.7c regeneration, split into D.7a-gen (only `SteleDatabase.sq` and `kmp/src/generated/**`, a mechanical PR with the local `git diff --exit-code kmp/src/generated/` result) and D.7a-repo (repository Kotlin and tests); never hand-merge generated files.
- **Split trigger (`UNION ALL` branch)**: if D.7a-0 finds `image_annotations` rows without an asset row, the `UNION ALL` branch ships as its own task D.7a-union (after D.7a-repo) when any of these holds: the branch makes `QueryPlanAuditTest` show a SCAN of either table beyond the filtered range; the keyset predicate or the search predicate has to be duplicated across the two branches in more than one place; or the reference filter in `LibraryFilterRules` needs more than one extra rule for union rows. D.7a-repo then ships with the asset-backed branch only and the union rows are tracked by a failing-then-passing `LibraryAnnotatedJoinTest` case.
- Files: `CM/repository/ImageAnnotationRepository.kt`, `kmp/src/commonMain/sqldelight/.../SteleDatabase.sq`, `kmp/src/generated/sqldelight/`, `BT/ui/library/LibraryAnnotatedJoinTest.kt`, `BT/ui/library/LargeLibraryBatchingTest.kt`, `BT/ui/library/LibraryFilterSqlParityTest.kt`
##### Task D.7c: Index migration for the sort, filter and join paths (S, before D.7b)
- `idx_image_annotations_imported_at` already exists (`MigrationRunner.kt:553-556`; the image-annotations indexes sit at `:419-421`). For the join key, each sort order and the tag filter that `QueryPlanAuditTest` shows scanning, add the index to `SteleDatabase.sq` **and** a `CREATE INDEX IF NOT EXISTS` entry in `MigrationRunner`, and extend `MigrationRunnerSchemaSyncTest` to cover indexes. If the audit shows every query indexed already, record "no migration needed" and close the task. ADR-008 is amended (indexes are migrations).
- Files: `kmp/src/commonMain/sqldelight/.../SteleDatabase.sq`, `CM/db/MigrationRunner.kt`, `BT/db/MigrationRunnerSchemaSyncTest.kt`, `JT/db/QueryPlanAuditTest.kt`
##### Task D.7b: `LibraryViewModel` paging, filters, sort, `catchDbError` guard (M)
- One ViewModel replacing `GalleryViewModel` and `AssetBrowserViewModel`; owns its scope with a `CoroutineExceptionHandler` (CLAUDE.md); exposes `LibraryState` (filters, sort, paging, error, loading). No `sortImages`, no in-memory tag aggregation.
- Files: `LIB/LibraryViewModel.kt`, `LIB/LibraryUiState.kt`, `BT/ui/library/LibraryViewModelStateTest.kt`, `UpgradeResilienceTest` entry

### Story D.8: Touch-point updates and gates
**As a** maintainer, **I want** every test and mapping that mentions the old names updated in one pass, **so that** gates stay green.
**Acceptance Criteria**: each row of the checklist below is updated and the named gate passes.
  - *Given* the merge, *When* `FirstOpenSmokeCatalogTest` and `FirstOpenSmokeTest` run, *Then* `first-open-screens.txt` entries and `tap(<I18n constant>)` agree and both pass.

| Touch point | Change |
|---|---|
| `androidApp/src/test/kotlin/dev/stapler/stelekit/FirstOpenSmokeTest.kt` (`FIRST_OPEN_SCREENS` at `:47`; the `"Assets browser"` branch `tap("Assets")` at `:111-113`, asserts "Search assets…" and "No assets yet") | rename the entry to the Library screen; use the I18n constant; assert the Library search and empty-state strings |
| `kmp/src/businessTest/resources/first-open-screens.txt` (entry "Assets browser") | replace with the Library entry |
| `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/assets/AssetResultsSurfaceTest.kt:239` `hasText("Assets")` | I18n constant |
| `AIT/AssetBrowserE2ETest.kt:110` and other text assertions (`rg -n 'Assets\|Gallery' kmp androidApp --glob '*Test*.kt'`) | constants; the E2E test is instrumented, not in CI |
| `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/docs/DemoGraphCoverageTest.kt:90` help-exempt list ("Gallery", "AssetBrowser") | add "Library"; keep the aliases |
| `CM/ui/StelekitViewModel.kt:1215-1216` label mapping ("Opened Gallery" / "Opened Asset Browser"), `navigateToGallery` (`:1368`, no callers) | add `Library` label from I18n; `navigateToGallery` redirects to Library or is deleted |
| `CM/ui/ScreenRouter.kt` (`:342-374`: Gallery and AssetBrowser branches) | add `Screen.Library`; aliases redirect (D.11a); titles from I18n |
| `CM/ui/GraphDialogLayer.kt:130` (`navigateTo(Screen.AssetBrowser(q))`), `CM/ui/assets/AssetsSearchSection.kt` | "Show all" navigates to `Library(initialSearch = q)`; section title from I18n |
| Command palette entries | D.5 |
| Help/docs and demo-graph pages mentioning Gallery/Assets | text update: "Library" |

**Tests first**: update the tests above to read constants before changing labels (they fail, then pass).

##### Task D.8a: Update the first-open gates to constants, then labels (S)
- Files: `androidApp/src/test/kotlin/dev/stapler/stelekit/FirstOpenSmokeTest.kt`, `kmp/src/businessTest/resources/first-open-screens.txt`, `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/assets/AssetResultsSurfaceTest.kt`
##### Task D.8b: ViewModel/router/search label mapping (S)
- Files: `CM/ui/StelekitViewModel.kt`, `CM/ui/ScreenRouter.kt`, `CM/ui/GraphDialogLayer.kt`, `CM/ui/assets/AssetsSearchSection.kt`
##### Task D.8c: Persisted screen-identifier audit (S) (pre-mortem failure 8)
- `rg` every persisted or serialised screen/route string (settings keys, restored-state, palette command ids, favourites, help/demo-graph links, tracing keys) and add a test that old values (`Gallery`, `AssetBrowser`, their tracing keys) still resolve to `Library` after the merge. Explicit AC: no persisted value breaks; the aliases remain. Output is the audit table in the PR.
- **Split trigger**: if the audit finds persisted or serialised screen identifiers in more than 3 distinct stores (settings keys, restored state, palette command ids, favourites, help/demo links, tracing keys) or the fixes would touch more than 5 files, split into D.8c-1 (audit table + `PersistedScreenIdTest`, no production change) and D.8c-2 (one fix PR per store).
- Files: `BT/ui/nav/PersistedScreenIdTest.kt`, plus whatever the audit finds
##### Task D.8d: Remaining text assertions, demo graph and help pages (S)
- `DemoGraphCoverageTest` help-exempt list, `AIT/AssetBrowserE2ETest.kt`, demo-graph and help text, any other literal found by the `rg` in the table.
- **Split trigger**: if the `rg` finds old-name literals in more than 5 files, split by directory into D.8d-1 (androidUnitTest, businessTest and jvmTest assertions), D.8d-2 (`androidInstrumentedTest`, not in CI) and D.8d-3 (demo-graph and help pages); each is a mechanical constants-only change.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/docs/DemoGraphCoverageTest.kt`, `AIT/AssetBrowserE2ETest.kt`, the demo-graph/help pages the `rg` finds

### Story D.9: First-run tip, upgrade notice and UX review
**As a** new user, **I want** a one-time explanation of Library, **and as an existing user** **I want** to be told that Gallery and Assets moved, **so that** neither group hunts for the old screens.
**Acceptance Criteria**:
- Fresh install: a dismissible tip shows on the first Library open only.
  - *Given* a fresh install and the first open of Library, *When* it renders, *Then* the tip "Everything attached to your pages lives here. Tap Annotated to see photos you've annotated." is visible; *Given* it was dismissed, *Then* it never reappears (persisted preference).
- Existing user: a one-time upgrade notice replaces the tip.
  - *Given* an install that already had a graph before this version (detected by a persisted `navIaVersion` setting that is absent while a graph is registered at launch), *When* the user first opens Library or the sidebar, *Then* a dismissible notice "Gallery and Assets are now Library. Use the Annotated filter to see your annotated photos." with [Got it] and [Show annotated photos] is visible on every Library open until dismissed; dismissal persists; the tip is not also shown. The badge and notice follow the state table of ux.md LD-20 (per install; opening Library clears the badge, the notice persists until dismissed; adding a second graph changes nothing; a store failure shows both for the session): `UpgradeNoticeStateMachineTest` walks every row.
  - *Given* an existing install whose user only uses the sidebar or More sheet and never opens Library, *When* the nav is shown, *Then* the Library row (sidebar, More sheet row and the bottom-bar More button) carries a "New" badge with a text equivalent (`stateDescription` "New: Gallery and Assets moved here") until Library is first opened (LD-20; the notice itself stays until dismissed); a fresh install has no badge (task D.9d, ux.md ED-16).
  - *Given* the notice was dismissed, *When* the user opens Settings > About > What's new, *Then* the entry "Gallery and Assets are now Library" is present and can be re-read.
  - *Given* the old route (palette entry, deep link, saved screen) lands on Library through an alias, *When* it opens, *Then* the notice is shown if not yet seen.
- A UX review finds no unlabeled or ambiguous destination (Success Metric 4).
  - *Given* the finished nav, *When* the `ux:review` skill is run over the sidebar and sheet, *Then* its report lists zero findings of severity MAJOR or higher on labelling.
- The first-open task test (requirements Success Metric 4 outcome measure) passes: `NavFirstOpenTaskTest` locates "my annotated photos" and "a file attachment" from the nav alone with no wrong taps.
**Files**: `LIB/LibraryOnboarding.kt` (new; state machine Fresh / Upgrade / Seen), preference keys in the existing settings store.
- Cold-user evidence (ED-17): *Given* 3 to 5 sessions with people who have not seen the app, *When* the owner runs the protocol (task D.9e), *Then* the pass thresholds in ux.md ED-17 are met or the failing copy/section name is fixed by an I18n edit and the affected sessions are repeated. `NavFirstOpenTaskTest` only proves that a zero-wrong-tap path exists.
**Tests first**: `AUT/ui/library/LibraryTipTest`, `AUT/ui/library/LibraryUpgradeNoticeTest`, `AUT/ui/library/LibraryNewBadgeTest`, `AUT/ui/nav/NavFirstOpenTaskTest`, `CT/ui/library/UpgradeNoticeStateMachineTest`.

##### Task D.9a: Tip + persistence + tests (S)
- Files: `LIB/LibraryOnboarding.kt`, `AUT/ui/library/LibraryTipTest.kt`
##### Task D.9b: Run `ux:review`, fix findings (S)
- Files: as findings dictate (within `Sidebar.kt`, `NavSectionList.kt`, `MoreSheet.android.kt`)
##### Task D.9c: Upgrade notice for existing users and the first-open task test (S)
- Detection by `navIaVersion` (see AC) through the pure reducer `LibraryOnboardingState` (commonMain, state table LD-20; test `CT/ui/library/UpgradeNoticeStateMachineTest`); notice copy and actions per `design/ux.md` S7; failure to read or write the preference never blocks the screen. `NavFirstOpenTaskTest` drives the shell as a first-time user (Robolectric) with the two tasks. **The before-run is mandatory**: run the same driver on commit `408907d9cd` (today's Gallery/Assets nav) first and paste the wrong-tap counts in the PR; the after-run is accepted only against that recorded number (requirements.md Success Metric 4).
- Files: `LIB/LibraryOnboarding.kt`, `AUT/ui/library/LibraryUpgradeNoticeTest.kt`, `AUT/ui/nav/NavFirstOpenTaskTest.kt`

##### Task D.9d: "New" badge for existing installs and Settings > About > What's new (S)
- The Library row (sidebar, More sheet, bottom-bar More button) shows a "New" dot-plus-word badge with a `stateDescription` equivalent until Library is first opened (`libraryBadgeCleared`, LD-20; the notice persists until `upgradeNoticeDismissed`); a Settings > About > What's new entry keeps "Gallery and Assets are now Library" findable. Fresh installs get neither. A preference failure shows the badge for the session and never blocks. Honours reduced motion and the C.9d tokens (UX-68).
- Files: `LIB/LibraryOnboarding.kt`, `CM/ui/components/NavSectionList.kt`, `CM/ui/settings/WhatsNewSection.kt` (new), `AUT/ui/library/LibraryNewBadgeTest.kt`
##### Task D.9e: Cold-user sessions and editor-gesture usability protocol (XS, owner/device step, W11)
- Write the protocol and the result sheet (3 to 5 sessions, tasks and pass thresholds of ux.md ED-17): find annotated photos, find a file attachment, place a two-point measurement (quick tap, hold, Crosshair), undo or clear an accidental point, re-find Library after dismissing the notice; record wrong taps and time to first correct tap, and test the labels Annotated vs measured and the Graph section name. Output: a results table in `validation.md`; failures become I18n/hint edits (D.1a, C.9a).
- Files: `implementation/validation.md`

### Story D.10: Accessibility pass on the new nav and Library
**As a** TalkBack user, **I want** a logical focus order and large targets, **so that** I can navigate.
**Acceptance Criteria**:
- Focus order follows visual order; every row and chip >= 48 dp; text not clipped at 200% font scale.
  - *Given* font scale 2.0 and a 411 dp width, *When* the sheet renders, *Then* no description is clipped beyond two lines and row heights >= 48 dp.
  - *Given* the Library filter bar at font scale 2.0, *When* inspected, *Then* the bar is the compact [Filters (n)] button with the sheet (LD-6, no hidden horizontal scroll); inside the sheet chips wrap without clipping, each is >= 48 dp, announces its selected or dimmed-with-reason state, and the groups are labelled ("Filter by media type", "Show", "Filter by tag").
**Files**: `CM/ui/components/NavSectionList.kt`, `AM/ui/MoreSheet.android.kt`, `LIB/LibraryFilterBar.kt`.
**Tests first**: `AUT/ui/nav/NavAccessibilityTest` (androidUnitTest, font scale 2.0).

##### Task D.10a: Accessibility test + fixes (S)
- Also `ScreenHeadingsTest` (Library title and filter group labels, Settings > Annotation, dialogs and sheets incl. Help: placing points and Delete file are headings with pane titles; ux.md S18, ED-25).
- Files: `AUT/ui/nav/NavAccessibilityTest.kt`, `AUT/ui/ScreenHeadingsTest.kt`, `CM/ui/components/NavSectionList.kt`, `LIB/LibraryFilterBar.kt`

### Story D.11: Route compatibility and retiring the old screens
**As a** maintainer, **I want** every old entry point to keep working and the old screens deleted only after parity, **so that** the merge breaks no saved state or link.
**Acceptance Criteria**:
- Old routes land on Library.
  - *Given* `navigateTo(Screen.Gallery)`, *When* it runs, *Then* the screen is `Library(initialFilter = Annotated)` and Back returns to the previous screen (no alias entry in the back stack).
  - *Given* global search "Show all" for "kitchen" (`GraphDialogLayer.kt:130`), *When* tapped, *Then* Library opens with search "kitchen".
- Old screens are removed only after parity.
  - *Given* every row of the D.6 parity table green, *When* D.11b runs, *Then* `GalleryScreen`, `GalleryViewModel`, `GalleryEmptyState`, `AssetBrowserScreen`, `AssetBrowserViewModel` and `AssetItemCard` are deleted, `rg 'GalleryViewModel|AssetBrowserViewModel'` returns nothing in `kmp/src/commonMain`, and the build, the wiring gate and the first-open gates pass.
**Files**: `CM/ui/AppState.kt`, `CM/ui/StelekitViewModel.kt`, `CM/ui/ScreenRouter.kt`, `LIB/LegacyScreenAlias.kt` (new), and the deleted files above.
**Tests first**: `BT/ui/nav/LibraryRedirectTest`.

##### Task D.11a: `Screen.Library`, alias redirects and router branch (S)
- Add `Screen.Library`; **the `NON_NAV_REDIRECT` classification is NEW work** (`rg NON_NAV_REDIRECT kmp/src` finds nothing in `AppState.kt` or anywhere else on 2026-11-08; D.2a defines the type, D.11a applies it to `Screen.Gallery` and `Screen.AssetBrowser`); the redirect in `navigateTo` (applying the entry-point normalization of ux.md LD-8), `LegacyScreenAlias`, the router branch (with `NavigationTracingEffect("Library")`) and the `Library` help-exempt/`HelpPage` annotation decision (follows `@HelpExempt` on the old screens).
- Files: `CM/ui/AppState.kt`, `CM/ui/StelekitViewModel.kt`, `CM/ui/ScreenRouter.kt`, `LIB/LegacyScreenAlias.kt`, `BT/ui/nav/LibraryRedirectTest.kt`
##### Task D.11b: Retire `GalleryScreen`, `AssetBrowserScreen` and their ViewModels (S)
- Delete only after the D.6 parity table is green; keep `AssetDetailScreen`. Update Bazel `srcs` if the targets list files explicitly.
- **Split trigger**: if the deletion touches more than 5 files beyond the six listed classes, if `rg 'GalleryViewModel|AssetBrowserViewModel|AssetItemCard' kmp androidApp` finds a reference outside the listed files, or if the Bazel `srcs` edit and test fixes are non-mechanical, split into D.11b-1 (delete the screens and ViewModels) and D.11b-2 (BUILD `srcs`, test and doc fallout). D.11b remains the last Epic D merge.
- Files: `CM/ui/gallery/*` and `CM/ui/assets/AssetBrowser*`, `CM/ui/assets/AssetItemCard.kt` (deletions), `kmp/src/commonMain/kotlin/BUILD.bazel` (only if it lists them)

---

## Cross-cutting rules respected

- **Arrow `Either`** at repository boundaries (C.5, D.7a, D.7b); no `Result`/nullable domain errors.
- **`DatabaseWriteActor` / `@DirectSqlWrite`**: no new mutating queries on `SteleDatabaseQueries`; D.7a adds read-only queries; C.5c/C.5d route every measurement-annotation write through `DatabaseWriteActor` (no `DirectRepositoryWrite` opt-in left in `ANN/`).
- **No `rememberCoroutineScope` escaping**: `EditorDiagnosticsSampler` and `AnnotationPersistenceQueue` own their scopes internally with a `CoroutineExceptionHandler` and an explicit `close()`; `UndoHistory` is a pure data structure and owns no scope; verified by `rg "remember.*scope"` in review.
- **Graph-scale reads**: D.7 (Library, query-level `annotated` join, no unbounded getAll); `QueryPlanAuditTest` extended.
- **Wiring gates**: `WiringCompletenessTest` (D.5), `first-open-screens.txt` + `FirstOpenSmokeTest` (D.4, D.8), `TestPlacementAuditTest` / `TestHygieneAuditTest` (all new tests placed per the table below; no `Thread.sleep` in businessTest/jvmTest; shared fixtures live in commonTest or are duplicated by design, never imported from `androidInstrumentedTest` into jvmTest).
- **New test class checklist**: every new commonTest class needs a `business_common_tests` srcs entry in `kmp/src/commonTest/kotlin/BUILD.bazel` and an `AllBusinessTests.kt` registration; every new businessTest class needs the `AllBusinessTests` registration (Story B.8). Acceptance: a `bazel test //kmp:business_tests --test_output=all` run lists every new class name (`scripts/check-bazel-test-listing.sh`); an unlisted class is a failed story.
- **Bazel vs Gradle**: pure/business tests run under `bazel test //kmp:business_tests`; Robolectric under `bazel test //kmp/src/androidUnitTest/kotlin:android_unit_tests --config=android`; all instrumented work is Gradle `connectedDebugAndroidTest` (Epic 7 not done). New Bazel test sources are added to the relevant `BUILD.bazel`.
- **Test placement** (CLAUDE.md): pure logic -> `commonTest`; no-UI behaviour -> `businessTest`; Compose-behaviour not needing pixels -> `androidUnitTest` (Robolectric); `jvmTest` only for real-renderer or desktop-specific; on-device resource work -> `androidInstrumentedTest`. `scripts/jvm-display-check.sh` precedes any `jvmTest` UI run.
- **SQLDelight**: regenerate and commit `kmp/src/generated/sqldelight/` and `sqldelight-telemetry/` when `.sq` changes (C.1d/C.1f, D.7a, D.7c); the `SQLDelight generated sources` job is not a required check today, so each such story runs the local diff and `gh pr checks` fallback (B.6e).
- **Privacy of CI outputs**: only synthetic fixtures leave the runner; `scripts/check-artifact-privacy.js` gates artifact upload, `benchmarks/**` history JSON and PR comments (B.6d).
- **Surface-task ownership of accessibility**: every task that adds UI uses the C.9d tokens, the C.9i timeout helper and honours reduced motion, with its own contrast/target assertion.
- **Release**: conventional commits (`fix:`, `feat:`, `test:`, `ci:`); one PR per story keeps release-please notes meaningful.

## Test-first plan summary (class and source set)

| Story | Test class(es) written first | Source set |
|---|---|---|
| A.2 | `ExitRecordFormatterTest`, `ProcStatParserTest` | commonTest |
| A.2 | `AnnotationEditorCrashGuardTest`, `EditorMemoryPressureTest` | businessTest |
| A.2 | `EditorDiagnosticsSamplerTest` | androidUnitTest |
| A.3 | `AnnotationPlacementResourceTest`, `AnnotationLifecycleResourceTest` | androidInstrumentedTest |
| A.4 | `EditorRecompositionTest` | androidUnitTest |
| A.4 | `ImageLoaderConstructionCountTest` | businessTest (pure seam, no UI) |
| A.4 | `MeasurementLabelCacheTest` | androidUnitTest |
| A.3 | `BackgroundPositiveControlTest` | androidInstrumentedTest |
| A.5 | `ImageSamplingTest` (extended) | commonTest |
| A.5 | `LoupeDegradationTest`, `LoupeStrategySpikeTest` | androidUnitTest / androidInstrumentedTest |
| B.1 | `ResourceProbeSelfTest` | androidInstrumentedTest |
| B.2 | `CoilSeamTest`; `SteleKitImageLoaderMemoryTest` (extended) | businessTest; jvmTest |
| B.2 | `UndoDepthTest` | businessTest |
| B.3 | `LibraryScrollSwitchResourceTest`; `FixtureGraphCountsTest` (over commonTest `FixtureGraphSeeds`) | androidInstrumentedTest; businessTest |
| B.4, B.5, B.7 | `benchdiff-export.test.js`, `bench-budget-check.test.js`, `bench-budget-calibrate.test.js`, `android-benchmark-summarize_test.py` | scripts (node/python) |
| B.6 | `AnnotationRapidPlacementSmokeTest` | androidApp androidTest |
| C.1 | `CalibrationDecodeInvarianceTest`, `NativeImageSizeTest`; `ImageBoundsTest`, `LegacyDisplayGoldenTest`; `LegacyCalibrationFlagTest`, `CalibrationBasisMigrationTest`, `CalibrationBasisDowngradeTest`, `MigrationRunnerSchemaSyncTest` (ext.) | commonTest; androidUnitTest; businessTest |
| C.2 | `LoupeLayoutPropertyTest` / `LoupeNarrowCanvasTest` | commonTest / androidUnitTest |
| C.3 | `UndoHistoryTest` | businessTest |
| C.5 | `AnnotationPersistenceQueueTest`, `EditorInitialLoadRaceTest`, `MeasurementAnnotationActorRoutingTest` | businessTest |
| C.6 | `UnsavedChangesDialogTest` | androidUnitTest |
| C.7 | `AnnotationExporterTest` (extended); `AndroidAnnotatedImageRendererTest` | jvmTest (real renderer); androidUnitTest |
| C.8 | `AnnotationSemanticsTest`, `HapticWiringTest`, `PlaceAtCrosshairControlsTest`, `MoveVertexActionsTest`; `CrosshairPlacementTest`, `CrosshairMoveVertexTest`, `CrosshairKeyHandlerTest` | androidUnitTest; businessTest |
| C.9 | `LoupeEdgeTest`, `InProgressCancelTest`, `EditorLoadingStateTest`, `EditorFocusOrderTest`, `EditorTouchTargetAuditTest`, `LiveRegionPolitenessTest`, `TimedMessageAccessibilityTest`, `ReducedMotionTest`; `PlacementGestureClassifierTest`; `EditorStateAtomicityTest`, `AssetDetailAnnotateOrphanTest` | androidUnitTest; commonTest; businessTest |
| C.10 | `AreaCloseTolerancePropertyTest`; `CaptureToEditorTest` | commonTest; androidApp androidTest |
| D.2 | `NavSectionBuilderTest`; `NavModelExhaustivenessTest` (reflection, JVM) | commonTest; businessTest |
| D.3 / D.4 / D.10 | `SidebarSectionsTest`, `MoreSheetTest`, `NavAccessibilityTest` | androidUnitTest |
| D.5 | `NavCommandsTest` | commonTest |
| D.6 | `LibraryScreenFilterTest`, `LibraryCardSemanticsTest`, `LibraryEmptyStateTest`, `LibraryErrorStateTest`, `LibraryMissingFileTest` | androidUnitTest |
| D.7 | `LibraryAnnotatedJoinTest`, `LargeLibraryBatchingTest`, `LibraryViewModelStateTest`; `QueryPlanAuditTest`, `MigrationRunnerSchemaSyncTest` (ext. for indexes) | businessTest; jvmTest |
| D.11 | `LibraryRedirectTest`; `PersistedScreenIdTest` (D.8c) | businessTest |
| B.8 | all rows above | each class registered in `business_common_tests` and/or `AllBusinessTests` |
| D.9 | `LibraryTipTest`, `LibraryUpgradeNoticeTest`, `LibraryNewBadgeTest`, `NavFirstOpenTaskTest` | androidUnitTest |
| D.2 (Repair 4) | `NavLabelUniquenessTest` | businessTest |
| D.6 / D.7 (Repair 4) | `LibraryFilterRulesTest`, `LibraryFilterRulesPropertyTest`; `LibraryCompactFilterTest`, `LibraryConfigChangeTest`, `LibraryKeyboardNavigationTest`, `LibraryOverflowAndDeleteTest`, `LibraryThumbnailPlaceholderTest`; `LibraryFilterSqlParityTest` | commonTest; androidUnitTest; businessTest |
| A.0 / C.8 / C.9 (Repair 4) | `LogRedactorTest`, `LogRedactionTest`; `HandleHitResolverTest`, `CanvasHandleEquivalentPathTest`, `EditorSnackbarQueueTest`, `NudgeStepTest`, `AccessibilityAutoCrosshairTest`; `PointerTypeGestureTest`, `CancelZoneFeedbackTest`, `EditorLoadGenerationTest`, `EditorConfigChangeTest`, `EditorKeyboardFocusTest`, `AnnotationSettingsTest`, `EditorOverflowMenuTest` | commonTest / androidUnitTest / businessTest per the task Files |
| D.6 / D.7 / D.9 / C.8 / C.9 (Repair 5) | `LibraryMediaClassTest`, `LibrarySearchTest`, `LibraryStatusLineTest`, `UpgradeNoticeStateMachineTest`, `PlacementCancelZoneTest`, `EditorBackPrecedenceTest`; `LibraryAddResultTest`, `LibraryAddAnchorTest`, `ScreenHeadingsTest`, `NavShellConfigChangeTest`, `QuickTapModeSwitchTest`, `SystemGestureInsetPressTest`, `CrosshairLayoutTest`, `EditorProcessDeathRestoreTest`; `LibraryFilterSqlParityTest` (search, keyset and membership cases added) | commonTest; androidUnitTest; businessTest |

## CI integration tasks (consolidated)

| Task | Change |
|---|---|
| B.4b | Extract summarizer; keep `ANDROID_BENCH` parsing intact |
| B.4c | `benchdiff-export.js --kind android-resource` |
| B.5a | Node budget evaluator + tests wired into an existing script-test step |
| B.6a | Third `gradlew` invocation in `android-benchmark.yml`, RAM pin, budget step (`if: always()` for the comment, then fail), artifact upload on failure |
| B.6b | `AnnotationRapidPlacementSmokeTest` in `android-smoke` |
| B.6c | `LoupeAlignmentE2ETest` in the class list after #560 |
| B.6d | `scripts/check-artifact-privacy.js` before `upload-artifact`, before the sticky comment and over `benchmarks/**` additions |
| B.6e | Read back rulesets / branch protection; local `git diff --exit-code kmp/src/generated/` + `gh pr checks` fallback |
| B.8c | `scripts/check-bazel-test-listing.sh` as the verify step of every story that adds a test class |
| every workflow edit | `actionlint -color`; `uvx 'zizmor==1.25.2' .`; pin any new action with the `gha-pin-actions` convention |

## Effort estimate

**How the CU figures are derived (reproducible from `~/.claude/skills/sdd/skills/ESTIMATION.md`)**. Weights: fresh input 1, cache write 1.25, cache read 0.1, output 5. Assumed **input cache mix for every row** (INFERRED; agents re-read the same plan and repo context across review and repair loops): 25% fresh, 5% cache write, 70% cache read, so the blended input weight is 0.25 x 1 + 0.05 x 1.25 + 0.70 x 0.1 = **0.3825 CU per input token**. For an output share `s`, CU per raw token = `5s + (1 - s) x 0.3825`. Three task classes, shown per row in the "Output share" column:

| Class | Output share `s` | CU per raw token | Applies to |
|---|---|---|---|
| Implementation (code, tests, config) | 12% | 0.937 | every story not listed below |
| Measurement, spike, CI wiring (large tool-output reads, little writing) | 8% | 0.752 | A.3, A.5, B.3, B.5, B.6, B.7 |
| Decision / document (mostly writing) | 20% | 1.306 | A.1, D.1 |

Row formula: raw tokens = sum of task midpoints (XS 20k, S 55k, M 130k); **CU = raw x CU/token(row class) + 0.75 x raw x 0.729**, where the second term is the x1.75 verification multiplier's extra 0.75 x raw tokens priced as review runs (7.5% output, 0.729 CU per token; ESTIMATION.md suggests 5-10% for review). "With x1.75 verification" is the plain token figure raw x 1.75. Worked row, A.2: raw 370k (4S + 1M + 1XS = 220 + 130 + 20); 370 x 0.937 = 346.6; 0.75 x 370 x 0.729 = 202.3; CU = 548.9k, table 549k. (Repair 2 used a flat 0.93 CU/token on the verified total; the per-row method above replaces it, so rows are not comparable to the old column one for one.)

| Story | Tasks | Cost classes | Raw tokens (k, tasks only) | Output share | With x1.75 verification (k) | Est. CU (k) | Wall-clock blocker |
|---|---|---|---|---|---|---|---|
| A.1 Owner-evidence capture and triage table | 1 | 1XS | 20 | 20% | 35 | 37 | W1 owner Logs line |
| A.0 Diagnostics-first release (PR-0), ships ahead of the wave | 5 | 4S, 1XS | 240 | 12% | 420 | 356 | W9 release cut (A.0d) gates all behaviour PRs; W3 dogfood starts on it |
| A.2 Production diagnostics without logcat | 7 | 5S, 1M, 1XS | 425 | 12% | 744 | 630 |  |
| A.3 Widened repro promoted to tracked tests | 6 | 4S, 2M | 480 | 8% | 840 | 623 | A.3f: #560 merged, or stacked (no owner wait) |
| A.4 Cheap CPU/memory fixes, each proven by a before/after probe delta | 5 | 4S, 1M | 350 | 12% | 612 | 519 |  |
| A.5 Loupe strategy via measured spike (ADR-003) | 3 | 1S, 2M | 315 | 8% | 551 | 409 |  |
| B.1 `ResourceProbe` core | 4 | 3S, 1XS | 185 | 12% | 324 | 274 |  |
| B.2 Test seams in production code | 5 | 2S, 3XS | 170 | 12% | 298 | 252 |  |
| B.3 Scenarios (a)-(g) plus CPU scenarios | 5 | 3S, 1M, 1XS | 315 | 8% | 551 | 409 |  |
| B.4 Emission and benchdiff reporting | 3 | 3S | 165 | 12% | 289 | 245 |  |
| B.5 Budgets file, evaluator, mutation and pre-fix proofs | 4 | 3S, 1M | 295 | 8% | 516 | 383 |  |
| B.6 CI integration | 5 | 2S, 1M, 2XS | 280 | 8% | 490 | 364 | W6 #560 merge (B.6c only); W10 optional ruleset (B.6e) |
| B.7 Calibration and gate promotion | 2 | 1S, 1XS | 75 | 8% | 131 | 97 | W5 >=10 CI runs |
| B.8 New-test-class registration and Bazel verification | 3 | 2S, 1XS | 130 | 12% | 228 | 193 |  |
| C.1 Calibration is independent of decode size (CRITICAL C1, ADR-004) | 7 | 6S, 1M | 460 | 12% | 805 | 682 |  |
| C.2 Loupe cannot crash on narrow or wide images (CRITICAL C2) | 2 | 1S, 1XS | 75 | 12% | 131 | 111 |  |
| C.3 Undo never resurrects in-progress points (CRITICAL C3, ADR-005) | 2 | 1S, 1M | 185 | 12% | 324 | 274 |  |
| C.5 Serialized persistence, no lost or resurrected rows (MAJOR M1/M7, ADR-005) | 5 | 1S, 4M | 575 | 12% | 1006 | 853 |  |
| C.6 Honest unsaved-changes behaviour (MAJOR M2) | 2 | 2S | 110 | 12% | 192 | 163 |  |
| C.7 Export is correct (MAJOR M5) | 3 | 2S, 1M | 240 | 12% | 420 | 356 |  |
| C.8 Accessibility, haptics, non-drag placement (MAJOR M3/M4) | 9 | 4S, 3M, 2XS | 650 | 12% | 1138 | 964 |  |
| C.9 Loupe edges, double decode, and minor findings (MAJOR M6, minors m3-m7) | 13 | 13S | 715 | 12% | 1251 | 1061 |  |
| C.10 Remaining test-coverage gaps (features.md section 5) | 2 | 2S | 110 | 12% | 192 | 163 |  |
| D.1 Decision recorded (ADR-006 DECIDED, owner, 2026-11-08) | 2 | 2XS | 40 | 20% | 70 | 74 | none (DECIDED 2026-11-08) |
| D.2 Shared `NavSection` model (ADR-007) | 2 | 1S, 1XS | 75 | 12% | 131 | 111 |  |
| D.3 Sidebar sections with descriptions | 2 | 1S, 1M | 185 | 12% | 324 | 274 |  |
| D.4 Phone "More" opens a grouped sheet (ADR-007) | 3 | 1S, 1M, 1XS | 205 | 12% | 359 | 304 |  |
| D.5 Command palette navigation commands | 1 | 1S | 55 | 12% | 96 | 82 |  |
| D.6 The Library screen (merges Gallery and Assets behaviours) | 7 | 6S, 1M | 460 | 12% | 805 | 682 |  |
| D.7 Library reads are bounded (ADR-008; refactor-first) | 4 | 2S, 2M | 370 | 12% | 648 | 549 |  |
| D.8 Touch-point updates and gates | 4 | 4S | 220 | 12% | 385 | 326 |  |
| D.9 First-run tip, upgrade notice and UX review | 5 | 4S, 1XS | 240 | 12% | 420 | 356 | W11 cold-user sessions (D.9e, owner/device) |
| D.10 Accessibility pass on the new nav and Library | 1 | 1S | 55 | 12% | 96 | 82 |  |
| D.11 Route compatibility and retiring the old screens | 2 | 2S | 110 | 12% | 192 | 163 |  |
| **Total** | **136** | | **8580** | | **15014** | **12421** | |

Totals recounted by script (`recount.py`, described in Repair 4 and re-run in Repair 5: it parses the `### Story` and `##### Task` headings and their `(XS|S|M)` size tags, applies the weights above and rewrites this table so every total equals the sum of its rows) from the headings of this file: 34 stories, 136 tasks, 8,580k raw tokens, 15,014k with verification, 12,421k CU. Class midpoints are INFERRED (ESTIMATION.md), not measured on implementers. Tasks formerly gated on owner decisions (C.8c and the conditional sub-items) are now unconditional and counted at full cost. C.1d, C.1e, C.5c and C.9d were narrowed in Repair 3 (their remainders became C.1f, C.1g, C.5e and the surface-ownership rule), so their class labels moved with them.

**Planning overhead already spent** (research agents plus this plan) is not included above. **Size band**: about 12.4M CU including verification, still the Large band (3-15M CU) matching the requirements.md estimate (Repair 3 reported 11.3M; Repair 4 added 13 tasks and Repair 5 adds 2 tasks and no story, itemised in the Repair log; the Repair 5 UX decisions are folded into existing tasks, so their cost is carried by the size classes of D.6a/D.6f/D.7a/C.9, INFERRED). Informational only; not a scope question.

**Critical path (agent waves)**: W-0 {PR-0: A.0a-c, A.2a/b/e/f, minimum A.2d} -> **W9 release cut (A.0d, wall-clock)** while non-behaviour work runs in W-A {B.1, B.2, B.4a, A.2c, C.1a, C.2a, D.1, D.2, D.7a/c, B.6d} -> W-B {A.3f (#560 rebase or stack), B.4b-c, B.5a-b, B.5d, C.9d, C.5c+C.5e, C.3, D.7b, D.11a} -> W-C {A.3a-e, B.3, A.4a-e, C.5a/b/d, C.1b-g, D.6, D.3} -> W-D {A.5a, B.5c, D.4, D.5} -> W-E {A.5b-c, B.6, B.6e, C.2b, C.6-C.9, D.8, D.9a/c} -> W-F {B.7a, D.9b, D.10, D.11b, final verify}. Seven serial waves after PR-0 (PR-0 is the first merge and release; the probe-only PR is the second). Stories within a wave touch disjoint files except `AnnotationEditorViewModel.kt` (A.2b, B.2c, C.3b, C.5b, C.8c, C.9c, C.9g) and `AnnotationEditorScreen.kt` (A.4a-b, A.5, C.6, C.8c-g, C.9) — these serialize inside their wave in the order listed; Epic D files (`ScreenRouter.kt`, `StelekitViewModel.kt`, `AppState.kt`: D.4c, D.8b, D.11a) serialize the same way.

**Overrun checkpoint**: if cumulative spend exceeds 25% over the table value for completed stories (e.g. more than 1.25 x the CU shown for stories done so far), pause and report the delta with its cause (wrong class vs real surprise). Informational, not an invitation to cut. Likeliest overrun sources: C.5d/C.5c (real-actor ordering tests), C.1e/C.1g (golden fixtures for the display surfaces), A.5a/A.5b (loupe strategy depends on the spike; A.5b may be under-classed if an Android `LoupeSource` plus parity test is needed), D.6a and D.7a/D.7b (the merged Library screen, query-level join and the parity table are the largest new surface), C.8c/C.8f (crosshair plus non-drag move), B.6a (emulator CI iteration, RAM-pin dry run), A.3d/A.3e (positive control and n>=10 noise floor on the CI emulator).

**Wall-clock blockers**
1. W1 - Owner (Tyler) pastes the in-app Logs `Previous process exit` line, phone model, Android version, foreground/background at death (A.1). Does not block A.2-A.4.
2. ~~W2 - IA decision~~ DECIDED 2026-11-08 (merge into one Library); no longer a blocker. Epic D is unblocked.
3. W3 - Owner (Tyler) dogfoods the PR-0 release APK (Story A.0), then later releases, after Epic A merges and reports whether kills recur (confirms or refutes any "fixed" claim). **Soak window (requirements Success Metric 6)**: at least 10 qualifying editor sessions over at least 14 days, evaluated by the owner on day 14 from the in-app Logs screen; if fewer than 10 sessions, one 14-day extension, then an owner decision. W3 is the longest wall-clock item in the project (14 to 28 days after the Epic A release) and runs in parallel with Epics C and D.
4. W4 - Owner (Tyler) physical low-RAM phone run of the probe or the soak (unresolved question).
5. W5 - At least 10 CI runs of `android-benchmark.yml` on the merged harness before gate promotion (B.7b); CI queue time.
6. W6 - PR #560 merge by its author before B.6c only. The loupe work itself does not wait (A.3f stacks on #560 or lands it first).
7. W7 - Emulator/CI runs for every benchmark-affecting change; the 5-minute stock-kill soak is manual or scheduled, not per-PR.
8. ~~W8 - OD-1..OD-4~~ DECIDED 2026-11-08; no longer a blocker.
9. **W9 - PR-0 release cut (task A.0d), owner Tyler (or an authorised `workflow_dispatch`)**: after PR-0 merges, the standing release-please PR must be merged and the release workflow must publish the APK. Gates every A.4, A.5 and Epic C behaviour PR; on the critical path to the first behaviour change. Non-behaviour work continues meanwhile.
10. W10 - Optional, owner Tyler: add `SQLDelight generated sources` to a ruleset as a required check (B.6e). Not blocking; the local-diff fallback is in force until then.
11. W11 - Owner (Tyler) or recruited cold users: 3 to 5 cold-user sessions and the editor-gesture usability checks (task D.9e, ux.md ED-17) on the phone shell after the Library UI and the placement changes are released to a build; also the Pixel_Fold fold/unfold pass (C.9k, D.6e). Does not block merges; its failures become I18n/hint edits.

_Basis: bands and weights INFERRED; recalibrate after the first implementer run._


---

## Verification checklist (Phase 6 will run these)

- PR-0 released (release PR merged, release workflow green, APK attached; task A.0d, W9) before any A.4/A.5/C behaviour PR merged (Story A.0); `ForcedKillReplayTest` two-run evidence attached.
- **Planned (B.5/B.5a), not yet runnable**: `scripts/bench-budget-check.js` (budget evaluator) and the budgets file `benchmarks/budgets/annotation-resource-budgets.json` do not exist until B.5; every `gate` entry must have a `mutation` name and a recorded red run; `globalMode` is `report-only` until B.7b.
- Legacy display goldens (editor, any Library card showing a distance, export) pass over the pre-fix fixture DB (C.1g); `RealActorOrderingTest` passes against the real `DatabaseWriteActor` (C.5d).
- Release APK contains no `Mutations` symbol (B.5d).

- `bazel test //kmp:business_tests` and the Robolectric target; `scripts/jvm-display-check.sh -- bazel test //kmp:jvm_tests ...` for jvmTest.
- `./gradlew :kmp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=...` on Pixel_Fold AVD (no logcat; read `kmp/build/outputs/androidTest-results/connected/debug/*.xml`).
- `node --test scripts/*.test.js` (the test files that exist; `bench-budget-check.test.js`, `bench-budget-calibrate.test.js`, `check-artifact-privacy.test.js` and `benchdiff-export.test.js` join as their tasks land), `actionlint -color`, `uvx 'zizmor==1.25.2' .`.
- SQLDelight regeneration diff clean after C.1f/D.7a/D.7c (`git diff --exit-code kmp/src/generated/`); `gh api repos/tstapler/stelekit/rulesets` and branch protection read back (B.6e) and `gh pr checks` shows `SQLDelight generated sources` green before each such merge.
- **Planned (B.6d), not yet runnable**: `node scripts/check-artifact-privacy.js` must pass over the artifact directory, `benchmarks/**` additions and the comment body; a planted real path must make it exit 1. The script does not exist until B.6d lands.
- Library parity table (D.6) fully green before D.11b deletes the old screens; `rg 'GalleryViewModel|AssetBrowserViewModel' kmp/src/commonMain` returns nothing afterwards; old routes redirect (`LibraryRedirectTest`, `PersistedScreenIdTest`).
- `NavFirstOpenTaskTest` passes (annotated photos and a file attachment located from the nav alone, no wrong taps); upgrade notice shown once to an existing install (`LibraryUpgradeNoticeTest`).
- Editor accessibility suite green (C.9h, C.9i) and the manual TalkBack pass on the owner's phone recorded (validation.md device evidence).
- Library filter rules: `LibraryFilterRulesPropertyTest` (no contradiction reachable by enabled chips) and `LibraryFilterSqlParityTest` (SQL equals the in-memory reference) green; `NavLabelUniquenessTest` and `SidebarSectionsTest.graphStatsRename` green (no "Library Stats" row, exactly one "Library").
- Library search/paging (Repair 5): `LibraryFilterSqlParityTest` agrees with the reference filter on multi-word, `%`/`\` literal, 1-character, ASCII-case and non-ASCII-case queries, on keyset paging with no repeat or skip over 8,030 rows in all four sorts, and on the `AssetMediaType` membership table; spike D.7a-0 output pasted in ADR-008 before D.7a starts.
- **C-findings checklist (requirements Success Metric 5)**: each finding has a fixing task and a green test, or a tracked follow-up link, and the owner reviews the list: C1 -> C.1, C2 -> C.2, C3 -> C.3, M1/M7 -> C.5, M2 -> C.6, M3/M4 -> C.8, M5 -> C.7, M6 -> C.9, m3-m8 -> C.9/C.10 (finding text: research/features.md section 3).
- `rg NON_NAV_REDIRECT kmp/src` matches only after D.2a/D.11a (it is new work); `rg 'Library Stats' kmp/src/commonMain` returns nothing after D.3b.
- Cold-user sessions (D.9e, W11) and the Pixel_Fold fold/unfold pass recorded in validation.md; `LogRedactionTest` green (A.0e).
- For each gate: the pre-fix proof procedure (probe-only PR on unfixed behaviour, fix-only revert, or `-Pannotate.mutation=<name>` run) with its red result attached to the PR (tasks B.5c, B.5d).
- **Planned (B.8c), not yet runnable**: `scripts/check-bazel-test-listing.sh <new classes>` must list every new test class (Story B.8); the script does not exist until B.8c lands (until then use `bazel test //kmp:business_tests --test_output=all` and read the class names). `bazel build //kmp:android_app --config=android` for each Android-source task.
- `rg "DirectRepositoryWrite" kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/` returns no matches (C.5).


---

## Repair log

Repair pass of 2026-11-08 against `architecture-review.md` and `adversarial-review.md`. Task counts recounted by script: 93 `##### Task` headings, 32 `### Story` headings, effort table sums 93 tasks / 10,666k with-verification tokens / 9,927k CU (was 77 / 9,231k / 8,585k; the +16 tasks are listed below).

| Finding | Exact edit |
|---|---|
| Arch BLOCKER 1: measurement writes bypass `DatabaseWriteActor` | Tech Debt Disposition: new row "SqlDelightMeasurementAnnotationRepository" (Refactor-first, cites `:39-117`, `DatabaseWriteActor.kt:763`, VM opt-in lines). Story C.5: AC 5 rewritten (zero `DirectRepositoryWrite` in `ANN/`, single/batch/undo-set-save/calibration paths each one actor call), Files updated, `persistLock` removal in C.5a. C.5c rewritten from "evaluate" to Refactor-first M before C.5a; new C.5d routes VM paths. ADR-005 consequence amended in C.5c; `research/architecture.md` sections 2 and 4 correction listed in C.5c Files. Cross-cutting rule updated. |
| Arch BLOCKER 2: Bazel registration of commonTest classes | New Story B.8 (B.8a/b/c) with verified facts (`commonTest/kotlin/BUILD.bazel:104-133` explicit srcs, `AllBusinessTests.kt` suite, `AllBusinessTestsCompletenessTest`), the new test class checklist table, acceptance criterion that `bazel test //kmp:business_tests --test_output=all` lists every class, `scripts/check-bazel-test-listing.sh`. Cross-cutting rule, CI table (B.8c), verification checklist and test summary updated. Note: businessTest classes also need `AllBusinessTests` registration. |
| Arch concern: `MemoryPressureListeners` trim level | Task A.2e (typed `MemoryPressureLevel`, callers listed); A.2 AC 3 reworded; A.2c depends on it; disposition row. |
| Arch concern: diagnostics sampler port | Task A.2f (commonMain `EditorDiagnosticsSource/Sink`), A.2 Files, A.2d dependency and Bazel verify step. |
| Arch concern: iOS/wasmJs actuals | A.5 Files and A.5b: commonMain `LoupeSource` interface, no `expect`; if expect kept, all four actuals listed. C.1: `ImageBoundsReader` `fun interface`, `NativeImageSize.oriented` pure, no expect/actual; `NativeImageSize` moved to `platform/image/`. |
| Arch concern: paged Gallery vs in-memory sort/tags | D.7: new AC (SQL `ORDER BY` per `GallerySortOrder`, bounded `getDistinctAnnotationTags()`, paging state in `GalleryState`), D.7a/b updated, disposition row updated. |
| Arch concern: crosshair out of the VM | C.8c: `CrosshairPlacementState` pure class, one VM field; disposition row with no-net-growth `wc -l` rule. |
| Arch concerns folded in: queue scope ownership, `LoupeLayout.kt` vs `LoupeGeometry.kt`, `NavDestinationSpec` icon key, MoreSheet state, Bazel android verify, jvmTest placement of Coil tests, D.6a join key | C.5 Files (queue owns scope + `close()`, `UndoHistory` owns none); C.2 Files (`LoupeGeometry.kt`); Glossary + D.2 (`NavIconKey`); Task D.4c (`AppState.moreSheetOpen`); Bazel verify steps in A.2d and D.4a; Coil tests moved to businessTest (A.4c, B.2); D.6a join key and `getAnnotatedKeys` signature. |
| Re-review 1 concerns (arch: C.5c call sites + RestrictedDatabaseQueries + no re-entrancy; B.8c derive classes from git diff; adversarial: mutation seam in probe-only PR) | C.5c, B.8c, B.5d text amended | 
| Adv B1: gates pass pre-fix; counter lives in A.4c; `408907d9cd` has no harness | Task B.1d probe-only PR (first merge, no behaviour change); Task B.2d moves the loader-construction counter out of A.4c (A.4c text amended); Story B.5 pre-fix proof procedure (4 steps) and per-gate proof matrix with "cannot be proven" column; B.5c rewritten; new Task B.5d test-only mutation flags (incl. mandatory `backgroundBusyLoop` and `exitOnBackground`); dependency diagram and waves updated; Success Metric 1 acceptance added to Unresolved Questions. |
| Adv B2: no positive control for the background repro | Task A.3d (positive control, importance recorded via `getRunningAppProcesses()`/`oom_score_adj`, outcome in ADR-002); A.3 AC rewritten; "Claim vocabulary" paragraph after the What-we-know table (only "mitigated and monitored" without the owner's `importance=` line). |
| Adv B3: C.1 legacy flag needs a persisted marker | Migration Plan rewritten: real migration (`image_annotations.calibration_basis_version`, steps incl. `MigrationRunner`, `RestrictedDatabaseQueries`, generated sources rsync, `MigrationRunnerSchemaSyncTest`, pre/post row test); Task C.1a produces an ADR-004 amendment before any code; new Task C.1d (M); C.1 AC and Files updated; C1 labelled unconfirmed until the C.1a log. |
| Adv concern: n=1 inflated harness | A.3 AC (harness provisional), Task A.3e (n>=10 direct `dispatchTouchEvent` noise floor), A.4 Keep rule uses the measured band, A.4 goal reworded to "suspected hot spots". |
| Adv concern: idle-CPU gate and 60% contradiction | B.5b: idle CPU and sustained CPU `ReportOnly` until B.7 (median of 3 windows); decode-byte thresholds not fixed before A.5a; A.3 AC states window and denominator; ADR-001 amendment listed in B.5b. |
| Adv concern: ordering, #560 | Unresolved Questions (hard prerequisite); Task A.3f; C.2 (C.2b needs A.4a + #560); C.9a prerequisites; dependency diagram; D.4 AC moves the `first-open-screens.txt` update into the D.4 PR. |
| Adv concern: D.7 index may be a migration | Migration Plan (indexes are migrations); Task D.7c with `MigrationRunner` entry, `MigrationRunnerSchemaSyncTest` extension, ADR-008 amendment. |
| Adv concern: test placement | B.3: shared seeds in commonTest (`FixtureGraphSeeds`), AIT wrapper, `FixtureGraphCountsTest` in businessTest; D.2: compiler-exhaustive `when` + businessTest `NavModelExhaustivenessTest` replaces the commonTest reflection test; `MeasurementLabelCacheTest` decided (androidUnitTest) with seam files listed in A.4b; WeakReference leak loop with timeout in B.3. |
| Adv concern: ram-size pin, minor items | B.6 AC (dry run; memory budgets `ReportOnly` if pin fails); D.5 picker callback scope; Glossary count corrected to 29 (the original 25 was correct, 4 terms added). |
| UX items beyond the plan (not approved) | Unresolved Questions OD-1 (crosshair mode; UX-33, UX-34, UX-19), OD-2 (step selector; UX-33/34), OD-3 (off-canvas release cancel; UX-38, UX-31, UX-30), OD-4 (Move vertex TalkBack actions and Drive offline message; UX-39, UX-36, UX-29, UX-40). Conditional marking in C.8 AC 3, C.8a, C.8c, C.7a, C.9a; Task C.8d records the decisions; wall-clock blocker W8. |
| Header counts, diagram, estimate | Header Size line; dependency diagram and agent waves; effort table rows for A.2, A.3, B.1, B.2, B.5, C.1, C.5, C.8, D.4, D.7, new B.8; Total row; size band and overrun sources. |

New tasks (16): A.2e, A.2f, A.3d, A.3e, A.3f, B.1d, B.2d, B.5d, B.8a, B.8b, B.8c, C.1d, C.5d, C.8d, D.4c, D.7c.

Not fully resolved (by design): the B.6 low-RAM profile depends on a CI dry run (task B.6a); ADR-004 acceptance depends on spike C.1a results; the stock-rule claim depends on the owner's `importance=` line and the outcome of A.3d.

---

## Repair 2

Repair pass of 2026-11-08 against `pre-mortem.md` (4 P1, 5 P2, 2 P3), `consistency-review.md` (3 BLOCKER, 13 CONCERN, 8 NITPICK) and `validation.md` gaps. Counts recounted by script: 33 `### Story` headings, 105 `##### Task` headings (was 32 / 93), effort table sums 105 tasks / 12,214k with-verification tokens / 11,361k CU (was 93 / 10,666k / 9,927k). New tasks (12): A.0a, A.0b, A.0c, B.2e, C.1e, C.6b, C.7c, C.8e, C.9d, C.9e, D.6d, D.8c. New story: A.0. C.5d reclassed S to M. Glossary now 30 terms.

| Finding | Exact edit |
|---|---|
| Pre-mortem P1-1: diagnostics ship late | New Story A.0 "Diagnostics-first release (PR-0)": PR-0 = A.2a, A.2b, A.2e, A.2f, minimum A.2d (10 s tick) and A.0a-c, merged and released before any A.4/A.5/C behaviour change; W3 dogfood starts on it; forced-kill replay AC (`ForcedKillReplayTest`, two-run). A.2 intro, A.2d (minimum form), dependency diagram, waves (new W-0), glossary term PR-0, verification checklist, W3 text. |
| P1-2: day-one gate flake | B.5b: explicit bad-exit allowlist filtered by pid and timestamp (unit-tested with harness-caused entries), `globalMode: report-only` until the B.7b soak, one-edit demotion, label `skip-annotation-gate`. B.6a: job `timeout-minutes`, +10 min runtime budget, `continue-on-error` and non-required for the first 10 runs, path filter, n>=10 runs on nightly/dispatch. B.7b flips `globalMode`. Risk Control "Gate rollout". ADR-001 amendment (allowlist, `globalMode`, `mutation` field, terminology). |
| P1-3: legacy calibration display | C.1 AC "Never rescale legacy rows silently" (displayed values asserted in editor, Gallery, export over a pre-fix fixture DB; write-path property test; downgrade test); new Task C.1e (legacy-basis read path, goldens); C.1d adds `CalibrationBasisMigrationTest` and the downgrade test; Migration Plan steps (6)-(7); ADR-004 amendment. |
| P1-4: HIGH/LOW channel reordering | C.5 new ACs: one priority (`Priority.HIGH`) via single wrapper `MeasurementWrites.execute`, `invalidate` scoped to annotation tables, ordering/race tests against the real actor with LOW traffic (`RealActorOrderingTest`), queue `close()` versus actor shutdown; C.5c wrapper; C.5d (now M) runs the real-actor tests; ADR-005 amendment; B.8 table. |
| P2-5 probe-only PR touches production | B.1d production-diff audit step; B.5d mutation seams only in `androidInstrumentedTest`/debug, injected through shipped seams, release-APK absence check replaces the runtime assertion. |
| P2-6 CI runtime | B.6a timeout and runtime budget, deterministic subset per PR, noise-floor/calibration on nightly/dispatch; B.7b counts nightly runs. |
| P2-7 merge conflicts | Migration Plan: generated sources serialised C.1d, D.7c, D.7a, D.6a, `SQLDelight generated sources` job confirmed required (B.6a). A.3f already hard prerequisite. |
| P2-8 nav rename persisted ids | New Task D.8c (persisted screen-identifier audit, "labels only, identifiers unchanged" AC, `PersistedScreenIdTest`). |
| P2-9 Coil upgrade | B.2b: `CoilSeamTest` asserts listener fired >= 1 and names the Coil version; version in `ProbeRecord.device`; ADR-001: Coil bumps rerun the probe. |
| P3-10 positive control void | A.3d fallback: one manual device soak (W4) before any "fixed" wording; Claim vocabulary paragraph. |
| P3-11 sampler/handler | A.0c: editor stops accepting input after a handled `Error` until Retry or Close editor. |
| Consistency B-1 (SM1 unmeetable) | requirements.md "Amendment 2 - pending owner confirmation" item 1 (honest criterion); plan OD-5; Claim vocabulary cites it. |
| B-2 (schema vs Out of Scope) | requirements.md Amendment 2 item 2 (additive migrations C.1d, D.7c under CLAUDE.md rules); plan OD-6; C.1d notes owner confirmation. |
| B-3 (ADR-003 expect/actual) | Decision: commonMain `fun interface LoupeSource` injected, no `expect` (facts: editor is commonMain; the only existing `expect` in `platform/image/`, `PlatformImageDownscaler`, needs `.ios.kt`/`.wasmJs.kt` actuals; `ImageBoundsReader` uses the same pattern). ADR-003 options and Consequences amended; plan A.5/A.5b already matched. |
| C-1 ADR-001 decode-bytes gate | ADR-001 day-one list corrected (max decode bytes removed, duplicate "process survived" removed). |
| C-2 nav drift guard names | `NavModelExhaustivenessTest` everywhere; ADR-007 consequence and ux.md S1 amended. |
| C-3 Option A vs D.6c | D.6 retitled; D.6c marked option-independent; new D.6d; ADR-006 and ux.md S4 now skip only D.6a-D.6b; D.1 AC text. |
| C-4 UX criteria without tasks | UX-21 -> A.0c, UX-22/23 -> A.0b, UX-26 -> C.6b, UX-29 -> C.7c, UX-36/37 -> C.8e, UX-41 -> C.9d, UX-15 (and UX-14 inline retry) -> D.6d; ux.md surface table updated; validation.md traceability updated. |
| C-5 / C-6 / C-7 ux.md OD labels and conditional copy | ux.md Open design questions labelled OD-1..OD-4 one-to-one; S8 "Place at crosshair" and S11 "Drag off the image to cancel" marked CONDITIONAL (OD-1, OD-3); S10 "already saved" line gated on open question 2; C.8d wording. |
| C-8 mutation list | B.5 procedure step 3 and glossary list all ten flags; `BudgetEntry.mutation` field in glossary and ADR-001. |
| C-9 audit items | New Task B.2e (downscaler, Coil disk cache, depth inputs). |
| C-10 observability | Coil memory-cache size added to `DiagnosticsSample` (glossary, A.2d). |
| C-11 traceability | Epic C traceability: m8 -> C.9e (new), M8 -> A.4a, m1-m7 -> C.9. |
| C-12 scope drift | Recorded as OD-7 (confirm, do not remove). |
| C-13 ADR-002 idle CPU | ADR-002 reworded to report-only until B.7 promotion; A.3d rename rule. |
| Nits N-1..N-8 | N-1 "Library Stats" -> "Graph Stats" (ux.md, glossary note); N-2 glossary naming note; N-3 scenario letters defined in glossary; N-4 gate/report-only terminology in glossary and ADR-001; N-5 `MeasurementAnnotationActorRoutingTest` added to the B.8 table; N-6 ux.md S12 note made consistent (crosshair over fixed image); N-7 recorded as ux.md open question 6; N-8 header Status reworded. |
| validation.md gaps | Nine UX criteria (UX-15, 21, 22, 23, 26, 29, 36, 37, 41) mapped to tasks above; plan-gap markers removed; new rows for PR-0 replay, allowlist, legacy display goldens, real-actor ordering, mutation absence in release APK. |

Not resolved (needs owner or measurement): OD-1..OD-7; the stock-rule claim (owner's `importance=` line, A.3d outcome); ADR-004 final shape (spike C.1a); the legacy read path assumes the pre-fix computation is recoverable from stored data (confirm in C.1a, otherwise the badge plus frozen display strategy is re-scoped in an ADR-004 amendment); B.6 low-RAM profile (CI dry run); the 12 new tasks' size classes are INFERRED.

---

## Repair 3 (triad iteration 1)

Repair pass of 2026-11-08 against the triad review (engineering, UX and product gaps) and the owner's decisions made the same day. Counts recounted by script from the headings of this file: **34 `### Story` headings, 121 `##### Task` headings** (was 33 / 105), effort table sums 121 tasks / 13,694k with-verification tokens / 11,307k CU (per-row output-share method, see Effort estimate). New story: D.11. New tasks (16): A.0d, B.6d, B.6e, C.1f, C.1g, C.5e, C.8f, C.8g, C.9f, C.9g, C.9h, C.9i, D.8d, D.9c, D.11a, D.11b. Epic D stories D.1, D.6, D.7 were re-purposed for the merged Library (task IDs kept so traceability holds). Glossary now 33 terms.

| Group / item | Exact edit |
|---|---|
| **Owner decisions** OD-5 ACCEPTED, OD-6 ALLOWED, OD-1 INCLUDED, ADR-006 MERGE (all DECIDED 2026-11-08) | New section "Owner Decisions" before the Dependency Visualization (table of all decisions with where each lands). All "pending owner confirmation" wording removed from the header Status, Claim vocabulary, Migration Plan, Unresolved Questions, C.1d, C.7a, C.7c, C.8, C.9a. OD-1..OD-6 entries removed from Unresolved Questions (replaced by one pointer line); OD-7 relabelled non-blocking. OD-2, OD-3, OD-4 are recorded as included by the same decision (the instruction made UX-33/34/36/38/39/29 unconditional) and flagged so the owner can correct. requirements.md Amendment 2 retitled ACCEPTED; ADR-004, ADR-006, ADR-007, ADR-008 statuses updated (see those files). |
| OD-1 dependent stories unconditional | C.8 AC 3 rewritten (Crosshair default step 1 px, step selector, drag-the-crosshair, non-drag vertex move, desktop keys); C.8c no longer "CONDITIONAL" and extended (step selector, tap-moves-crosshair, disabled reason); new C.8f (Move vertex via crosshair + TalkBack actions) and C.8g (keyboard); C.8a now cites C.8f; C.8d rewritten as "recorded, done"; C.9a includes off-canvas cancel and handedness; C.7a/C.7c offline message unconditional; glossary `CrosshairPlacementState`; dependency diagram and waves. |
| **ADR-006 merge: Epic D reworked** | Whole Epic D section replaced (Phase 3 text). Ten recorded design decisions: single `Screen.Library` with `Screen.Gallery`/`Screen.AssetBrowser` kept one release as `NON_NAV_REDIRECT` aliases rewritten in `navigateTo` (route/deep-link compatibility, decided); tracing key `Library` plus `LegacyScreenAlias`; one `LibraryViewModel` and one paged query with the `annotated` join in SQL (no unbounded getAll, no N+1); tags = annotation tags only; four sort orders; "Import from Gallery" stub deleted (it never showed: `GalleryScreen.kt:124` passes `null`); annotated cards open the editor, others open detail; new nav section "Media" (**superseded in Repair 4: Library is the first row of the Graph section, ED-16; there is no Media section**); `shortLabelKey` ("Pages" in the bar, "All pages" elsewhere); Developer section appended last. Stories: D.1 (decision recorded), D.2 (+`NON_NAV_REDIRECT`, short labels), D.3 (+no-layout-shift AC), D.4 (Media section), D.5 (Go to Library, Show annotated photos, Annotate a photo), D.6 (Library screen with a 14-row parity table; tasks D.6a-d), D.7 (bounded Library reads; D.7a-0 spike, D.7a/D.7c/D.7b), D.8 (touch-point table with corrected paths; +D.8d), D.9 (+upgrade notice for existing users, D.9c, and the first-open task test), D.10 (+filter-bar a11y), new D.11 (route compatibility, retire old screens after parity). B.3 scenarios (f)/(g) and C.1/C.1g, glossary (`MeasuredPhotos / Files` replaced by `Library`; +`LibraryQuery`, `LegacyScreenAlias`, `PlacementGesture`), Tech Debt row (Gallery/Assets ViewModels replaced by merge), Migration Plan, Risk Control rollback, B.8 table, test-first summary, cross-cutting rules all updated. |
| Eng: split C.1d / C.1e / C.5c (each exceeded 3-5 files) | C.1d now schema + `MigrationRunner` + schema-sync test (4 files); new C.1f: write stubs, regenerated sources (one mechanical unit), downgrade test; C.1e now read path + write-path property test (3 files); new C.1g: displayed-value goldens (editor, Library card, export); C.5c now four production files (`SqlDelightMeasurementAnnotationRepository`, new `MeasurementWrites`, `RepositoryFactory`, routing test); new C.5e: mechanical constructor call-site sweep + doc corrections, justified (one-line edits of one pattern, `git diff --stat`) and landing with C.5c in one PR as two commits. C.9d narrowed to tokens + helper + contrast test; applying them is each surface task's ownership rule (cross-cutting rules); D.8a split (D.8a / D.8d). |
| Eng: CU conversion reproducible | Effort estimate rewritten: stated cache mix (25% fresh, 5% write, 70% read -> 0.3825 per input token), output share per row (12% implementation, 8% measurement/CI, 20% decision/doc), review share 7.5% for the x1.75 increment, formula and a worked row (A.2), per-row "Output share" column, re-totalled by script (11,307k CU; old flat 0.93 rate retired). |
| Eng: release-please wait for PR-0 as a named blocker | New task A.0d and wall-clock blocker W9 (merge the standing release PR, workflow green, APK attached) on the critical path in the Dependency Visualization, waves, Effort critical path and blocker list; A.0 AC and Files reference it. |
| Eng: privacy of uploaded artifacts, `benchmarks/` JSON, PR comments | New B.6 AC and task B.6d (`scripts/check-artifact-privacy.js`, synthetic-fixture rule, planted-path negative test, runs before upload and before the comment); CI table, cross-cutting rule, verification checklist. |
| Eng: SQLDelight generated-sources check as a verification, not an assumption | Verified 2026-11-08: `gh api repos/tstapler/stelekit/rulesets` -> `[]`; `.../branches/main/protection/required_status_checks` -> HTTP 404 "Branch not protected", so the job (`ci.yml:81-107`) is NOT a required check. New B.6 AC and task B.6e (read back + owner-independent fallback: local `git diff --exit-code kmp/src/generated/` and `gh pr checks` green before merge; optional W10 ruleset). Migration Plan sentence corrected; B.6a text corrected. |
| Eng: owner-independent fallback for PR #560 | Verified 2026-11-08: #560 is OPEN. A.3f now (S): rebase if merged; otherwise stack loupe work on #560's branch; if abandoned, land its fix first as a prerequisite PR; baselines re-taken on the chosen base. Unresolved Questions entry, wave plan, W6 (now B.6c only) and diagram updated. |
| Eng: stale file:line refs | Corrected from the source: `Screen` sealed class is `AppState.kt:37-104` (was 37-110); `Screen.Gallery` `:79`, `AssetBrowser` `:83`; Sidebar rows `Sidebar.kt:340-341`; `ScreenRouter.kt:342-374` (was 342-378); `FirstOpenSmokeTest.kt` `FIRST_OPEN_SCREENS` `:47`, branch `:111-113`; `AssetResultsSurfaceTest` full path `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/assets/AssetResultsSurfaceTest.kt:239`; `DemoGraphCoverageTest` full path `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/docs/DemoGraphCoverageTest.kt:90`; `GraphDialogLayer.kt:130`; `PlatformBottomBar.android.kt:29` ("Pages"); `LoupeOverlay` at `AnnotationEditorScreen.kt:1610`; `MigrationRunner.kt:419-421` indexes. |
| UX: editor open / main-image loading state | C.9 AC + new task C.9g (spinner, cached placeholder, input disabled until `NativeImageSize` known, 8 s timeout error with Retry/Back); UX-48. |
| UX: tap vs drag semantics | C.9 AC fixes the rules (tap <= 300 ms within 8 dp slop places a point; hold >= 120 ms shows loupe, lift places; off-canvas release cancels; second pointer cancels; Select taps select; Crosshair-mode taps move the crosshair); new task C.9f with pure `PlacementGesture` classifier; UX-49. |
| UX: cancel beyond Undo | C.9 AC + C.9f: visible [Cancel] button whenever in-progress points exist; UX-50. |
| UX: TalkBack / accessibility test plan | C.9 AC lists focus order, 48 dp, contrast, reduced motion, live-region politeness and accessibility timeouts; new tasks C.9h (focus order, targets, live regions) and C.9i (`rememberA11yTimeout` helper over `AccessibilityManager.getRecommendedTimeoutMillis`, timed-message and reduced-motion tests); validation.md rows and manual TalkBack device evidence; UX-51, UX-52. |
| UX: bottom bar "Pages" vs "All Pages" | Decided: `shortLabelKey`; bar "Pages", sidebar/sheet "All pages" (Epic D decision 9, D.2 AC, D.2b); ux.md open question 6 closed. |
| UX: Developer section show/hide layout shift | Decided: appended last, never moves rows above it; D.2 and D.3 ACs; UX-53. |
| UX: orphan-asset Annotate | Decided: no annotation is created for an orphan; Annotate stays enabled and shows "Add this file to a page to annotate it" with [Open pages] (**superseded in Repair 4 by ED-8: the control is disabled with a visible reason line and [Open pages], no snackbar**); C.9 AC, D.6d; UX-54. |
| UX: handedness setting location | Decided: Settings > Annotation > "Magnifier side" (Right default) with an editor-overflow shortcut; C.9 AC and C.9a; UX-55; ux.md open question 5 closed. |
| Product: requirements.md additions | Post-release outcome metric (zero `EXCESSIVE_RESOURCE_USAGE`/ANR/`LOW_MEMORY` exit reasons across >= 10 editor sessions over >= 14 days on the owner's phone), riskiest assumption under Feasibility Risks, SM4 outcome measure (first-open task test + baseline of today's Gallery/Assets confusion), SM4 and Alternatives rewritten for the merge; see requirements.md. Plan: `NavFirstOpenTaskTest` in D.9c, owner-evidence row in validation.md. |
| Other | Verification checklist extended (W9, privacy, generated sources, parity, first-open task test, a11y); validation.md and ux.md traceability tables updated. |

Not resolved (needs owner or measurement): OD-7 (veto-able scope additions); the stock-rule claim (owner's `importance=` line, A.3d outcome); ADR-004 final shape (spike C.1a); whether every annotation has an asset row (spike D.7a-0 decides the `UNION ALL` branch); the post-release metric needs the dogfood window to elapse; the "Library" title is a working title (one I18n edit); the 16 new tasks' size classes are INFERRED.

---

## Repair 4 (triad iteration 2)

Repair pass of 2026-11-08 against the triad iteration 2 findings (UX: 2 blockers B-UX-1, B-UX-2 and 20 gaps; engineering and product gaps). Planning documents only. Counts recounted by script: **34 `### Story` headings, 134 `##### Task` headings** (was 34 / 121), effort table 134 tasks / 8,470k raw / 14,821k with-verification tokens / 12,257k CU. The script (scratchpad `recount.py`) parses the `### Story` and `##### Task ... (XS|S|M)` headings, uses midpoints XS 20k, S 55k, M 130k, CU = raw x (5s + (1 - s) x 0.3825) + 0.75 x raw x 0.729 with the output shares of the Effort estimate section, verification tokens = raw x 1.75, rewrites the table in place and prints the totals; the printed totals were then cross-checked by `grep -c '^##### Task'` (134), `grep -c '^### Story'` (34) and a separate sum of the table rows (34 rows, 134 / 8,470 / 14,821 / 12,257). Glossary recounted by script: 36 terms. New tasks (13): A.0e, A.2g, B.3e, C.8h, C.8i, C.9j, C.9k, C.9l, D.6e, D.6f, D.6g, D.9d, D.9e. No new story. The agent-chosen defaults are numbered LD-1..LD-10 and ED-1..ED-17 in `design/ux.md` ("Defaults - owner may override") and mirrored in ADR-006 (Defaults 1-8) and ADR-008 (Defaults 1-8); they are not owner decisions.

| Group / item | Exact edit |
|---|---|
| **B-UX-1 Library filter semantics** | ux.md LD-1..LD-8 (groups, contradiction rule, sort and nulls, search scope, persistence, debounce, compact sheet, exact SQL semantics, entry-point normalization); ADR-008 "Defaults" 1-8 (same semantics at repository level); plan decision 5 amended; D.6 ACs (OR within Type, dimmed-with-reason chips, status line debounce, persistence, compact width); new D.6g (`LibraryFilterRules` pure model, tests first), D.6e (compact filter sheet, config change); D.7 ACs and D.7a text (`LibraryFilterSqlParityTest`, search scope, count cap); glossary `LibraryFilterRules`; validation rows. Contradiction handling chosen: dim the conflicting chip with a stated reason (focusable, tap shows the reason), no empty-state variant. |
| **B-UX-2 Touch-target audit** | ux.md ED-1 and S13/S18 rows (audited surface = controls; canvas excluded; `HandleHitResolver` nearest-wins and handle-priority; Move vertex as the equivalent path; WCAG 2.2 SC 2.5.8 equivalent-control exception, cited from the spec text, not re-fetched); plan C.9 Targets bullet; C.8e rewritten around `HandleHitResolver` with `HandleHitResolverTest` and `CanvasHandleEquivalentPathTest`; glossary `HandleHitResolver`; validation rows. |
| Section naming (Library vs Media) | Decision: Library is the first row of the Graph section; sections Daily, Connections, Graph, Developer; "Media" removed from ux.md S1/S2/S4, ADR-006, ADR-007, plan decision 8, D.2b, D.3, D.4, glossary `NavSection` and `Library`; UX-02 and cross-cutting headings reconciled; fallback header "Files and graph" if cold-user sessions miss it (ED-16). |
| Stale pre-merge words | UX-13..16, cross-cutting focus order and font-scale lines, S14 orphan row, S4 table purged of "Photos", "Files", "Gallery/Files"; vocabulary paragraph at the top of ux.md; "measured photos" replaced by "annotated photos" in S7 copy, D.9 tip and notice. |
| Terms (Annotated vs measured, unfinished, Cancel/Done) | ED-2 vocabulary; S10 and UX-25 "unfinished points"; [Cancel] -> [Clear points] (S11, S17, UX-50, C.9 AC, C.9f), [Done] -> [Deselect] (S13) / [Finish], "Cancel move" (S13, C.8 AC, C.8f), "Cancel export" (S15); test class `InProgressCancelTest` keeps its name. |
| Tap-to-place precision and the 120-300 ms band | ED-3: two thresholds only (8 dp, 120 ms); quick tap moves the crosshair (setting Quick tap, Fingertip offset); S11, S17 gesture table, UX-49, C.9 AC, C.9f, glossary `PlacementGesture`. |
| In-gesture cancel feedback, edge tolerance, mouse/stylus | ED-4 (dim, "Release to cancel", haptic, 12 dp tolerance), ED-3b; S11, S17, UX-57, UX-66; new C.9j. |
| Crosshair hint, nudge ergonomics, accessibility auto-offer | ED-5, ED-6; S12; UX-33 text, UX-63..65; C.8 AC (screen-relative steps, auto-repeat, auto-offer), C.8c/C.8g text; new C.8i with `NudgeStep`. |
| Fold, unfold, rotation | ED-7, ux.md S20, UX-61/62; new C.9k (editor) and D.6e (Library); `EditorConfigChangeTest`, `LibraryConfigChangeTest`, Pixel_Fold device evidence. |
| Orphan Annotate | ED-8: disabled with a visible reason line and [Open pages] (supersedes the 2026-11-08 snackbar wording; flagged for the owner); S6, S14, UX-54, C.9 AC, C.9c title, D.6d. |
| Low-memory placeholder vs "File missing" | ED-9; S5 states; UX-67; D.6 AC and D.6f (`LibraryThumbnailPlaceholderTest`). |
| Per-asset overflow, delete, detail, Settings > Annotation, editor overflow, Add menu | ux.md S19, LD-10, ED-10, ED-11, UX-69/70; new D.6f and C.9l; missing non-annotated asset sheet in S5. |
| Desktop keyboard accessibility | LD-9, ED-12, UX-59; D.6a, D.10 AC, C.9 Keyboard bullet (`EditorKeyboardFocusTest`, `LibraryKeyboardNavigationTest`). |
| Editor load, retry vs stale decode | ED-13; S17 ("Still opening..." at 3 s, 15 s ceiling replacing 8 s, generation numbers); UX-60; C.9 AC, C.9g, `EditorLoadGenerationTest`. |
| Snackbar dismiss/stacking, "Time to take action" | ED-14; S18; UX-71; C.9 timeouts bullet, C.9i, new C.8h (`EditorSnackbarQueue`). |
| Reduced-motion source | ED-15; S18; UX-72; C.9d text. |
| Upgrade notice reach | ED-16; S7 bullet; UX-68; D.9 AC and new D.9d (New badge, Settings > About > What's new); `LibraryNewBadgeTest`. |
| Cold-user sessions | ED-17; D.9 AC; new D.9e; blocker W11; validation owner/device rows. |
| Glossary NavSection | Row rewritten to Daily, Connections, Graph, Developer; count 36 by script. |
| Split A.2e, B.3a, C.8e | A.2e (API + test + registration, 3 files) / A.2g (host mapping and caller sweep, 3 files); B.3a (seeds, count test, BUILD, registration, 4 files) / B.3e (Android wrapper + smoke, 2 files); C.8e (hit resolver + handles, 5 files) / C.8h (delete + snackbar queue, 5 files). |
| Explicit split triggers | D.7a and C.1f (generated sqldelight: more than 6 generated files, unrelated generated code, or rebase conflict -> mechanical `-gen` PR first); D.8c (more than 3 stores or more than 5 files); D.8d (more than 5 files -> by directory); D.11b (more than 5 extra files, an out-of-list reference, or non-mechanical BUILD edits -> D.11b-1/-2). |
| D.11a `NON_NAV_REDIRECT` | Marked NEW work in decision 1, D.2 AC, D.11a text; verified `rg NON_NAV_REDIRECT kmp/src` returns nothing (2026-11-08). |
| Effort totals | Recounted and rewritten by the script above; Size line, Effort prose and size band updated. |
| Verification checklist | `scripts/bench-budget-check.js` (and its budgets file), `scripts/check-artifact-privacy.js` and `scripts/check-bazel-test-listing.sh` marked "Planned (B.5 / B.6d / B.8c), not yet runnable" (confirmed absent by `ls scripts`, 2026-11-08); new checklist lines for filter rules, label uniqueness, `NON_NAV_REDIRECT`, cold-user and fold evidence. |
| "Library Stats" -> "Graph Stats" | D.2 AC and `NavLabelUniquenessTest` (new, businessTest), D.3 AC and `SidebarSectionsTest.graphStatsRename`, D.3b renamed (`Sidebar.kt:346`, VERIFIED present today). |
| A.0b redaction | A.0b text and new A.0e (`LogRedactor.redactForShare`: what could be pasted publicly, copy only the visible exit block and at most 3 samples, strip paths, `content://` URIs, graph and page names, nothing beyond what Logs already shows); `LogRedactorTest`, `LogRedactionTest`. |
| Product text (requirements.md) | See requirements.md: Metric 1 and the Out-of-Scope `.sq` line base text, Size blocker (3), Open Questions 1-4, Metric 6 end condition, RICE/ICE line with bundling rationale, annotate-then-find-later JTBD. |
| Other | Dependency diagram and waves (Repair 4 line), Unresolved Questions (agent-chosen defaults awaiting review), blockers (W11), test-first summary and Story B.8 registration rows, validation.md (rows, device evidence, traceability). |

Not resolved (needs owner or measurement): review of the agent-chosen defaults (notably ED-8, ED-3, ED-16); the cold-user results (W11); the reduced-motion signal on the Pixel_Fold (ED-15, INFERRED until C.9d verifies it); whether the SQL for tag/label prefix search can be fully indexed (D.7a-0 and D.7c decide); the 13 new tasks' size classes are INFERRED.

## Repair 5 (triad iteration 3, final)

Final repair pass of 2026-11-08 against the triad iteration 3 findings (one BLOCKER, product-text and UX contradictions/gaps, engineering nits). Planning documents only; no scope cuts. Counts recounted by script (scratchpad `recount.py`: parses `### Story` headings and `##### Task ... (XS|S|M[,)]` headings, midpoints XS 20k, S 55k, M 130k, CU = raw x (5s + (1 - s) x 0.3825) + 0.75 x raw x 0.729, rewrites the effort table in place; unchanged rows reproduced the Repair 4 values exactly, only C.9 and D.7 changed): **34 `### Story` headings, 136 `##### Task` headings** (was 34 / 134), effort table 136 tasks / 8,580k raw / 15,014k with-verification tokens / 12,421k CU, cross-checked by `grep -c '^##### Task'` (136) and `grep -c '^### Story'` (34). New tasks (2): D.7a-0 (S, explicit spike), C.9m (S, layout fallback and Back precedence). New LD-11..LD-20, ED-18..ED-27, UX-73..UX-82 in `design/ux.md` are agent-chosen defaults the owner may override.

| Group / item | Exact edit |
|---|---|
| **BLOCKER: search semantics (LD-3 vs LD-7)** | One definition in ux.md LD-3 and LD-7, ADR-008 Defaults 4-5, plan decision 5, D.6g, D.7 AC, D.7a-0 and validation.md: tokens split on whitespace and `_ - . , / " [ ]` (max 5), at least 2 characters (1 character = hint, no search), every token a word-prefix match in file name, annotation tag or annotation label, ASCII-only case folding, `%` and `\` escaped with `ESCAPE '\'`. SQL shape (`hay()` REPLACE chain, `% tok%` patterns built in Kotlin, five NULL-able token parameters, tags matched as JSON text without `json_each`) follows the existing asset search convention (`SteleDatabase.sq` `LIKE :query ESCAPE '\'`, VERIFIED); bounded LIMITed scan, no FTS. Parity cases added in validation.md (multi-word, wildcards, 1-char, ASCII/non-ASCII case, separators). |
| LD-2 keyset claim | Keyset paging with the key tuples listed in LD-2 and ADR-008 item 3; the "never repeat or skip" claim is scoped to items present for the whole scroll. |
| Type-chip membership | LD-11 from `AssetMediaType` (VERIFIED `asset/AssetModels.kt`); `LibraryMediaClass`, SQL `IN`/`NOT IN` and tests share it. |
| Add under filters; capture/pick vs ED-8 | LD-12 (scroll-and-outline, "Hidden by current filters" snackbar, [Show] under deep sorts); LD-13 (anchor to the last viewed page or today's journal; empty-state copy fixed; Add menu success copy). D.6c, D.6f. |
| Stale ED-8 text | Plan decision 7 and the Repair 3 log row corrected/annotated to ED-8; ux.md flow-completeness row "orphan snackbar" fixed. |
| Crosshair gestures, quick tap, cancel vs back gesture, layout | ED-18 (quick-tap switch visible and announced), ED-19 (Crosshair-mode gesture table), ED-20 (inset rule, gesture exclusion, toolbar-region cancel), ED-21 (min canvas, side rail, Nudge-pad sheet), S12/S17 tables; C.8c, C.9f, C.9j, new C.9m. |
| Shell swap, scroll anchor, Back precedence, vertex actions, process death | ED-22/S20 (D.4c), LD-19 (D.6e, D.7b), ED-24 (C.9m), ED-23 (C.8f), ED-26 (C.9e). |
| Undecodable, tag overflow, annotated-and-orphaned, multi-page count | LD-18, LD-14, LD-16 (the Annotated/Orphaned dimming rule from Repair 4 is removed: an annotation can outlive its page), LD-15. |
| Status templates, live regions, headings, TalkBack order, contrast | LD-17, S18 rows, ED-25, ED-27; D.6a, D.10a, C.9h, C.9d; `LibraryStatusLineTest`, `ScreenHeadingsTest`. |
| Upgrade badge/notice | LD-20 state table (badge clears on Library open, notice persists until dismissed, per install); S7 text reconciled ("first open only" removed); D.9c, D.9d, `UpgradeNoticeStateMachineTest`. |
| Product text | requirements.md Amendment 5 and base text: In Scope regression-test and Risk Control reworded to the Amendment 2 criterion; Out of Scope and In Scope planning lines scoped to the planning run; Problems 1 and 2 framed as reported/suspected; Metric 3 (budgets file B.5/B.7), Metric 4 (mandatory before-run, W11 feedback), Metric 5 (outcome evidence and checklist, plan Verification checklist), Metric 6 (bad exit defined, N justified, evaluated by the owner on day 14 from the Logs screen); Open Question 7 answered by B.6; owner named sole acceptance authority. |
| Engineering nits | D.7a-0 explicit task and the `UNION ALL` split trigger (D.7a-union), D.6a split trigger, A.5b split trigger (A.5b-1 / A.5b-2); W3 now carries the soak window and the longest wall-clock chain statement; dependency diagram and Repair 5 line; B.8 registration rows; test-first summary; Verification checklist (search/paging line and the C-findings checklist). |

Not resolved (needs owner or measurement): review of the agent-chosen defaults (notably ED-8, ED-3/ED-18, ED-16, LD-16, LD-13); the result of spike D.7a-0 (join key, `UNION ALL` need, file-name column, scan cost of the leading-`%` search path, driver parity of `ESCAPE`/`REPLACE`); the cold-user results (W11); the reduced-motion signal and `systemGestureExclusion` behaviour on a real phone; the 2 new tasks' size classes (INFERRED).
