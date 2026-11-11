package dev.stapler.stelekit

// `bazel test //kmp:business_tests` only runs classes listed below — a plain `srcs` glob
// (see kmp/src/businessTest/kotlin/BUILD.bazel) compiles every *Test.kt file in this source
// set, but JUnit's `@Suite.SuiteClasses` runner only executes what's named here, so a new
// test file that isn't added to this list silently never runs under Bazel. This list was
// found to cover only 25 of 101 businessTest files as of 2026-09-10; the other 76 compiled
// but never executed via this target. Bazel CI (bazel-ci.yml) only runs `//kmp:jvm_tests`,
// which does not depend on this target at all, so Bazel-side CI never exercised most of
// businessTest's tests. They do run via `./gradlew jvmTest` (Gradle's default JUnit
// auto-discovery, no suite list) — currently invoked only in the release gate
// (release.yml), not on every PR.
//
// 7 of those 76 needed real fixes rather than just adding an import: MigrationRunnerSchemaSyncTest,
// BacklogTriageRuleTest, GapBacklogSchemaTest, GapBacklogTraceabilityTest, JourneyDocsFrontmatterTest,
// JourneyStepCountRubricTest, and GitShadowWorktreeNoCoroutineScopeTest all read real repo files
// (SteleDatabase.sq, docs/journeys/*.md, project_plans/.../gap-backlog.md+plan.md,
// GitShadowWorktree.kt) or a Gradle-injected system property, none of which Bazel's jarred
// runfiles classpath provides the way Gradle's unpacked one does. Fixed by bundling each real
// file as a classpath resource (see BUILD.bazel's *_as_resource(s) targets in this package, and
// the root/kmp/androidMain BUILD.bazel filegroups they consume) and updating each test (and
// DocRepoLocator, shared by 4 of them) to try that resource first, falling back to the original
// Gradle-only resolution — the same Bazel-resource/Gradle-fallback idiom DemoFileSystemSyncTest
// already applies to DemoFileSystem.kt.
//
// This regressed a second time on 2026-09-14 (PR #327): 15 of 16 new relocation/storage-move
// test classes were added without a suite entry. AllBusinessTestsCompletenessTest now scans the
// runtime classpath for every dev.stapler.stelekit `*Test` class carrying a JUnit `@Test` method
// and fails if it isn't registered here, so a missing entry is caught by this suite itself
// instead of relying on someone noticing.
import dev.stapler.stelekit.ui.nav.PersistedScreenIdTest
import dev.stapler.stelekit.ui.nav.NavModelExhaustivenessTest
import dev.stapler.stelekit.ui.nav.NavLabelUniquenessTest
import dev.stapler.stelekit.ui.nav.LibraryRedirectTest
import dev.stapler.stelekit.ui.library.LibraryViewModelStateTest
import dev.stapler.stelekit.ui.library.LibraryFilterSqlParityTest
import dev.stapler.stelekit.ui.library.LibraryAnnotatedJoinTest
import dev.stapler.stelekit.ui.library.LargeLibraryBatchingTest
import dev.stapler.stelekit.ui.library.LibrarySketchFilterTest
import dev.stapler.stelekit.ui.library.LibrarySessionTest
import dev.stapler.stelekit.ui.library.LibraryViewModelActionsTest
import dev.stapler.stelekit.llm.ProjectAnalysisServiceTest
import dev.stapler.stelekit.llm.ProjectAnalysisGoldenPromptTest
import dev.stapler.stelekit.llm.ProjectAnalysisInboxVariantTest
import dev.stapler.stelekit.projectbridge.ProjectAnalysisStepTest
import dev.stapler.stelekit.projectbridge.OutboundPreviewTest
import dev.stapler.stelekit.projectbridge.SettleBarrierTest
import dev.stapler.stelekit.projectbridge.ProjectBridgeSettingsTest
import dev.stapler.stelekit.projectbridge.PostSyncProjectJobEntryPointTest
import dev.stapler.stelekit.projectbridge.PostSyncProjectCoordinatorTest
import dev.stapler.stelekit.projectbridge.PostSyncProjectJobFailureTest
import dev.stapler.stelekit.capture.TargetWriterRouterHolderTest
import dev.stapler.stelekit.capture.CaptureTargetResolverTest
import dev.stapler.stelekit.query.QueryExecutorTest
import dev.stapler.stelekit.capture.InboxFallbackAppenderTest
import dev.stapler.stelekit.capture.JournalAppenderOffGraphTest
import dev.stapler.stelekit.db.ShareWritePathMigrationTest
import dev.stapler.stelekit.ui.NotesPathWiringTest
import dev.stapler.stelekit.db.misplaced.MisplacedFilesServiceTest
import dev.stapler.stelekit.ui.misplaced.MisplacedFilesControllerTest
import dev.stapler.stelekit.git.merge.StrayPageMergeTest
import dev.stapler.stelekit.db.GraphManagerNotesPathTest
import dev.stapler.stelekit.ui.NotesPathAccessorsTest
import dev.stapler.stelekit.db.ShareWritePathRepairTest
import dev.stapler.stelekit.capture.JournalAppenderTest
import dev.stapler.stelekit.clipboard.BlockClipboardTest
import dev.stapler.stelekit.domain.ImportServiceTest
import dev.stapler.stelekit.editor.LinkInsertionTest
import dev.stapler.stelekit.editor.commands.CommandManagerTest
import dev.stapler.stelekit.editor.commands.CommandSystemRegistryTest
import dev.stapler.stelekit.editor.commands.EssentialCommandsTest
import dev.stapler.stelekit.flashcard.FlashcardPropertiesTest
import dev.stapler.stelekit.flashcard.FlashcardReviewTest
import dev.stapler.stelekit.llm.IosAvailabilityMappingTest
import dev.stapler.stelekit.llm.LlmProviderAvailabilityTest
import dev.stapler.stelekit.llm.LlmProviderRegistryFactoryTest
import dev.stapler.stelekit.llm.LlmProviderRegistryTest
import dev.stapler.stelekit.llm.LlmSettingsTest
import dev.stapler.stelekit.performance.HistogramRegressionTest
import dev.stapler.stelekit.performance.HistogramWriterTest
import dev.stapler.stelekit.performance.PerfExporterPickerTest
import dev.stapler.stelekit.performance.PerfExporterRegressionTest
import dev.stapler.stelekit.performance.QueryStatsRepositoryTest
import dev.stapler.stelekit.db.DemoFileSystemSyncTest
import dev.stapler.stelekit.db.DemoGraphPersistenceTest
import dev.stapler.stelekit.db.GraphInfoSerializationTest
import dev.stapler.stelekit.db.GraphLoaderLfsPointerTest
import dev.stapler.stelekit.db.SplitJournalTest
import dev.stapler.stelekit.console.CommandLineTokenizerTest
import dev.stapler.stelekit.console.ConfirmPolicyTest
import dev.stapler.stelekit.console.ConsoleBackupTest
import dev.stapler.stelekit.console.DbOnlyRowsGuardTest
import dev.stapler.stelekit.console.DbRestoreLockStressTest
import dev.stapler.stelekit.console.DbRestoreTest
import dev.stapler.stelekit.console.GraphRestoreCommandTest
import dev.stapler.stelekit.console.RestoreRecoveryTest
import dev.stapler.stelekit.console.ConsoleBuildFlagsTest
import dev.stapler.stelekit.console.ConsoleSessionConfirmTest
import dev.stapler.stelekit.console.SqlConsoleWriterTest
import dev.stapler.stelekit.console.SqlWriteCommandTest
import dev.stapler.stelekit.console.SqlWriteFtsConsistencyTest
import dev.stapler.stelekit.console.SqlWriteGuardTest
import dev.stapler.stelekit.db.DirectSqlWriteOptInAllowlistTest
import dev.stapler.stelekit.console.ConsoleBoundedStreamingTest
import dev.stapler.stelekit.console.ConsoleScopeOwnershipAuditTest
import dev.stapler.stelekit.console.ConsoleSessionTest
import dev.stapler.stelekit.console.CredentialColumnInventoryTest
import dev.stapler.stelekit.console.SqlCommandTest
import dev.stapler.stelekit.console.SqlConsoleBypassTest
import dev.stapler.stelekit.console.ResultGridTest
import dev.stapler.stelekit.console.ScrollbackBufferTest
import dev.stapler.stelekit.security.RedactorSingleSiteAuditTest
import dev.stapler.stelekit.security.RedactorTest
import dev.stapler.stelekit.repository.BacklinkRepositoryTest
import dev.stapler.stelekit.sections.CrossSectionBacklinkRenderTest
import dev.stapler.stelekit.sections.NewPageAutoAssignmentTest
import dev.stapler.stelekit.ui.CommandManagerCoordinatorEquivalenceTest
import dev.stapler.stelekit.merge.MarkdownTargetWriterTest
import dev.stapler.stelekit.merge.MarkdownTargetWriterContractTest
import dev.stapler.stelekit.merge.ActiveTargetWriterContractTest
import dev.stapler.stelekit.merge.CopyEditRecopyPropertyTest
import dev.stapler.stelekit.merge.TargetWriterCapabilitiesTest
import dev.stapler.stelekit.merge.ActiveDbPageSourceTest
import dev.stapler.stelekit.merge.IosWebCopyGatingTest
import dev.stapler.stelekit.merge.PullCopyFlowTest
import dev.stapler.stelekit.merge.PullIndexingTest
import dev.stapler.stelekit.ui.PullCopyViewModelTest
import dev.stapler.stelekit.transfer.MergeUuidRoundTripSpikeTest
import dev.stapler.stelekit.ui.ToolbarActionTest
import dev.stapler.stelekit.ui.MemoryPressureListenersTest
import dev.stapler.stelekit.ui.CopyFlowControllerTest
import dev.stapler.stelekit.ui.PullCopyFlowControllerTest
import dev.stapler.stelekit.merge.InMemoryMergeStorageTest
import dev.stapler.stelekit.ui.CopyPagesViewModelTest
import dev.stapler.stelekit.platform.image.ImageSamplingTest
import dev.stapler.stelekit.platform.image.ImageTiersTest
import dev.stapler.stelekit.ui.assets.AssetImageReferenceTest
import dev.stapler.stelekit.ui.PageSelectionTest
import dev.stapler.stelekit.asset.AssetMoveServiceTest
import dev.stapler.stelekit.asset.AssetMoveServiceWalTest
import dev.stapler.stelekit.asset.AssetIndexServiceTest
import dev.stapler.stelekit.asset.AssetIndexBackfillBoundedTest
import dev.stapler.stelekit.asset.DrawingExcludedFromCloudTest
import dev.stapler.stelekit.asset.AssetIndexedBrowseTest
import dev.stapler.stelekit.asset.AssetIndexingControllerTest
import dev.stapler.stelekit.asset.AssetIndexingFailurePathsTest
import dev.stapler.stelekit.ui.assets.AssetDetailMoveTest
import dev.stapler.stelekit.asset.AssetMoveReplayEntryPointTest
import dev.stapler.stelekit.voice.VoiceCaptureViewModelTest
import dev.stapler.stelekit.voice.VoiceNoteBlockFormatTest
import dev.stapler.stelekit.voice.VoicePipelineFactoryTest
import dev.stapler.stelekit.voice.VoiceSettingsTest
import dev.stapler.stelekit.asset.AssetMediaTypeTest
import dev.stapler.stelekit.asset.AssetStoragePathResolverTest
import dev.stapler.stelekit.asset.MimeTypeDetectorTest
import dev.stapler.stelekit.asset.pipeline.AssetPipelineDrainBugTest
import dev.stapler.stelekit.asset.pipeline.AssetPipelineServiceTest
import dev.stapler.stelekit.asset.pipeline.AssetPipelineTerminalStateTest
import dev.stapler.stelekit.asset.pipeline.AssetPipelineProgressTest
import dev.stapler.stelekit.asset.pipeline.AssetPipelineOomInjectionTest
import dev.stapler.stelekit.asset.pipeline.AssetPipelineCrashLoopBreakerTest
import dev.stapler.stelekit.asset.pipeline.DefaultAssetPluginsTest
import dev.stapler.stelekit.asset.pipeline.OcrPluginTest
import dev.stapler.stelekit.asset.AssetSearchSqlTest
import dev.stapler.stelekit.asset.AssetTagSearchSqlTest
import dev.stapler.stelekit.repository.ImageAnnotationTagEscapingTest
import dev.stapler.stelekit.calibration.CalibrationBasisServiceTest
import dev.stapler.stelekit.db.CalibrationBasisMigrationTest
import dev.stapler.stelekit.asset.AssetSearchCloudDescriptionTest
import dev.stapler.stelekit.asset.CloudDescriptionStorageTest
import dev.stapler.stelekit.asset.CloudKeyLeakageAuditTest
import dev.stapler.stelekit.asset.pipeline.CloudBackfillTest
import dev.stapler.stelekit.asset.pipeline.CloudClaudePluginTest
import dev.stapler.stelekit.asset.pipeline.CloudConsentEnforcementTest
import dev.stapler.stelekit.asset.pipeline.CloudStructuralAuditTest
import dev.stapler.stelekit.asset.pipeline.EgressLedgerTest
import dev.stapler.stelekit.asset.pipeline.GrantedConsentMintSiteAuditTest
import dev.stapler.stelekit.audit.EgressSurfaceAuditTest
import dev.stapler.stelekit.llm.AnthropicMessagesClientCharacterizationTest
import dev.stapler.stelekit.asset.pipeline.AssetPluginsControllerTest
import dev.stapler.stelekit.asset.pipeline.PluginRegistryTest
import dev.stapler.stelekit.settings.PluginHostRemovalAuditTest
import dev.stapler.stelekit.auto.AudiobookNoteFormatterTest
import dev.stapler.stelekit.auto.AudiobookNoteWriterTest
import dev.stapler.stelekit.capture.ShareInboxTest
import dev.stapler.stelekit.db.AtomicFileRelocationStepTest
import dev.stapler.stelekit.db.BlockHierarchyCteTest
import dev.stapler.stelekit.db.SqlDelightBlockRepositoryTest
import dev.stapler.stelekit.db.UndoManagerTest
import dev.stapler.stelekit.db.BulkCopyVerifierTest
import dev.stapler.stelekit.db.DiskConflictBlockMatcherTest
import dev.stapler.stelekit.db.GraphLoaderDirtySetTest
import dev.stapler.stelekit.db.GraphManagerAddGraphTest
import dev.stapler.stelekit.db.GraphManagerEnrichmentCoordinatorTest
import dev.stapler.stelekit.db.GraphManagerInitAutoRestoreTest
import dev.stapler.stelekit.db.GraphManagerOnGraphLocationDeterminedTest
import dev.stapler.stelekit.db.GraphManagerPendingStorageLocationRemovalTest
import dev.stapler.stelekit.db.GraphManagerRemoveGraphTest
import dev.stapler.stelekit.db.GraphManagerSwitchLockStressTest
import dev.stapler.stelekit.db.GraphManagerSwitchNotesPathTest
import dev.stapler.stelekit.db.GraphManagerUpdateGraphPathTest
import dev.stapler.stelekit.db.GraphManagerUpdateHostDirNameTest
import dev.stapler.stelekit.db.GraphRelocationCoordinatorAppOwnedTest
import dev.stapler.stelekit.db.GraphRelocationCoordinatorCancellationTest
import dev.stapler.stelekit.db.GraphRelocationCoordinatorFailureTest
import dev.stapler.stelekit.db.GraphRelocationCoordinatorFlagClearingTest
import dev.stapler.stelekit.db.GraphRelocationCoordinatorLinkTest
import dev.stapler.stelekit.db.GraphRelocationCoordinatorLoggingTest
import dev.stapler.stelekit.db.GraphRelocationCoordinatorPathPersistenceTest
import dev.stapler.stelekit.db.GraphRelocationCoordinatorQuiesceTimeoutTest
import dev.stapler.stelekit.db.GraphRelocationCoordinatorReopenFailureTest
import dev.stapler.stelekit.db.GraphRelocationCoordinatorSameGraphSwitchTest
import dev.stapler.stelekit.db.GraphRelocationCoordinatorTest
import dev.stapler.stelekit.db.GraphSwitchInvalidationTest
import dev.stapler.stelekit.db.GraphWriteLockTest
import dev.stapler.stelekit.db.IndexDrainSectionFilterTest
import dev.stapler.stelekit.db.MigrationRunnerCoverageTest
import dev.stapler.stelekit.db.MigrationRunnerIndexTest
import dev.stapler.stelekit.db.MigrationRunnerSchemaSyncTest
import dev.stapler.stelekit.db.SketchMigrationTest
import dev.stapler.stelekit.sketch.export.SketchExportRoundTripTest
import dev.stapler.stelekit.sketch.export.SketchExporterTest
import dev.stapler.stelekit.db.SketchStubGatingTest
import dev.stapler.stelekit.repository.SketchDeleteProjectTest
import dev.stapler.stelekit.repository.SketchRepositoryContractTest
import dev.stapler.stelekit.repository.SketchStaleEditorTest
import dev.stapler.stelekit.db.RelocationStagingDirectoryTest
import dev.stapler.stelekit.merge.AssetCopierTest
import dev.stapler.stelekit.merge.LinkClosureTest
import dev.stapler.stelekit.merge.MergeManifestTest
import dev.stapler.stelekit.merge.MergeStagingDirectoryTest
import dev.stapler.stelekit.merge.MergeUndoTest
import dev.stapler.stelekit.merge.OwnedPageReplaceSpikeTest
import dev.stapler.stelekit.merge.ActiveOwnedPageWriterContractTest
import dev.stapler.stelekit.merge.OwnedFileGuardTest
import dev.stapler.stelekit.merge.OwnedHashStabilityTest
import dev.stapler.stelekit.merge.OwnedRefusalMappingTest
import dev.stapler.stelekit.merge.OwnedReplaceLockOrderTest
import dev.stapler.stelekit.merge.MetricsLogContractTest
import dev.stapler.stelekit.merge.SourceReaderParityTest
import dev.stapler.stelekit.merge.SourceReadCapabilitiesTest
import dev.stapler.stelekit.merge.PageMergeServiceTest
import dev.stapler.stelekit.ui.screens.copy.ConflictReviewViewModelTest
import dev.stapler.stelekit.merge.LargeGraphMergeTest
import dev.stapler.stelekit.merge.MergeResilienceTest
import dev.stapler.stelekit.db.SqliteStatementAnalyzerTest
import dev.stapler.stelekit.db.StorageLocationPersistenceTest
import dev.stapler.stelekit.db.WithoutRowidMigrationTest
import dev.stapler.stelekit.docs.BacklogTriageRuleTest
import dev.stapler.stelekit.docs.GapBacklogSchemaTest
import dev.stapler.stelekit.docs.GapBacklogTraceabilityTest
import dev.stapler.stelekit.docs.JourneyDocsFrontmatterTest
import dev.stapler.stelekit.docs.JourneyStepCountRubricTest
import dev.stapler.stelekit.editor.ImageAttachCallbackContractTest
import dev.stapler.stelekit.export.ExportServiceJournalRangeTest
import dev.stapler.stelekit.export.ExportServiceLinkedPagesTest
import dev.stapler.stelekit.git.GitCloneRetryCleanupTest
import dev.stapler.stelekit.jobs.TrackedCopyRunHostTest
import dev.stapler.stelekit.ui.GitSyncCoordinatorJobsTest
import dev.stapler.stelekit.git.GitConfigCloneDepthMappingTest
import dev.stapler.stelekit.git.GitOperationSupportClassifierTest
import dev.stapler.stelekit.git.GitRepositoryUnshallowTest
import dev.stapler.stelekit.git.GitShadowWorktreeNoCoroutineScopeTest
import dev.stapler.stelekit.git.GitSyncBusyCounterFetchOnlyTest
import dev.stapler.stelekit.git.GitSyncServiceChangeBackTest
import dev.stapler.stelekit.git.GitSyncServiceAutoMergeTest
import dev.stapler.stelekit.git.GitSyncServiceConflictResolutionTest
import dev.stapler.stelekit.git.GitSyncServiceErrorRoutingTest
import dev.stapler.stelekit.git.GitSyncServiceConflictMarkerGuardTest
import dev.stapler.stelekit.git.GitSyncServiceFirstSyncGateTest
import dev.stapler.stelekit.git.GitSyncServiceRateLimitRetryTest
import dev.stapler.stelekit.git.GitSyncServiceTest
import dev.stapler.stelekit.git.GitTransportFaultInjectionTest
import dev.stapler.stelekit.git.GitTransportRetryStateTest
import dev.stapler.stelekit.git.GitTransportRetryTest
import dev.stapler.stelekit.git.lfs.LfsPostPushAuditTest
import dev.stapler.stelekit.git.lfs.LfsStagingCallSitesStructuralTest
import dev.stapler.stelekit.git.lfs.GitAttributesDifferentialTest
import dev.stapler.stelekit.git.lfs.LfsGuardCoordinatorTest
import dev.stapler.stelekit.git.lfs.LfsRulesLoaderTest
import dev.stapler.stelekit.git.lfs.LfsRawBinaryNeverCommittedPropertyTest
import dev.stapler.stelekit.git.lfs.LfsRawBinaryNeverCommittedTest
import dev.stapler.stelekit.git.lfs.LfsIndexVerifierTest
import dev.stapler.stelekit.error.DomainErrorTest
import dev.stapler.stelekit.git.lfs.LfsModeResolverTest
import dev.stapler.stelekit.git.lfs.LfsGuardPropertyTest
import dev.stapler.stelekit.git.lfs.LfsGuardTest
import dev.stapler.stelekit.git.lfs.LfsPathPolicyTest
import dev.stapler.stelekit.git.lfs.GitAttributesMatcherPropertyTest
import dev.stapler.stelekit.git.lfs.GitAttributesMatcherTest
import dev.stapler.stelekit.git.lfs.LfsPointerPropertyTest
import dev.stapler.stelekit.git.lfs.LfsPointerTest
import dev.stapler.stelekit.git.lfs.GitAttributesParserTest
import dev.stapler.stelekit.git.lfs.GitWildmatchTest
import dev.stapler.stelekit.git.GitOperationSupportHelpersTest
import dev.stapler.stelekit.git.GitRefDiagnosticsTest
import dev.stapler.stelekit.domain.HtmlBlockConverterTest
import dev.stapler.stelekit.mcp.McpServerControllerTest
import dev.stapler.stelekit.git.GitSyncServiceStepRoutingTest
import dev.stapler.stelekit.capture.ActiveWriteHooksTest
import dev.stapler.stelekit.tags.LlmTagProviderBranchesTest
import dev.stapler.stelekit.llm.LlmSuggestionWriterEditsTest
import dev.stapler.stelekit.vault.VaultHeaderModelTest
import dev.stapler.stelekit.parser.MarkdownParserInlineStorageTest
import dev.stapler.stelekit.git.lfs.FileSystemLfsContentProbeTest
import dev.stapler.stelekit.git.SqlDelightGitConfigRepositoryRoundTripTest
import dev.stapler.stelekit.migration.ChangeApplierBranchesTest
import dev.stapler.stelekit.migration.PostMigrationFlusherTest
import dev.stapler.stelekit.git.GitSyncServiceLfsGuardTest
import dev.stapler.stelekit.git.GitSyncServicePeriodicTest
import dev.stapler.stelekit.git.PeriodicSyncDriverTest
import dev.stapler.stelekit.git.ConflictMarkerDiffScanTest
import dev.stapler.stelekit.git.FirstSyncPreviewSourceTest
import dev.stapler.stelekit.git.GitSyncServiceChangeGuardsTest
import dev.stapler.stelekit.git.GitSyncServicePublishFailureTest
import dev.stapler.stelekit.git.GitSyncServiceAutoCommitTest
import dev.stapler.stelekit.git.GitSyncServiceScheduledSyncTest
import dev.stapler.stelekit.git.SyncSafetyConfirmTest
import dev.stapler.stelekit.git.BannerPrecedenceTest
import dev.stapler.stelekit.git.ColdSyncRunnerTest
import dev.stapler.stelekit.git.StalenessChipStateTest
import dev.stapler.stelekit.git.SyncNotificationLogicTest
import dev.stapler.stelekit.git.SyncStalenessStoreTest
import dev.stapler.stelekit.git.SyncStatusSheetModelTest
import dev.stapler.stelekit.git.UpdatesNotificationToggleTest
import dev.stapler.stelekit.ui.FirstSyncPreviewLoaderTest
import dev.stapler.stelekit.ui.GitSyncCoordinatorTest
import dev.stapler.stelekit.git.ScheduledSyncPolicyTest
import dev.stapler.stelekit.git.EditLockTest
import dev.stapler.stelekit.git.ScheduledSyncStackE2ETest
import dev.stapler.stelekit.git.ShallowMergeTest
import dev.stapler.stelekit.git.merge.JournalMergeServiceTest
import dev.stapler.stelekit.git.merge.LogseqMergeDriverTest
import dev.stapler.stelekit.git.testsupport.StubGitRepositoryFailureSequenceTest
import dev.stapler.stelekit.llm.AndroidOnDeviceFallbackTest
import dev.stapler.stelekit.llm.CustomProviderUrlValidationTest
import dev.stapler.stelekit.llm.LlmCredentialMigrationTest
import dev.stapler.stelekit.llm.LlmCredentialStoreTest
import dev.stapler.stelekit.llm.LlmSettingsCustomProviderTest
import dev.stapler.stelekit.llm.LlmSuggestionInboxTest
import dev.stapler.stelekit.llm.LlmSuggestionWriterTest
import dev.stapler.stelekit.llm.LlmSynthesisContextBuilderTest
import dev.stapler.stelekit.llm.LlmSynthesisServiceTest
import dev.stapler.stelekit.llm.PendingLlmSuggestionTest
import dev.stapler.stelekit.llm.StelekitViewModelLlmSuggestionTest
import dev.stapler.stelekit.llm.TagSuggestionOnDeviceDefaultTest
import dev.stapler.stelekit.llm.VoiceDeviceLlmMigrationTest
import dev.stapler.stelekit.model.BlockTypeTest
import dev.stapler.stelekit.performance.OtelProviderStabilityTest
import dev.stapler.stelekit.platform.measurement.MeasurementInjectionTest
import dev.stapler.stelekit.platform.security.CredentialAccessTest
import dev.stapler.stelekit.repository.AssetRepositoryTest
import dev.stapler.stelekit.repository.SqlDelightAssetRepositoryContractTest
import dev.stapler.stelekit.repository.SqlDelightSearchRepositoryTest
import dev.stapler.stelekit.repository.WikilinkBatchInsertTest
import dev.stapler.stelekit.resilience.RetryPoliciesTest
import dev.stapler.stelekit.sections.DeviceProfileTest
import dev.stapler.stelekit.sections.ThreeStateSubscriptionTest
import dev.stapler.stelekit.tags.LlmTagProviderTest
import dev.stapler.stelekit.tags.TagAvailabilityPollerTest
import dev.stapler.stelekit.tags.TagSuggestionEngineTest
import dev.stapler.stelekit.tags.TagSuggestionViewModelTest
import dev.stapler.stelekit.tags.WikiLinkExtractorTest
import dev.stapler.stelekit.transfer.qrcode.QrImportServiceTest
import dev.stapler.stelekit.transfer.qrcode.QrTransferCoordinatorTest
import dev.stapler.stelekit.transfer.qrcode.TransferSessionTest
import dev.stapler.stelekit.ui.StelekitViewModelNavigationTest
import dev.stapler.stelekit.ui.StelekitViewModelUiToggleTest
import dev.stapler.stelekit.ui.screens.DiskConflictFullScreenStateTest
import dev.stapler.stelekit.ui.components.MermaidRenderKeyTest
import dev.stapler.stelekit.ui.components.MermaidRenderResultTest
import dev.stapler.stelekit.ui.components.MermaidSvgAspectRatioTest
import dev.stapler.stelekit.ui.state.BlockInvalidationIntegrationTest
import dev.stapler.stelekit.ui.transfer.QrDecodeViewModelTest
import dev.stapler.stelekit.ui.transfer.QrEncodeViewModelTest
import dev.stapler.stelekit.util.FractionalIndexingTest
import dev.stapler.stelekit.voice.GenAiErrorMappingTest
import dev.stapler.stelekit.voice.LlmProviderSupportTest
import dev.stapler.stelekit.voice.MlKitAvailabilityMappingTest
import dev.stapler.stelekit.voice.VoicePipelineConfigTest
import dev.stapler.stelekit.audit.TestHygieneAuditTest
import dev.stapler.stelekit.audit.TestPlacementAuditTest
import dev.stapler.stelekit.google.GoogleAuthCodeExchangeTest
import dev.stapler.stelekit.google.GoogleDriveFlowTest
import dev.stapler.stelekit.google.GooglePhotosFlowTest
import dev.stapler.stelekit.wiring.FirstOpenSmokeCatalogTest
import dev.stapler.stelekit.wiring.ObservableEffectCatalogTest
import dev.stapler.stelekit.wiring.PlantedDeadCodeConstructorTest
import dev.stapler.stelekit.wiring.WiringAllowlistRenewalTest
import dev.stapler.stelekit.wiring.WiringCompletenessTest
import dev.stapler.stelekit.testing.GoldenFileGuardTest
import org.junit.runner.RunWith
import org.junit.runners.Suite

@RunWith(Suite::class)
@Suite.SuiteClasses(
    PersistedScreenIdTest::class,
    NavModelExhaustivenessTest::class,
    NavLabelUniquenessTest::class,
    LibraryRedirectTest::class,
    LibraryViewModelStateTest::class,
    LibrarySketchFilterTest::class,
    LibrarySessionTest::class,
    LibraryViewModelActionsTest::class,
    LibraryFilterSqlParityTest::class,
    LibraryAnnotatedJoinTest::class,
    LargeLibraryBatchingTest::class,
    ProjectAnalysisServiceTest::class,
    ProjectAnalysisGoldenPromptTest::class,
    ProjectAnalysisInboxVariantTest::class,
    ProjectAnalysisStepTest::class,
    OutboundPreviewTest::class,
    SettleBarrierTest::class,
    ProjectBridgeSettingsTest::class,
    PostSyncProjectJobEntryPointTest::class,
    PostSyncProjectCoordinatorTest::class,
    PostSyncProjectJobFailureTest::class,
    TargetWriterRouterHolderTest::class,
    AllBusinessTestsCompletenessTest::class,
    BlockClipboardTest::class,
    CaptureTargetResolverTest::class,
    JournalAppenderTest::class,
    JournalAppenderOffGraphTest::class,
    ShareWritePathMigrationTest::class,
    NotesPathWiringTest::class,
    MisplacedFilesServiceTest::class,
    MisplacedFilesControllerTest::class,
    StrayPageMergeTest::class,
    ShareWritePathRepairTest::class,
    GraphManagerNotesPathTest::class,
    NotesPathAccessorsTest::class,
    InboxFallbackAppenderTest::class,
    ImportServiceTest::class,
    LinkInsertionTest::class,
    CommandManagerTest::class,
    CommandSystemRegistryTest::class,
    EssentialCommandsTest::class,
    FlashcardPropertiesTest::class,
    FlashcardReviewTest::class,
    IosAvailabilityMappingTest::class,
    LlmProviderAvailabilityTest::class,
    LlmProviderRegistryFactoryTest::class,
    LlmProviderRegistryTest::class,
    LlmSettingsTest::class,
    dev.stapler.stelekit.llm.ProjectAnalysisSettingsTest::class,
    HistogramRegressionTest::class,
    HistogramWriterTest::class,
    PerfExporterPickerTest::class,
    PerfExporterRegressionTest::class,
    QueryStatsRepositoryTest::class,
    BacklinkRepositoryTest::class,
    CrossSectionBacklinkRenderTest::class,
    NewPageAutoAssignmentTest::class,
    CommandManagerCoordinatorEquivalenceTest::class,
    DemoFileSystemSyncTest::class,
    DemoGraphPersistenceTest::class,
    GraphInfoSerializationTest::class,
    GraphLoaderLfsPointerTest::class,
    SplitJournalTest::class,
    MarkdownTargetWriterTest::class,
    MarkdownTargetWriterContractTest::class,
    ActiveTargetWriterContractTest::class,
    CopyEditRecopyPropertyTest::class,
    TargetWriterCapabilitiesTest::class,
    ActiveDbPageSourceTest::class,
    CopyFlowControllerTest::class,
    PullCopyFlowControllerTest::class,
    InMemoryMergeStorageTest::class,
    CopyPagesViewModelTest::class,
    IosWebCopyGatingTest::class,
    PullCopyFlowTest::class,
    PullIndexingTest::class,
    PullCopyViewModelTest::class,
    PageSelectionTest::class,
    ImageSamplingTest::class,
    ImageTiersTest::class,
    AssetImageReferenceTest::class,
    dev.stapler.stelekit.merge.TargetWriterRouterInFlightSwitchTest::class,
    dev.stapler.stelekit.db.GraphLocatorTest::class,
    MergeUuidRoundTripSpikeTest::class,
    ToolbarActionTest::class,
    MemoryPressureListenersTest::class,
    dev.stapler.stelekit.platform.diagnostics.ExitRecordFormatterTest::class,
    dev.stapler.stelekit.platform.diagnostics.ProcStatParserTest::class,
    dev.stapler.stelekit.platform.diagnostics.LogRedactorTest::class,
    dev.stapler.stelekit.ui.annotate.AnnotationEditorCrashGuardTest::class,
    dev.stapler.stelekit.ui.annotate.AnnotationPersistenceQueueTest::class,
    dev.stapler.stelekit.repository.MeasurementWritesPriorityTest::class,
    dev.stapler.stelekit.repository.SqlDelightMeasurementAnnotationWritesTest::class,
    dev.stapler.stelekit.ui.annotate.AnnotationEditorPersistenceTest::class,
    dev.stapler.stelekit.ui.annotate.ExportStyleTest::class,
    dev.stapler.stelekit.ui.annotate.ExportLabelFontTest::class,
    dev.stapler.stelekit.ui.annotate.ExifOrientationTest::class,
    dev.stapler.stelekit.repository.RealActorOrderingTest::class,
    dev.stapler.stelekit.ui.annotate.LeavePolicyTest::class,
    dev.stapler.stelekit.ui.annotate.AnnotationEditorUnitTest::class,
    dev.stapler.stelekit.ui.annotate.PostKillBannerControllerTest::class,
    dev.stapler.stelekit.ui.annotate.UndoDepthTest::class,
    dev.stapler.stelekit.ui.annotate.UndoHistoryTest::class,
    dev.stapler.stelekit.ui.components.CoilSeamTest::class,
    dev.stapler.stelekit.ui.components.ImageLoaderConstructionCountTest::class,
    dev.stapler.stelekit.db.FixtureGraphCountsTest::class,
    AssetMoveServiceTest::class,
    AssetMoveServiceWalTest::class,
    AssetIndexServiceTest::class,
    AssetIndexBackfillBoundedTest::class,
    DrawingExcludedFromCloudTest::class,
    AssetIndexedBrowseTest::class,
    AssetIndexingControllerTest::class,
    AssetIndexingFailurePathsTest::class,
    AssetDetailMoveTest::class,
    AssetMoveReplayEntryPointTest::class,
    VoiceCaptureViewModelTest::class,
    VoiceNoteBlockFormatTest::class,
    VoicePipelineFactoryTest::class,
    VoiceSettingsTest::class,
    AssetMediaTypeTest::class,
    AssetStoragePathResolverTest::class,
    MimeTypeDetectorTest::class,
    AssetPipelineDrainBugTest::class,
    AssetPipelineServiceTest::class,
    AssetPipelineTerminalStateTest::class,
    AssetPipelineProgressTest::class,
    AssetPipelineOomInjectionTest::class,
    AssetPipelineCrashLoopBreakerTest::class,
    DefaultAssetPluginsTest::class,
    OcrPluginTest::class,
    PluginRegistryTest::class,
    AssetSearchSqlTest::class,
    AssetTagSearchSqlTest::class,
    ImageAnnotationTagEscapingTest::class,
    CalibrationBasisServiceTest::class,
    CalibrationBasisMigrationTest::class,
    AssetSearchCloudDescriptionTest::class,
    CloudDescriptionStorageTest::class,
    CloudKeyLeakageAuditTest::class,
    CloudClaudePluginTest::class,
    CloudBackfillTest::class,
    CloudConsentEnforcementTest::class,
    CloudStructuralAuditTest::class,
    EgressLedgerTest::class,
    GrantedConsentMintSiteAuditTest::class,
    EgressSurfaceAuditTest::class,
    AnthropicMessagesClientCharacterizationTest::class,
    AssetPluginsControllerTest::class,
    PluginHostRemovalAuditTest::class,
    AudiobookNoteFormatterTest::class,
    AudiobookNoteWriterTest::class,
    AtomicFileRelocationStepTest::class,
    ShareInboxTest::class,
    BlockHierarchyCteTest::class,
    SqlDelightBlockRepositoryTest::class,
    UndoManagerTest::class,
    BulkCopyVerifierTest::class,
    DiskConflictBlockMatcherTest::class,
    GraphLoaderDirtySetTest::class,
    GraphManagerAddGraphTest::class,
    GraphManagerEnrichmentCoordinatorTest::class,
    GraphManagerInitAutoRestoreTest::class,
    GraphManagerOnGraphLocationDeterminedTest::class,
    GraphManagerPendingStorageLocationRemovalTest::class,
    GraphManagerRemoveGraphTest::class,
    GraphManagerSwitchLockStressTest::class,
    GraphManagerSwitchNotesPathTest::class,
    GraphManagerUpdateGraphPathTest::class,
    GraphManagerUpdateHostDirNameTest::class,
    GraphRelocationCoordinatorAppOwnedTest::class,
    GraphRelocationCoordinatorCancellationTest::class,
    GraphRelocationCoordinatorFailureTest::class,
    GraphRelocationCoordinatorFlagClearingTest::class,
    GraphRelocationCoordinatorLinkTest::class,
    GraphRelocationCoordinatorLoggingTest::class,
    GraphRelocationCoordinatorPathPersistenceTest::class,
    GraphRelocationCoordinatorQuiesceTimeoutTest::class,
    GraphRelocationCoordinatorReopenFailureTest::class,
    GraphRelocationCoordinatorSameGraphSwitchTest::class,
    GraphRelocationCoordinatorTest::class,
    GraphSwitchInvalidationTest::class,
    GraphWriteLockTest::class,
    IndexDrainSectionFilterTest::class,
    MigrationRunnerCoverageTest::class,
    MigrationRunnerIndexTest::class,
    MigrationRunnerSchemaSyncTest::class,
    RelocationStagingDirectoryTest::class,
    MergeStagingDirectoryTest::class,
    MergeManifestTest::class,
    MergeUndoTest::class,
    OwnedPageReplaceSpikeTest::class,
    ActiveOwnedPageWriterContractTest::class,
    OwnedFileGuardTest::class,
    OwnedHashStabilityTest::class,
    OwnedRefusalMappingTest::class,
    OwnedReplaceLockOrderTest::class,
    MetricsLogContractTest::class,
    SourceReaderParityTest::class,
    SourceReadCapabilitiesTest::class,
    PageMergeServiceTest::class,
    ConflictReviewViewModelTest::class,
    LargeGraphMergeTest::class,
    MergeResilienceTest::class,
    LinkClosureTest::class,
    AssetCopierTest::class,
    SqliteStatementAnalyzerTest::class,
    StorageLocationPersistenceTest::class,
    WithoutRowidMigrationTest::class,
    BacklogTriageRuleTest::class,
    GapBacklogSchemaTest::class,
    GapBacklogTraceabilityTest::class,
    JourneyDocsFrontmatterTest::class,
    JourneyStepCountRubricTest::class,
    ImageAttachCallbackContractTest::class,
    ExportServiceJournalRangeTest::class,
    ExportServiceLinkedPagesTest::class,
    GitCloneRetryCleanupTest::class,
    GitConfigCloneDepthMappingTest::class,
    GitOperationSupportClassifierTest::class,
    GitRepositoryUnshallowTest::class,
    GitShadowWorktreeNoCoroutineScopeTest::class,
    GitSyncBusyCounterFetchOnlyTest::class,
    GitSyncServiceChangeBackTest::class,
    GitSyncServiceAutoMergeTest::class,
    GitSyncServiceConflictResolutionTest::class,
    GitSyncServiceErrorRoutingTest::class,
    GitSyncServiceConflictMarkerGuardTest::class,
    GitSyncServiceFirstSyncGateTest::class,
    GitSyncServiceRateLimitRetryTest::class,
    GitSyncServiceTest::class,
    GitTransportFaultInjectionTest::class,
    GitTransportRetryStateTest::class,
    GitTransportRetryTest::class,
    LfsPostPushAuditTest::class,
    LfsStagingCallSitesStructuralTest::class,
    GitAttributesDifferentialTest::class,
    LfsGuardCoordinatorTest::class,
    LfsRulesLoaderTest::class,
    LfsRawBinaryNeverCommittedPropertyTest::class,
    LfsRawBinaryNeverCommittedTest::class,
    LfsIndexVerifierTest::class,
    DomainErrorTest::class,
    LfsModeResolverTest::class,
    LfsGuardPropertyTest::class,
    LfsGuardTest::class,
    LfsPathPolicyTest::class,
    GitAttributesMatcherPropertyTest::class,
    GitAttributesMatcherTest::class,
    LfsPointerPropertyTest::class,
    LfsPointerTest::class,
    GitAttributesParserTest::class,
    GitWildmatchTest::class,
    GitOperationSupportHelpersTest::class,
    GitRefDiagnosticsTest::class,
    HtmlBlockConverterTest::class,
    McpServerControllerTest::class,
    GitSyncServiceStepRoutingTest::class,
    ActiveWriteHooksTest::class,
    LlmTagProviderBranchesTest::class,
    LlmSuggestionWriterEditsTest::class,
    VaultHeaderModelTest::class,
    MarkdownParserInlineStorageTest::class,
    FileSystemLfsContentProbeTest::class,
    SqlDelightGitConfigRepositoryRoundTripTest::class,
    ChangeApplierBranchesTest::class,
    PostMigrationFlusherTest::class,
    GitSyncServiceLfsGuardTest::class,
    GitSyncServicePeriodicTest::class,
    PeriodicSyncDriverTest::class,
    ConflictMarkerDiffScanTest::class,
    GitSyncServiceChangeGuardsTest::class,
    FirstSyncPreviewSourceTest::class,
    GitSyncServicePublishFailureTest::class,
    GitSyncServiceScheduledSyncTest::class,
    GitSyncServiceAutoCommitTest::class,
    SyncSafetyConfirmTest::class,
    BannerPrecedenceTest::class,
    ColdSyncRunnerTest::class,
    StalenessChipStateTest::class,
    SyncNotificationLogicTest::class,
    SyncStalenessStoreTest::class,
    SyncStatusSheetModelTest::class,
    UpdatesNotificationToggleTest::class,
    FirstSyncPreviewLoaderTest::class,
    GitSyncCoordinatorTest::class,
    ScheduledSyncPolicyTest::class,
    EditLockTest::class,
    ScheduledSyncStackE2ETest::class,
    ShallowMergeTest::class,
    StubGitRepositoryFailureSequenceTest::class,
    JournalMergeServiceTest::class,
    LogseqMergeDriverTest::class,
    AndroidOnDeviceFallbackTest::class,
    CustomProviderUrlValidationTest::class,
    LlmCredentialMigrationTest::class,
    LlmCredentialStoreTest::class,
    LlmSettingsCustomProviderTest::class,
    LlmSuggestionInboxTest::class,
    LlmSuggestionWriterTest::class,
    LlmSynthesisContextBuilderTest::class,
    LlmSynthesisServiceTest::class,
    PendingLlmSuggestionTest::class,
    StelekitViewModelLlmSuggestionTest::class,
    TagSuggestionOnDeviceDefaultTest::class,
    VoiceDeviceLlmMigrationTest::class,
    BlockTypeTest::class,
    OtelProviderStabilityTest::class,
    MeasurementInjectionTest::class,
    CredentialAccessTest::class,
    AssetRepositoryTest::class,
    SqlDelightAssetRepositoryContractTest::class,
    SqlDelightSearchRepositoryTest::class,
    WikilinkBatchInsertTest::class,
    RetryPoliciesTest::class,
    DeviceProfileTest::class,
    ThreeStateSubscriptionTest::class,
    LlmTagProviderTest::class,
    TagAvailabilityPollerTest::class,
    TagSuggestionEngineTest::class,
    TagSuggestionViewModelTest::class,
    WikiLinkExtractorTest::class,
    QrImportServiceTest::class,
    QrTransferCoordinatorTest::class,
    TransferSessionTest::class,
    StelekitViewModelNavigationTest::class,
    StelekitViewModelUiToggleTest::class,
    DiskConflictFullScreenStateTest::class,
    MermaidRenderKeyTest::class,
    MermaidRenderResultTest::class,
    MermaidSvgAspectRatioTest::class,
    BlockInvalidationIntegrationTest::class,
    QrDecodeViewModelTest::class,
    QrEncodeViewModelTest::class,
    FractionalIndexingTest::class,
    GenAiErrorMappingTest::class,
    LlmProviderSupportTest::class,
    MlKitAvailabilityMappingTest::class,
    VoicePipelineConfigTest::class,
    QueryExecutorTest::class,
    TestHygieneAuditTest::class,
    TestPlacementAuditTest::class,
    FirstOpenSmokeCatalogTest::class,
    ObservableEffectCatalogTest::class,
    PlantedDeadCodeConstructorTest::class,
    WiringAllowlistRenewalTest::class,
    WiringCompletenessTest::class,
    CommandLineTokenizerTest::class,
    ConfirmPolicyTest::class,
    ConsoleBackupTest::class,
    DbOnlyRowsGuardTest::class,
    DbRestoreLockStressTest::class,
    DbRestoreTest::class,
    GraphRestoreCommandTest::class,
    RestoreRecoveryTest::class,
    ConsoleBuildFlagsTest::class,
    ConsoleSessionConfirmTest::class,
    SqlConsoleWriterTest::class,
    SqlWriteCommandTest::class,
    SqlWriteFtsConsistencyTest::class,
    SqlWriteGuardTest::class,
    DirectSqlWriteOptInAllowlistTest::class,
    ConsoleBoundedStreamingTest::class,
    ConsoleScopeOwnershipAuditTest::class,
    ConsoleSessionTest::class,
    CredentialColumnInventoryTest::class,
    SqlCommandTest::class,
    SqlConsoleBypassTest::class,
    ScrollbackBufferTest::class,
    ResultGridTest::class,
    RedactorSingleSiteAuditTest::class,
    RedactorTest::class,
    GoogleAuthCodeExchangeTest::class,
    GoogleDriveFlowTest::class,
    GooglePhotosFlowTest::class,
    TrackedCopyRunHostTest::class,
    HomeSketchFixtureVisibilityTest::class,
    SketchMigrationTest::class,
    SketchExporterTest::class,
    SketchExportRoundTripTest::class,
    SketchStubGatingTest::class,
    SketchRepositoryContractTest::class,
    SketchDeleteProjectTest::class,
    SketchStaleEditorTest::class,
    dev.stapler.stelekit.fixtures.ClosetFixtureTest::class,
    dev.stapler.stelekit.fusion.FixtureRegistryTest::class,
    dev.stapler.stelekit.fusion.ClosetInvariantTest::class,
    dev.stapler.stelekit.sketchimport.KsoupSvgSpikeTest::class,
    PrivateStringGuardTest::class,
    dev.stapler.stelekit.ui.annotate.AnnotationEditorUnitTest::class,
    GoldenFileGuardTest::class,
    GitSyncCoordinatorJobsTest::class,
    dev.stapler.stelekit.ui.library.LibraryFilterRulesTest::class,
    dev.stapler.stelekit.ui.library.LibraryFilterRulesPropertyTest::class,
    dev.stapler.stelekit.ui.library.LibrarySearchTest::class,
    dev.stapler.stelekit.ui.library.LibraryMediaClassTest::class,
    dev.stapler.stelekit.ui.library.LibraryStatusLineTest::class,
    dev.stapler.stelekit.ui.library.LibraryItemActionsTest::class,
    dev.stapler.stelekit.ui.library.UpgradeNoticeStateMachineTest::class,
    dev.stapler.stelekit.ui.nav.NavSectionBuilderTest::class,
    dev.stapler.stelekit.ui.nav.NavCommandsTest::class,
    dev.stapler.stelekit.ui.nav.NavModelExhaustivenessTest::class,
    dev.stapler.stelekit.ui.nav.NavLabelUniquenessTest::class,
    dev.stapler.stelekit.ui.nav.LibraryRedirectTest::class,
    dev.stapler.stelekit.ui.nav.PersistedScreenIdTest::class,
    dev.stapler.stelekit.ui.library.LibraryFilterSqlParityTest::class,
    dev.stapler.stelekit.ui.library.LibraryAnnotatedJoinTest::class,
    dev.stapler.stelekit.ui.library.LargeLibraryBatchingTest::class,
    dev.stapler.stelekit.ui.library.LibraryViewModelStateTest::class,
    DomainLayerAuditTest::class,
    dev.stapler.stelekit.units.RationalExamplesTest::class,
    dev.stapler.stelekit.units.RationalPropertyTest::class,
    dev.stapler.stelekit.platform.AtomicFileWriterTest::class,
    dev.stapler.stelekit.platform.PlatformLayerAuditTest::class,
    dev.stapler.stelekit.projectbridge.ProjectBridgeSettingsTest::class,
    dev.stapler.stelekit.projectbridge.SettleBarrierTest::class,
    dev.stapler.stelekit.projectbridge.ProjectRelevanceLoopSafetyTest::class,
    dev.stapler.stelekit.projectbridge.TwoDeviceConvergenceTest::class,
    dev.stapler.stelekit.projectbridge.PostSyncProjectCoordinatorTest::class,
    dev.stapler.stelekit.projectbridge.PostSyncProjectJobFailureTest::class,
    dev.stapler.stelekit.capture.TargetWriterRouterHolderTest::class,
    dev.stapler.stelekit.projectbridge.PostSyncProjectJobEntryPointTest::class,
    dev.stapler.stelekit.projectbridge.MiniYamlGoldenTest::class,
    dev.stapler.stelekit.projectbridge.MiniYamlPropertyTest::class,
    dev.stapler.stelekit.units.LengthParserGoldenTest::class,
    dev.stapler.stelekit.units.LengthParserPropertyTest::class,
    dev.stapler.stelekit.sketchimport.LabelBinderPropertyTest::class,
    dev.stapler.stelekit.sketchimport.SvgReaderInlineTest::class,
    dev.stapler.stelekit.sketchimport.SvgReaderPropertyTest::class,
    dev.stapler.stelekit.sketchimport.SvgTransformTest::class,
    dev.stapler.stelekit.sketchimport.SvgReaderTest::class,
    dev.stapler.stelekit.sketchimport.SheetCalibrationTest::class,
    dev.stapler.stelekit.sketchimport.GeometryRulesTest::class,
    dev.stapler.stelekit.sketchimport.GeometryExtractorTest::class,
    dev.stapler.stelekit.sketchimport.LabelBinderTest::class,
    dev.stapler.stelekit.sketchimport.TaggingModelTest::class,
    dev.stapler.stelekit.sketchimport.ImportFlowTest::class,
    dev.stapler.stelekit.sketchimport.PngSheetAdapterTest::class,
    dev.stapler.stelekit.sketchimport.SketchImportViewModelTest::class,
    dev.stapler.stelekit.fusion.LinearSolverTest::class,
    dev.stapler.stelekit.fusion.DimensionRegistryTest::class,
    dev.stapler.stelekit.fusion.FusionEngineTest::class,
    dev.stapler.stelekit.fusion.LinearSolverPropertyTest::class,
    dev.stapler.stelekit.projectbridge.DigestRendererTest::class,
    dev.stapler.stelekit.projectbridge.LlmPayloadBuilderTest::class,
    dev.stapler.stelekit.projectbridge.DigestWriteIdempotenceTest::class,
    dev.stapler.stelekit.projectbridge.DigestSelfFeedTest::class,
    dev.stapler.stelekit.projectbridge.ProjectManagementTest::class,
    dev.stapler.stelekit.projectbridge.SourceBindingTest::class,
    dev.stapler.stelekit.projectbridge.BoundPagesReaderTest::class,
    dev.stapler.stelekit.projectbridge.SourceBindingServiceTest::class,
    dev.stapler.stelekit.projectbridge.YamlSnapshotImporterTest::class,
    dev.stapler.stelekit.ui.annotate.AnnotationEditorUnitTest::class,
    dev.stapler.stelekit.projectbridge.ProjectFusionSourceTest::class,
    dev.stapler.stelekit.ui.sketch.ProjectListViewModelTest::class,
    dev.stapler.stelekit.ui.sketch.RegistryEditorViewModelTest::class,
    dev.stapler.stelekit.projectbridge.DigestWriterJobTest::class,
    dev.stapler.stelekit.llm.OutboundLlmGateTest::class,
    dev.stapler.stelekit.llm.GateIssuedAuditTest::class,
    dev.stapler.stelekit.sketch.SketchGesturesTest::class,
    dev.stapler.stelekit.sketch.SketchViewMathTest::class,
    dev.stapler.stelekit.sketch.SketchRenderCacheTest::class,
    dev.stapler.stelekit.sketch.SketchHistoryTest::class,
    dev.stapler.stelekit.sketch.SketchSheetPropsTest::class,
    dev.stapler.stelekit.sketch.SketchSheetViewModelTest::class,
    dev.stapler.stelekit.sketch.SketchViewModelTest::class,
    dev.stapler.stelekit.projectbridge.SketchNavigationGateTest::class,
    dev.stapler.stelekit.sketch.SnapEnginePropertyTest::class,
    dev.stapler.stelekit.sketch.SketchNonDragModeTest::class,
    dev.stapler.stelekit.ui.placement.NudgeStepperPropertyTest::class,
    dev.stapler.stelekit.ui.placement.InchesPlacementControllerContractTest::class,
    dev.stapler.stelekit.ui.placement.SketchPlacementControllerContractTest::class,
    dev.stapler.stelekit.ui.placement.AnnotationPlacementControllerContractTest::class,
    dev.stapler.stelekit.ui.annotate.AnnotationPlacementTest::class,
    dev.stapler.stelekit.sketch.ViewTransformTest::class,
    dev.stapler.stelekit.projectbridge.SidecarBundleTest::class,
    dev.stapler.stelekit.projectbridge.SidecarReconcileTest::class,
    dev.stapler.stelekit.projectbridge.ProjectSidecarStoreTest::class,
    dev.stapler.stelekit.projectbridge.PostSyncProjectJobSidecarTest::class,
    dev.stapler.stelekit.projectbridge.TableParserCharacterizationTest::class,
    dev.stapler.stelekit.projectbridge.TableMeasurementExtractorTest::class,
    dev.stapler.stelekit.projectbridge.TableExtractorPropertyTest::class,
    dev.stapler.stelekit.projectbridge.TableExtractorInlineTest::class,
    dev.stapler.stelekit.projectbridge.ProseExtractorsTest::class,
    dev.stapler.stelekit.projectbridge.ProseExtractorsClosetTest::class,
    dev.stapler.stelekit.projectbridge.ClosetEndToEndTest::class,
)
class AllBusinessTests
