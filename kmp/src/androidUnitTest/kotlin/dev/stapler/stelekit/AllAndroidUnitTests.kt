package dev.stapler.stelekit

import dev.stapler.stelekit.db.AndroidGraphMoveQuiesceStrategyTest
import dev.stapler.stelekit.db.QuiesceRealFlushTest
import dev.stapler.stelekit.db.WalConfiguredCallbackTest
import dev.stapler.stelekit.git.AndroidGitLegacyConfigSourceTest
import dev.stapler.stelekit.git.AndroidGitRepositoryClaimTest
import dev.stapler.stelekit.git.AndroidGitRepositoryLfsGuardTest
import dev.stapler.stelekit.google.AndroidGoogleSupportTest
import dev.stapler.stelekit.git.AndroidBatterySettingsLauncherTest
import dev.stapler.stelekit.git.AndroidGitRepositoryTestRemoteTest
import dev.stapler.stelekit.git.GitSyncBusyCounterSharedWiringTest
import dev.stapler.stelekit.git.GitSyncTaskRegistrationTest
import dev.stapler.stelekit.git.GitSyncWorkerGateAndRecordTest
import dev.stapler.stelekit.git.GitSyncWorkerStaleGateTest
import dev.stapler.stelekit.git.WorkManagerSyncSchedulerPauseTest
import dev.stapler.stelekit.git.WorkManagerSyncSchedulerRetryOwnerTest
import dev.stapler.stelekit.git.WorkManagerSyncSchedulerSlowPathYieldTest
import dev.stapler.stelekit.git.WorkManagerSyncSchedulerUniqueWorkTest
import dev.stapler.stelekit.git.WorkManagerSyncSchedulerWatchdogTest
import dev.stapler.stelekit.jobs.schedule.LegacyWorkUpgradeTest
import dev.stapler.stelekit.jobs.schedule.PlatformSettingsColdPathTest
import dev.stapler.stelekit.jobs.schedule.WorkManagerJitterSpikeTest
import dev.stapler.stelekit.jobs.schedule.TaskRegistryBothRootsTest
import dev.stapler.stelekit.jobs.schedule.TaskWorkerTest
import dev.stapler.stelekit.platform.PlatformEdgeHostsTest
import dev.stapler.stelekit.platform.ml.ModelDownloadEndToEndTest
import dev.stapler.stelekit.platform.ml.ModelDownloadPresentationTest
import dev.stapler.stelekit.platform.ml.ModelDownloadTaskTest
import dev.stapler.stelekit.platform.ml.OneShotSkipTest
import dev.stapler.stelekit.jobs.schedule.ScheduleStartupTest
import dev.stapler.stelekit.jobs.schedule.ScheduledTaskReconcilerTest
import dev.stapler.stelekit.jobs.schedule.WorkManagerTaskSchedulerConstraintsTest
import dev.stapler.stelekit.jobs.schedule.WorkManagerTaskSchedulerConcurrencyTest
import dev.stapler.stelekit.jobs.schedule.WorkManagerTaskSchedulerPauseResumeTest
import dev.stapler.stelekit.jobs.schedule.WorkManagerTaskSchedulerContractTest
import dev.stapler.stelekit.platform.LegacyPathValidationTest
import dev.stapler.stelekit.platform.PlatformFileSystemPickerTest
import dev.stapler.stelekit.platform.PlatformFileSystemSafTest
import dev.stapler.stelekit.platform.PlatformFileSystemUriLogicTest
import dev.stapler.stelekit.platform.SafChangeDetectorFileObserverTest
import dev.stapler.stelekit.platform.SafPermissionPersistenceTest
import dev.stapler.stelekit.platform.SafPermissionStateTransitionTest
import dev.stapler.stelekit.platform.ShadowFileCacheTest
import dev.stapler.stelekit.platform.UpgradePathTest
import dev.stapler.stelekit.service.AndroidMediaAttachmentServiceTest
import dev.stapler.stelekit.platform.measurement.usb.AndroidUsbSerialFactoryTest
import dev.stapler.stelekit.platform.security.AndroidCredentialStoreCommitTest
import dev.stapler.stelekit.platform.security.AndroidCredentialStoreFailsLoudTest
import dev.stapler.stelekit.platform.image.PlatformImageDownscalerAndroidTest
import dev.stapler.stelekit.platform.sensor.CameraCaptureCleanupTest
import dev.stapler.stelekit.platform.sensor.CameraLensSourceTest
import dev.stapler.stelekit.platform.sensor.CameraXBackendTest
import dev.stapler.stelekit.platform.sensor.ExifOrientationFixerLensTest
import dev.stapler.stelekit.wiring.CameraLensCatalogEntryPointTest
import dev.stapler.stelekit.platform.sensor.ExifOrientationFixerTest
import dev.stapler.stelekit.platform.sensor.ImageMemoryGlueTest
import dev.stapler.stelekit.ui.LoadingStateTest
import dev.stapler.stelekit.ui.components.MermaidWebViewBridgeTest
import dev.stapler.stelekit.ui.components.MermaidWebViewSettingsTest
import dev.stapler.stelekit.ui.transfer.QrDecodeViewModelBackgroundingTest
import dev.stapler.stelekit.voice.AndroidSpeechRecognizerPermissionTest
import dev.stapler.stelekit.wiring.PlatformCapsRootTest
import dev.stapler.stelekit.wiring.RepoGateSharedEntryPointTest
import org.junit.runner.RunWith
import org.junit.runners.Suite

@RunWith(Suite::class)
@Suite.SuiteClasses(
    WalConfiguredCallbackTest::class,
    PlatformEdgeHostsTest::class,
    AndroidGraphMoveQuiesceStrategyTest::class,
    QuiesceRealFlushTest::class,
    WorkManagerJitterSpikeTest::class,
    WorkManagerTaskSchedulerContractTest::class,
    WorkManagerTaskSchedulerConstraintsTest::class,
    WorkManagerTaskSchedulerPauseResumeTest::class,
    WorkManagerTaskSchedulerConcurrencyTest::class,
    ScheduledTaskReconcilerTest::class,
    ScheduleStartupTest::class,
    TaskWorkerTest::class,
    ModelDownloadPresentationTest::class,
    ModelDownloadTaskTest::class,
    ModelDownloadEndToEndTest::class,
    OneShotSkipTest::class,
    AndroidBatterySettingsLauncherTest::class,
    TaskRegistryBothRootsTest::class,
    WorkManagerSyncSchedulerPauseTest::class,
    WorkManagerSyncSchedulerRetryOwnerTest::class,
    WorkManagerSyncSchedulerSlowPathYieldTest::class,
    WorkManagerSyncSchedulerUniqueWorkTest::class,
    WorkManagerSyncSchedulerWatchdogTest::class,
    LegacyWorkUpgradeTest::class,
    GitSyncWorkerGateAndRecordTest::class,
    GitSyncWorkerStaleGateTest::class,
    GitSyncTaskRegistrationTest::class,
    AndroidGitLegacyConfigSourceTest::class,
    PlatformSettingsColdPathTest::class,
    PlatformCapsRootTest::class,
    RepoGateSharedEntryPointTest::class,
    AndroidGoogleSupportTest::class,
    GitSyncBusyCounterSharedWiringTest::class,
    AndroidGitRepositoryTestRemoteTest::class,
    AndroidGitRepositoryClaimTest::class,
    AndroidGitRepositoryLfsGuardTest::class,
    LegacyPathValidationTest::class,
    PlatformFileSystemPickerTest::class,
    PlatformFileSystemSafTest::class,
    PlatformFileSystemUriLogicTest::class,
    AndroidMediaAttachmentServiceTest::class,
    AndroidUsbSerialFactoryTest::class,
    SafChangeDetectorFileObserverTest::class,
    SafPermissionPersistenceTest::class,
    SafPermissionStateTransitionTest::class,
    ShadowFileCacheTest::class,
    dev.stapler.stelekit.ui.sketch.v2.UxOrganisationAcceptanceTest::class,
    UpgradePathTest::class,
    AndroidCredentialStoreCommitTest::class,
    AndroidCredentialStoreFailsLoudTest::class,
    ExifOrientationFixerTest::class,
    ExifOrientationFixerLensTest::class,
    CameraCaptureCleanupTest::class,
    CameraXBackendTest::class,
    CameraLensSourceTest::class,
    CameraLensCatalogEntryPointTest::class,
    ImageMemoryGlueTest::class,
    PlatformImageDownscalerAndroidTest::class,
    LoadingStateTest::class,
    AndroidSpeechRecognizerPermissionTest::class,
    QrDecodeViewModelBackgroundingTest::class,
    MermaidWebViewBridgeTest::class,
    MermaidWebViewSettingsTest::class,
    dev.stapler.stelekit.sketch.v2.AndroidCrashGuardTest::class,
    dev.stapler.stelekit.ui.sketch.v2.DeadEndWalkTest::class,
    // MermaidWebViewHostTest and MermaidWebViewSecurityDirectiveTest are deliberately NOT
    // registered here (unlike Gradle's testDebugUnitTest, which does run them): this repo's
    // Bazel android_local_test setup has two pre-existing gaps unrelated to Mermaid — (1) the
    // resolved androidx.compose.ui:ui-test-junit4 jar is a version whose AndroidComposeTestRule
    // lacks the setContent(Function0) overload these tests call (NoSuchMethodError), and (2)
    // Robolectric's asset manager can't see kmp/src/androidMain/assets/mermaid/render.html from
    // this target (FileNotFoundException) — kt_android_library's `associates` pulls in compiled
    // classes but not the android_main target's assets. Both are Bazel Android-test-harness
    // configuration gaps, not defects in the tests themselves; fixing them is out of scope here.
    //
    // NewGraphFlowTest is excluded for the same reason (1) above — it also calls
    // ComposeContentTestRule.setContent, hitting the same resolved-jar/NoSuchMethodError gap.
    // Runs fine under Gradle's testDebugUnitTest.
    //
    // LfsHeldFilesSheetTest is excluded for the same reason (1).
    //
    // GitSyncSchedulingEntryPointTest is excluded for the same reason (1): it drives
    // rememberGraphContentScheduledSyncControls through composeRule.setContent. Runs under Gradle.
    // TaskSchedulerAndroidEntryPointTest is excluded for the same reason (1).
    // LayersPanelTest, StylePanelTest and ShortcutsSheetTest (sketch v2) are excluded for the same reason (1) and compose @Composable lambdas (no Compose plugin in this target).
    // ElementsPanelTest (sketch v2, Story 3.5.7b) is excluded for the same reason (1) and composes @Composable lambdas (no Compose plugin in this target).
    // PointerAdapterTest and TextEditorTest (sketch v2) are excluded for the same reason (1) and compose @Composable lambdas (no Compose plugin in this target).
    // EdgePanStripTest and DiagnosticsTest (sketch v2, Tasks 3.1.2g and 3.6.4b) are excluded for the same reason (1) and compose @Composable lambdas (no Compose plugin in this target).
    // HandleLayoutTest (sketch v2 selection overlay) is excluded for the same reason (1) and composes @Composable lambdas (no Compose plugin in this target).
    // TouchHelpTest and NonDragTransformTest (sketch v2, Stories 3.6.7 and 3.6.3) are excluded for the same reason (1) and compose @Composable lambdas (no Compose plugin in this target).
    // DiscoverabilityTest, CoachWalkthroughTest and EmptyStateTest (sketch v2, Stories 3.6.8 and 3.6.6) are excluded for the same reason (1) and compose @Composable lambdas (no Compose plugin in this target).
    // SolveProgressIndicatorTest (sketch v2, Task 4.1.4b) is excluded for the same reason (1) and composes @Composable lambdas (no Compose plugin in this target).
    // SceneSemanticsTest and TechnicalDataPanelTest (sketch v2, Stories 3.6.2 and 4.2.5) are excluded for the same reason (1) and compose @Composable lambdas (no Compose plugin in this target).
    // HelpSearchDialogTest (sketch v2 Help and search) is excluded for the same reason (1) and composes @Composable lambdas (no Compose plugin in this target).
    // ConstraintPanelTest and UxConstraintsAcceptanceTest (sketch v2, Stories 4.2.2 and 4.2.3) are excluded for the same reason (1) and compose @Composable lambdas (no Compose plugin in this target).
    // ConstraintTargetPickerTest and DimensionEditTest (sketch v2, Tasks 4.2.6b, 4.2.7b and 4.2.7d) are excluded for the same reason (1) and compose @Composable lambdas (no Compose plugin in this target).
    // PanelTabsA11yTest (sketch v2 review finding 6) is excluded for the same reason (1) and composes the shell (no Compose plugin in this target).
    // ToolStripTest, AdaptiveStateSurvivalTest, SketchCanvasRecompositionTest and ScenePainterAndroidTest (sketch v2, Stories 3.1.3 and 2.1.3)
    // are excluded for the same reason (1) and compose @Composable lambdas or need commonTest fixtures (no Compose plugin or commonTest in this target).
    // ChromeBudgetTest, ActionChipRowTest, TopBarTest and CapacityUxTest (sketch v2, Stories 3.1.4 and 2.2.4) are excluded for the same reason (1) and compose @Composable lambdas (no Compose plugin in this target).
    // TextMetricsAndroidEntryPointTest is excluded for the same reason (1) and composes a @Composable (no Compose plugin in this target).
    // SketchStorageAndroidEntryPointTest (Task 1.4.3q) is excluded for the same reason (1), composes @Composable calls and needs the commonTest FakeFileSystem.
    // SketchPlatformInputAndroidEntryPointTest (Task 3.1.2h) is excluded for the same reason (1), composes @Composable calls and needs the commonTest FakeFileSystem.
    // LeaveSketchPromptTest is excluded for the same reason (1) and composes a @Composable (no Compose plugin in this target).
    // RestoreBackupDialogTest (sketch backup restore, Tasks 6.2.6b and 6.2.6c) is excluded for the same reason (1) and composes @Composable lambdas (no Compose plugin in this target).
    // MigrationUiTest (sketch v2 migration and enablement composables, Story 1.4.6) is excluded for the same reason (1) and composes @Composable lambdas (no Compose plugin in this target).
    // EngineSurfacesUiTest (label dialog, sheet-style row and sketch copy flow, integration pass A) is excluded for the same reason (1), composes @Composable lambdas and needs the commonTest fixtures (no Compose plugin or commonTest in this target).
    // SketchCopyDialogTest (sketch copy result dialog, Task 6.1.2c) is excluded for the same reason (1) and composes a @Composable (no Compose plugin in this target).
    // SketchRenderDispatcherTest (sketch embed states, Story 6.1.1) is excluded for the same reason (1), composes @Composable lambdas and needs commonTest fixtures (no Compose plugin or commonTest in this target).
    // ArchitectureScenarioAndroidTest and UxAuthoringAcceptanceTest (sketch v2 scenario and UX acceptance, Task 6.2.4a) are excluded for the same reason (1); they need the commonTest scenario fixtures and the latter composes @Composable lambdas (no Compose plugin or commonTest in this target).
    // ImportFlowTest, ExportDialogTest and SuccessFeedbackTest (sketch import and export dialogs, Stories 5.1.5 and 5.2.3) are excluded for the same reason (1), compose @Composable lambdas and need commonTest fixtures (no Compose plugin or commonTest in this target).
    // V2EntryRouteTest (sketch v2 creation and conversion route, Task 6.2.0c) is excluded for the same reasons as SketchEditorHostTest below.
    // SketchEditorHostExtrasTest (sketch v2 editor host extras, Task 6.2.0d) is excluded for the same reason (1), composes @Composable lambdas and needs the commonTest rig (no Compose plugin or commonTest in this target).
    // SketchEditorHostTest and WiringV2AndroidEntryPointTest (sketch v2 editor host, Story 6.2.0b) are excluded for the same reason (1), compose @Composable lambdas and need the commonTest rig (no Compose plugin or commonTest in this target).
    // GoToElementTest, UxA11yAcceptanceTest, WalkthroughKeyboardTest and LoadingStatesTest (sketch v2, Stories 3.6.5, 3.6.6 and 3.6.8) are excluded for the same reason (1), compose @Composable lambdas (no Compose plugin in this target) and the first two sample native-graphics renders.
    //
    // BackgroundTasksSectionTest, TaskRunLogRowsTest and ModelDownloadUiTest (background-task scheduling UI)
    // are excluded for the same reason (1): they drive composables through ComposeContentTestRule.
    // They run under Gradle's testDebugUnitTest.
    // BackgroundTasksUxGapsTest, ScheduledSyncStatusActionsTest, TaskSettingsAccessibilityAndErrorsTest,
    // DepthEstimationPanelLiveRegionTest and ScheduledSyncControlsApplyTest are excluded for the same reason (1).
    // AndroidPixelSpikeTest (Spike 0.1.3e) needs the commonTest DeterminismSpikeSupport and InputSpikeAndroidTest composes @Composable lambdas (no Compose plugin or commonTest in this target); both run under Gradle.
    // EscOrderIntegrationTest and BracketPlateUxScenarioTest (sketch v2, Tasks 6.2.4h and 6.2.4f) mount the real host (composeRule, no Compose plugin in this target) over the commonTest rig; Gradle-only.
    // TechnicalTabHostTest (the Technical tab of the real host) mounts the host over the commonTest rig and StripIconsTest renders Compose canvases (composeRule, no Compose plugin in this target); both Gradle-only.
    // PhoneMenuEntryTest (the phone-size overflow menu entries for panels and the sheet type) mounts the real host over the commonTest rig (composeRule, no Compose plugin in this target); Gradle-only.
    // TouchLoupeHostTest (the touch magnifier beside a dragging finger, phone renders) mounts the real host over the commonTest rig (composeRule, no Compose plugin in this target); Gradle-only.
    // MobileChromeRenderTest (phone, wide-phone and tablet renders of the chrome) ControlHelpPopupTest (the long-press help of each icon-only control) SnapToastHostTest (one live Snap toast) CanvasPressHostTest (presses reach the canvas through the chrome) and ChromeTargetSizeTest (every chrome control meets the touch floor) mount the real host over the commonTest rig (composeRule, no Compose plugin in this target); Gradle-only.
    // ResizeSnapHostTest (a handle resize snapping to a neighbour on a phone, with Roborazzi shots) mounts the real host over the commonTest rig (composeRule, no Compose plugin in this target); Gradle-only.
    // KeyboardAccessHostTest (sketch v2, Task 3.6.3c and the host's accessibility mounts) mounts the real host over the commonTest rig (composeRule, no Compose plugin in this target); Gradle-only.
    // DimensionPillsHostTest (the on-canvas dimension pills of a technical sheet, phone renders) mounts the real host over the commonTest rig (composeRule, no Compose plugin in this target) and DimensionTourTest drives TourTriggers and Help search the same way; Gradle-only.
    // ElementStatsHostTest (the element size section of the real host's Style tab) mounts the host over the commonTest rig (composeRule, no Compose plugin in this target); Gradle-only.
    // Task 6.2.1c: WiringV2AndroidEntryPointTest (the root, the class seams of the plan-seam-classes block and the saver's durability) stays Gradle-only for the reasons named above; it is named here so TestRegistrationAuditTest can tell an exclusion from an unregistered class.
    // SyncStatusSheetStatesTest, SyncStatusBadgeTest and SyncStatusTextScaleTest (Task 1.7b) are excluded for the same reason (1).
    // MeasurementValueEditorTest, MeasurementValueEditorErrorStatesTest, ValueEditorTextScaleTest, ValueEditorSheetTest,
    // CalibrationPillTest, KnownDistancesSheetTest, ValueEditorScopeTest and AnnotationEditorValueHooksTest (E3 value editor,
    // calibration pill, Known distances sheet) are Compose host tests, excluded from the Bazel target like the other annotate
    // ones; they run under Gradle's testDebugUnitTest.
)
class AllAndroidUnitTests
