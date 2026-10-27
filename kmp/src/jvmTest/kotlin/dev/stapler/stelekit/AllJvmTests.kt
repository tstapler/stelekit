package dev.stapler.stelekit

import dev.stapler.stelekit.benchmark.GraphLoadJankTest
import dev.stapler.stelekit.benchmark.GraphLoadTimingTest
import dev.stapler.stelekit.benchmark.RepositoryBenchmarkRunnerTest
import dev.stapler.stelekit.benchmark.RepositoryBenchmarkTest
import dev.stapler.stelekit.cache.CacheHitBenchmarkTest
import dev.stapler.stelekit.db.AppLoadJournalIntegrationTest
import dev.stapler.stelekit.db.BacklinkRenamerTest
import dev.stapler.stelekit.db.BlocksFtsTriggerTest
import dev.stapler.stelekit.db.ConflictMarkerDetectorTest
import dev.stapler.stelekit.db.DatabaseWriteActorTest
import dev.stapler.stelekit.db.DemoGraphIntegrationTest
import dev.stapler.stelekit.db.DiffMergeTest
import dev.stapler.stelekit.db.ExternalChangeConflictTest
import dev.stapler.stelekit.db.FileRegistryTest
import dev.stapler.stelekit.db.GraphLoaderIntegrationTest
import dev.stapler.stelekit.db.GraphLoaderNotesPathTest
import dev.stapler.stelekit.db.ImageSidecarIndexerTest
import dev.stapler.stelekit.platform.image.PlatformImageDownscalerJvmTest
import dev.stapler.stelekit.db.ImageSidecarManagerTest
import dev.stapler.stelekit.ui.SectionManagementCoordinatorTest
import dev.stapler.stelekit.ui.SectionsSettingsDialogsTest
import dev.stapler.stelekit.db.GraphLoaderProgressiveTest
import dev.stapler.stelekit.db.GraphLoaderSectionFilterTest
import dev.stapler.stelekit.db.GraphLoaderTest
import dev.stapler.stelekit.sections.SectionFilterTest
import dev.stapler.stelekit.sections.SectionManifestParserTest
import dev.stapler.stelekit.sections.NotesFolderMetadataStoresTest
import dev.stapler.stelekit.db.GraphLoaderWatcherTest
import dev.stapler.stelekit.db.GraphWriterTest
import dev.stapler.stelekit.db.MigrationRunnerApplyAllTest
import dev.stapler.stelekit.db.PooledJdbcSqliteDriverTest
import dev.stapler.stelekit.db.QueryPlanAuditTest
import dev.stapler.stelekit.db.SanitizationTest
import dev.stapler.stelekit.docs.DemoGraphCoverageTest
import dev.stapler.stelekit.docs.FeatureDocRegistryTest
import dev.stapler.stelekit.domain.ClaudeTopicEnricherTest
import dev.stapler.stelekit.domain.UrlFetcherJvmTest
import dev.stapler.stelekit.export.ExportIntegrationTest
import dev.stapler.stelekit.export.HtmlExporterTest
import dev.stapler.stelekit.git.GitRepoHistoryStoreTest
import dev.stapler.stelekit.integration.JournalParseReproTest
import dev.stapler.stelekit.integration.PipelineReproductionTest
import dev.stapler.stelekit.llm.RemoteLlmProviderTest
import dev.stapler.stelekit.migration.ChangeApplierTest
import dev.stapler.stelekit.migration.DagValidatorTest
import dev.stapler.stelekit.migration.DryRunTest
import dev.stapler.stelekit.migration.DslEvaluatorTest
import dev.stapler.stelekit.migration.MigrationDslTest
import dev.stapler.stelekit.migration.MigrationRegressionTest
import dev.stapler.stelekit.migration.MigrationRunnerTest
import dev.stapler.stelekit.migration.MigrationsTest
import dev.stapler.stelekit.migration.NormalizeJournalNamesMigrationTest
import dev.stapler.stelekit.migration.RepairTest
import dev.stapler.stelekit.platform.PlatformSettingsContainsKeyTest
import dev.stapler.stelekit.repository.CacheInvalidationTest
import dev.stapler.stelekit.repository.ExactTitleMatchTest
import dev.stapler.stelekit.repository.FtsRebuildTest
import dev.stapler.stelekit.repository.SaveBlocksChunkingTest
import dev.stapler.stelekit.repository.SearchLatencyTest
import dev.stapler.stelekit.repository.SearchRepositoryIntegrationTests
import dev.stapler.stelekit.repository.VisitRecencyMultiplierTest
import dev.stapler.stelekit.search.FtsQueryBuilderTest
import dev.stapler.stelekit.stats.LibraryWrappedTest
import dev.stapler.stelekit.testing.OutlinerMonkeyTest
import dev.stapler.stelekit.transfer.qrcode.QrCodecJvmRoundTripTest
import dev.stapler.stelekit.transfer.qrcode.QrCodecJvmTest
import dev.stapler.stelekit.ui.DemoBannerTest
import dev.stapler.stelekit.ui.DiskConflictResolutionTest
import dev.stapler.stelekit.ui.GraphContentDemoFileSystemWiringTest
import dev.stapler.stelekit.ui.GraphContentVaultRepoRootTest
import dev.stapler.stelekit.ui.GraphSwitcherDemoFilterTest
import dev.stapler.stelekit.ui.DragDropReorderTest
import dev.stapler.stelekit.ui.InitializingScreenThemedTest
import dev.stapler.stelekit.ui.KeyboardShortcutTest
import dev.stapler.stelekit.ui.MigrationReadyLoadingTest
import dev.stapler.stelekit.ui.OutlinerRegressionTest
import dev.stapler.stelekit.ui.RecentPagesTest
import dev.stapler.stelekit.ui.StelekitViewModelLlmSettingsTest
import dev.stapler.stelekit.ui.StelekitViewModelLoadingTest
import dev.stapler.stelekit.platform.FileSystemResolveLoadableUriTest
import dev.stapler.stelekit.ui.components.ApplyAutocompleteSelectionTest
import dev.stapler.stelekit.ui.components.AutocompleteMenuTest
import dev.stapler.stelekit.ui.components.BlockItemCodeFenceDispatchTest
import dev.stapler.stelekit.ui.components.LogDashboardTest
import dev.stapler.stelekit.ui.components.MermaidBlockFocusTest
import dev.stapler.stelekit.ui.components.MermaidBlockInteractionTest
import dev.stapler.stelekit.ui.components.MermaidBlockTest
import dev.stapler.stelekit.ui.components.ParseMarkdownWithStylingTest
import dev.stapler.stelekit.ui.components.PerformanceDashboardTest
import dev.stapler.stelekit.ui.components.RememberSteleKitImageLoaderTest
import dev.stapler.stelekit.ui.components.SteleKitImageLoaderMemoryTest
import dev.stapler.stelekit.ui.components.SearchDialogTest
import dev.stapler.stelekit.ui.components.SteleKitAssetMapperTest
import dev.stapler.stelekit.ui.components.SteleKitSafPathMapperTest
import dev.stapler.stelekit.ui.components.SuggestionContextMenuTest
import dev.stapler.stelekit.ui.components.SuggestionNavigatorPanelTest
import dev.stapler.stelekit.ui.components.SuggestionRenderBenchmarkTest
import dev.stapler.stelekit.ui.components.SyncStatusBadgeTest
import dev.stapler.stelekit.ui.components.TopBarTest
import dev.stapler.stelekit.ui.components.settings.AddEditLlmProviderDialogTest
import dev.stapler.stelekit.ui.components.settings.LlmProviderListScreenTest
import dev.stapler.stelekit.ui.components.settings.PerFeatureProviderPickerTest
import dev.stapler.stelekit.ui.components.settings.SettingsDialogTest
import dev.stapler.stelekit.ui.components.settings.VoiceCaptureSettingsTest
import dev.stapler.stelekit.ui.layout.DesktopLayoutTest
import dev.stapler.stelekit.ui.layout.MobileLayoutTest
import dev.stapler.stelekit.ui.layout.SidebarLoadingStateTest
import dev.stapler.stelekit.ui.screens.AllPagesViewModelTest
import dev.stapler.stelekit.ui.screens.JournalsViewSqlDelightTest
import dev.stapler.stelekit.ui.screens.JournalsViewUITest
import dev.stapler.stelekit.ui.screens.PageViewUITest
import dev.stapler.stelekit.ui.screens.git.GitSetupScreenHelpersTest
import dev.stapler.stelekit.ui.screens.git.GitSetupScreenSaveLogicTest
import dev.stapler.stelekit.ui.screens.git.WikiSubdirUriGuardTest
import dev.stapler.stelekit.vault.crypto.CryptoEngineTest
import dev.stapler.stelekit.vault.integration.GraphLayerCryptoTest
import dev.stapler.stelekit.vault.integration.VaultRoundTripTest
import dev.stapler.stelekit.vault.layer.CryptoLayerTest
import dev.stapler.stelekit.vault.perf.VaultPerformanceTest
import dev.stapler.stelekit.vault.property.VaultPropertyTest
import dev.stapler.stelekit.vault.security.AdversarialTest
import dev.stapler.stelekit.vault.security.KeyslotIntegrityTest
import dev.stapler.stelekit.vault.security.NoncePropertyTest
import dev.stapler.stelekit.vault.vault.VaultHeaderSerializerTest
import dev.stapler.stelekit.vault.vault.VaultManagerTest
import dev.stapler.stelekit.voice.ClaudeLlmFormatterProviderTest
import dev.stapler.stelekit.voice.OpenAiLlmFormatterProviderTest
import dev.stapler.stelekit.voice.WhisperSpeechToTextProviderTest
import dev.stapler.stelekit.db.EditorSaveRealGraphTest
import dev.stapler.stelekit.db.FilePathRootMigrationTest
import dev.stapler.stelekit.db.FileSystemCallCountTest
import dev.stapler.stelekit.db.GraphFileWatcherTest
import dev.stapler.stelekit.db.GraphLoaderBatchWriteTest
import dev.stapler.stelekit.db.GraphLoaderCacheTest
import dev.stapler.stelekit.db.GraphLoaderIndexBatchingTest
import dev.stapler.stelekit.db.GraphManagerCloneTest
import dev.stapler.stelekit.db.GraphManagerDatabaseLifecycleTest
import dev.stapler.stelekit.db.GraphRelocationEndToEndTest
import dev.stapler.stelekit.db.GraphWriterEpochCaptureTest
import dev.stapler.stelekit.db.GraphWriterNotYetLoadedTest
import dev.stapler.stelekit.db.ImageAnnotationOfflineTest
import dev.stapler.stelekit.db.ImageImportServiceIntegrationTest
import dev.stapler.stelekit.db.DatabaseOpenDiagnosticsTest
import dev.stapler.stelekit.db.EnsureParentDirectoryTest
import dev.stapler.stelekit.db.GraphManagerDetectionLeakTest
import dev.stapler.stelekit.db.GraphManagerRestartNotesPathTest
import dev.stapler.stelekit.repository.BlockRepositoryClearTest
import dev.stapler.stelekit.repository.CountAllBlocksTest
import dev.stapler.stelekit.ui.StelekitViewModelStartupPathTest
import dev.stapler.stelekit.db.JvmDriverConnectionPropsTest
import dev.stapler.stelekit.db.LargeGraphWarmStartCrashTest
import dev.stapler.stelekit.db.libsql.JvmLibsqlDriverTest
import dev.stapler.stelekit.db.libsql.LibsqlFts5IntegrationTest
import dev.stapler.stelekit.db.MoveInProgressFlagWatcherIntegrationTest
import dev.stapler.stelekit.db.OffGraphReconcileRegressionTest
import dev.stapler.stelekit.db.OffGraphWriteReconcileSpikeTest
import dev.stapler.stelekit.db.PathContainmentSymlinkTest
import dev.stapler.stelekit.db.QueryExecutorGraphSwitchTest
import dev.stapler.stelekit.git.GitCredentialConnectionStoreTest
import dev.stapler.stelekit.git.GitHubDeviceFlowClientTest
import dev.stapler.stelekit.git.GitRefDiagnosticsRedactionTest
import dev.stapler.stelekit.git.GitSyncAttributeMergeE2ETest
import dev.stapler.stelekit.git.GitSyncServiceLfsJvmTest
import dev.stapler.stelekit.git.JvmGitCloneWorkerLauncherTest
import dev.stapler.stelekit.git.JvmGitRepositoryTest
import dev.stapler.stelekit.git.RemoteBranchResolutionTest
import dev.stapler.stelekit.git.RemoteDefaultBranchTest
import dev.stapler.stelekit.git.VaultCredentialStoreTest
import dev.stapler.stelekit.google.DriveApiClientContentTypeTest
import dev.stapler.stelekit.google.DriveExportServiceTest
import dev.stapler.stelekit.google.GoogleApiClientTest
import dev.stapler.stelekit.google.GoogleTokenRefresherTest
import dev.stapler.stelekit.google.JvmGoogleAuthManagerTest
import dev.stapler.stelekit.google.PhotosPickerApiClientTest
import dev.stapler.stelekit.repository.FtsMergeHotPathRegressionTest
import dev.stapler.stelekit.repository.PagePropertyParityTest
import dev.stapler.stelekit.repository.PaginationEnforcementTest
import dev.stapler.stelekit.repository.QueryRepositoryReactivityTest
import dev.stapler.stelekit.repository.RepositoryFlowResilienceTest
import dev.stapler.stelekit.repository.SqlDelightBacklinkRepositoryTest
import dev.stapler.stelekit.repository.SqlDelightBlockRepositoryOutdentTest
import dev.stapler.stelekit.repository.SqlDelightBlockRepositoryWarmPathTest
import dev.stapler.stelekit.repository.SqlDelightImageAnnotationRepositoryTest
import dev.stapler.stelekit.repository.SqlDelightMeasurementAnnotationRepositoryTest
import dev.stapler.stelekit.repository.UpgradeResilienceTest
import dev.stapler.stelekit.annotate.AnnotationExporterTest
import dev.stapler.stelekit.capture.CaptureControllerTargetTest
import dev.stapler.stelekit.capture.CaptureControllerTest
import dev.stapler.stelekit.capture.CaptureDeliveryDedupIntegrationTest
import dev.stapler.stelekit.capture.CaptureWriterIntegrationTest
import dev.stapler.stelekit.capture.CaptureWriterTest
import dev.stapler.stelekit.capture.JKeymasterHotkeyListenerTest
import dev.stapler.stelekit.capture.PendingCapturePollerIntegrationTest
import dev.stapler.stelekit.capture.PendingCapturePollerTest
import dev.stapler.stelekit.capture.PendingCaptureWriterTest
import dev.stapler.stelekit.cli.ArgParserTest
import dev.stapler.stelekit.cli.SyncCommandsTest
import dev.stapler.stelekit.coroutines.GraphScopedSessionThreadSafetyTest
import dev.stapler.stelekit.desktop.CaptureCliArgsTest
import dev.stapler.stelekit.domain.CaptureEnrichmentCoordinatorRealDispatcherTest
import dev.stapler.stelekit.domain.PageNameIndexResilienceTest
import dev.stapler.stelekit.domain.WaybackMachineServiceTest
import dev.stapler.stelekit.llm.CustomOpenAiCompatibleLlmProviderTest
import dev.stapler.stelekit.mcp.McpPendingCapTest
import dev.stapler.stelekit.mcp.McpSocketPathPermissionTest
import dev.stapler.stelekit.mcp.McpToolErrorTest
import dev.stapler.stelekit.merge.MarkdownTargetWriterSymlinkTest
import dev.stapler.stelekit.merge.RoundTripGuardPassRateRegressionTest
import dev.stapler.stelekit.merge.RoundTripGuardPassRateSpikeTest
import dev.stapler.stelekit.merge.RoundTripGuardRealGraphTest
import dev.stapler.stelekit.performance.InstrumentedPageRepositoryTest
import dev.stapler.stelekit.platform.DesktopFileSystemTest
import dev.stapler.stelekit.platform.JvmFileSystemBaseWhitelistTest
import dev.stapler.stelekit.platform.OpenInBrowserJvmTest
import dev.stapler.stelekit.platform.ReplaceFileAtomicallyTest
import dev.stapler.stelekit.platform.security.JvmCredentialStoreTest
import dev.stapler.stelekit.platform.sensor.DesktopFilePickerTest
import dev.stapler.stelekit.service.AttachmentFileNamingTest
import dev.stapler.stelekit.service.JvmMediaAttachmentServiceTest
import dev.stapler.stelekit.transfer.FrameTransportSignatureTest
import dev.stapler.stelekit.transfer.qrcode.QrRoundTripFidelityTest
import dev.stapler.stelekit.transfer.qrcode.QrTransferSettingsPersistenceTest
import dev.stapler.stelekit.voice.GeminiLlmFormatterProviderTest
import dev.stapler.stelekit.benchmark.BlockInsertBenchmarkTest
import dev.stapler.stelekit.benchmark.JournalViewFanoutBenchmarkTest
import dev.stapler.stelekit.benchmark.LatencyShimFileSystemTest
import dev.stapler.stelekit.benchmark.LibsqlConcurrentWriteBenchmarkTest
import dev.stapler.stelekit.benchmark.LibsqlSequentialWriteBenchmarkTest
import dev.stapler.stelekit.benchmark.UnifiedBenchmarkRunner
import dev.stapler.stelekit.benchmark.UserSessionBenchmarkTest
import dev.stapler.stelekit.ui.annotate.AnnotationEditorScreenKeyboardTest
import dev.stapler.stelekit.ui.annotate.AnnotationEditorScreenTagWiringTest
import dev.stapler.stelekit.ui.annotate.AnnotationToolbarShortcutTest
import dev.stapler.stelekit.ui.annotate.DisabledTintContrastTest
import dev.stapler.stelekit.ui.AppStorageSubtitleTest
import dev.stapler.stelekit.ui.assets.AssetBrowserScreenTest
import dev.stapler.stelekit.ui.CaptureAndImportTest
import dev.stapler.stelekit.ui.CommandPaletteFocusRetentionTest
import dev.stapler.stelekit.ui.components.BlockItemGestureTest
import dev.stapler.stelekit.ui.components.BlockListDropZoneTest
import dev.stapler.stelekit.ui.components.BlockListLassoSelectTest
import dev.stapler.stelekit.ui.components.ImageBlockDetectionTest
import dev.stapler.stelekit.ui.components.ParseTableContentTest
import dev.stapler.stelekit.ui.components.QueryBlockTest
import dev.stapler.stelekit.ui.components.SnippetTextTest
import dev.stapler.stelekit.ui.components.StorageMoveChoiceDialogTest
import dev.stapler.stelekit.ui.components.StorageMoveConfirmDialogTest
import dev.stapler.stelekit.ui.components.StorageMoveProgressDialogTest
import dev.stapler.stelekit.ui.components.TypedLazyItemsTest
import dev.stapler.stelekit.ui.components.UnifiedLocationPickerTest
import dev.stapler.stelekit.ui.components.WikilinkWrapTest
import dev.stapler.stelekit.ui.EditorToolbarRecompositionTest
import dev.stapler.stelekit.ui.ErrorStateNoDeadEndTest
import dev.stapler.stelekit.ui.ExternalFileChangeErrorHandlingTest
import dev.stapler.stelekit.ui.FirstRunHotkeyNoticeTest
import dev.stapler.stelekit.ui.GraphSwitcherMoveStorageBrowseClickTest
import dev.stapler.stelekit.ui.HotkeyConflictNoticeTest
import dev.stapler.stelekit.ui.JournalWarmStartRaceHealingTest
import dev.stapler.stelekit.ui.LlmSuggestionCaptionStatesUiTest
import dev.stapler.stelekit.ui.screens.EmptyGraphStateScreenTest
import dev.stapler.stelekit.ui.screens.git.GitSetupScreenCredentialPersistenceTest
import dev.stapler.stelekit.ui.screens.llm.LlmSuggestionReviewScreenMcpChipTest
import dev.stapler.stelekit.ui.SectionPickerTest
import dev.stapler.stelekit.ui.ShortcutTableConsistencyTest
import dev.stapler.stelekit.ui.StelekitViewModelCrashReproductionTest
import dev.stapler.stelekit.ui.StelekitViewModelSyncStateIntegrationTest
import dev.stapler.stelekit.ui.StelekitViewModelSyncStateTest
import dev.stapler.stelekit.ui.SuggestionBottomSheetChipsUiTest
import dev.stapler.stelekit.ui.TagInsertionFlagshipUiTest
import dev.stapler.stelekit.ui.transfer.CameraPermissionRationaleDialogUxTest
import dev.stapler.stelekit.ui.transfer.QrCancelUxTest
import dev.stapler.stelekit.ui.transfer.QrDecodePreflightUxTest
import dev.stapler.stelekit.ui.transfer.QrDecodeScreenTest
import dev.stapler.stelekit.ui.transfer.QrDecodeScreenUxTest
import dev.stapler.stelekit.ui.transfer.QrEncodeScreenFlashSafetyUxTest
import dev.stapler.stelekit.ui.transfer.QrEncodeScreenTest
import dev.stapler.stelekit.ui.transfer.QrEncodeScreenUxTest
import dev.stapler.stelekit.ui.transfer.QrImportConfirmDialogTest
import dev.stapler.stelekit.ui.transfer.QrImportConfirmDialogUxTest
import dev.stapler.stelekit.ui.transfer.QrImportEntryPointUxTest
import dev.stapler.stelekit.ui.transfer.QrNoDeadEndUxTest
import dev.stapler.stelekit.ui.transfer.QrSendEntryPointUxTest
import dev.stapler.stelekit.ui.transfer.QrTransferAccessibilityUxTest
import dev.stapler.stelekit.ui.transfer.QrTransferEntryPointsTest
import dev.stapler.stelekit.ui.VoiceCaptureStateTransitionTest
import dev.stapler.stelekit.mcp.ListBacklinksToolTest
import dev.stapler.stelekit.mcp.ListGraphsToolTest
import dev.stapler.stelekit.mcp.McpAdapterTest
import dev.stapler.stelekit.mcp.McpClientIdentityTest
import dev.stapler.stelekit.mcp.McpEndToEndReadTest
import dev.stapler.stelekit.mcp.McpLauncherMainTest
import dev.stapler.stelekit.mcp.McpRealSubprocessSmokeTest
import dev.stapler.stelekit.mcp.McpSocketServerTest
import dev.stapler.stelekit.mcp.McpToolTimeoutTest
import dev.stapler.stelekit.mcp.PageResourceTest
import dev.stapler.stelekit.mcp.ProposeBlockEditToolTest
import dev.stapler.stelekit.mcp.ProposeNewPageToolTest
import dev.stapler.stelekit.mcp.ReadPageToolTest
import dev.stapler.stelekit.mcp.SearchGraphToolTest
import dev.stapler.stelekit.capture.CaptureSocketClientTest
import dev.stapler.stelekit.capture.CaptureSocketListenerTest
import dev.stapler.stelekit.desktop.MainCaptureFlowTest
import dev.stapler.stelekit.desktop.MainCliCaptureIntegrationTest
import dev.stapler.stelekit.capture.CaptureWriterSourceAuditTest
import dev.stapler.stelekit.docs.WebsiteDocsCoverageTest
import dev.stapler.stelekit.transfer.AirGapGuardTest
import dev.stapler.stelekit.git.PatLeakageAuditTest
import dev.stapler.stelekit.ui.StelekitViewModelShareExportTest
import dev.stapler.stelekit.capture.CapturePopupWindowTest
import dev.stapler.stelekit.capture.CapturePopupWindowUxTest
import org.junit.runner.RunWith
import org.junit.runners.Suite

@RunWith(Suite::class)
@Suite.SuiteClasses(
    GraphLoadJankTest::class,
    GraphLoadTimingTest::class,
    RepositoryBenchmarkRunnerTest::class,
    RepositoryBenchmarkTest::class,
    CacheHitBenchmarkTest::class,
    DatabaseIntegrationTest::class,
    AppLoadJournalIntegrationTest::class,
    BacklinkRenamerTest::class,
    BlocksFtsTriggerTest::class,
    ConflictMarkerDetectorTest::class,
    DatabaseWriteActorTest::class,
    DemoGraphIntegrationTest::class,
    DiffMergeTest::class,
    ExternalChangeConflictTest::class,
    FileRegistryTest::class,
    GraphLoaderIntegrationTest::class,
    GraphLoaderNotesPathTest::class,
    ImageSidecarIndexerTest::class,
    PlatformImageDownscalerJvmTest::class,
    ImageSidecarManagerTest::class,
    SectionManagementCoordinatorTest::class,
    GraphLoaderProgressiveTest::class,
    GraphLoaderSectionFilterTest::class,
    GraphLoaderTest::class,
    SectionFilterTest::class,
    SectionManifestParserTest::class,
    NotesFolderMetadataStoresTest::class,
    GraphLoaderWatcherTest::class,
    GraphWriterTest::class,
    MigrationRunnerApplyAllTest::class,
    PooledJdbcSqliteDriverTest::class,
    QueryPlanAuditTest::class,
    SanitizationTest::class,
    DemoGraphCoverageTest::class,
    FeatureDocRegistryTest::class,
    ClaudeTopicEnricherTest::class,
    UrlFetcherJvmTest::class,
    ExportIntegrationTest::class,
    HtmlExporterTest::class,
    GitRepoHistoryStoreTest::class,
    JournalParseReproTest::class,
    PipelineReproductionTest::class,
    ChangeApplierTest::class,
    DagValidatorTest::class,
    DryRunTest::class,
    DslEvaluatorTest::class,
    MigrationDslTest::class,
    MigrationRegressionTest::class,
    MigrationRunnerTest::class,
    MigrationsTest::class,
    NormalizeJournalNamesMigrationTest::class,
    RepairTest::class,
    CacheInvalidationTest::class,
    ExactTitleMatchTest::class,
    FtsRebuildTest::class,
    SaveBlocksChunkingTest::class,
    SearchLatencyTest::class,
    SearchRepositoryIntegrationTests::class,
    VisitRecencyMultiplierTest::class,
    FtsQueryBuilderTest::class,
    dev.stapler.stelekit.stats.LibraryStatsProviderTest::class,
    dev.stapler.stelekit.stats.FileLibraryStatsProviderTest::class,
    LibraryWrappedTest::class,
    OutlinerMonkeyTest::class,
    QrCodecJvmTest::class,
    QrCodecJvmRoundTripTest::class,
    ApplyAutocompleteSelectionTest::class,
    BlockItemCodeFenceDispatchTest::class,
    MermaidBlockFocusTest::class,
    MermaidBlockInteractionTest::class,
    MermaidBlockTest::class,
    ParseMarkdownWithStylingTest::class,
    SearchDialogTest::class,
    FileSystemResolveLoadableUriTest::class,
    SteleKitAssetMapperTest::class,
    RememberSteleKitImageLoaderTest::class,
    SteleKitImageLoaderMemoryTest::class,
    SteleKitSafPathMapperTest::class,
    SuggestionContextMenuTest::class,
    SuggestionNavigatorPanelTest::class,
    SuggestionRenderBenchmarkTest::class,
    SyncStatusBadgeTest::class,
    TopBarTest::class,
    DemoBannerTest::class,
    GraphContentDemoFileSystemWiringTest::class,
    GraphContentVaultRepoRootTest::class,
    InitializingScreenThemedTest::class,
    GraphSwitcherDemoFilterTest::class,
    DiskConflictResolutionTest::class,
    DragDropReorderTest::class,
    KeyboardShortcutTest::class,
    DesktopLayoutTest::class,
    MobileLayoutTest::class,
    SidebarLoadingStateTest::class,
    MigrationReadyLoadingTest::class,
    OutlinerRegressionTest::class,
    RecentPagesTest::class,
    AllPagesViewModelTest::class,
    JournalsViewSqlDelightTest::class,
    JournalsViewUITest::class,
    PageViewUITest::class,
    GitSetupScreenHelpersTest::class,
    GitSetupScreenSaveLogicTest::class,
    WikiSubdirUriGuardTest::class,
    StelekitViewModelLoadingTest::class,
    StelekitViewModelLlmSettingsTest::class,
    LlmProviderListScreenTest::class,
    AddEditLlmProviderDialogTest::class,
    PerFeatureProviderPickerTest::class,
    SettingsDialogTest::class,
    VoiceCaptureSettingsTest::class,
    CryptoEngineTest::class,
    GraphLayerCryptoTest::class,
    VaultRoundTripTest::class,
    CryptoLayerTest::class,
    VaultPerformanceTest::class,
    VaultPropertyTest::class,
    AdversarialTest::class,
    KeyslotIntegrityTest::class,
    NoncePropertyTest::class,
    VaultHeaderSerializerTest::class,
    VaultManagerTest::class,
    ClaudeLlmFormatterProviderTest::class,
    OpenAiLlmFormatterProviderTest::class,
    RemoteLlmProviderTest::class,
    WhisperSpeechToTextProviderTest::class,
    PlatformSettingsContainsKeyTest::class,
    EditorSaveRealGraphTest::class,
    FilePathRootMigrationTest::class,
    FileSystemCallCountTest::class,
    GraphFileWatcherTest::class,
    GraphLoaderBatchWriteTest::class,
    GraphLoaderCacheTest::class,
    GraphLoaderIndexBatchingTest::class,
    GraphManagerCloneTest::class,
    GraphManagerDatabaseLifecycleTest::class,
    GraphRelocationEndToEndTest::class,
    GraphWriterEpochCaptureTest::class,
    GraphWriterNotYetLoadedTest::class,
    ImageAnnotationOfflineTest::class,
    ImageImportServiceIntegrationTest::class,
    DatabaseOpenDiagnosticsTest::class,
    EnsureParentDirectoryTest::class,
    GraphManagerDetectionLeakTest::class,
    GraphManagerRestartNotesPathTest::class,
    BlockRepositoryClearTest::class,
    CountAllBlocksTest::class,
    StelekitViewModelStartupPathTest::class,
    JvmDriverConnectionPropsTest::class,
    LargeGraphWarmStartCrashTest::class,
    JvmLibsqlDriverTest::class,
    LibsqlFts5IntegrationTest::class,
    MoveInProgressFlagWatcherIntegrationTest::class,
    OffGraphReconcileRegressionTest::class,
    OffGraphWriteReconcileSpikeTest::class,
    PathContainmentSymlinkTest::class,
    QueryExecutorGraphSwitchTest::class,
    GitCredentialConnectionStoreTest::class,
    GitHubDeviceFlowClientTest::class,
    GitRefDiagnosticsRedactionTest::class,
    GitSyncAttributeMergeE2ETest::class,
    GitSyncServiceLfsJvmTest::class,
    JvmGitCloneWorkerLauncherTest::class,
    JvmGitRepositoryTest::class,
    RemoteBranchResolutionTest::class,
    RemoteDefaultBranchTest::class,
    VaultCredentialStoreTest::class,
    DriveApiClientContentTypeTest::class,
    DriveExportServiceTest::class,
    GoogleApiClientTest::class,
    GoogleTokenRefresherTest::class,
    JvmGoogleAuthManagerTest::class,
    PhotosPickerApiClientTest::class,
    FtsMergeHotPathRegressionTest::class,
    PagePropertyParityTest::class,
    PaginationEnforcementTest::class,
    QueryRepositoryReactivityTest::class,
    RepositoryFlowResilienceTest::class,
    SqlDelightBacklinkRepositoryTest::class,
    SqlDelightBlockRepositoryOutdentTest::class,
    SqlDelightBlockRepositoryWarmPathTest::class,
    SqlDelightImageAnnotationRepositoryTest::class,
    SqlDelightMeasurementAnnotationRepositoryTest::class,
    UpgradeResilienceTest::class,
    AnnotationExporterTest::class,
    CaptureControllerTargetTest::class,
    CaptureControllerTest::class,
    CaptureDeliveryDedupIntegrationTest::class,
    CaptureWriterIntegrationTest::class,
    CaptureWriterTest::class,
    JKeymasterHotkeyListenerTest::class,
    PendingCapturePollerIntegrationTest::class,
    PendingCapturePollerTest::class,
    PendingCaptureWriterTest::class,
    ArgParserTest::class,
    SyncCommandsTest::class,
    GraphScopedSessionThreadSafetyTest::class,
    CaptureCliArgsTest::class,
    CaptureEnrichmentCoordinatorRealDispatcherTest::class,
    PageNameIndexResilienceTest::class,
    WaybackMachineServiceTest::class,
    CustomOpenAiCompatibleLlmProviderTest::class,
    McpPendingCapTest::class,
    McpSocketPathPermissionTest::class,
    McpToolErrorTest::class,
    MarkdownTargetWriterSymlinkTest::class,
    RoundTripGuardPassRateRegressionTest::class,
    RoundTripGuardPassRateSpikeTest::class,
    RoundTripGuardRealGraphTest::class,
    InstrumentedPageRepositoryTest::class,
    DesktopFileSystemTest::class,
    JvmFileSystemBaseWhitelistTest::class,
    OpenInBrowserJvmTest::class,
    ReplaceFileAtomicallyTest::class,
    JvmCredentialStoreTest::class,
    DesktopFilePickerTest::class,
    AttachmentFileNamingTest::class,
    JvmMediaAttachmentServiceTest::class,
    FrameTransportSignatureTest::class,
    QrRoundTripFidelityTest::class,
    QrTransferSettingsPersistenceTest::class,
    GeminiLlmFormatterProviderTest::class,
    BlockInsertBenchmarkTest::class,
    JournalViewFanoutBenchmarkTest::class,
    LatencyShimFileSystemTest::class,
    LibsqlConcurrentWriteBenchmarkTest::class,
    LibsqlSequentialWriteBenchmarkTest::class,
    UnifiedBenchmarkRunner::class,
    UserSessionBenchmarkTest::class,
    AnnotationEditorScreenKeyboardTest::class,
    AnnotationEditorScreenTagWiringTest::class,
    AnnotationToolbarShortcutTest::class,
    DisabledTintContrastTest::class,
    AppStorageSubtitleTest::class,
    AssetBrowserScreenTest::class,
    CaptureAndImportTest::class,
    CommandPaletteFocusRetentionTest::class,
    BlockItemGestureTest::class,
    BlockListDropZoneTest::class,
    BlockListLassoSelectTest::class,
    ImageBlockDetectionTest::class,
    ParseTableContentTest::class,
    QueryBlockTest::class,
    SnippetTextTest::class,
    StorageMoveChoiceDialogTest::class,
    StorageMoveConfirmDialogTest::class,
    StorageMoveProgressDialogTest::class,
    TypedLazyItemsTest::class,
    UnifiedLocationPickerTest::class,
    WikilinkWrapTest::class,
    EditorToolbarRecompositionTest::class,
    ErrorStateNoDeadEndTest::class,
    ExternalFileChangeErrorHandlingTest::class,
    FirstRunHotkeyNoticeTest::class,
    GraphSwitcherMoveStorageBrowseClickTest::class,
    HotkeyConflictNoticeTest::class,
    JournalWarmStartRaceHealingTest::class,
    LlmSuggestionCaptionStatesUiTest::class,
    EmptyGraphStateScreenTest::class,
    GitSetupScreenCredentialPersistenceTest::class,
    LlmSuggestionReviewScreenMcpChipTest::class,
    SectionPickerTest::class,
    ShortcutTableConsistencyTest::class,
    StelekitViewModelCrashReproductionTest::class,
    StelekitViewModelSyncStateIntegrationTest::class,
    StelekitViewModelSyncStateTest::class,
    SuggestionBottomSheetChipsUiTest::class,
    TagInsertionFlagshipUiTest::class,
    CameraPermissionRationaleDialogUxTest::class,
    QrCancelUxTest::class,
    QrDecodePreflightUxTest::class,
    QrDecodeScreenTest::class,
    QrDecodeScreenUxTest::class,
    QrEncodeScreenFlashSafetyUxTest::class,
    QrEncodeScreenTest::class,
    QrEncodeScreenUxTest::class,
    QrImportConfirmDialogTest::class,
    QrImportConfirmDialogUxTest::class,
    QrImportEntryPointUxTest::class,
    QrNoDeadEndUxTest::class,
    QrSendEntryPointUxTest::class,
    QrTransferAccessibilityUxTest::class,
    QrTransferEntryPointsTest::class,
    VoiceCaptureStateTransitionTest::class,
    ListBacklinksToolTest::class,
    ListGraphsToolTest::class,
    McpAdapterTest::class,
    McpClientIdentityTest::class,
    McpEndToEndReadTest::class,
    McpLauncherMainTest::class,
    McpRealSubprocessSmokeTest::class,
    McpSocketServerTest::class,
    McpToolTimeoutTest::class,
    PageResourceTest::class,
    ProposeBlockEditToolTest::class,
    ProposeNewPageToolTest::class,
    ReadPageToolTest::class,
    SearchGraphToolTest::class,
    CaptureSocketClientTest::class,
    CaptureSocketListenerTest::class,
    MainCaptureFlowTest::class,
    MainCliCaptureIntegrationTest::class,
    CaptureWriterSourceAuditTest::class,
    WebsiteDocsCoverageTest::class,
    AirGapGuardTest::class,
    PatLeakageAuditTest::class,
    StelekitViewModelShareExportTest::class,
    CapturePopupWindowTest::class,
    CapturePopupWindowUxTest::class,
    AutocompleteMenuTest::class,
    LogDashboardTest::class,
    PerformanceDashboardTest::class,
    SectionsSettingsDialogsTest::class,
    AllJvmTestsCompletenessTest::class,
)
class AllJvmTests
