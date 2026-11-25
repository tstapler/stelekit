package dev.stapler.stelekit

import dev.stapler.stelekit.benchmark.GraphLoadJankTest
import dev.stapler.stelekit.benchmark.GraphLoadTimingTest
import dev.stapler.stelekit.console.DbRestoreSwapTest
import dev.stapler.stelekit.console.spike.AuthorizerSpikeTest
import dev.stapler.stelekit.db.DemoGraphIntegrationTest
import dev.stapler.stelekit.db.GraphLoaderNotesPathTest
import dev.stapler.stelekit.ui.SectionsSettingsDialogsTest
import dev.stapler.stelekit.sections.SectionFilterTest
import dev.stapler.stelekit.sections.SectionManifestParserTest
import dev.stapler.stelekit.sections.NotesFolderMetadataStoresTest
import dev.stapler.stelekit.db.QueryPlanAuditTest
import dev.stapler.stelekit.docs.DemoGraphCoverageTest
import dev.stapler.stelekit.domain.ClaudeTopicEnricherTest
import dev.stapler.stelekit.domain.UrlFetcherJvmTest
import dev.stapler.stelekit.migration.DagValidatorTest
import dev.stapler.stelekit.migration.DslEvaluatorTest
import dev.stapler.stelekit.migration.MigrationDslTest
import dev.stapler.stelekit.platform.PlatformSettingsContainsKeyTest
import dev.stapler.stelekit.repository.SearchRepositoryIntegrationTests
import dev.stapler.stelekit.search.FtsQueryBuilderTest
import dev.stapler.stelekit.stats.LibraryWrappedTest
import dev.stapler.stelekit.ui.DemoBannerTest
import dev.stapler.stelekit.ui.GraphContentDemoFileSystemWiringTest
import dev.stapler.stelekit.ui.GraphContentVaultRepoRootTest
import dev.stapler.stelekit.ui.GraphSwitcherDemoFilterTest
import dev.stapler.stelekit.ui.InitializingScreenThemedTest
import dev.stapler.stelekit.ui.KeyboardShortcutTest
import dev.stapler.stelekit.ui.MigrationReadyLoadingTest
import dev.stapler.stelekit.ui.OutlinerRegressionTest
import dev.stapler.stelekit.ui.components.AutocompleteMenuTest
import dev.stapler.stelekit.ui.components.BlockItemCodeFenceDispatchTest
import dev.stapler.stelekit.ui.components.LogDashboardTest
import dev.stapler.stelekit.ui.components.MermaidBlockFocusTest
import dev.stapler.stelekit.ui.components.MermaidBlockInteractionTest
import dev.stapler.stelekit.ui.components.MermaidBlockTest
import dev.stapler.stelekit.ui.components.MobileBlockToolbarDiskConflictTest
import dev.stapler.stelekit.ui.components.MobileBlockToolbarGapButtonsTest
import dev.stapler.stelekit.ui.components.PerformanceDashboardTest
import dev.stapler.stelekit.ui.components.RememberSteleKitImageLoaderTest
import dev.stapler.stelekit.ui.components.SearchDialogTest
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
import dev.stapler.stelekit.ui.screens.JournalsViewSqlDelightTest
import dev.stapler.stelekit.ui.screens.JournalsViewUITest
import dev.stapler.stelekit.ui.screens.PageViewUITest
import dev.stapler.stelekit.ui.screens.git.GitSetupScreenHelpersTest
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
import dev.stapler.stelekit.db.ImageAnnotationOfflineTest
import dev.stapler.stelekit.db.OffGraphReconcileRegressionTest
import dev.stapler.stelekit.db.OffGraphWriteReconcileSpikeTest
import dev.stapler.stelekit.git.GitHubDeviceFlowClientTest
import dev.stapler.stelekit.git.GitHubDeviceFlowClientEdgeCasesTest
import dev.stapler.stelekit.platform.download.ResumableDownloadTest
import dev.stapler.stelekit.git.CloneEntryPointGateTest
import dev.stapler.stelekit.git.JvmGitCloneWorkerLauncherTest
import dev.stapler.stelekit.git.JgitCancelLockTest
import dev.stapler.stelekit.git.LockClaimIntegrationTest
import dev.stapler.stelekit.git.BeginOwnedOperationOrderingTest
import dev.stapler.stelekit.git.ProcessKillOrphanLockTest
import dev.stapler.stelekit.git.TwoJvmRecoveryRaceTest
import dev.stapler.stelekit.git.CrossProcessSyncOverDeadRecordTest
import dev.stapler.stelekit.git.JgitCancelSweepTest
import dev.stapler.stelekit.git.VaultCredentialStoreTest
import dev.stapler.stelekit.google.JvmGoogleAuthManagerTest
import dev.stapler.stelekit.ui.sketch.SketchCanvasDrawTest
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
import dev.stapler.stelekit.cli.SketchAgentCliTest
import dev.stapler.stelekit.cli.SketchUnderlayCliTest
import dev.stapler.stelekit.ui.sketch.v2.SheetImageBitmapsTest
import dev.stapler.stelekit.cli.SketchEditCliTest
import dev.stapler.stelekit.cli.SketchWallsCliTest
import dev.stapler.stelekit.ui.sketch.SketchSearchSectionTest
import dev.stapler.stelekit.cli.SketchSpecRoundTripPropertyTest
import dev.stapler.stelekit.cli.SyncCommandsTest
import dev.stapler.stelekit.cli.SyncMainClaimTest
import dev.stapler.stelekit.desktop.CaptureCliArgsTest
import dev.stapler.stelekit.domain.CaptureEnrichmentCoordinatorRealDispatcherTest
import dev.stapler.stelekit.desktop.DesktopMeasurementDeviceFactoriesTest
import dev.stapler.stelekit.domain.HtmlClipboardReaderTest
import dev.stapler.stelekit.domain.HtmlPasteEntryPointTest
import dev.stapler.stelekit.domain.WaybackMachineServiceTest
import dev.stapler.stelekit.mcp.McpPendingCapTest
import dev.stapler.stelekit.mcp.McpSocketPathPermissionTest
import dev.stapler.stelekit.mcp.McpToolErrorTest
import dev.stapler.stelekit.merge.MarkdownTargetWriterSymlinkTest
import dev.stapler.stelekit.merge.RoundTripGuardPassRateRegressionTest
import dev.stapler.stelekit.merge.RoundTripGuardPassRateSpikeTest
import dev.stapler.stelekit.merge.RoundTripGuardRealGraphTest
import dev.stapler.stelekit.performance.InstrumentedPageRepositoryTest
import dev.stapler.stelekit.platform.DesktopFileSystemTest
import dev.stapler.stelekit.platform.sensor.DesktopFilePickerTest
import dev.stapler.stelekit.service.AttachmentFileNamingTest
import dev.stapler.stelekit.service.JvmMediaAttachmentServiceTest
import dev.stapler.stelekit.transfer.qrcode.QrTransferSettingsPersistenceTest
import dev.stapler.stelekit.benchmark.LibsqlConcurrentWriteBenchmarkTest
import dev.stapler.stelekit.benchmark.LibsqlSequentialWriteBenchmarkTest
import dev.stapler.stelekit.benchmark.TableCommitBenchmarkTest
import dev.stapler.stelekit.benchmark.UnifiedBenchmarkRunner
import dev.stapler.stelekit.benchmark.UserSessionBenchmarkTest
import dev.stapler.stelekit.ui.annotate.AnnotationEditorScreenKeyboardTest
import dev.stapler.stelekit.ui.annotate.AnnotationKeyboardShortcutsTest
import dev.stapler.stelekit.ui.annotate.AnnotationEditorScreenTagWiringTest
import dev.stapler.stelekit.ui.annotate.AnnotationToolbarShortcutTest
import dev.stapler.stelekit.ui.library.LibraryScreenTest
import dev.stapler.stelekit.ui.CommandPaletteFocusRetentionTest
import dev.stapler.stelekit.ui.components.BlockItemGestureTest
import dev.stapler.stelekit.ui.components.QueryBlockTest
import dev.stapler.stelekit.ui.components.StorageMoveChoiceDialogTest
import dev.stapler.stelekit.ui.components.StorageMoveConfirmDialogTest
import dev.stapler.stelekit.ui.components.StorageMoveProgressDialogTest
import dev.stapler.stelekit.ui.components.TypedLazyItemsTest
import dev.stapler.stelekit.ui.components.UnifiedLocationPickerTest
import dev.stapler.stelekit.ui.EditorToolbarRecompositionTest
import dev.stapler.stelekit.ui.ErrorStateNoDeadEndTest
import dev.stapler.stelekit.ui.FirstRunHotkeyNoticeTest
import dev.stapler.stelekit.ui.GraphSwitcherMoveStorageBrowseClickTest
import dev.stapler.stelekit.ui.HotkeyConflictNoticeTest
import dev.stapler.stelekit.ui.LlmSuggestionCaptionStatesUiTest
import dev.stapler.stelekit.ui.screens.EmptyGraphStateScreenTest
import dev.stapler.stelekit.ui.screens.llm.LlmSuggestionReviewScreenMcpChipTest
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
import dev.stapler.stelekit.capture.CapturePopupWindowTest
import dev.stapler.stelekit.ui.sketch.v2.guide.GuideUiTest
import dev.stapler.stelekit.capture.CapturePopupWindowUxTest
import dev.stapler.stelekit.wiring.DesktopWiredAppFixtureTest
import dev.stapler.stelekit.wiring.GitSyncServiceRegistryBalanceTest
import dev.stapler.stelekit.logging.LogManagerConcurrencyTest
import dev.stapler.stelekit.wiring.GitSyncStackEntryPointTest
import dev.stapler.stelekit.wiring.ScheduledSyncOriginE2ETest
import dev.stapler.stelekit.wiring.DesktopTaskRootWiringTest
import dev.stapler.stelekit.wiring.TaskSchedulerDesktopEntryPointTest
import dev.stapler.stelekit.wiring.JvmPlatformCapsRootTest
import org.junit.runner.RunWith
import org.junit.runners.Suite

@RunWith(Suite::class)
@Suite.SuiteClasses(
    GraphLoadJankTest::class,
    DbRestoreSwapTest::class,
    AuthorizerSpikeTest::class,
    GraphLoadTimingTest::class,
    DemoGraphIntegrationTest::class,
    GraphLoaderNotesPathTest::class,
    SectionFilterTest::class,
    SectionManifestParserTest::class,
    NotesFolderMetadataStoresTest::class,
    QueryPlanAuditTest::class,
    DemoGraphCoverageTest::class,
    ClaudeTopicEnricherTest::class,
    UrlFetcherJvmTest::class,
    DagValidatorTest::class,
    DslEvaluatorTest::class,
    MigrationDslTest::class,
    SearchRepositoryIntegrationTests::class,
    FtsQueryBuilderTest::class,
    dev.stapler.stelekit.stats.LibraryStatsProviderTest::class,
    dev.stapler.stelekit.stats.FileLibraryStatsProviderTest::class,
    LibraryWrappedTest::class,
    BlockItemCodeFenceDispatchTest::class,
    MermaidBlockFocusTest::class,
    MermaidBlockInteractionTest::class,
    MermaidBlockTest::class,
    MobileBlockToolbarDiskConflictTest::class,
    MobileBlockToolbarGapButtonsTest::class,
    SearchDialogTest::class,
    RememberSteleKitImageLoaderTest::class,
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
    KeyboardShortcutTest::class,
    DesktopLayoutTest::class,
    MobileLayoutTest::class,
    SidebarLoadingStateTest::class,
    MigrationReadyLoadingTest::class,
    OutlinerRegressionTest::class,
    JournalsViewSqlDelightTest::class,
    JournalsViewUITest::class,
    PageViewUITest::class,
    GitSetupScreenHelpersTest::class,
    WikiSubdirUriGuardTest::class,
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
    PlatformSettingsContainsKeyTest::class,
    ImageAnnotationOfflineTest::class,
    OffGraphReconcileRegressionTest::class,
    OffGraphWriteReconcileSpikeTest::class,
    GitHubDeviceFlowClientTest::class,
    GitHubDeviceFlowClientEdgeCasesTest::class,
    ResumableDownloadTest::class,
    CloneEntryPointGateTest::class,
    JvmGitCloneWorkerLauncherTest::class,
    JgitCancelLockTest::class,
    LockClaimIntegrationTest::class,
    BeginOwnedOperationOrderingTest::class,
    ProcessKillOrphanLockTest::class,
    TwoJvmRecoveryRaceTest::class,
    CrossProcessSyncOverDeadRecordTest::class,
    JgitCancelSweepTest::class,
    VaultCredentialStoreTest::class,
    JvmGoogleAuthManagerTest::class,
    SketchCanvasDrawTest::class,
    dev.stapler.stelekit.ui.sketch.v2.InputSpikeTest::class,
    dev.stapler.stelekit.ui.sketch.v2.PointerAdapterDesktopTest::class,
    dev.stapler.stelekit.ui.sketch.v2.OutlineDialogsDesktopTest::class,
    dev.stapler.stelekit.sketch.v2.TextMetricsDesktopEntryPointTest::class,
    dev.stapler.stelekit.sketch.v2.SketchStorageDesktopEntryPointTest::class,
    dev.stapler.stelekit.ui.sketch.SketchPreviewRenderTest::class,
    dev.stapler.stelekit.sketch.v2.SceneStructureBenchmark::class,
    dev.stapler.stelekit.sketch.v2.SceneStructureBenchmarkGuard::class,
    dev.stapler.stelekit.sketch.v2.baseline.CanvasReferenceBenchmark::class,
    dev.stapler.stelekit.ui.sketch.v2.ScenePainterTest::class,
    dev.stapler.stelekit.sketch.v2.PngExportTest::class,
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
    SketchAgentCliTest::class,
    dev.stapler.stelekit.cli.SketchCliPaperTest::class,
    SketchUnderlayCliTest::class,
    SheetImageBitmapsTest::class,
    SketchEditCliTest::class,
    SketchWallsCliTest::class,
    SketchSearchSectionTest::class,
    SketchSpecRoundTripPropertyTest::class,
    SyncCommandsTest::class,
    SyncMainClaimTest::class,
    CaptureCliArgsTest::class,
    CaptureEnrichmentCoordinatorRealDispatcherTest::class,
    WaybackMachineServiceTest::class,
    HtmlPasteEntryPointTest::class,
    HtmlClipboardReaderTest::class,
    DesktopMeasurementDeviceFactoriesTest::class,
    McpPendingCapTest::class,
    McpSocketPathPermissionTest::class,
    McpToolErrorTest::class,
    MarkdownTargetWriterSymlinkTest::class,
    RoundTripGuardPassRateRegressionTest::class,
    RoundTripGuardPassRateSpikeTest::class,
    RoundTripGuardRealGraphTest::class,
    InstrumentedPageRepositoryTest::class,
    DesktopFileSystemTest::class,
    DesktopFilePickerTest::class,
    AttachmentFileNamingTest::class,
    JvmMediaAttachmentServiceTest::class,
    QrTransferSettingsPersistenceTest::class,
    LibsqlConcurrentWriteBenchmarkTest::class,
    TableCommitBenchmarkTest::class,
    LibsqlSequentialWriteBenchmarkTest::class,
    UnifiedBenchmarkRunner::class,
    UserSessionBenchmarkTest::class,
    AnnotationEditorScreenKeyboardTest::class,
    AnnotationKeyboardShortcutsTest::class,
    AnnotationEditorScreenTagWiringTest::class,
    AnnotationToolbarShortcutTest::class,
    LibraryScreenTest::class,
    CommandPaletteFocusRetentionTest::class,
    BlockItemGestureTest::class,
    QueryBlockTest::class,
    StorageMoveChoiceDialogTest::class,
    StorageMoveConfirmDialogTest::class,
    StorageMoveProgressDialogTest::class,
    TypedLazyItemsTest::class,
    UnifiedLocationPickerTest::class,
    EditorToolbarRecompositionTest::class,
    ErrorStateNoDeadEndTest::class,
    FirstRunHotkeyNoticeTest::class,
    GraphSwitcherMoveStorageBrowseClickTest::class,
    HotkeyConflictNoticeTest::class,
    LlmSuggestionCaptionStatesUiTest::class,
    EmptyGraphStateScreenTest::class,
    LlmSuggestionReviewScreenMcpChipTest::class,
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
    CapturePopupWindowTest::class,
    GuideUiTest::class,
    CapturePopupWindowUxTest::class,
    AutocompleteMenuTest::class,
    LogDashboardTest::class,
    PerformanceDashboardTest::class,
    SectionsSettingsDialogsTest::class,
    AllJvmTestsCompletenessTest::class,
    DesktopWiredAppFixtureTest::class,
    LogManagerConcurrencyTest::class,
    GitSyncStackEntryPointTest::class,
    ScheduledSyncOriginE2ETest::class,
    TaskSchedulerDesktopEntryPointTest::class,
    DesktopTaskRootWiringTest::class,
    GitSyncServiceRegistryBalanceTest::class,
    JvmPlatformCapsRootTest::class,
    dev.stapler.stelekit.ui.settings.ScheduledSyncRadioKeyboardTest::class,
    dev.stapler.stelekit.ui.state.BlockStateManagerEmissionTraceTest::class,
    dev.stapler.stelekit.ui.components.TableSheetRealFeedTest::class,
    // Gradle-only sketch v2 classes (kmp/src/jvmTest/kotlin/BUILD.bazel excludes each: the commonTest fixtures they drive, or Compose
    // Multiplatform's generated `Res`, are not on this target's classpath). They run under `./gradlew :kmp:jvmTest`; they are named here
    // so TestRegistrationAuditTest can tell an exclusion from an unregistered class: ArchitectureScenarioTest, ToolsEndToEndTest,
    // ParityV1SuiteTest, RenderedSliceScenarioTest, DeterminismSpikeJvmTest, LargeSceneDegradationTest, ComposeTextMetricsProviderTest, SketchExportRunnerPngTest
    // and, for Task 6.2.1c, WiringV2DesktopEntryPointTest (it drives the commonTest V2EditorRig).
)
class AllJvmTests
