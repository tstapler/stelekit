# Validation Plan: cross-graph-merge-share-target

**Date**: 2026-10-07

Path aliases (same as plan.md): `CT` = `kmp/src/commonTest/kotlin/dev/stapler/stelekit`; `BT` = `kmp/src/businessTest/kotlin/dev/stapler/stelekit`; `AU` = `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit`; `JT` = `kmp/src/jvmTest/kotlin/dev/stapler/stelekit`; `WT` = `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit`; `APP` = `androidApp/src/test/kotlin/dev/stapler/stelekit`. Every new `BT` class must be registered in `BT/AllBusinessTests.kt` (`AllBusinessTestsCompletenessTest` enforces it).

## Happy Path Scenario
Given two registered graphs (`Personal` active with page `Projects` = blocks A, B, C, `Work` inactive with `Projects` = blocks A, B) and no switch of the active graph, when the user selects `Projects` in "Copy pages to...", chooses `Work`, reviews the dry run ("1 already exist - blocks will be combined") and confirms, then `Work/pages/Projects.md` contains `A`, `B`, `C` with its original bytes untouched and `C` carrying a remapped `id::` and `src-id::`, repeating the copy reports "Nothing to copy", and a share sent to SteleKit with the default capture graph `Work` lands in `Work`'s journal in one tap. *(Anchor for all tests below; the dry-run to confirm to repeat sequence is `PageMergeServiceTest.happyPath_should_CombineIntoInactiveGraph_ThenReportNothingToCopyOnRepeat`.)*

## Requirement derivation
requirements.md has no numbered requirements, so the IDs below are derived from its Success Metrics, In Scope, Constraints, Non-functional, Observability and Risk Control sections.

| ID | Requirement | Source section |
|----|-------------|----------------|
| REQ-1 | Block-level merge: match by UUID then identical content; properties union; true conflicts flagged; nothing removed from target | In Scope, Success Metrics 1 |
| REQ-2 | Idempotent: repeating the same merge adds zero duplicate blocks (stable identity, remap, refs) | Success Metrics 1 |
| REQ-3 | Copy a chosen subset (page picker with search), not the whole graph | Success Metrics 2, In Scope |
| REQ-4 | Filters: journals vs pages, date range, namespace, tag | In Scope |
| REQ-5 | Optional inclusion of linked pages and assets (bounded closure) | In Scope, Rabbit Holes |
| REQ-6 | Dry-run summary (new / combined / unchanged; requirements.md's "merged" means combined) before commit, gating the write | Success Metrics 2, Risk Control |
| REQ-7 | Write to a non-active graph without forcing a switch (single-open `GraphManager` respected) | In Scope, Constraints |
| REQ-8 | Share target: default capture graph in Settings | In Scope, Success Metrics 3 |
| REQ-9 | Share target: per-share override in Android `CaptureActivity`, remembers last used, lands in chosen graph in one step | In Scope, Success Metrics 3 |
| REQ-10 | Equivalent graph choice for desktop quick capture | In Scope |
| REQ-11 | Platforms: copy UI on Android, Desktop, Web, iOS; share-target choice where an entry point exists | In Scope |
| REQ-12 | Performance/scalability: 8 000-page graph, chunked bounded reads, no OOM or GC thrash | NFR |
| REQ-13 | Repository rules: Arrow `Either`, writes via `DatabaseWriteActor`, `@DirectSqlWrite`, bounded reads, `MigrationRunner`, regenerated SQLDelight | Constraints |
| REQ-14 | Shared logic in `commonMain`/`commonTest` with property-based tests for the merge function | Constraints |
| REQ-15 | Observability: `Logger` counts and destination graph id; failures surfaced (snackbar/dialog), never silent | Observability |
| REQ-16 | Risk control: additive-only merge gated by dry run; undo for the target | Risk Control |
| REQ-17 | Share failure is a visible state (queued), never silent loss; copies never queue and always report a definite per-page result | Observability, Risk Control, user decision 1 |
| REQ-18 | Security: internal data, local only, no network | NFR |

## Requirement -> Test Mapping

| Requirement | Test File | Test Name | Type | Scenario |
|-------------|-----------|-----------|------|----------|
| REQ-1 | CT/merge/MergePageExamplesTest | mergePage_should_UnionBlocksPreservingTargetOrder_When_PagesShareBlockByUuid | Unit | Happy path |
| REQ-1 | CT/merge/MergePageExamplesTest | mergePage_should_ReturnUnchanged_When_SameContentDifferentUuid | Unit | Happy path |
| REQ-1 | CT/merge/MergePageExamplesTest | mergePage_should_UnionPropertiesAndKeepTargetScalar_When_ScalarPropertyClashes | Unit | Happy path |
| REQ-1 | CT/merge/MergePageExamplesTest | mergePage_should_KeepBothBlocks_When_ShortContentUnderDifferentParents | Unit | Happy path |
| REQ-1 | CT/merge/MergePageExamplesTest | mergePage_should_ReturnNewWithRemappedUuids_When_ExistingPageIsNull | Unit | Edge |
| REQ-1 | CT/merge/MergePageExamplesTest | mergePage_should_AddFlaggedConflictSibling_When_SameUuidDifferentContent | Unit | Error path |
| REQ-1 | CT/merge/MergePageExamplesTest | mergePage_should_AppendAfterLastSibling_When_TargetBlocksHaveNoExplicitId | Unit | Edge (R3 positional uuids) |
| REQ-1 | CT/merge/MergePagePropertyTest | mergePage_should_NeverDropTargetOrSourceContent_When_AnyGeneratedPair | Unit (property) | Happy path, no loss |
| REQ-1 | CT/merge/MergePagePropertyTest | mergePage_should_HaveEqualContentMultiset_When_MergedEitherWayWithoutConflicts | Unit (property) | Commutativity |
| REQ-1 | CT/merge/MergePagePropertyTest | mergePage_should_ReturnUnchanged_When_SelfMergedOrSourceEmpty | Unit (property) | Identity |
| REQ-1 | CT/merge/MergePagePropertyTest | mergePage_should_NeverProduceDuplicateUuid_When_UuidsCollide | Unit (property) | Error path |
| REQ-1 | CT/merge/MergePagePropertyTest | mergePage_should_KeepPositionalUuidsOfUnlabeledBlocks_When_RenderedAndReparsed | Unit (property) | Edge (R3) |
| REQ-1 | CT/merge/MergeConvertersTest | converters_should_RoundTripNestedBlocks_When_ConvertedThroughMergePageAndSerializer | Unit | Happy path |
| REQ-1 | CT/merge/MergeConvertersTest | converters_should_ParseParentChildAndExplicitId_When_MarkdownHasIdProperty | Unit | Happy path |
| REQ-1 | CT/merge/MarkdownSplicerFixtureTest | splice_should_PreserveEveryOriginalByte_When_FixtureHasCrlfTabsFencesAndCollapsed | Unit | Happy path |
| REQ-1 | CT/merge/MarkdownSplicerFixtureTest | splice_should_KeepPreExistingParsedUuidsUnchanged_When_FixturesAreUnlabeled | Unit | Edge (R3) |
| REQ-1 | CT/merge/MarkdownSplicerFixtureTest | splice_should_NotRewriteExistingAliasOrTagsLine_When_PagePropertyUnionNeeded | Unit | Edge |
| REQ-1 | CT/merge/RoundTripGuardTest | guard_should_ReturnNotRoundTrippable_When_SerializeOfParseDiffersFromFile | Unit | Error path |
| REQ-1 | BT/merge/MarkdownTargetWriterTest | write_should_PreserveOriginalBytesAndTouchOnlyThatFile_When_AddingBlockToExistingPage | Integration | FakeFileSystem |
| REQ-1 | BT/merge/MarkdownTargetWriterTest | write_should_ReturnNotRoundTrippableAndLeaveFileUntouched_When_FixtureFailsGuard | Integration | Error path |
| REQ-1 | BT/merge/TargetWriterContractTest (Markdown + Active subclasses) | contract_should_ProduceIdenticalBlockData_When_RunAgainstMarkdownAndActiveWriters | Integration | Parity, file re-parse and DB |
| REQ-2 | CT/merge/MergePagePropertyTest | mergePage_should_BeIdempotent_When_SourceMergedTwice | Unit (property) | Happy path |
| REQ-2 | CT/merge/MergePagePropertyTest | mergePage_should_AddOneConflictThenNothing_When_TargetEditedAfterCopyAndRecopiedRepeatedly | Unit (property) | Edge (R4) |
| REQ-2 | CT/merge/MergePagePropertyTest | mergePage_should_BeIdempotent_When_SourceDriftsBetweenCopies | Unit (property) | Edge (R4) |
| REQ-2 | CT/merge/MergePageExamplesTest | mergePage_should_AddSingleConflictSiblingThenReturnUnchanged_When_RecopiedAfterTargetEdit | Unit | Error path (R4) |
| REQ-2 | CT/merge/UuidRemapTest | remap_should_ReturnSameUuidAndSrcId_When_ComputedTwice | Unit | Happy path |
| REQ-2 | CT/merge/UuidRemapTest | remap_should_NeverEqualSourceUuidOrCollide_When_10000DistinctPairs | Unit (property) | Error path |
| REQ-2 | CT/merge/UuidRemapTest | remap_should_UseSha256Derivation_When_NotFnvGenerateDeterministic | Unit | Happy path |
| REQ-2 | CT/merge/UuidRemapTest | conflictUuid_should_NeverEqualRemappedUuid_When_SameGraphAndSourceUuid | Unit | Error path |
| REQ-2 | CT/merge/UuidRemapTest | rewriteRefs_should_RewriteSelectedRefsAndEmbedsAndLeaveUnselectedRefs_When_BlockHasRefs | Unit | Happy path |
| REQ-2 | CT/merge/UuidRemapTest | rewriteRefs_should_BeIdempotent_When_AppliedTwice | Unit (property) | Edge |
| REQ-2 | CT/merge/StagedPageTest | stagedPage_should_DecodeEqual_When_EncodedWithUuidsPropsNestingOrder | Unit | Happy path |
| REQ-2 | CT/merge/MarkdownSplicerFixtureTest | renderer_should_EmitIdForEveryInsertedBlock_When_ParsedBack | Unit | Happy path |
| REQ-2 | BT/transfer/MergeUuidRoundTripSpikeTest | spike_should_RecordDuplicateUuidInsertOutcome_When_SameUuidOnTwoPages | Integration | Error path (sizes clobber guard) |
| REQ-2 | BT/merge/ActiveTargetWriterTest | write_should_FailWithUuidCollisionBeforeInsert_When_UuidPrimeExistsOnDifferentPage | Integration | Error path |
| REQ-2 | BT/merge/MarkdownTargetWriterTest | write_should_RefuseInsert_When_UuidPrimeExistsInFileUnderDifferentSrcId | Integration | Error path |
| REQ-2 | BT/merge/MarkdownTargetWriterTest | write_should_RecordZeroWrites_When_AllBlocksAlreadyPresentBySrcId | Integration | Idempotent |
| REQ-2 | BT/merge/PageMergeServiceTest | apply_should_ReportAllUnchangedAndWriteNothing_When_AppliedTwice | Integration | Idempotent |
| REQ-2 | BT/merge/TargetWriterContractTest | contract_should_ResolveCopiedRefAfterReload_When_BlockReferencesAnotherCopiedBlock | Integration | R1 id:: on active path |
| REQ-2 | BT/merge/CopyEditRecopyPropertyTest | recopy_should_AddOnlyOneConflictSiblingPerEditedBlockThenNothing_When_CopiedEditedThroughEditorAndRecopiedOnRealActivePath | Integration (property, real ActiveTargetWriter + GraphWriter + GraphLoader) | P2-4 end-to-end identity |
| REQ-2 | BT/merge/CopyEditRecopyPropertyTest | readExistingUuids_should_EqualDbUuids_When_PagePathSeedUsedByGraphLoader | Integration | pagePath seed equality (pre-mortem #4) |
| REQ-3 | CT/ui/PageSelectionTest | selection_should_KeepKeys_When_SearchTextChanges | Unit | Happy path |
| REQ-3 | CT/ui/PageSelectionTest | selectAllMatching_should_SelectExactlyFilteredSet_When_FilterMatches213 | Unit | Happy path |
| REQ-3 | CT/ui/PageSelectionTest | review_should_BeDisabledWithMissingReason_When_NoPageOrNoDestinationChosen | Unit | Error path |
| REQ-3 | BT/ui/CopyPagesViewModelTest | viewModel_should_KeepSelectedCount_When_QueryAndFiltersChange | Integration | In-memory repos |
| REQ-3 | BT/ui/CopyPagesViewModelTest | viewModel_should_SurfaceReadFailureWithRetry_When_SourceDbClosed | Integration | Error path |
| REQ-3 | BT/merge/PageMergeServiceTest | stage_should_SpillOnlySelectedPagesAsStagedJson_When_SubsetSelected | Integration | Happy path |
| REQ-3 | BT/merge/PageMergeServiceTest | happyPath_should_CombineIntoInactiveGraph_ThenReportNothingToCopyOnRepeat | Integration | Anchor scenario |
| REQ-4 | CT/merge/SelectionFilterTest | matches_should_AndJournalDateNamespaceTagFilters_When_AllSet | Unit | Happy path |
| REQ-4 | CT/merge/SelectionFilterTest | matches_should_MatchNothing_When_DateRangeInverted | Unit | Error path |
| REQ-4 | CT/merge/SelectionFilterTest | matches_should_AgreeWithSqlOracleAndSelectionFilterPropertyAcrossGeneratedPages | Unit (property) | Oracle for SQL |
| REQ-4 | BT/merge/ActiveDbPageSourceTest | getPagesFiltered_should_ReturnOnlyMatchingPagesBounded_When_JournalsAndNamespaceFilterSet | Integration | In-memory SQLite |
| REQ-4 | BT/merge/ActiveDbPageSourceTest | countPagesFiltered_should_EqualPredicateOracleCount_When_FiltersCombined | Integration | Happy path |
| REQ-4 | BT/merge/ActiveDbPageSourceTest | getPagesFiltered_should_ReturnEmptyAndZeroCount_When_NothingMatches | Integration | Error path |
| REQ-4 | BT/db/QueryPlanAuditTest | filteredPageQueries_should_UseIndexAndNoFullTableScan_When_AuditedPlanInspected | Integration | New .sq queries |
| REQ-5 | CT/merge/LinkClosurePolicyTest | closure_should_FlagConfirmationAbove200AndTruncateAt1000_When_CountExceedsCaps | Unit | Edge |
| REQ-5 | CT/merge/LinkClosurePolicyTest | closure_should_BeOffByDefault_When_PolicyNotSet | Unit | Happy path |
| REQ-5 | BT/merge/LinkClosureTest | expand_should_AddDepth1PagesOnly_When_PolicyDepth1 | Integration | Happy path |
| REQ-5 | BT/merge/LinkClosureTest | expand_should_AddNothing_When_PolicyOff | Integration | Happy path |
| REQ-5 | BT/merge/LinkClosureTest | expand_should_ReportMoreNotIncluded_When_Closure1340 | Integration | Error/limit path |
| REQ-5 | BT/merge/AssetCopierTest | copy_should_RenameWithHashSuffixAndRewriteLink_When_TargetHasDifferentBytes | Integration | Happy path |
| REQ-5 | BT/merge/AssetCopierTest | copy_should_SkipCopy_When_BytesIdentical | Integration | Happy path |
| REQ-5 | BT/merge/AssetCopierTest | copy_should_WarnAndStillCopyPage_When_SourceAssetMissing | Integration | Error path |
| REQ-6 | CT/merge/MergePlanTest | planFingerprint_should_Change_When_TargetPageContentChanges | Unit | Happy path |
| REQ-6 | CT/merge/MergePlanTest | commitCount_should_EqualNewPlusCombined_When_ConflictsAreInsideCombined | Unit | Happy path |
| REQ-6 | CT/merge/MergePlanTest | summary_should_DisableCommit_When_NewAndCombinedAreZero | Unit | Error path |
| REQ-6 | BT/merge/PageMergeServiceTest | plan_should_ClassifyNewCombinedUnchanged_When_30PagesSelectedAndNoFileChanged | Integration | Happy path |
| REQ-6 | BT/merge/PageMergeServiceTest | plan_should_RetainOnlyCountersAndFiftyConflictDetails_When_8000PagesPlanned | Integration | Serialized size < 256 KB |
| REQ-6 | BT/merge/PageMergeServiceTest | apply_should_ReturnPlanStaleWithNewSummaryAndWriteNothing_When_TargetPageChangedAfterPlan | Integration | Error path |
| REQ-6 | BT/merge/PageMergeServiceTest | plan_should_CountUnreadable_When_SourcePageCannotBeParsed | Integration | Error path |
| REQ-6 | BT/merge/PageMergeServiceTest | plan_should_ReturnTypedFailure_When_TargetUnreadable | Integration | Error path |
| REQ-7 | CT/db/PageFileResolverTest | resolve_should_MatchLegacyGraphWriterPath_When_PageIsNamespaced | Unit | Happy path |
| REQ-7 | CT/db/PageFileResolverTest | resolve_should_RoundTripJournalDateThroughJournalUtils_When_GeneratedDates | Unit (property) | Happy path |
| REQ-7 | CT/db/PageFileResolverTest | resolve_should_SanitizeIllegalFilenameChars_When_NameHasThem | Unit | Error path |
| REQ-7 | BT/merge/TargetWriterCapabilitiesTest | canWriteOffGraph_should_ReturnTrue_When_PlainFolderGraph | Unit | Happy path |
| REQ-7 | BT/merge/TargetWriterCapabilitiesTest | canWriteOffGraph_should_ReturnReason_When_EncryptedNoGrantSafInboxOnlyOrPlatformUnsupported | Unit | Error path (4 reasons; no ForcedInbox, cut) |
| REQ-7 | BT/merge/TargetWriterCapabilitiesTest | capabilities_should_HaveNoForcedInboxReason_When_ReasonTypeReflected | Unit | Structural (merge_force_inbox cut) |
| REQ-7 | BT/db/GraphLocatorTest | locate_should_ReturnGraphInfoWithoutActivating_When_GraphRegisteredButInactive | Integration | Happy path |
| REQ-7 | BT/db/GraphLocatorTest | locate_should_ReturnNotFound_When_GraphIdUnknown | Integration | Error path |
| REQ-7 | BT/db/GraphWriteLockTest | switchGraphInit_should_WaitForMergeBatch_When_LockHeldForIncomingGraph | Integration | Happy path |
| REQ-7 | BT/db/GraphWriteLockTest | factoryClose_should_WaitForBatchAndUseOwnersLock_When_SwitchBThenCImmediately | Integration | Error path (C-3) |
| REQ-7 | BT/db/GraphWriteLockTest | switches_should_CompleteWithoutDeadlock_When_SlowDriverFactoryAndNestedLockAssertion | Integration | Lock order (N1) |
| REQ-7 | BT/db/GraphWriteLockTest | init_should_OpenGraphWithoutMergeAndLogTimeout_When_LockAcquireTimesOut | Integration | Degrade-open (P1-3) |
| REQ-7 | BT/db/GraphWriteLockTest | lockOrderGuard_should_ThrowInDebugBuild_When_SecondGraphLockOrAwaitMigrationWhileHolding | Unit | Debug-build assertion (P1-3) |
| REQ-7 | BT/db/GraphManagerSwitchLockStressTest | stress_should_CompleteAndReleaseLocks_When_RapidSwitchesSlowDriverPausedMigrationThrowingMigrationHeldLock | Integration (CI, every platform, withTimeout) | Own first PR gate (P1-3) |
| REQ-7 | JT/db/OffGraphWriteReconcileSpikeTest | switchGraph_should_IndexNewFileWithZeroDiskConflictAndNoDuplicateJournal_When_FileWrittenWhileClosedOnRealTempDir | Integration (real FS, real GraphLoader/watcher/FileRegistry) | Spike 0.1.1 gate for Stories 2.3.1, 4.1.3 |
| REQ-7 | JT/db/OffGraphWriteReconcileSpikeTest | switchGraph_should_ReconcileAppendedBlockWithZeroDiskConflict_When_ExistingFileModifiedWhileClosedOnRealTempDir | Integration (real FS) | Spike 0.1.1 |
| REQ-7 | JT/db/OffGraphWriteReconcileSpikeTest | reopen_should_NotReparseFile_When_FileRegistryAlreadyHoldsNewHash | Integration (real FS) | Spike 0.1.1, FileRegistry state |
| REQ-7 | Manual (recorded in ADR-001) | androidDevice_should_ShowPageAndNoDiskConflictPrompt_When_FileAddedToInactiveGraphThenOpened | Manual (plain folder + SAF) | Spike 0.1.1 device pass |
| REQ-7 | JT/db/OffGraphReconcileRegressionTest | productionMarkdownWriter_should_ReconcileWithZeroDiskConflict_When_WritesThenGraphOpened | Integration (real FS) | Permanent regression (Story 5.1.1) |
| REQ-1 | JT/merge/RoundTripGuardPassRateSpikeTest | guard_should_PassAtLeast95PercentOrRelaxToStructureStable_When_RealExportedGraphMeasured | Integration (real graph path or SyntheticGraphContent XLARGE; skipped with message if neither) | Spike 0.1.4 go/no-go gate |
| REQ-1 | CT/merge/RoundTripGuardTest | guard_should_AssertRecordedFailingFixturesAtChosenStrictness_When_Spike014FixturesLoaded | Unit | Permanent regression of Spike 0.1.4 fixtures |
| REQ-9 | BT/capture/JournalAppenderOffGraphTest | append_should_LandInOneStepForGuardPassingFixturesAndQueueOthers_When_Spike014FixturesUsed | Integration | Share one-step metric tied to guard pass rate (C5) |
| REQ-7 | AU/platform/SafAtomicReplaceSpikeTest | safRename_should_ReplaceAtomicallyOrRecordNo_When_TempRenamedOverExisting | Integration | Spike 0.1.2 (Robolectric / device) |
| REQ-7 | BT/merge/TargetWriterRouterInFlightSwitchTest | router_should_AwaitOutsideLockThenWriteActive_When_InitUnfinished | Integration | Deadlock regression |
| REQ-7 | BT/merge/TargetWriterRouterInFlightSwitchTest | router_should_FallBackToMarkdownWriter_When_SwitchToOtherGraphDuringAwait | Integration | Error path |
| REQ-7 | BT/merge/TargetWriterRouterInFlightSwitchTest | router_should_RouteLaterPagesToActiveWriter_When_TargetBecomesActiveMidRun | Integration | Inactive to active |
| REQ-7 | BT/merge/TargetWriterRouterInFlightSwitchTest | router_should_RouteLaterPagesToMarkdownWriter_When_TargetBecomesInactiveMidRun | Integration | Active to inactive |
| REQ-7 | BT/merge/TargetWriterRouterInFlightSwitchTest | router_should_ReturnRetryableLeft_When_ClosedChannelInjected | Integration | Error path |
| REQ-7 | BT/merge/TargetWriterRouterInFlightSwitchTest | router_should_WaitForMigration_When_ReadyGraphSetButMigrationPaused | Integration | Readiness ordering |
| REQ-7 | BT/merge/TargetWriterRouterInFlightSwitchTest | router_should_NeverSeeIdSetMismatch_When_TeardownRacesWrite | Integration | ReadyGraph pair (C-2) |
| REQ-7 | BT/merge/TargetWriterRouterTest | router_should_UseOneRouterClassForMergeAndShare_When_SourcesGrepped | Integration | Structural |
| REQ-7 | BT/merge/MarkdownTargetWriterTest | write_should_LeaveOriginalIntactAndNoTmp_When_WriteFailsMidway | Integration | Error path |
| REQ-7 | BT/merge/MarkdownTargetWriterTest | write_should_ReturnWriteRefusedAndWriteNothing_When_EncryptedOrSafWithoutGrant | Integration | Error path (no inbox reference) |
| REQ-17 | BT/merge/PageMergeServiceTest | apply_should_FailRemainingPagesDefinitivelyAndNeverQueue_When_GrantLostMidRun | Integration | Copy: definite result, Retry failed succeeds after re-grant |
| REQ-17 | BT/merge/PageMergeServiceTest | apply_should_NeverCreateShareInboxEntry_When_AnyCopyPageIsRefused | Integration | Structural: copies never queue |
| REQ-11 | BT/ui/CopyPagesViewModelTest | destinations_should_BeDisabledWithReasonAndReviewBlocked_When_EncryptedGrantLostOrSafInboxOnly | Integration | Decision 1 |
| REQ-7 | BT/merge/ActiveTargetWriterTest | write_should_UseActorSaveBlockAndSuppressWatcher_When_TargetActive | Integration | Happy path, 0 DiskConflict |
| REQ-7 | BT/merge/ActiveTargetWriterTest | write_should_DeferAndReport_When_TargetPageHasPendingEdits | Integration | Error path |
| REQ-8 | CT/capture/CaptureTargetResolverTest | resolve_should_FollowLastThenDefaultThenActive_When_SettingsAndGraphsVary | Unit | Happy path |
| REQ-8 | CT/capture/CaptureTargetResolverTest | resolve_should_FallBackToActiveGraph_When_DefaultAndLastDeleted | Unit (property) | Error path |
| REQ-8 | CT/capture/CaptureTargetSettingsTest | settings_should_PersistDefaultAndRemember_When_Written | Unit | Happy path |
| REQ-8 | CT/capture/CaptureTargetSettingsTest | settings_should_ReturnNullDefault_When_KeyAbsent | Unit | Error path |
| REQ-8 | BT/capture/JournalAppenderTest | append_should_AddDeterministicBlockToTodaysJournal_When_ActiveGraphAndExistingChain | Integration | Behavior preserved |
| REQ-9 | CT/capture/CaptureTargetResolverTest | override_should_UpdateLastUsedOnly_When_ShareOverridesDefault | Unit | Happy path |
| REQ-9 | CT/capture/CaptureTargetResolverTest | resolve_should_IgnoreLast_When_RememberDisabled | Unit | Error path |
| REQ-9 | BT/capture/JournalAppenderOffGraphTest | append_should_AddBlockViaMergePageAndReportAppended_When_TargetInactive | Integration | Happy path |
| REQ-9 | BT/capture/JournalAppenderOffGraphTest | append_should_CreateJournalFileNamedByResolverAndReconcileToSamePage_When_NoJournalFile | Integration | Edge |
| REQ-9 | BT/capture/JournalAppenderOffGraphTest | append_should_DisableLinkSuggestionsAndCopyImageToAssets_When_TargetInactive | Integration | Edge |
| REQ-9 | BT/capture/JournalAppenderTest | append_should_ReturnAlreadyPresent_When_SameCaptureIdReplayed | Integration | Idempotent |
| REQ-9 | APP/CaptureActivityTargetTest | overlay_should_SaveToNamedGraphWithNoActiveGraph_When_TargetNamed | Integration | Gate removed |
| REQ-9 | APP/CaptureActivityTargetTest | overlay_should_PersistPayloadAndNotDuplicate_When_OnNewIntentOrRotation | Integration | Error/redelivery path |
| REQ-10 | JT/capture/CaptureControllerTargetTest | performSave_should_PassNamedGraphAndUpdateLastUsedOnly_When_PopupShownWithTargetGraph | Integration | State logic only, no renderer |
| REQ-10 | JT/capture/CaptureControllerTargetTest | popup_should_StayOpenWithErrorState_When_SaveFails | Integration | Error path |
| REQ-10 | CT/capture/CaptureChooserKeysTest | altG_should_MapToOpenChooser_And_OtherKeysIgnored | Unit | Happy/Error |
| REQ-10 | JT/capture/CaptureControllerTargetTest | esc_should_CloseOnEmptyTextAndShowConfirmDiscardOnNonEmpty_When_PopupShown | Integration | State logic only (decision 2, UX-42) |
| REQ-10 | JT/capture/CaptureControllerTargetTest | keepEditing_should_PreserveTextAndFocus_And_Discard_should_SaveNothing_When_ConfirmDiscardShown | Integration | Decision 2 |
| REQ-9 | APP/CaptureActivityTargetTest | back_should_AutoSaveToShownDestination_When_TextPresent | Integration | Decision 2 (Android Back saves) |
| REQ-17 | APP/CaptureActivityTargetTest | back_should_QueueInInboxAndShowQueuedMessage_When_AutoSaveFails | Integration | Decision 2 failure path |
| REQ-3 | AU/ui/CopyPagesScreenTest | escOrBack_should_AskDiscardSelectionOfN_When_SelectionGreaterThanZero | Integration | Story 3.2.1 |
| REQ-11 | WT/merge/MergePageWasmSmokeTest | mergePage_should_RunOnWasm_When_ExampleInputMerged | Unit | Happy path |
| REQ-11 | BT/merge/TargetWriterCapabilitiesTest | canWriteOffGraph_should_ReturnPlatformUnsupported_When_IosOrWasmCannotAddressPath | Unit | Error path |
| REQ-11 | BT/merge/IosWebCopyGatingTest | copyAttempt_should_OfferOnlyPullAndDisablePushDestinationsAndCreateNoStagingOrInboxEntry_When_IosOrWasmCapabilities | Integration | Replaces removed apply-on-activate tests (decision 1) |
| REQ-11 | BT/merge/SourceReadCapabilitiesTest | canReadOffGraph_should_ReturnReasonPerCase_When_EncryptedNoGrantFolderMissingOrPlatformUnsupported | Unit | Error path; a source with a reason is disabled, never queued |
| REQ-11 | CT/merge/MarkdownSourceGraphReaderParseTest | readPage_should_ParseFixturesAndReportUnreadableWithoutCrash_When_CrlfCodeFencesIdPropsOversizeOrGarbage | Unit + Property | `Arb` MergePage render -> read round-trip (500 runs in `runTest`); journal filename decode; 2 MB cap |
| REQ-11 | BT/merge/SourceReaderParityTest | readPage_should_EqualGraphLoaderDerivedStagedPage_When_SameFixtureFiles | Integration | Same uuids/props/nesting as the push path (idempotence pull vs push) |
| REQ-11 | BT/merge/PullCopyFlowTest | pull_should_ReportNewCombinedUnchangedFailedAndNeverQueue_When_SourceReadThroughActiveTargetWriter | Integration | Happy: new, then re-pull -> unchanged; edge: combined with conflict sibling; no `ShareInbox` entry |
| REQ-11 | BT/merge/PullCopyFlowTest | pull_should_FailPagesDefinitelyWithRetry_When_GrantLostOrFileVanishesOrDestinationSwitchedMidRun | Integration | Error path; Retry failed re-reads; completed pages stay undoable |
| REQ-11 | BT/merge/PullCopyFlowTest | pull_should_NeverOpenSourceDatabaseAndBoundParsedPages_When_8kPageSource | Integration | Counting `DriverFactory` open = 0 for source; max parsed-in-memory <= 50, listing page <= 100, no body read before Review |
| REQ-11 | BT/ui/PullCopyViewModelTest | selection_should_SurviveSearchAndSelectAllMatchingNames_When_PullDirection | Unit | Name/kind/date filters; disabled filters expose the reason; undo reverts destination |
| REQ-11 | BT/capture/CaptureTargetResolverTest (or platform test) | quickAdd_should_ResolveDefaultCaptureGraphAndQueueWhenUnaddressable_When_IosWebEntryPointExists | Integration | Task 4.5.1e; N/A if no entry point (record finding) |
| REQ-11 | build (CI) | compileTestKotlinWasmJs_and_IosSimulatorArm64_should_Succeed_When_NoJavaStarUsageInNewCommonCode | Integration | Compile gate (command in Test Stack) |
| REQ-12 | BT/merge/LargeGraphMergeTest | fullCopy_should_ReadAtMost100RowsAndStageOnePageAtATime_When_8030Pages | Integration | Happy path |
| REQ-12 | BT/merge/LargeGraphMergeTest | secondCopy_should_BeAllUnchangedWithNoUncaughtThrowable_When_8030Pages | Integration | Idempotent, recording handler |
| REQ-12 | BT/merge/ActiveDbPageSourceTest | pageSource_should_IssueBoundedQueries_When_FilterOrSearchChanges8030Pages | Integration | Happy path |
| REQ-12 | BT/merge/MergeStagingDirectoryTest | readAll_should_HoldAtMostOnePage_When_5000StagedFiles | Integration | Counting fake |
| REQ-12 | BT/merge/MergeStagingDirectoryTest | sweep_should_DeleteOnlyMarkedDirsOlderThan7Days_When_MixedDirsPresent | Integration | Error path (no-marker dir kept) |
| REQ-12 | BT/merge/MergeStagingDirectoryTest | create_should_WriteMarkerJsonAndOneFilePerPage_When_RunStarted | Integration | Happy path |
| REQ-12 | BT/merge/MergeResilienceTest | collectors_should_YieldReadFailedNotCrash_When_SourceDriverClosed | Integration | Error path |
| REQ-12 | BT/merge/MergeResilienceTest | service_should_SurfaceFailureViaHandler_When_ThrowableEscapesApplyScope | Integration | Error path |
| REQ-12 | BT/merge/LargeGraphMergeTest | offGraphWrites_should_CauseAtMostOneReconcile_When_NFilesWrittenThenOpened | Integration | Watcher burst |
| REQ-13 | BT/merge/MergeArchitectureInvariantsTest | mergePackage_should_NotCallMutatingSteleDatabaseQueries_When_SourcesScanned | Unit | Structural |
| REQ-13 | BT/merge/MergeArchitectureInvariantsTest | repositoryAndServiceMethods_should_ReturnEither_When_PublicSignaturesScanned | Unit | Structural |
| REQ-13 | BT/merge/MergeArchitectureInvariantsTest | mergeAndCaptureClasses_should_NotAcceptRememberCoroutineScope_When_ConstructorsScanned | Unit | Structural |
| REQ-13 | BT/merge/ActiveTargetWriterTest | write_should_WrapSqlExceptionAsWriteFailedLeft_When_DatabaseThrows | Integration | Error path |
| REQ-13 | BT/db/MigrationRunnerSchemaSyncTest (existing) | migrationRunner_should_StillListEveryTable_When_OnlyQueriesAdded | Integration | Regression guard |
| REQ-13 | BT/AllBusinessTestsCompletenessTest (existing) | allBusinessTests_should_ListEveryNewClass_When_ClassesAdded | Integration | Regression guard |
| REQ-13 | BT/db/QueryPlanAuditTest | newQueries_should_BeRegisteredAndBounded_When_AuditRuns | Integration | Happy path |
| REQ-13 | BT/repository/PageRepositoryBoundednessTest | pageRepository_should_ExposeNoUnboundedListMethod_When_InterfaceReflected | Unit | Structural |
| REQ-14 | CT/merge/MergePagePropertyTest | propertyTests_should_RunInCommonTestOnAllTargets_When_ParametrizedOnBothKeyFunctions | Unit (property) | Happy path |
| REQ-14 | BT/merge/MergeArchitectureInvariantsTest | mergeCore_should_ContainNoJavaOrPlatformImports_When_CommonMainMergeSourcesScanned | Unit | Error path |
| REQ-15 | BT/merge/PageMergeServiceTest | apply_should_LogOneSummaryLineWithCountsAndGraphIds_When_RunCompletes | Integration | Recording Logger |
| REQ-15 | BT/merge/PageMergeServiceTest | apply_should_LogFailedPageNameAndError_When_PageFails | Integration | Error path |
| REQ-15 | BT/merge/PageMergeServiceTest | apply_should_NeverLogPageBodies_When_LogsInspected | Integration | Privacy |
| REQ-15 | BT/capture/JournalAppenderOffGraphTest | append_should_LogTargetWriterAndOutcomeWithoutText_When_Appended | Integration | Recording Logger |
| REQ-15 | BT/merge/PageMergeServiceTest | apply_should_ExposeTypedResultToUi_When_PageFails | Integration | Failure surfaced |
| REQ-16 | BT/merge/PageMergeServiceTest | apply_should_CommitOthersKeepStagingAndRetrySucceeds_When_Page3Fails | Integration | Error path |
| REQ-16 | BT/merge/PageMergeServiceTest | apply_should_KeepCommittedPagesAndConverge_When_CancelledAfter1200Of4000 | Integration | Cancel |
| REQ-16 | CT/merge/MergePagePropertyTest | mergePage_should_NeverRemoveTargetBlock_When_AnyGeneratedPair | Unit (property) | Additive-only |
| REQ-16 | BT/merge/MergeManifestTest | manifest_should_ReloadEqual_When_FlushedAfterCreatePageAndAddedBlocks | Integration | Happy path |
| REQ-16 | BT/merge/MergeManifestTest | findInterrupted_should_ReturnRun_When_StatusInProgress | Integration | Error path |
| REQ-16 | BT/merge/MergeUndoTest | undo_should_DeleteCreatedPageAndAddedBlocksOnly_When_NothingEditedSince | Integration | Happy path |
| REQ-16 | BT/merge/MergeUndoTest | undo_should_LeaveEditedBlockAndReportIt_When_BlockChangedSinceCopy | Integration | Error path |
| REQ-16 | BT/merge/MergeUndoTest | undo_should_BeUnavailable_When_ManifestOlderThan7Days | Integration | Edge |
| REQ-16 | BT/merge/MergeUndoTest | undo_should_WorkThroughRouter_When_TargetBecameActive | Integration | Happy path |
| REQ-16 | BT/merge/TargetWriterContractTest | contract_should_RemoveOnlyOnMatchingHash_When_DeletePageFileOrRemoveBlocksCalled | Integration | Error path (mismatch) |
| REQ-17 | BT/capture/ShareInboxTest | inbox_should_AppendOnceAfterRestartAndReady_When_EnqueuedThenGraphReady | Integration | Happy path |
| REQ-17 | BT/capture/ShareInboxTest | drain_should_WaitForAwaitPendingMigration_When_ActiveGraphIdChangesFirst | Integration | Race |
| REQ-17 | BT/capture/ShareInboxTest | pendingCount_should_ReportQueuedItems_When_TwoEnqueued | Integration | Visible |
| REQ-17 | BT/capture/ShareInboxTest | enqueue_should_CopyImageToPrivateStorage_When_ImageShared | Integration | Edge |
| REQ-17 | BT/capture/InboxFallbackAppenderTest | append_should_ReturnQueuedAndPersistItem_When_CapabilityPermissionOrNotRoundTrippable | Integration | Error path |
| REQ-17 | BT/capture/ShareInboxTest | drain_should_KeepItemAndReportReason_When_AppendFailsAfterActivation | Integration | Error path |
| REQ-18 | BT/merge/MergeArchitectureInvariantsTest | mergeAndCaptureSources_should_ImportNoNetworkClients_When_Scanned | Unit | Error path |
| REQ-18 | BT/merge/MergeArchitectureInvariantsTest | stagingAndInboxFiles_should_LiveUnderAppPrivateDir_When_Created | Integration | Happy path |

## UX Acceptance Tests
Placement follows CLAUDE.md: Compose-behavior tests (wording, semantics, focus, tap counts) go to `androidUnitTest` (Robolectric, headless, any machine); `jvmTest` is used only where a real desktop window/renderer is needed, and then runs behind `scripts/jvm-display-check.sh`. Web and iOS behavior is Manual because no automated Compose host exists for them in the repo. Where a criterion is partly automatable, the Robolectric test is primary and the Manual checklist step is the confirmation.

| UX Criterion | Test File | Test Name | Tool | Steps |
|---|---|---|---|---|
| UX-01 Single page copy in <= 4 taps | AU/ui/CopyPagesFlowTest | copySinglePage_should_FinishInFourTaps_When_StartedFromPageMenu | Robolectric | Open page menu, tap "Copy this page to...", pick destination, Review, Copy; assert tap counter == 4 and dialog "Copied to" shown |
| UX-02 Subset copy, exact counter | AU/ui/CopyPagesScreenTest | picker_should_ShowExactSelectedCountAndStageOnlySelection_When_SubsetChosen | Robolectric | Search "road", select 2 rows, assert "12 selected" style text equals 2 and plan input has 2 uuids |
| UX-03 Share lands in 1 tap; change adds 2 | APP/CaptureActivityTargetTest | share_should_SaveInOneTapWithDefaultAndTwoMoreToChange_When_SheetOpens | Robolectric | Open with default; tap Save (1). Reopen; tap row, tap graph, tap Save (3) |
| UX-04 No graph switch required | BT/merge/PageMergeServiceTest | copyFlow_should_NeverCallSwitchGraph_When_CopyingToInactiveGraph | businessTest (spy GraphManager) | Run plan+apply to inactive graph; assert `switchGraph` call count 0 and active id unchanged |
| UX-05 Single entry label; old labels gone | AU/ui/SidebarEntryPointsTest | sidebar_should_ShowOnlyCopyPagesTo_When_Rendered | Robolectric | Render sidebar and page menu; assert labels; plus string-resource grep asserts no "export pages for merge"/"Merge captured pages" |
| UX-06 Dry-run wording lines | AU/ui/CopyDialogsTest | dryRun_should_ShowRequiredLinesAndReassurance_When_SummaryNew3Combined5Unchanged20 | Robolectric | Render with summary; assert the three count lines and both reassurance sentences |
| UX-07 Confirm label contains count | AU/ui/CopyDialogsTest | confirmButton_should_ReadCopy8Pages_When_New3Combined5 | Robolectric | Assert button text "Copy 8 pages" equals new+combined; vary to 0 conflicts and 2 conflicts |
| UX-08 Re-run shows Nothing to copy | AU/ui/CopyDialogsTest | dryRun_should_DisableCommitAndShowNothingToCopy_When_NewAndCombinedZero | Robolectric | Render 0/0/20; assert message text and commit disabled |
| UX-09 Selection persists; counter announced | AU/ui/CopyPagesScreenTest | selection_should_PersistAndAnnounceCounter_When_SearchAndFiltersChange | Robolectric | Select 3, type search hiding them, assert counter live-region text "N results, 3 selected", clear search, assert still checked |
| UX-10 Select-all-matching scoped; whole graph confirmed | AU/ui/CopyPagesScreenTest | selectAll_should_SelectOnlyFilteredAndConfirmWholeGraph_When_Invoked | Robolectric | Filter to 213, tap "Select all 213 matching", assert 213; overflow "All pages in graph" shows "Select all 8,030 pages?" |
| UX-11 Current graph disabled with reason | AU/ui/CopyPagesScreenTest | destination_should_ShowCurrentGraphDisabledWithReason_When_ChooserOpened | Robolectric | Open chooser; assert source graph node disabled with text "current graph" |
| UX-12 Linked pages off by default, delta, confirm, cap | AU/ui/CopyPagesScreenTest | linkedPages_should_DefaultOffShowDeltaConfirmAbove200AndStateCap_When_Toggled | Robolectric | Assert unchecked; toggle shows "adds N pages"; N=1340 shows inline confirm and "Adding the first 1,000 of 1,340" |
| UX-13 Determinate progress, Stop reachable (renamed from Cancel, Repair pass 6), completion announced | AU/ui/CopyDialogsTest | progress_should_BeDeterministicWithReachableStopAndAnnounceCompletion_When_Running | Robolectric | Assert `progressBarRangeInfo`, Stop focusable and in tab order; on finish assert live-region text |
| UX-14 Stop reports Stopped after X of Y with copied/not-copied counts in words | AU/ui/CopyDialogsTest | stopResult_should_ShowStoppedAfterXOfYWithContinueAndUndo_When_Stopped | Robolectric | Stop at 1,200/4,000; assert title, "already copied" text, Continue and Undo buttons |
| UX-15 Result dialog with failures and Retry failed | AU/ui/CopyDialogsTest | result_should_ListCategoriesFailedPagesAndActions_When_New3Combined5Failed1 | Robolectric | Assert categories text, expandable failed list, "Retry failed", "Review 2 conflicts", "Undo this copy" |
| UX-16 Undo available 7 days, scoped, reports edited | AU/ui/CopyDialogsTest | undo_should_ShowForRecentRunAndReportEditedBlocks_When_Within7Days | Robolectric + BT/merge/MergeUndoTest | Dialog text "Removes the 3 pages and 12 blocks"; hidden after 7 days; partial-undo dialog names edited block |
| UX-17 Stale plan never commits silently | AU/ui/CopyDialogsTest | stalePlan_should_ShowBannerWithNewCountsAndRequireSecondPress_When_PlanStale | Robolectric | Return `PlanStale`; assert "Things changed - review again", new counts, no apply invoked until second press |
| UX-18 Interrupted copy notice | AU/ui/CopyDialogsTest | interrupted_should_OfferResumeDismissAndCantResumeReason_When_InProgressManifestAtLaunch | Robolectric | Start with InProgress manifest; assert notice; with staging swept assert "Can't resume" text with "Open picker" |
| UX-19 Conflict review actions | AU/ui/ConflictReviewTest | conflictReview_should_ListRowsAndHandleMarkResolvedRemoveBlockOpen_When_TwoConflictsExist | Robolectric | Assert page name + original neighbor; Mark resolved removes the flag and a re-copy adds nothing; Remove this block confirms it returns on re-copy, Remove this block deletes only flagged block and shows Undo snackbar; Open navigates; copy never blocked |
| UX-20 Conflict label is text | AU/ui/ConflictReviewTest | conflictBlock_should_ShowTextConflictBadge_When_MergeConflictPropertyPresent | Robolectric | Assert node with text "Conflict" exists on block; no color-only reliance |
| UX-21 Overlay shows actual destination | APP/CaptureActivityTargetTest | overlayRow_should_ReadSavingToPersonalGraphTodaysJournal_When_DefaultWorkLastPersonalRememberOn | Robolectric | Open share; assert row text |
| UX-22 Destination change keeps text and focus | APP/CaptureActivityTargetTest | changeDestination_should_KeepTypedTextAndRestoreFocus_When_MenuClosed | Robolectric | Type text, change graph, assert text equal and text field focused |
| UX-23 Override updates last-used only | BT/capture/CaptureTargetSettingsTest | override_should_LeaveDefaultUnchanged_When_UserPicksOtherGraph | businessTest | Pick personal; assert default still work, last == personal |
| UX-24 Fallback is announced, not silent | APP/CaptureActivityTargetTest | overlay_should_ShowFallbackNote_When_ResolvedTargetRemoved | Robolectric | Remove work; open share; assert one-line note naming actual graph |
| UX-25 Unavailable: text kept, Save to fallback / Retry / Queue for later | APP/CaptureActivityTargetTest | unavailableTarget_should_KeepTextAndOfferThreeActions_When_GrantRevoked | Robolectric | Revoke grant; assert error row, text visible, three buttons |
| UX-26 Never dismiss on failed save | APP/CaptureActivityTargetTest | overlay_should_StayOpenWithErrorAndActions_When_SaveFails | Robolectric | Fake appender returns Left; assert activity not finishing, snackbar and actions |
| UX-27 Redelivery creates no duplicate | BT/capture/JournalAppenderTest | append_should_YieldOneBlockAndShowAlreadyAdded_When_SameShareRedelivered | businessTest + APP/CaptureActivityTargetTest | Replay same captureId; one block; overlay shows "Already added" |
| UX-28 Desktop chooser Alt+G and toast | JT/capture/CapturePopupUiTest | popup_should_OpenChooserOnAltGAndToastGraphName_When_Saved | jvmTest (real renderer; behind jvm-display-check.sh) + Manual | Press Alt+G, pick Work, Enter; assert toast "Saved to Work graph's journal"; Manual: Tab reaches chooser |
| UX-29 Pending shares indicator and actions | AU/ui/PendingSharesTest | indicator_should_ShowCountAndOfferCopyDiscardWithConfirmRetry_When_TwoQueued | Robolectric | Assert "2 shares queued for Work graph"; Discard shows first 80 chars confirm; Copy text; Retry now |
| UX-30 Every error state has message and exit | AU/ui/ErrorStatesTest | everyErrorState_should_ExposeMessageAndAtLeastOneExitAction_When_AllStatesRendered | Robolectric (parametrized over S2-S13 error states) + Manual walkthrough | Render each state from ux.md tables; assert cause text and an action node |
| UX-31 Unwritable copy destinations disabled with reason, never queued | AU/ui/CopyPagesScreenTest | destination_should_BeDisabledWithReasonAndNotHidden_When_EncryptedGrantLostOrUnsupported | Robolectric | Fake capabilities for each reason; assert disabled node text e.g. "Can't write here: folder access was revoked", node not selectable, Review stays disabled, no ShareInbox entry |
| UX-32 Undo/conflict/setting failures inline with Retry | AU/ui/ErrorStatesTest | actionFailures_should_ShowInlineMessageAndRetry_When_UndoConflictOrSettingWriteFails | Robolectric | Force each failure; assert inline text and Retry; manifest retained after undo failure |
| UX-33 No second graph explained | AU/ui/CopyPagesScreenTest | copyFlow_should_ShowNeedSecondGraphWithAddGraph_When_OnlyOneGraphRegistered | Robolectric | One graph registered; assert text and "Add a graph" |
| UX-34 Keyboard navigable, visible focus | AU/ui/CopyPagesScreenTest | tabOrder_should_FollowSearchChipsListOptionsDestinationActions_When_TabPressed | Robolectric + Manual desktop pass | Drive Tab key events and assert order; Manual: verify focus ring visible on Desktop/Web |
| UX-35 Row checkbox semantics | AU/ui/CopyPagesScreenTest | row_should_ExposeToggleableCheckboxWithMergedText_When_Roadmap14BlocksChecked | Robolectric | Assert role Checkbox and merged text "Roadmap, 14 blocks, checked" |
| UX-36 Live regions and text lines | AU/ui/CopyPagesScreenTest | counters_should_UsePoliteLiveRegionAndTextCategories_When_Rendered | Robolectric | Assert `liveRegion=Polite` on counter, progress text, and category lines are Text nodes |
| UX-37 Dialog focus trap, Cancel first for Undo, restore | AU/ui/CopyDialogsTest | dialogs_should_TrapFocusStartOnCancelForUndoAndRestoreInvoker_When_OpenedAndClosed | Robolectric | Open Undo dialog; assert initial focus on Cancel; close; assert invoker focused |
| UX-38 Desktop shortcuts | AU/ui/CopyPagesScreenTest + JT/capture/CapturePopupUiTest | shortcuts_should_ToggleSelectAllCancelAndGateEnter_When_KeysPressed | Robolectric (Space, Ctrl+A, Esc, Enter gating) + jvmTest (Alt+G) | Space toggles row; Ctrl/Cmd+A selects filtered; Esc asks discard when selection > 0; Enter in search does not activate |
| UX-39 Overlay button node and TalkBack pause | APP/CaptureActivityTargetTest | destinationRow_should_BeSingleNodeWithDescriptionAndPauseAutoFinish_When_MenuOpen | Robolectric + Manual TalkBack | Assert contentDescription "Saving to Personal graph. Double-tap to change"; advance timer with menu open, activity not finished; Manual TalkBack pass |
| UX-40 48dp targets, 4.5:1 contrast, light and dark | AU/ui/AccessibilityBaselineTest + CT/ui/ContrastRatioTest | targets_should_Be48dpAndPairsMeet4_5Contrast_When_LightAndDarkThemes | Robolectric + commonTest + Manual dark visual check | Assert `assertHeightIsAtLeast(48.dp)` on actionables; compute ratios for token pairs; Manual: eyeball dark theme |
| UX-42 Esc/Back rules | JT/capture/CaptureControllerTargetTest + APP/CaptureActivityTargetTest + AU/ui/CopyPagesScreenTest | see REQ-10/REQ-9/REQ-3 Esc and Back rows | businessTest/Robolectric | Desktop Esc with text asks; Android Back auto-saves, queues on failure; picker Esc/Back with selection asks |
| UX-43 Pull entry points, no push on iOS/Web | AU/ui/PullEntryPointsTest | entryPoints_should_ShowCopyFromAndHideCopyTo_When_PullOnlyPlatform | Robolectric (pull-only flag) + Manual iOS/Web | Sidebar, palette and switcher row; header "Copying into <graph>" |
| UX-44 Unreadable source disabled with reason | AU/ui/PullSourceChooserTest | source_should_BeDisabledWithReasonAndReselectActionOnlyWhereRegrantable_When_NoGrantOrUnsupported | Robolectric | All reasons; "No graph to copy from" state with Close |
| UX-45 Disabled filters in pull | AU/ui/CopyPagesScreenTest | pullFilters_should_ShowDisabledNoteForTagsPropertiesAndLinked_When_PullDirection | Robolectric | Name search, Pages/Journals, date range work; Select all N matching |
| UX-46 Definite pull results | AU/ui/CopyResultDialogTest + BT/merge/PullCopyFlowTest | result_should_NeverMentionQueued_When_PullRun | Robolectric + businessTest | Includes Retry failed and Undo |
| UX-47 8k-page responsiveness | BT/merge/PullCopyFlowTest + Manual iOS/Web | listing_should_PageAtMost100AndDryRunShowChunkProgressWithCancel_When_8kPageSource | businessTest + Manual | Manual jank check on a device/browser |
| UX-41 Present on all four platforms | AU/ui/SettingsCaptureSectionTest + Manual iOS/Web + CI compile | captureSection_should_ShowNoteInsteadOfHiding_When_PlatformHasNoExternalShare | Robolectric + Manual (iOS simulator, Web) + compile gates | Assert note text when `hasExternalShare=false`; Manual: open Copy flow, Settings Capture, inbox chip on iOS and Web |

## Repair pass 6 additions (triad)

Rows added for the new plan content. Same aliases as above. "Gate" = release gate in plan.md "Release gates, ordering and derived scope".

| Req / UX | File | Test name | Type | Notes / Gate |
|---|---|---|---|---|
| B3 / REQ-9 one-tap metric | BT/capture/JournalAppenderOffGraphTest | already listed (Spike 0.1.4 fixtures) | Integration | One tap claimed only for guard-passing fixtures; PR reports measured P |
| M1-M5 metrics source | BT/merge/MetricsLogContractTest | logLines_should_HaveExactKeySetAndNoBodies_When_CopyUndoAndShareComplete | Integration (recording Logger) | Story 5.1.3; the logs are the local-only measurement source; Gate 1 |
| REQ-7 journal rules | CT/db/PageFileResolverTest | resolveJournal_should_ReuseExistingDashSeparatedStem_When_FileExists | Unit | Story 1.2.2 rules; no literal journal file name in any AC |
| REQ-7 journal rules | CT/db/PageFileResolverTest | resolveJournal_should_RoundTripStemThroughJournalUtils_When_GeneratedDates | Unit (property) | already listed; extended with separator axis |
| REQ-7 journal rules | BT/merge/TargetWriterCapabilitiesTest | journalOffGraph_should_ReturnPlatformUnsupported_When_ConfigDeclaresNonDefaultFileNameFormat | Unit | Conservative stop (no code reads this setting today) |
| NFR path safety | CT/db/PageFileResolverTest | resolve_should_StayInsideFolderOrReturnInvalidPageName_When_NameHasDotDotAbsoluteNulBackslashOrNamespaceEscape | Unit (property + named cases) | Task 1.2.2e |
| NFR path safety | JT/db/PathContainmentSymlinkTest | write_should_RefuseAndChangeNothingOutsideRoot_When_PageFileIsSymlinkOutsideGraph | Integration (real temp dir) | Task 1.2.2e / 2.3.1e; skipped with message where symlinks are unsupported |
| NFR path safety | BT/merge/MergeStagingDirectoryTest, AssetCopierTest, MarkdownTargetWriterTest, CT/merge/MarkdownSourceGraphReaderParseTest | containment cases per call site | Unit/Integration | staging names, asset destinations, pull listings, manifest delete targets |
| NFR crash safety | BT/capture/ShareInboxTest | inbox_should_RecoverTextAndNeverDuplicate_When_CrashAfterTmpAfterRenameOrMidImage | Integration (FakeFileSystem fault injection) | Task 4.4.1a |
| NFR crash safety | BT/capture/ShareInboxTest | inbox_should_QuarantineAndKeepItem_When_ChecksumFailsTruncatedOrFutureVersion | Integration | never deletes; shows "couldn't be read" |
| REQ-17 / ADR-004 | BT/capture/ShareInboxTest | unassigned_should_RekeyOnceToFirstGraphAndDrainOnce_When_FirstGraphCreated | Integration | also: crash during re-key; replay is `AlreadyPresent` |
| REQ-17 Gate 2 | AU/ui/QueuedSharesPanelTest | actions_should_CopyTextAndDiscardWithConfirm_When_GraphGone ; semantics_should_ExposeCustomActionsWithGraphName | Robolectric | Task 4.4.1d |
| REQ-1 "flagged" Gate 2 | AU/ui/ConflictReviewTest | markResolved_should_ClearFlagAndRecopyAddNothing ; removeCopy_should_ConfirmAndRecopyReAddFlaggedOnce ; rows_should_ExposeMergedNodeWithCustomActions | Robolectric + BT | ADR-002 rev. 4; Story 3.3.2 |
| UX S5 wording | AU/ui/CopyDialogsTest | progress_should_ShowStopNotCancelAndPartialWriteResultText_When_StoppedMidRun | Robolectric | Story 3.3.1 |
| UX-51 loading | AU/ui/CopyPagesScreenTest, CopyDialogsTest, ConflictReviewTest, CaptureActivityTargetTest, PullSourceChooserTest | loading_should_ShowSkeletonOrCheckingTextAndAnnouncePolitely_When_DataPending (one per surface) | Robolectric with slow fakes | picker 8k first page, destination probing (Task 3.1.1e), dry-run chunks, conflict list, share resolver, pull index (Task 4.5.3g) |
| UX-48 large text | AU/ui/CopyPagesScreenTest (+ConflictReviewTest, QueuedSharesPanelTest, CaptureActivityTargetTest) | layout_should_NotClipAndKeep48dp_When_FontScale2 | Robolectric `fontScale=2.0` | Task 3.2.1f |
| UX-49 RTL | AU/ui/CopyPagesScreenTest (+ConflictReviewTest) | layout_should_MirrorAndFlipArrowKeys_When_RtlLocaleAndRtlPageName | Robolectric RTL | Task 3.2.1f |
| UX-50 Web keyboard | Manual Web checklist (+WT smoke if a browser harness exists, none today) | picker/source chooser/conflict list reachable by Tab, roving arrows, Space/Enter/Esc; Ctrl+A only inside list | Manual (Chrome) | UNVERIFIED until run; Gate 3 for pull, Gate 2 for conflict list |
| UX S10 Back toast | APP/CaptureActivityTargetTest | back_should_SaveThenToastWithGraphNameUndoAndChange_When_TextPresent | Robolectric | Task 4.2.1h; Undo removes the block, `last_graph` not changed by Undo |
| Gate 2 last-used | BT/ui/CopyPagesViewModelTest | lastUsed_should_PreselectOnlyWhenValidAndWritable_AndNeverTouchCaptureKeys | Integration | Task 3.1.1d |
| REQ-11 quick-add | BT/capture/JournalAppenderTest | quickAdd_should_UseResolverAndQueueUnaddressableTarget_When_PlatformHasEntryPoint | Integration | Task 4.5.1f, conditional on 4.5.1e |
| Bazel | CI: `bazel build //kmp:desktop_app`, `bazel test //kmp:business_tests` | newClasses_should_AppearInRun | CI | Story 5.1.3a; other test BUILD files checked by running them |
| Phase 0 gate | spikes 0.1.1-0.1.5 | see Spike rows above | Real FS / device / manual | Gate 0: only these run before the go/no-go checkpoint; results flip ADR status per plan.md "Phase 0 checkpoint" |

Known gaps (Repair pass 6): no usability session validates the "Copy" verb, "combined" or "Mark resolved" wording; demand (A-DEMAND) and persona are UNVERIFIED; metrics M1-M5 can only observe installs the owner controls.

## Test Stack
- **Unit**: `kotlin.test` `@Test` + `kotest-assertions-core`; property tests with `kotest-property` (`Arb`/`checkAll(500)` inside `runTest`) in `commonTest`, so one run covers JVM, Android, iOS and wasmJs. Pure logic (merge, remap, splicer, resolver, selection, filter, capture-target resolver) lives here.
- **Integration**: `businessTest` with in-memory repositories, in-memory SQLite driver, okio `FakeFileSystem`, a fake slow `DriverFactory`, a recording `Logger`, and a spy `GraphManager`. `TargetWriterContractTest` is one abstract suite run against `MarkdownTargetWriter` and `ActiveTargetWriter`.
- **UX / behavior**: Robolectric Compose tests in `androidUnitTest` and `androidApp/src/test` (headless); one `jvmTest` desktop popup test that needs a real window and runs only through `scripts/jvm-display-check.sh`; Manual checklists for TalkBack, Web, iOS, and dark-theme visuals. Playwright is not used: the app is Compose, not DOM, and the Web target has no existing browser-automation harness in the repo.
- **Commands**: `bazel test //kmp:business_tests`; `testDebugUnitTest` via `./gradlew testDebugUnitTest` (or `bazel test //kmp/src/androidUnitTest/kotlin:android_unit_tests --config=android`); `scripts/jvm-display-check.sh -- bazel test //kmp:jvm_tests --sandbox_add_mount_pair=/tmp/.X11-unix --test_env=DISPLAY --test_env=XAUTHORITY`; compile gates `./gradlew :kmp:compileTestKotlinWasmJs -PenableJs=true` and `:kmp:compileTestKotlinIosSimulatorArm64`. A `jvmTest` UI failure not run through the display script is unverified, not a regression.
- **Placement exceptions**: `CaptureControllerTargetTest` is `jvmTest` only because `CaptureController` is in `jvmMain`; it tests state, not rendering. If the logic is moved to `commonMain` it should move to `businessTest`. `SafAtomicReplaceSpikeTest` needs the Android SAF provider.

## Migration test
N/A. plan.md's Migration Plan adds no table (queries only, plus `src-id::` block properties and JSON files), so there is no schema up/down to reverse and no `migration_should_be_reversible` test. The guard is instead `MigrationRunnerSchemaSyncTest` continuing to pass unchanged, the CI job "SQLDelight generated sources" (regenerated `kmp/src/generated/sqldelight/` committed), and `QueryPlanAuditTest` entries for the two new read-only queries. Reversibility is by code revert plus manifest undo (REQ-16).

## Coverage Targets and How to Measure

| Stack | Coverage command | Target |
|---|---|---|
| Kotlin/JVM (commonMain merge/capture code via jvmTest + businessTest) | `./gradlew jacocoTestReport` then check `kmp/build/reports/jacoco/` (if the task is not configured in this repo, use `./gradlew jvmTest` pass/fail and the mapping table above as the gate) | >= 80% line on `merge/` and `capture/` packages; 100% of public `PageMergeService`, `TargetWriterRouter`, `JournalAppender`, `ShareInbox` methods have happy and error path |

- All public service methods: happy path + error paths covered (rows above per REQ).
- All external integrations (filesystem, SAF, DB, GraphManager lifecycle): unit-level fakes plus at least one integration test (REQ-7, REQ-12).
- UX acceptance criteria: each of UX-01..UX-51 has a test or manual step in the UX table above.
- Known gaps, to be stated in the PR: UX-28 jvmTest, UX-39 TalkBack, UX-41/UX-43/UX-47 on real iOS/Web and the dark-theme half of UX-40 depend on a human or a display; Story 4.2.2 Direct Share (Gate 2, ordered late) has no test rows yet; they are specified when the story is built, and its system-chooser behavior needs a manual Android device check; `merge_force_inbox` was cut from v1 and has no tests (the structural "no ForcedInbox reason" row guards against it creeping back); the removed apply-on-activate tests are replaced by `IosWebCopyGatingTest` and the pull-copy tests (`PullCopyFlowTest`, `SourceReaderParityTest`, `MarkdownSourceGraphReaderParseTest`). iOS/Web pull-copy has no CI-run test against a real iOS bookmark or browser directory handle: that is Spike 0.1.5 (manual, recorded in ADR-001), so real-device source reading is UNVERIFIED until it runs, and pull omits assets, linked-page closure and tag/property filters in v1. Spike 0.1.1's Android device pass and Spike 0.1.4 against a real graph are manual or environment-dependent and are recorded in ADR-001, not in CI.

## Repair pass 7 additions (triad round 2)

Gate 1 now includes the conflict review screen (UX-19, ConflictReviewTest) and the queued-share rescue actions (Task 4.4.1d tests); their rows above run for the Gate 1 release, not Gate 2. Vocabulary check: dry-run and result text use "unchanged" (never "identical") and the single-block action is "Remove this block"; a Robolectric constants test asserts neither "identical" nor "Remove copy" appears in user-facing strings (UX-06, UX-19).

| Req / UX | File | Test name | Type | Notes / Gate |
|---|---|---|---|---|
| UX S13 Android visibility | APP/QueuedShareIndicatorTest | queuedBadge_should_ShowOnGraphSwitcherAndAppStartNotice_When_InboxHasItems | Robolectric | Task 4.4.1d; Gate 1; not Settings only |
| UX S10 Back fallback | APP/CaptureActivityTargetTest | back_should_ShowRecentCaptureNoticeAtNextStart_When_ToastHostGone | Robolectric | Story 4.2.1 fallback |
| UX S5 graph switch mid-run | BT/ui/CopyDialogsTest | progress_should_ShowBackgroundNotice_When_GraphSwitchedMidPushCopy; pullSwitch_should_ConfirmStop_When_GraphSwitchAttemptedMidPullCopy | Robolectric | Story 3.3.1 AC (pull row is Gate 3) |
| UX S2 Gate 1 variant | BT/ui/CopyPagesPickerTest | picker_should_HideLinkedPagesToggleAndLeaveDestinationEmpty_When_Gate2FlagOff | Robolectric | Release gates (plan) |
| UX S1 subtitle | AU/ui/CopyEntryTest | entry_should_ShowSubtitleAddsPagesCombinesWithExisting | Robolectric | adopted mitigation |

### Wording validation step (Repair pass 7)

- **What**: check that users read "Copy pages to...", the subtitle "Adds pages; combines with existing ones", "combined", "unchanged", "Mark resolved" and "Remove this block" the way they are meant (nothing deleted from the destination; same-named pages are combined).
- **Owner**: the repo owner (the person running the build; no outside moderator assumed).
- **Trigger and timing**: before the Gate 1 release, once the dry-run, result and conflict screens run on a real graph (end of Phase 3). It blocks the Gate 1 release tag, not Phase 4.
- **Method (cheap)**: 3 to 5 informal user tries (anyone who has two graphs or can imagine them; think-aloud on the entry, the dry run and one conflict) OR, if no testers are available, an owner dogfood checklist: for each of the six strings, write down what the owner expected it to do before pressing it, then compare with what happened; any mismatch is a wording defect.
- **Pass**: no tester (or checklist line) expects the destination's existing content to be replaced or duplicated; "Remove this block" is not confused with "Undo this copy". Failures change the constants (one file) and re-run the Robolectric wording tests.
- **Post-release proxy**: metric M4 (undo rate, undone copies / completed copies, from the `MergeUndo` and summary log lines) above 25% on the owner's installs triggers a UX review of the same strings before Gate 2 starts (ux.md Open questions). M4 only sees owner installs, so it is a weak proxy, which is why the pre-release step exists.

Known gaps (Repair pass 7): demand and persona remain UNVERIFIED pending the owner's probe; the wording step uses informal tries, not a powered study.
