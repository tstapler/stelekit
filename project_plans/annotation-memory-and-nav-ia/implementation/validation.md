# Validation Plan: annotation-memory-and-nav-ia

**Date**: 2026-11-08
**Inputs**: `requirements.md` (incl. Amendments 2, 3, 4 and 5, 2026-11-08), `implementation/plan.md` (incl. Repair 5), `design/ux.md` (UX-01..UX-82; defaults LD-1..LD-20, ED-1..ED-27), `/home/tstapler/Programming/stelekit/CLAUDE.md` testing rules.
**Abbreviations**: `CT` commonTest, `BT` businessTest, `AUT` androidUnitTest (Robolectric), `JT` jvmTest, `AIT` androidInstrumentedTest, `APP` `androidApp/src/androidTest`, `SCR` `scripts/` (node/python). Path prefixes as in plan.md.

## Happy Path Scenario
Given the unmodified `408907d9cd` behaviour plus the probe-only PR (Baseline: owner kill never reproduced, ~100 ms CPU per press, flat memory, no harness), when 100 direct-injected back-to-back placements with the loupe run on a 4000x3000 photo on the pinned CI emulator and the app is then sent to the background, then the `ResourceProbe` records CPU, memory and frames for the run, the process survives with no bad exit reason, every deterministic gate in `benchmarks/budgets/annotation-resource-budgets.json` passes (`loaderConstructions == 1`, decode count/press, cache size, `undoDepth <= 50`), and the same gates go red when the matching fix is reverted or its mutation flag is set.

## Requirement Identifiers
REQ-1..REQ-5 are Success Metrics 1..5 (REQ-1 = SM1, and so on). REQ-6..REQ-15 come from Scope, Non-functional, Observability, Risk Control and Constraints. REQ-16 is Success Metric 6 (Amendment 3, post-release crash outcome).

| ID | Requirement (source) |
|---|---|
| REQ-1 (SM1) | Crash fixed/mitigated with regression test; CPU per gesture and sustained CPU within budget; no OOM/ANR/excessive-CPU kill/process death; pre-fix red proof |
| REQ-2 (SM2) | Peak heap/native/bitmap bytes plus CPU recorded for scenarios (a)-(g), each with a numeric budget calibrated from a measured baseline |
| REQ-3 (SM3) | CI regression protection; mixed gate/report-only policy; crash-class scenario (b) guarded |
| REQ-4 (SM4) | Navigation self-explanatory: sections + descriptions, ADR, UX review, first-open smoke updated |
| REQ-5 (SM5) | Annotation end-to-end review; every BLOCKER/CRITICAL fixed or tracked (C1, C2, C3, C4, M1-M7, minors) |
| REQ-6 | Audit of every image allocation path (Coil main + loupe decode, cache sizing, exporter/encoder, downscaler, depth, thumbnails) |
| REQ-7 | Benchmark suite design: scenarios, metrics, harness, budgets, report format, calibration procedure |
| REQ-8 | IA review/design across desktop sidebar, Android bottom bar/More, command palette |
| REQ-9 | NFR: placement/loupe stay within frame/jank SLOs |
| REQ-10 | NFR scalability: >=48 MP never decoded at native size; thousands of assets in Gallery/Assets |
| REQ-11 | NFR security: no image content/paths logged or uploaded |
| REQ-12 | Observability: machine-readable ProbeRecord consumed by benchdiff |
| REQ-13 | Observability: production-side diagnostics without logcat (exit reason, trim, samples, OOM guards) |
| REQ-14 | Risk control: gates start report-only, promotion/rollback is a single-file edit, per-PR revert |
| REQ-15 | Constraints: CLAUDE.md architecture rules (Either, DatabaseWriteActor, scope ownership, bounded reads, migrations), wiring/hygiene gates, Android-only crash class |
| REQ-16 (SM6) | Post-release outcome: zero `EXCESSIVE_RESOURCE_USAGE`/`ANR`/`LOW_MEMORY` exit reasons across >= 10 editor sessions over >= 14 days on the owner's phone (device evidence) |

## Requirement -> Test Mapping

"Pre-fix red" column: how the test is shown red before the fix (procedure = plan Story B.5: P = probe-only PR on unfixed behaviour; R = revert only the fix commit; M = `-Pannotate.mutation=<name>`; T = test written first, run red on pre-change code; n/a = nothing to fix, not a gate).

| Requirement | Test File (source set) | Test Name | Type | Scenario | Pre-fix red |
|---|---|---|---|---|---|
| REQ-1 | `AnnotationRapidPlacementSmokeTest` (APP, in `android-smoke` API 29 `-memory 2048`) | `rapidPlacement_should_CompleteWithoutUncaughtException_When_100DirectInjectedPointsPlaced` | Integration | Happy: 100 presses incl. a 4:1 canvas case, no crash | R (revert C.2 clamp) and M (`busyWaitMove`/throwing pointer handler) |
| REQ-1 | `AnnotationRapidPlacementSmokeTest` (APP) | `rapidPlacement_should_FailJob_When_PointerHandlerThrows` | Integration | Error path: mutation proves the smoke gate can fail | M |
| REQ-1 | `AnnotationPlacementResourceTest` (AIT) | `rapidPlacement_should_StayWithinCpuBudgetPerPlacement_When_100Presses4000x3000` | Integration | `RapidPlacement.cpuMsPerPlacement` vs budget, n>=10 median | M `busyWaitMove`; band from A.3e |
| REQ-1 | `AnnotationPlacementResourceTest` (AIT) | `sustainedDrag_should_ReportCpuPctAndP95Frame_When_20sCircularDrag` | Integration | 20 s drag, report-only 60% until B.7 | n/a (report-only) |
| REQ-1 | `AnnotationLifecycleResourceTest` (AIT) | `backgroundIdle_should_StayUnderIdleCpuBudget_When_60sAfterBurst` | Integration | Median of 3x20 s windows < 10% of a core, no new exit reason | M `backgroundBusyLoop` |
| REQ-1 | `BackgroundPositiveControlTest` (AIT) | `backgroundControl_should_ExceedGateAndRecordImportance_When_BusyLoopRunsInBackground` | Integration | Positive control A.3d: probe sees load and records process importance | M `backgroundBusyLoop` |
| REQ-1 | `BackgroundPositiveControlTest` (AIT) | `backgroundControl_should_RelabelAsIdleCpuAfterBurst_When_ImportanceNeverReachesCached` | Unit | Error/limit path: outcome (b) removes "stock rule" wording (ADR-002 amendment check via node text test) | n/a |
| REQ-1 | `AnnotationPlacementResourceTest` (AIT) | `rapidPlacement_should_ReportNoBadExitReason_When_BurstCompletes` | Integration | `getHistoricalProcessExitReasons` has no new `EXCESSIVE_RESOURCE_USAGE`/`LOW_MEMORY`/`ANR`; exit-reason gate | M `exitOnBackground` |
| REQ-1 | `EditorRecompositionTest` (AUT) | `loupeMove_should_RecomposeLoupeOnly_When_Finger20Moves` | Unit | Screen-body counter increments <= 1 across 20 moves | R (revert A.4a); counter from B.1d |
| REQ-1 | `EditorRecompositionTest` (AUT) | `loupeOffset_should_NotBeReadAtScreenScope_When_LoupeIsNull` | Unit | Error/edge: null offset, no screen recomposition | T |
| REQ-1 | `MeasurementLabelCacheTest` (AUT) | `labelOverlay_should_MeasureZeroTimes_When_SecondDrawWithUnchangedState50Annotations` | Unit | Second draw measures 0 | R (revert A.4b) / M `perDrawMeasure` |
| REQ-1 | `MeasurementLabelCacheTest` (AUT) | `labelOverlay_should_ReMeasure_When_TextOrStyleChanges` | Unit | Cache invalidates on key change (no stale label) | T |
| REQ-1 | `ImageLoaderConstructionCountTest` (BT) | `loaderConstruction_should_CountOnce_When_100LoupePressesInOneSession` | Unit | `loaderConstructions == 1` | R (revert A.4c alone, expect 100); M `loaderPerPress` |
| REQ-1 | `ImageLoaderConstructionCountTest` (BT) | `capturePreviewLoader_should_UseSharedMemoryCache_When_DialogOpens` | Unit | Cache is `sharedImageMemoryCache(context)` instance | R |
| REQ-1 | `AnnotationEditorCrashGuardTest` (BT) | `editorScope_should_RouteOomToStateErrorNotDefaultHandler_When_RepositoryThrowsOutOfMemoryError` | Unit | Default uncaught handler 0 invocations, `state.error != null` | T (red before A.2b) |
| REQ-1 | `AnnotationEditorCrashGuardTest` (BT) | `editorScope_should_KeepProcessAlive_When_ErrorThrownInDepthCoordinatorLaunch` | Unit | Error path in `DepthEstimationCoordinator` scope | T |
| REQ-1 | `AnnotationLifecycleResourceTest` (AIT) | `realPersistenceDepth_should_ReportCpuShareAndNoExitReason_When_100PressesWithSqlDelightAndDepthEstimator` | Integration | Real SQLDelight + depth + haptics; A.4d/A.4e handoff rule (>15% DefaultDispatcher or >50 ms commit) | M `backgroundBusyLoop` for depth-after-onStop |
| REQ-1 | `DepthEstimationCoordinatorTest` (BT, new; validation-added) | `depthEstimation_should_CancelPass_When_ActivityStops` | Unit | A.4e cancellation on `onStop` | T |
| REQ-1 | `ClaimVocabularyCheck` (SCR node, new; validation-added) | `fixPrs_should_NotContainFixesCrashClaim_When_PrDescriptionLinted` | Unit | Keep rule: no PR says "fixes the crash" without owner `importance=` evidence | n/a (process gate) |
| REQ-2 | `ResourceProbeSelfTest` (AIT) | `snapshot_should_ReturnNonNegativeFieldsAndIdleCpuUnder300ms_When_TwoSnapshots3sApart` | Integration | Happy: probe sanity | n/a |
| REQ-2 | `ResourceProbeSelfTest` (AIT) | `snapshot_should_SeeMoreThan150msCpuPerSecond_When_BurnLoopRuns2s` | Integration | Mutation: probe can see load | M (built in) |
| REQ-2 | `ProcStatParserTest` (CT) | `parse_should_SumTicksEqualToSumOfLines_When_AnyWellFormedStatLines` | Unit | Property: sum invariant | T |
| REQ-2 | `ProcStatParserTest` (CT) | `parse_should_IgnoreMalformedLines_When_StatLineTruncatedOrNonNumeric` | Unit | Error path | T |
| REQ-2 | `AnnotationLifecycleResourceTest` (AIT) | `openEditor_should_RecordPeakHeapAndBitmapBytes_When_LargePhotoOpened` | Integration | Scenario (a) | M `loupeFullDecode` for decode-bytes |
| REQ-2 | `AnnotationPlacementResourceTest` (AIT) | `rapidPlacementWithLoupe_should_RecordPeakAndRetainedHeap_When_50Placements` | Integration | Scenario (b) memory half | P (baseline) |
| REQ-2 | `AnnotationPlacementResourceTest` (AIT) | `loupeCycles_should_RecordMaxDecodeBytesAndDecodeCount_When_PressReleaseWithoutPlacing` | Integration | Scenario (c); decode count/press <= 1 | P (unfixed loupe: full-size decode) / M `loupeFullDecode` |
| REQ-2 | `AnnotationPlacementResourceTest` (AIT) | `selectMoveDrag_should_RecordCpuAndHeap_When_VertexDragsStartOnVertex` | Integration | Scenario (d); drags must start on a vertex (cpu.md invalid-run lesson) | n/a (report-only) |
| REQ-2 | `AnnotationLifecycleResourceTest` (AIT) | `captureAnnotateExport_should_RecordBakePeakAndAllocationByteCount_When_4096x3072Source` | Integration | Scenario (e), task B.3d | n/a (report-only; estimate INFERRED) |
| REQ-2 | `LibraryScrollSwitchResourceTest` (AIT) | `libraryScroll_should_RecordRetainedMbAndCacheWithinMax_When_Library5000ItemsScrolledTwiceUnfilteredAndAnnotated` | Integration | Scenario (f); `cacheSize <= cacheMaxSize` | M `cacheNoLimit` |
| REQ-2 | `LibraryScrollSwitchResourceTest` (AIT) | `screenSwitchChurn_should_ClearWeakReference_When_30CyclesLibraryEditorLibrary` | Integration | Scenario (g) leak gate with bounded GC loop | M `leakStrongRef` |
| REQ-2 | `FixtureGraphCountsTest` (BT, over `FixtureGraphSeeds` in CT) | `seeds_should_ProduceExactRowCounts_When_5000AnnotationsAnd5000AssetsRequested` | Unit | Fixture correctness | T |
| REQ-2 | `BudgetsFileCoverageTest` (SCR node, `bench-budget-check.test.js`) | `validate_should_FailWithMissingEntry_When_EmittedMetricHasNoBudget` | Unit | Every emitted `scenario.metric` has an entry | T |
| REQ-2 | `bench-budget-calibrate.test.js` (SCR) | `calibrate_should_ProposeMax1p5xOr2xMedian_When_10HistoryFiles` | Unit | Median 141, max 190 -> 285, file not edited | T |
| REQ-2 | `bench-budget-calibrate.test.js` (SCR) | `promote_should_RefuseWithNeed10Have9_When_9ValidRuns` | Unit | Error path; also refuses with any `invalid-device` run | T |
| REQ-3 | `bench-budget-check.test.js` (SCR) | `check_should_Exit1NamingMetricValueBudget_When_GateBreached` | Unit | Happy gate trip | T |
| REQ-3 | `bench-budget-check.test.js` (SCR) | `check_should_Exit0WithWarningRow_When_ReportOnlyBreached` | Unit | Report-only policy | T |
| REQ-3 | `bench-budget-check.test.js` (SCR) | `check_should_Exit1WithNoOtherChange_When_OnlyModeFlippedToGate` | Unit | One-field promotion | T |
| REQ-3 | `bench-budget-check.test.js` (SCR) | `check_should_RequireMutationNamePerGateEntry_When_BudgetsFileValidated` | Unit | Proof-matrix enforcement (B.5d) | T |
| REQ-3 | `bench-budget-check.test.js` (SCR) | `check_should_TagInvalidDeviceAndNeverFail_When_MemTotalDiffersMoreThan10PctFromRamSize` | Unit | Device validity (3,050,208 kB vs 2048 MB case) | T |
| REQ-3 | `android-benchmark.yml` lint (`actionlint -color`, `uvx 'zizmor==1.25.2' .`) | `workflow_should_LintClean_When_BenchmarkStepsAdded` | Integration | CI wiring; budget step after sticky comment (`if: always()`) | n/a |
| REQ-3 | `android-benchmark.yml` dry run (CI, one-off) | `ciDryRun_should_ShowRamSizeReachesGuest_When_JobStarts` | Integration | B.6a pin; else memory budgets `ReportOnly` | n/a (device-evidence, see below) |
| REQ-3 | `LoupeAlignmentE2ETest` (AIT) | `loupeAlignment_should_RunInCi_When_Pr560Merged` | Integration | B.6c, blocked on #560 | n/a |
| REQ-3 | `GateProofMatrixCheck` (manual PR checklist, task B.5c) | `eachGate_should_HaveRecordedRedRun_When_PromotedOrMerged` | Integration | Red JSON line + checker exit 1 pasted per row of the plan matrix | P/R/M per row |
| REQ-4 (SM4) | `NavModelExhaustivenessTest` (BT, JVM reflection) | `navModel_should_ClassifyEverySealedScreenSubtypeOnce_When_ScreenHierarchyReflected` | Unit | Every `Screen` has one spec or `NON_NAV` | T (compile: no `else`) |
| REQ-4 | `NavSectionBuilderTest` (CT) | `builder_should_ListNoDestinationTwice_When_AnyScreenSetGenerated` | Unit | Property | T |
| REQ-4 | `NavSectionBuilderTest` (CT) | `builder_should_OmitDeveloperSection_When_CurrentScreenNotLogsOrPerformance` | Unit | Developer only when active; contains Logs when on Logs | T |
| REQ-4 | `NavSectionBuilderTest` (CT) | `spec_should_HaveDescriptionOfAtMost10Words_When_AllSpecsChecked` | Unit | UX-03 precondition | T |
| REQ-4 | `SidebarSectionsTest` (AUT) | `sidebar_should_ShowHeadingThenDescribedRowsAtLeast48dp_When_GraphSectionRendered` | Unit | Heading semantics, visible description | T |
| REQ-4 | `MoreSheetTest` (AUT) | `moreTap_should_OpenGroupedSheetAndNotToggleSidebar_When_Width411dp` | Unit | D.4 | T |
| REQ-4 | `FirstOpenSmokeTest` + `FirstOpenSmokeCatalogTest` (existing, updated) | `firstOpen_should_ReachLibraryDestinationsViaSheet_When_FirstOpenScreensUpdated` | Integration | `first-open-screens.txt` and `tap(<I18n constant>)` agree | T (fails until updated) |
| REQ-4 | `ADR006GateCheck` (manual) | `adr006_should_BeDecidedWithExactI18nStrings_When_ReadBack` | Integration | ADR-006 DECIDED 2026-11-08 (merge); strings listed; rejected options marked | n/a |
| REQ-4 | `NavFirstOpenTaskTest` (AUT) | `firstOpen_should_ReachAnnotatedPhotosAndFileAttachmentWithZeroWrongTaps_When_NavOnly` | Integration | SM4 outcome measure on desktop sidebar and 411 dp shell; before-run on `408907d9cd` recorded where the driver applies | T / baseline run |
| REQ-4 | `LibraryRedirectTest` (BT) | `navigate_should_RedirectGalleryAndAssetBrowserToLibraryWithoutAliasOnBackStack_When_OldRouteUsed` | Integration | D.11a; Back returns to previous screen | T |
| REQ-4 | `PersistedScreenIdTest` (BT) | `persistedIds_should_ResolveToLibrary_When_OldGalleryAndAssetBrowserKeysRestored` | Integration | D.8c; tracing keys and palette ids | T |
| REQ-4 | `LibraryUpgradeNoticeTest` (AUT) | `notice_should_ShowOnceToExistingInstallAndTipToFreshInstall_When_FirstLibraryOpen` | Unit | D.9c; never both; pref failure non-blocking | T |
| REQ-4 | `ux:review` run (D.9b) | `uxReview_should_ListZeroMajorLabellingFindings_When_NavFinished` | Integration | Manual tool run | n/a |
| REQ-5 (SM5) | `CalibrationDecodeInvarianceTest` (CT) | `computeMeasurement_should_Return20mAtAnyDecodeWidth_When_Native4000PxAnd1080vs2208` | Unit | C1 property across widths 256-4096 and EXIF 1-8 | T (red on pre-fix `intrinsicSize`) |
| REQ-5 | `NativeImageSizeTest` (CT) | `oriented_should_Return3000x4000_When_4000x3000WithExifOrientation6` | Unit | Orientation swap | T |
| REQ-5 | `ImageBoundsTest` (AUT) | `readNativeImageSize_should_FailSafely_When_FileCorruptOrMissing` | Unit | Error path | T |
| REQ-5 | `LegacyCalibrationFlagTest` (BT) | `legacyRow_should_FlagRecalibrateAndKeepValues_When_BasisVersion0` | Integration | v0 flagged, no stored value changes; v1 no badge | T |
| REQ-5 | `CalibrationBasisMigrationTest` (BT, new; validation-added; Migration) | `migration_should_be_reversible` | Migration | ALTER adds `calibration_basis_version DEFAULT 0`; verify existing rows 0; "down" = pre-migration code still reads rows (additive column left unused) and, on SQLite >= 3.35, `DROP COLUMN` restores original schema | T |
| REQ-5 | `MigrationRunnerSchemaSyncTest` (BT, existing, extended) | `schemaSync_should_ListEveryTableAndIndexInMigrationRunner_When_SqFileScanned` | Unit | Indexes (D.7c) and the new column | T |
| REQ-5 | `LoupeLayoutPropertyTest` (CT) | `coerceInSafe_should_ReturnFiniteAndNeverThrow_When_MinGreaterThanMax` | Unit | C2 property | T (red pre-fix: `IllegalArgumentException`) |
| REQ-5 | `LoupeNarrowCanvasTest` (AUT) | `loupe_should_StayInsideCanvas_When_360x90dpPanorama` | Unit | C2 | T / R |
| REQ-5 | `UndoHistoryTest` (BT) | `undo_should_LeaveCommittedAndInProgressEmpty_When_RandomOpSequenceUndoneAll` | Unit | C3 property | T (red on pre-C.3) |
| REQ-5 | `UndoHistoryTest` (BT) | `undo_should_RestoreOnlyCalibration_When_TopEntryIsCalibrationAndCommitEarlier` | Unit | `UndoEntry.Calibration` semantics | T |
| REQ-5 | `UndoDepthTest` (BT) | `undoDepth_should_CapAt50_When_60Commits` | Unit | Bound; mutation `undoUnbounded` | M |
| REQ-5 | `AnnotationPersistenceQueueTest` (BT) | `queue_should_NotResurrectUndoneRow_When_CommitThenUndoWith200msSaveLatency` | Unit | M1; randomized-ordering property | T |
| REQ-5 | `EditorInitialLoadRaceTest` (BT) | `initialLoad_should_Keep4Rows_When_LoadCompletesAfterFirstPlacement` | Integration | M7 | T |
| REQ-5 | `EditorInitialLoadRaceTest` (BT) | `failedInitialLoad_should_IssueNoBulkDeleteAndShowRetryBanner_When_GetAnnotationsReturnsLeft` | Integration | Error path | T |
| REQ-5 | `MeasurementAnnotationActorRoutingTest` (BT) | `measurementWrites_should_CallActorExactlyOnceInOrder_When_SingleBatchUndoCalibrationPathsRun` | Integration | Recording `DatabaseWriteActor`; plus `rg "DirectRepositoryWrite" .../ui/annotate/` == 0 | T |
| REQ-5 | `UnsavedChangesDialogTest` (AUT) | `back_should_CloseWithoutDialog_When_OnlyCommittedAnnotations` | Unit | M2 | T |
| REQ-5 | `UnsavedChangesDialogTest` (AUT) | `back_should_ShowDiscardNUnfinishedPoints_When_UnfinishedPointsExist` | Unit | M2 plural | T |
| REQ-5 | `AnnotationExporterTest` (JT, real renderer) | `bake_should_DrawLabelTextInPill_When_DistanceLabel3p20m` | Unit | M5 golden diff; JT needs `scripts/jvm-display-check.sh` | T |
| REQ-5 | `AnnotationExporterTest` (JT) | `bake_should_ScaleStroke4xPlusMinus5Pct_When_1000pxVs4000px` | Unit | M5 scale | T |
| REQ-5 | `AndroidAnnotatedImageRendererTest` (AUT) | `bake_should_PlaceDotOrientedAndReturnLeft_When_Exif6AndInjectedOutOfMemoryError` | Unit | EXIF + Throwable guard, `Either.Left` | T |
| REQ-5 | `AnnotationSemanticsTest` (AUT) | `semantics_should_ExposeFocusableButtonPerAnnotation_When_3Committed` | Unit | M3 | T |
| REQ-5 | `HapticWiringTest` (AUT) | `commit_should_InvokeHapticOnceAndSetLiveRegion_When_MeasurementCommits` | Unit | M4 | T |
| REQ-5 | `LoupeEdgeTest` (AUT) | `loupe_should_HaveNoTransparentRegion_When_PressAt2dpFromLeftEdge` | Unit | M6 | T |
| REQ-5 | `EditorStateAtomicityTest` (BT) | `state_should_KeepBothWrites_When_AddPointAndInitialLoadRaceUnderTestDispatcher` | Unit | Atomic `update` property | T |
| REQ-5 | `AssetDetailAnnotateOrphanTest` (BT) | `annotate_should_BeDisabledWithReason_When_AssetHasNoPageUuids` | Unit | m-finding; gap 7; ED-8 (disabled control with visible reason, not a snackbar) | T |
| REQ-5 | `AreaCloseTolerancePropertyTest` (CT) | `areaClose_should_CloseWithinTolerance_When_AnyPointOnRingNearFirst` | Unit | Gap 8 property | T |
| REQ-5 | `InProgressAnnotationCanvasGestureTest` (AUT, extended) | `pointerCancel_should_DiscardPressWithNoPoint_When_SystemCancelsGesture` | Unit | Gap 8 `finally` path | T |
| REQ-5 | `CaptureToEditorTest` (APP) | `capture_should_OpenEditorWithImage_When_CameraStubFileReturned` | Integration | Gap 7 | n/a |
| REQ-5 | `AnnotationLifecycleResourceTest` (AIT) | `coldOpen_should_DecodeMainImageOnce_When_AspectSettles` | Integration | m4 decode count 1 (baseline 2) | T |
| REQ-5 | Review traceability (manual checklist C1..m8 -> story -> test) | `featuresReview_should_MapEveryBlockerCriticalToTestOrFollowUp_When_Phase6Closes` | Integration | SM5 closure | n/a |
| REQ-6 | `CoilSeamTest` (BT) | `loaderBuilder_should_BeBehaviourallyIdentical_When_EventListenerFactoryNull` | Unit | Seam is no-op; `SteleKitImageLoaderMemoryTest` (JT, existing) still passes | T |
| REQ-6 | `CoilSeamTest` (BT) | `resetSharedImageMemoryCacheForTest_should_EmptyCache_When_3EntriesHeld` | Unit | `size == 0` | T |
| REQ-6 | `CoilDecodeStats` via `AnnotationPlacementResourceTest` (AIT) | `decodeStats_should_RecordMaxBytesAndCacheHits_When_ListenerFactoryInstalled` | Integration | Allocation audit per path | n/a |
| REQ-6 | `LoupeStrategySpikeTest` (AIT, spike harness not a gate) | `loupeStrategies_should_ProduceAdr003Table_When_5RunsEachOn100Presses` | Integration | A.5a table: CPU/press, main ms/press, p95, max decode, graphics PSS | n/a |
| REQ-6 | `ImageSamplingTest` (CT, extended) | `quantize_should_YieldAtMost4DistinctSizes_When_10PctResizeSweep` | Unit | Quantization property | T |
| REQ-6 | `LoupeDegradationTest` (AUT) | `loupe_should_UseLevel1OrHigherAndShowChipOnce_When_RunningCriticalDelivered` | Unit | A.5 degradation | T |
| REQ-6 | `AnnotationLifecycleResourceTest` (AIT) | `loupeDecode_should_StayAtOrBelow2MiB_When_48MpSource100Presses` | Integration | Gate after spike chooses strategy; before that report-only | P (full-size decode) / M `loupeFullDecode` |
| REQ-7 | `bench-budget-check.test.js` (SCR) | `validate_should_RejectModeOtherThanGateOrReportOnly_When_BudgetsFileParsed` | Unit | Schema | T |
| REQ-7 | `ProbeReporter` + local `connectedDebugAndroidTest` (AIT) | `reporter_should_WriteProbeRecordFileOnHost_When_LocalRunFinishes` | Integration | B.4a no-logcat channel | n/a |
| REQ-7 | `ResourceProbeSelfTest` (AIT) | `frameSampler_should_ReportP50P95P99AndJankyPct_When_120FramesSampled` | Integration | FrameMetrics, not Choreographer | n/a |
| REQ-7 | `ResourceProbeSelfTest` (AIT) | `deviceProfile_should_AttachMemoryClassAndLargeHeapFalse_When_RecordEmitted` | Integration | ADR-009 | n/a |
| REQ-8 | `NavCommandsTest` (CT) | `navCommands_should_NavigateToLibrary_When_QueryLibraryFirstResultActivated` | Unit | D.5 | T |
| REQ-8 | `NavCommandsTest` (CT) | `navCommands_should_DeriveGoToCommand_When_NewSpecAddedInTest` | Unit | No drift | T |
| REQ-8 | `WiringCompletenessTest` (BT, existing, extended) | `wiring_should_ExpectMoreSheetTriggerAndAnnotatePicker_When_D4cAndD5Added` | Integration | Wiring gate | T |
| REQ-8 | `MoreSheetShellNonRegressionTest` (BT, validation-added) | `desktopAndIosShells_should_KeepSidebarDrawer_When_MoreSheetIntroduced` | Integration | Non-regression | T |
| REQ-8 | `NavAccessibilityTest` (AUT) | `sheet_should_NotClipDescriptionsBeyond2Lines_When_FontScale2At411dp` | Unit | D.10 | T |
| REQ-8 | `LibraryTipTest` (AUT) | `tip_should_ShowOnceAndPersistDismissal_When_FirstLibraryOpen` | Unit | D.9 | T |
| REQ-8 | `LibraryTipTest` (AUT) | `tip_should_ShowInSessionAndLogFailure_When_PreferenceWriteFails` | Unit | Error path | T |
| REQ-8 | `LibraryAnnotatedJoinTest` (BT) | `page_should_MarkExactly7Of100AnnotatedFromTheSameQuery_When_7Annotated` | Integration | D.7a: one query per page, no N+1 (recording repository) | T |
| REQ-8 | `LibraryAnnotatedJoinTest` (BT) | `page_should_ReturnLeftAndShowErrorStateWithRetry_When_JoinQueryFails` | Integration | Error path (UX-17) | T |
| REQ-8 | `LibraryCardSemanticsTest` (AUT) | `cards_should_HaveDistinctDescriptionsWithDateAndCount_When_TwoCardsRendered` | Unit | D.6b/UX-16 | T |
| REQ-8 | `LibraryScreenFilterTest` (AUT) | `filters_should_CombineWithAndAndClearOnRequest_When_AnnotatedImagesTagAndSearchApplied` | Unit | D.6a; parity rows: sort, tags, search, type, Orphaned, status line | T |
| REQ-8 | `LibraryViewModelStateTest` (BT) | `state_should_KeepLatestFilterAndPreviousListVisible_When_FilterChangedDuringLoad` | Unit | D.7b | T |
| REQ-8 | `LibraryEmptyStateTest` (AUT) | `emptyStates_should_OfferActionsAndOmitImportFromGalleryStub_When_NoItems` | Unit | D.6c | T |
| REQ-9 | `AnnotationPlacementResourceTest` (AIT) | `rapidPlacement_should_ReportP95FrameAndJankyPct_When_100Presses` | Integration | Frame SLO vs `kmp/TESTING_README.md`; report-only until B.7 | n/a |
| REQ-9 | `AnnotationPlacementResourceTest` (AIT) | `cpuVsAnnotationCount_should_ReportSlopeAtMost1p2x_When_n50Over_n10` | Integration | A.4b target (baseline +43%) | R / M `perDrawMeasure` |
| REQ-9 | `LargeSourceFrameTest` (AIT, in `AnnotationLifecycleResourceTest`) | `placement_should_NotRegressJank_When_AfterFixVsBaselineMedianN10` | Integration | No-regression compare against A.3e noise band | n/a |
| REQ-10 | `AnnotationLifecycleResourceTest` (AIT) | `largeSource_should_DecodeAtMostMaxDecodePixelsTimes4_When_8000x6000Exif6Open20Presses` | Integration | `maxDecodeBytes <= MAX_DECODE_PIXELS*4` | M `loupeFullDecode` |
| REQ-10 | `AnnotationLifecycleResourceTest` (AIT) | `largeSource_should_NotThrow_When_HeicWebpAndSafStyleUriOpened` | Integration | Error/format path | n/a |
| REQ-10 | `LargeLibraryBatchingTest` (BT) | `library_should_QueryOnlyLimitAtMost100AndNeverGetAll_When_8030RowsScrolledToEnd` | Integration | D.7 bounded reads | T (red on standing `getAllImageAnnotations`) |
| REQ-10 | `LargeLibraryBatchingTest` (BT) | `library_should_EmitAtMost100RowsPerEmission_When_5sOfWrites` | Integration | Standing observer bound | T |
| REQ-10 | `LargeLibraryBatchingTest` (BT) | `library_should_OrderGloballyInSql_When_SortOrderChangedAcross8030Rows` | Integration | No `sortImages` over full list; tags via `getDistinctAnnotationTags()` | T |
| REQ-10 | `QueryPlanAuditTest` (JT, extended) | `queryPlan_should_UseIndexNotScan_When_PagedFilteredAndJoinedPerLibrarySortOrder` | Integration | Plan audit for each sort and filter (Annotated, type, Orphaned, tag, search prefix) | T |
| REQ-10 | `UpgradeResilienceTest` (existing, extended) | `flowReads_should_ReturnLeftNotCrash_When_DbClosedForNewImageAnnotationReads` | Integration | `catchDbError` on new flows | T |
| REQ-11 | `ProbeRecordPrivacyTest` (BT/CT, validation-added) | `record_should_ContainNoPathOrContent_When_ProbeRecordSerialized` | Unit | Dimensions/bytes/thread names only | T |
| REQ-11 | `EditorDiagnosticsSamplerTest` (AUT) | `sample_should_OmitImagePaths_When_DiagnosticsLineFormatted` | Unit | Privacy of persisted logs | T |
| REQ-11 | `benchdiff-export.test.js` (SCR) | `export_should_UploadNoFieldOutsideAllowlist_When_AndroidResourceKindRun` | Unit | Allowlist of fields | T |
| REQ-12 | `benchdiff-export.test.js` (SCR) | `export_should_MapToGroupAndroidAnnotateResourcesLowerBetterMs_When_RapidPlacementCpu142` | Unit | Happy | T |
| REQ-12 | `benchdiff-export.test.js` (SCR) | `export_should_SkipMalformedRecordAndExitNonZero_When_InputLineInvalid` | Unit | Error path | T |
| REQ-12 | `android-benchmark-summarize_test.py` (SCR) | `summarize_should_WriteHistoryIdenticalInShapeToInlineStep_When_AndroidBenchFixtureLines` | Unit | Regression of extracted summarizer | T |
| REQ-12 | `android-benchmark.yml` CI (sticky comment `android-resource-benchmark`) | `ciRun_should_PostStickyComment_When_PrTouchesAitPerformance` | Integration | Observed once in B.6a PR | n/a |
| REQ-13 | `ExitRecordFormatterTest` (CT) | `format_should_ContainReasonImportancePssDescriptionVerbatim_When_Reason9Importance400` | Unit | `EXCESSIVE_RESOURCE_USAGE`, `importance=CACHED`, `pss=205MB` | T |
| REQ-13 | `ExitRecordFormatterTest` (CT) | `format_should_NeverThrowAndNameUnknown_When_AnyImportanceInt` | Unit | Property over ints | T |
| REQ-13 | `EditorMemoryPressureTest` (BT) | `trim_should_RaiseLoupeLevelAndLogEditorTrimLine_When_RunningCriticalDelivered` | Unit | Typed `MemoryPressureLevel` | T |
| REQ-13 | `MemoryPressureListenersTest` (BT, extended) | `listeners_should_DeliverLevelAndKeepNoArgOverload_When_Registered` | Unit | A.2e back-compat | T |
| REQ-13 | `EditorDiagnosticsSamplerTest` (AUT) | `sampler_should_AppendOneBurstSampleWithTop3ThreadGroups_When_6PlacementsIn2s` | Unit | Fake clock | T |
| REQ-13 | `EditorDiagnosticsSamplerTest` (AUT) | `sampler_should_StopWritingAndCostUnder1PctCore_When_EditorClosed` | Unit | Lifecycle | T |
| REQ-13 | `PreviousSessionLogReplayTest` (BT/AUT, existing, extended) | `replay_should_ListUpTo10ExitRecordsNewestFirst_When_Api30Plus` | Integration | Logs screen content | T |
| REQ-14 | `bench-budget-check.test.js` (SCR) | `rollback_should_PassCheck_When_AffectedBudgetsSetBackToReportOnly` | Unit | One-file rollback | T |
| REQ-14 | `bench-budget-check.test.js` (SCR) | `seam_should_BeNoOpByDefaultAndUnreadInRelease_When_MutationArgsAbsent` | Unit | B.5d no-op check | T |
| REQ-14 | `Mutations` guard (AIT/AUT) | `mutationFlags_should_BeInertInReleaseBuild_When_ArgNotProvided` | Integration | Release has no mutation path | T |
| REQ-15 | `AnnotationEditorCrashGuardTest` (BT) | `editorScopes_should_AttachExceptionHandler_When_ScopesInstantiated` | Unit | CLAUDE.md scope rule | T |
| REQ-15 | `AnnotationPersistenceQueueTest` (BT) | `queue_should_CloseScopeAndRejectLaunch_When_CloseCalled` | Unit | Owns scope + `close()` | T |
| REQ-15 | `MeasurementAnnotationActorRoutingTest` (BT) | `repository_should_MapExceptionToWriteFailedLeft_When_ActorOpThrows` | Unit | Arrow Either / `DomainError.DatabaseError.WriteFailed` | T |
| REQ-15 | `TestPlacementAuditTest`, `TestHygieneAuditTest` (existing) | `audits_should_PassWithNewClasses_When_PlacedPerClaudeMdRules` | Integration | No `Thread.sleep` in BT/JT; no AIT import into JT | gate |
| REQ-15 | `AllBusinessTestsCompletenessTest` (existing) + `check-bazel-test-listing.sh` | `completeness_should_PassAndListEveryNewClass_When_BusinessTestsRun` | Integration | Story B.8; fails if a class is unregistered | gate |
| REQ-15 | `WiringCompletenessTest` (BT) | `wiring_should_PassWithNewDestinations_When_NavSpecAdded` | Integration | Wiring gate | gate |
| REQ-15 | `FirstOpenSmokeCatalogTest` (existing) | `catalog_should_AgreeWithTapConstants_When_LabelsRenamed` | Integration | D.8 | T |
| REQ-15 | `AnnotationRapidPlacementSmokeTest` (APP) | `crashClass_should_RunOnAndroidNotJvm_When_ScenarioBRuns` | Integration | Crash class cannot be JVM-only | n/a |
| REQ-11 | `check-artifact-privacy.test.js` (SCR) | `privacy_should_Exit1NamingFile_When_ArtifactHistoryOrCommentContainsRealPath` | Unit | B.6d: planted `/home/alice/Pictures/IMG_0001.jpg`, `content://media/...`, `/storage/emulated/0/DCIM/x.jpg`, EXIF GPS; clean run exits 0 | T |
| REQ-11 | `check-artifact-privacy.test.js` (SCR) | `privacy_should_BlockUploadAndComment_When_ScanFails` | Unit | Workflow order: scan precedes `upload-artifact` and the sticky comment (workflow lint plus fixture) | T |
| REQ-15 | `RequiredCheckVerification` (manual, B.6e) | `generatedSourcesJob_should_BeRequiredOrFallbackInForce_When_ReadBackWithGhApi` | Integration | 2026-11-08 reading: rulesets `[]`, branch protection 404 = not required; local `git diff --exit-code kmp/src/generated/` + `gh pr checks` fallback | n/a |
| REQ-15 | `CalibrationBasisDowngradeTest` (BT) | `olderSchema_should_ReadAndWriteAnnotations_When_AddedColumnPresent` | Migration | C.1f downgrade | T |
| REQ-5 | `PlacementGestureClassifierTest` (CT) | `classify_should_MapEveryPressToExactlyOneOutcome_When_AnyDurationSlopAndPointerTypeGenerated` | Unit | UX-49 property over durations (below, at and above 120 ms; no upper limit), slops (<= and > 8 dp), pointer types and off-canvas distances (inside and beyond the 12 dp tolerance); quick tap = crosshair, hold/drag = aim, off-canvas = cancel | T |
| REQ-5 | `InProgressCancelTest` (AUT) | `cancel_should_ClearOnlyUnfinishedPointsAndBeAbsentWithNone_When_PointsPlaced` | Unit | UX-50 | T |
| REQ-5 | `EditorLoadingStateTest` (AUT) | `editor_should_DisablePlacementShowStillOpeningAt3sAndErrorAt15s_When_MainImageSlowOrFails` | Unit | UX-48; cached placeholder skips spinner | T |
| REQ-5 | `EditorFocusOrderTest`, `EditorTouchTargetAuditTest`, `LiveRegionPolitenessTest` (AUT) | `editor_should_FollowS18OrderAndHave48dpControlTargetsAndPoliteLiveRegions_When_EveryStateRendered` | Unit | C.9h; UX-52; audited surface = controls only (ED-1) | T |
| REQ-5 | `HandleHitResolverTest` (CT), `CanvasHandleEquivalentPathTest` (AUT) | `resolve_should_PickAtMostOneHandleNearestWinsWithDeterministicTies_When_AnyTwoVerticesAndTouchPoint` | Unit | B-UX-2 property; and `equivalentPath_should_ExposeMoveVertexActionsAnd48dpButtons_When_VerticesCloserThan48dp` | T |
| REQ-5 | `TimedMessageAccessibilityTest`, `ReducedMotionTest` (AUT) | `timedMessages_should_PersistWithAccessibilityServiceAndSkipAnimation_When_ReducedMotion` | Unit | C.9i; UX-51; fake `AccessibilityManager` | T |
| REQ-5 | `CrosshairMoveVertexTest` (BT), `MoveVertexActionsTest` (AUT), `CrosshairKeyHandlerTest` (BT) | `moveVertex_should_ApplyOneUndoEntryOrRestore_When_NudgedViaCrosshairOrTalkBackAction` | Integration | C.8f, C.8g; OD-1 included | T |
| REQ-16 | Post-release soak (device evidence, W3) | `postRelease_should_ShowZeroBadExitReasons_When_10EditorSessionsOver14Days` | Device | SM6: counted from `DiagnosticsSample` lines; missing data = not satisfied | n/a |

## UX Acceptance Tests

Tool column: R = Robolectric `createComposeRule` (AUT, headless; preferred per CLAUDE.md), A = on-device instrumented (APP or AIT), M = manual checklist on Pixel_Fold AVD or owner phone, S = Roborazzi/JT screenshot (requires `scripts/jvm-display-check.sh`), N = node/BT pure test. No browser automation applies (Compose app); Playwright is not used.

| UX Criterion | Test File | Test Name | Tool | Steps |
|---|---|---|---|---|
| UX-01 State what Library contains and what Annotated narrows, from nav text alone | `SidebarSectionsTest` (AUT) + owner read | `sidebar_should_ShowLibraryDescriptionText_When_Rendered` | R + M | Render sidebar; assert the Library description is visible; owner (or fresh reader) states what Library holds without opening it |
| UX-02 Section order Daily, Connections, Graph (Library first in Graph); Developer only on Logs/Performance | `NavSectionBuilderTest` (CT) + `NavLabelUniquenessTest` (BT) | `builder_should_OrderSectionsAndGateDeveloper_When_ScreensVaried` | N | Build for Journals, Logs, Performance; assert order, presence and that no section holds one row whose label equals its header; `Screen.LibraryStats` is labelled "Graph Stats" and only `Screen.Library` is labelled "Library" |
| UX-03 Headline + <=10-word visible description | `NavSectionBuilderTest` (CT) + `SidebarSectionsTest` (AUT) | `spec_should_HaveVisibleDescriptionOfAtMost10Words_When_AllRowsRendered` | N + R | Assert word count and no reliance on tooltip |
| UX-04 Phone: More opens grouped sheet in one tap; any destination <= 2 taps | `MoreSheetTest` (AUT) | `moreSheet_should_ReachAnyDestinationIn2Taps_When_Width411dp` | R | Tap More, assert sheet (not drawer); iterate every spec, tap, assert screen |
| UX-05 Sheet dismiss by swipe/scrim/X/Back; focus returns to More | `MoreSheetTest` (AUT) | `moreSheet_should_DismissAndReturnFocus_When_SwipeScrimXBack` | R | Four dismissal paths; assert focus on More button |
| UX-06 Font scale 200%: descriptions <= 2 lines, rows >= 48dp | `NavAccessibilityTest` (AUT) | `rows_should_NotClipBeyond2LinesAndBe48dp_When_FontScale2` | R | `fontScale=2.0`, measure bounds |
| UX-07 TalkBack: headers as headings, rows in order, bottom bar announced once | `NavAccessibilityTest` (AUT) + device pass | `semantics_should_ExposeHeadingsThenRowsAndNullIconDescriptions_When_BarAndSheetInspected` | R + M | Semantics tree assertion; owner TalkBack pass on phone (real screen reader not automatable) |
| UX-08 Current destination identifiable without colour | `SidebarSectionsTest` (AUT) | `selectedRow_should_HaveSelectedSemanticsAndIndicatorShape_When_LibraryCurrent` | R | Assert `selected=true` and indicator node |
| UX-09 Palette lists Go to Library, Show annotated photos and Annotate a photo; Enter navigates | `NavCommandsTest` (CT) + palette UI test | `palette_should_ListThreeCommandsAndNavigate_When_EnterOnFirstResult` | N + R | Query "library"; Enter; assert `Screen.Library`; "Show annotated photos" asserts the Annotated filter with every other filter and the search cleared (LD-8). "Annotate a photo" hidden with reason on desktop/wasm |
| UX-10 Decision recorded with strings; title is one I18n edit | `ADR006GateCheck` | `adr006_should_ListMergeDecisionAndStringsAndMarkRejectedOptions_When_ReadBack` | M | ADR-006 DECIDED 2026-11-08; changing `NAV_LIBRARY` updates sidebar, sheet, palette and tests together |
| UX-11 Tip once, dismissible, persistence failure non-blocking | `LibraryTipTest` (AUT) | `tip_should_ShowOnceDismissViaGotItOrBackAndSurvivePrefFailure_When_FirstOpen` | R | Open Library twice; fake pref store throws |
| UX-12 Empty states offer Attach a file / Capture Photo / Clear filters | `LibraryEmptyStateTest` (AUT) | `emptyStates_should_OfferActions_When_NoItemsAndWhenFiltersMatchNothing` | R | Render empty and filtered-empty Library; assert buttons |
| UX-13 Load error: plain message + Retry re-runs load; Back available | `LibraryErrorStateTest` (AUT) | `error_should_ShowMessageAndRetryReinvokesLoad_When_LoadFails` | R | Failing fake repo; click Retry; assert load count 2 |
| UX-14 8,000+ rows scroll without stall; footer progress; page failure inline Retry | `LargeLibraryBatchingTest` (BT) + `LibraryScrollSwitchResourceTest` (AIT) | `library_should_ShowFooterAndInlineRetry_When_8030RowsAndPage2Fails` | N + A + M | Batching/limit assertions; AIT p95 frame and `jankyPct` report; owner visual check for "no visible stall" on phone |
| UX-15 Missing file: "File missing", Keep / Remove with confirmation | `LibraryMissingFileTest` (AUT) | `card_should_ShowFileMissingAndConfirmRemove_When_FileAbsent` | R | Row without file; tap; sheet; Remove asks confirmation. Implemented by D.6b |
| UX-16 Distinct card descriptions with date + count | `LibraryCardSemanticsTest` (AUT) | `cards_should_HaveDistinctDescriptions_When_TwoCards` | R | As D.6 AC |
| UX-17 Annotated badge icon+word, chip pages <= 100, join failure shows Library error state | `LibraryAnnotatedJoinTest` (BT) + `LibraryCardSemanticsTest` (AUT) | `badge_should_ShowIconAndWordAndChipPageAtMost100_When_7Annotated` | N + R | Badge semantics; chip pages; failed join query shows the error state with Retry |
| UX-18 Open annotation only on annotated images; Back returns to detail then Library | `AssetDetailOpenAnnotationTest` (AUT, validation-added) | `openAnnotation_should_AppearOnlyWhenAnnotatedAndBackReturnsToDetail_When_Tapped` | R | Annotated vs non-annotated detail; navigate and Back; Library filters kept |
| UX-19 Under pressure: can place; L1 chip once; L2 persistent chip + crosshair offered | `LoupeDegradationTest` (AUT) + `AnnotationPlacementResourceTest` (AIT) | `degradation_should_ShowL1ChipOnceAndL2PersistentChip_When_TrimLevelsDelivered` | R + A | Deliver RunningCritical twice; place point at each level. "Place at crosshair" action asserted only if OD-1 approved |
| UX-20 No mid-gesture change; no modal | `LoupeDegradationTest` (AUT) | `degradation_should_ApplyAtNextPressNeverMidGestureAndNeverModal_When_TrimDuringDrag` | R | Trim during drag; level unchanged until next press; no dialog node |
| UX-21 Editor error banner with Retry/Close editor; measurements remain | `AnnotationEditorCrashGuardTest` (BT) + `EditorErrorBannerTest` (AUT, validation-added) | `banner_should_ShowRetryAndCloseAndKeepMeasurements_When_ThrowableInScope` | N + R | OOM injected; assert banner actions and committed list intact. Implemented by A.0c; input stops after a handled `Error` |
| UX-22 Post-kill launch banner once, View log, no repeat | `PostKillBannerTest` (AUT, validation-added) + device kill | `banner_should_ShowOnceForRecordAndNotRepeat_When_ExitRecordPresent` | R + M | Fake exit record; relaunch twice. Real kill on phone via mutation `exitOnBackground`. Implemented by A.0b (PR-0) |
| UX-23 Logs exit line fields + copyable | `ExitRecordFormatterTest` (CT) + `LogsScreenCopyTest` (AUT, validation-added) | `logs_should_ShowExitFieldsAndCopyWithConfirmation_When_ReplayRendered` | N + R | Formatter fields; Copy action shows "Copied". Implemented by A.0b (PR-0) |
| UX-24 Back with only committed: no dialog | `UnsavedChangesDialogTest` (AUT) | `back_should_CloseWithoutDialog_When_OnlyCommitted` | R | As C.6 |
| UX-25 Back with N unfinished points: dialog, default focus Keep editing, tap-outside keeps | `UnsavedChangesDialogTest` (AUT) | `dialog_should_FocusKeepEditingAndKeepOnOutsideTap_When_NUnfinishedPoints` | R | 1 and 3 points; outside tap; Discard keeps committed |
| UX-26 Pending/failed save on leave: Saving... or retry banner, never silent loss | `AnnotationPersistenceQueueTest` (BT) + `LeaveWhileSavingTest` (AUT, validation-added) | `leave_should_WaitUpTo2sAndShowRetryOnFailure_When_QueueNonEmpty` | N + R | Slow then failing fake repo. Implemented by C.6b (depends on C.5a) |
| UX-27 Failed initial load: retry banner, no deletion | `EditorInitialLoadRaceTest` (BT) | `failedInitialLoad_should_ShowRetryBannerAndKeepRows_When_Left` | N | As REQ-5 error row; assert banner state |
| UX-28 Legacy calibration text badge; no stored change | `LegacyCalibrationFlagTest` (BT) + `LegacyBadgeTest` (AUT, validation-added) | `badge_should_ShowRecalibrateTextAndChangeNothing_When_BasisVersion0` | N + R | Text node present; DB value unchanged until user acts |
| UX-29 Export failure states specific message + action; Drive icon contrast | `AnnotationExportStatesTest` (AUT, validation-added) | `export_should_ShowSpecificMessageAndActionPerState_When_OfflineSignedOutOomMissingFile` | R + S | Each state incl. the offline message (OD-4 included, unconditional); contrast 4.5:1/3:1 via screenshot/colour-token check in both themes. Implemented by C.7a, C.7c |
| UX-30 4:1 panorama loupe inside canvas, no crash | `LoupeNarrowCanvasTest` (AUT) + `AnnotationRapidPlacementSmokeTest` (APP) | `loupe_should_StayInsideCanvasAndNotCrash_When_4to1Panorama` | R + A | Robolectric bounds; on-device 100 presses |
| UX-31 Press within 2dp of edge: filled loupe, crosshair under touch, aim reaches edge | `LoupeEdgeTest` (AUT) | `loupe_should_BeFilledAndAimReachEdge_When_PressWithin2dp` | R | Left/right/top/bottom presses |
| UX-32 First press shows placeholder | `LoupeEdgeTest` (AUT) | `loupe_should_ShowPlaceholderNotEmptyCircle_When_FirstPressBeforeDecode` | R | Delayed fake loader |
| UX-33 Crosshair mode: 10 Nudge Right + Place at expected x; buttons >= 48dp labelled | `CrosshairPlacementTest` (BT) + `PlaceAtCrosshairControlsTest` (AUT) | `nudge_should_PlacePointAt0p5Plus10OverWidthPx_When_10NudgeRightThenPlace` | N + R | Unconditional (OD-1 included 2026-11-08) |
| UX-34 Desktop arrow keys nudge, Shift = 10 px, Enter, Esc | `CrosshairKeyHandlerTest` (BT) | `keys_should_NudgeShiftTenPlaceEnterAndEscLeaves_When_DesktopKeyEvents` | N | Unconditional (OD-1/OD-2 included) |
| UX-35 One haptic + announcement per commit; <0.2% apart: Points too close, nothing created | `HapticWiringTest` (AUT) + `EditorStateAtomicityTest` (BT) | `commit_should_TickOnceAnnounceAndRejectCoincident_When_PointsUnder0p2Pct` | R + N | Commit and coincident cases |
| UX-36 Vertex handle >= 48dp, non-colour selected style, Delete/Undo visible when selected | `SelectHandlesTest` (AUT, validation-added) | `handles_should_Have48dpTouchAndRingStyleAndShowDeleteUndo_When_AnnotationSelected` | R | Assert bounds and semantics. Implemented by C.8e; non-drag Move vertex by C.8f (OD-4 included) |
| UX-37 Delete: 6 s Undo snackbar restores | `DeleteUndoSnackbarTest` (AUT, validation-added) | `delete_should_ShowSnackbarAndUndoRestoresWithin6s_When_AnnotationDeleted` | R | Virtual clock. Implemented by C.8e |
| UX-38 Drag off canvas on release cancels placement | `LoupeEdgeTest` (AUT) | `release_should_CancelPlacementWithNoPoint_When_FingerOffCanvas` | R | Unconditional (OD-3 included): off-canvas release cancels |
| UX-39 TalkBack: focusable button per annotation with Select/Delete | `AnnotationSemanticsTest` (AUT) + device pass | `semantics_should_ExposeSelectAndDeleteActions_When_3Committed` | R + M | Semantics actions incl. "Move vertex 1/2" (C.8f); real TalkBack pass on phone |
| UX-40 No dead ends: every banner/dialog/sheet/snackbar has action/dismiss + Back | `DeadEndAuditTest` (AUT, validation-added) | `everySurface_should_OfferActionOrDismissAndBack_When_S1ToS15Enumerated` | R + M | Enumerate each surface in ux.md S1-S15; manual walkthrough checklist |
| UX-41 Contrast 4.5:1/3:1 light+dark, reduced motion | `ThemeContrastTest` (JT/AUT, validation-added) | `tokens_should_MeetContrastAndSkipAnimation_When_LightDarkAndReducedMotion` | S + R + M | Colour-token contrast computation for new tokens; reduced-motion flag disables sheet/chip/shimmer animation; manual visual pass. Implemented by C.9d |
| UX-42 Library filters AND, Annotated chip pages <= 100, status line, Clear filters | `LibraryScreenFilterTest` (AUT) | `filters_should_CombineWithAndAndAnnounceCountAndClear_When_ChipsAndSearchApplied` | R | 100 items, 7 annotated; combos; live region text |
| UX-43 Tap rule and badge | `LibraryScreenFilterTest` + `LibraryCardSemanticsTest` (AUT) | `tap_should_OpenEditorForAnnotatedImageAndDetailForOthers_When_CardsTapped` | R | Annotated image, plain image, PDF; overflow offers Details |
| UX-44 Tip once for fresh install; upgrade notice once for existing | `LibraryUpgradeNoticeTest`, `LibraryTipTest` (AUT) | `onboarding_should_ShowExactlyOneOfTipOrNoticeOnceAndSurvivePrefFailure_When_FirstLibraryOpen` | R | `navIaVersion` absent with and without a registered graph |
| UX-45 Old routes land on Library, Back correct | `LibraryRedirectTest` (BT) | `navigate_should_LandOnLibraryWithFilterOrSearchAndKeepBackStackClean_When_OldRoutesUsed` | N | Gallery, AssetBrowser("kitchen"), global-search "Show all" |
| UX-46 First-open task test | `NavFirstOpenTaskTest` (AUT) + owner phone run | `firstOpen_should_ReachAnnotatedPhotosAndFileAttachmentWithZeroWrongTaps_When_NavOnly` | R + M | Desktop sidebar and 411 dp shell; owner repeats once on the phone |
| UX-47 Add menu, no stub | `LibraryEmptyStateTest` (AUT) | `addMenu_should_OfferCaptureAttachAndAndroidOnlyPicker_When_Opened` | R | Platform variants; no "Import from Gallery" node |
| UX-48 Editor loading state | `EditorLoadingStateTest` (AUT) | `editor_should_DisablePlacementShowStillOpeningAt3sAndErrorAt15s_When_MainImageSlowOrFails` | R | Virtual clock; cached placeholder skips spinner; Back works |
| UX-49 Tap vs drag | `PlacementGestureClassifierTest` (CT) + `LoupeEdgeTest` (AUT) | `gesture_should_MoveCrosshairOnQuickTapShowLoupeOnHoldCancelOffCanvas_When_PressesInjected` | N + R | Table in ux.md S17 (quick tap < 120 ms and <= 8 dp = crosshair; hold or move = aim; no upper time limit) |
| UX-50 In-progress Cancel | `InProgressCancelTest` (AUT) | `cancel_should_ClearOnlyUnfinishedPointsAndBeAbsentWithNone_When_PointsPlaced` | R | Visible, labelled "Clear points", >= 48 dp; assert no control is labelled a bare "Cancel" or "Done" (ED-2) |
| UX-51 Accessibility timeouts | `TimedMessageAccessibilityTest` (AUT) | `timedMessages_should_PersistAtLeastRecommendedTimeoutAndNeverDismissActions_When_ServiceOn` | R | Fake `AccessibilityManager` |
| UX-52 Editor focus order and targets | `EditorFocusOrderTest`, `EditorTouchTargetAuditTest` (AUT) + device pass | `editor_should_FollowS18OrderAndHave48dpTargets_When_EveryStateRendered` | R + M | Owner TalkBack pass on phone |
| UX-53 Developer section no layout shift | `SidebarSectionsTest` (AUT) | `developerSection_should_NotMoveRowsAbove_When_ShownForLogs` | R | Bounds equal for Journals vs Logs |
| UX-54 Orphan Annotate | `AssetDetailAnnotateOrphanTest` (BT) + `LibraryCardSemanticsTest` (AUT) | `annotate_should_BeDisabledWithReasonLineAndOpenPagesButtonAndNeverOpenEditor_When_AssetHasNoPage` | N + R | Card overflow, detail and palette entry points (ED-8) |
| UX-55 Handedness setting | `LoupeEdgeTest` (AUT) + settings test | `magnifierSide_should_MirrorOffsetAndBeAtSettingsAnnotation_When_LeftSelected` | R | Settings > Annotation > Magnifier side; overflow shortcut |

## Test Stack
- **Unit (pure)**: `kotlin.test` + `kotest-assertions-core` + `kotest-property` (`Arb`/`checkAll` inside `runTest`) in `commonTest`; no Kotest runner (CLAUDE.md). Node `--test` for `scripts/*.test.js`, `pytest`/`unittest` for `android-benchmark-summarize_test.py`.
- **Integration (JVM, no UI)**: `businessTest` (in-memory/SQLDelight JDBC, recording `DatabaseWriteActor`, `TestDispatcher`); `jvmTest` only for real-renderer (`AnnotationExporterTest`, `QueryPlanAuditTest`) behind `scripts/jvm-display-check.sh`.
- **Compose behaviour**: `androidUnitTest` Robolectric (headless, no display), per CLAUDE.md placement rule. Roborazzi goldens (Gradle-only) for the sidebar change.
- **On-device**: `androidInstrumentedTest` (`ResourceProbe`, `DirectTouchInjector`, UiAutomator `pressHome`) and `androidApp/src/androidTest` smoke tests via Gradle `connectedDebugAndroidTest`; no logcat (read `kmp/build/outputs/androidTest-results/connected/debug/*.xml` and the probe record file).
- **E2E / UX**: Robolectric semantics tests where possible, on-device manual checklist on Pixel_Fold AVD, owner phone for TalkBack and real-kill checks.
- **Bazel registration (Story B.8)**: new `commonTest` class -> `business_common_tests` srcs in `kmp/src/commonTest/kotlin/BUILD.bazel` and `AllBusinessTests.kt`; new `businessTest` class -> `AllBusinessTests.kt`; `androidUnitTest` classes globbed. Verify with `scripts/check-bazel-test-listing.sh <classes>`. Validation-added classes that need registration: `CalibrationBasisMigrationTest`, `MoreSheetShellNonRegressionTest`, `DepthEstimationCoordinatorTest`, `ProbeRecordPrivacyTest` (all businessTest, or commonTest for the last, then both lists).
- **Test-hygiene audits**: `TestPlacementAuditTest` (no AIT imports into JT; commonMain-only logic in commonTest), `TestHygieneAuditTest` (no `Thread.sleep`; use `awaitIdle`/virtual time; the WeakReference GC loop lives in AIT only).

## Coverage Targets and How to Measure

| Stack | Coverage command | Target |
|---|---|---|
| Kotlin/JVM (businessTest) | `bazel test //kmp:business_tests --test_output=all`; gradle `./gradlew :kmp:jacocoTestReport` (if configured) -> `kmp/build/reports/jacoco/` | >=80% line on new classes (`UndoHistory`, `AnnotationPersistenceQueue`, `NavSection`, `ExitRecord`, `ProcStatParser`, `NativeImageSize`, `CrosshairPlacementState`) |
| Robolectric | `bazel test //kmp/src/androidUnitTest/kotlin:android_unit_tests --config=android` | Every class named in the UX table appears in the output |
| Node scripts | `node --test scripts/*.test.js` | All branches of gate/report-only/invalid-device/promotion |
| Instrumented | `./gradlew :kmp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<comma list>` on Pixel_Fold AVD | All listed scenario classes produce records; all gates have a recorded red run |
| Workflow lint | `actionlint -color`; `uvx 'zizmor==1.25.2' .` | Clean |
| SQLDelight | regenerate + `git diff --exit-code kmp/src/generated/` | Clean after C.1f, D.7c, D.7a (each split into a mechanical `-gen` PR when its split trigger fires) |
| Registration | `scripts/check-bazel-test-listing.sh` (no args derives from `git diff`) | Every new test class listed |

- All public service methods: happy path + error path covered (REQ-5, REQ-13, REQ-15 rows).
- All external integrations (Coil, Android process APIs, Drive export): unit with seam/fake plus at least one on-device integration test.
- UX acceptance criteria: each of UX-01..UX-82 has a test row or manual step above.

## Owner / Device Evidence Only (not automatable)

| Item | Why it cannot be automated | Evidence required | Owner |
|---|---|---|---|
| Owner's `Previous process exit: ... importance=<n> <description>` line (W1) | The kill never reproduced in CI or on the AVD (cpu.md); the stock excessive-CPU rule applies only to cached/HOME processes and OEM watchdogs cannot be reproduced; only the owner's phone has the record | Pasted Logs line, phone model, Android version, foreground/background at death, charging; fills the A.1 triage table | Tyler |
| "Fixed" (vs "mitigated and monitored") claim for the crash (REQ-1) | Requires the W1 line plus a post-fix soak on the real device (W3 dogfood) | Owner confirms no recurrence across a soak period; until then only the mitigated/monitored vocabulary is allowed | Tyler |
| SM1 literal "automated test reproduces the pre-fix failure" | Failure does not reproduce, so no test can be red on unfixed code for the CPU kill itself | Owner ACCEPTED the substitute on 2026-11-08 (OD-5, requirements.md Amendment 2 item 1): gate red on revert/mutation per Story B.5 matrix, positive control A.3d, PR-0 diagnostics, "mitigated and monitored" wording | Tyler (decided) |
| Calibration-basis schema migration (OD-6) | Requirements Out of Scope forbade schema changes unless the crash needs them | ALLOWED by the owner on 2026-11-08 (requirements.md Amendment 2 item 2); the migration, schema-sync and downgrade tests are the evidence | Tyler (decided) |
| Calibration of noisy budgets over >= 10 CI runs (W5, B.7b) | Needs runs to accrue on the merged harness on the real 16 GB runner; cannot be simulated locally | `calibratedFromRuns >= 10`, zero `invalid-device` runs, promotion PR | Agent after CI window |
| Physical low-RAM device memory (W4) | AVD ignored `-memory 2048`; swiftshader hides hardware-bitmap pressure | Phone/Firebase Test Lab probe run; until then memory metrics beyond deterministic gates stay `ReportOnly` | Tyler (device), agent (script) |
| CI emulator RAM pin | Only observable on the hosted runner | One CI dry run showing guest `MemTotal` within 10% of `ram-size` | Agent |
| ADR-006 IA decision (UX-10, D.1) | Product decision | DECIDED 2026-11-08: merge into one Library; ADR-006 Accepted; only the working title can still change (one I18n edit) | Tyler (decided) |
| First-open task on the owner's phone (UX-46, SM4) | A real person's wrong taps are not automatable | One owner run: locate annotated photos and a file attachment from the nav alone, wrong taps counted; compared with the mandatory before-run of `NavFirstOpenTaskTest` on commit `408907d9cd` (D.9c) | Tyler |
| Post-release outcome (REQ-16, SM6) | Needs real dogfooding time on the owner's phone | >= 10 editor sessions over >= 14 days, zero bad exits (any abnormal non-user exit after an editor session, requirements Success Metric 6) in `PreviousSessionLog` (sessions counted from `DiagnosticsSample` lines; missing data = not satisfied); evaluated by the owner on day 14 from the in-app Logs screen, one 14-day extension if fewer than 10 sessions, then an owner decision; the owner's real kill frequency is unknown, N = 10 is a starting value | Tyler |
| PR-0 release cut (W9, A.0d) | The standing release-please PR must be merged and the release workflow run | Release PR merged, workflow green, APK attached; version recorded | Tyler (or authorised dispatch) |
| Required status check for `SQLDelight generated sources` (B.6e, W10) | Repository settings; the 2026-11-08 reading shows no ruleset and an unprotected `main` | Owner adds the ruleset (optional); until then the local diff + `gh pr checks` fallback is in force | Tyler (optional) |
| OD-1..OD-4 (UX-33/34/36/38/39/29) | DECIDED 2026-11-08 (included) | Recorded in C.8d; tests are unconditional | Tyler (decided) |
| TalkBack reading order, editor focus order, Move vertex actions, accessibility-timeout snackbars, bottom-bar single announcement (UX-07, UX-39, UX-51, UX-52) | Robolectric asserts semantics only; real screen reader behaviour and OEM TalkBack differ | Manual pass on the owner's phone against ux.md S18 | Tyler |
| "No visible stall" scrolling 8,000+ rows (UX-14) and perceived loupe quality | Subjective frame feel; automated p95/jank is report-only | Owner visual check plus recorded p95 | Tyler |
| Real process kill banner (UX-22) | Real `getHistoricalProcessExitReasons` data needs an actual OS kill (mutation `exitOnBackground` approximates it on the AVD) | Phone relaunch after a real kill | Tyler |
| `ux:review` verdict (SM4) | Judgement-based skill run | Report with zero MAJOR labelling findings | Agent |
| PR #560 merge before B.6c (W6) | External PR (OPEN on 2026-11-08); the loupe work does not wait: A.3f rebases, stacks on #560, or lands it first | Merge confirmation for B.6c only | Tyler |
| Legacy instrumented tests not in CI (`AssetPipelineHeapGateTest`, `AssetBrowserE2ETest`, `AssetBrowserIndexedE2ETest`) | Deliberately out of scope, recorded as debt | Follow-up issue | Tyler |
| **Cold-user sessions and editor-gesture usability checks (W11, task D.9e, ux.md ED-17)** | `NavFirstOpenTaskTest` (Robolectric driver) proves only that a zero-wrong-tap path exists, not that a person finds it; the owner wrote the IA | 3 to 5 sessions with people who have not seen the app, on the phone shell, think-aloud: find annotated photos, find a file attachment, place a two-point measurement (quick tap, hold, Crosshair), clear an accidental point, re-find Library after dismissing the notice (one session). Record wrong taps and time to first correct tap in the results table below. Pass: >= 4 of 5 find both destinations with <= 1 wrong tap; >= 4 of 5 place a correct measurement unaided within 2 minutes; >= 4 of 5 find Clear points. Failures become I18n/hint edits (Annotated vs measured, the Graph section name with fallback "Files and graph", thresholds) and the failing sessions are repeated | Tyler (or recruited users) |
| Pixel_Fold fold/unfold/rotate pass (UX-61, UX-62, ED-7) | Robolectric changes qualifiers but not the real posture change or the real inner/cover display | One pass on the owner's Pixel Fold or the Pixel_Fold AVD: editor with unfinished points, crosshair, selection and a dialog open; Library with filters, a sheet and scroll position; fold, unfold, rotate; record what survived | Tyler |
| Reduced-motion signal (ED-15) | `ANIMATOR_DURATION_SCALE` as the "Remove animations" signal is INFERRED from platform behaviour | On device: enable Remove animations, confirm the provider reports reduced motion and the sheet, chip, badge and loupe dim do not animate | Agent (AVD) or Tyler |
| Real TalkBack / switch access auto-offer of Crosshair mode (UX-64) | Robolectric fakes the accessibility service | Phone pass: with TalkBack on, the editor opens in Crosshair mode and announces positions | Tyler |

| Real system back gesture versus cancel-by-drag (UX-77, ED-20) | Robolectric cannot inject the OS back gesture or `systemGestureExclusion` limits | On the phone with gesture navigation: drag a placement toward the left and right edges (no Back navigation, no point) and down onto the toolbar (cancel feedback, no point) | Tyler |

## Gaps found in the plan (resolved in Repair 2; Repair 3 notes below)
- The nine UX criteria that had tests but no implementing task now have one: UX-15 -> D.6d, UX-21 -> A.0c, UX-22 and UX-23 -> A.0b, UX-26 -> C.6b, UX-29 -> C.7c, UX-36 and UX-37 -> C.8e, UX-41 -> C.9d. Their validation-added tests are expected red until those tasks land.
- Former owner-conditional items are now unconditional (OD-1 included 2026-11-08; OD-2..OD-4 included with it): UX-33, UX-34, UX-38, UX-36 non-drag move, UX-39 Move vertex, UX-29 offline copy, UX-19 "Place at crosshair" offer. Their tests are no longer droppable.
- `migration_should_be_reversible` was not in the plan; added as `CalibrationBasisMigrationTest` for C.1d (and index migration D.7c covered by the extended `MigrationRunnerSchemaSyncTest`).

## UX criterion -> implementing task (every UX-01..UX-41 maps to a task)

| UX | Task(s) | UX | Task(s) |
|---|---|---|---|
| UX-01..03 | D.2, D.3 | UX-22, UX-23 | A.0b |
| UX-04, 05 | D.4 | UX-24, 25 | C.6a |
| UX-06, 07, 08 | D.3, D.4, D.10 | UX-26 | C.6b |
| UX-09 | D.5 | UX-27, 28 | C.5b, C.1c |
| UX-10 | D.1 | UX-29 | C.7a, C.7c (offline copy included) |
| UX-11 | D.9a | UX-30, 31, 32 | C.2b, C.9a |
| UX-12, 13, 16 | D.6c, D.6b | UX-33, 34 | C.8c, C.8g |
| UX-14 | D.7, D.6c | UX-35 | C.8b, C.9c |
| UX-15 | D.6b | UX-36, 37 | C.8e, C.8f (non-drag move) |
| UX-17, 18 | D.7a, D.6b, D.6d | UX-38 | C.9a, C.9f |
| UX-19, 20 | A.2c, A.5c | UX-39 | C.8a, C.8f |
| UX-21 | A.0c | UX-40 | D.9b, D.10a (audit) |
| UX-41 | C.9d, C.9i | UX-42 | D.6a |
| UX-43 | D.6b | UX-44 | D.9a, D.9c |
| UX-45 | D.11a, D.8c | UX-46 | D.9c |
| UX-47 | D.6c | UX-48 | C.9g |
| UX-49, 50 | C.9f | UX-51 | C.9i |
| UX-52 | C.9h | UX-53 | D.3a |
| UX-54 | C.9c, D.6d | UX-55 | C.9a |

## Repair 2 additions (tests added for pre-mortem and consistency fixes)

| Requirement | Test | Type | Pre-fix red |
|---|---|---|---|
| REQ-13 PR-0 forced-kill replay | `ForcedKillReplayTest` (AIT, two runs) | Device evidence | M `exitOnBackground` |
| REQ-3 day-one flake protection | `bench-budget-check.test.js` fixture with `REASON_OTHER`/`USER_REQUESTED`/other-pid exit entries ignored; `globalMode: report-only` never fails | Node | T |
| REQ-5 legacy display unchanged | `LegacyDisplayGoldenTest` (AUT), `AnnotationExporterTest` extension (JT), `LegacyCalibrationWritePathPropertyTest` (BT), `CalibrationBasisMigrationTest` downgrade case (BT) | R + N | T over pre-fix fixture DB |
| REQ-15 write ordering | `RealActorOrderingTest` (BT, real `DatabaseWriteActor` + LOW traffic), priority-wrapper test, queue `close()` vs actor shutdown | N | T / R |
| REQ-6 allocation audit | B.2e audit table in `research/architecture.md` | Review | n/a |
| REQ-14 no mutation in release | R8/dex-grep check that `Mutations` and flag names are absent from the release APK | Build check | M |
| REQ-8 persisted route ids | `PersistedScreenIdTest` (BT) | N | T |

New classes needing registration per Story B.8: `CalibrationBasisMigrationTest`, `LegacyCalibrationWritePathPropertyTest`, `RealActorOrderingTest`, `PersistedScreenIdTest` (all businessTest, `AllBusinessTests`); `LegacyDisplayGoldenTest`, `PostKillBannerTest`, `LogsScreenCopyTest`, `EditorErrorBannerTest`, `LeaveWhileSavingTest`, `AnnotationExportStatesTest`, `SelectHandlesTest`, `DeleteUndoSnackbarTest`, `ThemeContrastTest`, `GalleryMissingFileTest` are androidUnitTest (globbed).

## Repair 3 additions (owner decisions of 2026-11-08 and triad iteration 1)

- Renamed tests for the merged Library: `LibraryAnnotatedJoinTest`, `LargeLibraryBatchingTest`, `LibraryCardSemanticsTest`, `LibraryMissingFileTest`, `LibraryEmptyStateTest`, `LibraryErrorStateTest` replace the Gallery/Assets names; new `LibraryScreenFilterTest`, `LibraryViewModelStateTest`, `LibraryRedirectTest`, `LibraryUpgradeNoticeTest`, `NavFirstOpenTaskTest`.
- New classes needing registration per Story B.8: businessTest `LibraryAnnotatedJoinTest`, `LargeLibraryBatchingTest`, `LibraryViewModelStateTest`, `LibraryRedirectTest`, `CalibrationBasisDowngradeTest`, `CrosshairMoveVertexTest`, `CrosshairKeyHandlerTest` (all `AllBusinessTests`); commonTest `PlacementGestureClassifierTest` (`business_common_tests` srcs and `AllBusinessTests`); the androidUnitTest classes are globbed.
- New node tests: `scripts/check-artifact-privacy.test.js` (B.6d).
- Privacy: REQ-11 now covers uploaded failure artifacts, `benchmarks/**` history JSON and PR comments with a planted-path negative test.
- Required-check verification (B.6e): paste the `gh api` read-back; absence of a required check is expected today.
- Outcome measures: SM4 (first-open task test, UX-46) and SM6 (post-release soak, REQ-16).

## Repair 4 additions (triad iteration 2: filter semantics, touch-target audit, UX gaps)

New classes needing registration per Story B.8: commonTest `LibraryFilterRulesTest`, `LibraryFilterRulesPropertyTest`, `HandleHitResolverTest`, `PointerTypeGestureTest`, `LogRedactorTest`; businessTest `NavLabelUniquenessTest`, `LibraryFilterSqlParityTest`, `NudgeStepTest`, `EditorSnackbarQueueTest`, `EditorLoadGenerationTest`; the androidUnitTest classes below are globbed.

### Requirement -> Test Mapping (additions)

| Requirement | Test File (source set) | Test Name | Type | Scenario | Pre-fix red |
|---|---|---|---|---|---|
| REQ-4 / REQ-8 | `LibraryFilterRulesPropertyTest` (CT) | `rules_should_LeaveNoContradictionReachableByEnabledChipsAndNormalizeIdempotently_When_RandomTapSequences` | Unit | LD-1: Annotated, Orphaned, tags and non-image types; `normalize(normalize(s)) == normalize(s)` | T |
| REQ-4 / REQ-8 | `LibraryFilterRulesTest` (CT) | `disabledReason_should_NameTheReasonForEveryContradictionPair_When_StateBuilt` | Unit | Annotated+PDF, tag+Audio, tag+Video (dimmed); Annotated+Orphaned and tag+Orphaned allowed (LD-16); Type OR within the group | T |
| REQ-10 | `LibraryFilterSqlParityTest` (BT) | `sqlPage_should_EqualReferenceFilterIdsAndOrder_When_RandomItemSetsAndNormalizedStates` | Integration | LD-7: types OR, AND across groups, unannotated last for annotation sorts, id tiebreak, search by name/tag/label prefix | T |
| REQ-10 | `LibraryFilterSqlParityTest` (BT) | `count_should_CapAt10000AndPageNeverRepeatOrSkipARow_When_8030RowsPaged` | Integration | LD-5, LD-2 stable paging | T |
| REQ-8 | `LibraryScreenFilterTest` (AUT) | `status_should_AnnounceOnceAfter400msSettle_When_FiveChipTapsInside400ms` | Unit | LD-5 debounce, fake clock | T |
| REQ-8 | `LibraryScreenFilterTest` (AUT) | `dimmedChip_should_ExposeReasonAndShowItInStatusLine_When_TappedWhileConflicting` | Unit | LD-1 / UX-56 | T |
| REQ-8 | `LibraryCompactFilterTest` (AUT) | `bar_should_CollapseToFiltersButtonAndShowFirstCard_When_411dpAtFontScale2` | Unit | LD-6 / UX-58 | T |
| REQ-8 | `LibraryConfigChangeTest` (AUT) | `library_should_KeepFiltersSortSheetAndScrollAnchor_When_RotatedFoldedAndRestored` | Unit | LD-4, ED-7 / UX-62 | T |
| REQ-8 | `LibraryKeyboardNavigationTest` (AUT) | `grid_should_MoveFocusByArrowsAndRestoreToOriginatingCard_When_BackFromEditor` | Unit | LD-9 / UX-59 | T |
| REQ-8 | `LibraryOverflowAndDeleteTest` (AUT) | `delete_should_ConfirmNamingPagesAndMeasurementsAndShowRetryOnFailure_When_FileDeleted` | Unit | LD-10 / UX-70 | T |
| REQ-8 | `LibraryThumbnailPlaceholderTest` (AUT) | `placeholder_should_DifferFromFileMissingTileInTextTintAndDescription_When_ThumbnailLoadingUnderPressure` | Unit | ED-9 / UX-67 | T |
| REQ-4 | `LibraryNewBadgeTest` (AUT) | `badge_should_ShowOnExistingInstallUntilDismissedOrOpenedAndNeverOnFresh_When_NavRendered` | Unit | ED-16 / UX-68; preference failure non-blocking | T |
| REQ-4 | `NavLabelUniquenessTest` (BT) | `labels_should_BeUniqueAndNameLibraryStatsGraphStats_When_AllSpecsChecked` | Unit | Rename guard; `SidebarSectionsTest.graphStatsRename` asserts the rendered sidebar | T (red while `Sidebar.kt:346` says "Library Stats") |
| REQ-5 | `PointerTypeGestureTest` (CT) | `mouseAndStylusClick_should_PlaceAtPointerAtOnce_When_ClickWithinSlop` | Unit | ED-3b / UX-66 | T |
| REQ-5 | `CancelZoneFeedbackTest` (AUT) | `loupe_should_DimLabelAndHapticOnceAndPlaceAtEdge_When_DragLeavesBeyondAndWithinTolerance` | Unit | ED-4 / UX-57 | T |
| REQ-5 | `NudgeStepTest` (BT) | `steps_should_ConvertToAtLeastOneImagePixelAndRepeatWithAcceleration_When_Held` | Unit | ED-6 / UX-63 | T |
| REQ-5 | `AccessibilityAutoCrosshairTest` (AUT) | `editor_should_StartInCrosshairMode_When_TouchExplorationOrSwitchAccessEnabledAndSettingAuto` | Unit | ED-6 / UX-64 | T |
| REQ-5 | `EditorLoadGenerationTest` (BT) | `lateFailureOfOlderAttempt_should_NeverReplaceNewerSuccess_When_RetryPressedDuringDecode` | Unit | ED-13 / UX-60; "Still opening..." at 3 s and 15 s ceiling in `EditorLoadingStateTest` | T |
| REQ-5 | `EditorConfigChangeTest` (AUT) | `editor_should_KeepPointsCrosshairModeSelectionAndDialogs_When_RotatedFoldedUnfolded` | Unit | ED-7 / UX-61; in-flight press discarded | T |
| REQ-5 | `EditorSnackbarQueueTest` (BT) | `queue_should_NeverReplaceActionSnackbarCoalesceDeletesAndCapAt3_When_MessagesArrive` | Unit | ED-14 / UX-71 | T |
| REQ-5 | `EditorKeyboardFocusTest` (AUT) | `controls_should_ShowFocusRingOnKeyboardFocusOnly_When_TabbedThroughEditor` | Unit | ED-12 / UX-59 | T |
| REQ-5 | `AnnotationSettingsTest`, `EditorOverflowMenuTest` (AUT) | `settings_should_PersistAndDisableFingertipOffsetWithReason_When_QuickTapIsMoveCrosshair` | Unit | ED-10 / UX-69 | T |
| REQ-5 | `ReducedMotionTest` (AUT) | `provider_should_ReadAnimatorScaleOnAndroidAndSettingElsewhere_When_FakeProviderInjected` | Unit | ED-15 / UX-72 | T |
| REQ-11 | `LogRedactorTest` (CT), `LogRedactionTest` (AUT) | `redactForShare_should_RemovePathsUrisGraphAndPageNamesAndCopyOnlyVisibleExitBlock_When_PlantedValuesPresent` | Unit | A.0e: planted `/home/alice/Pictures/IMG_0001.jpg`, `content://media/...`, graph folder and page names; idempotent; nothing beyond what Logs displays | T |
| REQ-13 | `MemoryPressureListenersTest` (BT, extended), host mapping (A.2g) | `hostTrimMapping_should_DeliverTypedLevelToEveryExistingCaller_When_OnTrimMemoryCalled` | Unit | A.2g sweep | T |

### UX Acceptance Tests (additions, UX-56..UX-72)

| UX Criterion | Test File | Test Name | Tool | Steps |
|---|---|---|---|---|
| UX-56 Contradictory chips dimmed with reason | `LibraryScreenFilterTest`, `LibraryFilterRulesPropertyTest` | see above | R + N | As above |
| UX-57 Cancel-zone feedback | `CancelZoneFeedbackTest` | see above | R | Drag beyond and inside the 12 dp tolerance |
| UX-58 Compact filter pattern | `LibraryCompactFilterTest` | see above | R | 411 dp, font scale 2.0 |
| UX-59 Keyboard focus and restore | `LibraryKeyboardNavigationTest`, `EditorKeyboardFocusTest` | see above | R | Arrow/Home/End/PageUp/PageDown; focus ring on keyboard only |
| UX-60 Still opening / stale decode | `EditorLoadingStateTest`, `EditorLoadGenerationTest` | see above | R + N | Fake clock 3 s, 15 s; Retry during decode |
| UX-61 Editor config change | `EditorConfigChangeTest` + Pixel_Fold pass | see above | R + M | Rotate, fold, unfold |
| UX-62 Library config change | `LibraryConfigChangeTest` + Pixel_Fold pass | see above | R + M | Crossing 600 dp swaps filter layout |
| UX-63 Screen-relative steps | `NudgeStepTest`, `CrosshairPlacementTest` | see above | N | Fine step on a 48 MP image moves >= 1 screen pixel |
| UX-64 Auto-offer Crosshair | `AccessibilityAutoCrosshairTest` + phone TalkBack pass | see above | R + M | Fake accessibility manager |
| UX-65 Hints incl. existing users | `PlaceAtCrosshairControlsTest`, `AnnotationSettingsTest` | `hint_should_ShowOncePerVersionToExistingUsersAndBeReReadableFromOverflow_When_FirstEditorOpenAfterUpdate` | R | Hint version absent with existing graph |
| UX-66 Mouse/stylus | `PointerTypeGestureTest` | see above | N | Click, drag, Esc |
| UX-67 Placeholder vs missing | `LibraryThumbnailPlaceholderTest` | see above | R | Both tiles |
| UX-68 New badge and What's new | `LibraryNewBadgeTest` | see above | R | Existing vs fresh install |
| UX-69 Settings > Annotation, overflow | `AnnotationSettingsTest`, `EditorOverflowMenuTest` | see above | R | Items >= 48 dp, Esc closes |
| UX-70 Add menu, delete states | `LibraryOverflowAndDeleteTest`, `LibraryEmptyStateTest` | see above | R | Permission denied, cancelled, unreadable, failure + Retry |
| UX-71 Snackbars | `EditorSnackbarQueueTest`, `TimedMessageAccessibilityTest` | see above | N + R | Queue rules; "Time to take action" only |
| UX-72 Reduced-motion source | `ReducedMotionTest` + device check | see above | R + M | Fake provider; Remove animations on device |

UX criterion -> implementing task (additions): UX-56 D.6g, D.6a; UX-57 C.9j; UX-58 D.6e; UX-59 D.6a, C.9h, D.10a; UX-60 C.9g; UX-61 C.9k; UX-62 D.6e; UX-63 C.8c, C.8i; UX-64 C.8i; UX-65 C.8i, C.9a, C.9l; UX-66 C.9j; UX-67 D.6f; UX-68 D.9d; UX-69 C.9l; UX-70 D.6f; UX-71 C.8h, C.9i; UX-72 C.9d, C.9i. Corrected earlier rows: UX-02 now D.2a, D.3b; UX-50 now C.9f (Clear points); UX-54 now C.9c, D.6d (disabled with reason); UX-33 now C.8c, C.8i.

### Cold-user session results (to be filled by the owner, task D.9e)

| Session | Task 1: annotated photos (wrong taps / seconds) | Task 2: file attachment | Task 3: two-point measurement unaided within 2 min | Task 4: found Clear points | Notes (naming: Annotated vs measured; Graph section) |
|---|---|---|---|---|---|
| 1 | | | | | |
| 2 | | | | | |
| 3 | | | | | |
| 4 (optional) | | | | | |
| 5 (optional) | | | | | |

## Repair 5 additions (triad iteration 3, final: one search definition, keyset paging, UX gaps)

New classes needing registration per Story B.8: commonTest `LibraryMediaClassTest`, `LibrarySearchTest`, `LibraryStatusLineTest`, `UpgradeNoticeStateMachineTest`, `PlacementCancelZoneTest`, `EditorBackPrecedenceTest`; androidUnitTest `LibraryAddResultTest`, `LibraryAddAnchorTest`, `ScreenHeadingsTest`, `NavShellConfigChangeTest`, `QuickTapModeSwitchTest`, `SystemGestureInsetPressTest`, `CrosshairLayoutTest`, `EditorProcessDeathRestoreTest`. `LibraryFilterSqlParityTest` gains the cases below.

### Requirement -> Test Mapping (additions)

| Requirement | Test File (source set) | Test Name | Type | Scenario | Pre-fix red |
|---|---|---|---|---|---|
| REQ-10 | `LibraryFilterSqlParityTest` (BT) | `search_should_MatchReferenceFilter_When_MultiWordTokensSpanNameTagAndLabel` | Integration | LD-3/LD-7: "kit sink" where "kit" prefixes the file name and "sink" an annotation label; every token must match; page name and file content never match | T |
| REQ-10 | `LibraryFilterSqlParityTest` (BT) | `search_should_TreatPercentAndBackslashAsLiterals_When_UserTypesWildcardCharacters` | Integration | queries `100%`, `a\b`, `%%`: SQL (ESCAPE) and reference both match only literal text; `_` splits the token (not a wildcard) | T |
| REQ-10 | `LibraryFilterSqlParityTest` (BT), `LibrarySearchTest` (CT) | `search_should_RunNoSearchAndShowHint_When_QueryIsOneCharacter` | Integration | 0 chars unfiltered; 1 char unchanged list plus hint; 2 chars searches; whitespace-only counts as 0 | T |
| REQ-10 | `LibraryFilterSqlParityTest` (BT) | `search_should_FoldAsciiCaseOnlyAndAgreeWithReference_When_UnicodeAndMixedCaseQueries` | Integration | "KIT" matches "kitchen"; `\u00e9` does not match `\u00c9` on both sides; non-ASCII names/tags/labels | T |
| REQ-10 | `LibraryFilterSqlParityTest` (BT) | `search_should_SplitOnSeparatorsAndMatchWordStartsOnly_When_UnderscoreHyphenDotInNames` | Integration | "IMG_0012" = tokens img, 0012; "site-visit" matches tag "site visit"; "tchen" does not match "kitchen" (not a word start); tags stored as JSON text match without `json_each` | T |
| REQ-10 | `LibraryFilterSqlParityTest` (BT) | `paging_should_ReturnEachRowOnceInOrder_When_KeysetPagedInAllFourSortsWithTies` | Integration | LD-2: 8,030 rows, ties on every leading key component, items inserted between page requests do not cause a repeat or skip of existing rows | T |
| REQ-10 | `LibraryMediaClassTest` (CT), `LibraryFilterSqlParityTest` (BT) | `membership_should_MapEveryAssetMediaTypeAndUnknownToOneChip_When_TableApplied` | Unit + Integration | LD-11: IMAGE, PDF, AUDIO, VIDEO, DOCUMENT, FILE and an unknown stored string (Other); SQL `IN`/`NOT IN` equals `LibraryMediaClass.of` | T |
| REQ-10 | `LibraryFilterSqlParityTest` (BT) | `measurementCount_should_SumOverAllAnnotationRowsOfAsset_When_ImageAnnotatedFromTwoPages` | Integration | LD-15: one card, sum of counts, sort and filter by the sum; annotated orphan listed with Annotated + Orphaned both on (LD-16) | T |
| REQ-8 | `LibraryStatusLineTest` (CT) | `statusLine_should_FollowTemplatesForEveryFilterCombination_When_CountsAndChipsVary` | Unit | LD-17 table: one/two types, Annotated, Orphaned, 1/2/3 tags, long search, singular, zero, "10,000+" | T |
| REQ-8 | `LiveRegionPolitenessTest` (AUT, ext.) | `libraryRegions_should_BeSeparatePoliteNodes_When_CountReasonAndSnackbarChange` | Unit | LD-17: reasons never replace the count | T |
| REQ-8 | `LibraryAddResultTest` (AUT) | `add_should_ScrollOutlineOrShowHiddenSnackbar_When_FiltersOrSortHideTheNewItem` | Unit | LD-12: matching item outlined, Annotated-filtered item gives "Hidden by current filters" + Clear filters, Name A-Z gives Show | T |
| REQ-8 | `LibraryAddAnchorTest` (AUT) | `add_should_AnchorToLastViewedPageOrTodaysJournalAndNeverCreateOrphan_When_CaptureChooseOrAttach` | Unit | LD-13, ED-8 | T |
| REQ-8 | `LibraryThumbnailPlaceholderTest` (AUT) | `thumbnail_should_ShowCantPreviewWithoutRetryLoop_When_DecoderThrowsAndSkeletonWhenOutOfMemory` | Unit | LD-18 / UX-80 | T |
| REQ-8 | `LibraryConfigChangeTest` (AUT) | `library_should_RestoreScrollAnchorByBoundedForwardLoad_When_ProcessDeathAndItemMissing` | Unit | LD-19: found within 10 pages vs top fallback | T |
| REQ-4 | `UpgradeNoticeStateMachineTest` (CT) | `reducer_should_ClearBadgeOnLibraryOpenAndKeepNoticeUntilDismissed_When_EveryTableRowWalked` | Unit | LD-20 / UX-82: fresh, existing, open, dismiss, second graph, store failure | T |
| REQ-4 | `NavShellConfigChangeTest` (AUT) | `shell_should_KeepDestinationAndDeveloperStateAndDismissMoreSheet_When_Crossing600dp` | Unit | ED-22 / UX-78; focus lands on the current destination | T |
| REQ-4 | `ScreenHeadingsTest` (AUT) | `screens_should_MarkTitlesGroupsAndDialogTitlesAsHeadingsWithPaneTitles_When_LibrarySettingsAndDialogsRendered` | Unit | S18: Library, Settings > Annotation, dialogs, Help | T |
| REQ-5 | `QuickTapModeSwitchTest` (AUT) | `quickTap_should_SwitchToCrosshairVisiblyAndAnnounce_When_QuickTapMovesCrosshair` | Unit | ED-18 / UX-75; Place point setting: no mode change | T |
| REQ-5 | `CrosshairPlacementTest` (BT) | `drag_should_MoveCrosshairRelativelyAndNeverPlaceOnLift_When_DragFromCanvasOrFromCrosshair` | Unit | ED-19 / UX-76 | T |
| REQ-5 | `PlacementCancelZoneTest` (CT), `SystemGestureInsetPressTest` (AUT) | `cancelZone_should_IncludeToolbarRegionAndIgnorePressesStartingInGestureInset_When_PressEvaluated` | Unit | ED-20 / UX-77 | T |
| REQ-5 | `CrosshairLayoutTest` (AUT) | `layout_should_KeepCanvasAtLeast200dpAndFallBackToRailOrSheet_When_LandscapeOrFontScale2` | Unit | ED-21: 640x360, 411x891 at 2.0, 673 dp | T |
| REQ-5 | `MoveVertexActionsTest` (AUT) | `actions_should_OfferSelectNextPreviousAndMoveSelectedAndDirectOnlyUpToSix_When_NVertices` | Unit | ED-23 / UX-81: N = 2, 4, 6, 12 | T |
| REQ-5 | `EditorBackPrecedenceTest` (CT) | `back_should_CloseTopmostLayerFirstAndNeverLeaveWhileLayerOpen_When_RandomLayerStacks` | Unit (property) | ED-24 / UX-79 | T |
| REQ-5 | `EditorProcessDeathRestoreTest` (AUT) | `restore_should_KeepToolAndSelectionAndShowNoteOnce_When_UnfinishedPointsExistedAtDeath` | Unit | ED-26 | T |
| REQ-5 | `ThemeContrastTest` (AUT, ext.) | `tokens_should_MeetContrast_When_CancelLabelDimmedChipReasonCantPreviewGlyphAndBadgeRendered` | Unit | ED-27, light and dark | T |
| REQ-5 | `EditorFocusOrderTest` (AUT, ext.) | `order_should_PlaceBannersBeforeCanvasAndSnackbarLast_When_BannerAndSnackbarShown` | Unit | ED-25 | T |

### UX Acceptance Tests (additions, UX-73..UX-82)

| UX Criterion | Test File | Test Name | Tool | Steps |
|---|---|---|---|---|
| UX-73 Add result under filters | `LibraryAddResultTest` | see above | R | Annotated on, then Name A-Z, then no filters |
| UX-74 Status templates, separate regions | `LibraryStatusLineTest`, `LiveRegionPolitenessTest` | see above | N + R | Table of combinations |
| UX-75 Quick-tap switch visible | `QuickTapModeSwitchTest` | see above | R | Quick tap in Drag mode |
| UX-76 Crosshair gestures and layout | `CrosshairPlacementTest`, `CrosshairLayoutTest` | see above | N + R | Drag from canvas and crosshair; landscape; font 2.0 |
| UX-77 Cancel without an edge | `PlacementCancelZoneTest`, `SystemGestureInsetPressTest` + phone pass | see above | N + R + M | Toolbar cancel; inset press |
| UX-78 Shell swap | `NavShellConfigChangeTest` + Pixel_Fold pass | see above | R + M | Cross 600 dp with More sheet open |
| UX-79 Back precedence | `EditorBackPrecedenceTest` | see above | N | Layer stacks |
| UX-80 Undecodable image | `LibraryThumbnailPlaceholderTest` | see above | R | Corrupt file vs OOM |
| UX-81 N-vertex actions | `MoveVertexActionsTest` + phone TalkBack pass | see above | R + M | N = 2, 4, 6, 12 |
| UX-82 Upgrade state machine | `UpgradeNoticeStateMachineTest`, `LibraryNewBadgeTest`, `LibraryUpgradeNoticeTest` | see above | N + R | Every table row |

UX criterion -> implementing task (additions): UX-73 D.6f; UX-74 D.6a, D.6g; UX-75 C.9f; UX-76 C.8c, C.9m; UX-77 C.9j; UX-78 D.4c; UX-79 C.9m; UX-80 D.6b, D.6f, C.9g; UX-81 C.8f; UX-82 D.9c, D.9d. Corrected: UX-56 now allows Annotated with Orphaned (D.6g, LD-16); UX-68 badge clears on Library open while the notice persists until dismissed (LD-20).
