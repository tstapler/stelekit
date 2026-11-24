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
import dev.stapler.stelekit.llm.ProjectAnalysisGoldenPromptTest
import dev.stapler.stelekit.projectbridge.ProjectAnalysisStepTest
import dev.stapler.stelekit.projectbridge.OutboundPreviewTest
import dev.stapler.stelekit.projectbridge.PostSyncProjectJobEntryPointTest
import dev.stapler.stelekit.capture.CaptureTargetResolverTest
import dev.stapler.stelekit.capture.InboxFallbackAppenderTest
import dev.stapler.stelekit.capture.JournalAppenderOffGraphTest
import dev.stapler.stelekit.ui.NotesPathWiringTest
import dev.stapler.stelekit.capture.JournalAppenderTest
import dev.stapler.stelekit.editor.LinkInsertionTest
import dev.stapler.stelekit.db.DemoFileSystemSyncTest
import dev.stapler.stelekit.console.DbOnlyRowsGuardTest
import dev.stapler.stelekit.console.DbRestoreLockStressTest
import dev.stapler.stelekit.console.SqlConsoleWriterTest
import dev.stapler.stelekit.console.SqlWriteCommandTest
import dev.stapler.stelekit.console.SqlWriteFtsConsistencyTest
import dev.stapler.stelekit.console.SqlWriteGuardTest
import dev.stapler.stelekit.db.DirectSqlWriteOptInAllowlistTest
import dev.stapler.stelekit.console.ConsoleScopeOwnershipAuditTest
import dev.stapler.stelekit.console.ConsoleSessionTest
import dev.stapler.stelekit.console.CredentialColumnInventoryTest
import dev.stapler.stelekit.security.RedactorSingleSiteAuditTest
import dev.stapler.stelekit.merge.MarkdownTargetWriterContractTest
import dev.stapler.stelekit.transfer.MergeUuidRoundTripSpikeTest
import dev.stapler.stelekit.platform.image.ImageSamplingTest
import dev.stapler.stelekit.platform.image.ImageTiersTest
import dev.stapler.stelekit.ui.assets.AssetImageReferenceTest
import dev.stapler.stelekit.ui.PageSelectionTest
import dev.stapler.stelekit.asset.DrawingExcludedFromCloudTest
import dev.stapler.stelekit.asset.AssetIndexedBrowseTest
import dev.stapler.stelekit.asset.AssetIndexingControllerTest
import dev.stapler.stelekit.asset.AssetIndexingFailurePathsTest
import dev.stapler.stelekit.asset.AssetMoveReplayEntryPointTest
import dev.stapler.stelekit.asset.pipeline.AssetPipelineDrainBugTest
import dev.stapler.stelekit.asset.pipeline.AssetPipelineTerminalStateTest
import dev.stapler.stelekit.asset.pipeline.AssetPipelineOomInjectionTest
import dev.stapler.stelekit.asset.pipeline.AssetPipelineCrashLoopBreakerTest
import dev.stapler.stelekit.asset.pipeline.DefaultAssetPluginsTest
import dev.stapler.stelekit.asset.AssetSearchSqlTest
import dev.stapler.stelekit.asset.AssetTagSearchSqlTest
import dev.stapler.stelekit.asset.AssetSearchCloudDescriptionTest
import dev.stapler.stelekit.asset.CloudDescriptionStorageTest
import dev.stapler.stelekit.asset.CloudKeyLeakageAuditTest
import dev.stapler.stelekit.asset.pipeline.CloudBackfillTest
import dev.stapler.stelekit.asset.pipeline.CloudStructuralAuditTest
import dev.stapler.stelekit.asset.pipeline.GrantedConsentMintSiteAuditTest
import dev.stapler.stelekit.audit.EgressSurfaceAuditTest
import dev.stapler.stelekit.audit.NonCancellableReturnAuditTest
import dev.stapler.stelekit.llm.AnthropicMessagesClientCharacterizationTest
import dev.stapler.stelekit.asset.pipeline.AssetPluginsControllerTest
import dev.stapler.stelekit.asset.pipeline.PluginRegistryTest
import dev.stapler.stelekit.settings.PluginHostRemovalAuditTest
import dev.stapler.stelekit.auto.AudiobookNoteFormatterTest
import dev.stapler.stelekit.capture.ShareInboxTest
import dev.stapler.stelekit.db.GraphManagerOnGraphLocationDeterminedTest
import dev.stapler.stelekit.db.GraphRelocationCoordinatorTest
import dev.stapler.stelekit.db.MigrationRunnerSchemaSyncTest
import dev.stapler.stelekit.sketch.export.SketchExportRoundTripTest
import dev.stapler.stelekit.merge.OwnedFileGuardTest
import dev.stapler.stelekit.merge.MetricsLogContractTest
import dev.stapler.stelekit.docs.BacklogTriageRuleTest
import dev.stapler.stelekit.docs.GapBacklogSchemaTest
import dev.stapler.stelekit.docs.GapBacklogTraceabilityTest
import dev.stapler.stelekit.docs.JourneyDocsFrontmatterTest
import dev.stapler.stelekit.docs.JourneyStepCountRubricTest
import dev.stapler.stelekit.git.GitCloneRetryCleanupTest
import dev.stapler.stelekit.git.GitOperationSupportClassifierTest
import dev.stapler.stelekit.git.GitShadowWorktreeNoCoroutineScopeTest
import dev.stapler.stelekit.git.GitSyncServiceConflictResolutionTest
import dev.stapler.stelekit.git.GitSyncServiceErrorRoutingTest
import dev.stapler.stelekit.git.GitSyncServiceConflictMarkerGuardTest
import dev.stapler.stelekit.git.GitSyncServiceTest
import dev.stapler.stelekit.git.GitTransportFaultInjectionTest
import dev.stapler.stelekit.git.GitTransportRetryTest
import dev.stapler.stelekit.git.lfs.LfsPostPushAuditTest
import dev.stapler.stelekit.git.lfs.LfsStagingCallSitesStructuralTest
import dev.stapler.stelekit.git.lfs.LfsGuardCoordinatorTest
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
import dev.stapler.stelekit.domain.HtmlBlockConverterTest
import dev.stapler.stelekit.mcp.McpServerControllerTest
import dev.stapler.stelekit.capture.ActiveWriteHooksTest
import dev.stapler.stelekit.vault.VaultHeaderModelTest
import dev.stapler.stelekit.parser.MarkdownParserInlineStorageTest
import dev.stapler.stelekit.parser.TableRoundTripTest
import dev.stapler.stelekit.git.GitSyncServiceLfsGuardTest
import dev.stapler.stelekit.git.GitSyncServicePeriodicTest
import dev.stapler.stelekit.git.GitSyncServiceChangeGuardsTest
import dev.stapler.stelekit.git.SyncSafetyConfirmTest
import dev.stapler.stelekit.git.BannerPrecedenceTest
import dev.stapler.stelekit.git.StalenessChipStateTest
import dev.stapler.stelekit.git.SyncNotificationLogicTest
import dev.stapler.stelekit.git.SyncStalenessStoreTest
import dev.stapler.stelekit.git.SyncStatusSheetModelTest
import dev.stapler.stelekit.git.SyncStatusSheetProblemTest
import dev.stapler.stelekit.git.UpdatesNotificationToggleTest
import dev.stapler.stelekit.git.GitLastSuccessSourceTest
import dev.stapler.stelekit.git.ClockStepStartTimeTest
import dev.stapler.stelekit.git.SyncInProgressUiConsumersTest
import dev.stapler.stelekit.git.GitSyncOneShotConflictAuditTest
import dev.stapler.stelekit.git.GateBeforeWorktreeOrderTest
import dev.stapler.stelekit.wiring.NoGateFallbackAuditTest
import dev.stapler.stelekit.git.GitGateAuditTest
import dev.stapler.stelekit.audit.NewCodeAuditTest
import dev.stapler.stelekit.audit.ScopeEscapeAuditTest
import dev.stapler.stelekit.ux.ExitPathAuditTest
import dev.stapler.stelekit.git.GateKeyAliasTest
import dev.stapler.stelekit.git.LockRecoveryDecisionTest
import dev.stapler.stelekit.git.PidNamespaceAbsentPidTest
import dev.stapler.stelekit.git.EntryPointGateTest
import dev.stapler.stelekit.git.RepoOpGateReleaseStructureTest
import dev.stapler.stelekit.git.ProcStatParserTest
import dev.stapler.stelekit.git.LockRecoveryClaimTest
import dev.stapler.stelekit.git.LockRecoveryReviewFixesTest
import dev.stapler.stelekit.git.ActiveRunNotClearedOwnTest
import dev.stapler.stelekit.git.RecoveryLockInProcessMutexTest
import dev.stapler.stelekit.git.ForeignLockNewerThanDeadRecordTest
import dev.stapler.stelekit.git.GitRepositoryDefaultsFailClosedTest
import dev.stapler.stelekit.git.ForceRemoveRacesLiveRunTest
import dev.stapler.stelekit.git.OwnerHeartbeatTest
import dev.stapler.stelekit.git.RepoOpGateTest
import dev.stapler.stelekit.git.TornRecordIsUnknownTest
import dev.stapler.stelekit.git.GitSyncTaskMappingTest
import dev.stapler.stelekit.git.testsupport.StubGitRepositoryFailureSequenceTest
import dev.stapler.stelekit.ui.components.TableColumnLayoutTest
import dev.stapler.stelekit.ui.components.TableModelTest
import dev.stapler.stelekit.ui.components.TablePreferencesTest
import dev.stapler.stelekit.audit.ModuleManifestGuardTest
import dev.stapler.stelekit.audit.TestHygieneAuditTest
import dev.stapler.stelekit.audit.TestPlacementAuditTest
import dev.stapler.stelekit.audit.TestRegistrationAuditTest
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
    ProjectAnalysisGoldenPromptTest::class,
    ProjectAnalysisStepTest::class,
    OutboundPreviewTest::class,
    PostSyncProjectJobEntryPointTest::class,
    AllBusinessTestsCompletenessTest::class,
    CaptureTargetResolverTest::class,
    JournalAppenderTest::class,
    JournalAppenderOffGraphTest::class,
    NotesPathWiringTest::class,
    InboxFallbackAppenderTest::class,
    LinkInsertionTest::class,
    DemoFileSystemSyncTest::class,
    MarkdownTargetWriterContractTest::class,
    PageSelectionTest::class,
    ImageSamplingTest::class,
    ImageTiersTest::class,
    AssetImageReferenceTest::class,
    MergeUuidRoundTripSpikeTest::class,
    dev.stapler.stelekit.platform.OpfsProbeDirectoryTest::class,
    dev.stapler.stelekit.platform.OpfsStorageVerdictTest::class,
    dev.stapler.stelekit.platform.V2StorageStatusTest::class,
    dev.stapler.stelekit.projectbridge.ClassicWebFileSidePolicyTest::class,
    dev.stapler.stelekit.platform.diagnostics.ExitRecordFormatterTest::class,
    dev.stapler.stelekit.platform.diagnostics.ProcStatParserTest::class,
    dev.stapler.stelekit.platform.diagnostics.LogRedactorTest::class,
    dev.stapler.stelekit.ui.annotate.AnnotationEditorCrashGuardTest::class,
    dev.stapler.stelekit.ui.annotate.AnnotationViewTransformTest::class,
    dev.stapler.stelekit.ui.annotate.AnnotationGestureArbitrationTest::class,
    dev.stapler.stelekit.ui.annotate.AnnotationHitTestUnderTransformTest::class,
    dev.stapler.stelekit.ui.annotate.AnnotationHitTestScaleTest::class,
    dev.stapler.stelekit.ui.annotate.AnnotationPointClampTest::class,
    dev.stapler.stelekit.ui.annotate.EditorChromePolicyTest::class,
    dev.stapler.stelekit.ui.annotate.LoupeEdgeContainmentTest::class,
    dev.stapler.stelekit.ui.annotate.PlacementClassifierTest::class,
    dev.stapler.stelekit.ui.annotate.ToolPolicyTest::class,
    DrawingExcludedFromCloudTest::class,
    AssetIndexedBrowseTest::class,
    AssetIndexingControllerTest::class,
    AssetIndexingFailurePathsTest::class,
    AssetMoveReplayEntryPointTest::class,
    AssetPipelineDrainBugTest::class,
    AssetPipelineTerminalStateTest::class,
    AssetPipelineOomInjectionTest::class,
    AssetPipelineCrashLoopBreakerTest::class,
    DefaultAssetPluginsTest::class,
    PluginRegistryTest::class,
    AssetSearchSqlTest::class,
    AssetTagSearchSqlTest::class,
    AssetSearchCloudDescriptionTest::class,
    CloudDescriptionStorageTest::class,
    CloudKeyLeakageAuditTest::class,
    CloudBackfillTest::class,
    CloudStructuralAuditTest::class,
    GrantedConsentMintSiteAuditTest::class,
    EgressSurfaceAuditTest::class,
    NonCancellableReturnAuditTest::class,
    AnthropicMessagesClientCharacterizationTest::class,
    AssetPluginsControllerTest::class,
    PluginHostRemovalAuditTest::class,
    AudiobookNoteFormatterTest::class,
    ShareInboxTest::class,
    GraphManagerOnGraphLocationDeterminedTest::class,
    GraphRelocationCoordinatorTest::class,
    MigrationRunnerSchemaSyncTest::class,
    OwnedFileGuardTest::class,
    MetricsLogContractTest::class,
    BacklogTriageRuleTest::class,
    GapBacklogSchemaTest::class,
    GapBacklogTraceabilityTest::class,
    JourneyDocsFrontmatterTest::class,
    JourneyStepCountRubricTest::class,
    GitCloneRetryCleanupTest::class,
    GitOperationSupportClassifierTest::class,
    GitShadowWorktreeNoCoroutineScopeTest::class,
    GitSyncServiceConflictResolutionTest::class,
    GitSyncServiceErrorRoutingTest::class,
    GitSyncServiceConflictMarkerGuardTest::class,
    GitSyncServiceTest::class,
    GitTransportFaultInjectionTest::class,
    GitTransportRetryTest::class,
    LfsPostPushAuditTest::class,
    LfsStagingCallSitesStructuralTest::class,
    LfsGuardCoordinatorTest::class,
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
    HtmlBlockConverterTest::class,
    McpServerControllerTest::class,
    ActiveWriteHooksTest::class,
    VaultHeaderModelTest::class,
    MarkdownParserInlineStorageTest::class,
    TableRoundTripTest::class,
    TableModelTest::class,
    TableColumnLayoutTest::class,
    TablePreferencesTest::class,
    GitSyncServiceLfsGuardTest::class,
    GitSyncServicePeriodicTest::class,
    GitSyncServiceChangeGuardsTest::class,
    SyncSafetyConfirmTest::class,
    BannerPrecedenceTest::class,
    StalenessChipStateTest::class,
    SyncNotificationLogicTest::class,
    SyncStalenessStoreTest::class,
    SyncStatusSheetModelTest::class,
    SyncStatusSheetProblemTest::class,
    UpdatesNotificationToggleTest::class,
    GitSyncTaskMappingTest::class,
    GitLastSuccessSourceTest::class,
    ClockStepStartTimeTest::class,
    GateKeyAliasTest::class,
    GateBeforeWorktreeOrderTest::class,
    SyncInProgressUiConsumersTest::class,
    GitSyncOneShotConflictAuditTest::class,
    NoGateFallbackAuditTest::class,
    GitGateAuditTest::class,
    NewCodeAuditTest::class,
    ScopeEscapeAuditTest::class,
    ExitPathAuditTest::class,
    LockRecoveryDecisionTest::class,
    PidNamespaceAbsentPidTest::class,
    ProcStatParserTest::class,
    LockRecoveryClaimTest::class,
    LockRecoveryReviewFixesTest::class,
    ActiveRunNotClearedOwnTest::class,
    RecoveryLockInProcessMutexTest::class,
    ForeignLockNewerThanDeadRecordTest::class,
    GitRepositoryDefaultsFailClosedTest::class,
    ForceRemoveRacesLiveRunTest::class,
    OwnerHeartbeatTest::class,
    EntryPointGateTest::class,
    RepoOpGateReleaseStructureTest::class,
    RepoOpGateTest::class,
    TornRecordIsUnknownTest::class,
    StubGitRepositoryFailureSequenceTest::class,
    ModuleManifestGuardTest::class,
    TestHygieneAuditTest::class,
    TestPlacementAuditTest::class,
    TestRegistrationAuditTest::class,
    FirstOpenSmokeCatalogTest::class,
    ObservableEffectCatalogTest::class,
    PlantedDeadCodeConstructorTest::class,
    WiringAllowlistRenewalTest::class,
    WiringCompletenessTest::class,
    DbOnlyRowsGuardTest::class,
    DbRestoreLockStressTest::class,
    SqlConsoleWriterTest::class,
    SqlWriteCommandTest::class,
    SqlWriteFtsConsistencyTest::class,
    SqlWriteGuardTest::class,
    DirectSqlWriteOptInAllowlistTest::class,
    ConsoleScopeOwnershipAuditTest::class,
    ConsoleSessionTest::class,
    CredentialColumnInventoryTest::class,
    RedactorSingleSiteAuditTest::class,
    HomeSketchFixtureVisibilityTest::class,
    SketchExportRoundTripTest::class,
    dev.stapler.stelekit.fixtures.ClosetFixtureTest::class,
    dev.stapler.stelekit.fusion.FixtureRegistryTest::class,
    dev.stapler.stelekit.fusion.ClosetInvariantTest::class,
    dev.stapler.stelekit.sketchimport.KsoupSvgSpikeTest::class,
    dev.stapler.stelekit.sketchimport.RasterTaggingTest::class,
    dev.stapler.stelekit.sketchimport.PngTagSourcesTest::class,
    dev.stapler.stelekit.sketchimport.RasterImportFlowTest::class,
    dev.stapler.stelekit.ui.sketchimport.RasterTaggingStateTest::class,
    PrivateStringGuardTest::class,
    GoldenFileGuardTest::class,
    dev.stapler.stelekit.ui.library.LibraryFilterRulesTest::class,
    dev.stapler.stelekit.ui.library.LibraryFilterRulesPropertyTest::class,
    dev.stapler.stelekit.ui.library.LibrarySearchTest::class,
    dev.stapler.stelekit.ui.library.LibraryMediaClassTest::class,
    dev.stapler.stelekit.ui.library.LibraryStatusLineTest::class,
    dev.stapler.stelekit.ui.library.LibraryItemActionsTest::class,
    dev.stapler.stelekit.ui.library.UpgradeNoticeStateMachineTest::class,
    dev.stapler.stelekit.ui.nav.NavSectionBuilderTest::class,
    dev.stapler.stelekit.ui.nav.NavCommandsTest::class,
    DomainLayerAuditTest::class,
    dev.stapler.stelekit.units.RationalExamplesTest::class,
    dev.stapler.stelekit.units.RationalPropertyTest::class,
    dev.stapler.stelekit.platform.AtomicFileWriterTest::class,
    dev.stapler.stelekit.platform.PlatformLayerAuditTest::class,
    dev.stapler.stelekit.projectbridge.ProjectRelevanceLoopSafetyTest::class,
    dev.stapler.stelekit.projectbridge.TwoDeviceConvergenceTest::class,
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
    dev.stapler.stelekit.projectbridge.AttachedSheetsTest::class,
    dev.stapler.stelekit.projectbridge.ProjectFusionSourceTest::class,
    dev.stapler.stelekit.projectbridge.DigestWriterJobTest::class,
    dev.stapler.stelekit.llm.OutboundLlmGateTest::class,
    dev.stapler.stelekit.llm.GateIssuedAuditTest::class,
    dev.stapler.stelekit.sketch.SketchGesturesTest::class,
    dev.stapler.stelekit.sketch.SketchViewMathTest::class,
    dev.stapler.stelekit.sketch.SketchHistoryTest::class,
    dev.stapler.stelekit.sketch.SketchSheetPropsTest::class,
    dev.stapler.stelekit.sketch.v2.DeterminismSpikeTest::class,
    dev.stapler.stelekit.sketch.v2.FloatDriftSpikeTest::class,
    dev.stapler.stelekit.sketch.v2.ChromeTableFixtureTest::class,
    dev.stapler.stelekit.sketch.v2.KeymapSpecTest::class,
    dev.stapler.stelekit.sketch.v2.TextWrapTest::class,
    dev.stapler.stelekit.sketch.v2.SketchLogTest::class,
    dev.stapler.stelekit.sketch.v2.ErrorLogCoverageTest::class,
    dev.stapler.stelekit.sketch.v2.SceneLimitsTest::class,
    dev.stapler.stelekit.sketch.v2.CanonicalJsonTest::class,
    dev.stapler.stelekit.sketch.v2.SceneLimitsGrepTest::class,
    dev.stapler.stelekit.sketch.v2.SceneModelTest::class,
    dev.stapler.stelekit.sketch.v2.DrawListGoldenTest::class,
    dev.stapler.stelekit.sketch.v2.SketchyGoldenTest::class,
    dev.stapler.stelekit.sketch.v2.HachureFillTest::class,
    dev.stapler.stelekit.sketch.v2.ExcalidrawFillTest::class,
    dev.stapler.stelekit.sketch.v2.ScenePainterPixelTest::class,
    dev.stapler.stelekit.sketch.v2.ToolStripModelTest::class,
    dev.stapler.stelekit.ui.sketch.v2.StripIconDrawTest::class,
    dev.stapler.stelekit.sketch.v2.ElementStatsGeometryTest::class,
    dev.stapler.stelekit.sketch.v2.FreehandGoldenTest::class,
    dev.stapler.stelekit.sketch.v2.tools.ToolCancelTest::class,
    dev.stapler.stelekit.sketch.v2.tools.ToolInventoryCompletenessTest::class,
    dev.stapler.stelekit.sketch.v2.view.SketchViewportTest::class,
    dev.stapler.stelekit.ui.sketch.v2.SketchPointerAdapterTest::class,
    dev.stapler.stelekit.sketch.v2.tools.TextToolTest::class,
    dev.stapler.stelekit.sketch.v2.tools.ImageToolTest::class,
    dev.stapler.stelekit.sketch.v2.tools.SelectToolTest::class,
    dev.stapler.stelekit.sketch.v2.tools.ResizeSnapTest::class,
    dev.stapler.stelekit.sketch.v2.tools.SnapFollowupsTest::class,
    dev.stapler.stelekit.sketch.v2.tools.SelectVertexDragTest::class,
    dev.stapler.stelekit.sketch.v2.tools.SelectTouchReachTest::class,
    dev.stapler.stelekit.sketch.v2.tools.SmallShapeResizeTest::class,
    dev.stapler.stelekit.ui.sketch.v2.NoticeReplaceTest::class,
    dev.stapler.stelekit.ui.sketch.v2.NameTagHiddenTest::class,
    dev.stapler.stelekit.sketch.v2.tools.EraserFrameToolTest::class,
    dev.stapler.stelekit.sketch.v2.tools.PointArcToolsTest::class,
    dev.stapler.stelekit.sketch.v2.tools.FreedrawToolTest::class,
    dev.stapler.stelekit.sketch.v2.SelectionCommandsTest::class,
    dev.stapler.stelekit.sketch.v2.image.ImageHeaderProbeTest::class,
    dev.stapler.stelekit.sketch.v2.image.ReferencedAssetsTest::class,
    dev.stapler.stelekit.sketch.v2.ImageAssetStoreTest::class,
    dev.stapler.stelekit.sketch.v2.ElementNameTest::class,
    dev.stapler.stelekit.sketch.v2.LayersModelTest::class,
    dev.stapler.stelekit.sketch.v2.AlignCommandsTest::class,
    dev.stapler.stelekit.sketch.v2.StaleConstraintRepairTest::class,
    dev.stapler.stelekit.sketch.v2.ReconcileScaleTest::class,
    dev.stapler.stelekit.sketch.v2.ReviewFixEditsTest::class,
    dev.stapler.stelekit.sketch.v2.ObjectSnapTest::class,
    dev.stapler.stelekit.sketch.v2.ElementLibraryTest::class,
    dev.stapler.stelekit.sketch.v2.StylePanelModelTest::class,
    dev.stapler.stelekit.sketch.v2.GroupFrameTest::class,
    dev.stapler.stelekit.sketch.v2.ElementsPanelModelTest::class,
    dev.stapler.stelekit.sketch.v2.LargeSceneDegradationPolicyTest::class,
    dev.stapler.stelekit.sketch.v2.KeyDispatcherTest::class,
    dev.stapler.stelekit.sketch.v2.BannerCandidateTest::class,
    dev.stapler.stelekit.sketch.v2.ChromeBudgetPolicyTest::class,
    dev.stapler.stelekit.sketch.v2.tools.ShapeToolsTest::class,
    dev.stapler.stelekit.ui.sketch.v2.NoticeQueueTest::class,
    dev.stapler.stelekit.ui.sketch.v2.OnboardingPolicyTest::class,
    dev.stapler.stelekit.ui.sketch.v2.guide.TourControllerTest::class,
    dev.stapler.stelekit.ui.sketch.v2.guide.GuideContentTest::class,
    dev.stapler.stelekit.ui.sketch.v2.guide.GuideSearchTest::class,
    dev.stapler.stelekit.ui.sketch.v2.guide.GuideModelsTest::class,
    dev.stapler.stelekit.ui.sketch.v2.OnboardingSequencerTest::class,
    dev.stapler.stelekit.ui.sketch.v2.NonDragTransformModeTest::class,
    dev.stapler.stelekit.ui.sketch.v2.NonDragPickModesTest::class,
    dev.stapler.stelekit.ui.sketch.v2.PanPadPolicyTest::class,
    dev.stapler.stelekit.ui.sketch.v2.FingerDrawingDefaultsTest::class,
    dev.stapler.stelekit.ui.sketch.v2.EdgePanPolicyTest::class,
    dev.stapler.stelekit.ui.sketch.v2.LoupeSpecTest::class,
    dev.stapler.stelekit.ui.sketch.v2.TouchModeMatrixTest::class,
    dev.stapler.stelekit.sketch.v2.tools.PreviewCursorTest::class,
    dev.stapler.stelekit.ui.sketch.v2.PlatformInputHostTest::class,
    dev.stapler.stelekit.ui.sketch.v2.WebStorageHostTest::class,
    dev.stapler.stelekit.ui.sketch.v2.KeyboardPanningTest::class,
    dev.stapler.stelekit.ui.sketch.v2.DiagnosticsBridgeTest::class,
    dev.stapler.stelekit.sketch.v2.BannerOwnershipTest::class,
    dev.stapler.stelekit.sketch.v2.ConversionPolicyTest::class,
    dev.stapler.stelekit.projectbridge.TechnicalDataTest::class,
    dev.stapler.stelekit.sketch.v2.BridgePropertyTest::class,
    dev.stapler.stelekit.sketch.v2.ReconcileTest::class,
    dev.stapler.stelekit.sketch.v2.ReconcilePropertyTest::class,
    dev.stapler.stelekit.sketch.v2.HistoryV2PropertyTest::class,
    dev.stapler.stelekit.sketch.v2.SheetCodecV2Test::class,
    dev.stapler.stelekit.sketch.v2.SheetCodecV2FuzzTest::class,
    dev.stapler.stelekit.sketch.v2.HostileInputBoundsTest::class,
    dev.stapler.stelekit.sketch.v2.ElementCommandsTest::class,
    dev.stapler.stelekit.sketch.v2.ConstraintAdapterTest::class,
    dev.stapler.stelekit.sketch.v2.SvgExporterGoldenTest::class,
    dev.stapler.stelekit.sketch.v2.SvgEscapingPropertyTest::class,
    dev.stapler.stelekit.sketch.v2.SpatialIndexPropertyTest::class,
    dev.stapler.stelekit.sketch.v2.HitTesterTest::class,
    dev.stapler.stelekit.sketch.v2.CanvasPerfCheckpointTest::class,
    dev.stapler.stelekit.sketch.v2.FidelityCheckpointTest::class,
    dev.stapler.stelekit.sketch.v2.BindingCheckpointTest::class,
    dev.stapler.stelekit.sketch.v2.A1GateTest::class,
    dev.stapler.stelekit.sketch.v2.ConstraintTargetTest::class,
    dev.stapler.stelekit.sketch.v2.PartHitTesterTest::class,
    dev.stapler.stelekit.sketch.v2.SetDimensionValueTest::class,
    dev.stapler.stelekit.sketch.v2.ContainerTextTest::class,
    dev.stapler.stelekit.sketch.v2.SolveRunnerTest::class,
    dev.stapler.stelekit.sketch.v2.SketchEditorControllerTest::class,
    dev.stapler.stelekit.sketch.v2.SketchEditorThreadingTest::class,
    dev.stapler.stelekit.sketch.v2.SketchEditorInputWiringTest::class,
    dev.stapler.stelekit.sketch.v2.LivePreviewOpsTest::class,
    dev.stapler.stelekit.sketch.v2.SketchEditorLeaveTest::class,
    dev.stapler.stelekit.sketch.v2.SketchEditorSolveRaceTest::class,
    dev.stapler.stelekit.sketch.v2.SketchEditorCloseRaceTest::class,
    dev.stapler.stelekit.sketch.v2.SheetSaverResilienceTest::class,
    dev.stapler.stelekit.sketch.v2.EditorExtrasTest::class,
    dev.stapler.stelekit.sketch.v2.SketchEditorOpenerTest::class,
    dev.stapler.stelekit.sketch.v2.SketchEditorStringsTest::class,
    dev.stapler.stelekit.sketch.v2.SceneSemanticsModelTest::class,
    dev.stapler.stelekit.sketch.v2.AutoConstrainTest::class,
    dev.stapler.stelekit.sketch.v2.ProjectionFusionTest::class,
    dev.stapler.stelekit.ui.sketch.v2.ConstraintPanelModelTest::class,
    dev.stapler.stelekit.ui.sketch.v2.ConstraintGlyphLayoutTest::class,
    dev.stapler.stelekit.ui.sketch.v2.ConstraintTargetPickerModelTest::class,
    dev.stapler.stelekit.ui.sketch.v2.DimensionEditModelTest::class,
    dev.stapler.stelekit.ui.sketch.v2.DimensionChipsTest::class,
    dev.stapler.stelekit.ui.sketch.v2.DimensionPillModelTest::class,
    dev.stapler.stelekit.ui.sketch.v2.SmartDimensionPathBoundTest::class,
    dev.stapler.stelekit.sketch.v2.tools.ArrowToolTest::class,
    dev.stapler.stelekit.sketch.v2.BindingFollowTest::class,
    dev.stapler.stelekit.sketch.v2.BindingEngineTest::class,
    dev.stapler.stelekit.sketch.v2.BindingInvariantPropertyTest::class,
    dev.stapler.stelekit.sketch.v2.KeyboardDrawingFlowTest::class,
    dev.stapler.stelekit.sketch.v2.StyleTypeCommandsTest::class,
    dev.stapler.stelekit.sketch.v2.FeatureBaselineCompletenessTest::class,
    dev.stapler.stelekit.sketch.v2.HardcodedStringAuditTest::class,
    dev.stapler.stelekit.ui.sketch.v2.FirstRunCoachMarkTest::class,
    dev.stapler.stelekit.ui.sketch.v2.WalkthroughModelTest::class,
    dev.stapler.stelekit.ui.sketch.v2.HintTextAuditTest::class,
    dev.stapler.stelekit.sketch.v2.ElementLabelIndexTest::class,
    dev.stapler.stelekit.sketch.v2.FocusRevealTest::class,
    dev.stapler.stelekit.sketch.v2.MotionPolicyGrepTest::class,
    dev.stapler.stelekit.ui.sketch.v2.WalkthroughRegionsTest::class,
    dev.stapler.stelekit.ui.sketch.v2.LoadingPolicyTest::class,
    dev.stapler.stelekit.ui.sketch.v2.SketchTokensAuditTest::class,
    dev.stapler.stelekit.sketch.v2.SketchV2EngineImportDirectionTest::class,
    dev.stapler.stelekit.sketch.v2.SketchCanonicalNumberGuardTest::class,
    dev.stapler.stelekit.platform.AndroidApi26CompatibilityTest::class,
    dev.stapler.stelekit.sketch.excalidraw.JsonSpanScannerTest::class,
    dev.stapler.stelekit.sketch.excalidraw.ParserBudgetTest::class,
    dev.stapler.stelekit.sketch.excalidraw.ElementSetRepairScaleTest::class,
    dev.stapler.stelekit.sketch.excalidraw.ScannerSpanFloodTest::class,
    dev.stapler.stelekit.sketch.v2.SvgExporterScaleTest::class,
    dev.stapler.stelekit.sketch.excalidraw.ImageBombTest::class,
    dev.stapler.stelekit.sketch.excalidraw.ParserFuzzTest::class,
    dev.stapler.stelekit.sketch.excalidraw.LibraryImportSpanTest::class,
    dev.stapler.stelekit.sketch.excalidraw.SketchImportImportDirectionTest::class,
    dev.stapler.stelekit.sketch.excalidraw.ExcalidrawFixtureImportTest::class,
    dev.stapler.stelekit.sketch.excalidraw.ImporterFixtureTest::class,
    dev.stapler.stelekit.sketch.excalidraw.ExcalidrawImporterMappingTest::class,
    dev.stapler.stelekit.sketch.excalidraw.RoundTripPropertyTest::class,
    dev.stapler.stelekit.sketch.excalidraw.FixtureRoundTripTest::class,
    dev.stapler.stelekit.sketch.excalidraw.ClipboardTest::class,
    dev.stapler.stelekit.sketch.v2.DimensionSuggestionTest::class,
    dev.stapler.stelekit.db.SketchSheetWritePathsPreserveSchemaTest::class,
    dev.stapler.stelekit.sketch.constraints.LinalgPropertyTest::class,
    dev.stapler.stelekit.sketch.constraints.JacobianFiniteDifferenceTest::class,
    dev.stapler.stelekit.sketch.constraints.SolverPropertyTest::class,
    dev.stapler.stelekit.sketch.constraints.SolverCorpusTest::class,
    dev.stapler.stelekit.sketch.constraints.SolverDeterminismTest::class,
    dev.stapler.stelekit.sketch.constraints.SolverScaleTest::class,
    dev.stapler.stelekit.sketch.constraints.FallbackPlateScenarioTest::class,
    dev.stapler.stelekit.sketch.constraints.BracketPlateScenarioTest::class,
    dev.stapler.stelekit.sketch.constraints.SolverTierTest::class,
    dev.stapler.stelekit.sketch.constraints.SolverTierFactsTest::class,
    dev.stapler.stelekit.sketch.v2.ConstraintAxisAndEndpointTest::class,
    dev.stapler.stelekit.sketch.v2.EngineSurfacesTest::class,
    dev.stapler.stelekit.ui.sketch.v2.DoubleClickDetectorTest::class,
    dev.stapler.stelekit.sketch.constraints.SketchSolverImportDirectionTest::class,
    dev.stapler.stelekit.sketch.v2.ConstraintEligibilityTest::class,
    dev.stapler.stelekit.sketch.SnapEnginePropertyTest::class,
    dev.stapler.stelekit.sketch.SketchNonDragModeTest::class,
    dev.stapler.stelekit.sketch.SketchDragTest::class,
    dev.stapler.stelekit.sketch.SketchPreviewFitTest::class,
    dev.stapler.stelekit.ui.placement.NudgeStepperPropertyTest::class,
    dev.stapler.stelekit.ui.placement.InchesPlacementControllerContractTest::class,
    dev.stapler.stelekit.ui.placement.SketchPlacementControllerContractTest::class,
    dev.stapler.stelekit.ui.placement.AnnotationPlacementControllerContractTest::class,
    dev.stapler.stelekit.sketch.ViewTransformTest::class,
    dev.stapler.stelekit.projectbridge.SidecarOldReaderSchemaGateTest::class,
    dev.stapler.stelekit.projectbridge.SidecarBackupAndStagingTest::class,
    dev.stapler.stelekit.projectbridge.SheetCodecDispatchTest::class,
    dev.stapler.stelekit.projectbridge.SidecarRetentionPolicyTest::class,
    dev.stapler.stelekit.projectbridge.BundleV2PassthroughTest::class,
    dev.stapler.stelekit.projectbridge.SidecarStoreOpaqueContractTest::class,
    dev.stapler.stelekit.projectbridge.ReconcilerV2GoldenTest::class,
    dev.stapler.stelekit.projectbridge.SidecarWriterRaceTest::class,
    dev.stapler.stelekit.repository.ReplaceProjectContentsV2Test::class,
    dev.stapler.stelekit.db.GraphRegistryKnownScopesTest::class,
    dev.stapler.stelekit.jobs.schedule.TaskKeyTest::class,
    dev.stapler.stelekit.jobs.schedule.JitterPolicyPropertyTest::class,
    dev.stapler.stelekit.jobs.schedule.NetworkGateTest::class,
    dev.stapler.stelekit.git.lfs.LfsNetworkGateTest::class,
    dev.stapler.stelekit.git.ScheduledSyncPresentationTest::class,
    dev.stapler.stelekit.git.ScheduleHealthTest::class,
    dev.stapler.stelekit.jobs.schedule.NoopTaskSchedulerTest::class,
    dev.stapler.stelekit.jobs.schedule.ScheduleLatenessTest::class,
    dev.stapler.stelekit.jobs.schedule.JobsScheduleImportDirectionTest::class,
    dev.stapler.stelekit.jobs.schedule.TaskConfigCodecTest::class,
    dev.stapler.stelekit.jobs.schedule.TaskSettingsStoreMigrationTest::class,
    dev.stapler.stelekit.git.LegacyGitSyncMigrationTest::class,
    dev.stapler.stelekit.git.ConfigRepositoryLegacyConfigSourceTest::class,
    dev.stapler.stelekit.git.GitSyncScheduleControllerTest::class,
    dev.stapler.stelekit.git.PeriodicSyncDriverJitterTest::class,
    dev.stapler.stelekit.git.PeriodicSyncDriverRunRecordTest::class,
    dev.stapler.stelekit.jobs.schedule.TaskRunLogTest::class,
    dev.stapler.stelekit.jobs.schedule.GraphRemovalTasksTest::class,
    dev.stapler.stelekit.ui.theme.MutedTextContrastTest::class,
    dev.stapler.stelekit.jobs.schedule.FakeBackgroundTaskSchedulerContractTest::class,
    dev.stapler.stelekit.jobs.schedule.FakeBackgroundTaskSchedulerMinimalCapabilitiesContractTest::class,
    dev.stapler.stelekit.projectbridge.TableMeasurementExtractorTest::class,
    dev.stapler.stelekit.projectbridge.TableExtractorInlineTest::class,
    dev.stapler.stelekit.projectbridge.ProseExtractorsTest::class,
    dev.stapler.stelekit.projectbridge.ProseExtractorsClosetTest::class,
    dev.stapler.stelekit.projectbridge.ClosetEndToEndTest::class,
    dev.stapler.stelekit.projectbridge.FusionHarnessTest::class,
    dev.stapler.stelekit.projectbridge.FusionHarnessLocalRunTest::class,
    dev.stapler.stelekit.wiring.SketchStorageHostSitesTest::class,
    dev.stapler.stelekit.sketch.v2.ConversionReadyConsistencyTest::class,
    dev.stapler.stelekit.sketch.v2.MixedProjectFusionGoldenTest::class,
    dev.stapler.stelekit.sketch.v2.RestoreBackupActionTest::class,
    dev.stapler.stelekit.sketch.v2.SketchyAvailabilityTest::class,
    dev.stapler.stelekit.sketch.v2.SketchPreviewV2Test::class,
    dev.stapler.stelekit.sketch.v2.SheetSaverTest::class,
    dev.stapler.stelekit.sketch.v2.SheetSaverDurabilityTest::class,
    dev.stapler.stelekit.sketch.v2.WebStorageUxTest::class,
    dev.stapler.stelekit.platform.SidecarLiveSetTest::class,
    dev.stapler.stelekit.platform.Utf8PrefixTest::class,
    dev.stapler.stelekit.sketch.v2.ConsentCopyTest::class,
    dev.stapler.stelekit.sketch.v2.QualitySignalsLocalOnlyTest::class,
    dev.stapler.stelekit.ui.sketch.v2.ImportControllerTest::class,
    dev.stapler.stelekit.ui.sketch.v2.ExportDialogModelTest::class,
    dev.stapler.stelekit.db.sidecar.LensRecordFocalSourceTest::class,
    dev.stapler.stelekit.platform.sensor.LensClassifierTest::class,
    dev.stapler.stelekit.platform.sensor.LensClassifierFixtureMatrixTest::class,
    dev.stapler.stelekit.platform.sensor.CameraSessionTest::class,
    dev.stapler.stelekit.platform.sensor.ProbingLensCatalogTest::class,
    dev.stapler.stelekit.platform.sensor.CaptureLensRecordTest::class,
    dev.stapler.stelekit.platform.sensor.CameraControlsStateTest::class,
    dev.stapler.stelekit.platform.sensor.CapabilityProbeFormatTest::class,
    dev.stapler.stelekit.audit.CameraBinderAuditTest::class,
    dev.stapler.stelekit.calibration.AccuracyStudyVerdictTest::class,
    dev.stapler.stelekit.calibration.HeightOffsetPlaneCompositionTest::class,
    dev.stapler.stelekit.calibration.FocalSourceResolverTest::class,
    dev.stapler.stelekit.calibration.GoldenSceneBaselineTest::class,
    dev.stapler.stelekit.calibration.ConstraintAccuracyHarnessTest::class,
    dev.stapler.stelekit.calibration.ConstraintCalibratorFailureTest::class,
    dev.stapler.stelekit.calibration.ConstraintOutlierPropertyTest::class,
    dev.stapler.stelekit.calibration.ConstraintCalibratorPropertyTest::class,
    dev.stapler.stelekit.calibration.GradientIdentifiabilityTest::class,
    dev.stapler.stelekit.calibration.GradientNoiseFalsePositiveRateTest::class,
    dev.stapler.stelekit.calibration.GradientSignificanceTest::class,
    dev.stapler.stelekit.calibration.GradientLooVetoTest::class,
    dev.stapler.stelekit.calibration.CroppedFrameRefusalTest::class,
    dev.stapler.stelekit.calibration.GradientDefaultOffTest::class,
    dev.stapler.stelekit.calibration.OutOfModelRobustnessTest::class,
    dev.stapler.stelekit.calibration.ConstraintStudyRunnerTest::class,
    dev.stapler.stelekit.calibration.ConstraintFitApplierTest::class,
    dev.stapler.stelekit.model.CalibrationConstraintCodecTest::class,
    dev.stapler.stelekit.ui.annotate.MeasurementValueEditTest::class,
    dev.stapler.stelekit.ui.annotate.CalibrationHistoryTest::class,
    dev.stapler.stelekit.ui.annotate.CalibrationStatePresenterTest::class,
    dev.stapler.stelekit.merge.ActiveTargetWriterContractTest::class,
)
class AllBusinessTests
