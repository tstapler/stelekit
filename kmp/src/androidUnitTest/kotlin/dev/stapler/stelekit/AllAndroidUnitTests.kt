package dev.stapler.stelekit

import dev.stapler.stelekit.db.WalConfiguredCallbackTest
import dev.stapler.stelekit.git.AndroidGitRepositoryTestRemoteTest
import dev.stapler.stelekit.git.GitSyncBusyCounterSharedWiringTest
import dev.stapler.stelekit.platform.LegacyPathValidationTest
import dev.stapler.stelekit.platform.PlatformFileSystemPickerTest
import dev.stapler.stelekit.platform.PlatformFileSystemSafTest
import dev.stapler.stelekit.platform.PlatformFileSystemUriLogicTest
import dev.stapler.stelekit.platform.SafChangeDetectorFileObserverTest
import dev.stapler.stelekit.platform.SafPermissionPersistenceTest
import dev.stapler.stelekit.platform.SafPermissionStateTransitionTest
import dev.stapler.stelekit.platform.ShadowFileCacheTest
import dev.stapler.stelekit.platform.UpgradePathTest
import dev.stapler.stelekit.platform.security.AndroidCredentialStoreCommitTest
import dev.stapler.stelekit.platform.security.AndroidCredentialStoreFailsLoudTest
import dev.stapler.stelekit.platform.sensor.ExifOrientationFixerTest
import dev.stapler.stelekit.ui.LoadingStateTest
import dev.stapler.stelekit.ui.components.MermaidWebViewBridgeTest
import dev.stapler.stelekit.ui.components.MermaidWebViewSettingsTest
import dev.stapler.stelekit.ui.transfer.QrDecodeViewModelBackgroundingTest
import dev.stapler.stelekit.voice.AndroidSpeechRecognizerPermissionTest
import org.junit.runner.RunWith
import org.junit.runners.Suite

@RunWith(Suite::class)
@Suite.SuiteClasses(
    WalConfiguredCallbackTest::class,
    GitSyncBusyCounterSharedWiringTest::class,
    AndroidGitRepositoryTestRemoteTest::class,
    LegacyPathValidationTest::class,
    PlatformFileSystemPickerTest::class,
    PlatformFileSystemSafTest::class,
    PlatformFileSystemUriLogicTest::class,
    SafChangeDetectorFileObserverTest::class,
    SafPermissionPersistenceTest::class,
    SafPermissionStateTransitionTest::class,
    ShadowFileCacheTest::class,
    UpgradePathTest::class,
    AndroidCredentialStoreCommitTest::class,
    AndroidCredentialStoreFailsLoudTest::class,
    ExifOrientationFixerTest::class,
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
)
class AllAndroidUnitTests
