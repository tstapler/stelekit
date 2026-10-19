package dev.stapler.stelekit

/**
 * jvmTest classes that predate [AllJvmTestsCompletenessTest] and are not in [AllJvmTests], so
 * `bazel test //kmp:jvm_tests` never runs them. Gradle runs them. Register a class in
 * [AllJvmTests] and delete its entry here; the completeness test fails if an entry is stale.
 * Do not add entries: register new test classes instead.
 */
internal val UNREGISTERED_JVM_TESTS: Set<String> = setOf(
    "dev.stapler.stelekit.capture.CapturePopupWindowTest", // fails under Bazel
    "dev.stapler.stelekit.capture.CapturePopupWindowUxTest", // fails under Bazel
    "dev.stapler.stelekit.capture.CaptureSocketClientTest", // fails under Bazel
    "dev.stapler.stelekit.capture.CaptureSocketListenerTest", // fails under Bazel
    "dev.stapler.stelekit.capture.CaptureWriterSourceAuditTest", // fails under Bazel
    "dev.stapler.stelekit.desktop.MainCaptureFlowTest", // fails under Bazel
    "dev.stapler.stelekit.desktop.MainCliCaptureIntegrationTest", // fails under Bazel
    "dev.stapler.stelekit.docs.WebsiteDocsCoverageTest", // fails under Bazel
    "dev.stapler.stelekit.mcp.ListBacklinksToolTest", // fails under Bazel
    "dev.stapler.stelekit.mcp.ListGraphsToolTest", // fails under Bazel
    "dev.stapler.stelekit.mcp.McpAdapterTest", // fails under Bazel
    "dev.stapler.stelekit.mcp.McpClientIdentityTest", // fails under Bazel
    "dev.stapler.stelekit.mcp.McpEndToEndReadTest", // fails under Bazel
    "dev.stapler.stelekit.mcp.McpLauncherMainTest", // fails under Bazel
    "dev.stapler.stelekit.mcp.McpRealSubprocessSmokeTest", // fails under Bazel
    "dev.stapler.stelekit.mcp.McpSocketServerTest", // fails under Bazel
    "dev.stapler.stelekit.mcp.McpToolTimeoutTest", // fails under Bazel
    "dev.stapler.stelekit.mcp.PageResourceTest", // fails under Bazel
    "dev.stapler.stelekit.mcp.ProposeBlockEditToolTest", // fails under Bazel
    "dev.stapler.stelekit.mcp.ProposeNewPageToolTest", // fails under Bazel
    "dev.stapler.stelekit.mcp.ReadPageToolTest", // fails under Bazel
    "dev.stapler.stelekit.mcp.SearchGraphToolTest", // fails under Bazel
    "dev.stapler.stelekit.transfer.AirGapGuardTest", // fails under Bazel
    "dev.stapler.stelekit.git.PatLeakageAuditTest", // reads wasmJsMain sources; not staged in the Bazel sandbox
    "dev.stapler.stelekit.benchmark.BlockInsertBenchmarkTest",
    "dev.stapler.stelekit.benchmark.JournalViewFanoutBenchmarkTest",
    "dev.stapler.stelekit.benchmark.LatencyShimFileSystemTest",
    "dev.stapler.stelekit.benchmark.LibsqlConcurrentWriteBenchmarkTest",
    "dev.stapler.stelekit.benchmark.LibsqlSequentialWriteBenchmarkTest",
    "dev.stapler.stelekit.benchmark.UnifiedBenchmarkRunner",
    "dev.stapler.stelekit.benchmark.UserSessionBenchmarkTest",
    "dev.stapler.stelekit.ui.annotate.AnnotationEditorScreenKeyboardTest",
    "dev.stapler.stelekit.ui.annotate.AnnotationEditorScreenTagWiringTest",
    "dev.stapler.stelekit.ui.annotate.AnnotationToolbarShortcutTest",
    "dev.stapler.stelekit.ui.annotate.DisabledTintContrastTest",
    "dev.stapler.stelekit.ui.AppStorageSubtitleTest",
    "dev.stapler.stelekit.ui.assets.AssetBrowserScreenTest",
    "dev.stapler.stelekit.ui.CaptureAndImportTest",
    "dev.stapler.stelekit.ui.CommandPaletteFocusRetentionTest",
    "dev.stapler.stelekit.ui.components.BlockItemGestureTest",
    "dev.stapler.stelekit.ui.components.BlockListDropZoneTest",
    "dev.stapler.stelekit.ui.components.BlockListLassoSelectTest",
    "dev.stapler.stelekit.ui.components.ImageBlockDetectionTest",
    "dev.stapler.stelekit.ui.components.ParseTableContentTest",
    "dev.stapler.stelekit.ui.components.QueryBlockTest",
    "dev.stapler.stelekit.ui.components.SnippetTextTest",
    "dev.stapler.stelekit.ui.components.StorageMoveChoiceDialogTest",
    "dev.stapler.stelekit.ui.components.StorageMoveConfirmDialogTest",
    "dev.stapler.stelekit.ui.components.StorageMoveProgressDialogTest",
    "dev.stapler.stelekit.ui.components.TypedLazyItemsTest",
    "dev.stapler.stelekit.ui.components.UnifiedLocationPickerTest",
    "dev.stapler.stelekit.ui.components.WikilinkWrapTest",
    "dev.stapler.stelekit.ui.EditorToolbarRecompositionTest",
    "dev.stapler.stelekit.ui.ErrorStateNoDeadEndTest",
    "dev.stapler.stelekit.ui.ExternalFileChangeErrorHandlingTest",
    "dev.stapler.stelekit.ui.FirstRunHotkeyNoticeTest",
    "dev.stapler.stelekit.ui.GraphSwitcherMoveStorageBrowseClickTest",
    "dev.stapler.stelekit.ui.HotkeyConflictNoticeTest",
    "dev.stapler.stelekit.ui.JournalWarmStartRaceHealingTest",
    "dev.stapler.stelekit.ui.LlmSuggestionCaptionStatesUiTest",
    "dev.stapler.stelekit.ui.screens.EmptyGraphStateScreenTest",
    "dev.stapler.stelekit.ui.screens.git.GitSetupScreenCredentialPersistenceTest",
    "dev.stapler.stelekit.ui.screens.llm.LlmSuggestionReviewScreenMcpChipTest",
    "dev.stapler.stelekit.ui.SectionPickerTest",
    "dev.stapler.stelekit.ui.ShortcutTableConsistencyTest",
    "dev.stapler.stelekit.ui.StelekitViewModelCrashReproductionTest",
    "dev.stapler.stelekit.ui.StelekitViewModelShareExportTest",
    "dev.stapler.stelekit.ui.StelekitViewModelSyncStateIntegrationTest",
    "dev.stapler.stelekit.ui.StelekitViewModelSyncStateTest",
    "dev.stapler.stelekit.ui.SuggestionBottomSheetChipsUiTest",
    "dev.stapler.stelekit.ui.TagInsertionFlagshipUiTest",
    "dev.stapler.stelekit.ui.transfer.CameraPermissionRationaleDialogUxTest",
    "dev.stapler.stelekit.ui.transfer.QrCancelUxTest",
    "dev.stapler.stelekit.ui.transfer.QrDecodePreflightUxTest",
    "dev.stapler.stelekit.ui.transfer.QrDecodeScreenTest",
    "dev.stapler.stelekit.ui.transfer.QrDecodeScreenUxTest",
    "dev.stapler.stelekit.ui.transfer.QrEncodeScreenFlashSafetyUxTest",
    "dev.stapler.stelekit.ui.transfer.QrEncodeScreenTest",
    "dev.stapler.stelekit.ui.transfer.QrEncodeScreenUxTest",
    "dev.stapler.stelekit.ui.transfer.QrImportConfirmDialogTest",
    "dev.stapler.stelekit.ui.transfer.QrImportConfirmDialogUxTest",
    "dev.stapler.stelekit.ui.transfer.QrImportEntryPointUxTest",
    "dev.stapler.stelekit.ui.transfer.QrNoDeadEndUxTest",
    "dev.stapler.stelekit.ui.transfer.QrSendEntryPointUxTest",
    "dev.stapler.stelekit.ui.transfer.QrTransferAccessibilityUxTest",
    "dev.stapler.stelekit.ui.transfer.QrTransferEntryPointsTest",
    "dev.stapler.stelekit.ui.VoiceCaptureStateTransitionTest",
)
