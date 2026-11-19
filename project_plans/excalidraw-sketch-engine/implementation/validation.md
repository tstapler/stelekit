# Validation Plan: excalidraw-sketch-engine

**Date**: 2026-11-14

Inputs read: `requirements.md`, `implementation/plan.md` (all of it; re-synced after the Phase 4 repair and after every triad-review repair iteration, see the history table below): **19 epics, 95 stories, 317 tasks**, the CURRENT count, derived by `check-plan.py` (section "Plan document check" below) and never typed from memory, `design/ux.md` (UX-01..UX-153, **146 criteria**, counted by script), repo `CLAUDE.md` test rules, `4-validate/validation-prompt.md`.

**History of task additions** (the ids are checked against the plan by `check-plan.py`; a "-" means the iteration is recorded only in its own section below or in the plan status line; the last row is the computed current count):

<!-- history:start -->
```text
# iteration | tasks added | task ids ("-" = not listed here) | UX criteria added | reclassified
3 | 13 | - | 0 | 6.2.3b XS to S
4 | 5 | 0.1.7d 3.1.4a 3.1.4b 3.6.3d 5.1.1e | 7 | 3.1.2g S to M
5 | 2 | 3.1.2h 5.1.1f | 0 | -
6 | 3 | 3.1.4c 3.2.2d 5.1.1g | 0 | -
7 | 1 | 3.1.4d | 0 | -
11 | 1 | 1.1.5b | 0 | -
12 | 0 | - | 0 | -
14 | 1 | 1.4.3h | 5 | -
15 | 10 | 0.1.8a 0.1.8b 1.1.5c 1.4.3i 1.4.3j 1.4.6j 1.4.7a 1.4.7b 1.4.7c 1.4.7d | 5 | -
16 | 8 | 1.4.7e 1.4.7f 1.4.7g 1.4.7h 1.4.7i 1.4.7j 1.4.7k 1.4.7l | 5 | -
17 | 7 | 1.4.3k 1.4.3l 1.4.3m 1.4.3n 1.4.3o 1.4.3p 1.4.7m | 5 | 1.4.7j S to M
18 | 2 | 1.4.3q 1.4.3r | 3 | -
19 | 7 | 0.1.8c 1.4.3s 1.4.3t 1.4.6k 1.4.7n 2.1.2c 6.2.1c | 2 | -
20 | 1 | 1.4.7o | 0 | 1.4.3o S to M
now | 317 | - | 146 | computed
```
<!-- history:end -->

**Path shorthand** (same as plan.md): `CT` = `kmp/src/commonTest/kotlin/dev/stapler/stelekit`, `BT` = `kmp/src/businessTest/kotlin/dev/stapler/stelekit`, `JT` = `kmp/src/jvmTest/kotlin/dev/stapler/stelekit`, `AT` = `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit`. Test names follow `subject_should_Expected_When_Condition`. Rows marked `NEW` were not named by any story before the Phase 4 repair; each is now in a story's Files list (see Gap resolution). The tests are designed, not written or run.

## Happy Path Scenario
Given a project holding one v1 sheet with 12 line/rect primitives (the Baseline, flag `sketch.engine.v2` off), when the owner enables the flag, opens the sheet (it opens in the classic editor and nothing converts), chooses Upgrade on the offer chip, confirms the first-v2-write dialog (conversion with backup), draws two labelled boxes and a bound arrow, adds a Horizontal constraint to a line, moves a box and exports SVG, then the arrow end follows the box in the same undo step, the constraint solves to within 1e-9, the 12 original ids and the backup `backup/<uuid>.v1.json` exist, fusion/CSV/digest output equals the pre-conversion output, and the SVG parses as well-formed XML. *(Anchor: `ArchitectureScenarioTest` extended with `SheetConversionGoldenTest` fixtures.)*

## Requirement Register

Success metrics are SM-1..SM-7 (requirements.md). R-8..R-19 are the remaining requirements in Scope, Constraints, NFRs, Observability and Risk Control, numbered here so each maps to tests.

| ID | Requirement | Source |
|---|---|---|
| SM-1 | Tool parity: every tool in the inventory implemented with a passing scene-engine test | Success Metrics 1 |
| SM-2 | Diagram authoring: 15-element architecture diagram authored in-app, at most 60 actions, and (advisory) at most 1.5x the owner's MEASURED excalidraw.com time (baseline Task 0.1.7a) | Success Metrics 2 |
| SM-3 | Constraint sketching: FreeCAD-style set solves deterministically; over/under-constrained detected | Success Metrics 3 |
| SM-4 | Cross-platform engine: commonMain, tested once; identical render within pixel tolerance on JVM/Android/wasmJs | Success Metrics 4 |
| SM-5 | Layered UX: layers create/reorder/lock/hide/opacity, z-order, groups, frames, undo covers all | Success Metrics 5 |
| SM-6 | No regression and no data loss: every stored sketch opens, edits, exports unchanged; `MigrationRunnerSchemaSyncTest` green | Success Metrics 6 |
| SM-7 | Interop: `.excalidraw` import/export round trip; SVG and PNG render all kinds | Success Metrics 7 |
| SM-8 | Adoption (advisory): >= 50% of new diagrams authored in-app after 8 weeks; local counter | Success Metrics 8 |
| R-8 | Both Clean and Sketchy styles behind a strategy seam (OQ3) | Decisions, Scope |
| R-9 | Text elements: in-place editing, wrapping, fonts, container text, persisted size hint | Scope, Rabbit Holes |
| R-10 | Input: mouse, touch, stylus, keyboard; touch navigates, stylus/mouse acts | Scope, NFR Accessibility |
| R-11 | Risk control: flag, backup before first v2 write, rollback, router, lease, v1 coexistence | Risk Control |
| R-12 | Security: imported `.excalidraw` and native sheets are untrusted (caps, no network, no script, no svg+xml) | NFR Security |
| R-13 | Persistence: `schema_version` migration, sidecar bundle pipeline passes v2 through, old reader fails closed | Scope Persistence, Constraints |
| R-14 | Observability: `Logger` lines with ids only, budgets file samples | Observability Requirements |
| R-15 | Performance SLO and scalability: 500-element frame budget, 100-constraint solve < 50 ms on JVM and < 150 ms on wasmJs (50 ms wasmJs advisory, D-30), 5,000 elements, 200 constraints, bounded history, sub-linear hit-test | NFR Performance, Scalability |
| R-16 | Accessibility: non-drag path for every drag, keyboard-only operation, semantic labels | NFR Accessibility |
| R-17 | Embeds continue; cross-graph copy via `TargetWriterRouter`; image assets by hash | Scope Export, Constraints |
| R-18 | Editing aids: grid, object snap, align/distribute, clipboard, duplicate, style panel, library | Scope |
| R-19 | Engineering constraints: engine pure in commonMain, Arrow `Either`, no new deps, wiring, Bazel registration, hygiene audits | Constraints, CLAUDE.md |
| R-20 | Product gates and measured baselines: baselines before the bars that cite them (Story 0.1.7), A1 go/no-go gate (Task 6.2.4e), A6 Job 2 demand (Task 0.1.2d), abort/pivot checkpoints (Story 6.2.9), Job 1 first | requirements Priority, A1 gate, Abort and pivot, Performance SLO |
| R-21 | Flag-exit and legacy-retirement criteria, local quality signals (data-loss reports, crash rate, `NoConvergence` rate), the A4 go/no-go and A5 decision rules, and the counted Excalidraw feature baseline | requirements "Flag and legacy-editor exit criteria", "A4 and A5 decision rules", "Excalidraw feature baseline" |

## Requirement -> Test Mapping

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| SM-1: tool parity | `CT/sketch/v2/tools/ShapeToolsTest.kt` | `rectangleTool_should_CommitSquare_When_ShiftDragged` | Unit | Happy path (also Alt-from-centre, diamond, ellipse, Shift 15-degree line) |
| SM-1: tool parity | `CT/sketch/v2/tools/ToolCancelTest.kt` | `drawingTool_should_CancelWithNoCommand_When_SecondPointerOrEscMidDrag` | Unit | Error path, every drawing tool via scenario harness |
| SM-1: tool parity | `CT/sketch/v2/tools/ToolInventoryCompletenessTest.kt` (NEW, Story 3.1.1) | `toolRegistry_should_HaveScenarioTestForEveryTool_When_InventoryEnumerated` | Integration | Enumerates the `Tool` registry: select, hand, rectangle, diamond, ellipse, line, arrow, freedraw, text, image, eraser, frame, point, arc (the frozen 14; point and arc only when OD-8 is accepted) and asserts each id appears in a scenario test; guards "each tool has a test" |
| SM-1: tool parity | `CT/sketch/v2/tools/ArrowToolTest.kt` | `arrowTool_should_CommitRoundArrowWithEndArrowhead_When_ThreePointsPlaced` | Unit | Happy path (arrow) |
| SM-1: tool parity | `CT/sketch/v2/tools/ArrowToolTest.kt` | `arrowTool_should_RemoveZeroLengthArrow_When_ReleasedWithoutDrag` | Unit | Error path (UX 5 "Nothing drawn") |
| SM-1: tool parity | `CT/sketch/v2/tools/FreedrawToolTest.kt` | `freedrawTool_should_StorePressures_When_StylusAndSimulate_When_Mouse` | Unit | Happy path |
| SM-1: tool parity | `CT/sketch/v2/tools/FreedrawToolTest.kt` | `freedrawTool_should_SimplifyToMax2000Points_When_20000PointStroke` | Unit | Error/limit path |
| SM-1: tool parity | `CT/sketch/v2/tools/EraserFrameToolTest.kt` | `eraser_should_DeleteTouchedElementsInOneTransaction_When_StrokeCrossesTwo` | Unit | Happy path (eraser, frame adoption) |
| SM-1: tool parity | `CT/sketch/v2/tools/EraserFrameToolTest.kt` | `eraser_should_SkipLockedElements_When_StrokeCrossesLockedLayer` | Unit | Error path |
| SM-1: tool parity | `CT/sketch/v2/tools/SelectToolTest.kt` | `selectTool_should_ScaleBothAboutLeftEdge_When_RightHandleDraggedOnTwoElements` | Unit | Happy path (select/transform, one undo step) |
| SM-1: tool parity | `CT/sketch/v2/tools/SelectToolTest.kt` | `selectTool_should_NotSelect_When_ElementLockedOrLayerHidden` | Unit | Error path |
| SM-1: tool parity | `CT/sketch/v2/tools/TextToolTest.kt` (NEW, Story 3.4.1 Files) | `textTool_should_CommitMultilineText_When_TypedThenBlurred` / `textTool_should_DeleteElement_When_BlurredEmpty` | Unit | Happy + error path (hand tool covered by `PointerAdapterTest`) |
| SM-1: tool parity | `CT/sketch/v2/ImageAssetStoreTest.kt` | `imageTool_should_StoreByHashOnce_When_PlacedAndRefuse60MbFile` | Unit | Happy + cap error |
| SM-1: tool parity | `JT/sketch/v2/ToolsEndToEndTest.kt` (NEW, Story 6.2.4 Files, `// jvmTest-reason: desktop-only`) | `tools_should_ProduceExpectedScene_When_AllToolsDrivenThroughPointerAdapter` | Integration | Real adapter + view model + store, in-memory repository |
| SM-2: diagram authoring | `JT/sketch/v2/ArchitectureScenarioTest.kt` | `architectureScenario_should_MatchGoldenScene_When_15ElementEventScriptRun` | Integration | Happy path: 9 labelled boxes, 4 bound arrows, 1 frame, 1 group; scene equals `golden/architecture-15.scene.json` |
| SM-2: diagram authoring | `AT/sketch/v2/ArchitectureScenarioAndroidTest.kt` | `architectureScenario_should_MatchGoldenScene_When_RunUnderRobolectric` | Integration | Same script on Robolectric |
| SM-2: diagram authoring | `JT/sketch/v2/ArchitectureScenarioTest.kt` | `architectureScenario_should_MoveArrowEndpoints_When_BoxMoved` | Unit | Binding follows move, one undo step |
| SM-2: diagram authoring | `JT/sketch/v2/ArchitectureScenarioTest.kt` | `architectureScenario_should_RefuseNewElement_When_SceneAtCapacity` | Unit | Error path (5,000 cap mid-script, status "limit") |
| SM-2: diagram authoring | manual (`owner-verification.md`) | `ownerChecklist_should_ConfirmArchitectureDiagram_When_RunOnDesktopAndPixelFold` | Manual | Desktop + Pixel_Fold AVD run (OD-2) |
| SM-3: constraint sketching | `CT/sketch/constraints/SolverPropertyTest.kt` | `solver_should_SatisfyEveryConstraintWithin1e9_When_PerturbedSatisfiableSketch` | Property | Happy path (kotest `Arb`) |
| SM-3: constraint sketching | `CT/sketch/constraints/SolverPropertyTest.kt` | `solver_should_ReportRedundantAndConflictIds_When_OverConstrained` | Unit | Error path (Story 0.1.2 cases, DOF analytic) |
| SM-3: constraint sketching | `CT/sketch/constraints/SolverPropertyTest.kt` | `solver_should_ReturnLastGoodParamsWithNoNaN_When_NoConvergence` | Unit | Error path |
| SM-3: constraint sketching | `CT/sketch/constraints/SolverPropertyTest.kt` | `solver_should_BeDeterministic_When_SameProblemSolvedTwice` | Property | Determinism (metric 3 "deterministically") |
| SM-3: constraint sketching | `CT/sketch/constraints/JacobianFiniteDifferenceTest.kt` | `jacobian_should_MatchCentralDifferenceWithin1e6_When_EachConstraintType` | Property | Happy path per constraint type |
| SM-3: constraint sketching | `CT/sketch/constraints/JacobianFiniteDifferenceTest.kt` | `residual_should_ContainNoNaN_When_ZeroLengthLineParallel` | Unit | Error path |
| SM-3: constraint sketching | `CT/sketch/constraints/LinalgPropertyTest.kt` | `qr_should_RecoverRank25_When_Random40x60RankDeficientMatrix` | Property | Numeric core |
| SM-3: constraint sketching | `CT/sketch/v2/SolveRunnerTest.kt` | `solveRunner_should_CommitOneTransactionWithSolverGeometry_When_VertexDragReleased` | Integration | Happy path; redo replays patch, solver counter flat |
| SM-3: constraint sketching | `CT/sketch/v2/SolveRunnerTest.kt` | `solveRunner_should_KeepLastGoodGeometry_When_DragNonConverges` | Integration | Error path |
| SM-3: constraint sketching | `CT/sketch/v2/ConstraintAdapterTest.kt` | `deleteGeometry_should_CascadeConstraintsAndUndoRestoreAll_When_L1Deleted` | Integration | Scene adapter |
| SM-3: constraint sketching | `CT/sketch/v2/ConstraintAdapterTest.kt` | `adapter_should_MapPointArcCircleLineRect_AndRejectUnequalEllipse_When_ConstraintTargetsBuilt` | Unit | OD-8 / consistency B1 (Task 4.2.1b) |
| SM-3: constraint sketching | `CT/sketch/v2/SceneModelTest.kt` | `ellipse_should_BeCircleOnlyWhenRadiiEqual_And_PointArcConstructOnlyFinite` | Unit | OD-8 (Story 1.2.1) |
| SM-1: tool parity | `CT/sketch/v2/tools/PointArcToolsTest.kt` (NEW, Story 3.2.4) | `pointTool_should_CommitPoint_And_arcTool_should_CommitThreePointArcOrNothingDrawn_When_Collinear` | Unit | OD-8 tools |
| SM-7: interop | `CT/sketch/excalidraw/RoundTripPropertyTest.kt` | `exportThenImport_should_RestorePointAndArcKinds_When_CustomDataTagged` | Unit | OD-8 (Story 5.1.3) |
| SM-3: constraint sketching | `CT/sketch/constraints/SolverSpikeTest.kt` | `spikeSolver_should_Solve100ConstraintsUnder50ms_When_40Lines` | Unit | Perf gate (also R-15); JVM asserts 50 ms, the wasmJs run asserts 150 ms and records the 50 ms advisory figure |
| SM-4: cross-platform engine | `CT/sketch/v2/DrawListGoldenTest.kt` | `drawList_should_EqualGoldenOnJvmAndWasm_When_Architecture15Clean` | Integration | Happy path (same test source runs under `jvmTest` and `wasmJsBrowserTest`) |
| SM-4: cross-platform engine | `CT/sketch/v2/DeterminismSpikeTest.kt` | `canonicalDoubles_should_BeByteIdentical_When_JvmAndWasm` | Unit | Cross-target determinism |
| SM-4: cross-platform engine | `JT/sketch/v2/ScenePainterTest.kt` (`// jvmTest-reason: pixels`) | `render_should_StayWithinPixelTolerance_When_JvmVsWasmArchitecture15` | Integration | Happy path |
| SM-4: cross-platform engine | `JT/sketch/v2/ScenePainterTest.kt` | `pixelDiff_should_FailAboveTolerance_When_OneShapeShiftedTwoPx` | Unit | Negative control: the tolerance check can fail |
| SM-4: cross-platform engine | `BT/sketch/v2/SketchV2EngineImportDirectionTest.kt` | `v2Engine_should_ImportNothingFromUiDbRepositoryPlatform_When_SourcesScanned` | Unit | Purity (also R-19) |
| SM-4: cross-platform engine | `AT/sketch/v2/AndroidPixelSpikeTest.kt` | `androidRender_should_MatchJvmWithinTolerance_When_RobolectricIsReliable` | Integration | Android parity or documented fallback to draw-list equality |
| SM-5: layered UX | `CT/sketch/v2/ElementCommandsTest.kt` | `layerCommands_should_ReorderAndSetOpacityWithOneUndoPerOp_When_WallsMovedUpAtHalfOpacity` | Unit | Happy path |
| SM-5: layered UX | `CT/sketch/v2/ElementCommandsTest.kt` | `deleteLastLayer_should_BeRejected_When_OnlyDefaultLayerRemains` | Unit | Error path |
| SM-5: layered UX | `CT/sketch/v2/GroupFrameTest.kt` (NEW, Story 3.5.1 Files) | `selectTool_should_SelectOuterGroupThenDescend_When_ClickedThenDoubleClicked` | Unit | Nested groups |
| SM-5: layered UX | `CT/sketch/v2/GroupFrameTest.kt` | `bringToFront_should_SortAfterAllZKeys_When_AFirstOfThree` | Unit | Z-order |
| SM-5: layered UX | `CT/sketch/v2/HistoryV2PropertyTest.kt` | `history_should_RestoreInitialSceneAfterUndoAll_When_50RandomCommands` | Property | Undo/redo over all commands incl. layers/groups |
| SM-5: layered UX | `CT/sketch/v2/HistoryV2PropertyTest.kt` | `history_should_EvictWholeTransactions_When_120Committed` | Unit | Bounded history |
| SM-5: layered UX | `CT/sketch/v2/ReconcileTest.kt` | `reconcile_should_ClearDanglingGroupFrameAndBinding_When_ReferencedElementMissing` | Property | Invariants after random sequences |
| SM-5: layered UX | `AT/sketch/v2/LayersPanelTest.kt` | `layersPanel_should_HideUntilSecondLayer_When_SingleLayerSheet` | Unit | Panel behaviour |
| SM-6: no regression | `BT/sketch/v2/SheetConversionGoldenTest.kt` | `conversion_should_ExportBytesEqualPreConversion_When_EveryGoldenV1Fixture` | Integration | Happy path |
| SM-6: no regression | `BT/sketch/v2/SheetConversionCrashInjectionTest.kt` | `conversion_should_NeverYieldCorrupt_When_CrashAtEachStepOrDeferred` | Integration | Error path: crash x {S1..S5, mid-write} + Deferred x {S1,S2,S4} |
| SM-6: no regression | `BT/sketch/v2/TechnicalDataConversionGoldenTest.kt` | `conversion_should_CarryTechnicalDataVerbatim_When_RefusalOverridden` / `conversion_should_RefuseAndWriteNothing_When_ZonesOrDimensionsPresent` | Integration | Fusion, CSV, digest output equality |
| SM-6: no regression | `BT/projectbridge/SidecarOldReaderSchemaGateTest.kt` | `oldReader_should_FailClosedAndWriteNothing_When_Schema3SheetReferenced` | Integration | Fail-closed |
| SM-6: no regression | `BT/projectbridge/BundleV2PassthroughTest.kt` | `v2Sheet_should_SurviveEveryProjectLevelWrite_When_DimensionEditSyncRestorePull` | Integration | Data-loss guard |
| SM-6: no regression | `BT/projectbridge/ReconcilerV2GoldenTest.kt` | `reconcile_should_KeepCorruptAndWriteNothing_When_V2ShaMismatch` | Integration | Error path |
| SM-6: no regression | `BT/projectbridge/SidecarWriterRaceTest.kt` | `staleReconcilePush_should_ReturnStaleOpaqueAndWriteNothing_When_SaverWroteFirst` | Integration | Race |
| SM-6: no regression | `BT/projectbridge/SidecarWriterRaceTest.kt` | `parkedBundle_should_BeDroppedAsStaleOpaque_And_V2FileSurvive_When_ParkConvertFlush` | Integration | Pre-mortem P1 #2 (Story 1.1.3 AC) |
| SM-6: no regression | `BT/db/SketchSheetWritePathsPreserveSchemaTest.kt` (NEW, Task 1.4.1d) | `schemaVersion2_should_Survive_When_EverySheetRowWritePathRuns` | Integration | Pre-mortem P1 #1: `insertSketchSheet`, stub, `put`, both `replaceProjectContents`, layer/zone edits, dimension save |
| SM-6: no regression | `BT/projectbridge/SidecarBackupAndStagingTest.kt` | `sweep_should_DeleteOnlySchema1Unreferenced_And_LeaveStagedV2AndConflictsAlone_When_WriteLocked` | Integration | Pre-mortem P1 #1 (Task 0.1.1b) |
| SM-6: no regression | `BT/sketch/v2/LegacyStoreRefusesV2Test.kt` | `legacyStoreAndRouter_should_RefuseV2_When_FileSchema2_And_MirrorResetTo1` | Integration | File schema wins over mirror |
| SM-6: no regression | `BT/sketch/v2/SheetSaverConflictTest.kt` (NEW, Task 1.4.4c) | `saver_should_WriteConflictCopyAndKeepPulledFile_When_FileChangedUnderOpenEditor` | Integration | Pre-mortem P1 #3: both versions survive, diagnostics entry, `sketch.save.conflict` |
| SM-6: no regression | `BT/sketch/v2/MixedProjectFusionGoldenTest.kt` | `fusionCsvDigest_should_EqualUnconvertedOutput_When_OneSheetConverted` | Integration | Mixed v1+v2 |
| SM-6: no regression | `BT/db/MigrationRunnerSchemaSyncTest.kt` (existing) | `migrationRunner_should_ContainEveryCreateTable_When_SchemaSynced` | Integration | Existing gate must stay green |
| SM-6: no regression | `BT/sketch/v2/RestoreFromBackupTest.kt` | `restore_should_ReturnOriginalV1Bytes_When_BackupValid` / `restore_should_RefuseAndChangeNothing_When_BackupMissingOrShaMismatch` | Integration | Happy + error |
| SM-6: no regression | `JT/sketch/v2/ParityV1SuiteTest.kt` (Task 6.2.4b) | `v2Editor_should_ExerciseEveryV1Capability_When_ParitySuiteRun` | Integration | Every v1 tool/export/zone/layer behaviour via v2; gates legacy removal |
| SM-6: no regression | `BT/sketch/v2/ConversionPolicyTest.kt` | `refusal_should_BeHasTechnicalData_When_ZonesOrProjectDimensionsNonEmpty` | Unit | Predicate truth table |
| SM-7: interop | `CT/sketch/excalidraw/RoundTripPropertyTest.kt` | `exportThenImport_should_PreserveKindsGeometryTextBindingsZOrder_When_GeneratedSupportedScene` | Property | Happy path (kotest `Arb`) |
| SM-7: interop | `CT/sketch/excalidraw/FixtureRoundTripTest.kt` | `exportImportExport_should_YieldEqualScene_When_EachHandAuthoredFixture` | Integration | Plus `extras` re-emitted unchanged |
| SM-7: interop | `CT/sketch/excalidraw/ImporterFixtureTest.kt` | `importer_should_MapKindsAndBindingsBothGenerations_When_FixtureImported` | Unit | Happy path; `isDeleted` dropped, report counts |
| SM-7: interop | `CT/sketch/excalidraw/ImporterFixtureTest.kt` | `importer_should_SkipAndReportUnknownKinds_When_EmbeddableOrUnknownArrowhead` | Unit | Error path |
| SM-7: interop | `CT/sketch/v2/SvgExporterGoldenTest.kt` | `svg_should_EqualGolden_When_Architecture15Exported` | Unit | Happy path |
| SM-7: interop | `CT/sketch/v2/SvgEscapingPropertyTest.kt` | `svg_should_ParseWellFormedAndRoundTripText_When_GeneratedHostileStrings` | Property | Error path (`<`, `&`, `]]>`, `\u0000`, emoji) |
| SM-7: interop | `JT/sketch/v2/PngExportTest.kt` (NEW, Story 5.2.2 Files, `// jvmTest-reason: pixels`) | `png_should_RenderEveryKindNonBlank_When_OneOfEachKindCleanAndSketchy` | Integration | Metric 7 per-kind pixel check |
| SM-7: interop | `JT/sketch/v2/PngExportTest.kt` | `png_should_DecodeTo400x200_When_200x100At2x` / `png_should_RefuseWithReason_When_SideExceeds16kPx` | Unit | Happy + limit error |
| R-8: Clean/Sketchy | `CT/sketch/v2/SketchyGoldenTest.kt` | `sketchyStyle_should_MatchRoughGoldenWithin1e6_When_Seed1337Rect` | Unit | Happy path |
| R-8: Clean/Sketchy | `CT/sketch/v2/SketchyGoldenTest.kt` | `sketchyStyle_should_EqualCleanBytes_When_RoughnessZero` | Unit | Edge/error path |
| R-8: Clean/Sketchy | `CT/sketch/v2/HachureFillTest.kt` | `hachure_should_LieInsidePolygon_When_AnyConvexOrDiamond` | Property | Fill clipping |
| R-8: Clean/Sketchy | `CT/sketch/v2/FreehandGoldenTest.kt` | `freehandOutline_should_MatchGoldenWithin1e6_When_30PointFixture` | Unit | Golden |
| R-8: Clean/Sketchy | `JT/sketch/v2/LargeSceneDegradationTest.kt` (NEW, Story 2.2.3 Files, `// jvmTest-reason: pixels`) | `largeScene_should_RenderCleanAndKeepStoredRoughness_When_4900Elements` | Integration | Degradation |
| R-9: text | `CT/sketch/v2/TextWrapTest.kt` | `wrap_should_SplitAfterAuthentication_When_Width120FixedAdvance8` | Unit | Happy path |
| R-9: text | `CT/sketch/v2/TextWrapTest.kt` | `render_should_NotRemeasureOrPersistSize_When_FontNotLoaded` | Unit | Error path |
| R-9: text | `CT/sketch/v2/TextWrapTest.kt` | `drawList_should_BeEqualAcrossTargets_When_SameSheetBytesWithStoredWrapOpened` | Unit | Pre-mortem P2 #7, persisted wraps authoritative |
| R-9: text | `CT/sketch/v2/TextWrapTest.kt` | `cjk_should_UsePersistedSizeAndFallbackGlyphPath_When_FakeProviderLacksGlyph` | Unit | Policy from Spike 0.1.3b |
| R-9: text | `CT/sketch/v2/ContainerTextTest.kt` | `containerText_should_GrowContainerAndDeleteWithIt_When_TextCommitted` | Unit | Happy path; autoResize loop guard |
| R-9: text | `AT/sketch/v2/TextEditorTest.kt` | `textOverlay_should_PositionAtTransformedRectAndCommitMultiline_When_Zoom2_5` | Integration | Compose + Robolectric |
| R-10: input | `AT/sketch/v2/PointerAdapterTest.kt` | `pointerAdapter_should_PanAndCreateNothing_When_TouchOneFingerDragInRectangleMode` | Unit | Happy path |
| R-10: input | `AT/sketch/v2/PointerAdapterTest.kt` | `pointerAdapter_should_IgnoreTouch_When_StylusStrokeActive` | Unit | Palm rejection error path |
| R-10: input | `JT/sketch/v2/PointerAdapterDesktopTest.kt` (`// jvmTest-reason: desktop-only`) | `wheelZoom_should_KeepFocalPointAndClamp1To3000Percent_When_WheelScrolled` | Integration | Desktop |
| R-10: input | `JT/sketch/v2/KeyboardShortcutsTest.kt` (`// jvmTest-reason: desktop-only`) | `keyDispatcher_should_UndoAndAnnounce_When_CtrlZPressed` / `keyDispatcher_should_TypeLetterNotSwitchTool_When_TextOverlayFocused` | Integration | Happy + conflict |
| R-10: input | `JT/sketch/v2/InputSpikeTest.kt` + `AT/sketch/v2/InputSpikeAndroidTest.kt` | `inputSpike_should_LogStylusTypeAndPressure_When_MotionEventStylus0_6` | Unit | Per-target matrix |
| R-11: flag/backup/rollback | `CT/sketch/v2/EditorRouterTest.kt` | `router_should_ChooseEditorPerMatrix_When_ModeSchemaConversionStateCombinations` | Unit | Happy path (full table: (On,1,NotConverted) is classic editable WITH an Upgrade offer and never converts on open, per-sheet Declined has no chip, Failed, NewerSchema; UX-104) |
| R-11: flag/backup/rollback | `BT/sketch/v2/SheetLeaseRegistryTest.kt` | `lease_should_RefuseSheetBusy_When_LegacyEditorHoldsSheet` | Unit | Error path |
| R-11: flag/backup/rollback | `BT/sketch/v2/ConversionReadyConsistencyTest.kt` | `conversionReady_should_BeFalse_When_AnyRequiredTestClassUnregistered` | Unit | Constant cannot lie; required list includes 1.1.5 (`SheetCodecV2Test`, `SheetCodecV2FuzzTest`) and technical-data (`TechnicalDataTest`, `ConversionPolicyTest`) classes (consistency C5); iteration 10: it reads the `AllBusinessTests` source and loads each class by name on the classpath, and reads no `BUILD.bazel` (no filegroup exports one into the businessTest sandbox) |
| R-11: flag/backup/rollback | `BT/sketch/v2/LegacyStoreRefusesV2Test.kt` | `legacyStore_should_ReturnSheetIsV2Left_When_SchemaVersion2` | Integration | Defence in depth |
| R-11: flag/backup/rollback | `BT/sketch/v2/RouterRefusalGoldenTest.kt` | `router_should_ReturnLegacyWithHasTechnicalDataAndWriteNothing_When_FixtureProject` | Integration | Refusal end to end |
| R-11: flag/backup/rollback | `BT/sketch/v2/FlagRolloutTest.kt` | `v2Sheet_should_OpenReadOnlyWithReason_When_FlagOff` | Integration | Rollback |
| R-11: flag/backup/rollback | `BT/sketch/v2/SheetConversionGoldenTest.kt` | `converter_should_ReturnNotReadyAndWriteNothing_When_ConversionReadyFalse` / `converter_should_ReturnRequiresConfirmation_When_FirstV2WriteOnGraph` | Unit | Gate and OD-1 hook |
| R-11: flag/backup/rollback | `BT/projectbridge/SidecarBackupAndStagingTest.kt` | `backupDir_should_BeInvisibleToHashAndSurviveWrites_When_BundleRewritten` | Integration | Backup lifecycle |
| R-12: security | `CT/sketch/excalidraw/ParserFuzzTest.kt` | `parser_should_ReturnLeftOrSanitisedScene_When_25MbDeepNanDuplicateCyclicSvgInput` | Property | Fuzz, never throws |
| R-12: security | `CT/sketch/excalidraw/ParserFuzzTest.kt` | `parser_should_RefuseBeforeParsing_When_FileOverSizeCap` | Unit | Cap error path |
| R-12: security | `BT/sketch/excalidraw/SketchImportImportDirectionTest.kt` | `importPackage_should_ImportNoHttpClient_When_Scanned` | Unit | No network |
| R-12: security | `CT/sketch/v2/SheetCodecV2FuzzTest.kt` | `decode_should_ReturnSheetErrorAndNeverThrow_When_TruncatedNestedOversizedNanBytes` | Property | Native sheet untrusted |
| R-12: security | `CT/sketch/v2/SheetCodecV2Test.kt` | `codec_should_RoundTripAndReencodeIdentically_When_DocumentWithLayersConstraintsExtras` | Unit | Happy path |
| R-13: persistence | `CT/sketch/v2/SheetCodecV2Test.kt` | `reencode_should_ChangeExactlyOneLine_When_OneElementXChanged` | Unit | Pre-mortem P2 #6, element-local diffs |
| R-12: security | `CT/sketch/v2/SceneLimitsTest.kt` | `limits_should_BeTheOnlyNumericCaps_When_SourcesGrepped` | Unit | ONE canonical 32-field `SceneLimits` (Story 1.2.4: elements 5,000, constraints 200, coord 2^28, points 2,000, JSON depth 64, sheet 16 MB, text 10,000, name 80, import 20 MB, image 60 MB, image side 8,192, image pixels 16,000,000, decoded 64 MB per sheet, parse 5,000 ms, library 200 items and 16 MB, layers 64, layer id 64, soft 4,000 / 160, PNG 16,000 px, slow solve 100 ms, history 100, semantics 200, diagnostics 20, notices 3, go-to 50, conflict copies 10, zoom 1-3000); the grep covers `V2`, `RT`, `XCAL`, `SOLV`, `UI2` and `CM/projectbridge`, with a shrink-only `scene-limits-allowlist.txt` for non-cap numerics; also `sceneLimits_should_HaveExactlyTheCanonicalFieldsAndDefaults_And_NoImageMegapixelsFieldRemains` |
| R-13: persistence | `BT/db/SketchV2MigrationTest.kt` | `migration_should_AddSchemaVersionDefault1AndBeIdempotent_When_RealV1DbFixtureMigrated` | Migration | Happy path; no `sketch_elements`/`sketch_constraints` tables |
| R-13: persistence | `BT/db/SketchV2MigrationTest.kt` | `migration_should_be_reversible` | Migration | Up: column + rows = 1. Down (plan is forward-only; reversal is the restore path): convert one sheet, run `SketchBackupRestore`, assert v1 bytes and `schema_version = 1`; column dropped is NOT claimed (see Gaps) |
| R-13: persistence | `BT/db/SketchV2MigrationTest.kt` | `reconciler_should_UpdateMirrorToFileSchema_When_DbRow1AndDiskSheet2` | Integration | File wins |
| R-13: persistence | `BT/db/UpgradeResilienceTest.kt` (existing, extended) | `closedDb_should_NotCrashSketchReads_When_SchemaVersionFlowsCollected` | Integration | `catchDbError` guard |
| R-13: persistence | `BT/repository/ReplaceProjectContentsV2Test.kt` | `replaceProjectContents_should_StoreV2RowWithNoPrimitives_When_SqlDelightAndInMemory` | Integration | Shared contract over both repos |
| R-13: persistence | `BT/projectbridge/SheetCodecDispatchTest.kt` | `classify_should_AcceptSheetSchema2AndRejectSchema3AndModelSchema2_When_PerKindLimits` | Unit | Happy + error |
| R-13: persistence | `BT/projectbridge/SheetCodecDispatchTest.kt` | `v1Bundles_should_ReencodeByteIdentically_When_EveryGoldenFixture` | Unit | Regression |
| R-13: persistence | `BT/projectbridge/TechnicalDataTest.kt` | `technicalData_should_BeByteIdentical_When_NoChangeRequestedAndLayerMirrorWritten` | Unit | Verbatim carry |
| R-13: persistence | `CT/sketch/v2/CanonicalJsonTest.kt` | `canonicalJson_should_RoundTripFiniteDoubles_When_ArbDouble` / `canonicalJson_should_ReturnNonFiniteLeft_When_NaN` | Property | Happy + error |
| R-13: persistence | `CT/sketch/v2/SheetSaverTest.kt` | `saver_should_PersistFinalScene_When_EditUndoEditWithin100ms` | Unit | Single-writer |
| R-13: persistence | `CT/sketch/v2/SheetSaverTest.kt` | `saver_should_KeepMemoryAndShowRetry_When_SerializerThrowsOom` | Unit | Error path |
| R-13: persistence | `BT/sketch/v2/SheetSaverReconcileIntegrationTest.kt` (NEW, Story 1.4.4 Files) | `reconcile_should_BeUnchanged_When_SaverJustWroteLayerEdit` | Integration | Saver + DB row + model mirror agree |
| R-14: observability | `CT/sketch/v2/SketchLogTest.kt` | `solveFailedLog_should_CarryIdsOnly_When_SheetContainsSecretText` | Unit | Happy path |
| R-14: observability | `CT/sketch/v2/SketchLogTest.kt` | `allSketchLogLines_should_ContainNoElementTextOrFileName_When_ScenarioRunWithMarkers` | Property | Error/negative path |
| R-14: observability | `CT/sketch/v2/SketchLogTest.kt` (case in Story 6.2.3) | `pointerMove_should_EmitNoLogLine_When_1000MovesDispatched` | Unit | UX-73 |
| R-14: observability | `JT/sketch/v2/SketchEngineBenchmark.kt` | `budgets_should_RecordEightMetricsAndFailBuild_When_BudgetBreached` | Integration | Writes `sketch-engine-budgets.json`, now incl. `sketch.encode_ms_5000` and `sketch.save_bytes_5000` |
| R-13: persistence | `CT/sketch/v2/SheetSaverTest.kt` | `saver_should_BackOffDebounce_When_LastEncodeExceededBudget` | Unit | Pre-mortem P2 #6 (virtual time) |
| R-14: observability | `CT/sketch/v2/SketchLogTest.kt` | `scriptedRun_should_EmitEachObservabilityLineOnce_When_AllTriggersFired` | Unit | Consistency C13 (Story 6.2.3) |
| R-15: perf/scale | `JT/sketch/v2/SketchEngineBenchmark.kt` | `framePanP95_should_BeAtMost1_5xEmptyCanvas_When_500Elements` | Integration | Happy path (JVM CI only) |
| R-15: perf/scale | `CT/sketch/v2/SpatialIndexPropertyTest.kt` | `index_should_EqualBruteForce_When_5000ElementsRandomEdits` | Property | Happy path |
| R-15: perf/scale | `CT/sketch/v2/HitTesterTest.kt` | `hit_should_MissHollowInterior_And_HitWithin6PxOverZoom_When_Zoom1And0_1` | Unit | Hit rules |
| R-15: perf/scale | `CT/sketch/v2/SceneModelTest.kt` | `add_should_ReturnCapacityExceededLeft_When_5000ElementsPresent` | Unit | Error path |
| R-15: perf/scale | `CT/sketch/v2/ConstraintAdapterTest.kt` | `addConstraint_should_ReturnCapExceeded200Left_When_201stAdded` | Unit | Error path |
| R-15: perf/scale | `JT/sketch/v2/ScenePainterTest.kt` | `staticLayer_should_NotRecompose_When_OneOf500ElementsDragged60Moves` | Integration | Recomposition counter |
| R-15: perf/scale | `JT/sketch/v2/SceneStructureBenchmarkGuard.kt` (NEW, Story 0.1.6; JVM bench, `// jvmTest-reason: desktop-only`) | `snapshotPerStep_should_BeWithinRecordedBudget_When_5000ElementsMoved100Times` | Integration | Records decision |
| R-16: accessibility | `AT/sketch/v2/NonDragTransformTest.kt` | `nonDrag_should_ResizeWidthPlus30_When_ResizeThenNudge3At10ThenApply` | Unit | Happy path; also rotate, bind end, marquee, polyline |
| R-16: accessibility | `AT/sketch/v2/NonDragTransformTest.kt` | `nonDrag_should_ClampAndMessage_When_ApplyWouldMakeWidthZero` | Unit | Error path |
| R-16: accessibility | `AT/sketch/v2/SceneSemanticsTest.kt` | `semantics_should_EmitAtMost200NodesAndLabelArrow_When_3000Elements` | Integration | "Arrow from API to DB" |
| R-16: accessibility | `AT/sketch/v2/GoToElementTest.kt` | `goToElement_should_PanSelectAndFocus_When_BillingResultActivated` | Integration | Beyond-viewport reach |
| R-16: accessibility | `AT/sketch/v2/DiagnosticsTest.kt` | `diagnostics_should_AnnounceOnceWithRetry_When_SaveFails` | Unit | Live region |
| R-16: accessibility | `AT/sketch/v2/LayersPanelTest.kt` | `layerToggles_should_ReadLayerWallsVisibleSwitch_When_TalkBackSemanticsQueried` | Unit | Merged semantics |
| R-17: embeds/copy/assets | `JT/sketch/v2/EmbedDispatchTest.kt` (`// jvmTest-reason: pixels`) | `dispatcher_should_RenderV1AndV2_When_JournalEmbedsOfS1AndS2` | Integration | Happy path |
| R-17: embeds/copy/assets | `JT/sketch/v2/EmbedDispatchTest.kt` | `dispatcher_should_ShowUpdateSteleKitPlaceholder_When_NewerSchemaProject` | Integration | Error path, no crash |
| R-17: embeds/copy/assets | `BT/sketch/v2/SketchPreviewV2Test.kt` | `previewLoader_should_ReturnDrawingNotEmpty_When_ConvertedSheetHasNoDbPrimitives` | Integration | Review C4 |
| R-17: embeds/copy/assets | `BT/merge/SketchBundleCopyTest.kt` | `copy_should_CreateFilesUnderTargetLock_When_SlugAbsent` | Integration | Happy path, run via `TargetWriterContractSuite` for both writers |
| R-17: embeds/copy/assets | `BT/merge/SketchBundleCopyTest.kt` | `copy_should_RefuseSlugExistsAndAssetConflictAndChangeNothing_When_TargetHasSlugOrDifferingAsset` | Integration | Error path |
| R-17: embeds/copy/assets | `CT/sketch/v2/ImageAssetStoreTest.kt` | `referencedFileIds_should_ReturnOnlyLiveSet_When_DeletedElementHoldsOtherId` | Unit | Asset enumeration |
| R-18: editing aids | `CT/sketch/v2/ObjectSnapTest.kt` | `objectSnap_should_SnapToEdgeAndShowOneGuide_When_Within8Px` / `objectSnap_should_Disable_When_CtrlHeld` | Unit | Happy + error |
| R-18: editing aids | `CT/sketch/v2/AlignCommandsTest.kt` | `distributeHorizontally_should_YieldX0_50_100_When_RectsAt0_30_100` | Unit | Happy path |
| R-18: editing aids | `CT/sketch/v2/AlignCommandsTest.kt` | `paste_should_OffsetBy10AndRemapIds_When_RepeatedPaste` | Unit | Clipboard |
| R-18: editing aids | `CT/sketch/excalidraw/ClipboardTest.kt` | `paste_should_CreateThreeElementsAtCursorInOneUndo_When_ExcalidrawClipboardJson` / `paste_should_DoNothing_When_PlainText` | Unit | Happy + error |
| R-18: editing aids | `CT/sketch/v2/ElementLibraryTest.kt` | `library_should_InsertFreshIdsAndRoundTripLib_When_ItemInsertedTwice` | Unit | Happy path |
| R-18: editing aids | `CT/sketch/v2/ElementLibraryTest.kt` (case in Story 3.5.6 AC) | `library_should_RefuseWithMessage_When_200ItemCapReached` | Unit | Error path |
| R-18: editing aids | `AT/sketch/v2/StylePanelTest.kt` | `stylePanel_should_UpdateSelectionInOneUndo_Else_ToolDefaults_When_BoldChosen` | Unit | Happy + mixed value |
| R-18: editing aids | `CT/sketch/v2/ElementCommandsTest.kt` | `duplicate_should_RemapBindingsToCopies_When_RectAndBoundArrowDuplicated` | Unit | Binding remap |
| R-18: editing aids | `CT/sketch/v2/BindingEngineTest.kt` + `BindingInvariantPropertyTest.kt` | `arrowEnd_should_Follow1e6_When_TargetMoved` / `bindingInvariant_should_Hold_When_AnyCommandSequence` | Unit / Property | Binding |
| R-19: engineering | `BT/sketch/constraints/SketchSolverImportDirectionTest.kt` | `solver_should_ImportNoSceneOrUi_When_Scanned` | Unit | Hexagonal boundary |
| R-19: engineering | `AT/sketch/v2/WiringV2AndroidEntryPointTest.kt` and `JT/sketch/v2/WiringV2DesktopEntryPointTest.kt` (NEW, Task 6.2.1a; businessTest cannot call a fixture, D-146) | `everyClassSeam_should_BeReachableThroughTheRealFixture` (the nine names of the `plan-seam-classes` block); `seams_should_BeConstructedInCompositionRoots_When_WiringCompletenessAuditRun` stays in `WiringCompletenessTest` for the five audited interfaces | Integration (Robolectric and JVM) | `WiringCompletenessTest` + `wiring-catalog.txt` lines (added by the task of each seam after its SEAMS task) + `ObservableEffect.assert` |
| R-19: engineering | `BT/TestRegistrationAuditTest.kt` (NEW, Story 6.2.1 Files) | `everyV2TestClass_should_BeRegisteredInAllSuites_When_SourceTreeScanned` | Unit | Fails if a `sketch/v2` test class is absent from `AllBusinessTests`/`AllJvmTests`/`AllAndroidUnitTests` or a commonTest file lacks a `BUILD.bazel` entry |
| R-19: engineering | `BT/TestPlacementAuditTest.kt` + `TestHygieneAuditTest.kt` (existing) | `placementAndHygiene_should_PassWithNoNewBaselineEntries_When_V2TestsAdded` | Unit | `jvmTest-reason` header, no `Thread.sleep`/`GlobalScope` |
| R-19: engineering | `BT/db/RestrictedDatabaseQueriesAuditTest.kt` (existing pattern) | `schemaVersionUpdate_should_RouteThroughDirectSqlWriteStub_When_QueryAdded` | Unit | `@DirectSqlWrite` gate |
| R-19: engineering | `AT/sketch/v2/AndroidCrashGuardTest.kt` (NEW, Story 1.4.4 Files) | `longLivedScopes_should_SwallowOomInHandler_When_SheetSaverAndSolveRunnerThrow` | Unit | `CoroutineExceptionHandler` rule |

## UX Acceptance Tests

Default tool: Robolectric Compose test in `AT/sketch/v2/Ux*AcceptanceTest.kt` (per CLAUDE.md, behaviour tests that need no real pixels belong in `androidUnitTest`, not `jvmTest`). "Pure" = commonTest scenario harness. "Manual" = row in `owner-verification.md` (device-only; CI cannot cover, plan OD-2). No Playwright: the app is Compose, not a DOM; the wasm browser pass is a manual checklist row (`wasm` in Steps).

| UX Criterion | Test File | Test Name | Tool | Steps |
|---|---|---|---|---|
| UX-01 labelled box <= 4 steps | `AT/sketch/v2/UxAuthoringAcceptanceTest.kt` | `ux01_labelledBox_should_NeedAtMost4Steps_UnderTheOneCountingConvention` | Robolectric | (1) pick Rectangle, (2) drag, (3) Enter to open the editor, (4) type the label and Esc as ONE typed-string step; assert the shared step counter reads 4 and element + text exist |
| UX-02 bound arrow <= 3 steps | `UxAuthoringAcceptanceTest.kt` | `ux02_boundArrow_should_NeedAtMost3StepsAndFollowBox` | Robolectric | Arrow, drag A to B, release; move A, arrow end follows |
| UX-03 15-element diagram, no drag on touch-only | `UxAuthoringAcceptanceTest.kt` | `ux03_architectureDiagram_should_BeAuthorableWithNonDragPathsOnly` | Robolectric | scripted via crosshair/non-drag mode, Touch pointers only; golden scene |
| UX-04 tool lock | `UxAuthoringAcceptanceTest.kt` | `ux04_toolLock_should_ReturnToSelectOrStayAndAnnounceWithoutColour` | Robolectric | draw one shape lock off/on; assert tool, glyph node and semantics "Tool lock on" |
| UX-05 Undo/Redo/Delete pinned | `AT/sketch/v2/ToolStripTest.kt` | `ux05_pinnedActions_should_BeVisibleWithoutScrolling_When_AnyLayout` | Robolectric | 360/600/840 dp; assert displayed, not scrolled |
| UX-06 adaptive layout, no overlap | `ToolStripTest.kt` | `ux06_layout_should_DockAt360AndRailAt840WithoutOverlap_When_ChipZoomMicPresent` | Robolectric | bounds intersection of crosshair chip, zoom cluster, mic = empty |
| UX-07 touch pans, stylus draws, hint | `PointerAdapterTest.kt` | `ux07_touchDrag_should_PanThenStylusDraw_AndFirstTimeHintShownOnce` | Robolectric | one-finger drag, stylus drag, toggle Finger drawing, hint once |
| UX-08 second pointer/Esc cancels | `CT/sketch/v2/tools/ToolCancelTest.kt` | `ux08_cancel_should_LeaveNoElementAndNoUndoEntry` | Pure | mid-draw second pointer, then Esc; history size unchanged |
| UX-09 zoom focal point, fit, clamp | `JT/sketch/v2/PointerAdapterDesktopTest.kt` | `ux09_zoom_should_KeepFocalPointFitInOneStepAndDisableButtonsAtLimits` | Compose desktop | wheel + pinch; Fit; zoom to 1% and 3000% |
| UX-10 multi-select resize one undo | `CT/sketch/v2/tools/SelectToolTest.kt` | `ux10_multiResize_should_BeSingleUndoStep` | Pure | resize two, undo once, both restored |
| UX-11 group selection | `CT/sketch/v2/GroupFrameTest.kt` | `ux11_groupSelect_should_SelectOuterThenDescendAndUngroupByShortcutAndMenu` | Pure | click, double-click, Ctrl+Shift+G, menu |
| UX-12 locked layer message | `AT/sketch/v2/UxOrganisationAcceptanceTest.kt` | `ux12_lockedLayerEdit_should_ShowLockedMessageWithUnlock` | Robolectric | edit element on locked layer; assert text "Locked layer: Walls." + Unlock; the canvas never selects hidden/locked elements; the Elements list may select a locked one (shows [Unlock]) and never a hidden one (offers [Show layer]) |
| UX-13 hide clears selection | `LayersPanelTest.kt` | `ux13_hideLayer_should_ClearSelectionAndAnnounce` | Robolectric | select, hide layer; assert empty selection + live region text |
| UX-14 style panel heading, Mixed | `StylePanelTest.kt` | `ux14_stylePanel_should_ShowSelectionNOrDefaultsForToolAndMixedChip` | Robolectric | select 3 mixed elements, then none |
| UX-15 colour names | `StylePanelTest.kt` | `ux15_colourSwatches_should_HaveNameInSemanticsAndTooltip` | Robolectric | every swatch node has non-empty contentDescription |
| UX-16 layer ops one undo each | `LayersPanelTest.kt` | `ux16_layerOps_should_BeOneUndoEachViaDragAndMoveButtons` | Robolectric | create, reorder (drag + up/down), lock, hide, rename, opacity, delete |
| UX-17 TalkBack toggle reads | `LayersPanelTest.kt` | `ux17_layerToggles_should_ReadVisibleAndLockedSwitchText` | Robolectric | merged semantics strings; device TalkBack row in manual list |
| UX-18 delete layer confirm | `LayersPanelTest.kt` | `ux18_deleteLayerWithElements_should_ConfirmCountAndOfferMoveToBase` | Robolectric | dialog text contains count, 3 buttons, undoable (Story 3.5.2 AC, Task 3.5.2c; file `UxOrganisationAcceptanceTest.kt` too) |
| UX-19 panel hidden until 2nd layer | `LayersPanelTest.kt` | `ux19_layersPanel_should_HideUntilSecondLayerAndBeReachableFromOverflow` | Robolectric | overflow Layers entry creates the second layer |
| UX-20 IME multi-line at 2.5x | `AT/sketch/v2/TextEditorTest.kt` + manual | `ux20_textOverlay_should_StayAboveKeyboardAtZoom2_5` | Robolectric + manual | inset test in Robolectric; real IME composition on Pixel_Fold (manual) |
| UX-21 empty blur | `TextEditorTest.kt` | `ux21_emptyBlur_should_LeaveNothingAndNoMessage` | Robolectric | open overlay, blur empty |
| UX-22 shortcut letters type | `JT/sketch/v2/KeyboardShortcutsTest.kt` | `ux22_overlayFocus_should_TypeLettersAndKeepTool` | Compose desktop | type "rtv" in overlay |
| UX-23 >10,000 chars refused | `TextEditorTest.kt` (case from Task 3.4.1c) | `ux23_paste_should_RefuseOver10000CharsAndKeepText` | Robolectric | paste 10,001 chars; dialog states limit; text unchanged |
| UX-30 constraint <= 3 steps | `AT/sketch/v2/UxConstraintsAcceptanceTest.kt` | `ux30_addParallel_should_NeedAtMost3Steps` | Robolectric | select two lines, Constrain, Parallel |
| UX-31 status line | `ConstraintPanelTest.kt` | `ux31_statusLine_should_ShowOneOfThreeStatesAsLiveRegion` | Robolectric | free, full, over-constrained |
| UX-32 conflict rejection | `ConstraintPanelTest.kt` | `ux32_conflictingConstraint_should_RejectNameBothAndOfferThreeActions` | Robolectric | message "Parallel conflicts with Horizontal on Line 3", geometry unchanged, Esc = remove |
| UX-33 not colour alone | `ConstraintPanelTest.kt` | `ux33_stateEncoding_should_UseGlyphAndTextAndMeet3to1And24dp` | Robolectric | glyph nodes, contrast calc, bounds >= 24 dp |
| UX-34 row/geometry highlight both ways | `ConstraintPanelTest.kt` | `ux34_selection_should_HighlightConstraintsAndRowsBothWays` | Robolectric | select row, then geometry |
| UX-35 non-convergence message | `CT/sketch/v2/SolveRunnerTest.kt` | `ux35_nonConvergence_should_KeepLastGoodPoseAndShowCannotSatisfy` | Pure + Robolectric toast | no NaN in geometry; undo works |
| UX-36 slow solve progress/cancel | `SolveRunnerTest.kt` (Story 4.1.4 AC, Task 4.1.4b) | `ux36_solveOver100ms_should_ShowProgressAndCancelRestoresLastGood` | Pure (virtual time) | fake solver delay via `TestScope` clock, no real delay |
| UX-37 disabled with reason | `ConstraintPanelTest.kt` | `ux37_invalidMenuItems_should_BeDisabledWithReason_And_FlyoutAvailableForOneOrMoreSelected` | Robolectric | Tangent without circle; one line selected enables Horizontal/Vertical/Fixed/Distance; two-element kinds disabled with reason; nothing selected disables the trigger (consistency B3) |
| UX-38 delete cascades constraints | `CT/sketch/v2/ConstraintAdapterTest.kt` | `ux38_deleteConstrainedGeometry_should_RemoveInSameStepWithToastCount` | Pure | undo restores |
| UX-39 auto-constraint toast | `CT/sketch/v2/AutoConstrainTest.kt` (Story 4.2.2 AC, Task 4.2.2b) | `ux39_autoConstraint_should_AddToastWithUndoAndCtrlSuppresses` | Pure + Robolectric toast | endpoint snap, near-horizontal 2 degrees |
| UX-40 non-drag per gesture | `AT/sketch/v2/NonDragTransformTest.kt` | `ux40_everyDragGesture_should_HaveSinglePointerPath_ExceptRecordedFreedrawExemption` | Robolectric | resize, rotate, bind, marquee, polyline, layer reorder; the Freedraw stroke exemption is asserted as the only allow-listed drag |
| UX-41 keyboard-only, no trap | `JT/sketch/v2/KeyboardShortcutsTest.kt` | `ux41_keyboardOnly_should_ReachEveryOperationAndTabLeavesCanvas` | Compose desktop | tools, selection, z-order, layers, constraints |
| UX-42 `?` sheet | `KeyboardShortcutsTest.kt` | `ux42_questionMark_should_ListEveryCoreShortcutAndEscReturnsFocus` | Compose desktop | compare to core map list |
| UX-43 undo announcements | `KeyboardShortcutsTest.kt` | `ux43_undoRedo_should_AnnounceActionNameOrNothingToUndo` | Compose desktop | live region text |
| UX-44 element semantics | `AT/sketch/v2/SceneSemanticsTest.kt` | `ux44_semantics_should_LabelByKindAndCapAt200WithStatement` | Robolectric + manual | TalkBack on device (manual) |
| UX-45 focus ring contrast | `SceneSemanticsTest.kt` | `ux45_focusRing_should_Meet3to1AgainstElementAndBackgroundAndNotBeHidden` | Robolectric | colour math; panel overlap check |
| UX-46 contrast and targets | `AT/sketch/v2/UxA11yAcceptanceTest.kt` (NEW, Story 3.6.6, Task 3.6.6a) | `ux46_tokens_should_Meet4_5To1Text3To1NonTextAnd24Or48dpTargets` | Robolectric | walk semantics tree + theme tokens |
| UX-47 reduce motion | `UxA11yAcceptanceTest.kt` (Task 3.6.6b) | `ux47_reduceMotion_should_ReplaceEveryTransitionWithInstantChange_And_NoAnimationApiOutsideMotionPolicy` | Robolectric | animation scale 0: solve, zoom-to-fit, panel open, flyout, toast, coach mark, banner, erase fade, selection overlay; grep test over `UI2` |
| UX-48 live regions | `DiagnosticsTest.kt` | `ux48_saveAndSolveResults_should_BePoliteAndAssertiveOnlyForBlockedWork` | Robolectric | save ok, solve done, save failed |
| UX-50 import <= 3 steps, one undo | `AT/sketch/v2/ImportFlowTest.kt` | `ux50_import_should_NeedAtMost3StepsOneUndoAndShowXofYSummary` | Robolectric | Import, pick fixture, Done |
| UX-51 invalid file | `ImportFlowTest.kt` | `ux51_invalidFile_should_ShowReasonCodeCreateNothingOfferChooseAnother` | Robolectric | feed `not-json.txt` |
| UX-52 size cap | `ImportFlowTest.kt` | `ux52_oversizeFile_should_BeRefusedBeforeParsingWithCap` | Robolectric | 21 MB stream, parser call counter 0 |
| UX-53 over 5,000 | `ImportFlowTest.kt` (Story 5.1.5 AC, Task 5.1.5b) | `ux53_over5000_should_OfferFirst5000NewSketchCancelAndEachWork` | Robolectric | three branches |
| UX-54 cancel / process death | `ImportFlowTest.kt` | `ux54_cancelOrKill_should_LeaveSheetUnchanged` | Robolectric | cancel; kill scope before commit |
| UX-55 missing image | `CT/sketch/excalidraw/ImporterFixtureTest.kt` | `ux55_missingImageBinary_should_BecomeLabelledPlaceholderAndLinkedNotDownloaded` | Pure | image fixture without `files` |
| UX-56 summary dialog a11y | `ImportFlowTest.kt` | `ux56_summaryDialog_should_CopyDetailsUndoImportAndTrapFocus` | Robolectric | focus order, title announced, focus restored |
| UX-57 export dialog | `AT/sketch/v2/ExportDialogTest.kt` (NEW, Story 5.2.3, Task 5.2.3b) | `ux57_exportDialog_should_OfferFormatsScopesAndFixImpossibleScale` | Robolectric | 24,000 px case shows "Use 1x" |
| UX-58 flatten notice | `ExportDialogTest.kt` | `ux58_excalidrawExportWithLayers_should_ShowFlattenNotice` | Robolectric | 2 layers |
| UX-59 clipboard paste | `CT/sketch/excalidraw/ClipboardTest.kt` | `ux59_paste_should_PlaceAtCursorOneUndoAndIgnorePlainText` | Pure | see R-18 |
| UX-60 embeds | `JT/sketch/v2/EmbedDispatchTest.kt` | `ux60_embeds_should_RenderV1V2AndNewerSchemaPlaceholder` | Compose desktop | see R-17 |
| UX-61 missing sketch | `EmbedDispatchTest.kt` (Story 6.1.1 AC, Task 6.1.1c) | `ux61_missingSketch_should_ShowNotFoundWithRemoveAndChoose` | Compose desktop | deleted slug |
| UX-62 copy refusal | `BT/merge/SketchBundleCopyTest.kt` | `ux62_copy_should_RefuseCollisionAndDisableUnwritableTargetWithReason` | Integration | `SlugExists`, capabilities |
| UX-63 copy success | `SketchBundleCopyTest.kt` (Story 6.1.2 AC, Task 6.1.2c) | `ux63_copySuccess_should_ShowDestinationAndOpenAction` | Integration | result model, not pixels |
| UX-64 first-write confirmation | `AT/sketch/v2/UxMigrationAcceptanceTest.kt` | `ux64_firstUpgrade_should_RequireOneCheckboxStateOlderBuildConsequence_And_OpenClassicWritesNothing_And_DialogHasNoKeepClassicCheckbox` | Robolectric | file bytes unchanged after Open classic, Esc and back (checked or not, nothing acknowledged, sheet stays `NotConverted`); exactly one checkbox in the semantics tree, unchecked on every open; `Declined` is set only by the chip-menu "Keep this sketch classic" (Task 1.4.6g), not re-offered at open, re-offered by the sheet menu (Story 1.4.6, Tasks 1.4.6a, 1.4.6g) |
| UX-65 post-conversion banner | `UxMigrationAcceptanceTest.kt` (Story 1.4.6, Task 1.4.6b) | `ux65_afterConversion_should_ShowOneTimeBannerWithDetails` | Robolectric | banner once, Details shows backup path |
| UX-66 failed conversion | `BT/sketch/v2/SheetConversionCrashInjectionTest.kt` + `UxMigrationAcceptanceTest.kt` | `ux66_failedConversion_should_KeepOriginalAndOfferThreeActions` | Integration + Robolectric | forced hash mismatch |
| UX-67 flag off read-only | `BT/sketch/v2/FlagRolloutTest.kt` + Robolectric | `ux67_flagOffV2_should_BeReadOnlyWithReasonAndPanZoomSelectExportWork` | Integration + Robolectric | disabled tools announce reason |
| UX-68 newer schema | `RouterRefusalGoldenTest.kt` | `ux68_newerSchema_should_ShowUpdateMessageAndNeverWrite` | Integration | file bytes unchanged |
| UX-69 flag flip mid-session | `UxMigrationAcceptanceTest.kt` (Task 1.4.6b) | `ux69_flagFlipMidSession_should_NotSwapEditorAndShowNextOpenNotice` | Robolectric | flip while open |
| UX-70 logs ids only | `CT/sketch/v2/SketchLogTest.kt` | `ux70_logs_should_ContainIdsAndCountsOnly` | Pure | see R-14 |
| UX-71 each error has a log line | `CT/sketch/v2/ErrorLogCoverageTest.kt` (NEW, Story 6.2.7) | `ux71_everyUserVisibleErrorState_should_EmitOneSketchLogLine` | Pure | table-driven over the error enum |
| UX-72 save failure | `AT/sketch/v2/DiagnosticsTest.kt` | `ux72_saveFailure_should_ShowRetryKeepSceneAndPromptOnLeave` | Robolectric | Retry save / Export copy / Discard (Story 1.4.4 AC; `LeaveSketchPromptTest`) |
| UX-73 large drawing | `JT/sketch/v2/LargeSceneDegradationTest.kt` | `ux73_scene_should_BannerAt4000_NotAt3999_DegradeInOrderAndRefuseAt5000WithSplit` | Compose desktop | ONE threshold: 4,000 elements / 160 constraints (`SceneLimits.softElementThreshold`); plan Story 2.2.3 and ux.md 15 now agree; hard-cap actions are Story 2.2.4 (`CapacityUxTest`, `SheetSplitterTest`) |
| UX-74 empty state | `UxA11yAcceptanceTest.kt` (Task 3.6.6c) | `ux74_emptySketch_should_ShowEmptyStateUntilFirstElement` | Robolectric | Import and shortcuts buttons |
| UX-75 no dead ends | `AT/sketch/v2/DeadEndWalkTest.kt` (NEW, Story 6.2.7, Task 6.2.7b) | `ux75_everyErrorState_should_ExposeActionOrDismissThatRestoresEditor` | Robolectric + review checklist | table-driven over the error-state enum; manual walk of ux.md sections 1-16 |

## Test Stack
- **Unit**: Kotlin `kotlin.test` `@Test`, `kotest-assertions-core` and `kotest-property` (`Arb`, `checkAll` inside `runTest`) for property tests; Arrow `Either` assertions; fixed-advance `TextMetricsProvider` fake; `TestScope` virtual time (no `Thread.sleep`, no real `delay`).
- **Integration**: `businessTest` over `InMemory*Repository` and an in-memory `ProjectSidecarStore` fake plus a real-file store for crash injection; real SQLDelight DB fixture for the migration test; `TargetWriterContractSuite` for both writers; `WiredAppFixture`/`DesktopWiredAppFixture` for wiring.
- **E2E / UX**: Robolectric Compose tests in `androidUnitTest` for behaviour (headless, no display needed); `jvmTest` only for real pixels or desktop-only code, each file with `// jvmTest-reason: <pixels|desktop-only>` and run via `scripts/jvm-display-check.sh -- bazel test //kmp:jvm_tests --sandbox_add_mount_pair=/tmp/.X11-unix --test_env=DISPLAY --test_env=XAUTHORITY`; wasm via `./gradlew :kmp:wasmJsBrowserTest`; iOS `compileKotlinIosArm64` only. Device-only items in `owner-verification.md` (OD-2).

### Registration checklist (CLAUDE.md, enforced by `R-19` tests)
- Every new `BT` class listed in `AllBusinessTests`; every `JT` class in `AllJvmTests`; every `AT` class in `AllAndroidUnitTests` (explicit lists, Bazel-guarded).
- Every new `CT` file has a `kmp/src/commonTest/kotlin/BUILD.bazel` entry; fixtures under `kmp/src/commonTest/resources/` are added to the Bazel resource filegroup; businessTest fixtures bundled via `//kmp/src/businessTest/kotlin:wiring_*_as_resources` where audited.
- `TestPlacementAuditTest`: any `jvmTest` file importing `androidx.compose.ui.test` carries a `jvmTest-reason` header; `TestHygieneAuditTest`: no new `Thread.sleep`, `GlobalScope`, uncancelled `Dispatchers.IO` scope, real-clock `delay`; baselines may only shrink.
- Each new seam implementation is constructed in a composition root and has a `wiring-catalog.txt` line; "SketchScreenV2" added to `first-open-screens.txt` and `FIRST_OPEN_SCREENS`.
- `MigrationRunnerSchemaSyncTest` green, `kmp/src/generated/sqldelight/` regenerated and committed, `RestrictedDatabaseQueries` `@DirectSqlWrite` stub added for the schema-version update.
- Property tests use kotest `Arb` over: `CanonicalJson` doubles, `V1ToV2.lift`/`project` identity (`Arb.long(-2^28..2^28)`), `HistoryV2` command sequences, `SpatialIndex` vs brute force, binding invariant, solver satisfiability, Jacobian vs finite differences, round-trip export/import, SVG escaping, hachure clipping, `SheetCodecV2` fuzz.

## Coverage Targets and How to Measure

| Stack | Coverage command | Target |
|---|---|---|
| Kotlin/JVM (commonMain engine under `V2`, `SOLV`, `XCAL`) | `./gradlew :kmp:jvmTest` with Kover/JaCoCo report if enabled; otherwise `bazel coverage //kmp:business_tests` | >= 80% line on `V2/**`, `SOLV/**`, `XCAL/**` (pure engine); >= 70% on `RT/**` |
| Android Compose UI (`UI2`) | `./gradlew :kmp:testDebugUnitTest` | Every composable has at least one behaviour test; no line target |
| wasmJs | `./gradlew :kmp:wasmJsBrowserTest` | Golden/determinism tests pass; no line target |

- All public service methods: happy path plus error paths (every `Either.Left` variant of `CommandError`, `SheetError`, `ConversionOutcome`, `SolveResult` has a test).
- All external integrations (file store, DB, asset store, platform PNG encoder, file picker seam): unit with fake plus at least one integration test.
- UX acceptance: each UX-xx criterion has a row above or an explicit manual step.

## Coverage Summary

- Requirements covered: 22/22 (SM-1..SM-8 and R-8..R-21; SM-8 is advisory and covered by the counter test plus owner read, not a CI gate; R-20 is covered by the Story 0.1.7 harness test and manual owner rows; R-21 by the `sketch.quality` test and the owner checklist rows of iteration 3), each with >= 1 happy-path, >= 1 error-path test; integration test present for every requirement that touches a store or external call (SM-1, SM-2, SM-3, SM-4, SM-6, SM-7, R-9, R-10, R-11, R-12, R-13, R-14, R-15, R-16, R-17, R-18, R-19; R-8 via `LargeSceneDegradationTest`/`ScenePainterTest`).
- Success metrics covered: 8/8 (SM-8 advisory).
- UX criteria covered: 126/126 (UX-129..133 = 5 in the iteration 14 section; UX-126..128 = 3 in the iteration 6 section; UX-119..125 = 7 in the iteration 4 section; the earlier 111: UX-01..09, 10..19, 20..23, 30..39, 40..48, 50..59, 60..63, 64..69, 70..75 = 68, plus UX-76..97 = 22 in the iteration 1 section, plus UX-98..111 = 14 in the iteration 2 section, plus UX-112..118 = 7 in the iteration 3 section below).
- Migration test: yes (`migration_should_be_reversible`, plus crash-injection and restore tests).

## Gap resolution (Phase 4 repair)

The ten plan gaps found while mapping are now story acceptance criteria or tasks (documents only; the tests are designed, not written or run):
| # | Gap | Now in |
|---|---|---|
| 1 | UX-18 delete-layer confirmation | Story 3.5.2 AC, Task 3.5.2c |
| 2 | UX-23 10,000-char paste refusal (cap now `SceneLimits.maxTextChars`) | Story 3.4.1 AC, Task 3.4.1c; Story 1.2.4 |
| 3 | UX-36 slow-solve progress with Cancel | Story 4.1.4 AC, Task 4.1.4b |
| 4 | UX-39 auto-constraint toast with Undo | Story 4.2.2 AC, Task 4.2.2b |
| 5 | UX-46 contrast/target audit, UX-47 reduce motion, UX-74 empty state | Story 3.6.6 (Tasks 3.6.6a-c) |
| 6 | UX-53 import over cap; UX-57 "Use 1x" fix; UX-58 flatten notice | Story 5.1.5 AC, Task 5.1.5b; Story 5.2.3 AC, Task 5.2.3b |
| 7 | UX-61 missing sketch; UX-63 copy success Open; UX-65 post-conversion banner; UX-69 next-open notice | Tasks 6.1.1c, 6.1.2c; Story 1.4.6 (Task 1.4.6b) |
| 8 | UX-73 threshold mismatch: ONE number, 4,000 elements / 160 constraints (80% of the cap), in `SceneLimits`, plan Story 2.2.3 and ux.md; hard-cap "Delete some / Split into a new sketch" actions | Stories 2.2.3, 2.2.4 |
| 9 | UX-72 leave-screen prompt (Retry save / Export copy / Discard) | Story 1.4.4 AC, Task 1.4.4c |
| 10 | Shared error-state enum (UX-71, UX-75) | Story 6.2.7 (`SketchErrorState` in `RT/telemetry`) |
Also resolved: the Declined behaviour of UX-64 (Story 1.4.6), the single-element Constrain flyout of UX-37 (Task 4.2.3b), and the `NEW` test files, each now in a story's Files list (ToolInventoryCompletenessTest 3.1.1, TextToolTest 3.4.1, GroupFrameTest 3.5.1, LargeSceneDegradationTest 2.2.3, PngExportTest 5.2.2, ExportDialogTest 5.2.3, SceneStructureBenchmarkGuard 0.1.6, SheetSaverReconcileIntegrationTest / AndroidCrashGuardTest / SheetSaverConflictTest / LeaveSketchPromptTest 1.4.4, UxMigrationAcceptanceTest 1.4.6, UxA11yAcceptanceTest 3.6.6, ErrorLogCoverageTest / DeadEndWalkTest 6.2.7, WiringV2EntryPointTest / TestRegistrationAuditTest 6.2.1, ToolsEndToEndTest / UxAuthoringAcceptanceTest / VerticalSliceScenarioTest 6.2.4, UxOrganisationAcceptanceTest 3.5.2, UxConstraintsAcceptanceTest 4.2.3, SketchSheetWritePathsPreserveSchemaTest 1.4.1, PointArcToolsTest 3.2.4, SheetSplitterTest and CapacityUxTest 2.2.4).

Test-design caveats (unchanged unless noted):
- Migration reversibility: plan declares the schema change forward-only (additive column). `migration_should_be_reversible` therefore verifies content reversal (restore from backup) rather than dropping the column; SQLite column drop is not claimed.
- Device-only behaviour is manual (OD-2): stylus pressure/hover/palm rejection (UX-07, R-10), TalkBack (UX-17, UX-44), real IME (UX-20), Pixel_Fold frame time (R-15 advisory), wasm browser pass, iOS device (deferred; compile-only).
- Cross-target pixel parity depends on Spike 0.1.3e: if Robolectric is unreliable, Android parity (SM-4) falls back to draw-list equality plus owner on-device check.
- Solver production tests (SM-3) are blocked on OD-3 and Spike 0.1.2; if the spike fails the first release is geometric-only (Story 0.1.2 fallback) and tests for tangent/symmetric/angle are not required until their property tests pass. Point/Arc rows (OD-8) exist only if OD-8 is accepted. Golden tests for freehand/rough (R-8) need Node plus network once (Spike 0.1.5b), until then those two use self-consistency tests only.
- Frame-time SLO is asserted only as the relative 1.5x rule on the JVM harness; the plan's absolute 16 ms and 24 ms figures are not CI checks.
- Success metric 1 "tool parity" depends on a fixed tool inventory; `ToolInventoryCompletenessTest` relies on the `Tool` registry (`V2/tools/ToolRegistry.kt`, now a Story 3.1.1 file).

## Triad-review repair iteration 1: new and changed test rows

Designed, not written or run. Same naming rule (`subject_should_Expected_When_Condition`). Registration rules of the checklist above apply to every row.

| Requirement / UX | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| SM-2 early slice (E1) | `BT/sketch/v2/HeadlessSliceScenarioTest.kt` (Task 6.2.4d, W7) | `headlessSlice_should_ReloadEqualSceneWithBoundArrow_When_SavedThroughSheetSaverAndCodecV2` | Integration | commands -> `SheetSaver` -> real-file store -> fresh decode; `reconcile` Unchanged; DB `schemaVersion = 2`; no flag/router/painter |
| SM-2 rendered slice (E1) | `JT/sketch/v2/RenderedSliceScenarioTest.kt` (`// jvmTest-reason: pixels`) | `renderedSlice_should_RenderCleanAndReopen_When_NewV2SheetCreatedThroughRouterWithFlagOn` | Compose desktop | after 2.1.3 and 1.4.3; draw-list equality plus non-blank pixels |
| SM-2 bound (metric 2) | `JT/sketch/v2/ArchitectureScenarioTest.kt` | `architectureScenario_should_CompleteInAtMost60Actions_When_15ElementScriptRun` | Integration | action counter over the script; manual timed run is an owner-checklist row (1.5x the owner's excalidraw.com time) |
| SM-3 numeric (metric 3) | `CT/sketch/constraints/SolverPropertyTest.kt` | `solve_should_ReachResidualBelow1e6SceneUnits_When_RandomisedSatisfiableSketch` | Property | Arb sketches; residual bar 1e-6; also asserts bit-identical re-run on one target |
| SM-3 scenario (Job 2) | `CT/sketch/constraints/BracketPlateScenarioTest.kt` (new) | `bracketPlate_should_BeFullyConstrainedZeroDof_AndRejectConflictingParallel` | Pure | 4 lines + circle, coincident/H/V/Fixed/Distance/Radius per requirements Job 2 |
| SM-4 tolerance (metric 4) | `JT/sketch/v2/PixelToleranceTest.kt` (`// jvmTest-reason: pixels`) | `pixelDiff_should_StayWithin0_5PercentOver16of255AndWithin2pxOfEdges_When_JvmVsWasmRender` | Compose desktop + wasm | tolerance constants in one place; text excluded per Spike 0.1.3b outcome |
| SM-6 baseline (metric 6) | `BT/sketch/v2/FlagRolloutTest.kt` (Task 6.2.5a) | `baseline_should_RunAllRecordedV1TestsAndFixturesUnmodified_AndExportBytesEqual` | Integration | script records the count of v1 sketch tests/fixtures at Phase 5 start; 100% pass; v1 export bytes equal after conversion |
| SM-7 baseline (metric 7) | `CT/sketch/excalidraw/RoundTripPropertyTest.kt` | `roundTrip_should_PassAllSixFixtures_AndPreserveSupportedSubsetExactly` | Property + golden | 5 scenes + legacy file; exact geometry |
| SM-8 adoption | `CT/sketch/v2/SketchLogTest.kt` | `adoption_should_EmitCountsOnlyAndNeverTransmit_When_AuthoredSheetCreatedOrExcalidrawImported` | Pure | `sketch.adoption authored/imported` counts; no names; no network port referenced; authored is the metric-8 numerator (iteration 7, D-48) |
| SM-1 inventory (metric 1) | `CT/sketch/v2/tools/ToolInventoryCompletenessTest.kt` | `inventory_should_EqualFrozen14ToolIds_AndEachHaveAScenario` | Pure | adding or removing a tool id fails until requirements.md changes |
| E2 diagnostics port | `CT/sketch/v2/SheetSaverTest.kt` | `saver_should_RaiseEntriesThroughDiagnosticsPort_WithNoUi2Import` | Pure | fake port records; `SketchV2EngineImportDirectionTest` covers the import rule; entry retained until host attaches |
| E3 saver split | `BT/sketch/v2/SheetSaverConflictTest.kt` | `conflict_should_WriteCopyAndKeepBothVersions_When_BaseShaMismatch` (1.4.4c); `pullEvent_should_PromptReload_When_OpenSheetReplacedMidSession` (1.4.4d) | Integration | ACs of 1.4.4 unchanged, now four tasks |
| E3 encode back-off | `CT/sketch/v2/SheetSaverTest.kt` | `encode_should_BackOffTo1sThen2s_AndResetOnFastEncode_When_SlowEncoder` (1.4.4e); `leavePrompt_should_DefaultToRetryAndKeepMemory` (1.4.4f) | Pure + Robolectric | virtual time |
| Eng gap 7 | `CT/sketch/v2/SheetSplitterTest.kt` | `split_should_LeaveProjectPresent_When_CrashAfterEachOfSteps1To4`; `undoSplit_should_RestoreElementsAndOfferDeleteNewSketch` | Integration | one model write is the commit point; no cross-sheet undo |
| Eng gap 11 | `CT/sketch/excalidraw/ParserBudgetTest.kt` and wasm counterpart | `parser_should_ReturnImportTimeoutAndYield_When_FakeClockPassesDeadline`; `parser_should_NotStarveEventLoop_When_RunOnWasmSingleThread` | Pure + wasm | cooperative yield every 256 elements / 64 KB |
| Eng E1 (iteration 4) single-call decode | `CT/sketch/excalidraw/JsonSpanScannerTest.kt` and wasm counterpart (Tasks 5.1.1e, 5.1.1f) | `scanner_should_NeverHandMoreThanMaxImportSpanBytesToTheDecoder_When_20MbFileImported`; `scanner_should_YieldEvery64KbAndDecodeBase64InSlices`; `import_should_EqualWholeFileDecode_ForWellFormedInLimitFixtures_AndFollowTheEightClassesOfStory5_1_1`; `importer_should_DropOversizedElementWithImportSpanTooLarge_AndRefuseOversizedTopLevelSpan`; `wasm_should_FireTimerCallbackBeforeParseCompletes_And_RecordMaxYieldGapAndSpanDecodeMs` | Pure + wasm | structural proof by decode-call-size and bytes-between-yields counters; the wasm wall-clock gap is recorded (advisory), the timer-callback check is binary; `maxImportSpanBytes` lowered if the recorded worst span decode exceeds 100 ms |
| Eng gap 13 | `CT/sketch/excalidraw/ImageBombTest.kt` | `imageBomb_should_BecomePlaceholderWithoutDecode_When_HeaderDeclaresOversize`; `perSheetDecodedBudget_should_StopAdmittingImages` | Pure | decoder call counter 0; reason codes `ImageTooLarge`, `ImageMimeMismatch` |
| Eng gap 12 | `BT/projectbridge/SidecarRetentionPolicyTest.kt` (Task 0.1.1c) | `retention_should_KeepAllBackupsAndNewest10Conflicts_AndIgnoreOnlyConflicts` | Integration | `git check-ignore` classification; `bundleHash` unchanged |
| UX-76 flag enable | `AT/sketch/v2/UxMigrationAcceptanceTest.kt` | `ux76_flagEnable_should_ConfirmWholeProjectWarningStorePerDeviceAndDisableWithReasonWhenNotReady` | Robolectric | Cancel stores nothing |
| UX-77 classic fallback | `UxMigrationAcceptanceTest.kt` | `ux77_newSheetAsClassic_should_ShowTheClassicEditorToast_WithTheReasonInItsDetails_AndNoOtherNotice` (iteration 11) | Robolectric | flag off, not ready, HasTechnicalData, New sketch projects only in an existing project, the user's own choice |
| UX-78 restore confirm | `BT/sketch/v2/RestoreFromBackupTest.kt` | `ux78_restore_should_ConfirmDiscardOfV2EditsAndChangeNothingOnCancel` | Integration | Cancel leaves bytes unchanged |
| UX-79 newer schema actions | `UxMigrationAcceptanceTest.kt` | `ux79_newerSchemaScreen_should_CheckForUpdatesAndExportBackupWithoutWriting` | Robolectric | fake `UpdateCheckPort`; project bytes unchanged |
| UX-80 finger drawing | `AT/sketch/v2/FingerDrawingDefaultsTest.kt` (Task 3.1.2d) | `ux80_fingerDrawing_should_DefaultOnUntilStylusSeenThenOff_NeverOverrideChoice_AndHintOnce` | Robolectric | finger-only phone usable; two-finger pan; palm ignored |
| UX-81 eligibility | `CT/sketch/v2/ConstraintEligibilityTest.kt` | `ux81_eligibility_should_AcceptOnlyTechnicalGeometry_AndReturnReasonForOthers`; `ruleTable_should_BeTheOnlyImplementation` | Pure | grep test; `NotConstrainable` command error |
| UX-82 auto-constrain toggle | `AT/sketch/v2/AutoConstrainToggleTest.kt` | `ux82_autoConstrain_should_DefaultOffOnDiagramOnOnTechnicalAndConverted_AndSuppressByChipOnTouch` | Robolectric | |
| UX-83 DOF wording | `ConstraintEligibilityTest.kt` / `UxConstraintsAcceptanceTest.kt` | `ux83_dof_should_AlwaysCarryPlainLanguageGloss` | Robolectric | |
| UX-84 tab discoverable | `UxConstraintsAcceptanceTest.kt` | `ux84_constraintsTab_should_BePresentWithEmptyStateWhenNothingSelected` | Robolectric | |
| UX-85 shortcuts | `CT/sketch/v2/KeymapSpecTest.kt`; `JT/sketch/v2/KeyboardShortcutsTest.kt` | `ux85_singleKeys_should_FireOnlyWithCanvasFocusAndSettingOn`; `keymap_should_MatchProducedCharacterOnAzertyAndUseCmdOnMac` | Pure + Compose desktop | defaults are in UX-108 |
| UX-86 switch layer / unlock all | `AT/sketch/v2/LayerPanelTest.kt` | `ux86_switchLayerAndUnlockAll_should_ActAnnounceAndUndoAsOneStep` | Robolectric | Task 3.5.2d |
| UX-87..89 a11y audit | `AT/sketch/v2/UxA11yAcceptanceTest.kt` | `ux87_focus_should_NeverBeFullyObscured`; `ux88_fontScale200_should_NotClipOrHideActions`; `ux89_darkAndHighContrast_should_MeetContrastBars` | Robolectric | audit only; findings go to owning stories |
| UX-90 touch help | `AT/sketch/v2/TouchHelpTest.kt` | `ux90_longPressAndFocus_should_ShowSameTextAsHoverAndExposeInSemantics` | Robolectric | |
| UX-91 notice queue | `AT/sketch/v2/NoticeQueueTest.kt` | `ux91_notices_should_OrderByPriorityCoalesceAndThrottlePoliteAnnouncements` | Robolectric (virtual time) | |
| UX-92/93 handles, fold | `AT/sketch/v2/AdaptiveStateSurvivalTest.kt` | `ux92_handles_should_Have44dpHitArea_AndDeleteSeparatedFromRedo`; `ux93_foldUnfold_should_KeepSelectionToolPanelAndTextEdit` | Robolectric | |
| UX-94 loading | `AT/sketch/v2/LoadingStatesTest.kt` | `ux94_longOperations_should_ShowLabelledProgressAnnouncedAtStartAndEnd` | Robolectric | |
| UX-95 strings | `BT/sketch/v2/HardcodedStringAuditTest.kt` | `ux95_ui2_should_HaveNoLiteralUserVisibleStrings` | Integration (source audit) | |
| UX-96 usability | manual (`implementation/usability-results.md`, Story 6.2.8) | `usabilityPass_should_ShowAtLeastCeil80PercentCompleteTasks1_2_3_4_5_And_Ceil60PercentTask6` | Manual (owner wall-clock) | think-aloud; task 5 = pan while Finger drawing is ON and describe the pen-detected flip; task 6 = give a line a length of 6 in, without then with the smart-dimension chip; decision rule in requirements "A4 and A5 decision rules" |
| UX-97 discoverability | `AT/sketch/v2/DiscoverabilityTest.kt` | `ux97_tools_should_BeFlatOnlyWhenFlatExtentFits_SplitButtonsOtherwise_FlyoutKeyboardReachable_AndCoachMarkOnce` | Robolectric | flat only when `flatExtent` fits the strip extent (the windows of the chrome table decide which are flat and which grouped), at every width class |
| UX-56 Undo import (5.1.5c) | `AT/sketch/v2/ImportFlowTest.kt` | `ux56_undoImportButton_should_RemoveImportedElementsAndHideAfterLaterEdit` | Robolectric | |
| UX-74 changed | `UxA11yAcceptanceTest.kt` | `ux74_emptyState_should_OfferImportAndKeyboardShortcutsNotTour` | Robolectric | tour dropped |
| UX-07 changed | `PointerAdapterTest.kt` | `ux07_touchDrag_should_PanWhenFingerDrawingOff_AndDrawWhenOn` | Robolectric | replaces the old toggle wording |
| Release notes (6.2.5c) | manual checklist in `owner-verification.md` | `releaseNotes_should_StateWholeProjectOldVersionConsequence_BeforeEachReleaseThatReachesConversion` | Manual | checked before the release-please PR merges |

## Triad-review repair iteration 2: new and changed test rows

Designed, not written or run. Same naming rule and registration rules as above. "Manual" rows are owner-run and recorded in the named file.

| Requirement / UX | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| Eng (4) canonical limits | `CT/sketch/v2/SceneLimitsTest.kt` | `sceneLimits_should_HaveExactlyTheCanonicalFieldsAndDefaults_And_NoImageMegapixelsFieldRemains` | Unit | one 32-field list (adds `maxImportSpanBytes` and `maxLibraryBytes`, E1); importer (5.1.1), image tool (3.4.3), text, library, PNG, history, semantics, diagnostics, notices, go-to, conflict copies, zoom all read it |
| Eng (4) no other cap | `CT/sketch/v2/SceneLimitsTest.kt` | `limits_should_BeTheOnlyNumericCaps_When_SourcesGrepped` | Unit | grep scope incl. `CM/projectbridge`; `scene-limits-allowlist.txt` shrinks only |
| Eng (3) SceneLimits first | `CT/sketch/v2/SceneModelTest.kt` | `scene_should_ReadCapacityFromSceneLimits_When_Added5001stElement` | Unit | 1.2.1 capacity reads `SceneLimits.maxElements`; 1.2.2 and 1.2.3 build against the same constants (no local caps) |
| Eng (5) conflict copy | `BT/projectbridge/SidecarBackupAndStagingTest.kt` | `writeSheetConflictCopy_should_WriteUnderConflictsAndLeaveProjectModelConflictFileAlone_When_BothKindsPresent` | Integration | new method writes `conflicts/<uuid>.v2.conflict-<hash8>.json`; existing `writeConflict` still writes `project-model.conflict-<hash8>.json` at the slug root and is not pruned or hashed by `RetentionPolicy` |
| Eng (5) existing writeConflict unchanged | `BT/projectbridge/ProjectSidecarStoreTest.kt` (existing; `writeConflict` is already exercised there) | `writeConflict_should_KeepLocalModelBesideSidecar_As_Before` | Integration | regression: reconciler behaviour unchanged by Task 0.1.1b |
| Eng (1)-(2) plan structure | document check `check-plan.py` (embedded below, not a repo test) | counts, edge and wave check | Doc check | epics, stories, tasks, XS/S/M, glossary, UX counts equal the stated numbers; every edge of the machine-readable block points to an earlier wave and the declared waves equal the computed ones (W1..W14) |
| Eng (1) 3.6.8 split | `AT/sketch/v2/LoadingStatesTest.kt` | `ux94_longOperations_should_ShowLabelledProgress_When_ExportLibraryImportEmbedExceed200ms` | Robolectric | runs only after 5.1.5, 5.2.3, 3.5.6, 6.1.1 exist (Task 3.6.8c, W12); Tasks a, b, d have no such dependency |
| Product (6) A1 gate | manual (`implementation/a1-gate.md`, Task 6.2.4e) | `a1Gate_should_RecordProceedAdjustOrPivot_With_DiagramFinishedTimeBlockerAndWouldContinue` | Manual (owner) | gated stories (4.2.2, 4.2.3, 4.2.5, 4.2.6b, 4.2.7b, 4.2.7d, 5.1.5, 5.2.3, Task 6.2.5c) do not start until PROCEED is recorded |
| Product (7) baselines | manual (`implementation/baselines.md`, Task 0.1.7a) | `baseline_should_RecordOwnerExcalidrawComMedianPerDevice_Before_Metric2bIsJudged` | Manual (owner) | 3 runs per device, first attempt, median |
| Product (7) perf reference | `JT/sketch/v2/baseline/CanvasReferenceBenchmark.kt` (`// jvmTest-reason: pixels`) | `reference_should_RecordEmptyAndRect500P95_When_JvmHarnessPans120Frames` | Integration | writes `reference.empty_canvas_ms` and `reference.rect500_ms`; the 1.5x rule of `SketchEngineBenchmark` reads them |
| Product (7) Pixel_Fold measured | manual (`baselines.md`, Task 0.1.7b) | `pixelFold_should_RecordMeasuredReferenceP95_And_Confirm24msOrRestateIt` | Manual (owner, OD-2) | measured, not assumed |
| Product (7) 50% bar | manual (`baselines.md`, Task 0.1.7c) | `adoptionBar_should_BeRestatedAdvisoryNextToOwnersFiveDiagramList` | Manual (owner) | bar marked INFERRED and advisory |
| Product (6) A6 | manual (`implementation/a6-job2-demand.md`, Task 0.1.2d) | `job2Demand_should_RecordProceedAdjustOrDefer_After_SolverSpike` | Manual (owner) | defer parks Epic 4.2 UI, never deletes |
| Product (8) Sketchy per target | `CT/sketch/v2/SketchyAvailabilityTest.kt` | `sketchyAvailability_should_ResolveToCleanAndKeepStoredRoughnessAndSeed_When_TargetFlaggedUnavailable` | Unit | wasmJs/iOS pivot; notice text; ADR-004 |
| Product (8) binding pivot | `CT/sketch/v2/BindingInvariantPropertyTest.kt` | `bindingInvariant_should_HoldInReducedScope_When_RotateAndGroupTransformUnbindWithNotice` | Property | reduced-scope mode: bind-on-drop, follow-on-move, unbind-on-delete only |
| Product (8) perf pivot | manual (`pivots.md`, Task 6.2.9c) + `JT/sketch/v2/LargeSceneDegradationTest.kt` | `perTargetSoftThreshold_should_ApplyLowerLimit_When_TargetFailsReferenceRule` | Compose desktop + manual | lowers `softElementThreshold` for that target only |
| Product (8) fidelity pivot | `CT/sketch/excalidraw/FixtureRoundTripTest.kt` | `supportedSubset_should_ExcludeKindsOfAnyFailingFixture_Before_Release` | Integration | fewer than 4 of 6 passing marks import "beta" |
| Product (9) priority | doc check | `requirements_should_StateJob1First_IceRationale_And_RoadmapGap` | Doc check | requirements.md "Priority and roadmap fit" present |
| UX-98 target parts | `CT/sketch/v2/PartHitTesterTest.kt` | `hitPart_should_PreferEndpointOverEdge_And_EdgeOverWhole_When_AllWithinTolerance`; `hitPart_should_EqualBruteForce_When_RandomSketch` | Unit + Property | priority, 6 px / 22 dp tolerance over zoom, topmost tie-break, "also: Line 4 start" |
| UX-98 picker | `AT/sketch/v2/ConstraintTargetPickerTest.kt` | `ux98_picker_should_MarkByShapeNameInText_And_WorkByTapKeyboardCycleAndList` | Robolectric | square/circle/bar markers, label chip, Tab cycle order, "Pick target" list, same part twice rejected |
| UX-98 two picks | `CT/sketch/v2/ConstraintTargetTest.kt` | `coincident_should_BeCreatedFromTwoPartPicks_InOneUndoStep` | Unit | "Added Coincident: Line 1 end, Line 2 start" |
| UX-99 edit dimension | `CT/sketch/v2/SetDimensionValueTest.kt` | `setDimensionValue_should_CommitWithSolverGeometryInOneUndo_And_KeepOldOnUnsolvable`; `duplicateDimension_should_RedirectToExisting_And_ConflictOfferReplaceExisting` | Unit | reference dimension read-only |
| UX-99 UI | `AT/sketch/v2/DimensionEditTest.kt` | `ux99_labelTapOrEnterOrF2_should_OpenInPlaceEditor_AndInvalidKeepsOldValue` | Robolectric | |
| UX-100 inventory | `CT/sketch/v2/KeymapSpecTest.kt` | `keymap_should_BindAll14ToolsUniquely_And_ShortcutsSheetListAll14` | Pure | digits 1-9 for the first nine, letters after, Eraser also 0 |
| UX-100 hints | `AT/sketch/v2/ToolStripTest.kt` | `ux100_toolStrip_should_ListAll14ToolsWithGroupShortcutAndHintLine` | Robolectric | |
| UX-101 eraser | `CT/sketch/v2/tools/EraserFrameToolTest.kt` | `eraser_should_PreviewThenDeleteInOneTransaction_And_CancelOnEscOrSecondPointer_And_SkipLockedWithCount` | Unit | |
| UX-101 touch eraser | `AT/sketch/v2/EraserTouchTest.kt` | `ux101_tapWithEraser_should_EraseOneElement_When_FingerDrawingOff`; `eraser_should_EraseElementUnderCrosshair_When_Apply` | Robolectric | |
| UX-101 stylus | `AT/sketch/v2/PointerAdapterTest.kt` | `stylusEraserEnd_should_ActivateEraserForOneStroke_And_BarrelButtonBeSecondaryClick_And_BeAbsentWhereUnavailable` | Robolectric | synthesised `MotionEvent` tool types; Spike 0.1.4 availability |
| UX-102 flip | `AT/sketch/v2/FingerDrawingFlipTest.kt` | `ux102_flip_should_ShowOneNoticeAtFirstPenUp_When_DefaultOn_And_NoneWhenExplicitChoice` | Robolectric | polite announcement, once per device |
| UX-102 pan | `AT/sketch/v2/FingerDrawingFlipTest.kt` | `ux102_fingerDrawingOn_should_PanByEdgeStripTwoFingersHandToolAndSelectTool` | Robolectric | drag from 10 dp pans, from 40 dp draws |
| UX-103 consent copy | `AT/sketch/v2/ConsentCopyTest.kt` | `ux103_consentSurfaces_should_ContainSameWholeProjectSentence_And_NoOtherLiteralExists` | Robolectric + grep | flag-enable, first-write dialog, banner Details |
| UX-104 upgrade offer | `AT/sketch/v2/UpgradeOfferTest.kt` | `ux104_open_should_NeverConvertOrWrite_When_FlagOnAndNotConverted`; `chipUpgrade_should_ConvertWithoutModal_When_ProjectAcknowledged`; `keepClassic_should_SetDeclinedAndHideChip` | Robolectric | file bytes unchanged on open |
| UX-105 key pan | `JT/sketch/v2/KeyboardShortcutsTest.kt` | `ux105_arrowKeys_should_PanView_When_NothingSelected_And_CtrlArrowAlwaysPans` | Compose desktop | 40 dp, Shift 200 dp, PageUp/PageDown, Home |
| UX-106 glyph declutter | `AT/sketch/v2/ConstraintPanelTest.kt` | `ux106_glyphs_should_CollapseToCountBadge_When_AnchorsWithin24dp`; `noTwoVisibleGlyphTargetsOverlap_When_200ConstraintsRandomLayout` | Robolectric + Property | selected element fans out with 4 dp gaps |
| UX-107 keyboard drawing | `AT/sketch/v2/KeyboardDrawingFlowTest.kt` | `ux107_keyboardOnly_should_DrawRectangleThroughCrosshair_With_FixedTabOrder_AndNoTrap` | Robolectric | R, Enter, arrows, Enter, Enter; Line, Arrow, Point, Arc variants |
| UX-108 screen readers | `CT/sketch/v2/KeymapSpecTest.kt`; `AT/sketch/v2/SceneSemanticsTest.kt` | `ux108_singleKey_should_DefaultOffOnWebAndUnderTouchExploration`; `canvas_should_ExposeApplicationRole` | Pure + Robolectric | collision note present on the shortcuts sheet; reader behaviour is a manual owner row (U11) |
| UX-109 flyout | `AT/sketch/v2/DiscoverabilityTest.kt` | `ux109_flyout_should_OpenByCornerMarkLongPressRightClickBarrelAndDownArrow_And_NotByAltClick` | Robolectric | |
| UX-110 names | `CT/sketch/v2/ElementNameTest.kt`; `AT/sketch/v2/ElementsPanelTest.kt` | `ux110_names_should_NeverBeReused_When_ElementDeleted`; `elementsTab_should_ListLayersAndElementsByName_RenameInPlace_AndRejectDuplicate` | Unit + Robolectric | tag chips on selection and in messages; "Show names" default per sheet kind |
| UX-111 constraint keys | `CT/sketch/v2/KeymapSpecTest.kt` | `ux111_constraintMenuKey_should_OpenMenu_And_MnemonicsWorkOnlyWhileOpen`; `deleteAndEnterOnFocusedConstraint_should_RemoveAndEdit` | Pure + Robolectric | |
| Bracket plate UX | `AT/sketch/v2/BracketPlateUxScenarioTest.kt` (Task 6.2.4f) | `bracketPlate_should_ReachZeroDofWithin42Actions_ByTapAndKeyboardOnly_And_RejectConflictingParallel_And_EditWidthInPlace` | Robolectric | ux.md section 9 walkthrough; pure counterpart `BracketPlateScenarioTest` |
| UX literal citations | doc check | `storyAcs_should_CiteLiteralUxIds_NotRanges` | Doc check | no `UX-nn..UX-mm` range in plan story ACs or Files lines (the header summary and ux.md headings excepted) |
| UX-97 changed, UX-96 changed, UX-64 changed, UX-47 changed, UX-85 changed, UX-40 changed | see the rows above in the iteration 1 table | | | replaced in place |

## Triad-review repair iteration 3: new and changed test rows

Designed, not written or run. Same naming rule and registration rules as above. "Manual" rows are owner-run and recorded in the named file. Nothing here claims that any review has passed.

| Requirement / UX | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| Eng E1-E3 waves | `check-plan.py` (below) | `edges_should_PointToEarlierWavesAndMatchDeclaredWaves_When_ScriptRun` | Doc check | 4.2.6a is W7 and no longer needs 3.6.3a (4.2.6b needs it, W12); 3.4.2 is W6 after 2.1.4 (W5); the parser 5.1.1 is W3 and the mapper 5.1.2 W7 |
| Eng E4 store contract | `BT/projectbridge/SidecarStoreOpaqueContractTest.kt` (new, Task 1.1.3b) | `store_should_ReturnStaleOpaqueAndWriteNothing_When_OpaqueShaMismatchesInWriteWriteNowOrFlushPending` | Integration | lock deliberately bypassed; parked entry dropped at flush |
| Eng E4 reconciler | `BT/projectbridge/ReconcilerV2GoldenTest.kt` (Task 1.1.3c) | `reconcile_should_ReplanAndWriteNothingForV2_When_StaleOpaqueReturned` | Integration | the former combined task is now b (store), c (reconciler), d (`replaceProjectContents`), e (goldens) |
| Eng E5 file lists | `check-plan.py` (below) | `mTasks_should_EachListOneToFiveDistinctFiles_When_ScriptRun` | Doc check | all 81 M tasks plus the new S tasks; more than 5 files fails |
| Eng reference integrity | `check-plan.py` (below) | `taskAndStoryReferences_should_ResolveToHeadings_When_ScriptRun` | Doc check | catches stale ids after the 1.1.3 renumbering |
| UX literal citations | `check-plan.py` (below) | `everyUxId_should_AppearInPlan_When_ScriptRun` | Doc check | every id of ux.md section 20 (126, counted by the script) appears in plan.md |
| UX-103/104 per project (D-16) | `AT/sketch/v2/UpgradeOfferTest.kt` | `ux104_chip_should_CarryConsequenceLineUntilProjectAcknowledged`; `ux104_secondProject_should_ShowDialogAgain_When_FirstProjectAcknowledged` | Robolectric | whole-project sentence on the chip until the project is acknowledged, then in the dialog and every post-conversion banner |
| UX-103 copy | `AT/sketch/v2/ConsentCopyTest.kt` | `ux103_firstWriteDialog_should_NameProject_And_ChipSentenceComeFromSameConstants` | Robolectric + grep | no consent literal outside `SketchConsentCopy` |
| UX-64/D-16 converter | `BT/sketch/v2/SheetConversionGoldenTest.kt` | `converter_should_RequireConfirmationPerProject_When_OtherProjectAlreadyAcknowledged` | Integration | key is (device, slug) |
| UX-102 strip placement (D-18) | `AT/sketch/v2/EdgePanStripTest.kt` (new, Task 3.1.2g) | `ux102_strip_should_StartAtInset_RegisterExclusionRectForProtectedSpanOnly_And_HideWhenCancelledTwice`; `edgePanSide_should_DefaultOffUnlessSupported_And_ShowEdgePanStoppedNoticeOnce` | Robolectric | fake exclusion port; changed in iteration 4 (see the UX-121 row); two cancels hide the strip and persist `ExclusionFailed` |
| UX-102 device | manual (`research/input-spike.md` Task 0.1.4d + `owner-verification.md`) | `edgeGestures_should_BeRecordedPerTarget_ExclusionCapAndIosDeferral` | Manual (OD-2) | Pixel_Fold AVD and a touch laptop; iOS compile-only, device UNVERIFIED |
| UX-80/102 state chip | `AT/sketch/v2/TopBarTest.kt` (Task 3.1.4d) | `ux80_topBarChip_should_ShowFingerDrawingStateAsText_AtEveryWidth_NeverInTheScrollingStrip_NorOnlyInOverflow` | Robolectric | text plus icon, switch semantics; the chip is a top-bar node (D-43) |
| UX-102 palm | `AT/sketch/v2/PointerAdapterTest.kt` | `palmTouch_should_BeIgnored_When_PlatformClassifiesPalm_And_AllTouchesWhileStylusStrokeActive` | Robolectric | no app-defined size threshold |
| UX-116 sequencer | `AT/sketch/v2/OnboardingSequencerTest.kt` (new, Task 3.6.7c) | `ux116_onboarding_should_ShowOneItemAtATime_InPriorityOrder_PerItsRecurrenceRow`; `sequencer_should_YieldToBlockingAndWarningNotices` | Robolectric (virtual time) | the order is the banner-table rank (the test reads the table, it does not restate the order); recurrence per the UX-122 row |
| UX-118 compact entries | `AT/sketch/v2/ToolStripTest.kt` | `ux118_compactDock_should_OfferConstrainWhenSelected_And_GeometryGroupOrOverflow_ByTouchAlone` | Robolectric | 360 dp |
| UX-98 Cancel chip | `AT/sketch/v2/ConstraintTargetPickerTest.kt` | `ux98_cancelChip_should_DisarmWithoutCommitting_And_EmptyCanvasTapMustNotCancel` | Robolectric | touch has no Esc |
| UX-117 skip canvas | `JT/sketch/v2/KeyboardShortcutsTest.kt`; `CT/sketch/v2/KeymapSpecTest.kt` | `ux117_f6_should_CycleRegionsWithoutVisitingElements_And_SkipLinkBeFirstCanvasTabStop_And_SheetListIt` | Compose desktop + Pure | Esc returns to the canvas container |
| Keymap gate (U8, U11, U12) | `CT/sketch/v2/KeymapSpecTest.kt` | `keymap_should_FreezeOnlyAfterU8U11U12RecordedOrDefaulted_And_CarryFootnoteWhenDefaulted` | Pure | the footnote text is asserted |
| UX-45 two-tone ring | `AT/sketch/v2/UxA11yAcceptanceTest.kt` | `ux45_focusRing_should_ContrastWithElementAndBackground_In_LightDarkAndHighContrast` | Robolectric | renders each theme and samples both sides |
| UX-46/92 one floor (D-17) | `AT/sketch/v2/UxA11yAcceptanceTest.kt` | `ux46_targets_should_MeetFloorOfActivePointerClass_44dpWithTouchOrPen_24dpMouseOnly` | Robolectric | names the node below the floor; glyph collapse uses the same size |
| UX-112 notice timing (D-20) | `AT/sketch/v2/NoticeQueueTest.kt` | `ux112_actionNotices_should_PersistOrStayTenSeconds_With_HoverAndFocusPause`; `infoNotice_should_StayFiveSeconds` | Robolectric (virtual time) | restart from full 10 s on leaving |
| UX-113 web equivalents (D-28) | `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/sketch/v2/WebReflowTest.kt` (new, Task 3.6.6d) | `ux113_web_should_Reflow320pxAnd400PercentZoom_SurviveTextSpacing_And_MapPrefersReducedMotion` | wasm browser test (`:kmp:wasmJsBrowserTest`) | no page-level horizontal scroll; `MotionPolicy` reads the media query |
| UX-114 success feedback (D-29) | `AT/sketch/v2/SuccessFeedbackTest.kt` (new, Story 5.2.3 Files) | `ux114_success_should_NameFileAndLocationOnExport_KeptCopyOnRestore_And_CountsForLibraryAddAndInsert` | Robolectric | polite, through the notice queue |
| UX-115 smart dimension (D-21) | `CT/sketch/v2/DimensionSuggestionTest.kt` (new, Task 4.2.7c) | `suggestion_should_OfferLengthForLine_RadiusForCircle_CentreToEdgeForCircleAndLine_AndNothingForTextOrArrow` | Unit | current value prefilled |
| UX-115 path bound | `AT/sketch/v2/BracketPlateUxScenarioTest.kt` | `bracketPlate_should_ReachZeroDofWithin34Actions_WithSmartDimensionChips_And42ThroughConstrainMenu_UnderOneCountingConvention` | Robolectric | ux.md section 9 recount |
| Metric 5 numeric bar (D-23) | `AT/sketch/v2/UxOrganisationAcceptanceTest.kt` | `floorPlanLayers_should_CompleteSevenOperationsEachInAtMost3ActionsAndOneUndo` | Robolectric | scenario "floor plan layers" |
| Metric 1 baseline (D-22) | `CT/sketch/v2/FeatureBaselineCompletenessTest.kt` (new, Task 3.5.4c) | `featureBaseline_should_HaveExactly41Ids_EachResolvingToACommandOrControl_AndEachTested` | Integration | 15 + 13 + 8 + 5 |
| Metric 7 real exports (D-24) | `CT/sketch/excalidraw/FixtureRoundTripTest.kt` | `realExports_should_RoundTripAtLeastFive_Or_PrintHandAuthoredOnlyUnmeasuredLabel` | Integration | owner-supplied under `excalidraw/real/` |
| R-21 quality signals (D-26) | `CT/sketch/v2/SketchLogTest.kt` | `quality_should_EmitCountsOnlyOncePerSession_NeverTransmit_And_ShowSameFiguresInSettings` | Pure | 200 solves, 3 `NoConvergence`, 1 conflict, 1 crash record |
| R-21 flag exit | manual (`owner-verification.md`, Task 6.2.5d) | `flagExit_should_BeChecked_Against_DataLossCrashAndNoConvergenceThresholds_BeforeDefaultFlip` | Manual (owner) | thresholds copied from requirements |
| A4 go/no-go | manual (ADR-001/ADR-005 note, batched session) | `a4_should_RecordGoOrNoGo_With_DeviceListAndFallbackChoice` | Manual (owner) | NO-GO keeps conversion unreachable |
| 0.1.7c pass threshold (D-27) | manual (`baselines.md`, Task 0.1.7c) | `preCheck_should_RecordPassMarginalOrFail_AndHoldUiSpendOnFail` | Manual (owner) | PASS at least 3 of 5 |
| A5 decision rule | manual (`usability-results.md`, Story 6.2.8) | `a5_should_ApplyDecisionRule_When_Task2Or5Or6MissTheBar` | Manual (owner) | promotion of pan alternatives before any default flip |
| Sunk-cost figures | `check-plan.py --effort` (below) | `requirements_should_QuoteSunkCostFiguresFromScript_And_LeaveNoPlaceholders` | Doc check | no double-at placeholder token remains in any plan document |

## Triad-review repair iteration 4: new and changed test rows

Documents only; the tests are designed, not written or run. This iteration is NOT re-reviewed and no review pass is claimed.

| Item | File | Test | Type | Notes |
|---|---|---|---|---|
| P1 solver time bars (D-30) | `CT/sketch/constraints/SolverSpikeTest.kt` (JVM and `wasmJsBrowserTest`) | `spikeSolver_should_Solve100ConstraintsUnder50msOnJvm_And150msOnWasm_AndRecordWasm50msAdvisory` | Unit + wasm | one figure set in requirements NFR, A2, metric 3, Spike 0.1.2, ADR-003 and the plan; 50 ms on wasmJs recorded in `sketch-engine-budgets.json` as advisory |
| P2 regression baseline (D-37) | `scripts/sketch-baseline/record-sketch-baseline.sh` (Task 0.1.7d) | `baseline_should_ListSketchTestsAndFixturesWithShaAndCounts_AndReproduceByteForByte_AtPreChangeCommit` | Script check | writes and commits `implementation/regression-baseline.txt` in W1 before any `V2` code exists |
| P2 comparison (metric 6) | `BT/sketch/v2/FlagRolloutTest.kt` (Task 6.2.5a) | `baseline_should_FailWhenAnyRecordedTestOrFixtureIsMissingEditedOrOutnumbered_And_PassWhenAllUnmodified` | Integration | re-runs the script and diffs against the W1 file; replaces the Phase-5 snapshot |
| E1 single-call decode | see the "Eng E1 (iteration 4) single-call decode" row in the Story 5.1.1 block above (`JsonSpanScannerTest`, Task 5.1.1e) | `scanner_should_NeverHandMoreThanMaxImportSpanBytesToTheDecoder_When_20MbFileImported` | Pure + wasm | 32-field `SceneLimits` (30 when this row was written; `maxLibraryBytes` was added in iteration 6); differential test equals whole-file decode |
| UX-119 chrome budget (D-34) | `CT/sketch/v2/ChromeBudgetPolicyTest.kt`; `AT/sketch/v2/ChromeBudgetTest.kt` (Tasks 3.1.4a, 3.1.4b) | `chrome_should_KeepMinimumCanvas_PinnedTrioAndAll14ToolsReachable_AtEveryWindowOfTheChromeTable_AtFontScales1And2`; `landscapePhone800x360_should_UseRailAndSideSheet`; `collapse_should_FollowHintStatusRowBannerSlotSelectionBarOrder_AndReturnInReverse`; `blockingNotice_should_NeverCollapseIntoBadge` | Pure + Robolectric | every guaranteed window of the table at both font scales (the table is parsed, no figure is typed into the test); semantics audit finds no clipped node without a scroll path |
| UX-120 pan pad and sheet handle (D-33) | `AT/sketch/v2/NonDragTransformTest.kt` (Task 3.6.3d) | `panPad_should_MoveQuarterCanvasPerTap_RepeatOnHoldAfter400msThen150ms_And_NeedNoDrag`; `panArrows_should_BeShownByDefault_OnlyWhenFingerDrawingOnAndTouchOrPenPresent_NotOnMouseDesktop_WhateverTheStripState`; `bottomSheet_should_OpenResizeAndClose_WithTapsAndKeysOnly` | Robolectric | taps-only completion of pan and sheet resize; UX-40 list extended |
| UX-121 strip geometry and safe default (D-32) | `AT/sketch/v2/EdgePanStripTest.kt` (Task 3.1.2g) | `strip_should_StartAtInset_Be44dpHit_24dpVisible_And_CoverOnlyProtectedSpan`; `edgePanPolicy_should_DefaultOff_When_TargetNotMarkedSupported_Or_ApiBelow29`; `stripAbsentFromSemantics_And_OtherPanPathsPass_When_Off`; `exclusionFailed_should_PersistSoNextSessionStartsOff`; `visibleBand_should_Meet3to1Contrast_InLightDarkAndHighContrast` | Robolectric | replaces the earlier \"outer 24 dp\" rows; fake exclusion port and fake spike result |
| UX-102 device (changed) | manual (`research/input-spike.md` Task 0.1.4d) | `edgeGestures_should_RecordSupportedOrNot_PerTarget_InAndOutsideTheProtectedSpan_AndGrantedSpanHeight` | Manual (OD-2) | at least 20 drags per place; Supported only if all reach the app |
| UX-122 recurrence table (D-19) | `AT/sketch/v2/OnboardingPolicyTest.kt` (Task 3.6.7c) | `policy_should_EqualTheSection16Table_RowByRow`; `fingerHintOff_should_ShowTwice_ThenRetire_AndNeverAfterAction`; `coachMark_should_ShowOnce`; `upgradeChip_should_ReturnOnEveryOpenAfterNotNow_AndNeverAfterUpgradeOrKeepClassic`; `emptyState_should_ReturnWhenSheetEmptiedAgain`; `newOnboardingItemWithoutPolicyRow_should_Fail` | Robolectric (virtual time) | replaces the \"OncePerDevice\" wording of the UX-116 row |
| UX-123 comprehension (D-36) | manual (`usability-results.md`, Story 6.2.8 task 2) | `consentComprehension_should_PassCeil80PercentCorrectAnswers_WholeProjectNotJustThisSketch` | Manual (owner wall-clock) | written question; miss is a `SketchConsentCopy` defect and one re-run |
| UX-64 / D-35 one checkbox | `AT/sketch/v2/UxMigrationAcceptanceTest.kt` (Task 1.4.6a) | `dialog_should_HaveExactlyOneCheckbox_UncheckedOnEveryOpen_AndOpenClassicEscBackWriteAndAcknowledgeNothing` | Robolectric | row UX-64 above is changed in place |
| UX-124 touch hints | `AT/sketch/v2/DiscoverabilityTest.kt`; `AT/sketch/v2/TextEditorTest.kt` | `hintTextAudit_should_FailOnTouchHintNamingAKey`; `textEditor_should_ShowDoneAndCommitOnTapOutside_OnTouchWithoutKeyboard` | Robolectric | switches back to keyboard hints when a hardware keyboard is attached |
| UX-125 selection visible | `AT/sketch/v2/StylePanelTest.kt` | `stylePanel_should_PanNotZoomSoSelectionStaysVisibleAboveSheet_And_RestoreViewOnClose` | Robolectric | pan is not an undo entry |
| UX-81 single copy source | `CT/sketch/v2/ConstraintEligibilityTest.kt` | `notConstrainable_should_BeOneStringNamingFrames_And_NoSecondWordingExists` | Pure + grep | flyout, touch help, semantics and command error read `SketchReasonText.NotConstrainable` |
| UX-31 over-constrained provenance | `CT/sketch/v2/ConstraintCommandsTest.kt`; `BT/sketch/v2/ConstraintImportTest.kt` | `interactiveEdit_should_RejectConflict_NeverReachOverConstrained`; `loadedSheetWithConflicts_should_ShowOverConstrainedAndStayEditable` | Pure + Integration | states reachable only through import, external edit or sync merge |
| Read-only banner restore | `AT/sketch/v2/EditorRoutingTest.kt` | `readOnlyBanner_should_OfferUseNewRestoreOriginalAndExport_And_CollapseToMoreAtCompact` | Robolectric | restore is the confirmed action of Story 6.2.6 |
| UX-112 touch note | `AT/sketch/v2/NoticeQueueTest.kt` | `touchOnNotice_should_FreezeCountdownUntilReleasePlusThreeSeconds` | Robolectric (virtual time) | WCAG 2.2.1 note: information in action-less notices is also in the diagnostics list |
| Metric 8 denominator | manual (`baselines.md`, 8-week read) | `adoption_should_BeReportedAsOwnerSelfReport_WithAuthoredCounterAsNumeratorOnly_AndImportsReportedSeparately` | Manual (owner) | counter cannot see diagrams drawn elsewhere; bar is ceil(0.5 n) for n at least 5 |
| Frame-time rule | `JT/sketch/v2/SketchEngineBenchmark.kt` | `frame_should_AssertOnlyTheRelative1_5xRule_AndNeverTheAbsolute16Or24ms` | Integration | the absolute figures stay advisory |
| Metric 3 stop tolerance | `CT/sketch/constraints/SolverPropertyTest.kt` | `solver_should_ReportNoConvergence_When_ResidualAbove1e6_And_StopAt1e9Relative` | Unit | acceptance 1e-6 against solver stop 1e-9 |

## Triad-review repair iteration 5: new and changed test rows
Design rule of this iteration: contradictions were resolved by choosing one rule and deleting the competing text; the rows below replace, never add to, the earlier rows named in the "Replaces" column. No UX criterion was added (118), two tasks were (3.1.2h, 5.1.1f).

| Item | Test (file) | Test names | Level | Replaces / note |
|---|---|---|---|---|
| P1 default range single source | `check-plan.py` (this file) | `checkPlan_should_FailWhenAnyDocumentNamesADifferentMaximumThanThePlanListsForDefaultsD1ToDn` | Script check | requirements.md names the range by pointing at the plan list |
| P2 A6 cost share | `check-plan.py --effort` | `effort_should_PrintJob2EpicsShare_4_1_Plus_4_2_OfTotal` | Script check | requirements A6 quotes the figure the script prints (checked, iteration 6) |
| P3 A6 go/no-go | manual (`implementation/a6-job2-demand.md`, Task 0.1.2d) | `job2Demand_should_RecordProceedAtOneOfThree_AdjustNarrowsKinds_DeferAtZero_ParkNeverDelete` | Manual (owner) | outcome names PROCEED / ADJUST / DEFER owned by requirements.md "A6 go/no-go" (D-38) |
| E1 task split | `check-plan.py` | `checkPlan_should_FlagATaskWhoseHeadingNamesMoreThanFourDeliverablesBeyondItsFileList` | Script check | best-effort heuristic; Tasks 3.1.2g (policy, strip, setting, backstop) and 3.1.2h (platform hosts, palm) list every file |
| E1 platform hosts | `AT/sketch/v2/PlatformEdgeHostsTest.kt` (Task 3.1.2h) | `androidHost_should_ReRegisterExclusionRectForSpanOnEveryLayoutPass`; `palmClassifier_should_IgnoreToolTypePalmAndPalmCancel`; `iosAndWebHosts_should_CompileAndSetDeferralAndTouchAction` | Robolectric + compile | iOS compile-only |
| E2 baseline SHA | `scripts/sketch-baseline/record-sketch-baseline.sh` (Tasks 0.1.7d, 6.2.5a) | `baseline_should_StartWithBaselineShaLine_AndDeriveOnlyFromGitLsTreeAtThatSha_AndRefuseAShaNotOnOriginMain`; `rerun_should_ReproduceByteForByteAtRecordedShaFromALaterCheckout`; `regression_should_FailWhenAListedTestFileDiffersBetweenShaAndHead`; `ci_should_FailWithShallowCloneMessage_When_ShaUnreachable_AndUseFetchDepthZero` | Script check | replaces the working-tree baseline wording of D-37; the SHA must be an ancestor of `origin/main` so it survives a squash merge |
| E3 differential classes | `CT/sketch/excalidraw/JsonSpanScannerTest.kt` (Task 5.1.1e) | `differential_should_EqualWholeFileOracle_ForWellFormedInLimitInput`; `malformed_should_RejectWithMalformedJsonInBothPaths_ForEveryGeneratedMutationFamilyAtEveryPosition`; `duplicateTopLevelKeys_and_duplicateFileIds_should_LastWin`; `escapedKeys_should_BeRecognisedAsElements`; `nonArrayElements_and_nonObjectFiles_should_BeIgnoredWithReportReason`; `bom_should_BeSkippedOnlyWhenFirst`; `overLimitInput_should_BeComparedToOracleMinusDroppedSpans`; `escapedDataUrl_should_BecomePlaceholderWithDataUrlEscaped_AndOtherEntriesEqualOracle` | Pure | nine explicit classes in Story 5.1.1 (class 9 added in iteration 6) |
| E4 depth in scanner | `CT/sketch/excalidraw/JsonSpanScannerTest.kt` | `deepNestedBrackets_within2MbSpan_should_BeRefusedImportTooDeep_BeforeDecode_WithZeroDecoderCalls_AndNoStackOverflow` | Pure | `maxJsonDepth` 64 from the document root |
| E5 files rule and source API | `CT/sketch/excalidraw/JsonSpanScannerTest.kt`; `CT/sketch/excalidraw/ClipboardTest.kt` | `filesEntry_should_TreatOnlyDataUrlAsRawRange_InAnyMemberOrder_AndCapOtherMembersAt2Mb`; `clipboardText_should_EnterThroughImportSourceOfText_RefusedAboveMaxImportBytesBeforeCopy` | Pure | replaces "each entry of the files map is one span" |
| E5 wasm test split | `kmp/src/wasmJsTest/.../JsonSpanScannerWasmTest.kt` (Task 5.1.1f) | `wasm_should_FireTimerCallbackBeforeParseCompletes_And_RecordMaxYieldGapAndSpanDecodeMs` | wasm | moved from Task 5.1.1e; runs on the real dispatcher with real timers, never `runTest` virtual time |
| U1 split button | `AT/sketch/v2/ToolStripTest.kt` (Task 3.1.3a) | `groupButton_should_HaveSeparateMainAndFlyoutTargets_EachAtToolTarget_AlongTheStripAxis_InDockAndRail`; `uxA11y_should_NameAnyTargetBelowTheFloor` | Robolectric | replaces "its own 24 dp target" and the corner-mark wording |
| U2 flat rule | `CT/sketch/v2/ChromeBudgetPolicyTest.kt` (Task 3.1.4a) | `tools_should_BeFlatOnlyWhenFlatExtentFits_AtEveryWindowOfTheTable_AtEveryWidthClass` | Pure | single source for UX-97 and UX-119: replaces the earlier hand-typed flat-extent figure, which the script now computes |
| U3 worked examples | `AT/sketch/v2/ChromeBudgetTest.kt` | `canvasHeights_should_EqualTheScriptComputedColumn_ForEveryWindowOfTheTable` | Robolectric | no figure typed in the test: expected heights come from the parsed chrome table and the same formula as `check-plan.py` |
| U4 banner slot | `AT/sketch/v2/ChromeBudgetTest.kt` | `allBannerCandidatesPending_should_ShowExactlyOneInRankOrder_AndKeepMinimumCanvas_AtEveryWindowOfTheTable` | Robolectric | the candidate list is the banner table (count computed), emitted into the real queue by a fake producer; at most three transient rows |
| U5 pan pad | `AT/sketch/v2/NonDragTransformTest.kt` (Task 3.6.3d); `AT/sketch/v2/EdgePanStripTest.kt` | `panPad_should_CollapseToToolTargetButton_OpenAsGridOrRowPerTheTable_HaveNoAutoCollapseTimer_AndNotShowOnMouseOnlyDesktop`; `panPadAndCluster_should_BeInsetClearOfActiveStripSpan`; `edgePanSide_should_DefaultRightOnCompact_BothFrom600dp_OffWhenNotSupported`; `holdRepeat_should_JumpInstantlyUnderReducedMotion` | Robolectric | no dominant-hand rule; no translucency |
| U6 new-sheet dialog | `AT/sketch/v2/UxMigrationAcceptanceTest.kt` (Task 1.4.6a) | `newSheetDialog_should_HaveNoBackupSentence_OneCheckbox_AndFourOutcomes_CreateAndOpen_CreateClassicWithNotice_EscCancels_BackCancels` | Robolectric | UX-64 row extended |
| U7 UX-104 wording | `AT/sketch/v2/UpgradeOfferTest.kt` | `chip_should_CarryConsequenceLineAndDetailsSentence_UpgradeShouldAlwaysOpenDialogBeforeFirstConversion_AndBannerRepeatsSentenceAfterEveryConversion` | Robolectric | replaces "chip carries the whole sentence until acknowledged" (it cannot fit the banner row, D-41) |
| U8 counting convention | `AT/sketch/v2/BracketPlateUxScenarioTest.kt` | `actionCounter_should_CountTypedValuePlusEnterAsOne_AndLineToolHeldByToolLock`; bounds 42 and 34 | Robolectric | D-21 |
| U9 DOF text | `AT/sketch/v2/ConstraintStatusTest.kt` | `status_should_BeAbsentOnDiagramSheetWithNoConstraints_AndShownOnTechnicalOrConstrainedSheets` | Robolectric | UX-31, D-40 |
| U10 move without drag | `AT/sketch/v2/NonDragTransformTest.kt` (Task 3.6.3a) | `moveElement_should_ReachableByCrosshairAndArrowKeys_OneUndoStep_BoundArrowsFollow` | Robolectric | UX-40 list extended, D-39 |
| Nits | `AT/sketch/v2/OnboardingSequencerTest.kt`; `AT/sketch/v2/DiscoverabilityTest.kt` | `tieBreak_should_OrderEdgePanStoppedThenFingerHintOffThenOn`; `coachMark_should_NameThreeThingsInOneString`; `web_should_OfferGoToRegionWhereBrowserKeepsF6` | Robolectric + wasm | Library tab and `[Restore original]` label aligned in ux.md |

## Triad-review repair iteration 6: new and changed test rows
Documents only; the tests are designed, not written or run. This iteration is NOT re-reviewed and no review pass is claimed. Design rule: every layout figure is defined once in the ux.md chrome table and computed by `check-plan.py`; contradictions were resolved by single-sourcing or deletion where possible (counted in the iteration report), by addition only where a missing rule had to be written.

| Item | Test (file) | Test names | Level | Replaces / note |
|---|---|---|---|---|
| Single-source layout | `check-plan.py` (this file) | `chrome_should_ComputeExtentsStacksAndCanvasHeightsFromTheTable`; `chromeComputedBlock_should_EqualScriptOutput`; `prose_should_NotStateADerivedFigureThatDiffers`; `bannerTable_should_FitTheTextBudgetAtEveryWidthAndScale`; `recurrenceTable_should_ListTheOnboardingRowsInBannerRankOrder` | Script check | one table, one order; the script fails on any stale figure |
| U1 one banner order | `AT/sketch/v2/OnboardingSequencerTest.kt`; `AT/sketch/v2/ChromeBudgetTest.kt` | `ux116_order_should_BeReadFromBannerTable`; `allBannerCandidatesPending_should_ShowExactlyOneInRankOrder_AndKeepMinimumCanvas_AtEveryWindowOfTheTable` | Robolectric (virtual time) | one order in one place (ux.md banner table); s16 table and Task 3.6.7c reference it |
| U2 banner content fit | `AT/sketch/v2/ChromeBudgetTest.kt` | `everyBannerRow_should_RenderAsOneRowWithinTextBudget_At320And360Dp_AtScales1And2_NoClipping_ButtonsAtFloor`; `longCopy_should_LiveInDetails_NotInTheRow` | Robolectric | UX-126; script checks summary and short-title lengths against the budget |
| U3 action chip row | `AT/sketch/v2/ActionChipRowTest.kt` (Task 3.1.4c) | `touchOnly_should_FinishPolylineAndToggleSquare_AtCompactHeightRail`; `chips_should_NeverCollapseWithARow_AndScrollWithChevron`; `hintRow_should_ContainNoControl` | Robolectric | UX-126, D-42 |
| U4 strip arithmetic | `CT/sketch/v2/ChromeBudgetPolicyTest.kt` | `flatAndGroupedExtents_should_EqualTheTableComputation`; `splitButtonOrientation_should_LieAlongStripAxis_AndRailWidthBeFixed`; `groupedStrip_should_ListSixSinglesAndThreeGroups` | Pure | replaces the typed 908 / 784 / 732 figures and the "1280 x 800 grouped fits" claim |
| U5 layout rule | `AT/sketch/v2/ChromeBudgetTest.kt` | `medium_should_FollowHeightRule_DockAtRegularHeightRailAtCompactHeight` | Robolectric | Medium has no layout of its own |
| U6 Finger chip pinned | `AT/sketch/v2/TopBarTest.kt` (Task 3.1.4d) | `ux80_topBarChip_should_BeOnScreenWhileStripScrolls_AtEveryWidth`; `topBar_should_LeaveNameItsMinimumAt320` | Robolectric | D-43 |
| U7 degraded layout | `CT/sketch/v2/ChromeBudgetPolicyTest.kt`; `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/sketch/v2/WebReflowTest.kt` | `degradedWindow_should_NotClipAndKeepEveryControlReachable_At320x256`; `ux113_web_should_ReflowAt320x256CssPx_Zoom400` | Pure + wasm | UX-128; test grid adds the degraded windows of the table |
| U8 pan pad | `AT/sketch/v2/NonDragTransformTest.kt` (Task 3.6.3d) | `panPad_should_HaveNoAutoCollapseTimer`; `panPad_should_UseRowVariantWhereGridCoversOverTheTableShare`; `panPad_should_NeverCoverMoreThanTheTableShareOfAGuaranteedCanvas` | Robolectric | WCAG 2.2.1; D-44 |
| U9 selectability | `CT/sketch/v2/tools/SelectToolTest.kt`; `AT/sketch/v2/ElementsPanelTest.kt` | `canvasSelection_should_NeverSelectLockedOrHidden`; `elementsList_should_SelectLockedWithUnlock_And_OfferShowLayerForHidden`; `lock_should_LeaveJustLockedElementsSelected` | Pure + Robolectric | UX-12 amended, D-45 |
| U10 UX-01 steps | `AT/sketch/v2/UxAuthoringAcceptanceTest.kt` | `ux01_labelledBox_should_NeedAtMost4Steps_UnderTheOneCountingConvention` | Robolectric | convention shared with metric 2 and the bracket plate |
| U11 Alt | `AT/sketch/v2/DiscoverabilityTest.kt` | `hintTextAudit_should_FailOnAnyLinuxHintNamingAlt_And_OnAnyAltOnlyFunction`; `arrowTool_should_DisableBindingWithCtrlOnly` | Robolectric | UX-109, D-45 |
| U12 handle tiers | `AT/sketch/v2/HandleLayoutTest.kt` (Task 3.2.2d) | `handles_should_NeverOverlap_ForGeneratedSizes_AtBothFloors`; `smallSelection_should_OfferTransformMenuStatsAndCrosshair` | Robolectric | UX-127 |
| U13 toast lane | `AT/sketch/v2/ActionChipRowTest.kt` (Task 3.1.4c) | `toasts_should_NeverFullyCoverAFocusedNode_AtMaximumToasts`; `toastCount_should_EqualTheTableColumn`; `coveredControlFocus_should_MoveToastToDiagnostics` | Robolectric | UX-128, WCAG 2.4.11 |
| Nits (UX) | `AT/sketch/v2/DiscoverabilityTest.kt`; `CT/sketch/v2/KeymapSpecTest.kt`; `AT/sketch/v2/StylePanelTest.kt`; `AT/sketch/v2/UxA11yAcceptanceTest.kt` | `coachMark_should_PointAtOverflowEntry_NotAQuestionMarkControl`; `webConstrainChord_should_BeCtrlK`; `opacity_should_OfferNumericFieldAndStepButtons`; `ux47_should_CoverProgressShimmerAndSolvingIndicator`; `touchFloorSwitch_should_NotMoveAnyControl`; `strip_should_BeOneTabStopWithRovingFocus` | Robolectric + Pure | tab list is one list of five; press-and-hold delay 400 ms |
| P1 circle | `CT/sketch/v2/ConstraintEligibilityTest.kt` (existing row) | `ellipseWithUnequalRadii_should_NotBeConstrainable` | Pure | requirements now says circle (ADR-003 decision 5) |
| P2 gated list | `check-plan.py --effort` | `gatedList_should_EqualRequirementsList_And_Story424_ShouldNotBeGated_And_JobTwoFirstCostBeComputed` | Script check | one GATED set; requirements quotes the script's figures |
| P3 metric 8 and A6 x A1 | manual (`baselines.md`, `a6-job2-demand.md`) | `metric8_should_ReportInsufficientData_When_FewerThan5Diagrams`; `jobTwoFirst_should_NotBeOffered_When_A6Defer` | Manual (owner) | D-46; Task 0.1.7c records the diagram frequency |
| E1 library spans | `CT/sketch/excalidraw/LibraryImportSpanTest.kt` (Task 5.1.1g) | `library9Mb120Items_should_ImportWithDecoderCallsUnderSpanCap_AndEqualOracle`; `overCapItem_should_BeSkippedOnly`; `libraryBytesCap_should_RefuseTheItemThatExceeds16Mb`; `importBeyond200ItemsOr16Mb_should_KeepFittingItemsAndReportLibraryFull` | Pure | `maxLibraryBytes` (31st field) |
| E2 banner ownership | `BT/sketch/v2/BannerOwnershipTest.kt` (Task 3.6.7a) | `producers_should_NotImportBannerSlot_And_BannerSlotShouldNotImportProducers` | Unit | producers emit through `DiagnosticsPort`; candidates are `SketchNoticeQueue` entries |
| E nits | `CT/sketch/excalidraw/ClipboardTest.kt`; `CT/sketch/excalidraw/JsonSpanScannerTest.kt` | `clipboardText_should_BeRefusedWhenEncodedSizeExceedsCap_EvenIfCharCountDoesNot`; `clipboardText_should_NotBeEncodedWhenFirstNonSpaceCharIsNotBrace` | Pure | ofText re-check after UTF-8 and the first-character check |

## Triad-review repair iteration 7: new and changed test rows
Documents only; designed, not written or run; this iteration is NOT re-reviewed and no review pass is claimed. Design rule: layout and text figures stay in the ux.md chrome table and `check-plan.py` computes them; where a figure was wrong (banner text width without the icon) the table and script were fixed and the prose that restated it was deleted.

| Item | Test (file) | Test names | Level | Replaces / note |
|---|---|---|---|---|
| P1 authored numerator | `CT/sketch/v2/SketchLogTest.kt` (Task 6.2.3b) | `adoption_should_CountOnlySheetsWithAtLeast3DrawnElements_AndReportImportsAndConversionsSeparately` | Pure | `sketch.adoption authored=1 imported=1` for two new sheets (3 and 2 drawn elements), one conversion and one import |
| P1 rounding rule | manual (`baselines.md`, 8-week read) | `metric8_should_NeedCeilHalfNAuthored_For5To8Diagrams_3_3_4_4`; `fewerThan5Diagrams_should_BeInsufficientData_NeverPassOrMiss` | Manual (owner) | rule stated in requirements metric 8 |
| P2 flag-exit integers | `BT/sketch/v2/QualitySignalsThresholdTest.kt` (Task 6.2.5d) | `flip_should_NeedAtLeast50Sessions_AndAtMostFloorNOver100Crashes`; `stopShip_should_TripAtTwoCrashesInTheLast20Sessions_NotAtOne`; `noConvergence_should_AllowFloorNOver50At200Solves_AndStopAtElevenInLast100`; `unmetMinimumNAtWeek26_should_ReportInsufficientData_AndNeverFlipTheDefault`; `noConvergenceRow_should_BeNotApplicable_WhileConstraintUiIsParked` | Unit | replaces the percentage wording, which at 50 sessions meant zero crashes and tripped on one |
| P3 A1 second ADJUST | manual (`a1-gate.md`) | `secondAdjust_should_RecordOwnerDecisionProceedWithNamedDefectsOrPivot_NeverAThirdRepeat` | Manual (owner) | like A5 |
| P3 combined outcomes | `check-plan.py` (below) | `combinedTable_should_CoverEveryA1ResultAndTheA6DeferRows_AndBeCitedByPlan` | Script check | requirements "Combined outcomes: A1 x A4 x A6" |
| A5 recruitment | manual (`usability-results.md`) | `participants_should_BeOwnerPlus2To4KnownDiagramMakers_OrBeReportedOwnerOnlyUnmeasured` | Manual (owner) | pre-W1 item (f) |
| E1 pure candidate | `CT/sketch/v2/BannerCandidateTest.kt` (Task 3.6.4a); `BT/sketch/v2/BannerOwnershipTest.kt` | `candidate_should_BeBuiltOnlyFromRowIdAndDetails_AndRefuseOverBudgetStringsAndLabels`; `budgetConstants_should_EqualTheChromeTableComputation`; `producersInV2Diagnostics_should_EmitAll14RowsWithoutImportingUI2`; `candidateType_should_BeDeclaredOnlyInV2Diagnostics` | Pure + Unit | replaces the `UI2/BannerCandidate.kt` ownership of iteration 6 |
| E nit: library spans | `CT/sketch/excalidraw/LibraryImportSpanTest.kt` (Task 5.1.1g) | rows of iteration 6, now listed in Story 5.1.1 Files and the file-list block | Pure | |
| E nit: wasm library mode | `JsonSpanScannerWasmTest.kt` (Task 5.1.1f) | `libraryMode9Mb120Items_should_FireTimerCallbacksBetweenItems_And_RecordMaxYieldGap` | wasm | real timers, like the whole-file case |
| U1 banner budget | `check-plan.py`; `AT/sketch/v2/ChromeBudgetTest.kt` | `bannerTable_should_WordWrapInsideTwoLines_AtTheComputedLineWidth_At320x1And320x2`; `primaryLabels_should_FitTheButton_WordsWrapOnlyBetweenWords`; `everyBannerRow_should_RenderAsOneRowWithinTextBudget_At320And360Dp_AtScales1And2_NoClipping_ButtonsAtFloor` | Script + Robolectric | the text width is 108 dp at 320 dp, not 140 |
| U2 dynamic text and PenDetected | `AT/sketch/v2/ChromeBudgetTest.kt`; `AT/sketch/v2/FingerDrawingFlipTest.kt` | `warningNotice_should_ShowFixedSummary_AndNameTheConstraintOnlyInDetails`; `penDetected_should_BeBannerRow6_WithFixedSummary_AndFullTextInDetails` | Robolectric | UX-102, UX-126, D-49 |
| U3 top bar at large fonts | `AT/sketch/v2/TopBarTest.kt` (Task 3.1.4d) | `fingerChip_should_StayTextAtEveryScale_OnOffFrom1_5`; `saveStatus_should_BeGlyphOnlyFrom1_5_WithFullStateInSemanticsAndTooltip`; `topBar_should_NeverWrap_AndKeepNameMin_AtEveryWindowOfTheTable`; `topBarColumn_should_EqualTheScriptComputedTier` | Robolectric | UX-80, D-50 |
| U4 keyboard order | `AT/sketch/v2/KeyboardDrawingFlowTest.kt`; `CT/sketch/v2/KeymapSpecTest.kt`; `check-plan.py` | `tabOrder_should_FollowTheSection10List_AtEveryLayout`; `skipLink_should_LandOnTheNextStop_SoAlignOrderLockTransformAreOneTabAway`; `f6_should_ReachNoticeRegionAndSelectionBar`; `toastAndBannerActions_should_BeKeyboardOperable_AndRepeatedInNoticesList` | Robolectric + Pure | D-52; the script checks the two lists in ux.md |
| U5 Selection menu | `AT/sketch/v2/TopBarTest.kt` | `selectionMenu_should_HoldDuplicateGroupAlignOrderTransformLock_AtCompactHeightRail_AndMoveToOverflowWhenDegraded` | Robolectric | UX-127; Transform is the handle fallback |
| U6 overlays | `AT/sketch/v2/ChromeBudgetTest.kt` | `overlays_should_NeverOverlapOrExceedTheTableShare_AtEveryWindow`; `drawingWidth_should_StayAtLeastMinCanvasWidth_WithEdgeStripsActive`; `panPad_should_HaveNoCornerCells` | Robolectric | table columns "View cluster..." and "Drawing width" |
| U7 toasts | `AT/sketch/v2/ActionChipRowTest.kt` | `everyToastRow_should_FitBudgetAtDynMax_AtScales1And2_With320Dp`; `toastWithTwoActions_should_ShowPrimaryAndMore` | Robolectric | UX-128 |
| U8 focus reveal | `AT/sketch/v2/FocusRevealTest.kt` (Task 3.6.6a) | `focusedElementUnderOverlay_should_PanLeastAmountClearOfAllOverlays_AndNotZoom_AndNotBeUndoEntry`; `noFocusedNodeCentre_should_BeCoveredAtAnyWindowOfTheTable` | Robolectric | UX-125 |
| UX nits | `AT/sketch/v2/DiscoverabilityTest.kt`; `AT/sketch/v2/ActionChipRowTest.kt`; `AT/sketch/v2/EdgePanStripTest.kt` | `hintTextAudit_should_FailOnAltOnLinuxNativeAndWeb`; `singleKeySetting_should_CoverDigitsQuestionMarkBracketsAndQ`; `toolLockChip_should_ShowWhileLockOn_AndTurnItOff`; `spike_should_MeasureDragsStartingInsideTheStripNotTheInset` | Robolectric + manual | D-51, D-53 |
| UX wording | `AT/sketch/v2/UpgradeOfferTest.kt` | `chip_should_ShowConsequenceLine_UpgradeBlocksOtherDevices_AndDetailsCarryTheFullSentence` | Robolectric | "digest" retired for the chip |

## Triad-review repair iteration 8: new and changed test rows
Documents only; designed, not written or run; this iteration is NOT re-reviewed and no review pass is claimed. Design rule: figures and strings stay in the ux.md chrome table and `check-plan.py` checks them (now by word wrap and accessible name, not by character count); the tests read the table through ONE generated fixture; duplicated prose was deleted or pointed at the table.

| Item | Test (file) | Test names | Level | Replaces / note |
|---|---|---|---|---|
| P1 A1 paired gate | manual (`a1-gate.md`, Task 6.2.4e) | `a1Gate_should_RecordPairedInAppAndExcalidrawComTimes_AndTheirRatio_N1ByDesign`; `a1Result_should_FollowPivotThenAdjustThenProceed_WhenRatioAndBlockerClassDisagree` | Manual (owner) | replaces the comparison with the 0.1.7a median (a different diagram); D-54 |
| P2 combined table | `check-plan.py` | `combinedTable_should_HaveNoEmptyCell_AndNameTheSecondAdjustOutcome` | Script check | the "ADJUST (second)" row |
| E1 chip ownership | `AT/sketch/v2/TopBarTest.kt` (Task 3.1.4d); `AT/sketch/v2/FingerDrawingFlipTest.kt` (Task 3.1.2e) | `fingerChip_should_LiveInTheTopBar_ReadTheFingerDrawingState_AndToggleTheSetting`; `flip_should_ChangeTheStateAndRaiseTheNoticeOnly` | Robolectric | Task 3.1.2e exposes state, setting and notice; the chip composable is Task 3.1.4d (edge 3.1.4 after 3.1.2d) |
| E2 generated fixture | `CT/sketch/v2/ChromeTableFixtureTest.kt` (Task 3.6.4d); CI step `gen-chrome-fixture.py --check` (Task 3.6.4c) | `fixture_should_ParseIntoTypedRows_And_CarryTheContentHash`; `ciStep_should_FailWhenTheCommittedFixtureDiffersFromTheUxTable` | Pure + CI | `ChromeBudgetPolicyTest`, `ChromeBudgetTest`, `BannerCandidateTest`, `OnboardingSequencerTest` and `TopBarTest` read the fixture and type no figure; D-55 |
| E nit: references | `check-plan.py` | `references_should_ResolveUnsuffixedIdsAndPluralTaskLists`; `storyNeedsHeading_should_MatchTheEdgeBlock_ExceptTheWhitelistedStoryToTaskRefinements` | Script check | whitelist: 1.1.5 to 1.2.4a, 6.2.7 to 6.2.3a and 3.6.4b; Story 3.6.8 and 4.2.6 resolve through their tasks |
| E nit: ownership test | `BT/sketch/v2/BannerOwnershipTest.kt` (Task 3.6.7a) | `producers_should_NotImportBannerSlot_And_BannerSlotShouldNotImportProducers` | Unit | now a named task row, no longer only a story file |
| U1 word wrap | `check-plan.py`; `AT/sketch/v2/ChromeBudgetTest.kt` | `everySummaryAndShortTitle_should_WordWrapInsideTheRowLines_AtTheComputedLineWidth`; `renderedRow_should_HaveTheLineCountTheScriptComputes_At320And360Dp_AtScales1And2` | Script + Robolectric | five summaries reworded (rows 6, 9, 11, 13, 14), three short titles reworded (rows 5, 8, 14) and the consent row has none |
| U2 consent line | `AT/sketch/v2/UpgradeOfferTest.kt` (Task 1.4.6g) | `chip_should_ShowOneConsequenceLine_UpgradeBlocksOtherDevices`; `consentRow_should_BecomeASheetNotAShortTitle_WhenTheSummaryDoesNotFit`; `consentSheet_should_ShowTheWholeProjectSentenceUnclipped_AtFontScale2`; `laterSketchInAcknowledgedProject_should_ConvertOnStartWithoutModal_WhileTheConsequenceStaysOnTheChip`; `postConversionRow_should_ReadUpgradedBackupSaved_WithTheSentenceInDetails` | Robolectric | UX-103, UX-104, UX-123, D-56 |
| U2 comprehension at 2.0 | manual (`usability-results.md`, Story 6.2.8 task 2) | `consentComprehension_should_BeAskedAtFontScale2_OnTheConsentSheet` | Manual (owner) | UX-123 |
| U3 read-only [Use new] | `AT/sketch/v2/ReadOnlyUseNewTest.kt` (Task 1.4.6c) | `useNew_should_OpenTheFlagEnableConfirmation_AndNeverSwapTheOpenEditor`; `useNew_should_BeDisabledWithReason_WhileConversionIsNotReady`; `turnOnAndReopen_should_StoreTheModeAndReopenInOneAction`; `cancel_should_StoreNothing` | Robolectric | ADR-005 items 2 and 3, UX-69, D-57 |
| U4 toast catalogue | `check-plan.py`; `AT/sketch/v2/ActionChipRowTest.kt`; `AT/sketch/v2/SuccessFeedbackTest.kt` | `everyToastRow_should_WordWrapInsideTwoLines_AtDynMaxAndTheNPlaceholder_AtScales1And2`; `exportSaved_should_ShowOpenAndCopyPath_WithTheFileNameInDetails`; `libraryAddedAndInserted_should_CarryUndo_WithCountsInDetails`; `copiedToGraph_should_CarryOpen`; `restored_should_CarryCopyPathOfTheKeptCopy`; `classicEditorToast_should_CarryChange_WithTheReasonInDetails`; `lowMemoryToast_should_CarrySaveNow_AndDismissInMore`; `constraintLimitToast_should_CarryReview`; `lockedSkippedToast_should_CarryUnlockAll` | Script + Robolectric | 16 toast rows; UX-114, UX-126 |
| U5 names and labels | `check-plan.py`; `AT/sketch/v2/ChromeBudgetTest.kt` | `primaryNames_should_ContainTheVisibleLabel_AddAnObject_AndBeUnique`; `visibleLabel_should_NotBeReusedForDifferentMeanings`; `liveRegion_should_AnnounceTheFullSummary_AtFontScale2`; `detailsRule_should_PutDetailsInMore_UnlessPrimaryIsLearnMore`; `tierOrder_should_BeBlockingStateWarningInfoOnboarding` | Script + Robolectric | WCAG 2.5.3, 2.4.6, 4.1.2; D-56 |
| U nit: Bind end | `AT/sketch/v2/TopBarTest.kt` | `selectionMenu_should_OfferBindEnd_WhenOneArrowIsSelected_AtCompactHeightRail` | Robolectric | UX-127 |
| U nit: Show me | `AT/sketch/v2/DiscoverabilityTest.kt` | `showMe_should_RunThreeSteps_WithBackNextDone_FocusMoves_OneAnnouncementPerStep_DeviceAwareWording_EscEndsIt_AndChangeNothing` (superseded by `CoachWalkthroughTest`, iteration 9) | Robolectric | UX-97; replaced by the iteration 9 rows |
| U nit: queue priority | `AT/sketch/v2/NoticeQueueTest.kt` | `priority_should_FollowTheBannerTierOrder_IncludingState` | Robolectric (virtual time) | UX-91 |
| U nit: large fonts | `AT/sketch/v2/ChromeBudgetTest.kt` | `fontScale3_should_HoldChromeAt2_AndScrollPanelsAndSheets`; `smallestPhoneAtScale2_should_KeepADrawingAreaBetweenTheEdgeStrips_WhenOverlaysCoverTheComputedShare` | Robolectric | UX-88, UX-119 |

## Triad-review repair iteration 9: new and changed test rows
Documents only; designed, not written or run; this iteration is NOT re-reviewed and no review pass is claimed. Design rule: a decision the plan makes about a parked outcome, a flag mode or a threshold is a script check or a named test, never prose alone.

| Item | Test (file) | Test names | Level | Replaces / note |
|---|---|---|---|---|
| P1 flag mode matrix | `CT/sketch/v2/FlagModeMatrixTest.kt` (Task 1.4.3j) | `kindOfNewSheet_should_BeV2OnlyInANewSketchProject_WhenNewProjectsOnly_AndAnywhereWhenOn`; `effectiveMode_should_CapOnToNewProjectsOnly_WhenConversionOfferedFalse`; `effectiveMode_should_BeOff_WhenConversionReadyFalse`; `newProjectsOnly_should_EmitNoUpgradeChip_AndNeverReachTheConverter`; `modeStoredAsString_should_ReadUnknownAsOff_AndNeverCallGetBoolean` (iteration 10: typed fake `Settings` that throws on `getBoolean` for the key; replaces the vacuous `storedBoolean_should_ReadAsOffOrOn`) | Unit | every cell of 3 modes x ConversionReady x ConversionOffered x project kind {created by this action, existing-empty, all-v2, mixed, v1-only} x schema |
| P1 A4 NO-GO build | `BT/sketch/v2/ConversionReadyConsistencyTest.kt`; `CT/sketch/v2/FlagModeMatrixTest.kt` | `noGoBuild_should_CreateV2OnlyInNewProjects_AndNeverOfferOrRunConversion`; `converter_should_ReturnNotReady_WhenConversionOfferedFalse_AndWriteNothing` | Unit | requirements A4 NO-GO outcome made implementable |
| P1 settings control | `AT/sketch/v2/FlagModeSettingsTest.kt` (Task 1.4.6h; UX-76) | `modeControl_should_ShowThreeOptions_DisableCappedOnesWithReason_NeverHideThem`; `newProjectsOnlyConfirmation_should_UseTheNewProjectWholeProjectSentence`; `useNew_should_StoreNewProjectsOnly` | Robolectric | `ConsentCopyTest` gains the `NewProjectsOnly` lead-in row |
| P1 ADR record | owner checklist (Task 1.4.3e) | `a4Result_should_BeRecordedInTheAdr005Index_AndMatchTheConversionOfferedConstant` | Manual (owner) | NO-GO: ADR-001 amendment A4-NO-GO with the 0.74M CU estimate |
| P2 parked outcomes | `check-plan.py` | `everyNodeWithAnEdgeIntoAParkedNode_should_HaveAReducedFormLine`; `readerOnlyRelease_should_HaveNoPathToTheGateOrAParkedNode_AndFeedTheFlagRelease`; `pivotCosts_should_IncludeTheReleasePath` | Script check | mutation-tested: removing a REDUCED line, adding an edge 6.2.4e to 6.2.5e, or dropping 6.2.5e from 6.2.5c each fail |
| P2 skipped nodes named | owner checklist row of Task 6.2.5b (its pasted output lists each `Skipped(<outcome>)` node of `implementation/parked.md`) | `verificationRun_should_NameEverySkippedNode` | Manual (checklist row) | a skipped node is never silently absent |
| P3 pre-check hold | `check-plan.py` | `heldSet_should_EqualTheClosureComplementOfTheKeptEngines`; `everyHeldRoot_should_HaveAnEdgeFromTheGate`; `everyHeldNode_should_ReachTheGate` | Script check | mutation-tested: deleting one root edge fails |
| P4 fallback plate | `CT/sketch/constraints/FallbackPlateScenarioTest.kt` (Story 4.1.3) | `fallbackPlate_should_HaveFourDof_ResidualUnder1e6_DragPlateRigidly_RejectSecondDistanceNamingBoth_AndWidenBy1In` | Pure | replaces the bracket plate as metric 3's scenario while the fallback is active; `BracketPlateScenarioTest` is enabled per kind |
| P4 residual in spike exit | `CT/sketch/constraints/SolverSpikeTest.kt` (Task 0.1.2c) | `spike_should_FailTheExitCriteria_WhenAnySuccessfulSolveLeavesAResidualAbove1e6` | Pure | ADR-003 item 6 |
| P nit: metric 4 Android | owner checklist (Task 6.2.4c) | `androidPixels_should_BeReportedOwnerChecked_WhenRobolectricRuledOut` | Manual (owner) | requirements metric 4 |
| P nit: window start | `BT/sketch/v2/QualitySignalsThresholdTest.kt` (Task 6.2.5d) | `windowStart_should_BeTheFirstLineOfQualitySignals_AndTheOnlyOriginOfEveryWindow` | Unit | requirements metric 8 defines it once |
| P nit: input-spike note | doc presence check in Task 0.1.4d | `inputSpikeNote_should_ExistAndHaveNoUnmeasuredCell_BeforeTheSpikeIsClosed` | Manual (checklist row) | ADR-007 links to `research/input-spike.md` (stub created) |
| E1/E2 generator and parser | `CT/sketch/v2/ChromeTableFixtureTest.kt` (Task 3.6.4d); CI step `gen-chrome-fixture.py --check` (Task 3.6.4c) | `generator_should_EscapeDollarAndTripleQuotes_AndTakeTheUxPathAsAParameter`; `fixture_should_RoundTripANonAsciiDollarBearingTable`; `bazelTargets_should_BundleNoTestClass` | Pure + CI | selftest; `sketch_chrome_fixture` and `sketch_chrome_fixture_android` have no `@Test` class, so no completeness guard sees them |
| E3 heading needs | `check-plan.py` | `taskHeadingNeeds_should_MatchTheEdgeCarrier` | Script check | mutation-tested: removing 3.1.2d from the 3.1.4 edge fails |
| E nit: positive controls | `check-plan.py` | `proseFigureScans_should_FlagAPlantedWrongFigure_AndPrintTheirCounts` | Script check | the "0 prose figures checked" result is no longer vacuous |
| U1 consent threshold | `check-plan.py`; `AT/sketch/v2/ChromeBudgetTest.kt` (UX-126); `AT/sketch/v2/UpgradeChipConsentSheetTest.kt` (Task 1.4.6i; UX-104, UX-123) | `consentSheet_should_ReplaceTheRow_WheneverTheWrappedSummaryExceedsTwoLines_AtScales1_1_15_1_3_1_5_2_At320And360Dp`; `thresholdProse_should_EqualTheComputedScale` | Script + Robolectric | computed: from font scale 1.11 at 320 dp wide and from 1.52 at 360 dp wide |
| U2 consent sheet behaviour | `AT/sketch/v2/UpgradeChipConsentSheetTest.kt` | `sheet_should_OpenOncePerProjectPerSession_NotMidGestureOrTextEntry`; `everyDismissal_should_MeanNotNow_AndLeaveTheBannerSlotEmpty`; `focus_should_MoveToTheTitleAndReturn`; `classicEditor_should_StayUsableAfterDismissal`; `upgrade_should_StayReachableFromSketchMenuAndNoticesList` | Robolectric | ux.md 14.2 |
| U3 coach walkthrough | `AT/sketch/v2/CoachWalkthroughTest.kt` (Task 3.6.8e; UX-97) | `walkthrough_should_HaveThreeSteps_WithBackNextDone_AndStepIndicator`; `focus_should_MoveToThePopoverThenThePrimaryButtonAndBack`; `announcement_should_BeOneStringPerStep`; `wording_should_FollowTheLastInputDevice_NoKeyNamesOnTouch`; (iteration 10, rows below)  `escAndDone_should_ChangeNothingInTheSketch`; `collapsedStrip_should_PointAtTheOverflow` | Robolectric | ux.md section 22 |
| U nit: two-line label height | `check-plan.py` | `twoLineLabelAtScale2_should_FitTheToastRowAndTheBannerSlot` | Script check | `bannerLabelLine` parameter of the chrome table |
| U nit: label price | `check-plan.py` | `consentLabelUpgrade_should_BePricedInTheComputedBlock` | Script check | why the consent button stays "Start" |
| U nit: toast ids | `check-plan.py` | `bannerSlotParagraph_should_NameEveryToastRowId` | Script check | LayerLocked, CantSatisfy and EngineChange were missing |

## Triad-review repair iteration 10: new and changed test rows
Documents only; designed, not written or run; this iteration is NOT re-reviewed and no review pass is claimed. Design rule as in iteration 9: a decision is a script check or a named test, never prose alone.

| Item | Test (file) | Test names | Level | Replaces / note |
|---|---|---|---|---|
| P1 pre-check stop set | `check-plan.py` | `stopSet_should_ContainTheNamedNodes_4_1_4_6_1_1_6_2_5e_6_2_4e_6_2_5c`; `heldSet_should_BeASubsetOfTheStopSet`; `combinedOutcomesTable_should_HaveBothPreCheckFailRows` | Script check | the HELD line covers Epics 2 and 3 only; the stop set is every node outside the kept closure, the gate closure and the NONUI tooling |
| P1 hold versus gate | owner checklist (Task 0.1.7c) | `continueChoice_should_ReleaseTheHoldOnly_AndLeaveTheA1GateAsWritten` | Manual (owner) | requirements "Combined outcomes" precedence sentence; recorded in `baselines.md` |
| P2 NewProjectsOnly flip | `BT/sketch/v2/QualitySignalsThresholdTest.kt` (Task 6.2.5d) | `flipToNewProjectsOnly_should_UseTheSameThresholdsWindowAndCap_AndNotRequireEveryDeviceReaderCapable`; `flipToOn_should_RequireEveryDeviceReaderCapable`; `flip_should_NeverApplyByItself_AndShowTheEnableConfirmationFirst` | Unit | requirements "Flag and legacy-editor exit criteria", D-67 |
| P3 brand-new projects only | `CT/sketch/v2/FlagModeMatrixTest.kt` (Task 1.4.3j) | `existingEmptyProject_should_CreateClassicWithReason_WhenNewProjectsOnly`; `projectCreatedByTheCreateAction_should_GetAV2Sheet_WhenNewProjectsOnly`; `existingAllV2Project_should_GetAV2Sheet_WhenNewProjectsOnly`; `noProjectAnOlderBuildOpensToday_should_BecomeUnreadable_UnderNewProjectsOnly` | Unit | D-68; the guarantee of requirements A4 NO-GO and the ADR-001 amendment |
| P3 splitter intent | `CT/sketch/v2/SheetSplitterTest.kt` (Task 2.2.4a) | `split_should_CreateAV2Sheet_UnderNewProjectsOnly_WithoutConsultingTheNewSheetRule` | Integration | plan Story 2.2.4 "Mode intent" |
| P nit: metric 8 caveat | `CT/sketch/v2/SketchLogTest.kt` (Task 6.2.3b) | `adoption_should_NotCountClassicSketchesInExistingProjects_UnderNewProjectsOnly_AndLabelTheResult` | Pure | requirements metric 8 |
| E1 ConversionReady list | `BT/sketch/v2/ConversionReadyConsistencyTest.kt` (Task 1.4.3c) | `registrationList_should_BeReadFromAllBusinessTests_AndEachClassLoadByName_WithoutReadingABuildFile` | Unit | replaces the commonTest `BUILD.bazel` read, absent from the Bazel sandbox |
| E2 Bazel registration | `AllBusinessTestsCompletenessTest` (existing guard) and `check-plan.py` file lists | `bannerCandidateTest_should_BeInBusinessCommonTestsSrcsAndAllBusinessTests` (Task 3.6.4f); `chromeBudgetPolicyTest_should_BeInBusinessCommonTestsSrcsAndAllBusinessTests` (Task 3.1.4e) | Script check plus existing guard | both consume `:sketch_chrome_fixture` through `business_common_tests` |
| E nit: wave widths | `check-plan.py` | `nodesPerWaveSentence_should_EqualTheComputedWidths`; `retiredWaveWidthClaim_should_NotAppear` | Script check | replaces the earlier fixed agent-width claim |
| E nit: prose edge table | `check-plan.py` | `proseEdgeTable_should_NotClaimADependencyTheEdgeBlockLacks` (plain id-list rows; planted wrong edge flagged) | Script check | the table is narrative; rows with prose caveats are skipped and not counted as checked |
| E nit: ADR wording | `check-plan.py` | `adrFlagNames_should_UseCanonicalSpelling_AndNameTheRequiredTerms`; `retiredFlagPhrases_should_NotAppear` (planted errors flagged) | Script check | V2Mode, ConversionOffered, ConversionReady, NewProjectsOnly, NewSketchProject |
| U1 banner variant | `AT/sketch/v2/ConsentCopyTest.kt`; `AT/sketch/v2/ReadOnlyUseNewTest.kt` (Task 1.4.6c) | `readOnlyUseNewVariant_should_SayReopeningUnlocksEditingThisSketch_AndContainWholeProjectAlreadyTrue`; `readOnlyUseNewVariant_should_NotContainTheNewProjectsOnlySentences` | Robolectric | UX-69, UX-76, UX-103; D-69 |
| U2 walkthrough does not invite acting | `AT/sketch/v2/CoachWalkthroughTest.kt` (Task 3.6.8e) | `stepText_should_NotInstructTheUserToActivateThePointedAtControl_InAnyVariant`; `canvasGesture_should_NotEndTheWalkthrough`; `activatingTheAnchoredControl_should_EndItAndRetireTheCoachMark` | Robolectric | ux.md section 22 |
| U3 walkthrough accessibility | `AT/sketch/v2/CoachWalkthroughTest.kt` | `step1_should_BeAnnouncedThroughTheFocusedPopoverName_WithNoSeparateLiveString`; `laterSteps_should_MoveFocusToThePrimaryButtonThenOnePoliteString`; `tab_should_MoveThroughThePopoverAndOnWithoutWrapping`; `esc_should_EndAndReturnFocusToOriginOrCanvasWhenOriginRetired`; `popover_should_NeverCoverItsAnchorTheFocusedControlOrItsPrimaryButton_At320DpAndScale2`; `highlightRing_should_HoldThreeToOneInLightDarkAndHighContrast`; `stepText_should_ContainNoScreenPosition`; `keyboardVariant_should_UseOnlyShiftTabAndNeverACanvasOnlyKey` | Robolectric | UX-124, WCAG 1.3.3, 2.1.2, 2.4.11, 1.4.11 |
| U4 mode control | `AT/sketch/v2/FlagModeSettingsTest.kt` (Task 1.4.6h) | `modeControl_should_BeARadiogroupNamedNewEditor_WithDescriptions`; `arrowKeys_should_MoveFocusWithoutSelectingOrOpeningADialog`; `disabledOption_should_StayFocusableWithTheReasonAsDescription`; `confirmation_should_ShowOnlyWhenWideningAboveTheDeviceLevel`; `narrowingAndReselecting_should_ShowNoDialogAndWriteNothing`; `cancel_should_LeaveTheModeAndTheLevelUnchanged`; `bannerUseNew_should_AlwaysShowItsVariant` | Robolectric | UX-76, UX-90; D-71 |
| U5 runtime rule | `AT/sketch/v2/UpgradeChipConsentSheetTest.kt` (Task 1.4.6i) | `measuredLines_should_NotExceedModelLines_ForTheRepresentativeFontSet`; `crossingThreshold_should_KeepAnOpenSheetOpen_AndWithdrawAnOverlongRow`; `widthsBelow320Dp_should_BeDecidedByMeasurementWithoutAClaim` | Robolectric | UX-104, UX-123, UX-126; D-72 |
| U nit: dismissed chip recurrence | `AT/sketch/v2/UpgradeChipConsentSheetTest.kt` | `notNow_should_HideTheChipAndSheetForTheProjectForTheRestOfTheSession_AndReturnInALaterSession` | Robolectric | "later open" now means a later session |

## Triad-review repair iteration 11: new and changed test rows
Documents only; designed, not written or run; this iteration is NOT re-reviewed and no review pass is claimed. Same design rule: a decision is a script check or a named test, never prose alone. Rows of earlier iterations that this one replaces are edited in place (UX-77, the walkthrough keyboard rows, the font-set method name).

| Item | Test (file) | Test names | Level | Replaces / note |
|---|---|---|---|---|
| P1 A6 scope | `check-plan.py` | `a6Row_should_QuoteTheScriptComputedCostOfTheParkedSet`; `taskA6_should_HaveNoEdgeIntoEpic41`; `a6Dependants_should_BeTheSixConstraintUiStoriesPlus_4_2_7c` | Script check | requirements A6 row, "Job 2 validation", "Threshold and why", the DEFER row of the combined table and plan D-38 all quote 1.18M CU / 4.0%; Epic 4.1 is built in every outcome |
| P1 explicit DEFER row | `check-plan.py` | `combinedTable_should_HaveAnExplicitImportExportFirstRowForA6Defer` | Script check | the table keys are unchanged; the new row is covered by the no-empty-cell rule |
| P2 reachable demand | `check-plan.py` | `reachableDemand_should_BeDefinedOnce_AndNamedByMetric8_Task017c_Task012d_AndTheA1Gate` | Script check | requirements "Reachable demand"; the phrase "and again by the A1 go/no-go gate" is retired |
| P2 forms record kinds | owner checklist (Tasks 0.1.7c, 0.1.2d, 6.2.4e) | `preCheckList_should_RecordAProjectKindPerItem_AndCountOnlyReachableOnes`; `a6List_should_RecordAProjectKindPerPart`; `a1GateDiagram_should_BeAReachableItem_OrTheGateIsRecordedUnmeasuredUnderTheCap` | Manual (owner) | `baselines.md`, `a6-job2-demand.md`, `a1-gate.md` forms |
| P nit: A4 cell | `check-plan.py` | `combinedTable_should_HaveNoEmptyCell` (the stop row now reads "read before W1 (moot after a stop)") | Script check | A4 is read before W1, so "not read" was wrong |
| P nit: solver kinds versus flyout entries | `check-plan.py` | `retiredPhrase_14ConstraintKinds_should_NotAppear` | Script check | A6 counts the 14 Constrain flyout entries (UI choices); the ICE row's 10 are solver kinds, INFERRED |
| E1 ConversionReady CT registration | `check-plan.py` | `conversionReadyCommonTestClasses_should_EachBeNamedByARegistrationTaskHeading`; `mutation_DroppingAClassFromTheRegistrationTasks_should_FailTheCheck` | Script check | Task 1.1.5b registers `SheetCodecV2Test` and `SheetCodecV2FuzzTest` in `business_common_tests` and `AllBusinessTests`; `BannerCandidateTest` (3.6.4f) and `ChromeBudgetPolicyTest` (3.1.4e) already have theirs |
| E1 registration is real | `BT/sketch/v2/ConversionReadyConsistencyTest.kt` (Task 1.4.3c) | `registrationList_should_FailWhenACommonTestClassOfTheListIsNotInAllBusinessTests` | Unit | extends the existing row of iteration 10 |
| E2 per-graph flag | `CT/sketch/v2/FlagModeMatrixTest.kt` (Tasks 1.4.3a and 1.4.3j) | `modeFlag_should_RebindOnGraphSwitch_AndNeverShowTheOtherGraphsMode`; `modeFlag_should_WriteOnlyTheReadyGraphsKey`; `set_should_ReturnNoGraph_WhenNoGraphIsReady`; `modeFlag_should_BeDerivedFromReadyGraph_NotFromTheRegistryActiveGraphId` | Unit | graph A `On`, graph B unset: B reads `Off`; a typed fake `Settings` still throws on `getBoolean` of the key |
| E3 bounded and file-classified predicate | `CT/sketch/v2/FlagModeMatrixTest.kt` (Task 1.4.3j) | `allV2ProjectWithHasMore_should_NotQualify_AsANewSketchProject`; `mirrorSaysV2_fileSaysV1_should_NotQualify`; `mirrorSaysV1_fileSaysV2_should_QualifyWhenEverySheetFileIsV2`; `unclassifiableFile_should_NotQualify` | Unit | `SketchSheetRepository.getSheets` is bounded (default 50, `hasMore`); `schema_version` is a hint |
| E3 canonical sentence | `check-plan.py` | `newSketchProjectSentence_should_BeIdenticalInRequirementsPlanAndBothADRs`; `mutation_AnAlteredSentence_should_BeDetected` | Script check | the marked line `**NewSketchProject (canonical sentence):**` |
| E nit: reader-only message | `check-plan.py` | `readerOnlyRelease_should_NameTask624eInItsFailureMessageAndPlanRow` | Script check | plan row "NOTHING from the A1 gate (Task 6.2.4e)" |
| U1 one keyboard rule | `AT/sketch/v2/CoachWalkthroughTest.kt` (Task 3.6.8e) | `tab_should_MoveThroughThePopoverAndOnWithoutWrapping`; `f6FromThePopover_should_LeaveToTheNextRegionAndTheTourContinues`; `homeFromThePopover_should_NotMoveTheView`; `escFromOutsideThePopover_should_EndTheTour`; `escWithAnOpenFlyout_should_CloseTheFlyoutFirst`; `keyboardVariant_should_UseOnlyShiftTabAndNeverACanvasOnlyKey` | Robolectric | ux.md section 22 keyboard contract, aligned with sections 10 and 17 (Tab never traps) |
| U2 step 1 by strip state | `AT/sketch/v2/CoachWalkthroughTest.kt` | `step1_should_HaveFlatAndGroupedTextsInEveryVariant`; `flatText_should_NotMentionAChevron`; `variant_should_BeFixedWhileAStepIsShownAndChooseAgainAtTheNextStep`; `step2_should_NameTheViewButtonsBeforeThePinchOrScroll` | Robolectric | the old coach text in ux.md section 2 is deleted |
| U nit: walkthrough details | `AT/sketch/v2/CoachWalkthroughTest.kt` | `step1AccessibleName_should_EqualTheAnnouncedStringWithNoSeparateDescription`; `blockingNotice_should_StillInterruptDuringTheTour`; `step3_should_PointAtTheOverflowButtonWithTheMenuClosed`; `focusReturn_should_GoToTheOverflowButtonAfterShowTips`; `showTips_should_RetireAVisibleCoachMark` | Robolectric | |
| U3 reason copy | `AT/sketch/v2/UxMigrationAcceptanceTest.kt` (Task 1.4.6e) | `classicReason_should_ComeFromTheOneSketchReasonTextTable_ForEveryCase`; `existingProjectReasons_should_EndWithTheRouteToAllSketchesUpgrade`; `emptiedProject_should_GetTheExistingProjectReason`; `changeAction_should_OpenSettingsNewEditorWithTheModeGroupFocused`; `reasonText_should_NotContainOlderApps` | Robolectric | UX-77; a project is defined once for the user |
| U3 Settings lines | `AT/sketch/v2/FlagModeSettingsTest.kt` (Task 1.4.6h) | `offLine_should_SayNewEditorSketchesOpenReadOnly`; `newProjectsOnlyLine_should_SayProjectsHoldingOnlyNewEditorSketchesStayNew_AndOtherExistingProjectsStayClassic` | Robolectric | UX-76, UX-77 |
| U3 web radiogroup | `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/sketch/v2/FlagModeRadioWasmTest.kt` (Task 1.4.6h) | `arrowKeys_should_MoveFocusWithoutSelectingInARealBrowser`; `spaceOrEnter_should_Choose`; `disabledOption_should_StayInTheArrowOrder` | wasmJs browser test | `:kmp:wasmJsBrowserTest` |
| U4 one classic notice | `AT/sketch/v2/UxMigrationAcceptanceTest.kt` | `ux77_newSheetAsClassic_should_ShowTheClassicEditorToast_WithTheReasonInItsDetails_AndNoOtherNotice`; `createClassicSketchChoice_should_GiveTheReasonYouChoseIt` | Robolectric | replaces `ux77_newSheetAsClassic_should_ExplainReasonOnce`; the one-line notice "[How to change this]" is retired |
| U nit: EngineChange | `AT/sketch/v2/UxMigrationAcceptanceTest.kt` | `engineChange_should_OfferReopen_AndReopenOnlyOnExplicitActivation` | Robolectric | UX-69; the open editor is never swapped by itself |
| U nit: first-write reference form | `AT/sketch/v2/ConsentCopyTest.kt` | `firstWriteDialog_should_UseTheReferenceFormOnlyRightAfterAnEnableConfirmation`; `projectReference_should_ExistOnlyInSketchConsentCopy` | Robolectric | ux.md 14.2; the acknowledgement is stored identically |
| U nit: consent sheet | `AT/sketch/v2/UpgradeChipConsentSheetTest.kt` (Task 1.4.6i) | `measuredLines_should_NotExceedModelLines_ForTheRepresentativeFontSet` | Robolectric | replaces `..._ForSupportedFonts`; the set is the target UI font plus one wider-glyph test font |

## Triad-review repair iteration 12: new and changed test rows
Documents only; designed, not written or run; this iteration is NOT re-reviewed and no review pass is claimed. Same rule: a decision is a script check or a named test. Rows of earlier iterations that this one replaces are edited in place (the walkthrough step-1 rows, the Settings line row, the read-only [Use new] row, the consent-row oracle).

| Item | Test (file) | Test names | Level | Replaces / note |
|---|---|---|---|---|
| P1 reachable kind | `check-plan.py` | `reachableDemand_should_DefineExistingClassicTechnical_AndAllThreeFormsListIt` | Script check | requirements "Reachable demand", D-75, Task 0.1.7c, Task 0.1.2d (D-83) |
| P1 forms count only reachable | owner checklist (Tasks 0.1.7c, 0.1.2d, 6.2.4e) | `preCheckList_should_CountAnExistingClassicTechnicalItemAsElsewhere_UnlessAPlanToRemoveOrMoveItIsRecorded`; `a6List_should_NotCountATechnicalPartInAClassicProject_TowardProceed`; `a1GateDiagram_should_NotBeAnExistingClassicTechnicalItem` | Manual (owner) | `baselines.md`, `a6-job2-demand.md`, `a1-gate.md` forms; replaces the capped-row-only wording of iteration 11 |
| P nit: Task 4.2.7c status | `check-plan.py` | `job2Validation_should_NameTask427cAsBuiltInEveryOutcome_AndOutsideTheA6Figure` | Script check | requirements "Job 2 validation" and A6 DEFER outcome; the 1.18M CU figure is the parked set only |
| P nit: six items | `check-plan.py` | `retiredPhrase_ConstraintUiStories_should_NotAppear` | Script check | "six constraint-UI items" (3 stories and 3 tasks) |
| P nit: fallback hidden set | `check-plan.py` | `geometricOnlyFallback_should_HideTheSameSetInAbortCriteriaAndMetric3` | Script check | tangent, symmetric, angle, Fixed, Radius and Parallel |
| P nit: pre-check stop cost | `check-plan.py` | `preCheckStop_should_QuoteStopEntirelyAndStopAtEnginesCostsFromTheEffortFigures`; `keptNodes_should_NotBeInW1` | Script check | no agent work before the pre-check (0.00M CU), the KEPT closure of 33 nodes (28 outside W1: 9.15M CU; 5 W1 spikes: 1.18M CU) 10.33M CU in all (33.7%), derived by the script, not a hard-coded list; row "stop entirely" in the combined table; `plan-hold` NOTKEPT line mutation-tested |
| E1 ADR-001 cite | `check-plan.py` | n/a (manual line check) | Manual | `ProjectSidecarStore.kt:78-96` (readLocked), verified against the file |
| E2 ConversionReady source-set tags | `check-plan.py` | `conversionReadyList_should_TagEveryClassBtOrCt`; `tag_should_AgreeWithTheClassPathInThePlan`; `untaggedClass_should_FailTheCheck` | Script check | mutation-tested: removing a tag fails |
| E3 predicate cost bound | `CT/sketch/v2/NewSketchProjectPredicateTest.kt` (Task 1.4.3i) | `newSketchProjectPredicate_should_OpenAtMost50FilesAndReadAtMost12800Bytes`; `predicate_should_ReadNoFile_WhenHasMoreOrNoSheets`; `headerVerdict_should_NeverSayV2ForAFileWhoseFullClassificationIsV1` (in `BT/projectbridge/SheetCodecDispatchTest.kt`) | Unit | the 50 files and 12,800 bytes are the SHEET prefixes; the one model open is asserted by `SidecarModelOnlyReadTest` and by the predicate test as exactly one `readModel`; runs on wasmJs too over the same counting fake (on wasm the predicate reads cache slices, D-104, Task 1.4.7b; the 50 prefix reads, budget 10 ms, are timed in `WasmSidecarStoreDurabilityTest`, D-126, not the predicate); the cold milliseconds (50 ms Android at 50 sheets, INFERRED; desktop is not budgeted) are recorded in `owner-verification.md` |
| U1 view cluster stop | `AT/sketch/v2/KeyboardDrawingFlowTest.kt` | `tabOrder_should_IncludeTheViewClusterAsOneStopInEveryLayout`; `f6_should_CycleToTheViewCluster`; `gridAndSnap_should_ToggleFromChordsAndOverflowEntries`; `walkthroughStep2Popover_should_FollowTheViewCluster_AndF6GoesToTheRegionAfterTheAnchor` | Robolectric | UX-117, WCAG 2.1.1, 2.4.3; `check-plan.py` checks the section 10 and 17 text |
| U2 no collapsed strip | `AT/sketch/v2/CoachWalkthroughTest.kt` | `step1_should_HaveFlatAndGroupedTextsInEveryVariant`; `flatText_should_NotMentionAChevron` | Robolectric | replaces the two collapsed-strip names of iteration 11; the variant and its trigger-less text are deleted |
| U3 one Esc order | `AT/sketch/v2/EscOrderIntegrationTest.kt` (Task 6.2.4h, W13: needs Solving 4.1.4, the armed picker 4.2.6b, the first-write dialog 1.4.6a, the walkthrough 3.6.8e and the toast lane 3.1.4c); per-row cases in `KeyboardDrawingFlowTest` (rows 6 and 7), `CoachWalkthroughTest` (row 8) and the owning stories | `escape_should_UndoOneTransientPerPress_InTheTwelveRowOrderOfSection17`; `escape_should_UndoElevenRowsInOrder_WhenTheArmedPickerIsParked`; `escape_should_CommitANonEmptyTextOverlay_AndNeverLoseIt`; `escape_onABlockingBannerAction_should_ReturnFocusToTheCanvas_AndKeepTheBanner`; `keyboardDrawing_firstEsc_should_CancelTheRectangleWithNoCommand_AndStayInCrosshair` | Robolectric | all twelve rows in effect at once; the order is the canonical list parsed from ux.md section 17 by `check-plan.py` |
| U4 toast dismissal | `check-plan.py`; `AT/sketch/v2/ActionChipRowTest.kt` (Task 3.1.4c) | `everyToastRow_should_HaveDismissAsTheLastMoreItem`; `everyPersistentToast_should_BeDismissibleByPointerAndTouch`; `laneOfOne_should_MoveTheDisplayedToastToDiagnostics_WhenANewerOneArrives` | Script check; Robolectric | UX-112, WCAG 2.5.7; the section 1 error row no longer says "or Dismiss" without a path |
| U5 ClassicEditor recurrence | `AT/sketch/v2/UxMigrationAcceptanceTest.kt` (Task 1.4.6e); `AT/sketch/v2/OnboardingPolicyTest.kt` | `classicToast_should_NotShowInModeOff`; `classicToast_should_ShowOncePerProjectPerReason`; `change_should_BeOfferedOnlyWhereSettingsCanChangeTheOutcome_OtherwiseLearnMore`; `change_should_LeaveTheCreatedSketchOpenAndReturnToIt`; `toastRecurrencePolicy_should_MatchTheSection16Row` | Robolectric | UX-77; section 16 toast recurrence row |
| U6 Settings copy | `AT/sketch/v2/FlagModeSettingsTest.kt` (Task 1.4.6h); `AT/sketch/v2/ConsentCopyTest.kt` | `newProjectsOnlyLine_should_SayProjectsHoldingOnlyNewEditorSketchesStayNew_AndOtherExistingProjectsStayClassic`; `newProjectsOnlyConfirmation_should_NotPromiseThatEveryExistingProjectStaysClassic`; `heading_should_SayThisGraphOnThisDevice` | Robolectric | replaces `newProjectsOnlyLine_should_SayASketchAddedToAnExistingProjectIsClassic` |
| U7 chip consequence | `AT/sketch/v2/UpgradeOfferTest.kt`; `AT/sketch/v2/UpgradeChipConsentSheetTest.kt` (Task 1.4.6i) | `chipLine_should_BeUpgradeBlocksOtherDevices_AndDetailsDefineOtherDevices`; `chipAlone_should_LetAParticipantSayItAffectsOtherDevices` (think-aloud task 2, UX-123); `chipRow_should_FitTheClassicHostAt320And360DpAtScales1And2` | Robolectric; manual (think-aloud) | "older apps" retired; the consent-row matrix is recomputed (320 dp 2 / 3 / 3 / 4 / 5+) |
| U nit: read-only banner | `AT/sketch/v2/ReadOnlyUseNewTest.kt` | `readOnlySummary_should_BeNeutralWhetherTheEditorIsOffOrTheBuildCannotReadIt`; `disabledUseNew_should_ShowItsReasonInTheHelpPopupAndDetails`; `turnOnAndReopen_should_StoreTheModeAndReopenInOneAction` | Robolectric | replaces `afterTurnOn_should_KeepReadOnlyUntilReopen_AndShowEngineChangeToast` for the banner path; the toast stays for Settings changes |
| U nit: first-write dialog | `AT/sketch/v2/UxMigrationAcceptanceTest.kt` | `firstWriteDialog_should_FocusTheWarningTextFirst_ThenTheCheckbox_AndShowTheDisabledButtonReasonAsText` | Robolectric | both variants |

## Triad-review repair iteration 13: new and changed test rows
Documents only; designed, not written or run; this iteration is NOT re-reviewed and no review pass is claimed. Rule of this iteration: every `check-plan.py` check added or touched parses the VALUE (a number, a row list, a kind list) and compares it with the computed or canonical one, and each was mutation-tested on a temporary copy (list in the iteration report).

| Item | Test (file) | Test names | Level | Replaces / note |
|---|---|---|---|---|
| P1 KEPT cost of "stop at the pure engines" | `check-plan.py` | `keptSet_should_BeTheClosureOfThePlanHoldKeptLine_OutsideW1_LessNotkept`; `quotedKeptFigures_should_EqualTheDerivedOnes`; `notkeptNode_should_NotBeNamedByAnEdgeOfAKeptNode` | Script check | replaces the hard-coded 15-node list: 28 nodes outside W1 (6185k raw, 9.15M CU) plus the 5 W1 spikes of the closure (795k raw, 1.18M CU), 10.33M CU (33.7%); D-94, D-107 |
| P nit: A6 structural DEFER; PIVOT once; flag-exit n | owner records (`a6-job2-demand.md`, `a1-gate.md`) | `defer_should_RecordNoDemandOrStructural`; `pivot_should_BeReversedByARecordedChangedResult_NotByAMandatoryRepeat` | Manual (owner) | requirements A6 and A1 gate; D-92, D-93; "insufficient data" says the flip is the owner's recorded decision |
| E1/E2 probe key, bound, scope | `CT/sketch/v2/NewSketchProjectPredicateTest.kt` (Task 1.4.3i); `BT/projectbridge/HeaderProbeGoldenTest.kt` (Task 1.1.5c) | `classifyHeader_should_ClassifyTheRealBytesOfAV1SheetAsV1_AndAV2SheetAsV2`; `v1OnlyProject_should_StopAfterOneOpenAndAtMost256Bytes`; `mixedProject_should_StopAtTheFirstNonV2Sheet`; `shortOrTornHeader_should_BeUndecidedAndDisqualify`; `projectWith51To500Sheets_should_NeverQualify`; `unreconciledGitPulledSheet_should_BeInvisible`; `probe_should_NeverRunWhenV2StorageIsUnavailable` | Unit | `check-plan.py` parses `HEADER_PROBE_BYTES`, the opens, the 50 x 256 = 12,800 product, the 51-500 scope against `SidecarReconciler.MAX_SHEETS` and the file key `schema` against `SidecarFileState` and `ProjectModelJson` |
| E2 mismatch fixtures | `CT/sketch/v2/NewSketchProjectPredicateTest.kt` (Task 1.4.3i) | `mirror2_fileV1_should_NotQualify`; `mirror1_fileV2_should_Qualify`; `sheetRefNamesAbsentV2File_should_NotQualify`; `bothFilesPresent_should_ProbeTheSheetRefNamedOne` | Unit | counting fake file system |
| E3 prefix read | `JT/platform/FilePrefixReadTest.kt` (Task 1.4.3g) | `readFilePrefix_should_ReturnAllOfAShortFile`; `readFilePrefix_should_ReturnExactlyMaxBytesOfALongOne`; `readFilePrefix_should_ReturnNullForAnAbsentFile`; `readFilePrefix_should_NeverReadMoreThanMaxBytesOnJvm` | JVM | the Android SAF override is compiled and exercised through the on-device list of `owner-verification.md`; wasmJs gets its override in Task 1.4.7b (`PlatformFileSystemBytesTest`), iOS has none and the predicate never runs there (D-104); registered in `AllJvmTests` |
| E4 all-rows Esc order | `AT/sketch/v2/EscOrderIntegrationTest.kt` (Task 6.2.4h, W13) | see the U3 row above | Robolectric | moved from Story 3.6.3 (W7-W9) because Solving (4.1.4), the armed picker (4.2.6b), the first-write dialog (1.4.6a), the walkthrough (3.6.8e) and the toast lane (3.1.4c) do not exist earlier; registered in `AllAndroidUnitTests`; `check-plan.py` parses the 12 rows of ux.md section 17 against the canonical list and checks the node's edges and wave |
| E5 persistence owners | `AT/sketch/v2/ClassicNoticeLedgerTest.kt` (Task 1.4.6e); `AT/sketch/v2/OnboardingPolicyTest.kt` (Task 3.6.7c) | `ledger_should_SurviveAStoreRecreation`; `ledger_should_EvictTheOldestBeyond200Entries`; `ledger_should_DropEntriesOfADeletedProject`; `ledger_should_BeRemovedWithTheGraph`; `onboardingCounters_should_SurviveARecreatedPolicy_AndUseOneKeyPerItem` | Robolectric / unit | `check-plan.py` compares the 200-entry bound of the story with D-96 and the (graph, project, reason) scope tuple in four places |
| U1 text overlay Esc | `AT/sketch/v2/TextOverlayTest.kt`; `EscOrderIntegrationTest` | `escape_should_CommitANonEmptyTextOverlay_ExactlyLikeDone`; `escape_should_RemoveAnEmptyNewTextOverlay` | Robolectric | ux.md section 6 step 2 and section 10 step 4 agree with Esc row 3 |
| U2 Esc rows 6/7 and Blocking banner | `KeyboardDrawingFlowTest`; `EscOrderIntegrationTest` | `keyboardDrawing_firstEsc_should_CancelTheRectangleWithNoCommand_AndStayInCrosshair`; `escape_onABlockingBannerAction_should_ReturnFocusToTheCanvas_AndKeepTheBanner` | Robolectric | mid-draw is row 6, Crosshair row 7 |
| U3 ClassicEditorFixed label | `check-plan.py`; `UxMigrationAcceptanceTest` | `toastTable_should_HaveClassicEditorFixedWithLearnMore_AndNoDetailsInMore`; `classicToast_should_ShowLearnMoreWhereSettingsCannotChangeTheOutcome` | Script check / Robolectric | width, uniqueness and accessible name are checked by the shared toast rules |
| U4 no offer for a refused sketch | `AT/sketch/v2/UpgradeOfferTest.kt` | `refusedSheet_should_NotOfferTheChip_AndDisableTheMenuEntryWithTheReason`; `refusalAppearingBeforeStart_should_WithdrawTheChip` | Robolectric | D-98 |
| U5 technical-data reason | `AT/sketch/v2/UxMigrationAcceptanceTest.kt` | `classicToast_technicalDataReason_should_UseTheNewSheetFragment_NotTheConversionRefusalSentence` | Robolectric | fragment `TechnicalDataNewSheet` |
| U6 discovery at mode Off | `AT/sketch/v2/FlagModeSettingsTest.kt` | `tryTheNewEditor_should_BePresentOnlyInOff_OpenTheModeGroupAndStoreNothing`; `tryTheNewEditor_should_BeAbsentInNewProjectsOnlyOnAndWhenTheBuildOrPlatformIsCapped` | Robolectric | D-97 |
| U7 backup export | `AT/sketch/v2/NewerSchemaScreenTest.kt` | `exportLastBackup_should_BeHiddenWhenNoBackupExists` | Robolectric | |
| U8 consent copy | `AT/sketch/v2/ConsentCopyTest.kt` | `firstWriteDialog_should_NotPromiseAnUpdateMessage_AndSayTheProjectWontOpen` | Robolectric | D-103; the exact message depends on release 6.2.5e |
| U9 real-valued consent row | `check-plan.py` (chrome); `AT/sketch/v2/UpgradeChipConsentSheetTest.kt` | `charsPerLine_should_BeFloorOfWidthOverCharDpTimesScale`; `consentRow_should_ReplaceTheRowFromScale1_11At320dpAnd1_52At360dp`; `sheet_should_AutoOpenAtMostOncePerProjectPerDay`; `sheetClose_should_ReturnFocusToTheCanvasWhenNoControlHeldIt` | Script check / Robolectric | first scale 1.11 (320 dp) and 1.52 (360 dp), recomputed with real-valued per-character width |
| U nits | `AT/sketch/v2/KeyboardMapTest.kt`; `ReadOnlyUseNewTest.kt`; think-aloud protocol (Story 6.2.8) | `gridSnapChords_should_FallBackToThePhysicalKey_AndNeverTreatCtrlAltAsAlt`; `readOnlyBanner_should_HaveNoPrimaryWhenTheBuildCannotTurnTheModeOn`; one session at 320 x 480 dp and font scale 2.0 | Robolectric / manual | ux.md section 17 skip-link wording reconciled with section 10 |

## Triad-review repair iteration 14: new and changed test rows
Documents only; designed, not written or run; this iteration is NOT re-reviewed and no review pass is claimed. Rule of this iteration: every `check-plan.py` check added or touched parses a number, a list or a fact of the code and compares it with the computed one; each was mutation-tested on a temporary copy (the caught-or-missed list is in the iteration report).

| Item | Test (file) | Test names | Level | Replaces / note |
|---|---|---|---|---|
| E1 header-first key order | `CT/sketch/v2/CanonicalJsonTest.kt` (Task 1.1.2a); `CT/sketch/v2/SheetCodecV2Test.kt` (Task 1.1.5a); `BT/projectbridge/HeaderProbeGoldenTest.kt` (Task 1.1.5c); `check-plan.py` | `headerKeys_should_LeadAndTheRestSorted`; `encode_should_PutSchemaWithinTheFirst64Bytes_OfARealSheet`; `classifyHeader_should_ClassifyTheRealBytesOfAV1SheetAsV1_AndAV2SheetAsV2` | Unit / Script check | ADR-001 item 5, D-105; the script builds a v1 layout (pretty-printed) and a v2 layout (header first, 100 elements) and a fully sorted one, and computes the `"schema"` offsets (4, 1 and 6940), checks them against `SCHEMA_WINDOW` = 64 and the 256-byte prefix, and against the offsets and literals the plan and ADR-001 quote |
| E2 model-only read | `BT/projectbridge/SidecarModelOnlyReadTest.kt` (Task 1.4.3h); `check-plan.py` | `readModel_should_OpenOneFileAndNeverListOrReadASheet_EvenWithFiveHundredSheetsInTheModel`; `readModel_should_BePresentAbsentOrCorrupt_AsReadDoes`; `isAvailable_should_BeFalseWhenTemporaryRenameWritesAreUnsupported` | Unit | D-106; the script checks the model file name against `SidecarCodec.MODEL_PATH`, that `readLocked` still lists and reads every sheet, that 1.4.3h is an S task of Story 1.4.3 after Story 1.4.2, and that no two nodes that edit `ProjectSidecarStore.kt` share a wave |
| E3 platform capability | `CT/sketch/v2/FlagModeMatrixTest.kt` (Task 1.4.3j); `AT/sketch/v2/FlagModeSettingsTest.kt`; `CT/sketch/v2/EditorRouterTest.kt` (Task 1.4.3d); `BT/projectbridge/SidecarModelOnlyReadTest.kt` (Task 1.4.3h); `check-plan.py` | `effectiveMode_should_BeOffWhereV2StorageIsUnavailable_ForEveryStoredMode`; `predicate_should_ReadNothing_WhenV2StorageIsUnavailable`; `nullStore_should_BeUnavailable_ForEveryStoredMode`; `v2StorageAvailable_should_BeStoreNonNullAndIsAvailable`; `modeControl_should_BeDisabledWithTheCaseReason_WhenV2StorageIsUnavailable`; `v2Sheet_should_OpenAsNoEditorWithTheReason_WhenV2StorageIsUnavailable`; `excalidrawImport_should_BeDisabledWithTheCaseReason_WhenV2StorageIsUnavailable` | Unit / Robolectric | D-104 (owner decision), D-112, D-116, UX-133; platform cells: desktop=true, android=true, wasmJs-opfs=true, wasmJs-no-opfs=false, iOS=false, store-null=false; the script derives today's facts from `supportsTempRenameWrites` in the four `PlatformFileSystem` classes (false on wasmJs and iOS), requires a task that lists the wasm `PlatformFileSystem.kt` before it splits the wasm cell in two, and checks every cited line of every document against the code; the iOS cell is a fake-store cell that proves the logic on the other targets (iOS is compile-only); Option W cost is script-computed |
| E4 prefix read default | `JT/platform/FilePrefixReadTest.kt` (Task 1.4.3g) | `defaultReadFilePrefix_should_ReturnNull_WhenReadFileBytesIsUnsupported`; `defaultReadFilePrefix_should_TruncateWhenReadFileBytesWorks`; `jvmReadFilePrefix_should_GoThroughValidatePath` | JVM | D-106; the script checks that the `FileSystem.readFileBytes` default still throws, that `validatePath` is protected on `JvmFileSystemBase`, that the desktop class extends it, that the task lists `JvmFileSystemBase.kt` and no wasm file, and that every listed non-test path exists |
| E5 D-62, D-85, D-100 | `check-plan.py` | `d62_should_QuoteTheComputedStopClosureAndNameTheNotkeptNodes`; `d85_should_QuoteTheNumberedEscRowsParsedFromUx`; `d100_should_QuoteTheParsedRowNumbersOfMidDrawAndCrosshair`; `reduced6_2_4h_should_NameTheParsedRowsOfTheArmedPickerAndTheDimensionField` | Script check | the numbers come from the computed closure and the parsed rows, so swapping rows 6 and 7, changing 6130k or dropping a NOTKEPT name fails |
| P1 pre-check in OWNER0 | `check-plan.py` | `precheck_should_BeWaveZero_AndEveryOtherNodeShouldHaveAPathFromIt`; `everyW1Node_should_HaveAnEdgeFromThePrecheck`; `stopCosts_should_BeQuotedFromTheComputedClosure` | Script check | D-107; "stop entirely" starts no agent work (0.00M CU), "stop at the pure engines" is the 33-node closure; the old claim held only if W2 was held (52 nodes at W2 and later, 13.38M CU, had no path) |
| P2 fourth gate outcome | `check-plan.py` | `a1Precedence_should_ListFourOutcomes_AndEveryOutcomeShouldHaveACombinedRow`; `unmeasuredRow_should_QuoteTheComputedSunkAndGatedCosts` | Script check | D-108; requirements, plan and the table agree on UNMEASURED, PIVOT, ADJUST, PROCEED |
| P3 stop rule and merges | `check-plan.py` | `mergesNodes_should_LieInsideTheKeptClosure`; `stopRule_should_ListThreeUsesAndQuoteTheClosureShare` | Script check | D-109; `MERGES: 0.1.1b 1.1.3 1.4.1` |
| P nits | `check-plan.py` | `metric5Baseline_should_CountTheOperationsThatExist`; `sizeLine_should_QuoteTheComputedTotal`; `probeScope_should_AnchorToDefaultSheetLimit` | Script check | 3 of 7 operations exist (create, lock, hide) and 4 do not; Size quotes 30.65M CU; the 50 comes from `SketchSheetRepository.DEFAULT_SHEET_LIMIT` |
| U1 scenario 3 | `CT/sketch/v2/EditorRouterTest.kt`; `check-plan.py` | `readerOnlyBuild_should_OpenAV2SheetReadOnlyWithTheReadOnlyBanner_NotTheUpdateScreen`; `newerSchemaScreen_should_ShowThreeActionsAndNoInternalTerm` | Robolectric / Script check | D-103 corrected; the script compares the actions of the dialog and the routing row and scans the dialog for internal terms |
| U2 consent sheet cadence | `AT/sketch/v2/UpgradeChipConsentSheetTest.kt`; `check-plan.py` | `sheet_should_AutoOpenAtMostOncePerProjectPerDay_AndNotAgainInTheSessionAfterADismissal`; `cadenceUnits_should_AllBeDay_AcrossEveryStatement` | Robolectric / Script check | D-102 wins; UX-104, D-64, ux.md 14.2 and section 16 row 12 corrected |
| U3 discovery scope | `AT/sketch/v2/FlagModeSettingsTest.kt`; `check-plan.py` | `tryTheNewEditor_should_BePresentOnlyInOff_OpenTheModeGroupAndStoreNothing`; `discoveryItemModes_should_BeOffEverywhereItIsStated` | Robolectric / Script check | D-110, UX-129 |
| UX-130 chip suppression | `AT/sketch/v2/UpgradeOfferTest.kt` | `refusedSheet_should_NotOfferTheChip_AndDisableTheMenuEntryWithTheReason`; `refusalAppearingBeforeStart_should_WithdrawTheChip` | Robolectric | D-98 (rows above kept) |
| UX-131 reason precedence | `AT/sketch/v2/UxMigrationAcceptanceTest.kt`; `check-plan.py` | `classicReason_should_NameOnlyTheFirstApplicableRow_InPrecedenceOrder`; `classicToast_should_BeClassicEditorFixedWhereSettingsCannotChangeTheOutcome`; `reasonIds_should_MatchBetweenTheUxTableAndThePlan` | Robolectric / Script check | D-111 |
| UX-132 backup export | `AT/sketch/v2/NewerSchemaScreenTest.kt` | `exportLastBackup_should_BeHiddenWhenNoBackupExists`; `exportLastBackup_should_SaveTheNewestBackupWithoutWritingTheProject` | Robolectric | D-103 |
| UX-133 platform without file storage | see row E3 | `modeControl_should_BeDisabledWithTheCaseReason_WhenV2StorageIsUnavailable`; `v2Sheet_should_OpenAsNoEditorWithTheReason_WhenV2StorageIsUnavailable` | Robolectric | D-104, D-116 |
| U nits | `AT/sketch/v2/EscOrderIntegrationTest.kt`; `ReadOnlyBannerTest.kt`; think-aloud protocol (Story 6.2.8) | `escape_onAFocusedUpgradeChipAction_should_MeanNotNow`; `escape_onAConstraintConflictCard_should_RemoveTheNewConstraint`; `readOnlyBanner_should_ListRestoreOriginalOnlyWhereRestoreAndABackupExist`; the nag observation of the daily auto-opening sheet | Robolectric / manual | ux.md 14.1 On dialog title now names the choice ("Turn on the new editor for all sketches?"); the chip test names say "OtherDevices" |

## Triad-review repair iteration 15: new and changed test rows

Documents only; designed, not written or run; this iteration is NOT re-reviewed and no review pass is claimed. Design rule: every check added or touched asserts a computed or parsed value (a number, an ordered list, a code line), never a wording fragment, and each was mutation-tested on a temp copy (the iteration report lists caught and missed). Option W (the owner decision D-104) added Story 0.1.8 and Story 1.4.7 (their test rows are in the iteration 16 section below, where Story 1.4.7 was split and re-costed); the null store, the platform axis, the per-target solver tier and the no-editor screen are new rows below.

| Req | Test class / check | Test names | Level | Notes |
|---|---|---|---|---|
| Web import | `AT/sketch/v2/ImportFlowTest.kt`; `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/ui/sketch/v2/CanvasFileDropTest.kt` (Task 5.1.5b) | `canvasDrop_should_StartTheImportFlow_OnWeb`; `dropAndPaste_should_ShowTheStorageReasonAndCreateNothing_WhenV2StorageIsUnavailable` | Robolectric / `:kmp:wasmJsBrowserTest` | E3 stale web text reconciled; BUG-004 is open, so the handler is canvas-local; UX-135 |
| UX-134 recovery actions | `AT/sketch/v2/NoEditorAccessibilityTest.kt` (Task 1.4.6j) | `storageUnavailableScreen_should_OfferCopyDetailsAndHowToOpenIt_AndSayNothingCanBeExported`; `copyDetails_should_CopyNameProjectSchemaPlatformAndReasonIdButNoSketchContent`; `newerSchemaScreen_should_GainCopyDetails` | Robolectric | UX-134; `check-plan.py` compares the two action lists with the ux.md 14.7 table |
| UX-135 no storage: import, embed, copy | `AT/sketch/v2/ImportFlowTest.kt`; `JT/sketch/v2/EmbedDispatchTest.kt`; `BT/merge/SketchBundleCopyTest.kt` | `v2Embed_should_BeALabelledPlaceholderWithOpenPage_WhenV2StorageIsUnavailable`; `v2CopySource_should_BeRefusedWithTheReason_WhenV2StorageIsUnavailable` | Unit / Robolectric | UX-135, D-118 |
| UX-136 routing precedence | `CT/sketch/v2/EditorRouterTest.kt` (Task 1.4.3d); `check-plan.py` | `offSchema2_should_BeNoEditor_NotReadOnly_WhenV2StorageIsUnavailable`; `storageUnavailableRow_should_BeFirstInTheUxRoutingTable` | Unit / Script check | the first row of the ux.md routing table is parsed |
| UX-137 reasons and the rest of Settings | `AT/sketch/v2/FlagModeSettingsTest.kt` (Task 1.4.6h) | `disabledOptionReason_should_PreferPlatformOverBuildCapped`; `restoreOriginalDeleteBackupsAndEditorSettings_should_BeShownDisabledWithTheReason_WhenV2StorageIsUnavailable`; `offRadioLine_should_NotClaimReadOnlyNewEditorSketches_WhenV2StorageIsUnavailable` | Robolectric | the reason order is parsed from the ux.md reason table and the plan sentence |
| UX-138 no-editor accessibility | `AT/sketch/v2/NoEditorAccessibilityTest.kt` (Task 1.4.6j) | `noEditorScreen_should_FocusTheLevel1HeadingOnArrival`; `noEditorScreen_should_BeARegionNamedByItsHeading_NotADialog`; `tabOrder_should_FollowTheTable`; `escAndBack_should_Close_AndReturnFocusToTheOpener` | Robolectric | WCAG 2.4.3, 4.1.2, 1.3.1, 4.1.3 |
| P1 platform axis | `check-plan.py` | `platformAxis_should_BeOneListRepeatedByEveryForm`; `metric8_should_CutByPlatformAndTieToV2StorageAvailable` | Script check | D-113; the four platforms are parsed from "Reachable demand" and required in Task 0.1.7c, Task 0.1.2d and the A1 gate criterion |
| P2 platform framing | `check-plan.py` | `retiredPlatformPhrases_should_BeAbsentFromEveryDocument`; `whereEachMetricIsMeasured_should_ParseMetrics1To8_AndKeepIosCompileOnly`; `oq5AndAdr004_should_RecordWebStorageAndIosCompileOnly` | Script check | D-113 |
| P4 per-target solver | `check-plan.py` | `solverTier_should_BeDecidedPerTarget_InRequirementsPlanAndAdr003`; `tieBreak_should_SayATargetNeverInheritsAnotherTargetsMiss` | Script check | D-114 |
| P nits | `check-plan.py` | `precheckFail_should_BeRecordedNoDemandOrStructural_InThreePlaces`; `abandonedFigure_should_ExcludeTheKeptSpikes_AndBeComputed`; `importExportPivot_should_SayMetric8CannotShowSuccess`; `a1Outcomes_should_BeNamedInPrecedenceInTheGlossaryTheRowAndTheHeading`; `a6ShareOfTotal_should_BeTheComputedPercentageEverywhere` | Script check | D-115 |
| E1 null store | `CT/sketch/v2/FlagModeMatrixTest.kt` (Task 1.4.3j); `BT/projectbridge/SidecarModelOnlyReadTest.kt` (Task 1.4.3h); `check-plan.py` | `nullStore_should_BeUnavailable_ForEveryStoredMode`; `v2StorageAvailable_should_BeStoreNonNullAndIsAvailable`; `nullStoreCitation_should_CoverTheSidecarStoreProviderAndTheIsDemoTest` | Unit / Script check | D-116; `GraphContentViewModelSetup.kt:221-227` is checked against the code |
| E2 header probe owner and split | `check-plan.py` | `classifyHeader_should_BeNamedByExactlyOneTaskHeadingWhoseFileListHoldsSheetCodec`; `splitTasks_should_EachHaveAFileLineOfAtMostFiveFiles`; `commonTestFiles_should_HaveBazelSrcsAndRegistrationInTheSameTask` | Script check | D-116; Tasks 1.1.5c, 1.4.3d, 1.4.3i, 1.4.3j |
| E4 script false passes | `check-plan.py` | `multipliers_should_AllEqualTheComputedOnes`; `adr001Offsets_should_EqualTheComputedLayouts`; `codeCitations_should_NameTheirSymbolOnTheirFirstLines_InEveryDocument`; `taskHeadingWaves_should_EqualTheWaveOfTheNamedNode`; `dIdList_should_CiteOnlyDefaultsThatNameTheSubject` | Script check | each mutation-tested on a temp copy: the multiplier 1.75 changed to 1.5 (plan) and to 1.7 (requirements), ADR-001 offset 4 to 3, requirements `:622` to `:623`, Task 1.4.3h `(W8` to `(W7`, the D-id list `D-106` to `D-107` |
| Android storage items | `owner-verification.md` (Task 6.2.4c) | the `saf://` `readFilePrefix` returns at most 256 bytes; the SAF TEMP_RENAME durability caveat pass or fail | Manual (owner) | `supportsAtomicReplace` is false on SAF |
| UX nits | `check-plan.py` | `toastSuppression_should_MeanTheStoredModeIsOff_InThreePlaces`; `zoom400Deviation_should_BeRecordedAsAcceptedWithWcag1410` | Script check | D-117, D-118 |

## Triad-review repair iteration 16: new and changed test rows

Documents only; designed, not written or run; this iteration is NOT re-reviewed and no review pass is claimed. It repairs the engineering gaps of Option W (the durability contract, write ordering, temp and rename side effects, the stale-temp sweep, two tabs, the preload fact, the verdict inputs, the 50-prefix budget), wires the whole-option abandonment, adds the web UX, and re-costs Option W from the actual task list (Story 1.4.7 had 12 tasks, a to l, at that iteration, each of at most five files; iteration 17 adds Task 1.4.7m). Design rule as before: every check added or touched asserts a computed or parsed value and was mutation-tested on a temp copy.

| Req | Test class / check | Test names | Level | Notes |
|---|---|---|---|---|
| Option W verdict and status | `CT/platform/OpfsStorageVerdictTest.kt` (Task 1.4.7a) | `decide_should_MatchTheTruthTable_ForAll32CombinationsOfTheFiveFacts`; `decide_should_ApplyTheReasonsInTheStatedOrder`; `notProbed_should_BeUnavailable`; `status_should_BeCheckingThenReadyOrUnavailable_ForEveryVerdictAndPreloadStatePair`; `persistence_should_NeverChangeTheVerdict` | Unit (every target) | D-124, D-125; the truth table is written independently of `decide` and its row counts are script-recomputed |
| Option W primitives | `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/OpfsAtomicReplaceTest.kt` (Task 1.4.7e) | `replace_should_ShowTheNewBytesOnlyAfterClose`; `replace_should_LeaveTheOldBytes_WhenTheWritableIsAborted`; `moveOverExisting_should_ReplaceTheDestination`; `copyVerify_should_RecopyFromTheTemp_AndKeepTheTemp_WhenTheDestinationReadBackDiffers`; `sliceRead_should_ReturnExactly256Bytes`; `quotaExceeded_should_MapToTheTypedError` | `:kmp:wasmJsBrowserTest` (Chrome) | browser-API facts INFERRED until Spike 0.1.8 has a row per browser |
| Option W ordered queue and durable ack | `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/OpfsWriteQueueTest.kt` (Task 1.4.7f) | `replaceAfterThreeWrites_should_RunLast_WhenTheFakeCompletesOutOfOrder`; `tail_should_BeTheLatestOperation_AndBeClearedOnlyByIt`; `awaitDurable_should_ReturnTheTypedFailure_OfAnEarlierOperation`; `awaitDurable_should_ReturnRight_WhenNothingIsPending`; `beforeUnloadGuard_should_BeOn_WhileAnyPathIsUnpersisted` | `:kmp:wasmJsBrowserTest` | D-119, D-120 |
| Option W file system | `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/PlatformFileSystemBytesTest.kt` (Task 1.4.7b) | `readFileBytes_should_ReturnTheBytesCacheOrTheUtf8TextCache_AndNullWhenAbsent`; `readFilePrefix_should_TakeAtMostMaxBytesCharactersBeforeEncoding`; `readFilePrefix_should_ReturnAtMostMaxBytes`; `renameFile_should_MoveByteAndTextEntries`; `fileExistsListFilesListDirectoriesAndDirectoryExists_should_SeeByteEntries`; `preload_should_ReadNonJsonSidecarFilesAsBytes` | `:kmp:wasmJsBrowserTest` | D-126; restores the wasm `readFilePrefix` of D-91 |
| Option W temp, replace and rename | `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/PlatformFileSystemDirtyHostTest.kt`; `BT/platform/AtomicFileWriterTest.kt` (Task 1.4.7g) | `tempWrite_should_NotBeDirty_AndNotBeHostWritten`; `replace_should_MarkTheTargetDirtyOnce_DropTheTempAndEvictTheStaleCacheEntry`; `rename_should_NotRecordADeleteForTheSource_AndHostWriteTheTarget`; `abandonedTemp_should_LeaveNoDirtyEntry`; `atomicFileWriter_should_WriteItsTempThroughWriteTempBytes` | `:kmp:wasmJsBrowserTest` / Unit | D-121; a fake host directory records the payloads |
| Option W stale temp | `BT/platform/StaleTempSweeperTest.kt` (Task 1.4.7h) | `nullTimeAndOldName_should_BeRemoved`; `nullTimeAndYoungName_should_BeKept`; `nonUuidName_should_BeSkipped`; `realTime_should_StillWin` | Unit | D-122; the claim "removed on the next write" is retired |
| Option W two tabs | `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/TwoTabSketchLockTest.kt` (Task 1.4.7i; the port, its always-held default and its consumers are Tasks 1.4.3m, 1.4.3n and 1.4.3o, rows of iteration 17) | `secondInstance_should_BeHeldElsewhere_AndItsStoreWriteReturnFailedWriterLeaseNotHeld`; `readyGraphChange_should_ReleaseTheLeaseAndLetTheOtherInstanceEdit` (two lease instances in one page: defence in depth, a persistent web graph never switches); `afterTheFirstReleases_should_ReloadTheSidecarRootBeforeWriting`; `reacquire_should_ReloadTheSidecarRootAndGrowTheEpoch`; `staleCache_should_NeverOverwriteANewerFile` | `:kmp:wasmJsBrowserTest` (two instances in one page) / Unit | D-123; real two-tab behaviour is an owner step of Task 1.4.7d |
| Option W preload fact | `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/OpfsPreloadStateTest.kt` (Task 1.4.7j) | `missingGraphDirectory_should_BeOk`; `unreadableSidecarFile_should_BeFailed`; `failedPreload_should_MakeStorageUnavailable_AndNeverLetThePredicateRun`; `temporaryGraph_should_BeReadySessionOnly`; `statusIsChecking_should_TimeOutAfterTenSeconds` | `:kmp:wasmJsBrowserTest` | D-124; UX-141 |
| Option W saver states | `CT/sketch/v2/SheetSaverDurabilityTest.kt` (Task 1.4.7k) | `saved_should_AppearOnlyAfterAwaitPersisted`; `lateDurability_should_ShowSavingUntilItResolves`; `quotaFailure_should_RaiseSaveFailedAndKeepTheScene`; `neverResolving_should_NotReportSaved`; `hidden_should_SkipTheDebounce` | Unit | UX-140, D-119 |
| Option W web UX policy | `CT/sketch/v2/WebStorageUxTest.kt` (Task 1.4.7l) | `everyUnavailableReason_should_HaveItsOwnText`; `noOpfsOnIosWebKit_should_NameSafariEngine_AndNoBrowser`; `checking_should_NeverMapToTheNoEditorScreen`; `temporaryGraph_should_GetTheSessionOnlyLine`; `persistDenied_should_ChangeNoAvailability` | Unit | UX-139, UX-141, UX-143; the reason ids are parsed from the ux.md table by `check-plan.py` |
| Option W durability | `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/WasmSidecarStoreDurabilityTest.kt` (Task 1.4.7c) | `write_should_SurviveDroppingTheCachesAndPreloadingAgainAfterAwaitDurable`; `interruptedReplace_should_ReadTheOldBytes`; `atomicFileWriter_should_BeInAtomicReplaceMode`; `fiftyPrefixReadsOverTwoMegabyteSheets_should_FitTenMilliseconds` | `:kmp:wasmJsBrowserTest` (Chrome) | D-126; times the prefix reads, not the predicate (the predicate is timed in Task 1.4.3i) |
| Option W spike and pass | Task 0.1.8a harness `OpfsAtomicWriteSpikeTest.kt`; Tasks 0.1.8b and 1.4.7d (owner) | one row per browser in `research/opfs-spike.md` (criteria a to d gating, e recorded, replace path, whether `persist()` prompts, whether `lastModified` exists, whether an Unavailable browser can still read, verdict); the web pass rows (two tabs, persistence prompt, 500-sheet preload) in `owner-verification.md` | Manual (owner) | the per-browser pivot rule and the whole-option abandonment rule of requirements.md (neither Chrome nor Firefox Available records Story 1.4.7 as `Skipped(opfs-spike)`, D-127) |
| UX-139 durability line and temporary graph | `AT/sketch/v2/UxMigrationAcceptanceTest.kt` (Task 1.4.6a) | `webDialog_should_ShowTheDurabilityLine_InBothVariants`; `temporaryGraphDialog_should_ShowTheSessionOnlyLineInstead`; `temporaryGraphModeControl_should_ShowTheSessionOnlyLineAsDescription` | Robolectric | UX-139, D-128 |
| UX-140 save status | `CT/sketch/v2/SheetSaverDurabilityTest.kt` (Task 1.4.7k) | `statusTooltip_should_ReadStillSavingAfterThreeSeconds`; `stillSavingToast_should_BeAVisiblePoliteMessage`; `temporaryGraphSaved_should_ReadSavedForThisSessionOnly` | Unit | UX-140 |
| UX-141 pending state | `CT/sketch/v2/EditorRouterTest.kt` (Task 1.4.3d); `AT/sketch/v2/FlagModeSettingsTest.kt` (Task 1.4.6h) | `checkingStatus_should_RouteToPending_NotToTheNoEditorScreen`; `checkingStatus_should_DisableTheOnOptionsWithTheCheckingText`; `createAction_should_WaitForTheStatusAndNeverCreateV2FromChecking`; `embed_should_ShowTheLoadingPlaceholderWhileChecking` | Unit / Robolectric | UX-141; the routing table's first row is parsed by `check-plan.py` |
| UX-142 second tab | `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/TwoTabSketchLockTest.kt` (Task 1.4.7i) | `heldElsewhere_should_OpenReadOnlyUnderTheLeaseBannerWithoutAPrimaryAction`; `takeover_should_ClearTheBannerAfterTheReload` | `:kmp:wasmJsBrowserTest` | UX-142, D-123 |
| UX-143 persistence request | `AT/sketch/v2/UxMigrationAcceptanceTest.kt` (Task 1.4.6a); `CT/sketch/v2/WebStorageUxTest.kt` (Task 1.4.7l) | `persist_should_BeRequestedOnlyInTheConfirmClickHandler`; `openClassicAndEsc_should_RequestNothing`; `denied_should_ShowTheSettingsRowAndNoToastOrBanner` | Robolectric / Unit | UX-143, D-125 |
| E1 to E9 and product wiring (script) | `check-plan.py` | `verdictTruthTable_should_HaveTheComputedRowCounts`; `webReasonTable_should_CoverEveryUnavailableReason`; `afterAndBeforeClauses_should_MatchTheEdgesOrTheSameNodeOrder`; `optwCitations_should_EqualTheComputedLines`; `opfsSpikeSet_should_EqualTheTasksOfStory147`; `metric6And8_should_SayWhatTheyBecomeUnderOpfsSpike`; `optwCost_should_BeTheComputedSentenceAndQuoteTheOldFigure` | Script check | each mutation-tested on a temp copy, results in the iteration 16 report |

## Triad-review repair iteration 17: new and changed test rows

Documents only; designed, not written or run; this iteration is NOT re-reviewed and no review pass is claimed. It gives the writer lease its consumers and the sweep its own lock, moves the shared storage types and ports out of the parked Story 1.4.7 into Story 1.4.3 (so Story 1.4.7 can be `Skipped(opfs-spike)` without breaking a non-parked task), puts the new seams in the composition roots, adds the quota error, bounds the preload, gives the Option W keep or drop decision a rule, and adds the web save states of ux.md 14.8. Design rule as before: every check added or touched asserts a computed or parsed value and was mutation-tested on a temporary copy (with `kmp/` symlinked beside `project_plans/`; the script now FAILS when `kmp/src` is absent instead of skipping the code-anchored checks), and the results are in the iteration 17 report.

| Req | Test class / check | Test names | Level | Notes |
|---|---|---|---|---|
| Storage types | `CT/platform/V2StorageStatusTest.kt` (Task 1.4.3k) | `privateWindowHint_should_BeSuspectedBelowTheQuotaThreshold_AndNotAtIt`; `unsupportedPersistence_should_AnswerUnsupported`; `readyStatus_should_CarrySessionOnlyAndPrivateSuspected` | Unit (every target) | D-129, D-136 |
| Quota and lease errors | `CT/error/DomainErrorTest.kt` (Task 1.4.3l) | `storageQuotaExceeded_should_HaveItsUiMessage`; `writerLeaseNotHeld_should_HaveItsUiMessage` | Unit | `kmp/src` holds exactly two exhaustive `when`s over `DomainError`, `toUiMessage()` and the `val msg: String = when (err)` of this test (a compile error when a branch is missing); `check-plan.py` finds them by grepping every source set and fails when a file that holds one is not in the file list of Task 1.4.3l (iteration 18, mutation-tested) |
| File system defaults and the lease port | `BT/platform/FileSystemStoragePortsTest.kt` (Task 1.4.3m) | `defaults_should_BeReadyRightAndPlainWrites`; `deleteTempBytes_should_DefaultToDeleteFile`; `alwaysHeldLease_should_StayHeld` | Unit | D-129 |
| Lease reaches the router | `BT/sketch/v2/WriterLeaseRouterTest.kt` (Task 1.4.3n) | `heldElsewhere_should_RouteToLeaseConflictOtherTab`; `held_should_RouteToEditable`; `acquiring_should_NotGrantTheEditLease` | Unit | D-130, UX-142 |
| Lease refuses the write | `BT/sketch/v2/WriterLeaseRefusalTest.kt` (Task 1.4.3o) | `writeWithoutLease_should_ReturnFailedAndWriteNothing`; `writeSourceSnapshotWithoutLease_should_ReturnFailed`; `writeConflictWithoutLease_should_ReturnLeft`; `writeNowWithoutLease_should_ReturnFailed`; `flushPendingAfterLeaseLoss_should_KeepWritesParked`; `modelWriteFromStaleTab_should_BeRefused` | Unit | D-130, D-142; the methods are the `plan-store-scope` block, compared by `check-plan.py` with the interface and with the construction sites of Task 1.4.3o |
| Saver turns the refusal into read-only | `BT/sketch/v2/WriterLeaseSaverTest.kt` (Task 1.4.3r) | `refusal_should_BecomeReadOnlyNotSaveFailed`; `refusal_should_StopTheDebounce` | Unit | D-130 |
| Default wiring (Android) | `AT/sketch/v2/SketchStorageAndroidEntryPointTest.kt` (Task 1.4.3q) | `alwaysHeldLease_should_BeConstructedByTheAndroidRoot` (effect `sketch-writer-lease-held-android`); `unsupportedBrowserPersistence_should_BeConstructedByTheAndroidRoot` (effect `browser-persistence-unsupported-android`) | Robolectric | D-132, D-141; drives `WiredAppFixture`, catalog lines in `wiring-catalog.txt` |
| Default wiring (desktop) | `JT/sketch/v2/SketchStorageDesktopEntryPointTest.kt` (Task 1.4.3q) | `alwaysHeldLease_should_BeConstructedByTheDesktopRoot` (effect `sketch-writer-lease-held-desktop`); `unsupportedBrowserPersistence_should_BeConstructedByTheDesktopRoot` (effect `browser-persistence-unsupported-desktop`) | Unit (jvmTest) | D-132, D-141; drives `DesktopWiredAppFixture` |
| SEAMS list assertion (8 names after Task 2.1.2b, 10 after Task 1.4.3p, 12 after Task 1.4.7m; script-computed) | `BT/wiring/WiringCompletenessTest.kt` (Tasks 2.1.2b, 1.4.3p, 1.4.7m) | `seamList_should_EqualGlossaryList_When_Parsed` (edited with each task that adds a seam); `realCatalog_should_PassAgainstRealTestSources` | Unit | D-141; `check-plan.py` reads the code assertion and fails when it no longer equals `WiringAudit.SEAMS` |
| Web wiring | `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/WebStorageWiringTest.kt` (Task 1.4.7m) | `probe_should_RunOncePerPageLoad`; `visibilityHidden_should_FlushTheSaver`; `leaseAndDurabilityAndPersistence_should_BeConstructedInTheWasmRoot` | `:kmp:wasmJsBrowserTest` | D-132, D-141; no catalog line (no fixture in wasmJsTest and the source set is not bundled), the wasm root is audited for construction by `WiringCompletenessTest` |
| Preload bound | `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/OpfsPreloadStateTest.kt` (Task 1.4.7j) | `overTheCap_should_BePreloadTooLarge_AndReadNothing`; `projectWithHugeBackupDir_should_StayAvailable`; `backupConflictsAndSources_should_NotBeReadByThePreload`; `lateOkWithinSixtySeconds_should_RecoverOnce`; `lateOkAfterSixtySeconds_should_StayUnavailable`; `textPreload_should_NotHoldTheStatus`; `sweep_should_RunUnderItsOwnLock_AndSkipWhenNotGranted` | `:kmp:wasmJsBrowserTest` | D-130, D-131, D-143; the live set is the `plan-preload-live` block |
| Probe place and cleanup | `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/OpfsAtomicReplaceTest.kt` (Task 1.4.7e) | `probe_should_WorkOnlyInItsOwnDirectory_AndDeleteIt`; `crashedProbeOlderThanADay_should_BeRemoved`; `twoConcurrentProbes_should_NeverShareAPath`; `estimateQuota_should_BeRecorded` | `:kmp:wasmJsBrowserTest` | D-137 |
| Temp delete and recursive sweep | `kmp/src/wasmJsTest/.../PlatformFileSystemDirtyHostTest.kt` (Task 1.4.7g); `BT/platform/StaleTempSweeperTest.kt` (Task 1.4.7h) | `tempDelete_should_NotRecordADeleteOrHostDelete`; `deleteOfARealFile_should_StillRecordTheDelete`; `staleTempTwoDirectoriesDeep_should_BeRemoved` | `:kmp:wasmJsBrowserTest` / Unit | D-137 |
| Saver quota variant and Still saving | `CT/sketch/v2/SheetSaverDurabilityTest.kt` (Task 1.4.7k) | `quotaFailure_should_RaiseSaveFailedQuotaWithSaveCopyAsPrimary`; `stillSavingToast_should_AppearAfterThreeSeconds_AndLeaveAtPersisted`; `otherFailure_should_RaisePlainSaveFailed` | Unit | UX-144, UX-145, D-134 |
| Web wording | `CT/sketch/v2/WebStorageUxTest.kt` (Task 1.4.7l) | `privateSuspected_should_UseThePrivateWindowLineAndSavedTooltip`; `refusedPersistence_should_ShowTheInlineLine`; `quotaVariantStrings_should_FitTheBannerBudgetOfTheChromeFixture`; `preloadTooLargeText_should_NameTheCap` | Unit | UX-144, UX-147, UX-148 |
| Pending screen and Reload | `AT/sketch/v2/NoEditorAccessibilityTest.kt` (Task 1.4.6j) | `pending_should_FocusItsHeadingOnArrival`; `pendingResolvingToEditor_should_FocusTheCanvasAndAnnounceReadyOnce`; `pendingTimeout_should_ShowCheckTimedOutWithReload`; `transientReasons_should_OfferReloadAndNoOtherReasonDoes` | Robolectric | UX-146, UX-147; the rows are the `pending-contract` table of ux.md 14.8 |
| Script checks (iteration 17) | `check-plan.py` | `counts_should_QuoteTheComputedCostAndEveryIteration`; `keepOrDropRule_should_BeParsedAndExercisedOnTheWebItemCount`; `reducedForm_should_NameTheOmittedNodeIdentically`; `wiringTable_should_MatchTheFileListsAndTheParkedSets`; `domainErrorSubtypes_should_MatchTheCode`; `sharedSymbols_should_HavePathsAndNoParkedIntroducer`; `preloadCap_should_AgreeAcrossPlanAndUx`; `saveVariant_should_FitTheChromeBudget`; `pendingAndStorageRowTables_should_BeParsed`; `proseEdgeTable_should_BeBidirectional`; `absentCodeTree_should_FailLoudly` | Script check | each mutation-tested on a temporary copy; results in the iteration 17 report |

**Iteration 17 checks** (each parses or computes a value): the counts paragraph quotes `~N.NNM CU` equal to the computed figure with verification and its own `N.NNM x1.75`, and mentions every iteration up to the highest `Iteration N checks` heading; the Option W keep or drop rule is parsed (threshold on `webItems`, the web-intent exception) and exercised on 0, 0 with intent, 1 and 5, the spike node set `OPFSDROP` equals the nodes `0.1.8*`, the cost of `OPFSDROP` plus `OPFSSPIKE` equals the Option W cost, and five places quote the computed share; the node that the reduced form of 6.2.5b omits is the owner browser pass in every document; the `plan-wiring` table rows are checked against the task file lists (root file, `wiring-catalog.txt`, entry-point test), the parked sets and Story 6.2.1; the exhaustive `when` over `DomainError` and the new subtype names are checked against `DomainError.kt` and every file with a `FileSystemError` arm; the `plan-symbols` table gives every shared symbol a declaring task, every task heading that names it a path to it and no non-parked heading a parked declarer, and no non-parked file list a path that a parked task introduces first; `maxPreloadBytes` agrees across the limits list, the story, D-131 and the reason text; the save variant fits the chrome budget; the pending contract, the storage row and the transient actions are parsed; the private-window quota constant agrees; the probe bound agrees; every new test class has a validation row; the prose edge table is checked in both directions; and the run FAILS when `kmp/src` is absent beside `project_plans/`.

## Triad-review repair iteration 18: new and changed test rows

Documents only; designed, not written or run; this iteration is NOT re-reviewed and no review pass is claimed. It defines what a pre-check item's platform means and what keeps Option W (Safari-only and elsewhere-only items, a web intent that names a use, a post-spike demand check, one label precedence), states the verified baseline of classic sketches on the web, re-places the wiring tests where the audit can read them, corrects the exhaustive-`when` claim, the store refusal scope and the preload set, and gives the web UX its second-tab variant, its Still saving contract and one durability wording per state. Design rule: every new or changed check parses a block or the code and compares computed values; none matches a wording fragment.

| Req | Test class / check | Test names | Level | Notes |
|---|---|---|---|---|
| Option W keep or drop (script) | `check-plan.py` | `keepOrDropCases_should_EqualTheParametersApplied`; `abandonCases_should_EqualTheDemandCheck`; `labelPrecedence_should_NameThePreCheckStopFirst` | Script check | the blocks `keep-drop`, `keep-drop-cases`, `abandon-cases` and `label-precedence` of requirements.md (D-138, D-139); each case is recomputed from the parameters, the cases must cover a Safari-only item, a Firefox-only item, an intent without a use and a Safari-only intent, and a Safari pass that does not keep Option W |
| Web baseline (script) | `check-plan.py` | `baselineAnchors_should_MatchTheCodeLines`; `wasmAndIosFileSide_should_StillBeUnavailable` | Script check | 11 anchors of D-140 are regexes over the code whose matching line must equal the cited line, and the cited `file:line` must appear in requirements.md or plan.md |
| Footprint and solver bars (script) | `check-plan.py` | `footprint_should_BeStatedOnceAndEqualTheComputedSum`; `solverBars_should_EqualThePlanThresholds_AndAdvisoryBeBelowHard` | Script check | D-145; the footprint is `OPTW + OPFSSHARED` computed from the task classes |
| Wiring rows (script) | `check-plan.py` | `wiringRows_should_MatchTheAuditFixturesAndBundle`; `seamAddingTask_should_NameTheSeamsItAdds`; `opfsCapability_should_HaveNoRow`; `seamListTest_should_EqualWiringAuditSeams` | Script check | D-141; the fixture source sets and the bundled source sets are read from the code and the Bazel file |
| Store scope (script) | `check-plan.py` | `storeScope_should_EqualTheInterfaceMutatingMethods`; `constructionSites_should_BeInTheFileListOfTask143o` | Script check | D-142; the `plan-store-scope` block and the two construction sites |
| Preload live set (script) | `check-plan.py` | `liveSet_should_BeTheModelAndSheets`; `projectWithLargeBackup_should_StayUnderTheCap`; `sceneLimitsField_should_NotBeEditedByTask147j` | Script check | D-143, D-145; the `plan-preload-live` block is applied to a synthetic tree |
| Introducer, ordering, shared set (script) | `check-plan.py` | `unmarkedNewPathListedByParkedAndNonParked_should_Fail`; `pathMarkedNewByParkedTask_should_NotBeListedByNonParked`; `crossNodeLister_should_NameTheIntroducerInAnAfterClause`; `optwShared_should_BeTheStoragePortTasks` | Script check | the `(new)` mark of plan-files replaces "the lowest wave introduces" |
| Second tab banner variant | `AT/sketch/v2/ChromeBudgetTest.kt`; `BT/sketch/v2/BannerOwnershipTest.kt`; `check-plan.py` | `leaseVariant_should_FitTheBannerRowAt320x1And320x2`; `leaseConflictOtherTab_should_EmitTheVariantWithoutAPrimary`; `leaseVariant_should_RepeatNoSummaryOfTheBannerTable` | Robolectric / Unit / Script | UX-149; the `lease-variants` block of ux.md 14.8 |
| Still saving contract | `CT/sketch/v2/WebStorageUxTest.kt` (Task 1.4.7l); `check-plan.py` | `stillSaving_should_BeVisibleAtLeastOneSecond_WhenTheWriteCompletesAt3Point2`; `stillSaving_should_AnnounceOnce_AndNotWhenPersistedFirst`; `stillSaving_should_NotDisplaceAnotherToast`; `stillSaving_should_BeRemovedFromTheListAtPersisted`; `chip_should_NotBeALiveRegion` | Unit / Script | UX-150; the `still-saving` block is simulated at six completion times |
| Durability wording | `CT/sketch/v2/WebStorageUxTest.kt` (Task 1.4.7l); `check-plan.py` | `durabilityWording_should_ReturnTheWinnerOfTheFourCombinations`; `temporaryAndPrivate_should_GetTheSessionOnlyLine`; `privateSuspected_should_ShowTheHintRowCue`; `exportSavedOnWeb_should_HaveNoOpenAction` | Unit / Script | UX-151; the `durability-wording` block |
| UX-149 second tab variant | `AT/sketch/v2/ChromeBudgetTest.kt` | `leaseVariant_should_FitTheBannerRowAt320x1And320x2` | Robolectric | UX-149 |
| UX-150 Still saving timing | `CT/sketch/v2/WebStorageUxTest.kt` (Task 1.4.7l) | `stillSaving_should_AnnounceOnce_AndNotWhenPersistedFirst` | Unit | UX-150 |
| UX-151 one durability winner | `CT/sketch/v2/WebStorageUxTest.kt` (Task 1.4.7l) | `temporaryAndPrivate_should_GetTheSessionOnlyLine` | Unit | UX-151 |

**Iteration 18 checks** (each parses or computes a value): requirements.md blocks `keep-drop`, `keep-drop-cases` (11 cases, each recomputed), `abandon-cases` (8 cases) and `label-precedence`; the cases must cover the classes listed in the first row above; the pre-check stop must keep no Option W node in its kept closure, so `Skipped(pre-check)` names it first; 11 code anchors of the web baseline (regex, line and citation); the total web footprint (Option W plus the shared ports) recomputed and equal in every place that states it, and the shared-port cost repeated in D-133; the `solver-bars` block against the plan thresholds; the plan-wiring table (9 columns) against the file lists, the fixture and bundle source sets read from the code, the parked sets, the task that adds each seam and the code assertion of `WiringCompletenessTest`; exhaustive `when`s over `DomainError` found by grepping every source set (with a synthetic self-test) against the claim and the file list of Task 1.4.3l; the `plan-store-scope` block against the interface and the construction sites; the `plan-preload-live` block applied to a tree with a 400 MB `backup/`; the `(new)` marks, the introducer, same-node and cross-node ordering and the `OPFSSHARED` set; the `lease-variants`, `still-saving` and `durability-wording` blocks of ux.md; UX-149 to UX-151 in section 20.

## Triad-review repair iteration 19: new and changed test rows
Documents only; designed, not written or run; this iteration is NOT re-reviewed and no review pass is claimed. Design rule: the classification of seams, the order of the tasks that edit the audit and the figures of the new screens are blocks that `check-plan.py` recomputes; the tests below prove behaviour.

| Item | Test (file) | Test names | Level | Replaces / note |
|---|---|---|---|---|
| E1 text metrics wiring | `AT/sketch/v2/TextMetricsAndroidEntryPointTest.kt`, `JT/sketch/v2/TextMetricsDesktopEntryPointTest.kt` (Task 2.1.2c) | `composeTextMetricsProvider_should_MeasureThroughTheRealRoot` (effects `text-metrics-measured-android`, `text-metrics-measured-desktop`) | Robolectric / JVM | D-146; two catalog lines added after Task 2.1.2b put `TextMetricsProvider` on the list |
| E1 host sites | `BT/wiring/SketchStorageHostSitesTest.kt` (Task 1.4.3t) | `everyHostSite_should_NameBothStorageFields`; `noProductionHost_should_ConstructTheDefaultsItself`; `wasmSites_should_PassTheDefaultsUntilTasks147eAnd147iSwapThemIn` | Unit (reads the production sources through `WiringInputs`) | D-146; the entry-point tests of Task 1.4.3q read the fixtures, this scan covers `MainActivity`, the desktop `Main` and `App.kt` and both wasm sites |
| E1 early wiring | `BT/wiring/WiringCompletenessTest.kt` (Tasks 2.1.2b, 1.4.3p, 1.4.7m) | `seamList_should_EqualGlossaryList_When_Parsed` (edited by each task); `evaluate_should_PassInEveryWave_When_ImplementationIsConstructedByItsDeclaringOrListTask` | Unit | D-146; the order is the `plan-wiring` red-window rule, checked by script |
| E1 class seams | `AT/sketch/v2/WiringV2AndroidEntryPointTest.kt`, `JT/sketch/v2/WiringV2DesktopEntryPointTest.kt` (Task 6.2.1a; registered by Task 6.2.1c) | `everyClassSeam_should_BeReachableThroughTheRealFixture` | Robolectric / JVM | the nine `plan-seam-classes` names, no catalog line (the catalog rejects a non-seam) |
| E3 refusal of the remaining writes | `BT/sketch/v2/WriterLeaseRefusalTest.kt` (Task 1.4.3o) | `writeSourceSnapshotWithoutLease_should_ReturnFailedAndWriteNothing`; `writeBackupWithoutLease_should_ReturnFailedAndCreateNoBackupDirectory`; `writeSheetConflictCopyWithoutLease_should_ReturnLeftAndWriteNoCopy`; `flushPendingAfterLeaseLoss_should_KeepWritesParked` | Unit | D-142; `writeBackup` and `writeSheetConflictCopy` exist since Task 0.1.1b, before the lease parameter of Task 1.4.3o |
| E3 stale parked bundle | `BT/sketch/v2/WriterLeaseRefusalTest.kt` (Task 1.4.3o); `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/TwoTabSketchLockTest.kt` (Task 1.4.7i) | `flushPendingAfterLeaseReacquire_should_NotReplayStaleBundle`; `staleParkedBundle_should_BecomeConflictCopy`; `staleParkedModel_should_BecomeConflictCopy`; `reacquire_should_ReloadTheSidecarRootAndGrowTheEpoch` | Unit / `:kmp:wasmJsBrowserTest` | D-150; the outcomes are the `plan-lease-epoch` block |
| P1 classic file side policy | `BT/projectbridge/ClassicWebFileSidePolicyTest.kt` (Task 1.4.7o, was 1.4.7n) | `verdictUnavailable_should_BeOffWhateverTheSetting`; `defaultSetting_should_BeOff`; `v2Sheet_should_BeReconciledRegardlessOfTheSetting`; `classicSheetOfAMixedProject_should_StayDatabaseOnlyUnderOff`; `settingIsIndependentOfV2Mode` | Unit | D-147, D-153; the table is the `classic-web-file-side` block, now per sheet |
| P1 classic reconcile on wasm | `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/WasmClassicReconcileTest.kt` (Task 1.4.7n) | `settingOff_should_LeaveEveryClassicFileSideUntouched`; `existingDatabaseAndSyncedBundle_should_ReconcileLikeDesktop`; `divergentEdits_should_FollowTheDesktopOutcomeRows`; `pullOverwrite_should_ReplaceTheDatabaseContentsOnlyThroughReplaceProjectContents` | `:kmp:wasmJsBrowserTest` | metric 6 on wasm (D-147); dropped with Story 1.4.7 when it is `Skipped(opfs-spike)` |
| P2 post-spike re-read | manual (`baselines.md`, Task 0.1.8c) | `postSpikeRecount_should_BeRecordedBeforeAnyW2NodeStarts`; `decideCase_should_RecordContinueOrStop` | Manual (owner) | D-148; every case is a row of the `post-spike-recount` block, recomputed by script |
| U1 boot refusal | `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/browser/BootRefusalTest.kt` (Task 1.4.6k) | `bootRefusal_should_ShowHeadingAndAlertAndMoveNoFocus`; `bootRefusal_should_ReloadWhenTheLockIsGranted`; `bootRefusal_should_ShowTheNoRetryMessageWhenLocksSignalIsUnsupported`; `bootRefusal_should_OfferOpenTemporarilyWithoutADriverLock`; `bootRefusal_should_ShowAVisibleFocusRingOnReload`; `bootRefusal_should_NotShowTheGraphName`; `bootRefusal_should_ReflowAt320CssPxAnd400PercentZoom` | `:kmp:wasmJsBrowserTest` | UX-152, D-149; the figures are the `boot-refusal` block of ux.md 14.9 |
| U1 which screen a real second tab shows | manual (`owner-verification.md`, Task 1.4.7d) | `secondTab_should_ShowTheBootRefusalScreen_OnAPersistentWebGraph`; `pendingLockRequest_should_BeGrantedWhenTheFirstTabCloses` | Manual (owner) | INFERRED until run; the `OtherTab` banner is defence in depth |
| U2 private cue | `CT/sketch/v2/WebStorageUxTest.kt` (Task 1.4.7l) | `hintCue_should_PickTheLongestFormThatFitsTheLine`; `hintCue_should_ShareTheLineAndLetTheHintTextYieldFirst`; `collapsedHintRow_should_MoveTheCueToTheChipRow` | Unit | UX-153, D-151; the `hint-cue` block |
| U2 text nits | `CT/sketch/v2/WebStorageUxTest.kt` (Task 1.4.7l) | `stillSaving_should_AnnounceAtFourSeconds_EvenWhenTheLaneIsOccupied`; `saveFailedSummary_should_NotClaimTheWorkIsKeptAfterTheTabCloses` | Unit | UX-140, UX-150; D-152 |

**Iteration 19 checks** (each parses or computes a value): the `plan-wiring` rows against the red-window rule (an implementation is constructed no later than the later of the task that adds its interface to `WiringAudit.SEAMS` and the task that declares it, and a catalog line comes after the list task), the SEAMS count chain (7 names today, then the cumulative count named by each adding task's heading, the three tasks totally ordered), the 14 names of Story 6.2.1 against the `plan-wiring` seams plus the `plan-seam-classes` block, the construction sites of `StelekitAppPlatformIntegrations` found in the code against the file lists of Tasks 1.4.3s and 1.4.3t (the wasm line numbers the heading cites), the `plan-lease-epoch` outcomes, the store scope rows against named refusal tests, the `classic-web-file-side` table and its setting, the `post-spike-recount` cases (counts and gate recomputed) and the zero-width gate in the edge block (wave, edges into every W2 node), the `a6-tier-cases` and `cross-target-agreement` blocks, the mirror abandon case, the no-family web item, the boot-refusal figures (characters per line at the largest text scale, longest word, contrast ratios, target size, contract rows, outcomes by platform, code anchors), the hint cue at both scales and the windows where `HintStatus` collapses (computed from the chrome table) and the StillSaving announcement second.

## Triad-review repair iteration 20: new and changed test rows
Documents only; designed, not written or run; this iteration is NOT re-reviewed and no review pass is claimed. Design rule: every figure and rule of this iteration is a block that `check-plan.py` parses or computes (the per-sheet policy table, the scenarios, the discovery bounds, the availability switch, the cue forms, the windows without `HintStatus`); the tests below prove behaviour.

| Item | Test (file) | Test names | Level | Replaces / note |
|---|---|---|---|---|
| P1 discovery of a sync-only v2 project | `BT/projectbridge/SyncedV2ProjectDiscoveryTest.kt` (Task 1.4.7n) | `syncedV2ProjectNotInDatabase_should_BeImportedUnderOff`; `classicOnlySyncedProject_should_BeLeftUntouched`; `mixedProject_should_ImportV2SheetsOnly`; `projectAlreadyInDatabase_should_NotBeScanned`; `scan_should_StopAtTheProjectAndTimeBounds_And_ResumeAtTheNextOpen`; `scan_should_ReadOnlyTheListingAnd256BytesOfOneSheet_And_WriteNothing` | Unit (a counting file system and a virtual clock) | D-153; the bounds are the `v2-discovery` block |
| P1 combined outcome | `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/WasmClassicReconcileTest.kt` (Task 1.4.7n) | `settingOffAndSyncedV2Project_should_OpenEditable` (verdict Available, setting Off, a desktop-synced v2 project not in the web database: found, imported, editable, nothing of a classic sheet touched); `scenarioRows_should_HoldOnTheWasmFileSystem` (each row of `classic-web-scenarios`) | `:kmp:wasmJsBrowserTest` | D-153; ties the headline claim to the default setting |
| P2 per-sheet scope | `BT/projectbridge/ClassicWebFileSidePolicyTest.kt` (Task 1.4.7o) | `classicSheetOfAMixedProject_should_StayDatabaseOnlyUnderOff`; `v2SheetOfAMixedProject_should_BeReconciledUnderOff`; `everyRowOfTheBlock_should_MatchThePolicy` | Unit | D-153; the rows are the `classic-web-file-side` block |
| E1 gate before the availability switch | `BT/projectbridge/ClassicWebFileSidePolicyTest.kt` (Task 1.4.7o); `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/OpfsPreloadStateTest.kt` (Task 1.4.7j) | `defaultSetting_should_BeOff`; `afterTheAvailabilitySwitch_should_KeepClassicSheetsDatabaseOnly` | Unit / `:kmp:wasmJsBrowserTest` | D-154; the order is the `plan-availability-switch` block, checked by script |
| E entry-point tests observe a refusal | `AT/sketch/v2/SketchStorageAndroidEntryPointTest.kt`, `JT/sketch/v2/SketchStorageDesktopEntryPointTest.kt` (Task 1.4.3q) | `defaultLease_should_LetTheRealStoreProviderWrite`; `heldElsewhereLease_should_RefuseTheWriteThroughTheRealProvider` (effects `sketch-writer-lease-held-android`, `sketch-writer-lease-held-desktop`) | Robolectric / JVM | D-157; the `plan-entry-observation` block |
| E lease ordering and epoch stamp | `BT/sketch/v2/WriterLeaseRefusalTest.kt` (Task 1.4.3o) | `leaseCheck_should_PrecedeTheGitSyncPark`; `reparkedBundle_should_KeepItsOriginalEpoch` | Unit | D-150, D-157; the anchors are the lines of `ProjectSidecarStore.kt` that `check-plan.py` finds |
| U1 cue chip fit | `CT/sketch/v2/WebStorageUxTest.kt` (Task 1.4.7l); `AT/sketch/v2/ChromeBudgetTest.kt` (Task 3.6.4c consumer) | `windowsWithoutHintStatus_should_EqualCollapsedUnionPermanent`; `cueChip_should_PickTheLongestFormThatFitsTheChipRow_ForEveryWindowWithoutHintStatus`; `cueChip_should_NotScroll_AtAnyWindowWithoutHintStatus` | Unit / Robolectric | D-155, UX-153; the chip row width is the computed line of the chrome block |
| U4 temporary-graph cue | `CT/sketch/v2/WebStorageUxTest.kt` (Task 1.4.7l) | `sessionOnlyGraph_should_ShowItsOwnPersistentCue`; `sessionOnlyCue_should_WinOverThePrivateWindowCue` | Unit | D-155, UX-151 |
| U nit accessible names | `CT/sketch/v2/WebStorageUxTest.kt` (Task 1.4.7l) | `saveStatusAccessibleName_should_ContainTheVisibleLabel` (every row of `save-status-names`) | Unit | D-155 (WCAG 2.5.3) |

**Iteration 20 checks** (each parses or computes a value): the per-sheet policy rows recomputed from the verdict, the setting and the sheet kind; the scenario rows recomputed (where a project is found, which kinds of sheet the file side touches), including the headline row (setting Off, project unknown to the database, a v2 sheet in the bundle: found by the discovery scan) that the "synced from desktop opens editable" claim needs; the discovery bounds (probe bytes equal the header probe, projects times bytes quoted by D-153); the demand counted by the keep or drop rule equal to the `web-value` block; the `plan-availability-switch` block against the tasks whose heading names `supportsTempRenameWrites`, with the gate an ancestor in an earlier wave; the `plan-serial-edges` edge and shared file; the `plan-entry-observation` rows against the `plan-wiring` labels and the production class they observe through; the code lines of `ProjectSidecarStore.write` and `flushPending` that Task 1.4.3o cites; the REDUCED line of Story 6.2.1 against the parked sets that hold seams; the seam-class builders against the heading or file list (with `(new)`); the windows without `HintStatus` as the union of the Rows-collapsed column and the `permanent` section, each with the longest cue form that fits its chip row; the four cue forms (decreasing length, the chip form a prefix of the full form), their fit on the hint row at each font scale, and no prose enumeration of the windows; the boot-refusal figures in rem (contrast, non-text contrast of the button and the focus ring, widths of both actions, one announcement source, the two message variants, the open-temporarily URL and the index.html anchors); the visible label of each save status inside its accessible name; the qualifier "defence in depth" on every OtherTab paragraph; the history table against the plan.

### Plan document check (`check-plan.py`)

Run from the repository root: `sed -n '/^```python check-plan/,/^```$/p' project_plans/excalidraw-sketch-engine/implementation/validation.md | sed '1d;$d' > /tmp/check-plan.py && python3 /tmp/check-plan.py project_plans/excalidraw-sketch-engine --effort --code-root <DIR>`, where `<DIR>` holds `kmp/`, `androidApp/` and `docs/` as of the planning commit (`git archive 444af51bbf kmp androidApp docs | tar -x -C <DIR>`): the code-anchored checks compare the plan with the code AS PLANNED, so against the live tree they fail once a task they describe is implemented (the code moved, the plan did not), and that is not a plan error. It exits 1 and prints one `FAIL` line per broken rule. It checks: stated counts equal computed counts (epics, stories, tasks and XS/S/M, glossary terms, UX criteria); every UX id of ux.md section 20 appears in plan.md and no range citation appears in the plan body; every node of the machine-readable edge block is a story or task and every task resolves to a node; waves computed from the block equal the declared waves block; every M task has 1-5 distinct files in the file-list block and sizes agree with headings; every `Task x.y.zL` and `Story x.y.z` reference in the four documents resolves to a heading; owner defaults D-1..D-n are all present and every document that names the range `D-1..D-N` or `D-1 to D-N` names the plan's maximum; a best-effort check that no task heading names more than 4 deliverables beyond its file count (split the task or list every file); no double-at placeholder remains; a wave stated next to a task id equals that task's node wave. `--effort` prints the epic table rows, the sunk-cost figures and the pivot-option costs; since iteration 6 the same figures are CHECKED, not only printed: requirements.md and plan.md must contain the exact sentences the script computes (totals, W1/W9/W10 shares, the GATED share, the Job 2 and import/export pivot costs, every epic table row). Iteration 10 extends it: the pre-check STOP set (every node outside the kept closure, the gate closure and the NONUI tooling) must contain the `STOPNAMED` nodes of the plan-hold block and the HELD set, and the two pre-check rows of the combined-outcomes table and the READERONLY exclusion must exist; the prose edge table of "Dependency Visualization" is compared with the edge block (plain id-list rows only; a planted wrong edge must be flagged); the ADRs are scanned for the canonical spelling of `V2Mode`, `ConversionOffered`, `ConversionReady`, `NewProjectsOnly` and `NewSketchProject`, for the names ADR-005 and ADR-001 must carry and for retired phrases (planted errors must be flagged); and the nodes-per-wave sentence must equal the computed widths. Iteration 9 extends it: a task heading "needs <ids>" must be carried by the task's own node or its story's node in the edge block (mutation-tested); the pre-check hold set is recomputed from the dependency closure of the kept pure engines and every root of it must have an edge from node 0.1.7c; every node with an edge into a parked node (JOB2UI, INTEROP, FLAGREL of the plan-parked block) must have a REDUCED line and the reader-only release (Task 6.2.5e) must have no path to the gate, the rollout task or a parked node and must feed Task 6.2.5c; the pivot costs include the release path and are enforced as sentences; the consent-row line counts at scales 1.0, 1.15, 1.3, 1.5 and 2.0 and the first scale at which the sheet replaces the row are computed per window width and every prose figure of the form "from font scale X at 320 dp" is compared; a two-line label at font scale 2.0 must fit the toast row and the banner slot; the price of widening the primary button is computed; the banner-slot paragraph must name every toast row id; each prose-figure scan is proven by a planted wrong figure and its counts are printed. Iteration 8 extends it again: every banner summary and short title (and every toast, placeholders expanded, `{n:3}` three characters) is word-wrapped greedily at the line width of 320 dp and font scale 1.0 or 2.0 and must fit the row's two lines; the consent row has no short title and must state its consequence for other devices; every primary label has an accessible name that contains the label, adds an object and is unique, no label is reused except Undo, Got it, Open, Show and Learn more, and "Learn more" rows do not repeat Details in More; the banner tiers run Blocking, State, Warning, Info, Onboarding; references resolve for unsuffixed ids and plural task lists; each story heading "(needs ...)" is a subset of its edges (with an explicit whitelist of story-to-task refinements); the combined outcomes table has no empty cell and the A1 gate names the paired measurement; and the computed block also states the worst overlay share. Iteration 7 extends it: the top-bar widths per font scale and the tier each window needs, the toast table and its budget, the primary-label rule for banner and toast, the icon-inclusive banner budget, the view-cluster and chip-row overlay budget with the drawing width, the one tab order of section 10 against the F6 regions of section 17, and the A1 x A4 x A6 table of requirements.md. Iteration 6 also adds the **chrome table check**: the table in ux.md section 1 is the one source of every layout figure; the script computes flatExtent, groupedExtent (technical and diagram sheets), the stacks, the minimum canvas, every worked canvas height, the pan-pad variant, the toast count and the banner text budget, fails if the "Computed from the table" block of ux.md differs from its output (`--chrome` prints it), fails if any prose figure it recognises (a flat or grouped extent, a stack, a canvas height) or a retired figure (908, 784, 732) appears in the four documents, checks every banner row against the text budget, checks that the section 16 recurrence rows follow the banner ranks, and checks that the `SceneLimits` field count named in the documents equals the list. Iteration 13 extends it and VALUE-checks what earlier iterations checked by wording: the effort KEPT set is DERIVED from the closure of the plan-hold KEPT line less its NOTKEPT line (28 nodes, priced outside W1) and the quoted figures must equal it; the probe bound is parsed (`HEADER_PROBE_BYTES`, opens, the product 50 x 256 = 12,800, the 51-500 scope against `SidecarReconciler.MAX_SHEETS` and `SketchSheetRepository.DEFAULT_SHEET_LIMIT` when the code is present, the file key against `SidecarFileState` and `ProjectModelJson`); the 12 Esc rows of ux.md section 17 are parsed and compared with the canonical list; the four project kinds are parsed from every list that enumerates them; the ClassicEditor scope tuple is parsed in four places and the ledger bound in two; the chars-per-line model is property-checked as floor(width / (7 dp x scale)); every added check was mutation-tested on a temporary copy. Iteration 11 extends it: the A6 parked-set cost sentences, the absence of any edge from Task 0.1.2d into Epic 4.1, the "Reachable demand" section and its mentions in metric 8 and the three forms, every commonTest class of the `ConversionReadyConsistencyTest` list named by a registration task (mutation-tested), the identical `NewSketchProject` canonical sentence in requirements, plan and both ADRs (mutation-tested), the reader-only failure message naming Task 6.2.4e, and the retired phrases of the iteration. **Iteration 14 additions** (each parses or computes the value it checks): the wave-0 pre-check (`0.1.7c: @OWNER0`, every other node has a path from it, every W1 node has an edge from it) and the stop costs it quotes (the KEPT closure split into the nodes outside W1 and the W1 spikes it needs, the `MERGES` nodes inside it); the header-first offsets of the v1 and v2 layouts and of a fully sorted emission against `SCHEMA_WINDOW` and the 256-byte probe prefix, and the model file name against `SidecarCodec.MODEL_PATH`; the platform capability derived from `supportsTempRenameWrites` in the four `PlatformFileSystem` classes and the cited lines, the Option W cost, the JVM facts of Task 1.4.3g and that its listed files exist; D-62, D-85 and D-100 quoting the closure figures and the parsed Esc rows; the fourth A1 gate outcome in the precedence lists and the combined table; the metric 5 baseline counts, the Size line total, the newer-schema actions, the consent sheet cadence unit over every statement, the modes of the discovery item, the reason precedence ids, the `(after Task ...)` order notes, the `onGraphRemoved` code lines and the A6 dependants outside JOB2UI.

**Iteration 16 checks** (each parses or computes a value): every `after`/`before` clause of a task heading is resolved to nodes and must be an edge path, or, within one node, an acyclic order (with a planted-reversal self-test); the verdict truth table is recomputed from the reasons in the plan and its six row counts compared; the web reason table of ux.md 14.1 must list exactly the Unavailable reasons plus `PreloadFailed` and `CheckTimedOut`, one text each, naming no browser except on the iOS WebKit row; the first routing row must be the pending row; the superseded Option W figure is quoted exactly twice and exceeded by the computed cost; the parked set `OPFSSPIKE` equals the nodes of Story 1.4.7 and every node with an edge into it has a REDUCED line naming it; metrics 6 and 8 and three documents say `Skipped(opfs-spike)`; every cited D-id exists; and every citation of the Option W code files equals a line or range computed from the code (and the required ones are cited).

```python check-plan
#!/usr/bin/env python3
"""check-plan.py <project_plans/excalidraw-sketch-engine> [--effort] [--chrome]
Mechanical document check for plan.md, validation.md, ux.md and requirements.md.
Exit status 1 and a list of FAIL lines when any rule is broken. No repo code is touched."""
import re, sys, os
D = sys.argv[1] if len(sys.argv) > 1 else '.'
rd = lambda p: open(os.path.join(D, p), encoding='utf-8').read()
plan, ux, req, val = rd('implementation/plan.md'), rd('design/ux.md'), rd('requirements.md'), rd('implementation/validation.md')
fails = []
def fail(m): fails.append(m)
from fractions import Fraction
def chrome_charsper(avail, s, cd):
    return int(Fraction(avail) / (Fraction(cd) * Fraction(str(s))))

# ---- chrome table (single source of every layout figure): parsed from ux.md, computed here
def chrome_parse(ux):
    mm = re.search(r'<!-- chrome-table:start -->\s*```text\n(.*?)```', ux, re.S)
    if not mm: return None
    sec = None; T = {'params': {}, 'tools': [], 'groups': {}, 'strip': [], 'pinned': [], 'rows': {}, 'topbar': [], 'windows': [], 'banner': [], 'toast': [], 'perm': {}}
    for l in mm.group(1).split('\n'):
        l = l.split('  #')[0].strip()
        if not l or l.startswith('#'): continue
        if l.startswith('['): sec = l.strip('[]'); continue
        p = [x.strip() for x in l.split('|')]
        if sec == 'params': k, v = [x.strip() for x in l.split('=')]; T['params'][k] = int(v)
        elif sec == 'tools': T['tools'].append((p[0], p[1]))
        elif sec == 'groups': T['groups'][p[0]] = p[1]
        elif sec == 'strip': T['strip'].append((p[0], int(p[1])))
        elif sec == 'pinned': T['pinned'].append((p[0], int(p[1])))
        elif sec == 'rows': T['rows'][p[0]] = (int(p[1]), int(p[2]), p[3], p[4])
        elif sec == 'topbar': T['topbar'].append(dict(name=p[0], w1=int(p[1]), w2=int(p[2]), t1=p[3], t2=p[4]))
        elif sec == 'windows': T['windows'].append((p[0], int(p[1]), int(p[2]), float(p[3]), p[4]))
        elif sec == 'permanent': T['perm'][p[0]] = p[1].split()
        elif sec == 'banner': T['banner'].append(dict(rank=int(p[0]), id=p[1], tier=p[2], summary=p[3], short=p[4], primary=p[5], more=[x.strip() for x in p[6].split(';') if x.strip() and x.strip() != '-'], name=p[7]))
        elif sec == 'toast': T['toast'].append(dict(id=p[0], summary=p[1], short=p[2], primary=p[3], more=[x.strip() for x in p[4].split(';') if x.strip() and x.strip() != '-'], name=p[5]))
    return T

def chrome_compute(T, fail):
    P = T['params']; V = {}
    pinned = sum(w for _, w in T['pinned']) + P['deleteGap']
    nt = len(T['tools']); singles = [t for t, g in T['tools'] if g == '-']
    gcount = {}
    for t, g in T['tools']:
        if g != '-': gcount[g] = gcount.get(g, 0) + 1
    ctl = sum(w for _, w in T['strip'])
    split = P['toolTarget'] * P['splitTargets']
    V['pinned'] = pinned
    V['tools'] = nt; V['singles'] = len(singles); V['grouped'] = sum(gcount.values()); V['groups'] = len(gcount)
    V['flatExtent'] = pinned + nt * P['toolTarget'] + ctl
    def grouped(technical):
        gs = [g for g in gcount if technical or T['groups'][g] == 'all']
        return pinned + len(gs) * split + len(singles) * P['toolTarget'] + ctl
    V['groupedTech'] = grouped(True); V['groupedDiagram'] = grouped(False)
    V['flatWindowWidth'] = V['flatExtent']; V['flatWindowHeight'] = V['flatExtent'] + P['topBarReserve']
    V['visibleAt320'] = (320 - pinned) // P['toolTarget']
    TB = {b['name']: b for b in T['topbar']}
    def tbneed(tier, sel):
        key = 'w1' if tier == 0 else 'w2'
        return sum(b[key] for n, b in TB.items() if n not in ('NameMin',) and (sel or n != 'SelectionMenu'))
    V['nameAt320'] = 320 - tbneed(0, False)
    V['nameAt320x2'] = 320 - tbneed(1, False)
    nm = TB['NameMin']['w1']
    for n, b in TB.items():
        if b['t1'] != '-' and b['w1'] < P['chipPad'] + len(b['t1']) * P['bannerCharDp']: fail('top bar %s is %d dp wide at font scale 1.0 but its label "%s" needs %d dp' % (n, b['w1'], b['t1'], P['chipPad'] + len(b['t1']) * P['bannerCharDp']))
        if b['t2'] != '-' and b['w2'] < P['chipPad'] + len(b['t2']) * P['bannerCharDp'] * 2: fail('top bar %s is %d dp wide at font scale 2.0 but its label "%s" needs %d dp' % (n, b['w2'], b['t2'], P['chipPad'] + len(b['t2']) * P['bannerCharDp'] * 2))
        if b['w2'] < P['toolTarget']: fail('top bar %s is under the touch floor at font scale 2.0' % n)
    if V['nameAt320'] < nm: fail('top bar leaves %d dp for the name at 320 dp wide, below NameMin %d' % (V['nameAt320'], nm))
    if V['nameAt320x2'] < nm: fail('top bar leaves %d dp for the name at 320 dp wide and font scale 2.0, below NameMin %d' % (V['nameAt320x2'], nm))
    def layout(w, h):
        if h < P['compactHeight']: return 'rail+side sheet'
        if w >= P['expandedWidth']: return 'rail+side pane'
        return 'dock+bottom sheet'
    R = T['rows']
    def stack(lay, scale, collapsed):
        s = 0
        for n, (h1, h2, col, inl) in R.items():
            if inl == 'dock' and not lay.startswith('dock'): continue
            if n in T['perm'].get(lay, []): continue
            if n in collapsed: continue
            s += h1 if scale == 1.0 else h2
        return s
    def order():
        return [n for n, v in sorted(R.items(), key=lambda kv: kv[1][2]) if v[2].isdigit()]
    rows = []
    for (wid, w, h, sc, g) in T['windows']:
        lay = layout(w, h)
        ext = w if lay.startswith('dock') else h - P['topBarReserve']
        mode = 'flat' if V['flatExtent'] <= ext else 'grouped'
        fits = 'no scroll' if mode == 'flat' else ('%s' % ('scrolls on technical sheets' if V['groupedTech'] > ext else 'no scroll') if V['groupedDiagram'] <= ext else 'scrolls')
        cw = w - (P['railWidth'] if lay.startswith('rail') else 0) - (P['sidePaneWidth'] if lay == 'rail+side pane' else 0)
        mn = min(P['minCanvasMaxDp'], P['minCanvasPct'] * h // 100)
        full = h - stack(lay, sc, [])
        collapsed = []; ch = full
        for n in order():
            if ch >= mn: break
            if n in T['perm'].get(lay, []): continue
            collapsed.append(n); ch = h - stack(lay, sc, collapsed)
        worst = full; wc = []
        for n in order():
            if worst >= mn: break
            if R[n][2] == '2' or n in T['perm'].get(lay, []): continue
            wc.append(n); worst = h - stack(lay, sc, wc)
        pct = ch * 100 // h
        area = cw * ch
        padFull = P['padGridCells'] * P['padCell'] ** 2; padRow = P['padCell'] * P['padRow'] * P['padCell']
        pad = 'grid' if padFull * 100 <= P['padMaxPct'] * area else 'row'
        cov = (padFull if pad == 'grid' else padRow) * 1000 // area
        trow = P['toastRow'] if sc == 1.0 else P['toastRow2']
        toasts = max(1, min(3, (ch * P['toastMaxPct'] // 100) // (trow + P['toastGap'])))
        # top bar: natural tier by font scale, escalate while the name would drop below NameMin
        sel = lay == 'rail+side sheet'
        tier = 0 if sc < 1.5 else 1
        need = tbneed(tier, sel) + nm
        if need > cw and tier == 0: tier = 1; need = tbneed(1, sel) + nm
        tbtier = 'full' if tier == 0 else 'short'
        if need > cw and sel: sel = False; need = tbneed(1, False) + nm; tbtier = 'short, Selection in overflow'
        namew = cw - (need - nm)
        # overlays (bottom band): mic, chip row, view cluster, edge strips
        sides = 1 if w < P['compactWidth'] else 2
        avail = cw - sides * (P['gestureInset'] + P['toolTarget'])
        gap = P['clusterGap']; mic = P['toolTarget']
        one = P['viewTargets'] * P['toolTarget'] + (P['viewTargets'] - 1) * gap
        two = max(3, P['viewTargets'] - 3) * P['toolTarget'] + (max(3, P['viewTargets'] - 3) - 1) * gap
        if avail - mic - gap - P['chipMin'] - gap >= one: cl, clh = 'one row', P['toolTarget']
        else: cl, clh = 'two rows', 2 * P['toolTarget'] + gap
        clw = one if cl == 'one row' else two
        beside = avail - mic - gap - clw - gap >= P['chipMin']
        ovh = clh + (0 if beside else P['toolTarget'] + gap)
        drawW = cw - sides * P['toolTarget']
        rows.append(dict(wid=wid, w=w, h=h, sc=sc, g=g, lay=lay, mode=mode, ext=ext, fits=fits, full=full, collapsed=collapsed, ch=ch, worst=worst, mn=mn, cw=cw, pct=pct, pad=pad, cov=cov, toasts=toasts, namew=namew, tbtier=tbtier, tbnat=(0 if sc < 1.5 else 1), tbneed=need, sides=sides, avail=avail, cl=cl, clw=clw, beside=beside, ovh=ovh, drawW=drawW))
        if g != 'yes' and w >= P['minWindowWidth'] and h >= P['minWindowHeight']: fail('chrome: degraded window %s is inside the guaranteed range' % wid)
        if namew < nm: fail('chrome: %s %dx%d scale %.1f top bar leaves the name %d dp, below NameMin %d, even with the Selection menu in overflow' % (wid, w, h, sc, namew, nm))
        if avail < clw: fail('chrome: %s %dx%d view cluster (%d dp) does not fit the %d dp left after the edge-strip insets' % (wid, w, h, clw, avail))
        if g == 'yes':
            if w < P['minWindowWidth'] or h < P['minWindowHeight']: fail('chrome: guaranteed window %s %dx%d is below the minimum window' % (wid, w, h))
            if worst < mn: fail('chrome: %s canvas %d dp with a Blocking banner is below the minimum %d dp' % (wid, worst, mn))
            if cw < P['minCanvasWidth']: fail('chrome: %s canvas width %d dp below %d dp' % (wid, cw, P['minCanvasWidth']))
            if drawW < P['minCanvasWidth']: fail('chrome: %s drawing width %d dp (canvas minus %d edge strips) below %d dp' % (wid, drawW, sides, P['minCanvasWidth']))
            if cov > P['padMaxPct'] * 10: fail('chrome: %s pad covers %.1f%% of the canvas' % (wid, cov / 10.0))
            if ovh * 100 > P['overlayMaxPct'] * ch: fail('chrome: %s cluster and chip row cover %d dp of a %d dp canvas, over %d%%' % (wid, ovh, ch, P['overlayMaxPct']))
            if tbtier.endswith('overflow') or (tier > (0 if sc < 1.5 else 1)): fail('chrome: guaranteed window %s %dx%d scale %.1f needs top-bar degradation (%s)' % (wid, w, h, sc, tbtier))
    V['rows'] = rows
    cfm = re.search(r'<!-- cue-forms:start -->\s*```text\n(.*?)```', ux, re.S)
    CF = {}
    for l in (cfm.group(1).split('\n') if cfm else []):
        l = l.strip()
        if l and not l.startswith('#'): p = [x.strip() for x in l.split('|')]; CF[p[0]] = p[1:]
    V['cueforms'] = CF
    V['nohint'] = []
    for r in rows:
        why = 'collapsed' if 'HintStatus' in r['collapsed'] else ('permanent' if 'HintStatus' in T['perm'].get(r['lay'], []) else None)
        if not why: continue
        chipw = r['avail'] - P['toolTarget'] - P['clusterGap'] - ((r['clw'] + P['clusterGap']) if r['beside'] else 0)
        pick = {}
        for wid, fs in CF.items():
            pick[wid] = next((f for f in fs if P['chipPad'] + Fraction(len(f) * P['bannerCharDp']) * Fraction(str(r['sc'])) <= chipw), None)
            if pick[wid] is None and r['g'] == 'yes': fail('chrome: %s %dx%d scale %.1f: even the chip form of the %s cue does not fit the %d dp chip row' % (r['wid'], r['w'], r['h'], r['sc'], wid, chipw))
        V['nohint'].append((r['wid'], r['w'], r['h'], r['sc'], why, chipw, pick, r['g']))
    # text budgets
    # per-character width is REAL-valued (7 dp x scale), never truncated to an integer: floor(width / (charDp x scale)) is the conservative count (iteration 13, U9)
    charsper = lambda avail, s: chrome_charsper(avail, s, P['bannerCharDp'])
    def cap(w, s, icon):
        lead = (P['bannerIcon'] + 3 * P['bannerGap']) if icon else 2 * P['bannerGap']
        prim = P['bannerPrimary'] if icon else P['toastPrimary']
        return P['bannerLines'] * charsper(w - 2 * P['gutter'] - prim - P['bannerMore'] - lead, s)
    V['cap'] = {(w, s): cap(w, s, True) for w in (320, 360) for s in (1.0, 2.0)}
    V['tcap'] = {(w, s): cap(w, s, False) for w in (320, 360) for s in (1.0, 2.0)}
    lab = lambda prim: ((prim - 2 * P['btnPad']) // P['bannerCharDp'], (prim - 2 * P['btnPad']) // (P['bannerCharDp'] * 2))
    V['label'] = lab(P['bannerPrimary']); V['tlabel'] = lab(P['toastPrimary'])
    def expand(s): return re.sub(r'\{\w+(?::(\d+))?\}', lambda m: 'x' * (int(m.group(1)) if m.group(1) else P['dynMax']), s)
    def wrap(text, per):
        # greedy word wrap; a word breaks only after a hyphen; None when a piece is wider than a line
        lines, cur, first = 1, 0, True
        for w in text.split():
            pieces = re.findall(r'[^-]*-|[^-]+', w)
            for k, pc in enumerate(pieces):
                if len(pc) > per: return None
                sep = 0 if (k > 0 or first) else 1
                if cur + sep + len(pc) <= per: cur += sep + len(pc)
                else: lines += 1; cur = len(pc)
                first = False
        return lines
    V['wrap'] = wrap
    per = lambda w, s, icon: charsper(w - 2 * P['gutter'] - (P['bannerPrimary'] if icon else P['toastPrimary']) - P['bannerMore'] - ((P['bannerIcon'] + 3 * P['bannerGap']) if icon else 2 * P['bannerGap']), s)
    V['per'] = {(w, s, i): per(w, s, i) for w in (320, 360) for s in (1.0, 2.0) for i in (True, False)}
    def labelcheck(owner, lab, cp):
        if lab == '-': return
        if len(lab) > cp[0]: fail('%s primary label "%s" is %d chars, over the %d that fit the button at font scale 1.0' % (owner, lab, len(lab), cp[0]))
        for wd in lab.split():
            if len(wd) > cp[1]: fail('%s primary label word "%s" is %d chars, over the %d that fit the button at font scale 2.0 (a label may wrap only between words)' % (owner, wd, len(wd), cp[1]))
        if len(lab.split()) > 2: fail('%s primary label "%s" wraps to more than two lines at font scale 2.0' % (owner, lab))
    ranks = [b['rank'] for b in T['banner']]
    if ranks != list(range(1, len(ranks) + 1)): fail('banner ranks are not 1..n in order')
    for b in T['banner']:
        if '{' in b['summary'] + b['short']: fail('banner %s has a placeholder: a banner row is fixed text, dynamic values go to Details' % b['id'])
        if len(b['summary']) > V['cap'][(320, 1.0)]: fail('banner %s summary is %d chars, over the %d-char budget at 320 dp and font scale 1.0' % (b['id'], len(b['summary']), V['cap'][(320, 1.0)]))
        if b['short'] != '-' and len(b['short']) > V['cap'][(320, 2.0)]: fail('banner %s short title is %d chars, over the %d-char budget at 320 dp and font scale 2.0' % (b['id'], len(b['short']), V['cap'][(320, 2.0)]))
        ws = wrap(b['summary'], V['per'][(320, 1.0, True)])
        if ws is None or ws > P['bannerLines']: fail('banner %s summary "%s" wraps to %s lines by word at %d chars per line (320 dp, font scale 1.0), over the %d-line row' % (b['id'], b['summary'], 'more than the limit' if ws is None else ws, V['per'][(320, 1.0, True)], P['bannerLines']))
        if b['short'] != '-':
            wt = wrap(b['short'], V['per'][(320, 2.0, True)])
            if wt is None or wt > P['bannerLines']: fail('banner %s short title "%s" wraps to %s lines by word at %d chars per line (320 dp, font scale 2.0), over the %d-line row' % (b['id'], b['short'], 'more than the limit' if wt is None else wt, V['per'][(320, 2.0, True)], P['bannerLines']))
        else:
            if b['tier'] != 'Onboarding' or not re.search(r'other devices', b['summary']): fail('banner %s is a consent row (short title "-") but is not an onboarding row whose summary states the consequence for other devices' % b['id'])
        if b['primary'] == 'Learn more' and 'Details' in b['more']: fail('banner %s: the primary "Learn more" opens Details, so More must not repeat it' % b['id'])
        labelcheck('banner ' + b['id'], b['primary'], V['label'])
        if b['tier'] == 'Blocking' and 'Dismiss' in b['more']: fail('Blocking banner %s must not be dismissible' % b['id'])
    for t in T['toast']:
        if len(expand(t['summary'])) > V['tcap'][(320, 1.0)]: fail('toast %s summary is %d chars at the dynamic maximum, over the %d-char budget at 320 dp and font scale 1.0' % (t['id'], len(expand(t['summary'])), V['tcap'][(320, 1.0)]))
        if len(expand(t['short'])) > V['tcap'][(320, 2.0)]: fail('toast %s short title is %d chars, over the %d-char budget at 320 dp and font scale 2.0' % (t['id'], len(expand(t['short'])), V['tcap'][(320, 2.0)]))
        ws = wrap(expand(t['summary']), V['per'][(320, 1.0, False)]); wt = wrap(expand(t['short']), V['per'][(320, 2.0, False)])
        if ws is None or ws > P['bannerLines']: fail('toast %s summary "%s" wraps to %s lines by word at %d chars per line (320 dp, font scale 1.0), over %d' % (t['id'], expand(t['summary']), 'more than the limit' if ws is None else ws, V['per'][(320, 1.0, False)], P['bannerLines']))
        if wt is None or wt > P['bannerLines']: fail('toast %s short title "%s" wraps to %s lines by word at %d chars per line (320 dp, font scale 2.0), over %d' % (t['id'], t['short'], 'more than the limit' if wt is None else wt, V['per'][(320, 2.0, False)], P['bannerLines']))
        labelcheck('toast ' + t['id'], t['primary'], V['tlabel'])
        if t['primary'] == '-' and any(m not in ('Details', 'Dismiss') for m in t['more']): fail('toast %s has More actions other than Details and Dismiss but no primary action' % t['id'])
        if 'Dismiss' not in t['more'] or t['more'][-1] != 'Dismiss': fail('toast %s has no Dismiss as the last item of its More menu (a persistent toast needs a pointer and touch way out, UX-112)' % t['id'])
    # accessible names (WCAG 2.5.3, 2.4.6, 4.1.2): visible label contained in the name, the name adds the object, one name per action
    names = {}; ALLOW = {'Undo', 'Got it', 'Open', 'Learn more', 'Show'}; labels = {}
    for kind, rowsx in (('banner', T['banner']), ('toast', T['toast'])):
        for r in rowsx:
            lab, nm_ = r['primary'], r['name']
            if lab == '-':
                if nm_ != '-': fail('%s %s has no primary action but names one' % (kind, r['id']))
                continue
            if lab.lower() not in nm_.lower(): fail('%s %s accessible name "%s" does not contain the visible label "%s" (WCAG 2.5.3)' % (kind, r['id'], nm_, lab))
            if len(nm_.split()) <= len(lab.split()): fail('%s %s accessible name "%s" adds no object to the label "%s"' % (kind, r['id'], nm_, lab))
            if nm_ in names: fail('%s %s and %s share the accessible name "%s"' % (kind, r['id'], names[nm_], nm_))
            names[nm_] = r['id']; labels.setdefault(lab, []).append(r['id'])
    for lab, ids in labels.items():
        if len(ids) > 1 and lab not in ALLOW: fail('visible label "%s" is used by %s with different meanings; use distinct labels' % (lab, ', '.join(ids)))
    order = ['Blocking', 'State', 'Warning', 'Info', 'Onboarding']
    ti = [order.index(b['tier']) if b['tier'] in order else -1 for b in T['banner']]
    if -1 in ti or ti != sorted(ti): fail('banner tiers are not in the order %s' % ' > '.join(order))
    V['bannerN'] = len(T['banner']); V['toastN'] = len(T['toast'])
    # two-line primary label at font scale 2.0 must fit the toast row and the banner slot
    V['labelH2'] = 2 * P['bannerLabelLine'] * 2 + 2 * P['btnPad']
    if V['labelH2'] > P['toastRow2']: fail('a two-line label at font scale 2.0 is %d dp tall, over the %d dp toast row' % (V['labelH2'], P['toastRow2']))
    if V['labelH2'] > R['BannerSlot'][1]: fail('a two-line label at font scale 2.0 is %d dp tall, over the %d dp banner slot' % (V['labelH2'], R['BannerSlot'][1]))
    # consent rows: lines at the listed scales per window width, and the first scale (0.01 steps) at which the sheet replaces the row
    V['cscales'] = (1.0, 1.15, 1.3, 1.5, 2.0); V['cm'] = {}; V['cfirst'] = {}
    for b in T['banner']:
        if b['short'] != '-': continue
        for w in (320, 360):
            V['cm'][(b['id'], w)] = [wrap(b['summary'], per(w, sc, True)) for sc in V['cscales']]
            fs = None
            for k in range(100, 301):
                n = wrap(b['summary'], per(w, k / 100.0, True))
                if n is None or n > P['bannerLines']: fs = k / 100.0; break
            V['cfirst'][(b['id'], w)] = fs
            if V['cm'][(b['id'], w)][0] is None or V['cm'][(b['id'], w)][0] > P['bannerLines']: fail('consent row %s does not fit its row at font scale 1.0 and %d dp' % (b['id'], w))
    # price of widening the primary button so that the longest consent-style label fits one word at font scale 2.0
    need = 7 * P['bannerCharDp'] * 2 + 2 * P['btnPad']
    perw = (320 - 2 * P['gutter'] - need - P['bannerMore'] - (P['bannerIcon'] + 3 * P['bannerGap'])) // P['bannerCharDp']
    V['widen'] = (need, perw, sum(1 for b in T['banner'] if (wrap(b['summary'], perw) or 99) > P['bannerLines']))
    V['consent'] = [b['id'] for b in T['banner'] if b['short'] == '-']
    V['tiers'] = {}
    for b in T['banner']: V['tiers'][b['tier']] = V['tiers'].get(b['tier'], 0) + 1
    f = P['toolTarget']; m = P['mouseFloor']
    V['handles'] = (P['handleFull'] * f, P['handleCorners'] * f, P['handleFull'] * m, P['handleCorners'] * m)
    V['viewOne'] = P['viewTargets'] * P['toolTarget'] + (P['viewTargets'] - 1) * P['clusterGap']
    V['viewTwo'] = max(3, P['viewTargets'] - 3) * P['toolTarget'] + (max(3, P['viewTargets'] - 3) - 1) * P['clusterGap']
    return V

def chrome_render(T, V):
    P = T['params']; o = []
    o.append('flatExtent = pinned %d + %d tools x %d + strip controls %d = %d dp' % (V['pinned'], V['tools'], P['toolTarget'], sum(w for _, w in T['strip']), V['flatExtent']))
    o.append('groupedExtent = %d dp on technical sheets (%d split groups x %d + %d singles x %d), %d dp on diagram sheets (Geometry group absent)' % (V['groupedTech'], V['groups'], P['toolTarget'] * P['splitTargets'], V['singles'], P['toolTarget'], V['groupedDiagram']))
    o.append('flat needs a dock window at least %d dp wide or a rail window at least %d dp tall; %d tools are in %d split groups and %d are singles; at 320 dp wide %d non-pinned targets are visible; the top bar leaves the name %d dp at 320 dp and font scale 1.0 and %d dp at font scale 2.0' % (V['flatWindowWidth'], V['flatWindowHeight'], V['grouped'], V['groups'], V['singles'], V['visibleAt320'], V['nameAt320'], V['nameAt320x2']))
    o.append('view cluster: one row of %d targets is %d dp wide, two rows are %d dp wide' % (P['viewTargets'], V['viewOne'], V['viewTwo']))
    o.append('handle tiers (shorter side of the selection bounds on screen): all eight resize handles and rotation from %d dp (mouse-only %d dp), the four corner handles and rotation from %d dp (mouse-only %d dp), below that no on-canvas handles' % (V['handles'][0], V['handles'][2], V['handles'][1], V['handles'][3]))
    o.append('banner text budget (2 lines, severity icon included): %s' % ', '.join('%d chars at %d dp wide and font scale %.1f' % (V['cap'][(w, s)], w, s) for w in (320, 360) for s in (1.0, 2.0)))
    o.append('toast text budget (2 lines, no icon): %s' % ', '.join('%d chars at %d dp wide and font scale %.1f' % (V['tcap'][(w, s)], w, s) for w in (320, 360) for s in (1.0, 2.0)))
    o.append('primary button label (banner %d dp wide, toast %d dp wide): at most %d (toast %d) chars at font scale 1.0 and words of at most %d (toast %d) chars at font scale 2.0; a label wraps only between words, at most two words' % (P['bannerPrimary'], P['toastPrimary'], V['label'][0], V['tlabel'][0], V['label'][1], V['tlabel'][1]))
    o.append('line width in characters (summary at font scale 1.0, short title at 2.0): banner %d and %d at 320 dp wide, %d and %d at 360 dp; toast %d and %d at 320 dp, %d and %d at 360 dp; every summary and short title is word-wrapped greedily at that width (a word breaks only after a hyphen) and must fit the row\'s %d lines' % (V['per'][(320, 1.0, True)], V['per'][(320, 2.0, True)], V['per'][(360, 1.0, True)], V['per'][(360, 2.0, True)], V['per'][(320, 1.0, False)], V['per'][(320, 2.0, False)], V['per'][(360, 1.0, False)], V['per'][(360, 2.0, False)], P['bannerLines']))
    o.append('banner candidates: %d (%s), of which %d consent (%s: a sheet replaces the row when its wrapped summary exceeds the row\'s lines); toast catalogue: %d' % (V['bannerN'], ', '.join('%d %s' % (v, k) for k, v in V['tiers'].items()), len(V['consent']), ', '.join(V['consent']), V['toastN']))
    for (cid, w), ls in sorted(V['cm'].items(), key=lambda kv: -kv[0][1]) if False else sorted(V['cm'].items()):
        pass
    cid = V['consent'][0] if V['consent'] else ''
    if cid:
        o.append('consent row %s (summary "%s"): lines at font scale %s: %s; the sheet replaces the row from font scale %.2f at 320 dp wide and from %.2f at 360 dp wide' % (cid, [b for b in T['banner'] if b['id'] == cid][0]['summary'], ' / '.join('%.2f' % s_ if s_ != int(s_) else '%.1f' % s_ for s_ in V['cscales']), ', '.join('%d dp %s' % (w, ' / '.join('5+' if n is None else str(n) for n in V['cm'][(cid, w)])) for w in (320, 360)), V['cfirst'][(cid, 320)], V['cfirst'][(cid, 360)]) + ' (5+ means a word is wider than the line)')
    o.append('a two-word primary label at font scale 2.0 is %d dp tall (2 lines x %d dp x 2.0 + 2 x %d dp padding), inside the toast row (%d dp) and the banner slot (%d dp)' % (V['labelH2'], P['bannerLabelLine'], P['btnPad'], P['toastRow2'], T['rows']['BannerSlot'][1]))
    o.append('widening the primary button to %d dp so that a 7-character word such as "Upgrade" fits at font scale 2.0 would leave %d characters per line at 320 dp wide and font scale 1.0 (from 15) and push %d of the %d banner summaries past %d lines' % (V['widen'][0], V['widen'][1], V['widen'][2], V['bannerN'], P['bannerLines']))
    g = [r for r in V['rows'] if r['g'] == 'yes']; wo = max(g, key=lambda r: r['ovh'] * 1000 // r['ch'])
    o.append('overlay share: at most %d%% of the collapsed canvas (%s %d x %d at font scale %.1f; limit %d%%)' % ((wo['ovh'] * 100 + wo['ch'] // 2) // wo['ch'], wo['wid'], wo['w'], wo['h'], wo['sc'], P['overlayMaxPct']))
    o.append('windows without HintStatus (the Rows collapsed column and the permanent section of the layout): %s' % '; '.join('%s %d x %d at font scale %.1f (%s)' % (n, w, h, sc, why) for n, w, h, sc, why, _, _, _ in V['nohint']))
    o.append('cue chip, the first chip of the action chip row, form chosen per window (chip row dp; %s): %s' % (', '.join(V['cueforms']), '; '.join('%s %d x %d at %.1f: %d dp, %s' % (n, w, h, sc, cw_, ', '.join('%s "%s"' % (k, v) for k, v in pk.items())) for n, w, h, sc, why, cw_, pk, g in V['nohint'])))
    o.append('')
    o.append('| Window | Scale | Layout | Tools | Strip extent | Stack with every row | Canvas with every row | Rows collapsed | Canvas (collapsed) | Canvas with a Blocking banner | Minimum | Canvas width | Pan pad (share) | Toasts | Top bar (name dp) | View cluster, chip row, overlay height | Drawing width |')
    o.append('|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|')
    for r in V['rows']:
        ok = r['g'] == 'yes'
        o.append('| %s %d x %d%s | %.1f | %s | %s (%s) | %d | %d | %d | %s | %d (%d%%) | %d%s | %d | %d | %s %d.%d%% | %d | %s, %d | %s, chips %s, %d | %d (%d edge strip%s) |' % (
            r['wid'], r['w'], r['h'], '' if ok else ' (degraded, not claimed)', r['sc'], r['lay'], r['mode'], r['fits'], r['ext'], r['h'] - r['full'], r['full'], ', '.join(r['collapsed']) or 'none', r['ch'], r['pct'], r['worst'], '' if ok else ' (not claimed)', r['mn'], r['cw'], r['pad'], r['cov'] // 10, r['cov'] % 10, r['toasts'], r['tbtier'], r['namew'], r['cl'], 'beside' if r['beside'] else 'above', r['ovh'], r['drawW'], r['sides'], '' if r['sides'] == 1 else 's'))
    return '\n'.join(('- ' + x) if (x and not x.startswith('|')) else x for x in o)


# ---- structure counts
epics = re.findall(r'^### Epic (\d+\.\d+):', plan, re.M)
stories = re.findall(r'^#### Story (\d+\.\d+\.\d+):', plan, re.M)
tasks = []   # (id, size)
for m in re.finditer(r'^##### Task (\d+\.\d+\.\d+[a-z]?):(.*)$', plan, re.M):
    s = re.search(r'\((XS|S|M)\)', m.group(2))
    if not s: fail('task without size tag: ' + m.group(1)); continue
    tasks.append((m.group(1), s.group(1)))
size = {t: s for t, s in tasks}
if len(size) != len(tasks): fail('duplicate task ids')
mix = {k: sum(1 for _, s in tasks if s == k) for k in ('M', 'S', 'XS')}
gl = plan.split('## Domain Glossary')[1].split('## Pattern Decisions')[0]
glossary = len([l for l in gl.split('\n') if l.startswith('| `')])
ux20 = ux.split('## 20. UX acceptance criteria')[1].split('## 21.')[0]
uxids = re.findall(r'^- (UX-\d+)', ux20, re.M)
print('epics %d stories %d tasks %d (M %d / S %d / XS %d) glossary %d ux %d' % (len(epics), len(stories), len(tasks), mix['M'], mix['S'], mix['XS'], glossary, len(uxids)))

# ---- stated counts equal computed counts
stated = re.search(r'\*\*(\d+) epics, (\d+) stories, (\d+) tasks \((\d+) M / (\d+) S / (\d+) XS\)\*\*, (\d+) glossary terms, (\d+) UX criteria', plan)
if not stated: fail('plan.md Effort estimate lacks the stated-count sentence')
else:
    got = tuple(int(x) for x in stated.groups()); want = (len(epics), len(stories), len(tasks), mix['M'], mix['S'], mix['XS'], glossary, len(uxids))
    if got != want: fail('stated counts %s != computed %s' % (got, want))
m = re.search(r'\((\d+) terms\.\)', plan)
if not m or int(m.group(1)) != glossary: fail('glossary footer count mismatch')
m = re.search(r'\*\*(\d+) epics, (\d+) stories, (\d+) tasks\*\*', val)
if not m or tuple(map(int, m.groups())) != (len(epics), len(stories), len(tasks)): fail('validation.md header counts mismatch')
m = re.search(r'\*\*(\d+) criteria\*\*', val)
if not m or int(m.group(1)) != len(uxids): fail('validation.md UX count mismatch')

m = re.search(r'Count \(recounted by script\):.*\*\*(\d+)\*\*\.', ux)
if not m or int(m.group(1)) != len(uxids): fail('ux.md Count line does not end at the computed %d' % len(uxids))

# ---- UX ids cited literally in plan.md, and no ranges in plan
for u in uxids:
    if not re.search(re.escape(u) + r'(?!\d)', plan): fail('UX id not cited in plan.md: ' + u)
for r in re.findall(r'UX-\d+\s*(?:\.\.|to|-)\s*UX-\d+', plan.split('## Dependency Visualization')[1]):
    fail('UX range in plan body: ' + r)

# ---- edges and waves
eb = re.search(r'<!-- plan-edges:start -->\s*```text\n(.*?)```', plan, re.S)
E = {}
if not eb: fail('no plan-edges block')
else:
    for l in eb.group(1).split('\n'):
        l = l.split('#')[0].strip()
        if l:
            n, ds = l.split(':', 1); E[n.strip()] = ds.split()
W = {}
def wave(n, st=()):
    if n in W: return W[n]
    if n in st: fail('cycle at ' + n); return 0
    if n not in E: fail('edge to undefined node ' + n); return 0
    W[n] = 0 if (n == 'OWNER0' or E[n] == ['@OWNER0']) else (max([wave(x, st + (n,)) for x in E[n] if x != '@ZW']) if '@ZW' in E[n] else 1 + max([wave(x, st + (n,)) for x in E[n]] + [0]))
    return W[n]
for n in E: wave(n)
for n in E:
    if n != 'OWNER0' and n not in size and n not in stories and not any(s == n for s in stories): fail('node is neither a story nor a task: ' + n)
def node_of(t):
    if t in E: return t
    s = re.match(r'(\d+\.\d+\.\d+)', t).group(1)
    return s if s in E else None
for t in size:
    if node_of(t) is None: fail('task has no node: ' + t)
for s in stories:
    if s not in E and not any(re.match(re.escape(s) + r'[a-z]$', n) for n in E): fail('story has no node: ' + s)
dw = re.search(r'<!-- plan-waves:start -->\s*```text\n(.*?)```', plan, re.S)
if not dw: fail('no plan-waves block')
else:
    declared = {}
    for l in dw.group(1).strip().split('\n'):
        mm = re.match(r'W(\d+): (.*)$', l.strip())
        if mm:
            for n in mm.group(2).split(): declared[n] = int(mm.group(1))
    for n, w in W.items():
        if n != 'OWNER0' and declared.get(n) != w: fail('declared wave of %s is %s, computed %s' % (n, declared.get(n), w))
    for n in declared:
        if n not in W: fail('declared node not in edges: ' + n)
maxw = max(W.values()) if W else 0
_wid = [sum(1 for n, x in W.items() if x == k and n != 'OWNER0') for k in range(1, maxw + 1)]
_ws = re.search(r'W1 to W14 = ([\d, ]+) \(script-checked: the widest wave is W(\d+) with (\d+) nodes, the narrowest W(\d+) with (\d+)\)', plan)
if not _ws: fail('plan.md has no computed nodes-per-wave sentence')
elif [int(x) for x in _ws.group(1).replace(' ', '').split(',')] != _wid or (int(_ws.group(2)), int(_ws.group(3)), int(_ws.group(4)), int(_ws.group(5))) != (_wid.index(max(_wid)) + 1, max(_wid), _wid.index(min(_wid)) + 1, min(_wid)):
    fail('plan.md nodes-per-wave sentence differs from the computed widths %s' % _wid)
if re.search(r'3-8 agents' + r' wide', plan + req + val.split('```python check-plan')[0]): fail('the retired claim "3-8 agents wide" is still stated')
print('nodes %d, waves W1..W%d' % (len(W) - 1, maxw))

# ---- per-task file lists: every M task listed, 1..5 distinct paths, sizes agree
fb = re.search(r'<!-- plan-files:start -->\s*```text\n(.*?)```', plan, re.S)
FL = {}; NEWM = {}
if not fb: fail('no plan-files block')
else:
    for l in fb.group(1).strip().split('\n'):
        if not l.strip() or l.startswith('#'): continue
        p = [x.strip() for x in l.split('|')]
        if len(p) != 3: fail('bad file line: ' + l[:60]); continue
        tid, sz, fl = p; paths = [x.strip() for x in fl.split(';') if x.strip()]
        NEWM[tid] = {x[:-6] for x in paths if x.endswith(' (new)')}; paths = [x[:-6] if x.endswith(' (new)') else x for x in paths]
        if tid not in size: fail('file list for unknown task ' + tid); continue
        if sz != size[tid]: fail('file list size %s != heading size %s for %s' % (sz, size[tid], tid))
        if not 1 <= len(paths) <= 5 or len(set(paths)) != len(paths): fail('task %s lists %d files (need 1-5 distinct)' % (tid, len(paths)))
        FL[tid] = paths
for t, s in size.items():
    if s == 'M' and t not in FL: fail('M task without file list: ' + t)
# best-effort deliverable heuristic: a heading naming many comma-separated deliverables must list enough files
def deliverables(h):
    h = h.split(': ', 1)[1] if ': ' in h else h
    h = re.sub(r'\s\((?:XS|S|M)\).*$', '', h)
    h = re.split(r' after | needs ', h)[0]
    for _ in range(3): h = re.sub(r'\([^()]*\)', '', h)
    h = re.sub(r'`[^`]*`', 'X', h).split(':')[0]
    return len([x for x in re.split(r',| and | plus ', h) if x.strip()])
for mm in re.finditer(r'^##### Task (\d+\.\d+\.\d+[a-z]?):(.*)$', plan, re.M):
    t = mm.group(1)
    if t in FL and deliverables(mm.group(2)) > len(FL[t]) + 4:
        fail('task %s heading names %d deliverables but lists %d files (split the task or list every file)' % (t, deliverables(mm.group(2)), len(FL[t])))
print('file lists: %d tasks (%d M)' % (len(FL), sum(1 for t in FL if size[t] == 'M')))

# ---- reference integrity across the four documents
allt, alls = set(size), set(stories)
for name, txt in (('plan', plan), ('validation', val), ('ux', ux), ('requirements', req)):
    # singular and unsuffixed ids, plural id lists, and bare letter lists after a story id
    for mm in re.finditer(r'Tasks? (\d+\.\d+\.\d+[a-z]?(?:(?:, | and |, and | to |/)\d+\.\d+\.\d+[a-z]?)*)', txt):
        for i in re.findall(r'\d+\.\d+\.\d+[a-z]?', mm.group(1)):
            if i not in allt: fail('%s.md references missing Task %s' % (name, i))
    for line in txt.split('\n'):
        for mm in re.finditer(r'Tasks ([a-z](?:(?:, | and |, and )[a-z])+|[a-z](?= and| need| have))(?! [a-z]{2})', line):
            before = re.findall(r'\d+\.\d+\.\d+', line[:mm.start()])
            if not before: continue
            for ch in re.findall(r'\b[a-z]\b', mm.group(1)):
                if before[-1] + ch not in allt: fail('%s.md says "Tasks %s" after %s but Task %s%s does not exist' % (name, mm.group(1), before[-1], before[-1], ch))
    for mm in re.finditer(r'Story (\d+\.\d+\.\d+)\b', txt):
        if mm.group(1) not in alls: fail('%s.md references missing Story %s' % (name, mm.group(1)))


# ---- story heading "(needs ...)" against the edge block; benign story-to-task refinements are whitelisted explicitly
BENIGN = {('1.1.5', '1.2.4'): '1.2.4a', ('6.2.7', '6.2.3'): '6.2.3a', ('6.2.7', '3.6.4'): '3.6.4b'}
for mm in re.finditer(r'^#### Story (\d+\.\d+\.\d+): (.*)$', plan, re.M):
    sid, h = mm.groups()
    if 'needs' not in h: continue
    deps = re.findall(r'\b\d+\.\d+\.\d+[a-z]?\b', h[h.index('needs') + 5:])
    have = set(E.get(sid, []))
    for t in E:
        if re.fullmatch(re.escape(sid) + r'[a-z]', t): have |= set(E[t])
    for d in deps:
        if d in have: continue
        if BENIGN.get((sid, d)) in have: continue
        fail('Story %s heading says it needs %s but the edge block (story and its tasks) does not list it' % (sid, d))

# ---- task heading "needs <ids>" against the edge carrier (the task's own node, else its story's node): removing the edge fails here
def carrier_deps(t):
    n = node_of(t); return set(E.get(n, [])) if n else set()
for mm in re.finditer(r'^##### Task (\d+\.\d+\.\d+[a-z]?):(.*)$', plan, re.M):
    t, h = mm.groups()
    mq = re.search(r'(?:; | )needs ((?:Tasks? |Stor(?:y|ies) )?\d.*)$', h)
    if not mq: continue
    have = carrier_deps(t)
    for d in re.findall(r'\b\d+\.\d+\.\d+[a-z]?\b', re.split(r'; |\(S\)|\(XS\)|\(M\)', mq.group(1))[0]):
        dn = node_of(d) if d in size else (d if d in E else None)
        sd = re.match(r'(\d+\.\d+\.\d+)', d).group(1)
        if not (d in have or (dn and dn in have) or sd in have): fail('Task %s heading says it needs %s but its edge carrier %s does not list it' % (t, d, node_of(t)))
nneeds = len(re.findall(r'^##### Task [^\n]*(?:; | )needs (?:Tasks? |Stor(?:y|ies) )?\d', plan, re.M))
print('task headings with a "needs" clause checked against their edge carrier: %d' % nneeds)

# ---- P3 pre-check hold: the held set is recomputed from the KEPT closure and every root has an edge from the gate node
def blockof(name):
    mm = re.search(r'<!-- %s:start -->\s*```text\n(.*?)```' % name, plan, re.S)
    return mm.group(1) if mm else None
hb = blockof('plan-hold')
KEPTN = []
if hb is None: fail('plan.md has no plan-hold block')
else:
    H = {}
    for l in hb.strip().split('\n'):
        k, v = l.split(':', 1); H[k.strip()] = v.split()
    gate = H['GATE'][0]
    SKIPN = {'OWNER0', gate, '0.1.8c'}   # the OWNER0 session and the pre-check inside it are never part of any closure
    def closure(seed):
        seen = set(); st = list(seed)
        while st:
            x = st.pop()
            for n in ([x] if x in E else []) + [m for m in E if re.fullmatch(re.escape(x) + r'[a-z]', m)]:
                if n in seen or n in SKIPN: continue
                seen.add(n); st += E[n]
        return seen
    cl_naive = closure(H['KEPT'])
    # NOTKEPT: lettered owner/measurement tasks that the story-level closure drags in although no kept node names them (Task 0.1.2d A6, Task 0.1.3d float drift)
    NOTK = H.get('NOTKEPT', [])
    if not NOTK: fail('plan-hold block has no NOTKEPT line')
    for n in NOTK:
        if n not in cl_naive: fail('NOTKEPT node %s is not even in the naive closure (stale entry)' % n)
        if any(n in E[k] for k in cl_naive if k not in NOTK): fail('NOTKEPT node %s is named by an edge of a kept node, so it IS needed' % n)
    def closure_skip(seed, skip):
        seen = set(); st = list(seed)
        while st:
            x = st.pop()
            for n in ([x] if x in E else []) + [m for m in E if re.fullmatch(re.escape(x) + r'[a-z]', m)]:
                if n in seen or n in skip or n in SKIPN: continue
                seen.add(n); st += E[n]
        return seen
    cl = closure_skip(H['KEPT'], set(NOTK))
    KEPTN = sorted(n for n in cl if W.get(n, 0) > 1)
    KEPTW1 = sorted(n for n in cl if W.get(n, 0) == 1)
    if W.get(gate) != 0 or E.get(gate) != ['@OWNER0']: fail('the pre-check gate %s must be performed inside the OWNER0 session (edge line `%s: @OWNER0`, wave 0), not as a wave-1 node' % (gate, gate))
    _nogate = [n for n in E if n not in ('OWNER0', gate) and not (lambda r: r(r, n, ()))(lambda r, x, seen: x == gate or any(r(r, d, seen + (x,)) for d in E.get(x, []) if d not in seen and d != '@OWNER0'))]
    if _nogate: fail('nodes with no path from the pre-check gate %s (a stop or a hold would not reach them): %s' % (gate, _nogate))
    held = sorted(n for n in E if re.match(r'[23]\.', n) and n not in cl and n not in H['NONUI'])
    if sorted(H['HELD']) != held: fail('plan-hold HELD line differs from the computed held set: missing %s, extra %s' % (sorted(set(held) - set(H['HELD'])), sorted(set(H['HELD']) - set(held))))
    roots = [n for n in held if not any(d in held for d in E[n])]
    for r in roots:
        if gate not in E[r]: fail('held root %s has no edge from the pre-check gate %s' % (r, gate))
    def reaches(n, tgt, seen=()):
        return n == tgt or any(reaches(d, tgt, seen + (n,)) for d in E.get(n, []) if d not in seen)
    for n in held:
        if not reaches(n, gate): fail('held node %s does not reach the pre-check gate %s through the edge block' % (n, gate))
    stop = set(E) - {'OWNER0'} - cl - closure(H['GATE']) - set(H['NONUI']) - set().union(*[closure([x]) for x in H['NONUI']])
    for n in H.get('STOPNAMED', []):
        if n not in stop: fail('STOPNAMED node %s is not in the computed pre-check stop set (outside the kept closure, the gate closure and the NONUI tooling)' % n)
    if not H.get('STOPNAMED'): fail('plan-hold block has no STOPNAMED line')
    if not set(held) <= stop: fail('held nodes missing from the stop set: %s' % sorted(set(held) - stop))
    for row_ in ('| Pre-check FAIL, "stop entirely"', '| Pre-check FAIL, "stop at the pure engines"', '| Pre-check FAIL, "continue, adoption is not the goal"'):
        if row_ not in req: fail('requirements.md combined-outcomes table lacks the row ' + row_[2:])
    if 'every outcome except v1 extended only and a pre-check stop' not in plan: fail('plan.md READERONLY sentence does not exclude a pre-check stop')
    print('pre-check hold: %d held nodes, %d roots, gate %s (wave 0, inside OWNER0), every one of the %d other nodes has a path from it; kept closure %d nodes (%d outside W1 + %d in W1); stop set %d nodes (names %s)' % (len(held), len(roots), gate, len(E) - 2, len(cl), len(KEPTN), len(KEPTW1), len(stop), ' '.join(H['STOPNAMED'])))

# ---- the prose edge table of "Dependency Visualization" against the authoritative edge block (iteration 10)
def _prose_edge_rows(txt):
    seg = re.split(r'\n#{2,3} ', txt.split('**Story-level dependency edges**')[1])[0]
    rows = []
    for l in seg.split('\n'):
        if not l.startswith('| ') or l.startswith('| Story') or l.startswith('|---'): continue
        c = [x.strip() for x in l.strip().strip('|').split(' | ', 1)]
        if len(c) == 2: rows.append(c)
    return rows
_ID = r'(?<![\w.])(\d+\.\d+\.\d+[a-z]?)(?![\w.])'
def _fan(n):
    r_ = [m for m in E if m == n or re.fullmatch(re.escape(n) + r'[a-z]', m)]
    if not r_ and n in size and re.match(r'(\d+\.\d+\.\d+)', n).group(1) in E: r_ = [re.match(r'(\d+\.\d+\.\d+)', n).group(1)]
    return r_
def _reach(srcs):
    seen = set(); st = list(srcs)
    while st:
        x = st.pop()
        for d in E.get(x, []):
            if d not in seen: seen.add(d); st.append(d)
    return seen
GATE_EXEMPT = {'0.1.7c', '0.1.2d', '6.2.4e', '0.1.8c'}
def prose_edge_check(txt):
    bad = []; nrows = 0
    for c1, c2 in _prose_edge_rows(txt):
        nodes = re.findall(_ID, c1)
        if not re.search(_ID + r': ', c2):
            bare = re.sub(r'\([^)]*\)', '', c2)
            if not all(re.fullmatch(r'\d+\.\d+\.\d+[a-z]?|OD-\d+', p_.strip()) for p_ in bare.split(',')) or not all(_fan(d) for d in re.findall(_ID, bare)):
                continue
            c2 = bare
        if re.search(_ID + r': ', c2):
            pairs = [(m.group(1), re.findall(_ID, m.group(2))) for m in re.finditer(_ID + r': ([^;]*)', c2)]
        else:
            pairs = [(n, re.findall(_ID, c2)) for n in nodes]
        for n, deps in pairs:
            src = _fan(n); nrows += 1
            if len(src) == 1:
                listed = set(deps) | {y for d_ in deps for y in _fan(d_)}
                for x_ in E.get(src[0], []):
                    if x_ in ('OWNER0', '@OWNER0') or x_ in GATE_EXEMPT or x_ in nodes or x_ in listed: continue
                    bad.append('prose edge row of %s lacks the edge dependency %s that the edge block lists (the check is bidirectional)' % (n, x_))
            if not src: bad.append('prose edge row names %s, which is not a node' % n); continue
            r = _reach(src)
            for d in deps:
                if d == n: continue
                tg = set(_fan(d))
                if not tg: bad.append('prose edge row of %s names %s, which is not a node' % (n, d))
                elif not (tg & r): bad.append('prose edge row of %s claims a dependency on %s that the edge block does not have' % (n, d))
    return bad, nrows
_pb, _pn = prose_edge_check(plan)
for m_ in _pb: fail(m_)
_probe = plan.replace('| 1.1.4 technical data home (accessors, layer mirror, refusal predicate) | 1.1.3 |', '| 1.1.4 technical data home (accessors, layer mirror, refusal predicate) | 1.1.3, 3.6.4a |')
if _probe == plan or not prose_edge_check(_probe)[0]: fail('prose edge check self-test: a planted prose dependency 1.1.4 -> 3.6.4a was not flagged')
_probe2 = plan.replace('| 1.4.7k saver durability states | 1.4.7f, 1.4.4, 1.4.3 |', '| 1.4.7k saver durability states | 1.4.4, 1.4.3 |')
if _probe2 == plan or not prose_edge_check(_probe2)[0]: fail('prose edge check self-test: deleting 1.4.7f from the prose row of 1.4.7k was not flagged (the check must be bidirectional)')
print('prose edge table: %d node rows of plain id lists checked against the edge block (rows with prose caveats are skipped), planted wrong edge and planted deleted edge flagged, bidirectional' % _pn)

# ---- ADR wording of the flag names: canonical spelling, required names, retired phrases (iteration 10)
_adr = {fn: rd('decisions/' + fn) for fn in sorted(os.listdir(os.path.join(D, 'decisions'))) if fn.endswith('.md')}
_names = ('V2Mode', 'ConversionOffered', 'ConversionReady', 'NewProjectsOnly', 'NewSketchProject')
_retired = ('an empty or brand-new project', 'a stored boolean reads', 'stored `true` reads', 'resolved where `homeSketch` is', 'all-v2 project (an empty')
def adr_wording_check(adr, docs):
    bad = []
    for fn, txt in adr.items():
        for nm in _names:
            for m_ in re.finditer(re.escape(nm), txt, re.I):
                if m_.group(0) != nm: bad.append('%s spells the flag name %s as %s' % (fn, nm, m_.group(0)))
        for ph in _retired:
            if ph in txt: bad.append('%s still says "%s"' % (fn, ph))
    for fn, nms in (('ADR-005', _names), ('ADR-001', ('ConversionOffered', 'NewSketchProject'))):
        t_ = ''.join(x for k, x in adr.items() if k.startswith(fn))
        for nm in nms:
            if nm not in t_: bad.append('%s does not name %s' % (fn, nm))
    for dn, txt in docs.items():
        for ph in _retired:
            if ph in txt: bad.append('%s still says "%s"' % (dn, ph))
        for nm in _names:
            for m_ in re.finditer(r'`' + re.escape(nm) + r'`', txt, re.I):
                if m_.group(0) != '`' + nm + '`': bad.append('%s spells the flag name %s as %s' % (dn, nm, m_.group(0)))
    return bad
_docs = {'plan.md': plan, 'ux.md': ux, 'requirements.md': req}
for m_ in adr_wording_check(_adr, _docs): fail(m_)
_k5 = [k for k in _adr if k.startswith('ADR-005')][0]
_pl = dict(_adr); _pl[_k5] = _adr[_k5].replace('`ConversionOffered`', '`conversionOffered`', 1)
_pl2 = dict(_adr); _pl2[_k5] = _adr[_k5] + ' (an empty or brand-new project qualifies)'
if not adr_wording_check(_pl, _docs) or not adr_wording_check(_pl2, _docs): fail('ADR wording check self-test: a planted misspelled name or retired phrase was not flagged')
print('ADR wording: %d ADRs, %d canonical names, %d retired phrases scanned in ADRs and 3 documents, planted errors flagged' % (len(_adr), len(_names), len(_retired)))

# ---- P2 parked outcomes: sets, the reduced-form list, and the reader-only release independence
pb = blockof('plan-parked')
PK = {}; RED = {}
if pb is None: fail('plan.md has no plan-parked block')
else:
    for l in pb.strip().split('\n'):
        k, v = l.split(':', 1)
        if k.strip() == 'REDUCED':
            p = [x.strip() for x in v.split('|')]
            if len(p) != 3 or any(not x for x in p): fail('bad REDUCED line: ' + l[:60]); continue
            RED[p[0]] = p
        else: PK[k.strip()] = v.split()
    PARK_ANY = set(PK.get('JOB2UI', [])) | set(PK.get('INTEROP', [])) | set(PK.get('FLAGREL', [])) | set(PK.get('OPFSSPIKE', []))
    gui6 = ['4.2.2', '4.2.3', '4.2.5', '4.2.6b', '4.2.7b', '4.2.7d']
    if sorted(PK.get('JOB2UI', [])) != sorted(gui6 + ['6.2.4f']): fail('plan-parked JOB2UI is not the six constraint-UI items plus Task 6.2.4f')
    if sorted(PK.get('INTEROP', [])) != ['5.1.5', '5.2.3'] or PK.get('FLAGREL') != ['6.2.5c']: fail('plan-parked INTEROP or FLAGREL differs from the GATED list')
    for n, d in E.items():
        if n in PARK_ANY or n == 'OWNER0': continue
        if set(d) & PARK_ANY and n not in RED: fail('node %s has an edge into a parked node %s but no REDUCED line (plan "Parked-dependency reductions")' % (n, sorted(set(d) & PARK_ANY)))
    for n in RED:
        if n not in E or not (set(E[n]) & PARK_ANY): fail('REDUCED line names %s, which has no edge into a parked node' % n)
    for r in PK.get('READERONLY', []):
        bad = (closure([r]) - {r}) & (PARK_ANY | {'6.2.4e', '6.2.5a', '6.2.4f'})
        if bad: fail('reader-only release %s depends on the A1 gate (Task 6.2.4e), Task 6.2.4f, Task 6.2.5a or a parked node: %s' % (r, sorted(bad)))
        if r not in E.get('6.2.5c', []): fail('the flag release 6.2.5c has no edge from the reader-only release %s (A4 GO: reader-only ships first)' % r)
    for t_ in sum((PK.get(k, []) for k in ('RELEASE', 'IMPEXP')), []):
        if t_ not in size and t_ not in stories: fail('plan-parked names unknown task or story ' + t_)
    print('parked outcomes: %d parked nodes, %d reduced-form lines, reader-only release %s has no edge from the A1 gate (Task 6.2.4e)' % (len(PARK_ANY), len(RED), ','.join(PK.get('READERONLY', []))))

# ---- placeholders, owner defaults, wave mentions next to task ids
for name, txt in (('plan', plan), ('validation', val), ('ux', ux), ('requirements', req)):
    if '@' * 2 in txt: fail('unfilled double-at placeholder in %s.md' % name)
uq = plan.split('## Unresolved Questions')[1].split('## Dependency Visualization')[0]
nd = max(int(x) for x in re.findall(r'^  - D-(\d+) ', uq, re.M) or ['0'])
for k in range(1, nd + 1):
    if not re.search(r'^  - D-%d ' % k, uq, re.M): fail('owner default D-%d missing' % k)
for name, txt in (('plan', plan), ('validation', val), ('requirements', req), ('ux', ux)):
    for mm in re.finditer(r'D-1(?:\.\.| to )D-(\d+)', txt):
        if int(mm.group(1)) != nd: fail('%s.md names the default range D-1..D-%s but the plan lists D-1..D-%d' % (name, mm.group(1), nd))
for name, txt in (('plan', plan), ('validation', val), ('requirements', req)):
    for mm in re.finditer(r'Tasks? (\d+\.\d+\.\d+[a-z]?)[^.\n;]{0,70}?\b(?:wave )?W(\d+)\b', txt):
        n = node_of(mm.group(1)) if mm.group(1) in size else None
        if n and W.get(n) and W[n] != int(mm.group(2)) and 'after' not in mm.group(0) and 'before' not in mm.group(0):
            fail('%s.md says Task %s is in W%s but its node %s is W%d' % (name, mm.group(1), mm.group(2), n, W[n]))


# ---- chrome table: ONE source of every layout figure; derived figures computed here and compared with the prose
CTB = chrome_parse(ux); CV = None
if CTB is None: fail('ux.md has no chrome-table block')
else:
    CV = chrome_compute(CTB, fail); comp = chrome_render(CTB, CV)
    mm = re.search(r'<!-- chrome-computed:start -->\n(.*?)\n<!-- chrome-computed:end -->', ux, re.S)
    if not mm or mm.group(1) != comp: fail('ux.md "Computed from the table" block differs from the script output (run --chrome and paste it)')
    if '--chrome' in sys.argv: print(comp)
    strip_blocks = lambda t: re.sub(r'<!-- chrome-computed:start -->.*?<!-- chrome-computed:end -->', '', re.sub(r'<!-- chrome-table:start -->.*?```\n.*?```', '', t, flags=re.S), flags=re.S)
    docs = {'ux': strip_blocks(ux), 'plan': plan, 'validation': val.split('```python check-plan')[0], 'requirements': req}
    allowed = lambda vals: set(str(v) for v in vals)
    stacks = allowed(r[17 - 8] for r in [()]) if False else set()
    canv = set(); stk = set()
    for r in CV['rows']:
        canv |= {r['full'], r['ch'], r['worst'], r['mn']}; stk.add(r['h'] - r['full']); stk.add(r['h'] - r['ch'])
    scans = [
        (r'(?i)flat ?extent[^.\n;]{0,40}?\b(\d{3,4}) dp', {CV['flatExtent']}, 'flatExtent'),
        (r'(?i)grouped ?extent[^.\n;]{0,40}?\b(\d{3,4}) dp', {CV['groupedTech'], CV['groupedDiagram']}, 'groupedExtent'),
        (r'(?i)\bstack[^.\n;]{0,24}?\b(\d{3}) dp', stk, 'stack'),
        (r'(?i)\bcanvas(?: height)?(?: is| of| at)?[^.\n;]{0,14}?\b(\d{3}) dp', canv | {200, 240}, 'canvas height'),
    ]
    nscan = 0
    for name, txt in docs.items():
        for rx, okv, label in scans:
            for mm in re.finditer(rx, txt):
                nscan += 1
                if int(mm.group(1)) not in okv: fail('%s.md states a %s of %s dp that the chrome table does not compute (%s)' % (name, label, mm.group(1), sorted(okv)))
        for stale in ('908 dp', '784 dp', '732 dp', '96 dp wide', 'ten cases', 'ten window', 'eight banner', 'all eight banner',
                      "Old apps can't open project", 'Upgrade affects whole project', 'new sketch editor', 'Enable Sketch engine v2', '[Fix it]', '[Go there]', 'banner [Enable]',
                      'font scale 1.5 and above at 320 dp', 'where its summary does not fit the current width', 'Pen found: Finger drawing off', 'Edge pan stopped working.', 'Tools, zoom, keys', 'Single-key shortcuts are off.', 'Arrow unbound from deleted shape', 'Engine change applies next time',
                      "Layer 'Walls' is locked", 'Keep classic'):
            if stale in txt: fail('%s.md still says "%s", a figure the chrome table now computes or removed' % (name, stale))
    # positive controls: each scan regex must flag a planted wrong figure, else a '0 prose figures' result would be vacuous
    ctl = {'flatExtent': 'flat extent of 111 dp', 'groupedExtent': 'grouped extent 111 dp', 'stack': 'stack of 111 dp', 'canvas height': 'canvas height is 111 dp'}
    ctlok = 0
    for rx, okv, label in scans:
        mm = re.search(rx, ctl[label])
        if mm and int(mm.group(1)) not in okv: ctlok += 1
        else: fail('prose-figure scan "%s" does not match its planted wrong figure, so it cannot detect drift' % label)
    # consent threshold figures quoted in prose must equal the computed ones
    cid0 = CV['consent'][0]
    for name, txt in docs.items():
        for mm in re.finditer(r'from font scale ([0-9.]+) at 320 dp', txt):
            nscan += 1
            if abs(float(mm.group(1)) - CV['cfirst'][(cid0, 320)]) > 1e-9: fail('%s.md says the consent sheet starts at font scale %s at 320 dp; computed %.2f' % (name, mm.group(1), CV['cfirst'][(cid0, 320)]))
        for mm in re.finditer(r'from ([0-9.]+) at 360 dp', txt):
            nscan += 1
            if abs(float(mm.group(1)) - CV['cfirst'][(cid0, 360)]) > 1e-9: fail('%s.md says the consent sheet starts at %s at 360 dp wide; computed %.2f' % (name, mm.group(1), CV['cfirst'][(cid0, 360)]))
    # toast ids are all named in the banner-slot paragraph; label exceptions in prose equal the script allow-list
    mt = re.search(r'\(the (\d+) rows of the `toast` table:(.*?)\)\. Success|\(the (\d+) rows of the `toast` table:(.*?)\), not banner candidates', ux, re.S)
    if not mt: fail('ux.md banner-slot paragraph does not list the toast rows')
    else:
        body = mt.group(2) or mt.group(4); cnt = int(mt.group(1) or mt.group(3))
        miss = [t_['id'] for t_ in CTB['toast'] if '`%s`' % t_['id'] not in body]
        if miss or cnt != len(CTB['toast']): fail('ux.md banner-slot paragraph omits toast rows %s or states %d rows for %d' % (miss, cnt, len(CTB['toast'])))
    if 'Undo, Got it, Open, Learn more and Show' not in ux: fail('ux.md does not state the label-reuse exceptions Undo, Got it, Open, Learn more and Show')
    print('chrome self-test: %d/%d scan regexes flag a planted wrong figure; consent matrix: %s' % (ctlok, len(scans), '; '.join('%d dp %s' % (w, '/'.join(str(n) for n in CV['cm'][(cid0, w)])) for w in (320, 360))))
    print('chrome: %d prose figures checked against the table; flat %d, grouped %d/%d, banner candidates %d, toast catalogue %d' % (nscan, CV['flatExtent'], CV['groupedTech'], CV['groupedDiagram'], CV['bannerN'], CV['toastN']))
    # onboarding rows of the recurrence table follow the banner rank
    sec16 = ux.split('## 16. Diagnostics host')[1].split('## 17.')[0]
    rows16 = [(int(a), b) for a, b in re.findall(r'^\| (\d+) \| `(\w+)`', sec16, re.M)]
    want = [(b['rank'], b['id']) for b in CTB['banner'] if b['tier'] == 'Onboarding']
    if rows16 != want: fail('ux.md section 16 recurrence rows %s differ from the banner table onboarding rows %s' % (rows16, want))
    # SceneLimits field count
    lim = plan.split('**Canonical `SceneLimits` (')[1].split('\n')[0]
    nfields = len(re.findall(r'`(?:max|min|soft|slow)\w+=', lim)); claimed = int(re.match(r'(\d+) fields', lim).group(1))
    if nfields != claimed: fail('SceneLimits list names %d fields but says %d' % (nfields, claimed))
    for name, txt in (('plan', plan), ('validation', val)):
        for mm in re.finditer(r'(\d+)-field `?SceneLimits|canonical field list of (\d+) fields|(\d+)-field list|(\d+)-field canonical', txt):
            n = int([g for g in mm.groups() if g][0])
            if n != claimed: fail('%s.md says a %d-field SceneLimits but the list has %d' % (name, n, claimed))

    # keyboard order: ONE tab order in section 10; the F6 regions of section 17 are a subsequence of it
    s10 = ux.split('## 10. Non-drag transform mode')[1].split('## 11.')[0]
    mt = re.search(r'Tab order, fixed and tested.*?refer to:(.*?)\. Shift\+Tab reverses', s10, re.S)
    stops = ['top bar', 'notice region', 'tool strip', 'canvas', 'action chip row', 'crosshair pad', 'selection bar', 'side-pane tabs', 'Notices', 'status line']
    pos = [mt.group(1).find(x) for x in stops] if mt else [-1]
    if -1 in pos or pos != sorted(pos): fail('ux.md section 10 tab order does not list %s in this order' % ', '.join(stops))
    s17 = ux.split('## 17. Keyboard map')[1].split('## 18.')[0]
    mf = re.search(r'cycle the regions of that order without touching the elements: (.*?), \(c\)', s17, re.S)
    regs = ['top bar', 'notice region', 'tool strip', 'canvas', 'selection bar', 'side panel', 'status line']
    rp = [mf.group(1).find(x) for x in regs] if mf else [-1]
    if -1 in rp or rp != sorted(rp): fail('ux.md section 17 F6 regions do not list %s in this order' % ', '.join(regs))
    if 'Skip canvas elements' not in s17 or 'NEXT stop of the tab order' not in s17: fail('ux.md section 17 skip link does not land on the next stop of the tab order')

    # combined outcomes table of requirements.md: every A1 result, and the A6 DEFER rows, are covered; the plan cites it
    ct = req.split('## Combined outcomes: A1 x A4 x A6')
    if len(ct) < 2: fail('requirements.md lacks the "Combined outcomes: A1 x A4 x A6" table')
    else:
        tb = ct[1].split('## A4 and A5 decision rules')[0]
        for key in ('| PROCEED |', '| ADJUST (first) |', '| ADJUST (second) |', '| PIVOT |', 'DEFER'):
            if key not in tb: fail('combined outcomes table lacks a row for %s' % key.strip('| '))
        if 'Combined outcomes: A1 x A4 x A6' not in plan: fail('plan.md does not cite the combined outcomes table')
        for l in tb.split('\n'):
            if l.startswith('| ') and not l.startswith('|---'):
                cells = [c.strip() for c in l.strip().strip('|').split('|')]
                if any(c == '' for c in cells): fail('combined outcomes table row has an empty cell: %s' % l[:60])
        for frag in ('paired measurement', 'n = 1 by design', 'PIVOT, then ADJUST, then PROCEED'):
            if frag not in req: fail('requirements.md A1 gate lacks "%s"' % frag)
        if 'paired' not in plan.split('##### Task 6.2.4e')[1].split('\n')[0]: fail('Task 6.2.4e heading does not name the paired measurement')
    for frag in ('announces the FULL summary', 'accessible name', 'Font scales above 2.0', 'consent row'):
        if frag not in ux: fail('ux.md section 1 lacks the rule text "%s"' % frag)
    for doc, txt in (('plan', plan), ('validation', val.split('```python check-plan')[0])):
        if 'gen-chrome-fixture.py' not in txt: fail('%s.md does not name the chrome-table fixture generator' % doc)

# ---- effort
CU = {'XS': 20, 'S': 55, 'M': 130}
def cost(t): return CU[size[t]]
def costof(x):
    # a task id costs itself; a story or node id costs every task whose node it is
    if x in size: return cost(x)
    return sum(cost(t) for t in size if node_of(t) == x)
def setcost_ids(ids): return sum(costof(x) for x in ids)
if '--effort' in sys.argv:
    byep = {}
    for t, s in tasks:
        ep = '.'.join(t.split('.')[:2]); byep.setdefault(ep, []).append(t)
    sty = {}
    for s in stories: sty.setdefault('.'.join(s.split('.')[:2]), []).append(s)
    tot = 0
    for ep in epics:
        ts = byep[ep]; raw = sum(cost(t) for t in ts); tot += raw
        mixs = ' '.join('%d%s' % (sum(1 for t in ts if size[t] == k), k) for k in ('M', 'S', 'XS') if any(size[t] == k for t in ts))
        print('| %s | %d | %d | %s | %dk | 12%% | %.2fM |' % (ep, len(sty[ep]), len(ts), mixs, raw, raw * 1.48 / 1000))
    print('TOTAL raw %dk CU %.2fM x1.75 %.2fM' % (tot, tot * 1.48 / 1000, tot * 1.48 * 1.75 / 1000))
    j2 = sum(cost(t) for t in size if t.startswith(('4.1.', '4.2.')))
    print('JOB2EPICS (4.1+4.2) raw %dk CU %.2fM = %.1f%% of total' % (j2, j2 * 1.48 / 1000, 100.0 * j2 / tot))
    def upto(n, excl=()):
        return sum(cost(t) for t in size if node_of(t) and 0 < W[node_of(t)] <= n and node_of(t) not in excl)
    for n in (1, 2, 9, 10):
        r = upto(n); print('W1-W%d raw %dk CU %.2fM (%.1f%% of total)' % (n, r, r * 1.48 / 1000, 100.0 * r / tot))
    def setcost(nodes):
        return setcost_ids(sorted(nodes))
    kept = setcost(set(KEPTN)); print('KEPT (closure of the plan-hold KEPT line outside W1, %d nodes) raw %dk CU %.2fM' % (len(KEPTN), kept, kept * 1.48 / 1000))
    keptw1 = setcost(set(KEPTW1)); print('KEPT in W1 (%d spike nodes the kept closure needs: %s) raw %dk CU %.2fM; whole closure %d nodes raw %dk CU %.2fM (%.1f%% of total)' % (len(KEPTW1), ' '.join(KEPTW1), keptw1, keptw1 * 1.48 / 1000, len(KEPTN) + len(KEPTW1), kept + keptw1, (kept + keptw1) * 1.48 / 1000, 100.0 * (kept + keptw1) / tot))
    print('agent work started before the pre-check is read (nodes of wave <= the gate wave, gate excluded): raw %dk' % sum(cost(t) for t in size if node_of(t) and W[node_of(t)] <= W[gate] and t != gate))
    for label, ns in (('JOB2 stories only', PK['JOB2UI']), ('JOB2 first (stories + release path)', PK['JOB2UI'] + PK['RELEASE']), ('IMPORTEXPORT stories only', PK['IMPEXP']), ('IMPORTEXPORT first (stories + release path)', PK['IMPEXP'] + PK['RELEASE']), ('RELEASE path', PK['RELEASE']), ('GATED', ['4.2.2', '4.2.3', '4.2.5', '4.2.6b', '4.2.7b', '4.2.7d', '5.1.5', '5.2.3', '6.2.5c'])):
        r = setcost(set(ns)); print('%s raw %dk CU %.2fM (%.2fM with verification)' % (label, r, r * 1.48 / 1000, r * 1.48 * 1.75 / 1000))
    a4 = 3 * CU['M'] + 2 * CU['S']; print('ADR-001 A4-NO-GO v1-compatible projection estimate (3 M + 2 S) raw %dk CU %.2fM (%.2fM with verification)' % (a4, a4 * 1.48 / 1000, a4 * 1.48 * 1.75 / 1000))
# ---- figures copied from the effort estimate into requirements.md and plan.md are script-enforced
byep = {}
for t, s_ in tasks:
    byep.setdefault('.'.join(t.split('.')[:2]), []).append(t)
def upto2(n): return sum(cost(t) for t in size if node_of(t) and 0 < W[node_of(t)] <= n)
def setc(ns): return setcost_ids(list(ns))
M2 = lambda k: '%.2f' % (k * 1.48 / 1000); MV = lambda k: '%.2f' % (k * 1.48 * 1.75 / 1000); PC = lambda a, b: '%.1f' % (100.0 * a / b)
tot2 = sum(cost(t) for t in size)
J2E = sum(cost(t) for t in size if t.startswith(('4.1.', '4.2.')))
GATED2 = setc(['4.2.2', '4.2.3', '4.2.5', '4.2.6b', '4.2.7b', '4.2.7d', '5.1.5', '5.2.3', '6.2.5c'])
JOB22 = setc(PK['JOB2UI'] + PK['RELEASE'])
IMP2 = setc(PK['IMPEXP'] + PK['RELEASE'])
A4EST = 3 * CU['M'] + 2 * CU['S']
KEPT2 = setc(KEPTN)
KEPT1 = setc(KEPTW1)
PRE2 = sum(cost(t) for t in size if node_of(t) and W[node_of(t)] <= W[gate] and t != gate)
W1c, W9c, W10c = upto2(1), upto2(9), upto2(10)
# the ONE sentence that prices "stop at the pure engines" (requirements, D-62, D-84 and the Task 0.1.7c threshold bullet quote it) and the one that prices "stop entirely"
S_STOP = 'only the KEPT closure is built (%d nodes: %d outside W1, %dk raw tokens, %sM CU, and %d W1 spikes it needs, %dk raw tokens, %sM CU; %sM CU in all, %s%% of the total)' % (len(KEPTN) + len(KEPTW1), len(KEPTN), KEPT2, M2(KEPT2), len(KEPTW1), KEPT1, M2(KEPT1), M2(KEPT2 + KEPT1), PC(KEPT2 + KEPT1, tot2))
STOPENT = 'no agent work is started (%sM CU)' % M2(PRE2)
expect = [
 ('requirements', 'Total raw estimate %.2fM tokens (%sM CU; %sM CU with verification)' % (tot2 / 1000.0, M2(tot2), MV(tot2))),
 ('requirements', 'before W1)**: %s, so nothing is sunk' % STOPENT),
 ('requirements', 'the spikes and baselines, which cost %sM CU (%s%% of the total)' % (M2(W1c), PC(W1c, tot2))),
 ('requirements', 'a FAIL reaches' if False else 'every W1 node has an edge from it'),
 ('plan', 'a FAIL reaches all %d other nodes, not only a hand-named set' % (len(E) - 2)),
 ('requirements', 'so %sM CU (%sM CU with verification, %s%% of the total) is already spent, and %sM CU (%s%%) counting' % (M2(W9c), MV(W9c), PC(W9c, tot2), M2(W10c), PC(W10c, tot2))),
 ('requirements', 'the remaining %s%% of the cost (everything after W9), of which %s%% of the total (the `GATED` set)' % ('%.1f' % (100 - float(PC(W9c, tot2))), PC(GATED2, tot2))),
 ('requirements', '%s%% of total cost: %sM of %sM CU' % (PC(J2E, tot2), M2(J2E), M2(tot2))),
 ('requirements', '%sM CU (%sM with verification) | everything built through W10 (scene, persistence' % (M2(JOB22), MV(JOB22))),
 ('requirements', '%sM CU (%sM with verification) | everything built through W10 | the app is an Excalidraw' % (M2(IMP2), MV(IMP2))),
 ('requirements', 'and the store and codec they need; %d nodes outside W1, %sM CU) stay as tested library code' % (len(KEPTN), M2(KEPT2))),
 ('requirements', '"stop entirely" (nothing is built: %s, because the pre-check is read before W1), "stop at the pure engines" (%s, and nothing user-visible ships' % (STOPENT, S_STOP)),
 ('plan', '"stop entirely" (%s, because the pre-check is read before W1), "stop at the pure engines" (Stories 4.1.1-4.1.3, 5.1.1-5.1.4, 5.2.1, 5.2.2 and 6.1.2 and everything they depend on; %s;' % (STOPENT, S_STOP)),
 ('plan', '"stop entirely" (%s); "stop at the pure engines": %s, script-enforced' % (STOPENT, S_STOP)),
 ('plan', 'The cost of "stop at the pure engines" is the cost of the whole closure: %s (iteration 13' % S_STOP),
 ('requirements', 'about %sM CU of the W1-W10 work (%sM minus %sM for the kept closure and its %d kept W1 spikes) is abandoned' % (M2(W10c - KEPT2 - KEPT1), M2(W10c), M2(KEPT2 + KEPT1), len(KEPTW1))),
 ('plan', 'Job 2 is %s%% of total cost (%sM of %sM CU, Epics 4.1 and 4.2)' % (PC(J2E, tot2), M2(J2E), M2(tot2))),
 ('plan', 'W1 cost, %sM CU of the %sM CU raw estimate' % (M2(W1c), M2(tot2))),
 ('plan', 'W1-W9 hold %s%% of the raw estimate (%sM CU, %sM CU with the x1.75 verification multiplier) and W1-W10 hold %s%% (%sM CU)' % (PC(W9c, tot2), M2(W9c), MV(W9c), PC(W10c, tot2), M2(W10c))),
 ('plan', '| **Total** | **%d** | **%d** | %dM %dS %dXS | **%.2fM** | | **%sM** |' % (len(stories), len(tasks), mix['M'], mix['S'], mix['XS'], tot2 / 1000.0, M2(tot2))),
]
gui = ['4.2.2', '4.2.3', '4.2.5', '4.2.6b', '4.2.7b', '4.2.7d']
expect += [('requirements', 'constraint UI ' + ', '.join(gui) + '; interop UX 5.1.5, 5.2.3'), ('requirements', 'Stories ' + ', '.join(gui) + ' and Task 6.2.4f'),
           ('plan', 'Stories ' + ', '.join(gui) + ', 5.1.5, 5.2.3 and Task 6.2.5c do not start'), ('validation', '(' + ', '.join(gui) + ', 5.1.5, 5.2.3, Task 6.2.5c) do not start')]
gate_deps = sorted(n for n, ds in E.items() if '6.2.4e' in ds)
if gate_deps != sorted(gui + ['5.1.5', '5.2.3', '6.2.5c']): fail('nodes with an edge from the A1 gate 6.2.4e are %s, not the GATED list' % gate_deps)
if '4.2.4' in gate_deps: fail('Story 4.2.4 must not be gated by 6.2.4e')
REL2 = setc(PK['RELEASE'])
expect += [('requirements', 'including the option\'s own stories AND the release path it needs, Tasks 6.2.5a, 6.2.5b, 6.2.5c, 6.2.5e and 6.2.8a, %sM CU for the five (%sM with verification)' % (M2(REL2), MV(REL2))),
           ('requirements', 'ESTIMATED at 3 M and 2 S tasks, %dk raw tokens, %sM CU, %sM CU with the x1.75 verification multiplier' % (A4EST, M2(A4EST), MV(A4EST))),
           ('adr001', '= %dk raw tokens = %sM CU (%sM CU with the x1.75 verification multiplier)' % (A4EST, M2(A4EST), MV(A4EST)))]
A6PROT = setc(PK['JOB2UI'])
expect += [('requirements', 'A6 gates only the six constraint-UI items (Stories 4.2.2, 4.2.3 and 4.2.5; Tasks 4.2.6b, 4.2.7b and 4.2.7d) and Task 6.2.4f, %sM CU, %s%% of the total' % (M2(A6PROT), PC(A6PROT, tot2))),
           ('requirements', 'A6 DEFER parks %sM CU (%s%% of the total)' % (M2(A6PROT), PC(A6PROT, tot2))),
           ('plan', 'A6 gates only the six constraint-UI items and Task 6.2.4f (%sM CU, %s%% of the total)' % (M2(A6PROT), PC(A6PROT, tot2)))]
def blk(txt, a, b):
    return txt.split(a)[1].split(b)[0] if a in txt else ''
MERGES_ = H.get('MERGES', []) if hb is not None else []
if not MERGES_ or any(n not in cl for n in MERGES_): fail('the plan-hold MERGES line %s is empty or names a node outside the kept closure (computed closure has %d nodes)' % (MERGES_, len(cl)))
for n_ in MERGES_:
    if n_ not in blk(req, '- **Choosing between the two stops', '\n- **PIVOT options'): fail('requirements "Choosing between the two stops" does not name the MERGES node %s' % n_)
D62_ = blk(plan, '  - D-62 ', '\n  - D-63')
for n_ in H.get('NOTKEPT', []):
    if n_ not in D62_: fail('D-62 does not name the NOTKEPT node %s' % n_)
for n_ in set(re.findall(r'\b\d\.\d\.\d[a-z]\b', D62_.split('NOTKEPT line')[1].split('the script fails')[0])) - set(H.get('NOTKEPT', [])): fail('D-62 names %s as NOTKEPT but the plan-hold NOTKEPT line does not' % n_)
adr1 = rd('decisions/ADR-001-storage-authority-and-sidecar-schema.md')
texts = {'requirements': req, 'plan': plan, 'validation': val.split('```python check-plan')[0], 'adr001': adr1}
for doc, frag in expect:
    if frag not in texts[doc]: fail('%s.md lacks the script-computed figure sentence: %s' % (doc, frag))
# ---- iteration 11: A6 scope, reachable demand, ConversionReady registration, the NewSketchProject sentence, retired phrases
a6edges = sorted(n for n, ds in E.items() if '0.1.2d' in ds)
if [n for n in a6edges if n.startswith('4.1.')]: fail('Task 0.1.2d has an edge into Epic 4.1 (%s) but the documents say A6 gates only the constraint UI' % [n for n in a6edges if n.startswith('4.1.')])
if not set(gui) <= set(a6edges): fail('the six constraint-UI items do not all have an edge from Task 0.1.2d')
if [n for n in a6edges if n not in gui and n != '4.2.7c']: fail('Task 0.1.2d has unexpected dependants %s' % [n for n in a6edges if n not in gui and n != '4.2.7c'])
def blk(txt, a, b):
    return txt.split(a)[1].split(b)[0] if a in txt else ''
m8 = blk(req, '\n8. **Adoption', '\n9. ')
for lab, tx in (('metric 8', m8), ('Task 0.1.7c story', blk(plan, '#### Story 0.1.7', '\n#### ')), ('Task 0.1.2d', blk(plan, '##### Task 0.1.2d', '\n#### ')),
                ('requirements A6 "What the owner does"', blk(req, '## A6 go/no-go', '- **Threshold')), ('requirements A1 gate "What the owner does"', blk(req, '## A1 go/no-go gate', '- **Criteria')),
                ('plan A1 gate acceptance criterion', blk(plan, '- A1 go/no-go gate (Task 6.2.4e;', '\n- Bracket-plate'))):
    if 'Reachable demand' not in tx and 'project kind' not in tx: fail('%s does not record the project kind / name "Reachable demand" (requirements "Reachable demand")' % lab)
rdsec = blk(req, '### Reachable demand', '\n### ')
for frag in ('existing-classic', 'existing-v2', 'reachable', 'unmeasured under the cap'):
    if frag not in rdsec: fail('requirements "Reachable demand" lacks "%s"' % frag)
for nm, txt in (('requirements', req), ('plan', plan), ('ux', ux)):
    for ph in ('and again by the A1 go/no-go gate', '14 constraint kinds', '[How to change this]', 'older apps open it', 'Pinch or scroll to zoom', 'hold a group for more', 'cycle WITHIN the popover', 'Tab wrapping inside the popover'):
        if ph in txt:
            fail('%s.md still contains the retired phrase "%s"' % (nm, ph))
if 'NOTHING from the A1 gate (Task 6.2.4e)' not in plan: fail('plan.md reader-only dependency row does not name Task 6.2.4e')
# every commonTest class of the ConversionReady list is named by a registration task heading (mutation-tested)
crs = 'Story 1.1.3 `BundleV2PassthroughTest`' + blk(plan, 'Story 1.1.3 `BundleV2PassthroughTest`', 'and `ConversionReady` is `false` in the same build')
crn = re.findall(r'`([A-Z][A-Za-z0-9]*Test)`', crs)
crtag = dict(re.findall(r'`([A-Z][A-Za-z0-9]*Test)` \((BT|CT)\)', crs))
for n in crn:
    if n not in crtag: fail('ConversionReady list class %s has no (BT) or (CT) source-set tag in the list itself' % n)
    elif not re.search(r'\b%s/[A-Za-z0-9/]*/%s\.kt' % (crtag[n], n), plan): fail('ConversionReady list tags %s (%s) but the plan has no %s/ path for it' % (n, crtag[n], crtag[n]))
ctn = [n for n in crn if crtag.get(n) == 'CT']
regheads = re.findall(r'^##### Task [^\n]*registration of[^\n]*$', plan, re.M)
def unreg(names, hs): return [n for n in names if not any('`%s`' % n in h for h in hs)]
if not ctn: fail('no commonTest class found in the ConversionReady list (list or CT paths changed)')
if unreg(ctn, regheads): fail('commonTest classes of the ConversionReadyConsistencyTest list not named by a registration task: %s' % unreg(ctn, regheads))
for n in ctn:
    if n not in unreg(ctn, [h.replace('`%s`' % n, 'X') for h in regheads]): fail('mutation test: dropping %s from the registration tasks was not detected' % n)
# ---- iteration 12: kind of reachable demand, pre-check stop cost, keyboard map, Esc order, toast dismissal, copy
if 'existing-classic-technical' not in rdsec: fail('requirements "Reachable demand" lacks the kind existing-classic-technical')
for lab, tx in (('Task 0.1.7c story', blk(plan, '#### Story 0.1.7', '\n#### ')), ('Task 0.1.2d', blk(plan, '##### Task 0.1.2d', '\n#### ')), ('D-75', blk(plan, '  - D-75 ', '\n  - D-76'))):
    if 'existing-classic-technical' not in tx: fail('%s does not list the project kind existing-classic-technical' % lab)
if 'existing-classic-technical' not in blk(req, '## A6 go/no-go', '- **Threshold') + req.split('| A6 |')[1].split('\n')[0] and 'existing-classic-technical' not in blk(req, '## A6 go/no-go', '## Combined'): fail('requirements A6 section does not mention existing-classic-technical')
if 'existing-classic-technical' not in blk(plan, '- A1 go/no-go gate (Task 6.2.4e;', '\n- Bracket-plate') and 'reachable item' not in blk(plan, '- A1 go/no-go gate (Task 6.2.4e;', '\n- Bracket-plate'): fail('plan A1 gate acceptance criterion does not name reachable items')
if '4.2.7c' not in blk(req, '- **Job 2 validation**', '\n\n'): fail('requirements "Job 2 validation" does not state the status of Task 4.2.7c')
for n in KEPTN:
    if W[n] <= 1: fail('KEPT node %s is in W1, so the outside-W1 KEPT figure would double count it' % n)
if 'constraint-UI stories' in req + plan + ux: fail('"constraint-UI stories" is retired: the six items are 3 stories and 3 tasks (say "items")')
if 'tangent, symmetric, angle, Fixed, Radius and Parallel stay hidden' not in req or 'tangent, symmetric, angle, Fixed, Radius and Parallel stay hidden' not in plan: fail('the geometric-only hidden set differs between the abort criteria and metric 3')
sec10 = blk(ux, 'Tab order, fixed and tested', '`KeyboardDrawingFlowTest` walks the list')
if 'view cluster' not in sec10 or 'step 2 after the view cluster' not in sec10: fail('ux.md section 10 tab order lacks the view cluster stop or the walkthrough popover positions')
sec17 = blk(ux, '## 17. Keyboard map', '## 18.')
if 'selection bar (where it exists), view cluster, side panel' not in sec17 or "Ctrl+' Grid" not in sec17 or 'Alt+S Snap' not in sec17: fail('ux.md section 17 lacks the view cluster F6 region or the Grid and Snap chords')
esc = blk(sec17, '**Esc precedence', '**Screen readers')
if esc and [i for i in range(1, 13) if '(%d) ' % i not in esc]: fail('ux.md Esc precedence list does not number rows (1) to (12)')
if not esc: fail('ux.md section 17 lacks the one Esc precedence list')
for nm_, tx_ in (('ux', ux), ('plan', plan)):
    if 'collapsed strip' in tx_.replace('the Dock never collapses', '') and nm_ == 'ux' and 'Collapsed strip' in tx_: fail('ux.md still has the collapsed-strip walkthrough variant')
for ph_ in ('The tools are in this menu', 'Read-only. New editor is off', 'Existing projects stay classic, and so does', 'Existing sketches and projects stay classic and nothing is converted', 'starts the new editor only in a project it creates', 'older apps'):
    for nm_, tx_ in (('ux', ux), ('requirements', req), ('ADR-005', rd('decisions/ADR-005-v1-v2-coexistence-and-flag.md'))):
        if ph_ in tx_.replace('"older apps" is not used', '').replace('"older apps" are not used', '').replace('neither "older apps" nor', '').replace('and the words "older apps" are not used', ''): fail('%s still contains the retired phrase "%s"' % (nm_, ph_))
if 'Upgrade blocks older apps' in plan.split('\n', 5)[5]: fail('plan.md still quotes the retired chip line "Upgrade blocks older apps" outside the status history')
for frag in ('not shown while the STORED mode is Off', 'once per project per reason', 'Return path'):
    if frag not in ux.split('New sheet created as classic (UX-77')[1].split('### 14.2')[0]: fail('ux.md 14.1 ClassicEditor rule lacks "%s"' % frag)
if '`ClassicEditor` ("Created as classic.")' not in blk(ux, '## 16. Diagnostics host', '## 17.'): fail('ux.md section 16 has no ClassicEditor row in the toast recurrence table')
for frag in ('HEADER_PROBE_BYTES', 'classifyHeader'):
    if frag not in plan: fail('plan.md lacks the NewSketchProject predicate cost bound ("%s")' % frag)
if 'Does upgrading change anything on this device, or on others?' not in ux: fail('UX-123 lacks the chip-alone comprehension question')
# the NewSketchProject sentence is identical in requirements, plan and both ADRs (mutation-tested)
def nsp(txt): return re.findall(r'\*\*NewSketchProject \(canonical sentence\):\*\* ([^\n]+)', txt)
canon = nsp(req)
if len(canon) != 1: fail('requirements.md must carry exactly one NewSketchProject canonical sentence')
else:
    for nm, txt in (('plan', plan), ('ADR-001', adr1), ('ADR-005', rd('decisions/ADR-005-v1-v2-coexistence-and-flag.md'))):
        if nsp(txt) != canon: fail('%s NewSketchProject canonical sentence differs from or is missing against requirements.md' % nm)
    if nsp(plan.replace(canon[0], canon[0][:-8])) == canon: fail('mutation test: an altered NewSketchProject sentence was not detected')
print('iteration 11: A6 dependants %s; ConversionReady list %d classes, %d commonTest (%s) named by registration tasks, mutation-tested; NewSketchProject sentence identical in 4 documents' % (a6edges, len(crn), len(ctn), ', '.join(ctn)))
# ---- iteration 13: VALUES, not wording. Every check below parses numbers or structured lists and compares the computed value.
import os
ROOTC = os.path.dirname(os.path.dirname(os.path.abspath(D)))
# The code-anchored checks (cited line numbers, "the code has no X yet" facts) compare the plan with the code AS PLANNED: run them against the
# planning snapshot with `--code-root DIR` (DIR holds kmp/ and docs/ as of the planning commit, e.g. `git archive <sha> kmp docs | tar -x -C DIR`).
# Without the flag they read the working tree, where implemented tasks make them fail by design (the code moved on, the plan did not).
if '--code-root' in sys.argv: ROOTC = os.path.abspath(sys.argv[sys.argv.index('--code-root') + 1])
def codefile(rel):
    p = os.path.join(ROOTC, rel); return open(p, encoding='utf-8').read() if os.path.exists(p) else None
# (E1-E3) the NewSketchProject header probe: bytes, opens, total and sheet limits are parsed and recomputed
pb = blk(plan, '- Cost bound of the `NewSketchProject` predicate', '\n- Reason strings')
mp = re.search(r'`HEADER_PROBE_BYTES` = (\d+)', pb)
mo = re.search(r'at most (\d+) sheet-file opens of at most (\d+) bytes each, (\d+) x (\d+) = ([\d,]+) bytes', pb)
ms = re.search(r'with (\d+) to (\d+) sheets never qualifies', pb)
if not (mp and mo and ms): fail('plan probe bullet lacks HEADER_PROBE_BYTES, the "at most N sheet-file opens of at most B bytes each, N x B = T bytes" bound or the "N to M sheets never qualifies" scope')
else:
    probe, opens, perfile, a_, b_, tot_ = int(mp.group(1)), int(mo.group(1)), int(mo.group(2)), int(mo.group(3)), int(mo.group(4)), int(mo.group(5).replace(',', ''))
    if not (probe == perfile == b_ and opens == a_ and opens * perfile == tot_): fail('probe bound is inconsistent: HEADER_PROBE_BYTES %d, %d opens of %d bytes, quoted %d x %d = %d (computed %d)' % (probe, opens, perfile, a_, b_, tot_, opens * perfile))
    if int(ms.group(1)) != opens + 1: fail('probe scope starts at %s sheets but the probe covers at most %d (the next size is %d)' % (ms.group(1), opens, opens + 1))
    sheetcap = codefile('kmp/src/commonMain/kotlin/dev/stapler/stelekit/projectbridge/SidecarReconciler.kt')
    if sheetcap is not None:
        mc = re.search(r'const val MAX_SHEETS = (\d+)', sheetcap)
        if not mc or int(mc.group(1)) != int(ms.group(2)): fail('probe scope says projects up to %s sheets never qualify but SidecarReconciler.MAX_SHEETS is %s' % (ms.group(2), mc.group(1) if mc else '?'))
    getcap = codefile('kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SketchSheetRepository.kt')
    if getcap is not None and not re.search(r'const val DEFAULT_SHEET_LIMIT = %d\b' % opens, getcap): fail('probe covers %d sheets but SketchSheetRepository.DEFAULT_SHEET_LIMIT (the getSheets page) differs' % opens)
    if 'AttachedSheets.MAX_SHEETS' in pb: fail('the probe scope is anchored to AttachedSheets.MAX_SHEETS, which is unrelated; anchor it to SketchSheetRepository.DEFAULT_SHEET_LIMIT')
    if 'SketchSheetRepository.DEFAULT_SHEET_LIMIT' not in pb: fail('the probe bullet does not name SketchSheetRepository.DEFAULT_SHEET_LIMIT as the source of the 50')
    vq = re.search(r'OpenAtMost(\d+)FilesAndReadAtMost(\d+)Bytes', val.split('```python check-plan')[0])
    if not vq or (int(vq.group(1)), int(vq.group(2))) != (opens, tot_): fail('validation.md names a probe-bound test for %s files and %s bytes, not the plan bound %d and %d' % (vq.groups() if vq else '?', '', opens, tot_))
sf = codefile('kmp/src/commonMain/kotlin/dev/stapler/stelekit/projectbridge/SidecarFileState.kt'); pm = codefile('kmp/src/commonMain/kotlin/dev/stapler/stelekit/projectbridge/ProjectModelJson.kt')
FILEKEY = re.search(r'file key is `(\w+)`', pb)
if not FILEKEY: fail('plan probe bullet does not name the file key')
else:
    if sf is not None and 'obj["%s"]' % FILEKEY.group(1) not in sf: fail('the probe keys on `%s` but SidecarFileState.classifyJson reads a different key' % FILEKEY.group(1))
    if pm is not None and 'put("%s", SCHEMA)' % FILEKEY.group(1) not in pm: fail('the probe keys on `%s` but ProjectModelJson writes a different key' % FILEKEY.group(1))
    if FILEKEY.group(1) != 'schema': fail('the sheet file key is `schema`, the plan says `%s`' % FILEKEY.group(1))
if 'writes `schema_version` as the first key' in plan: fail('plan still says the codec writes `schema_version` as the first key of a v2 file')
for t_ in ('1.4.3d', '1.4.3g'):
    if t_ not in size: fail('Task %s is missing' % t_)
if '1.4.3g' in size:
    if size['1.4.3g'] != 'M' or len(FL.get('1.4.3g', [])) > 5 or not FL.get('1.4.3g'): fail('Task 1.4.3g must be an M task with 1 to 5 listed files')
    if node_of('1.4.3d') != '1.4.3' or '1.4.3g' not in E.get('1.4.3', []): fail('Task 1.4.3g has no edge into Task 1.4.3d (its story node 1.4.3 must list it)')
    elif W['1.4.3g'] >= W['1.4.3']: fail('Task 1.4.3g (W%d) is not before its consumer node 1.4.3 (W%d)' % (W['1.4.3g'], W['1.4.3']))
# (U1, U2, E4) the Esc order: parsed row by row and compared with the canonical list
ESCN = ['dialog or sheet', 'menu, flyout', 'text overlay', '"Solving..."', 'armed constraint picker', 'mid-draw preview', 'Crosshair mode', 'walkthrough', 'toast or banner action', 'active tool other than Select', 'selection', 'newest toast']
esc2 = blk(ux, '**Esc precedence', '**Screen readers')
erows = re.findall(r'\((\d+)\) (.+?)(?=; \(\d+\) |\. Esc never)', esc2, re.S)
if [int(n) for n, _ in erows] != list(range(1, 13)): fail('Esc order rows parsed as %s, not 1 to 12' % [n for n, _ in erows])
else:
    for i, (n, tx) in enumerate(erows):
        if ESCN[i] not in tx: fail('Esc order row (%s) is "%s", expected the row about "%s"' % (n, tx[:50], ESCN[i]))
    if 'commits' not in erows[2][1].split(' or a dimension field')[0]: fail('Esc row 3: a text overlay must COMMIT (section 6 step 2), the row says otherwise')
    if 'Blocking' not in erows[8][1]: fail('Esc row 9 does not define Esc on a Blocking banner action (no Dismiss)')
if 'Ctrl+Enter / tap outside / Esc commits' not in ux: fail('ux.md section 6 no longer says Esc commits a non-empty text')
if '(the first Esc: Esc precedence row 6' not in ux: fail('ux.md section 10 step 5 does not tie the first Esc to Esc precedence row 6')
need13 = {'3.6.3c', '3.6.8', '3.1.4', '1.4.6', '4.1.4', '4.2.6b'}
if '6.2.4h' not in E or not need13 <= set(E['6.2.4h']): fail('Task 6.2.4h (the all-rows Esc test) must have edges from %s' % sorted(need13))
elif W['6.2.4h'] <= max(W[n] for n in need13): fail('Task 6.2.4h is W%d but one of its owners is W%d' % (W['6.2.4h'], max(W[n] for n in need13)))
# (spec) project kinds: parsed from "Reachable demand" and required in every list that enumerates them
KINDS = {'new', 'existing-classic', 'existing-classic-technical', 'existing-v2'}
KRE = re.compile(r'`(new|existing-classic-technical|existing-classic|existing-v2)`')
rdsec13 = blk(req, '### Reachable demand', '\n### ')
kdef = re.findall(r'`(new|existing-classic-technical|existing-classic|existing-v2)` \(', blk(rdsec13, 'and a **project kind**', 'An item is **reachable**'))
if kdef != ['new', 'existing-classic', 'existing-classic-technical', 'existing-v2']: fail('"Reachable demand" defines the project kinds %s, expected the four in order' % kdef)
rd_ = set(KRE.findall(rdsec13))
if rd_ != KINDS: fail('"Reachable demand" lists the project kinds %s, expected exactly %s' % (sorted(rd_), sorted(KINDS)))
for lab, tx in (('Task 0.1.7c story', blk(plan, '#### Story 0.1.7', '\n#### ')), ('Task 0.1.2d', blk(plan, '##### Task 0.1.2d', '\n#### ')), ('D-75', blk(plan, '  - D-75 ', '\n  - D-76')),
                ('requirements A6 "What the owner does"', blk(req, '## A6 go/no-go', '- **Threshold')), ('requirements A1 gate "What the owner does"', blk(req, '## A1 go/no-go gate', '- **Criteria')),
                ('plan A1 gate acceptance criterion', blk(plan, '- A1 go/no-go gate (Task 6.2.4e;', '\n- Bracket-plate'))):
    got = set(KRE.findall(tx))
    if got != KINDS: fail('%s lists the project kinds %s, expected the four %s' % (lab, sorted(got), sorted(KINDS)))
# (E5) ClassicEditor scope tuple and the ledger bound are parsed
SCOPE = re.compile(r'\(graph, project(?: slug)?, reason\)')
for lab, tx in (('plan D-87', blk(plan, '  - D-87 ', '\n  - D-88')), ('plan Story 1.4.6 ClassicEditor bullet', blk(plan, '- A NEW sheet that is created as legacy', '\n- Newer-schema screen')),
                ('ux 14.1', blk(ux, 'New sheet created as classic (UX-77', '### 14.2')), ('ux section 16 row', blk(ux, '| `ClassicEditor` ("Created as classic.")', '\n'))):
    if not SCOPE.search(tx) or 'per device' not in tx: fail('%s does not state the ClassicEditor recurrence scope "(graph, project, reason) per device"' % lab)
ml = re.search(r'`sketch\.classicNotices\.<graphId>` holding at most (\d+) `slug\|reason` entries', plan)
md96 = re.search(r'at most (\d+) entries', blk(plan, '  - D-96 ', '\n  - D-97'))
if not ml or not md96 or ml.group(1) != md96.group(1): fail('ClassicNoticeLedger bound differs between the story (%s) and D-96 (%s)' % (ml.group(1) if ml else None, md96.group(1) if md96 else None))
for need in ('sketch.onboarding.<item>', 'ClassicNoticeLedgerTest'):
    if need not in plan: fail('plan.md lacks the persistence owner detail "%s"' % need)
# (U3) toast rows: a "Learn more" primary never repeats Details (banner rows are checked above)
for t_ in CTB['toast'] if CTB else []:
    if t_['primary'] == 'Learn more' and 'Details' in t_['more']: fail('toast %s: the primary "Learn more" opens Details, so More must not repeat it' % t_['id'])
if CTB and not [t_ for t_ in CTB['toast'] if t_['id'] == 'ClassicEditorFixed' and t_['primary'] == 'Learn more']: fail('toast table lacks the row ClassicEditorFixed with the primary "Learn more"')
# (U9) the chars-per-line model is conservative: a line of the computed number of characters never exceeds the available width
if CTB:
    from fractions import Fraction as _F
    for av in range(60, 330, 7):
        for sc in (1.0, 1.1, 1.15, 1.3, 1.5, 2.0):
            cpl = chrome_charsper(av, sc, 7)
            if _F(cpl) * 7 * _F(str(sc)) > av or _F(cpl + 1) * 7 * _F(str(sc)) <= av: fail('chars-per-line model is not floor(width / (7 dp x scale)) at width %d scale %s: %d' % (av, sc, cpl))
# retired figures of iteration 13
for ph in ('5.29M CU', '7.10M CU', '24.3% of the total', '(1.58 at 360', 'from 1.58 at 360', '"older apps"" is not'):
    for nm, tx in (('requirements', req), ('plan', plan), ('validation', val.split('```python check-plan')[0]), ('ux', ux)):
        if ph in tx and 'retired' not in tx[max(0, tx.index(ph) - 80):tx.index(ph)]: fail('%s.md still quotes the retired figure "%s"' % (nm, ph))
# ================= iteration 14: values again. Every check parses a number, a list or a code fact and compares it with the computed one.
import json as _json
def pathx(p):
    for k, v in (('CM/', 'kmp/src/commonMain/kotlin/dev/stapler/stelekit/'), ('CT/', 'kmp/src/commonTest/kotlin/dev/stapler/stelekit/'), ('BT/', 'kmp/src/businessTest/kotlin/dev/stapler/stelekit/'), ('JT/', 'kmp/src/jvmTest/kotlin/dev/stapler/stelekit/'), ('AT/', 'kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/')):
        if p.startswith(k): return v + p[len(k):]
    return p
adr5i = rd('decisions/ADR-005-v1-v2-coexistence-and-flag.md')
# (E1) the header-first key order: offsets computed from both layouts and from a fully sorted emission
SW = re.search(r'`SCHEMA_WINDOW` = (\d+) bytes', pb)
v1doc = {'schema': 1, 'sheetUuid': 's1', 'primitives': []}
v1b = _json.dumps(v1doc, indent=2)
v2doc = {'schema': 2, 'sheetUuid': 's2', 'layers': [{'name': 'Base'}], 'constraints': [], 'extras': {}, 'elements': [{'id': 'e%d' % i, 'type': 'rectangle', 'x': i, 'y': i, 'width': 10, 'height': 10} for i in range(100)]}
HDR = ['schema', 'sheetUuid']
v2b = _json.dumps({**{k: v2doc[k] for k in HDR}, **{k: v2doc[k] for k in sorted(v2doc) if k not in HDR}}, separators=(',', ':'))
v2sorted = _json.dumps(v2doc, sort_keys=True, separators=(',', ':'))
o1, o2, osort = v1b.index('"schema"'), v2b.index('"schema"'), v2sorted.index('"schema"')
alls_ = {nm_: set(re.findall(r'`SCHEMA_WINDOW` = (\d+) bytes', tx_)) for nm_, tx_ in (('plan', plan), ('requirements', req), ('ADR-001', adr1), ('ux', ux))}
if len(set().union(*alls_.values())) > 1: fail('SCHEMA_WINDOW is quoted with different values: %s' % {k_: sorted(v_) for k_, v_ in alls_.items() if v_})
if not SW: fail('plan probe bullet lacks `SCHEMA_WINDOW` = N bytes')
else:
    swv = int(SW.group(1))
    if not (o1 < swv and o2 < swv): fail('SCHEMA_WINDOW %d does not cover the computed offsets (v1 %d, v2 %d)' % (swv, o1, o2))
    if swv > probe: fail('SCHEMA_WINDOW %d exceeds the probe prefix %d bytes' % (swv, probe))
    if osort < probe: fail('a fully sorted v2 emission puts "schema" at byte %d, inside the %d-byte probe prefix, so the header-first exception would not be load-bearing' % (osort, probe))
mo1 = re.search(r'\(key at byte offset (\d+)\)', pb); mo2 = re.search(r'\(offset (\d+)\) because the v2 canonical emitter', pb)
if not mo1 or not mo2 or (int(mo1.group(1)), int(mo2.group(1))) != (o1, o2): fail('plan quotes the schema offsets %s and %s, computed v1 %d and v2 %d' % (mo1 and mo1.group(1), mo2 and mo2.group(1), o1, o2))
LIT1 = '{\\n  "schema": 1,'; LIT2 = '{"schema":2,"sheetUuid":"'
if not v1b.startswith('{\n  "schema": 1,') or not v2b.startswith(LIT2): fail('computed header literals differ from the quoted ones')
for nm_, tx_ in (('plan probe bullet', pb), ('ADR-001', adr1)):
    if LIT1 not in tx_ or LIT2 not in tx_: fail('%s does not quote the v1 literal `%s` and the v2 literal `%s`' % (nm_, LIT1, LIT2))
if pm is not None:
    # Story 1.1.1 moved the v1 sheet writer from ProjectModelJson.sheetJson into SheetCodecV1.encode (SheetCodec.kt); read it where it lives now.
    scf = codefile('kmp/src/commonMain/kotlin/dev/stapler/stelekit/projectbridge/SheetCodec.kt')
    if scf is not None and 'object SheetCodecV1' in scf:
        shf = scf.split('object SheetCodecV1')[1].split('override fun decode')[0]; pretty_ok = 'prettyPrint = true' in shf
    elif 'private fun sheetJson' in pm:
        shf = pm.split('private fun sheetJson')[1].split('private fun decodeUnsafe')[0]; pretty_ok = 'prettyPrint = true' in pm
    else:
        shf = ''; pretty_ok = False; fail('code: neither SheetCodecV1.encode nor ProjectModelJson.sheetJson found; the v1 writer moved again, update this check')
    if shf and (shf.find('put("schema"') < 0 or shf.index('put("schema"') > shf.index('put("sheetUuid"') or not pretty_ok): fail('code: the v1 sheet writer (SheetCodecV1.encode) no longer writes schema first through the pretty printer, so the quoted v1 literal is stale')
if 'headerFirstKeys' not in plan or 'headerFirstKeys' not in adr1: fail('the header-first list `headerFirstKeys` is named by neither ADR-001 nor the plan')
# (E2) model-only read: the code file name, the missing interface method, the serialisation of the shared store file
MP = codefile('kmp/src/commonMain/kotlin/dev/stapler/stelekit/projectbridge/ProjectModelJson.kt'); mpn = re.search(r'const val MODEL_PATH = "([^"]+)"', MP or '')
if mpn:
    toks = set(re.findall(r'[\w-]*model\.json', plan + req + ux + adr1 + adr5i))
    if toks - {mpn.group(1)}: fail('documents name the model file %s, the code constant MODEL_PATH is %s' % (sorted(toks - {mpn.group(1)}), mpn.group(1)))
pst = codefile('kmp/src/commonMain/kotlin/dev/stapler/stelekit/projectbridge/ProjectSidecarStore.kt')
if pst is not None:
    if 'readModel' in pst: fail('code: ProjectSidecarStore already has readModel (Task 1.4.3h is implemented); the plan still words the E2 gap as open, so reword Task 1.4.3h / E2 as done or retire this check')
    elif 'private fun readLocked' not in pst: fail('code: ProjectSidecarStore.readLocked is gone and readModel is absent, so the E2 rationale anchor moved; update this check')
    else:
        rl_ = pst.split('private fun readLocked')[1].split('override suspend fun write')[0]
        if 'fs.readFileBytes(abs)' not in rl_ or 'fs.listFiles' not in rl_: fail('code: readLocked no longer lists and reads every sheet, so the E2 rationale is stale')
if '1.4.3h' not in size or size['1.4.3h'] != 'S' or node_of('1.4.3h') != '1.4.3' or W['1.4.3'] <= W['1.4.2']: fail('Task 1.4.3h must be an S task of Story 1.4.3 whose node is after Story 1.4.2')
ps_nodes = {}
for t_, ps_ in FL.items():
    if any(p_.endswith('projectbridge/ProjectSidecarStore.kt') for p_ in ps_): ps_nodes[node_of(t_)] = W[node_of(t_)]
dup = sorted(n for n in ps_nodes if sum(1 for m in ps_nodes if ps_nodes[m] == ps_nodes[n]) > 1)
if dup: fail('ProjectSidecarStore.kt is edited by nodes %s that share a wave (shared-file serialisation broken): %s' % (dup, ps_nodes))
# (E3) the platform capability: derived from the code, quoted in the plan and in the validation cells
cap_false = set()
for plat_, dirn_ in (('wasmJs', 'wasmJsMain'), ('iOS', 'iosMain'), ('android', 'androidMain'), ('desktop', 'jvmMain')):
    pf = codefile('kmp/src/%s/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt' % dirn_)
    if pf is not None and re.search(r'supportsTempRenameWrites: Boolean get\(\) = false', pf): cap_false.add(plat_)
if not cap_false: cap_false = {'wasmJs', 'iOS'}  # no repo code next to the documents: the documented today-state
plb = blk(plan, '- **The platform can store v2 sheets', '\n- Refusal reaches the user')
if codefile('kmp/src/iosMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt') is not None and cap_false != {'wasmJs', 'iOS'}: fail('platforms whose supportsTempRenameWrites is false in the code today: %s, the plan says wasmJs and iOS (before Story 1.4.7)' % sorted(cap_false))
wasm_changed = any(p_.startswith('kmp/src/wasmJsMain') and p_.endswith('PlatformFileSystem.kt') for t_ in FL for p_ in FL[t_])
ios_changed = any(p_.startswith('kmp/src/iosMain') and p_.endswith('PlatformFileSystem.kt') for t_ in FL for p_ in FL[t_])
vcells = re.findall(r'platform cells: ([^|`;]+)', val.split('```python check-plan')[0])
if len(vcells) != 1: fail('validation.md must state the platform capability cells once ("platform cells: desktop=true, ...")')
else:
    cells = dict((a.strip(), b.strip()) for a, b in (kv.split('=') for kv in vcells[0].strip().rstrip('.').split(',')))
    want = {'desktop': 'true', 'android': 'true', 'store-null': 'false'}
    want['iOS'] = 'true' if ('iOS' not in cap_false or ios_changed) else 'false'
    if 'wasmJs' in cap_false and wasm_changed: want['wasmJs-opfs'] = 'true'; want['wasmJs-no-opfs'] = 'false'
    elif 'wasmJs' in cap_false: want['wasmJs'] = 'false'
    else: want['wasmJs'] = 'true'
    if cells != want: fail('validation platform cells %s differ from the code-derived capability %s (wasm file changed by a task: %s)' % (cells, want, wasm_changed))
fsk = codefile('kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/FileSystem.kt')
if fsk is not None and not re.search(r'fun readFileBytes\(path: String\): ByteArray\? =\s+throw UnsupportedOperationException', fsk): fail('code: the FileSystem.readFileBytes default no longer throws, so the E4 null-returning default is stale')
OPTW_T = sorted(t for t in size if t.startswith(('0.1.8', '1.4.7')))
OPTW = sum(cost(t) for t in OPTW_T)
OPTW_MIX = ', '.join('%d %s' % (sum(1 for t in OPTW_T if size[t] == k), k) for k in ('M', 'S', 'XS') if any(size[t] == k for t in OPTW_T)).rsplit(', ', 1)
OPTW_MIX = ' and '.join(OPTW_MIX) if len(OPTW_MIX) == 2 else OPTW_MIX[0]
OPTW_SHARED = sorted(PK.get('OPFSSHARED', []))
OPTW_SH = sum(cost(t) for t in OPTW_SHARED)
OPTW_S = lambda tail: 'Option W costs %s tasks (Tasks %s), %dk raw tokens, %sM CU (%sM CU with %s), %s%% of the total of %sM CU; the shared storage ports of Story 1.4.3 that stay built when it is dropped (Tasks %s) cost %dk raw tokens, %sM CU' % (OPTW_MIX, ', '.join(OPTW_T), OPTW, M2(OPTW), MV(OPTW), tail, PC(OPTW, tot2), M2(tot2), ', '.join(OPTW_SHARED), OPTW_SH, M2(OPTW_SH))
expect14 = [('requirements', OPTW_S('the x1.75 verification multiplier')), ('plan', OPTW_S('verification'))]
for doc_, frag_ in expect14:
    if frag_ not in texts[doc_]: fail('%s.md lacks the script-computed Option W cost sentence: %s' % (doc_, frag_))
if not wasm_changed: fail('no task lists the wasmJs PlatformFileSystem.kt, but D-104 is the owner decision to give wasm the capability (Task 1.4.7b)')
# (E4) Task 1.4.3g: JVM facts and file list
g_row = FL.get('1.4.3g', [])
if fsk is not None:
    for p_ in g_row:
        if not p_.endswith('Test.kt') and not os.path.exists(os.path.join(ROOTC, pathx(p_))): fail('Task 1.4.3g lists %s, which does not exist' % pathx(p_))
jb = codefile('kmp/src/jvmCommonMain/kotlin/dev/stapler/stelekit/platform/JvmFileSystemBase.kt'); jp = codefile('kmp/src/jvmMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt')
if jb is not None and jp is not None:
    if 'protected fun validatePath' not in jb or 'class PlatformFileSystem actual constructor() : JvmFileSystemBase()' not in jp: fail('code: JVM facts of Task 1.4.3g (protected validatePath, PlatformFileSystem extends JvmFileSystemBase) are stale')
    if not any(p_.endswith('JvmFileSystemBase.kt') for p_ in g_row): fail('Task 1.4.3g must list JvmFileSystemBase.kt, where the JVM bounded read has to live')
if any(p_.startswith('kmp/src/wasmJsMain') for p_ in g_row): fail('Task 1.4.3g lists a wasmJs file, but its file list is full: the wasm readFilePrefix override belongs to Task 1.4.7b (D-104)')
if not any('PlatformFileSystem.kt' in p_ and 'wasmJsMain' in p_ for p_ in FL.get('1.4.7b', [])): fail('Task 1.4.7b must list the wasmJs PlatformFileSystem.kt (readFilePrefix, readFileBytes, byte rename)')
if not ('1.4.3g' in E.get('1.4.7f', []) and '1.4.7e' in E.get('1.4.7f', []) and '0.1.8' in E.get('1.4.7e', []) and W['1.4.7f'] > W['0.1.8'] and W['1.4.7f'] > W['1.4.3g']): fail('Task 1.4.7f must follow Spike 0.1.8 (through 1.4.7e) and Task 1.4.3g')
if not (all(x_ in E.get('1.4.7d', []) for x_ in ('1.4.7c', '1.4.7k', '1.4.7l', '5.1.5', '6.2.4g')) and '1.4.7d' in E.get('6.2.5b', [])): fail('the web browser pass (Task 1.4.7d) must follow Tasks 1.4.7c, 1.4.7k and 1.4.7l, Story 5.1.5 and Task 6.2.4g and feed the final verification 6.2.5b')
# (E5) D-62, D-85 and D-100 quote computed values
ESHORT = ['dialog or sheet', 'menu', 'text overlay or dimension field', '"Solving..."', 'armed picker', 'mid-draw', 'Crosshair', 'walkthrough', 'toast or banner action', 'tool', 'selection', 'newest toast']
d85 = blk(plan, '  - D-85 ', '\n  - D-86'); d100 = blk(plan, '  - D-100 ', '\n  - D-101')
exp85 = '(' + ', '.join('%d %s' % (i + 1, n_) for i, n_ in enumerate(ESHORT)) + ';'
if exp85 not in d85: fail('D-85 does not quote the Esc rows as the numbered list %s' % exp85)
r_mid = [i + 1 for i, (n_, tx_) in enumerate(erows) if 'mid-draw preview' in tx_][0]; r_cross = [i + 1 for i, (n_, tx_) in enumerate(erows) if tx_.startswith('Crosshair mode')][0]; r_arm = [i + 1 for i, (n_, tx_) in enumerate(erows) if 'armed constraint picker' in tx_][0]; r_txt = [i + 1 for i, (n_, tx_) in enumerate(erows) if 'text overlay' in tx_][0]
if 'mid-draw is row %d before Crosshair row %d' % (r_mid, r_cross) not in d100: fail('D-100 does not state mid-draw as row %d before Crosshair row %d (parsed from ux.md)' % (r_mid, r_cross))
if 'a parked Task 4.2.6b drops row %d' % r_arm not in d85: fail('D-85 does not say that a parked Task 4.2.6b drops row %d (parsed from ux.md)' % r_arm)
red64 = blk(plan, 'REDUCED: 6.2.4h', '\nREDUCED: 6.2.8')
if 'row %d (the armed constraint picker, Task 4.2.6b) is absent' % r_arm not in red64: fail('the REDUCED line of Task 6.2.4h does not say row %d is the armed picker' % r_arm)
if 'the dimension-field half of row %d' % r_txt not in red64: fail('the REDUCED line of Task 6.2.4h does not name the dimension-field half of row %d' % r_txt)
if 'row %d is absent' % r_arm not in esc: fail('ux.md Esc order does not say row %d is absent when Task 4.2.6b is parked' % r_arm)
secn = re.search(r'^## (\d+)\. Constraint panel', ux, re.M)
mref = re.search(r'dimension field \(cancels the edit, the old value stays: section (\d+)\)', esc)
if not secn or not mref or secn.group(1) != mref.group(1): fail('Esc row %d cites section %s for the dimension field, the constraint panel is section %s' % (r_txt, mref and mref.group(1), secn and secn.group(1)))
if 'dragged' in D62_: fail('D-62 still says the NOTKEPT tasks are dragged into the closure')
# (P1) every W1 node has an edge from the gate
w1_without = [n for n in E if W[n] == 1 and gate not in E[n] and '@ZW' not in E[n]]
if w1_without: fail('W1 nodes without an edge from the pre-check gate: %s' % w1_without)
# (P2) the precedence list of the A1 gate: requirements, plan and the combined table rows agree
mp_ = re.search(r'\*\*Criteria and precedence \(([^;]+); the first that applies wins\)', req)
PREC = [x.strip() for x in re.split(r', then ', mp_.group(1))] if mp_ else []
pp_ = re.search(r'with the precedence ([A-Z, then]+): UNMEASURED', plan)
PREC_PLAN = [x.strip() for x in re.split(r', then ', pp_.group(1))] if pp_ else []
if len(PREC) != 4 or PREC != PREC_PLAN: fail('A1 gate precedence differs: requirements %s, plan %s (four outcomes expected)' % (PREC, PREC_PLAN))
ct_ = req.split('## Combined outcomes: A1 x A4 x A6')[1].split('\n## ')[0] if '## Combined outcomes: A1 x A4 x A6' in req else ''
for nm_ in PREC:
    if not re.search(r'^\| %s' % re.escape(nm_), ct_, re.M): fail('combined outcomes table has no row for the A1 outcome %s' % nm_)
fsum = 'the build through W9 is sunk (%sM CU) and the `GATED` set (%sM CU, %s%% of the total)' % (M2(W9c), M2(GATED2), PC(GATED2, tot2))
if fsum not in ct_: fail('the UNMEASURED row does not quote the computed costs: %s' % fsum)
# (P3) the stop rule lists three uses and quotes the closure share
cs_ = blk(req, '- **Choosing between the two stops', '\n- **PIVOT options')
if len(re.findall(r'\((?:a|b|c)\) ', cs_)) != 3 or ('%s%% of the build' % PC(KEPT2 + KEPT1, tot2)) not in cs_: fail('"Choosing between the two stops" must list the uses (a), (b), (c) and quote the closure share %s%% of the build' % PC(KEPT2 + KEPT1, tot2))
# (P nits) metric 5 baseline, size line
mm5 = re.search(r'so (\d+) of the 7 operations exist today \(([^)]*)\) and the other (\d+) \(([^)]*)\) do not', req)
ops5 = re.search(r'performs 7 operations: ([^.]*)\.', req)
if not mm5 or not ops5: fail('metric 5 baseline does not state which of the 7 operations exist today')
else:
    have, miss = [x.strip() for x in mm5.group(2).split(',')], [x.strip() for x in mm5.group(4).split(',')]
    nops = len(re.split(r', (?:and )?', ops5.group(1)))
    if (int(mm5.group(1)), int(mm5.group(3))) != (len(have), len(miss)) or len(have) + len(miss) != 7 or nops != 7: fail('metric 5 baseline counts %s/%s do not equal the lists (%d, %d) and the 7 operations (%d)' % (mm5.group(1), mm5.group(3), len(have), len(miss), nops))
msz = re.search(r'## Size\nXL \(([\d.]+)M CU computed', req)
if not msz or msz.group(1) != M2(tot2): fail('requirements Size line quotes %s M CU, the computed total is %s' % (msz and msz.group(1), M2(tot2)))
# (U1) the newer-schema screen: the routing row and the dialog agree on the actions, and the dialog has no internal term
box = re.search(r'\+ Update SteleKit to view this sketch -+\+\n(.*?)\+-+\+', ux, re.S)
acts = re.findall(r'\[([^\]]+)\]', box.group(1)) if box else []
rt_ = re.search(r'\| any \| newer than supported[^|]*\| no editor \| ([^\n]*) \|', ux)
racts = re.findall(r'\[([^\]]+)\]', rt_.group(1)) if rt_ else []
if not acts or acts != racts: fail('newer-schema actions differ: dialog %s, routing table %s' % (acts, racts))
if box and [t_ for t_ in ('schema', 'v2', 'sidecar') if t_ in box.group(1).lower()]: fail('the newer-schema dialog shows an internal term: %s' % [t_ for t_ in ('schema', 'v2', 'sidecar') if t_ in box.group(1).lower()])
for nm_, tx_ in (('plan D-103', blk(plan, '  - D-103 ', '\n  - D-104')), ('plan Story 1.4.6 dialog bullet', blk(plan, '  - *When* [Start] is activated and no acknowledgement', '\n  - *NEW-sheet variant'))):
    if re.search(r'builds? from the reader-only release[^.;]*show "Update SteleKit to view"', tx_): fail('%s says reader-only builds show "Update SteleKit to view" for a v2 project; the routing matrix opens it read-only' % nm_)
# (U2) one cadence for the consent sheet: every statement of it, in all documents, names the unit "day"
units = []
for nm_, tx_ in (('ux', ux), ('plan', plan), ('requirements', req), ('validation', val.split('```python check-plan')[0])):
    for mm_ in re.finditer(r'(?:once|at most once) per (?:sketch )?project per (day|session)|once per (day|session) per project|at most once per day per project', tx_): units.append((nm_, mm_.group(1) or mm_.group(2) or 'day'))
if not units or {u_ for _, u_ in units} != {'day'}: fail('consent sheet cadence units across %d statements are %s, expected only "day" (D-102)' % (len(units), sorted({u_ for _, u_ in units})))
# (U3) the discovery item: the set of modes it is present in is parsed everywhere
MODES = {'Off', 'NewProjectsOnly', 'On'}
pres = []
for nm_, tx_ in (('ux 14.1', blk(ux, '**Finding the new editor while the mode is Off', '\n\n### 14.2')), ('plan Story 1.4.6 bullet', blk(plan, '**Finding the new editor at the default mode Off', '\n- **No offer')), ('plan Task 1.4.6h', blk(plan, '##### Task 1.4.6h', '\n##### Task 1.4.6i')), ('plan D-97', blk(plan, '  - D-97 ', '\n  - D-98')), ('plan D-110', blk(plan, '  - D-110 ', '\n  - D-111')), ('ux UX-129', blk(ux, '- UX-129 ', '\n- UX-130'))):
    mm_ = re.search(r'present in modes: ([A-Za-z]+(?:, [A-Za-z]+)*)', tx_)
    if not mm_: fail('%s does not state "present in modes: ..."' % nm_); continue
    got = set(re.split(r', ', mm_.group(1)))
    if got != {'Off'} or not got <= MODES: fail('%s says the discovery item is present in modes %s, expected {Off}' % (nm_, sorted(got)))
    pres.append(nm_)
# (U) reason precedence: ids parsed from the ux.md reason table, compared with the plan list
rt2 = blk(ux, '| Case (id, in PRECEDENCE order)', '\n\nThe Details of every')
rids = re.findall(r'^\| `(\w+)`', rt2, re.M)
mpl = re.search(r'are ((?:`\w+`, )+`\w+`), in that precedence order', plan)
pids = re.findall(r'\w+', mpl.group(1)) if mpl else []
if not rids or rids != pids: fail('reason precedence differs: ux.md table %s, plan sentence %s' % (rids, pids))
# (nits) order notes and code anchors
heads14 = {m.group(1): (m.start(), m.group(2)) for m in re.finditer(r'^##### Task (\d+\.\d+\.\d+[a-z]?):(.*)$', plan, re.M)}
for t_, (pos_, h_) in heads14.items():
    for a_ in re.findall(r'\(after Task (\d+\.\d+\.\d+[a-z]?)', h_):
        if a_ not in heads14 or heads14[a_][0] >= pos_: fail('Task %s says it runs after Task %s, which is not defined earlier in the plan' % (t_, a_))
if '(after Task 1.4.6a' not in heads14['1.4.6e'][1] or not any(p_.endswith('ProjectAckStore.kt') for p_ in FL.get('1.4.6a', [])): fail('Task 1.4.6e must say it runs after Task 1.4.6a, the task that creates RT/session/ProjectAckStore.kt')
gm = codefile('kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt')
if gm is not None:
    gl = gm.split('\n'); mh = re.search(r'`GraphManager\.kt:(\d+)`, invoked on a successful removal at `:(\d+)`', plan)
    if not mh or 'var onGraphRemoved' not in gl[int(mh.group(1)) - 1] or 'onGraphRemoved(id)' not in gl[int(mh.group(2)) - 1]: fail('plan cites the onGraphRemoved hook lines %s but the code differs' % (str(mh.groups() if mh else None),))
if not any(p_.endswith('SketchReasonText.kt') for p_ in FL.get('1.4.6e', [])): fail('Task 1.4.6e must list the file holding the SketchReasonText table')
a6n = [n for n in a6edges if n not in PK['JOB2UI']]
paraP = blk(plan, '**Parked-dependency reductions (P2;', '\n\n<!-- plan-parked')
for n_ in a6n:
    if n_ not in paraP or 'built in every outcome' not in paraP: fail('A6 dependant %s is in no parked list and the parked-dependency paragraph does not state it is built in every outcome' % n_)
print('iteration 14: header offsets v1 %d, v2 %d, fully sorted %d (window %s, probe %d); platform capability false on %s; Option W %dk raw %sM CU; A1 precedence %s; discovery item modes {Off} in %d places; reason precedence %s; consent cadence units %s over %d statements; stop closure %d nodes %sM CU' % (o1, o2, osort, SW.group(1) if SW else '?', probe, sorted(cap_false), OPTW, M2(OPTW), PREC, len(pres), '/'.join(rids), sorted({u_ for _, u_ in units}), len(units), len(KEPTN) + len(KEPTW1), M2(KEPT2 + KEPT1)))
print('iteration 13: probe %d x %d = %d bytes, scope 51-%s sheets; Esc order %d rows parsed; project kinds %s; ClassicNoticeLedger cap %s; chars-per-line model property-checked' % (opens, probe, tot_, ms.group(2), len(erows), len(KINDS), ml.group(1) if ml else '?'))
# ================= iteration 15: values again. E4 false passes found by mutation on temp copies, the platform axis, Option W, the per-target solver and the no-editor screen.
VP = val.split('```python check-plan')[0]
ALLDOCS = [('plan', plan), ('requirements', req), ('ux', ux), ('validation', VP)] + [('ADR %s' % f_[:7], rd('decisions/' + f_)) for f_ in sorted(os.listdir(os.path.join(D, 'decisions'))) if f_.endswith('.md')]
adr3 = rd('decisions/ADR-003-constraint-solver-build-and-clean-room.md'); adr4 = rd('decisions/ADR-004-renderer-clean-and-sketchy.md')
# (E4.1, E4.2) ratios: every verification multiplier and the CU factor in every document equals the one the script computes
VERIF = 1.75; CUF = round(1 + 4 * 0.12, 2); nmult = 0
for nm_, tx_ in ALLDOCS:
    for mm_ in re.finditer(r'\bx(1\.\d+)\b', tx_):
        nmult += 1
        if float(mm_.group(1)) != VERIF: fail('%s states a verification multiplier x%s, the computed one is x%s' % (nm_, mm_.group(1), VERIF))
    for mm_ in re.finditer(r'raw x (1\.\d+)|= (1\.\d+) x tokens', tx_):
        if float(mm_.group(1) or mm_.group(2)) != CUF: fail('%s states the CU factor %s, the computed one is %s' % (nm_, mm_.group(1) or mm_.group(2), CUF))
if nmult < 5: fail('only %d verification multipliers were found, the scan is broken' % nmult)
# (E4.3) ADR-001 quotes the same schema offsets as the computed layouts
mo_a = re.search(r'at byte offset (\d+); a v1 file[^)]*?offset (\d+)\)', adr1)
if not mo_a or (int(mo_a.group(1)), int(mo_a.group(2))) != (o2, o1): fail('ADR-001 quotes the schema offsets %s, the computed v2 and v1 offsets are %d and %d' % (mo_a and mo_a.groups(), o2, o1))
# (E4.4) every `path.kt:N` or `path.kt:N-M` citation in every document: the file exists, the range is inside it, and the first cited line names the symbol the sentence cites
srcroots = [os.path.join(ROOTC, 'kmp', 'src'), os.path.join(ROOTC, 'androidApp')]
_idx = {}
for root_ in srcroots:
    for dp_, _, fs_ in os.walk(root_):
        for f_ in fs_:
            if f_.endswith('.kt'): _idx.setdefault(f_, []).append(os.path.join(dp_, f_))
ncite = nanch = 0
if _idx:
    for nm_, tx_ in ALLDOCS:
        for mm_ in re.finditer(r'`(?:(\w+)/\.\.\./)?(\w+\.kt):(\d+)(?:-(\d+))?`', tx_):
            hint_, fn_, a_, b_ = mm_.group(1), mm_.group(2), int(mm_.group(3)), mm_.group(4)
            cands_ = [p_ for p_ in _idx.get(fn_, []) if not hint_ or ('/%s/' % hint_) in p_]
            if len(cands_) != 1: continue
            lines_ = open(cands_[0], encoding='utf-8').read().split('\n'); ncite += 1
            hi_ = int(b_) if b_ else a_
            if a_ < 1 or hi_ > len(lines_) or hi_ < a_: fail('%s cites %s:%s which is outside the %d-line file' % (nm_, fn_, mm_.group(3) + ('-' + b_ if b_ else ''), len(lines_))); continue
            before_ = tx_[max(0, mm_.start() - 70):mm_.start()]
            idents_ = [x_.split('.')[-1].rstrip('()') for x_ in re.findall(r'`([A-Za-z_][\w.]*(?:\(\))?)`', before_)]
            idents_ = [x_ for x_ in idents_ if len(x_) > 4 and not x_.endswith('.kt') and x_ != fn_[:-3]][-3:]
            if not idents_: continue
            nanch += 1
            if not any(x_.lower() in ' '.join(lines_[a_ - 1:a_ + (1 if b_ else 0)]).lower() for x_ in idents_): fail('%s cites %s:%s but its first line(s) (%d) name none of %s' % (nm_, fn_, mm_.group(3) + ('-' + b_ if b_ else ''), a_, idents_))
# (E4.5) a wave stated in a task heading ("(W8," or "wave W9") equals the wave of the task's node
for mm_ in re.finditer(r'^##### Task (\d+\.\d+\.\d+[a-z]?):(.*)$', plan, re.M):
    t_, h_ = mm_.groups(); n_ = node_of(t_)
    if n_ is None or n_ not in W: continue
    for wm_ in re.finditer(r'(?:\(|\bwave )W(\d+)\b', h_):
        refs_ = [] if wm_.group(0).startswith('wave') else re.findall(r'(?:Task|Story) (\d+\.\d+\.\d+[a-z]?)', h_[:wm_.start()])
        who_ = refs_[-1] if refs_ else t_; wn_ = node_of(who_) if who_ in size else (who_ if who_ in W else None)
        if wn_ and wn_ in W and int(wm_.group(1)) != W[wn_]: fail('Task %s heading says W%s next to %s but its node %s is W%d' % (t_, wm_.group(1), who_, wn_, W[wn_]))
# (E4.6) D-ids cited in a bullet that names its subject: every cited default must carry that subject in its own entry
dent = {int(m_.group(1)): m_.group(0) for m_ in re.finditer(r'^  - D-(\d+) .*$', uq, re.M)}
ANCH = [('- Cost bound of the `NewSketchProject` predicate', '`NewSketchProject`', '\n- Reason strings')]
for st_, tok_, en_ in ANCH:
    bb_ = blk(plan, st_, en_); mcl_ = re.search(r'; ((?:D-\d+(?:, )?)+)\)\.', bb_[:500])
    if not mcl_: fail('the bullet "%s" lists no D-ids in its opening parenthesis' % st_); continue
    ids_ = [int(x_) for x_ in re.findall(r'D-(\d+)', mcl_.group(1))]
    if ids_ != sorted(set(ids_)): fail('the D-ids of "%s" are not ascending and distinct: %s' % (st_, ids_))
    for k_ in ids_:
        if k_ not in dent: fail('"%s" cites D-%d which does not exist' % (st_, k_))
        elif tok_ not in dent[k_]: fail('"%s" cites D-%d but that default never mentions %s (a wrong id in the list)' % (st_, k_, tok_))
# (P1) the platform axis: one list in "Reachable demand", repeated by every form and by metric 8
mplat = re.search(r'records a \*\*platform\*\* \(((?:`\w+`[^`(]*?)+)\)', rdsec13)
PLAT = re.findall(r'`(\w+)`', mplat.group(1)) if mplat else []
if PLAT != ['desktop', 'android', 'web', 'ios']: fail('"Reachable demand" defines the platforms %s, expected desktop, android, web, ios in order' % PLAT)
for lab_, tx_ in (('Story 0.1.7 (Task 0.1.7c)', blk(plan, '#### Story 0.1.7', '\n#### ')), ('Task 0.1.2d', blk(plan, '##### Task 0.1.2d', '\n#### ')), ('plan A1 gate criterion', blk(plan, '- A1 go/no-go gate (Task 6.2.4e;', '\n- Bracket-plate'))):
    got_ = [p_ for p_ in PLAT if '`%s`' % p_ in tx_]
    if got_ != PLAT: fail('%s does not record the platform (names %s of %s)' % (lab_, got_, PLAT))
for lab_, tx_ in (('requirements A6 "What the owner does"', blk(req, '## A6 go/no-go', '- **Threshold')), ('requirements A1 gate "What the owner does"', blk(req, '## A1 go/no-go gate', '- **Criteria'))):
    if not re.search(r'platform[^.;]{0,30}project kind', tx_): fail('%s does not record the platform beside the project kind' % lab_)
if 'platform' not in m8.lower(): fail('metric 8 does not cut by the platform')
if 'Platform cut' not in m8 or 'V2StorageAvailable' not in m8: fail('metric 8 lacks the platform cut over V2StorageAvailable')
if 'V2StorageAvailable' not in rdsec13: fail('"Reachable demand" does not tie reachability to V2StorageAvailable')
# (P2) platform framing: the retired claims are gone, and the "where each metric is measured" line is parsed per metric
for nm_, tx_ in ALLDOCS:
    for ph_ in ('runs identically on Desktop, Android, iOS and Web', 'Desktop, Android, iOS, Web.', 'ships the CLASSIC sketch editor only', 'ships the classic sketch editor only', 'for the web or iOS yet', 'Web and iOS cannot persist', 'no wasm read path exists', 'a FAIL makes likely', 'proceed / adjust / pivot', 'PROCEED / ADJUST / PIVOT', 'wasm gets no override'):
        if ph_ in tx_: fail('%s still contains the retired phrase "%s"' % (nm_, ph_))
mw = re.search(r'\*\*Where each metric is measured[^*]*\*\*: (.*?)\n', req)
MW = dict(re.findall(r'metric (\d) = ([^;]+?)(?=; metric \d = |\. The new editor)', mw.group(1))) if mw else {}
if sorted(MW) != [str(i_) for i_ in range(1, 9)]: fail('"Where each metric is measured" parses metrics %s, expected 1 to 8' % sorted(MW))
else:
    for k_, v_ in MW.items():
        if 'iOS' in v_ and k_ not in ('4', '8'): fail('metric %s names iOS but only the compile-only check of metric 4 and the exclusion in metric 8 may' % k_)
    if 'iOS compile-only' not in MW['4'] or 'iOS excluded' not in MW['8']: fail('metric 4 must say "iOS compile-only" and metric 8 "iOS excluded" in the platform line')
if 'iOS compile-only' not in adr4 or 'Desktop, Android and Web first' not in adr4: fail('ADR-004 targets are not restated for Desktop, Android and Web with iOS compile-only')
oq5 = re.search(r'^\| OQ5 \|[^\n]*$', req, re.M)
if not oq5 or 'Option W' not in oq5.group(0) or 'compile-only' not in oq5.group(0): fail('the OQ5 row does not record Web storage (Option W) and iOS compile-only')
# (P3, nits) the pre-check FAIL is recorded as "no demand" or "structural"; the abandoned figure is computed; the pivot row says metric 8 cannot show success
for lab_, tx_ in (('requirements pre-check', blk(req, '- **A FAIL is recorded as', '\n- **Choosing between')), ('plan Task 0.1.7c story', blk(plan, '#### Story 0.1.7', '\n#### ')), ('plan D-115', blk(plan, '  - D-115 ', '\n  - D-116'))):
    if '"no demand"' not in tx_ or '"structural"' not in tx_: fail('%s does not require the owner to record a FAIL as "no demand" or "structural"' % lab_)
if 'cannot show success' not in blk(req, '| Import/export first |', '\n| v1 editor extended only'): fail('the import/export-first pivot row does not say that metric 8 cannot show success')
gl_ = re.search(r'\| `AdoptionGate` \| [^(]*\(([^)]*)\)', plan)
if not gl_ or [x_.strip() for x_ in re.split(r' / ', gl_.group(1).split(', in that')[0])] != PREC: fail('the AdoptionGate glossary line does not list the four outcomes in the precedence order %s: %s' % (PREC, gl_ and gl_.group(1)))
if 'and the gate decides UNMEASURED / PIVOT / ADJUST / PROCEED' not in req or [x_ for x_ in PREC] != ['UNMEASURED', 'PIVOT', 'ADJUST', 'PROCEED']: fail('the A1 row of the risky assumptions does not name the four outcomes in the precedence order')
h64 = re.search(r'^##### Task 6\.2\.4e:.*$', plan, re.M)
if not h64 or any(x_ not in h64.group(0) for x_ in PREC): fail('the heading of Task 6.2.4e does not name the four outcomes %s' % PREC)
# (P4) the solver tier is decided per target: the tier names and the per-target rule agree in the abort criteria, the plan spike story, the glossary and ADR-003
TIERS = {'Full', 'GeometricOnly', 'Hidden'}
for lab_, tx_ in (('requirements abort criteria', blk(req, '- **Solver (Spike 0.1.2)', '\n- **Cross-target text layout')), ('plan Spike 0.1.2 thresholds', blk(plan, '**Abort/pivot thresholds (requirements', '**Fallback if the spike fails')), ('ADR-003 item 7', blk(adr3, '7. **Fallback acceptance', '\n## Alternatives'))):
    got_ = set(re.findall(r'`(Full|GeometricOnly|Hidden)`', tx_))
    if got_ != TIERS or 'per target' not in tx_.lower() or 'wasmJs' not in tx_: fail('%s does not decide the solver tier per target with %s (found %s)' % (lab_, sorted(TIERS), sorted(got_)))
if 'SolverTier' not in plan.split('## Domain Glossary')[1].split('## Pattern Decisions')[0]: fail('the glossary lacks SolverTier')
for lab_, tx_ in (('plan glossary SolverTier row', re.search(r'\| `SolverTier` \|[^\n]*', plan).group(0)), ('plan D-114', blk(plan, '  - D-114 ', '\n  - D-115')), ('ADR-003 item 7', blk(adr3, '7. **Fallback acceptance', '\n## Alternatives'))):
    if not re.search(r'time miss (?:only )?on its own target', tx_): fail('%s does not state that a time miss selects the tier of its own target only' % lab_)
if 'never takes the full solver' not in req or 'never inherits another target' not in req: fail('requirements does not state the per-target tie-break (a target never inherits another target\'s miss)')
# (E1) the capability is store != null && isAvailable(); the null-store case is in the requirement, the glossary, ADR-005, Task 1.4.3h and the matrix cells; the cited lines are in the code
for lab_, tx_ in (('requirements flag modes', req), ('plan glossary', plan.split('## Domain Glossary')[1].split('## Pattern Decisions')[0]), ('ADR-005', adr5i)):
    if 'store != null && store.isAvailable()' not in tx_: fail('%s does not define V2StorageAvailable as `store != null && store.isAvailable()`' % lab_)
if 'null store' not in blk(plan, '##### Task 1.4.3h', '\n#####').lower() and 'null' not in blk(plan, '##### Task 1.4.3h', '\n#####'): fail('Task 1.4.3h does not cover the null store')
mxb = blk(plan, '- Mode matrix and the kind of a NEW sheet', '\n- Cost bound')
mxb2 = re.search(r'`V2StorageAvailable` \(cells: ([^)]*)\)', mxb)
cellset = lambda tx_: [c_.strip() for c_ in re.split(r', ', re.sub(r';.*$', '', tx_))] if tx_ else []
plcells = re.search(r'one cell per capability case \(([^)]*)\)', plb)
want_cells = ['desktop true', 'android true', 'wasmJs with an Available OPFS verdict true', 'wasmJs with an Unavailable verdict false', 'iOS false', 'store null false']
for lab_, got_ in (('the mode-matrix bullet', cellset(mxb2.group(1)) if mxb2 else []), ('the platform-capability bullet', [x_.replace(' OPFS', '') for x_ in cellset(plcells.group(1))] if plcells else [])):
    if [x_.replace(' OPFS', '') for x_ in got_] != [x_.replace(' OPFS', '') for x_ in want_cells]: fail('%s lists the FlagModeMatrixTest capability cells %s, expected %s' % (lab_, got_, want_cells))
gcs = codefile('kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/GraphContentViewModelSetup.kt')
if gcs is not None:
    gl_ = gcs.split('\n'); mg_ = re.search(r'`GraphContentViewModelSetup\.kt:(\d+)-(\d+)`', plan)
    if not mg_ or 'isDemo' not in '\n'.join(gl_[int(mg_.group(1)) - 1:int(mg_.group(2))]) or 'val sidecarStore' not in gl_[int(mg_.group(1)) - 1]: fail('the null-store citation GraphContentViewModelSetup.kt:%s does not cover `val sidecarStore` and the isDemo test' % (mg_ and '%s-%s' % mg_.groups()))
# (E2) the header probe has ONE owner task that lists SheetCodec.kt; the split tasks all have file lines within five files; commonTest files have their Bazel and registration lines
cfh = [t_ for t_, h_ in ((m_.group(1), m_.group(2)) for m_ in re.finditer(r'^##### Task (\d+\.\d+\.\d+[a-z]?):(.*)$', plan, re.M)) if '`SheetCodec.classifyHeader(' in h_]
if len(cfh) != 1 or not any(p_.endswith('projectbridge/SheetCodec.kt') for p_ in FL.get(cfh[0], [])): fail('classifyHeader must be named by exactly one task heading whose file list holds SheetCodec.kt (found %s)' % cfh)
for t_ in ('1.1.5c', '1.4.3d', '1.4.3i', '1.4.3j', '1.4.6j', '1.4.7a', '1.4.7b', '1.4.7c', '1.4.7d', '1.4.7e', '1.4.7f', '1.4.7g', '1.4.7h', '1.4.7i', '1.4.7j', '1.4.7k', '1.4.7l', '0.1.8a', '0.1.8b', '5.1.5b'):
    if t_ not in FL: fail('Task %s has no file-list line' % t_)
for t_, ps_ in FL.items():
    if any(p_.endswith('commonTest/kotlin/BUILD.bazel') for p_ in ps_) and not any(p_.endswith('AllBusinessTests.kt') for p_ in ps_): fail('Task %s edits the commonTest BUILD.bazel but does not list AllBusinessTests.kt, so its commonTest classes would not be registered' % t_)
heads_ = {m_.group(1): m_.group(2) for m_ in re.finditer(r'^##### Task (\d+\.\d+\.\d+[a-z]?):(.*)$', plan, re.M)}
reg_heads = [h_ for t_, h_ in heads_.items() if any(q_.endswith('commonTest/kotlin/BUILD.bazel') for q_ in FL.get(t_, []))]
for t_ in ('1.4.3d', '1.4.3i', '1.4.3j', '1.4.7a'):
    for p_ in FL.get(t_, []):
        if p_.startswith('CT/') and p_.endswith('Test.kt'):
            nm_ = p_.split('/')[-1][:-3]
            if not any('`%s`' % nm_ in h_ for h_ in reg_heads): fail('commonTest class %s (Task %s) is named by no registration task heading (BUILD.bazel srcs and AllBusinessTests)' % (nm_, t_))
# (E3) stale web text: the plan and the ux routing table agree that web import is back and that BUG-004 is cited as OPEN
bug4 = codefile('docs/bugs/open/BUG-004-wasm-page-drop-target-noop.md')
if bug4 is not None and 'Status**: Open' not in bug4: fail('BUG-004 is no longer open in docs/bugs/open, the plan still cites it as open')
s515 = blk(plan, '#### Story 5.1.5', '\n##### Task 5.1.5a')
if 'BUG-004 is still open' not in s515 or 'CanvasFileDrop' not in s515 or 'Option W' not in s515: fail('Story 5.1.5 does not reconcile the web canvas drop with Option W and BUG-004')
# (U1, U3, U4, U5) the no-editor screen: ux.md 14.7 table, plan action lists and routing precedence parsed and compared
t147 = blk(ux, '### 14.7 The no-editor screen', '### Error table')
rows147 = {m_.group(1).strip(): [x_.strip() for x_ in re.findall(r'\[([^\]]+)\]', m_.group(3))] for m_ in re.finditer(r'^\| (Newer schema|Storage unavailable) \| ([^|]+) \| ([^\n]+) \|$', t147, re.M)}
mpa = re.search(r'`NoEditorScreen` actions, newer schema: ((?:\[[^\]]+\](?:, )?)+); storage unavailable: ((?:\[[^\]]+\](?:, )?)+)\.', plan)
pa_ = {'Newer schema': re.findall(r'\[([^\]]+)\]', mpa.group(1)), 'Storage unavailable': re.findall(r'\[([^\]]+)\]', mpa.group(2))} if mpa else {}
if len(rows147) != 2 or rows147 != pa_: fail('the no-editor screen actions differ: ux.md 14.7 %s, plan Story 1.4.6 %s' % (rows147, pa_))
if 'Copy details' not in rows147.get('Storage unavailable', []) or 'Export last backup' in rows147.get('Storage unavailable', []): fail('the storage-unavailable screen must offer [Copy details] and must not offer an export')
rt_rows = re.findall(r'^\| ([^|]+) \| ([^|]+) \| ([^|]+) \| ([^|]+) \|$', blk(ux, '| Mode (`V2Mode`, effective after the build cap) | Sheet schema |', '\n\n**Precedence'), re.M)
if not rt_rows or 'V2StorageAvailable' not in rt_rows[0][0]: fail('the first row of the ux.md routing table must be the storage-unavailable row (precedence), found %s' % (rt_rows[0][0] if rt_rows else None))
i_store = next((i_ for i_, r_ in enumerate(rt_rows) if 'V2StorageAvailable' in r_[0]), 99); i_off2 = next((i_ for i_, r_ in enumerate(rt_rows) if 'Off' == r_[0].strip() and r_[1].strip().startswith('2')), 99)
if i_store > i_off2: fail('the storage-unavailable row (%d) must come before the (Off, 2) row (%d) in the ux.md routing table' % (i_store, i_off2))
t146 = blk(ux, '### 14.6 Rollback', '### 14.7')
if 'V2StorageAvailable` is true' not in t146 or 'no-editor screen' not in t146 or 'nothing can be exported' not in t146: fail('ux.md 14.6 still says exports remain available on the no-editor path')
rids2 = re.findall(r'^\| `(\w+)`', blk(ux, '| Case (id, in PRECEDENCE order)', '\n\nThe Details of every'), re.M)
if rids2[:2] != ['Platform', 'BuildCapped']: fail('the reason table must start with Platform then BuildCapped, found %s' % rids2[:2])
if 'the order `Platform` then `BuildCapped`' not in plan or '`Platform` (the case text above) before `BuildCapped`' not in blk(ux, '### 14.1 Enable', '### 14.2'): fail('plan Task 1.4.6h and ux.md 14.1 do not state the one reason precedence Platform before BuildCapped')
for lab_, tx_ in (('ux.md 14.1 ClassicEditor rule', blk(ux, 'New sheet created as classic (UX-77', '### 14.2')), ('ux.md section 16 toast row', blk(ux, '## 16. Diagnostics host', '## 17.')), ('plan Story 1.4.6 recurrence', blk(plan, 'Recurrence (one row of the toast recurrence table of ux.md section 16)', 'a different reason in the same project'))):
    if 'STORED mode' not in tx_: fail('%s does not say that the toast suppression means the STORED mode is Off' % lab_)
for u_ in ('UX-134', 'UX-135', 'UX-136', 'UX-137', 'UX-138', 'UX-139', 'UX-140', 'UX-141', 'UX-142', 'UX-143'):
    if not re.search(r'^\| %s ' % u_, VP, re.M): fail('validation.md has no table row whose first cell is %s' % u_)
a11y = blk(ux, '**Accessibility contract (UX-138', '\n\n###')
mroles = re.search(r'the container is a (\w+) named by the heading, not a (\w+)', a11y)
if not mroles or mroles.groups() != ('region', 'dialog') or not re.search(r'heading is level 1', a11y) or 'Esc and system back activate [Close]' not in a11y or 'returns focus to the control that opened the sketch' not in a11y: fail('the no-editor accessibility contract of ux.md 14.7 must give role region (not dialog), heading level 1, Esc and back as [Close] and the focus return; parsed %s' % (mroles and mroles.groups(),))
if 'Accepted deviation (D-118' not in ux or '1.4.10' not in ux: fail('ux.md does not record the 400% zoom rows as an accepted AA deviation')
# (nit) the A6 share of the total is the computed percentage wherever it is quoted beside the constraint-UI figure
na6 = 0
for nm_, tx_ in ALLDOCS:
    for mm_ in re.finditer(r'(?:%sM CU, |Task 6\.2\.4f \()(\d+\.\d)%% of the total' % re.escape(M2(A6PROT)), tx_):
        na6 += 1
        if mm_.group(1) != PC(A6PROT, tot2): fail('%s quotes the A6 share %s%% of the total, the computed share is %s%%' % (nm_, mm_.group(1), PC(A6PROT, tot2)))
if na6 < 4: fail('only %d quotations of the A6 share were found, the scan is broken' % na6)
print('iteration 15: multipliers %d x%s, CU factor %s, ADR-001 offsets %s/%s, %d code citations checked (%d anchored), platforms %s, solver tiers %s, Option W %s %dk raw %sM CU, no-editor actions %s, UX 134-138' % (nmult, VERIF, CUF, o2, o1, ncite, nanch, PLAT, sorted(TIERS), OPTW_MIX, OPTW, M2(OPTW), rows147))

# ================= iteration 16: Option W repaired. Every check parses or computes a value; none matches a wording fragment.
# (E8) "after X" / "before X" clauses of task headings against the edge block; same-node order must be acyclic
_IDRX = r'(?:(?:Tasks?|Stor(?:y|ies)) )?\d+\.\d+\.\d+[a-z]?'
def _seg_ids(h):
    return [(mm.group(1), re.findall(r'\b\d+\.\d+\.\d+[a-z]?\b', mm.group(2))) for mm in re.finditer(r'\b(after|before) (%s(?:(?:, and |, | and )%s)*)' % (_IDRX, _IDRX), h)]
def _nodeof_any(x): return node_of(x) if x in size else (x if x in E else None)
ORD16 = {}; nclause = 0
for mm in re.finditer(r'^##### Task (\d+\.\d+\.\d+[a-z]?):(.*)$', plan, re.M):
    t, h = mm.groups(); n = node_of(t)
    for kw, ids in _seg_ids(h):
        for x in ids:
            nx = _nodeof_any(x)
            if nx is None: fail('Task %s heading names %s %s, which is neither a task nor a node' % (t, kw, x)); continue
            nclause += 1
            first, second = (x, t) if kw == 'after' else (t, x)
            nf, ns = _nodeof_any(first), _nodeof_any(second)
            if nf == ns: ORD16.setdefault(first, set()).add(second)
            elif nf not in _reach([ns]): fail('Task %s heading puts %s before %s but node %s has no path to node %s in the edge block' % (t, first, second, ns, nf))
def _cyc(g):
    seen = {}
    def dfs(a):
        seen[a] = 1
        for b in g.get(a, ()):
            if seen.get(b) == 1 or (b not in seen and dfs(b)): return True
        seen[a] = 2; return False
    return any(a not in seen and dfs(a) for a in list(g))
if _cyc(ORD16): fail('the same-node "after"/"before" clauses of task headings form a cycle: %s' % {k: sorted(v) for k, v in ORD16.items()})
# planted self-test: a clause that contradicts an existing one must be a cycle
_g2 = {k: set(v) for k, v in ORD16.items()}; _g2.setdefault('1.4.3i', set()).add('1.4.3h')
if not ORD16.get('1.4.3h') or not _cyc(_g2): fail('after/before self-test: a planted reverse order 1.4.3i before 1.4.3h was not a cycle')
# (E7) the verdict truth table: the reasons' order is parsed from the plan, the table recomputed, the stated row counts compared
vb16 = blk(plan, '- Per-browser verdict (pure; E7, D-125).', '\n- What "durable" means')
facts16 = re.search(r'\((`\w+`(?:, `\w+`){4}); the middle two', vb16)
FACTS16 = re.findall(r'`(\w+)`', facts16.group(1)) if facts16 else []
order16 = re.findall(r'(?:is|;) `Unavailable\((\w+)\)`', vb16)
if FACTS16 != ['rootPresent', 'createWritable', 'moveVerified', 'copyVerifyVerified', 'sliceRead']: fail('the verdict facts are %s, expected the five in order' % FACTS16)
if order16 != ['NotProbed', 'NoOpfs', 'NoCreateWritable', 'NoAtomicReplace', 'NoSliceRead']: fail('the verdict reasons are %s in this order, expected NotProbed, NoOpfs, NoCreateWritable, NoAtomicReplace, NoSliceRead' % order16)
import itertools
FAILS16 = {'NoOpfs': lambda f: not f[0], 'NoCreateWritable': lambda f: not f[1], 'NoAtomicReplace': lambda f: not (f[2] or f[3]), 'NoSliceRead': lambda f: not f[4]}
cnt16 = {}
for f_ in itertools.product((False, True), repeat=5):
    r_ = next((x for x in order16[1:] if FAILS16.get(x, lambda f: False)(f_)), None) or ('Available(Move)' if f_[2] else 'Available(CopyVerify)')
    cnt16[r_] = cnt16.get(r_, 0) + 1
mt16 = re.search(r'has (\d+) `NoOpfs`, (\d+) `NoCreateWritable`, (\d+) `NoAtomicReplace`, (\d+) `NoSliceRead`, (\d+) `Available\(Move\)` and (\d+) `Available\(CopyVerify\)` rows', vb16)
want16 = [cnt16.get(k, 0) for k in ('NoOpfs', 'NoCreateWritable', 'NoAtomicReplace', 'NoSliceRead', 'Available(Move)', 'Available(CopyVerify)')]
if not mt16 or [int(x) for x in mt16.groups()] != want16 or sum(want16) != 32: fail('the plan states the truth-table row counts %s, the computed counts are %s (sum %d)' % (mt16 and mt16.groups(), want16, sum(want16)))
if 'persistence' in ' '.join(order16).lower() or 'Persist' in ' '.join(order16): fail('persistence must not be a verdict reason')
# (E7, U1) the web reason table of ux.md 14.1 covers exactly the Unavailable reasons plus the two status reasons, with its own text each
sr16 = re.search(r'`reason` one of the verdict reasons, `(\w+)`, `(\w+)` or `(\w+)`', blk(plan, '- Preload is a fact of availability', '\n- Persistence request'))
EXTRA16 = list(sr16.groups()) if sr16 else []
wt16 = blk(ux, '**Web storage reasons', '**While the storage is being checked')
wrows = re.findall(r'^\| `([\w:]+)` \| [^|]+ \| "([^"]+)" \|$', wt16, re.M)
wid = [i_.split(':')[0] for i_, _ in wrows]
want_ids = order16[1:] + EXTRA16
if sorted(set(wid)) != sorted(want_ids): fail('the web reason table lists %s, the Unavailable reasons plus status reasons are %s' % (sorted(set(wid)), sorted(want_ids)))
if [i_ for i_, _ in wrows].count('NoOpfs:iosWebKit') != 1 or [i_ for i_, _ in wrows].count('NoOpfs') != 1: fail('the web reason table must have exactly one NoOpfs row and one NoOpfs:iosWebKit row')
tx16 = dict(wrows)
if tx16.get('NoOpfs') == tx16.get('NoOpfs:iosWebKit') or not tx16.get('NoOpfs'): fail('the iOS WebKit reason must differ from the generic NoOpfs reason')
for i_, t_ in wrows:
    if i_ != 'NoOpfs:iosWebKit' and re.search(r'Chrome|Firefox|Safari|Edge', t_): fail('web reason %s names a browser: %s' % (i_, t_))
rt16 = re.findall(r'^\| ([^|]+) \| ([^|]+) \| ([^|]+) \| ([^|]+) \|$', blk(ux, '| Mode (`V2Mode`, effective after the build cap) | Sheet schema |', '\n\n**Precedence'), re.M)
if not rt16 or 'Checking' not in rt16[0][0] or 'pending' not in rt16[0][2] or 'Checking' in rt16[1][0]: fail('the first routing row must be the pending (Checking) row and the second the storage-unavailable row')
# (E1..E6) the contract numbers quoted in the plan agree with the code: pagehide only logs, one Deferred per path, preload swallows
wfs = codefile('kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt')
if wfs is not None:
    if 'mutableMapOf<String, Deferred<Unit>>()' not in wfs: fail('code: opfsWriteInFlight is no longer one Deferred per path, so the E2 queue rationale is stale')
    if 'jsPageHidePromise' in wfs and 'awaitDurable' in wfs: fail('code: the wasm file system already has awaitDurable; Task 1.4.7f is stale')
    if 'OPFS preload failed, starting with empty graph' not in wfs: fail('code: the preload no longer swallows OPFS failures, so the E6 fact is stale')
# (E3) the cost figure: the quoted old figure is the iteration 15 constant, the new one is larger, and 0.66M / 445k occur only in the two sentences
oldq = [re.search(r'iteration 15 priced (?:it|Option W) at (\d+)k raw tokens and ([\d.]+)M CU', t_) for t_ in (plan, req)]
if any(o_ is None for o_ in oldq) or [(o_.group(1), o_.group(2)) for o_ in oldq] != [('445', '0.66')] * 2: fail('plan and requirements must each quote the iteration 15 Option W figure 445k raw and 0.66M CU')
if OPTW <= 445: fail('Option W now costs %dk raw, which must exceed the iteration 15 figure 445k' % OPTW)
for tok_ in ('0.66M', '445k'):
    for nm_, tx_ in ALLDOCS:
        for mm_ in re.finditer(r'(?<![\d.])' + re.escape(tok_), tx_):
            ctx_ = tx_[max(0, mm_.start() - 90):mm_.end()]
            if 'iteration 15 priced' not in ctx_ and not re.search(r'\| 3\.4 [^|\n]*\|[^\n]*$', ctx_ + tx_[mm_.end():tx_.find('\n', mm_.end())]): fail('%s mentions the superseded figure %s outside its one quotation (and the unrelated Epic 3.4 table row)' % (nm_, tok_))
# (P) whole-option abandonment: the parked set is the computed set of Story 1.4.7 tasks, every node with an edge into it has a REDUCED line, metrics 6 and 8 and the documents say what they become
S147 = sorted(n for n in E if re.fullmatch(r'1\.4\.7[a-z]', n))
if sorted(PK.get('OPFSSPIKE', [])) != S147: fail('plan-parked OPFSSPIKE %s differs from the nodes of Story 1.4.7 %s' % (sorted(PK.get('OPFSSPIKE', [])), S147))
if [t_ for t_ in size if t_.startswith('1.4.7') and t_ not in S147]: fail('a task of Story 1.4.7 has no node of its own: %s' % [t_ for t_ in size if t_.startswith('1.4.7') and t_ not in S147])
for n_, d_ in E.items():
    if n_ in S147: continue
    if set(d_) & set(S147) and ('OPFSSPIKE' not in RED.get(n_, ['', '', ''])[1]): fail('node %s has an edge into Story 1.4.7 but its REDUCED line does not name OPFSSPIKE' % n_)
for lab_, tx_ in (('plan', plan), ('requirements', req), ('validation', VP)):
    if 'Skipped(opfs-spike)' not in tx_: fail('%s.md never records Story 1.4.7 as Skipped(opfs-spike)' % lab_)
if 'opfs-spike' not in MW.get('6', '') or 'opfs-spike' not in MW.get('8', ''): fail('metric 6 and metric 8 of "Where each metric is measured" must say what they become under Skipped(opfs-spike)')
if not re.search(r'^\| OPFS spike: neither Chrome nor Firefox', req, re.M) or 'OPFSSPIKE' not in req: fail('requirements "Combined outcomes" and "Parked outcomes" do not cover the failed OPFS spike')
# (D) every D-id cited anywhere exists
dmax16 = nd
for nm_, tx_ in ALLDOCS:
    for mm_ in re.finditer(r'\bD-(\d+)\b', tx_):
        if int(mm_.group(1)) > dmax16 or int(mm_.group(1)) < 1: fail('%s cites D-%s, the plan lists D-1..D-%d' % (nm_, mm_.group(1), dmax16))
# (nit) Option W code citations: every citation of the files below equals a line or range COMPUTED from the code, and the required ones are present
def _lines(rel):
    p_ = os.path.join(ROOTC, rel); return open(p_, encoding='utf-8').read().split('\n') if os.path.exists(p_) else None
def _ln(L_, rx, occ=1):
    hits = [i_ + 1 for i_, l_ in enumerate(L_) if re.search(rx, l_)]; return hits[occ - 1] if len(hits) >= occ else None
def _close(L_, start):
    ind = len(L_[start - 1]) - len(L_[start - 1].lstrip())
    return next((i_ + 1 for i_ in range(start, len(L_)) if L_[i_] == ' ' * ind + '}'), None)
KT = 'kmp/src/%s/kotlin/dev/stapler/stelekit/'
ANCH16 = {}
def _reg(rel, items, required, strict=True):
    L_ = _lines(rel)
    if L_ is None: fail('code anchor file is missing (kmp/ absent or the code moved): ' + rel); return
    got_ = set()
    for it_ in items:
        try: v_ = it_(L_)
        except TypeError: v_ = None  # an anchor line the code no longer has (a `%d` of None): report it as a missing anchor, not a crash
        if v_ is None or 'None' in v_: fail('code anchor for %s is missing (the code changed; a cited line range can no longer be computed): %s' % (rel, it_.__doc__ or '')); continue
        got_.add(v_)
    ANCH16[rel] = (got_, required, strict)
w_ = KT % 'wasmJsMain' + 'platform/PlatformFileSystem.kt'
_reg(w_, [lambda L: str(_ln(L, r'private val opfsWriteInFlight')), lambda L: str(_ln(L, r'println\("\[SteleKit\] pagehide')), lambda L: str(_ln(L, r'println\("\[SteleKit\] OPFS preload failed')), lambda L: str(_ln(L, r'opfsWriteInFlight\[path\] = scope\.async')), lambda L: str(_ln(L, r'opfsWriteInFlight\[path\] = scope\.async', 2)), lambda L: str(_ln(L, r'HostWritePayload\.Bytes\(data\)')), lambda L: '%d-%d' % (_ln(L, r'override fun renameFile'), _close(L, _ln(L, r'override fun renameFile'))), lambda L: '%d-%d' % (_ln(L, r'actual override fun fileExists'), _ln(L, r'\.distinct\(\)')), lambda L: str(_ln(L, r'override val supportsTempRenameWrites')), lambda L: str(_ln(L, r'actual override fun getLastModifiedTime'))], {'62', '194', '233', '574', '610-618', '491-501', '622', '859', '564'})
_reg(KT % 'iosMain' + 'platform/PlatformFileSystem.kt', [lambda L: str(_ln(L, r'override val supportsTempRenameWrites'))], {'15'})
_reg(KT % 'jvmMain' + 'platform/PlatformFileSystem.kt', [lambda L: str(_ln(L, r'override fun readFileBytes'))], {'64'})
_reg(KT % 'commonMain' + 'platform/AtomicFileWriter.kt', [lambda L: str(_ln(L, r'fs\.writeFileBytes\(tmp, bytes\)')), lambda L: str(_ln(L, r'fs\.replaceFileAtomically\(tmp, path\)')), lambda L: str(_ln(L, r'val readBack = fs\.readFileBytes\(tmp\)'))], {'65', '80', '83'})
_reg(KT % 'commonMain' + 'platform/StaleTempSweeper.kt', [lambda L: str(_ln(L, r'val modified = fs\.getLastModifiedTime'))], {'23'})
_reg(KT % 'wasmJsMain' + 'platform/WebLock.kt', [lambda L: str(_ln(L, r'suspend fun tryAcquireLeader'))], {'184'})
_reg(KT % 'commonMain' + 'platform/FileSystem.kt', [lambda L: str(_ln(L, r'fun readFileBytes\(path: String\): ByteArray\? ='))], {'192'})
_reg(KT % 'commonMain' + 'projectbridge/ProjectSidecarStore.kt', [lambda L: '%d-%d' % (_ln(L, r'private fun unavailable'), _close(L, _ln(L, r'override suspend fun read\(slug'))), lambda L: '%d-%d' % (_ln(L, r'private fun readLocked'), _close(L, _ln(L, r'private fun readLocked')))], {'71-76'}, False)
_reg(KT % 'commonMain' + 'ui/GraphContentViewModelSetup.kt', [lambda L: '%d-%d' % (_ln(L, r'val sidecarStore = \{'), _close(L, _ln(L, r'val sidecarStore = \{')))], {'221-227'})
seen16 = {k: set() for k in ANCH16}; nchk16 = 0
for nm_, tx_ in ALLDOCS:
    for mm_ in re.finditer(r'`(?:(\w+)/\.\.\./)?(\w+\.kt):(\d+(?:-\d+)?)`', tx_):
        hint_, fn_, cite_ = mm_.groups()
        cands_ = [p_ for p_ in _idx.get(fn_, []) if not hint_ or ('/%s/' % hint_) in p_]
        if len(cands_) != 1: continue
        rel_ = os.path.relpath(cands_[0], ROOTC)
        if rel_ in ANCH16:
            nchk16 += 1; seen16[rel_].add(cite_)
            if ANCH16[rel_][2] and cite_ not in ANCH16[rel_][0]: fail('%s cites %s:%s but the computed lines of that file are %s' % (nm_, fn_, cite_, sorted(ANCH16[rel_][0])))
for rel_, (got_, req_, _st) in ANCH16.items():
    if req_ - seen16[rel_]: fail('no document cites %s for %s (required citations of Option W)' % (sorted(req_ - seen16[rel_]), rel_))
print('iteration 16: %d after/before clauses (%d same-node orders, acyclic), verdict rows %s, web reasons %s, Option W %dk raw (was 445k), OPFSSPIKE %d nodes, %d Option W citations equal their computed lines' % (nclause, sum(len(v) for v in ORD16.values()), want16, sorted(set(wid)), OPTW, len(S147), nchk16))

# ================= iteration 17: Option W repaired again. Every check parses or computes a value; none matches a wording fragment.
import itertools as _it
def _blockany(name, txt):
    mm = re.search(r'<!-- %s:start -->\s*```text\n(.*?)```' % name, txt, re.S)
    return mm.group(1) if mm else None
def _rows(b, n):
    out = []
    for l in (b or '').strip().split('\n'):
        l = l.strip()
        if not l or l.startswith('#'): continue
        p = [x.strip() for x in l.split('|')]
        if len(p) != n: fail('structured row has %d fields, expected %d: %s' % (len(p), n, l[:70])); continue
        out.append(p)
    return out
# (A) the code anchors must not be skipped silently: kmp/ must sit next to project_plans and every file a check reads must exist
REQCODE = ['kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt', 'kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/FileSystem.kt', 'kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/AtomicFileWriter.kt', 'kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/StaleTempSweeper.kt', 'kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt', 'kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/WebLock.kt', 'kmp/src/iosMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt', 'kmp/src/jvmMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt', 'kmp/src/commonMain/kotlin/dev/stapler/stelekit/projectbridge/ProjectSidecarStore.kt', 'kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/GraphContentViewModelSetup.kt', 'kmp/src/jvmCommonMain/kotlin/dev/stapler/stelekit/platform/JvmFileSystemBase.kt', 'kmp/src/businessTest/kotlin/dev/stapler/stelekit/wiring/WiringAudit.kt']
if not os.path.isdir(os.path.join(ROOTC, 'kmp', 'src')):
    fail('kmp/src is not next to project_plans (looked in %s): every code-anchored check would be skipped, so the run fails; symlink or copy kmp/ beside project_plans/' % ROOTC)
else:
    for rel_ in REQCODE:
        if codefile(rel_) is None: fail('code file read by a check is missing: ' + rel_)
# (B) P1: the stale cost figure and the history of the counts paragraph
cp_ = blk(plan, '- Counts are mechanically recomputed by `check-plan.py`', '\n- **Critical path')
mcu_ = re.search(r'\*\*~([\d.]+)M CU\*\* \(([\d.]+)M x1\.75; previous ~([\d.]+)M\)', cp_)
if not mcu_: fail('the counts paragraph lacks the form **~N.NNM CU** (N.NNM x1.75; previous ~N.NNM)')
else:
    if mcu_.group(1) != MV(tot2) or mcu_.group(2) != M2(tot2) or mcu_.group(3) == mcu_.group(1): fail('the counts paragraph says ~%sM CU (%sM x1.75; previous ~%sM) but the computed figures are ~%sM CU and %sM' % (mcu_.group(1), mcu_.group(2), mcu_.group(3), MV(tot2), M2(tot2)))
for nm_, tx_ in ALLDOCS:
    for mm_ in re.finditer(r'~(\d+\.\d+)M CU', tx_):
        if mm_.group(1) != MV(tot2) and not (mcu_ and nm_ == 'plan' and mm_.group(1) == mcu_.group(3) and 'previous ~' in tx_[mm_.start() - 10:mm_.start() + 1]): fail('%s quotes ~%sM CU, the computed figure with verification is ~%sM CU' % (nm_, mm_.group(1), MV(tot2)))
ITER17 = max(int(x_) for x_ in re.findall(r'\*\*Iteration (\d+) checks\*\*', val) or ['0'])
missing_it = [k_ for k_ in range(2, ITER17 + 1) if not re.search(r'[Ii]teration %d\b' % k_, cp_)]
if ITER17 < 17 or missing_it: fail('the counts paragraph does not mention every iteration up to the current %d (missing %s)' % (ITER17, missing_it))
# (C) P2: the Option W keep or drop rule, parsed and exercised
# (C) P1, P2 (iteration 18): the Option W keep or drop rule is parsed from the blocks of requirements.md and every case is recomputed
kdp_ = {r_[0]: r_[1] for r_ in _rows(_blockany('keep-drop', req), 2)}
T_ = int(kdp_['threshold']) if kdp_.get('threshold', '').isdigit() else None
FAM_ = kdp_.get('counted-families', '').split()
if T_ is None or T_ < 1 or sorted(FAM_) != ['chromium', 'firefox'] or kdp_.get('intent-needs', '').split() != ['use', 'family']: fail('requirements keep-drop block lacks threshold >= 1, the counted families chromium and firefox, or the intent needs (use, family): %s' % kdp_)
def kd_webitems(items_):
    n_ = 0
    for it_ in items_.split(';'):
        it_ = it_.strip()
        if it_ in ('', '-'): continue
        pl_, fm_ = it_.split('/', 1)
        if pl_ == 'web' and set(x_.strip() for x_ in fm_.split(',')) & set(FAM_): n_ += 1
    return n_
def kd_intent(s_):
    if s_.strip() in ('', '-'): return False
    use_, fam_ = s_.rsplit('/', 1)
    return use_.strip() not in ('', '-') and fam_.strip() in FAM_
def kd_outcome(items_, intent_s):
    n_ = kd_webitems(items_)
    return n_, ('KEEP' if (T_ is not None and n_ >= T_) or (n_ == 0 and kd_intent(intent_s)) else 'DROP')
KDC_ = _rows(_blockany('keep-drop-cases', req), 5)
for c_, items_, inten_, wi_, out_ in KDC_:
    n2_, o2_ = kd_outcome(items_, inten_)
    if str(n2_) != wi_ or o2_ != out_: fail('keep or drop case %s: the table says webItems %s and %s, the parameters compute %d and %s' % (c_, wi_, out_, n2_, o2_))
kdk_ = {c_[0]: (kd_webitems(c_[1]), kd_intent(c_[2]), kd_outcome(c_[1], c_[2])[1], c_[1], c_[2]) for c_ in KDC_}
if not any(v_[2] == 'DROP' and 'web/safari' in v_[3] and v_[0] == 0 for v_ in kdk_.values()): fail('keep or drop cases lack a Safari-only item that does not keep Option W')
if not any(v_[2] == 'KEEP' and v_[0] >= 1 and 'firefox' in v_[3] and 'chromium' not in v_[3] for v_ in kdk_.values()): fail('keep or drop cases lack a Firefox-only item that keeps Option W')
if not any(v_[0] == 0 and not v_[1] and v_[4] != '-' and v_[2] == 'DROP' for v_ in kdk_.values()): fail('keep or drop cases lack a web intent without a named use')
if not any(v_[0] == 0 and not v_[1] and v_[4].endswith('/safari') and v_[2] == 'DROP' for v_ in kdk_.values()): fail('keep or drop cases lack a Safari-only web intent')
if not any(v_[0] == 0 and v_[1] and v_[2] == 'KEEP' for v_ in kdk_.values()): fail('keep or drop cases lack a named-use web intent that keeps Option W')
# the post-spike demand check and the label precedence
def ab_outcome(counted, verd):
    cf_ = [x_.strip() == 'A' for x_ in verd.split(',')]
    av_ = dict(zip(('chromium', 'firefox', 'safari'), cf_))
    if not (av_['chromium'] or av_['firefox']): return 'ABANDON-SPIKE'
    return 'KEEP' if any(av_.get(x_, False) for x_ in counted.split()) else 'ABANDON-DEMAND'
ABC_ = _rows(_blockany('abandon-cases', req), 4)
for c_, counted_, verd_, out_ in ABC_:
    if len(verd_.split(',')) != 3 or any(x_.strip() not in ('A', 'U') for x_ in verd_.split(',')) or not set(counted_.split()) <= set(FAM_): fail('abandon case %s has a malformed verdict list or a family that the rule does not count' % c_); continue
    if ab_outcome(counted_, verd_) != out_: fail('abandon case %s: the table says %s, the demand check computes %s' % (c_, out_, ab_outcome(counted_, verd_)))
if {r_[3] for r_ in ABC_} != {'KEEP', 'ABANDON-SPIKE', 'ABANDON-DEMAND'}: fail('abandon cases must cover KEEP, ABANDON-SPIKE and ABANDON-DEMAND')
if not any(r_[2].split(',')[2].strip() == 'A' and r_[3] == 'ABANDON-SPIKE' for r_ in ABC_): fail('abandon cases lack a case in which Safari passes and Option W is still abandoned (a Safari verdict never keeps it)')
LPR_ = _rows(_blockany('label-precedence', req), 4)
if [r_[0] for r_ in LPR_] != [str(i_ + 1) for i_ in range(len(LPR_))] or [r_[2] for r_ in LPR_] != ['Skipped(pre-check)', 'Skipped(opfs-spike)', 'Skipped(opfs-spike)', 'Skipped(opfs-spike)'] or len(LPR_) != 4: fail('label-precedence rows are %s' % LPR_)
if set(S147) & set(cl): fail('the pre-check stop keeps Option W nodes %s, so the label Skipped(pre-check) would not name them' % sorted(set(S147) & set(cl)))
plat_ = re.search(r'records a \*\*platform\*\* \(((?:`\w+`(?:, )?)+)', req)
if not plat_ or 'web' not in re.findall(r'`(\w+)`', plat_.group(1)): fail('the pre-check platform field has no `web` value, so the rule has no input')
OPFSDROP_ = sorted(PK.get('OPFSDROP', []))
spk_ = sorted(n for n in E if re.fullmatch(r'0\.1\.8[a-z]?', n))
if OPFSDROP_ != spk_ or not spk_: fail('plan-parked OPFSDROP %s differs from the spike nodes %s' % (OPFSDROP_, spk_))
for n_, d_ in E.items():
    if any(x_ in OPFSDROP_ and x_ != '0.1.8c' for x_ in d_) and n_ not in S147 and n_ != '0.1.8c': fail('node %s depends on the spike node %s but is not in Story 1.4.7, so dropping the spike would break it' % (n_, OPFSDROP_))
if OPTW != setcost_ids(OPFSDROP_ + S147): fail('Option W cost %dk differs from the cost of the sets OPFSDROP plus OPFSSPIKE' % OPTW)
sp_ = sorted(t_ for t_ in size if node_of(t_) in OPFSDROP_ and node_of(t_) != '0.1.8c')
dr_ = re.search(r'Tasks (0\.1\.8[a-z]) and (0\.1\.8[a-z]) and every task of this story `Skipped\(opfs-spike\)` before W1', plan)
if not dr_ or sorted(dr_.groups()) != sp_: fail('the Story 1.4.7 drop rule names %s, the tasks of the spike node are %s' % (dr_ and dr_.groups(), sp_))
SHARE_TOK = 'Option W is %sM CU, %s%% of the total' % (M2(OPTW), PC(OPTW, tot2))
for nm_, where_ in (('requirements', blk(req, '## Size', '**Wall-clock blockers**')), ('requirements', blk(req, '**Cost and the keep or drop rule', '**Keep or drop rule**')), ('plan', blk(plan, '  - D-104 ', '\n  - D-1')), ('plan', blk(plan, '  - D-133 ', '\n  - D-134')), ('plan', blk(plan, '- [ ] Option W keep or drop', '\n- [x]'))):
    if SHARE_TOK not in where_: fail('%s: a place that states the keep or drop rule lacks the computed share "%s"' % (nm_, SHARE_TOK))
for nm_, tx_ in (('plan', plan), ('requirements', req)):
    for tok_ in ('1.53M', '2.68M', '1035k'):
        for mm_ in re.finditer(r'(?<![\d.])' + re.escape(tok_), tx_):
            ctx_ = tx_[max(0, mm_.start() - 120):mm_.end()]
            if 'iteration 16 priced it at' not in ctx_ and not re.search(r'\| 3\.4 [^|\n]*\|[^\n]*$', ctx_ + tx_[mm_.end():tx_.find('\n', mm_.end())]): fail('%s mentions the superseded figure %s outside its one quotation' % (nm_, tok_))
# (D) the wording nit: the node that 6.2.5b's reduced form omits is named identically
bp_ = next((t_ for t_ in sorted(size) if t_ in S147 and re.search(r'^##### Task %s: Web browser pass' % re.escape(t_), plan, re.M)), None)
ids_ = {}
for nm_, tx_ in (('plan', plan), ('requirements', req)):
    for mm_ in re.finditer(r'(?:omits|omit) (?:the )?web browser pass \(Task ([\d.a-z]+)\)|omits that pass|the web browser pass, Task ([\d.a-z]+),', tx_):
        ids_.setdefault(nm_, []).append(mm_.group(1) or mm_.group(2))
    if re.search(r'(?:omit|omits|omitted)[^.;]{0,30}web pass\b', tx_): fail('%s still says "web pass" for the omitted node (use "web browser pass (Task id)")' % nm_)
if not bp_ or any(x_ != bp_ for v_ in ids_.values() for x_ in v_ if x_) or not ids_.get('plan') or not ids_.get('requirements'): fail('the omitted node of the reduced form of 6.2.5b is named %s, the owner browser pass task is %s' % (ids_, bp_))
# (E1, E2) the wiring table (iteration 18): one row per seam implementation, checked against the task file lists, the parked sets and the audit code
wrows_ = _rows(_blockany('plan-wiring', plan), 9)
WCAT_ = 'kmp/src/businessTest/resources/wiring-catalog.txt'
WSRC_ = {'androidUnitTest': 'AT/', 'jvmTest': 'JT/', 'wasmJsTest': 'kmp/src/wasmJsTest/'}
WREG_ = {'androidUnitTest': 'kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/AllAndroidUnitTests.kt', 'jvmTest': 'kmp/src/jvmTest/kotlin/dev/stapler/stelekit/AllJvmTests.kt'}
fixture_sets_, bundled_ = None, None
for dp_, _, fs_ in os.walk(os.path.join(ROOTC, 'kmp', 'src')):
    for f_ in fs_:
        if f_ in ('WiredAppFixture.kt', 'DesktopWiredAppFixture.kt'):
            fixture_sets_ = (fixture_sets_ or set()) | {os.path.relpath(dp_, os.path.join(ROOTC, 'kmp', 'src')).split(os.sep)[0]}
bz_ = codefile('kmp/src/businessTest/kotlin/BUILD.bazel')
if bz_ is not None:
    mb_ = re.search(r'name = "wiring_kmp_sources_as_resources",\s*resources = \[(.*?)\]', bz_, re.S)
    bundled_ = set(re.findall(r'"//kmp/src/(\w+)/kotlin:', mb_.group(1))) if mb_ else None
if fixture_sets_ is None or bundled_ is None: fail('code: the WiredAppFixture classes or the wiring_kmp_sources_as_resources bundle could not be read, so the wiring rows cannot be checked')
ADDT19_ = {}
for mm_ in re.finditer(r'^##### Task (\d+\.\d+\.\d+[a-z]?):(.*)$', plan, re.M):
    ad_ = re.search(r'adds ((?:`\w+`(?:, | and )?)+) to `WiringAudit\.SEAMS`', mm_.group(2))
    for s_ in (re.findall(r'`(\w+)`', ad_.group(1)) if ad_ else []): ADDT19_[s_] = mm_.group(1)
seams_ = {}
for seam_, impl_, decl_, wt_, root_, ct_, label_, test_, ss_ in wrows_:
    seams_.setdefault(seam_, []).append(impl_)
    if seam_ == impl_: fail('wiring row %s names a class as its own seam: WiringCatalog rejects a seam that is not an interface on SEAMS and findImplementations skips a class whose own name is on it' % impl_)
    if decl_ not in size or wt_ not in size: fail('wiring row %s names an unknown task' % impl_); continue
    fl_ = FL.get(wt_, [])
    if root_ not in fl_: fail('wiring task %s of %s does not list its composition-root file' % (wt_, impl_))
    for need_ in (('BT/wiring/WiringAudit.kt', 'BT/wiring/WiringCompletenessTest.kt') if wt_ == ADDT19_.get(seam_) else ()):
        if need_ not in fl_: fail('wiring task %s of the interface %s does not list %s (the audit checks only interfaces on SEAMS and WiringCompletenessTest asserts the list is exactly its names)' % (wt_, seam_, need_))
    if ss_ not in WSRC_: fail('wiring row %s has the unknown test source set %s' % (impl_, ss_)); continue
    if label_ != '-':
        if ct_ not in size: fail('wiring row %s names the unknown catalog task %s' % (impl_, ct_)); continue
        cl_ = FL.get(ct_, [])
        if WCAT_ not in cl_: fail('catalog task %s of %s does not list wiring-catalog.txt' % (ct_, impl_))
        if not any(p_.startswith(WSRC_[ss_]) and p_.endswith('/' + test_ + '.kt') for p_ in cl_): fail('catalog task %s does not list the entry-point test %s under %s' % (ct_, test_, WSRC_[ss_]))
        if ss_ not in (fixture_sets_ or set()): fail('row %s: a catalog line needs a WiredAppFixture call and the fixtures exist only in %s, not %s' % (impl_, sorted(fixture_sets_ or []), ss_))
        if ss_ not in (bundled_ or set()): fail('row %s: the test source set %s is not bundled into wiring_kmp_sources_as_resources %s, so WiringCatalog could not read the test' % (impl_, ss_, sorted(bundled_ or [])))
        if ss_ in WREG_ and WREG_[ss_] not in cl_: fail('catalog task %s does not list the registration file %s of the new %s test' % (ct_, WREG_[ss_], ss_))
        if node_of(wt_) != node_of(ct_) and node_of(wt_) not in _reach([node_of(ct_)]): fail('catalog task %s has no path to the wiring task %s' % (ct_, wt_))
    else:
        if ct_ != '-': fail('row %s has no catalog label but names the catalog task %s' % (impl_, ct_))
        if ss_ in (fixture_sets_ or set()) and ss_ in (bundled_ or set()): fail('row %s has no catalog line although %s has a fixture and is bundled: add the line' % (impl_, ss_))
        if not any(p_.startswith(WSRC_[ss_]) and p_.endswith('/' + test_ + '.kt') for fl2_ in FL.values() for p_ in fl2_): fail('no task lists the entry-point test %s of %s' % (test_, impl_))
    nd_, nw_ = node_of(decl_), node_of(wt_)
    if nd_ != nw_ and nd_ not in _reach([nw_]): fail('wiring task %s has no path to the task %s that declares %s' % (wt_, decl_, impl_))
    if (wt_ in S147) != (decl_ in S147): fail('wiring of %s is %s but its declaring task is %s (a non-parked task must not wire a parked class)' % (impl_, 'parked' if wt_ in S147 else 'not parked', 'parked' if decl_ in S147 else 'not parked'))
    if not any('`%s`' % impl_ in h_ for h_ in re.findall(r'^##### Task (?:%s|%s):(.*)$' % (re.escape(decl_), re.escape(wt_)), plan, re.M)): fail('neither heading of Tasks %s and %s names the implementation %s' % (decl_, wt_, impl_))
lbl_ = [r_[6] for r_ in wrows_ if r_[6] != '-']
if len(set(lbl_)) != len(lbl_): fail('wiring catalog effect labels are not unique')
s621_ = blk(plan, '#### Story 6.2.1:', '**Files**')
for seam_ in seams_:
    if '`%s`' % seam_ not in s621_: fail('Story 6.2.1 does not list the seam %s of the plan-wiring table' % seam_)
if '`OpfsCapability`' in s621_ or any('OpfsCapability' in (r_[0], r_[1]) for r_ in wrows_): fail('OpfsCapability is a class, not an interface on SEAMS: it has no wiring row and Story 6.2.1 does not list it as a seam')
named_ = set()
for hd_ in re.findall(r'^##### Task (?:1\.4\.3[k-z]|1\.4\.7[a-m]):(.*)$', plan, re.M):
    named_ |= set(re.findall(r'`((?:AlwaysHeld|Unsupported|Wasm|FileSystem)\w*(?:Lease|Persistence|Durability|Lifecycle))`', hd_))
if named_ - set(r_[1] for r_ in wrows_): fail('seam implementations named in task headings but missing from the plan-wiring table: %s' % sorted(named_ - set(r_[1] for r_ in wrows_)))
# the task that adds an interface to SEAMS names exactly the interfaces whose first wiring row it owns
first_wt_ = {}
for seam_, impl_, decl_, wt_, *_r in wrows_:
    if seam_ not in first_wt_ or W.get(node_of(wt_), 0) < W.get(node_of(first_wt_[seam_]), 0): first_wt_[seam_] = wt_
for wt_ in sorted(set(first_wt_.values())):
    hd_ = re.search(r'^##### Task %s:(.*)$' % re.escape(wt_), plan, re.M).group(1)
    ad_ = re.search(r'adds ((?:`\w+`(?:, | and )?)+) to `WiringAudit\.SEAMS`', hd_)
    got_ = set(re.findall(r'`(\w+)`', ad_.group(1))) if ad_ else set()
    want_ = {s_ for s_, w_ in first_wt_.items() if w_ == wt_}
    if got_ != want_: fail('the heading of Task %s adds %s to WiringAudit.SEAMS but the plan-wiring table makes it the adding task of %s' % (wt_, sorted(got_), sorted(want_)))
wa_ = codefile('kmp/src/businessTest/kotlin/dev/stapler/stelekit/wiring/WiringAudit.kt')
wct_ = codefile('kmp/src/businessTest/kotlin/dev/stapler/stelekit/wiring/WiringCompletenessTest.kt')
if wa_ is not None:
    sm_ = re.search(r'val SEAMS = listOf\((.*?)\)', wa_, re.S)
    cur_ = re.findall(r'"(\w+)"', sm_.group(1)) if sm_ else None
    if cur_ is None or len(cur_) < 5: fail('code: WiringAudit.SEAMS is no longer a literal list, the wiring rows are stale')
    elif [s_ for s_ in seams_ if s_ in cur_]: fail('code: %s already on WiringAudit.SEAMS, so the task that adds it is stale' % [s_ for s_ in seams_ if s_ in cur_])
    if wct_ is not None:
        asl_ = re.search(r'fun seamList_should_EqualGlossaryList_When_Parsed\(\) \{\s*assertEquals\(\s*listOf\((.*?)\),\s*WiringAudit\.SEAMS', wct_, re.S)
        if not asl_ or re.findall(r'"(\w+)"', asl_.group(1)) != cur_: fail('code: WiringCompletenessTest.seamList no longer asserts exactly WiringAudit.SEAMS, so the tasks that add seams must edit it (or this check is stale)')
# (E3) the DomainError subtypes against the code
dek_ = codefile('kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt')
if dek_ is not None:
    def domain_when_blocks(tx_):
        n_ = 0
        for mm_ in re.finditer(r'\bwhen\s*(?:\([^)]*\))?\s*\{', tx_):
            i_ = mm_.end(); depth_ = 1; j_ = i_
            while j_ < len(tx_) and depth_:
                depth_ += (tx_[j_] == '{') - (tx_[j_] == '}'); j_ += 1
            top_ = []; d_ = 0
            for ln_ in tx_[i_:j_ - 1].split('\n'):
                if d_ == 0: top_.append(ln_)
                d_ += ln_.count('{') - ln_.count('}')
            tt_ = '\n'.join(top_)
            cats_ = set(re.findall(r'DomainError\.([A-Z]\w*)\.[A-Z]\w*[^\n]{0,80}?->', tt_))
            if len(cats_) >= 5 and not re.search(r'^\s*else\s*->', tt_, re.M): n_ += 1
        return n_
    _arm = lambda c_, k_: '\n'.join('is DomainError.%s.K%d -> 1' % (c_, i_) for i_ in range(k_))
    _syn = 'val x = when (e) {\n' + '\n'.join(_arm(c_, 1) for c_ in ('DatabaseError', 'FileSystemError', 'ParseError', 'NetworkError', 'GitError')) + '\n}\n'
    if domain_when_blocks(_syn) != 1 or domain_when_blocks(_syn.replace('}\n', 'else -> 0\n}\n')) != 0 or domain_when_blocks('val x = when (e) {\n' + _arm('GitError', 12) + '\n}\n') != 0: fail('exhaustive-when self-test: a synthetic exhaustive when over five categories must count, one with else or one over a single category must not')
    ex_files_ = []
    for dp_, _, fs_ in os.walk(os.path.join(ROOTC, 'kmp', 'src')):
        for f_ in fs_:
            if f_.endswith('.kt'):
                nb_ = domain_when_blocks(open(os.path.join(dp_, f_), encoding='utf-8').read())
                ex_files_ += [f_] * nb_
    exn_ = re.search(r'exactly (one|two|three|four) exhaustive `when`s? over `DomainError`', plan)
    if not exn_ or {'one': 1, 'two': 2, 'three': 3, 'four': 4}[exn_.group(1)] != len(ex_files_): fail('the plan says %s exhaustive whens over DomainError, the grep of every source set of kmp/src finds %d: %s' % (exn_ and exn_.group(1), len(ex_files_), sorted(ex_files_)))
    l13_ = {os.path.basename(p_) for p_ in FL.get('1.4.3l', [])}
    if set(ex_files_) - l13_: fail('files holding an exhaustive when over DomainError %s are not in the file list of Task 1.4.3l (%s)' % (sorted(set(ex_files_) - l13_), sorted(l13_)))
    sub_ = re.search(r'`DomainError\.FileSystemError\.(\w+)\(path\)` and `DomainError\.FileSystemError\.(\w+)\(path\)`', plan)
    for nm2_ in (sub_.groups() if sub_ else ('?',)):
        if re.search(r'class %s\b' % nm2_, dek_) or nm2_ == '?': fail('the subtype %s is missing from the plan or already declared in DomainError.kt' % nm2_)
    if 'sealed interface FileSystemError' not in dek_ or 'LfsQuotaExceeded' not in dek_: fail('code: FileSystemError or the existing GitError.LfsQuotaExceeded (the only quota error today) changed')
    if 'CM/error/DomainError.kt' not in FL.get('1.4.3l', []) or not any(p_.endswith('DomainErrorTest.kt') for p_ in FL.get('1.4.3l', [])): fail('Task 1.4.3l must list DomainError.kt and DomainErrorTest.kt')
# (E4) shared types outside the parked set: symbols, consumers, introducers
SYM_ = _rows(_blockany('plan-symbols', plan), 3)
for sym_, decl_, cons_ in SYM_:
    if decl_ not in size: fail('plan-symbols: %s declared by unknown task %s' % (sym_, decl_)); continue
    if not re.search(r'`[^`]*\b%s\b[^`]*`' % re.escape(sym_), re.search(r'^##### Task %s:(.*)$' % re.escape(decl_), plan, re.M).group(1)): fail('the heading of Task %s does not name the symbol %s it declares' % (decl_, sym_))
    nd_ = node_of(decl_)
    users_ = [t_ for t_ in size if t_ != decl_ and re.search(r'`[^`]*\b%s\b[^`]*`' % re.escape(sym_), re.search(r'^##### Task %s:(.*)$' % re.escape(t_), plan, re.M).group(1))]
    for u_ in set(users_) | set(cons_.split() if cons_ != '-' else []):
        nu_ = node_of(u_)
        if u_ in (cons_.split() if cons_ != '-' else []) and u_ not in users_: fail('Task %s is listed as a consumer of %s but its heading does not name it' % (u_, sym_))
        if nu_ != nd_ and nd_ not in _reach([nu_]): fail('Task %s uses %s (declared by Task %s) but node %s has no path to node %s in the edge block' % (u_, sym_, decl_, nu_, nd_))
        if decl_ in S147 and u_ not in S147 and u_ not in RED: fail('non-parked Task %s uses %s, declared by the parked Task %s (D-127: it would not compile when Story 1.4.7 is skipped)' % (u_, sym_, decl_))
SHORT_ = {}
for mm_ in re.finditer(r'^- `(\w+)` = `([^`]+)`', plan.split('**Path shorthand**')[1].split('**Standing definition')[0], re.M): SHORT_[mm_.group(1)] = mm_.group(2)
def real_(p_):
    h_ = p_.split('/')[0]
    return real_(SHORT_[h_] + p_[len(h_):]) if h_ in SHORT_ else p_
def intro_problems(FLx, NEWMx):
    out_ = []
    for p_ in sorted({p_ for t_ in FLx for p_ in FLx[t_]}):
        ls_ = sorted(((W.get(node_of(t_), 0), t_) for t_ in FLx if p_ in FLx[t_]))
        marked_ = sorted(t_ for t_ in NEWMx if p_ in NEWMx[t_])
        if len(marked_) > 1: out_.append('%s is marked (new) by more than one task: %s' % (p_, marked_)); continue
        pk_ = [t_ for _, t_ in ls_ if t_ in S147]; np_ = [t_ for _, t_ in ls_ if t_ not in S147 and t_ not in OPFSDROP_ and t_ not in RED]
        if marked_:
            i_ = marked_[0]
            if i_ in S147 and np_: out_.append('non-parked Tasks %s list %s, which the parked Task %s introduces (D-127)' % (np_, p_, i_))
        elif pk_ and np_ and not os.path.exists(os.path.join(ROOTC, real_(p_))):
            out_.append('%s does not exist yet and is listed by the parked Tasks %s and the non-parked Tasks %s: the task that creates it must carry the (new) mark in plan-files (the lowest wave is not an introducer)' % (p_, pk_, np_))
    return out_
for p_ in intro_problems(FL, NEWM): fail(p_)
_FLx = {t_: list(v_) for t_, v_ in FL.items()}; _FLx['1.4.3d'] = _FLx['1.4.3d'] + ['CM/platform/OpfsStorageVerdict.kt']
if not intro_problems(_FLx, NEWM): fail('introducer self-test: a non-parked task listing a parked, unmarked, not yet existing path was not flagged')
_NM = {t_: set(v_) for t_, v_ in NEWM.items()}; _NM.setdefault('1.4.7a', set()).add('CM/platform/OpfsStorageVerdict.kt')
if not intro_problems(_FLx, _NM): fail('introducer self-test: a non-parked task listing a path that a parked task marks (new) was not flagged')
ORDCL_ = {}
def _ordreach(a_):
    seen_ = set(); st_ = [a_]
    while st_:
        x_ = st_.pop()
        for y_ in ORD16.get(x_, ()):
            if y_ not in seen_: seen_.add(y_); st_.append(y_)
    return seen_
for p_ in sorted({p_ for t_ in NEWM for p_ in NEWM[t_]}):
    i_ = next(t_ for t_ in NEWM if p_ in NEWM[t_]); ni_ = node_of(i_)
    for t_ in sorted(t2_ for t2_ in FL if p_ in FL[t2_] and t2_ != i_):
        nt_ = node_of(t_)
        if ni_ == nt_:
            if t_ not in _ordreach(i_): fail('Task %s lists %s, which Task %s creates in the same node, but no "after" clause of the headings orders them' % (t_, p_, i_))
        else:
            if ni_ not in _reach([nt_]): fail('Task %s lists %s, which Task %s creates, but node %s has no path to node %s' % (t_, p_, i_, nt_, ni_))
            hd_ = re.search(r'^##### Task %s:(.*)$' % re.escape(t_), plan, re.M).group(1)
            aft_ = {x_ for kw_, xs_ in _seg_ids(hd_) if kw_ == 'after' for x_ in xs_}
            if not aft_ & {i_, ni_, re.match(r'\d+\.\d+\.\d+', i_).group(0)}: fail('Task %s lists %s, which Task %s creates in another node, but its heading has no "after" clause naming that task or story (found %s)' % (t_, p_, i_, sorted(aft_)))
sh_ = sorted(PK.get('OPFSSHARED', []))
if sh_ != sorted(t_ for t_ in size if re.fullmatch(r'1\.4\.3[k-z]', t_)) or any(t_ in S147 or t_ in OPFSDROP_ for t_ in sh_): fail('OPFSSHARED %s is not exactly the non-parked storage-port tasks 1.4.3k onward %s' % (sh_, sorted(t_ for t_ in size if re.fullmatch(r'1\.4\.3[k-z]', t_))))
for t_ in sh_:
    if t_ not in size or node_of(t_) is None: fail('OPFSSHARED names %s, which is not a task with a node' % t_); continue
    bad_ = (closure([node_of(t_)]) - {node_of(t_)}) & (set(S147) | set(OPFSDROP_))
    if bad_: fail('shared task %s depends on parked nodes %s' % (t_, sorted(bad_)))
for n_, d_ in E.items():
    if n_ in S147 or n_ in OPFSDROP_ or n_ in RED: continue
    if set(d_) & set(S147): fail('non-parked node %s has an edge into Story 1.4.7 (%s)' % (n_, sorted(set(d_) & set(S147))))
for t_ in ('1.4.3d', '1.4.3h', '1.4.6a', '1.4.6h'):
    if not any(('1.4.3k' in E.get(node_of(t_), []) or '1.4.3m' in E.get(node_of(t_), []) or n2_ in ('1.4.3k', '1.4.3m')) for n2_ in _reach([node_of(t_)])): fail('Task %s consumes the storage types but its node has no path to Tasks 1.4.3k or 1.4.3m' % t_)
# (E5) the preload bound
lim_ = plan.split('**Canonical `SceneLimits` (')[1].split('\n')[0]
mpb_ = re.search(r'`maxPreloadBytes=(\d+) MB`', lim_)
mb_ = int(mpb_.group(1)) if mpb_ else None
mbb_ = re.search(r'`SceneLimits\.maxPreloadBytes` \((\d+) MB = ([\d,]+) bytes', plan)
d131_ = re.search(r'`SceneLimits.maxPreloadBytes` = (\d+) MB \(([\d,]+) bytes\)', plan)
if not mb_ or not mbb_ or not d131_ or not (mb_ == int(mbb_.group(1)) == int(d131_.group(1)) and mb_ * 1048576 == int(mbb_.group(2).replace(',', '')) == int(d131_.group(2).replace(',', ''))): fail('maxPreloadBytes is not the same MB and byte figure in SceneLimits (%s), Story 1.4.7 (%s) and D-131 (%s)' % (mb_, mbb_ and mbb_.groups(), d131_ and d131_.groups()))
wr_ = dict(re.findall(r'^\| `([\w:]+)` \| [^|]+ \| "([^"]+)" \|$', blk(ux, '**Web storage reasons', '**While the storage is being checked'), re.M))
mtx_ = re.search(r'over (\d+) MB', wr_.get('PreloadTooLarge', ''))
if not mtx_ or int(mtx_.group(1)) != mb_: fail('the PreloadTooLarge text says %s MB, maxPreloadBytes is %s MB' % (mtx_ and mtx_.group(1), mb_))
nre_ = re.search(r'the (\w+) reasons of the web reason table', plan)
WORDS_ = {'seven': 7, 'six': 6, 'eight': 8}
if not nre_ or WORDS_.get(nre_.group(1)) != len(set(k_.split(':')[0] for k_ in wr_)): fail('Task 1.4.3k says %s reasons, the web reason table has %d' % (nre_ and nre_.group(1), len(set(k_.split(':')[0] for k_ in wr_))))
tun_ = re.search(r'within (\d+) s `\[tunable\]` after the (\d+) s `CheckTimedOut`', plan)
chk_ = re.search(r'at most (\d+) s', blk(plan, '| `V2StorageStatus` |', '\n| `OpfsWriteQueue`'))
if not tun_ or not chk_ or int(tun_.group(2)) != int(chk_.group(1)) or int(tun_.group(1)) <= 0: fail('the recovery window %s or the check timeout %s is inconsistent with the glossary timeout %s' % (tun_ and tun_.group(1), tun_ and tun_.group(2), chk_ and chk_.group(1)))
# (F) UX: save variants, pending contract, storage row, transient action, private window, still saving
VARS_ = _rows(_blockany('save-variants', ux), 7)
for base_, vid_, summ_, short_, prim_, more_, nm3_ in VARS_ if CV is not None else []:
    b_ = next((b2_ for b2_ in CTB['banner'] if b2_['id'] == base_), None)
    if b_ is None or b_['tier'] != 'Blocking' or b_['rank'] != 1: fail('save variant %s: its base %s is not the rank-1 Blocking banner' % (vid_, base_)); continue
    if len(summ_) > CV['cap'][(320, 1.0)] or (CV['wrap'](summ_, CV['per'][(320, 1.0, True)]) or 99) > CTB['params']['bannerLines']: fail('save variant %s summary "%s" does not fit the banner row at 320 dp and font scale 1.0' % (vid_, summ_))
    if (CV['wrap'](short_, CV['per'][(320, 2.0, True)]) or 99) > CTB['params']['bannerLines']: fail('save variant %s short title "%s" does not fit at font scale 2.0' % (vid_, short_))
    if len(prim_) > CV['label'][0] or any(len(w_) > CV['label'][1] for w_ in prim_.split()) or len(prim_.split()) > 2: fail('save variant %s primary label "%s" does not fit the button' % (vid_, prim_))
    allnames_ = [r_['name'] for r_ in CTB['banner'] + CTB['toast']]
    if prim_.lower() not in nm3_.lower() or len(nm3_.split()) <= len(prim_.split()) or nm3_ in allnames_: fail('save variant %s accessible name "%s" is not a unique name containing the label and adding an object' % (vid_, nm3_))
    mo_ = [x_.strip() for x_ in more_.split(';')]
    if 'Dismiss' in mo_ or 'Retry' not in mo_ or prim_ == b_['primary'] or prim_ in mo_: fail('save variant %s: Retry must stay under More, the primary must differ from %s and nothing is dismissible' % (vid_, base_))
if CV is not None and not VARS_: fail('ux.md 14.8 has no save-variants block')
PEND_ = _rows(_blockany('pending-contract', ux), 5)
if [r_[0] for r_ in PEND_] != ['arrive', 'still-checking', 'resolved-editor', 'resolved-no-editor', 'timeout', 'close']: fail('the pending contract events are %s' % [r_[0] for r_ in PEND_])
else:
    pr_ = {r_[0]: r_ for r_ in PEND_}
    if not (pr_['timeout'][1] == chk_.group(1) and pr_['arrive'][1] == '0' and 0 < int(pr_['still-checking'][1]) < int(pr_['timeout'][1])): fail('pending contract seconds %s do not fit the %s s status timeout' % ([r_[1] for r_ in PEND_], chk_ and chk_.group(1)))
    if any(r_[3] in ('-', '') for r_ in PEND_): fail('a pending contract row has no focus target')
    if [bool(re.search('polite', r_[4])) for r_ in PEND_] != [False, True, True, False, False, False]: fail('exactly the rows still-checking and resolved-editor announce a polite status: %s' % [r_[4] for r_ in PEND_])
    mps_ = re.search(r'a polite message at (\d+) s', plan)
    if not mps_ or mps_.group(1) != pr_['still-checking'][1]: fail('the plan says the polite message is at %s s, the table says %s s' % (mps_ and mps_.group(1), pr_['still-checking'][1]))
ROWS_ = _rows(_blockany('storage-row', ux), 5)
pst_ = re.search(r'`PersistenceState` \(((?:`\w+`(?:, )?)+)\)', plan)
pset_ = set(re.findall(r'`(\w+)`', pst_.group(1))) if pst_ else set()
if {r_[1] for r_ in ROWS_} != pset_ or not pset_: fail('the Browser storage row states cover %s, PersistenceState is %s' % (sorted({r_[1] for r_ in ROWS_}), sorted(pset_)))
for st_, ps_, lb_, line_, act_ in ROWS_:
    if st_ in ('Asking', 'Refused', 'Unsupported') and line_ == '-': fail('storage row state %s has no inline status line (a refused click would be silent)' % st_)
    if st_ == 'Refused' and act_ == '-': fail('the Refused state must offer the action again')
    if st_ == 'Kept' and (act_ != '-' or lb_ != 'Kept'): fail('the Kept state offers an action or another label')
t147b = blk(ux, '### 14.7 The no-editor screen', '### 14.8')
mtr_ = re.search(r'^\| Storage unavailable, transient \(((?:`\w+`(?:, )?)+)\) \| ([^|]+) \| ([^\n]+) \|$', t147b, re.M)
mtp_ = re.search(r', transient: ((?:\[[^\]]+\](?:, )?)+) \(UX-147', plan)
if not mtr_ or not mtp_ or [x_.strip() for x_ in re.findall(r'\[([^\]]+)\]', mtr_.group(3))] != re.findall(r'\[([^\]]+)\]', mtp_.group(1)) or re.findall(r'\[([^\]]+)\]', mtr_.group(3))[0] != 'Reload': fail('the transient no-editor actions of ux.md 14.7 and the plan differ or do not start with [Reload]')
else:
    trans_ = set(re.findall(r'`(\w+)`', mtr_.group(1)))
    if not trans_ <= set(k_.split(':')[0] for k_ in wr_) or 'Reload' in rows147.get('Storage unavailable', []) or 'Reload' in rows147.get('Newer schema', []): fail('[Reload] must be offered only for the transient reasons %s' % sorted(trans_))
qb_ = [re.findall(r'EPHEMERAL_QUOTA_BYTES` = ([\d,]+)(?: \((\d+) MiB\))?', tx_) for tx_ in (plan, ux)]
if not all(qb_) or len({x_[0] for l_ in qb_ for x_ in l_}) != 1 or any(int(x_[0].replace(',', '')) != int(x_[1] or 256) * 1048576 for l_ in qb_ for x_ in l_ if x_[1]): fail('EPHEMERAL_QUOTA_BYTES differs between plan and ux.md or is not 256 MiB: %s' % qb_)
ss_ = [re.search(p_, tx_) for p_, tx_ in ((r'after (\d+) s it shows the visible `StillSaving` toast "([^"]+)"', plan), (r'after (\d+) s of waiting the `StillSaving` toast .{0,90}reads "([^"]+)"', ux), (r'has not reached `Persisted` after (\d+) s shows|not reached `Persisted` after (\d+) s', ux))]
ts_ = {t_['id']: t_ for t_ in (CTB['toast'] if CTB else [])}
if None in ss_ or 'StillSaving' not in ts_ or len({m_.group(1) or m_.group(2) for m_ in ss_ if m_}) != 1 or ss_[0].group(2) != ts_['StillSaving']['summary'] or ss_[1].group(2) != ts_['StillSaving']['summary'] or ts_['StillSaving']['primary'] != '-': fail('the StillSaving toast: delay %s, texts %s, table row %s' % ([m_ and m_.groups() for m_ in ss_], None, ts_.get('StillSaving')))
mw_ = re.search(r'wait over (\d+) s|3 s', ux)
for u_ in ('UX-144', 'UX-145', 'UX-146', 'UX-147', 'UX-148'):
    if u_ not in uxids: fail('ux.md section 20 lacks %s' % u_)
# (G) nits: the probe bound, the sweep, the tests of the new tasks appear in validation.md
mpr_ = re.search(r'`stelekit-probe-<uuidv7>` directly under the OPFS root .{0,150}writes at most (\d+) files of at most (\d+) bytes each', plan)
mpr2_ = re.search(r'asserts the directory name, the (\d+) x (\d+) byte bound', plan)
if not mpr_ or not mpr2_ or mpr_.groups() != mpr2_.groups() or int(mpr_.group(1)) * int(mpr_.group(2)) > 1024: fail('the startup probe bound differs between its two statements or exceeds 1 KiB: %s %s' % (mpr_ and mpr_.groups(), mpr2_ and mpr2_.groups()))
if size.get('1.4.7j') != 'M': fail('Task 1.4.7j (preload state, status wiring, the cap, the sweep) is an M task')
if re.search(r'sweeps?[^.\n]{0,70}under the writer lease', plan.replace('never under the writer lease', '')): fail('a retired claim remains: the startup sweep runs under the writer lease (it runs under its own short-lived lock)')
newt_ = sorted(set(sh_) | set(S147) | {'1.4.6j'})
for t_ in newt_:
    for p_ in FL.get(t_, []):
        if p_.endswith('Test.kt') and os.path.basename(p_)[:-3] not in VP: fail('validation.md has no row naming the test class %s of Task %s' % (os.path.basename(p_)[:-3], t_))
print('iteration 17: kmp present and %d code files read, ~%sM CU (%sM x1.75), history to iteration %d, keep or drop rule threshold %s (%s), Option W %sM CU = %s%% of the total, shared ports %d tasks %dk raw, wiring rows %d for seams %s, symbols %d, introducer check over %d lists, SceneLimits 32 fields maxPreloadBytes %s MB, pending rows %d, storage-row states %s, save variants %d, UX-144..148' % (len(REQCODE), MV(tot2), M2(tot2), ITER17, T_, 'web-intent exception', M2(OPTW), PC(OPTW, tot2), len(sh_), OPTW_SH, len(wrows_), sorted(seams_), len(SYM_), len(FL), mb_, len(PEND_), [r_[0] for r_ in ROWS_], len(VARS_)))

# ================= iteration 18: the Option W keep or drop rule, the web baseline, wiring, the store scope, the live preload set and the web UX tables. Every check parses or computes a value.
# (P3) the verified baseline of classic sketches: each anchor is a regex over the code, the cited line must be among its matches and the document must cite it
BASE_ = [
    ('kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/RepositoryFactory.kt', r'sqlDelightSketchRepositories\(database\)', 'RepositoryFactory.kt:453'),
    ('kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt', r'driverFactory\.createDriverAsync\(graphId\)', 'Main.kt:267'),
    ('kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt', r'if \(driver\.actualBackend == "memory"\)', 'Main.kt:268'),
    ('kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/db/DriverFactory.js.kt', r'suspend fun createEphemeralDriverAsync', 'DriverFactory.js.kt:64'),
    ('kmp/src/iosMain/kotlin/dev/stapler/stelekit/db/DriverFactory.ios.kt', r'NativeSqliteDriver\(SteleDatabase\.Schema, dbName\)', 'DriverFactory.ios.kt:14'),
    ('kmp/src/commonMain/kotlin/dev/stapler/stelekit/projectbridge/ProjectSidecarStore.kt', r'if \(unavailable\(\)\) return SidecarRead\.Unavailable', 'ProjectSidecarStore.kt:74'),
    ('kmp/src/commonMain/kotlin/dev/stapler/stelekit/projectbridge/SidecarReconciler.kt', r'SidecarRead\.Unavailable -> return ReconcileOutcome\.DeviceLocal', 'SidecarReconciler.kt:121'),
    ('kmp/src/commonMain/kotlin/dev/stapler/stelekit/projectbridge/SidecarReconciler.kt', r'SidecarWriteOutcome\.Unavailable -> ReconcileOutcome\.DeviceLocal', ':145'),
    ('kmp/src/commonMain/kotlin/dev/stapler/stelekit/sketch/export/SketchExporter.kt', r'raise\(DomainError\.FileSystemError\.AtomicWriteUnsupported\(dir\)\)', 'SketchExporter.kt:33'),
    ('kmp/src/commonMain/kotlin/dev/stapler/stelekit/sketch/export/SketchExporter.kt', r'raise\(DomainError\.FileSystemError\.AtomicWriteUnsupported\(dir\)\)', ':52'),
    ('kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/db/DriverFactory.js.kt', r'WebLock\.tryAcquireLeader\("stelekit-sqlite-driver-', 'DriverFactory.js.kt:38'),
]
nbase_ = 0
for f_, rx_, cite_ in BASE_:
    tx_ = codefile(f_)
    if tx_ is None: fail('baseline anchor file %s is missing' % f_); continue
    lines_ = [i_ + 1 for i_, l_ in enumerate(tx_.split('\n')) if re.search(rx_, l_)]
    ln_ = int(cite_.split(':')[1])
    if ln_ not in lines_: fail('baseline anchor %s: the pattern %s matches lines %s, not %d' % (f_.split('/')[-1], rx_, lines_, ln_)); continue
    if not any(cite_ in tx2_ for tx2_ in (req, plan)): fail('no document cites the baseline anchor %s' % cite_)
    nbase_ += 1
pfs_ = codefile('kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt'); ifs_ = codefile('kmp/src/iosMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt')
if pfs_ is not None and ifs_ is not None and not (re.search(r'supportsTempRenameWrites: Boolean get\(\) = false', pfs_) and re.search(r'supportsTempRenameWrites: Boolean get\(\) = false', ifs_)): fail('code: wasm or iOS supportsTempRenameWrites is no longer false, so the "file side unavailable" baseline of D-140 is stale')
bl_ = blk(req, '**Web baseline for classic sketches', '**Option W keep or drop rule')
if 'DeviceLocal' not in bl_ or 'AtomicWriteUnsupported' not in bl_ or 'D-140' not in bl_: fail('the web baseline bullet no longer names the DeviceLocal outcome, AtomicWriteUnsupported and D-140')
# the total web footprint: stated once, computed, repeated nowhere with another value
FPRAW_ = OPTW + OPTW_SH
fps_ = 'total web footprint (stated once, here)**: Option W %sM CU plus the shared ports %sM CU is %sM CU (%dk raw tokens, %s%% of the total)' % (M2(OPTW), M2(OPTW_SH), M2(FPRAW_), FPRAW_, PC(FPRAW_, tot2))
if fps_ not in req: fail('requirements lacks the computed footprint sentence: %s' % fps_)
for nm_, tx_ in ALLDOCS:
    for mm_ in re.finditer(r'total web footprint (?:is|\(stated once, here\)\*\*: Option W [\d.]+M CU plus the shared ports [\d.]+M CU is) ([\d.]+)M CU', tx_):
        if mm_.group(1) != M2(FPRAW_): fail('%s quotes the total web footprint %sM CU, computed %sM CU' % (nm_, mm_.group(1), M2(FPRAW_)))
d133_ = blk(plan, '  - D-133 ', '\n  - D-134')
if '%sM CU stay built' % M2(OPTW_SH) not in d133_ or 'total web footprint is %sM CU' % M2(FPRAW_) not in d133_: fail('D-133 does not repeat the computed shared-port cost %sM CU and the footprint %sM CU' % (M2(OPTW_SH), M2(FPRAW_)))
# the solver bars: hard and advisory stated once, the plan equals the hard rows
SB_ = _rows(_blockany('solver-bars', req), 4)
hard_ = {r_[1]: int(r_[3].split()[0]) for r_ in SB_ if r_[0].startswith('solve time') and r_[2] == 'hard'}
adv_ = {r_[1]: int(r_[3].split()[0]) for r_ in SB_ if r_[0].startswith('solve time') and r_[2] == 'advisory'}
m708_ = re.search(r'100-constraint solve under (\d+) ms on JVM and under (\d+) ms on wasmJs', plan)
if sorted(hard_) != ['JVM', 'wasmJs'] or sorted(adv_) != ['Pixel_Fold', 'wasmJs'] or not m708_ or (int(m708_.group(1)), int(m708_.group(2))) != (hard_.get('JVM'), hard_.get('wasmJs')) or adv_.get('wasmJs', 999) >= hard_.get('wasmJs', 0): fail('the solver-bars block (hard %s, advisory %s) and the plan thresholds %s disagree or an advisory figure is not below its hard bar' % (hard_, adv_, m708_ and m708_.groups()))
sol_ = blk(req, '- **Solver (Spike 0.1.2)', '<!-- solver-bars:start -->')
if 'Pixel_Fold' in sol_ or re.search(r'\b\d+ ms\b', sol_): fail('the solver bullet restates a bar or the advisory Pixel_Fold figure outside the solver-bars block')
# (E4) the store scope against the interface, the construction sites against the file list of Task 1.4.3o
SS_ = _rows(_blockany('plan-store-scope', plan), 4)
psk2_ = codefile('kmp/src/commonMain/kotlin/dev/stapler/stelekit/projectbridge/ProjectSidecarStore.kt')
if psk2_ is not None:
    im_ = re.search(r'interface ProjectSidecarStore \{(.*?)\n\}', psk2_, re.S)
    meths_ = re.findall(r'suspend fun (\w+)\([^)]*\)\s*(?::\s*([A-Za-z]+))?', im_.group(1)) if im_ else []
    mut_ = {n_: r_ for n_, r_ in meths_ if n_.startswith(('write', 'flush'))}
    tab_ = {r_[0]: r_[1] for r_ in SS_ if r_[3] == 'code'}
    if not mut_ or mut_ != tab_: fail('plan-store-scope lists %s as in the code but the ProjectSidecarStore interface has the mutating methods %s' % (tab_, mut_))
    for m_, ret_, beh_, src_ in SS_:
        if src_ == 'code':
            if not {'Either': beh_.startswith('Left(WriterLeaseNotHeld'), 'SidecarWriteOutcome': beh_.startswith('Failed(WriterLeaseNotHeld'), 'Int': beh_.startswith('re-check')}.get(ret_): fail('plan-store-scope: %s returns %s but its lease behaviour is "%s"' % (m_, ret_, beh_))
        else:
            if src_ not in size or m_ not in plan.split('<!-- plan-store-scope:start -->')[0] or node_of(src_) not in _reach(['1.4.3']) | {'1.4.3'}: fail('plan-store-scope: the planned method %s is not named by the plan or its Task %s is not before Story 1.4.3' % (m_, src_))
            if src_ != 'code' and ret_ == 'SidecarWriteOutcome' and not beh_.startswith('Failed(WriterLeaseNotHeld'): fail('plan-store-scope: %s returns an outcome but its lease behaviour is "%s"' % (m_, beh_))
    sites_ = sorted({f_ for dp_, _, fs_ in os.walk(os.path.join(ROOTC, 'kmp', 'src')) for f_ in fs_ if f_.endswith('.kt') and f_ != 'ProjectSidecarStore.kt' and re.search(r'\bFileProjectSidecarStore\(', open(os.path.join(dp_, f_), encoding='utf-8').read())})
    l14_ = {os.path.basename(p_) for p_ in FL.get('1.4.3o', [])}
    if not sites_ or set(sites_) - l14_: fail('construction sites of FileProjectSidecarStore %s are not all in the file list of Task 1.4.3o %s' % (sites_, sorted(l14_)))
# (E5) the live set of the preload: classification recomputed over a synthetic tree
PL_ = _rows(_blockany('plan-preload-live', plan), 2)
pmk_ = codefile('kmp/src/commonMain/kotlin/dev/stapler/stelekit/projectbridge/ProjectModelJson.kt')
if pmk_ is not None:
    mp2_ = re.search(r'const val MODEL_PATH = "([^"]+)"', pmk_); sd2_ = re.search(r'const val SHEETS_DIR = "([^"]+)"', pmk_)
    live_ = {r_[0] for r_ in PL_ if r_[1] == 'live'}
    if not mp2_ or not sd2_ or live_ != {'<slug>/%s' % mp2_.group(1), '<slug>/%s/*.json' % sd2_.group(1)}: fail('the live set %s is not the model file and the sheets of the code (%s, %s)' % (sorted(live_), mp2_ and mp2_.group(1), sd2_ and sd2_.group(1)))
    if 'SOURCES = "sources"' not in pmk_ or 'exports' not in (codefile('kmp/src/commonMain/kotlin/dev/stapler/stelekit/sketch/export/SketchExporter.kt') or ''): fail('code: the sources or exports directory names changed')
if {r_[1] for r_ in PL_} != {'live', 'lazy'} or not {'backup/', '<slug>/conflicts/', '<slug>/sources/'} <= {r_[0] for r_ in PL_ if r_[1] == 'lazy'}: fail('the preload table must classify backup/, conflicts/ and sources/ as lazy')
def live_bytes_(tree_):
    pats_ = [re.compile('^' + re.escape(r_[0]).replace('<slug>', '[^/]+').replace(r'\*', '[^/]*') + '$') for r_ in PL_ if r_[1] == 'live']
    return sum(sz_ for p_, sz_ in tree_.items() if any(rx_.match(p_) for rx_ in pats_))
MB_ = 1048576
healthy_ = {'a/project-model.json': 1 * MB_, 'a/sheets/s1.json': 9 * MB_, 'backup/a/sheets/s1.json': 400 * MB_, 'a/conflicts/c.json': 50 * MB_, 'a/sources/yaml-1.yaml': 20 * MB_, 'a/exports/e.svg': 30 * MB_, 'a/preview.svg': 1 * MB_, 'a/sheets/x.1.tmp': 9 * MB_}
if live_bytes_(healthy_) != 10 * MB_ or live_bytes_(healthy_) > mb_ * MB_: fail('a project with 400 MB of backup/ must stay Available: its live bytes are %d against the %d MB cap' % (live_bytes_(healthy_), mb_))
if live_bytes_({'a/project-model.json': 1 * MB_, 'a/sheets/s.json': (mb_ + 1) * MB_}) <= mb_ * MB_: fail('live files over the cap must exceed it')
if 'V2/scene/SceneLimits.kt' in FL.get('1.4.7j', []) or '1.2.4a' in S147 or not re.search(r'maxPreloadBytes=%d MB' % mb_, lim_): fail('maxPreloadBytes must be in the canonical SceneLimits of the non-parked Task 1.2.4a and Task 1.4.7j must not edit SceneLimits.kt')
# (U1, U2, U3) the web UX tables
LV_ = _rows(_blockany('lease-variants', ux), 6)
if not LV_ and CV is not None: fail('ux.md 14.8 has no lease-variants block')
for base_, vid_, summ_, short_, prim_, more_ in LV_ if CV is not None else []:
    b_ = next((b2_ for b2_ in CTB['banner'] if b2_['id'] == base_), None)
    if b_ is None or b_['tier'] != 'State': fail('lease variant %s: its base %s is not a State banner' % (vid_, base_)); continue
    mo_ = [x_.strip() for x_ in more_.split(';')]
    if prim_ != '-' or not {'Details', 'Close'} <= set(mo_) or 'Dismiss' in mo_: fail('lease variant %s must have no primary action, Details and Close under More and no Dismiss' % vid_)
    if (CV['wrap'](summ_, CV['per'][(320, 1.0, True)]) or 99) > CTB['params']['bannerLines'] or (CV['wrap'](short_, CV['per'][(320, 2.0, True)]) or 99) > CTB['params']['bannerLines']: fail('lease variant %s does not fit the banner row at 320 dp (summary at scale 1.0, short title at 2.0)' % vid_)
    if summ_ in [b3_['summary'] for b3_ in CTB['banner']] or short_ in [b3_['short'] for b3_ in CTB['banner']]: fail('lease variant %s repeats a summary or short title of the banner table, which would read as the classic-versus-new editor case' % vid_)
    if vid_ not in plan or 'UX-149' not in plan: fail('the plan does not name the variant %s and UX-149' % vid_)
SSV_ = {r_[0]: r_[1] for r_ in _rows(_blockany('still-saving', ux), 2)}
if CV is not None:
    n5_ = re.search(r'an Info notice with no primary action stays at least (\d+) seconds', ux); thr1_ = re.search(r'at most one per second', ux)
    shown_ = int(SSV_.get('shown-after-seconds', '0') or 0); minv_ = float(SSV_.get('min-visible-seconds', '0') or 0); ann_ = float(SSV_.get('announce-after-visible-seconds', '0') or 0)
    if not n5_ or not thr1_ or shown_ != int(ss_[0].group(1)) or not 0 < minv_ < int(n5_.group(1)) or ann_ < minv_ or ann_ < 1: fail('still-saving block: shown after %s s (plan says %s), minimum visible %s, announce after %s, against the %s s notice minimum and the 1 per second throttle' % (shown_, ss_[0].group(1), minv_, ann_, n5_ and n5_.group(1)))
    if (SSV_.get('displaces-other-toast'), SSV_.get('displaced-by-newer-toast'), SSV_.get('removed-at'), SSV_.get('list-entry-removed-at'), SSV_.get('announcement-source'), SSV_.get('chip-is-live-region')) != ('no', 'yes', 'Persisted', 'Persisted', 'toast', 'no'): fail('still-saving block rows are %s' % SSV_)
    for t_ in (3.0, 3.2, 3.9, 4.0, 4.5, 8.0):
        vis_ = max(t_ - shown_, minv_); said_ = (t_ - shown_) > ann_
        if vis_ < minv_ or (t_ <= shown_ + ann_ and said_): fail('still-saving simulation at %.1f s: visible %.1f s, announced %s' % (t_, vis_, said_))
    if 'StillSaving' not in blk(ux, '- Notice timing (UX-112', 'Touch note'): fail('the notice timing rule of section 16 does not name the StillSaving exception')
DW_ = _rows(_blockany('durability-wording', ux), 6)
if CV is not None:
    dwid_ = lambda s_, p_: 'SessionOnly' if s_ == 'true' else ('PrivateWindow' if p_ == 'true' else 'Conservative')
    if sorted((r_[0], r_[1]) for r_ in DW_) != [('false', 'false'), ('false', 'true'), ('true', 'false'), ('true', 'true')]: fail('durability-wording must have one row per (sessionOnly, privateSuspected) combination: %s' % [(r_[0], r_[1]) for r_ in DW_])
    byid_ = {}
    for s_, p_, wid_, dlg_, tip_, cue_ in DW_:
        if wid_ != dwid_(s_, p_): fail('durability-wording row (%s, %s) says %s, the precedence (session-only over private over conservative) gives %s' % (s_, p_, wid_, dwid_(s_, p_)))
        if byid_.setdefault(wid_, (dlg_, tip_, cue_)) != (dlg_, tip_, cue_): fail('durability-wording rows with the id %s differ in their texts' % wid_)
        if (cue_ != '-') != (wid_ in ('PrivateWindow', 'SessionOnly')): fail('the hint row cue must exist exactly for PrivateWindow and SessionOnly (row %s, %s)' % (s_, p_))
        if cue_ != '-' and (CV['wrap'](cue_, chrome_charsper(320 - 2 * CTB['params']['gutter'], 1.0, CTB['params']['bannerCharDp'])) or 99) > 1: fail('the cue "%s" does not fit one hint row line at 320 dp' % cue_)
    if len({v_[0] for v_ in byid_.values()}) != 3 or len({v_[1] for v_ in byid_.values()}) != 3: fail('the three wordings must have distinct dialog lines and distinct Saved tooltips')
    d142_ = blk(ux, '- **Web build: the durability line', '### 14.8')
    for wid_, (dlg_, tip_, cue_) in byid_.items():
        if dlg_ not in d142_: fail('the first-write dialog line of %s in the durability-wording block is not the line quoted in ux.md 14.2' % wid_)
    if byid_.get('PrivateWindow', ('', '', ''))[1] not in plan: fail('the plan does not quote the private-window tooltip of the durability-wording block')
    tt_ = {t3_['id']: t3_ for t3_ in CTB['toast']}
    if tt_.get('ExportSavedWeb', {}).get('primary') != '-' or tt_.get('ExportSaved', {}).get('primary') != 'Open': fail('the ExportSavedWeb toast must have no primary action while ExportSaved keeps [Open]')
for u_ in ('UX-149', 'UX-150', 'UX-151'):
    if u_ not in uxids: fail('ux.md section 20 lacks %s' % u_)
print('iteration 18: %d code anchors of the web baseline verified, keep or drop %d cases (threshold %s, families %s), abandon %d cases, label precedence %d rows, footprint %dk raw %sM CU, solver bars hard %s advisory %s, wiring %d rows, exhaustive whens over DomainError %d, store scope %s, preload live set %d rows, lease variants %d, durability wording %d rows, still-saving block %d rows' % (nbase_, len(KDC_), T_, FAM_, len(ABC_), len(LPR_), FPRAW_, M2(FPRAW_), hard_, adv_, len(wrows_), len(ex_files_), sorted(tab_) if psk2_ is not None else '-', len(PL_), len(LV_), len(DW_), len(SSV_)))

for ep in epics:
    ts = byep[ep]; raw = sum(cost(t) for t in ts)
    mixs = ' '.join('%d%s' % (sum(1 for t in ts if size[t] == k), k) for k in ('M', 'S', 'XS') if any(size[t] == k for t in ts))
    if not re.search(r'^\| %s [^|]+\| %d \| %d \| %s \| %dk \| 12%% \| %.2fM \|' % (re.escape(ep), len([x for x in stories if x.startswith(ep + '.')]), len(ts), mixs, raw, raw * 1.48 / 1000), plan, re.M):
        fail('plan.md effort table row of epic %s differs from the computed row' % ep)

# ================= iteration 19: seam classification and wiring order, the classic file side on the web, the post-spike gate, the boot-refusal screen, the large-scale cue and the nits. Every check parses or computes a value; none matches a wording fragment except where a retired phrase must be gone.
def _leq(x, y):
    nx, ny = node_of(x), node_of(y)
    return x == y or (nx != ny and nx in _reach([ny]))
# (E1, E2) the red-window rule per row, the catalog order, the SEAMS list count chain
wa19_ = codefile('kmp/src/businessTest/kotlin/dev/stapler/stelekit/wiring/WiringAudit.kt')
base19_ = None
if wa19_ is not None:
    sm19_ = re.search(r'val SEAMS = listOf\((.*?)\)', wa19_, re.S); base19_ = len(re.findall(r'"(\w+)"', sm19_.group(1))) if sm19_ else None
if base19_ is None: fail('code: WiringAudit.SEAMS could not be counted')
for seam_, impl_, decl_, wt_, root_, ct_, label_, test_, ss_ in wrows_:
    a_ = ADDT19_.get(seam_)
    if a_ is None: fail('no task heading adds %s to WiringAudit.SEAMS' % seam_); continue
    if not (_leq(wt_, a_) or _leq(wt_, decl_)): fail('red window: %s is constructed by Task %s, which is neither before the task that adds %s to the list (Task %s) nor before the task that declares it (Task %s), so the audit sees an unconstructed implementation in between' % (impl_, wt_, seam_, a_, decl_))
    if label_ != '-' and not _leq(a_, ct_): fail('the catalog task %s of %s is not after the task %s that adds %s to the list, so the catalog would name an unknown seam' % (ct_, impl_, a_, seam_))
    if label_ != '-' and ss_ not in ('androidUnitTest', 'jvmTest'): fail('the entry-point test of %s lives in %s, which has no WiredAppFixture' % (impl_, ss_))
adders19_ = sorted(set(ADDT19_.values()), key=lambda t_: (W.get(node_of(t_), 0), t_))
cum19_ = base19_ or 0; chain19_ = []
for i_, t_ in enumerate(adders19_):
    hd_ = re.search(r'^##### Task %s:(.*)$' % re.escape(t_), plan, re.M).group(1)
    cum19_ += len([s_ for s_, tt_ in ADDT19_.items() if tt_ == t_]); chain19_.append((t_, cum19_))
    mm_ = re.search(r'\((\d+) names after this task', hd_)
    if not mm_ or int(mm_.group(1)) != cum19_: fail('the heading of Task %s says %s names are on WiringAudit.SEAMS after it, the computed count is %d' % (t_, mm_ and mm_.group(1), cum19_))
    if i_ and not _leq(adders19_[i_ - 1], t_): fail('Tasks %s and %s both edit the SEAMS list assertion but have no path between them' % (adders19_[i_ - 1], t_))
for nm_, tx_ in (('plan', plan), ('validation', val.split('```python check-plan')[0]), ('requirements', req), ('ux', ux)):
    if re.search(r'seven[- ]names?\b', tx_): fail('%s.md still says "seven-name" or "seven names" for the SEAMS list, which has %d names after the last task' % (nm_, cum19_))
# the nine class seams: classified, built by a task that names them, reached by tests in androidUnitTest and jvmTest
SC19_ = _rows(_blockany('plan-seam-classes', plan), 5)
ac19_ = re.search(r'^- All new seams are classified and covered.*$', plan, re.M)
if not ac19_: fail('Story 6.2.1 lacks the classification bullet')
else:
    listed19_ = set(re.findall(r'`([A-Z]\w+)`', ac19_.group(0))) - {'WiredAppFixture', 'DesktopWiredAppFixture', 'FIRST_OPEN_SCREENS'}
    want19_ = {r_[0] for r_ in wrows_} | {r_[0] for r_ in SC19_}
    if listed19_ != want19_ or len(want19_) != 14: fail('the 14 names of Story 6.2.1 (%d listed) differ from the plan-wiring seams plus the plan-seam-classes names (%d): %s' % (len(listed19_), len(want19_), sorted(listed19_ ^ want19_)))
for nm_, bt_, kd_, at_, dt_ in SC19_:
    if bt_ not in size: fail('seam class %s is built by the unknown task %s' % (nm_, bt_)); continue
    hd_ = re.search(r'^##### Task %s:(.*)$' % re.escape(bt_), plan, re.M).group(1)
    if '`%s`' % nm_ not in hd_ and not any(p_.endswith('/%s.kt' % nm_) or p_.endswith('/%s.kt (new)' % nm_) for p_ in FL.get(bt_, [])): fail('seam class %s is declared as built by Task %s, whose heading and file list do not name it' % (nm_, bt_))
    cr20_ = [t2_ for t2_, nw2_ in NEWM.items() if any(p_.endswith('/%s.kt' % nm_) for p_ in nw2_)]
    if cr20_ and bt_ not in cr20_: fail('seam class %s is declared as built by Task %s but the task that creates its file is %s' % (nm_, bt_, cr20_))
    if kd_ not in ('strategy', 'class', 'expect-class'): fail('seam class %s has the kind %s' % (nm_, kd_))
    if nm_ in (base19_ and re.findall(r'"(\w+)"', re.search(r'val SEAMS = listOf\((.*?)\)', wa19_, re.S).group(1))) or nm_ in {r_[0] for r_ in wrows_}: fail('%s is both a class of plan-seam-classes and an interface on the list' % nm_)
    if not any(p_.startswith('AT/') and p_.endswith('/%s.kt' % at_) for p_ in FL.get('6.2.1a', [])) or not any(p_.startswith('JT/') and p_.endswith('/%s.kt' % dt_) for p_ in FL.get('6.2.1a', [])): fail('the reachability tests %s and %s of %s are not listed under AT/ and JT/ by Task 6.2.1a' % (at_, dt_, nm_))
if any('BT/sketch/v2/WiringV2EntryPointTest' in p_ for fl2_ in FL.values() for p_ in fl2_): fail('an entry-point test of the wiring catalog is listed under businessTest, which cannot call a fixture')
if not {'kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/AllAndroidUnitTests.kt', 'kmp/src/jvmTest/kotlin/dev/stapler/stelekit/AllJvmTests.kt'} <= set(FL.get('6.2.1c', [])): fail('Task 6.2.1c does not list both registration files')
# the required fields: every production construction site of StelekitAppPlatformIntegrations is in the file lists of Tasks 1.4.3s and 1.4.3t, and the wasm lines are the ones the heading cites
sites19_ = {}
for root19_ in (os.path.join(ROOTC, 'kmp', 'src'), os.path.join(ROOTC, 'androidApp', 'src')):
    for dp_, _, fs_ in os.walk(root19_):
        for f_ in fs_:
            if f_.endswith('.kt') and f_ != 'StelekitAppDependencies.kt':
                tx_ = open(os.path.join(dp_, f_), encoding='utf-8').read()
                lns_ = [i_ + 1 for i_, l_ in enumerate(tx_.split('\n')) if 'StelekitAppPlatformIntegrations(' in l_]
                if lns_: sites19_[os.path.relpath(os.path.join(dp_, f_), ROOTC)] = lns_
lst19_ = set(FL.get('1.4.3s', [])) | set(FL.get('1.4.3t', []))
if not sites19_ or set(sites19_) - lst19_: fail('production construction sites of StelekitAppPlatformIntegrations %s are not in the file lists of Tasks 1.4.3s and 1.4.3t' % sorted(set(sites19_) - lst19_))
if set(FL.get('1.4.3s', [])) - set(sites19_): fail('Task 1.4.3s lists files that are not construction sites: %s' % sorted(set(FL.get('1.4.3s', [])) - set(sites19_)))
wm19_ = [k_ for k_ in sites19_ if k_.startswith('kmp/src/wasmJsMain/')]
h13t_ = re.search(r'^##### Task 1.4.3t:(.*)$', plan, re.M).group(1)
lc19_ = re.search(r'lines (\d+) and (\d+)', h13t_)
if len(wm19_) != 1 or not lc19_ or [int(lc19_.group(1)), int(lc19_.group(2))] != sites19_[wm19_[0]]: fail('Task 1.4.3t cites the wasm sites %s, the code has %s' % (lc19_ and lc19_.groups(), [sites19_[k_] for k_ in wm19_]))
for t_ in ('1.4.7e', '1.4.7i'):
    if not any(p_.startswith('kmp/src/wasmJsMain/') and p_.endswith('/browser/Main.kt') for p_ in FL.get(t_, [])): fail('Task %s constructs a wasm implementation but does not list browser/Main.kt' % t_)
sde19_ = codefile('kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitAppDependencies.kt')
if sde19_ is not None and re.search(r'val (sketchWriterLease|browserPersistence)\b', sde19_): fail('code: StelekitAppPlatformIntegrations already has the storage fields, Task 1.4.3p is stale')
if 'TEMPORARY' not in re.search(r'^##### Task 1.4.3p:(.*)$', plan, re.M).group(1) or 'REQUIRED' not in h13t_ or 'deleted' not in h13t_: fail('Tasks 1.4.3p and 1.4.3t must state the temporary defaults and their deletion')
# (E3) the lease epoch rule, recomputed
LE19_ = _rows(_blockany('plan-lease-epoch', plan), 5)
def epoch_outcome(parked_, state_, ep_): return 'KEEP_PARKED' if state_ != 'Held' else ('WRITE' if parked_ == ep_ else 'CONFLICT_COPY')
for sc_, pk_, st_, ep_, out_ in LE19_:
    if epoch_outcome(int(pk_), st_, int(ep_)) != out_: fail('lease epoch scenario %s: the table says %s, the rule computes %s' % (sc_, out_, epoch_outcome(int(pk_), st_, int(ep_))))
if {r_[4] for r_ in LE19_} != {'WRITE', 'KEEP_PARKED', 'CONFLICT_COPY'} or not any(int(r_[3]) > int(r_[1]) and r_[2] == 'Held' for r_ in LE19_): fail('plan-lease-epoch must cover WRITE, KEEP_PARKED and CONFLICT_COPY and a re-acquire under a later epoch')
if 'epoch' not in re.search(r'^##### Task 1.4.3m:(.*)$', plan, re.M).group(1): fail('the heading of Task 1.4.3m does not give the lease port its epoch')
SS19_ = _rows(_blockany('plan-store-scope', plan), 4)
for m_, ret_, beh_, src_ in SS19_:
    if ret_ == 'planned': fail('plan-store-scope: the planned method %s has no return family yet' % m_)
    if not re.search(r'`%s(?:WithoutLease|AfterLeaseLoss)_should_' % re.escape(m_), plan) or not re.search(r'%s(?:WithoutLease|AfterLeaseLoss)_should_' % re.escape(m_), val.split('```python check-plan')[0]): fail('no refusal test in plan.md and validation.md for the store method %s' % m_)
for t_ in ('flushPendingAfterLeaseReacquire_should_NotReplayStaleBundle', 'staleParkedBundle_should_BecomeConflictCopy', 'staleParkedModel_should_BecomeConflictCopy'):
    if t_ not in plan or t_ not in val.split('```python check-plan')[0]: fail('the stale-bundle test %s is missing from plan.md or validation.md' % t_)
# (P1, P2, P3; iteration 20) the classic file side on the web, per sheet: the policy rows, the scenarios, the discovery bounds, the demand the default build serves
CW19_ = _rows(_blockany('classic-web-file-side', req), 4); CS19_ = dict(_rows(_blockany('classic-web-setting', req), 2))
def classic_runs(verdict_, setting_, kind_): return verdict_ == 'Available' and (setting_ == 'On' or kind_ == 'v2')
for v_, s_, k_, out_ in CW19_:
    if ('yes' if classic_runs(v_, s_, k_) else 'no') != out_: fail('classic file side row (%s, %s, %s sheet): the table says %s' % (v_, s_, k_, out_))
if CS19_.get('default') != 'Off' or CS19_.get('independent-of') != 'V2Mode' or not any(r_[:3] == ['Available', 'Off', 'classic'] and r_[3] == 'no' for r_ in CW19_) or not any(r_[:3] == ['Available', 'Off', 'v2'] and r_[3] == 'yes' for r_ in CW19_) or not any(r_[0] != 'Available' and r_[1] == 'On' and r_[3] == 'no' for r_ in CW19_): fail('classic-web-setting must default to Off, be independent of V2Mode, and the table must keep a classic sheet off at the default, a v2 sheet on at the default and everything off in an unavailable browser')
for nm_, tx_ in (('plan', plan), ('requirements', req), ('validation', val), ('ux', ux)):
    if set(re.findall(r'sketch\.web\.\w+', tx_)) - {CS19_.get('setting')}: fail('%s.md names a web classic setting other than %s' % (nm_, CS19_.get('setting')))
if CS19_.get('owner-switch') != "localStorage.setItem('%s', 'On')" % CS19_.get('setting') or CS19_.get('store') != 'localStorage': fail('classic-web-setting: the owner switch must set the setting key in localStorage to On')
ps20_ = codefile('kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformSettings.kt') or ''
if not re.search(r'override fun putString\(key: String, value: String\) \{.{0,200}?localStorage\.setItem\(key, value\)', ps20_, re.S): fail('code: the wasm PlatformSettings no longer stores a key unprefixed in localStorage, so the owner switch is stale')
sw20_ = CS19_.get('owner-switch', '')
if sw20_ not in blk(plan, '- Web browser pass (Task 1.4.7d)', '\n- ') or sw20_ not in req: fail('the owner switch %s is not stated in the Task 1.4.7d browser pass and in requirements.md' % sw20_)
def scen_(setting_, indb_, bundle_):
    kinds_ = list(dict.fromkeys(bundle_.split()))
    found_ = 'database' if indb_ == 'yes' else ('importNew' if setting_ == 'On' else ('discovery scan' if 'v' in kinds_ else 'not found'))
    allowed_ = [k_ for k_ in kinds_ if classic_runs('Available', setting_, 'v2' if k_ == 'v' else 'classic')]
    return found_, (' '.join(allowed_) if found_ != 'not found' and allowed_ else 'none')
CSC20_ = _rows(_blockany('classic-web-scenarios', req), 5)
for s_, d_, b_, f_, tk_ in CSC20_:
    if scen_(s_, d_, b_) != (f_, tk_): fail('classic-web-scenarios row (%s, in database %s, bundle %s): the table says (%s, %s), the per-sheet rule computes %s' % (s_, d_, b_, f_, tk_, scen_(s_, d_, b_)))
if not any(r_[:3] == ['Off', 'no', 'v'] and r_[3:] == ['discovery scan', 'v'] for r_ in CSC20_) or not any(r_[0] == 'Off' and r_[2].split().count('c') and 'v' in r_[2].split() and 'c' not in r_[4].split() for r_ in CSC20_) or not any(r_[:3] == ['Off', 'no', 'c'] and r_[3] == 'not found' for r_ in CSC20_) or not any(r_[0] == 'On' and r_[1] == 'no' for r_ in CSC20_): fail('classic-web-scenarios must cover a synced v2 project unknown to the database under Off, a mixed project under Off whose classic sheets stay untouched, a classic-only project that is not found under Off and an On row')
if 'synced from desktop opens editable' in req and not any(r_[0] == 'Off' and r_[1] == 'no' and r_[3] == 'discovery scan' and 'v' in r_[4].split() for r_ in CSC20_): fail('requirements.md claims a v2 sheet synced from desktop opens editable but no scenario with the default setting Off finds a project the database does not know')
VD20_ = dict(_rows(_blockany('v2-discovery', req), 2))
try: vdmax_, vdb_, vdt_ = int(VD20_['max-unknown-projects']), int(VD20_['probe-bytes']), int(VD20_['time-budget-ms'])
except Exception: vdmax_ = vdb_ = vdt_ = 0; fail('requirements.md block v2-discovery lacks max-unknown-projects, probe-bytes or time-budget-ms')
if vdb_ != probe or vdmax_ < 1 or vdt_ < 1 or VD20_.get('writes') != 'none to the sidecar' or VD20_.get('imports') != 'v2 sheets only': fail('v2-discovery: probe-bytes %s must equal HEADER_PROBE_BYTES %d, the bounds must be positive, it writes nothing to the sidecar and imports v2 sheets only' % (vdb_, probe))
d153_ = re.search(r'^  - D-153 .*$', plan, re.M)
if not d153_ or not all(x_ in d153_.group(0) for x_ in ('at most %d unknown projects' % vdmax_, '%d ms' % vdt_, 'first %d bytes' % vdb_, '%s bytes in all' % format(vdmax_ * vdb_, ','))): fail('D-153 must quote the discovery bounds of the v2-discovery block: %d unknown projects, %d ms, %d bytes, %s bytes in all' % (vdmax_, vdt_, vdb_, format(vdmax_ * vdb_, ',')))
WV20_ = dict(_rows(_blockany('web-value', req), 2))
if WV20_.get('counted-use') != kdp_.get('counted-use') or 'v2 sheets synced from desktop' not in WV20_.get('delivers', '') or 'classic' not in WV20_.get('does-not-deliver', ''): fail('web-value: the counted use must equal the keep-drop parameter counted-use (%r vs %r), the default build must deliver synced v2 sheets and must not deliver the classic file side' % (WV20_.get('counted-use'), kdp_.get('counted-use')))
for nm_, tx_ in (('requirements', req), ('plan', plan)):
    if tx_.count('`web-value`') < 2 and nm_ == 'requirements': fail('requirements.md must point at the web-value block from the keep or drop rule and from the "what works" bullet')
if '1.4.7o' not in S147 or '1.4.7n' not in S147: fail('Tasks 1.4.7n and 1.4.7o must be in OPFSSPIKE')
if not any(p_.endswith('/WasmClassicReconcileTest.kt') and p_.startswith('kmp/src/wasmJsTest/') for p_ in FL.get('1.4.7n', [])) or not any(p_.endswith('/SyncedV2ProjectDiscovery.kt') for p_ in FL.get('1.4.7n', [])) or CS19_.get('setting') not in re.search(r'^##### Task 1.4.7o:(.*)$', plan, re.M).group(1) or CS19_.get('setting') not in re.search(r'^##### Task 1.4.7n:(.*)$', plan, re.M).group(1): fail('Task 1.4.7n must list WasmClassicReconcileTest under wasmJsTest and SyncedV2ProjectDiscovery, and Tasks 1.4.7n and 1.4.7o must name the setting')
m6_ = re.search(r'\*\*Platform scope \(D-147\)\*\*[^\n]*?Task 1\.4\.7n', req)
if not m6_: fail('metric 6 states no platform scope that names Task 1.4.7n')
cw_code_ = (codefile('kmp/src/commonMain/kotlin/dev/stapler/stelekit/sketch/export/SketchExporter.kt') or '') + (codefile('kmp/src/commonMain/kotlin/dev/stapler/stelekit/projectbridge/SidecarReconciler.kt') or '')
if 'AtomicWriteUnsupported' not in cw_code_ or 'DeviceLocal' not in cw_code_: fail('code: the web baseline outcomes AtomicWriteUnsupported and DeviceLocal that the default Off preserves are gone')
# (E1; iteration 20) no task that can make wasm storage available precedes the task that installs the classic file side gate
AS20_ = _rows(_blockany('plan-availability-switch', plan), 3)
sw_found_ = sorted(t_ for t_, h_ in ((m_.group(1), m_.group(2)) for m_ in re.finditer(r'^##### Task (\d+\.\d+\.\d+[a-z]?):(.*)$', plan, re.M)) if '`supportsTempRenameWrites`' in h_ and any(p_.startswith('kmp/src/wasmJsMain/') for p_ in FL.get(t_, [])))
if sorted(r_[0] for r_ in AS20_) != sw_found_ or not sw_found_: fail('plan-availability-switch lists %s but the tasks whose heading names supportsTempRenameWrites and that edit wasm code are %s' % (sorted(r_[0] for r_ in AS20_), sw_found_))
gates20_ = {r_[2] for r_ in AS20_}
for t_, member_, g_ in AS20_:
    hd_ = re.search(r'^##### Task %s:(.*)$' % re.escape(t_), plan, re.M).group(1)
    if member_ not in hd_ or g_ not in size: fail('plan-availability-switch row %s names a member or gate that does not exist' % t_); continue
    if node_of(g_) not in _reach([node_of(t_)]) or W[node_of(g_)] >= W[node_of(t_)]: fail('the classic file side gate %s is not an ancestor of %s in an earlier wave (gate W%s, switch W%s), so the classic file side would run ungated on wasm' % (g_, t_, W.get(node_of(g_)), W.get(node_of(t_))))
    if g_ not in S147 or 'closed by default' not in re.search(r'^##### Task %s:(.*)$' % re.escape(g_), plan, re.M).group(1) or 'CM/ui/GraphContentViewModelSetup.kt' not in FL.get(g_, []) or not any(p_.endswith('/ClassicWebFileSidePolicy.kt') for p_ in FL.get(g_, [])): fail('the gate task %s must be in OPFSSPIKE, be closed by default and list GraphContentViewModelSetup.kt and ClassicWebFileSidePolicy.kt' % g_)
if [t_ for t_, fl_ in FL.items() if t_ not in gates20_ and any(p_.endswith('/ClassicWebFileSidePolicy.kt') for p_ in fl_)]: fail('ClassicWebFileSidePolicy.kt is listed by a task other than the gate task')
# (nit) the serialising edge, and the entry-point observation through a real class
for t_, af_, sf_ in _rows(_blockany('plan-serial-edges', plan), 3):
    if af_ not in E.get(node_of(t_), []) or sf_ not in FL.get(t_, []) or sf_ not in FL.get(af_, []): fail('plan-serial-edges: %s after %s needs a direct edge and the shared file %s in both file lists' % (t_, af_, sf_))
EO20_ = _rows(_blockany('plan-entry-observation', plan), 4)
src20_ = ''.join(os.path.join(dp_, f_) + '\n' for dp_, _, fs_ in os.walk(os.path.join(ROOTC, 'kmp', 'src', 'commonMain')) for f_ in fs_)
for te_, lb_, thr_, out_ in EO20_:
    if not any(r_[7] == te_ and r_[6] == lb_ for r_ in wrows_) or thr_ in ('SketchStorageDefaults', 'WiredAppFixture', 'DesktopWiredAppFixture') or not re.search(r'/%s\.kt$' % re.escape(thr_), src20_, re.M) or not out_.startswith('Failed('): fail('plan-entry-observation row %s: the label must be a plan-wiring label of that test, observed through a production class that exists in commonMain (not the defaults or a fixture) and asserting a refusal' % te_)
if {r_[0] for r_ in EO20_} != {r_[7] for r_ in wrows_ if r_[0] == 'SketchWriterLease' and r_[5] != '-'}: fail('plan-entry-observation must cover every catalogued entry-point test of the lease')
# (E nit) the epoch ordering anchors in ProjectSidecarStore.kt and the claim that the store check precedes the park
pss20_ = (codefile('kmp/src/commonMain/kotlin/dev/stapler/stelekit/projectbridge/ProjectSidecarStore.kt') or '').split('\n')
def _ln20(sub_, after_=0):
    return next((i_ + 1 for i_ in range(after_, len(pss20_)) if sub_ in pss20_[i_]), None)
wl_ = _ln20('override suspend fun write(slug: String, files'); un_ = _ln20('if (unavailable()) return SidecarWriteOutcome.Unavailable', (wl_ or 1) - 1); gb_ = _ln20('if (isGitSyncBusy()) {', (wl_ or 1) - 1)
fl_ = _ln20('override suspend fun flushPending'); cl_ = _ln20('pending.clear()', (fl_ or 1) - 1); rp_ = _ln20('else pending[slug] = files', (fl_ or 1) - 1)
h1403o_ = re.search(r'^##### Task 1.4.3o:(.*)$', plan, re.M).group(1)
if None in (wl_, un_, gb_, fl_, cl_, rp_) or not (un_ == wl_ + 1 and gb_ == un_ + 1): fail('code: ProjectSidecarStore.write no longer has the unavailable return followed by the git-sync park, so the lease ordering of Task 1.4.3o is stale')
elif not all(x_ in h1403o_ for x_ in ('ProjectSidecarStore.kt:%d' % un_, '`:%d`' % gb_, 'at `:%d`' % cl_, 'at `:%d`' % rp_)): fail('Task 1.4.3o must cite the code lines %s (unavailable), %s (git-sync park), %s (clear) and %s (re-park) of ProjectSidecarStore.kt' % (un_, gb_, cl_, rp_))
red621_ = RED.get('6.2.1', ['', '', ''])[1]
if {t_ for r_ in wrows_ for t_ in (r_[2], r_[3])} & set(S147) and 'OPFSSPIKE' not in red621_: fail('the REDUCED line of Story 6.2.1 does not name OPFSSPIKE although %s of its seams are declared or wired by parked tasks' % sorted({r_[0] for r_ in wrows_ if {r_[2], r_[3]} & set(S147)}))
# (P2) the post-spike re-read: the block recomputed, the gate in the edge block
PS19_ = _rows(_blockany('post-spike-recount', req), 8)
def ps_count(items_, verd_, recorded_):
    av_ = dict(zip(('chromium', 'firefox', 'safari'), [x_.strip() == 'A' for x_ in verd_.split(',')])); n_ = 0
    for it_ in items_.split(';'):
        pl_, fm_ = it_.strip().split('/', 1); fams_ = {x_.strip() for x_ in fm_.split(',')} - {'-'}
        if pl_ in ('desktop', 'android'): n_ += 1
        elif pl_ == 'web': n_ += 1 if ((fams_ & set(FAM_)) if recorded_ else any(av_.get(f_, False) for f_ in fams_)) else 0
    return n_
def ps_tier(n_): return 'PASS' if n_ >= 3 else ('MARGINAL' if n_ == 2 else 'FAIL')
for c_, items_, verd_, rc_, rec_, cnt_, out_, gate_ in PS19_:
    rn_, nn_ = ps_count(items_, verd_, True), ps_count(items_, verd_, False)
    if (int(rc_), rec_) != (rn_, ps_tier(rn_)) or (int(cnt_), out_) != (nn_, ps_tier(nn_)): fail('post-spike recount case %s: recorded %s/%s recount %s/%s, computed %d/%s and %d/%s' % (c_, rc_, rec_, cnt_, out_, rn_, ps_tier(rn_), nn_, ps_tier(nn_)))
    g_ = 'DECIDE' if (ps_tier(nn_) == 'FAIL' and ps_tier(rn_) != 'FAIL') else 'CONTINUE'
    if g_ != gate_: fail('post-spike recount case %s: the gate is %s, computed %s' % (c_, gate_, g_))
if {r_[7] for r_ in PS19_} != {'DECIDE', 'CONTINUE'} or not any(r_[4] == 'PASS' and r_[6] == 'FAIL' for r_ in PS19_) or not any(r_[4] == 'MARGINAL' and r_[6] == 'FAIL' for r_ in PS19_) or not any(r_[4] == 'FAIL' and r_[7] == 'CONTINUE' for r_ in PS19_): fail('post-spike-recount must cover PASS to FAIL, MARGINAL to FAIL and an earlier FAIL that stays CONTINUE')
GZ19_ = '0.1.8c'
if E.get(GZ19_) != ['0.1.8', '@ZW'] or W.get(GZ19_) != W.get('0.1.8') or W.get(GZ19_) != 1: fail('the post-spike gate %s must be the zero-width edge line "%s: 0.1.8 @ZW" in wave W1' % (GZ19_, GZ19_))
nogz_ = [n_ for n_ in E if n_ != 'OWNER0' and W[n_] >= 2 and GZ19_ not in _reach([n_])]
if nogz_: fail('nodes of wave W2 or later with no path from the post-spike gate %s: %s' % (GZ19_, nogz_))
if [n_ for n_ in E if W[n_] == 2 and GZ19_ not in E[n_]]: fail('W2 nodes without a direct edge from the post-spike gate: %s' % [n_ for n_ in E if W[n_] == 2 and GZ19_ not in E[n_]])
if GZ19_ in cl or GZ19_ not in OPFSDROP_ or size.get(GZ19_) != 'XS': fail('the post-spike gate must be an XS node of the OPFSDROP set outside every kept closure')
if len([n_ for n_, d_ in E.items() if '@ZW' in d_]) != 1: fail('exactly one zero-width gate is expected')
# (nits) A6 per target, the abandon mirror, a web item with no families, the cross-target tiers, the retired phrases
A619_ = _rows(_blockany('a6-tier-cases', req), 5); tg19_ = {'desktop': 'JVM', 'android': 'JVM', 'web': 'wasmJs'}
for c_, jt_, wt_, pl_, out_ in A619_:
    tiers_ = {'JVM': jt_, 'wasmJs': wt_}
    want_ = 'yes' if any(tiers_[tg19_[x_.strip()]] == 'GeometricOnly' for x_ in pl_.split(';') if x_.strip() in tg19_) else 'no'
    if want_ != out_: fail('A6 tier case %s: the table says %s, the per-target rule computes %s' % (c_, out_, want_))
if not any(r_[4] == 'no' and 'GeometricOnly' in (r_[1], r_[2]) for r_ in A619_) or not any(r_[4] == 'yes' for r_ in A619_): fail('a6-tier-cases must show a GeometricOnly tier of another target that does not trigger ADJUST and one that does')
CT19_ = _rows(_blockany('cross-target-agreement', req), 3)
def compared(j_, w_): return 'none' if 'Hidden' in (j_, w_) else ('all' if (j_, w_) == ('Full', 'Full') else 'geometric-only')
if sorted((r_[0], r_[1]) for r_ in CT19_) != sorted((a_, b_) for a_ in ('Full', 'GeometricOnly', 'Hidden') for b_ in ('Full', 'GeometricOnly', 'Hidden')): fail('cross-target-agreement must list all 9 pairs of tiers')
for j_, w_, k_ in CT19_:
    if compared(j_, w_) != k_: fail('cross-target agreement (%s, %s): the table says %s, the rule computes %s' % (j_, w_, k_, compared(j_, w_)))
for cx_ in FAM_:
    oth_ = [f_ for f_ in FAM_ if f_ != cx_][0]
    if not any(r_[1] == cx_ and dict(zip(('chromium', 'firefox', 'safari'), [x_.strip() for x_ in r_[2].split(',')]))[cx_] == 'U' and dict(zip(('chromium', 'firefox', 'safari'), [x_.strip() for x_ in r_[2].split(',')]))[oth_] == 'A' and r_[3] == 'ABANDON-DEMAND' for r_ in ABC_): fail('abandon-cases lacks the case (counted %s only, %s Unavailable, %s Available)' % (cx_, cx_, oth_))
if not any('web/-' in r_[1].split(';') and r_[3] == '0' for r_ in KDC_): fail('keep-drop-cases lacks a web item with no recorded browser family counting as no families')
if req.count('home-sketching precedent') != 1 or re.search(r'[Rr]elative-to-empty-canvas', req): fail('the Performance SLO states the relative frame-time form more than once')
ps19_ = blk(req, '## Problem Statement', '## Baseline')
if 'D-104' not in ps19_ or 'keep or drop rule' not in ps19_: fail('the Problem Statement states Web support without its condition (D-104 and the keep or drop rule)')
if [b_['id'] for b_ in CTB['banner'] if 'kept' in b_['summary'].lower()] or any('kept' in r_[2].lower() for r_ in _rows(_blockany('save-variants', ux), 7)): fail('a SaveFailed summary still claims the work is kept, which holds only while the tab stays open')
if [t_['id'] for t_ in CTB['toast'] if t_['id'] == 'StillSaving' and t_['primary'] != '-']: fail('StillSaving must have no primary action')
if re.search(r'action-less Info notice', plan + ux + val.split('```python check-plan')[0] + req): fail('a document still calls the StillSaving toast action-less although Details and Dismiss sit under More')
# (U1) the boot-refusal screen: figures, contrast, reflow, contract, outcomes and code anchors
BR19_ = dict(_rows(_blockany('boot-refusal', ux), 2))
def _lum(h_):
    c_ = [int(h_[i_:i_ + 2], 16) / 255.0 for i_ in (1, 3, 5)]; c_ = [x_ / 12.92 if x_ <= 0.03928 else ((x_ + 0.055) / 1.055) ** 2.4 for x_ in c_]
    return 0.2126 * c_[0] + 0.7152 * c_[1] + 0.0722 * c_[2]
def _ratio(a_, b_):
    la_, lb_ = sorted((_lum(a_), _lum(b_)), reverse=True); return (la_ + 0.05) / (lb_ + 0.05)
vp19_, rt20_, cp19_, sc19_ = int(BR19_['viewport-css-px']), int(BR19_['root-px']), int(BR19_['char-px']), float(BR19_['text-scale'])
gu19_ = float(BR19_['gutter-rem']) * rt20_
per19_ = lambda sc_: int((vp19_ - 2 * gu19_ * sc_) / (cp19_ * sc_))
def _greedy(t_, n_):
    lines_ = 1; cur_ = 0
    for w_ in t_.split():
        if cur_ and cur_ + 1 + len(w_) > n_: lines_ += 1; cur_ = len(w_)
        else: cur_ = cur_ + (1 if cur_ else 0) + len(w_)
    return lines_
for k_ in ('heading', 'message', 'message-no-retry', 'status-waiting', 'status-free', 'action', 'secondary-action'):
    if max(len(w_) for w_ in BR19_[k_].split()) > per19_(sc19_): fail('boot-refusal %s has a word longer than the %d characters one line holds at text scale %s with rem sizing (horizontal scrolling)' % (k_, per19_(sc19_), sc19_))
rt_ = {'text': _ratio(BR19_['fg'], BR19_['bg']), 'button label': _ratio(BR19_['button-fg'], BR19_['button-bg']), 'link': _ratio(BR19_['link-fg'], BR19_['bg'])}
nt_ = {'button against the page': _ratio(BR19_['button-bg'], BR19_['bg']), 'focus ring against the button': _ratio(BR19_['focus-ring'], BR19_['button-bg']), 'focus ring against the page': _ratio(BR19_['focus-ring'], BR19_['bg'])}
if min(rt_.values()) < 4.5 or min(nt_.values()) < 3.0: fail('boot-refusal contrast %s is below 4.5:1 or non-text contrast %s is below 3:1' % ({k_: round(v_, 2) for k_, v_ in rt_.items()}, {k_: round(v_, 2) for k_, v_ in nt_.items()}))
if float(BR19_['target-rem']) * rt20_ < 44 or int(BR19_['focus-ring-px']) < 2 or any((max(len(w_) for w_ in BR19_[k_].split()) * cp19_ + 2 * gu19_) * sc19_ > vp19_ - 2 * gu19_ * sc19_ for k_ in ('action', 'secondary-action')): fail('boot-refusal action target (%s rem), focus ring or width does not fit' % BR19_['target-rem'])
if BR19_['container-overflow-y'] != 'auto' or BR19_['animation'] != 'none': fail('boot-refusal must scroll vertically and have no animation')
BC19_ = _rows(_blockany('boot-refusal-contract', ux), 5)
if [r_[0] for r_ in BC19_] != ['arrive', 'waiting', 'lock-granted', 'reload-activated', 'retry-unsupported']: fail('boot-refusal-contract rows changed')
else:
    srcs_ = (1 if 'heading' in BC19_[0][3] else 0) + (1 if 'alert' in BC19_[0][4] else 0)
    if srcs_ != 1: fail('the arrive row of boot-refusal-contract has %d announcement sources (a focused heading and a role=alert message both announce), exactly one is allowed' % srcs_)
    if 'by itself' not in BR19_['message'] or 'by itself' in BR19_['message-no-retry'] or 'message-no-retry' not in BC19_[4][2] or 'message-no-retry' in BC19_[0][2] or '[%s]' % BR19_['secondary-action'] not in BC19_[0][2] + BC19_[4][2]: fail('boot-refusal: the message that promises an automatic reload must be shown only where the retry exists, the retry-unsupported row must show message-no-retry and both rows must show the secondary action')
mnj20_ = codefile('kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt') or ''; idx20_ = codefile('kmp/src/wasmJsMain/resources/index.html') or ''
if 'search.contains("%s")' % BR19_['secondary-target'].lstrip('?') not in mnj20_ or not re.search(r'html, body \{[^}]*overflow: hidden', idx20_) or re.search(r'@keyframes|animation\s*:', idx20_): fail('code: the open-temporarily URL, the overflow-hidden page (which the refusal container must override) or the absence of animation in index.html changed')
SO19_ = _rows(_blockany('second-tab-outcomes', ux), 3)
if {r_[0] for r_ in SO19_} != {'desktop', 'android', 'web', 'ios'} or [r_[:2] for r_ in SO19_ if r_[2].startswith('BootRefusal')] != [['web', 'persistent']] or any('OtherTab' in r_[2] for r_ in SO19_): fail('second-tab-outcomes must show BootRefusal for exactly the web persistent graph and never OtherTab')
dfj_ = codefile('kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/db/DriverFactory.js.kt') or ''; mnj_ = codefile('kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt') or ''; idx_ = codefile('kmp/src/wasmJsMain/resources/index.html') or ''
if 'tryAcquireLeader("stelekit-sqlite-driver-' not in dfj_ or 'GraphLockedElsewhereException(' not in dfj_ or not re.search(r'catch \(e: GraphLockedElsewhereException\) \{.{0,600}?showBootError\(', mnj_, re.S) or 'id="loading"' not in idx_: fail('code: the boot-refusal anchors (driver lock, GraphLockedElsewhereException, showBootError, #loading) changed')
if not {'BootRefusal.kt', 'Main.kt', 'index.html'} <= {os.path.basename(p_) for p_ in FL.get('1.4.6k', [])}: fail('Task 1.4.6k must list BootRefusal.kt, Main.kt and index.html')
for u_ in ('UX-152', 'UX-153'):
    if u_ not in uxids: fail('ux.md section 20 lacks %s' % u_)
# (U2) the cue at both scales, the line-sharing rule and the collapse windows computed from the chrome table
HC19_ = dict(_rows(_blockany('hint-cue', ux), 2))
gtr_, cdp_ = CTB['params']['gutter'], CTB['params']['bannerCharDp']
cpl19_ = lambda sc_: chrome_charsper(320 - 2 * gtr_, sc_, cdp_)
CF20_ = {r_[0]: r_[1:] for r_ in _rows(_blockany('cue-forms', ux), 5)}
if sorted(CF20_) != ['PrivateWindow', 'SessionOnly'] or CV['cueforms'] != CF20_: fail('cue-forms must hold one row each for PrivateWindow and SessionOnly: %s' % sorted(CF20_))
grid19_ = [1.0, 1.15, 1.3, 1.5, 2.0]
chosen20_ = {}
for wid_, fs_ in CF20_.items():
    if [r_[5] for r_ in DW_ if r_[2] == wid_][0] != fs_[0]: fail('the full form of the %s cue differs from the durability-wording cue column' % wid_)
    if not all(len(a_) > len(b_) for a_, b_ in zip(fs_, fs_[1:])) or not fs_[0].startswith(fs_[3]): fail('cue-forms %s: the four forms must shrink in length and the chip form must be the first word of the full form (the visible label lies inside the accessible name, WCAG 2.5.3)' % wid_)
    for s_ in grid19_:
        f_ = next((x_ for x_ in fs_[:3] if len(x_) <= cpl19_(s_)), None)
        if f_ is None: fail('the %s cue has no form that fits one hint row line at scale %s (%d characters per line)' % (wid_, s_, cpl19_(s_)))
        chosen20_[(wid_, s_)] = f_
pre19_ = {s_: len(HC19_['prefix']) + 1 + len(chosen20_.get(('PrivateWindow', s_), '')) > cpl19_(s_) for s_ in grid19_}
nh20_ = CV['nohint']
if not nh20_ or HC19_['collapsed-home'] != 'action chip row' or HC19_['collapsed-home-position'] != 'first chip' or HC19_['collapsed-home-dismissible'] != 'no': fail('hint-cue: the home where HintStatus is absent must be the first, non-dismissible chip of the action chip row')
nhk20_ = {(n_, w_, h_, sc_) for n_, w_, h_, sc_, why_, cw_, pk_, g_ in nh20_}
col20_ = {(r_['wid'], r_['w'], r_['h'], r_['sc']) for r_ in CV['rows'] if 'HintStatus' in r_['collapsed']}; perm20_ = {(r_['wid'], r_['w'], r_['h'], r_['sc']) for r_ in CV['rows'] if 'HintStatus' in CTB['perm'].get(r_['lay'], [])}
if nhk20_ != col20_ | perm20_ or not perm20_ or not col20_: fail('the windows without HintStatus must be the union of the Rows collapsed column and the permanent section, both non-empty (collapsed %s, permanent %s)' % (sorted(col20_), sorted(perm20_)))
for nm20_, tx20_ in (('ux', re.sub(r'<!-- chrome-computed:start -->.*?<!-- chrome-computed:end -->', '', ux, flags=re.S)), ('plan', plan), ('requirements', req), ('validation', val.split('```python check-plan')[0])):
    if re.search(r'HintStatus[^.\n]{0,90}\b\d{3} x \d{3}\b|\b\d{3} x \d{3}\b[^.\n]{0,60}HintStatus (?:itself )?collapses', tx20_): fail('%s.md enumerates windows where HintStatus is absent; the computed block is the one place (windows without HintStatus)' % nm20_)
SN20_ = _rows(_blockany('save-status-names', ux), 3)
for st_, vis_, nm_ in SN20_:
    if vis_ not in nm_: fail('save status %s: the visible label "%s" is not contained in the accessible name "%s" (WCAG 2.5.3)' % (st_, vis_, nm_))
for wid_, (dlg_, tip_, cue_) in byid_.items():
    if wid_ in ('PrivateWindow', 'SessionOnly') and (wid_, tip_) not in {(r_[0], r_[2]) for r_ in SN20_}: fail('the save-status-names block lacks the %s name of the durability-wording block' % wid_)
if not any(r_[0] == 'StillSaving' and 'Still saving to this browser' in r_[2] for r_ in SN20_): fail('save-status-names lacks the StillSaving name')
ot20_ = [i_ for i_, l_ in enumerate(ux.split('\n')) if 'LeaseConflict:OtherTab' in l_ and not l_.startswith('LeaseConflict |') and 'defence in depth' not in l_ and 'defence-in-depth' not in l_]
if ot20_: fail('ux.md lines %s describe the OtherTab banner without the qualifier that it is defence in depth' % [i_ + 1 for i_ in ot20_])
# the StillSaving announcement is one rule
an19_ = int(SSV_.get('announce-at-seconds', '0'))
if an19_ != int(SSV_['shown-after-seconds']) + int(SSV_['announce-after-visible-seconds']) or SSV_.get('announce-clock-needs-shown-toast') != 'no' or not re.search(r'announced once, at %d s' % an19_, ux) or not re.search(r'once at %d s from the start' % an19_, ux): fail('still-saving: the announcement second %d is not the shown-after plus the announce-after-visible seconds, or UX-140 and the paragraph do not state it' % an19_)
print('iteration 19: SEAMS list %d names today then %s, %d wiring rows pass the red-window rule, %d class seams, %d host construction sites (%d wasm), lease epoch rows %d, classic web file side rows %d (default %s), post-spike recount %d cases (DECIDE %d), gate %s wave W%d with edges into %d W2 nodes, A6 tier cases %d, cross-target pairs %d, boot-refusal %d chars per line at scale %s, private cue form per scale %s, chip windows %s, prefix dropped %s, announce at %d s' % (base19_, chain19_, len(wrows_), len(SC19_), sum(len(v_) for v_ in sites19_.values()), len(sites19_[wm19_[0]]) if wm19_ else 0, len(LE19_), len(CW19_), CS19_.get('default'), len(PS19_), sum(1 for r_ in PS19_ if r_[7] == 'DECIDE'), GZ19_, W.get(GZ19_, -1), len([n_ for n_ in E if W[n_] == 2]), len(A619_), len(CT19_), per19_(sc19_), sc19_, [chosen20_[('PrivateWindow', s_)] for s_ in grid19_], sorted(nhk20_), sorted(s_ for s_, v_ in pre19_.items() if v_), an19_))

# ================= iteration 20: the history table of the validation header
HT20_ = _rows(_blockany('history', val), 5)
tid20_ = {t_ for t_, _ in tasks}
for it_, n_, ids_, ux_, rc_ in HT20_:
    if it_ == 'now':
        if int(n_) != len(tasks) or int(ux_) != len(uxids): fail('history table: the "now" row says %s tasks and %s UX criteria, computed %d and %d' % (n_, ux_, len(tasks), len(uxids)))
        continue
    idl_ = ids_.split() if ids_ != '-' else []
    if [x_ for x_ in idl_ if x_ not in tid20_]: fail('history table iteration %s lists task ids that are not tasks: %s' % (it_, [x_ for x_ in idl_ if x_ not in tid20_]))
    if idl_ and len(idl_) != int(n_): fail('history table iteration %s says %s tasks but lists %d ids' % (it_, n_, len(idl_)))
    for mm_ in re.finditer(r'(\d+\.\d+\.\d+[a-z]?) (XS|S|M) to (XS|S|M)', rc_):
        if size.get(mm_.group(1)) is None: fail('history table iteration %s reclassifies the unknown task %s' % (it_, mm_.group(1)))
if [r_[0] for r_ in HT20_][-1] != 'now' or [r_[0] for r_ in HT20_][-2] != '20' or [r_[2] for r_ in HT20_][-2] != '1.4.7o' or size.get('1.4.3o') != 'M' or '1.4.3o S to M' not in HT20_[-2][4]: fail('history table must end with the iteration 20 row (task 1.4.7o, 1.4.3o reclassified S to M) and the computed "now" row')
if re.search(r'iteration 18 added 2 tasks[^\n]*\(sections for iterations', val.split('```python check-plan')[0]): fail('validation.md header still carries the run-on enumeration of iterations instead of the history table')
print('iteration 20: per-sheet policy rows %d, scenarios %d (discovery bounds %d projects x %d bytes = %d, %d ms), availability switch %s behind gate %s (W%s < W%s), windows without HintStatus %s, cue forms %s, chip rows %s, boot-refusal min text contrast %.2f, min non-text contrast %.2f, %d chars per line at scale %s with rem sizing, history rows %d, Option W %d tasks' % (len(CW19_), len(CSC20_), vdmax_, vdb_, vdmax_ * vdb_, vdt_, [r_[0] for r_ in AS20_], sorted(gates20_), (W.get(node_of(AS20_[0][2])) if AS20_ else None), (W.get(node_of(AS20_[0][0])) if AS20_ else None), sorted(nhk20_), {k_: [len(x_) for x_ in v_] for k_, v_ in CF20_.items()}, {'%s %dx%d@%.1f' % (n_, w_, h_, sc_): cw_ for n_, w_, h_, sc_, why_, cw_, pk_, g_ in nh20_}, round(min(rt_.values()), 2), round(min(nt_.values()), 2), per19_(sc19_), sc19_, len(HT20_), len(S147) + len(OPFSDROP_)))

if fails:
    print('\n'.join('FAIL ' + f for f in fails)); sys.exit(1)
print('check-plan: all checks pass')
```
