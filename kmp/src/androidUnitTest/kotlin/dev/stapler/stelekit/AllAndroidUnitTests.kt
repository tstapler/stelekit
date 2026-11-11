package dev.stapler.stelekit

import dev.stapler.stelekit.db.WalConfiguredCallbackTest
import dev.stapler.stelekit.git.AndroidGitLegacyConfigSourceTest
import dev.stapler.stelekit.git.AndroidGitRepositoryLfsGuardTest
import dev.stapler.stelekit.google.AndroidGoogleSupportTest
import dev.stapler.stelekit.git.AndroidGitRepositoryTestRemoteTest
import dev.stapler.stelekit.git.GitSyncBusyCounterSharedWiringTest
import dev.stapler.stelekit.git.GitSyncWorkerGateAndRecordTest
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
import dev.stapler.stelekit.jobs.schedule.ScheduleStartupTest
import dev.stapler.stelekit.jobs.schedule.ScheduledTaskReconcilerTest
import dev.stapler.stelekit.jobs.schedule.WorkManagerTaskSchedulerConstraintsTest
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
import dev.stapler.stelekit.platform.sensor.ExifOrientationFixerTest
import dev.stapler.stelekit.platform.sensor.ImageMemoryGlueTest
import dev.stapler.stelekit.ui.LoadingStateTest
import dev.stapler.stelekit.ui.components.MermaidWebViewBridgeTest
import dev.stapler.stelekit.ui.components.MermaidWebViewSettingsTest
import dev.stapler.stelekit.ui.transfer.QrDecodeViewModelBackgroundingTest
import dev.stapler.stelekit.voice.AndroidSpeechRecognizerPermissionTest
import dev.stapler.stelekit.wiring.PlatformCapsRootTest
import org.junit.runner.RunWith
import org.junit.runners.Suite

@RunWith(Suite::class)
@Suite.SuiteClasses(
    WalConfiguredCallbackTest::class,
    WorkManagerJitterSpikeTest::class,
    WorkManagerTaskSchedulerContractTest::class,
    WorkManagerTaskSchedulerConstraintsTest::class,
    WorkManagerTaskSchedulerPauseResumeTest::class,
    ScheduledTaskReconcilerTest::class,
    ScheduleStartupTest::class,
    TaskWorkerTest::class,
    TaskRegistryBothRootsTest::class,
    WorkManagerSyncSchedulerPauseTest::class,
    WorkManagerSyncSchedulerRetryOwnerTest::class,
    WorkManagerSyncSchedulerSlowPathYieldTest::class,
    WorkManagerSyncSchedulerUniqueWorkTest::class,
    WorkManagerSyncSchedulerWatchdogTest::class,
    LegacyWorkUpgradeTest::class,
    GitSyncWorkerGateAndRecordTest::class,
    AndroidGitLegacyConfigSourceTest::class,
    PlatformSettingsColdPathTest::class,
    PlatformCapsRootTest::class,
    AndroidGoogleSupportTest::class,
    GitSyncBusyCounterSharedWiringTest::class,
    AndroidGitRepositoryTestRemoteTest::class,
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
    UpgradePathTest::class,
    AndroidCredentialStoreCommitTest::class,
    AndroidCredentialStoreFailsLoudTest::class,
    ExifOrientationFixerTest::class,
    ImageMemoryGlueTest::class,
    PlatformImageDownscalerAndroidTest::class,
    LoadingStateTest::class,
    AndroidSpeechRecognizerPermissionTest::class,
    QrDecodeViewModelBackgroundingTest::class,
    MermaidWebViewBridgeTest::class,
    MermaidWebViewSettingsTest::class,
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
)
class AllAndroidUnitTests
